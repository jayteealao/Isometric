# Phase 3 Plan: `perf/broad-phase-sort`

**Date:** 2026-03-11
**Status:** Plan
**Branch:** `perf/broad-phase-sort`
**Base branch:** `perf/benchmark-harness`
**Depends On:** Phase 1 harness implementation, Phase 2 baseline run

---

## 1. Objective

Implement and measure a broad-phase prefilter for depth sorting.

This branch should answer one specific question:

**Can we reduce prepare-time cost by pruning unnecessary pairwise overlap checks inside
`IsometricEngine.sortPaths()` without changing final draw order correctness?**

Target branch flags:

- `enableBroadPhaseSort = true`
- `enablePreparedSceneCache = false`
- `enablePathCaching = false`
- `enableSpatialIndex = false`
- `enableNativeCanvas = false`

This is the only Phase 3 branch that introduces a genuinely new engine optimization rather than
toggling an existing one.

---

## 2. Why This Branch Matters

Phase 2 baseline established that prepare/rebuild cost dominates frame time at larger scene sizes,
including in non-interaction scenarios. That makes depth-sorting work one of the most plausible
sources of broad improvement because it is paid even when:

1. interaction is `NONE`
2. hit testing is unused
3. draw work is not the bottleneck

Unlike the spatial-index branch, this branch targets the prepare path directly. If it works, it can
improve:

1. `NONE`
2. `OCCASIONAL`
3. `CONTINUOUS`

all at once, because all three rely on scene preparation.

---

## 3. Current Code State

Current implementation facts:

1. `BenchmarkFlags.enableBroadPhaseSort` exists
2. `BenchmarkActivity` logs a warning if `enableBroadPhaseSort=true`
3. `RenderOptions` does **not** yet expose a broad-phase sort control
4. `IsometricEngine.sortPaths()` still does all pairwise 2D intersection checks
5. the benchmark runner does not yet expose a branch flag override for this optimization

So this branch must do real implementation work in both:

1. engine/runtime flag plumbing
2. pair-pruning logic inside the sort path

---

## 4. Non-Negotiable Correctness Constraint

This optimization must change **how many candidate pairs are examined**, not **what ordering
dependencies are discovered** for pairs that truly can overlap.

That means:

1. if two transformed items can overlap in screen space, the broad phase must still allow the
   narrow-phase intersection/depth check to run
2. if two transformed items cannot overlap in screen space, skipping them is correct
3. the resulting dependency graph must produce the same final draw order as the baseline path

The branch is invalid if it improves speed by missing real dependencies.

---

## 5. Proposed Design

### 5.1 Broad-Phase Model

Use a 2D spatial grid over transformed item bounds during `sortPaths()`:

1. transform all items to 2D as today
2. compute an axis-aligned 2D bounding box for each transformed item
3. insert each item index into every grid cell its bounds overlap
4. generate candidate item pairs only from items that share at least one cell
5. run the existing narrow-phase logic only on those candidate pairs:
   - 2D polygon intersection
   - 3D depth comparison
   - dependency graph update
6. keep the existing topological-sort closeout logic unchanged

### 5.2 Why This Shape Is Safe

Using transformed 2D bounds is the right broad-phase key because the narrow phase already starts from
2D projection overlap. If two items do not share any 2D bounding-box cell coverage, they cannot have
2D polygon intersection and therefore cannot produce a sorting dependency.

### 5.3 Candidate Pair Dedup

Pair generation must deduplicate item pairs across cells.

Required properties:

1. each unordered pair `(i, j)` is checked at most once
2. pair identity is stable regardless of cell iteration order
3. self-pairs are never produced

Recommended shape:

- normalize every pair as `(min(i, j), max(i, j))`
- dedup via a `HashSet<Long>` or similar compact pair key

### 5.4 Cell Size

This branch needs a tunable broad-phase cell size.

Recommended default:

- `100.0` pixels initially, matching the current spatial-index default scale

Required behavior:

1. non-positive values are rejected
2. benchmark output makes the chosen cell size visible
3. the implementation can later test `50`, `100`, and `200` without redesign

---

## 6. Scope

### In Scope

1. `RenderOptions` plumbing for broad-phase sorting
2. engine implementation inside `sortPaths()`
3. tests proving parity with the old sort path
4. minimal runtime/benchmark plumbing needed to turn the optimization on
5. full 24-scenario branch benchmark run
6. comparison against the Phase 2 baseline

### Out of Scope

1. prepared-scene caching changes
2. path caching changes
3. spatial hit-testing changes
4. native-canvas changes
5. combo-branch work
6. changing default production behavior without benchmark evidence

---

## 7. Detailed Plan

### Step 1: Isolate The Sort Hot Path

Read and document the current path:

1. `IsometricEngine.prepare()`
2. `sortPaths()`
3. `IntersectionUtils.hasIntersection()`
4. dependency graph construction
5. topological sort closeout

Confirm:

1. where pairwise `O(N^2)` checks happen today
2. which parts are pair-prunable and which must remain unchanged
3. what benchmark-facing flags need plumbing

### Step 2: Add Runtime Flag Plumbing

Implementation tasks:

1. add `enableBroadPhaseSort` to `RenderOptions`
2. thread the benchmark flag through to the render context / engine prepare call
3. remove the benchmark warning once the feature exists

Guardrails:

1. baseline defaults remain unchanged
2. if the flag is off, engine behavior remains byte-for-byte equivalent to current semantics

### Step 3: Implement Broad-Phase Candidate Filtering

Implementation tasks:

1. compute transformed item bounds before sorting
2. bucket item indices into a 2D grid
3. generate unique candidate pairs from shared cells
4. run the existing narrow phase only on those pairs
5. leave dependency graph meaning and topological sort behavior unchanged

Required invariants:

1. no missing candidate pairs for truly overlapping bounds
2. no duplicate pair checks
3. unchanged output ordering for equivalent inputs

### Step 4: Add Tests Before Benchmarking

Minimum required coverage:

1. broad-phase off vs on produces identical command order for representative scenes
2. overlapping shapes in adjacent/neighboring cells still produce correct ordering dependencies
3. non-overlapping distant shapes do not require pair checks to preserve correctness
4. grid-cell-size validation rejects non-positive values
5. dense overlap scenes still behave correctly when pruning has little or no benefit

Recommended test shape:

1. sparse scene with clearly separated groups
2. partially overlapping scene
3. dense overlap scene

### Step 5: Add Minimal Runner Support

The current runner is baseline-oriented. For this branch, add the smallest reusable mechanism needed
to run:

```bash
bash isometric-benchmark/benchmark-runner.sh \
  --enable-broad-phase-sort \
  --label broad-phase-sort
```

Requirements:

1. baseline defaults stay all-off when no overrides are provided
2. self-tests keep their hardcoded configs
3. branch overrides apply only to the 24-scenario matrix
4. branch result directories stay isolated from baseline output

### Step 6: Smoke Test Before Full Matrix

Run:

1. `N=10`, `NONE`
2. `N=100`, `NONE`
3. `N=200`, `NONE`
4. `N=200`, `CONTINUOUS`

Purpose:

1. verify self-tests still pass
2. verify the broad-phase flag is actually active
3. check for ordering regressions before full benchmarking
4. validate that prepare-time measurement is the primary signal

### Step 7: Run Full 24-Scenario Matrix

Run the standard matrix with:

- `enableBroadPhaseSort = true`
- all other optimization flags `false`

### Step 8: Compare Against Baseline

Primary comparison metrics:

1. `avgPrepareMs`
2. `p95PrepareMs`
3. `p99PrepareMs`
4. `avgFrameMs`
5. `stdDevPrepareMs`

Secondary comparison metrics:

1. `avgHitTestMs`
2. `avgDrawMs`

Expected read:

1. prepare should improve first
2. frame time may improve downstream if prepare dominates enough
3. hit-test metrics should be mostly unchanged because this branch does not target hit testing

---

## 8. Benchmark Hypotheses

### 8.1 Best Case

In sparse or moderately overlapping scenes:

1. pair count drops sharply
2. `avgPrepareMs` falls meaningfully at `N=100` and `N=200`
3. `avgFrameMs` improves across all interaction patterns

### 8.2 Mixed Case

1. `NONE` scenarios improve clearly
2. interactive scenarios improve only modestly because other costs dominate
3. branch is still worth keeping because it reduces the base prepare cost

### 8.3 Worst Case

1. grid build overhead offsets pair-pruning gains
2. dense scenes gain little because many items still share cells
3. branch becomes useful only as a prerequisite for later combo work or is rejected outright

---

## 9. Major Risks

### 9.1 Missing Dependencies

The biggest risk is incorrectly pruning a pair that should have contributed a dependency edge.
That would silently produce wrong draw order.

### 9.2 False Confidence From Sparse Tests

If tests only cover obviously separated items, the broad-phase code can look correct while still
failing on adjacent-cell and partial-overlap cases.

### 9.3 Cell-Size Overfitting

If the branch is benchmarked only at one arbitrary cell size, the result may reflect tuning luck more
than the optimization itself. Start with one default, but leave the branch structured to test other
sizes if needed.

### 9.4 Benchmark Drift

Do not let runner changes, flag plumbing, or metadata work alter the Phase 2 methodology.

---

## 10. Success Criteria

This branch is complete when all of the following are true:

1. `enableBroadPhaseSort` is actually implemented, not just logged
2. broad-phase on/off parity tests pass
3. benchmark runner can execute a broad-phase-only matrix
4. self-tests still pass under branch execution
5. full 24-scenario run completes on a connected device
6. results are compared directly against the Phase 2 baseline
7. the branch closes with one explicit recommendation:
   - adopt standalone
   - reject standalone
   - keep only for combo branches

---

## 11. Acceptance Criteria

### Technical Acceptance

1. `:isometric-core:test` passes
2. `:isometric-compose:testDebugUnitTest` passes
3. `:isometric-benchmark:testDebugUnitTest` passes
4. `bash -n isometric-benchmark/benchmark-runner.sh` passes if the runner changes

### Benchmark Acceptance

1. both self-tests pass
2. 24/24 matrix scenarios complete
3. branch results clearly indicate `enableBroadPhaseSort=true` for matrix rows
4. no ordering regressions are observed

### Decision Acceptance

One explicit closeout decision:

1. adopt standalone
2. reject standalone
3. keep only for combo branches

---

## 12. Deliverables

1. engine/runtime implementation for broad-phase sort
2. parity-oriented tests
3. branch benchmark result directory
4. baseline-vs-branch comparison report
5. final recommendation

---

## 13. Commit Strategy

Keep history reviewable:

1. `engine`: broad-phase sort implementation and flag plumbing
2. `test`: parity and edge-case coverage
3. `benchmark`: minimal runner support for branch execution
4. `docs`: branch results and recommendation

Do not mix engine behavior changes with benchmark-runner interface changes in one commit.

---

## 14. Immediate First Task

Before touching the runner:

1. instrument and reason through the current `sortPaths()` pair-generation path
2. write at least one parity test for sort order on a sparse scene and one on a partially overlapping scene

Reason:

If broad-phase pruning changes draw order, the branch data is worthless regardless of how much faster
it looks.
