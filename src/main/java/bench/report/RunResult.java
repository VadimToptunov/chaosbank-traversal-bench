package bench.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One run of one mode. Both modes produce the same shape, so the results compare row for row. */
public final class RunResult {
    public final String mode;
    public final String platform;
    public final List<String> defects;
    public long wallMs;
    public int launches;
    public Map<String, Integer> stepCounts = new LinkedHashMap<>();
    public final List<ScenarioResult> scenarios = new ArrayList<>();
    /** Mode B: how many times it had to replay a prefix from a fresh launch, and why. */
    public final List<String> replays = new ArrayList<>();
    public boolean fellBack;

    public RunResult(String mode, String platform, List<String> defects) {
        this.mode = mode;
        this.platform = platform;
        this.defects = defects;
    }

    public record ScenarioResult(String name, String status, String failedStep, String message) {
        public boolean passed() { return status.equals("passed"); }
    }

    public int totalSteps() {
        return stepCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public ScenarioResult scenario(String name) {
        return scenarios.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    public String toJson() {
        StringBuilder b = new StringBuilder("{\n");
        b.append("  \"mode\": ").append(q(mode)).append(",\n");
        b.append("  \"platform\": ").append(q(platform)).append(",\n");
        b.append("  \"defects\": [").append(String.join(", ", defects.stream().map(RunResult::q).toList())).append("],\n");
        b.append("  \"wallMs\": ").append(wallMs).append(",\n");
        b.append("  \"launches\": ").append(launches).append(",\n");
        b.append("  \"stepExecutions\": ").append(totalSteps()).append(",\n");
        b.append("  \"fellBack\": ").append(fellBack).append(",\n");
        b.append("  \"replays\": [").append(String.join(", ", replays.stream().map(RunResult::q).toList())).append("],\n");
        b.append("  \"stepCounts\": {");
        b.append(String.join(", ", stepCounts.entrySet().stream().map(e -> q(e.getKey()) + ": " + e.getValue()).toList()));
        b.append("},\n  \"scenarios\": [\n");
        for (int i = 0; i < scenarios.size(); i++) {
            ScenarioResult s = scenarios.get(i);
            b.append("    {\"name\": ").append(q(s.name())).append(", \"status\": ").append(q(s.status()));
            if (s.failedStep() != null) b.append(", \"failedStep\": ").append(q(s.failedStep()));
            if (s.message() != null) b.append(", \"message\": ").append(q(s.message()));
            b.append("}").append(i < scenarios.size() - 1 ? ",\n" : "\n");
        }
        return b.append("  ]\n}\n").toString();
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson());
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
}
