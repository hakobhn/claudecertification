package com.example.subagentcontext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON and JSON Schema helpers. */
final class Json {

    private Json() {
    }

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    static final Map<String, Object> STRING = Map.of("type", "string");

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON from Claude: " + e.getOriginalMessage(), e);
        }
    }

    /** {"type":"object", properties, every property required, no extra properties}. */
    static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("properties", properties);
        m.put("required", new ArrayList<>(properties.keySet()));
        m.put("additionalProperties", false);
        return m;
    }

    static Map<String, Object> nullable(String type) {
        return Map.of("anyOf", List.of(Map.of("type", type), Map.of("type", "null")));
    }

    static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }
}
