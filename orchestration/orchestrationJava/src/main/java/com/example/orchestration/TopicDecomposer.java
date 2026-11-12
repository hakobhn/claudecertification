package com.example.orchestration;

import java.util.List;

/** Step 1 of the coordinator's flow: research scope partitioning. */
public interface TopicDecomposer {

    /**
     * Breaks a topic into non-overlapping subtopics that together cover its full breadth.
     *
     * <p>A result that is too narrow (too few subtopics, or a required category missing)
     * is sent back to Claude and asked again; as a last resort the missing required
     * categories are added directly.
     *
     * @param topic     the broad research topic
     * @param mustCover categories that must appear in some subtopic name or description
     * @return the subtopics, each with a scope description and a search query
     * @throws IllegalStateException if Claude never returns enough subtopics
     */
    List<Subtopic> decompose(String topic, List<String> mustCover);
}
