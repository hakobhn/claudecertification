package com.example.orchestration;

import java.util.List;

/**
 * Spoke 2: analyses internal documents for one subtopic.
 *
 * <p>Isolated: each call is a fresh conversation. The web findings in its {@link Context}
 * are passed on by the coordinator - never sent directly by the other subagent.
 */
public interface DocumentAnalysisAgent {

    /**
     * Analyses the given documents against the public findings.
     *
     * @param context everything the subagent will know
     * @return the key facts, and where the documents agree or disagree with the web findings
     */
    String analyse(Context context);

    /** Everything the document analysis subagent will know. */
    record Context(String topic, String researchGoal, String subtopic,
                   List<Document> documents, String webFindings) {
    }
}
