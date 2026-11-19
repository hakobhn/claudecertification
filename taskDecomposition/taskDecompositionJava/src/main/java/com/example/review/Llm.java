package com.example.review;

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

/** Every review pass is one brand-new, single-message call with structured output. Tests use a fake. */
@FunctionalInterface
public interface Llm {

    String complete(String system, String prompt, Map<String, Object> schema);

    /** The real implementation. */
    static Llm claude(AnthropicClient client) {
        return (system, prompt, schema) -> {
            JsonOutputFormat.Schema.Builder s = JsonOutputFormat.Schema.builder();
            schema.forEach((k, v) -> s.putAdditionalProperty(k, JsonValue.from(v)));
            Message response = client.messages().create(MessageCreateParams.builder()
                    .model("claude-opus-5-5").maxTokens(16_000L).system(system).addUserMessage(prompt)
                    .outputConfig(OutputConfig.builder().format(JsonOutputFormat.builder().schema(s.build()).build()).build())
                    .build());
            StopReason stop = response.stopReason().orElseThrow();
            if (!stop.equals(StopReason.END_TURN)) throw new IllegalStateException("stop_reason=" + stop.asString());
            return response.content().stream().flatMap(b -> b.text().stream()).map(TextBlock::text).collect(Collectors.joining());
        };
    }
}
