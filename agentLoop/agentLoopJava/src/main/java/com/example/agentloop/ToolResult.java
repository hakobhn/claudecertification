package com.example.agentloop;

/** What a tool returns: content for the {@code tool_result} block, and whether it failed. */
public record ToolResult(String content, boolean isError) {

    public static ToolResult success(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true);
    }
}
