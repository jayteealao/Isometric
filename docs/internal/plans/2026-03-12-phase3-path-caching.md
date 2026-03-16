# Phase 3 Plan: `perf/path-caching`

**Date:** 2026-03-12
**Status:** Plan
**Branch:** `perf/path-caching`
**Base branch:** `perf/benchmark-harness`
**Depends On:** Phase 1 harness implementation, Phase 2 baseline run

---

## 1. Objective

Measure and, if needed, tighten the existing Compose path-caching path in `IsometricRenderer`.

This branch should answer one specific question:

**When `enablePathCaching=true` and all other optimization flags remain off, does pre-converting
`RenderCommand` polygons into Compose `Path` objects reduce total frame cost enough to justify the
extra rebuild work and memory churn?**

Target branch flags:

- `enablePathCaching = true`
- `enableSpatialIndex = false`
- `enablePreparedSceneCache = false`
- `enableNativeCanvas = false`
- `enableBroadPhaseSort = false`

This branch is intentionally narrow:

1. it does not change projection or depth sorting
2. it does not change hit-testing behavior
3. it does not enable prepared-scene reuse across frames
4. it measures whether the cached-`Path` draw path is worthwhile in isolation

---

## 2. Why This Branch Matters

Phase 2 baseline showed that large scenes spend significant time inside the render pipeline even
when no specialized drawing optimization is active.

Path caching targets one specific part of that pipeline:

1. converting `RenderCommand.points` into Compose `Path` objects
2. reusing the same `Path` instance for fill and stroke within the draw pass
3. reducing per-frame allocation pressure on the Compose renderer path

This branch tests the question:

**Is path conversion itself a meaningful enough cost center to improve total frame time when cached
paths are enabled by themselves?**

---

## 3. Current Code State

Current implementation facts on `perf/benchmark-harness`:

1. `BenchmarkFlags.enablePathCaching` already exists
2. `BenchmarkScreen` already maps it to `IsometricScene(enablePathCaching = flags.enablePathCaching)`
3. `IsometricRenderer` already has:
   - `enablePathCaching`
   - `cachedPaths`
   - `buildPathCache(scene)`
   - draw fast path using `cachedPaths`
   - draw fallback converting paths inline when caching is disabled
4. `renderNative()` does not use `cachedPaths`; native canvas renders from per-command native paths
5. `rebuildCache()` builds `cachedPaths` immediately after `engine.prepare()` when path caching is enabled
6. the benchmark runner does **not** yet provide a branch override for this flag
7. the existing self-test `selftest_cache` exercises path caching only as part of `ALL_ON`, not in
   branch-isolated form

That means this branch is not “invent a new caching system.” It is:

1. verify the existing cached-path draw path is correct
2. ensure the benchmark can isolate it cleanly
3. measure whether it helps total frame cost on its own

---

## 4. Critical Benchmark Constraint

This branch does **not** measure long-lived path reuse across stable frames.

That is the most important methodological constraint in this plan.

On `perf/benchmark-harness`:

1. `enablePreparedSceneCache = false` implies `forceRebuild = true`
2. `forceRebuild = true` invalidates the renderer cache every frame
3. `rebuildCache()` therefore rebuilds `cachedPaths` every frame when path caching is enabled
4. the branch measures a different tradeoff:
   - more work during rebuild/prepare
   - less work during draw

So the expected signal is:

1. `avgPrepareMs` may rise because `buildPathCache()` is counted inside the prepare phase
2. `avgDrawMs` should drop if cached Compose `Path` objects reduce draw-side work
3. `avgFrameMs` is the main decision metric, not `avgPrepareMs` alone
4. mutation-heavy scenarios can still show benefit, because this branch is not relying on
   cross-frame scene stability
5. `allocatedMB` and `gcInvocations` are secondary evidence for whether path conversion pressure is actually reduced

This branch is valid even if prepare time increases, as long as total frame cost improves or memory/
GC pressure measurably improves.

This also means the branch should be judged honestly as a standalone mode. If standalone path caching
is neutral or negative on `avgFrameMs`, the correct conclusion is not “keep tuning this branch
forever”; it is that path caching likely only pays off when paired with prepared-scene reuse or a
later Compose-native cache lifetime model.

---

## 5. Non-Negotiable Correctness Constraint

Path caching must be rendering-equivalent to the non-cached path.

The branch is invalid if enabling cached paths changes:

1. polygon geometry
2. fill order
3. stroke application
4. command ordering
5. hit-testing semantics

This branch may change where work happens in the frame, but not what is rendered.

---

## 6. Scope

### In Scope

1. audit the current cached-path render path
2. add or tighten tests around cached-path correctness and invalidation
3. add minimal runner support to execute a path-caching-only matrix
4. run targeted smoke scenarios
5. run the full 24-scenario branch matrix
6. compare against the Phase 2 baseline

### Out of Scope

1. prepared-scene cache changes
2. spatial hit-testing changes
3. broad-phase sort changes
4. native canvas changes
5. combo-branch work
6. engine sort/projection algorithm changes

---

## 7. Detailed Plan

### Step 1: Audit The Existing Path Cache Contract

Read and document the current path-cache path:

1. `BenchmarkScreen`
2. `IsometricScene`
3. `IsometricRenderer.rebuildCache()`
4. `IsometricRenderer.buildPathCache()`
5. `DrawScope.render()` fast path and fallback path

Confirm:

1. exactly when `cachedPaths` is built today
2. whether `cachedPaths` is rebuilt on every frame under branch-isolated execution
3. whether any benchmark-driven state unexpectedly bypasses the cached draw path
4. whether draw-only work is equivalent between cached and uncached rendering

Required output:

1. one short note in code comments or plan notes clarifying that this branch measures
   per-frame path pre-conversion, not cross-frame path reuse

### Step 2: Verify Flag Plumbing End To End

Implementation tasks:

1. confirm `enablePathCaching` is parsed from JSON config
2. confirm it reaches `BenchmarkScreen`
3. confirm `IsometricScene` creates the renderer with the expected flag value
4. confirm runtime flag snapshots and validation report the expected state

Guardrails:

1. baseline defaults remain unchanged
2. self-tests keep their current hardcoded configs
3. path-caching branch execution must not affect baseline execution semantics
4. `useNativeCanvas` stays `false` for this branch, because native rendering bypasses cached Compose paths

### Step 3: Tighten Path Cache Correctness Tests

Minimum required coverage:

1. cached-path rendering path is reachable when `enablePathCaching=true`
2. uncached fallback rendering path is used when `enablePathCaching=false`
3. cache rebuild clears and rebuilds `cachedPaths` when the scene changes
4. `invalidate()` clears `cachedPaths`
5. cached and uncached rendering remain semantically equivalent for the same scene
6. enabling path caching does not change hit-test results for the same prepared scene

Recommended additions if missing:

1. one renderer test that compares cached vs uncached output semantics for the same scene
2. one benchmark-oriented test showing that draw/prepare hooks still fire consistently when path caching is enabled

Note:

- hit-test parity is a guardrail, not the main risk surface; render-path parity and cache lifecycle behavior matter more for this branch

### Step 4: Add Minimal Runner Support

The current runner is baseline-oriented. Add the smallest reusable mechanism needed to run:

```bash
bash isometric-benchmark/benchmark-runner.sh \
  --enable-path-caching \
  --label path-caching
```

Requirements:

1. baseline defaults stay all-off when no overrides are provided
2. self-tests keep their hardcoded configs
3. branch overrides apply only to the 24-scenario matrix
4. branch result directories stay isolated from baseline output
5. branch output clearly indicates `enablePathCaching=true` for matrix rows

### Step 5: Smoke Test The Draw/Prepare Tradeoff

Run a small set before the full matrix:

1. `N=10`, `mutationRate=0.10`, `NONE`
2. `N=100`, `mutationRate=0.10`, `NONE`
3. `N=100`, `mutationRate=0.10`, `CONTINUOUS`
4. `N=200`, `mutationRate=0.50`, `NONE`

Purpose:

1. verify self-tests still pass
2. verify branch output and labels are correct
3. verify `avgDrawMs` decreases or remains competitive in larger scenes
4. verify any `avgPrepareMs` increase is understood before the full matrix
5. confirm `avgFrameMs` is the metric worth optimizing, not draw time in isolation
6. spot obvious regressions in `allocatedMB` and `gcInvocations`

### Step 6: Run Full 24-Scenario Matrix

Run the standard matrix with:

- `enablePathCaching = true`
- all other optimization flags `false`

### Step 7: Compare Against Baseline

Primary comparison metrics:

1. `avgFrameMs`
2. `avgDrawMs`
3. `avgPrepareMs`
4. `allocatedMB`
5. `gcInvocations`
6. p95/p99 frame metrics where available
7. cache-related validation output only insofar as it confirms no unrelated cache feature was enabled

Questions to answer:

1. Does path caching lower total frame time?
2. If draw time improves, is the prepare-time increase small enough to keep the branch worthwhile?
3. Are wins concentrated in larger scenes?
4. Does path caching remain useful when interaction is continuous?
5. Do the coarse memory signals support the claim that path conversion pressure is reduced?
6. If timing is ambiguous, is a follow-up allocation trace or Perfetto capture needed before making a decision?

---

## 8. Hypotheses

Expected results:

1. strongest wins at `N=100` and `N=200`
2. modest or negligible wins at `N=10`
3. `avgDrawMs` should improve more consistently than `avgFrameMs`
4. some scenarios may show neutral or negative total results if path-cache build cost outweighs draw savings
5. mutation rate should not erase the signal the way it does for prepared-scene caching, because this branch already rebuilds every frame by design

---

## 9. Success Criteria

This branch is successful if all of the following are true:

1. rendering parity is preserved
2. runner support is minimal and does not perturb baseline behavior
3. the branch completes the standard matrix cleanly
4. `avgFrameMs` improves in enough meaningful scenarios to justify keeping the optimization exposed
5. `allocatedMB` and `gcInvocations` are neutral-to-better, or any regression is clearly small relative to frame-time wins

A strong outcome would be:

1. clear `avgDrawMs` reductions at `N=100` and `N=200`
2. neutral-to-positive `avgFrameMs` impact overall
3. no correctness regressions in renderer behavior
4. coarse memory signals are flat or improved

A weak-but-still-useful outcome would be:

1. draw-time improvement is real
2. total frame-time improvement is marginal
3. branch still provides evidence that path caching only pays off when combined with prepared-scene caching later

---

## 10. Failure Conditions

This branch should be considered a poor standalone optimization if:

1. `avgPrepareMs` rises more than `avgDrawMs` falls in most scenarios
2. total `avgFrameMs` regresses broadly
3. cached-path rendering changes output semantics
4. runner changes introduce branch/baseline coupling
5. memory or GC cost regresses without enough timing benefit to justify it

If that happens, the correct outcome is to stop treating standalone path caching as a winner and
carry it forward only as a possible combo-branch optimization with prepared-scene caching or a more
Compose-native cache lifetime model.

---

## 11. Acceptance Criteria

Before closing the branch:

1. renderer tests cover the main cached-path semantics
2. benchmark runner can execute a path-caching-only matrix
3. both self-tests still pass
4. 24/24 matrix scenarios complete
5. branch results clearly indicate `enablePathCaching=true` for matrix rows
6. a comparison summary exists against Phase 2 baseline, including timing and coarse memory signals

---

## 12. Deliverables

Expected deliverables:

1. code changes, if any, to renderer/test/runner support
2. branch benchmark result directory
3. comparison notes against Phase 2 baseline
4. implementation review documenting whether path caching is worthwhile as a standalone optimization

---

## 13. Commit Strategy

Keep commits atomic:

1. tests / renderer correctness clarification
2. runner support for branch execution
3. benchmark results/docs

Do not mix unrelated docs or other branch experiments into this branch.

---

## 14. Immediate First Task

Start with Step 1 and Step 3 together:

1. verify the renderer really uses the cached-path draw path only in the Compose renderer path
2. add one focused renderer test that distinguishes cached vs uncached rendering behavior without changing output semantics

That gives a clear correctness baseline before touching the runner.
