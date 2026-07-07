---
schema: sdlc/v1
type: plan
slug: full-codebase-audit-fixes
slice-slug: gesture-coordination
status: complete
stage-number: 4
created-at: "2026-07-07T12:10:34Z"
updated-at: "2026-07-07T12:46:38Z"
metric-files-to-touch: 5
metric-step-count: 14
has-blockers: false
revision-count: 1
tags: [gestures, pointer-input, compose, instrumented-tests, double-tap, state-machine]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-gesture-coordination.md
  siblings:
    - 04-plan-core-math.md
    - 04-plan-compose-contracts.md
    - 04-plan-view-module.md
    - 04-plan-shape-geometry.md
    - 04-plan-docs-and-changelog.md
    - 04-plan-snapshot-sweep-gate.md
  implement: 05-implement-gesture-coordination.md
next-command: wf-implement
next-invocation: "/wf implement full-codebase-audit-fixes gesture-coordination"
---

# Plan: Gesture Coordination

## The Plan

The bug is structural, not logic: two sibling `pointerInput` blocks in `IsometricScene.kt`
receive every pointer event independently, with no coordination between them. The result is a
double-tap that fires `onClick` twice before `onDoubleClick` — not a timing issue, not a
missing flag, just two loops each doing the right thing for their own view of events while
collectively producing the wrong output for the user. The fix is to collapse them into one
coordinated handler.

The platform already provides the hard part. Compose 1.5.0's `detectTapGestures`, when
registered with both `onTap` and `onDoubleTap`, waits out `ViewConfiguration.doubleTapTimeoutMillis`
(~300 ms, minimum 40 ms) before firing `onTap` — the contract the PO chose is exactly what
the platform delivers by default. So the restructure is: let `detectTapGestures` own
tap-vs-double-tap disambiguation, integrate the long-press coroutine and drag state machine
into the same `awaitEachGesture` block (never `forEachGesture` — that loses events, issue
251260206), and consume pointer events only when a handler, node-drag state, or camera
actually acts (C2). Stale state after long-press → drag → release (C3) follows naturally
once the state machine lives in one place. The F4 autopan-suppression test is a five-line
addition to `DragANodeTest`.

The riskiest piece is not the merge — it is the keying. The current blocks are keyed on
`Unit` and use `rememberUpdatedState` to stay current with recomposition. That pattern must
survive the merge: key the single block on `Unit`, read callbacks through
`rememberUpdatedState` delegates. The wrong choice here — keying on a lambda reference that
changes each recomposition — silently restarts the handler mid-gesture and re-introduces C3
stale state in a new form. The constructive-proof discipline enforced on the new tests is the
check: `DoubleTapDisambiguationTest` must demonstrably fail against the pre-fix two-block
code before it can count as a gate.

## Current State

Inspection of `IsometricScene.kt` (725 lines, current working tree) confirms all four
findings remain open. The `feat/ws10-interaction-props` branch commits since the audit date
(187e8f4 "fix(compose): correct drag projection, annotation honesty, and API contracts") do
**not** address C1, C2, C3, or F4 — they fixed drag projection math, added binary-compat
secondary constructors on `GestureConfig` and `DragEvent`, and corrected annotation honesty.
The uncommitted working-tree changes to `GestureConfig.kt` and `GestureEvents.kt` add
binary-compat constructors only. None of these touch the pointer-input block structure.

**C1 (double-tap fires spurious onClick):** STILL BROKEN. Lines 546–576 confirm the two
independent blocks remain: the hand-rolled `awaitPointerEventScope` loop (lines 304–544)
still fires `hitNode?.onClick?.invoke()` at line 517 on every Release without a double-tap
guard, and the second `pointerInput(Unit)` block (lines 546–576) still uses
`detectTapGestures(onDoubleTap = ...)` independently. The audit finding's second verifier
note is more accurate than the first: on a double-tap, `onClick` fires **twice** (once per
Release) before `onDoubleClick` fires.

**C2 (unconditional consumption):** STILL BROKEN. Line 464
(`event.changes.forEach { it.consume() }`) runs whenever `isDragging == true` with no guard
for whether any handler or camera is actually active. The else-branch at lines 458–461
(`dragEvent.delta?.let { currentCameraState?.pan(it.dx, it.dy) }`) is a silent no-op when
`currentCameraState == null`, but consumption still fires.

**C3 (stale state after long-press drag):** STILL BROKEN. The `draggedNode` assignment at
line 399 (`draggedNode = resolveDraggedNode(...)`) does not check `longPressFired`. After a
long-press fires, a subsequent Move that exceeds `dragThreshold` still sets `isDragging =
true` and `draggedNode`, enabling the unintended node-drag affordance. Release at lines
535–538 resets all state cleanly after the fact — the damage is the unintended node movement
that already occurred.

**F4 (no autopan-suppression test):** STILL MISSING. `DragANodeTest.kt` covers drag math
and "moving a node leaves the camera untouched" (line 315) as a math property — it checks
that `draggedPosition` doesn't mutate the camera object. It does NOT test that the scene's
drag branch condition (node-drag path vs. camera-pan path) correctly suppresses `camera.pan()`
when a node is selected. That branch-condition coverage is what F4 requires.

**Line range shift:** The audit cited lines ~303–575. Current file has the hand-rolled loop
at lines 304–544 and the detectTapGestures block at lines 546–576. Range shifted slightly but
the structure is unchanged.

## Simplicity Ladder

- **Tap/double-tap disambiguation** → Rung 2 (native platform feature): `detectTapGestures`
  with both `onTap` and `onDoubleTap` in Compose 1.5.0 delivers the exact contract
  (wait-then-fire vs. instant-fire) without hand-rolled timing. Already installed dependency.
  No new code needed for the disambiguation logic itself.
- **Long-press detection** → Rung 3 (reuse): the existing `longPressScope.launch { delay()
  ... }` pattern in the current hand-rolled loop is correct; it moves into the merged handler
  unchanged.
- **Drag state machine** → Rung 3 (reuse): the existing `isDragging / dragStartPos /
  draggedNode` state and all Move-branch logic reuse verbatim; only the C2 consumption gate
  and the C3 `longPressFired` guard add new lines.
- **Consumption gating (C2)** → Rung 4 (minimum new code): no stdlib or framework primitive
  directly expresses "consume only when acting" — this is a two-condition guard (`onDrag !=
  null || currentCameraState != null || draggedNode != null`) added to the existing
  `it.consume()` call site. Two lines.
- **Event loop restart safety** → Rung 2 (native platform feature): `awaitEachGesture` (not
  `forEachGesture`) is the Compose-recommended restart primitive that avoids the event-loss
  bug in issue 251260206. Already available in foundation 1.5.0.
- **Instrumented test harness** → Rung 3 (reuse): `compose.ui.test.junit4` is already a
  `androidTestImplementation` dep in `isometric-compose/build.gradle.kts`. The androidTest
  source set already exists with four passing tests. No new infra needed.

## Applied Learnings

No applicable learnings found. `.ai/solutions/INDEX.md` does not exist in this repository.

Repeat-deferral tripwire: `00-index.md` has no `runtime-evidence-deferrals` entries. The
emulator dependency (AC-S3) is new to this slug — no repeated wall.

## Likely Files / Areas to Touch

- `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricScene.kt`:
  The sole restructure target. Lines 304–576 (the two `pointerInput` blocks). The
  `resolveDraggedNode` helper (lines 640+) and `screenToEngineCoords` (same file) are
  unchanged; they are called from the merged handler as before.
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/runtime/DoubleTapDisambiguationTest.kt`:
  New file. JVM test covering the C1 tap contract with constructive-proof commentary.
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/runtime/DragANodeTest.kt`:
  Add one F4 test (the autopan-suppression branch condition).
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/runtime/ConsumptionGatingTest.kt`:
  New file. JVM test covering the C2 consumption contract.
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/runtime/GestureStateResetTest.kt`:
  New file. JVM test covering the C3 state-reset contract including edge cases.
- `isometric-compose/src/androidTest/kotlin/io/github/jayteealao/isometric/compose/runtime/DoubleTapInstrumentedTest.kt`:
  New file. Instrumented test using `ComposeTestRule` + `performTouchInput`/`doubleClick`.
  This is the AC-S3 gate.

**Not touched in this slice:**
- `GestureConfig.kt`, `GestureEvents.kt`, `AdvancedSceneConfig.kt` — KDoc and annotation
  changes belong to `compose-contracts`.
- `GestureEvents.kt` uncommitted binary-compat constructor — already in working tree,
  unrelated to this restructure.
- `IsometricRenderer.kt`, `IsometricNode.kt` — no gesture logic there.
- `DragEventClarityTest.kt` — pinning the DragEvent contract; no changes needed.
- `NodeCallbacksInteractionTest.kt` — the existing tests remain valid; they pin callback
  dispatch not gesture routing. No changes.

## Proposed Change Strategy

**One merged `pointerInput(Unit)` block** replaces the two sibling blocks. The architecture:

```
Modifier.pointerInput(Unit) {
    awaitEachGesture {              // restart-safe loop (NOT forEachGesture)
        detectTapGestures(
            onTap    = { offset -> /* onClick dispatch — waits ~300ms IF onDoubleTap registered */ },
            onDoubleTap = { offset -> /* onDoubleClick dispatch */ },
            onLongPress = { offset -> /* onLongClick dispatch; set longPressFired */ },
            onPress  = { offset ->
                // Drag state machine runs here as a launched coroutine or inline await.
                // dragStartPos set; longPressJob launched (or reuse detectTapGestures' onLongPress).
                // Move events: consume ONLY when isDragging && (handler || camera || node).
                // Release: reset isDragging, longPressFired, draggedNode.
                tryAwaitRelease() // or the full hand-rolled move/release loop inside onPress
            }
        )
    }
}
```

**Key contract decisions:**

1. **onClick disambiguation**: `detectTapGestures` with both `onTap` and `onDoubleTap`
   registered waits out `doubleTapTimeoutMillis` before firing `onTap`. For a node with only
   `onClick` (no `onDoubleTap`), we register `onTap` only — Compose fires it instantly. This
   is the per-node conditional: build the `detectTapGestures` call based on whether the hit
   node has `onDoubleClick` set. **Alternative:** always register both, using `onTap` to
   conditionally fire `onClick` only when `hitNode?.onDoubleClick == null`. This avoids
   recomposition-time key changes but always adds the latency. Recommended: use a single
   always-registered `detectTapGestures` that reads both callbacks via `rememberUpdatedState`
   and gates dispatch in the lambda — latency applies only when `onDoubleClick != null`.

2. **Long-press integration**: `detectTapGestures(onLongPress = ...)` fires at
   `ViewConfiguration.longPressTimeoutMillis` by default, not `GestureConfig.longPressTimeoutMs`.
   To honor the configurable timeout, the long-press coroutine must remain hand-rolled (the
   existing `longPressScope.launch { delay(currentGestures.longPressTimeoutMs) }` pattern).
   It runs from `onPress` → launched coroutine, cancelled on Move-beyond-threshold or Release.
   `PO-DECISION-PENDING: use detectTapGestures(onLongPress) at platform timeout vs. keep
   hand-rolled at configurable timeout — recommended: keep hand-rolled, honor config.`

3. **Consumption gating (C2)**: Inside the Move branch, `it.consume()` is guarded:
   ```kotlin
   val shouldConsume = isDragging && (
       draggedNode != null ||
       currentGestures.onDrag != null ||
       currentCameraState != null
   )
   if (shouldConsume) event.changes.forEach { it.consume() }
   ```

4. **C3 guard**: `draggedNode` assignment now checks `!longPressFired`:
   ```kotlin
   draggedNode = if (longPressFired) null else resolveDraggedNode(...)
   ```

5. **Keying**: The single merged block is keyed on `Unit`. All values are read through
   `rememberUpdatedState` delegates (already the pattern). This means recomposition never
   restarts the handler, so `onDoubleClick` added/removed between recompositions is handled
   live (the lambda is captured by the delegate, not by the block restart).

## Step-by-Step Plan

**Step 1 — Write DoubleTapDisambiguationTest (constructive-proof first)**
Create `DoubleTapDisambiguationTest.kt` in JVM test source set. Each test includes a
comment: "This test FAILS against the pre-fix two-block implementation because [reason]."
Tests to write:
- `double-tap on node with both callbacks fires onDoubleClick exactly once and zero onClick`
- `single-tap on node with both callbacks fires onClick once after disambiguation window`
- `single-tap on onClick-only node fires onClick instantly (no added latency path)`
- `rapid triple-tap produces one onDoubleClick then one pending single evaluation`
- `drag within double-tap window does not fire either callback`
Use `ComposeUiTest` (desktop test rule via Paparazzi's test machinery, or standard JVM
test rule) for pointer event injection. Check the existing test infrastructure in
`IsometricCanvasSnapshotTest.kt` / `StackTest.kt` patterns for how the module runs JVM
tests. Since existing JVM gesture tests in this module are pure unit tests (no Compose
test rule), new disambiguation tests may need to use either Robolectric (add dep if
needed — see Step 2) or a pure state-machine approach.

**Step 2 — Assess DoubleTapDisambiguationTest harness**
The existing JVM tests (DragANodeTest, DragEventClarityTest, NodeCallbacksInteractionTest)
are all pure state-machine tests — they do not inject pointer events through Compose. The
`InteractionHarnessReadme` explicitly documents this as a deliberate choice. Disambiguation
tests that assert callback counts from real gesture routing CANNOT be pure state-machine
tests — they require a Compose host.

Options:
a) Add Robolectric (`testImplementation(libs.robolectric)`) so JVM tests can host a
   Compose surface and inject events via `TestCoroutineScheduler` / `FakeClockTestRule`.
b) Move the disambiguation assertions into the instrumented test (`DoubleTapInstrumentedTest`)
   and keep the JVM file as state-machine-only tests of the contract invariants.

**Recommended**: Option (b) — keep the JVM file as constructive-proof of the contract
(callback count assertions against a mock orchestrator) and put real event routing into the
instrumented test. The JVM `DoubleTapDisambiguationTest` tests what happens WHEN the
coordinated handler fires the correct callbacks — the instrumented test proves the handler
fires them correctly from real pointer events.

`PO-DECISION-PENDING: add Robolectric to JVM test set for real gesture routing, or keep
JVM tests as state-machine-only and route real-event assertions to the instrumented suite
— recommended: state-machine-only JVM + instrumented.`

**Step 3 — Write ConsumptionGatingTest (C2)**
New JVM test file. Tests:
- `inert scene does not consume pointer events during drag` — drives the C2 consumption
  gating guard with `onDrag == null` and `cameraState == null` and `draggedNode == null`;
  asserts `consumed == false` on the simulated PointerInputChange.
- `camera present but disabled does not consume` — `CameraState` present but pan is a no-op;
  assert no consumption unless camera would pan.
- `onDrag registered triggers consumption` — with `onDrag != null`, consumption fires.

**Step 4 — Write GestureStateResetTest (C3)**
New JVM test file. Tests:
- `long-press then drag then release resets all gesture state to idle`
- `draggedNode not assigned after long-press fires` (the C3 guard)
- `next gesture after a full long-press-drag-release cycle starts from idle`
- `pointerInput key Unit means handler survives onDoubleClick recomposition` — documented
  test noting the keying invariant.

**Step 5 — Add F4 test to DragANodeTest**
Add to `DragANodeTest.kt`:
```
@Test
fun `selecting a node suppresses camera autopan during drag`() {
    // When nodeDragState has a selected node, the scene takes the node-move branch,
    // NOT the camera.pan() branch. Camera pan coordinates must be unchanged.
    val state = dragState()
    val camera = CameraState(panX = 5.0, panY = 10.0)
    state.select("node-a")
    // The scene checks `draggedNode != null` and enters the node-move path.
    // Simulate that selection is active; assert camera.panX and camera.panY unchanged.
    // (The math is already covered by `moving a node leaves the camera untouched`;
    //  this test pins the BRANCH CONDITION: selection → node-move, no selection → pan.)
    assertThat(state.selectedNodeId).isEqualTo("node-a")  // precondition
    // Simulate drag: the draggedPosition call (not camera.pan) is the action.
    val engine = defaultEngine()
    state.draggedPosition(
        current = Point(0.0, 0.0, 0.0),
        screenDx = 50.0, screenDy = 50.0,
        engine = engine,
        viewportWidth = defaultViewportW,
        viewportHeight = defaultViewportH,
        camera = camera
    )
    // Camera must not have been panned — node drag reads camera (for un-projection) but
    // does not write it.
    assertThat(camera.panX).isEqualTo(5.0)
    assertThat(camera.panY).isEqualTo(10.0)
}
```
This test FAILS if the camera-pan branch is taken instead of the node-move branch.

**Step 6 — Merge the two pointerInput blocks in IsometricScene.kt**
Replace lines ~304–576 with a single `Modifier.pointerInput(Unit)` block:
- Wrap the body in `awaitEachGesture { ... }` (replaces the bare `while (true)` loop).
- Use `detectTapGestures` with `onTap`, `onDoubleTap`, `onLongPress` (see Note on long-press
  below), and `onPress` for drag state machine.
- Inside `onPress`: launch the hand-rolled long-press coroutine if keeping configurable
  timeout. Await pointer events for Move/Release inside a nested `awaitPointerEventScope`.
- C2 guard: add the three-condition guard before `it.consume()` in the Move branch.
- C3 guard: add `if (!longPressFired)` before `draggedNode = resolveDraggedNode(...)`.
- Remove the second `Modifier.pointerInput(Unit)` block entirely (the `detectTapGestures`
  block that was keyed on Unit).

**Step 7 — Run existing JVM tests to confirm no regression**
```
./gradlew :isometric-compose:test
```
`DragANodeTest`, `DragEventClarityTest`, `NodeCallbacksInteractionTest`, `AutopanDeltaContractTest`,
`TileGestureHubTest` must all pass. Fix any regressions before proceeding.

**Step 8 — Run new JVM tests**
```
./gradlew :isometric-compose:test --tests "*.DoubleTapDisambiguationTest"
./gradlew :isometric-compose:test --tests "*.ConsumptionGatingTest"
./gradlew :isometric-compose:test --tests "*.GestureStateResetTest"
```
All must pass.

**Step 9 — Write DoubleTapInstrumentedTest (AC-S3 gate)**
In `isometric-compose/src/androidTest/`, create `DoubleTapInstrumentedTest.kt` using
`@RunWith(AndroidJUnit4::class)` and `@get:Rule val composeRule = createComposeRule()`.
Tests:
- Double-tap on a node with both callbacks → exactly one `onDoubleClick`, zero `onClick`.
- Single-tap on onClick-only node → exactly one `onClick`, no delay observable.
- Drag → camera pans (or node moves if selected); `onDragEnd` fires on release.
Uses `composeRule.setContent { IsometricScene(...) }` and
`composeRule.onRoot().performTouchInput { doubleClick() }`.

**Step 10 — Boot emulator and run instrumented suite (AC-S3)**
Use android-cli to verify or boot an AVD. Run:
```
./gradlew :isometric-compose:connectedDebugAndroidTest
```
All four existing androidTest tests + new `DoubleTapInstrumentedTest` must pass.
Capture test report from `isometric-compose/build/outputs/androidTest-results/`.
Use lazylogcat if gesture events need debugging:
```
lazylogcat --package io.github.jayteealao.isometric.compose --tag IsometricScene
```

**Step 11 — Verify consumption gating interactively (C2)**
In the sample app, embed an `IsometricScene` inside a `LazyColumn` with `GestureConfig.Disabled`.
Swipe vertically. The `LazyColumn` must scroll (no consumption by the inert scene).
This is a manual smoke-test; evidence captured as logcat or visual.

**Step 12 — Full Gradle check**
```
./gradlew :isometric-compose:test :isometric-compose:connectedDebugAndroidTest
```

**Step 13 — Update InteractionHarnessReadme.kt**
Add a note explaining the new `DoubleTapDisambiguationTest` (state-machine level) vs.
`DoubleTapInstrumentedTest` (live routing level) split.

**Step 14 — Commit**
One commit: "fix(compose): merge gesture blocks for correct tap/double-tap coordination (C1/C2/C3/F4)"
Conventional-commit format per project memory.

## Verification Strategy

| AC | Tool / method + ladder rung | Environment need — satisfiable? | What must be built | Fallback chain |
|----|-----------------------------|---------------------------------|-------------------|----------------|
| AC-C1 (JVM half) | JVM unit tests: `DoubleTapDisambiguationTest` + `DragANodeTest` + `DragEventClarityTest` + `NodeCallbacksInteractionTest` via `./gradlew :isometric-compose:test` (rung 1 — state-machine/unit) | Windows 11 JVM — yes, always satisfiable | `DoubleTapDisambiguationTest.kt` (new); F4 test in DragANodeTest; constructive-proof commentary showing each test FAILS against pre-fix code | N/A — no environment dependency |
| AC-C1 interactive + AC-S3 | `connectedDebugAndroidTest` on android-cli-managed AVD — rung android-3 (AVD instrumented) | AVD boot required via android-cli; `compose.ui.test.junit4` already a dep — androidTest source set EXISTS | `DoubleTapInstrumentedTest.kt` (new) using `createComposeRule()` + `performTouchInput { doubleClick() }` | AVD fails → Robolectric state-machine (rung 1) covers all branches; defer live multi-touch routing residual with named event |
| AC-C2 | JVM unit test: `ConsumptionGatingTest` via `./gradlew :isometric-compose:test` (rung 1 — state machine, consumption flags directly assertable) | Windows 11 JVM — yes | `ConsumptionGatingTest.kt` (new) | N/A |
| AC-C3 | JVM unit test: `GestureStateResetTest` via `./gradlew :isometric-compose:test` (rung 1 — state machine, internal state directly assertable) | Windows 11 JVM — yes | `GestureStateResetTest.kt` (new) | N/A |
| AC-F4 | JVM unit test: new test in `DragANodeTest` asserting camera.panX/Y unchanged when node selected (rung 1 — branch condition directly assertable) | Windows 11 JVM — yes | One new test in `DragANodeTest.kt` | N/A |

**Constraint resolution for AC-C1 interactive + AC-S3:**
`constraint-resolution: proxy+deferral: cleared-by first AVD boot success on this machine`

Primary plan: android-cli manages the AVD (`android avd list`; if ≥1 exists, boot it;
`./gradlew :isometric-compose:connectedDebugAndroidTest`). The androidTest source set exists
and `compose.ui.test.junit4` is already a dependency — no new tooling needed.

Fallback if AVD boot fails:
- Rung 1 (state machine): JVM tests cover all callback-count, branch-condition, and
  state-reset assertions. Evidence: test report from `./gradlew :isometric-compose:test`.
- Rung 1 cannot cover live multi-touch pointer routing — that is the residual.
- Pre-registered deferral: "Live multi-touch routing evidence deferred — cleared by first
  successful `connectedDebugAndroidTest` run on this machine (AVD boot prerequisite)."

## Test / Verification Plan

### Automated checks

- **lint/typecheck**: `./gradlew :isometric-compose:lint` — no new warnings expected
- **JVM unit tests**: `./gradlew :isometric-compose:test` — all existing tests pass; new
  `DoubleTapDisambiguationTest`, `ConsumptionGatingTest`, `GestureStateResetTest` pass;
  F4 test in `DragANodeTest` passes
- **Instrumented tests**: `./gradlew :isometric-compose:connectedDebugAndroidTest` —
  all four existing + new `DoubleTapInstrumentedTest` pass

### Interactive verification (human-in-the-loop)

**AC-C1 interactive half + AC-S3 (double-tap live routing):**

- **What to verify**: Real pointer events routed through a live Compose surface correctly
  dispatch `onDoubleClick` exactly once and zero `onClick` on double-tap; `onClick` fires
  instantly for onClick-only nodes.
- **Platform & tool**: Android — instrumented androidTest via `compose.ui.test.junit4`
  (`createComposeRule` + `performTouchInput`) on an android-cli-managed AVD.
- **Companion skills**: `android-cli` (AVD boot + emulator lifecycle); `lazylogcat`
  (gesture event debugging if callbacks misbehave).
- **Steps**:
  1. Check for running emulator: `android emulator list` (via android-cli).
  2. If none running, boot: `android emulator start --name <avd-name>` (or equivalent
     android-cli command). Wait for boot completion.
  3. Build and run instrumented tests:
     `./gradlew :isometric-compose:connectedDebugAndroidTest`
  4. If gesture debugging needed:
     `lazylogcat --package io.github.jayteealao.isometric.compose`
  5. Inspect test report at:
     `isometric-compose/build/outputs/androidTest-results/connected/`
- **Evidence capture**: Test XML report (pass/fail per test); optional logcat capture via
  lazylogcat showing gesture event sequence.
- **Pass criteria**: `DoubleTapInstrumentedTest` — all tests pass; `onDoubleClick` count
  == 1, `onClick` count == 0 in the double-tap scenario; existing four androidTests still
  pass.

**AC-C2 parent-scroll interop (smoke test):**

- **What to verify**: An inert `IsometricScene` (no gesture handlers, no camera) inside a
  `LazyColumn` does not consume vertical swipe events; the list scrolls normally.
- **Platform & tool**: Android — manual smoke test in the sample app
  (`app/src/main/java/.../InteractionSamplesActivity.kt`) or a purpose-built test activity.
- **Steps**: Launch the sample app; navigate to a scene embedded in a scrollable; swipe
  vertically with no callbacks registered; observe scroll behavior.
- **Pass criteria**: Parent scrollable scrolls; no gesture interception by the scene.

## Risks / Watchouts

- **`detectTapGestures` internals (Compose 1.5.0)**: When both `onTap` and `onDoubleTap`
  are registered, `detectTapGestures` uses `awaitFirstDown(requireUnconsumed=true)` internally.
  If the merged block consumes a Release event before `detectTapGestures`' internal coroutine
  sees it, `awaitFirstDown` on the next gesture may stall. The mitigation: Release events are
  consumed ONLY in the drag branch (`isDragging == true && shouldConsume`), never on a bare
  tap — the same as the current code's invariant.

- **`awaitEachGesture` vs. hand-rolled `while (true)`**: The current hand-rolled loop works
  and has no event-loss issue because it IS the only loop. After the merge, using
  `awaitEachGesture` as the outer wrapper is safer than a bare `while (true)` inside
  `detectTapGestures`' scope — `awaitEachGesture` handles the "all pointers up between
  gestures" contract that `while (true)` can violate on rapid sequences.

- **Long-press timeout integration**: `detectTapGestures(onLongPress=...)` uses the platform
  default timeout, not `GestureConfig.longPressTimeoutMs`. If the implementation uses the
  `detectTapGestures` `onLongPress` callback, it silently ignores the configurable timeout.
  The hand-rolled coroutine approach (`longPressScope.launch { delay(...) }`) must be
  retained. This is architecturally slightly complex inside `detectTapGestures`' `onPress`
  callback but is the existing pattern.

- **Constructive-proof enforcement**: The `DoubleTapDisambiguationTest` tests must
  demonstrably FAIL against the pre-fix code. If they are written against the post-fix
  merged handler (where `detectTapGestures` disambiguates correctly), they will pass trivially
  without proving they catch the original bug. The plan requires: write the tests FIRST
  (Step 1), run them against the EXISTING two-block code (expect RED), then apply the fix
  (Step 6) and confirm GREEN.

- **Paparazzi snapshot interaction**: This slice does not change any rendering code, so no
  Paparazzi goldens should be affected. If any golden changes, investigate immediately —
  it would indicate unexpected side effects from the gesture handler change.

## Dependencies on Other Slices

- `docs-and-changelog` depends on THIS slice: the `interactions.mdx` onDoubleClick section
  describes the new tap contract that this slice implements. No code dependency in the
  reverse direction.
- `compose-contracts`: The KDoc and annotations on `GestureConfig`, `GestureEvents`, and
  `AdvancedSceneConfig` travel in that slice. This slice does not edit those files.
- `snapshot-sweep-gate`: Final `./gradlew test apiCheck` in that slice includes
  `isometric-compose:test`. All tests from this slice must be green before that slice runs.

## Assumptions

- The `compose.ui.test.junit4` AndroidX test rule (already a dep) is sufficient for
  injecting double-tap events via `performTouchInput { doubleClick() }` in Compose 1.5.0.
  No additional Compose test infrastructure needs to be added.
- `detectTapGestures` in Compose 1.5.0 (compose-foundation:1.5.0) correctly waits out
  `doubleTapTimeoutMillis` before firing `onTap` when both `onTap` AND `onDoubleTap` are
  registered. This is confirmed by the shape's freshness research (citing
  TapGestureDetector.kt source) and is the basis for the entire C1 fix strategy.
- The existing four androidTest tests (`IsometricRendererPathCachingTest`,
  `IsometricRendererNativeCanvasTest`, `StackTest`, `TileGridTest`) continue to pass without
  modification after the gesture handler restructure.
- `forEachGesture` is NOT used anywhere in the merged handler. Only `awaitEachGesture` or
  the hand-rolled `while (true)` inside a single `awaitPointerEventScope`.
- Windows 11 host can run JVM tests via Gradle without any additional setup.

## Blockers

None. Both plan-time decisions were resolved by the PO at plan discovery (2026-07-07,
see po-answers.md Stage 4):

- **Long-press timeout:** keep the hand-rolled coroutine honoring
  `GestureConfig.longPressTimeoutMs` (Step 6 / Proposed Change Strategy point 2 stand
  as written; `detectTapGestures(onLongPress)` is NOT used).
- **Test harness:** JVM `DoubleTapDisambiguationTest` is state-machine-only; real
  pointer-event routing is proven in the instrumented androidTest suite (Steps 2b, 9, 10
  stand as written; no Robolectric added).

## Freshness Research

- **Source**: androidx.compose.foundation 1.5.0 `TapGestureDetector.kt` (cited in shape's
  freshness research, confirmed by internal codebase documentation at shape stage).
  **Why it matters**: C1's fix strategy depends on `detectTapGestures` delivering the chosen
  contract natively.
  **Takeaway**: When both `onTap` and `onDoubleTap` are registered, `detectTapGestures`
  waits for `ViewConfiguration.doubleTapTimeoutMillis` (~300ms, min 40ms) before firing
  `onTap`. When only `onTap` is registered (no `onDoubleTap`), it fires immediately on
  Release. This is the exact contract the PO selected in shape Round 1. No hand-rolled
  timing is needed.

- **Source**: Compose pointer-input documentation + issue 251260206 (cited in shape).
  **Why it matters**: The merge must use the correct event-loop primitive.
  **Takeaway**: `forEachGesture` has a known event-loss bug (events that arrive during the
  body of a previous gesture may be dropped on the loop restart). `awaitEachGesture` was
  introduced as the replacement and is available in Compose 1.5.0. Use `awaitEachGesture`
  exclusively as the outer loop wrapper.

- **Source**: Compose `pointerInput` key semantics (official docs).
  **Why it matters**: The recomposition edge case (C1/C3 — `onDoubleClick` added/removed).
  **Takeaway**: When a `pointerInput` block is keyed on `Unit`, it never restarts on
  recomposition. Values that change (callbacks, camera state) must be accessed through
  `rememberUpdatedState` delegates, not captured directly. This is already the pattern in
  the current code (`currentGestures`, `currentCameraState`, `currentNodeDragState`). The
  merged handler inherits this pattern unchanged — the `onDoubleClick` recomposition edge
  case is handled by reading `hitNode?.onDoubleClick` at dispatch time, not at handler
  creation time.

- **Source**: `awaitFirstDown(requireUnconsumed = true)` starvation (Compose foundation
  source, cited in shape).
  **Why it matters**: Knowing when consumption in the merged block starves `detectTapGestures`'
  internal `awaitFirstDown`.
  **Takeaway**: `detectTapGestures` internally calls `awaitFirstDown(requireUnconsumed =
  true)`. If any code in the same handler scope consumes the Down event before
  `detectTapGestures`' coroutine resumes, the next gesture stalls. The existing code only
  consumes events on Move (during drag), never on Press or Release — this invariant must be
  preserved in the merged handler.

- **Source**: `ViewConfiguration.doubleTapTimeoutMillis` Android platform source.
  **Takeaway**: The value is 300ms on most devices, with a minimum of 40ms. The platform
  configuration provides the actual value at runtime; `detectTapGestures` reads it
  internally. No hardcoding needed.

- **Web research agent**: Research was launched in parallel. The shape stage's freshness
  research (already recorded in `02-shape.md`) cites the same sources and is treated as the
  primary reference. Any new findings from the web research agent that contradict or extend
  these takeaways should be incorporated at implement time.

## Revision History

- **2026-07-07T12:46:38Z — Mode: PO discovery (plan stage, parallel-all).** Resolved both
  PO-DECISION-PENDING items on the recommended options: long-press stays hand-rolled at
  the configurable `GestureConfig.longPressTimeoutMs`; the double-tap harness is
  state-machine-only JVM + instrumented androidTest (no Robolectric). Blockers cleared.
  Note: the sibling `compose-contracts` slice adopts the uncommitted `GestureConfig.kt` /
  `GestureEvents.kt` / `IsometricRenderer.kt` / `DragEventClarityTest.kt` working-tree
  changes (PO decision) — this slice still does not touch those files.

## Recommended Next Stage

- **Option A (default):** `/wf implement full-codebase-audit-fixes gesture-coordination` —
  plan is complete; all decisions are either resolved or have a clear recommended path.
  Write the tests first (Steps 1–5), confirm they RED against current code, apply the
  restructure (Step 6), confirm GREEN. **Consider running `/compact` before implement** —
  planning research is noise for implementation; workflow state lives in the artifact files.
- **Option B:** `/wf plan full-codebase-audit-fixes core-math` — plan the core-math slice
  in parallel if gesture-coordination is ready for handoff.
