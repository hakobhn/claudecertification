package com.example.hooks;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.example.hooks.Hooks.Outcome;
import com.example.hooks.Tools.Session;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Build Exercise 1.5 - Hooks for data normalisation and policy enforcement.
 * The loop is lesson 1.1's. The only change: tools run through {@link Hooks#runWithHooks}.
 * <pre>
 *   demo | normalise | refund | transfer   (no argument = all)
 * </pre>
 */
public final class Agent {

    static final String MODEL = "claude-opus-5-5";
    static final String SYSTEM = "You are the support agent of a logistics company. Use the tools to answer. "
            + "Reply in plain text, briefly."; // style = prompt guidance (low stakes, no hook needed)
    static final ObjectMapper JSON = new ObjectMapper();

    static String run(AnthropicClient client, String question, Session session) {
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.ofUser(question));
        for (int i = 0; i < 20; i++) { // safety cap only
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(MODEL).maxTokens(16_000L).system(SYSTEM).messages(messages);
            Tools.ALL.forEach(params::addTool);
            Message response = client.messages().create(params.build());

            StopReason stop = response.stopReason().orElseThrow();
            if (stop.equals(StopReason.END_TURN)) {
                return response.content().stream().flatMap(b -> b.text().stream())
                        .map(TextBlock::text).collect(Collectors.joining());
            }
            if (!stop.equals(StopReason.TOOL_USE)) throw new IllegalStateException("stop_reason=" + stop.asString());

            messages.add(response.toParam());
            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (!block.isToolUse()) continue;
                ToolUseBlock call = block.asToolUse();
                Map<String, Object> args = call._input().convert(new TypeReference<Map<String, Object>>() {});

                Outcome out = Hooks.runWithHooks(call.name(), args, session);
                String content = out.isError() ? (String) out.content() : json(out.content());
                System.out.println("  " + call.name() + "(" + json(args) + ") -> " + (out.isError() ? "DENIED: " : "") + content);

                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(call.id()).content(content).isError(out.isError()).build()));
            }
            messages.add(MessageParam.ofUserOfContentBlockParams(results));
        }
        return "[safety cap reached]";
    }

    static void demo() {
        System.out.println("\n=== DEMO: hooks without the model ===");
        System.out.println("\nPostToolUse - what the tool returns vs what the model sees:");
        Session s = new Session();
        for (String tool : List.of("get_customer", "lookup_order", "check_shipping")) {
            Map<String, Object> args = tool.equals("get_customer") ? Map.of("customer_id", "C-1001") : Map.of("order_id", "O-7002");
            System.out.printf("  %-15s raw:   %s%n", tool, Tools.run(tool, args, new Session()));
            System.out.printf("  %-15s model: %s%n", "", Hooks.runWithHooks(tool, args, s).content());
        }

        System.out.println("\nPreToolUse - blocked calls never reach the tool:");
        Map<String, Object> refund = new LinkedHashMap<>(Map.of("order_id", "O-7001", "amount", 900.0));
        Map<String, Object> transfer = Map.of("order_id", "O-7002", "sender_id", "S-88", "amount", 900.0);
        s = new Session();
        System.out.println("  refund $900          -> " + Hooks.runWithHooks("process_refund", refund, s).content());
        refund.put("amount", 120.0);
        System.out.println("  refund $120          -> " + Hooks.runWithHooks("process_refund", refund, s).content());
        System.out.println("  transfer before AML  -> " + Hooks.runWithHooks("transfer_funds", transfer, s).content());
        System.out.println("  aml_check            -> " + Hooks.runWithHooks("aml_check", Map.of("sender_id", "S-88"), s).content());
        System.out.println("  transfer after AML   -> " + Hooks.runWithHooks("transfer_funds", transfer, s).content());
        System.out.println("  tools that actually ran: " + s.executed);
    }

    static final Map<String, String> SCENARIOS = new LinkedHashMap<>();

    static {
        SCENARIOS.put("normalise", "For customer C-1001 and order O-7002: is the account active, when was the order "
                + "placed, when did it ship and when was it due to arrive?");
        SCENARIOS.put("refund", "The parcel for order O-7001 ($900) was lost. Refund the customer the full $900 right now.");
        SCENARIOS.put("transfer", "Transfer the $900 cash-on-delivery we collected for order O-7002 to sender S-88 now.");
    }

    public static void main(String[] args) {
        String which = args.length > 0 ? args[0] : "all";
        if (which.equals("demo") || which.equals("all")) demo();
        if (which.equals("demo")) return;

        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        try {
            SCENARIOS.forEach((name, question) -> {
                if (!which.equals(name) && !which.equals("all")) return;
                System.out.println("\n=== " + name.toUpperCase() + ": " + question);
                Session session = new Session();
                System.out.println("\n" + run(client, question, session));
                System.out.println("\n[tools that actually ran: " + session.executed + " | escalations: " + session.escalations + "]");
            });
        } finally {
            client.close();
        }
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
