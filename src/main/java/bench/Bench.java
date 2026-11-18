package bench;

import bench.glue.TransferSteps;
import bench.report.CollectingPlugin;
import bench.report.RunResult;
import bench.traversal.FeatureParser;
import bench.traversal.StepLibrary;
import bench.traversal.TraversalExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point.
 *
 * <pre>
 *   ./run.sh once A      one run of mode A (or B)
 *   ./run.sh speed 6     A and B interleaved, 6 runs each
 *   ./run.sh honesty     every defect, both modes
 * </pre>
 * Settings (via JAVA_OPTS): -Dplatform=ios|android -Dudid=... -Dappium=http://127.0.0.1:4723 -Ddefects=a,b
 */
public final class Bench {

    static final Path FEATURES = Path.of("features");

    /** Defect → the scenario that has to go red when it's on. ChaosBank's catalog is the ground truth. */
    static final Map<String, String> DEFECTS = new LinkedHashMap<>();
    static {
        DEFECTS.put("zeroAmountAccepted", "A zero amount can't be sent");
        DEFECTS.put("transferNegativeCredits", "A negative amount can't be sent");
        DEFECTS.put("amountExceedsBalanceAllowed", "An amount over the balance can't be sent");
        DEFECTS.put("whitespaceRecipient", "A spaces-only recipient can't be sent to");
        DEFECTS.put("transferRoundsUp", "A valid transfer debits exactly the amount");
        DEFECTS.put("transferDebitsWrongAccount", "A valid transfer debits exactly the amount");
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("cucumber.publish.quiet", "true");
        String cmd = args.length > 0 ? args[0] : "once";
        Path out = Path.of("results", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Config cfg = Config.fromSystem();
        switch (cmd) {
            case "once" -> {
                RunResult r = runOnce(args.length > 1 ? args[1] : "B", cfg);
                r.write(out.resolve(r.mode + ".json"));
                print(r);
            }
            case "speed" -> speed(cfg, args.length > 1 ? Integer.parseInt(args[1]) : 6, out);
            case "honesty" -> honesty(cfg, out);
            default -> throw new IllegalArgumentException("unknown command " + cmd + " (once | speed | honesty)");
        }
        System.out.println("results: " + out.toAbsolutePath());
    }

    /** One run in a fresh Appium session. Session setup isn't timed: it's the same for both modes. */
    static RunResult runOnce(String mode, Config cfg) throws Exception {
        RunResult r = new RunResult(mode, cfg.platform().name().toLowerCase(), cfg.defects());
        World.app = App.connect(cfg);
        World.resetCounters();
        try {
            long t0 = System.nanoTime();
            if (mode.equalsIgnoreCase("A")) runCucumber(r);
            else runTraversal(r);
            r.wallMs = (System.nanoTime() - t0) / 1_000_000;
        } finally {
            r.launches = World.launches;
            r.stepCounts = new LinkedHashMap<>(World.stepCounts);
            World.app.quit();
        }
        return r;
    }

    /** Mode A: stock Cucumber-JVM. Every scenario runs its Background, i.e. a fresh launch. */
    static void runCucumber(RunResult r) {
        CollectingPlugin.target = r;
        io.cucumber.core.cli.Main.run(new String[] {
                "--glue", "bench.glue",
                "--plugin", CollectingPlugin.class.getName(),
                "--monochrome",
                FEATURES.toString()}, Thread.currentThread().getContextClassLoader());
    }

    /** Mode B: the same .feature files and the same glue, walked as a prefix tree. */
    static void runTraversal(RunResult r) throws Exception {
        List<FeatureParser.Scenario> scenarios = FeatureParser.parseDir(FEATURES);
        new TraversalExecutor(new StepLibrary(TransferSteps.class), scenarios).run(r);
    }

    static void speed(Config cfg, int runs, Path out) throws Exception {
        List<RunResult> a = new ArrayList<>(), b = new ArrayList<>();
        for (int i = 1; i <= runs; i++) {
            // Interleaved, so simulator warm-up and drift hit both modes alike.
            for (String mode : i % 2 == 1 ? List.of("A", "B") : List.of("B", "A")) {
                RunResult r = runOnce(mode, cfg);
                r.write(out.resolve(mode + "-" + i + ".json"));
                (mode.equals("A") ? a : b).add(r);
                System.out.printf("run %d %s: %.1f s, %d launches, %d steps, %d/%d passed%n", i, mode,
                        r.wallMs / 1000.0, r.launches, r.totalSteps(),
                        r.scenarios.stream().filter(RunResult.ScenarioResult::passed).count(), r.scenarios.size());
            }
        }
        String md = speedTable(cfg, a, b);
        Files.writeString(out.resolve("speed.md"), md);
        System.out.println(md);
    }

    static String speedTable(Config cfg, List<RunResult> a, List<RunResult> b) {
        StringBuilder s = new StringBuilder();
        s.append("# Speed: A (each scenario from scratch) vs B (prefix-tree traversal)\n\n");
        s.append("Platform: ").append(cfg.platform()).append(". Runs per mode: ").append(a.size())
                .append(", the first of each dropped as warm-up.\n\n");
        s.append("| | A | B |\n|---|---|---|\n");
        Stats sa = Stats.of(a), sb = Stats.of(b);
        s.append(String.format("| median wall time | %.1f s | %.1f s |%n", sa.median, sb.median));
        s.append(String.format("| min – max | %.1f – %.1f s | %.1f – %.1f s |%n", sa.min, sa.max, sb.min, sb.max));
        s.append(String.format("| app launches per run | %d | %d |%n", a.get(0).launches, b.get(0).launches));
        s.append(String.format("| step executions per run | %d | %d |%n", a.get(0).totalSteps(), b.get(0).totalSteps()));
        s.append(String.format("| \"I open the transfer form\" runs | %d | %d |%n",
                a.get(0).stepCounts.getOrDefault("I open the transfer form", 0),
                b.get(0).stepCounts.getOrDefault("I open the transfer form", 0)));
        s.append(String.format("| prefix replays in B | – | %d |%n", b.get(0).replays.size()));
        boolean allGreen = a.stream().allMatch(Bench::green) && b.stream().allMatch(Bench::green);
        s.append(String.format("| every scenario green in every run | %s | |%n", allGreen ? "yes" : "NO, see the JSON"));
        s.append(String.format("%nSpeed-up (median A / median B): %.2fx%n", sa.median / sb.median));
        return s.toString();
    }

    static boolean green(RunResult r) {
        return r.scenarios.stream().allMatch(RunResult.ScenarioResult::passed);
    }

    record Stats(double median, double min, double max) {
        static Stats of(List<RunResult> runs) {
            List<Double> t = runs.stream().skip(runs.size() > 1 ? 1 : 0).map(r -> r.wallMs / 1000.0).sorted().toList();
            int n = t.size();
            double med = n % 2 == 1 ? t.get(n / 2) : (t.get(n / 2 - 1) + t.get(n / 2)) / 2;
            return new Stats(med, t.get(0), t.get(n - 1));
        }
    }

    /**
     * The control that makes the speed number mean anything: with each defect on,
     * the scenario that should catch it has to go red in BOTH modes. If B were fast
     * because it skipped checks, this is where it would show.
     */
    static void honesty(Config cfg, Path out) throws Exception {
        StringBuilder s = new StringBuilder("# Honesty control: each defect on its own, both modes\n\n");
        s.append("| defect | scenario expected red | A | B | other reds A / B |\n|---|---|---|---|---|\n");
        boolean ok = true;
        for (RunResult clean : List.of(runOnce("A", cfg.withDefects(List.of())), runOnce("B", cfg.withDefects(List.of())))) {
            clean.write(out.resolve("clean-" + clean.mode + ".json"));
            if (!green(clean)) { ok = false; s.append("| (clean) | none | ").append(clean.mode).append(" has reds, see JSON | | |\n"); }
        }
        for (Map.Entry<String, String> d : DEFECTS.entrySet()) {
            Config c = cfg.withDefects(List.of(d.getKey()));
            RunResult a = runOnce("A", c), b = runOnce("B", c);
            a.write(out.resolve(d.getKey() + "-A.json"));
            b.write(out.resolve(d.getKey() + "-B.json"));
            boolean redA = !a.scenario(d.getValue()).passed(), redB = !b.scenario(d.getValue()).passed();
            ok &= redA && redB;
            s.append(String.format("| `%s` | %s | %s | %s | %s / %s |%n", d.getKey(), d.getValue(),
                    redA ? "red" : "**GREEN**", redB ? "red" : "**GREEN**", others(a, d.getValue()), others(b, d.getValue())));
            System.out.printf("%s: A %s, B %s%n", d.getKey(), redA ? "red" : "GREEN", redB ? "red" : "GREEN");
        }
        s.append(ok ? "\nEvery defect is caught by both modes.\n" : "\nAt least one defect slipped through. Don't publish a speed number until this is fixed.\n");
        Files.writeString(out.resolve("honesty.md"), s.toString());
        System.out.println(s);
    }

    static String others(RunResult r, String expected) {
        long n = r.scenarios.stream().filter(x -> !x.passed() && !x.name().equals(expected)).count();
        return String.valueOf(n);
    }

    static void print(RunResult r) {
        System.out.println(r.toJson());
    }
}
