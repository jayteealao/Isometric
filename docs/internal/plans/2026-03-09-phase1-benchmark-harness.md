# Phase 1: Build Benchmark Harness — Detailed Plan

**Date:** 2026-03-09
**Status:** Plan (Pre-Implementation)
**Parent plan:** `docs/plans/2026-03-08-performance-investigation-v2.md` (Phase 1, §1.1–§1.6)
**Prerequisite:** Phase 0 complete — master at `8fdc913`, all Runtime API code merged.
**Branch:** `perf/benchmark-harness` (created from master)

---

## Goal

Build a self-contained benchmark harness that can measure the Isometric rendering pipeline
in isolation, with per-optimization flag control, validated caching, and reproducible results.
The harness must address every failure mode documented in the v2 plan's "What Failed" section.

---

## Table of Contents

1. [Module Setup](#1-module-setup)
2. [Benchmark Flags](#2-benchmark-flags)
3. [Scene Generation](#3-scene-generation)
4. [Mutation Simulation](#4-mutation-simulation)
5. [Interaction Simulation](#5-interaction-simulation)
6. [Metrics Collection](#6-metrics-collection)
7. [Benchmark Orchestration](#7-benchmark-orchestration)
8. [Results Export](#8-results-export)
9. [Validation Checks](#9-validation-checks)
10. [Renderer Instrumentation](#10-renderer-instrumentation)
11. [BenchmarkActivity UI](#11-benchmarkactivity-ui)
12. [Shell Script Runner](#12-shell-script-runner)
13. [File Inventory](#13-file-inventory)
14. [Implementation Order](#14-implementation-order)
15. [Acceptance Criteria](#15-acceptance-criteria)
16. [Open Questions](#16-open-questions)

---

## 1. Module Setup

### 1.1 Gradle Module

Create `isometric-benchmark/` as an Android application module (not a library — it needs its
own `Activity` and launcher intent for adb invocation).

**`isometric-benchmark/build.gradle.kts`:**

```
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.fabianterhorst.isometric.benchmark"
    compileSdk = 34                           // Match isometric-compose (34)
    defaultConfig {
        applicationId = "io.fabianterhorst.isometric.benchmark"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"  // Match isometric-compose & app modules
    }
    buildFeatures { compose = true }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(project(":isometric-core"))
    implementation(project(":isometric-compose"))
    // Compose — use same version as isometric-compose module (1.5.0)
    val composeVersion = "1.5.0"
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.foundation:foundation:$composeVersion")
    implementation("androidx.compose.material:material:$composeVersion")
    implementation("androidx.compose.runtime:runtime:$composeVersion")
    implementation("androidx.activity:activity-compose:1.6.1")
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
}
```

> **Version alignment note:** All versions above are copied from `isometric-compose/build.gradle.kts`
> and `app/build.gradle.kts`. If those modules update versions, this module must follow.
> A future improvement would be to extract shared versions into `gradle.properties` or a
> version catalog (`libs.versions.toml`).

**`settings.gradle` change:** Add `include ':isometric-benchmark'` to line after existing
includes (currently line 21 of `settings.gradle`).

### 1.2 Package Structure

```
isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/
├── BenchmarkActivity.kt          — Entry point Activity
├── BenchmarkScreen.kt            — Compose UI for rendering the scene under test
├── BenchmarkOrchestrator.kt      — Lifecycle: warmup → cooldown → measurement → export
├── BenchmarkFlags.kt             — Optimization flag data class
├── Scenario.kt                   — Scene size / mutation rate / interaction pattern config
├── SceneGenerator.kt             — Deterministic scene generation
├── MutationSimulator.kt          — Per-frame mutations at configured rate
├── InteractionSimulator.kt       — Pre-generated tap points
├── MetricsCollector.kt           — Zero-allocation frame time recording
├── ResultsExporter.kt            — CSV + JSON output
└── validation/
    └── HarnessValidator.kt       — Post-run validation checks
```

### 1.3 AndroidManifest

```xml
<activity
    android:name=".BenchmarkActivity"
    android:exported="true"
    android:theme="@android:style/Theme.NoTitleBar.Fullscreen">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

The Activity must be launchable via adb:
```
adb shell am start -n io.fabianterhorst.isometric.benchmark/.BenchmarkActivity \
    --es config "..." 
```

---

## 2. Benchmark Flags

**File:** `BenchmarkFlags.kt`

Cross-reference: v2 plan §1.3 defines 5 flags. This section maps each flag to the actual
code locations where it takes effect.

```kotlin
data class BenchmarkFlags(
    val enablePreparedSceneCache: Boolean = false,
    val enablePathCaching: Boolean = false,
    val enableBroadPhaseSort: Boolean = false,
    val enableSpatialIndex: Boolean = false,
    val enableNativeCanvas: Boolean = false
)
```

### Flag-to-Code Mapping

| Flag | Controls | Code Location | How It Works |
|------|----------|---------------|--------------|
| `enablePreparedSceneCache` | Dirty-tracking skip of `engine.prepare()` | `IsometricRenderer.needsUpdate()` (`:isometric-compose`, line 267) | When `false`: force `rebuildCache()` every frame. When `true`: skip if `!rootNode.isDirty && cacheValid && same dimensions && same prepareInputs`. |
| `enablePathCaching` | Compose `Path` object reuse | `IsometricRenderer` constructor param (line 40), affects `rebuildCache()` line 331 and `render()` fast path line 131 | When `false`: always goes through `renderPreparedScene()` fallback. When `true`: pre-builds `CachedPath` list, reuses across frames. |
| `enableBroadPhaseSort` | Spatial grid pre-filter in `sortPaths()` | **Does not exist yet.** Must be added to `IsometricEngine.sortPaths()` (`:isometric-core`, line 278) and controlled via a new field on `RenderOptions`. | This is the only new implementation — Phase 3 Branch 3 in the v2 plan. The benchmark harness should define the flag but the harness itself does NOT implement broad-phase sort. |
| `enableSpatialIndex` | Grid-based hit testing | `IsometricRenderer` constructor param (line 41), affects `rebuildCache()` line 336 and `hitTest()` fast path line 215 | When `false`: linear scan via `engine.findItemAt()`. When `true`: builds `SpatialGrid(cellSize=100.0)`, queries O(k) candidates. |
| `enableNativeCanvas` | `android.graphics.Canvas` direct rendering | `IsometricScene` param `useNativeCanvas` (line 63), controls branch at line 231 | When `false`: Compose `DrawScope.render()`. When `true`: `DrawScope.renderNative()` using `canvas.nativeCanvas.drawPath()`. |

### How Flags Are Passed Through

The benchmark harness creates the `IsometricScene` composable with flags mapped as:

```
IsometricScene(
    enablePathCaching = flags.enablePathCaching,
    enableSpatialIndex = flags.enableSpatialIndex,
    useNativeCanvas = flags.enableNativeCanvas,
    renderOptions = RenderOptions(
        enableDepthSorting = true,   // always on (this is what we're measuring)
        enableBackfaceCulling = true,
        enableBoundsChecking = true
    ),
    ...
)
```

**`enablePreparedSceneCache` requires renderer modification.** Currently `IsometricRenderer`
always checks `needsUpdate()` via `ensurePreparedScene()` (line 73) and skips rebuild if
nothing changed. To benchmark the "no cache" baseline, the harness needs a way to force
rebuild every frame.

**Approach:** Add a `var forceRebuild: Boolean = false` **property** on `IsometricRenderer`
(not a method parameter). When `true`, `ensurePreparedScene()` calls `invalidate()` before
the `needsUpdate()` check, forcing a full rebuild every frame.

This avoids changing method signatures on `render()`/`renderNative()`, which would require
plumbing a new parameter through `IsometricScene.kt` lines 230–246. In production,
the property defaults to `false` — zero behavioral change.

**Call chain:** `BenchmarkScreen` creates `IsometricScene` (which creates `IsometricRenderer`).
The benchmark accesses the renderer via `remember { }` in `BenchmarkScreen` and sets
`forceRebuild` before the first frame. Since `IsometricScene` constructs the renderer
internally (line 73–78), the benchmark must either:
1. Pass `forceRebuild` as a new `IsometricScene` parameter that flows to the renderer, or
2. Construct the renderer externally and pass it to a lower-level API.

**Decision:** Option 1 — add `forceRebuild: Boolean = false` to `IsometricScene` params.
This is a single param addition (~3 lines) and keeps the benchmark using the production
`IsometricScene` composable.

### `enableBroadPhaseSort` — Harness-Only Flag

This flag is defined in `BenchmarkFlags` but **no implementation exists in the engine yet**.
The harness must:
1. Accept the flag in config
2. Log a warning if `enableBroadPhaseSort = true` and no engine support is detected
3. Proceed with the benchmark (the flag will have no effect until Branch 3 implements it)
4. The flag's existence allows the same harness to be used unchanged on Branch 3

---

## 3. Scene Generation

**File:** `SceneGenerator.kt`

Cross-reference: v2 plan §1.5 specifies deterministic generation with seed 12345, Prism/Pyramid
60/40 split, grid pattern with jitter.

### Design

```kotlin
class SceneGenerator(private val seed: Long = 12345L) {

    fun generate(sceneSize: Int): List<GeneratedItem>

    data class GeneratedItem(
        val shape: Shape,
        val color: IsoColor,
        val position: Point,
        val id: String
    )
}
```

### Generation Algorithm

1. **Random source:** `java.util.Random(seed)` — deterministic, reproducible.

2. **Grid layout with jitter:**
   - Compute `gridDim = ceil(sqrt(sceneSize))` — e.g., size 100 → 10x10 grid.
   - Cell spacing: 1.2 units (same as `OptimizedPerformanceSample.kt` line 149:
     `(x - half) * 1.2`). Center the grid around origin.
   - Per-cell jitter: ±0.2 units in x and y from `random.nextDouble()`.

3. **Shape selection:** 60% Prism, 40% Pyramid (v2 plan §1.5).
   - `random.nextDouble() < 0.6` → `Prism(origin, dx=1.0, dy=1.0, dz=height)`
   - Otherwise → `Pyramid(origin, dx=1.0, dy=1.0, dz=height)`
   - Height: `0.5 + random.nextDouble() * 1.5` (range 0.5–2.0).

4. **Color:** Deterministic from seed. HSV with hue = `random.nextDouble() * 360`,
   saturation = 0.6–0.9, value = 0.7–1.0. Convert to `IsoColor` RGB.

5. **ID assignment:** `"gen_${index}"` — stable across runs with same seed.

### Scene Sizes

Per v2 plan §1.2: **10, 50, 100, 200**.

Rationale (from v2 plan): Previous tests used 500 and 1000 which produced 2–10 second prepare
times due to O(N^2) sorting, dominating all results. Capping at 200 still shows scaling trends
without making other optimizations irrelevant.

### Validation

- Same seed + same size must produce identical scenes across runs. The harness validator
  checks this by generating twice with the same seed and asserting element-wise equality:
  each `GeneratedItem` pair must match on `id`, `position` (x, y, z), `shape` type + dimensions,
  and `color` (r, g, b).
- Do **not** rely on default object equality for `Shape` instances. `Shape` is not a data class,
  so equality must be defined explicitly for validation. Store comparable shape metadata in
  `GeneratedItem` (e.g. `shapeType`, `origin`, `dx`, `dy`, `dz`) or implement a dedicated
  `sameGeneratedShape(a, b)` helper that compares constructor inputs rather than object identity.
- Log shape type distribution (expect ~60/40 ± statistical variance for small N).

---

## 4. Mutation Simulation

**File:** `MutationSimulator.kt`

Cross-reference: v2 plan §1.2 specifies mutation rates of 10% and 50%.

### Design

```kotlin
class MutationSimulator(private val seed: Long = 67890L) {

    fun mutate(
        items: List<GeneratedItem>,
        mutationRate: Double,    // 0.10 or 0.50
        frameIndex: Int          // advances the random sequence per frame
    ): List<MutationResult>

    data class MutationResult(
        val itemIndex: Int,
        val newHeight: Double,     // the mutation: change the shape's height
        val newColor: IsoColor     // optional: color shift
    )
}
```

### Mutation Algorithm

1. **Per-frame random source:** `Random(seed + frameIndex)` — deterministic per frame,
   different each frame, reproducible.

2. **Selection:** For each item, `random.nextDouble() < mutationRate` → mutate.
   At 10% rate with 100 items, expect ~10 mutations per frame.

3. **What changes:**
   - **Height:** New height = `0.5 + random.nextDouble() * 1.5` (same range as generation).
     This forces a new `Prism` or `Pyramid` to be constructed, which triggers `markDirty()`
     through the Compose node update path.
   - **Color:** 30% chance of color shift (subtle — rotate hue by ±30°). This tests that
     color changes also trigger dirty tracking.

4. **Application:** Mutations are applied by updating the **state list** that drives
   recomposition, not by directly mutating `ShapeNode` properties. The `BenchmarkScreen`
   holds a `mutableStateListOf<GeneratedItem>()`. When a mutation occurs, the harness
   replaces the item: `items[i] = items[i].copy(shape = newShape, color = newColor)`.
   This triggers Compose recomposition of the corresponding `IsometricScope.Shape()`
   call, which in turn hits the `set(shape) { this.shape = it; markDirty() }` updater
   in `IsometricComposables.kt` (line 46). This is the same path production code uses —
   state change → recomposition → node property update → `markDirty()` → cache invalidation.

### Mutation Validation (v2 plan §1.6, check #5)

After each frame, count actual mutations applied. Log:
```
Frame 42: mutationRate=0.10, expected=~10, actual=12, items=100
```
Over 500 frames, the average should converge to the configured rate within ±2%.

---

## 5. Interaction Simulation

**File:** `InteractionSimulator.kt`

Cross-reference: v2 plan §1.2 specifies three interaction patterns.

### Design

```kotlin
class InteractionSimulator(private val seed: Long = 11111L) {

    fun generateTapPoints(
        items: List<GeneratedItem>,
        canvasWidth: Int,
        canvasHeight: Int,
        count: Int
    ): List<TapPoint>

    data class TapPoint(
        val x: Double,
        val y: Double,
        val expectedHit: Boolean      // true = projected from a known item (expect non-null)
    )
}
```

**ID mapping note:** `IsometricNode.nodeId` is `"node_${System.identityHashCode(this)}"` —
unstable across runs and unknowable at tap-generation time. The benchmark **cannot** predict
which specific node a hit test will return. Instead, validation uses a weaker but sufficient
check:

- `expectedHit = true`: the tap was projected from a known item's center → expect
  `hitTest() != null`. A `null` return means projection math is wrong or hit testing is broken.
- `expectedHit = false`: the tap is at a known empty coordinate (e.g., corner of canvas) →
  expect `hitTest() == null`. A non-null return means false positives.

This is appropriate because:
1. Hit-test *correctness* (returning the right node) is already covered by unit tests
   (`IsometricRendererTest` — overlapping shape test, fast-vs-slow agreement).
2. The benchmark measures hit-test *performance* — the validation only needs to confirm
   the code path executes (non-null for known hits).
3. Checking the exact node would require building a runtime `Map<ShapeProperties, GeneratedItemId>`
   lookup inside the composition, adding complexity that doesn't improve benchmark reliability.

### Interaction Patterns

| Pattern | Description | Implementation |
|---------|-------------|----------------|
| `NONE` | No interaction | No taps. Hit-test code is never called. |
| `OCCASIONAL` | 1 tap per second | At 60fps, tap every 60th frame. Pre-generate 10 tap points, cycle through them. |
| `CONTINUOUS` | Every frame | Tap on every measurement frame (500 taps total). Pre-generate 500 tap points. |

### Tap Point Generation

1. For each tap point, pick a random item from the scene.
2. Project that item's center point to 2D screen coordinates using `IsometricEngine`'s
   projection math. **Prerequisite:** change `translatePoint` from `private` to `internal`
   in `IsometricEngine.kt` (line 198). This is a minimal visibility change — `internal`
   keeps it hidden from external consumers while exposing it to the benchmark module
   (which is in the same Gradle build but a different module, so it needs `internal` +
   the modules must be in the same compilation group, OR the benchmark duplicates the math).

   **Chosen approach: duplicate the projection formula in `InteractionSimulator`.**
   The formula is only 2 lines (see `IsometricEngine.kt:199-202`) and depends on
   `transformation` and `scale`, which are derived from the engine's `angle` (π/6) and
   `scale` (70.0) constructor defaults. Since the benchmark always uses default engine
   settings, the projection can be hardcoded:

   ```kotlin
   // InteractionSimulator.kt — matches IsometricEngine defaults (angle=π/6, scale=70.0)
   private val SCALE = 70.0
   private val ANGLE = PI / 6
   private val TX = doubleArrayOf(SCALE * cos(ANGLE), SCALE * sin(ANGLE))
   private val TY = doubleArrayOf(SCALE * cos(PI - ANGLE), SCALE * sin(PI - ANGLE))

   fun projectPoint(point: Point, canvasWidth: Int, canvasHeight: Int): Point2D {
       val originX = canvasWidth / 2.0
       val originY = canvasHeight * 0.9
       return Point2D(
           originX + point.x * TX[0] + point.y * TY[0],
           originY - point.x * TX[1] - point.y * TY[1] - (point.z * SCALE)
       )
   }
   ```

   **Trade-off:** Duplication means the benchmark projection breaks if `IsometricEngine`
   defaults change. This is acceptable because: (a) the engine defaults haven't changed
   since inception, (b) a mismatch would manifest as hit-test misses caught by check #2
   in validation, (c) avoiding a production visibility change for benchmark convenience.

3. Add small jitter (±5px) to simulate realistic finger taps.
4. Store whether the tap is expected to hit geometry (`expectedHit = true`) or empty space
   (`expectedHit = false`) for lightweight validation during the benchmark.

### Tap Execution

On the designated frame, call `renderer.hitTest()` directly with the tap coordinates. Record:
- Hit-test latency (nanoTime before/after)
- Whether the result matches `expectedHit` (non-null when `true`, null when `false`)
- Mismatches are logged but not hard failures (jitter may cause occasional misses)

**Note:** This deliberately bypasses Compose's `pointerInput` dispatch pipeline
(`IsometricScene.kt` lines 154–213). We are benchmarking the engine-level hit-test
cost (spatial index lookup + `findItemAt` point-in-polygon), not the Compose gesture
system overhead. Results should be interpreted as engine hit-test latency, not
end-to-end touch-to-result latency.

---

## 6. Metrics Collection

**File:** `MetricsCollector.kt`

Cross-reference: v2 plan §1.4 specifies the full metrics list. v2 plan §1.7 (from "What
Worked" section) notes that pre-allocated arrays and Choreographer-based frame pacing were
sound approaches from the v1 harness.

### Design

```kotlin
class MetricsCollector(private val maxFrames: Int = 500) {

    // Pre-allocated arrays — zero allocation during measurement
    private val frameTimes = LongArray(maxFrames)        // vsync-to-vsync interval (ns)
    private val prepareTimes = LongArray(maxFrames)      // engine.prepare() time (ns)
    private val drawTimes = LongArray(maxFrames)         // canvas draw time (ns)
    private val hitTestTimes = LongArray(maxFrames)      // hit test time (ns), 0 if no tap

    private var frameIndex = 0

    // Cache tracking (populated by instrumented renderer via hooks)
    var cacheHits: Long = 0
    var cacheMisses: Long = 0

    // --- Called by BenchmarkHooksImpl (from inside renderer) ---
    fun recordPrepareTime(ns: Long)           // called by onPrepareEnd hook
    fun recordDrawTime(ns: Long)              // called by onDrawEnd hook

    // --- Called by orchestrator (from Choreographer callback) ---
    fun recordFrameTime(ns: Long)             // vsync-to-vsync interval
    fun recordHitTest(ns: Long)               // called after renderer.hitTest()

    // --- Called after measurement phase ends ---
    fun advanceFrame()                        // increments frameIndex
    fun computeResults(): FrameMetrics
}
```

**Two recording paths:** Prepare and draw times are recorded by `BenchmarkHooksImpl`
(from inside the renderer, see §10). Frame time (vsync interval) and hit-test time are
recorded by the orchestrator (from the `Choreographer` callback). `advanceFrame()` is
called at the end of each frame callback to move the write cursor forward, ensuring all
four arrays stay aligned by frame index.

### FrameMetrics Output

```kotlin
data class FrameMetrics(
    // Frame time
    val avgFrameMs: Double,
    val p50FrameMs: Double,
    val p95FrameMs: Double,
    val p99FrameMs: Double,
    val minFrameMs: Double,
    val maxFrameMs: Double,
    val stdDevFrameMs: Double,

    // Prepare time
    val avgPrepareMs: Double,
    val p50PrepareMs: Double,
    val p95PrepareMs: Double,
    val p99PrepareMs: Double,
    val minPrepareMs: Double,
    val maxPrepareMs: Double,
    val stdDevPrepareMs: Double,

    // Draw time
    val avgDrawMs: Double,
    val p50DrawMs: Double,
    val p95DrawMs: Double,
    val p99DrawMs: Double,
    val minDrawMs: Double,
    val maxDrawMs: Double,
    val stdDevDrawMs: Double,

    // Hit test (only for interaction scenarios)
    val avgHitTestMs: Double,
    val p95HitTestMs: Double,

    // Cache stats
    val cacheHits: Long,
    val cacheMisses: Long,
    val cacheHitRatePercent: Double,

    // Run metadata
    val frameCount: Int,
    val allocatedMB: Double,      // Runtime.totalMemory() - freeMemory() (Java heap only)
    val gcInvocations: Int        // best-effort GC count (see note below)
)
```

**GC metrics note:** `java.lang.management.ManagementFactory` is available on Android (ART
exposes `GarbageCollectorMXBean`) but the reported counts may not include all ART GC types
(concurrent, young-gen, etc.). Treat `gcInvocations` as **best-effort**: useful for detecting
gross GC pressure differences between flag configurations, not for precise GC counting.

Alternative Android-specific APIs for richer diagnostics (optional, not required for Phase 1):
- `Debug.getRuntimeStat("art.gc.gc-count")` — ART-specific, more accurate
- `Debug.getNativeHeapAllocatedSize()` — native heap (Path objects, Skia buffers)
- `ActivityManager.getMemoryInfo()` — system-wide memory pressure

For Phase 1, stick with `Runtime.totalMemory() - freeMemory()` for `allocatedMB` and
`ManagementFactory` for `gcInvocations`. If GC counts prove unreliable during validation,
replace with `Debug.getRuntimeStat("art.gc.gc-count")` at that point.

### Timing Approach

Timing is split across two recording sites because Compose's rendering pipeline is
framework-driven — the orchestrator cannot wrap `engine.prepare()` or canvas draw in
nanoTime calls from outside.

**Site 1: Inside the renderer (via `BenchmarkHooksImpl`, see §10)**

The hooks fire synchronously during `DrawScope.render()` / `DrawScope.renderNative()`,
which Compose calls on the **main thread** during its draw pass:

```
    ┌─ onPrepareStart()
    │   [renderer.rebuildCache / engine.prepare]
    └─ onPrepareEnd()    → collector.recordPrepareTime(elapsed)
    ┌─ onDrawStart()
    │   [canvas draw]
    └─ onDrawEnd()       → collector.recordDrawTime(elapsed)
```

**Site 2: Orchestrator (`Choreographer` callback)**

The orchestrator records vsync-to-vsync intervals and hit-test timing:

```
Choreographer frame callback N:
    frameTimeNs = thisCallbackNs - lastCallbackNs   → collector.recordFrameTime(frameTimeNs)
    [apply mutations → updates mutableStateListOf → triggers recomposition]
    [hit test if applicable]
        t0 = System.nanoTime()
        renderer.hitTest(...)
        t1 = System.nanoTime()                       → collector.recordHitTest(t1 - t0)
    collector.advanceFrame()
    post next frame callback
```

**Key distinction:**
- `prepareTime` and `drawTime` are measured **inside** the render call (precise, sub-ms)
- `frameTime` is the **vsync interval** — includes mutation application, recomposition
  scheduling, and framework overhead (coarser, but represents real frame cost)
- `hitTestTime` is measured **outside** the render call (direct `renderer.hitTest()` call)

The mutation → recomposition → draw pipeline is asynchronous within a single vsync frame:
the orchestrator applies mutations in the `Choreographer` callback, Compose schedules
recomposition, and the draw pass runs later in the same frame. The hooks capture timing
from inside the draw pass regardless of when it runs. The orchestrator does **not** need
to wait for draw completion — it records vsync intervals and posts the next callback.

### Percentile Calculation

Sort the pre-allocated LongArray, then:
- `p50 = sorted[count * 50 / 100]`
- `p95 = sorted[count * 95 / 100]`
- `p99 = sorted[count * 99 / 100]`

No allocations needed — sort in-place on a copy.

---

## 7. Benchmark Orchestration

**File:** `BenchmarkOrchestrator.kt`

Cross-reference: v2 plan §1.1 specifies 500 warmup frames, forced GC, 2s cooldown.

### Lifecycle

```
IDLE → WARMUP → COOLDOWN → MEASUREMENT (x3 iterations) → EXPORT → DONE
```

| Phase | Frames | Purpose |
|-------|--------|---------|
| WARMUP | 30–500 (adaptive, see §16 Q6) | JIT compilation, Compose layout stabilization, cache population |
| COOLDOWN | ~120 (2s @ 60fps) | Let GC settle, CPU cool. Force `System.gc()` at start. |
| MEASUREMENT | 500 per iteration, 3 iterations | Actual data collection |
| EXPORT | — | Write CSV + JSON, run validation |

### Configuration

```kotlin
data class BenchmarkConfig(
    val scenario: Scenario,
    val flags: BenchmarkFlags,
    val warmupMaxFrames: Int = 500,      // adaptive warmup ceiling (see §16 Q6)
    val measurementFrames: Int = 500,
    val iterations: Int = 3,
    val cooldownMs: Long = 2000
)
```

### Scenario

```kotlin
data class Scenario(
    val name: String,
    val sceneSize: Int,           // 10, 50, 100, 200
    val mutationRate: Double,     // 0.10, 0.50
    val interaction: InteractionPattern
)

enum class InteractionPattern {
    NONE,
    OCCASIONAL,    // 1 tap/sec
    CONTINUOUS     // every frame
}
```

### Frame Pacing

Use `Choreographer.getInstance().postFrameCallback()` to pace frames to vsync. This ensures
we measure real frame times, not synthetic tight-loop times. The previous harness (v1) used
this approach and it was sound (v2 plan "What Worked").

Each frame callback (runs on main thread):
1. Record vsync interval: `collector.recordFrameTime(now - lastCallbackNs)`
2. Increment frame counter
3. Check current phase (warmup / cooldown / measurement)
4. If measurement:
   a. Apply mutations via `MutationSimulator` → updates `mutableStateListOf` → Compose
      schedules recomposition (async, but within this vsync frame)
   b. Run hit test if applicable → `collector.recordHitTest(elapsed)`
   c. `collector.advanceFrame()`
5. Post next frame callback (unless done)

Note: prepare/draw timing is recorded by `BenchmarkHooksImpl` inside the renderer's draw
pass (see §6 Timing Approach), not by the orchestrator. The orchestrator only records
vsync intervals and hit-test timing.

### Multi-Iteration Handling

Run 3 iterations of 500 measurement frames each. Between iterations:
- Force `System.gc()`
- 2-second cooldown (post delayed frame callback)
- Reset `MetricsCollector` for fresh recording

The 3 iterations are used for consistency validation (§9, check #3).

---

## 8. Results Export

**File:** `ResultsExporter.kt`

Cross-reference: v2 plan §1.4 and previous CSV format from
`benchmark-results/benchmark_results_20260105-134048.csv`.

### CSV Format

Fresh format designed for the new harness. The v1 harness used a different column set
(`avgVsyncMs`, no flags column, no device metadata) — **no backward compatibility is
attempted or needed** since the v1 results were unreliable (see v2 plan "What Failed").

```
name,sceneSize,scenario,interaction,flags,
avgFrameMs,p50FrameMs,p95FrameMs,p99FrameMs,minFrameMs,maxFrameMs,stdDevFrameMs,
avgPrepareMs,p50PrepareMs,p95PrepareMs,p99PrepareMs,minPrepareMs,maxPrepareMs,stdDevPrepareMs,
avgDrawMs,p50DrawMs,p95DrawMs,p99DrawMs,minDrawMs,maxDrawMs,stdDevDrawMs,
avgHitTestMs,p95HitTestMs,
frameCount,iterations,allocatedMB,gcInvocations,
cacheHits,cacheMisses,cacheHitRate%,
deviceModel,androidVersion,isEmulator,
timestamp
```

**Notable columns:**
- `flags`: string encoding of `BenchmarkFlags` (e.g. `"cache=0,path=0,sort=0,spatial=0,native=0"`)
- `iterations`: number of measurement iterations (default 3)
- `avgHitTestMs`, `p95HitTestMs`: only populated for interaction scenarios
- `deviceModel`, `androidVersion`, `isEmulator`: device metadata for cross-device comparison

### JSON Format

Additionally export a JSON file per run with full per-frame timing arrays. This enables
offline analysis (histograms, outlier detection) that CSV aggregates can't provide.

```json
{
  "config": { "scenario": {...}, "flags": {...} },
  "device": { "model": "...", "androidVersion": "...", "isEmulator": true },
  "iterations": [
    {
      "frameTimes": [16.2, 16.5, ...],
      "prepareTimes": [12.1, 12.3, ...],
      "drawTimes": [3.8, 3.9, ...],
      "hitTestTimes": [0, 0, 0.5, 0, ...],
      "cacheHits": 490,
      "cacheMisses": 10
    },
    ...
  ],
  "aggregated": { ... same as CSV row ... }
}
```

### Output Location

Use **app-specific external storage** (`Context.getExternalFilesDir()`) instead of
`/sdcard/` directly. This avoids `WRITE_EXTERNAL_STORAGE` permission (removed in
Android 11+) and scoped storage complications:

```
// On device:
/sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark-results/<timestamp>/
├── summary.csv                    — one row per scenario (append mode)
├── <scenario-name>.json           — full per-frame data
└── validation.log                 — harness validation results
```

In code:
```kotlin
val outputDir = context.getExternalFilesDir("benchmark-results")
    ?: throw IllegalStateException("External storage not available")
val runDir = File(outputDir, timestamp)
runDir.mkdirs()
```

Pulled to local machine via:
```
adb pull /sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark-results/ benchmark-results/
```

> **Note:** `getExternalFilesDir()` content is deleted when the app is uninstalled.
> Pull results after each benchmark run. No storage permissions needed in AndroidManifest.

---

## 9. Validation Checks

**File:** `validation/HarnessValidator.kt`

Cross-reference: v2 plan §1.6 specifies 5 validation checks. These are the most critical
part of the harness — they are what was MISSING from v1 and caused all the broken results.

### Check 1: Cache Validation

**What:** Static scene + `enablePreparedSceneCache = true` → cache hit rate > 95% after warmup.

**How:**
- After warmup phase, reset cache counters.
- Run 500 measurement frames with `mutationRate = 0.0` (static).
- At end, check `cacheHits / (cacheHits + cacheMisses) > 0.95`.
- If FAIL: log error with full details. This means dirty tracking is broken (same as v1's
  0% hit rate problem). **Do not trust any results from this run.**

**Implementation:** The instrumented renderer (§10) must increment `cacheHits` when
`needsUpdate()` returns `false` and `cacheMisses` when it returns `true`.

### Check 2: Flag Validation

**What:** Assert optimization flags match config at startup.

**How:** At benchmark start, log all flag states:
```
[BENCHMARK] Flags: cache=true, pathCaching=false, broadPhaseSort=false, spatialIndex=false, nativeCanvas=false
[BENCHMARK] RenderOptions: depthSorting=true, backfaceCulling=true, boundsChecking=true
[BENCHMARK] Renderer: enablePathCaching=false, enableSpatialIndex=false
[BENCHMARK] Scene: useNativeCanvas=false
```

Cross-check: the renderer's constructor params must match the flags passed via config.
If mismatch: FAIL with descriptive error.

**Why this matters:** In v1, "enabled" didn't guarantee "working." A flag could be set but
the code path might not actually execute (e.g., the cache was "enabled" but always missed).

### Check 3: Consistency Check

**What:** 3 iterations must have coefficient of variation (CV) < 15% for average frame time.

**How:**
- After all 3 iterations, compute `stddev(avgFrameMs across iterations) / mean(avgFrameMs across iterations)`.
- If CV > 15%: log warning (not a hard fail, but flag for investigation).
- High CV indicates: thermal throttling, GC pressure, background processes, or a bug.

### Check 4: Sanity Check

**What:** N=10 baseline frame time < 20ms.

**How:** After the first baseline run (N=10, all flags disabled), check `avgFrameMs < 20`.
- If FAIL: something is fundamentally wrong with the harness or the device. All subsequent
  results are suspect.
- This catches: accidental debug builds, profiler overhead, emulator misconfiguration.

### Check 5: Mutation Validation

**What:** 10% mutation rate → ~10% of objects change per frame.

**How:** The `MutationSimulator` returns the count of mutations applied per frame. Over 500
frames, compute `mean(mutationCount) / sceneSize`. This should be within ±2% of the configured
`mutationRate`.

If FAIL: the mutation logic is buggy. Results for mutation scenarios are unreliable.

### Validation Output

All checks produce a `validation.log` file:

```
[PASS] Cache validation: hitRate=97.2% (threshold: >95%)
[PASS] Flag validation: all flags match config
[PASS] Consistency: CV=8.3% (threshold: <15%)
[PASS] Sanity: N=10 avgFrameMs=4.2ms (threshold: <20ms)
[PASS] Mutation validation: observed rate=10.3% (expected: 10.0%, tolerance: ±2%)
```

Or:
```
[FAIL] Cache validation: hitRate=0.0% (threshold: >95%)
       Detail: 0 hits, 500 misses over 500 frames on STATIC scene
       CRITICAL: Dirty tracking is broken. Do not trust cache-related results.
```

### Self-Test Scenarios

Checks 1 and 4 require scenarios that are **not** part of the 24-scenario benchmark matrix
(check 1 needs `mutationRate = 0.0`; check 4 needs a known-good N=10 baseline). These run
as a separate self-test pass before the main benchmark matrix:

| # | Name | Size | Mutation | Interaction | Cache | forceRebuild | Purpose |
|---|------|------|----------|-------------|-------|-------------|---------|
| S1 | `selftest_cache` | 10 | **0.0** | NONE | **ON** | false | Check 1: cache hit rate > 95% on static scene |
| S2 | `selftest_sanity` | 10 | 0.10 | NONE | OFF | **true** | Check 4: baseline frame time < 20ms |

Note: S2 sends empty flags (`{}`), so `enablePreparedSceneCache = false` → `forceRebuild = true`.
This means every frame does a full rebuild (100% cache miss). This is intentional — the sanity
check validates that even a full-rebuild path on a 10-shape scene stays under 20ms.

The self-test pass uses the same `BenchmarkOrchestrator` lifecycle but with shorter
measurement (100 frames, 1 iteration) since it's validating the harness, not collecting
performance data. If either self-test fails, the runner aborts with a diagnostic message
before running the 24-scenario matrix.

The shell script runs self-tests first:
```bash
echo "=== Harness self-test ==="
run_scenario "selftest_cache" '{"sceneSize":10,"mutationRate":0.0,"interaction":"NONE"}' \
    '{"enablePreparedSceneCache":true}'
run_scenario "selftest_sanity" '{"sceneSize":10,"mutationRate":0.10,"interaction":"NONE"}' \
    '{}'
# Check self-test results before proceeding
if ! validate_selftest; then
    echo "ABORT: Self-test failed. Fix harness before benchmarking."
    exit 1
fi
echo "=== Self-test passed. Starting benchmark matrix ==="
```

Acceptance criterion #6 (§15) references this self-test pass, not a scenario from the matrix.

---

## 10. Renderer Instrumentation

This is the key modification to production code that enables accurate benchmarking.

> **Line number references below** (e.g. "line 73", "line 118") are from the current
> `IsometricRenderer.kt` **before** this instrumentation is added. After adding the
> `RenderBenchmarkHooks` interface, `benchmarkHooks` property, and `forceRebuild` property,
> all downstream line numbers will shift by ~20–30 lines. Use function names (not line
> numbers) when navigating during implementation.

### Problem

The `IsometricRenderer` (`:isometric-compose`, `IsometricRenderer.kt`) does not track:
- Cache hit/miss counts
- Prepare time vs draw time breakdown
- Whether `needsUpdate()` returned true or false

The deleted v1 benchmark module had its own `MetricsCollector` but it was external to the
renderer, so it couldn't measure internal timings accurately. This led to the 0% cache hit
rate going undetected.

### Solution: BenchmarkHooks Interface

Add a minimal instrumentation interface to `IsometricRenderer`:

```kotlin
// In IsometricRenderer.kt (new addition)
interface RenderBenchmarkHooks {
    fun onPrepareStart()
    fun onPrepareEnd()
    fun onDrawStart()
    fun onDrawEnd()
    fun onCacheHit()
    fun onCacheMiss()
}
```

Add a nullable `benchmarkHooks` property to `IsometricRenderer`:

```kotlin
class IsometricRenderer(
    private val engine: IsometricEngine,
    private val enablePathCaching: Boolean = true,
    private val enableSpatialIndex: Boolean = true,
    var benchmarkHooks: RenderBenchmarkHooks? = null    // NEW — null in production
)
```

### Instrumentation Points

In `ensurePreparedScene()` (around line 73):
```kotlin
if (needsUpdate(rootNode, context, width, height)) {
    benchmarkHooks?.onCacheMiss()
    benchmarkHooks?.onPrepareStart()
    rebuildCache(rootNode, context, width, height)
    benchmarkHooks?.onPrepareEnd()
} else {
    benchmarkHooks?.onCacheHit()
}
```

In `render()` (around line 118):
```kotlin
benchmarkHooks?.onDrawStart()
// ... existing draw logic ...
benchmarkHooks?.onDrawEnd()
```

Same for `renderNative()` (around line 158).

### Force-Rebuild Property

To benchmark the "no cache" baseline, add a `var forceRebuild: Boolean = false` **property**
on `IsometricRenderer`. When `true`, `ensurePreparedScene()` calls `invalidate()` before the
`needsUpdate()` check, forcing a full rebuild every frame.

```kotlin
class IsometricRenderer(
    private val engine: IsometricEngine,
    private val enablePathCaching: Boolean = true,
    private val enableSpatialIndex: Boolean = true,
    var benchmarkHooks: RenderBenchmarkHooks? = null,
    var forceRebuild: Boolean = false              // NEW
)
```

In `ensurePreparedScene()` (line 73):
```kotlin
private fun ensurePreparedScene(...): PreparedScene? {
    if (width <= 0 || height <= 0) return null
    if (forceRebuild) invalidate()                 // NEW — force cache miss
    if (needsUpdate(rootNode, context, width, height)) {
        benchmarkHooks?.onCacheMiss()
        benchmarkHooks?.onPrepareStart()
        rebuildCache(rootNode, context, width, height)
        benchmarkHooks?.onPrepareEnd()
    } else {
        benchmarkHooks?.onCacheHit()
    }
    return cachedPreparedScene
}
```

Using a property (not a method parameter) avoids changing `render()`/`renderNative()`
signatures and their call sites in `IsometricScene.kt`. `IsometricScene` gains one new
parameter: `forceRebuild: Boolean = false`, then assigns `renderer.forceRebuild = forceRebuild`
after creating/remembering the renderer.

### Impact on Production Code

- `benchmarkHooks` is null by default → zero overhead in production (null check is branch-free
  on modern JVMs after inlining).
- `forceRebuild` defaults to `false` → no behavioral change for existing callers.
- The `RenderBenchmarkHooks` interface lives in `:isometric-compose` alongside the renderer.

### Benchmark-Side Implementation

In the benchmark module, implement `RenderBenchmarkHooks`:

```kotlin
/**
 * Thread safety: all hook methods are called from Compose's draw pass on the main thread.
 * The instance fields (prepareStartNs, drawStartNs) are safe without synchronization
 * because Compose guarantees single-threaded rendering. If renderNative() ever moves
 * off the main thread, this class would need @Volatile or thread-local storage.
 */
class BenchmarkHooksImpl(private val collector: MetricsCollector) : RenderBenchmarkHooks {
    private var prepareStartNs: Long = 0
    private var drawStartNs: Long = 0

    override fun onPrepareStart() { prepareStartNs = System.nanoTime() }
    override fun onPrepareEnd() { collector.recordPrepareTime(System.nanoTime() - prepareStartNs) }
    override fun onDrawStart() { drawStartNs = System.nanoTime() }
    override fun onDrawEnd() { collector.recordDrawTime(System.nanoTime() - drawStartNs) }
    override fun onCacheHit() { collector.cacheHits++ }
    override fun onCacheMiss() { collector.cacheMisses++ }
}
```

---

## 11. BenchmarkActivity UI

**File:** `BenchmarkActivity.kt` + `BenchmarkScreen.kt`

### BenchmarkActivity

- Receives `BenchmarkConfig` via Intent extras (JSON-encoded string).
- Sets up fullscreen, locks orientation (landscape preferred for consistent viewport).
- Creates `BenchmarkOrchestrator` and starts it.
- Displays `BenchmarkScreen` composable.
- On completion: writes results, calls `finish()` with result code.

### BenchmarkScreen

A minimal Compose UI:

```
┌─────────────────────────────────────────┐
│ [Status: WARMUP frame 342/500]          │ ← Small overlay text (top-left)
│                                         │
│                                         │
│         IsometricScene(...)             │ ← Full viewport scene under test
│                                         │
│                                         │
│                                         │
└─────────────────────────────────────────┘
```

The overlay shows:
- Current phase (WARMUP / COOLDOWN / MEASUREMENT / DONE)
- Frame counter
- Current iteration (1/3, 2/3, 3/3)
- Live avg frame time (updated every 60 frames to avoid recomposition overhead)

The overlay is rendered in a separate composable layer that does NOT participate in
the benchmark measurement.

### Scene Construction

The `BenchmarkScreen` composable generates the scene using `SceneGenerator` and renders
it inside `IsometricScene`. Mutations are applied per-frame via `MutationSimulator`.

The scene is constructed using the Runtime API composables (`IsometricScope.Shape`,
`IsometricScope.Group`, etc.) — the same API that production code uses. This ensures
we're benchmarking the real code path, not a synthetic shortcut.

```kotlin
// BenchmarkScreen.kt — scene construction with full benchmark wiring

// Create hooks impl that feeds timing data to the collector
val benchmarkHooks = remember(collector) { BenchmarkHooksImpl(collector) }

// items is a mutableStateListOf<GeneratedItem> — mutations drive recomposition
val items = remember { mutableStateListOf<GeneratedItem>() }

// Populate initial scene (once)
LaunchedEffect(config.scenario) {
    val generated = SceneGenerator(seed = 12345L).generate(config.scenario.sceneSize)
    items.clear()
    items.addAll(generated)
}

// LocalBenchmarkHooks provider wraps IsometricScene so the renderer
// picks up the hooks during composition (see §10 for hook wiring).
CompositionLocalProvider(LocalBenchmarkHooks provides benchmarkHooks) {
    IsometricScene(
        modifier = Modifier.fillMaxSize(),
        enablePathCaching = config.flags.enablePathCaching,
        enableSpatialIndex = config.flags.enableSpatialIndex,
        useNativeCanvas = config.flags.enableNativeCanvas,
        forceRebuild = !config.flags.enablePreparedSceneCache,  // bypass cache for baseline (§2)
        renderOptions = RenderOptions.Default,
        onTap = { x, y, node -> /* interaction simulation response */ }
    ) {
        // Use keyed ForEach for stable node identity and realistic dirty tracking.
        // key = item.id ensures Compose can diff individual nodes on mutation,
        // matching how production scenes with stable keys behave.
        ForEach(items, key = { it.id }) { item ->
            Shape(
                shape = item.shape,
                color = item.color,
                position = item.position
            )
        }
    }
}
```

> **Hook wiring:** `LocalBenchmarkHooks` is a CompositionLocal declared in the compose
> runtime package (`null` by default, zero production overhead). `IsometricScene` reads it
> via `LocalBenchmarkHooks.current` and assigns `renderer.benchmarkHooks = currentHook`
> after constructing/remembering the renderer. The provider must wrap `IsometricScene` (not
> follow it) so the local is available during `IsometricScene`'s composition.

---

## 12. Shell Script Runner

**File:** `isometric-benchmark/benchmark-runner.sh` (or `.bat` for Windows)

Cross-reference: v2 plan §1.5 specifies `BenchmarkRunner.sh`.

### Purpose

Automate running all 24 scenarios sequentially via adb. Each scenario:
1. Launches `BenchmarkActivity` with config JSON
2. Waits for activity to finish
3. Pulls results from device
4. Logs progress

### Scenario Matrix

Per v2 plan §1.2:

| # | Size | Mutation | Interaction | Name |
|---|------|----------|-------------|------|
| 1 | 10 | 10% | none | `s10_m10_none` |
| 2 | 10 | 10% | occasional | `s10_m10_occ` |
| 3 | 10 | 10% | continuous | `s10_m10_cont` |
| 4 | 10 | 50% | none | `s10_m50_none` |
| 5 | 10 | 50% | occasional | `s10_m50_occ` |
| 6 | 10 | 50% | continuous | `s10_m50_cont` |
| 7 | 50 | 10% | none | `s50_m10_none` |
| 8 | 50 | 10% | occasional | `s50_m10_occ` |
| 9 | 50 | 10% | continuous | `s50_m10_cont` |
| 10 | 50 | 50% | none | `s50_m50_none` |
| 11 | 50 | 50% | occasional | `s50_m50_occ` |
| 12 | 50 | 50% | continuous | `s50_m50_cont` |
| 13 | 100 | 10% | none | `s100_m10_none` |
| 14 | 100 | 10% | occasional | `s100_m10_occ` |
| 15 | 100 | 10% | continuous | `s100_m10_cont` |
| 16 | 100 | 50% | none | `s100_m50_none` |
| 17 | 100 | 50% | occasional | `s100_m50_occ` |
| 18 | 100 | 50% | continuous | `s100_m50_cont` |
| 19 | 200 | 10% | none | `s200_m10_none` |
| 20 | 200 | 10% | occasional | `s200_m10_occ` |
| 21 | 200 | 10% | continuous | `s200_m10_cont` |
| 22 | 200 | 50% | none | `s200_m50_none` |
| 23 | 200 | 50% | occasional | `s200_m50_occ` |
| 24 | 200 | 50% | continuous | `s200_m50_cont` |

### Estimated Runtime

Warmup is adaptive (§16 Q6), so per-scenario time varies by scene size:

| Scene size | Warmup (est.) | Cooldown | Measurement | Total (est.) |
|-----------|--------------|----------|-------------|-------------|
| N=10 | ~30 frames ≈ 0.5s | 6s | 25s | ~32s |
| N=50 | ~50 frames ≈ 1s | 6s | 25s | ~32s |
| N=100 | ~100 frames ≈ 5s | 6s | 25s | ~36s |
| N=200 | 30s ceiling | 6s | 25s | ~61s |

- Cooldown: 2s × 3 iterations = 6s
- Measurement: 500 frames × 3 iterations @ 60fps ≈ 25s
- **Total for 24 scenarios: ~15–20 minutes** (small scenes dominate the matrix)

### Script Pseudocode

```bash
#!/bin/bash
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULTS_DIR="benchmark-results/${TIMESTAMP}"
PACKAGE="io.fabianterhorst.isometric.benchmark"
ACTIVITY="${PACKAGE}/.BenchmarkActivity"

SIZES=(10 50 100 200)
MUTATIONS=(0.10 0.50)
INTERACTIONS=("NONE" "OCCASIONAL" "CONTINUOUS")

for size in "${SIZES[@]}"; do
  for mut in "${MUTATIONS[@]}"; do
    for interact in "${INTERACTIONS[@]}"; do
      NAME="s${size}_m${mut/0./}_${interact,,}"
      CONFIG="{\"sceneSize\":${size},\"mutationRate\":${mut},\"interaction\":\"${interact}\"}"

      echo "[${NAME}] Starting..."
      adb shell am start -n "${ACTIVITY}" \
          --es config "${CONFIG}" \
          --es flags "{}" \
          --es name "${NAME}"

      # Wait for activity to finish (broadcasts result)
      adb shell "am monitor" &  # or poll logcat for completion marker

      echo "[${NAME}] Done."
    done
  done
done

# Pull all results
mkdir -p "${RESULTS_DIR}"
adb pull "/sdcard/Android/data/${PACKAGE}/files/benchmark-results/" "${RESULTS_DIR}/"
echo "Results saved to ${RESULTS_DIR}/"
```

The exact wait-for-completion mechanism needs refinement during implementation (options:
`am instrument -w`, activity result broadcast, logcat polling for a sentinel string).

---

## 13. File Inventory

### New Files (benchmark module)

| File | Lines (est.) | Purpose |
|------|-------------|---------|
| `isometric-benchmark/build.gradle.kts` | ~40 | Module build config |
| `isometric-benchmark/src/main/AndroidManifest.xml` | ~15 | Activity declaration |
| `isometric-benchmark/src/main/kotlin/.../BenchmarkActivity.kt` | ~80 | Entry point |
| `isometric-benchmark/src/main/kotlin/.../BenchmarkScreen.kt` | ~120 | Compose UI |
| `isometric-benchmark/src/main/kotlin/.../BenchmarkOrchestrator.kt` | ~200 | Lifecycle orchestration |
| `isometric-benchmark/src/main/kotlin/.../BenchmarkFlags.kt` | ~30 | Flag data class |
| `isometric-benchmark/src/main/kotlin/.../Scenario.kt` | ~40 | Config data classes |
| `isometric-benchmark/src/main/kotlin/.../SceneGenerator.kt` | ~100 | Deterministic scene gen |
| `isometric-benchmark/src/main/kotlin/.../MutationSimulator.kt` | ~80 | Per-frame mutations |
| `isometric-benchmark/src/main/kotlin/.../InteractionSimulator.kt` | ~90 | Tap point generation |
| `isometric-benchmark/src/main/kotlin/.../MetricsCollector.kt` | ~120 | Zero-alloc metrics |
| `isometric-benchmark/src/main/kotlin/.../BenchmarkHooksImpl.kt` | ~25 | `RenderBenchmarkHooks` impl that bridges renderer → `MetricsCollector` |
| `isometric-benchmark/src/main/kotlin/.../ResultsExporter.kt` | ~150 | CSV + JSON output |
| `isometric-benchmark/src/main/kotlin/.../validation/HarnessValidator.kt` | ~120 | 5 validation checks |
| `isometric-benchmark/benchmark-runner.sh` | ~60 | Automation script |
| **Total new** | **~1,270** | |

### Modified Files (production code)

| File | Change | Lines Changed (est.) |
|------|--------|---------------------|
| `settings.gradle` | Add `include ':isometric-benchmark'` | +1 |
| `isometric-compose/.../IsometricRenderer.kt` | Add `RenderBenchmarkHooks` interface, `benchmarkHooks` property, instrumentation points in `ensurePreparedScene`/`render`/`renderNative`, `forceRebuild` property | ~40 lines added |
| `isometric-compose/.../IsometricScene.kt` | Add `forceRebuild: Boolean = false` parameter, forward to renderer after construction (see §10) | ~5 lines added |
| `isometric-compose/.../CompositionLocals.kt` | Add `LocalBenchmarkHooks` CompositionLocal (null default) for benchmark-only hook wiring | ~5 lines added |
| **Total modified** | | **~51** |

### No Changes To

- `IsometricEngine.kt` — no modifications (projection math duplicated in benchmark; see §5)
- `IsometricNode.kt` — no modifications (dirty tracking already works)
- `RenderOptions.kt` — no modifications
- `RenderContext.kt` — no modifications

---

## 14. Implementation Order

Recommended sequence for implementing Phase 1, with dependencies shown:

```
Step 1: Module scaffolding
    ├── build.gradle.kts
    ├── settings.gradle change
    ├── AndroidManifest.xml
    └── Verify: module compiles, blank Activity launches
         Dependencies: none

Step 2: Data classes
    ├── BenchmarkFlags.kt
    ├── Scenario.kt
    └── Verify: compiles
         Dependencies: Step 1

Step 3: Scene generation
    ├── SceneGenerator.kt
    └── Verify: unit test — same seed produces same output
         Dependencies: Step 2

Step 4: Mutation simulation
    ├── MutationSimulator.kt
    └── Verify: unit test — mutation rate matches configured rate ±2%
         Dependencies: Step 3

Step 5: Interaction simulation
    ├── InteractionSimulator.kt
    └── Verify: unit test — tap points are within canvas bounds
         Dependencies: Step 3

Step 6: Renderer instrumentation (PRODUCTION CODE CHANGE)
    ├── RenderBenchmarkHooks interface in IsometricRenderer.kt
    ├── benchmarkHooks property
    ├── Instrumentation in ensurePreparedScene(), render(), renderNative()
    ├── forceRebuild property (not method parameter — see §10)
    └── Verify: existing tests still pass, hooks are null in non-benchmark usage
         Dependencies: none (can be done in parallel with Steps 3-5)

Step 7: Metrics collection
    ├── MetricsCollector.kt
    └── Verify: unit test — percentile calculations correct
         Dependencies: Step 6

Step 8: Benchmark UI
    ├── BenchmarkScreen.kt
    ├── BenchmarkActivity.kt
    └── Verify: Activity launches, scene renders, flags take effect
         Dependencies: Steps 2, 3, 6

Step 9: Orchestration
    ├── BenchmarkOrchestrator.kt
    └── Verify: warmup → cooldown → measurement lifecycle works, frame counter advances
         Dependencies: Steps 7, 8

Step 10: Results export
    ├── ResultsExporter.kt
    └── Verify: CSV output matches expected format, JSON is parseable
         Dependencies: Step 7

Step 11: Validation
    ├── HarnessValidator.kt
    └── Verify: all 5 checks pass on a simple scenario
         Dependencies: Steps 7, 9, 10

Step 12: Shell script
    ├── benchmark-runner.sh
    └── Verify: end-to-end run of 1 scenario via adb
         Dependencies: Steps 9, 10, 11

Step 13: Full integration test
    └── Run all 24 scenarios with baseline flags (all disabled)
    └── Verify: 24 CSV rows, validation.log shows all PASS
         Dependencies: all above
```

### Parallelizable Work

- Steps 3, 4, 5 can be done in parallel (all depend only on Step 2)
- Step 6 can be done in parallel with Steps 3–5 (independent code paths)
- Steps 7, 8 can start once Step 6 is done

### Estimated Effort

| Step | Effort |
|------|--------|
| Steps 1–2 (scaffolding) | Small — boilerplate |
| Steps 3–5 (generators) | Medium — algorithmic but straightforward |
| Step 6 (instrumentation) | Small — but high risk, touches production code |
| Steps 7–8 (metrics + UI) | Medium |
| Step 9 (orchestration) | Large — most complex component, async lifecycle |
| Steps 10–11 (export + validation) | Medium |
| Steps 12–13 (script + integration) | Small–Medium |

---

## 15. Acceptance Criteria

Phase 1 is complete when:

1. **`isometric-benchmark` module builds and installs** on emulator or device.

2. **A single scenario runs end-to-end:** BenchmarkActivity starts → warmup → measurement
   → results exported to app-specific external storage (see §8).

3. **CSV output** contains all columns defined in §8, with non-zero values for timing fields.

4. **JSON output** contains per-frame timing arrays of length 500.

5. **All 5 validation checks pass** for a baseline scenario (N=10, all flags disabled,
   mutationRate=0.10, interaction=NONE):
   - Cache validation: N/A for baseline (cache disabled)
   - Flag validation: all flags match
   - Consistency: CV < 15%
   - Sanity: avgFrameMs < 20ms
   - Mutation validation: observed rate ≈ 10%

6. **Cache validation passes** via the `selftest_cache` self-test scenario (§9): N=10,
   `enablePreparedSceneCache=true`, `mutationRate=0.0`, interaction=NONE → hit rate > 95%.

7. **3 iterations produce consistent results** (CV < 15%) for at least the N=10 and N=100
   scenarios.

8. **The shell script runs all 24 scenarios** unattended and produces a complete results
   directory.

9. **No production behavior changes** — existing app and tests pass without modification
   (the `benchmarkHooks` property is null by default, `forceRebuild` defaults to false).

10. **Reproducibility:** Running the same scenario twice with the same config produces
    results within 15% of each other (same seed, same scene).

---

## 16. Open Questions

These should be resolved during implementation:

1. **Compose recomposition timing:** How do we precisely measure the time spent in
   `engine.prepare()` vs canvas draw when Compose controls the draw schedule? The
   `RenderBenchmarkHooks` approach (§10) should work, but needs validation that the
   hooks fire synchronously within the draw pass.

2. **Activity completion signaling:** The shell script needs to know when a benchmark
   scenario is finished. Options: (a) `setResult()` + `finish()` with `am start -W`,
   (b) broadcast intent, (c) logcat sentinel. Need to test which is most reliable.

3. **Emulator viewport size:** The canvas dimensions affect rendering (projection uses
   `width/2` and `height*0.9`). Should we lock to a specific resolution (e.g., 1920x1080)
   or measure at the device's natural resolution?

4. **Memory measurement accuracy:** `Runtime.totalMemory() - freeMemory()` is approximate.
   `Debug.getNativeHeapAllocatedSize()` might be more accurate for native allocations
   (Path objects). Decide during implementation.

5. **forceRebuild scope:** Should `forceRebuild` also bypass path caching and spatial
   index rebuilds, or only the PreparedScene cache? Current design: it calls `invalidate()`
   which clears everything. This means the "no cache" baseline truly has no caching at all.
   That's what we want for baseline, but it means we can't test "PreparedScene cache OFF,
   path caching ON" in the same run. This is fine — the v2 plan tests each flag individually.

6. **Warm-up frame count on slow scenes:** N=200 with all flags disabled takes ~450ms per
   prepare (estimated). 500 warmup frames would take 500 × 450ms = 225 seconds — too long.

   **Solution: Adaptive warmup with a stability gate.** Instead of a fixed frame count, warmup
   runs until frame times stabilize (JIT warm, caches populated) OR a hard ceiling is reached:

   ```kotlin
   // In BenchmarkOrchestrator
   val WARMUP_MIN_FRAMES = 30        // always run at least this many
   val WARMUP_MAX_FRAMES = 500       // never exceed this
   val WARMUP_MAX_DURATION_MS = 30_000L  // hard ceiling: 30 seconds
   val STABILITY_WINDOW = 20         // rolling window for CV check
   val STABILITY_CV_THRESHOLD = 0.10 // coefficient of variation < 10% = stable

   fun isWarmupComplete(frameTimes: LongArray, count: Int, elapsedMs: Long): Boolean {
       if (count < WARMUP_MIN_FRAMES) return false
       if (count >= WARMUP_MAX_FRAMES) return true
       if (elapsedMs >= WARMUP_MAX_DURATION_MS) return true
       // Check stability of last STABILITY_WINDOW frames
       if (count >= STABILITY_WINDOW) {
           val window = frameTimes.slice((count - STABILITY_WINDOW) until count)
           val mean = window.average()
           val stdDev = sqrt(window.map { (it - mean).pow(2) }.average())
           if (stdDev / mean < STABILITY_CV_THRESHOLD) return true
       }
       return false
   }
   ```

   This gives fast warmup for small scenes (~30 frames ≈ 0.5s) and bounded warmup for
   large scenes (at most 30s). The actual warmup frame count is recorded in the JSON output
   for post-hoc analysis.
