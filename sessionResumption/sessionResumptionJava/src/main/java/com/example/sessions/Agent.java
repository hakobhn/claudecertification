package com.example.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.example.sessions.Sessions.Session;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The review agent: lesson 1.1's loop, with two read-only tools over a workspace folder.
 * {@link #runTurn} adds one user message to a session, loops until end_turn, and saves the session.
 */
@SuppressWarnings("unchecked")
public final class Agent {

    private Agent() {
    }

    static final String SYSTEM = "You review the room booking service of a hotel chain (Node.js). The code is in the workspace: "
            + "use list_files and read_file. Cite file names and line numbers.";

    /** One model call: the history and output schema in, a stop reason and content blocks (as maps) out. Tests fake it. */
    @FunctionalInterface
    public interface Model {
        Reply call(List<Map<String, Object>> messages, Map<String, Object> schema);
    }

    public record Reply(String stopReason, List<Map<String, Object>> content) {
    }

    public record Turn(Map<String, Object> answer, List<String> filesRead) {
    }

    // ------------------------------------------------------------ tools
    static String runTool(String name, Map<String, Object> args, Path workspace) {
        try {
            if (name.equals("list_files")) {
                try (var files = Files.list(workspace)) {
                    return files.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(".js")).sorted()
                            .collect(Collectors.joining("\n"));
                }
            }
            if (name.equals("read_file")) {
                Path path = workspace.resolve(Path.of((String) args.get("path")).getFileName()); // stay inside the workspace
                if (!Files.exists(path)) return "No such file: " + args.get("path");
                List<String> lines = Files.readAllLines(path);
                return IntStream.range(0, lines.size()).mapToObj(i -> String.format("%3d| %s", i + 1, lines.get(i)))
                        .collect(Collectors.joining("\n"));
            }
            return "Unknown tool " + name;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------ the loop
    public static Turn runTurn(Session session, String prompt, Map<String, Object> schema, Model model, Path workspace) {
        session.messages.add(Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", prompt))));
        List<String> filesRead = new ArrayList<>();
        for (int i = 0; i < 30; i++) { // safety cap only
            Reply reply = model.call(session.messages, schema);
            session.messages.add(Map.of("role", "assistant", "content", reply.content()));

            if (reply.stopReason().equals("end_turn")) {
                session.save();
                String text = reply.content().stream().filter(b -> "text".equals(b.get("type")))
                        .map(b -> (String) b.get("text")).collect(Collectors.joining());
                return new Turn(Sessions.JSON.convertValue(parse(text), new TypeReference<>() {}), filesRead);
            }
            if (!reply.stopReason().equals("tool_use")) throw new IllegalStateException("stop_reason=" + reply.stopReason());

            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> b : reply.content()) {
                if (!"tool_use".equals(b.get("type"))) continue;
                Map<String, Object> input = (Map<String, Object>) b.get("input");
                if (b.get("name").equals("read_file")) filesRead.add(Path.of((String) input.get("path")).getFileName().toString());
                results.add(Map.of("type", "tool_result", "tool_use_id", b.get("id"),
                        "content", runTool((String) b.get("name"), input, workspace)));
            }
            session.messages.add(Map.of("role", "user", "content", results));
        }
        throw new IllegalStateException("safety cap reached");
    }

    static Object parse(String json) {
        try {
            return Sessions.JSON.readValue(json, Object.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------ the real model
    static Tool tool(String name, String description, Map<String, Object> properties) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder().name(name).description(description).strict(true)
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(List.copyOf(properties.keySet()))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build())
                .build();
    }

    static final List<Tool> TOOLS = List.of(
            tool("list_files", "List the source files in the workspace.", Map.of()),
            tool("read_file", "Read one source file, with line numbers.", Map.of("path", Map.of("type", "string", "description", "e.g. auth.js"))));

    public static Model claude(AnthropicClient client) {
        return (messages, schema) -> {
            JsonOutputFormat.Schema.Builder s = JsonOutputFormat.Schema.builder();
            schema.forEach((k, v) -> s.putAdditionalProperty(k, JsonValue.from(v)));
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model("claude-opus-5-5").maxTokens(16_000L).system(SYSTEM)
                    .messages(messages.stream().map(Agent::toParam).toList())
                    .outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(s.build()).build()).build());
            TOOLS.forEach(params::addTool);
            Message response = client.messages().create(params.build());
            return new Reply(response.stopReason().orElseThrow().asString(),
                    response.content().stream().map(Agent::toMap).filter(Objects::nonNull).toList());
        };
    }

    /** Saved history (maps) -> API message. */
    static MessageParam toParam(Map<String, Object> message) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (Map<String, Object> b : (List<Map<String, Object>>) message.get("content")) {
            switch ((String) b.get("type")) {
                case "text" -> blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text((String) b.get("text")).build()));
                case "tool_use" -> {
                    ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
                    ((Map<String, Object>) b.get("input")).forEach((k, v) -> input.putAdditionalProperty(k, JsonValue.from(v)));
                    blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                            .id((String) b.get("id")).name((String) b.get("name")).input(input.build()).build()));
                }
                case "tool_result" -> blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId((String) b.get("tool_use_id")).content((String) b.get("content")).build()));
                default -> throw new IllegalArgumentException("unknown block " + b.get("type"));
            }
        }
        return message.get("role").equals("user")
                ? MessageParam.ofUserOfContentBlockParams(blocks)
                : MessageParam.ofAssistantOfContentBlockParams(blocks);
    }

    /** API response block -> map, so the history can be saved as JSON. */
    static Map<String, Object> toMap(ContentBlock block) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (block.isText()) {
            m.put("type", "text");
            m.put("text", block.asText().text());
        } else if (block.isToolUse()) {
            m.put("type", "tool_use");
            m.put("id", block.asToolUse().id());
            m.put("name", block.asToolUse().name());
            m.put("input", block.asToolUse()._input().convert(new TypeReference<Map<String, Object>>() {}));
        } else {
            return null; // other block types are not used in this exercise
        }
        return m;
    }
}
