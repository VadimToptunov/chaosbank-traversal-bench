package bench.traversal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads .feature files as data: Background + Scenario, Given/When/Then/And/But.
 * Enough for this benchmark. Scenario Outline, doc strings and data tables are
 * rejected loudly rather than half-supported.
 */
public final class FeatureParser {
    private FeatureParser() { }

    public record Step(String keyword, String text) { }

    public record Scenario(String feature, String name, List<Step> steps) { }

    public static List<Scenario> parseDir(Path dir) throws IOException {
        List<Scenario> all = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".feature")).sorted().toList()) {
                all.addAll(parse(f));
            }
        }
        return all;
    }

    public static List<Scenario> parse(Path file) throws IOException {
        String feature = file.getFileName().toString();
        List<Step> background = new ArrayList<>();
        List<Scenario> out = new ArrayList<>();
        String current = null;          // scenario name, or null while in Background
        List<Step> steps = background;
        String lastKeyword = "Given";
        int n = 0;
        for (String raw : Files.readAllLines(file)) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("@")) continue;
            if (line.startsWith("Feature:")) { feature = line.substring(8).strip(); continue; }
            if (line.startsWith("Background:")) { current = null; steps = background; continue; }
            if (line.startsWith("Scenario Outline:") || line.startsWith("Examples:")
                    || line.startsWith("\"\"\"") || line.startsWith("|")) {
                throw new IllegalArgumentException(file + ":" + n + ": not supported by the traversal parser: " + line);
            }
            if (line.startsWith("Scenario:")) {
                if (current != null) out.add(new Scenario(feature, current, withBackground(background, steps)));
                current = line.substring(9).strip();
                steps = new ArrayList<>();
                continue;
            }
            int sp = line.indexOf(' ');
            String kw = sp < 0 ? line : line.substring(0, sp);
            if (!List.of("Given", "When", "Then", "And", "But", "*").contains(kw)) {
                if (current == null && steps == background && background.isEmpty()) continue; // feature description
                throw new IllegalArgumentException(file + ":" + n + ": can't read line: " + line);
            }
            String effective = kw.equals("And") || kw.equals("But") || kw.equals("*") ? lastKeyword : kw;
            lastKeyword = effective;
            steps.add(new Step(effective, line.substring(sp + 1).strip()));
        }
        if (current != null) out.add(new Scenario(feature, current, withBackground(background, steps)));
        return out;
    }

    private static List<Step> withBackground(List<Step> background, List<Step> own) {
        List<Step> all = new ArrayList<>(background);
        all.addAll(own);
        return List.copyOf(all);
    }
}
