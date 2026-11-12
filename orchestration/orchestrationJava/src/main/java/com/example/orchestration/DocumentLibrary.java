package com.example.orchestration;

import java.util.List;

/** The internal documents available to the document analysis subagent. */
public interface DocumentLibrary {

    /**
     * Finds the documents relevant to a subtopic.
     *
     * <p>An empty result means the document analysis subagent is not called for this
     * subtopic ("dynamic subagent selection").
     *
     * @param subtopicName the subtopic name (not its description)
     * @return matching documents, possibly empty
     */
    List<Document> find(String subtopicName);
}
