package com.example.orchestration;

import java.util.List;

/**
 * Step 2 of the coordinator's flow: hands subtopics to the subagents and stores what they find.
 *
 * <p>For each piece of work it calls the {@link WebSearchAgent}, then - only where the
 * {@link DocumentLibrary} has relevant documents - the {@link DocumentAnalysisAgent},
 * passing it the web findings. Subagents never talk to each other.
 */
public interface ResearchDelegator {

    /**
     * Researches every piece of work and appends the findings to its {@link SubtopicResearch}.
     *
     * <p>A subagent that fails is retried once, then skipped; the failure is logged, not thrown.
     *
     * @param topic the overall research topic (written into every subagent prompt)
     * @param goal  the research goal (written into every subagent prompt)
     * @param work  the subtopics to research this round
     */
    void delegate(String topic, String goal, List<Work> work);

    /** A targeted re-research request for a subtopic the evaluator found incomplete. */
    record FollowUp(String query, String gap) {
    }

    /** One subtopic to research; {@code followUp} is {@code null} on its first round. */
    record Work(SubtopicResearch research, FollowUp followUp) {
    }
}
