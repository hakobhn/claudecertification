package com.example.orchestration;

import java.util.List;

/** Step 4 of the coordinator's flow: writes the final report. */
public interface ReportWriter {

    /**
     * Writes a Markdown report from the findings only, followed by a coverage table.
     *
     * @param topic    the overall research topic
     * @param research the findings and coverage status of every subtopic
     * @param rounds   how many delegation rounds were run
     * @return the report as Markdown
     */
    String write(String topic, List<SubtopicResearch> research, int rounds);
}
