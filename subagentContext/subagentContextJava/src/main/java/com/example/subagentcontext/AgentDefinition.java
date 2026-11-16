package com.example.subagentcontext;

import java.util.List;
import java.util.Map;

/**
 * Step 2: a subagent, described by the same three parts the Agent SDK uses.
 *
 * @param name         the subagent_type the coordinator passes to the Task tool
 * @param description  read by the coordinator to decide when to spawn this agent
 * @param systemPrompt the goal and quality criteria (not a step-by-step procedure)
 * @param tools        the only tools this agent may use - restricted to its role
 * @param outputSchema JSON Schema of the agent's final message
 */
public record AgentDefinition(String name, String description, String systemPrompt,
                              List<String> tools, Map<String, Object> outputSchema) {
}
