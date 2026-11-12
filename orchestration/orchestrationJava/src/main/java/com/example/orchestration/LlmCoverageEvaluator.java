package com.example.orchestration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.example.orchestration.CoordinatorSupport.COORDINATOR_SYSTEM;
import static com.example.orchestration.CoordinatorSupport.STRING;
import static com.example.orchestration.CoordinatorSupport.SUBTOPIC_SCHEMA;
import static com.example.orchestration.CoordinatorSupport.arrayOf;
import static com.example.orchestration.CoordinatorSupport.object;
import static com.example.orchestration.CoordinatorSupport.orderedMap;
import static com.example.orchestration.CoordinatorSupport.parse;

/** {@link CoverageEvaluator} that sends all findings to Claude and gets a structured JSON assessment back. */
public final class LlmCoverageEvaluator implements CoverageEvaluator {

    public static final Map<String, Object> SCHEMA = object(orderedMap(
            "assessments", arrayOf(object(orderedMap(
                    "subtopic", STRING,
                    "status", Map.of("type", "string", "enum", List.of("covered", "partial", "missing")),
                    "gap", STRING,
                    "follow_up_query", STRING))),
            "missing_areas", arrayOf(SUBTOPIC_SCHEMA)));

    private final Llm llm;

    public LlmCoverageEvaluator(Llm llm) {
        this.llm = llm;
    }

    @Override
    public Evaluation evaluate(String topic, Collection<SubtopicResearch> research) {
        String aggregated = research.stream()
                .map(r -> "## " + r.subtopic.name() + "\nScope: " + r.subtopic.description() + "\n"
                        + (r.findings.isEmpty() ? "(no findings)" : r.findingsText()))
                .collect(Collectors.joining("\n\n"));
        String prompt = "Research topic: " + topic + "\n\nFindings collected so far, by subtopic:\n\n" + aggregated + "\n\n"
                + "For EACH subtopic above, rate coverage: 'covered' (substantive facts on what it is, maturity, "
                + "costs or scale, and limitations), 'partial' (some of that is missing) or 'missing'. For "
                + "partial or missing subtopics, describe the gap and give a targeted follow-up search query. "
                + "Then list any important areas of the topic that NO subtopic covers at all (missing_areas); "
                + "leave it empty if the subtopics already cover the full breadth.";
        return parse(llm.complete(COORDINATOR_SYSTEM, prompt, SCHEMA), Evaluation.class);
    }
}
