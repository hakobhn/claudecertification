package com.example.orchestration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared by the coordinator-side LLM steps (decompose, evaluate, report): the coordinator's
 * system prompt, JSON Schema builders and JSON parsing. Static utility, so no interface.
 */
public final class CoordinatorSupport {

    private CoordinatorSupport() {
    }

    public static final String COORDINATOR_SYSTEM =
            "You are the coordinator of a hub-and-spoke multi-agent research system. You never research "
                    + "subtopics yourself. Your jobs are: break a broad topic into subtopics that together cover its "
                    + "FULL breadth (including less obvious and emerging areas, not just the most popular ones); judge "
                    + "whether the findings your subagents returned cover each subtopic; and write the final report "
                    + "using only those findings.";

    // ------------------------------------------------------------------ JSON schemas

    static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", new ArrayList<>(properties.keySet()));
        m.put("additionalProperties", false);
        return m;
    }

    static Map<String, Object> arrayOf(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static final Map<String, Object> STRING = Map.of("type", "string");

    /** Schema of one {@link Subtopic}. */
    static final Map<String, Object> SUBTOPIC_SCHEMA = object(orderedMap(
            "name", STRING, "description", STRING, "search_query", STRING));

    // ------------------------------------------------------------------ JSON parsing + keys

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static <T> T parse(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON from Claude: " + e.getMessage(), e);
        }
    }

    /** Case-insensitive identity of a subtopic. */
    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
