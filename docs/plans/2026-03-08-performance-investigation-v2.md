# Performance Investigation Plan v2

**Date:** 2026-03-08
**Status:** Plan (Pre-Implementation)
**Predecessor:** `docs/plans/2026-01-03-isometric-performance-investigation.md`
**Approach:** Isolated Branch-Per-Optimization with Validated Benchmarking

---

## Context

The Compose Runtime API (`claude/redesign-compose-primitives-0OyB5`, 15 commits) is complete and
reviewed. It includes 9 performance optimizations — but all performance claims are theoretical.
Previous benchmark attempts (Jan 2026) revealed **critical issues**:

1. **0% cache hit rate** — PreparedScene cache showed 0 hits even on STATIC scenes, meaning
   caching was completely broken. All "cached" benchmarks were actually uncached.
2. **O(N^2) depth sorting dominates** — At N=100, prepare took 111ms; at N=500, 2422ms.
   Everything else is noise until sorting is addressed.
3. **Optimizations built cumulatively** — Impossible to isolate individual impact. Phase 3
   includes Phase 2's changes, so you can't tell if Phase 3 alone helps or hurts.
4. **Inflated claims** — Docs claimed "40x improvement" but no measurement validated this.
5. **Emulator-only testing** — AndroidX Microbenchmark refused to run; custom harness ran
   but results may not reflect real device behavior.

**Goal:** Restart performance work with proper methodology — isolated measurements per
optimization, validated caching, honest data, and a benchmark harness that actually works.

---

## Learnings from Previous Attempts

### What Worked

- **Custom benchmark harness design** — BenchmarkActivity with Choreographer-based frame pacing,
  500-frame warmup, pre-allocated metrics arrays. Sound architecture, just broken cache logic.
- **Deterministic scene generation** — Fixed seed (12345) for reproducibility. Separate mutation
  seed (67890). This approach should be preserved.
- **Comprehensive metrics** — Frame time percentiles (p50/p95/p99), prepare vs draw breakdown,
  cache hit/miss tracking. Keep all of this.
- **CSV export** — Enables offline analysis. Good format.

### What Failed

- **Cache validation was missing** — The harness collected cache hit/miss stats but never
  validated them. Static scene with 0% cache hits should have been flagged immediately.
- **AndroidX Microbenchmark on emulator** — All 13 tests failed with `ERRORS: EMULATOR`.
  Don't rely on this for primary measurement.
- **Scene sizes too large** — N=500 and N=1000 produced 2-10 second prepare times due to O(N^2)
  sorting. These dominated all results and made other optimizations irrelevant.
- **Cumulative optimization phases** — Each phase included all previous phases. Impossible to
  attribute improvement to a specific optimization.
- **No flag compliance checking** — No assertion that optimization flags were actually being
  respected by the renderer. "Enabled" didn't guarantee "working."

### Key Data Points from Previous Baseline (Jan 5, 2026)

| Scene | Avg Prepare (ms) | Avg Draw (ms) | Cache Hit Rate |
|-------|------------------|---------------|----------------|
| N=10 static | 5.0 | 0.9 | 0% |
| N=100 static | 111.0 | 10.0 | 0% |
| N=500 static | 2,422 | 20.9 | 0% |
| N=1000 static | 9,448 | 25.9 | 0% |
| N=10 full mutation | 1.4 | 0.1 | 0% |
| N=500 full mutation | 2,434 | 5.4 | 0% |

**Insight:** Draw time scales linearly and is reasonable (26ms at N=1000). Prepare time
scales quadratically due to depth sorting and dominates everything.

---

## Phase 0: Rebase & Merge (Linear History)

**Branch:** `claude/redesign-compose-primitives-0OyB5` (15 commits ahead of master)
**Merge base:** `ddb9495` — master hasn't moved, so this is a clean fast-forward.

```bash
git checkout claude/redesign-compose-primitives-0OyB5
git rebase master                                    # no-op (already based on master)
git checkout master
git merge --ff-only claude/redesign-compose-primitives-0OyB5  # linear history
```

**Archive old performance branch:**
```bash
git branch archive/performance-investigation-v1 performance-investigation
git branch -D performance-investigation
```

**Post-merge state:** master has the full Runtime API (IsometricScene, IsometricRenderer,
node tree, dirty tracking, path caching, spatial index, native canvas support).

---

## Phase 1: Build Benchmark Harness

### 1.1 Design Principles (Learnings Applied)

| Previous Issue | Fix |
|---|---|
| 0% cache hit rate | **Validate** cache stats at end of each run; fail if static scene has 0% hits |
| Emulator-only | Design to work on emulator with caveats; document "physical device recommended" |
| No flag validation | Assert optimization flags match config at startup; log flag state |
| Cumulative optimizations | Flag-driven: each optimization independently toggleable |
| O(N^2) at N>500 | Cap scene sizes at 200; still shows scaling trends without 10-second frames |
| JIT warmup noise | 500 warmup frames + forced GC + 2s cooldown (keep from previous harness) |
| Metrics allocation overhead | Pre-allocated LongArray for frame times (keep from previous harness) |

### 1.2 Benchmark Matrix

**Scene sizes:** 10, 50, 100, 200
**Mutation rates:** 10%, 50%
**Interaction patterns:** none, occasional (1 tap/sec), continuous (every frame)
**Total:** 4 x 2 x 3 = **24 scenarios per optimization level**

### 1.3 Optimization Flags

```kotlin
data class BenchmarkFlags(
    val enablePreparedSceneCache: Boolean = false,  // dirty-tracking skip
    val enablePathCaching: Boolean = false,          // Compose Path reuse
    val enableBroadPhaseSort: Boolean = false,       // spatial grid for O(N^2) sort
    val enableSpatialIndex: Boolean = false,         // grid-based hit testing
    val enableNativeCanvas: Boolean = false           // Android Canvas direct
)
```

**Baseline** = all flags false (pure naive implementation).

### 1.4 Metrics Collected

Per scenario run (500 measurement frames, 3 iterations):
- **Frame time:** avg, p50, p95, p99, min, max, stddev
- **Prepare time:** avg, p50, p95, p99 (the engine.prepare() call)
- **Draw time:** avg, p50, p95, p99 (the Canvas draw calls)
- **Hit test latency:** avg, p95 (only for interaction scenarios)
- **Cache stats:** hits, misses, hit rate % (VALIDATED: fail if static scene shows 0%)
- **Allocations:** estimated MB, GC invocations
- **Device info:** model, Android version, CPU, emulator flag

### 1.5 Harness Implementation

**Module:** `isometric-benchmark/` (new, clean implementation)

Key files:
- `BenchmarkActivity.kt` — Activity that receives config via Intent, runs benchmark
- `BenchmarkOrchestrator.kt` — Lifecycle: warmup -> cooldown -> measurement -> export
- `SceneGenerator.kt` — Deterministic scene generation (seed=12345)
- `MutationSimulator.kt` — Applies N% random mutations per frame (seed=67890)
- `InteractionSimulator.kt` — Pre-generated tap points at scene object locations
- `MetricsCollector.kt` — Zero-allocation frame time recording
- `ResultsExporter.kt` — CSV + JSON output
- `BenchmarkRunner.sh` — Shell script to run all 24 scenarios sequentially via adb

**Scene generation:**
- Uses `Prism`, `Pyramid` mix (60/40 split)
- Spatial spread: objects placed in grid pattern with jitter (avoids worst-case overlap)
- Colors: deterministic from seed (enables visual validation)

### 1.6 Harness Validation Checks

Before trusting results, the harness must pass:
1. **Cache validation:** Static scene + cache enabled -> hit rate > 95% after warmup
2. **Flag validation:** Log all flag states at startup; assert matches config
3. **Consistency check:** 3 iterations must have coefficient of variation < 15%
4. **Sanity check:** N=10 baseline frame time < 20ms (if not, something is wrong)
5. **Mutation validation:** 10% mutation rate -> ~10% of objects change per frame (log and verify)

---

## Phase 2: Baseline Benchmarks

Run all 24 scenarios with `BenchmarkFlags()` (all disabled).

**Purpose:**
- Establish ground truth for the naive implementation
- Validate harness works correctly and produces consistent results
- Identify the actual bottlenecks (is it prepare? draw? sorting?)

**Expected results based on previous data (adjusted for N<=200):**

| Size | Mutation | Interaction | Est. Prepare (ms) | Est. Draw (ms) |
|------|----------|-------------|-------------------|----------------|
| 10   | 10%      | none        | ~5                | ~1             |
| 50   | 10%      | none        | ~25               | ~5             |
| 100  | 10%      | none        | ~110              | ~10            |
| 200  | 10%      | none        | ~450              | ~15            |
| 200  | 50%      | continuous  | ~450              | ~15 + hit test |

**Deliverable:** `benchmark-results/baseline/` with CSV for all 24 scenarios.

---

## Phase 3: Individual Optimization Branches

Each branch created from master (post-merge). Each modifies ONLY the code for its
optimization. Benchmark harness runs with ONLY that optimization's flag enabled.

### Branch 1: `perf/prepared-scene-cache`

**What:** Skip `engine.prepare()` when scene hasn't changed (dirty tracking).

**Code changes:**
- `IsometricRenderer.kt`: Add `forceRebuild: Boolean` parameter (default false)
- When `forceRebuild=false` and `!rootNode.isDirty`: return cached PreparedScene
- When `forceRebuild=true`: always rebuild (baseline behavior)

**Benchmark flags:** `BenchmarkFlags(enablePreparedSceneCache = true)`

**Expected impact:**
- Static/10% mutation: 75-90% prepare time reduction
- 50% mutation: 30-50% prepare time reduction
- Full mutation: 0% (cache always stale)

**Validation:** Cache hit rate > 90% for 10% mutation scenes.

### Branch 2: `perf/path-caching`

**What:** Cache Compose `Path` objects between frames (avoid re-allocating).

**Code changes:**
- Already implemented in `IsometricRenderer.kt` via `CachedPath` and `enablePathCaching`
- Ensure flag properly toggleable (verify with baseline disabled)

**Benchmark flags:** `BenchmarkFlags(enablePathCaching = true)`

**Expected impact:**
- 20-30% reduction in GC pressure (fewer allocations per frame)
- Most visible in allocation metrics, less in frame time

### Branch 3: `perf/broad-phase-sort` (NEW IMPLEMENTATION)

**What:** Add spatial grid pre-filter to `IsometricEngine.sortPaths()` to reduce
O(N^2) pairwise intersection checks.

**Code changes in `IsometricEngine.kt`:**
- Add `enableBroadPhaseSort` to `RenderOptions`
- In `sortPaths()`: if enabled, bucket items into grid cells by 2D bounding box
- Only check intersection for items in same/adjacent cells
- Fine-phase: existing topological sort on reduced candidate set

**This is the only genuinely new implementation.** Everything else is toggling existing code.

**Benchmark flags:** `BenchmarkFlags(enableBroadPhaseSort = true)`

**Expected impact (from original investigation plan):**
- N=100: 40-60% sort time reduction (sparse scenes)
- N=200: 50-70% sort time reduction
- Dense overlapping: 10-20% (limited benefit)

**Key parameter to tune:** Grid cell size (test 50, 100, 200 pixel cells).

### Branch 4: `perf/spatial-index`

**What:** Grid-based spatial index for O(1) hit test queries.

**Code changes:**
- Already implemented in `IsometricRenderer.kt` via `SpatialGrid`
- Toggle via `enableSpatialIndex` parameter

**Benchmark flags:** `BenchmarkFlags(enableSpatialIndex = true)`

**Expected impact:**
- No-interaction scenes: ~0% (index build cost is pure overhead)
- Occasional interaction: 3-7x hit test speedup
- Continuous interaction: 7-25x hit test speedup (N=200)

### Branch 5: `perf/native-canvas`

**What:** Use `android.graphics.Canvas` directly instead of Compose DrawScope.

**Code changes:**
- Already implemented in `IsometricRenderer.kt` via `renderNative()`
- Toggle via `useNativeCanvas` parameter

**Benchmark flags:** `BenchmarkFlags(enableNativeCanvas = true)`

**Expected impact:**
- ~2x draw time reduction (Android-only)
- Most visible at larger N where draw time is significant

---

## Phase 4: Combined Branches

Create progressive merges to measure interaction effects:

### Combo 1: `perf/cache-combo`
- Merge: `perf/prepared-scene-cache` + `perf/path-caching`
- **Flags:** `enablePreparedSceneCache=true, enablePathCaching=true`
- **Purpose:** Test if caching optimizations are multiplicative
- Expected: near-zero prepare+draw time for static/low-mutation scenes

### Combo 2: `perf/cache-sort-combo`
- Merge: `perf/cache-combo` + `perf/broad-phase-sort`
- **Flags:** above + `enableBroadPhaseSort=true`
- **Purpose:** Test if sorting improvement stacks with caching
- Expected: large benefit for high-mutation scenes (where cache misses force re-sort)

### Combo 3: `perf/full-combo`
- Merge: `perf/cache-sort-combo` + `perf/spatial-index` + `perf/native-canvas`
- **Flags:** all enabled
- **Purpose:** Maximum optimization
- Expected: best overall performance across all scenarios

**Total benchmark runs:** 24 scenarios x 9 configurations (baseline + 5 individual + 3 combos)
= **216 runs** at ~30-60s each = **~2-4 hours** on device.

---

## Phase 5: Analysis & Decision

### 5.1 Results Table

Generate comparison table per scenario showing:
- Absolute frame time for each configuration
- % improvement vs baseline
- Cache hit rate (must be validated)
- Regression detection (any config slower than baseline?)

### 5.2 Decision Criteria (from original plan)

```
For optimization O in scenario S:
  improvement < 15%  -> REJECT (not worth complexity)
  15-30%             -> CONDITIONAL (adopt only if zero regression risk)
  >= 30%             -> ADOPT (clear win)
  regression > 5%    -> REJECT or make opt-in
```

### 5.3 Deliverables

- `benchmark-results/` directory with CSVs for all 216 runs
- `docs/BENCHMARK_REPORT.md` — data-driven report with actual numbers
- Final branch with winning optimizations merged and enabled by default

---

## Gains vs Losses

### What We Gain

1. **Honest, measured performance data** — No more theoretical estimates. Every claim
   backed by actual benchmark numbers.
2. **Isolated optimization impact** — Each optimization measured independently. Know
   exactly what each one contributes (and whether it regresses anything).
3. **Interaction analysis** — Combined branches reveal multiplicative vs additive effects.
   Some optimizations may only shine when paired with others.
4. **Cherry-pick winners** — If broad-phase sort doesn't help at N<=200, don't ship it.
   Only adopt what the data supports.
5. **Regression detection** — If an optimization hurts small scenes, we'll see it in the
   N=10 data. Previous approach would have masked this.
6. **Reproducible methodology** — Fixed seeds, documented harness, CSV output. Anyone
   can re-run and verify.
7. **Clean git history** — Each optimization's code changes isolated in its own branch,
   easy to review, revert, or bisect.

### What We Lose

1. **Git management overhead** — 8+ branches to maintain. Merge conflicts when combining
   branches that touch the same files (especially `IsometricRenderer.kt`).
2. **Time investment** — ~216 benchmark runs plus harness development. The harness alone
   is a meaningful engineering effort.
3. **Dependency blindness** — Testing optimizations in isolation may miss synergies.
   Path caching alone has limited value, but with PreparedScene cache it's powerful.
   The combo branches mitigate this, but we can't test ALL combinations.
4. **Emulator accuracy** — If benchmarking on emulator, absolute numbers are unreliable.
   Relative comparisons (% improvement) are still valid though.
5. **Scope creep risk** — Broad-phase sort is a genuinely new implementation that could
   take significant effort to get right.

### Risk Mitigation

- **Merge conflicts:** Keep optimization branches minimal — only change what's needed.
  Most optimizations are flag toggles, not new code (except broad-phase sort).
- **Time:** Automate benchmark runs with shell script. 24 scenarios per config is
  ~20-30 minutes unattended.
- **Emulator:** Focus on relative improvements (%), not absolute numbers. If a physical
  device becomes available, re-run the same harness for absolute validation.

---

## Key Files

| File | Role |
|---|---|
| `isometric-core/.../IsometricEngine.kt` | Core engine: projection, sorting, hit testing |
| `isometric-compose/.../IsometricRenderer.kt` | Renderer: caching, path conversion, spatial index |
| `isometric-compose/.../IsometricScene.kt` | Top-level composable with optimization flags |
| `isometric-compose/.../IsometricNode.kt` | Node tree: dirty tracking, render commands |
| `isometric-compose/.../RenderContext.kt` | Transform accumulation for node tree |
| `isometric-benchmark/` (new) | Benchmark harness module |
| `benchmark-results/` | Output directory for CSV results |

---

## Execution Order

1. **Rebase & merge** compose primitives to master (fast-forward)
2. **Build benchmark harness** on master in new `perf/benchmark-harness` branch
3. **Run baseline** (24 scenarios, all opts disabled)
4. **Create individual branches** (5 branches from master + harness)
5. **Run individual benchmarks** (24 scenarios x 5 branches = 120 runs)
6. **Create combo branches** (merge pairs/groups)
7. **Run combo benchmarks** (24 scenarios x 3 combos = 72 runs)
8. **Analyze** — compare all results, apply decision criteria
9. **Final merge** — winning optimizations to master with enabled defaults

---

## Verification

1. Harness validation: static scene cache hit rate > 95%
2. Harness consistency: 3 iterations per scenario, CV < 15%
3. Baseline sanity: N=10 frame time < 20ms
4. All optimizations: no > 5% regression on N=10 static (overhead check)
5. Broad-phase sort: prepare time reduction > 30% at N=200
6. Results CSV parseable and complete (24 rows per config)
