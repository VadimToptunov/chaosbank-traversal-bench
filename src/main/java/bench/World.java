package bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * State shared by the steps and both executors. Cucumber creates a new glue
 * instance per scenario, so the session and the counters live here.
 */
public final class World {
    private World() { }

    public static App app;

    /** Scenario-level memory (e.g. the EUR balance before a transfer). Reset on every fresh launch. */
    public static final Map<String, Object> memory = new LinkedHashMap<>();

    /** How many times each step text was executed in the current run. */
    public static final Map<String, Integer> stepCounts = new LinkedHashMap<>();
    public static int launches;

    /** Undo actions a step registers while it runs; the traversal executor collects them. */
    private static final List<Runnable> pendingUndo = new ArrayList<>();

    public static void undo(Runnable r) { pendingUndo.add(r); }

    public static List<Runnable> takeUndo() {
        List<Runnable> out = new ArrayList<>(pendingUndo);
        pendingUndo.clear();
        return out;
    }

    public static void count(String stepText) {
        stepCounts.merge(stepText, 1, Integer::sum);
    }

    public static void resetCounters() {
        stepCounts.clear();
        launches = 0;
        memory.clear();
        pendingUndo.clear();
    }
}
