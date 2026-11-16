package com.example.subagentcontext;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * The three subagents (step 2), their tools, and the agent loop every agent runs (lesson 1.1).
 *
 * <p>Mock data: the web index and the internal documents below are made up for the exercise.
 */
public final class Agents {

    private Agents() {
    }

    public static final String MODEL = "claude-opus-5-5";
    public static final long MAX_TOKENS = 16_000L;
    public static final int MAX_ITERATIONS = 20; // safety cap per agent - NOT the stopping mechanism

    static final Logger LOG = Logger.getLogger("agents");

    // ------------------------------------------------------------------ step 2: agent definitions

    /**
     * Every finding must carry these fields. Only the subagent's FINAL message reaches the coordinator -
     * its tool calls and results do not - so the prompt must say which fields to return.
     */
    private static final String RETURN_FIELDS = "For EVERY finding return: claim, analysis, source_url, source_title, "
            + "document_name, page_number, confidence (high/medium/low) and retrieved_by. A finding without its "
            + "source is useless downstream.";

    public static final AgentDefinition WEB_RESEARCH = new AgentDefinition(
            "web_research",
            "Researches public web sources. Returns findings with the source URL and title of each.",
            "You are a web research subagent. Goal: find concrete, current facts on the question you are given, "
                    + "from public sources only. Quality bar: every finding is backed by one search result and cites "
                    + "that result's exact url and title; never invent a source. Set document_name and page_number "
                    + "to null and retrieved_by to 'web_research'. " + RETURN_FIELDS,
            List.of("web_search"),
            Finding.LIST_SCHEMA);

    public static final AgentDefinition DOCUMENT_ANALYSIS = new AgentDefinition(
            "document_analysis",
            "Analyses the internal documents. Returns findings with the document name and page number.",
            "You are a document analysis subagent. Goal: extract the facts in the internal documents that answer "
                    + "the question you are given. Quality bar: every finding cites the exact document_name and "
                    + "page_number it came from; do not add facts that are not in the documents. Set source_url and "
                    + "source_title to null and retrieved_by to 'document_analysis'. " + RETURN_FIELDS,
            List.of("list_documents", "read_document"),
            Finding.LIST_SCHEMA);

    public static final AgentDefinition SYNTHESIS = new AgentDefinition(
            "synthesis",
            "Writes the final report from the findings of the research subagents. Has no tools.",
            "You are a synthesis subagent. Goal: write a short, balanced report that answers the question using "
                    + "ONLY the findings you are given. Quality bar: every factual claim cites its source - the "
                    + "finding's source_url, or its document_name and page_number - copied exactly. If two findings "
                    + "disagree, say so. Return the report text plus the list of claims with their citations.",
            List.of(),
            Synthesis.SCHEMA);

    public static final Map<String, AgentDefinition> ALL = byName(WEB_RESEARCH, DOCUMENT_ANALYSIS, SYNTHESIS);

    private static Map<String, AgentDefinition> byName(AgentDefinition... definitions) {
        Map<String, AgentDefinition> m = new LinkedHashMap<>();
        for (AgentDefinition d : definitions) m.put(d.name(), d);
        return m;
    }

    // ------------------------------------------------------------------ mock sources

    record WebPage(String url, String title, List<String> keywords, String snippet) {
    }

    static final List<WebPage> WEB_INDEX = List.of(
            new WebPage("https://example.org/offshore-wind-outlook-2025", "Offshore Wind Outlook 2025",
                    List.of("capacity", "growth", "outlook", "offshore", "wind"),
                    "Global offshore wind capacity reached about 83 GW at the end of 2024, with China and Europe "
                            + "accounting for most new installations."),
            new WebPage("https://example.org/offshore-wind-lcoe", "The falling cost of offshore wind",
                    List.of("cost", "lcoe", "price", "auction", "trend"),
                    "The levelised cost of fixed-bottom offshore wind fell roughly 60% between 2012 and 2023, but "
                            + "auction prices rose again in 2023-2024 as interest rates and turbine prices increased."),
            new WebPage("https://example.org/floating-wind-pilots", "Floating offshore wind: pilots to projects",
                    List.of("floating", "deep", "pilot", "risk"),
                    "Floating foundations open deep-water sites, but floating projects still cost about twice as "
                            + "much per MWh as fixed-bottom ones."),
            new WebPage("https://example.org/offshore-supply-chain", "Supply-chain strain in offshore wind",
                    List.of("supply", "vessel", "risk", "turbine", "operational"),
                    "Shortages of installation vessels and large turbine components delayed several 2024 projects."));

    static final Map<String, Map<Integer, String>> DOCUMENTS = new LinkedHashMap<>();

    static {
        Map<Integer, String> maintenance = new LinkedHashMap<>();
        maintenance.put(1, "Turbine availability across the North Sea cluster was 96.1% this year.");
        maintenance.put(2, "Gearbox failures caused 40% of downtime; the 15 MW direct-drive units had none.");
        maintenance.put(3, "Jack-up vessel day rates rose 35%, which makes operations and maintenance the largest "
                + "controllable cost after financing.");
        DOCUMENTS.put("Offshore Wind Maintenance Report", maintenance);

        Map<Integer, String> memo = new LinkedHashMap<>();
        memo.put(1, "Financing costs now make up about half of lifetime cost for new offshore sites.");
        memo.put(2, "The board approved a 1.2 GW extension, subject to a contract-for-difference strike price "
                + "above 80 EUR/MWh.");
        DOCUMENTS.put("North Sea Cluster Investment Memo", memo);
    }

    // ------------------------------------------------------------------ tools

    /** Mock search: every result carries its URL and title, so the agent can cite them. */
    static String webSearch(String query) {
        Set<String> words = Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(w -> w.replaceAll("[?,.]", "")).collect(Collectors.toSet());
        List<Map<String, String>> hits = WEB_INDEX.stream()
                .filter(p -> p.keywords().stream().anyMatch(words::contains))
                .map(p -> Map.of("url", p.url(), "title", p.title(), "snippet", p.snippet()))
                .toList();
        return Json.write(Json.ordered("query", query, "results", hits));
    }

    static String listDocuments() {
        return Json.write(DOCUMENTS.entrySet().stream()
                .map(e -> Json.ordered("document_name", e.getKey(), "pages", e.getValue().size())).toList());
    }

    /** Returns every page with its page number, so the agent can cite pages. */
    static String readDocument(String documentName) {
        Map<Integer, String> pages = DOCUMENTS.get(documentName);
        if (pages == null) throw new IllegalArgumentException("no document named '" + documentName + "'");
        List<Map<String, Object>> out = pages.entrySet().stream()
                .map(e -> Json.ordered("page_number", e.getKey(), "text", e.getValue())).toList();
        return Json.write(Json.ordered("document_name", documentName, "pages", out));
    }

    static final Map<String, Tool> TOOL_DEFINITIONS = Map.of(
            "web_search", tool("web_search", "Search public web sources. Returns results with url, title and snippet.",
                    Json.ordered("query", Json.STRING)),
            "list_documents", tool("list_documents", "List the internal documents and how many pages each has.",
                    Json.ordered()),
            "read_document", tool("read_document", "Read an internal document. Returns every page with its page_number.",
                    Json.ordered("document_name", Json.STRING)));

    private static String executeTool(String name, Map<String, Object> input) {
        return switch (name) {
            case "web_search" -> webSearch((String) input.get("query"));
            case "list_documents" -> listDocuments();
            case "read_document" -> readDocument((String) input.get("document_name"));
            default -> throw new IllegalArgumentException("unknown tool '" + name + "'");
        };
    }

    /** A tool whose properties are all required and which accepts nothing else. */
    static Tool tool(String name, String description, Map<String, Object> properties) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(props.build())
                        .required(new ArrayList<>(properties.keySet()))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    /** Runs only the tools this agent is allowed; anything else comes back as an error result. */
    static Function<List<ToolUseBlock>, List<ContentBlockParam>> restrictedToolRunner(AgentDefinition definition) {
        return toolUses -> toolUses.stream().map(use -> {
            if (!definition.tools().contains(use.name())) {
                return toolResult(use.id(), "Error: tool '" + use.name() + "' is not allowed for " + definition.name(), true);
            }
            try {
                return toolResult(use.id(), executeTool(use.name(), input(use)), false);
            } catch (RuntimeException e) { // errors go back to Claude, never crash the loop
                return toolResult(use.id(), "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), true);
            }
        }).toList();
    }

    // ------------------------------------------------------------------ the agent loop (lesson 1.1)

    /** One agent, one fresh conversation. stop_reason alone decides when it is done. */
    static String runAgent(MessageSender sender, String label, String system, String prompt, List<Tool> tools,
                           Function<List<ToolUseBlock>, List<ContentBlockParam>> runTools,
                           Map<String, Object> outputSchema) {
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.ofUser(prompt)); // nothing inherited: only this prompt

        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) { // safety cap only
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(MAX_TOKENS)
                    .system(system)
                    .messages(List.copyOf(messages));
            tools.forEach(params::addTool);
            if (outputSchema != null) {
                JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
                outputSchema.forEach((k, v) -> schema.putAdditionalProperty(k, JsonValue.from(v)));
                params.outputConfig(OutputConfig.builder()
                        .format(JsonOutputFormat.builder().schema(schema.build()).build())
                        .build());
            }
            Message response = sender.send(params.build());
            StopReason stop = response.stopReason().orElseThrow(() -> new IllegalStateException("no stop_reason"));
            LOG.info(String.format("%-17s iteration %d: stop_reason=%s", label, iteration, stop.asString()));

            if (stop.equals(StopReason.END_TURN)) {
                return response.content().stream()
                        .flatMap(b -> b.text().stream()).map(TextBlock::text).collect(Collectors.joining());
            }
            if (stop.equals(StopReason.TOOL_USE)) {
                messages.add(response.toParam());
                List<ToolUseBlock> toolUses = response.content().stream()
                        .filter(ContentBlock::isToolUse).map(ContentBlock::asToolUse).toList();
                messages.add(MessageParam.ofUserOfContentBlockParams(runTools.apply(toolUses))); // ONE message
                continue;
            }
            throw new IllegalStateException(label + " did not finish (stop_reason=" + stop.asString() + ")");
        }
        throw new IllegalStateException(label + " stopped: safety iteration cap reached");
    }

    static Map<String, Object> input(ToolUseBlock use) {
        return use._input().convert(new TypeReference<Map<String, Object>>() {});
    }

    static ContentBlockParam toolResult(String toolUseId, String content, boolean isError) {
        ToolResultBlockParam.Builder b = ToolResultBlockParam.builder().toolUseId(toolUseId).content(content);
        if (isError) b.isError(true);
        return ContentBlockParam.ofToolResult(b.build());
    }
}
