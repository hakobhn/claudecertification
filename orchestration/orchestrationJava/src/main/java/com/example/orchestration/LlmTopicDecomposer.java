package com.example.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.example.orchestration.CoordinatorSupport.COORDINATOR_SYSTEM;
import static com.example.orchestration.CoordinatorSupport.SUBTOPIC_SCHEMA;
import static com.example.orchestration.CoordinatorSupport.arrayOf;
import static com.example.orchestration.CoordinatorSupport.key;
import static com.example.orchestration.CoordinatorSupport.object;
import static com.example.orchestration.CoordinatorSupport.orderedMap;
import static com.example.orchestration.CoordinatorSupport.parse;

/** {@link TopicDecomposer} that asks Claude for subtopics as structured JSON, and re-asks when too narrow. */
public final class LlmTopicDecomposer implements TopicDecomposer {

    public static final int MIN_SUBTOPICS = 5;

    public static final Map<String, Object> SCHEMA = object(orderedMap(
            "subtopics", arrayOf(SUBTOPIC_SCHEMA)));

    private static final Logger LOG = Logger.getLogger("coordinator");

    /** The JSON shape of a decomposition answer. */
    record Decomposition(List<Subtopic> subtopics) {
    }

    private final Llm llm;
    private final int minSubtopics;

    public LlmTopicDecomposer(Llm llm, int minSubtopics) {
        this.llm = llm;
        this.minSubtopics = minSubtopics;
    }

    @Override
    public List<Subtopic> decompose(String topic, List<String> mustCover) {
        String prompt = "Break the research topic '" + topic + "' into at least " + minSubtopics + " distinct subtopics "
                + "that TOGETHER cover the full breadth of the subject. Include smaller, emerging and "
                + "experimental categories, not just the best-known ones. Also include borderline or adjacent "
                + "categories that are commonly discussed alongside this topic, even if their classification "
                + "is debated, and say so in their description. Subtopics must not overlap. "
                + "For each, give a one-sentence scope description and a focused search query.";
        List<Subtopic> subtopics = List.of();
        for (int attempt = 1; attempt <= 2; attempt++) {
            Decomposition d = parse(llm.complete(COORDINATOR_SYSTEM, prompt, SCHEMA), Decomposition.class);
            subtopics = unique(d.subtopics());
            List<String> uncovered = uncovered(mustCover, subtopics);
            String names = subtopics.stream().map(Subtopic::name).collect(Collectors.joining(", "));
            if (subtopics.size() >= minSubtopics && uncovered.isEmpty()) {
                LOG.info("decomposed '" + topic + "' into " + subtopics.size() + " subtopics: " + names);
                return subtopics;
            }

            // Too narrow: tell Claude exactly what is wrong and ask again.
            if (subtopics.size() < minSubtopics) {
                LOG.warning("decomposition attempt " + attempt + " gave only " + subtopics.size() + " subtopics; asking again");
                prompt += "\n\nYour previous answer had only " + subtopics.size() + " subtopics (" + names + "). "
                        + "That is too narrow: it leaves whole categories un-researched. Give at least " + minSubtopics + ".";
            }
            if (!uncovered.isEmpty()) {
                LOG.warning("decomposition attempt " + attempt + " does not cover required: "
                        + String.join(", ", uncovered) + "; asking again");
                prompt += "\n\nYour previous answer (" + names + ") does not cover these required categories: "
                        + String.join(", ", uncovered) + ". Add a subtopic for each of them.";
            }
        }

        if (subtopics.size() < minSubtopics) {
            throw new IllegalStateException("Could not decompose '" + topic + "' into " + minSubtopics + "+ subtopics");
        }
        // Last resort: the coordinator adds the required categories itself.
        List<Subtopic> result = new ArrayList<>(subtopics);
        for (String name : uncovered(mustCover, subtopics)) {
            LOG.warning("adding required subtopic the decomposition kept missing: " + name);
            String title = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            result.add(new Subtopic(title, name + " as part of " + topic, name + " " + topic));
        }
        return result;
    }

    /** Required categories that no subtopic name or description mentions. */
    private static List<String> uncovered(List<String> mustCover, List<Subtopic> subtopics) {
        String text = subtopics.stream().map(s -> s.name() + " " + s.description())
                .collect(Collectors.joining(" ")).toLowerCase(Locale.ROOT);
        return mustCover.stream().filter(k -> !text.contains(k.toLowerCase(Locale.ROOT))).toList();
    }

    private static List<Subtopic> unique(List<Subtopic> subtopics) {
        Map<String, Subtopic> seen = new LinkedHashMap<>();
        for (Subtopic s : subtopics) seen.putIfAbsent(key(s.name()), s);
        return List.copyOf(seen.values());
    }
}
