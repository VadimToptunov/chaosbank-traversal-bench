# chaosbank-traversal-bench

Does running a UI suite as a walk over its shared-prefix tree make it faster
without making it lie? This repo measures that on
[ChaosBank](https://github.com/VadimToptunov/ChaosBank-iOS), with Appium,
Gherkin and Java.

Both modes use **the same `.feature` files and the same step definitions**:

- **Mode A** is stock Cucumber-JVM. Each scenario runs its Background, so each
  one starts from a fresh app launch. This is how most teams run Gherkin suites.
- **Mode B** is a small traversal executor
  ([`TraversalExecutor`](src/main/java/bench/traversal/TraversalExecutor.java)).
  It reads the `.feature` files as data and builds a tree of the steps that
  scenarios share. A shared prefix runs once. Then the executor walks the
  branches, undoing its way back between siblings. Each scenario still gets its
  own pass/fail verdict.

## What keeps mode B honest

- A scenario passes only if every one of its steps ran and passed. Shared steps
  count for every scenario that goes through them.
- A failed step fails every scenario below it. Nothing is marked "skipped".
- A step that changes state registers an undo (`World.undo(...)`). If a branch
  can't be undone (no undo, or the step is marked `@Irreversible`), the
  executor relaunches the app and replays the prefix before the next sibling.
  Every replay is listed in the result JSON.
- Checks (`@Then`) run before actions among siblings, and irreversible steps run last.
- If replays keep failing, the executor gives up on the tree and runs what's
  left one scenario at a time, like mode A (`"fellBack": true` in the JSON).
- The positive scenario checks the result by fact: the EUR balance on Home,
  not the success toast.

The speed number only counts together with the **honesty control**. Each
ChaosBank defect is switched on alone, and the scenario that should catch it
has to go red in **both** modes. If B were fast because it skipped a check,
this is where it would show up.

## The suite

[`features/transfer.feature`](features/transfer.feature) has five Transfer
scenarios. They share the Background (launch, remember EUR, open the form), and
four of them share the recipient step too.

| Scenario | Defect it catches (ChaosBank exercise) |
|---|---|
| A zero amount can't be sent | `zeroAmountAccepted` (IOS-VAL-02) |
| A negative amount can't be sent | `transferNegativeCredits` (IOS-VAL-06) |
| An amount over the balance can't be sent | `amountExceedsBalanceAllowed` (IOS-VAL-05) |
| A spaces-only recipient can't be sent to | `whitespaceRecipient` (IOS-VAL-03) |
| A valid transfer debits exactly the amount | `transferRoundsUp`, `transferDebitsWrongAccount` |

## Running it

You need JDK 21, Maven, Appium 2+ with the `xcuitest` driver, and ChaosBank
installed on a booted simulator (`VadimToptunov.ChaosBank`).

```bash
appium --port 4723
```

```bash
JAVA_OPTS="-Dudid=<simulator udid>" ./run.sh once B
```

```bash
JAVA_OPTS="-Dudid=<simulator udid>" ./run.sh speed 6
```

```bash
JAVA_OPTS="-Dudid=<simulator udid>" ./run.sh honesty
```

- `once A|B`: one run, prints the result JSON.
- `speed N`: N runs of each mode, interleaved (A B, B A, ...) so simulator drift
  hits both. The first run of each mode is dropped as warm-up. Writes `speed.md`.
- `honesty`: one clean run of each mode, then every defect on its own in both
  modes. Writes `honesty.md`.

Results go to `results/<timestamp>/`. Session setup isn't included in the
timing, because it costs the same in both modes.

### Android

The step layer and the launch code already handle Android (`-Dplatform=android`,
intent extras instead of launch arguments). ChaosBank-Android doesn't expose
its Compose test tags to UiAutomator yet, though (`testTagsAsResourceId`), so
Appium can't find the elements there. Until the app does that, the numbers
here are iOS only.

## Limits

- One flow, five scenarios. The gain grows with longer shared prefixes and more
  siblings per node, and it shrinks for suites whose scenarios share little.
- The `.feature` parser is minimal: Background, Scenario and plain steps. No
  Scenario Outline, tables or doc strings.
- Undo is written by hand, per step. A step without an undo is still correct,
  just slower in mode B, because it forces a replay.
- Prior art: model-based testing (GraphWalker, AltWalker), state-transition and
  transition-tour coverage, GUI crawlers that build screen graphs. The narrow
  thing tried here is running *unmodified* Gherkin scenarios as a prefix-tree
  traversal while keeping a verdict per scenario.

## Results (2026-11-18)

iPhone 17 Pro simulator, iOS 27.0, Appium 3.6.0 with the xcuitest driver,
ChaosBank-iOS at `5100b5a`, Apple Silicon Mac. Six runs per mode, interleaved,
with the first run of each mode dropped.

| | A: each scenario from scratch | B: prefix-tree traversal |
|---|---|---|
| median wall time | 49.0 s | 26.4 s |
| min – max | 48.7 – 49.7 s | 26.1 – 26.5 s |
| app launches per run | 5 | 1 |
| step executions per run | 32 | 17 |
| prefix replays | – | 0 |
| every scenario green, every run | yes | yes |

That's 1.85x on five scenarios. Honesty control: each of the six defects,
switched on alone, turned its scenario red in both modes, with no other reds.

| defect | A | B |
|---|---|---|
| `zeroAmountAccepted` | red | red |
| `transferNegativeCredits` | red | red |
| `amountExceedsBalanceAllowed` | red | red |
| `whitespaceRecipient` | red | red |
| `transferRoundsUp` | red | red |
| `transferDebitsWrongAccount` | red | red |
