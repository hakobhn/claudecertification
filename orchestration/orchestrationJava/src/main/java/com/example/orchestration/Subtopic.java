package com.example.orchestration;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One slice of the research topic, as produced by the {@link TopicDecomposer}. */
public record Subtopic(String name, String description, @JsonProperty("search_query") String searchQuery) {
}
