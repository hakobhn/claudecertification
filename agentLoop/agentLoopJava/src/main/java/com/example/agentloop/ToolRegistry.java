package com.example.agentloop;

import com.anthropic.models.messages.Tool;

import java.util.List;
import java.util.Map;

/**
 * The set of tools the agent offers Claude, and the place where they actually run.
 *
 * <p>Claude never runs a tool itself - it only asks for a call. The agent loop sends
 * {@link #definitions()} with every request and passes each {@code tool_use} block
 * to {@link #execute}.
 */
public interface ToolRegistry {

    /** Tool definitions (name, description, JSON Schema) to send to Claude on every request. */
    List<Tool> definitions();

    /**
     * Runs the named tool.
     *
     * <p>Failures are returned as a result with {@code isError=true} (so Claude can recover),
     * never thrown.
     *
     * @param name  the tool name from the {@code tool_use} block
     * @param input the tool input from the {@code tool_use} block
     * @return the content for the {@code tool_result} block
     */
    ToolResult execute(String name, Map<String, Object> input);
}
