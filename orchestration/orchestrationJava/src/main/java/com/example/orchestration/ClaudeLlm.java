package com.example.orchestration;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;

import java.util.Map;
import java.util.stream.Collectors;

/** Real {@link Llm}: one Messages API call per {@code complete}. */
public final class ClaudeLlm implements Llm {

    public static final String MODEL = "claude-opus-5-5";
    public static final long MAX_TOKENS = 16_000L;

    private final AnthropicClient client;

    public ClaudeLlm(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public String complete(String system, String prompt, Map<String, Object> schema) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(system)
                .addUserMessage(prompt);                // fresh history every call

        if (schema != null) {
            // Structured output: the reply is guaranteed to be JSON matching the schema.
            JsonOutputFormat.Schema.Builder s = JsonOutputFormat.Schema.builder();
            schema.forEach((k, v) -> s.putAdditionalProperty(k, JsonValue.from(v)));
            params.outputConfig(OutputConfig.builder()
                    .format(JsonOutputFormat.builder().schema(s.build()).build())
                    .build());
        }

        Message response = client.messages().create(params.build());

        // Same rule as lesson 1.1: stop_reason decides whether the answer is complete.
        StopReason stop = response.stopReason().orElse(null);
        if (!StopReason.END_TURN.equals(stop)) {
            throw new IllegalStateException("Claude did not finish (stop_reason=" + (stop == null ? null : stop.asString()) + ")");
        }
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }
}
