package com.example.hooks;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The logistics company's tools. Deliberately messy: three tools, three data formats.
 * <pre>
 *   get_customer   (Tool A): Unix timestamps,  numeric status codes     (200)
 *   lookup_order   (Tool B): ISO 8601 strings, English status strings   ("pending")
 *   check_shipping (Tool C): DD/MM/YYYY,       single-character codes   ("S")
 * </pre>
 * The tools know nothing about hooks. Every call records that it ran in
 * {@code session.executed}, so tests can prove a blocked tool never executed.
 */
public final class Tools {

    private Tools() {
    }

    public static final class Session {
        public final List<String> executed = new ArrayList<>();               // which tools actually ran
        public boolean amlPassed;                                             // set by a PostToolUse hook
        public final List<Map<String, Object>> escalations = new ArrayList<>(); // refunds sent to humans
    }

    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> NUMBER = Map.of("type", "number");

    /** A strict tool whose parameters are all required. Pass name/schema pairs. */
    private static Tool tool(String name, String description, Object... params) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        List<String> required = new ArrayList<>();
        for (int i = 0; i < params.length; i += 2) {
            props.putAdditionalProperty((String) params[i], JsonValue.from(params[i + 1]));
            required.add((String) params[i]);
        }
        return Tool.builder().name(name).description(description).strict(true)
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build())
                .build();
    }

    public static final List<Tool> ALL = List.of(
            tool("get_customer", "Get a customer account.", "customer_id", STRING),
            tool("lookup_order", "Get an order: amount, status and when it was placed.", "order_id", STRING),
            tool("check_shipping", "Get the shipping status and dates for an order.", "order_id", STRING),
            tool("process_refund", "Refund an order to the customer.", "order_id", STRING, "amount", NUMBER),
            tool("aml_check", "Run the anti-money-laundering check on a sender. Required before any transfer.",
                    "sender_id", STRING),
            tool("transfer_funds", "Send cash-on-delivery money collected for an order to its sender.",
                    "order_id", STRING, "sender_id", STRING, "amount", NUMBER));

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** O-7001: parcel lost in transit.  O-7002: delivered, cash on delivery collected. */
    record OrderState(String status, String shipping, boolean codCollected) {
    }

    static final Map<String, OrderState> ORDERS = Map.of(
            "O-7001", new OrderState("active", "L", false),
            "O-7002", new OrderState("active", "D", true));

    /** The raw tools, with their raw, inconsistent output. */
    public static Map<String, Object> run(String name, Map<String, Object> args, Session session) {
        session.executed.add(name);
        OrderState order = ORDERS.getOrDefault(String.valueOf(args.get("order_id")), new OrderState("pending", "P", false));
        return switch (name) {
            case "get_customer" -> map("customer_id", args.get("customer_id"), "name", "Ana Silva",
                    "created_at", 1710489600L, "status", 200);                        // Tool A
            case "lookup_order" -> map("order_id", args.get("order_id"), "amount", 900.00, "currency", "USD",
                    "placed_at", "2024-03-15T12:00:00Z", "status", order.status(),
                    "cash_on_delivery_collected", order.codCollected());              // Tool B
            case "check_shipping" -> map("order_id", args.get("order_id"), "shipped_on", "03/04/2024",
                    "eta", "07/04/2024", "status", order.shipping());                 // Tool C
            case "process_refund" -> map("refund_id", "R-1", "order_id", args.get("order_id"),
                    "amount", args.get("amount"), "status", "processed");
            case "aml_check" -> map("sender_id", args.get("sender_id"), "result", "pass");
            case "transfer_funds" -> map("transfer_id", "T-1", "amount", args.get("amount"),
                    "sender_id", args.get("sender_id"), "status", "sent");
            default -> map("error", "unknown tool " + name);
        };
    }
}
