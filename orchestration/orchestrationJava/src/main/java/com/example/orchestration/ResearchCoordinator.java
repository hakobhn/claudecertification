package com.example.orchestration;

import java.util.List;

/**
 * The hub of the hub-and-spoke research system: turns one topic into a report.
 *
 * <p>Flow of {@link #run(String, List)} (implemented by {@link Coordinator}):
 * <ol>
 *   <li>{@link TopicDecomposer} - split the topic into subtopics covering its full breadth</li>
 *   <li>{@link ResearchDelegator} - send every subtopic to the subagents, in parallel</li>
 *   <li>{@link CoverageEvaluator} - rate coverage; re-delegate only the gaps; repeat</li>
 *   <li>{@link ReportWriter} - write the final report from the findings</li>
 * </ol>
 * Every message between the hub and a subagent goes through one {@link MessageRouter}.
 * Build one with {@link CoordinatorFactory}.
 */
public interface ResearchCoordinator {

    /** Researches a topic with no required categories. */
    default ResearchReport run(String topic) {
        return run(topic, List.of());
    }

    /**
     * Researches a topic end to end.
     *
     * @param topic     the broad research topic
     * @param mustCover categories the requester requires in the report (scope requirements)
     * @return the report, with per-subtopic findings, coverage status and number of rounds
     */
    ResearchReport run(String topic, List<String> mustCover);

    /** Every message routed between the coordinator and its subagents, in order. */
    List<MessageRouter.Route> trace();
}
