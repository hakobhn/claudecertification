package com.example.orchestration;

import java.util.List;

/** The coordinator's output: the Markdown report plus the research behind it. */
public record ResearchReport(String topic, List<SubtopicResearch> research, int rounds, String markdown) {

    /** Share of subtopics rated {@code covered}, from 0 to 1. */
    public double coverage() {
        long covered = research.stream().filter(r -> r.status.equals("covered")).count();
        return research.isEmpty() ? 0 : (double) covered / research.size();
    }
}
