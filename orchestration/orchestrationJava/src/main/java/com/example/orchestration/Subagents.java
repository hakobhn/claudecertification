package com.example.orchestration;

import com.example.orchestration.Documents.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The two spokes: a web search subagent and a document analysis subagent.
 *
 * <p><b>Isolation:</b> a subagent call is a fresh conversation. It does NOT get the
 * coordinator's system prompt, the coordinator's history, other subagents' results,
 * or anything from its own earlier invocations. Every fact it needs is written into
 * its prompt by the coordinator - see the {@code build*Prompt} methods.
 *
 * <p><b>Partitioning:</b> each subagent covers a different source type (public web
 * vs. internal documents), so no two subagents duplicate the same work.
 */
public final class Subagents {

    private Subagents() {
    }

    public static final String WEB_SEARCH_SYSTEM =
            "You are a web research subagent. You research ONE assigned subtopic using public sources: "
                    + "news, industry reports, government and academic publications. You have no live search tool, "
                    + "so answer from your own knowledge and say when a figure may be out of date. "
                    + "Stay strictly inside your assigned subtopic. Reply with 5-8 concise bullet points of concrete "
                    + "facts: how the technology works, maturity, typical costs or capacity figures, recent "
                    + "developments, and main limitations.";

    public static final String DOCUMENT_ANALYSIS_SYSTEM =
            "You are a document analysis subagent. You analyse ONLY the internal documents given to you "
                    + "for ONE assigned subtopic. Extract the key facts and figures, and say where the documents agree "
                    + "or disagree with the public findings you are given. Do not invent facts that are not in the "
                    + "documents. Reply with 3-6 concise bullet points.";

    /** Everything the web search subagent will know. priorFindings/gap are null on the first round. */
    public record WebSearchContext(String topic, String researchGoal, String subtopic, String description,
                                   String query, String priorFindings, String gap) {
    }

    /** Everything the document analysis subagent will know. */
    public record DocumentAnalysisContext(String topic, String researchGoal, String subtopic,
                                          List<Document> documents, String webFindings) {
    }

    public static String buildWebSearchPrompt(WebSearchContext c) {
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

    public static String buildDocumentAnalysisPrompt(DocumentAnalysisContext c) {
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

    public static String webSearch(Llm llm, WebSearchContext context) {
        return llm.complete(WEB_SEARCH_SYSTEM, buildWebSearchPrompt(context));
    }

    public static String documentAnalysis(Llm llm, DocumentAnalysisContext context) {
        return llm.complete(DOCUMENT_ANALYSIS_SYSTEM, buildDocumentAnalysisPrompt(context));
    }
}
