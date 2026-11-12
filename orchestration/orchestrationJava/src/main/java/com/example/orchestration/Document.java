package com.example.orchestration;

import java.util.List;

/** One internal document; {@code keywords} decide which subtopics it is relevant to. */
public record Document(String title, List<String> keywords, String text) {
}
