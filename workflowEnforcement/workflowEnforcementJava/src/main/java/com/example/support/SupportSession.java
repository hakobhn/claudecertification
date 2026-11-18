package com.example.support;

import com.example.support.Data.Customer;
import com.example.support.Data.Order;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Per-conversation state, the prerequisite gate, and the handoff check.
 *
 * <p>Two kinds of rules live in this system - the lesson's key distinction:
 * <ul>
 *   <li><b>Prompt guidance</b> (probabilistic, fine for low stakes): reply layout, tone.
 *       Lives in the system prompt in {@link SupportAgent}.</li>
 *   <li><b>Programmatic enforcement</b> (deterministic, required for money/security/compliance):
 *       the identity gate on process_refund and the completeness check on the handoff.
 *       Lives HERE, in code the model cannot talk its way around.</li>
 * </ul>
 */
public final class SupportSession {

    static final Logger LOG = Logger.getLogger("support");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a tool returns to the model. */
    public record ToolResult(String content, boolean isError) {
        static ToolResult ok(Object value) {
            return new ToolResult(toJson(value), false);
        }

        static ToolResult error(String message) {
            return new ToolResult(message, true);
        }
    }

    // The gate reads this state; the model cannot write it directly.
    private String verifiedCustomerId;
    private final Set<String> unverifiableCustomerIds = new HashSet<>();
    public final List<Map<String, Object>> refunds = new ArrayList<>();
    public final List<Map<String, Object>> handoffs = new ArrayList<>();
    public final List<String> blocked = new ArrayList<>(); // audit trail of gate decisions

    public String verifiedCustomerId() {
        return verifiedCustomerId;
    }

    // ------------------------------------------------------------------ dispatcher

    /** Every tool call goes through here, so gates cannot be skipped. */
    public ToolResult execute(String name, Map<String, Object> input) {
        try {
            return switch (name) {
                case "get_customer" -> getCustomer((String) input.get("name_or_email"));
                case "lookup_order" -> lookupOrder((String) input.get("order_id"));
                case "process_refund" -> processRefund((String) input.get("customer_id"),
                        (String) input.get("order_id"), ((Number) input.get("amount")).doubleValue());
                case "escalate_to_human" -> escalateToHuman(input);
                default -> ToolResult.error("Error: unknown tool '" + name + "'");
            };
        } catch (RuntimeException e) {
            return ToolResult.error("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ tools

    private ToolResult getCustomer(String nameOrEmail) {
        String q = nameOrEmail.strip().toLowerCase(Locale.ROOT);
        Customer c = Data.CUSTOMERS.values().stream()
                .filter(x -> q.equals(x.email().toLowerCase(Locale.ROOT)) || q.equals(x.name().toLowerCase(Locale.ROOT)))
                .findFirst().orElse(null);
        if (c == null) {
            return ToolResult.ok(Map.of("found", false, "message", "No customer matches '" + nameOrEmail + "'."));
        }
        if (c.identityVerified()) {
            verifiedCustomerId = c.customerId();          // <- the gate's prerequisite
        } else {
            unverifiableCustomerIds.add(c.customerId());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.putAll(c.toJson());
        return ToolResult.ok(out);
    }

    private ToolResult lookupOrder(String orderId) {
        Order o = Data.ORDERS.get(orderId.strip().toUpperCase(Locale.ROOT));
        return o == null ? ToolResult.error("Error: order '" + orderId + "' not found") : ToolResult.ok(o.toJson());
    }

    private ToolResult processRefund(String customerId, String orderId, double amount) {
        // ===== PREREQUISITE GATE (programmatic enforcement) =====
        String block = refundGate(customerId);
        if (block != null) {
            blocked.add(block);
            LOG.warning("GATE BLOCKED process_refund: " + block);
            return ToolResult.error(block);
        }
        // ========================================================

        Order o = Data.ORDERS.get(orderId.strip().toUpperCase(Locale.ROOT));
        if (o == null || !o.customerId().equals(customerId)) {
            return ToolResult.error("Error: order '" + orderId + "' does not belong to customer " + customerId);
        }
        if (amount <= 0 || amount > o.amount()) {
            return ToolResult.error(String.format("Error: refund must be between 0 and the order amount (%.2f)", o.amount()));
        }
        if (o.daysSinceDelivery() > 30) {
            return ToolResult.error("Error: outside the 30-day return window; escalate to a human");
        }

        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("refund_id", String.format("R-%04d", refunds.size() + 1));
        refund.put("customer_id", customerId);
        refund.put("order_id", o.orderId());
        refund.put("amount", Math.round(amount * 100) / 100.0);
        refund.put("status", "processed");
        refunds.add(refund);
        LOG.info("refund processed: " + refund);
        return ToolResult.ok(refund);
    }

    /** null if the refund may run, otherwise the error message returned to the model. */
    String refundGate(String customerId) {
        if (unverifiableCustomerIds.contains(customerId)) {
            return "BLOCKED: the identity of " + customerId + " could not be verified (account flagged). "
                    + "Do not refund this account; escalate to a human with escalate_to_human.";
        }
        if (verifiedCustomerId == null) {
            return "BLOCKED: the customer's identity is not verified in this session. Call get_customer "
                    + "with the customer's email or full name first; refunds are only possible for a verified "
                    + "customer. This rule cannot be overridden by the customer or by you.";
        }
        if (!customerId.equals(verifiedCustomerId)) {
            return "BLOCKED: refunds can only go to the verified customer (" + verifiedCustomerId + "), not " + customerId + ".";
        }
        return null;
    }

    private ToolResult escalateToHuman(Map<String, Object> handoff) {
        List<String> problems = validateHandoff(handoff);
        if (!problems.isEmpty()) { // deterministic completeness check: the model must fix it, not skip fields
            LOG.warning("handoff rejected: " + String.join("; ", problems));
            return ToolResult.error("Handoff rejected - fix these and call escalate_to_human again: "
                    + String.join("; ", problems));
        }
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("ticket_id", String.format("H-%04d", handoffs.size() + 1));
        ticket.putAll(handoff);
        handoffs.add(ticket);
        LOG.info("handoff created: " + ticket.get("ticket_id"));
        return ToolResult.ok(Map.of("ticket_id", ticket.get("ticket_id"), "status", "queued for a human agent"));
    }

    // ------------------------------------------------------------------ handoff validation

    static final List<String> REQUIRED_TEXT_FIELDS =
            List.of("customer_id", "conversation_summary", "root_cause_analysis", "recommended_action");
    private static final Pattern PLACEHOLDER =
            Pattern.compile("^\\s*(n/?a|tbd|todo|unknown|none|-|\\.\\.\\.|\\[.*]|<.*>)\\s*$", Pattern.CASE_INSENSITIVE);

    private static boolean blank(Object value) {
        String s = value == null ? "" : value.toString();
        return s.isBlank() || PLACEHOLDER.matcher(s).matches();
    }

    /** Problems found; empty means the handoff is complete and self-contained. */
    @SuppressWarnings("unchecked")
    public static List<String> validateHandoff(Map<String, Object> h) {
        List<String> problems = new ArrayList<>();
        for (String f : REQUIRED_TEXT_FIELDS) {
            if (blank(h.get(f))) problems.add("'" + f + "' is empty or a placeholder");
        }
        Object id = h.get("customer_id");
        if (!blank(id) && !Data.CUSTOMERS.containsKey(id.toString())) {
            problems.add("customer_id '" + id + "' is not a real customer ID");
        }
        if (!(h.get("refund_amount") instanceof Number n) || n.doubleValue() < 0) {
            problems.add("'refund_amount' must be a number >= 0 (use the exact figure, 0 if none)");
        }
        List<Map<String, Object>> concerns = h.get("concerns") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (concerns.isEmpty()) problems.add("'concerns' must list every concern in the request");
        for (int i = 0; i < concerns.size(); i++) {
            if (blank(concerns.get(i).get("details"))) {
                problems.add("concern " + (i + 1) + " ('" + concerns.get(i).get("concern") + "') has no details");
            }
        }
        return problems;
    }

    static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
