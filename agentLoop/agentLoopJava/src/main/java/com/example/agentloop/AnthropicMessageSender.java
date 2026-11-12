package com.example.agentloop;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/** Real {@link MessageSender}: one call to {@code client.messages().create(...)}. */
public final class AnthropicMessageSender implements MessageSender {

    private final AnthropicClient client;

    public AnthropicMessageSender(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public Message send(MessageCreateParams params) {
        return client.messages().create(params);
    }
}
