package com.example.orchestration;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A small internal document library for the document-analysis subagent.
 *
 * <p>Made-up memos from a fictional energy consultancy. Only some subtopics have
 * documents (nothing on tidal or fusion), so the coordinator invokes the
 * document-analysis subagent only where it can add something - "dynamic subagent
 * selection" from the lesson.
 */
public final class Documents {

    private Documents() {
    }

    public record Document(String title, List<String> keywords, String text) {
    }

    public static final List<Document> ALL = List.of(
            new Document("Q3 Solar Portfolio Review", List.of("solar", "photovoltaic", "pv"),
                    "Our 3 utility-scale PV sites averaged a 24% capacity factor in Q3. Module prices fell 18% "
                            + "year on year, but grid-connection queues now delay new sites by 2-4 years. Bifacial modules "
                            + "on single-axis trackers produced 9% more energy than fixed-tilt arrays."),
            new Document("Offshore Wind Maintenance Report", List.of("wind", "offshore", "turbine"),
                    "Turbine availability across the North Sea cluster was 96.1%. Gearbox failures caused 40% of "
                            + "downtime; the 15 MW direct-drive units had none. Jack-up vessel day rates rose 35%, which "
                            + "makes operations and maintenance the largest controllable cost after financing."),
            new Document("Geothermal Feasibility Study - Highland Site", List.of("geothermal"),
                    "Exploration wells found 180 C brine at 2.8 km, which is suitable for a binary-cycle plant of "
                            + "about 25 MW. Drilling is 55% of capital cost and the main risk is a dry well. An enhanced "
                            + "geothermal system (EGS) pilot would widen the usable resource but needs induced-seismicity monitoring."),
            new Document("Biomass Supply Contract Review", List.of("biomass", "bioenergy", "biofuel", "wood pellet"),
                    "The plant burns 1.2 Mt of wood pellets a year. Sustainability certification now covers 92% of "
                            + "supply. Pellet prices are linked to freight costs and moved +/-30% in 18 months. Carbon "
                            + "accounting is contested unless feedstock comes from residues rather than whole trees."));

    /**
     * Documents whose keywords appear as whole words in the subtopic NAME.
     *
     * <p>Matching on the name only keeps selection tight: a subtopic like "Off-grid energy access"
     * whose description merely mentions solar should not pull in the solar portfolio memo.
     */
    public static List<Document> find(String subtopicName) {
        String name = subtopicName.toLowerCase(Locale.ROOT);
        return ALL.stream()
                .filter(d -> d.keywords().stream()
                        .anyMatch(k -> Pattern.compile("\\b" + Pattern.quote(k) + "\\b").matcher(name).find()))
                .toList();
    }
}
