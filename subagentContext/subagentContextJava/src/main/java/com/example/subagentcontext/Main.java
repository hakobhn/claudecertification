package com.example.subagentcontext;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Entry point. Arguments: an optional question, and {@code --strip-metadata} to reproduce the bug
 * (findings reach the synthesis subagent without their source metadata).
 *
 * <p>Credentials: fromEnv() uses ANTHROPIC_API_KEY if set, otherwise your `ant auth login` profile.
 */
public final class Main {

    static final String DEFAULT_QUESTION = "What are the cost trends and main operational risks of offshore wind?";

    public static void main(String[] args) {
        configureLogging();
        List<String> words = new ArrayList<>(List.of(args));
        boolean stripMetadata = words.remove("--strip-metadata");
        String question = words.isEmpty() ? DEFAULT_QUESTION : String.join(" ", words);

        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        try {
            Coordinator coordinator = new Coordinator(params -> client.messages().create(params),
                    Coordinator.Options.defaults(), stripMetadata);
            Coordinator.Result result = coordinator.run(question);

            System.out.println();
            System.out.println(result.report());
            System.out.println();
            System.out.println("Spawn batches: " + result.spawnBatches());
            System.out.println("Findings: " + result.findings().size() + ", claims: " + result.claims().size()
                    + ", unattributed claims: " + result.unattributed().size());
            result.unattributed().forEach(c -> System.out.println("  NO SOURCE: " + c.claim()));
        } finally {
            client.close();
        }
    }

    /** One-line log format: "INFO spawning in parallel: web_research, document_analysis". */
    private static void configureLogging() {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s %5$s%n");
        Logger root = Logger.getLogger("");
        for (var h : root.getHandlers()) root.removeHandler(h);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter());
        handler.setLevel(Level.INFO);
        root.addHandler(handler);
        root.setLevel(Level.INFO);
        Logger.getLogger("okhttp3").setLevel(Level.WARNING);
    }
}
