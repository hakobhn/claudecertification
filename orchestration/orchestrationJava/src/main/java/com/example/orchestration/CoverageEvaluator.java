package com.example.orchestration;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collection;
import java.util.List;

/** Step 3 of the coordinator's flow: judges whether the findings cover each subtopic. */
public interface CoverageEvaluator {

    /**
     * Aggregates all findings and asks for a coverage assessment.
     *
     * @param topic    the overall research topic
     * @param research everything collected so far, one entry per subtopic
     * @return a status per subtopic plus any areas no subtopic covers
     */
    Evaluation evaluate(String topic, Collection<SubtopicResearch> research);

    /** Coverage of one subtopic: {@code covered}, {@code partial} or {@code missing}, with the gap to fill. */
    record Assessment(String subtopic, String status, String gap,
                      @JsonProperty("follow_up_query") String followUpQuery) {
    }

    /** The full assessment: one entry per subtopic, plus breadth gaps the decomposition missed. */
    record Evaluation(List<Assessment> assessments,
                      @JsonProperty("missing_areas") List<Subtopic> missingAreas) {
    }
}
