package bench.traversal;

import bench.World;
import bench.report.RunResult;
import bench.report.RunResult.ScenarioResult;
import bench.traversal.FeatureParser.Scenario;
import bench.traversal.FeatureParser.Step;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs unmodified scenarios as one walk over their shared-prefix tree.
 *
 * <ul>
 *   <li>A prefix shared by several scenarios runs once.</li>
 *   <li>Siblings are ordered: checks first (they don't change state), then
 *       undoable actions, then irreversible ones.</li>
 *   <li>After a branch, the executor undoes its way back. If it can't (no undo,
 *       an irreversible step, a failed action), it replays the path from a fresh
 *       launch before the next sibling.</li>
 *   <li>A failed step fails every scenario below it. Nothing is reported as
 *       skipped, and nothing passes without having run all of its steps.</li>
 *   <li>If replays themselves keep failing, it gives up on the tree and runs the
 *       remaining scenarios one by one from scratch, like mode A.</li>
 * </ul>
 */
public final class TraversalExecutor {

    static final class Node {
        final Step step;
        final StepLibrary.Kind kind;
        final Map<String, Node> children = new LinkedHashMap<>();
        final List<String> endsHere = new ArrayList<>();
        final List<String> below = new ArrayList<>(); // every scenario whose path goes through this node

        Node(Step step, StepLibrary.Kind kind) { this.step = step; this.kind = kind; }

        List<Node> orderedChildren() {
            List<Node> c = new ArrayList<>(children.values());
            // Stable: CHECK, ACTION, IRREVERSIBLE; and among equals, branches with an
            // irreversible step somewhere below go last, so they don't force a replay.
            c.sort(Comparator.<Node>comparingInt(n -> n.kind.ordinal())
                    .thenComparing(n -> n.hasIrreversibleBelow()));
            return c;
        }

        boolean hasIrreversibleBelow() {
            if (kind == StepLibrary.Kind.IRREVERSIBLE) return true;
            for (Node ch : children.values()) if (ch.hasIrreversibleBelow()) return true;
            return false;
        }
    }

    private static final int MAX_FAILED_REPLAYS = 2;

    private final StepLibrary steps;
    private final List<Scenario> scenarios;
    private final Node root = new Node(null, StepLibrary.Kind.CHECK);
    private final Map<String, ScenarioResult> results = new LinkedHashMap<>();
    private RunResult run;
    private boolean dirty;
    private int failedReplaysInARow;
    private boolean fallBack;
    private String lastReplayError;

    public TraversalExecutor(StepLibrary steps, List<Scenario> scenarios) {
        this.steps = steps;
        this.scenarios = scenarios;
        Set<String> names = new HashSet<>();
        for (Scenario s : scenarios) {
            if (!names.add(s.name())) throw new IllegalArgumentException("duplicate scenario name: " + s.name());
            Node at = root;
            at.below.add(s.name());
            for (Step st : s.steps()) {
                at = at.children.computeIfAbsent(st.text(), t -> new Node(st, steps.kind(t)));
                at.below.add(s.name());
            }
            at.endsHere.add(s.name());
        }
    }

    /** How many step executions the tree needs if nothing has to be replayed. */
    public int plannedSteps() { return count(root) ; }

    private int count(Node n) {
        int c = n == root ? 0 : 1;
        for (Node ch : n.children.values()) c += count(ch);
        return c;
    }

    public void run(RunResult into) {
        this.run = into;
        dirty = false;
        for (Scenario s : scenarios) results.put(s.name(), null);
        visit(new ArrayList<>(List.of(root)));
        if (fallBack) runRemainingOneByOne();
        for (Scenario s : scenarios) into.scenarios.add(results.get(s.name()));
        into.fellBack = fallBack;
    }

    private void visit(List<Node> path) {
        Node node = path.get(path.size() - 1);
        for (Node child : node.orderedChildren()) {
            if (fallBack) return;
            if (pendingIn(child) == 0) continue;
            if (dirty && !replay(path, "before '" + child.step.text() + "'")) {
                if (fallBack) return; // the one-by-one run picks this branch up
                fail(child, "(replay of the prefix)", lastReplayError);
                continue;
            }
            List<Runnable> undo;
            try {
                undo = exec(child);
            } catch (Throwable t) {
                fail(child, child.step.text(), message(t));
                // A failed check leaves the screen as it was; a failed action leaves it unknown.
                dirty = child.kind != StepLibrary.Kind.CHECK;
                continue;
            }
            for (String s : child.endsHere) results.put(s, new ScenarioResult(s, "passed", null, null));
            path.add(child);
            visit(path);
            path.remove(path.size() - 1);
            if (fallBack || pendingTotal() == 0) return;
            if (!dirty) restore(child, undo);
        }
    }

    /** Brings the app back from "after child" to "before child", or marks the state dirty. */
    private void restore(Node child, List<Runnable> undo) {
        if (child.kind == StepLibrary.Kind.CHECK) return;
        if (child.kind == StepLibrary.Kind.IRREVERSIBLE || undo.isEmpty()) { dirty = true; return; }
        try {
            for (int i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
        } catch (RuntimeException | AssertionError e) {
            dirty = true;
            run.replays.add("undo of '" + child.step.text() + "' failed: " + message(e));
        }
    }

    private boolean replay(List<Node> path, String why) {
        run.replays.add("replayed " + (path.size() - 1) + " steps " + why);
        try {
            for (int i = 1; i < path.size(); i++) exec(path.get(i));
            dirty = false;
            failedReplaysInARow = 0;
            return true;
        } catch (Throwable t) {
            lastReplayError = message(t);
            run.replays.add("replay failed: " + lastReplayError);
            if (++failedReplaysInARow >= MAX_FAILED_REPLAYS) fallBack = true;
            return false;
        }
    }

    private List<Runnable> exec(Node n) throws Throwable {
        World.takeUndo();
        World.count(n.step.text());
        steps.run(n.step.text());
        return World.takeUndo();
    }

    private void fail(Node n, String step, String msg) {
        for (String s : n.below) {
            if (results.get(s) == null) results.put(s, new ScenarioResult(s, "failed", step, msg));
        }
    }

    private int pendingIn(Node n) {
        int c = 0;
        for (String s : n.below) if (results.get(s) == null) c++;
        return c;
    }

    private int pendingTotal() {
        int c = 0;
        for (ScenarioResult r : results.values()) if (r == null) c++;
        return c;
    }

    /** The avalanche fallback: what's left runs scenario by scenario, from scratch. */
    private void runRemainingOneByOne() {
        for (Scenario s : scenarios) {
            if (results.get(s.name()) != null) continue;
            ScenarioResult r = new ScenarioResult(s.name(), "passed", null, null);
            for (Step st : s.steps()) {
                try {
                    World.takeUndo();
                    World.count(st.text());
                    steps.run(st.text());
                } catch (Throwable t) {
                    r = new ScenarioResult(s.name(), "failed", st.text(), message(t));
                    break;
                }
            }
            results.put(s.name(), r);
        }
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }
}
