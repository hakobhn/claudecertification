package com.example.subagentcontext;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 3: one research result. Content is kept apart from the metadata that says where it came from.
 *
 * @param claim        content: the fact
 * @param analysis     content: what it means
 * @param sourceUrl    metadata: web source (web_research), else null
 * @param sourceTitle  metadata: title of that web source, else null
 * @param documentName metadata: internal document (document_analysis), else null
 * @param pageNumber   metadata: page in that document, else null
 * @param confidence   metadata: high / medium / low
 * @param retrievedBy  metadata: which subagent found it
 */
public record Finding(
        String claim,
        String analysis,
        @JsonProperty("source_url") String sourceUrl,
        @JsonProperty("source_title") String sourceTitle,
        @JsonProperty("document_name") String documentName,
        @JsonProperty("page_number") Integer pageNumber,
        String confidence,
        @JsonProperty("retrieved_by") String retrievedBy) {

    /** What is left when metadata gets stripped - the bug the exam asks about. */
    public Map<String, Object> contentOnly() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claim", claim);
        m.put("analysis", analysis);
        return m;
    }

    /** The same Finding as a JSON Schema, so each research subagent's final message is a list of Findings. */
    public static final Map<String, Object> SCHEMA = Json.object(Json.ordered(
            "claim", Json.STRING,
            "analysis", Json.STRING,
            "source_url", Json.nullable("string"),
            "source_title", Json.nullable("string"),
            "document_name", Json.nullable("string"),
            "page_number", Json.nullable("integer"),
            "confidence", Map.of("type", "string", "enum", List.of("high", "medium", "low")),
            "retrieved_by", Json.STRING));

    /** {"findings": [Finding, ...]} - the output format of both research subagents. */
    public static final Map<String, Object> LIST_SCHEMA = Json.object(Json.ordered(
            "findings", Map.of("type", "array", "items", SCHEMA)));

    /** The JSON shape of a research subagent's final message. */
    public record FindingList(List<Finding> findings) {
    }
}
