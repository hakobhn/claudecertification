package com.example.hooks;

import com.example.hooks.Tools.Session;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hooks: deterministic code that runs around every tool call.
 * <pre>
 *     model asks for a tool
 *           |
 *     [ PreToolUse ]  -- deny -->  the tool NEVER runs; the model gets the reason
 *           | allow
 *       tool runs
 *           |
 *     [ PostToolUse ] -- may replace the result (updatedToolOutput)
 *           |
 *     model sees the (clean) result
 * </pre>
 * Names follow the Claude Agent SDK: PreToolUse / PostToolUse, permissionDecision "deny" +
 * permissionDecisionReason, and updatedToolOutput. The SDK runs hooks for you; this tiny
 * runner ({@link #runWithHooks}) just makes the mechanism visible.
 */
public final class Hooks {

    private Hooks() {
    }

    static final double REFUND_LIMIT = 500.00;

    /** Runs before a tool. Return null to allow, or a map with permissionDecision "deny". */
    interface PreToolUse {
        Map<String, Object> check(Map<String, Object> args, Session session);
    }

    /** Runs after a tool. Return null to keep the result, or a map with "updatedToolOutput". */
    interface PostToolUse {
        Map<String, Object> after(Map<String, Object> result, Session session);
    }

    record Hook<T>(String matcher, T hook) {
        boolean matches(String tool) {
            return matcher.equals("*") || matcher.equals(tool);
        }
    }

    // ---------------------------------------------------------------- PreToolUse: policy enforcement

    /** Block refunds above $500 and redirect them to a human. */
    static Map<String, Object> refundLimit(Map<String, Object> args, Session session) {
        double amount = ((Number) args.get("amount")).doubleValue();
        if (amount > REFUND_LIMIT) {
            session.escalations.add(Map.of("order_id", args.get("order_id"), "amount", amount));
            return deny(String.format("Refunds above $%.0f need staff approval. The $%.2f refund was NOT processed; "
                    + "it has been sent to the human escalation queue. Tell the customer.", REFUND_LIMIT, amount));
        }
        return null; // allow
    }

    /** Block transfers until aml_check has passed in this session. */
    static Map<String, Object> amlGate(Map<String, Object> args, Session session) {
        if (!session.amlPassed) {
            return deny("Transfer blocked: no passing AML check in this session. "
                    + "Call aml_check for the sender first, then retry.");
        }
        return null;
    }

    private static Map<String, Object> deny(String reason) {
        return Map.of("permissionDecision", "deny", "permissionDecisionReason", reason);
    }

    // ---------------------------------------------------------------- PostToolUse: data normalisation

    static final Map<Object, String> STATUS_CODES = Map.of(
            200, "active", 404, "not found", 500, "error",
            "S", "shipped", "P", "pending", "D", "delivered", "L", "lost in transit");
    static final Pattern DD_MM_YYYY = Pattern.compile("^(\\d{2})/(\\d{2})/(\\d{4})$");

    static Object normalise(String key, Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put((String) k, normalise((String) k, v)));
            return out;
        }
        if (key.endsWith("_at") && value instanceof Number n) {                // Unix timestamp -> ISO 8601
            return Instant.ofEpochSecond(n.longValue()).toString();
        }
        if (value instanceof String s) {
            Matcher m = DD_MM_YYYY.matcher(s);                                  // DD/MM/YYYY -> ISO 8601
            if (m.matches()) return m.group(3) + "-" + m.group(2) + "-" + m.group(1) + "T00:00:00Z";
        }
        if (key.equals("status")) {                                              // codes -> English
            return STATUS_CODES.getOrDefault(value, String.valueOf(value).toLowerCase());
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> normaliseResult(Map<String, Object> result, Session session) {
        return Map.of("updatedToolOutput", normalise("", result));
    }

    /** Set the state that amlGate checks. Observes only: returns no updatedToolOutput. */
    static Map<String, Object> recordAmlPass(Map<String, Object> result, Session session) {
        if ("pass".equals(result.get("result"))) session.amlPassed = true;
        return null;
    }

    // ---------------------------------------------------------------- registration (matcher -> hook)

    static final List<Hook<PreToolUse>> PRE_TOOL_USE = List.of(
            new Hook<>("process_refund", Hooks::refundLimit),
            new Hook<>("transfer_funds", Hooks::amlGate));

    static final List<Hook<PostToolUse>> POST_TOOL_USE = List.of(
            new Hook<>("aml_check", Hooks::recordAmlPass),
            new Hook<>("*", Hooks::normaliseResult));      // "*" = every tool

    /** What the model gets back: the content, and whether it is an error. */
    public record Outcome(Object content, boolean isError) {
    }

    @SuppressWarnings("unchecked")
    public static Outcome runWithHooks(String name, Map<String, Object> args, Session session) {
        for (Hook<PreToolUse> h : PRE_TOOL_USE) {
            if (!h.matches(name)) continue;
            Map<String, Object> decision = h.hook().check(args, session);
            if (decision != null && "deny".equals(decision.get("permissionDecision"))) {
                return new Outcome(decision.get("permissionDecisionReason"), true); // tool never runs
            }
        }

        Map<String, Object> result = Tools.run(name, args, session);

        for (Hook<PostToolUse> h : POST_TOOL_USE) {
            if (!h.matches(name)) continue;
            Map<String, Object> output = h.hook().after(result, session);
            if (output != null && output.containsKey("updatedToolOutput")) {
                result = (Map<String, Object>) output.get("updatedToolOutput");
            }
        }
        return new Outcome(result, false);
    }
}
