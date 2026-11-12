package com.example.agentloop;

/**
 * An agent that answers one user request, calling tools as often as it needs to.
 *
 * <p>Flow: {@link Main} builds an {@code Agent} and calls {@link #run} once. The
 * implementation ({@link AgentLoop}) talks to Claude through a {@link MessageSender}
 * and runs the tools Claude asks for through a {@link ToolRegistry}.
 */
public interface Agent {

    /**
     * Runs the request to completion and returns Claude's final answer.
     *
     * @param userInput the user's question or task
     * @return the final text of the {@code end_turn} response
     * @throws IllegalStateException if Claude stops for any reason other than finishing
     *                               (refusal, truncation, an unknown stop_reason)
     */
    String run(String userInput);
}
