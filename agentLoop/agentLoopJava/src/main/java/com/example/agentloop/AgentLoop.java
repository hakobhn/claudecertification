package com.example.agentloop;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * The agentic loop (steps 2-4 and 6 of the exercise).
 *
 * <p>The loop is controlled ONLY by {@code stop_reason}:
 * <ul>
 *   <li>{@code tool_use} - run the requested tools, append the results, call Claude again</li>
 *   <li>{@code end_turn} - Claude has finished; return the final text</li>
 *   <li>anything else - "not finished, check why" (pause_turn, max_tokens, refusal, ...)</li>
 * </ul>
 * It never checks content types, never parses phrases like "I'm done", and the
 * iteration cap is only a safeguard against runaway agents.
 */
public final class AgentLoop {

    public static final String MODEL = "claude-opus-5-5";
    public static final long MAX_TOKENS = 16_000L;
    public static final int MAX_ITERATIONS = 20; // safety cap - NOT the primary stopping mechanism

    static final Logger LOG = Logger.getLogger("agent");

    /** Sends one request to Claude. In production this is client.messages()::create; tests pass a fake. */
    @FunctionalInterface
    public interface MessageSender {
        Message send(MessageCreateParams params);
    }

    private final MessageSender sender;

    public AgentLoop(MessageSender sender) {
        this.sender = sender;
    }

    public String run(String userInput) {
        // The conversation history. The API keeps no memory between calls,
        // so the FULL history is sent on every iteration.
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.ofUser(userInput));

        int iterations = 0;
        while (true) {
            iterations++;
            if (iterations > MAX_ITERATIONS) {
                // Safety net only. Hitting this means something is wrong (runaway loop).
                LOG.warning("Safety cap of " + MAX_ITERATIONS + " iterations hit; stopping a possibly runaway agent.");
                return "[stopped: safety iteration cap reached before the task finished]";
            }

            // Step 2: send the request, then inspect stop_reason.
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(MAX_TOKENS)
                    .messages(messages);
            Tools.ALL.forEach(params::addTool);
            Message response = sender.send(params.build());

            StopReason stopReason = response.stopReason()
                    .orElseThrow(() -> new IllegalStateException("Response has no stop_reason"));
            LOG.info("iteration " + iterations + ": stop_reason=" + stopReason.asString());

            // Step 4: end_turn -> Claude has finished. Return the final text.
            if (stopReason.equals(StopReason.END_TURN)) {
                return finalText(response);
            }

            // Step 3: tool_use -> run tools, append assistant turn + tool results, loop again.
            if (stopReason.equals(StopReason.TOOL_USE)) {
                // Keep the whole assistant turn (text AND tool_use blocks) unchanged.
                messages.add(response.toParam());

                List<ContentBlockParam> toolResults = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    // Text next to tool_use is NOT a completion signal - just skip non-tool blocks.
                    if (!block.isToolUse()) continue;
                    ToolUseBlock toolUse = block.asToolUse();

                    Map<String, Object> input = toolUse._input().convert(new TypeReference<Map<String, Object>>() {});
                    Tools.Result result = Tools.execute(toolUse.name(), input);
                    LOG.info("  tool " + toolUse.name() + "(" + Tools.toJson(input) + ") -> " + result.content());

                    ToolResultBlockParam.Builder tr = ToolResultBlockParam.builder()
                            .toolUseId(toolUse.id())          // must match the tool_use block's id
                            .content(result.content());
                    if (result.isError()) tr.isError(true);
                    toolResults.add(ContentBlockParam.ofToolResult(tr.build()));
                }

                // All results go back in ONE user message.
                messages.add(MessageParam.ofUserOfContentBlockParams(toolResults));
                continue;
            }

            // A server-side tool turn paused: append it and re-send so Claude can continue.
            if (stopReason.equals(StopReason.PAUSE_TURN)) {
                messages.add(response.toParam());
                continue;
            }

            // Every other value means "not finished, check why" - never "done".
            if (stopReason.equals(StopReason.REFUSAL)) {
                String category = response.stopDetails()
                        .flatMap(d -> d.category())
                        .map(Object::toString)
                        .orElse("unknown");
                throw new IllegalStateException("Claude declined the request (refusal, category=" + category + ")");
            }
            if (stopReason.equals(StopReason.MAX_TOKENS) || stopReason.equals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED)) {
                throw new IllegalStateException("Response truncated (stop_reason=" + stopReason.asString() + ")");
            }
            throw new IllegalStateException("Unhandled stop_reason: " + stopReason.asString());
        }
    }

    private static String finalText(Message response) {
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }
}
