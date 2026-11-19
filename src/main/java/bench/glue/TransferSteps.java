package bench.glue;

import bench.App;
import bench.Irreversible;
import bench.World;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.openqa.selenium.WebElement;

import java.time.Duration;

/**
 * The one step layer both modes use. Mode A runs it through Cucumber; mode B
 * reads the same annotations and calls the same methods.
 *
 * A step that changes state and can be taken back registers an undo with
 * {@link World#undo}. Mode A ignores undos (every scenario starts from a fresh
 * launch anyway); mode B uses them to move between sibling branches.
 */
public class TransferSteps {

    private static App app() { return World.app; }

    @Given("the app is launched unlocked")
    public void launched() {
        World.memory.clear();
        app().launchFresh();
        World.launches++;
        app().el("home.totalBalance", Duration.ofSeconds(20));
    }

    @Given("I remember the EUR balance")
    public void rememberEur() {
        World.memory.put("eurBefore", App.lastNumber(app().read(app().el("home.account.EUR"))));
    }

    @Given("I open the transfer form")
    public void openTransfer() {
        app().el("home.quickAction.transfer").click();
        app().el("transfer.recipientField");
    }

    @When("I enter recipient {string}")
    public void enterRecipient(String text) {
        type("transfer.recipientField", text);
        World.undo(() -> clear("transfer.recipientField"));
    }

    @When("I enter amount {string}")
    public void enterAmount(String text) {
        type("transfer.amountField", text);
        World.undo(() -> clear("transfer.amountField"));
    }

    @Then("Continue is disabled")
    public void continueDisabled() {
        if (app().el("transfer.continueButton").isEnabled()) {
            throw new AssertionError("Continue is enabled, expected disabled");
        }
    }

    @Then("Continue is enabled")
    public void continueEnabled() {
        if (!app().el("transfer.continueButton").isEnabled()) {
            throw new AssertionError("Continue is disabled, expected enabled");
        }
    }

    @Irreversible
    @When("I continue and confirm")
    public void continueAndConfirm() {
        app().hideKeyboard();
        app().el("transfer.continueButton").click();
        app().el("transfer.confirmButton").click();
        if (!app().gone("transfer.confirmButton", Duration.ofSeconds(20))) {
            throw new AssertionError("the confirm sheet didn't close within 20 s");
        }
    }

    /** Checked on Home, the way a user would see it, not on the success toast. */
    @Then("the EUR balance dropped by {double}")
    public void eurDropped(double amount) {
        Object before = World.memory.get("eurBefore");
        if (!(before instanceof Double eurBefore)) throw new AssertionError("no EUR balance was remembered");
        double expected = eurBefore - amount;
        double last = Double.NaN;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            last = App.lastNumber(app().read(app().el("home.account.EUR")));
            if (Math.abs(last - expected) < 0.005) return;
            sleep(250);
        }
        throw new AssertionError(String.format("EUR is %.2f, expected %.2f (%.2f - %.2f)", last, expected, eurBefore, amount));
    }

    // ---- helpers ----

    private void type(String id, String text) {
        WebElement field = app().el(id);
        // Mode B reaches a field after undoing a sibling. If the undo left text
        // behind, the new input would be appended to it, so refuse to start.
        String before = app().value(field);
        if (!before.isEmpty() && !isPlaceholder(field, before)) {
            throw new IllegalStateException(id + " isn't empty before typing: '" + before + "'");
        }
        field.click();
        field.sendKeys(text);
        // Re-read through a fresh lookup: UiAutomator2 can hand back a cached node
        // that still shows the text from before the recomposition.
        String now = readFresh(id, text, true, Duration.ofSeconds(2));
        // Some keyboards can't type every character (a decimal pad has no minus).
        // Say so instead of testing a value that never got in.
        if (!text.isBlank() && !now.contains(text)) {
            throw new IllegalStateException("typed '" + text + "' into " + id + " but it reads '" + now + "'");
        }
    }

    private void clear(String id) {
        WebElement field = app().el(id);
        field.clear();
        String left = readFresh(id, "", false, Duration.ofMillis(500));
        if (!left.isEmpty() && !isPlaceholder(app().el(id), left)) {
            field = app().el(id);
            field.click();
            field.sendKeys("\b".repeat(left.length()));
        }
    }

    /**
     * The field's value, re-read through fresh lookups until it contains {@code want}
     * (or is empty), for at most {@code budget}. Bounded by time, not by attempts:
     * one lookup costs ~30 ms on Android and several hundred on iOS.
     */
    private String readFresh(String id, String want, boolean contains, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        String now;
        while (true) {
            WebElement f = app().el(id);
            now = app().value(f);
            boolean done = contains ? (want.isBlank() || now.contains(want)) : (now.isEmpty() || isPlaceholder(f, now));
            if (done || System.nanoTime() >= deadline) return now;
            sleep(100);
        }
    }

    private boolean isPlaceholder(WebElement field, String value) {
        String ph = app().platform == App.Platform.IOS ? field.getAttribute("placeholderValue") : null;
        return ph != null && ph.equals(value);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
