package com.example.orchestration;

import com.example.orchestration.Coordinator.ResearchReport;
import com.example.orchestration.Coordinator.SubtopicResearch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Offline tests: a fake LLM answers by role (which system prompt it was given). */
class CoordinatorTest {

    static final List<String> SIX = List.of("Solar power", "Wind power", "Geothermal energy",
            "Tidal and wave energy", "Biomass energy", "Nuclear fusion");

    // ---------------------------------------------------------------- JSON builders

    static String subtopicsJson(List<String> names) {
        return "{\"subtopics\":[" + names.stream()
                .map(n -> "{\"name\":\"" + n + "\",\"description\":\"Scope of " + n + "\",\"search_query\":\"" + n + " overview\"}")
                .collect(Collectors.joining(",")) + "]}";
    }

    static String evaluationJson(Map<String, String> statuses, List<String> missingAreas) {
        String assessments = statuses.entrySet().stream().map(e -> {
            boolean ok = e.getValue().equals("covered");
            return "{\"subtopic\":\"" + e.getKey() + "\",\"status\":\"" + e.getValue() + "\",\"gap\":\""
                    + (ok ? "" : e.getKey() + " lacks cost data") + "\",\"follow_up_query\":\""
                    + (ok ? "" : e.getKey() + " cost per MWh") + "\"}";
        }).collect(Collectors.joining(","));
        String areas = missingAreas.stream()
                .map(n -> "{\"name\":\"" + n + "\",\"description\":\"Scope of " + n + "\",\"search_query\":\"" + n + " overview\"}")
                .collect(Collectors.joining(","));
        return "{\"assessments\":[" + assessments + "],\"missing_areas\":[" + areas + "]}";
    }

    static Map<String, String> statuses(List<String> names, String status) {
        Map<String, String> m = new LinkedHashMap<>();
        names.forEach(n -> m.put(n, status));
        return m;
    }

    static String allCovered(List<String> names) {
        return evaluationJson(statuses(names, "covered"), List.of());
    }

    // ---------------------------------------------------------------- fake LLM

    record Call(String system, String prompt, Map<String, Object> schema) {
    }

    static final class FakeLlm implements Llm {
        final LinkedList<String> decompositions;
        final LinkedList<String> evaluations;
        final List<Call> calls = new ArrayList<>();

        FakeLlm(List<String> decompositions, List<String> evaluations) {
            this.decompositions = new LinkedList<>(decompositions);
            this.evaluations = new LinkedList<>(evaluations);
        }

        @Override
        public synchronized String complete(String system, String prompt, Map<String, Object> schema) {
            calls.add(new Call(system, prompt, schema));
            if (system.equals(Subagents.WEB_SEARCH_SYSTEM)) return "- web fact about " + subtopicOf(prompt);
            if (system.equals(Subagents.DOCUMENT_ANALYSIS_SYSTEM)) return "- document fact";
            if (schema == Coordinator.DECOMPOSE_SCHEMA) return decompositions.removeFirst();
            if (schema == Coordinator.EVALUATE_SCHEMA) return evaluations.removeFirst();
            return "# Report\n" + SIX.stream().map(n -> "## " + n + "\ntext").collect(Collectors.joining("\n"));
        }

        synchronized List<String> promptsFor(String system) {
            return calls.stream().filter(c -> c.system().equals(system)).map(Call::prompt).toList();
        }
    }

    static String subtopicOf(String prompt) {
        return prompt.split("Your assigned subtopic: ")[1].split("\n")[0];
    }

    @BeforeEach
    void quiet() {
        Coordinator.LOG.setLevel(Level.OFF);
    }

    @AfterEach
    void loud() {
        Coordinator.LOG.setLevel(null);
    }

    // ---------------------------------------------------------------- tests

    @Test
    void narrowDecompositionIsRejectedAndRetried() {
        FakeLlm llm = new FakeLlm(List.of(subtopicsJson(List.of("Solar power", "Wind power")), subtopicsJson(SIX)),
                List.of(allCovered(SIX)));
        ResearchReport report = new Coordinator(llm).run("renewable energy technologies");

        assertEquals(SIX, report.research().stream().map(r -> r.subtopic.name()).toList());
        String retryPrompt = llm.calls.stream().filter(c -> c.schema() == Coordinator.DECOMPOSE_SCHEMA).toList().get(1).prompt();
        assertTrue(retryPrompt.contains("too narrow"));
    }

    @Test
    void missingRequiredCategoryIsSentBackToClaude() {
        List<String> five = SIX.subList(0, 5); // enough subtopics, but no fusion - the narrow-decomposition failure
        FakeLlm llm = new FakeLlm(List.of(subtopicsJson(five), subtopicsJson(SIX)), List.of(allCovered(SIX)));
        ResearchReport report = new Coordinator(llm).run("renewable energy technologies", Main.REQUIRED_FOR_TEST);

        String retryPrompt = llm.calls.stream().filter(c -> c.schema() == Coordinator.DECOMPOSE_SCHEMA).toList().get(1).prompt();
        assertTrue(retryPrompt.contains("does not cover these required categories: fusion"));
        assertTrue(report.research().stream().anyMatch(r -> r.subtopic.name().equals("Nuclear fusion")));
    }

    @Test
    void coordinatorAddsRequiredCategoryAsLastResort() {
        List<String> five = SIX.subList(0, 5);
        List<String> withFusion = new ArrayList<>(five);
        withFusion.add("Fusion");
        FakeLlm llm = new FakeLlm(List.of(subtopicsJson(five), subtopicsJson(five)), List.of(allCovered(withFusion)));
        ResearchReport report = new Coordinator(llm).run("renewable energy technologies", Main.REQUIRED_FOR_TEST);

        assertEquals(withFusion, report.research().stream().map(r -> r.subtopic.name()).toList());
        long fusionCalls = llm.promptsFor(Subagents.WEB_SEARCH_SYSTEM).stream().filter(p -> p.contains("subtopic: Fusion")).count();
        assertEquals(1, fusionCalls); // it really was researched
    }

    @Test
    void subagentsAreIsolatedAndGetExplicitContext() {
        FakeLlm llm = new FakeLlm(List.of(subtopicsJson(SIX)), List.of(allCovered(SIX)));
        new Coordinator(llm).run("renewable energy technologies");

        List<String> subagentPrompts = new ArrayList<>(llm.promptsFor(Subagents.WEB_SEARCH_SYSTEM));
        subagentPrompts.addAll(llm.promptsFor(Subagents.DOCUMENT_ANALYSIS_SYSTEM));
        for (String p : subagentPrompts) {
            assertFalse(p.contains(Coordinator.COORDINATOR_SYSTEM));   // no inherited coordinator prompt
            assertTrue(p.contains("Overall research topic: renewable energy technologies"));
            assertTrue(p.contains("Research goal:"));
        }

        // Each web search prompt mentions only its own subtopic, never another subagent's output.
        String solar = llm.promptsFor(Subagents.WEB_SEARCH_SYSTEM).stream()
                .filter(p -> p.contains("subtopic: Solar power")).findFirst().orElseThrow();
        assertFalse(solar.contains("web fact about Wind power"));

        // Document analysis receives the web findings - passed by the coordinator.
        String geo = llm.promptsFor(Subagents.DOCUMENT_ANALYSIS_SYSTEM).stream()
                .filter(p -> p.contains("subtopic: Geothermal")).findFirst().orElseThrow();
        assertTrue(geo.contains("web fact about Geothermal energy"));
        assertTrue(geo.contains("Geothermal Feasibility Study"));
    }

    @Test
    void documentAnalysisOnlyWhereDocumentsExist() {
        FakeLlm llm = new FakeLlm(List.of(subtopicsJson(SIX)), List.of(allCovered(SIX)));
        ResearchReport report = new Coordinator(llm).run("renewable energy technologies");

        List<String> analysed = llm.promptsFor(Subagents.DOCUMENT_ANALYSIS_SYSTEM).stream()
                .map(CoordinatorTest::subtopicOf).sorted().toList();
        assertEquals(List.of("Biomass energy", "Geothermal energy", "Solar power", "Wind power"), analysed);
        assertEquals(6, llm.promptsFor(Subagents.WEB_SEARCH_SYSTEM).size());
        assertEquals(1.0, report.coverage());
    }

    @Test
    void refinementLoopRedelegatesOnlyTheGaps() {
        Map<String, String> first = statuses(SIX, "covered");
        first.put("Tidal and wave energy", "partial");
        List<String> withHydro = new ArrayList<>(SIX);
        withHydro.add("Hydropower");
        FakeLlm llm = new FakeLlm(List.of(subtopicsJson(SIX)),
                List.of(evaluationJson(first, List.of("Hydropower")), allCovered(withHydro)));

        ResearchReport report = new Coordinator(llm).run("renewable energy technologies");

        assertEquals(2, report.rounds());
        assertEquals(1.0, report.coverage());
        List<String> web = llm.promptsFor(Subagents.WEB_SEARCH_SYSTEM);
        assertEquals(8, web.size()); // 6 initial + tidal follow-up + new hydropower area

        String tidalFollowUp = web.stream().filter(p -> p.contains("Tidal") && p.contains("Gap the coordinator"))
                .findFirst().orElseThrow();
        assertTrue(tidalFollowUp.contains("Search focus: Tidal and wave energy cost per MWh"));
        assertTrue(tidalFollowUp.contains("web fact about Tidal and wave energy")); // prior findings passed back in
        assertTrue(report.research().stream().anyMatch(r -> r.subtopic.name().equals("Hydropower")));
    }

    @Test
    void refinementStopsAtMaxRounds() {
        String neverDone = evaluationJson(statuses(SIX, "partial"), List.of());
        FakeLlm llm = new FakeLlm(List.of(subtopicsJson(SIX)), List.of(neverDone, neverDone, neverDone, neverDone));
        ResearchReport report = new Coordinator(llm, 5, 3, 6).run("renewable energy technologies");

        assertEquals(3, report.rounds());
        assertEquals(18, llm.promptsFor(Subagents.WEB_SEARCH_SYSTEM).size()); // 3 rounds x 6 subtopics
        assertTrue(report.markdown().contains("Coverage: 0/6"));
    }

    @Test
    void allMessagesRouteThroughTheCoordinator() {
        FakeLlm llm = new FakeLlm(List.of(subtopicsJson(SIX)), List.of(allCovered(SIX)));
        Coordinator coordinator = new Coordinator(llm);
        coordinator.run("renewable energy technologies");

        assertFalse(coordinator.trace.isEmpty());
        for (Coordinator.Route r : coordinator.trace) {
            assertTrue(r.from().equals("coordinator") || r.to().equals("coordinator")); // no spoke-to-spoke
        }
    }

    @Test
    void failedSubagentIsRetriedThenRecorded() {
        FakeLlm fake = new FakeLlm(List.of(subtopicsJson(SIX)), List.of(allCovered(SIX)));
        AtomicInteger failures = new AtomicInteger();
        Llm flaky = (system, prompt, schema) -> {
            if (system.equals(Subagents.WEB_SEARCH_SYSTEM) && prompt.contains("Nuclear fusion")
                    && failures.getAndIncrement() < 1) {
                throw new IllegalStateException("overloaded");
            }
            return fake.complete(system, prompt, schema);
        };

        ResearchReport report = new Coordinator(flaky).run("renewable energy technologies");
        SubtopicResearch fusion = report.research().stream()
                .filter(r -> r.subtopic.name().equals("Nuclear fusion")).findFirst().orElseThrow();
        assertEquals(1, fusion.findings.size());
        assertEquals("web_search", fusion.findings.get(0)[0]);
        assertEquals("- web fact about Nuclear fusion", fusion.findings.get(0)[1]);
    }
}
