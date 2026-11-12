package com.example.orchestration;

/**
 * Spoke 1: researches one subtopic from public sources.
 *
 * <p>Isolated: each call is a fresh conversation. It knows only what is in its {@link Context}.
 */
public interface WebSearchAgent {

    /**
     * Researches one subtopic.
     *
     * @param context everything the subagent will know
     * @return the findings as concise bullet points
     */
    String research(Context context);

    /** Everything the web search subagent will know. {@code priorFindings}/{@code gap} are null on the first round. */
    record Context(String topic, String researchGoal, String subtopic, String description,
                   String query, String priorFindings, String gap) {
    }
}
