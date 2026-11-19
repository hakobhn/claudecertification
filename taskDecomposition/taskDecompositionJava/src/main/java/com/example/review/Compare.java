package com.example.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scores a review against the issues planted in ../sample-pr, and compares the two architectures. */
@SuppressWarnings("unchecked")
public final class Compare {

    private Compare() {
    }

    public record Seeded(String file, int line, String what) {
    }

    public record CrossFile(Set<String> files, String what) {
    }

    record Pair(String fileA, int lineA, String fileB, int lineB, String what) {
    }

    /** Local bugs planted in the sample PR. A finding matches if it is in the same file within 3 lines. */
    public static final List<Seeded> SEEDED = List.of(
            new Seeded("03-orders.js", 7, "getOrder: no sign-in / ownership check"),
            new Seeded("06-inventory.js", 12, "off-by-one: allows one more than in stock"),
            new Seeded("07-catalog.js", 11, "SQL injection via search term"),
            new Seeded("08-shipping.js", 8, "null address on ebook orders"),
            new Seeded("10-reviews.js", 11, "SQL injection via review text"),
            new Seeded("11-wishlist.js", 7, "getWishlist: no sign-in / ownership check"),
            new Seeded("12-invoices.js", 20, "null customer on guest orders"),
            new Seeded("13-tax.js", 8, "VAT rate not divided by 100"));

    /** Cross-file issues: invisible when you look at one file. Matched by the pair of files named. */
    public static final List<CrossFile> CROSS_FILE = List.of(
            new CrossFile(Set.of("04-basket.js", "05-discounts.js"), "applyDiscount returns dollars; basket treats it as cents"),
            new CrossFile(Set.of("04-basket.js", "14-receipts.js"), "receipts imports calculateTotal; basket exports computeTotal"));

    /** Identical code in two files. Judging one and not the other is an attention dilution artefact. */
    static final List<Pair> PAIRS = List.of(
            new Pair("03-orders.js", 7, "11-wishlist.js", 7, "missing sign-in check"),
            new Pair("03-orders.js", 17, "11-wishlist.js", 17, "forEach + push instead of map"));

    static final int WINDOW = 3;

    static List<Map<String, Object>> files(Map<String, Object> review) {
        return (List<Map<String, Object>>) review.get("files");
    }

    static List<Map<String, Object>> findings(Map<String, Object> fileEntry) {
        return (List<Map<String, Object>>) fileEntry.get("findings");
    }

    static boolean flagged(Map<String, Object> review, String file, int line) {
        return files(review).stream().filter(e -> e.get("file").equals(file)).flatMap(e -> findings(e).stream())
                .anyMatch(f -> Math.abs(((Number) f.get("line")).intValue() - line) <= WINDOW);
    }

    static boolean crossCaught(Map<String, Object> review, Set<String> pair) {
        return ((List<Map<String, Object>>) review.get("cross_file_issues")).stream()
                .anyMatch(issue -> ((List<String>) issue.get("files")).containsAll(pair));
    }

    /** Pairs of identical code where the review flagged one copy and not the other. */
    public static List<String> artefacts(Map<String, Object> review) {
        List<String> out = new ArrayList<>();
        for (Pair p : PAIRS) {
            boolean a = flagged(review, p.fileA(), p.lineA()), b = flagged(review, p.fileB(), p.lineB());
            if (a != b) {
                out.add(p.what() + ": flagged in " + (a ? p.fileA() : p.fileB()) + ", passed in " + (a ? p.fileB() : p.fileA()));
            }
        }
        return out;
    }

    public record Score(int findings, List<Seeded> seededCaught, List<CrossFile> crossCaught, List<String> artefacts) {
    }

    public static Score score(Map<String, Object> review) {
        return new Score(
                files(review).stream().mapToInt(e -> findings(e).size()).sum(),
                SEEDED.stream().filter(s -> flagged(review, s.file(), s.line())).toList(),
                CROSS_FILE.stream().filter(c -> crossCaught(review, c.files())).toList(),
                artefacts(review));
    }

    static int count(Map<String, Object> review, String file) {
        return files(review).stream().filter(e -> e.get("file").equals(file)).mapToInt(e -> findings(e).size()).findFirst().orElse(0);
    }

    static String mark(Map<String, Object> review, String file) {
        List<Seeded> bugs = SEEDED.stream().filter(s -> s.file().equals(file)).toList();
        if (bugs.isEmpty()) return "-";
        long hit = bugs.stream().filter(s -> flagged(review, file, s.line())).count();
        return hit + "/" + bugs.size();
    }

    public static String table(List<String> fileNames, Map<String, Object> single, Map<String, Object> multi) {
        StringBuilder sb = new StringBuilder();
        sb.append("file              | single: findings  planted | multi: findings  planted\n");
        sb.append("------------------+--------------------------+-------------------------\n");
        for (String f : fileNames) {
            sb.append(String.format("%-18s| %15d  %7s | %14d  %7s%n", f, count(single, f), mark(single, f), count(multi, f), mark(multi, f)));
        }
        Score s = score(single), m = score(multi);
        sb.append(String.format("%ntotal findings      single %3d   multi %3d%n", s.findings(), m.findings()));
        sb.append(String.format("planted bugs caught single %d/%d   multi %d/%d%n", s.seededCaught().size(), SEEDED.size(), m.seededCaught().size(), SEEDED.size()));
        sb.append(String.format("cross-file caught   single %d/%d   multi %d/%d%n", s.crossCaught().size(), CROSS_FILE.size(), m.crossCaught().size(), CROSS_FILE.size()));
        sb.append("artefacts           single ").append(s.artefacts().isEmpty() ? "none" : s.artefacts()).append('\n');
        sb.append("                    multi  ").append(m.artefacts().isEmpty() ? "none" : m.artefacts()).append('\n');
        appendMissed(sb, "missed by single    ", s);
        appendMissed(sb, "missed by multi     ", m);
        return sb.toString();
    }

    private static void appendMissed(StringBuilder sb, String label, Score score) {
        List<String> missed = SEEDED.stream().filter(x -> !score.seededCaught().contains(x))
                .map(x -> x.file() + ":" + x.line() + " " + x.what()).toList();
        if (!missed.isEmpty()) sb.append(label).append(missed).append('\n');
    }
}
