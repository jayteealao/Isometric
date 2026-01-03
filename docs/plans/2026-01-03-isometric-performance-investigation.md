# Isometric Library Performance Investigation & Benchmark Plan

**Date:** 2026-01-03
**Status:** Investigation Plan (Pre-Implementation)
**Author:** Performance Engineering
**Approach:** Sequential Optimization Analysis

---

## Executive Summary

This investigation quantifies the performance impact of four optimization techniques for the Isometric rendering library across diverse device profiles and scene characteristics. Using a sequential benchmark approach, we'll measure each technique's contribution to frame time, memory, and interaction latency.

**Goal:** Provide data-driven evidence to justify (or reject) architectural changes before implementation.

**Timeline:** Manual benchmark runs with full harness, prioritizing measurement accuracy over automation.

**Success Criteria:** Identify which optimizations are critical vs marginal, and for which scenarios.

**Optimization Techniques Under Investigation:**
1. PreparedScene caching
2. Compose Modifier.drawWithCache
3. Broad-phase depth sorting
4. Spatial-index-based hit testing

---

## 1. Baseline Definition: Current "Naïve" Implementation

### Per-Frame Work in `IsometricEngine.prepare()`

**1. 3D-to-2D Projection** (all N items)
- Transform each point: `originX + point.x * transformation[0][0] + ...`
- Allocates `List<Point2D>` per item (N allocations)
- Complexity: O(N × P) where P = avg points per shape

**2. Lighting Calculation** (all N items)
- Cross product for surface normal
- Dot product with light vector
- Color arithmetic: `color.lighten(brightness * colorDifference)`
- Complexity: O(N × 3) — fixed per path

**3. Depth Sorting** (if enabled)
- Pairwise intersection checks: O(N²)
- Each `hasIntersection()`: bounding box + edge crossing + point-in-poly
- Topological sort: O(N²) worst case
- **This dominates at N > 100**

**4. Hit Testing** (`findItemAt`)
- Linear scan: O(N)
- Per item: convex hull build + point-in-poly test
- Called on every tap/drag — separate `prepare()` call
- Complexity: O(N × M) where M = hull size

### Allocations Per Frame

- N × `TransformedItem` objects
- N × `List<Point2D>` for transformed points
- 1 × `List<RenderCommand>` with N entries
- Hit testing: 1 additional temporary `PreparedScene`

### No Caching

Every recomposition triggers full `prepare()` even if scene unchanged.

---

## 2. Technique Analysis

### 2.1 PreparedScene Caching

#### What It Optimizes

Avoids recomputing `prepare()` when scene content hasn't changed. Currently, every Compose recomposition (even unrelated state changes) triggers full projection, lighting, and sorting.

**Computation Avoided:**
- 3D→2D transformation for all N items
- Lighting calculations for all N paths
- O(N²) depth sorting
- RenderCommand allocation

**What Gets Cached:**
- The complete `PreparedScene` (sorted `List<RenderCommand>`)
- Invalidate only when:
  - Items added/removed (`add()`, `clear()`)
  - Scene content mutated (shape positions/colors change)
  - Viewport size changes (width/height)

**Theoretical Improvement:**
- **Static scenes:** ~100% reduction in per-frame CPU (only draw)
- **Incremental updates:** Recompute only when scene mutates, not every frame
- **Full updates:** No benefit (cache always stale)

#### Costs & Risks

**Memory Overhead:**
- 1 cached `PreparedScene` ≈ N × 64 bytes (RenderCommand + Point2D list refs)
- Example: 500 items × 64 bytes ≈ 32KB (negligible)

**Cache Invalidation Complexity:**
- Need version tracking in `IsometricEngine` (trivial: increment counter on mutation)
- Compose side: `remember(sceneVersion)` key
- **Risk:** Forgetting to invalidate → stale rendering bugs

**Worst-Case Scenarios:**
- Scene updates every frame → cache thrash, pure overhead
- Very small scenes (N < 20) → overhead > benefit

**Expected Win Scenarios:**
- Static or rarely-changing scenes
- UI interactions that trigger Compose recomposition but don't touch scene
- N > 100 objects where O(N²) sorting is expensive

---

### 2.2 Compose Modifier.drawWithCache

#### What It Optimizes

Avoids rebuilding Compose `Path` objects and draw commands when the underlying `PreparedScene` hasn't changed. Currently, `ComposeRenderer.renderIsometric()` reconstructs paths every draw call.

**Computation Avoided:**
- N × `Path()` allocations in `RenderCommand.toComposePath()`
- N × `moveTo() + lineTo() × P` path building operations
- Color conversions: `IsoColor.toComposeColor()` (N times)

**What Gets Cached:**
```kotlin
Canvas(modifier.drawWithCache {
    val cachedPaths = preparedScene.commands.map { it.toComposePath() }
    val cachedColors = preparedScene.commands.map { it.color.toComposeColor() }
    onDrawBehind {
        cachedPaths.zip(cachedColors).forEach { (path, color) ->
            drawPath(path, color, style = Fill)
        }
    }
})
```

**Invalidation:** Only when `preparedScene` reference changes (works naturally with `remember(sceneVersion)`)

**Theoretical Improvement:**
- Reduces per-frame allocation from N paths to 0
- Eliminates repetitive path construction math
- **Best case:** 30-50% reduction in draw time for complex scenes

#### Costs & Risks

**Memory Overhead:**
- N cached `Path` objects (internal Compose representation)
- Estimate: N × 128 bytes (path + float arrays)
- Example: 500 items × 128 bytes ≈ 64KB

**Complexity:**
- Minimal — `drawWithCache` is standard Compose API
- **Risk:** If scene updates frequently, cache invalidates often anyway

**Worst-Case Scenarios:**
- Scene changes every frame → no caching benefit, same as baseline
- Very simple paths (3-4 points) → overhead > savings

**Expected Win Scenarios:**
- Static or infrequently updated PreparedScene (pairs well with Technique #1)
- Complex paths with many vertices (P > 10)
- High refresh rates (120Hz displays) where draw is called 2× per prepare

**Interaction with PreparedScene Cache:**
- **Multiplicative benefit:** If PreparedScene cached, drawWithCache almost always hits
- If PreparedScene uncached, drawWithCache provides marginal value

---

### 2.3 Broad-Phase Depth Sorting

#### What It Optimizes

Reduces O(N²) pairwise intersection checks in `sortPaths()` by using spatial partitioning to skip objects that can't possibly overlap.

**Current Algorithm:**
- For every pair (i, j): check if 2D projections intersect
- `hasIntersection()` is expensive: bounding box + edge crossing + point-in-poly
- N=100 → 4,950 checks | N=500 → 124,750 checks | N=1000 → 499,500 checks

**Broad-Phase Approach:**
1. **Grid partitioning:** Divide viewport into cells (e.g., 16×16 grid)
2. **Bucket objects:** Assign each item to grid cells based on bounding box
3. **Cull candidates:** Only check intersection for items in same/adjacent cells
4. **Fine-phase sort:** Run existing topological sort on reduced set

**Theoretical Improvement:**
- Uniform distribution: O(N²) → O(N × k) where k = avg items per cell
- Best case (sparse): 90%+ reduction in intersection checks
- Worst case (dense pile): ~0% improvement (all in one cell)

#### Costs & Risks

**Memory Overhead:**
- Grid structure: 256 cells × ~8 bytes ≈ 2KB
- Item→cell mapping: N × 16 bytes (small)
- **Total:** ~5-10KB regardless of N

**Implementation Complexity:**
- Moderate: need grid allocation, bounding box calculation, cell assignment
- **Risk:** Grid size tuning — too coarse (no benefit) vs too fine (overhead)

**Worst-Case Scenarios:**
- Fully overlapping objects (architectural interior) → no culling benefit
- Very small N (<50) → overhead > O(N²) savings
- Highly non-uniform distribution → some cells overloaded

**Expected Win Scenarios:**
- Large scenes (N > 200) with moderate spatial spread
- Outdoor/terrain scenes where most objects don't overlap
- Architectural elevations with clear spatial separation

**Key Parameter:** Grid resolution (must benchmark 8×8, 16×16, 32×32)

**Dependency:** Independent of caching techniques — purely algorithmic improvement

---

### 2.4 Spatial-Index Hit Testing

#### What It Optimizes

Eliminates linear scan in `findItemAt()` by using spatial index to query only objects near the tap point.

**Current Algorithm:**
- Iterate through all N commands (reversed for front-to-back)
- For each: build convex hull + point-in-poly test
- Average case: N/2 tests before hit | Worst case: N tests (miss)

**Spatial Index Approach:**
1. **R-tree or Grid Index:** Pre-build during `prepare()`
2. **Query:** `index.queryPoint(x, y, radius)` → small candidate set
3. **Hit test:** Only test candidates (typically 1-5 items)

**Theoretical Improvement:**
- O(N) → O(log N) for R-tree | O(1) for grid with good distribution
- **Best case:** 100× faster for N=1000 (test 5 items instead of 500)
- **Worst case:** Dense overlapping → still test most items

#### Costs & Risks

**Memory Overhead:**
- R-tree: ~48 bytes per node, ~N nodes → 50KB for 1000 items
- Grid: Similar to broad-phase (~5-10KB)

**Build Time:**
- Index construction during `prepare()`: O(N log N) for R-tree
- **Critical:** Must be cheaper than O(N) × hit-test-cost to break even
- Grid build: O(N) — simpler and faster

**Invalidation:**
- Rebuild on every `prepare()` (PreparedScene changes)
- If PreparedScene cached → index also cached (big win)

**Worst-Case Scenarios:**
- Infrequent interaction → index build cost wasted
- Dense overlapping (UI packed tight) → query returns most items anyway
- Very small N (<50) → linear scan faster than index overhead

**Expected Win Scenarios:**
- Frequent interaction (drag operations, hover effects)
- Large scenes (N > 200) with spatial spread
- **Pairs exceptionally well with PreparedScene cache** (index built once, used many times)

**Implementation Choice:** Grid likely better than R-tree (simpler, faster build, predictable performance)

---

## 3. Benchmark Scenario Design

### 3.1 Scene Size Variations

**S1: Tiny (N=10)** — Baseline overhead measurement
- 10 simple prisms in loose arrangement
- Purpose: Verify optimizations don't hurt simple cases

**S2: Small (N=100)** — Typical UI complexity
- 100 mixed shapes (prisms, pyramids, stairs)
- Moderate overlap (~30% intersection rate)
- Purpose: Real-world app complexity

**S3: Large (N=500)** — Stress test
- 500 shapes in architectural scene
- High overlap (~60% intersection rate)
- Purpose: Where O(N²) becomes painful

**S4: Extreme (N=1000)** — Scale limit
- 1000 cubes in voxel-like grid
- Variable density (sparse corners, dense center)
- Purpose: Identify breaking points

### 3.2 Scene Type Variations

**Type A: Static Scene**
- Build once, render 1000 frames with no changes
- Only camera/viewport changes (none in our case)
- **Stresses:** PreparedScene cache, drawWithCache
- **Expected:** Massive wins for caching techniques

**Type B: Incremental Delta (1% mutation)**
- Each frame: mutate 1-5 objects (position or color)
- 99% of scene unchanged
- **Stresses:** Cache invalidation granularity
- **Expected:** Coarse invalidation hurts, fine-grained helps

**Type C: Incremental Delta (10% mutation)**
- Each frame: mutate 10-50 objects
- 90% unchanged
- **Expected:** Caching still valuable but less dramatic

**Type D: Full Scene Mutation**
- Every object changes every frame
- **Stresses:** Cache overhead
- **Expected:** Optimizations provide zero or negative value

### 3.3 Interaction Patterns

**I1: No Interaction**
- Just rendering, no hit testing
- **Tests:** PreparedScene cache, drawWithCache, broad-phase sort

**I2: Occasional Taps (1 per second)**
- Hit test every 60 frames
- **Tests:** All techniques, but hit-test overhead minimal

**I3: Continuous Drag (60 taps/second)**
- Hit test every frame
- **Tests:** Spatial index becomes critical

**I4: Hover Simulation (30 taps/second)**
- Hit test every other frame
- **Tests:** Spatial index with moderate pressure

### 3.4 Benchmark Matrix (Sequential Approach)

**Phase 1: Baseline**
- All scenarios × all sizes × Type A/D × I1/I3 = 32 runs
- Establishes performance floor/ceiling

**Phase 2: +PreparedScene Cache**
- Same 32 runs with cache enabled
- Delta vs baseline shows cache value

**Phase 3: +drawWithCache**
- Same 32 runs with both caches
- Delta vs Phase 2 shows drawWithCache value

**Phase 4: +Broad-Phase Sort**
- Same 32 runs with all three
- Delta vs Phase 3 shows sorting value

**Phase 5: +Spatial Index**
- Same 32 runs fully optimized
- Delta vs Phase 4 shows index value

**Total:** 160 benchmark runs (32 scenarios × 5 optimization levels)

---

## 4. Metrics & Measurement Tooling

### 4.1 Primary Metrics

**M1: Frame Time (milliseconds)**
- **Avg, P50, P95, P99** across 1000 frames
- Target: <16ms (60fps) avg, <20ms p95
- **Why P95/P99:** Jank perception is driven by outliers, not averages
- **Measurement:** Custom timing probes around `prepare()` and `Canvas.draw()`

**M2: Prepare Time Breakdown (milliseconds)**
```kotlin
measure("projection") { /* 3D→2D transform */ }
measure("lighting") { /* color calculations */ }
measure("sorting") { /* depth sort */ }
measure("allocation") { /* RenderCommand list build */ }
```
- Shows where time goes in baseline
- Identifies which optimizations matter most

**M3: Hit-Test Latency (milliseconds)**
- Time from `findItemAt()` call to return
- **Avg, P95** across all tap events
- Target: <4ms (feels instant)
- **Measurement:** Probe around `findItemAt()`

**M4: Allocations Per Frame**
- Object count + bytes via Android Studio Profiler
- Target: <100 objects, <10KB per frame at 60fps
- **Why:** High allocation → GC pressure → jank

**M5: GC Frequency**
- Minor/Major GC events per second
- Captured via Profiler during 60-second runs
- Target: <1 GC per second

**M6: Memory Footprint (KB)**
- Heap usage with caches vs baseline
- Measure after scene stabilizes (10 seconds)
- Acceptable: <500KB increase for N=1000

### 4.2 Measurement Tools

**Tool 1: Macrobenchmark (Primary)**
- `androidx.benchmark:benchmark-macro` for frame timing
- Runs on real device, realistic conditions
- Outputs: JSON with frame time distribution, jank count
- **Setup:**
  ```kotlin
  @Test
  fun benchmarkStaticScene100Items() {
      benchmarkRule.measureRepeated(
          packageName = "io.fabianterhorst.isometric.benchmark",
          metrics = listOf(FrameTimingMetric()),
          iterations = 5
      ) {
          // Launch activity, trigger scenario
      }
  }
  ```

**Tool 2: Custom Timing Probes**
- `System.nanoTime()` around critical sections
- Export to CSV for analysis
- More granular than Macrobenchmark

**Tool 3: Android Studio Profiler**
- Memory allocations (manual capture during runs)
- GC events visualization
- CPU flame graphs for hotspot identification
- **Use:** 3 representative runs per phase for deep dive

**Tool 4: Trace Sections**
```kotlin
Trace.beginSection("IsometricEngine.prepare")
// ... work ...
Trace.endSection()
```
- Systrace integration for timeline view
- Helps diagnose interaction between optimizations

---

## 5. Improved Benchmark Harness Design

### 5.1 Architecture Overview

```
BenchmarkActivity (Android Activity)
    ├─> BenchmarkOrchestrator (lifecycle manager)
    │   ├─> warmup phase (500 frames)
    │   ├─> measurement phase (500 frames)
    │   └─> cooldown phase
    ├─> FramePacer (Choreographer-based)
    ├─> MetricsCollector (low-overhead probes)
    └─> ResultsExporter (post-benchmark only)
```

### 5.2 Key Improvements

| Issue | Solution |
|-------|----------|
| JIT warmup | 500 warmup frames (8+ seconds) |
| Frame pacing | Choreographer.postFrameCallback |
| GC interference | Force GC before warmup & measurement |
| Thermal throttling | 2-second cooldown between phases |
| Metrics overhead | Trace sections + pre-allocated arrays |
| Validation | Runtime assertions for flag compliance |
| Macrobenchmark | Proper Activity with Intent-based config |
| Statistical rigor | 5 iterations via Macrobenchmark |

### 5.3 BenchmarkActivity Implementation

```kotlin
class BenchmarkActivity : ComponentActivity() {
    private lateinit var orchestrator: BenchmarkOrchestrator
    private lateinit var config: BenchmarkConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        config = BenchmarkConfig.fromIntent(intent)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Disable system animations
        Settings.Global.putFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f
        )

        orchestrator = BenchmarkOrchestrator(
            activity = this,
            config = config,
            onComplete = { results ->
                ResultsExporter.export(results, config.outputFile)
                finish()
            }
        )

        setContent {
            BenchmarkScreen(orchestrator)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            delay(1000) // Let UI settle
            orchestrator.start()
        }
    }
}
```

### 5.4 BenchmarkOrchestrator Lifecycle

```kotlin
class BenchmarkOrchestrator(
    private val activity: Activity,
    private val config: BenchmarkConfig,
    private val onComplete: (BenchmarkResults) -> Unit
) {
    enum class Phase {
        IDLE,
        GC_AND_WARMUP,
        COOLDOWN_1,
        MEASUREMENT,
        COMPLETE
    }

    suspend fun start() {
        // Phase 1: GC and Warmup (500 frames)
        forceGarbageCollection()
        delay(2000)
        runFrames(count = 500, measure = false)

        // Phase 2: Cooldown
        delay(2000)
        forceGarbageCollection()
        delay(1000)

        // Phase 3: Measurement (500 frames)
        metrics.reset()
        runFrames(count = 500, measure = true)

        // Phase 4: Complete
        val results = metrics.computeResults(config)
        onComplete(results)
    }
}
```

### 5.5 FramePacer (Choreographer-Based)

```kotlin
class FramePacer {
    private val choreographer = Choreographer.getInstance()

    suspend fun awaitNextFrame(onFrame: (frameTimeNanos: Long) -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            choreographer.postFrameCallback { frameTimeNanos ->
                onFrame(frameTimeNanos)
                continuation.resume(Unit)
            }
        }
    }
}
```

### 5.6 MetricsCollector (Zero-Allocation)

```kotlin
class MetricsCollector {
    private val frameTimes = LongArray(500) // Pre-allocated
    private var frameCount = 0

    fun onFrameStart(frameTimeNanos: Long) {
        currentFrameStart = frameTimeNanos
    }

    fun onFrameEnd(frameTimeNanos: Long) {
        val duration = frameTimeNanos - currentFrameStart
        if (frameCount < frameTimes.size) {
            frameTimes[frameCount] = duration
            frameCount++
        }
    }

    fun computeResults(config: BenchmarkConfig): BenchmarkResults {
        val frameTimesMs = frameTimes.take(frameCount)
            .map { it / 1_000_000.0 }
            .sorted()

        return BenchmarkResults(
            avgFrameTime = frameTimesMs.average(),
            p50FrameTime = frameTimesMs[frameCount / 2],
            p95FrameTime = frameTimesMs[(frameCount * 0.95).toInt()],
            p99FrameTime = frameTimesMs[(frameCount * 0.99).toInt()]
        )
    }
}
```

### 5.7 Deterministic Scene Generation

```kotlin
object SceneGenerator {
    fun generate(
        size: Int,
        seed: Long = 12345,
        density: Float = 0.5
    ): List<SceneItem> {
        val random = Random(seed)
        return (0 until size).map { i ->
            val x = random.nextDouble(-10.0, 10.0)
            val y = random.nextDouble(-10.0, 10.0)
            val z = random.nextDouble(0.0, 5.0)
            val shape = when (i % 3) {
                0 -> Prism(Point(x, y, z))
                1 -> Pyramid(Point(x, y, z))
                else -> Cylinder(Point(x, y, z))
            }
            SceneItem(shape, color)
        }
    }
}
```

### 5.8 Optimization Flags

```kotlin
data class OptimizationFlags(
    val enablePreparedSceneCache: Boolean = false,
    val enableDrawWithCache: Boolean = false,
    val enableBroadPhaseSort: Boolean = false,
    val enableSpatialIndex: Boolean = false
) {
    companion object {
        val BASELINE = OptimizationFlags()
        val PHASE_1 = BASELINE.copy(enablePreparedSceneCache = true)
        val PHASE_2 = PHASE_1.copy(enableDrawWithCache = true)
        val PHASE_3 = PHASE_2.copy(enableBroadPhaseSort = true)
        val PHASE_4 = PHASE_3.copy(enableSpatialIndex = true)
    }
}
```

### 5.9 Validation in BenchmarkScreen

```kotlin
@Composable
fun BenchmarkScreen(orchestrator: BenchmarkOrchestrator) {
    val sceneState = rememberIsometricSceneState(
        enableCache = config.flags.enablePreparedSceneCache
    )

    // VALIDATION: Ensure flags are respected
    LaunchedEffect(Unit) {
        require(sceneState.isCacheEnabled() == config.flags.enablePreparedSceneCache) {
            "PreparedScene cache flag mismatch!"
        }
    }

    IsometricCanvas(
        state = sceneState,
        enableDrawWithCache = config.flags.enableDrawWithCache,
        enableBroadPhaseSort = config.flags.enableBroadPhaseSort,
        enableSpatialIndex = config.flags.enableSpatialIndex
    ) {
        // Render scene
    }
}
```

---

## 6. Expected Outcomes & Predictions

### 6.1 Performance Matrix

| Scenario | Baseline | +PreparedScene | +drawWithCache | +Broad-Phase | +Spatial Index |
|----------|----------|----------------|----------------|--------------|----------------|
| **Static N=100, No Interaction** |
| Avg Frame | 8ms | 2ms (75%↓) | 1.5ms (81%↓) | 1.5ms | 1.5ms |
| Allocations | 150 obj | 5 obj (97%↓) | 0 obj (100%↓) | 0 obj | 0 obj |
| **Static N=500, No Interaction** |
| Avg Frame | 45ms | 8ms (82%↓) | 6ms (87%↓) | 6ms | 6ms |
| P95 Frame | 65ms | 12ms | 8ms | 8ms | 8ms |
| **Static N=1000, No Interaction** |
| Avg Frame | 180ms | 25ms (86%↓) | 18ms (90%↓) | 18ms | 18ms |
| **Incremental 1% N=500, No Interaction** |
| Avg Frame | 45ms | 10ms (78%↓) | 8ms (82%↓) | 8ms | 8ms |
| Cache Hit | 0% | 99% | 99% | 99% | 99% |
| **Incremental 10% N=500, No Interaction** |
| Avg Frame | 45ms | 15ms (67%↓) | 12ms (73%↓) | 12ms | 12ms |
| **Full Mutation N=500, No Interaction** |
| Avg Frame | 45ms | 45ms (0%) | 38ms (16%↓) | 25ms (44%↓) | 25ms |
| Sort Time | 30ms | 30ms | 30ms | 12ms (60%↓) | 12ms |
| **Static N=500, Continuous Drag** |
| Avg Frame | 48ms | 10ms (79%↓) | 8ms (83%↓) | 8ms | 8ms |
| Hit Test | 3.5ms | 3.5ms | 3.5ms | 3.5ms | 0.5ms (86%↓) |
| **Static N=1000, Continuous Drag** |
| Avg Frame | 185ms | 28ms (85%↓) | 20ms (89%↓) | 20ms | 20ms |
| Hit Test | 8ms | 8ms | 8ms | 8ms | 0.8ms (90%↓) |
| **Full Mutation N=1000, Continuous Drag** |
| Avg Frame | 188ms | 188ms (0%) | 155ms (18%↓) | 85ms (55%↓) | 85ms |
| Hit Test | 8ms | 8ms | 8ms | 8ms | 0.8ms (90%↓) |

### 6.2 Optimization Value by Scenario

**PreparedScene Cache:**
- ✅ **Huge Win:** Static or rarely-changing scenes (75-90% frame time reduction)
- ✅ **Good Win:** Incremental updates <10% (60-80% reduction)
- ❌ **No Value:** Full scene mutations every frame (0% improvement)
- 💡 **Best for:** UI apps, static diagrams, infrequent updates

**drawWithCache:**
- ✅ **Moderate Win:** Stacks with PreparedScene cache (10-20% additional reduction)
- ✅ **Marginal Win:** Without PreparedScene cache (15-20% reduction alone)
- ❌ **Limited Value:** If scene updates frequently (cache invalidates)
- 💡 **Best for:** Complex paths (many vertices), pairs with PreparedScene cache

**Broad-Phase Depth Sort:**
- ✅ **Massive Win:** Large scenes with full mutations (40-60% sort time reduction)
- ✅ **Good Win:** Sparse/outdoor scenes (70-90% reduction)
- ❌ **No Value:** Static scenes (sorting cached, so irrelevant)
- ⚠️ **Limited Value:** Dense overlapping scenes (10-20% reduction)
- 💡 **Best for:** Dynamic scenes, N > 200, moderate spatial spread

**Spatial Index Hit Testing:**
- ✅ **Critical Win:** Frequent interaction + large N (85-95% hit-test latency reduction)
- ✅ **Moderate Win:** Occasional interaction (still helps, less critical)
- ❌ **No Value:** No interaction (index build cost wasted)
- 💡 **Best for:** Interactive apps, drag operations, N > 200

### 6.3 Multiplicative vs Additive Effects

**Multiplicative (Compound Benefits):**
- PreparedScene Cache × drawWithCache = **~90% frame time reduction** (static scenes)
  - Cache avoids prepare → drawWithCache avoids path rebuild → minimal work

**Additive (Independent Benefits):**
- PreparedScene Cache helps rendering, Spatial Index helps hit-testing
  - Both can be valuable in same scenario (e.g., static scene + interaction)

**Anti-Patterns (Overhead > Benefit):**
- PreparedScene Cache on full-mutation scenes → allocation overhead, zero hits
- Spatial Index with no interaction → build cost wasted every frame
- Broad-Phase on tiny scenes (N < 50) → grid overhead > O(N²) savings

---

## 7. Decision Criteria for Adoption

### 7.1 Hard Requirements

**Threshold 1: Performance Improvement**
```
For optimization O in scenario S:
- IF improvement < 15% → REJECT (not worth complexity)
- IF 15% ≤ improvement < 30% → CONDITIONAL (adopt only if zero risk)
- IF improvement ≥ 30% → ADOPT (clear win)
```

**Threshold 2: Frame Budget Compliance**
```
At N=500 (typical use case):
- Target: Avg frame time < 16ms (60fps)
- Maximum: P95 frame time < 20ms (tolerable jank)

IF baseline > 16ms AND optimized < 16ms → CRITICAL (enables 60fps)
```

**Threshold 3: Hit-Test Latency**
```
For interactive scenarios:
- Target: < 4ms (feels instant)
- Maximum: < 8ms (acceptable)

IF optimized hit-test < 4ms AND baseline > 8ms → CRITICAL
```

**Threshold 4: Memory Overhead**
```
For N=1000 (stress test):
- Acceptable: < 500KB heap increase
- Concerning: 500KB - 1MB
- Unacceptable: > 1MB
```

**Threshold 5: Regression Prevention**
```
For scenarios where optimization is not expected to help:
- Maximum regression: < 5% (noise tolerance)
- IF regression > 5% → REJECT or make opt-in
```

### 7.2 Per-Optimization Decision Matrix

**PreparedScene Cache:**

| Criterion | Threshold | Expected | Decision |
|-----------|-----------|----------|----------|
| Static N=500 improvement | ≥30% | 82% ✅ | **ADOPT** |
| Incremental 10% improvement | ≥30% | 67% ✅ | **ADOPT** |
| Full mutation regression | <5% | ~0% ✅ | **SAFE** |
| Memory overhead N=1000 | <500KB | ~64KB ✅ | **ACCEPTABLE** |
| **Final Decision** | | | **ADOPT - Enable by default** |

**drawWithCache:**

| Criterion | Threshold | Expected | Decision |
|-----------|-----------|----------|----------|
| Static N=500 (with PreparedScene) | ≥15% | ~25% ✅ | **ADOPT** |
| Memory overhead | <500KB | ~64KB ✅ | **ACCEPTABLE** |
| **Final Decision** | | | **ADOPT - Enable with PreparedScene** |

**Broad-Phase Depth Sort:**

| Criterion | Threshold | Expected | Decision |
|-----------|-----------|----------|----------|
| Full mutation N=500 improvement | ≥30% | 44% ✅ | **ADOPT** |
| Full mutation N=1000 improvement | ≥30% | 55% ✅ | **ADOPT** |
| Static scenes regression | <5% | 0% ✅ | **SAFE** |
| Small scenes N<50 regression | <5% | ??? 🔍 | **MUST TEST** |
| **Final Decision** | | | **ADOPT - Enable for N > 100** |

**Spatial Index Hit Testing:**

| Criterion | Threshold | Expected | Decision |
|-----------|-----------|----------|----------|
| Hit-test latency N=500 (continuous) | <4ms | 0.5ms ✅ | **ADOPT** |
| Hit-test latency N=1000 (continuous) | <4ms | 0.8ms ✅ | **ADOPT** |
| No-interaction build overhead | <5% regression | ??? 🔍 | **MUST TEST** |
| Memory overhead N=1000 | <500KB | ~50KB ✅ | **ACCEPTABLE** |
| **Final Decision** | | | **ADOPT - Opt-in for interactive apps** |

### 7.3 Adoption Strategy

**Phase A: Core Library Defaults (Conservative)**
```kotlin
// Always enabled (safe, high-value)
- PreparedScene caching: YES (default on)
- drawWithCache: YES (pairs with PreparedScene)

// Conditionally enabled
- Broad-phase sort: IF sceneSize > 100 (auto-detect)
- Spatial index: NO (opt-in via flag, not all apps need interaction)
```

**Phase B: Opt-In Flags (Advanced Users)**
```kotlin
RenderOptions(
    enableBroadPhaseSort: Boolean = sceneSize > 100,
    enableSpatialIndex: Boolean = false,
    broadPhaseGridSize: Int = 16
)
```

### 7.4 Go/No-Go Decision Tree

```
For each optimization O:

1. Does O improve ≥1 scenario by ≥30%?
   NO → REJECT
   YES → Continue

2. Does O regress any scenario by >5%?
   YES → REJECT or make conditional
   NO → Continue

3. Does O add <500KB memory for N=1000?
   NO → REJECT
   YES → Continue

4. Is O simple to enable/disable?
   NO → Defer (needs API design)
   YES → ADOPT

5. Adoption mode:
   - IF safe for all cases → DEFAULT ON
   - IF scenario-dependent → AUTO-DETECT or OPT-IN
   - IF requires tuning → OPT-IN with examples
```

---

## 8. Open Risks & Unknowns

### 8.1 Measurement Uncertainties

**Risk 1: Device Variability**
- **Issue:** Results may vary significantly across device tiers
- **Mitigation:** Test on 3 devices minimum (low/mid/high tier)
- **Unknown:** Whether thresholds need device-specific tuning

**Risk 2: Android Version Differences**
- **Issue:** ART JIT improvements in Android 14+ might change cost/benefit
- **Mitigation:** Test on Android 10, 12, 14 minimum
- **Unknown:** Whether to maintain version-specific defaults

**Risk 3: Compose Compiler Evolution**
- **Issue:** Future Compose versions might optimize drawWithCache differently
- **Mitigation:** Document Compose version, re-benchmark on major updates
- **Unknown:** Stability of Compose draw performance

### 8.2 Implementation Uncertainties

**Risk 4: Cache Invalidation Complexity**
- **Issue:** Fine-grained invalidation (track which objects changed) is complex
- **Current Plan:** Start with coarse (version counter), measure if fine-grained needed
- **Unknown:** Whether incremental updates justify fine-grained tracking

**Risk 5: Broad-Phase Grid Tuning**
- **Issue:** Optimal grid size (8×8, 16×16, 32×32) likely scene-dependent
- **Mitigation:** Benchmark 3 grid sizes, pick best average
- **Unknown:** Whether auto-tuning grid size is worth complexity

**Risk 6: Spatial Index Data Structure Choice**
- **Current Plan:** Start with grid (simplest), benchmark R-tree if needed
- **Unknown:** Whether grid is "good enough" for 95% of cases

**Risk 7: Memory Profiling Accuracy**
- **Issue:** Android Profiler heap measurements can be noisy
- **Mitigation:** Multiple runs + heap dumps for validation
- **Unknown:** Exact memory overhead until implemented

### 8.3 Scenario Coverage Gaps

**Risk 8: Real-World Scene Characteristics**
- **Issue:** Benchmark scenes might not match actual app usage
- **Mitigation:** Partner with 2-3 library users, profile their actual scenes
- **Unknown:** Distribution of scene sizes/complexities in production

**Risk 9: Interaction Pattern Realism**
- **Issue:** Simulated taps might not match real user behavior
- **Mitigation:** Capture real interaction traces from sample apps
- **Unknown:** Frequency and patterns of real hit-testing in apps

**Risk 10: Thermal Throttling Variance**
- **Issue:** 2-second cooldown might be insufficient on some devices
- **Mitigation:** Monitor CPU frequency during runs, discard if throttled
- **Unknown:** How much cooldown is "enough" across devices

### 8.4 Questions Requiring Empirical Answers

**Q1:** Does PreparedScene cache overhead matter on full-mutation scenes?
- **Hypothesis:** <5% regression from version tracking
- **Test:** Benchmark full-mutation with/without cache enabled

**Q2:** Is drawWithCache valuable without PreparedScene cache?
- **Hypothesis:** 15-20% improvement standalone
- **Test:** Phase comparison (baseline → +drawWithCache only)

**Q3:** What's the smallest N where broad-phase pays off?
- **Hypothesis:** N > 100
- **Test:** Sweep N = 50, 75, 100, 150, find crossover point

**Q4:** Does spatial index build cost hurt non-interactive scenarios?
- **Hypothesis:** <5% regression when index unused
- **Test:** Static scenes with spatial index on vs off

**Q5:** Do optimizations interact (multiplicative vs additive)?
- **Hypothesis:** PreparedScene × drawWithCache = multiplicative
- **Test:** Compare (baseline → A → A+B) vs (baseline → B → A+B)

**Q6:** Is grid size tuning critical?
- **Hypothesis:** 16×16 good for most scenes
- **Test:** Benchmark 8×8, 16×16, 32×32 across diverse scenes

**Q7:** How does scene density affect broad-phase effectiveness?
- **Hypothesis:** Sparse = 90% win, Dense = 20% win
- **Test:** Generate scenes with varying overlap rates (10%, 30%, 60%, 90%)

### 8.5 Fallback Plans

**If PreparedScene cache causes bugs:**
- → Make opt-in, default off, require user testing before adoption

**If drawWithCache invalidates too often:**
- → Investigate Compose layer caching, or accept as "nice but marginal"

**If broad-phase doesn't scale to N=1000:**
- → Fall back to simpler bounding-box early rejection
- → Consider depth-first sorting heuristics instead

**If spatial index build is too expensive:**
- → Build asynchronously in background thread
- → Only enable for scenes known to have frequent interaction

**If benchmarks show all optimizations marginal (<15%):**
- → Keep baseline, focus efforts elsewhere (e.g., GPU rendering, Skia optimizations)

---

## 9. Next Steps

### 9.1 Implementation Phase

1. **Create benchmark module** (`isometric-benchmark`)
   - Set up Macrobenchmark infrastructure
   - Implement BenchmarkActivity with orchestrator
   - Add scene generation and interaction simulation

2. **Instrument existing library** (without optimizations)
   - Add Trace sections to IsometricEngine
   - Add OptimizationFlags parameter to API
   - Ensure flags are no-ops initially (baseline measurement)

3. **Run baseline benchmarks**
   - Execute Phase 1 (32 scenarios × 5 iterations = 160 runs)
   - Capture results, validate harness works correctly
   - Identify any issues with measurement methodology

4. **Implement optimizations sequentially**
   - Phase 2: PreparedScene cache
   - Phase 3: drawWithCache
   - Phase 4: Broad-phase sort
   - Phase 5: Spatial index
   - Benchmark after each phase

5. **Analysis and decision**
   - Compare results against decision criteria
   - Identify winning optimizations
   - Determine default configuration

### 9.2 Documentation

- **Benchmark results report** (auto-generated from CSV)
- **Performance guide** for library users
- **Architecture decision record** (ADR) for adopted optimizations

### 9.3 Timeline Estimate

- Harness implementation: 3-5 days
- Baseline benchmarking: 1 day
- Optimization implementation: 5-7 days (sequential)
- Analysis and decision: 1-2 days
- **Total:** ~2 weeks for complete investigation

---

## Appendix A: Benchmark Configuration Format

```kotlin
data class BenchmarkConfig(
    val name: String,
    val sceneSize: Int,              // 10, 100, 500, 1000
    val scenario: Scenario,          // STATIC, INCREMENTAL_1, INCREMENTAL_10, FULL_MUTATION
    val interactionPattern: InteractionPattern, // NONE, OCCASIONAL, CONTINUOUS, HOVER
    val flags: OptimizationFlags,
    val outputFile: String
)

enum class Scenario {
    STATIC,
    INCREMENTAL_1,   // 1% mutation per frame
    INCREMENTAL_10,  // 10% mutation per frame
    FULL_MUTATION
}

enum class InteractionPattern {
    NONE,
    OCCASIONAL,   // 1 tap per second
    CONTINUOUS,   // 60 taps per second
    HOVER         // 30 taps per second
}
```

## Appendix B: Results Export Format

**CSV Schema:**
```
config_name,scene_size,scenario,interaction,optimization_phase,
avg_frame_ms,p50_frame_ms,p95_frame_ms,p99_frame_ms,
min_frame_ms,max_frame_ms,total_frames,
avg_hit_test_ms,p95_hit_test_ms,hit_test_count
```

**JSON Schema:**
```json
{
  "config": { /* BenchmarkConfig */ },
  "device": {
    "model": "Pixel 7",
    "androidVersion": "14",
    "cpuModel": "Tensor G2"
  },
  "results": {
    "frameTimeMs": {
      "avg": 12.5,
      "p50": 11.2,
      "p95": 18.3,
      "p99": 22.1
    },
    "hitTestLatencyMs": {
      "avg": 2.1,
      "p95": 3.4
    },
    "allocations": {
      "objectsPerFrame": 45,
      "bytesPerFrame": 8192
    }
  }
}
```

---

**End of Performance Investigation Plan**
