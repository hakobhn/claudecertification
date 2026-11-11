package com.example.agentloop;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

/** Offline tests: a scripted fake sender stands in for the Claude API. */
class AgentLoopTest {

    // ---------------------------------------------------------------- helpers

    /** Build a Message the same way the SDK does: by deserializing API JSON. */
    private static Message response(String stopReason, String... contentBlocksJson) {
        String json = """
                {"id":"msg_test","type":"message","role":"assistant","model":"%s",
                 "content":[%s],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """.formatted(AgentLoop.MODEL, String.join(",", contentBlocksJson), stopReason);
        try {
            return ObjectMappers.jsonMapper().readValue(json, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(String t) {
        return "{\"type\":\"text\",\"text\":\"" + t + "\"}";
    }

    private static String toolUse(String id, String name, String inputJson) {
        return "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}";
    }

    /** Returns scripted responses in order and records each request's messages as JSON. */
    private static final class FakeSender implements AgentLoop.MessageSender {
        private final Iterator<Message> responses;
        final List<JsonNode> requests = new ArrayList<>();

        FakeSender(List<Message> responses) {
            this.responses = responses.iterator();
        }

        @Override
        public Message send(MessageCreateParams params) {
            requests.add(ObjectMappers.jsonMapper().valueToTree(params._body()).get("messages"));
            return responses.next();
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    void toolsWork() {
        assertTrue(Tools.calculator("(8849 - 6190) * 2").contains("\"result\":5318"));
        assertTrue(Tools.webSearch("height of Everest").contains("8,849"));
        assertEquals(-4.0, Calculator.evaluate("-2 ** 2"));
        assertEquals(3.0, Calculator.evaluate("7 // 2"));
        assertTrue(Tools.execute("calculator", Map.of("expression", "System.exit(0)")).isError());
    }

    @Test
    void sequentialToolCallsContinueUntilEndTurn() {
        // Text + tool_use in the same response must NOT end the loop (anti-pattern 3).
        FakeSender fake = new FakeSender(List.of(
                response("tool_use", text("Let me look up both heights."),
                        toolUse("t1", "web_search", "{\"query\":\"Everest height\"}"),
                        toolUse("t2", "web_search", "{\"query\":\"Denali height\"}")),
                response("tool_use", text("Now I'll compute the difference."),
                        toolUse("t3", "calculator", "{\"expression\":\"8849 - 6190\"}")),
                response("end_turn", text("Everest is 2,659 m taller than Denali."))));

        String out = new AgentLoop(fake).run("How much taller is Everest than Denali?");

        assertEquals("Everest is 2,659 m taller than Denali.", out);
        assertEquals(3, fake.requests.size()); // two tool iterations, then end_turn

        // History grows: user, assistant(tool_use), user(tool_results), ...
        JsonNode second = fake.requests.get(1);
        assertEquals(3, second.size());
        assertEquals("assistant", second.get(1).get("role").asText());
        JsonNode results = second.get(2).get("content");
        assertEquals(2, results.size()); // both results in ONE user message
        assertEquals("t1", results.get(0).get("tool_use_id").asText());
        assertEquals("t2", results.get(1).get("tool_use_id").asText());
        assertTrue(results.get(0).get("content").asText().contains("8,849"));

        JsonNode third = fake.requests.get(2);
        assertTrue(third.get(4).get("content").get(0).get("content").asText().contains("\"result\":2659"));
    }

    @Test
    void toolErrorIsReturnedToClaude() {
        FakeSender fake = new FakeSender(List.of(
                response("tool_use", toolUse("t1", "calculator", "{\"expression\":\"1/0\"}")),
                response("end_turn", text("Division by zero is undefined."))));

        new AgentLoop(fake).run("1/0?");

        JsonNode result = fake.requests.get(1).get(2).get("content").get(0);
        assertTrue(result.get("is_error").asBoolean());
        assertTrue(result.get("content").asText().contains("division by zero"));
    }

    @Test
    void safetyCapIsABackstopAndWarns() {
        List<Message> looping = new ArrayList<>();
        for (int i = 0; i < AgentLoop.MAX_ITERATIONS + 5; i++) {
            looping.add(response("tool_use", toolUse("t" + i, "web_search", "{\"query\":\"x\"}")));
        }
        FakeSender fake = new FakeSender(looping);

        List<LogRecord> warnings = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) { if (r.getLevel() == Level.WARNING) warnings.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        AgentLoop.LOG.addHandler(capture);
        try {
            String out = new AgentLoop(fake).run("loop forever");
            assertEquals(AgentLoop.MAX_ITERATIONS, fake.requests.size());
            assertTrue(out.contains("safety"));
            assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains("Safety cap")));
        } finally {
            AgentLoop.LOG.removeHandler(capture);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"max_tokens", "refusal", "model_context_window_exceeded"})
    void otherStopReasonsAreNotTreatedAsDone(String reason) {
        FakeSender fake = new FakeSender(List.of(response(reason, text("partial"))));
        assertThrows(IllegalStateException.class, () -> new AgentLoop(fake).run("q"));
    }
}
