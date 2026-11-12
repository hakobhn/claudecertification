package com.example.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * {@link ResearchDelegator} that researches all work items in parallel on a fixed thread pool.
 *
 * <p>Covers two coordinator responsibilities from the lesson: dynamic subagent selection
 * (documents only where they exist) and one uniform error policy for every subagent call.
 */
public final class ParallelResearchDelegator implements ResearchDelegator {

    public static final int DEFAULT_WORKERS = 6;

    private static final Logger LOG = Logger.getLogger("coordinator");

    private final WebSearchAgent webSearch;
    private final DocumentAnalysisAgent documentAnalysis;
    private final DocumentLibrary documents;
    private final MessageRouter router;
    private final int maxWorkers;

    public ParallelResearchDelegator(WebSearchAgent webSearch, DocumentAnalysisAgent documentAnalysis,
                                     DocumentLibrary documents, MessageRouter router, int maxWorkers) {
        this.webSearch = webSearch;
        this.documentAnalysis = documentAnalysis;
        this.documents = documents;
        this.router = router;
        this.maxWorkers = maxWorkers;
    }

    @Override
    public void delegate(String topic, String goal, List<Work> work) {
        try (ExecutorService pool = Executors.newFixedThreadPool(maxWorkers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (Work w : work) futures.add(pool.submit(() -> researchSubtopic(topic, goal, w)));
            for (Future<?> f : futures) f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private void researchSubtopic(String topic, String goal, Work work) {
        SubtopicResearch r = work.research();
        Subtopic s = r.subtopic;
        FollowUp f = work.followUp();

        // Web search subagent: ALL context is in its prompt (it has no memory of earlier rounds).
        WebSearchAgent.Context webContext = new WebSearchAgent.Context(topic, goal, s.name(), s.description(),
                f != null ? f.query() : s.searchQuery(),
                f != null ? r.findingsText() : null,
                f != null ? f.gap() : null);
        String web = invoke("web_search", s.name(), () -> webSearch.research(webContext));
        if (web == null) return;
        r.findings.add(new String[]{"web_search", web});

        // Dynamic selection: only call document analysis when there are relevant documents,
        // and only once - the documents do not change between rounds.
        List<Document> docs = documents.find(s.name());
        if (!docs.isEmpty() && !r.documentsAnalysed) {
            DocumentAnalysisAgent.Context docContext =
                    new DocumentAnalysisAgent.Context(topic, goal, s.name(), docs, web); // routed via the coordinator
            String analysis = invoke("document_analysis", s.name(), () -> documentAnalysis.analyse(docContext));
            if (analysis != null) {
                r.findings.add(new String[]{"document_analysis", analysis});
                r.documentsAnalysed = true;
            }
        }
    }

    /** Call one subagent with one uniform error policy: retry once, then record the failure. */
    private String invoke(String agent, String label, Supplier<String> call) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            router.route("coordinator", agent, label, "");
            try {
                String result = call.get();
                router.route(agent, "coordinator", label, result.length() + " chars");
                return result;
            } catch (RuntimeException e) { // consistent error handling lives in the hub
                LOG.warning(agent + " [" + label + "] failed (attempt " + attempt + "): " + e.getMessage());
            }
        }
        return null;
    }
}
