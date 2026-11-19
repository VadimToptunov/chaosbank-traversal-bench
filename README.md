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

You need JDK 21, Maven and Appium 2+. For iOS: the `xcuitest` driver and
ChaosBank installed on a booted simulator (`VadimToptunov.ChaosBank`). For
Android: the `uiautomator2` driver, `ANDROID_HOME` exported for Appium, and a
ChaosBank-Android build with `testTagsAsResourceId` on a running emulator.

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

On Android, add `-Dplatform=android` to `JAVA_OPTS`.

- `once A|B`: one run, prints the result JSON.
- `speed N`: N runs of each mode, interleaved (A B, B A, ...) so simulator drift
  hits both. The first run of each mode is dropped as warm-up. Writes `speed.md`.
- `honesty`: one clean run of each mode, then every defect on its own in both
  modes. Writes `honesty.md`.

Results go to `results/<timestamp>/`. Session setup isn't included in the
timing, because it costs the same in both modes.

### Android

The steps don't branch on the platform. Launching and reading do:

- ChaosBank-Android has to expose its Compose test tags as resource ids
  (`testTagsAsResourceId`), on the root and on each bottom sheet, which is a
  window of its own.
- The launch uses `mobile: startActivity` with `component`, not `intent`. The
  driver puts `intent` before the extras, and `am` ignores every option after it.
- A Compose card doesn't carry its children's text the way an iOS combined
  element carries its label, so on Android a card is read child by child.
- UiAutomator2 can return a cached node that still shows the old text, so
  field values are re-read through a fresh lookup.
- The keyboard covers Continue, so it's put away before the tap.

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

## Results (2026-11-19)

Six runs per mode, interleaved, with the first run of each mode dropped.
ChaosBank-iOS at `5100b5a`, ChaosBank-Android at `90586f3` plus the
`testTagsAsResourceId` change. Apple Silicon Mac, Appium 3.6.0.

| | iOS: A | iOS: B | Android: A | Android: B |
|---|---|---|---|---|
| device | iPhone 17 Pro sim, iOS 26.3 | | Pixel 3a emulator, API 34 | |
| median wall time | 53.4 s | 32.8 s | 144.3 s | 59.4 s |
| min – max | 52.9 – 53.9 s | 32.2 – 32.8 s | 140.6 – 153.7 s | 55.0 – 61.3 s |
| app launches per run | 5 | 1 | 5 | 1 |
| step executions per run | 32 | 17 | 32 | 17 |
| prefix replays | – | 0 | – | 0 |
| every scenario green, every run | yes | yes | yes | yes |
| **speed-up** | | **1.63x** | | **2.43x** |

The gap between the platforms is the launch. A cold start of the Compose app on
the emulator costs much more than on the simulator, and B pays it once instead
of five times.

Honesty control: on both platforms, each of the six defects, switched on alone,
turned its scenario red in both modes, with no other reds.

| defect | iOS A | iOS B | Android A | Android B |
|---|---|---|---|---|
| `zeroAmountAccepted` | red | red | red | red |
| `transferNegativeCredits` | red | red | red | red |
| `amountExceedsBalanceAllowed` | red | red | red | red |
| `whitespaceRecipient` | red | red | red | red |
| `transferRoundsUp` | red | red | red | red |
| `transferDebitsWrongAccount` | red | red | red | red |

What the control caught while this was being built:

- An emulator ANR dialog (System UI, then Digital Wellbeing) kept the app from
  starting. The clean runs went red and the report said not to trust the speed
  number.
- After clearing a field, mode B could append new input to what the undo left
  behind (`999999100.40`). Typing now refuses to start on a field that isn't
  empty.
- An earlier iOS number, 1.85x, came from a version without the fresh re-reads
  and the empty-field check. Those checks cost time in both modes, so the
  current 1.63x is the one to quote.
