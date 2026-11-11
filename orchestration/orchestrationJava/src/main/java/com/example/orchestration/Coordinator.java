package com.example.orchestration;

import com.example.orchestration.Documents.Document;
import com.example.orchestration.Subagents.DocumentAnalysisContext;
import com.example.orchestration.Subagents.WebSearchContext;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Build Exercise 1.2 - Hub-and-spoke research coordinator.
 *
 * <pre>
 *                  +----------------------+
 *                  |     COORDINATOR      |  decomposes, selects subagents,
 *                  |        (hub)         |  routes context, evaluates coverage,
 *                  +----------+-----------+  re-delegates gaps, writes the report
 *            +----------------+-----------------+
 *    +-------v--------+                 +-------v---------+
 *    | web_search     |                 | document_       |
 *    | subagent       |                 | analysis        |
 *    +----------------+                 +-----------------+
 *    Subagents never talk to each other. Everything goes through the coordinator.
 * </pre>
 *
 * The four coordinator responsibilities from the lesson, and where they are:
 * <ol>
 *   <li>Dynamic subagent selection   - {@link #researchSubtopic} (documents only where they exist)</li>
 *   <li>Research scope partitioning  - {@link #decompose} + one source type per subagent</li>
 *   <li>Iterative refinement loop    - {@link #run}: evaluate, re-delegate gaps, evaluate ...</li>
 *   <li>Centralised routing          - {@link #route} logs every message in one place</li>
 * </ol>
 */
public final class Coordinator {

    public static final int MIN_SUBTOPICS = 5;
    public static final int MAX_ROUNDS = 3;      // 1 initial round + up to 2 refinement rounds
    public static final int MAX_SUBTOPICS = 12;  // guard against the evaluator adding areas forever

    static final Logger LOG = Logger.getLogger("coordinator");

    public static final String COORDINATOR_SYSTEM =
            "You are the coordinator of a hub-and-spoke multi-agent research system. You never research "
                    + "subtopics yourself. Your jobs are: break a broad topic into subtopics that together cover its "
                    + "FULL breadth (including less obvious and emerging areas, not just the most popular ones); judge "
                    + "whether the findings your subagents returned cover each subtopic; and write the final report "
                    + "using only those findings.";

    // ------------------------------------------------------------------ JSON schemas + result types

    private static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", new ArrayList<>(properties.keySet()));
        m.put("additionalProperties", false);
        return m;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static final Map<String, Object> STRING = Map.of("type", "string");

    private static final Map<String, Object> SUBTOPIC_SCHEMA = object(orderedMap(
            "name", STRING, "description", STRING, "search_query", STRING));

    public static final Map<String, Object> DECOMPOSE_SCHEMA = object(orderedMap(
            "subtopics", arrayOf(SUBTOPIC_SCHEMA)));

    public static final Map<String, Object> EVALUATE_SCHEMA = object(orderedMap(
            "assessments", arrayOf(object(orderedMap(
                    "subtopic", STRING,
                    "status", Map.of("type", "string", "enum", List.of("covered", "partial", "missing")),
                    "gap", STRING,
                    "follow_up_query", STRING))),
            "missing_areas", arrayOf(SUBTOPIC_SCHEMA)));

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public record Subtopic(String name, String description, @JsonProperty("search_query") String searchQuery) {
    }

    record Decomposition(List<Subtopic> subtopics) {
    }

    record Assessment(String subtopic, String status, String gap, @JsonProperty("follow_up_query") String followUpQuery) {
    }

    record Evaluation(List<Assessment> assessments, @JsonProperty("missing_areas") List<Subtopic> missingAreas) {
    }

    public record FollowUp(String query, String gap) {
    }

    /** Everything the coordinator holds about one subtopic. Only the coordinator sees all of these. */
    public static final class SubtopicResearch {
        public final Subtopic subtopic;
        public final List<String[]> findings = new ArrayList<>(); // {agent name, text}
        boolean documentsAnalysed;
        public String status = "missing";
        String gap = "";

        SubtopicResearch(Subtopic subtopic) {
            this.subtopic = subtopic;
        }

        String findingsText() {
            return findings.stream().map(f -> "[" + f[0] + "]\n" + f[1]).collect(Collectors.joining("\n\n"));
        }
    }

    public record Work(SubtopicResearch research, FollowUp followUp) {
    }

    public record Route(String from, String to, String subtopic) {
    }

    public record ResearchReport(String topic, List<SubtopicResearch> research, int rounds, String markdown) {
        public double coverage() {
            long covered = research.stream().filter(r -> r.status.equals("covered")).count();
            return research.isEmpty() ? 0 : (double) covered / research.size();
        }
    }

    // ------------------------------------------------------------------ state

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Llm llm;
    private final int minSubtopics;
    private final int maxRounds;
    private final int maxWorkers;
    public final List<Route> trace = Collections.synchronizedList(new ArrayList<>()); // every routed message

    public Coordinator(Llm llm) {
        this(llm, MIN_SUBTOPICS, MAX_ROUNDS, 6);
    }

    public Coordinator(Llm llm, int minSubtopics, int maxRounds, int maxWorkers) {
        this.llm = llm;
        this.minSubtopics = minSubtopics;
        this.maxRounds = maxRounds;
        this.maxWorkers = maxWorkers;
    }

    // ------------------------------------------------------------------ main flow

    public ResearchReport run(String topic) {
        return run(topic, List.of());
    }

    /** @param mustCover categories the requester requires in the report (scope requirements) */
    public ResearchReport run(String topic, List<String> mustCover) {
        String goal = "Produce a broad, balanced overview of '" + topic + "' that covers every major category, "
                + "with concrete facts, maturity, costs and limitations for each.";

        // Step 2: decomposition covering the full breadth.
        Map<String, SubtopicResearch> research = new LinkedHashMap<>();
        for (Subtopic s : decompose(topic, mustCover)) research.put(key(s.name()), new SubtopicResearch(s));

        // Step 3: first round of delegation (all subtopics in parallel).
        delegate(topic, goal, research.values().stream().map(r -> new Work(r, null)).toList());
        int rounds = 1;

        // Steps 4-5: evaluate coverage, re-delegate gaps, repeat.
        while (true) {
            List<Work> work = evaluate(topic, research);
            LOG.info("round " + rounds + " coverage: " + research.values().stream()
                    .map(r -> r.subtopic.name() + "=" + r.status).collect(Collectors.joining(", ")));
            if (work.isEmpty()) break;
            if (rounds >= maxRounds) {
                LOG.warning("Stopping refinement after " + rounds + " rounds; " + work.size()
                        + " subtopic(s) still not fully covered.");
                break;
            }
            rounds++;
            LOG.info("round " + rounds + ": re-delegating " + work.size() + " gap(s): "
                    + work.stream().map(w -> w.research().subtopic.name()).collect(Collectors.joining(", ")));
            delegate(topic, goal, work);
        }

        List<SubtopicResearch> all = List.copyOf(research.values());
        return new ResearchReport(topic, all, rounds, writeReport(topic, all, rounds));
    }

    // ------------------------------------------------------------------ 1. decomposition

    public List<Subtopic> decompose(String topic, List<String> mustCover) {
        String prompt = "Break the research topic '" + topic + "' into at least " + minSubtopics + " distinct subtopics "
                + "that TOGETHER cover the full breadth of the subject. Include smaller, emerging and "
                + "experimental categories, not just the best-known ones. Also include borderline or adjacent "
                + "categories that are commonly discussed alongside this topic, even if their classification "
                + "is debated, and say so in their description. Subtopics must not overlap. "
                + "For each, give a one-sentence scope description and a focused search query.";
        List<Subtopic> subtopics = List.of();
        for (int attempt = 1; attempt <= 2; attempt++) {
            Decomposition d = parse(llm.complete(COORDINATOR_SYSTEM, prompt, DECOMPOSE_SCHEMA), Decomposition.class);
            subtopics = unique(d.subtopics());
            List<String> uncovered = uncovered(mustCover, subtopics);
            String names = subtopics.stream().map(Subtopic::name).collect(Collectors.joining(", "));
            if (subtopics.size() >= minSubtopics && uncovered.isEmpty()) {
                LOG.info("decomposed '" + topic + "' into " + subtopics.size() + " subtopics: " + names);
                return subtopics;
            }

            // Too narrow: tell Claude exactly what is wrong and ask again.
            if (subtopics.size() < minSubtopics) {
                LOG.warning("decomposition attempt " + attempt + " gave only " + subtopics.size() + " subtopics; asking again");
                prompt += "\n\nYour previous answer had only " + subtopics.size() + " subtopics (" + names + "). "
                        + "That is too narrow: it leaves whole categories un-researched. Give at least " + minSubtopics + ".";
            }
            if (!uncovered.isEmpty()) {
                LOG.warning("decomposition attempt " + attempt + " does not cover required: "
                        + String.join(", ", uncovered) + "; asking again");
                prompt += "\n\nYour previous answer (" + names + ") does not cover these required categories: "
                        + String.join(", ", uncovered) + ". Add a subtopic for each of them.";
            }
        }

        if (subtopics.size() < minSubtopics) {
            throw new IllegalStateException("Could not decompose '" + topic + "' into " + minSubtopics + "+ subtopics");
        }
        // Last resort: the coordinator adds the required categories itself.
        List<Subtopic> result = new ArrayList<>(subtopics);
        for (String name : uncovered(mustCover, subtopics)) {
            LOG.warning("adding required subtopic the decomposition kept missing: " + name);
            String title = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            result.add(new Subtopic(title, name + " as part of " + topic, name + " " + topic));
        }
        return result;
    }

    /** Required categories that no subtopic name or description mentions. */
    private static List<String> uncovered(List<String> mustCover, List<Subtopic> subtopics) {
        String text = subtopics.stream().map(s -> s.name() + " " + s.description())
                .collect(Collectors.joining(" ")).toLowerCase(Locale.ROOT);
        return mustCover.stream().filter(k -> !text.contains(k.toLowerCase(Locale.ROOT))).toList();
    }

    // ------------------------------------------------------------------ delegation

    private void delegate(String topic, String goal, List<Work> work) {
        try (ExecutorService pool = Executors.newFixedThreadPool(maxWorkers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (Work w : work) futures.add(pool.submit(() -> researchSubtopic(topic, goal, w)));
            for (Future<?> f : futures) f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private void researchSubtopic(String topic, String goal, Work work) {
        SubtopicResearch r = work.research();
        Subtopic s = r.subtopic;
        FollowUp f = work.followUp();

        // Web search subagent: ALL context is in its prompt (it has no memory of earlier rounds).
        WebSearchContext webContext = new WebSearchContext(topic, goal, s.name(), s.description(),
                f != null ? f.query() : s.searchQuery(),
                f != null ? r.findingsText() : null,
                f != null ? f.gap() : null);
        String web = invoke("web_search", s.name(), () -> Subagents.webSearch(llm, webContext));
        if (web == null) return;
        r.findings.add(new String[]{"web_search", web});

        // Dynamic selection: only call document analysis when there are relevant documents,
        // and only once - the documents do not change between rounds.
        List<Document> docs = Documents.find(s.name());
        if (!docs.isEmpty() && !r.documentsAnalysed) {
            DocumentAnalysisContext docContext = new DocumentAnalysisContext(topic, goal, s.name(), docs, web); // routed via the coordinator
            String analysis = invoke("document_analysis", s.name(), () -> Subagents.documentAnalysis(llm, docContext));
            if (analysis != null) {
                r.findings.add(new String[]{"document_analysis", analysis});
                r.documentsAnalysed = true;
            }
        }
    }

    /** Call one subagent with one uniform error policy: retry once, then record the failure. */
    private String invoke(String agent, String label, Supplier<String> call) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            route("coordinator", agent, label, "");
            try {
                String result = call.get();
                route(agent, "coordinator", label, result.length() + " chars");
                return result;
            } catch (RuntimeException e) { // consistent error handling lives in the hub
                LOG.warning(agent + " [" + label + "] failed (attempt " + attempt + "): " + e.getMessage());
            }
        }
        return null;
    }

    private void route(String from, String to, String subtopic, String note) {
        trace.add(new Route(from, to, subtopic));
        LOG.info(String.format("%-17s -> %-17s [%s] %s", from, to, subtopic, note));
    }

    // ------------------------------------------------------------------ evaluation

    /** Aggregate all findings, ask for a coverage assessment, return the follow-up work. */
    List<Work> evaluate(String topic, Map<String, SubtopicResearch> research) {
        String aggregated = research.values().stream()
                .map(r -> "## " + r.subtopic.name() + "\nScope: " + r.subtopic.description() + "\n"
                        + (r.findings.isEmpty() ? "(no findings)" : r.findingsText()))
                .collect(Collectors.joining("\n\n"));
        String prompt = "Research topic: " + topic + "\n\nFindings collected so far, by subtopic:\n\n" + aggregated + "\n\n"
                + "For EACH subtopic above, rate coverage: 'covered' (substantive facts on what it is, maturity, "
                + "costs or scale, and limitations), 'partial' (some of that is missing) or 'missing'. For "
                + "partial or missing subtopics, describe the gap and give a targeted follow-up search query. "
                + "Then list any important areas of the topic that NO subtopic covers at all (missing_areas); "
                + "leave it empty if the subtopics already cover the full breadth.";
        Evaluation e = parse(llm.complete(COORDINATOR_SYSTEM, prompt, EVALUATE_SCHEMA), Evaluation.class);

        List<Work> work = new ArrayList<>();
        for (Assessment a : e.assessments()) {
            SubtopicResearch r = research.get(key(a.subtopic()));
            if (r == null) continue;
            r.status = a.status();
            r.gap = a.gap();
            if (!r.status.equals("covered")) {
                String query = a.followUpQuery().isBlank() ? r.subtopic.searchQuery() : a.followUpQuery();
                work.add(new Work(r, new FollowUp(query, a.gap())));
            }
        }
        for (Subtopic area : e.missingAreas()) { // breadth gaps the decomposition missed
            if (research.containsKey(key(area.name())) || research.size() >= MAX_SUBTOPICS) continue;
            LOG.info("evaluation found an uncovered area: " + area.name());
            SubtopicResearch r = new SubtopicResearch(area);
            research.put(key(area.name()), r);
            work.add(new Work(r, null));
        }
        return work;
    }

    // ------------------------------------------------------------------ final report

    String writeReport(String topic, List<SubtopicResearch> research, int rounds) {
        String aggregated = research.stream()
                .map(r -> "## " + r.subtopic.name() + "\n" + r.findingsText())
                .collect(Collectors.joining("\n\n"));
        String prompt = "Write a research report on '" + topic + "' in Markdown, using ONLY the findings below. Start with "
                + "a 2-3 sentence overview, then one '## <subtopic>' section per subtopic in the order given, "
                + "then a short '## Conclusion'. Keep each section to one short paragraph plus a few bullets.\n\n"
                + aggregated;
        String body = llm.complete(COORDINATOR_SYSTEM, prompt);

        long covered = research.stream().filter(r -> r.status.equals("covered")).count();
        String table = research.stream()
                .map(r -> "| " + r.subtopic.name() + " | " + r.status + " | "
                        + String.join(", ", r.findings.stream().map(f -> f[0]).collect(Collectors.toCollection(TreeSet::new)))
                        + " |")
                .collect(Collectors.joining("\n"));
        return body + "\n\n---\n\n## Coverage evaluation\n\n"
                + "Coverage: " + covered + "/" + research.size() + " subtopics covered after " + rounds + " round(s).\n\n"
                + "| Subtopic | Status | Subagents used |\n|---|---|---|\n" + table + "\n";
    }

    // ------------------------------------------------------------------ helpers

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static List<Subtopic> unique(List<Subtopic> subtopics) {
        Map<String, Subtopic> seen = new LinkedHashMap<>();
        for (Subtopic s : subtopics) seen.putIfAbsent(key(s.name()), s);
        return List.copyOf(seen.values());
    }

    private static <T> T parse(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON from Claude: " + e.getMessage(), e);
        }
    }
}
