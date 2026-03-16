# Phase 3 Plan: `perf/spatial-index`

**Date:** 2026-03-11
**Status:** Plan
**Branch:** `perf/spatial-index`
**Depends On:** Phase 1 harness implementation, Phase 2 baseline run
**Baseline Reference:** `docs/reviews/2026-03-11-phase2-baseline-results.html`

---

## 1. Objective

Measure the isolated effect of the renderer spatial index with:

- `enableSpatialIndex = true`
- `enablePreparedSceneCache = false`
- `enablePathCaching = false`
- `enableNativeCanvas = false`
- `enableBroadPhaseSort = false`

This branch exists to answer one narrow question:

**Does the existing spatial-index path reduce hit-test cost enough to justify keeping it, either as a
standalone optimization or as a prerequisite for later combo branches?**

Success is not "the branch must win on frame time." Success is "the branch produces trustworthy data
about that question."

---

## 2. Why This Branch Is First

Phase 2 baseline data showed that hit testing becomes one of the dominant costs as scene size grows.

Representative baseline numbers from the physical-device run:

| Scenario | Avg Hit-Test | Avg Frame | Observation |
|---|---:|---:|---|
| `s50_m10_continuous` | `10.4155ms` | `23.9996ms` | Hit testing is already a major fraction of frame time |
| `s100_m10_continuous` | `34.6897ms` | `76.1760ms` | Interaction is clearly expensive |
| `s200_m10_continuous` | `87.8970ms` | `184.2846ms` | Baseline is far too slow |
| `s200_m50_continuous` | `106.1012ms` | `224.9124ms` | Worst-case interaction bottleneck |

By contrast, `NONE` scenarios had zero hit-test cost and were dominated by prepare/rebuild cost.

That means this branch should be judged primarily on:

1. `OCCASIONAL` and `CONTINUOUS` scenarios at `N=50`, `N=100`, and `N=200`
2. whether `NONE` scenarios regress because index construction is pure overhead
3. whether the branch improves tail hit-test latency enough to matter for later combo work

---

## 3. Current Code State

The spatial index already exists in
`isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRenderer.kt`.

Current implementation facts:

1. `enableSpatialIndex` exists and gates the fast path
2. `SpatialGrid` exists and is built from prepared commands
3. hit testing can use a filtered `PreparedScene`
4. `commandToNodeMap` already gives O(1) command-to-node resolution
5. `enableBroadPhaseSort` is still unimplemented; `BenchmarkActivity` warns if it is enabled

So this branch is not building a new optimization from scratch. It is:

1. validating the correctness of the existing fast path
2. fixing any correctness gaps that invalidate measurement
3. adding the minimum runner support needed to benchmark this branch cleanly
4. comparing the branch directly against the Phase 2 all-off baseline

---

## 4. Interpretation Constraint

This branch intentionally leaves prepared-scene caching disabled.

That has a direct consequence in the current renderer:

1. the scene is prepared
2. the spatial index is rebuilt during the same cache rebuild path
3. hit tests query that freshly built index

With `enablePreparedSceneCache = false`, the renderer rebuilds the prepared scene and the spatial
index on every measured update. So `avgPrepareMs` in this branch is guaranteed to include index-build
cost. That is not a side effect or a risk. It is the thing this isolated branch is measuring.

Expected result shape:

1. `avgHitTestMs` can improve
2. `avgPrepareMs` can regress
3. `avgFrameMs` may improve, stay flat, or regress depending on which side dominates

That is acceptable. This branch answers:

**"Is the query-side spatial index valuable on its own, with its real rebuild cost included?"**

It does **not** answer:

**"What happens when spatial indexing is combined with caching?"**

That is a later combo-branch question.

---

## 5. Hypotheses

### 5.1 Expected Wins

When interaction is active:

1. `avgHitTestMs` should drop for `OCCASIONAL` and `CONTINUOUS`
2. the biggest absolute gains should show up at `N=100` and `N=200`
3. `p95HitTestMs` and `p99HitTestMs` should improve more clearly than averages

### 5.2 Expected Non-Wins

When interaction is `NONE`:

1. hit-test time remains `0`
2. index construction is pure overhead
3. `avgPrepareMs` will rise relative to baseline
4. `avgFrameMs` may regress

### 5.3 Keep/Reject Threshold

The branch is worth keeping if at least one of these holds:

1. `CONTINUOUS` scenarios show meaningful frame-time improvement
2. hit-test latency drops enough to make the branch an obvious prerequisite for combo work
3. regressions in `NONE` scenarios stay small enough that opt-in use remains defensible

---

## 6. Risks To Resolve Before Benchmarking

### 6.1 Cell-Boundary Misses

The fast path must not miss hits whose tap radius crosses a cell boundary.

Required rule:

1. query expansion must scale from `ceil(hitRadius / cellSize)` in each axis

This must not be implemented as a hardcoded "neighboring 1-cell ring," because the branch now has a
configurable cell size and the hit radius is a renderer constant.

### 6.2 Candidate Ordering

The filtered-scene fast path must preserve the same winner as the slow path for overlapping geometry.

Required rules:

1. dedup candidate command IDs across queried cells
2. preserve original command order when constructing the filtered `PreparedScene`
3. add an explicit overlapping-candidate parity test

### 6.3 Bounds Correctness

Spatial insertion must remain correct for negative coordinates and offscreen bounds.

Required rules:

1. bounds calculation must use correct min/max sentinels
2. cell indexing must use `floor(...)`, not truncation
3. invalid insert ranges must be skipped cleanly

### 6.4 Harness Scope Control

This branch must not change benchmark methodology.

Do not change:

1. warmup behavior
2. iteration counts
3. measurement frame counts
4. baseline flag defaults
5. self-test definitions

Any runner changes in this branch must be limited to branch flag selection and result-directory
isolation.

---

## 7. Scope

### In Scope

1. spatial-index correctness fixes in renderer hit testing
2. spatial-index-specific renderer tests
3. minimal runner support for a branch matrix with `enableSpatialIndex=true`
4. full 24-scenario branch run on a comparable device
5. direct comparison against Phase 2 baseline data
6. branch review/report doc with a clear keep/reject/combo-only recommendation

### Out of Scope

1. prepared-scene caching changes
2. path caching changes
3. native-canvas changes
4. broad-phase sort implementation
5. changing production defaults
6. combination-branch work

---

## 8. Detailed Plan

### Step 1: Audit The Current Fast Path

Read and verify:

1. `IsometricRenderer` fast-path control flow
2. spatial-grid insertion logic
3. filtered-scene construction
4. command-to-node lookup path

Confirm:

1. `enableSpatialIndex=false` still uses the slow path
2. `enableSpatialIndex=true` actually builds and queries the grid
3. no other optimization is implicitly enabled
4. benchmark flag snapshots still match runtime behavior

### Step 2: Fix Renderer Correctness Gaps

Required implementation properties:

1. radius-aware multi-cell query using `ceil(hitRadius / cellSize)`
2. dedup across queried cells without losing scene order
3. filtered commands re-ordered to match original prepared-scene order
4. correct bounds insertion for negative/offscreen coordinates
5. fast out for obviously invalid/out-of-grid queries

Guardrails:

1. do not change slow-path semantics
2. keep the fast path fully behind `enableSpatialIndex`
3. do not change global defaults

### Step 3: Add Branch-Specific Tests

Add or extend tests in
`isometric-compose/src/test/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRendererTest.kt`.

Minimum required coverage:

1. fast-path parity with slow path for the same tap
2. hit near a cell boundary still resolves correctly
3. miss near a cell boundary still misses
4. overlapping candidates still return the frontmost node
5. invalid cell size is rejected
6. negative/offscreen bounds do not corrupt the index

### Step 4: Add Minimal Runner Support For Phase 3 Flags

The current runner is baseline-oriented. It supports:

1. `--skip-selftests`
2. `--sizes`
3. timestamp-isolated output directories
4. hardcoded self-test configs

It does **not** support generic optimization-branch flag overrides yet.

Add the smallest reusable mechanism needed to run this branch:

Preferred shape:

```bash
bash isometric-benchmark/benchmark-runner.sh \
  --enable-spatial-index \
  --label spatial-index
```

Requirements:

1. baseline defaults remain all-off if no branch flags are passed
2. self-tests always keep their hardcoded configs
3. branch overrides apply only to the 24-scenario matrix
4. branch runs write to a distinct result directory, e.g. `${TIMESTAMP}-spatial-index`
5. exported scenario metadata must still show the active flags explicitly

Do not add retries, dedup passes, or verification-script behavior in this branch. Those are not part
of the shipped Phase 2 runner contract today.

### Step 5: Smoke-Test The Branch

Run a reduced branch pass first:

1. `N=10`, `NONE`
2. `N=50`, `CONTINUOUS`
3. `N=200`, `CONTINUOUS`

Purpose:

1. confirm self-tests still pass under the existing hardcoded configs
2. confirm only the matrix scenarios use `enableSpatialIndex=true`
3. confirm branch results land in a distinct directory
4. catch correctness regressions before the full run

### Step 6: Run The Full 24-Scenario Matrix

Run the standard Phase 2 matrix:

1. sizes: `10`, `50`, `100`, `200`
2. mutation rates: `0.10`, `0.50`
3. interaction patterns: `NONE`, `OCCASIONAL`, `CONTINUOUS`

with flags:

1. `enableSpatialIndex=true`
2. all other optimization flags `false`

Use the same physical-device class as baseline if possible.

### Step 7: Compare Against Phase 2 Baseline

Comparison table should include at minimum:

1. `avgPrepareMs`
2. `avgFrameMs`
3. `avgHitTestMs`
4. `p95HitTestMs`
5. `p99HitTestMs`
6. `cacheHitRate`

Analysis must explicitly separate:

1. `NONE` scenarios, where prepare regression is expected overhead
2. interaction scenarios, where query-side wins are expected

Primary questions:

1. How much does hit-test latency improve for `OCCASIONAL` and `CONTINUOUS`?
2. How much prepare overhead does the index add to `NONE` scenarios?
3. Do any scenarios show clear end-to-end frame-time wins?
4. Is the branch strong enough to keep standalone, or only as a combo prerequisite?

---

## 9. Success Criteria

This branch is complete when all of the following are true:

1. the fast path has parity-oriented correctness tests
2. cell-boundary false negatives are fixed
3. ordering parity with the slow path is verified
4. runner support exists for a spatial-index-only branch matrix
5. self-tests still pass under their hardcoded configs
6. the full 24-scenario branch run completes on a connected device
7. a comparison doc makes one explicit recommendation:
   - adopt standalone
   - reject standalone
   - keep for combo branches only

---

## 10. Acceptance Criteria

### Technical Acceptance

1. `:isometric-compose:testDebugUnitTest` passes
2. `:isometric-benchmark:testDebugUnitTest` passes
3. `bash -n isometric-benchmark/benchmark-runner.sh` passes if the runner changes

### Benchmark Acceptance

1. both self-tests pass
2. 24/24 matrix scenarios complete
3. branch results clearly indicate `enableSpatialIndex=true` for matrix rows
4. no hit-test correctness regression is observed

### Decision Acceptance

The branch closes with one explicit recommendation:

1. adopt standalone
2. reject standalone
3. keep only for combo branches

---

## 11. Expected Result Shapes

### Best Case

1. large `avgHitTestMs`, `p95HitTestMs`, and `p99HitTestMs` reductions for `CONTINUOUS`
2. measurable frame-time wins at `N=100` and `N=200`
3. acceptable prepare overhead in `NONE` scenarios

### Mixed Case

1. hit-test latency improves sharply
2. total frame time improves only slightly or not at all
3. branch is still worth keeping as a combo prerequisite

### Worst Case

1. index-build cost overwhelms query-side wins
2. frame times do not improve
3. correctness remains fragile at boundaries or overlaps
4. recommendation: reject standalone and revisit only in combo work

---

## 12. Deliverables

1. renderer correctness changes, if needed
2. spatial-index renderer tests
3. branch benchmark result directory
4. baseline-vs-branch comparison doc or review note
5. final recommendation: adopt, reject, or combo-only

---

## 13. Commit Strategy

Keep branch history reviewable:

1. `test/renderer`: spatial-index correctness tests
2. `renderer`: spatial-index correctness fixes
3. `benchmark`: minimal runner support for branch flag selection, if needed
4. `docs`: branch results and recommendation

Do not mix renderer behavior changes with runner/interface changes in one commit.

---

## 14. Immediate First Task

Before touching the runner, confirm fast-path trustworthiness:

1. add or verify a parity test for hits near cell boundaries
2. add or verify an overlapping-candidate ordering test

Reason:

Benchmarking an incorrect fast path produces unusable branch data, no matter how good the numbers
look.
