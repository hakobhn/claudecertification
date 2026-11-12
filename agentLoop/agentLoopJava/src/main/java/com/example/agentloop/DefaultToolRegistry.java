package com.example.agentloop;

import com.anthropic.models.messages.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link ToolRegistry} over a fixed list of {@link AgentTool}s, looked up by name. */
public final class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public DefaultToolRegistry(List<AgentTool> tools) {
        tools.forEach(t -> this.tools.put(t.name(), t));
    }

    /** The exercise's tool set: calculator + web search. */
    public static DefaultToolRegistry standard() {
        return new DefaultToolRegistry(List.of(new CalculatorTool(new Calculator()), new WebSearchTool()));
    }

    @Override
    public List<Tool> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    @Override
    public ToolResult execute(String name, Map<String, Object> input) {
        AgentTool tool = tools.get(name);
        if (tool == null) return ToolResult.error("Error: unknown tool '" + name + "'");
        try {
            return ToolResult.success(tool.execute(input));
        } catch (RuntimeException e) {
            return ToolResult.error("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
