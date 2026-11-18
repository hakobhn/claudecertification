package com.example.support;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline tests. The gate and handoff check are tested directly (no model), and the whole
 * loop is tested with a scripted fake sender that misbehaves on purpose.
 */
class SupportAgentTest {

    static Map<String, Object> completeHandoff() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("customer_id", "C-1001");
        h.put("conversation_summary", "Customer asked to return O-5001, reported a duplicate charge on O-5002 and "
                + "asked to change her shipping address. Return refund R-0001 (129.00) processed.");
        h.put("root_cause_analysis", "O-5002 shows two identical charges of 89.50: a duplicate capture at checkout.");
        h.put("refund_amount", 89.50);
        h.put("recommended_action", "Refund the duplicate 89.50 charge on O-5002 and update the shipping address "
                + "to 12 Elm Street, Bath BA1 2QQ after confirming with the customer.");
        h.put("concerns", List.of(
                Map.of("concern", "Return O-5001", "status", "resolved", "details", "Refunded 129.00 (R-0001)."),
                Map.of("concern", "Duplicate charge O-5002", "status", "needs_human", "details", "Two charges of 89.50."),
                Map.of("concern", "Address change", "status", "needs_human", "details", "New: 12 Elm Street, Bath BA1 2QQ.")));
        return h;
    }

    @BeforeEach
    void quiet() {
        SupportSession.LOG.setLevel(Level.OFF);
    }

    @AfterEach
    void loud() {
        SupportSession.LOG.setLevel(null);
    }

    // ---------------------------------------------------------------- gate (no model)

    @Nested
    class Gate {
        SupportSession.ToolResult refund(SupportSession s, String customer, String order, double amount) {
            return s.execute("process_refund", Map.of("customer_id", customer, "order_id", order, "amount", amount));
        }

        @Test
        void refundBlockedBeforeVerification() {
            SupportSession s = new SupportSession();
            SupportSession.ToolResult r = refund(s, "C-1001", "O-5001", 129.0);
            assertTrue(r.isError());
            assertTrue(r.content().contains("BLOCKED"));
            assertTrue(r.content().contains("get_customer")); // the error tells the model what to do
            assertTrue(s.refunds.isEmpty());
        }

        @Test
        void refundAllowedAfterVerification() {
            SupportSession s = new SupportSession();
            s.execute("get_customer", Map.of("name_or_email", "maria.lopez@example.com"));
            assertFalse(refund(s, "C-1001", "O-5001", 129.0).isError());
            assertEquals(129.0, s.refunds.get(0).get("amount"));
        }

        @Test
        void unverifiedAccountStaysBlocked() {
            SupportSession s = new SupportSession();
            s.execute("get_customer", Map.of("name_or_email", "James Chen")); // identity_verified = false
            SupportSession.ToolResult r = refund(s, "C-1002", "O-5003", 45.0);
            assertTrue(r.isError());
            assertTrue(r.content().contains("could not be verified"));
            assertTrue(r.content().contains("escalate"));
            assertTrue(s.refunds.isEmpty());
        }

        @Test
        void refundOnlyForTheVerifiedCustomer() {
            SupportSession s = new SupportSession();
            s.execute("get_customer", Map.of("name_or_email", "maria.lopez@example.com"));
            SupportSession.ToolResult r = refund(s, "C-1002", "O-5003", 45.0);
            assertTrue(r.isError());
            assertTrue(r.content().contains("only go to the verified customer (C-1001)"));
        }

        @Test
        void businessRulesStillApplyAfterTheGate() {
            SupportSession s = new SupportSession();
            s.execute("get_customer", Map.of("name_or_email", "maria.lopez@example.com"));
            assertTrue(refund(s, "C-1001", "O-5001", 500.0).isError()); // more than the order amount
        }
    }

    // ---------------------------------------------------------------- handoff check

    @Nested
    class Handoff {
        @Test
        void completeHandoffPasses() {
            assertEquals(List.of(), SupportSession.validateHandoff(completeHandoff()));
        }

        @Test
        void missingAndPlaceholderFieldsAreRejected() {
            Map<String, Object> bad = completeHandoff();
            bad.put("root_cause_analysis", "");
            bad.put("recommended_action", "TBD");
            bad.put("customer_id", "[customer]");
            String problems = String.join(" ", SupportSession.validateHandoff(bad));
            assertTrue(problems.contains("root_cause_analysis"));
            assertTrue(problems.contains("recommended_action"));
            assertTrue(problems.contains("customer_id"));
        }

        @Test
        void concernsNeedDetails() {
            Map<String, Object> bad = completeHandoff();
            bad.put("concerns", List.of(Map.of("concern", "Address", "status", "needs_human", "details", "N/A")));
            assertTrue(SupportSession.validateHandoff(bad).stream().anyMatch(p -> p.contains("has no details")));
            bad.put("concerns", List.of());
            assertFalse(SupportSession.validateHandoff(bad).isEmpty());
        }
    }

    // ---------------------------------------------------------------- whole loop, scripted model

    static String json(Object o) {
        return SupportSession.toJson(o);
    }

    static String text(String t) {
        return json(Map.of("type", "text", "text", t));
    }

    static String toolUse(String id, String name, Map<String, Object> input) {
        return json(Map.of("type", "tool_use", "id", id, "name", name, "input", input));
    }

    static Message response(String stopReason, String... blocks) {
        String body = "{\"id\":\"msg\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"" + SupportAgent.MODEL + "\","
                + "\"content\":[" + String.join(",", blocks) + "],\"stop_reason\":\"" + stopReason + "\","
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        try {
            return ObjectMappers.jsonMapper().readValue(body, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final class FakeSender implements SupportAgent.MessageSender {
        private final Iterator<Message> responses;
        final List<JsonNode> requests = new ArrayList<>();

        FakeSender(Message... responses) {
            this.responses = List.of(responses).iterator();
        }

        @Override
        public Message send(MessageCreateParams params) {
            requests.add(ObjectMappers.jsonMapper().valueToTree(params._body()).get("messages"));
            return responses.next();
        }

        JsonNode toolResults(int request) {
            JsonNode messages = requests.get(request);
            return messages.get(messages.size() - 1).get("content");
        }
    }

    static Map<String, Object> refundInput() {
        return Map.of("customer_id", "C-1001", "order_id", "O-5001", "amount", 129.0);
    }

    @Nested
    class Loop {
        @Test
        void modelSkipsVerificationGateBlocksThenModelRecovers() {
            // The model "decides" to skip verification - exactly the 8% case from the lesson.
            FakeSender fake = new FakeSender(
                    response("tool_use", text("Processing your refund now."), toolUse("t1", "process_refund", refundInput())),
                    response("tool_use", toolUse("t2", "get_customer", Map.of("name_or_email", "maria.lopez@example.com"))),
                    response("tool_use", toolUse("t3", "process_refund", refundInput())),
                    response("end_turn", text("Your refund of 129.00 has been processed.")));

            SupportAgent.AgentResult result = new SupportAgent(fake).run(Main.BYPASS_MESSAGE);

            JsonNode blocked = fake.toolResults(1).get(0);
            assertTrue(blocked.get("is_error").asBoolean());
            assertTrue(blocked.get("content").asText().contains("BLOCKED"));
            SupportSession s = result.session();
            assertEquals(1, s.blocked.size());
            assertEquals(1, s.refunds.size()); // only after verification
            assertEquals(s.verifiedCustomerId(), s.refunds.get(0).get("customer_id"));
        }

        @Test
        void multiConcernParallelLookupsOneRefundOneCompleteHandoff() {
            Map<String, Object> incomplete = new HashMap<>(completeHandoff());
            incomplete.put("recommended_action", "");
            FakeSender fake = new FakeSender(
                    // decompose + investigate in parallel: three tool calls in ONE turn
                    response("tool_use", text("Let me look into all three items."),
                            toolUse("a", "get_customer", Map.of("name_or_email", "maria.lopez@example.com")),
                            toolUse("b", "lookup_order", Map.of("order_id", "O-5001")),
                            toolUse("c", "lookup_order", Map.of("order_id", "O-5002"))),
                    response("tool_use", toolUse("d", "process_refund", refundInput())),
                    response("tool_use", toolUse("e", "escalate_to_human", incomplete)),        // rejected
                    response("tool_use", toolUse("f", "escalate_to_human", completeHandoff())), // fixed
                    response("end_turn", text("All three items are handled.")));

            SupportAgent.AgentResult result = new SupportAgent(fake).run(Main.MULTI_MESSAGE);

            assertEquals(3, fake.toolResults(1).size()); // all parallel results in one message
            JsonNode rejected = fake.toolResults(3).get(0);
            assertTrue(rejected.get("is_error").asBoolean());
            assertTrue(rejected.get("content").asText().contains("recommended_action"));

            SupportSession s = result.session();
            assertEquals(1, s.refunds.size());
            assertEquals(1, s.handoffs.size());
            assertEquals(3, ((List<?>) s.handoffs.get(0).get("concerns")).size());
            assertEquals(89.50, ((Number) s.handoffs.get(0).get("refund_amount")).doubleValue());
        }
    }
}
