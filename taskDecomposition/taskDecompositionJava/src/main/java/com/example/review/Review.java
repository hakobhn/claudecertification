package com.example.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The two review architectures from lesson 1.6. Same model, same system prompt.
 * <pre>
 *   SINGLE PASS                          MULTI-PASS (a fixed pipeline)
 *   ┌──────────────────────┐             file 1 ─► local pass ─┐
 *   │ all 14 files in ONE  │             file 2 ─► local pass ─┤
 *   │ prompt               │             ...                   ├─► integration pass ─► report
 *   └──────────────────────┘             file 14 ─► local pass ─┘    (cross-file only)
 * </pre>
 * A review is a plain {@code Map}: {@code {files: [{file, findings: [{line, severity, description}]}],
 * cross_file_issues: [{files, severity, description}]}} - the same JSON shape as the Python version.
 */
public final class Review {

    private Review() {
    }

    static final String SYSTEM = "You are a senior code reviewer for an online bookshop's Node.js checkout code. "
            + "Report real defects: bugs, security holes, data-handling errors and questionable patterns. "
            + "Cite the exact line number from the numbered listing.";

    static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------ JSON schemas (structured output)
    /** An object schema whose properties are all required. Pass name/schema pairs. */
    static Map<String, Object> obj(Object... nameSchemaPairs) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (int i = 0; i < nameSchemaPairs.length; i += 2) props.put((String) nameSchemaPairs[i], nameSchemaPairs[i + 1]);
        return Map.of("type", "object", "properties", props, "required", List.copyOf(props.keySet()),
                "additionalProperties", false);
    }

    static Map<String, Object> arrayOf(Object items) {
        return Map.of("type", "array", "items", items);
    }

    static final Map<String, Object> STR = Map.of("type", "string");
    static final Map<String, Object> SEVERITY = Map.of("type", "string", "enum", List.of("critical", "high", "medium", "low"));
    static final Map<String, Object> FINDING = obj("line", Map.of("type", "integer"), "severity", SEVERITY, "description", STR);
    static final Map<String, Object> CROSS_FILE = obj("files", arrayOf(STR), "severity", SEVERITY, "description", STR);

    static final Map<String, Object> SINGLE_PASS_SCHEMA = obj(
            "files", arrayOf(obj("file", STR, "findings", arrayOf(FINDING))),
            "cross_file_issues", arrayOf(CROSS_FILE));
    static final Map<String, Object> PER_FILE_SCHEMA = obj(
            "findings", arrayOf(FINDING),
            "interface", Map.of("type", "array", "items", STR,
                    "description", "Every export AND every import, with parameters, return value and units."));
    static final Map<String, Object> INTEGRATION_SCHEMA = obj("cross_file_issues", arrayOf(CROSS_FILE));

    // ------------------------------------------------------------ input
    /** Reads every .js/.ts file in the directory, in name order. */
    public static Map<String, String> loadFiles(Path directory) {
        try (var paths = Files.list(directory)) {
            Map<String, String> files = new LinkedHashMap<>();
            for (Path p : paths.filter(p -> p.toString().endsWith(".js") || p.toString().endsWith(".ts")).sorted().toList()) {
                files.put(p.getFileName().toString(), Files.readString(p));
            }
            if (files.size() < 10) {
                throw new IllegalArgumentException("need at least 10 source files to show attention dilution, found " + files.size());
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String numbered(String name, String source) {
        StringBuilder sb = new StringBuilder("=== " + name + " ===\n");
        String[] lines = source.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) break; // trailing newline
            sb.append(String.format("%3d| %s%n", i + 1, lines[i]));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ architecture 1: one pass
    /** Every file in one prompt. The baseline that shows attention dilution. */
    public static Map<String, Object> singlePass(Map<String, String> files, Llm llm) {
        StringBuilder prompt = new StringBuilder("Review this pull request. It contains " + files.size()
                + " files. Give findings for every file, then any cross-file issues.\n\n");
        files.forEach((name, source) -> prompt.append(numbered(name, source)).append('\n'));
        return parse(llm.complete(SYSTEM, prompt.toString(), SINGLE_PASS_SCHEMA));
    }

    // ------------------------------------------------------------ architecture 2: multi-pass
    /** Layer 1: one file gets the model's whole attention. */
    static Map<String, Object> localPass(String name, String source, Llm llm) {
        String prompt = "Review this ONE file from a pull request. Report its local defects. Also list its interface: "
                + "every export and every import, with parameters, return value and units.\n\n" + numbered(name, source);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", name);
        result.putAll(parse(llm.complete(SYSTEM, prompt, PER_FILE_SCHEMA)));
        return result;
    }

    /** Layer 2: sees the per-file summaries (not the code) and looks only BETWEEN files. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> integrationPass(List<Map<String, Object>> local, Llm llm) {
        String prompt = "The per-file reviews of a pull request are done; their results are below. Do NOT repeat local "
                + "issues. Find cross-file issues only:\n"
                + "- data flow: a value produced in one file and used in another with different units, types or nullability\n"
                + "- API consistency: imports that do not match what the other file exports\n"
                + "- pattern consistency: the same code judged differently in different files\n\n" + toJson(local);
        return (List<Map<String, Object>>) parse(llm.complete(SYSTEM, prompt, INTEGRATION_SCHEMA)).get("cross_file_issues");
    }

    public static Map<String, Object> multiPass(Map<String, String> files, Llm llm, int workers) {
        List<Map<String, Object>> local = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) { // local passes are independent
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            files.forEach((name, source) -> futures.add(pool.submit(() -> localPass(name, source, llm))));
            for (Future<Map<String, Object>> f : futures) local.add(f.get());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        List<Map<String, Object>> perFile = local.stream()
                .map(r -> Map.<String, Object>of("file", r.get("file"), "findings", r.get("findings"))).toList();
        return Map.of("files", perFile, "cross_file_issues", integrationPass(local, llm));
    }

    // ------------------------------------------------------------ JSON helpers
    static Map<String, Object> parse(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String toJson(Object value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
