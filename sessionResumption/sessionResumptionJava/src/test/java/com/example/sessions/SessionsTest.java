package com.example.sessions;

import com.example.sessions.Agent.Model;
import com.example.sessions.Agent.Reply;
import com.example.sessions.Agent.Turn;
import com.example.sessions.Sessions.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests of the three session options. A scripted fake model replaces the API. */
class SessionsTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        Sessions.dir = tmp.resolve("sessions");
        Main.workspace = tmp.resolve("workspace");
        Main.resetWorkspace();
    }

    /** Answers with a scripted list of replies. */
    static Model scripted(Reply... replies) {
        Deque<Reply> queue = new ArrayDeque<>(List.of(replies));
        return (messages, schema) -> queue.removeFirst();
    }

    static Reply read(String path) {
        return new Reply("tool_use", List.of(Map.of("type", "tool_use", "id", "t1", "name", "read_file", "input", Map.of("path", path))));
    }

    static Reply answer(String json) {
        return new Reply("end_turn", List.of(Map.of("type", "text", "text", json)));
    }

    /** A day-1 session that read the ORIGINAL auth.js. */
    static void dayOne() {
        Agent.runTurn(Sessions.start("review"), "review", Map.of(), scripted(read("auth.js"), answer("{\"files\":[]}")), Main.workspace);
    }

    @Test
    void resumeRestoresTheWholeHistoryIncludingOldToolResults() throws Exception {
        dayOne();
        Main.applyFixes();                                             // auth.js changes on disk...
        Session resumed = Sessions.resume("review");
        assertEquals(4, resumed.messages.size());                      // user, tool_use, tool_result, answer
        assertTrue(resumed.toolResults().get(0).contains("token_expires_at > Date.now()")); // ...the history has the OLD one
        assertTrue(Files.readString(Main.workspace.resolve("auth.js")).contains("FIXED"));
    }

    @Test
    void forkCopiesHistoryAndLeavesTheOriginalUnchanged() {
        dayOne();
        Session a = Sessions.fork("review", "a");
        Sessions.fork("review", "b");
        Agent.runTurn(a, "plan A", Map.of(), scripted(answer("{\"plan\":[\"A\"]}")), Main.workspace);
        assertEquals(4, Sessions.resume("review").messages.size());     // original untouched
        assertEquals(6, Sessions.resume("a").messages.size());
        assertEquals(4, Sessions.resume("b").messages.size());          // b never sees a's work
    }

    @Test
    void freshSessionHasTheSummaryButNoOldToolResults() throws Exception {
        Main.applyFixes();
        Map<String, Object> summary = Map.of("files", List.of(Map.of("file", "pricing.js",
                "issues", List.of(Map.of("line", 12, "description", "Friday missing")))));
        Session session = Sessions.start("day2");
        Turn turn = Agent.runTurn(session, Sessions.summaryInjectionPrompt(summary, Main.CHANGED, Main.QUESTION),
                Map.of(), scripted(read("auth.js"), answer("{}")), Main.workspace);
        String first = (String) ((List<Map<String, Object>>) session.messages.get(0).get("content")).get(0).get("text");
        assertTrue(first.contains("Friday missing"));                   // prior knowledge kept
        assertTrue(first.contains("auth.js, database.js, api-routes.js")); // targeted re-analysis
        assertTrue(session.toolResults().get(0).contains("FIXED"));     // it reads the CURRENT file
        assertEquals(List.of("auth.js"), turn.filesRead());
    }

    @Test
    void classifiesStaleClaimsAndKeptBugs() {
        Map<String, Object> status = Map.of("open_bugs", List.of(
                Map.of("file", "auth.js", "line", 16, "description", "fixed expiry bug"),
                Map.of("file", "auth.js", "line", 12, "description", "a different, real issue"),
                Map.of("file", "pricing.js", "line", 12, "description", "y")), "verified_fixed", List.of(), "fix_first", "");
        Main.Row row = Main.classify("t", 0, status, List.of());
        assertEquals(List.of("auth.js:16"), row.stale());
        assertEquals(new TreeSet<>(List.of("pricing.js")), row.kept());
    }
}
