package com.example.agentloop;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Step 1 of the exercise: two tools with a JSON Schema {@code input_schema},
 * plus the code that actually runs them. Claude never runs a tool itself -
 * it only asks for a call; {@link #execute} is where your code does the work.
 */
public final class Tools {

    private Tools() {
    }

    /** What a tool returns: content for the tool_result block, and whether it failed. */
    public record Result(String content, boolean isError) {
    }

    // ------------------------------------------------------------------ definitions

    public static final Tool CALCULATOR = Tool.builder()
            .name("calculator")
            .description("Evaluate an arithmetic expression and return the numeric result. "
                    + "Supports + - * / // % ** and parentheses. Use this for any math "
                    + "instead of computing in your head.")
            .inputSchema(schema("expression", "Arithmetic expression, e.g. '(8849 - 6190) * 2'"))
            .strict(true)
            .build();

    public static final Tool WEB_SEARCH = Tool.builder()
            .name("web_search")
            .description("Search the web for facts and figures (populations, heights, prices, "
                    + "dates). Returns a short list of result snippets. Use this whenever "
                    + "you need a value you don't already have in the conversation.")
            .inputSchema(schema("query", "The search query"))
            .strict(true)
            .build();

    public static final List<Tool> ALL = List.of(CALCULATOR, WEB_SEARCH);

    /** {"type":"object","properties":{name:{"type":"string",...}},"required":[name],"additionalProperties":false} */
    private static Tool.InputSchema schema(String property, String description) {
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

    // ------------------------------------------------------------------ execution

    /** Run a tool by name. Errors are returned (is_error=true) so Claude can recover, never thrown. */
    public static Result execute(String name, Map<String, Object> input) {
        try {
            return switch (name) {
                case "calculator" -> new Result(calculator(string(input, "expression")), false);
                case "web_search" -> new Result(webSearch(string(input, "query")), false);
                default -> new Result("Error: unknown tool '" + name + "'", true);
            };
        } catch (RuntimeException e) {
            return new Result("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    static String calculator(String expression) {
        double value = Calculator.evaluate(expression);
        Object result = (value == Math.rint(value) && !Double.isInfinite(value)) ? (Object) (long) value : value;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expression", expression);
        out.put("result", result);
        return toJson(out);
    }

    private static final Map<String, String> MOCK_INDEX = new LinkedHashMap<>();

    static {
        MOCK_INDEX.put("everest", "Mount Everest is 8,849 metres tall (2020 survey).");
        MOCK_INDEX.put("kilimanjaro", "Mount Kilimanjaro is 5,895 metres tall.");
        MOCK_INDEX.put("denali", "Denali is 6,190 metres tall.");
        MOCK_INDEX.put("tokyo", "The population of Tokyo (city proper) is about 14,000,000.");
        MOCK_INDEX.put("paris", "The population of Paris (city proper) is about 2,100,000.");
        MOCK_INDEX.put("speed of light", "The speed of light in vacuum is 299,792 km/s.");
    }

    /** Web search stub: returns mock results from a tiny in-memory index. */
    static String webSearch(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Map<String, String>> hits = new ArrayList<>();
        MOCK_INDEX.forEach((key, text) -> {
            if (q.contains(key)) {
                String title = Character.toUpperCase(key.charAt(0)) + key.substring(1);
                hits.add(Map.of("title", title, "snippet", text,
                        "url", "https://example.com/" + key.replace(' ', '-')));
            }
        });
        if (hits.isEmpty()) {
            hits.add(Map.of("title", "No results", "snippet", "No mock results for '" + query + "'.", "url", ""));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        out.put("results", hits);
        return toJson(out);
    }

    private static String string(Map<String, Object> input, String key) {
        Object v = input.get(key);
        if (!(v instanceof String s)) throw new IllegalArgumentException("missing string field '" + key + "'");
        return s;
    }

    static String toJson(Object value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
