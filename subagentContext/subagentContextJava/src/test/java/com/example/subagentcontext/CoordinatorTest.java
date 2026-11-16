package com.example.subagentcontext;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/** Offline tests: a fake sender answers as each agent, chosen by the system prompt it receives. */
class CoordinatorTest {

    static final String URL = "https://example.org/offshore-wind-lcoe";
    static final String DOC = "Offshore Wind Maintenance Report";
    static final int PAGE = 3;

    // ---------------------------------------------------------------- response builders

    /** Build a Message the same way the SDK does: by deserializing API JSON. */
    static Message response(String stopReason, String... blocks) {
        String json = """
                {"id":"msg_test","type":"message","role":"assistant","model":"%s",
                 "content":[%s],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """.formatted(Agents.MODEL, String.join(",", blocks), stopReason);
        try {
            return ObjectMappers.jsonMapper().readValue(json, Message.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String text(String t) {
        return ObjectMappers.jsonMapper().valueToTree(Map.of("type", "text", "text", t)).toString();
    }

    static String toolUse(String id, String name, Map<String, Object> input) {
        return ObjectMappers.jsonMapper().valueToTree(Map.of("type", "tool_use", "id", id, "name", name, "input", input)).toString();
    }

    static String task(String id, String subagentType) {
        return toolUse(id, "Task", Map.of("subagent_type", subagentType, "description", subagentType,
                "prompt", "Research offshore wind costs"));
    }

    static Map<String, Object> finding(String claim, String url, String doc, Integer page, String by) {
        Map<String, Object> f = new java.util.LinkedHashMap<>();
        f.put("claim", claim);
        f.put("analysis", "");
        f.put("source_url", url);
        f.put("source_title", url != null ? "t" : null);
        f.put("document_name", doc);
        f.put("page_number", page);
        f.put("confidence", "high");
        f.put("retrieved_by", by);
        return f;
    }

    static String json(Object o) {
        return ObjectMappers.jsonMapper().valueToTree(o).toString();
    }

    // ---------------------------------------------------------------- fake sender

    record Request(String system, List<String> tools, String firstPrompt) {
    }

    /** Plays every agent. The two research agents must be in flight at the same time (a barrier checks it). */
    static final class FakeSender implements MessageSender {
        final LinkedList<Message> coordinatorScript;
        final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        final CyclicBarrier bothResearchRunning = new CyclicBarrier(2);

        FakeSender(List<Message> coordinatorScript) {
            this.coordinatorScript = new LinkedList<>(coordinatorScript);
        }

        @Override
        public Message send(MessageCreateParams params) {
            JsonNode body = ObjectMappers.jsonMapper().valueToTree(params._body());
            String system = body.get("system").asText();
            JsonNode messages = body.get("messages");
            String prompt = messages.get(0).get("content").asText();
            List<String> tools = body.has("tools")
                    ? StreamSupport.stream(body.get("tools").spliterator(), false).map(t -> t.get("name").asText()).toList()
                    : List.of();
            requests.add(new Request(system, tools, prompt));
            boolean firstCall = messages.size() == 1;

            if (system.equals(Coordinator.COORDINATOR_SYSTEM)) {
                synchronized (coordinatorScript) {
                    return coordinatorScript.removeFirst();
                }
            }
            if (system.equals(Agents.WEB_RESEARCH.systemPrompt())) {
                if (firstCall) {
                    awaitOtherResearchAgent();
                    return response("tool_use", toolUse("w1", "web_search", Map.of("query", "offshore wind cost")));
                }
                return response("end_turn", text(json(Map.of("findings",
                        List.of(finding("Costs fell 60% since 2012", URL, null, null, "web_research"))))));
            }
            if (system.equals(Agents.DOCUMENT_ANALYSIS.systemPrompt())) {
                if (firstCall) {
                    awaitOtherResearchAgent();
                    return response("tool_use", toolUse("d1", "read_document", Map.of("document_name", DOC)));
                }
                return response("end_turn", text(json(Map.of("findings",
                        List.of(finding("Vessel day rates rose 35%", null, DOC, PAGE, "document_analysis"))))));
            }
            if (system.equals(Agents.SYNTHESIS.systemPrompt())) {
                // Cites exactly what it was given - so missing metadata shows up as missing citations.
                String given = prompt.split("\\(complete, with source metadata\\):\n", 2)[1];
                List<Map<String, Object>> claims = new ArrayList<>();
                for (JsonNode f : readTree(given)) {
                    Map<String, Object> c = new java.util.LinkedHashMap<>();
                    c.put("claim", f.get("claim").asText());
                    c.put("source_url", f.hasNonNull("source_url") ? f.get("source_url").asText() : null);
                    c.put("document_name", f.hasNonNull("document_name") ? f.get("document_name").asText() : null);
                    c.put("page_number", f.hasNonNull("page_number") ? f.get("page_number").asInt() : null);
                    claims.add(c);
                }
                return response("end_turn", text(json(Map.of("report", "# Offshore wind", "claims", claims))));
            }
            throw new AssertionError("unknown agent");
        }

        /** Fails if the other research agent is not running at the same time. */
        private void awaitOtherResearchAgent() {
            try {
                bothResearchRunning.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("research subagents did not run in parallel", e);
            }
        }

        List<Request> requestsFor(String system) {
            synchronized (requests) {
                return requests.stream().filter(r -> r.system().equals(system)).toList();
            }
        }

        private static JsonNode readTree(String json) {
            try {
                return ObjectMappers.jsonMapper().readTree(json);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static List<Message> fullRunScript() {
        return List.of(
                response("tool_use", text("Starting both research agents."),
                        task("t1", "web_research"), task("t2", "document_analysis")), // ONE response, two Task calls
                response("tool_use", task("t3", "synthesis")),
                response("end_turn", text("The report is ready.")));
    }

    static Coordinator.Result run(FakeSender fake, boolean stripMetadata) {
        return new Coordinator(fake, Coordinator.Options.defaults(), stripMetadata).run("q");
    }

    @BeforeEach
    void quiet() {
        Logger.getLogger("").setLevel(Level.OFF);
    }

    @AfterEach
    void loud() {
        Logger.getLogger("").setLevel(Level.INFO);
    }

    // ---------------------------------------------------------------- tests

    @Test
    void coordinatorNeedsTaskInAllowedTools() {
        FakeSender fake = new FakeSender(List.of(response("end_turn", text("I have no way to delegate."))));
        Coordinator.Options noTask = new Coordinator.Options(List.of(), Agents.ALL, Coordinator.COORDINATOR_SYSTEM);
        Coordinator.Result result = new Coordinator(fake, noTask, false).run("q");

        assertEquals(List.of(), fake.requests.get(0).tools()); // no Task tool offered
        assertEquals(1, fake.requests.size());                  // so no subagent ever ran
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void independentSubagentsRunInParallel() {
        Coordinator.Result result = run(new FakeSender(fullRunScript()), false); // the barrier fails if sequential

        assertEquals(List.of(List.of("web_research", "document_analysis"), List.of("synthesis")), result.spawnBatches());
    }

    @Test
    void eachAgentGetsOnlyItsOwnTools() {
        FakeSender fake = new FakeSender(fullRunScript());
        run(fake, false);

        assertEquals(List.of("Task"), fake.requestsFor(Coordinator.COORDINATOR_SYSTEM).get(0).tools());
        assertEquals(List.of("web_search"), fake.requestsFor(Agents.WEB_RESEARCH.systemPrompt()).get(0).tools());
        assertEquals(List.of("list_documents", "read_document"),
                fake.requestsFor(Agents.DOCUMENT_ANALYSIS.systemPrompt()).get(0).tools());
        assertEquals(List.of(), fake.requestsFor(Agents.SYNTHESIS.systemPrompt()).get(0).tools());
    }

    @Test
    void subagentSeesOnlyItsPrompt() {
        FakeSender fake = new FakeSender(fullRunScript());
        run(fake, false);

        assertEquals("Research offshore wind costs", // the Task prompt, nothing inherited
                fake.requestsFor(Agents.WEB_RESEARCH.systemPrompt()).get(0).firstPrompt());
    }

    @Test
    void completeFindingsWithMetadataReachSynthesis() {
        FakeSender fake = new FakeSender(fullRunScript());
        run(fake, false);

        String prompt = fake.requestsFor(Agents.SYNTHESIS.systemPrompt()).get(0).firstPrompt();
        for (String expected : List.of(URL, DOC, "\"page_number\" : " + PAGE, "Costs fell", "Vessel day rates")) {
            assertTrue(prompt.contains(expected), "synthesis prompt is missing: " + expected);
        }
    }

    @Test
    void everyClaimIsAttributed() {
        Coordinator.Result result = run(new FakeSender(fullRunScript()), false);

        assertEquals(2, result.claims().size());
        assertEquals(List.of(), result.unattributed());
    }

    @Test
    void strippedMetadataBreaksAttribution() {
        // Exam pattern: research worked, synthesis "lost" its sources -> the cause is context passing.
        Coordinator.Result result = run(new FakeSender(fullRunScript()), true);

        assertEquals(2, result.findings().size());     // the research subagents did their job
        assertEquals(2, result.unattributed().size()); // but no claim can be traced to a source
    }

    @Test
    void subagentCannotUseAToolOutsideItsDefinition() {
        ToolUseBlock use = response("tool_use", toolUse("x", "read_document", Map.of("document_name", DOC)))
                .content().get(0).asToolUse();
        ContentBlockParam result = Agents.restrictedToolRunner(Agents.WEB_RESEARCH).apply(List.of(use)).get(0);

        assertTrue(result.asToolResult().isError().orElse(false));
    }

    @Test
    void toolsReturnSourceMetadata() {
        assertTrue(Agents.webSearch("offshore wind cost trend").contains(URL));
        assertTrue(Agents.readDocument(DOC).contains("\"page_number\" : 3"));
    }
}
