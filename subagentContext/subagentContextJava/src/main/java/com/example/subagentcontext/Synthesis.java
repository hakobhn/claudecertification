package com.example.subagentcontext;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** The synthesis subagent's final message: the report plus every claim with its citation. */
public record Synthesis(String report, List<Claim> claims) {

    /** One factual claim in the report and the source it cites (a URL, or a document page). */
    public record Claim(String claim,
                        @JsonProperty("source_url") String sourceUrl,
                        @JsonProperty("document_name") String documentName,
                        @JsonProperty("page_number") Integer pageNumber) {
    }

    public static final Synthesis EMPTY = new Synthesis("", List.of());

    public static final Map<String, Object> SCHEMA = Json.object(Json.ordered(
            "report", Json.STRING,
            "claims", Map.of("type", "array", "items", Json.object(Json.ordered(
                    "claim", Json.STRING,
                    "source_url", Json.nullable("string"),
                    "document_name", Json.nullable("string"),
                    "page_number", Json.nullable("integer"))))));
}
