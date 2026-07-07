---
schema: sdlc/v1
type: plan
slug: full-codebase-audit-fixes
slice-slug: view-module
status: complete
stage-number: 4
created-at: "2026-07-07T12:10:36Z"
updated-at: "2026-07-07T15:55:11Z"
metric-files-to-touch: 4
metric-step-count: 5
has-blockers: false
revision-count: 0
tags: [isometric-android-view, paint-reuse, touch-handling, samples, robolectric]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-view-module.md
  siblings:
    - 04-plan-core-math.md
    - 04-plan-gesture-coordination.md
    - 04-plan-compose-contracts.md
    - 04-plan-shape-geometry.md
    - 04-plan-docs-and-changelog.md
    - 04-plan-snapshot-sweep-gate.md
  implement: 05-implement-view-module.md
next-command: wf-implement
next-invocation: "/wf implement full-codebase-audit-fixes view-module"
---

# Plan: View Module & Samples

## The Plan

Three of the four findings in this slice are already done. Commit 38c77e1 landed Paint reuse
(D1), lazy scene rebuild on option setters (D3), and the ACTION_UP return value fix (G2) — and
it brought 16 Robolectric regression tests with it, covering every behavioral AC with the
appropriate proxy. What that commit did not touch is the DragLifecycleSample KDoc (D2): lines
612–615 of `InteractionSamplesActivity.kt` still say `x/y` in `onDrag` are "the drag-start
position, captured in onDragStart," which is wrong. In `onDrag`, `x/y` is the live pointer
position — the drag-start semantics are only accurate for the `onDragStart` event. The fix is
a three-line KDoc edit cross-checked against `GestureEvents.kt` lines 34–44.

The test suite 38c77e1 shipped is honest about what Robolectric can and cannot see. The
Paint-reuse AC (AC-D1) states `observable: false` — allocation behavior is not measurable
through Robolectric's stub Canvas — so the test proxy is consecutive-draw scene identity:
two draws of the same scene must produce the same `cachedScene` object (no re-projection,
which would only happen if the renderer mutated the scene data). The `per-face field reset`
pattern in `AndroidCanvasRenderer.kt` is the code-review-verifiable guarantee; the test
confirms the surrounding invariant. That's an honest tradeoff, stated plainly in the test's
own comment at line 196.

The remaining residual is lightweight: verify the 16 tests pass (`./gradlew
:isometric-android-view:test`), fix the one KDoc line, and do the D2 read-through at review.
This plan is mostly a record of what 38c77e1 already delivered.

## Current State

| Finding | Classification | Evidence |
|---------|---------------|---------|
| D1 — Paint allocation | **ALREADY FIXED** (38c77e1) | `AndroidCanvasRenderer.kt` line 36-37: `val androidPath = Path(); val paint = Paint(...)` allocated once before the loop; each face resets via `androidPath.reset()` + explicit per-face field sets. `IsometricViewLifecycleTest.ac8_*` is the regression proxy. |
| D3 — setSort/setCull/setBoundsCheck stale | **ALREADY FIXED** (38c77e1) | `IsometricView.kt` lines 66–95: each setter sets `sceneDirty = true; invalidate()`. `onDraw` (line 189) re-projects when `sceneDirty || cachedScene == null`. Lazy strategy confirmed (rebuild on next draw, not per setter call). Tests: `ac7_setSort_*`, `ac7_setCull_*`, `ac7_setBoundsCheck_*`, `ac7_multipleAddCalls*`. |
| G2 — ACTION_UP returns false | **ALREADY FIXED** (38c77e1) | `IsometricView.kt` line 229: `return true` under `ACTION_UP` when `listener != null`. Tests: `ac9_onTouchEventReturnsTrueWhenListenerRegistered`, `ac9_onTouchEventReturnsFalseWhenNoListenerRegistered`. |
| D2 — DragLifecycleSample KDoc | **NOT YET FIXED** | `InteractionSamplesActivity.kt` line 612–615 still contains "the drag-start position, captured in onDragStart." `GestureEvents.kt` line 34-44 is the authoritative definition (x/y = live pointer position in `onDrag`). The phrase correctly describes `onDragStart` but is wrong for `onDrag`. |

D3 implementation note: the landed fix is lazy rebuild (sceneDirty flag + invalidate → single
reprojection on next onDraw). The slice AC allowed either lazy or eager; lazy is preferable
because it batches any number of setter calls that happen before the next frame into a single
projection. No PO action needed — confirmed correct.

## Simplicity Ladder

No new capabilities are required. The one remaining change (D2 KDoc) is pure prose. All
implementation code is already in place.

- D2 KDoc fix → **Rung 1 (stdlib / language built-in)**: Kotlin multiline KDoc comment — no
  library, no helper, no new code. Edit the three affected comment lines in the existing
  source file.
- D1/D3/G2 already implemented → ladder N/A for new code.

## Applied Learnings

No applicable learnings found. The `.ai/solutions/INDEX.md` corpus is absent from this
repository.

No runtime-evidence-deferrals were recorded in `00-index.md` for this slice. Repeat-deferral
tripwire: N/A.

## Likely Files / Areas to Touch

- `app/src/main/kotlin/io/github/jayteealao/isometric/sample/InteractionSamplesActivity.kt`
  (lines 612–615): Fix DragLifecycleSample KDoc to state `x/y` in `onDrag` is the live
  pointer position, not the drag-start position. Cross-reference `GestureEvents.kt` semantics.
- `isometric-android-view/src/main/kotlin/io/github/jayteealao/isometric/view/AndroidCanvasRenderer.kt`:
  No code change needed. Review step: verify per-face field resets (lines 59–82) prevent state
  leaks between faces, satisfying AC-D1's code-review gate.
- `isometric-android-view/src/main/kotlin/io/github/jayteealao/isometric/view/IsometricView.kt`:
  No code change needed. Review step: verify `sceneDirty` logic and ACTION_UP return (AC-D3,
  AC-G2).
- `isometric-android-view/src/test/kotlin/io/github/jayteealao/isometric/view/IsometricViewLifecycleTest.kt`:
  No test change needed. Run step: `./gradlew :isometric-android-view:test` — all 16 tests
  must pass.

## Proposed Change Strategy

The slice reduces to one code-edit (D2 KDoc), one test run, and two code-review steps.

1. Fix D2 KDoc in `InteractionSamplesActivity.kt` — align the DragLifecycleSample description
   with `GestureEvents.kt`'s authoritative definition of `DragEvent.x/y` semantics.
2. Run `./gradlew :isometric-android-view:test` to confirm the 16 Robolectric tests pass.
3. Code-review verify AC-D1: inspect `AndroidCanvasRenderer.kt`'s per-face field reset
   sequence to confirm zero new Paint/Path allocations inside the render loop.
4. Code-review verify AC-D3/AC-G2: re-read `IsometricView.kt`'s `sceneDirty` flag and
   `onTouchEvent` return path.
5. Manual read-through verify AC-D2: cross-check edited KDoc against `GestureEvents.kt`
   lines 34–44 at review stage.

## Step-by-Step Plan

1. **Edit D2 KDoc** — Open
   `app/src/main/kotlin/io/github/jayteealao/isometric/sample/InteractionSamplesActivity.kt`.
   Lines 612–615: replace the clause "the drag-start position, captured in onDragStart" with
   "the drag-start position when delivered to `onDragStart`, and the **live** pointer position
   when delivered to `onDrag`". Retain "delta is the per-event movement." New text should match
   `GestureEvents.kt` line 34–44 semantics exactly.

2. **Run view-module tests** — `./gradlew :isometric-android-view:test` — all 16 Robolectric
   tests must pass. Failure here means the 38c77e1 fix has a regression; stop and investigate
   before proceeding.

3. **Code-review AC-D1** — Read `AndroidCanvasRenderer.kt` lines 36–83. Confirm:
   - `val androidPath = Path()` and `val paint = Paint(Paint.ANTI_ALIAS_FLAG)` appear exactly
     once, before the `for` loop.
   - `androidPath.reset()` is called at the top of the loop body (line 41).
   - `paint.style`, `paint.color`, and `paint.strokeWidth` are all set explicitly for each
     branch of the `when (strokeStyle)` block — no field carries over silently from the
     previous iteration.
   This is the AC-D1 acceptance gate per the slice's `observable: false` annotation.

4. **Code-review AC-D3** — Read `IsometricView.kt` lines 66–95 and 186–200. Confirm `sceneDirty
   = true` is set in `setSort`, `setCull`, `setBoundsCheck` (and `setStrokeStyle`, `add`, `clear`),
   and that `onDraw` re-projects exactly once when `sceneDirty || cachedScene == null` then
   clears the flag.

5. **Manual D2 read-through** — At review stage, read the edited DragLifecycleSample KDoc and
   `GestureEvents.kt` lines 34–44 side by side. The KDoc must correctly distinguish:
   - `onDragStart`: `x/y` = drag-start position (absolute, pointer position at gesture start)
   - `onDrag`: `x/y` = live (current) pointer position (absolute, moves with the pointer)
   - `delta` = per-event movement in both

## Verification Strategy

| AC | Tool / method + ladder rung | Environment need — satisfiable in target env? | What must be BUILT to make it verifiable | Fallback chain |
|----|------------------------------|-----------------------------------------------|------------------------------------------|----------------|
| AC-D1: zero Paint allocations per draw | Code-review inspection of `AndroidCanvasRenderer.kt` lines 36–83 (rung: manual-review) + `ac8_renderIsIdenticalOnConsecutiveDrawsAfterM5Reuse` Robolectric test (rung: jvm-unit) | JVM only — yes, no device needed | Already built: single-instance pattern in renderer; proxy test in `IsometricViewLifecycleTest`. No new seam needed. | Code review is the primary gate; proxy test is the automated confirmation of the surrounding invariant. |
| AC-D3: setSort/setCull/setBoundsCheck take effect on next draw | Robolectric unit tests `ac7_setSort_*`, `ac7_setCull_*`, `ac7_setBoundsCheck_*`, `ac7_multipleAddCalls*` via `./gradlew :isometric-android-view:test` (rung: jvm-unit) | JVM only — yes | Already built (38c77e1): `CountingSceneProjector` test seam, `sceneDirty` internal accessor, 6 tests covering all three setters plus batching. | No fallback needed; tests are already present and passing. |
| AC-G2: onTouchEvent returns true for handled ACTION_UP | Robolectric unit tests `ac9_onTouchEventReturnsTrueWhenListenerRegistered`, `ac9_onTouchEventReturnsFalseWhenNoListenerRegistered` via `./gradlew :isometric-android-view:test` (rung: jvm-unit) | JVM only — yes | Already built (38c77e1): MotionEvent.obtain stubs in test, two tests covering the listener-present and listener-absent cases. | No fallback needed. |
| AC-D2: DragLifecycleSample KDoc matches GestureEvents.kt semantics | Manual read-through at review stage — cross-check edited KDoc in `InteractionSamplesActivity.kt` against `GestureEvents.kt` lines 34–44 (rung: manual-review; residual — prose judgment) | Source file read — yes, no runtime needed | Requires D2 KDoc edit (Step 1 above) to exist before review. | No automated fallback; prose accuracy is inherently human-judged. |

Constraint-resolution per AC:

- AC-D1: `constraint-resolution: po-accepted: AC-D1 is marked observable:false in the slice; the allocation pattern is code-review verifiable and the proxy test covers the surrounding invariant. No runtime allocation counter is planned.`
- AC-D3: `constraint-resolution: po-accepted: JVM/Robolectric unit tests are the verified ceiling for this AC; no device or emulator is needed to assert a dirty-flag state machine.`
- AC-G2: `constraint-resolution: po-accepted: JVM/Robolectric MotionEvent injection is sufficient to assert a return-value contract; no UI rendering or live device is needed.`
- AC-D2: `constraint-resolution: po-accepted: prose accuracy is a manual-review criterion; the slice definition carries verify: { rung: manual-review (residual — prose judgment) }.`

Repeat-deferral tripwire: no existing runtime-evidence-deferrals in `00-index.md` match this
slice's environment dependencies (JVM-only Robolectric, source-read prose). No wall is being
re-paid.

## Test / Verification Plan

### Automated checks

- **lint/typecheck:** `./gradlew :isometric-android-view:lint :app:lint` — covers the KDoc edit.
- **unit tests:** `./gradlew :isometric-android-view:test` — 16 Robolectric tests must all
  pass (AC-D3: 6 setter tests + 1 batching test + 2 hit-test-only setter tests; AC-G2: 2
  return-value tests; AC-D1 proxy: 1 consecutive-draw identity test; additional StrokeStyle
  and lifecycle tests).
- **API check:** `./gradlew :isometric-android-view:apiCheck` — the D2 KDoc edit is in the
  app module (not the library), so no API surface change. Verify passes as-is.

### Interactive verification (human-in-the-loop)

**AC-D2 (manual read-through at review stage):**

- What to verify: The DragLifecycleSample KDoc correctly states that in `onDrag`, `x/y` is
  the live (current) pointer position, and in `onDragStart`, `x/y` is the drag-start position.
- Platform & tool: Source code review — no device or runtime needed.
- Steps:
  1. Read `InteractionSamplesActivity.kt` lines 608–616 (the DragLifecycleSample KDoc).
  2. Read `GestureEvents.kt` lines 34–44 (the `DragEvent` class KDoc).
  3. Verify the two descriptions are consistent: `x/y` = absolute position (drag-start in
     `onDragStart`; live pointer in `onDrag`); `delta` = per-event movement (non-null in
     `onDrag`, null in `onDragStart`).
- Companion skills: none.
- Evidence capture: Reviewer confirms in the PR review comment that the KDoc matches the
  implementation.
- Pass criteria: KDoc accurately distinguishes `onDragStart` and `onDrag` semantics for
  both `x/y` and `delta`, matching `GestureEvents.kt` verbatim intent.

**AC-D1/D3/G2 (code-review at review stage):**

- What to verify: Per-face Paint/Path field resets (D1); `sceneDirty` flag lifecycle (D3);
  ACTION_UP return branch (G2).
- Platform & tool: Source code review — no device or runtime needed.
- Steps: Read `AndroidCanvasRenderer.kt` per Step 3 above; read `IsometricView.kt` per Step 4.
- Pass criteria: No allocation inside the render loop (D1); `sceneDirty = true` in all three
  setters (D3); `return true` branch exists under `ACTION_UP` when `listener != null` (G2).

## Risks / Watchouts

- **AC-D1 allocation assertion ceiling:** Robolectric's stub Canvas cannot count `drawPath`
  calls at the allocation level. The code-review gate is the real acceptance mechanism;
  the proxy test confirms the scene-structure invariant only. If a future instrumented smoke
  test needs true allocation counts, a `StrictMode` profiling run or `Debug.startAllocCounting`
  in an emulator test would be the right tool — not required for this slice.
- **D2 KDoc scope:** Only the `DragLifecycleSample` KDoc at line 612–615 is in scope. The
  function body (lines 618+) already demonstrates the correct pattern (reading `event.x` in
  `onDragStart` for the start position). No other file in the `app` module needs touching.
- **D3 lazy strategy confirmed:** 38c77e1 chose lazy rebuild (sceneDirty + invalidate). Any
  callers who expected the old `onMeasure`-triggered projection path will now get the correct
  behavior (projection on next draw) rather than no behavior at all. This is a behavioral
  improvement, not a regression.

## Dependencies on Other Slices

- None. The view-module slice is fully self-contained.
- Note: The `gesture-coordination` slice owns gesture *semantics* (the Compose gesture pipeline).
  This slice only touches the View wrapper's `onTouchEvent` return value (a distinct,
  independently verifiable contract). The two slices share no files.

## Assumptions

- Commit 38c77e1 is present on `feat/ws10-interaction-props` and is not reverted. If it were
  reverted, D1/D3/G2 would all need re-implementation, and the 16 tests would need to be
  re-added.
- Robolectric 4.x is configured in `isometric-android-view/build.gradle*` — confirmed by the
  `@RunWith(RobolectricTestRunner::class)` annotation and `@Config(sdk = [33])` already in
  the test file.
- The `app` module's KDoc is not part of the binary API surface (`app` is the sample
  application, not a published library), so the D2 fix requires no `apiDump` regeneration.

## Blockers

None.

## Freshness Research

**Targeted check: idiomatic allocation-assertion patterns for JVM/Android unit tests**

The question this slice posed: how do other Android projects assert "zero new Paint objects
per draw call" from JVM unit tests?

Finding: Robolectric's `Canvas` shadow is a stub — `drawPath`, `drawRect` etc. are no-ops
that produce no pixel output and do not count allocations. True per-draw allocation counting
requires either (a) an instrumented test with `Debug.startAllocCounting()` / `vmstat` on a
real or emulated device, or (b) a profiling session with Android Memory Profiler. Neither is
cost-effective for a one-Paint-per-renderer fix that is directly code-review verifiable.

The AC's own `observable: false` annotation and the slice definition's comment ("allocation
behavior is code-review verifiable plus existing render tests; no live runtime needed")
pre-resolve this: code-review is the correct acceptance gate for D1. The `ac8_*` proxy test
covers the structural invariant (no re-projection between draws), which is the closest
JVM-testable proxy for "renderer state is correctly reset between faces."

Conclusion: the plan's approach (code-review gate + proxy test) is idiomatic for this class
of renderer-optimization fix in the Android ecosystem. No additional tool or test pattern is
warranted.

**Source:** Android developer documentation on `StrictMode.setThreadPolicy` (for main-thread
allocation detection) and the Robolectric documentation on Shadow limitations — both confirm
that pixel-level / allocation-level assertions require instrumented or device-backed tests.
No new external dependency is introduced; no security advisories are relevant to this slice.

## Revision History

*(appended by review-and-fix mode)*

## Recommended Next Stage

- **Option A (default):** `/wf implement full-codebase-audit-fixes view-module` — The plan
  reduces to one KDoc edit (Step 1), one test run (Step 2), and code-review steps (Steps 3–5).
  No open questions remain. Consider running `/compact` before `/wf implement` — workflow
  state lives in the artifact files on disk and the SessionStart hook re-reads it
  automatically after compaction.
- **Option B:** Proceed directly with the KDoc edit inline (this plan is so small the
  implement stage may be skipped in favor of a direct edit + commit).
