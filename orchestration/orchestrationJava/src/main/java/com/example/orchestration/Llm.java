package com.example.orchestration;

import java.util.Map;

/**
 * How every agent (coordinator and subagents) talks to Claude.
 *
 * <p>Each call is a brand-new, single-message conversation: nothing is remembered
 * between calls. That is the isolation the lesson describes - an agent only knows
 * what is in its prompt. Tests replace this with a fake.
 *
 * @param schema a JSON Schema for structured output, or {@code null} for free text
 */
@FunctionalInterface
public interface Llm {
    String complete(String system, String prompt, Map<String, Object> schema);

    default String complete(String system, String prompt) {
        return complete(system, prompt, null);
    }
}
