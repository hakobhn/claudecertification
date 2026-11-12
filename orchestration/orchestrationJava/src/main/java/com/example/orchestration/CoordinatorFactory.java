package com.example.orchestration;

/**
 * Wires the research system together. This is the one place that names the concrete
 * classes; everything else depends only on the interfaces.
 */
public final class CoordinatorFactory {

    private CoordinatorFactory() {
    }

    /** A coordinator with the default limits (5+ subtopics, 3 rounds, 6 parallel workers). */
    public static ResearchCoordinator create(Llm llm) {
        return create(llm, LlmTopicDecomposer.MIN_SUBTOPICS, Coordinator.MAX_ROUNDS,
                ParallelResearchDelegator.DEFAULT_WORKERS);
    }

    /**
     * A coordinator whose every agent (hub and spokes) talks to Claude through {@code llm}.
     *
     * @param minSubtopics fewest subtopics a decomposition may have
     * @param maxRounds    most delegation rounds (1 initial + refinements)
     * @param maxWorkers   subtopics researched in parallel
     */
    public static ResearchCoordinator create(Llm llm, int minSubtopics, int maxRounds, int maxWorkers) {
        MessageRouter router = new LoggingMessageRouter();
        ResearchDelegator delegator = new ParallelResearchDelegator(
                new LlmWebSearchAgent(llm),
                new LlmDocumentAnalysisAgent(llm),
                new InMemoryDocumentLibrary(),
                router,
                maxWorkers);
        return new Coordinator(
                new LlmTopicDecomposer(llm, minSubtopics),
                delegator,
                new LlmCoverageEvaluator(llm),
                new LlmReportWriter(llm),
                router,
                maxRounds);
    }
}
