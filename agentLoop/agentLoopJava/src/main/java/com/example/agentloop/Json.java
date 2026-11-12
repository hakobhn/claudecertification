package com.example.agentloop;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.Map;

/** JSON helpers shared by the tools and the loop's logging. Static utility, so no interface. */
final class Json {

    private Json() {
    }

    static String toJson(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {"type":"object","properties":{name:{"type":"string",...}},"required":[name],"additionalProperties":false} */
    static Tool.InputSchema singleStringSchema(String property, String description) {
        return Tool.InputSchema.builder()
                .properties(Tool.InputSchema.Properties.builder()
                        .putAdditionalProperty(property, JsonValue.from(Map.of(
                                "type", "string",
                                "description", description)))
                        .build())
                .addRequired(property)
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
    }
}
