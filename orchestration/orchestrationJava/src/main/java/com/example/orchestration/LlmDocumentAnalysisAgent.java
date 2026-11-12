package com.example.orchestration;

import java.util.stream.Collectors;

/**
 * {@link DocumentAnalysisAgent} on Claude: one fresh conversation per call. The web findings
 * in its prompt were routed through the coordinator, never sent by the other subagent.
 */
public final class LlmDocumentAnalysisAgent implements DocumentAnalysisAgent {

    public static final String SYSTEM =
            "You are a document analysis subagent. You analyse ONLY the internal documents given to you "
                    + "for ONE assigned subtopic. Extract the key facts and figures, and say where the documents agree "
                    + "or disagree with the public findings you are given. Do not invent facts that are not in the "
                    + "documents. Reply with 3-6 concise bullet points.";

    private final Llm llm;

    public LlmDocumentAnalysisAgent(Llm llm) {
        this.llm = llm;
    }

    @Override
    public String analyse(Context context) {
        return llm.complete(SYSTEM, buildPrompt(context));
    }

    /** The full prompt: topic, goal, subtopic, the documents, and the web findings passed on by the coordinator. */
    public static String buildPrompt(Context c) {
        String docs = c.documents().stream()
                .map(d -> "### " + d.title() + "\n" + d.text())
                .collect(Collectors.joining("\n\n"));
        return String.join("\n\n",
                "Overall research topic: " + c.topic(),
                "Research goal: " + c.researchGoal(),
                "Your assigned subtopic: " + c.subtopic(),
                "Internal documents to analyse:\n" + docs,
                // Context from another subagent - routed THROUGH the coordinator, never directly.
                "Public findings from the web search subagent (passed on by the coordinator):\n" + c.webFindings());
    }
}
