package com.example.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.example.sessions.Agent.Model;
import com.example.sessions.Agent.Turn;
import com.example.sessions.Sessions.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Build Exercise 1.7 - session management: resume vs fork vs fresh start with summary injection.
 * <pre>
 *   analyse   day 1: named session reviews 10 files -> sessions/booking-review.json + summary.json
 *   fork      two independent branches from day 1, comparing two refactoring strategies
 *   modify    overnight: the developer fixes the 3 core files
 *   resume    day 2, the trap: resume the session -> stale tool results
 *   fresh     day 2, the fix: fresh session + summary + targeted re-analysis
 *   (none)    all of the above, then the comparison
 * </pre>
 */
@SuppressWarnings("unchecked")
public final class Main {

    static final Path CODEBASE = Path.of("../codebase");
    static Path workspace = Path.of("workspace");
    static final String NAME = "booking-review";
    static final List<String> CHANGED = List.of("auth.js", "database.js", "api-routes.js"); // fixed overnight
    static final List<String> STILL_OPEN = List.of("bookings.js", "pricing.js");             // nobody fixed these
    static final Map<String, Integer> FIXED_BUGS = Map.of("auth.js", 16, "database.js", 17, "api-routes.js", 25); // file -> line
    static final String QUESTION = "Which bugs are still open, and which one should I fix first?";

    // ------------------------------------------------------------ schemas
    static Map<String, Object> obj(Object... nameSchemaPairs) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (int i = 0; i < nameSchemaPairs.length; i += 2) props.put((String) nameSchemaPairs[i], nameSchemaPairs[i + 1]);
        return Map.of("type", "object", "properties", props, "required", List.copyOf(props.keySet()), "additionalProperties", false);
    }

    static Map<String, Object> arrayOf(Object items) {
        return Map.of("type", "array", "items", items);
    }

    static final Map<String, Object> STR = Map.of("type", "string");
    static final Map<String, Object> INT = Map.of("type", "integer");
    static final Map<String, Object> SUMMARY_SCHEMA = obj("files", arrayOf(obj("file", STR, "issues", arrayOf(obj(
            "line", INT, "severity", Map.of("type", "string", "enum", List.of("high", "medium", "low")),
            "description", STR, "recommendation", STR)))));
    static final Map<String, Object> STATUS_SCHEMA = obj(
            "open_bugs", arrayOf(obj("file", STR, "line", INT, "description", STR)),
            "verified_fixed", arrayOf(STR),
            "fix_first", STR);

    // ------------------------------------------------------------ workspace
    static void resetWorkspace() throws IOException {
        if (Files.exists(workspace)) {
            try (var paths = Files.walk(workspace)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        Files.createDirectories(workspace);
        try (var files = Files.list(CODEBASE.resolve("original"))) {
            for (Path f : files.toList()) Files.copy(f, workspace.resolve(f.getFileName()));
        }
    }

    static List<String> applyFixes() throws IOException {
        for (String f : CHANGED) {
            Files.copy(CODEBASE.resolve("fixes").resolve(f), workspace.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }
        return CHANGED;
    }

    static String fileName(Object path) {
        return Path.of((String) path).getFileName().toString();
    }

    record Row(String label, int carried, List<String> read, List<String> stale, TreeSet<String> kept, int verifiedFixed) {
    }

    /** Reports one of the 3 bugs that were fixed overnight as still open. */
    static boolean isStale(Map<String, Object> bug) {
        Integer line = FIXED_BUGS.get(fileName(bug.get("file")));
        return line != null && Math.abs(((Number) bug.get("line")).intValue() - line) <= 2;
    }

    /** How the day-2 answer compares with reality. */
    static Row classify(String label, int carried, Map<String, Object> status, List<String> read) {
        List<Map<String, Object>> bugs = (List<Map<String, Object>>) status.get("open_bugs");
        List<String> files = bugs.stream().map(b -> fileName(b.get("file"))).toList();
        return new Row(label, carried, read,
                bugs.stream().filter(Main::isStale).map(b -> b.get("file") + ":" + b.get("line")).toList(),
                new TreeSet<>(files.stream().filter(STILL_OPEN::contains).toList()),      // real open bugs, carried over
                ((List<?>) status.get("verified_fixed")).size());
    }

    // ------------------------------------------------------------ the steps
    static void analyse(Model model) throws IOException {
        resetWorkspace();
        Session session = Sessions.start(NAME);
        Turn turn = Agent.runTurn(session, "Review the booking service: read every file and find every bug. Your answer is "
                + "a structured summary that a later session will rely on, so be complete but concise.", SUMMARY_SCHEMA, model, workspace);
        Sessions.JSON.writerWithDefaultPrettyPrinter().writeValue(Sessions.dir.resolve("summary.json").toFile(), turn.answer());
        System.out.printf("Session '%s' read %d files, saved %d messages (%d tool results).%n",
                NAME, turn.filesRead().size(), session.messages.size(), session.toolResults().size());
        for (Map<String, Object> f : (List<Map<String, Object>>) turn.answer().get("files")) {
            for (Map<String, Object> i : (List<Map<String, Object>>) f.get("issues")) {
                System.out.printf("  %s:%s [%s] %s%n", f.get("file"), i.get("line"), i.get("severity"), i.get("description"));
            }
        }
    }

    static void forkDemo(Model model) {
        int original = Sessions.resume(NAME).messages.size();
        Map<String, Object> schema = obj("plan", arrayOf(STR));
        Map<String, String> strategies = new LinkedHashMap<>();
        strategies.put("refactor-repository", "a repository layer that owns all SQL");
        strategies.put("refactor-middleware", "Express middleware for authentication and ownership checks");
        strategies.forEach((branch, strategy) -> {
            Session session = Sessions.fork(NAME, branch);
            Turn turn = Agent.runTurn(session, "Plan a refactor built around " + strategy + ". Three steps at most.", schema, model, workspace);
            System.out.printf("%nFork '%s' (%d messages):%n", branch, session.messages.size());
            ((List<String>) turn.answer().get("plan")).forEach(step -> System.out.println("  - " + step));
        });
        System.out.printf("%nOriginal '%s' still has %d messages (was %d): forks never change it, and neither fork saw the other.%n",
                NAME, Sessions.resume(NAME).messages.size(), original);
    }

    static Row resumeDay2(Model model) {
        Session session = Sessions.resume(NAME);
        boolean oldAuth = session.toolResults().stream().anyMatch(r -> r.contains("token_expires_at > Date.now()")); // the unfixed line
        System.out.printf("Resumed '%s': %d messages, %d tool results restored%s.%n", NAME, session.messages.size(),
                session.toolResults().size(), oldAuth ? " - including the OLD auth.js, which no longer matches the file on disk" : "");
        int carried = session.toolResults().size();
        Turn turn = Agent.runTurn(session, "Good morning, I'm back. " + QUESTION, STATUS_SCHEMA, model, workspace);
        return report("resume", carried, turn);
    }

    static Row freshDay2(Model model) throws IOException {
        Map<String, Object> summary = Sessions.JSON.readValue(Sessions.dir.resolve("summary.json").toFile(), Map.class);
        Session session = Sessions.start(NAME + "-day2");
        Turn turn = Agent.runTurn(session, Sessions.summaryInjectionPrompt(summary, CHANGED, QUESTION), STATUS_SCHEMA, model, workspace);
        return report("fresh + summary", 0, turn);
    }

    static Row report(String label, int carried, Turn turn) {
        Map<String, Object> status = turn.answer();
        System.out.printf("%n[%s] files read this turn: %s%n", label, turn.filesRead().isEmpty() ? "none" : turn.filesRead());
        for (Map<String, Object> b : (List<Map<String, Object>>) status.get("open_bugs")) {
            String mark = isStale(b) ? "  <-- STALE: this bug was fixed overnight" : "";
            System.out.printf("  open: %s:%s %s%s%n", b.get("file"), b.get("line"), b.get("description"), mark);
        }
        ((List<String>) status.get("verified_fixed")).forEach(v -> System.out.println("  fixed: " + v));
        System.out.println("  fix first: " + status.get("fix_first"));
        return classify(label, carried, status, turn.filesRead());
    }

    public static void main(String[] args) throws IOException {
        String which = args.length > 0 ? args[0] : "all";
        AnthropicClient client = which.equals("modify") ? null : AnthropicOkHttpClient.fromEnv();
        try {
            Model model = client == null ? null : Agent.claude(client);
            if (which.equals("analyse") || which.equals("all")) {
                System.out.println("=== DAY 1: analyse ===");
                analyse(model);
            }
            if (which.equals("fork") || which.equals("all")) {
                System.out.println("\n=== FORK: two strategies from the same baseline ===");
                forkDemo(model);
            }
            if (which.equals("modify") || which.equals("all")) {
                System.out.println("\n=== OVERNIGHT: fixed " + applyFixes() + " ===");
            }
            List<Row> rows = new ArrayList<>();
            if (which.equals("resume") || which.equals("all")) {
                System.out.println("\n=== DAY 2 (trap): --resume ===");
                rows.add(resumeDay2(model));
            }
            if (which.equals("fresh") || which.equals("all")) {
                System.out.println("\n=== DAY 2 (fix): fresh start + summary injection ===");
                rows.add(freshDay2(model));
            }
            if (rows.size() == 2) {
                System.out.println("\n=== COMPARISON ===");
                System.out.printf("%-16s %16s %10s %13s %15s %15s%n", "approach", "old tool results", "files read",
                        "stale claims", "open bugs kept", "fixes verified");
                for (Row r : rows) {
                    System.out.printf("%-16s %16d %10d %13d %13d/%d %15d%n", r.label(), r.carried(), r.read().size(), r.stale().size(),
                            r.kept().size(), STILL_OPEN.size(), r.verifiedFixed());
                }
            }
        } finally {
            if (client != null) client.close();
        }
    }
}
