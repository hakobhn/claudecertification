package com.example.sessions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The three session management options from lesson 1.7.
 *
 * <p>A session is a named conversation history saved to {@code sessions/<name>.json}. The history holds
 * everything: the agent's reasoning AND every tool result (e.g. the contents of each file it read).
 * <pre>
 *   resume(name)        continue the SAME history          -> old tool results come back too
 *   fork(name, new)     copy the history into a new branch -> original untouched; branches independent
 *   start(name) + summaryInjectionPrompt(...)
 *                       a NEW, empty history whose first message is a curated summary
 *                       -> prior knowledge kept, stale tool results gone
 * </pre>
 * Claude Code equivalents: {@code claude --name x}, {@code claude --resume x}, {@code --resume x --fork-session}.
 * The history uses the same JSON shape as the Python version: {@code [{role, content: [blocks]}]}.
 */
@SuppressWarnings("unchecked")
public final class Sessions {

    private Sessions() {
    }

    static Path dir = Path.of("sessions");
    static final ObjectMapper JSON = new ObjectMapper();

    public static final class Session {
        public final String name;
        public final List<Map<String, Object>> messages;

        Session(String name, List<Map<String, Object>> messages) {
            this.name = name;
            this.messages = messages;
        }

        public void save() {
            try {
                Files.createDirectories(dir);
                JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(name + ".json").toFile(), messages);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Every tool result in the history: what the agent will reason from if this session continues. */
        public List<String> toolResults() {
            return messages.stream().flatMap(m -> ((List<Map<String, Object>>) m.get("content")).stream())
                    .filter(b -> "tool_result".equals(b.get("type"))).map(b -> (String) b.get("content")).toList();
        }
    }

    /** A new, empty, named session (claude --name &lt;name&gt;). */
    public static Session start(String name) {
        return new Session(name, new ArrayList<>());
    }

    /** Continue a named session (claude --resume &lt;name&gt;): the ENTIRE history is restored. */
    public static Session resume(String name) {
        try {
            return new Session(name, JSON.readValue(dir.resolve(name + ".json").toFile(), new TypeReference<>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Branch a session (fork_session): a copy of the history under a new name. The original is not changed. */
    public static Session fork(String name, String newName) {
        Session branch = new Session(newName, resume(name).messages);
        branch.save();
        return branch;
    }

    /** The first message of a fresh session: curated conclusions, no raw tool output. */
    public static String summaryInjectionPrompt(Map<String, Object> summary, List<String> changedFiles, String question) {
        try {
            return "You are continuing a code review from a previous session. Its structured summary:\n\n"
                    + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n\n"
                    + "Since then these " + changedFiles.size() + " files were changed: " + String.join(", ", changedFiles) + ".\n"
                    + "Re-read ONLY those files to verify the fixes and check for new issues. For every other file, "
                    + "trust the summary and do not re-read it.\n\n" + question;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
