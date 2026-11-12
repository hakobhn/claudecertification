package com.example.agentloop;

import com.anthropic.models.messages.Tool;

import java.util.Map;

/**
 * One tool the agent can offer Claude: its definition plus the code that runs it.
 *
 * <p>Implementations: {@link CalculatorTool}, {@link WebSearchTool}. They are collected
 * in a {@link ToolRegistry}.
 */
public interface AgentTool {

    /** The definition sent to Claude: name, description and JSON Schema {@code input_schema}. */
    Tool definition();

    /** The tool name Claude uses in {@code tool_use} blocks. */
    default String name() {
        return definition().name();
    }

    /**
     * Runs the tool.
     *
     * @param input the {@code tool_use} input
     * @return the tool output as a JSON string
     * @throws RuntimeException on bad input or failure; the registry turns it into an error result
     */
    String execute(Map<String, Object> input);

    /** Reads a required string field from a tool input. */
    static String requireString(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (!(v instanceof String s)) throw new IllegalArgumentException("missing string field '" + key + "'");
        return s;
    }
}
