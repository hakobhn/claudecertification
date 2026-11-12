package com.example.agentloop;

import com.anthropic.models.messages.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The {@code web_search} tool. A stub: it returns mock results from a tiny in-memory index. */
public final class WebSearchTool implements AgentTool {

    private static final Tool DEFINITION = Tool.builder()
            .name("web_search")
            .description("Search the web for facts and figures (populations, heights, prices, "
                    + "dates). Returns a short list of result snippets. Use this whenever "
                    + "you need a value you don't already have in the conversation.")
            .inputSchema(Json.singleStringSchema("query", "The search query"))
            .strict(true)
            .build();

    private static final Map<String, String> MOCK_INDEX = new LinkedHashMap<>();

    static {
        MOCK_INDEX.put("everest", "Mount Everest is 8,849 metres tall (2020 survey).");
        MOCK_INDEX.put("kilimanjaro", "Mount Kilimanjaro is 5,895 metres tall.");
        MOCK_INDEX.put("denali", "Denali is 6,190 metres tall.");
        MOCK_INDEX.put("tokyo", "The population of Tokyo (city proper) is about 14,000,000.");
        MOCK_INDEX.put("paris", "The population of Paris (city proper) is about 2,100,000.");
        MOCK_INDEX.put("speed of light", "The speed of light in vacuum is 299,792 km/s.");
    }

    @Override
    public Tool definition() {
        return DEFINITION;
    }

    /** Returns {@code {"query": ..., "results": [{title, snippet, url}, ...]}}. */
    @Override
    public String execute(Map<String, Object> input) {
        String query = AgentTool.requireString(input, "query");
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
        return Json.toJson(out);
    }
}
