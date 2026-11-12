package com.example.agentloop;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Entry point. Step 5 of the exercise: a prompt that needs sequential tool calls
 * (search for values, then calculate with them).
 *
 * <p>Credentials: fromEnv() uses ANTHROPIC_API_KEY if set, otherwise your
 * `ant auth login` profile.
 */
public final class Main {

    public static void main(String[] args) {
        configureLogging();

        String prompt = args.length > 0 ? String.join(" ", args)
                : "How much taller is Mount Everest than Denali, in metres? "
                + "Look up both heights, then use the calculator for the difference.";

        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        try {
            // Wiring: the agent depends only on the MessageSender and ToolRegistry interfaces.
            Agent agent = new AgentLoop(new AnthropicMessageSender(client), DefaultToolRegistry.standard());
            System.out.println();
            System.out.println(agent.run(prompt));
        } finally {
            client.close();
        }
    }

    /** One-line log format: "INFO iteration 1: stop_reason=tool_use". */
    private static void configureLogging() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s %5$s%n");
        Logger root = Logger.getLogger("");
        for (var h : root.getHandlers()) root.removeHandler(h);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.INFO);
        root.addHandler(handler);
        root.setLevel(Level.INFO);
    }
}
