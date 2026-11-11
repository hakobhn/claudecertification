package com.example.orchestration;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import java.util.List;
import java.util.Locale;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Entry point. Step 6 of the exercise: research "renewable energy technologies" and
 * check that the report covers solar, wind, geothermal, tidal, biomass and fusion.
 *
 * <p>Credentials: fromEnv() uses ANTHROPIC_API_KEY if set, otherwise your
 * `ant auth login` profile.
 */
public final class Main {

    static final String DEFAULT_TOPIC = "renewable energy technologies";
    static final List<String> REQUIRED_FOR_TEST = List.of("solar", "wind", "geothermal", "tidal", "biomass", "fusion");

    public static void main(String[] args) {
        configureLogging();
        String topic = args.length > 0 ? String.join(" ", args) : DEFAULT_TOPIC;

        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        try {
            boolean isExerciseTest = topic.equals(DEFAULT_TOPIC);
            // Step 6: the exercise's test case - the requester requires these six categories.
            Coordinator.ResearchReport report = new Coordinator(new ClaudeLlm(client))
                    .run(topic, isExerciseTest ? REQUIRED_FOR_TEST : List.of());
            System.out.println();
            System.out.println(report.markdown());

            if (isExerciseTest) {
                String text = report.markdown().toLowerCase(Locale.ROOT);
                List<String> missing = REQUIRED_FOR_TEST.stream().filter(k -> !text.contains(k)).toList();
                System.out.println("Required categories present: " + (REQUIRED_FOR_TEST.size() - missing.size()) + "/"
                        + REQUIRED_FOR_TEST.size() + (missing.isEmpty() ? " - OK" : " - MISSING: " + String.join(", ", missing)));
            }
        } finally {
            client.close();
        }
    }

    /** One-line log format: "INFO coordinator -> web_search [Solar] ...". */
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
