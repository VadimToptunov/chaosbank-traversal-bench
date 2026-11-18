package bench.report;

import bench.World;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestStepFinished;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mode A's reporter: turns Cucumber's events into the same {@link RunResult}
 * the traversal executor fills, and counts step executions the same way.
 */
public final class CollectingPlugin implements ConcurrentEventListener {
    /** Set by the bench before each mode-A run. */
    public static RunResult target;

    private final Map<UUID, String[]> firstFailure = new HashMap<>();

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestStepFinished.class, e -> {
            if (!(e.getTestStep() instanceof PickleStepTestStep step)) return;
            Result r = e.getResult();
            if (r.getStatus() == Status.SKIPPED) return; // never executed
            World.count(step.getStep().getText());
            if (r.getStatus() != Status.PASSED) {
                firstFailure.putIfAbsent(e.getTestCase().getId(), new String[] {
                        step.getStep().getText(),
                        r.getError() != null ? r.getError().getMessage() : r.getStatus().name()});
            }
        });
        publisher.registerHandlerFor(TestCaseFinished.class, e -> {
            String[] f = firstFailure.get(e.getTestCase().getId());
            boolean passed = e.getResult().getStatus() == Status.PASSED;
            target.scenarios.add(new RunResult.ScenarioResult(e.getTestCase().getName(),
                    passed ? "passed" : "failed", f == null ? null : f[0], f == null ? null : f[1]));
        });
    }
}
