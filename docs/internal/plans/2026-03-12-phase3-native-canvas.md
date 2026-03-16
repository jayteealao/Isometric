# Phase 3 Plan: `perf/native-canvas`

**Date:** 2026-03-12
**Status:** Plan
**Branch:** `perf/native-canvas`
**Base branch:** `perf/benchmark-harness`
**Depends On:** Phase 1 harness implementation, Phase 2 baseline run

---

## 1. Objective

Measure and, if needed, tighten the Android native-canvas render path in `IsometricRenderer`.

This branch should answer one specific question:

**When `useNativeCanvas=true` and all other optimization flags remain off, does rendering through
`android.graphics.Canvas` lower total frame cost enough to justify the Android-only path and its
maintenance cost?**

Target branch flags:

- `enablePathCaching = false`
- `enableSpatialIndex = false`
- `enablePreparedSceneCache = false`
- `enableNativeCanvas = true`
- `enableBroadPhaseSort = false`

This branch is intentionally narrow:

1. it does not change projection or depth sorting
2. it does not change hit-testing behavior
3. it does not enable any other rendering optimization
4. it measures whether the Android-only draw path is worthwhile in isolation

---

## 2. Why This Branch Matters

Phase 2 baseline showed that frame cost remains high even with all optional optimizations disabled.

Native canvas targets the draw stage directly:

1. bypassing Compose `DrawScope.drawPath()` for rendering
2. using `android.graphics.Canvas` and reusable `Paint` instances
3. potentially reducing overhead in the Android graphics pipeline for large command counts

This branch tests the question:

**Is the draw-call overhead of Compose itself large enough that the Android native canvas path is a
meaningful standalone optimization?**

---

## 3. Current Code State

Current implementation facts on `perf/benchmark-harness`:

1. `BenchmarkFlags.enableNativeCanvas` already exists
2. `BenchmarkScreen` already maps it to `IsometricScene(useNativeCanvas = flags.enableNativeCanvas)`
3. `IsometricScene` already reports `useNativeCanvas` in `RuntimeFlagSnapshot`
4. `IsometricRenderer` already has two render paths:
   - `DrawScope.render()` using Compose `drawPath`
   - `DrawScope.renderNative()` using `android.graphics.Canvas`
5. `renderNative()` already reuses `Paint` instances but still converts each command to an Android `Path` during draw
6. `renderNative()` shares the same prepared-scene cache path as the Compose renderer
7. the benchmark runner does **not** yet provide a branch override for this flag
8. the existing `selftest_cache` and `selftest_sanity` do not isolate native canvas behavior

That means this branch is not “invent native rendering.” It is:

1. validate the existing Android-only path
2. ensure the benchmark can isolate it cleanly
3. measure whether it improves total frame cost on its own

---

## 4. Critical Benchmark Constraint

This branch does **not** measure a fully cached Android draw path.

That is the main methodological constraint in this plan.

On `perf/benchmark-harness`:

1. `enablePreparedSceneCache = false` implies `forceRebuild = true`
2. `forceRebuild = true` invalidates the renderer cache every frame
3. `renderNative()` still calls `command.toNativePath()` for every command on every draw
4. the branch therefore measures:
   - Android native draw overhead vs Compose draw overhead
   - not native-path reuse across stable frames

So the expected signal is:

1. `avgPrepareMs` should stay close to baseline, because native canvas mainly changes draw work
2. `avgDrawMs` should drop if `android.graphics.Canvas` is actually cheaper than Compose `drawPath`
3. `avgFrameMs` is the main decision metric
4. `allocatedMB` and `gcInvocations` are secondary evidence, because per-command native-path allocation may still keep memory pressure high

This branch is valid even if the win is modest. But if standalone native canvas is neutral or
negative on `avgFrameMs`, the correct conclusion is not to keep forcing the branch; it means the
existing native path may only be worthwhile when combined with other cache layers.

---

## 5. Non-Negotiable Correctness Constraint

Native canvas rendering must be visually equivalent to the Compose draw path for Android output.

The branch is invalid if enabling native canvas changes:

1. polygon geometry
2. fill colors
3. stroke visibility or ordering
4. command order / depth ordering
5. hit-testing behavior

Platform scope note:

- `renderNative()` is Android-only by design
- the branch must not break the Compose render path on non-Android or in unit tests

---

## 6. Execution Requirement

Phase 2 baseline was established on a real Android device. This branch must be compared on the same
class of hardware.

Requirements:

1. benchmark comparison runs must use a physical Android device, not an emulator
2. emulator use is acceptable only for smoke tests, debug, and instrumentation development
3. final comparison against Phase 2 baseline must be run on the same physical device model or the
   same test device family whenever possible

Reason:

- native rendering behavior, HWUI cost, and graphics-driver characteristics differ materially between
  emulators and physical devices, so emulator numbers are not trustworthy branch-decision data here

---

## 7. Scope

### In Scope

1. audit the current native canvas render path
2. add or tighten tests around native-canvas correctness and flag plumbing
3. add minimal runner support to execute a native-canvas-only matrix
4. run targeted smoke scenarios
5. run the full 24-scenario branch matrix
6. compare against the Phase 2 baseline

### Out of Scope

1. prepared-scene cache changes
2. path caching changes
3. spatial hit-testing changes
4. broad-phase sort changes
5. combo-branch work
6. engine sort/projection algorithm changes

---

## 8. Detailed Plan

### Step 1: Audit The Existing Native Render Contract

Read and document the current native-canvas path:

1. `BenchmarkScreen`
2. `IsometricScene`
3. `IsometricRenderer.renderNative()`
4. shared cache path via `ensurePreparedScene()`
5. `toNativePath()` and reusable paint setup

Confirm:

1. exactly what work is still done per frame in `renderNative()` today
2. whether any benchmark-driven state unexpectedly bypasses the native path
3. whether draw-only work is equivalent between native and Compose rendering
4. whether the benchmark is measuring native draw overhead rather than some unrelated cache effect

Required output:

1. one short note in code comments or plan notes clarifying that this branch measures native draw-path cost, not native-path reuse across stable frames

### Step 2: Verify Flag Plumbing End To End

Implementation tasks:

1. confirm `enableNativeCanvas` is parsed from JSON config
2. confirm it reaches `BenchmarkScreen`
3. confirm `IsometricScene` reports the expected runtime flag snapshot
4. confirm the Android path is actually selected when `useNativeCanvas=true`

Guardrails:

1. baseline defaults remain unchanged
2. self-tests keep their current hardcoded configs
3. native-canvas branch execution must not affect baseline execution semantics
4. non-Android-safe code remains confined to the native render path

### Step 3: Tighten Native Canvas Correctness Tests

Minimum required coverage:

1. runtime flag snapshot reports `useNativeCanvas=true` when enabled
2. enabling native canvas does not change prepared-scene or hit-test semantics
3. native canvas path can execute on Android without crashing
4. Compose render path still works when native canvas is disabled
5. at least one Android-side test proves the renderer actually routes through `renderNative()` when `useNativeCanvas=true`

Recommended additions if missing:

1. one Android instrumentation test that exercises `renderNative()` directly or through `IsometricScene`
2. one parity-oriented test or snapshot proving native and Compose rendering are visually/structurally aligned enough for benchmark use

Note:

- this branch is Android-only by nature, so Android instrumentation coverage matters more than pure JVM coverage here
- `RuntimeFlagSnapshot.useNativeCanvas=true` alone is not enough; at least one test must exercise the actual native draw branch

### Step 4: Add Minimal Runner Support

The current runner is baseline-oriented. Add the smallest reusable mechanism needed to run:

```bash
bash isometric-benchmark/benchmark-runner.sh \
  --enable-native-canvas \
  --label native-canvas
```

Requirements:

1. baseline defaults stay all-off when no overrides are provided
2. self-tests keep their hardcoded configs
3. branch overrides apply only to the 24-scenario matrix
4. branch result directories stay isolated from baseline output
5. branch output clearly indicates `enableNativeCanvas=true` for matrix rows

### Step 5: Smoke Test The Native Draw Signal

Run a small set before the full matrix:

1. `N=10`, `mutationRate=0.10`, `NONE`
2. `N=100`, `mutationRate=0.10`, `NONE`
3. `N=100`, `mutationRate=0.10`, `CONTINUOUS`
4. `N=200`, `mutationRate=0.50`, `NONE`

Purpose:

1. verify self-tests still pass
2. verify branch output and labels are correct
3. verify `avgDrawMs` decreases or remains competitive in larger scenes
4. confirm `avgPrepareMs` stays close to baseline
5. spot obvious regressions in `allocatedMB` and `gcInvocations`

Execution note:

- smoke tests may be run on an emulator for quick validation, but any branch decision must be based on a physical-device run

### Step 6: Run Full 24-Scenario Matrix

Run the standard matrix with:

- `enableNativeCanvas = true`
- all other optimization flags `false`

Execution requirement:

- this full comparison matrix must run on a physical Android device, not an emulator

### Step 7: Compare Against Baseline

Primary comparison metrics:

1. `avgFrameMs`
2. `avgDrawMs`
3. `avgPrepareMs`
4. `allocatedMB`
5. `gcInvocations`
6. p95/p99 frame metrics where available
7. runtime flag validation showing `useNativeCanvas=true`

Questions to answer:

1. Does native canvas lower total frame time?
2. Are any wins concentrated in larger scenes where draw cost dominates?
3. Does native canvas reduce draw time without causing offsetting memory/GC regressions?
4. Does it remain useful when interaction is continuous?
5. If timing is ambiguous, is a follow-up trace needed before deciding whether to keep the branch?

Decision rule:

- if `avgDrawMs` improves but `avgFrameMs` is flat, noisy, or contradictory, do not guess; capture a Perfetto trace and/or use Profile GPU Rendering before deciding whether the branch is a real win

---

## 9. Hypotheses

Expected results:

1. strongest wins at `N=100` and `N=200`
2. modest or negligible wins at `N=10`
3. `avgDrawMs` should improve more consistently than `avgFrameMs`
4. `avgPrepareMs` should stay near baseline, because native canvas mainly affects draw work
5. some scenarios may show limited total benefit if native-path creation cost remains high

---

## 10. Success Criteria

This branch is successful if all of the following are true:

1. native rendering parity is preserved well enough for benchmark use
2. runner support is minimal and does not perturb baseline behavior
3. the branch completes the standard matrix cleanly
4. `avgFrameMs` improves in enough meaningful scenarios to justify keeping the Android-only path exposed
5. memory/GC behavior is neutral-to-better, or any regression is clearly small relative to frame-time wins

A strong outcome would be:

1. clear `avgDrawMs` reductions at `N=100` and `N=200`
2. neutral-to-positive `avgFrameMs` impact overall
3. no correctness regressions in renderer behavior
4. coarse memory signals are flat or improved

A weak-but-still-useful outcome would be:

1. draw-time improvement is real
2. total frame-time improvement is marginal
3. branch still provides evidence that native canvas only pays off when combined with other cache layers

---

## 11. Failure Conditions

This branch should be considered a poor standalone optimization if:

1. total `avgFrameMs` regresses broadly
2. native canvas changes output semantics or stability
3. runner changes introduce branch/baseline coupling
4. memory or GC cost regresses without enough timing benefit to justify it
5. Android-only maintenance cost is not justified by measurable performance gains

If that happens, the correct outcome is to stop treating standalone native canvas as a winner and
carry it forward only as a possible combo-branch optimization.

---

## 12. Acceptance Criteria

Before closing the branch:

1. renderer/tests cover the main native-canvas semantics
2. benchmark runner can execute a native-canvas-only matrix
3. both self-tests still pass
4. 24/24 matrix scenarios complete
5. branch results clearly indicate `enableNativeCanvas=true` for matrix rows
6. a comparison summary exists against Phase 2 baseline, including timing and coarse memory signals
7. the final comparison run is taken on a physical Android device

---

## 13. Deliverables

Expected deliverables:

1. code changes, if any, to renderer/test/runner support
2. branch benchmark result directory
3. comparison notes against Phase 2 baseline
4. implementation review documenting whether native canvas is worthwhile as a standalone optimization

---

## 14. Commit Strategy

Keep commits atomic:

1. tests / renderer correctness clarification
2. runner support for branch execution
3. benchmark results/docs

Do not mix unrelated docs or other branch experiments into this branch.

---

## 15. Immediate First Task

Start with Step 1 and Step 3 together:

1. verify the renderer really routes through `renderNative()` only when `useNativeCanvas=true`
2. add one focused Android-side test that proves the native render path is reachable and does not disturb hit-test semantics

That gives a clear correctness baseline before touching the runner.
