package com.example.orchestration;

import com.example.orchestration.CoverageEvaluator.Assessment;
import com.example.orchestration.CoverageEvaluator.Evaluation;
import com.example.orchestration.ResearchDelegator.FollowUp;
import com.example.orchestration.ResearchDelegator.Work;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.example.orchestration.CoordinatorSupport.key;

/**
 * Build Exercise 1.2 - Hub-and-spoke research coordinator: the {@link ResearchCoordinator}
 * implementation. It only orchestrates; each step is done by a collaborator.
 *
 * <pre>
 *                  +----------------------+
 *                  |     COORDINATOR      |  decomposes, selects subagents,
 *                  |        (hub)         |  routes context, evaluates coverage,
 *                  +----------+-----------+  re-delegates gaps, writes the report
 *            +----------------+-----------------+
 *    +-------v--------+                 +-------v---------+
 *    | web_search     |                 | document_       |
 *    | subagent       |                 | analysis        |
 *    +----------------+                 +-----------------+
 *    Subagents never talk to each other. Everything goes through the coordinator.
 * </pre>
 *
 * The four coordinator responsibilities from the lesson, and where they are:
 * <ol>
 *   <li>Dynamic subagent selection   - {@link ResearchDelegator} (documents only where they exist)</li>
 *   <li>Research scope partitioning  - {@link TopicDecomposer} + one source type per subagent</li>
 *   <li>Iterative refinement loop    - {@link #run}: evaluate, re-delegate gaps, evaluate ...</li>
 *   <li>Centralised routing          - {@link MessageRouter} logs every message in one place</li>
 * </ol>
 */
public final class Coordinator implements ResearchCoordinator {

    public static final int MAX_ROUNDS = 3;      // 1 initial round + up to 2 refinement rounds
    public static final int MAX_SUBTOPICS = 12;  // guard against the evaluator adding areas forever

    static final Logger LOG = Logger.getLogger("coordinator");

    private final TopicDecomposer decomposer;
    private final ResearchDelegator delegator;
    private final CoverageEvaluator evaluator;
    private final ReportWriter reportWriter;
    private final MessageRouter router;
    private final int maxRounds;

    public Coordinator(TopicDecomposer decomposer, ResearchDelegator delegator, CoverageEvaluator evaluator,
                       ReportWriter reportWriter, MessageRouter router, int maxRounds) {
        this.decomposer = decomposer;
        this.delegator = delegator;
        this.evaluator = evaluator;
        this.reportWriter = reportWriter;
        this.router = router;
        this.maxRounds = maxRounds;
    }

    @Override
    public ResearchReport run(String topic, List<String> mustCover) {
        String goal = "Produce a broad, balanced overview of '" + topic + "' that covers every major category, "
                + "with concrete facts, maturity, costs and limitations for each.";

        // Step 1: decomposition covering the full breadth.
        Map<String, SubtopicResearch> research = new LinkedHashMap<>();
        for (Subtopic s : decomposer.decompose(topic, mustCover)) research.put(key(s.name()), new SubtopicResearch(s));

        // Step 2: first round of delegation (all subtopics in parallel).
        delegator.delegate(topic, goal, research.values().stream().map(r -> new Work(r, null)).toList());
        int rounds = 1;

        // Step 3: evaluate coverage, re-delegate gaps, repeat.
        while (true) {
            List<Work> work = applyEvaluation(evaluator.evaluate(topic, research.values()), research);
            LOG.info("round " + rounds + " coverage: " + research.values().stream()
                    .map(r -> r.subtopic.name() + "=" + r.status).collect(Collectors.joining(", ")));
            if (work.isEmpty()) break;
            if (rounds >= maxRounds) {
                LOG.warning("Stopping refinement after " + rounds + " rounds; " + work.size()
                        + " subtopic(s) still not fully covered.");
                break;
            }
            rounds++;
            LOG.info("round " + rounds + ": re-delegating " + work.size() + " gap(s): "
                    + work.stream().map(w -> w.research().subtopic.name()).collect(Collectors.joining(", ")));
            delegator.delegate(topic, goal, work);
        }

        // Step 4: the report.
        List<SubtopicResearch> all = List.copyOf(research.values());
        return new ResearchReport(topic, all, rounds, reportWriter.write(topic, all, rounds));
    }

    @Override
    public List<MessageRouter.Route> trace() {
        return router.trace();
    }

    /** Records each subtopic's status and returns the follow-up work: gaps plus newly found areas. */
    private List<Work> applyEvaluation(Evaluation e, Map<String, SubtopicResearch> research) {
        List<Work> work = new ArrayList<>();
        for (Assessment a : e.assessments()) {
            SubtopicResearch r = research.get(key(a.subtopic()));
            if (r == null) continue;
            r.status = a.status();
            r.gap = a.gap();
            if (!r.status.equals("covered")) {
                String query = a.followUpQuery().isBlank() ? r.subtopic.searchQuery() : a.followUpQuery();
                work.add(new Work(r, new FollowUp(query, a.gap())));
            }
        }
        for (Subtopic area : e.missingAreas()) { // breadth gaps the decomposition missed
            if (research.containsKey(key(area.name())) || research.size() >= MAX_SUBTOPICS) continue;
            LOG.info("evaluation found an uncovered area: " + area.name());
            SubtopicResearch r = new SubtopicResearch(area);
            research.put(key(area.name()), r);
            work.add(new Work(r, null));
        }
        return work;
    }
}
