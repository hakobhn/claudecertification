package com.example.subagentcontext;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/** Sends one request to Claude. In production this is client.messages()::create; tests pass a fake. */
@FunctionalInterface
public interface MessageSender {
    Message send(MessageCreateParams params);
}
