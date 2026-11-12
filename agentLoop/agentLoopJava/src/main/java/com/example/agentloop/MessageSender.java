package com.example.agentloop;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/**
 * Sends one request to the Claude Messages API.
 *
 * <p>Production code uses {@link AnthropicMessageSender}; tests pass a scripted fake.
 */
@FunctionalInterface
public interface MessageSender {

    /**
     * Sends a single Messages API request.
     *
     * @param params the full request: model, conversation history and tool definitions
     * @return Claude's response, including its {@code stop_reason}
     */
    Message send(MessageCreateParams params);
}
