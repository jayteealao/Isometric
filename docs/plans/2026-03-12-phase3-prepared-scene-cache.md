# Phase 3 Plan: `perf/prepared-scene-cache`

**Date:** 2026-03-12
**Status:** Plan
**Branch:** `perf/prepared-scene-cache`
**Base branch:** `perf/benchmark-harness`
**Depends On:** Phase 1 harness implementation, Phase 2 baseline run

---

## 1. Objective

Measure and, if needed, tighten the existing PreparedScene cache path in `IsometricRenderer`.

This branch should answer one specific question:

**When `enablePreparedSceneCache=true`, how much frame cost can we eliminate by skipping
`engine.prepare()` on frames where the node tree, viewport, render options, and lighting inputs
are unchanged, and how does that behavior hold up under the standard benchmark matrix?**

Target branch flags:

- `enablePreparedSceneCache = true`
- `enablePathCaching = false`
- `enableSpatialIndex = false`
- `enableNativeCanvas = false`
- `enableBroadPhaseSort = false`

This branch is different from `perf/broad-phase-sort`:

1. it does not introduce a new algorithm in `IsometricEngine`
2. it validates and isolates an optimization that already exists in the Compose renderer
3. its primary deliverable may be benchmark evidence rather than substantial code changes
4. it requires one branch-specific stable-scene run in addition to the standard 24-scenario matrix

---

## 2. Why This Branch Matters

Phase 2 baseline established that prepare/rebuild cost dominates frame time at larger scene sizes.
Prepared-scene caching attacks that cost at the highest-leverage point:

1. instead of making `prepare()` cheaper, it avoids calling `prepare()` at all
2. it applies before draw-path optimizations such as path caching or native canvas
3. it should benefit every scenario where the scene remains stable across frames

This branch is the clearest test of the question:

**How much of the observed frame cost is repeated work on unchanged scenes?**

---

## 3. Current Code State

Current implementation facts on `perf/benchmark-harness`:

1. `BenchmarkFlags.enablePreparedSceneCache` already exists
2. `BenchmarkScreen` already maps it to `forceRebuild = !flags.enablePreparedSceneCache`
3. `IsometricRenderer` already has:
   - `cachedPreparedScene`
   - `needsUpdate(...)`
   - `rebuildCache(...)`
   - `ensurePreparedScene(...)`
   - benchmark hooks for cache hits and misses
4. `IsometricRendererTest` already covers several cache invalidation cases:
   - lightDirection changes
   - renderOptions changes
   - dirty-root rebuild
   - viewport resize rebuild
   - invalidate() behavior
5. the benchmark runner does **not** yet provide a branch override for this flag
6. the existing self-test `selftest_cache` already exercises this path with all cache-oriented
   optimizations enabled, but not in branch-isolated form

That means this branch is not “build the cache feature.” It is:

1. validate cache correctness and exact invalidation semantics
2. add minimal runner support to isolate the cache-only branch
3. add one explicit stable-scene cache measurement path
4. benchmark it cleanly against Phase 2 baseline

---

## 4. Critical Benchmark Constraint

Prepared-scene caching only helps when the scene stays stable between frames.

That constraint is especially important in this harness:

1. when `mutationRate > 0.0`, `BenchmarkOrchestrator` mutates items during measurement
2. item mutation marks the tree dirty
3. a dirty tree correctly invalidates the prepared-scene cache
4. therefore many mutation-heavy scenarios should show low cache-hit rates and small wins

The current standard matrix does not include `mutationRate=0.0`; it only covers `0.10` and `0.50`.
That means the matrix alone cannot answer the branch’s primary “unchanged scene” question.

So the expected signal is:

1. **strongest** in `NONE` scenarios at `mutationRate=0.10` or `0.50` only if the mutations do not
   structurally invalidate every frame
2. **most trustworthy** in an explicit stable-scene branch run with `mutationRate=0.0`
3. **weakest** in highly mutating scenarios, where cache misses are the correct behavior

This branch is valid even if it only wins strongly in the stable-scene run and shows weaker gains in
the matrix. That is not a branch failure; it is the actual cache behavior being measured.

---

## 5. Non-Negotiable Correctness Constraint

Prepared-scene caching must never reuse stale projection/sort output.

The cache is invalid unless all of the following are unchanged:

1. node tree contents / dirty state
2. viewport width
3. viewport height
4. `RenderOptions`
5. `lightDirection`

The branch is invalid if it “improves” performance by reusing a stale `PreparedScene` across any of
those changes.

---

## 6. Scope

### In Scope

1. review and tighten `IsometricRenderer` cache invalidation semantics
2. add/expand tests around prepared-scene cache behavior
3. add minimal runner support to execute a prepared-scene-cache-only matrix
4. run targeted smoke scenarios
5. run the full 24-scenario branch matrix
6. compare against the Phase 2 baseline

### Out of Scope

1. path caching changes
2. spatial hit-testing changes
3. broad-phase sort changes
4. native canvas changes
5. combo-branch work
6. changing engine sort/render algorithms

---

## 7. Detailed Plan

### Step 1: Audit The Existing Cache Contract

Read and document the current cache path:

1. `BenchmarkScreen`
2. `IsometricScene`
3. `IsometricRenderer.ensurePreparedScene()`
4. `IsometricRenderer.needsUpdate()`
5. `IsometricRenderer.rebuildCache()`

Confirm:

1. exactly what inputs invalidate the cache today
2. how `forceRebuild` interacts with `enablePreparedSceneCache`
3. whether any benchmark-driven state changes are accidentally invalidating cache every frame
4. whether draw-only work can proceed entirely from cached `PreparedScene`

Required output:

1. one short note in code comments or plan notes clarifying the cache contract

### Step 2: Verify Flag Plumbing End To End

Implementation tasks:

1. confirm `enablePreparedSceneCache` is parsed from JSON config
2. confirm it reaches `BenchmarkScreen`
3. confirm `forceRebuild = !flags.enablePreparedSceneCache`
4. confirm runtime flag snapshots and validation report the expected state

Guardrails:

1. baseline defaults remain unchanged
2. self-tests keep their current hardcoded configs
3. cache-enabled branch execution must not affect baseline execution semantics

### Step 3: Tighten Cache Correctness Tests

Minimum required coverage:

1. same scene + same context + same size does **not** rebuild
2. dirty root rebuilds
3. viewport size change rebuilds
4. `RenderOptions` change rebuilds
5. `lightDirection` change rebuilds
6. `invalidate()` clears prepared-scene state
7. `forceRebuild=true` disables cache reuse even on stable frames

Recommended additions if missing:

1. a cache-hit test that proves repeated stable frames produce cache-hit events in `MetricsCollector`
2. a “mutation every frame” test showing low or zero cache hit rate is expected when the scene is
   actually changing

### Step 4: Add Minimal Runner Support

The current runner is baseline-oriented. Add the smallest reusable mechanism needed to run:

```bash
bash isometric-benchmark/benchmark-runner.sh \
  --enable-prepared-scene-cache \
  --label prepared-scene-cache
```

Requirements:

1. baseline defaults stay all-off when no overrides are provided
2. self-tests keep their hardcoded configs
3. branch overrides apply only to the 24-scenario matrix
4. branch result directories stay isolated from baseline output
5. there is a simple way to launch one stable-scene branch run outside the matrix:

```bash
adb shell am start -n io.fabianterhorst.isometric.benchmark/.BenchmarkActivity \
  --es config '{"sceneSize":100,"mutationRate":0.0,"interactionPattern":"NONE","name":"stable_cache_probe","iterations":3,"measurementFrames":500,"flags":{"enablePathCaching":false,"enableSpatialIndex":false,"enablePreparedSceneCache":true,"enableNativeCanvas":false,"enableBroadPhaseSort":false}}'
```

### Step 5: Smoke Test The Cache Signal

Run a small set before the full matrix:

1. stable-scene probe: `N=10`, `mutationRate=0.0`, `NONE`
2. stable-scene probe: `N=100`, `mutationRate=0.0`, `NONE`
3. matrix representative: `N=100`, `mutationRate=0.10`, `NONE`
4. matrix representative: `N=100`, `mutationRate=0.10`, `CONTINUOUS`

Purpose:

1. verify self-tests still pass
2. verify cache-hit rate is meaningfully above baseline in the explicit stable-scene probes
3. verify mutation/interaction scenarios do not falsely report high cache hits if the tree is dirty
4. confirm the branch result directory and flags are tagged correctly

### Step 6: Run Full 24-Scenario Matrix

Run the standard matrix with:

- `enablePreparedSceneCache = true`
- all other optimization flags `false`

### Step 7: Compare Against Baseline

Primary comparison metrics:

1. stable-scene probe `cacheHitRate`
2. stable-scene probe `avgPrepareMs`
3. matrix `cacheHitRate`
4. matrix `avgPrepareMs`
5. `avgFrameMs`

Secondary comparison metrics:

1. `avgDrawMs`
2. `avgHitTestMs`

Expected read:

1. if the cache is working, prepare cost should collapse toward zero on cache-hit frames in the
   stable-scene probes
2. the clearest wins should appear in the stable-scene probes
3. the matrix primarily shows how much benefit survives under real benchmark invalidation pressure
4. mutation-heavy scenarios may show smaller wins or none at all, and that may be correct

### Step 8: Close Out With An Explicit Recommendation

The branch ends with one of:

1. adopt standalone
2. reject standalone
3. keep only for combo branches

---

## 8. Benchmark Hypotheses

### 8.1 Best Case

In low-mutation or effectively stable scenarios:

1. cache-hit rate is very high
2. `avgPrepareMs` drops sharply
3. `avgFrameMs` improves materially at all scene sizes

### 8.2 Mixed Case

1. `NONE` scenarios improve strongly
2. `OCCASIONAL` improves moderately
3. `CONTINUOUS` improves only when interaction itself is not the bottleneck
4. high-mutation rows show weaker gains because cache invalidation is correct

### 8.3 Worst Case

1. the benchmark loop invalidates the tree every frame even in scenarios we thought were stable
2. cache-hit rate stays near zero across most of the matrix
3. this branch ends up being useful only in production workloads that are less mutation-heavy than
   the benchmark harness

---

## 9. Major Risks

### 9.1 False Cache Hits

The highest-risk bug is stale scene reuse after:

1. mutations
2. render option changes
3. light changes
4. viewport changes

### 9.2 Benchmark Misread

If mutation-heavy scenarios are treated as “cache failed,” the branch will be misjudged. Low hit
rate under real invalidation is correct behavior, not necessarily a bug.

### 9.3 Hidden Invalidation Source

If the benchmark loop or scene plumbing marks the tree dirty every frame for unrelated reasons,
cache benefit will be masked and the branch data will be misleading.

### 9.4 Runner Drift

Runner support for this branch must remain minimal and must not alter the Phase 2 execution model.

---

## 10. Success Criteria

This branch is complete when all of the following are true:

1. the prepared-scene cache contract is explicitly documented and reviewable
2. cache correctness tests pass
3. one stable-scene branch probe demonstrates real cache reuse
4. benchmark runner can execute a prepared-scene-cache-only matrix
5. self-tests still pass under branch execution
6. full 24-scenario run completes on a connected device
7. results are compared directly against the Phase 2 baseline
8. the branch closes with one explicit recommendation

---

## 11. Acceptance Criteria

### Technical Acceptance

1. `:isometric-compose:testDebugUnitTest` passes
2. `:isometric-benchmark:testDebugUnitTest` passes
3. `bash -n isometric-benchmark/benchmark-runner.sh` passes if the runner changes

### Benchmark Acceptance

1. both self-tests pass
2. stable-scene probe shows high cache-hit rate and low prepare cost relative to baseline
3. 24/24 matrix scenarios complete
4. branch results clearly indicate `enablePreparedSceneCache=true` for matrix rows
5. cache-hit behavior is explainable and consistent with scene invalidation semantics

### Decision Acceptance

One explicit closeout decision:

1. adopt standalone
2. reject standalone
3. keep only for combo branches

---

## 12. Deliverables

1. any cache-path hardening changes needed for correctness or measurement clarity
2. cache-specific tests
3. runner support for prepared-scene-cache branch execution
4. stable-scene probe result artifact
5. branch benchmark result directory
6. baseline-vs-branch comparison report
7. final recommendation

---

## 13. Commit Strategy

Keep history reviewable:

1. `test`: cache invalidation / reuse coverage
2. `benchmark`: minimal runner support for branch execution
3. `docs`: branch plan, results, and recommendation

Only add a separate `renderer` commit if the cache implementation itself needs hardening beyond
tests and measurement plumbing.

---

## 14. Immediate First Task

Before touching the runner:

1. verify that stable frames in the benchmark harness can actually produce cache hits
2. add or tighten one focused test for `forceRebuild` vs stable-scene reuse

Reason:

If the harness invalidates the scene every frame regardless of mutation state, this branch’s matrix
results will be misleading no matter how clean the runner interface looks.
