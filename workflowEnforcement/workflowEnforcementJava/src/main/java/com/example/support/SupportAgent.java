package com.example.support;

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
import java.util.stream.Collectors;

/**
 * The support agent: the lesson 1.1 loop (stop_reason drives it), with every tool call
 * routed through {@link SupportSession#execute}, where the gate and handoff check live.
 */
public final class SupportAgent {

    public static final String MODEL = "claude-opus-5-5";
    public static final long MAX_TOKENS = 16_000L;
    public static final int MAX_ITERATIONS = 20; // safety cap only (lesson 1.1)

    public static final String SYSTEM_PROMPT = """
            You are the customer support agent for an online clothing shop.

            Workflow:
            - Always verify the customer's identity with get_customer before processing any refund.
              (This is also enforced in code: process_refund is blocked until verification succeeds.)
            - You may refund returns of delivered items within 30 days, up to the order amount.
            - You cannot resolve billing disputes (such as duplicate charges) or change account details
              such as the shipping address. Those go to a human with escalate_to_human.
            - If a request contains several concerns, identify every one of them, look up everything you
              need in parallel, resolve what you can, and put ALL remaining concerns into ONE handoff.
              Never drop a concern.
            - A handoff must be self-contained: the human agent cannot see this conversation.

            Reply style (low stakes, so this is just guidance): reply in plain text, start with a one-line
            summary, then one short paragraph per concern.""";

    /** Sends one request to Claude. In production: client.messages()::create; tests pass a fake. */
    @FunctionalInterface
    public interface MessageSender {
        Message send(MessageCreateParams params);
    }

    public record AgentResult(String reply, SupportSession session) {
    }

    private final MessageSender sender;

    public SupportAgent(MessageSender sender) {
        this.sender = sender;
    }

    public AgentResult run(String userMessage) {
        return run(userMessage, new SupportSession());
    }

    public AgentResult run(String userMessage, SupportSession session) {
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.ofUser(userMessage));

        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(MODEL).maxTokens(MAX_TOKENS).system(SYSTEM_PROMPT).messages(messages);
            SupportTools.ALL.forEach(params::addTool);
            Message response = sender.send(params.build());

            StopReason stop = response.stopReason().orElseThrow(() -> new IllegalStateException("no stop_reason"));
            SupportSession.LOG.info("iteration " + iteration + ": stop_reason=" + stop.asString());

            if (stop.equals(StopReason.END_TURN)) {
                String reply = response.content().stream().flatMap(b -> b.text().stream())
                        .map(TextBlock::text).collect(Collectors.joining());
                return new AgentResult(reply, session);
            }

            if (stop.equals(StopReason.TOOL_USE)) {
                messages.add(response.toParam());
                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (!block.isToolUse()) continue;
                    ToolUseBlock toolUse = block.asToolUse();
                    Map<String, Object> input = toolUse._input().convert(new TypeReference<Map<String, Object>>() {});

                    SupportSession.ToolResult r = session.execute(toolUse.name(), input);
                    SupportSession.LOG.info("  tool " + toolUse.name() + "(" + abbreviate(SupportSession.toJson(input)) + ") -> "
                            + (r.isError() ? "ERROR " : "") + abbreviate(r.content()));

                    ToolResultBlockParam.Builder tr = ToolResultBlockParam.builder()
                            .toolUseId(toolUse.id()).content(r.content());
                    if (r.isError()) tr.isError(true);
                    results.add(ContentBlockParam.ofToolResult(tr.build()));
                }
                messages.add(MessageParam.ofUserOfContentBlockParams(results));
                continue;
            }

            if (stop.equals(StopReason.PAUSE_TURN)) {
                messages.add(response.toParam());
                continue;
            }

            throw new IllegalStateException("Agent did not finish (stop_reason=" + stop.asString() + ")");
        }

        SupportSession.LOG.warning("Safety cap of " + MAX_ITERATIONS + " iterations hit.");
        return new AgentResult("[stopped: safety iteration cap reached]", session);
    }

    private static String abbreviate(String s) {
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }
}
