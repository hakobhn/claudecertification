package com.example.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Everything the coordinator holds about one subtopic. Only the coordinator sees all of these. */
public final class SubtopicResearch {

    public final Subtopic subtopic;
    public final List<String[]> findings = new ArrayList<>(); // {agent name, text}
    boolean documentsAnalysed;
    public String status = "missing";
    String gap = "";

    SubtopicResearch(Subtopic subtopic) {
        this.subtopic = subtopic;
    }

    /** All findings as text, each headed by the subagent that produced it. */
    String findingsText() {
        return findings.stream().map(f -> "[" + f[0] + "]\n" + f[1]).collect(Collectors.joining("\n\n"));
    }
}
