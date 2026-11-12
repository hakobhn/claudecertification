package com.example.orchestration;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link WebSearchAgent} on Claude: one fresh conversation per call, with every fact it
 * needs written into the prompt. It does NOT get the coordinator's system prompt or history.
 */
public final class LlmWebSearchAgent implements WebSearchAgent {

    public static final String SYSTEM =
            "You are a web research subagent. You research ONE assigned subtopic using public sources: "
                    + "news, industry reports, government and academic publications. You have no live search tool, "
                    + "so answer from your own knowledge and say when a figure may be out of date. "
                    + "Stay strictly inside your assigned subtopic. Reply with 5-8 concise bullet points of concrete "
                    + "facts: how the technology works, maturity, typical costs or capacity figures, recent "
                    + "developments, and main limitations.";

    private final Llm llm;

    public LlmWebSearchAgent(Llm llm) {
        this.llm = llm;
    }

    @Override
    public String research(Context context) {
        return llm.complete(SYSTEM, buildPrompt(context));
    }

    /** The full prompt: topic, goal, subtopic, scope and query, plus prior findings and gap on refinement rounds. */
    public static String buildPrompt(Context c) {
        List<String> parts = new ArrayList<>(List.of(
                "Overall research topic: " + c.topic(),
                "Research goal: " + c.researchGoal(),
                "Your assigned subtopic: " + c.subtopic(),
                "Scope of this subtopic: " + c.description(),
                "Search focus: " + c.query()));
        if (c.priorFindings() != null && !c.priorFindings().isBlank()) {
            // Refinement round: the coordinator passes what is already known, because the
            // subagent has no memory of its earlier invocation.
            parts.add("Findings already collected for this subtopic (do not repeat them):\n" + c.priorFindings());
        }
        if (c.gap() != null && !c.gap().isBlank()) {
            parts.add("Gap the coordinator wants filled: " + c.gap());
        }
        return String.join("\n\n", parts);
    }
}
