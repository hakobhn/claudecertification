package com.example.orchestration;

import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.example.orchestration.CoordinatorSupport.COORDINATOR_SYSTEM;

/** {@link ReportWriter} that has Claude write the body and appends a coverage table itself. */
public final class LlmReportWriter implements ReportWriter {

    private final Llm llm;

    public LlmReportWriter(Llm llm) {
        this.llm = llm;
    }

    @Override
    public String write(String topic, List<SubtopicResearch> research, int rounds) {
        String aggregated = research.stream()
                .map(r -> "## " + r.subtopic.name() + "\n" + r.findingsText())
                .collect(Collectors.joining("\n\n"));
        String prompt = "Write a research report on '" + topic + "' in Markdown, using ONLY the findings below. Start with "
                + "a 2-3 sentence overview, then one '## <subtopic>' section per subtopic in the order given, "
                + "then a short '## Conclusion'. Keep each section to one short paragraph plus a few bullets.\n\n"
                + aggregated;
        String body = llm.complete(COORDINATOR_SYSTEM, prompt);

        long covered = research.stream().filter(r -> r.status.equals("covered")).count();
        String table = research.stream()
                .map(r -> "| " + r.subtopic.name() + " | " + r.status + " | "
                        + String.join(", ", r.findings.stream().map(f -> f[0]).collect(Collectors.toCollection(TreeSet::new)))
                        + " |")
                .collect(Collectors.joining("\n"));
        return body + "\n\n---\n\n## Coverage evaluation\n\n"
                + "Coverage: " + covered + "/" + research.size() + " subtopics covered after " + rounds + " round(s).\n\n"
                + "| Subtopic | Status | Subagents used |\n|---|---|---|\n" + table + "\n";
    }
}
