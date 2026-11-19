package com.example.review;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Build Exercise 1.6 - multi-pass code review vs single-pass review.
 * <pre>
 *   mvn -q compile exec:java                                  # review ../sample-pr both ways and compare
 *   mvn -q compile exec:java "-Dexec.args=&lt;directory&gt;"   # any directory with 10+ .js/.ts files
 * </pre>
 * Results are saved to results.json, so you can read every finding.
 */
public final class Main {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "../sample-pr");
        Map<String, String> files = Review.loadFiles(directory);
        System.out.println("Reviewing " + files.size() + " files from " + directory + "\n");

        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        try {
            Llm llm = Llm.claude(client);

            long t = System.currentTimeMillis();
            Map<String, Object> single = Review.singlePass(files, llm);
            System.out.printf("single pass: 1 call, %ds%n", (System.currentTimeMillis() - t) / 1000);

            t = System.currentTimeMillis();
            Map<String, Object> multi = Review.multiPass(files, llm, 7);
            System.out.printf("multi-pass:  %d local calls + 1 integration call, %ds%n%n", files.size(), (System.currentTimeMillis() - t) / 1000);

            System.out.println(Compare.table(new ArrayList<>(files.keySet()), single, multi));
            System.out.println("Cross-file issues from the integration pass:");
            for (Map<String, Object> issue : (List<Map<String, Object>>) multi.get("cross_file_issues")) {
                System.out.println("  [" + issue.get("severity") + "] " + issue.get("files") + ": " + issue.get("description"));
            }
            Files.writeString(Path.of("results.json"), Review.toJson(Map.of("single", single, "multi", multi)));
            System.out.println("\nAll findings saved to results.json");
        } finally {
            client.close();
        }
    }
}
