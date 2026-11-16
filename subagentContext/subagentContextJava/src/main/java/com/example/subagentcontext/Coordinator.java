package com.example.subagentcontext;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Build Exercise 1.3 - Subagent invocation and context passing.
 *
 * <p>The six steps of the exercise, and where they are:
 * <ol>
 *   <li>Coordinator with {@code Task} in allowedTools, subagents in options.agents - {@link Options}</li>
 *   <li>Two research subagents, each an AgentDefinition with restricted tools - {@link Agents}</li>
 *   <li>A structured Finding: content separated from source metadata - {@link Finding}</li>
 *   <li>COMPLETE findings, metadata intact, passed to the synthesis subagent - {@link #spawn}</li>
 *   <li>Verify every claim in the report cites a real source - {@link #verifyAttribution}</li>
 *   <li>Independent subagents spawned in parallel (several Task calls, ONE turn) - {@link #runTasks}</li>
 * </ol>
 */
public final class Coordinator {

    static final Logger LOG = Logger.getLogger("coordinator");

    public static final String COORDINATOR_SYSTEM =
            "You coordinate a team of subagents and never research anything yourself. Use the Task tool to spawn "
                    + "them. Subagents whose work is independent must be spawned in the SAME response (several Task "
                    + "calls at once) so they run in parallel. Each subagent sees nothing but the prompt you write, so "
                    + "state the question, the goal and the quality criteria - not step-by-step instructions. When "
                    + "the research is back, spawn the synthesis subagent; the complete research findings are "
                    + "attached to its prompt for you. When synthesis is done, reply with one sentence saying the "
                    + "report is ready.";

    // ------------------------------------------------------------------ step 1: the coordinator's options

    /** Same shape as the Agent SDK's query() options: allowedTools + agents. */
    public record Options(List<String> allowedTools, Map<String, AgentDefinition> agents, String systemPrompt) {

        /** Task allowed (without it nothing can be spawned), all three subagents defined. */
        public static Options defaults() {
            return new Options(List.of("Task"), Agents.ALL, COORDINATOR_SYSTEM);
        }
    }

    /** The Task tool. Its description lists each subagent's description - that is how the coordinator picks one. */
    static Tool taskTool(Map<String, AgentDefinition> agents) {
        String available = agents.values().stream()
                .map(d -> "- " + d.name() + ": " + d.description()).collect(Collectors.joining("\n"));
        return Agents.tool("Task",
                "Spawn a subagent to do part of the work. It starts with an empty context: it sees ONLY the "
                        + "prompt you write. Available subagents:\n" + available,
                Json.ordered(
                        "subagent_type", Map.of("type", "string", "enum", new ArrayList<>(agents.keySet())),
                        "description", Map.of("type", "string", "description", "3-5 word label for the task"),
                        "prompt", Map.of("type", "string",
                                "description", "The question, the goal and the quality criteria for this subagent.")));
    }

    /**
     * @param unattributed step 5: claims whose source is not among the findings
     * @param spawnBatches step 6: which subagents each coordinator response spawned
     */
    public record Result(String report, List<Synthesis.Claim> claims, List<Finding> findings,
                         List<Synthesis.Claim> unattributed, List<List<String>> spawnBatches) {
    }

    private final MessageSender sender;
    private final Options options;
    private final boolean stripMetadata; // true reproduces the exam's bug: metadata dropped on the way

    private final List<Finding> findings = Collections.synchronizedList(new ArrayList<>());
    private final List<List<String>> spawnBatches = new ArrayList<>();
    private volatile Synthesis synthesis;

    public Coordinator(MessageSender sender, Options options, boolean stripMetadata) {
        this.sender = sender;
        this.options = options;
        this.stripMetadata = stripMetadata;
    }

    public Result run(String question) {
        findings.clear();
        spawnBatches.clear();
        synthesis = null;

        // Step 1: the coordinator can spawn subagents only if Task is in its allowed tools.
        List<Tool> tools = options.allowedTools().contains("Task") ? List.of(taskTool(options.agents())) : List.of();
        if (tools.isEmpty()) LOG.warning("'Task' is not in allowedTools: the coordinator cannot spawn any subagent");
        Agents.runAgent(sender, "coordinator", options.systemPrompt(), question, tools, this::runTasks, null);

        Synthesis s = synthesis != null ? synthesis : Synthesis.EMPTY;
        List<Finding> all = List.copyOf(findings);
        return new Result(s.report(), s.claims(), all, verifyAttribution(s.claims(), all), List.copyOf(spawnBatches));
    }

    // ------------------------------------------------------------------ step 6: parallel spawning

    /** All Task calls from ONE coordinator response run at the same time; it waits for all of them. */
    private List<ContentBlockParam> runTasks(List<ToolUseBlock> toolUses) {
        List<String> batch = toolUses.stream().map(u -> String.valueOf(Agents.input(u).get("subagent_type"))).toList();
        spawnBatches.add(batch);
        LOG.info("spawning in parallel: " + String.join(", ", batch));

        try (ExecutorService pool = Executors.newFixedThreadPool(toolUses.size())) {
            List<Future<ContentBlockParam>> futures = new ArrayList<>();
            for (ToolUseBlock use : toolUses) futures.add(pool.submit(() -> spawn(use)));
            List<ContentBlockParam> results = new ArrayList<>();
            for (Future<ContentBlockParam> f : futures) results.add(f.get());
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    // ------------------------------------------------------------------ invocation + step 4: context passing

    private ContentBlockParam spawn(ToolUseBlock use) {
        Map<String, Object> input = Agents.input(use);
        AgentDefinition definition = options.agents().get(String.valueOf(input.get("subagent_type")));
        if (!use.name().equals("Task") || definition == null) {
            return Agents.toolResult(use.id(), "Error: cannot run " + use.name() + "(" + input + ")", true);
        }

        // Isolation: the subagent starts from this prompt only - no coordinator history, no other results.
        String prompt = (String) input.get("prompt");
        if (definition == Agents.SYNTHESIS) {
            prompt += "\n\nFindings from the research subagents (complete, with source metadata):\n"
                    + Json.write(findingsToPass());
        }

        String output;
        try {
            List<Tool> tools = definition.tools().stream().map(Agents.TOOL_DEFINITIONS::get).toList();
            output = Agents.runAgent(sender, definition.name(), definition.systemPrompt(), prompt, tools,
                    Agents.restrictedToolRunner(definition), definition.outputSchema());
            if (definition == Agents.SYNTHESIS) {
                synthesis = Json.read(output, Synthesis.class);
            } else {
                findings.addAll(Json.read(output, Finding.FindingList.class).findings());
            }
        } catch (RuntimeException e) {
            LOG.warning(definition.name() + " failed: " + e.getMessage());
            return Agents.toolResult(use.id(), "Error: " + definition.name() + " failed: " + e.getMessage(), true);
        }
        return Agents.toolResult(use.id(), output, false); // only the subagent's final message goes back
    }

    private List<?> findingsToPass() {
        synchronized (findings) {
            if (stripMetadata) return findings.stream().map(Finding::contentOnly).toList(); // the bug
            return List.copyOf(findings);
        }
    }

    // ------------------------------------------------------------------ step 5: verify attribution

    /** Returns the claims that do NOT cite a URL, or a document page, that some finding came from. */
    static List<Synthesis.Claim> verifyAttribution(List<Synthesis.Claim> claims, List<Finding> findings) {
        Set<String> urls = findings.stream().map(Finding::sourceUrl).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> pages = findings.stream().filter(f -> f.documentName() != null)
                .map(f -> f.documentName() + "#" + f.pageNumber()).collect(Collectors.toSet());
        return claims.stream()
                .filter(c -> !(c.sourceUrl() != null && urls.contains(c.sourceUrl()))
                        && !(c.documentName() != null && pages.contains(c.documentName() + "#" + c.pageNumber())))
                .toList();
    }
}
