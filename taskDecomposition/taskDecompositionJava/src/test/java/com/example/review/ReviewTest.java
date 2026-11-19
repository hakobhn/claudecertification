package com.example.review;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline tests: a fake LLM shows the architecture without API calls. */
class ReviewTest {

    static final Map<String, String> FILES = Review.loadFiles(Path.of("../sample-pr"));

    /** Records every prompt. Per-file passes 'find' the planted bug of their file; the single pass is diluted. */
    static final class FakeLlm implements Llm {
        final List<String> prompts = new ArrayList<>();

        @Override
        public synchronized String complete(String system, String prompt, Map<String, Object> schema) {
            prompts.add(prompt);
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            if (props.containsKey("interface")) {
                String name = prompt.split("=== ")[1].split(" ===")[0];
                List<Map<String, Object>> found = Compare.SEEDED.stream().filter(s -> s.file().equals(name))
                        .map(s -> Map.<String, Object>of("line", s.line(), "severity", "high", "description", s.what())).toList();
                return Review.toJson(Map.of("findings", found, "interface", List.of("exports of " + name)));
            }
            if (!props.containsKey("files")) { // integration pass
                return Review.toJson(Map.of("cross_file_issues", List.of(Map.of(
                        "files", List.of("04-basket.js", "05-discounts.js"), "severity", "high", "description", "units"))));
            }
            // single pass: only the first 5 files get attention
            List<Map<String, Object>> files = Compare.SEEDED.stream().filter(s -> s.file().compareTo("06") < 0)
                    .map(s -> Map.<String, Object>of("file", s.file(), "findings",
                            List.of(Map.of("line", s.line(), "severity", "high", "description", s.what())))).toList();
            return Review.toJson(Map.of("files", files, "cross_file_issues", List.of()));
        }
    }

    @Test
    void loadsAtLeastTenFilesInOrder() {
        assertEquals(14, FILES.size());
        assertEquals("01-auth.js", FILES.keySet().iterator().next());
    }

    @Test
    void singlePassPutsEveryFileInOnePrompt() {
        FakeLlm llm = new FakeLlm();
        Review.singlePass(FILES, llm);
        assertEquals(1, llm.prompts.size());
        FILES.keySet().forEach(name -> assertTrue(llm.prompts.get(0).contains("=== " + name + " ===")));
    }

    @Test
    void multiPassGivesEachFileItsOwnPromptThenOneIntegrationPass() {
        FakeLlm llm = new FakeLlm();
        Review.multiPass(FILES, llm, 1);
        assertEquals(15, llm.prompts.size()); // 14 local + 1 integration
        List<String> names = new ArrayList<>(FILES.keySet());
        for (int i = 0; i < names.size(); i++) {
            List<String> headers = llm.prompts.get(i).lines().filter(l -> l.startsWith("=== ")).toList();
            assertEquals(List.of("=== " + names.get(i) + " ==="), headers); // exactly one file per local pass
        }
        String integration = llm.prompts.get(14);
        assertTrue(integration.contains("Do NOT repeat local issues"));
        assertFalse(integration.contains("  1| ")); // summaries, not the source code
    }

    @Test
    void scoresAndArtefacts() {
        Compare.Score s = Compare.score(Review.singlePass(FILES, new FakeLlm()));
        Compare.Score m = Compare.score(Review.multiPass(FILES, new FakeLlm(), 1));
        assertEquals(Compare.SEEDED.size(), m.seededCaught().size());
        assertTrue(s.seededCaught().size() < Compare.SEEDED.size());
        assertEquals(1, m.crossCaught().size());
        String artefact = "missing sign-in check: flagged in 03-orders.js, passed in 11-wishlist.js";
        assertTrue(s.artefacts().contains(artefact));
        assertFalse(m.artefacts().contains(artefact));
    }
}
