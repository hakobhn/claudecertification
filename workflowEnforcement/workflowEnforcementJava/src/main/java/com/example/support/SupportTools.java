package com.example.support;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Step 1: the four tool definitions (JSON Schema). Execution lives in {@link SupportSession}. */
public final class SupportTools {

    private SupportTools() {
    }

    private static final Map<String, Object> STRING = Map.of("type", "string");

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** Build a strict tool: every property required, no extra properties. */
    private static Tool tool(String name, String description, Map<String, Object> properties) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required(List.copyOf(properties.keySet()))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .strict(true)
                .build();
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static final Tool GET_CUSTOMER = tool("get_customer",
            "Look up a customer by email address or full name. Returns the customer ID and whether the "
                    + "customer's identity is verified. Required before any refund.",
            ordered("name_or_email", str("Email address or full name")));

    public static final Tool LOOKUP_ORDER = tool("lookup_order",
            "Get order details: item, amount, status, days since delivery, and every charge made.",
            ordered("order_id", str("Order ID, e.g. O-5001")));

    public static final Tool PROCESS_REFUND = tool("process_refund",
            "Refund money to a customer. Only for returns of delivered items within 30 days, up to the order "
                    + "amount. Billing disputes (e.g. duplicate charges) go to a human.",
            ordered("customer_id", STRING,
                    "order_id", STRING,
                    "amount", Map.of("type", "number", "description", "Refund amount in GBP")));

    public static final Tool ESCALATE_TO_HUMAN = tool("escalate_to_human",
            "Hand the case to a human support agent. The human CANNOT see this conversation: the handoff must "
                    + "be completely self-contained. Use one handoff for all open concerns.",
            ordered("customer_id", STRING,
                    "conversation_summary", str("What the customer asked for and what you already did"),
                    "root_cause_analysis", str("Your assessment of the underlying issue"),
                    "refund_amount", Map.of("type", "number",
                            "description", "Exact amount in GBP the human should refund; 0 if none"),
                    "recommended_action", str("What the human agent should do next"),
                    "concerns", Map.of(
                            "type", "array",
                            "description", "Every concern in the request, including ones you already resolved",
                            "items", ordered(
                                    "type", "object",
                                    "properties", ordered(
                                            "concern", STRING,
                                            "status", Map.of("type", "string", "enum", List.of("resolved", "needs_human")),
                                            "details", STRING),
                                    "required", List.of("concern", "status", "details"),
                                    "additionalProperties", false))));

    public static final List<Tool> ALL = List.of(GET_CUSTOMER, LOOKUP_ORDER, PROCESS_REFUND, ESCALATE_TO_HUMAN);
}
