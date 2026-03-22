# WebGPU Rendering Roadmap — WS11, WS12, WS13

> **Workstreams**: WS11 (module + Phase 1), WS12 (Phase 2 renderer), WS13 (Phase 3 full GPU pipeline)
> **Depends on**: WS10 (per-node interaction props) — must be merged before WS11 begins
> **New artifact**: `isometric-webgpu` (separate Gradle module, optional dependency)
> **API pinned to**: `androidx.webgpu:1.0.0-alpha04` — breaking changes accepted as they land
> **Canvas backend**: Permanent public API — `RenderBackend.Canvas` is never removed
> **Authored**: 2026-03-18

---

## Quick Look — Caller Experience

Before any technical detail: this is the **shortest path** to GPU acceleration for an existing scene.

```kotlin
// Before (CPU sort, Canvas draw — unchanged):
IsometricScene {
    Shape(Prism(Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
}

// After (GPU compute sort, Canvas draw — add one dependency + one config field):
// build.gradle.kts: implementation("io.github.jayteealao:isometric-webgpu:x.y.z")
IsometricScene(config = SceneConfig(computeBackend = ComputeBackend.WebGpu)) {
    Shape(Prism(Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
}

// Phase 2 — full GPU render (WebGPU surface):
IsometricScene(config = SceneConfig(renderBackend = RenderBackend.WebGpu)) {
    Shape(Prism(Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
}
```

The default is always `ComputeBackend.Cpu` + `RenderBackend.Canvas`. Any caller who does not add
the `isometric-webgpu` dependency sees no change. Callers who do add it get two orthogonal
opt-in knobs — both expressed as `SceneConfig` fields, keeping `IsometricScene`'s parameter list
unchanged.

---

## Decision Record

| # | Topic | Decision |
|---|---|---|
| 1 | Phase scope | All three phases — full GPU roadmap |
| 2 | Alpha API risk | Pin `alpha04`, accept churn — no internal wrapper |
| 3 | Module structure | Separate `isometric-webgpu` Gradle artifact |
| 4 | Canvas backend | Permanent, first-class public API |
| 5 | Depth sort algorithm | GPU radix sort (common case) + CPU topological fallback (ambiguous pairs) |
| 6 | Texture support | Deferred to Phase 3 — UV coords retained in Phase 2 vertex layout; `textureIndex` added only when texture API is designed |
| 7 | Touch/hit-testing | Invisible zero-alpha Compose `Box` overlay; maps through viewport transform |
| 8 | Ordering | WS10 (per-node interaction props) lands before WS11 begins |
| 9 | GPU testing | Physical device CI — Firebase Test Lab or equivalent |
| 10 | Regression guard | Existing Canvas benchmarks suffice |

---

## Table of Contents

1. [Prerequisite: WS10 Per-Node Interaction Props](#1-prerequisite-ws10-per-node-interaction-props)
2. [Module Structure](#2-module-structure)
3. [RenderBackend Public API](#3-renderbackend-public-api)
4. [ComputeBackend Public API — GPU Compute for the Canvas Path](#4-computebackend-public-api--gpu-compute-for-the-canvas-path)
5. [WS11 — Phase 1: Module Scaffold + GpuDepthSorter](#5-ws11--phase-1-module-scaffold--gpudepthsorter)
6. [WS12 — Phase 2: WebGPU Isometric Renderer](#6-ws12--phase-2-webgpu-isometric-renderer)
7. [WS13 — Phase 3: Full GPU-Driven Pipeline](#7-ws13--phase-3-full-gpu-driven-pipeline)
8. [Testing Strategy](#8-testing-strategy)
9. [Risk Register](#9-risk-register)
10. [API Accuracy Reference — androidx.webgpu](#10-api-accuracy-reference--androidxwebgpu)

---

## 1. Prerequisite: WS10 Per-Node Interaction Props

WS10 must be fully merged before WS11 begins. The dependency is load-bearing in two ways:

**Hit-test wire-through.** Phase 2's touch bridge routes pointer events through the existing
`PointerEventType.Release` handler in `IsometricScene.kt`. That handler, after WS10, dispatches
`hitNode?.onClick?.invoke()`. If WS11 begins before WS10, the Phase 2 touch bridge ships against
a handler that will be structurally modified in WS10 — guaranteed merge conflict.

**Per-node interaction surface.** WS10 adds direct per-node props (`alpha`, `onClick`,
`onLongClick`, `testTag`, `nodeId`) to the scene composables through new source-visible overloads.
Phase 2's `AndroidExternalSurface` composable embedding re-wraps the `IsometricScene` signature,
and the WebGPU examples use those composables directly. Finalising the public-facing interaction
surface once, not twice, is the right order.

---

## 2. Module Structure

### New Gradle module: `isometric-webgpu`

```
isometric-webgpu/
├── build.gradle.kts
└── src/main/kotlin/io/github/jayteealao/isometric/webgpu/
    ├── GpuContext.kt                     — GPUDevice lifecycle
    ├── RenderBackendWebGpu.kt            — RenderBackend impl (Phase 2)
    ├── WebGpuSceneRenderer.kt            — AndroidExternalSurface composable (Phase 2)
    ├── sort/
    │   ├── DepthSorter.kt                — interface extracted from IsometricEngine (WS11)
    │   ├── CpuDepthSorter.kt             — existing sortPaths() logic moved here
    │   └── GpuDepthSorter.kt             — GPU radix sort + CPU topo fallback
    ├── triangulation/
    │   └── RenderCommandTriangulator.kt  — N-gon → triangle fan (Phase 2)
    ├── shader/
    │   ├── RadixSortShader.kt            — WGSL radix sort compute shader (Phase 1)
    │   ├── IsometricVertexShader.kt      — WGSL vertex shader (Phase 2)
    │   └── IsometricFragmentShader.kt    — WGSL fragment shader (Phase 2)
    └── pipeline/
        ├── GpuVertexBuffer.kt            — buffer management (Phase 2)
        └── GpuRenderPipeline.kt          — pipeline state object (Phase 2)
```

### `build.gradle.kts` for `isometric-webgpu`

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.jayteealao.isometric.webgpu"
    minSdk = 24  // matches androidx.webgpu requirement and existing project minSdk
}

dependencies {
    api(project(":isometric-compose"))       // access to GroupNode, RenderCommand, etc.
    implementation("androidx.webgpu:webgpu:1.0.0-alpha04")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

### `settings.gradle.kts` addition

```kotlin
include(":isometric-webgpu")
```

### Dependency topology

```
isometric-core
    ↑
isometric-compose   ← CanvasRenderBackend lives here (RenderBackend.Canvas)
    ↑
isometric-webgpu    ← WebGpuRenderBackend lives here (RenderBackend.WebGpu)
```

No circular dependencies. `isometric-compose` does not depend on `isometric-webgpu`.

---

## 3. RenderBackend Public API

`RenderBackend` is a composable interface defined in `isometric-compose`. It is the extension
point for all rendering backends — current and future.

### Interface definition (in `isometric-compose`)

```kotlin
// New file: isometric-compose/.../render/RenderBackend.kt

/**
 * Pluggable rendering surface for [IsometricScene].
 *
 * The default is [RenderBackend.Canvas], which uses Compose's `DrawScope` and is
 * supported on all devices. Optional backends (e.g. [WebGPU][androidx.webgpu]) are provided
 * by the `isometric-webgpu` artifact.
 *
 * **Ownership contract:**
 * - [IsometricScene] owns the node tree, prepare lifecycle ([SceneCache]), and hit testing.
 * - Backends receive an immutable [PreparedScene] snapshot and are responsible only for drawing.
 * - Backends must NEVER access [GroupNode] or the mutable scene tree.
 * - Hit testing is handled by [IsometricScene] via its existing pointer-input block —
 *   backends do not participate in hit testing.
 */
interface RenderBackend {
    /**
     * Emit the Compose tree for this backend's rendering surface.
     *
     * This function is called inside [IsometricScene]'s composition. Implementations are
     * responsible for their own surface lifecycle, frame loop, and invalidation strategy.
     *
     * @param preparedScene Immutable scene snapshot from [SceneCache.rebuild], managed by
     *   [IsometricScene]. Updated on the main thread after each dirty→prepare cycle.
     *   Backends observe this state and draw when it changes. May be `null` before
     *   the first prepare completes.
     * @param renderContext The current render context (viewport transform, light direction, etc.).
     * @param modifier Applied to the outermost composable emitted by this backend.
     * @param strokeStyle Stroke rendering strategy from [SceneConfig.strokeStyle]. GPU backends
     *   may ignore this in Phase 2 (all faces are filled; stroke is a Canvas-only concept for now).
     */
    @Composable
    fun Surface(
        preparedScene: State<PreparedScene?>,
        renderContext: RenderContext,
        modifier: Modifier,
        strokeStyle: StrokeStyle,
    )

    companion object {
        /** Canvas-based renderer. Supported on all devices. Always available. */
        val Canvas: RenderBackend = CanvasRenderBackend()
    }
}
```

### `SceneConfig` addition (in `isometric-compose`)

`renderBackend` is a field on `SceneConfig` — **not** a flat parameter on `IsometricScene`.
The existing `config: SceneConfig` / `config: AdvancedSceneConfig` overloads remain unchanged:

```kotlin
// SceneConfig gains renderBackend (and computeBackend — see §4) as new fields,
// both defaulting to the safe CPU/Canvas path:
open class SceneConfig(
    // ... all existing fields unchanged ...
    val renderBackend: RenderBackend = RenderBackend.Canvas,
    val computeBackend: ComputeBackend = ComputeBackend.Cpu,
)
```

`IsometricScene` itself does not change its parameter list. It owns the prepare lifecycle
via the existing `SceneCache.rebuild()` / `IsometricRenderer.ensurePreparedScene()` path,
exposes the result as a `State<PreparedScene?>`, and passes it to the backend:

```kotlin
// Inside IsometricScene (advanced overload) — scene ownership stays here:
val preparedScene = remember { mutableStateOf<PreparedScene?>(null) }

// The Canvas draw block (for RenderBackend.Canvas) or a SideEffect (for other backends)
// drives the prepare step. After each prepare, the snapshot is published:
//   preparedScene.value = renderer.currentPreparedScene
// This is the existing ensurePreparedScene() → SceneCache.rebuild() → PreparedScene path.
// Hit testing also stays in IsometricScene — backends never participate in hit testing.

config.renderBackend.Surface(
    preparedScene = preparedScene,
    renderContext = renderContext,
    modifier = modifier,
    strokeStyle = config.strokeStyle,
)
```

### `CanvasRenderBackend` (replaces current inline Canvas block)

```kotlin
// In isometric-compose, package-private
internal class CanvasRenderBackend : RenderBackend {
    @Composable
    override fun Surface(
        preparedScene: State<PreparedScene?>,
        renderContext: RenderContext,
        modifier: Modifier,
        strokeStyle: StrokeStyle,
    ) {
        // No rootNode access, no onDirty wiring, no IsometricRenderer instance.
        // IsometricScene owns the prepare lifecycle and hit testing.
        // This backend is a pure drawing surface.
        Canvas(modifier = modifier) {
            val scene = preparedScene.value ?: return@Canvas
            renderPreparedScene(scene, strokeStyle)
        }
    }

    // Mirrors IsometricRenderer.renderPreparedScene() — iterates scene.commands,
    // calls DrawScope.drawPath() with the appropriate StrokeStyle variant.
    private fun DrawScope.renderPreparedScene(scene: PreparedScene, strokeStyle: StrokeStyle) {
        scene.commands.forEach { command ->
            val path = command.toComposePath()
            val color = command.color.toComposeColor()
            when (strokeStyle) {
                is StrokeStyle.FillOnly -> drawPath(path, color, style = Fill)
                is StrokeStyle.Stroke ->
                    drawPath(path, strokeStyle.color.toComposeColor(), style = Stroke(strokeStyle.width))
                is StrokeStyle.FillAndStroke -> {
                    drawPath(path, color, style = Fill)
                    drawPath(path, strokeStyle.color.toComposeColor(), style = Stroke(strokeStyle.width))
                }
            }
        }
    }
}
```

**Key differences from the previous plan version:**
- `modifier` is passed through as-is (no `.fillMaxSize()` — respects caller sizing, per WS2 contract)
- No `rootNode` reference — the backend never touches the mutable node tree
- No `SideEffect`/`DisposableEffect` for `rootNode.onDirty` — `IsometricScene` owns dirty tracking
- No `IsometricRenderer` instance — the renderer is owned by `IsometricScene`; this backend draws
  from the immutable `PreparedScene` directly

### Usage in consumer apps

```kotlin
// Default — Canvas backend, no additional dependency
IsometricScene {
    Shape(Prism(Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
}

// WebGPU backend — requires isometric-webgpu dependency
IsometricScene(config = SceneConfig(renderBackend = RenderBackend.WebGpu)) {
    Shape(Prism(Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
}
```

`RenderBackend.WebGpu` is defined as an extension property in `isometric-webgpu`:

```kotlin
// In isometric-webgpu
val RenderBackend.Companion.WebGpu: RenderBackend
    get() = WebGpuRenderBackend()
```

This pattern (extension on `companion object`) means `RenderBackend.WebGpu` reads identically to
`RenderBackend.Canvas` at the call site, with zero magic. If `isometric-webgpu` is not on the
classpath, `RenderBackend.WebGpu` simply does not exist — compile error, not a runtime crash.

---

## 4. ComputeBackend Public API — GPU Compute for the Canvas Path

`RenderBackend` controls **where pixels are drawn** (Canvas vs WebGPU surface). `ComputeBackend`
controls **how the scene is prepared** (CPU topological sort vs GPU radix sort). The two axes are
independent: the most immediately useful combination is GPU compute + Canvas draw, which gets the
O(N²) sort bottleneck off the main thread while leaving `DrawScope.drawPath()` completely intact.

### Two interfaces, defined in `isometric-core`

Using primitive-only method signatures keeps `isometric-core` free of any GPU dependency and
avoids exposing the internal `DepthSorter.TransformedItem` type across module boundaries.

> **Guideline alignment (§4 Naming, §2 Progressive Disclosure)**:
> `SortingComputeBackend` is an `internal` extension point — it is not part of the public API
> surface. Users interact only with `ComputeBackend`. The extension property in `isometric-webgpu`
> is typed as `SortingComputeBackend` internally but exposed as `ComputeBackend.WebGpu` — callers
> never reference `SortingComputeBackend` by name. This satisfies §4 (names express intent, not
> implementation) and §2 (simple layer: `ComputeBackend`, power-user layer: `SortingComputeBackend`
> only if the implementor adds a custom GPU backend).

```kotlin
// isometric-core/.../ComputeBackend.kt

/**
 * Strategy for compute-intensive scene preparation.
 * isAsync = false  →  IsometricScene uses the existing synchronous Canvas draw path unchanged.
 * isAsync = true   →  IsometricScene uses a LaunchedEffect async path (see Section 4 below).
 */
interface ComputeBackend {
    val isAsync: Boolean get() = false
    companion object {
        val Cpu: ComputeBackend = CpuComputeBackend()   // default; synchronous; no GPU
    }
}

/**
 * Async extension for backends that sort by a float depth key.
 * The engine extracts one scalar key per face, calls sortByDepthKeys(), then reorders
 * its TransformedItem list by the returned indices, then runs refineAdjacentPairs().
 */
interface SortingComputeBackend : ComputeBackend {
    override val isAsync: Boolean get() = true

    /**
     * @param depthKeys Float depth key per face. Higher = further back (drawn first).
     * @return Indices into depthKeys in back-to-front order. Length == depthKeys.size.
     */
    suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray
}

private class CpuComputeBackend : ComputeBackend { override val isAsync = false }
```

`ComputeBackend.WebGpu` is defined as a companion extension in `isometric-webgpu` so it reads
identically to `ComputeBackend.Cpu` but is a compile error (not a runtime crash) when the artifact
is absent:

```kotlin
// isometric-webgpu: available only when the artifact is on the classpath
val ComputeBackend.Companion.WebGpu: SortingComputeBackend
    get() = WebGpuComputeBackend()
```

### `SceneConfig` field (not a flat `IsometricScene` parameter)

`computeBackend` is a field on `SceneConfig` (alongside `renderBackend` — see §3), keeping
`IsometricScene`'s own parameter list unchanged:

```kotlin
open class SceneConfig(
    // ... existing fields unchanged ...
    val renderBackend: RenderBackend = RenderBackend.Canvas,
    val computeBackend: ComputeBackend = ComputeBackend.Cpu,
)
```

Both new fields default to the safe CPU/Canvas path — no breaking change for any existing caller.
`AdvancedSceneConfig` inherits both fields from `SceneConfig`.

### The async Canvas path

When `computeBackend is SortingComputeBackend`, scene preparation moves off the main thread:

```
[Main thread] rootNode.onDirty → sceneVersion++
    ↓ triggers LaunchedEffect(sceneVersion, canvasWidth, canvasHeight)
[Dispatchers.Default] renderer.prepareAsync()
    └── SceneCache.rebuildAsync()
            └── engine.projectSceneAsync(... computeBackend)
                    ├── GPU: computeBackend.sortByDepthKeys(depthKeys)  ← suspend
                    └── CPU: DepthSorter.refineAdjacentPairs()          ← O(N) correction pass
    ↓ stores result in preparedScene (MutableState<PreparedScene?>, shared with backends)
[Main thread] state change → Canvas redraws → renderer.renderFromScene(preparedScene.value!!)
    └── DrawScope.drawPath() calls — identical to the CPU path
```

The Canvas lambda uses `preparedScene` as its state dependency in both paths:

```kotlin
// Inside the Canvas { } lambda in IsometricScene.kt — same in both CPU and GPU paths:
@Suppress("UNUSED_EXPRESSION") preparedScene.value
// CPU path:  set synchronously inside Canvas after ensurePreparedScene()
// GPU path:  set by LaunchedEffect(sceneVersion) when async prepare completes
// sceneVersion still drives the LaunchedEffect key; preparedScene carries the result.
```

The roles are cleanly separated: `sceneVersion` means "the tree changed, kick off a prepare";
`preparedScene` means "here is a ready-to-draw snapshot". The Canvas only ever depends on
`preparedScene` — no branching between paths, zero overhead for `ComputeBackend.Cpu` callers.

**First-frame behaviour**: `preparedScene.value` is `null` until the first prepare completes.
The Canvas draws nothing on the first frame. This is acceptable — the GPU init + first sort
typically completes within one vsync on a warmed device.

### Internal collaborators (all in `isometric-compose`)

| Type | Change | Purpose |
|---|---|---|
| `SceneCache` | Add `suspend fun rebuildAsync(... computeBackend: SortingComputeBackend)` | Mirrors `rebuild()`, calls `engine.projectSceneAsync()` |
| `IsometricRenderer` | Add `suspend fun prepareAsync(...)` | Orchestrates `rebuildAsync` + hit-test index update off main thread |
| `IsometricRenderer` | Add `fun DrawScope.renderFromScene(scene, strokeStyle)` | Draws a pre-built scene; skips the rebuild step |
| `IsometricRenderer` | Add `fun DrawScope.renderNativeFromScene(scene, strokeStyle)` | Native-canvas variant of `renderFromScene` |

All four are `internal`. The CPU path's `rebuild()`, `render()`, and `renderNative()` are not
touched.

### Internal collaborators (in `isometric-core`)

| Type | Change | Purpose |
|---|---|---|
| `SceneProjector` | Add `suspend fun projectSceneAsync(... computeBackend: SortingComputeBackend): PreparedScene` with default `= projectScene(...)` | Lets `SceneCache.rebuildAsync` call through to the engine's async overload |
| `IsometricEngine` | Override `projectSceneAsync` | Extracts `x + y − 2z` depth keys per face; calls `sortByDepthKeys`; reorders; calls `DepthSorter.refineAdjacentPairs` |
| `DepthSorter` | Add `internal fun refineAdjacentPairs(items, options)` | Single O(N) bubble pass; swaps adjacent pairs where the float-key approximation disagrees with `Path.closerThan` |

### Depth key formula

```
depthKey(face) = average of (pt.x + pt.y − 2 × pt.z) over all 3D vertices of the face
```

From the engine KDoc: `Point.depth = x + y − 2z`. Higher value = further from viewer = drawn first.
This is an approximation — the radix sort gets ~99% of faces in the right order; `refineAdjacentPairs`
corrects the residual set of adjacent swaps.

The float key has a sign-bit issue for negative depth values (faces below the isometric ground
plane). Apply the standard IEEE 754 sign-bit XOR before upload:
```kotlin
// In IsometricEngine.projectSceneAsync, before calling sortByDepthKeys:
val correctedKeys = FloatArray(depthKeys.size) { i ->
    val bits = java.lang.Float.floatToRawIntBits(depthKeys[i])
    java.lang.Float.intBitsToFloat(if (bits < 0) bits.inv() else bits or Int.MIN_VALUE)
}
```
Document whether the engine guarantees non-negative depth keys before deciding whether to apply
this. Most isometric scenes have all geometry above z = 0, making the correction unnecessary.

### `WebGpuComputeBackend` (in `isometric-webgpu`)

```kotlin
class WebGpuComputeBackend : SortingComputeBackend {
    override val isAsync = true

    private val initMutex = Mutex()
    private var gpuContext: GpuContext? = null
    private var initAttempted = false

    // Lazy, mutex-guarded — concurrent coroutines don't race to create multiple GPU devices.
    private suspend fun ensureContext(): GpuContext? = initMutex.withLock {
        if (initAttempted) return@withLock gpuContext
        initAttempted = true
        gpuContext = runCatching { GpuContext.create() }.getOrNull()
        gpuContext
    }

    override suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray {
        val ctx = ensureContext() ?: return cpuFallbackSort(depthKeys)
        return runCatching { GpuDepthSorter(ctx).sortByDepthKeys(depthKeys) }
            .getOrElse { cpuFallbackSort(depthKeys) }
    }

    // CPU fallback when GPU unavailable or errored — descending key order.
    private fun cpuFallbackSort(keys: FloatArray): IntArray =
        keys.indices.sortedByDescending { keys[it] }.toIntArray()
}
```

`GpuContext.create()` is a suspend function (calls `requestAdapter` / `requestDevice` async).
The mutex ensures this runs exactly once regardless of how many concurrent `LaunchedEffect`
coroutines are active at startup.

### Usage

```kotlin
// CPU — synchronous, zero change (default)
IsometricScene { ... }

// GPU compute, Canvas draw — GPU sort, drawPath() unchanged
IsometricScene(config = SceneConfig(computeBackend = ComputeBackend.WebGpu)) { ... }

// GPU compute + WebGPU surface — Phase 2+ only (WS12)
IsometricScene(config = SceneConfig(renderBackend = RenderBackend.WebGpu)) { ... }
```

---

## 5. WS11 — Phase 1: Module Scaffold + GpuDepthSorter

### Scope

Build the `isometric-webgpu` module, establish `GpuContext`, extract `DepthSorter` interface
from `IsometricEngine`, and implement `GpuDepthSorter` with a GPU radix sort + CPU topological
fallback. The Canvas render path is completely unchanged.

**Important constraint**: GPU buffer readback is always async. The callback-based API is
`GPUBuffer.mapAsync(mode, offset, size, executor, callback)`. The suspend (coroutine-friendly)
wrapper is `GPUBuffer.mapAndAwait(mode, offset, size)` — note the name difference. The existing
Canvas render path runs synchronously in `DrawScope`. `GpuDepthSorter` is therefore designed as a
`suspend fun` from day one. It does not integrate with the synchronous Canvas path in WS11 — it
activates in WS12 when the async `AndroidExternalSurface` frame loop provides a natural suspension
point.

### Step 1: Module Scaffold

1. Add `isometric-webgpu/` directory.
2. Write `build.gradle.kts` (as above).
3. Add `:isometric-webgpu` to `settings.gradle.kts`.
4. Create `GpuContext.kt` — stub with `TODO()` bodies.
5. Compile check. Nothing executes yet.

### Step 2: `DepthSorter` Interface Extraction

Extract from `IsometricEngine.kt` into the new interface. This is the only change to
`isometric-compose` in WS11.

```kotlin
// New file: isometric-compose/.../sort/DepthSorter.kt

/**
 * Strategy for ordering projected faces front-to-back for correct painter's algorithm rendering.
 *
 * The default [CpuDepthSorter] implements the existing O(N²) topological sort.
 * [GpuDepthSorter][io.github.jayteealao.isometric.webgpu.sort.GpuDepthSorter] in the `isometric-webgpu`
 * artifact provides a GPU radix sort with CPU topological fallback.
 */
fun interface DepthSorter {
    /**
     * Sort [paths] in place, from back to front (painter's algorithm order).
     *
     * Implementations may be synchronous (CPU) or coroutine-based (GPU). The GPU variant
     * is a `suspend fun` declared in the concrete class — this interface models the CPU contract.
     */
    fun sort(paths: MutableList<Path>)
}
```

```kotlin
// New file: isometric-compose/.../sort/CpuDepthSorter.kt

internal class CpuDepthSorter : DepthSorter {
    override fun sort(paths: MutableList<Path>) {
        // Exact logic moved verbatim from IsometricEngine.sortPaths() lines 278-335
        // No behaviour change.
    }
}
```

`IsometricEngine.sortPaths()` becomes a one-line delegation:
```kotlin
private fun sortPaths(paths: MutableList<Path>) = cpuDepthSorter.sort(paths)
private val cpuDepthSorter = CpuDepthSorter()
```

### Step 3: `GpuContext` — Device Lifecycle

```kotlin
// isometric-webgpu/.../GpuContext.kt

import android.os.Handler
import android.os.Looper
import androidx.webgpu.BackendType
import androidx.webgpu.GPU
import androidx.webgpu.GPUAdapter
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUInstance
import androidx.webgpu.GPUQueue
import androidx.webgpu.GPURequestAdapterOptions
import androidx.webgpu.PowerPreference
import androidx.webgpu.helper.Util.initLibrary   // System.loadLibrary("webgpu_c_bundled")

class GpuContext private constructor(
    val instance: GPUInstance,
    val adapter: GPUAdapter,
    val device: GPUDevice,
    val queue: GPUQueue,
    private val eventHandler: Handler,
) {
    companion object {
        private const val POLLING_DELAY_MS = 100L

        /**
         * Initialise the GPU context. Safe to call from any coroutine context.
         *
         * API notes:
         *   - Entry point is [GPU.createInstance], NOT GPUInstance() constructor.
         *   - [initLibrary] must run before any androidx.webgpu call.
         *   - [GPUInstance.requestAdapter] and [GPUAdapter.requestDevice] are suspend functions
         *     that throw [WebGpuException] on failure — no null return.
         *   - A GPUAdapter can only produce one GPUDevice; never destroy and recreate from the same adapter.
         *   - [GPUInstance.processEvents] must be polled periodically (Dawn does not self-drive
         *     its async callback loop). Use a Handler on the main thread, ~100ms interval.
         */
        suspend fun create(): GpuContext {
            initLibrary()                                // loads "webgpu_c_bundled" native lib
            val instance = GPU.createInstance()         // ← GPU companion object, not GPUInstance()
            val adapter = instance.requestAdapter(      // suspend; throws WebGpuException on failure
                GPURequestAdapterOptions(
                    powerPreference = PowerPreference.HighPerformance,  // ← PowerPreference, not GPUPowerPreference
                    backendType = BackendType.Vulkan
                )
            )
            val device = adapter.requestDevice()        // suspend; throws WebGpuException on failure

            // Dawn requires instance.processEvents() to be polled for async callbacks to fire.
            // The handler keeps running until destroy() is called.
            var isClosing = false
            val handler = Handler(Looper.getMainLooper())
            fun scheduleProcess() {
                handler.postDelayed({
                    if (!isClosing) { instance.processEvents(); scheduleProcess() }
                }, POLLING_DELAY_MS)
            }
            scheduleProcess()

            return GpuContext(instance, adapter, device, device.queue, handler)
        }
    }

    fun destroy() {
        // device.destroy() — NOTE: after destroy(), adapter.requestDevice() will throw;
        // GpuContext is single-use. Recreate GpuContext (new instance+adapter+device) if needed.
        device.destroy()
        instance.close()   // ← AutoCloseable.close(), NOT instance.release()
        adapter.close()
    }
}
```

### Step 4: WGSL Radix Sort Shader

32-bit floating-point radix sort. Four passes (8 bits per pass). Each pass:
1. Count histogram of the current byte
2. Prefix sum (exclusive scan) over histogram
3. Scatter keys to output buffer

```kotlin
// isometric-webgpu/.../shader/RadixSortShader.kt

internal object RadixSortShader {
    // Struct mirrors Kotlin's ProjectedFace
    val WGSL = """
        struct SortKey {
            depth: f32,
            originalIndex: u32,
        }

        @group(0) @binding(0) var<storage, read>       keys_in:   array<SortKey>;
        @group(0) @binding(1) var<storage, read_write>  keys_out:  array<SortKey>;
        @group(0) @binding(2) var<storage, read_write>  histogram: array<atomic<u32>>;
        @group(0) @binding(3) var<uniform>              params:    RadixParams;

        struct RadixParams {
            count:     u32,
            bitShift:  u32,  // 0, 8, 16, 24 for the four passes
        }

        const RADIX: u32 = 256u;

        @compute @workgroup_size(256)
        fn countPass(@builtin(global_invocation_id) id: vec3<u32>) {
            if (id.x >= params.count) { return; }
            let key = keys_in[id.x];
            // Reinterpret float bits as u32 for bit-extraction
            let bits = bitcast<u32>(key.depth);
            let bucket = (bits >> params.bitShift) & 0xFFu;
            atomicAdd(&histogram[bucket], 1u);
        }

        @compute @workgroup_size(256)
        fn scatterPass(@builtin(global_invocation_id) id: vec3<u32>) {
            if (id.x >= params.count) { return; }
            let key = keys_in[id.x];
            let bits = bitcast<u32>(key.depth);
            let bucket = (bits >> params.bitShift) & 0xFFu;
            let pos = atomicAdd(&histogram[bucket], 1u);
            keys_out[pos] = key;
        }
    """.trimIndent()
}
```

> **Note on float sort correctness**: IEEE 754 floats sort correctly as u32 bit-patterns for positive
> values (all projected Z depths are positive for above-origin isometric scenes). For scenes with
> negative depths, apply the standard sign-bit XOR trick before uploading keys:
> `if (bits & 0x80000000u) != 0u { bits = bits ^ 0xFFFFFFFFu } else { bits = bits ^ 0x80000000u }`.
> This maps floats to a u32 space that sorts identically. Document whether the engine guarantees
> positive depths before deciding whether to apply this.

### Step 5: `GpuDepthSorter`

```kotlin
// isometric-webgpu/.../sort/GpuDepthSorter.kt

class GpuDepthSorter(private val ctx: GpuContext) {

    private val shaderModule: GPUShaderModule by lazy {
        // GPUShaderModuleDescriptor has NO 'code' field. WGSL source is nested inside
        // GPUShaderSourceWGSL. This is a common footgun when migrating from web WebGPU examples.
        ctx.device.createShaderModule(
            GPUShaderModuleDescriptor(
                shaderSourceWGSL = GPUShaderSourceWGSL(code = RadixSortShader.WGSL)
            )
        )
    }

    /**
     * Sort [paths] by projected depth using GPU radix sort for the common case and
     * CPU topological sort for genuinely ambiguous pairs.
     *
     * Must be called from a coroutine context (GPU readback is always async).
     */
    suspend fun sort(paths: MutableList<Path>) {
        if (paths.size < GPU_SORT_THRESHOLD) {
            cpuFallback.sort(paths)
            return
        }

        val depthKeys = buildDepthKeys(paths)          // Upload depth centroids to GPU
        val sortedIndices = gpuRadixSort(depthKeys)    // Four-pass radix sort
        val reordered = sortedIndices.map { paths[it] }.toMutableList()

        // CPU topological refinement: only for adjacent pairs within AMBIGUITY_THRESHOLD
        refinedTopoSort(reordered)

        paths.clear()
        paths.addAll(reordered)
    }

    private suspend fun gpuRadixSort(keys: FloatArray): IntArray {
        // 1. Create input/output buffers (ping-pong)
        // 2. For each of 4 passes (bitShift = 0, 8, 16, 24):
        //    a. Zero histogram buffer
        //    b. Dispatch countPass
        //    c. CPU prefix sum on histogram readback (or GPU scan — use CPU in Phase 1 for simplicity)
        //    d. Upload prefix-summed histogram
        //    e. Dispatch scatterPass
        //    f. Swap ping-pong buffers
        // 3. mapAndAwait output buffer → return sorted originalIndex array
        //    NOTE: the coroutine-friendly suspend version is GPUBuffer.mapAndAwait(),
        //    NOT mapAsync() which is the callback-based version.
        TODO("Implement in WS11 Step 5")
    }

    /**
     * CPU topological refinement for ambiguous pairs.
     * Runs only on pairs within [AMBIGUITY_DEPTH_THRESHOLD] of each other in the sorted order.
     * Typical scene: 0–10 pairs even at N=1000.
     */
    private fun refinedTopoSort(paths: MutableList<Path>) {
        for (i in 0 until paths.size - 1) {
            val a = paths[i]
            val b = paths[i + 1]
            if (abs(a.depthCentroid - b.depthCentroid) < AMBIGUITY_DEPTH_THRESHOLD) {
                if (b.closerThan(a)) {
                    paths[i] = b
                    paths[i + 1] = a
                }
            }
        }
        // For larger ambiguous regions (rare), run a bounded topological sort on the subrange.
    }

    private fun buildDepthKeys(paths: List<Path>): FloatArray =
        FloatArray(paths.size) { i -> paths[i].depthCentroid.toFloat() }

    private val cpuFallback = CpuDepthSorter()

    companion object {
        private const val GPU_SORT_THRESHOLD = 64  // Below this, CPU is faster than GPU dispatch overhead
        private const val AMBIGUITY_DEPTH_THRESHOLD = 0.001f
    }
}
```

**`Path.depthCentroid`**: The projected Z-centroid of the face. If not already cached, add it:

```kotlin
// In Path.kt — computed once at projection time, cached
var depthCentroid: Double = 0.0
    internal set
```

Set in `IsometricEngine.prepare()` during the existing projection step, before `sortPaths()` is called. This is a free computation already implicit in the existing code.

### WS11 Deliverables

- `isometric-webgpu` module compiles cleanly
- `ComputeBackend` + `SortingComputeBackend` interfaces in `isometric-core` (see Section 4)
- `WebGpuComputeBackend` + `ComputeBackend.Companion.WebGpu` in `isometric-webgpu`
- `SceneConfig` gains `computeBackend: ComputeBackend = ComputeBackend.Cpu` and `renderBackend: RenderBackend = RenderBackend.Canvas` fields; `IsometricScene` parameter list unchanged
- Async `LaunchedEffect` path + `renderer.prepareAsync()` + `renderFromScene()` wired in `IsometricScene`
- `SceneCache.rebuildAsync()` and `IsometricEngine.projectSceneAsync()` implemented
- `DepthSorter.refineAdjacentPairs()` implemented
- `RenderBackend` interface + `CanvasRenderBackend` in `isometric-compose`
- `GpuContext`, `GpuDepthSorter`, `RadixSortShader` implemented; `GpuDepthSorter.sortByDepthKeys()` wired into `WebGpuComputeBackend`
- `IsometricScene(config = SceneConfig(computeBackend = ComputeBackend.WebGpu))` works end-to-end on a physical device
- Existing Canvas benchmarks pass without regression
- Unit tests: `GpuDepthSorter` with `n < GPU_SORT_THRESHOLD` delegates to CPU sorter; shader WGSL parses without error on a real device

---

## 6. WS12 — Phase 2: WebGPU Isometric Renderer

### Scope

Implement the full `WebGpuRenderBackend` — `AndroidExternalSurface` embedding, vertex buffer with
UV-forward layout, WGSL vertex/fragment shaders, triangle fan triangulation, the invisible Compose
overlay for touch events, and surface resize handling. The `GpuDepthSorter` from WS11 activates here
inside the async frame loop.

### Step 1: Vertex Buffer Layout

The Phase 2 vertex layout carries position, color, and UV coordinates. UV coords are reserved
for Phase 3 texture support; `textureIndex` is **not** included in Phase 2 — it will be added in
Phase 3 once the texture API design is complete. This avoids coupling `RenderCommand` to texture
plans before any texture API exists.

```wgsl
// In IsometricVertexShader.kt

struct Vertex {
    @location(0) position: vec2<f32>,   // screen-space XY, pixels
    @location(1) color:    vec4<f32>,   // RGBA (IsoColor → [0,1] floats)
    @location(2) uv:       vec2<f32>,   // reserved for Phase 3 texture sampling
}

struct VertexOutput {
    @builtin(position) clipPosition: vec4<f32>,
    @location(0) color: vec4<f32>,
    @location(1) uv:    vec2<f32>,
}

struct Uniforms {
    viewportSize: vec2<f32>,   // surface width × height in pixels
    _padding:     vec2<f32>,
}

@group(0) @binding(0) var<uniform> uniforms: Uniforms;

@vertex
fn vertexMain(in: Vertex) -> VertexOutput {
    // Convert pixel coords to NDC: x in [-1, 1], y in [-1, 1] (WebGPU: y-up)
    let ndc = vec2<f32>(
        (in.position.x / uniforms.viewportSize.x) * 2.0 - 1.0,
        1.0 - (in.position.y / uniforms.viewportSize.y) * 2.0
    );
    var out: VertexOutput;
    out.clipPosition = vec4<f32>(ndc, 0.0, 1.0);
    out.color        = in.color;
    out.uv           = in.uv;
    return out;
}
```

```wgsl
// In IsometricFragmentShader.kt (Phase 2 — solid color only)

@fragment
fn fragmentMain(in: VertexOutput) -> @location(0) vec4<f32> {
    // Phase 2: solid color only; return vertex color directly.
    // Phase 3 will add a textureIndex field and atlas sampler.
    return in.color;
}
```

### Step 2: `RenderCommandTriangulator`

All isometric faces are convex polygons (guaranteed by orthographic projection of convex 3D faces).
A convex polygon triangulates correctly as a triangle fan from vertex 0.

```kotlin
// isometric-webgpu/.../triangulation/RenderCommandTriangulator.kt

internal object RenderCommandTriangulator {

    /**
     * Convert a list of [RenderCommand]s (N-gon polygons) into a flat vertex array
     * ready for upload to a GPU vertex buffer.
     *
     * Output vertex format (bytes per vertex = 32):
     *   position(8) + color(16) + uv(8)
     *
     * `textureIndex` is deferred to Phase 3.
     */
    fun triangulate(commands: List<RenderCommand>): FloatArray {
        val vertices = mutableListOf<Float>()
        for (cmd in commands) {
            val pts = cmd.polygonPoints
            val color = cmd.color.toGpuColor()  // IsoColor → vec4<f32>
            val n = pts.size
            if (n < 3) continue

            // Triangle fan from vertex 0: (0,1,2), (0,2,3), ..., (0,n-2,n-1)
            val uvs = defaultUvs(n)
            for (i in 1 until n - 1) {
                appendVertex(vertices, pts[0],   color, uvs[0])
                appendVertex(vertices, pts[i],   color, uvs[i])
                appendVertex(vertices, pts[i+1], color, uvs[i+1])
            }
        }
        return vertices.toFloatArray()
    }

    /** Standard UV layout for a convex N-gon: vertices distributed evenly on a unit circle. */
    private fun defaultUvs(n: Int): Array<PointF> = Array(n) { i ->
        val angle = (2.0 * PI * i / n).toFloat()
        PointF(0.5f + 0.5f * cos(angle), 0.5f + 0.5f * sin(angle))
    }

    private fun appendVertex(
        out: MutableList<Float>,
        pt: PointF,
        color: FloatArray,   // [r, g, b, a]
        uv: PointF,
    ) {
        out += pt.x; out += pt.y
        out += color[0]; out += color[1]; out += color[2]; out += color[3]
        out += uv.x; out += uv.y
    }
}
```

**No `RenderCommand` changes in WS12.** `textureIndex` is deferred to Phase 3, when the full
texture API (atlas layout, sampler binding, shader) is designed together. Adding an unused field
to `RenderCommand` now would couple Phase 2 to undesigned Phase 3 API surface.

### Step 3: `WebGpuRenderBackend`

```kotlin
// isometric-webgpu/.../RenderBackendWebGpu.kt

class WebGpuRenderBackend(
    private val gpuContext: GpuContext,
) : RenderBackend {

    @Composable
    override fun Surface(
        preparedScene: State<PreparedScene?>,
        renderContext: RenderContext,
        modifier: Modifier,
        strokeStyle: StrokeStyle,   // Phase 2: ignored — WebGPU draws filled faces only
    ) {
        val gpuDepthSorter = remember { GpuDepthSorter(gpuContext) }
        val renderer = remember { WebGpuSceneRenderer(gpuContext, gpuDepthSorter) }

        // No rootNode access. IsometricScene owns the prepare lifecycle and publishes
        // immutable PreparedScene snapshots via the preparedScene state parameter.
        // No onDirty wiring — that stays in IsometricScene's DisposableEffect.
        // No hit testing — that stays in IsometricScene's pointer-input block.

        Box(modifier = modifier) {
            // ── WebGPU surface layer ─────────────────────────────────────────────────
            AndroidExternalSurface(modifier = Modifier.fillMaxSize()) {
                onSurface { surface, width, height ->
                    // init() is safe to offload — no frame-clock dependency.
                    withContext(Dispatchers.Default) { renderer.init(surface, width, height) }

                    // IMPORTANT: withFrameNanos MUST remain on the frame-clock coroutine
                    // provided by AndroidExternalSurface's onSurface block. Never wrap
                    // the entire frame loop in withContext(Dispatchers.Default) —
                    // withFrameNanos requires the Choreographer-backed coroutine context
                    // that onSurface provides, and will deadlock or miss frames otherwise.
                    try {
                        var lastSnapshot: PreparedScene? = null
                        var surfaceLost = false
                        while (!surfaceLost) {
                            withFrameNanos { _ ->
                                val snapshot = preparedScene.value
                                if (snapshot != null && snapshot !== lastSnapshot) {
                                    // Triangulation + GPU upload are CPU/GPU-bound; offload them.
                                    withContext(Dispatchers.Default) {
                                        renderer.updateGeometry(snapshot)
                                    }
                                    lastSnapshot = snapshot
                                }
                                // drawFrame() returns true when the surface is irretrievably lost.
                                // The loop exits; AndroidExternalSurface will re-deliver onSurface.
                                surfaceLost = renderer.drawFrame()
                            }
                        }
                    } finally {
                        renderer.destroy()
                    }
                }

                onSurfaceChanged { width, height ->
                    renderer.resize(width, height)
                }
            }

            // ── Hit testing ──────────────────────────────────────────────────────────
            // The WebGPU backend does NOT own hit testing. IsometricScene's existing
            // pointer-input block (with camera-inverse transform — see IsometricScene.kt
            // lines 332–343) handles all tap/drag events and routes them through
            // renderer.hitTest() (instance method on IsometricRenderer).
            //
            // AndroidExternalSurface creates a SurfaceView that sits in a separate window
            // layer and captures pointer events before they reach the Compose tree. The
            // zero-alpha overlay Box MUST always be present for WebGPU backends — it is not
            // optional. See Step 5 for the coordinate transform contract.
            //
            // Zero-alpha overlay — always present:
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset -> /* see Step 5 for transform */ }
                    }
            )
        }
    }
}
```

### Step 3b: WebGPU Object Lifetime Ownership

Every `GPUInstance`, `GPUAdapter`, `GPUDevice`, `GPUSurface`, and buffer is `AutoCloseable`.
`close()` decrements the native refcount; GPU buffers additionally need `destroy()` to invalidate
the allocation immediately. The table below defines who creates, who closes, and what survives a
surface resize.

| Object | Created by | Owner | Closed/destroyed by | Survives resize? |
|---|---|---|---|---|
| `GPUInstance` | `GPU.createInstance()` in `GpuContext.create()` | `GpuContext` | `GpuContext.close()` | Yes |
| `GPUAdapter` | `instance.requestAdapter()` in `GpuContext.create()` | `GpuContext` | `GpuContext.close()` | Yes |
| `GPUDevice` | `adapter.requestDevice()` in `GpuContext.create()` | `GpuContext` | `GpuContext.close()` | Yes — one device per adapter lifetime |
| `GPUQueue` | `device.queue` property | `GpuContext` (transitively) | closed with `GPUDevice` | Yes |
| `GPUSurface` | `WebGpuSceneRenderer.init()` | `WebGpuSceneRenderer` | `WebGpuSceneRenderer.destroy()` | Reconfigured, not recreated |
| `GPURenderPipeline` | `WebGpuSceneRenderer.buildPipeline()` | `WebGpuSceneRenderer` | `WebGpuSceneRenderer.destroy()` | Yes |
| `GPUBuffer` (uniform) | `WebGpuSceneRenderer.buildUniformBuffer()` | `WebGpuSceneRenderer` | `WebGpuSceneRenderer.destroy()` | Yes |
| `GPUBuffer` (vertex) | `WebGpuSceneRenderer.updateGeometry()` | `WebGpuSceneRenderer` | `destroy()` + `close()` in `updateGeometry()` (on grow) and `WebGpuSceneRenderer.destroy()` | Old buffer destroyed when capacity is exceeded |
| `GPUCommandEncoder` | per frame in `drawFrame()` | transient | consumed by `encoder.finish()` + `queue.submit()` — do **not** call `close()` after submit | N/A — one per frame |
| `GPUBindGroup` | `WebGpuSceneRenderer.buildUniformBuffer()` | `WebGpuSceneRenderer` | `WebGpuSceneRenderer.destroy()` | Yes |
| `GPUShaderModule` | `buildPipeline()` | `WebGpuSceneRenderer` | can be `close()`d after pipeline creation | N/A — used only during init |

**Rule**: `GpuContext` outlives the renderer. Pass it in via constructor; never close it in
`WebGpuSceneRenderer.destroy()`. The composable that owns `GpuContext` (via `remember`) is
responsible for calling `ctx.close()` in a `DisposableEffect` cleanup.

### Step 4: `WebGpuSceneRenderer`

```kotlin
// isometric-webgpu/.../WebGpuSceneRenderer.kt

// Labeling contract: every named GPU object must call setLabel() immediately after creation.
// Labels appear in GPU validation errors, Android GPU Inspector, and crash stacks.
// Format: "IsometricScene/<objectRole>" — e.g. "IsometricScene/vertexBuffer".
// GPURenderPassEncoder uses pushDebugGroup/popDebugGroup around logical draw groups.
internal class WebGpuSceneRenderer(
    private val ctx: GpuContext,
    private val depthSorter: GpuDepthSorter,
) {
    private lateinit var gpuSurface: GPUSurface
    private lateinit var pipeline: GPURenderPipeline
    private lateinit var uniformBuffer: GPUBuffer
    private lateinit var vertexBuffer: GPUBuffer
    private lateinit var uniformBindGroup: GPUBindGroup

    var cacheValid: Boolean = false
        private set
    private var vertexCount: Int = 0

    // Surface dimensions — kept in sync by init() and resize(). Used by reconfigure()
    // so drawFrame() can reconfigure without needing the caller to pass dimensions.
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0

    // Set true when getCurrentTexture() returns SuccessSuboptimal.
    // Causes reconfigure() at the top of the *next* drawFrame() before acquiring a new texture.
    private var needsReconfigure: Boolean = false

    // Reusable direct ByteBuffer for vertex uploads. Allocated once; grown only when the
    // vertex count exceeds the previous peak. Avoids per-frame allocation and GC pressure.
    private var stagingBuffer: java.nio.ByteBuffer? = null

    fun init(surface: Surface, width: Int, height: Int) {
        currentWidth = width
        currentHeight = height

        // Surface creation requires an ANativeWindow pointer obtained via Util.windowFromSurface.
        // ctx.instance.createSurface(surface) does NOT work — there is no overload that takes
        // an Android Surface directly. The surface must be wrapped via GPUSurfaceDescriptor.
        val nativeWindow = androidx.webgpu.helper.Util.windowFromSurface(surface)
        gpuSurface = ctx.instance.createSurface(
            GPUSurfaceDescriptor(
                surfaceSourceAndroidNativeWindow = GPUSurfaceSourceAndroidNativeWindow(nativeWindow)
            )
        )
        gpuSurface.setLabel("IsometricScene/gpuSurface")

        // Determine the best supported format for this surface+adapter combination.
        // Hardcoding TextureFormat.BGRA8Unorm is fragile — query capabilities first.
        // All type names are IntDef annotation classes (no GPU prefix):
        //   TextureFormat (not GPUTextureFormat), TextureUsage (not GPUTextureUsage),
        //   CompositeAlphaMode (not GPUCompositeAlphaMode), LoadOp (not GPULoadOp), etc.
        val caps = gpuSurface.getCapabilities(ctx.adapter)
        val format = if (TextureFormat.BGRA8Unorm in caps.formats) {
            TextureFormat.BGRA8Unorm
        } else {
            caps.formats.first()   // fall back to first supported format
        }

        gpuSurface.configure(
            GPUSurfaceConfiguration(
                device = ctx.device,
                format = format,           // TextureFormat.BGRA8Unorm, not GPUTextureFormat.*
                width = width,
                height = height,
                usage = TextureUsage.RenderAttachment,  // TextureUsage, not GPUTextureUsage
                alphaMode = CompositeAlphaMode.Opaque,  // CompositeAlphaMode, not GPUCompositeAlphaMode
            )
        )
        buildPipeline(format)
        buildUniformBuffer(width.toFloat(), height.toFloat())
    }

    private var surfaceFormat: Int = TextureFormat.Undefined

    fun resize(width: Int, height: Int) {
        currentWidth = width
        currentHeight = height
        reconfigure(width, height)
        uploadViewportSize(width.toFloat(), height.toFloat())
        cacheValid = false
    }

    // Shared by resize() and the drawFrame() status-handling paths.
    private fun reconfigure(width: Int, height: Int) {
        gpuSurface.configure(
            GPUSurfaceConfiguration(
                device = ctx.device,
                format = surfaceFormat,
                width = width,
                height = height,
                usage = TextureUsage.RenderAttachment,
                alphaMode = CompositeAlphaMode.Opaque,
            )
        )
    }

    suspend fun updateGeometry(scene: PreparedScene) {
        // PreparedScene is an immutable snapshot produced by SceneCache.rebuild()
        // on the Compose side. Its field is PreparedScene.commands: List<RenderCommand>
        // (see isometric-core PreparedScene.kt). Safe to read from any coroutine context.

        // 1. GPU depth-sort the commands (radix sort activates here)
        val sortedCommands = scene.commands.toMutableList()
        depthSorter.sort(sortedCommands)  // suspend — runs GPU radix sort

        // 2. Triangulate sorted commands → flat float array
        val vertexData = RenderCommandTriangulator.triangulate(sortedCommands)
        vertexCount = vertexData.size / FLOATS_PER_VERTEX

        // 3. Upload to GPU vertex buffer
        //    writeBuffer takes ByteBuffer, not FloatArray — must convert.
        //    BufferUsage.Vertex | BufferUsage.CopyDst  (IntDef flag int, no GPU prefix)
        val byteSize = (vertexData.size * 4).toLong()
        if (!::vertexBuffer.isInitialized || vertexBuffer.size < byteSize) {
            if (::vertexBuffer.isInitialized) {
                vertexBuffer.destroy()
                vertexBuffer.close()
            }
            vertexBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    size = byteSize,
                    usage = BufferUsage.Vertex or BufferUsage.CopyDst,  // BufferUsage, not GPUBufferUsage
                )
            )
            vertexBuffer.setLabel("IsometricScene/vertexBuffer")
            // Invalidate staging buffer — new vertex buffer may need a larger capacity.
            stagingBuffer = null
        }
        // Reuse the staging ByteBuffer if it is large enough; allocate a new one otherwise.
        // GPUQueue.writeBuffer() requires a direct ByteBuffer (not FloatArray or heap buffer).
        val staging = stagingBuffer?.takeIf { it.capacity() >= byteSize.toInt() }
            ?: java.nio.ByteBuffer
                .allocateDirect(byteSize.toInt())
                .order(java.nio.ByteOrder.nativeOrder())
                .also { stagingBuffer = it }
        staging.clear()
        staging.asFloatBuffer().put(vertexData)
        staging.rewind()
        ctx.queue.writeBuffer(vertexBuffer, 0L, staging)
        cacheValid = true
    }

    /**
     * Draws a single frame. Returns `true` if the surface is irretrievably lost and the frame
     * loop should exit — [AndroidExternalSurface] will issue a new `onSurface` callback when
     * the system provides a replacement surface.
     */
    fun drawFrame(): Boolean {
        if (vertexCount == 0) return false

        // Reconfigure eagerly if the previous frame flagged SuccessSuboptimal.
        // Done before acquiring the next texture so the new config takes effect immediately.
        if (needsReconfigure) {
            reconfigure(currentWidth, currentHeight)
            needsReconfigure = false
        }

        // getCurrentTexture() returns GPUSurfaceTexture (non-null), with a .status field.
        // Branch on status BEFORE touching the texture — several states are not safe to render.
        val surfaceTexture = gpuSurface.getCurrentTexture()
        when (surfaceTexture.status) {
            SurfaceGetCurrentTextureStatus.SuccessOptimal -> { /* render normally below */ }
            SurfaceGetCurrentTextureStatus.SuccessSuboptimal -> {
                // Texture is still renderable; render this frame then reconfigure before the next.
                needsReconfigure = true
            }
            SurfaceGetCurrentTextureStatus.Outdated -> {
                // Surface config no longer matches the physical surface (e.g. after rotation).
                // Reconfigure now and skip this frame; next frame will have a valid texture.
                reconfigure(currentWidth, currentHeight)
                return false
            }
            SurfaceGetCurrentTextureStatus.Lost -> {
                // The surface cannot be recovered in-place. Signal the frame loop to exit.
                // AndroidExternalSurface will deliver a new onSurface callback when the system
                // provides a replacement surface — init() will be called again at that point.
                return true
            }
            SurfaceGetCurrentTextureStatus.Timeout,
            SurfaceGetCurrentTextureStatus.Error -> {
                // Cannot safely use this texture. Skip the frame without presenting.
                return false
            }
        }

        val encoder = ctx.device.createCommandEncoder()  // descriptor optional, can omit
        encoder.setLabel("IsometricScene/frameEncoder")
        val renderPass = encoder.beginRenderPass(
            GPURenderPassDescriptor(
                // colorAttachments is Array<GPURenderPassColorAttachment>, NOT List — use arrayOf()
                colorAttachments = arrayOf(
                    GPURenderPassColorAttachment(
                        clearValue = GPUColor(r = 0.0, g = 0.0, b = 0.0, a = 0.0),
                        view = surfaceTexture.texture.createView(),
                        loadOp = LoadOp.Clear,    // LoadOp (IntDef), not GPULoadOp
                        storeOp = StoreOp.Store,  // StoreOp (IntDef), not GPUStoreOp
                    )
                )
            )
        )
        renderPass.setLabel("IsometricScene/renderPass")
        renderPass.pushDebugGroup("IsometricScene: draw faces")
        renderPass.setPipeline(pipeline)
        renderPass.setBindGroup(0, uniformBindGroup)
        renderPass.setVertexBuffer(0, vertexBuffer)
        renderPass.draw(vertexCount)
        renderPass.popDebugGroup()
        renderPass.end()

        // queue.submit() takes Array<GPUCommandBuffer>, NOT List — use arrayOf()
        ctx.queue.submit(arrayOf(encoder.finish()))
        // present() pushes the rendered frame to the display surface.
        // surfaceTexture.texture.destroy() would discard the frame — do NOT call it here.
        gpuSurface.present()
        return false
    }

    // No hitTest() method. Hit testing is owned by IsometricScene via
    // IsometricRenderer.hitTest(rootNode, x, y, context, width, height) — an instance
    // method on the renderer, not a static helper. The WebGPU backend does not
    // participate in hit testing in any phase.

    /**
     * Releases all GPU resources owned by this renderer.
     *
     * Destroy order: buffers → bind group → pipeline → surface.
     * For GPU buffers: call destroy() to invalidate the allocation immediately, then close()
     * to decrement the native refcount. Both are required to avoid leaks.
     * GpuContext (device/adapter/instance) is owned externally and must NOT be closed here.
     */
    fun destroy() {
        stagingBuffer = null  // release the JVM-side direct buffer
        if (::vertexBuffer.isInitialized) {
            vertexBuffer.destroy()
            vertexBuffer.close()
        }
        uniformBuffer.destroy()
        uniformBuffer.close()
        uniformBindGroup.close()
        pipeline.close()
        gpuSurface.unconfigure()
        gpuSurface.close()
    }

    private fun buildPipeline() {
        // Create GPURenderPipeline from vertex/fragment shaders.
        // Label the pipeline and shader module for GPU debugger identification:
        //   shaderModule.setLabel("IsometricScene/shaderModule")
        //   pipeline.setLabel("IsometricScene/renderPipeline")
    }
    private fun buildUniformBuffer(width: Float, height: Float) {
        // Create uniform GPUBuffer and GPUBindGroup; label both for debuggability:
        //   uniformBuffer.setLabel("IsometricScene/uniformBuffer")
        //   uniformBindGroup.setLabel("IsometricScene/uniformBindGroup")
    }
    private fun uploadViewportSize(width: Float, height: Float) { /* writeBuffer to uniformBuffer */ }

    companion object {
        private const val FLOATS_PER_VERTEX = 8  // xy(2) + rgba(4) + uv(2)
    }
}
```

### Step 5: Touch Bridge — Pointer Coordinate Contract

**Ownership**: Hit testing is owned by `IsometricScene`, not by the backend. The existing
pointer-input block in `IsometricScene.kt` (lines 278–383) handles all tap/drag events and
calls `renderer.hitTest(rootNode, x, y, context, width, height)` — an **instance method** on
`IsometricRenderer`, not a static helper. The WebGPU backend does not duplicate this logic.

`AndroidExternalSurface` creates a `SurfaceView` in a separate window layer that intercepts
pointer events before they reach the Compose tree. The zero-alpha overlay Box is therefore
**mandatory** for all WebGPU backends — it is not conditional on whether pointer input
"seems to work" without it. The overlay captures events in **Compose layout coordinates (dp)**, while `IsometricRenderer.hitTest()`
operates in **surface pixel coordinates** with camera pan/zoom applied. Two sequential
transforms are required before forwarding coordinates to the hit-test path:

**Transform 1 — dp → px**: Multiply by `LocalDensity.current.density`.

**Transform 2 — camera inverse**: When `AdvancedSceneConfig.cameraState` is non-null the scene
is panned and/or zoomed. Hit coordinates must be inverse-transformed to match where the engine
placed the projected geometry. The formula is reproduced verbatim from `IsometricScene.kt`
(lines 332–343):

```kotlin
// Inside the overlay's detectTapGestures { onTap = { offset -> ... } } block:
val density = LocalDensity.current.density
val rawX = offset.x * density   // dp → px
val rawY = offset.y * density

val hitX: Float
val hitY: Float
if (camera != null) {
    val cx = surfaceWidthPx / 2.0
    val cy = surfaceHeightPx / 2.0
    hitX = ((rawX - cx - camera.panX) / camera.zoom + cx).toFloat()
    hitY = ((rawY - cy - camera.panY) / camera.zoom + cy).toFloat()
} else {
    hitX = rawX
    hitY = rawY
}
val hit = renderer.hitTest(
    rootNode = rootNode,
    x = hitX.toDouble(),
    y = hitY.toDouble(),
    context = renderContext,
    width = surfaceWidthPx,
    height = surfaceHeightPx,
)
```

`surfaceWidthPx` / `surfaceHeightPx` are the pixel dimensions reported by the most recent
`onSurface` or `onSurfaceChanged` callback. Store them as `var` fields on `WebGpuRenderBackend`
and keep them in sync with `resize()`.

Add an alignment assertion in debug builds to catch size mismatches between the overlay and the
surface:

```kotlin
if (BuildConfig.DEBUG) {
    check(surfaceWidthPx == (layoutWidthDp * density).roundToInt()) {
        "Surface pixel width does not match layout pixel width — check overlay alignment"
    }
}
```

### WS12 Deliverables

- `RenderBackend.WebGpu` available when `isometric-webgpu` is on classpath
- `IsometricScene(config = SceneConfig(renderBackend = RenderBackend.WebGpu))` renders all existing demo scenes
- `GpuDepthSorter` active in the Phase 2 async frame loop
- Vertex buffer layout fixed: xy, rgba, uv (8 floats/vertex; `textureIndex` deferred to Phase 3)
- `onSurfaceChanged` / resize handled correctly (no black screens on rotation)
- `GPUSurfaceTexture.status` checked on every `drawFrame()` call: `Suboptimal` triggers deferred reconfigure, `Outdated` reconfigures immediately, `Lost` exits the frame loop cleanly
- GPU object lifetime table (Step 3b) implemented: every `GPUBuffer` is `destroy()`ed then `close()`d; `GPUSurface` and pipeline objects are `close()`d in `destroy()`; `GpuContext` is not closed by the renderer
- Vertex upload path uses a reusable staging `ByteBuffer` — no per-frame direct-buffer allocation
- Zero-alpha overlay Box is always present in `WebGpuRenderBackend.Surface()` — not conditional (see Step 5)
- Every named GPU object carries a `setLabel("IsometricScene/<role>")` call; render passes use `pushDebugGroup`/`popDebugGroup`
- Hit testing stays in `IsometricScene` (not in the backend); overlay forwards events with camera-inverse transform (see Step 5)
- `onTap`, per-node `onClick` (WS10), and `TileGestureHub` route unchanged through existing `IsometricScene` pointer-input block
- Benchmarks: measure draw-path frame time on 1000-face scene against Canvas baseline (~22–29ms); sub-5ms is the target hypothesis, not a guaranteed outcome — Phase 2 still includes CPU triangulation, `ByteBuffer` packing, and buffer uploads that may dominate on some devices
- Physical device CI: at least Pixel 6 (Adreno 650, Vulkan 1.1) + one Mali device

---

## 7. WS13 — Phase 3: Full GPU-Driven Pipeline

### Scope

Move the entire `prepare()` pipeline onto the GPU. After WS13, the CPU only submits one command
buffer per dirty frame. All transform, cull, sort, light, and draw operations happen on GPU cores
in parallel.

### Architecture

```
[CPU] rootNode.renderTo(commands, renderContext) → flat SceneData buffer (faces × attributes)
    ↓ uploadOnce per dirty frame
[GPU] Compute: parallel 3D→2D vertex transform     (N faces × M vertices, all simultaneously)
    ↓ output: TransformedFace buffer
[GPU] Compute: back-face culling + frustum culling  (N parallel invocations)
    ↓ output: VisibleFaceIndices (compacted via prefix sum)
[GPU] Compute: radix sort by depth key              (visible face count, 4 passes)
    ↓ output: sorted VisibleFaceIndices
[GPU] Compute: lighting (dot product with light direction, writes final color per face)
    ↓ output: LitColorBuffer
[GPU] Compute: populate indirect draw buffer        (1 invocation per visible face)
    ↓ output: GPUBuffer for drawIndirect
[GPU] Render:  single drawIndirect() call
    ↓
[Display] Present via AndroidExternalSurface
```

The CPU never reads GPU data back (no `mapAsync` in the hot path). Scene changes are detected via
`rootNode.isDirty` — only dirty frames trigger a data re-upload. Static scenes cost nothing beyond
a single `drawIndirect` re-submission.

### Step 1: SceneData Buffer Layout

```wgsl
struct FaceData {
    // 3D input vertices (up to 4 for quads; use padding for triangles)
    v0: vec3<f32>, _p0: f32,
    v1: vec3<f32>, _p1: f32,
    v2: vec3<f32>, _p2: f32,
    v3: vec3<f32>, vertexCount: u32,  // 3 or 4
    // Lighting
    baseColor: vec4<f32>,    // RGBA before lighting
    normal: vec3<f32>, _p4: f32,
    // Identity
    faceIndex: u32,
    textureIndex: u32,
    _padding: vec2<u32>,
}
```

### Step 2: Transform Compute Pass

```wgsl
@group(0) @binding(0) var<storage, read>       scene:        array<FaceData>;
@group(0) @binding(1) var<storage, read_write> transformed:  array<TransformedFace>;
@group(0) @binding(2) var<uniform>             camera:       CameraUniforms;

struct CameraUniforms {
    isoMatrix:     mat4x4<f32>,   // 3D isometric projection → 2D screen
    lightDir:      vec3<f32>,
    ambientLight:  f32,
}

@compute @workgroup_size(256)
fn transformPass(@builtin(global_invocation_id) id: vec3<u32>) {
    let i = id.x;
    if (i >= arrayLength(&scene)) { return; }
    let face = scene[i];

    // Project each vertex via the isometric matrix
    let s0 = (camera.isoMatrix * vec4<f32>(face.v0, 1.0)).xy;
    let s1 = (camera.isoMatrix * vec4<f32>(face.v1, 1.0)).xy;
    let s2 = (camera.isoMatrix * vec4<f32>(face.v2, 1.0)).xy;
    let s3 = (camera.isoMatrix * vec4<f32>(face.v3, 1.0)).xy;

    // Depth centroid (average projected Z before 2D projection)
    let depth = (face.v0.y + face.v1.y + face.v2.y + face.v3.y) / f32(face.vertexCount);

    // Lighting: diffuse dot product
    let lit = max(dot(normalize(face.normal), camera.lightDir), camera.ambientLight);
    let finalColor = vec4<f32>(face.baseColor.rgb * lit, face.baseColor.a);

    transformed[i] = TransformedFace(s0, s1, s2, s3, face.vertexCount,
                                      depth, finalColor, face.faceIndex, face.textureIndex);
}
```

### Step 3: Culling + Compaction Pass

```wgsl
@compute @workgroup_size(256)
fn cullPass(@builtin(global_invocation_id) id: vec3<u32>) {
    let i = id.x;
    if (i >= arrayLength(&transformed)) { return; }
    let face = transformed[i];

    // Back-face cull: signed area of projected polygon < 0 means facing away
    let area = (face.s1 - face.s0).x * (face.s2 - face.s0).y
             - (face.s2 - face.s0).x * (face.s1 - face.s0).y;
    if (area >= 0.0) { return; }  // back-facing or degenerate

    // Frustum cull: AABB of face vs viewport
    let minX = min(min(face.s0.x, face.s1.x), min(face.s2.x, face.s3.x));
    let maxX = max(max(face.s0.x, face.s1.x), max(face.s2.x, face.s3.x));
    let minY = min(min(face.s0.y, face.s1.y), min(face.s2.y, face.s3.y));
    let maxY = max(max(face.s0.y, face.s1.y), max(face.s2.y, face.s3.y));
    if (maxX < 0.0 || minX > viewport.x || maxY < 0.0 || minY > viewport.y) { return; }

    // Write to compacted visible list via atomic counter
    let slot = atomicAdd(&visibleCount, 1u);
    visibleIndices[slot] = i;
}
```

### Step 4: Populate Indirect Draw Buffer

```wgsl
// After radix sort + lighting, one invocation per visible face
@compute @workgroup_size(256)
fn populateIndirectBuffer(@builtin(global_invocation_id) id: vec3<u32>) {
    let i = id.x;
    if (i >= atomicLoad(&visibleCount)) { return; }
    let faceIdx = sortedVisibleIndices[i];
    let face = transformed[faceIdx];

    // Write triangulated vertices into the final vertex buffer (triangle fan)
    let baseVertex = atomicAdd(&vertexWriteCursor, (face.vertexCount - 2u) * 3u);
    // ... write (vertexCount - 2) triangles for this face ...

    // Indirect draw structure: vertexCount, instanceCount, firstVertex, firstInstance
    indirectBuffer[i] = DrawIndirectArgs(
        vertexCount:   (face.vertexCount - 2u) * 3u,
        instanceCount: 1u,
        firstVertex:   baseVertex,
        firstInstance: 0u,
    );
}
```

### Step 5: Single `drawIndirect` Call

```kotlin
fun drawFrame() {
    val encoder = ctx.device.createCommandEncoder(GPUCommandEncoderDescriptor())

    // --- Compute passes ---
    val compute = encoder.beginComputePass()  // GPUComputePassDescriptor? is optional

    compute.setPipeline(transformPipeline)
    compute.setBindGroup(0, transformBindGroup)
    compute.dispatchWorkgroups(ceil(faceCount / 256f).toInt())

    compute.setPipeline(cullPipeline)
    compute.setBindGroup(0, cullBindGroup)
    compute.dispatchWorkgroups(ceil(faceCount / 256f).toInt())

    // radix sort passes (4×)
    for (pass in 0 until 4) {
        compute.setPipeline(radixSortPipelines[pass])
        compute.setBindGroup(0, radixBindGroups[pass])
        compute.dispatchWorkgroups(ceil(faceCount / 256f).toInt())
    }

    compute.setPipeline(lightingPipeline)
    compute.setBindGroup(0, lightingBindGroup)
    compute.dispatchWorkgroups(ceil(faceCount / 256f).toInt())

    compute.setPipeline(populateIndirectPipeline)
    compute.setBindGroup(0, populateIndirectBindGroup)
    compute.dispatchWorkgroups(ceil(faceCount / 256f).toInt())

    compute.end()

    // --- Render pass ---
    val renderPass = encoder.beginRenderPass(/* ... */)
    renderPass.setPipeline(renderPipeline)
    renderPass.setBindGroup(0, uniformBindGroup)
    renderPass.setVertexBuffer(0, gpuVertexBuffer)
    renderPass.drawIndirect(indirectBuffer, 0)   // GPU decides draw count
    renderPass.end()

    ctx.queue.submit(arrayOf(encoder.finish()))  // Array, not List
}
```

### WS13 Deliverables

- Full GPU-driven pipeline: zero per-frame CPU work for static scenes
- `drawIndirect` replaces all explicit `draw()` calls
- Back-face + frustum culling on GPU: invisible faces cost nothing
- Lighting computed on GPU: `transformColor()` removed from CPU path
- Verified frame budget: 1000-face scene < 2ms total on Pixel 6
- Existing Canvas path and Phase 2 path regression-free

---

## 8. Testing Strategy

### Unit Tests (JVM, no GPU required)

| Test Class | What It Covers |
|---|---|
| `DepthSorterInterfaceTest` | `CpuDepthSorter` produces same ordering as old `sortPaths()` on known scenes |
| `RenderCommandTriangulatorTest` | Triangle fan output count: quad→2 triangles, pentagon→3, etc. Winding order correct (counter-clockwise) |
| `RadixSortShaderParseTest` | WGSL string parses without error (via `GPUDevice.createShaderModule` on test device only) |
| `GpuDepthSorterFallbackTest` | N < 64 delegates to `CpuDepthSorter`; verify no GPU calls made via mock |
| `RenderBackendCanvasTest` | `CanvasRenderBackend` produces identical output to previous `IsometricScene` inline Canvas block |

### Physical Device CI

Minimum device matrix for GPU tests:

| Device | GPU | Reason |
|---|---|---|
| Pixel 6 | Arm Mali-G78 | High-end ARM Mali, Vulkan 1.1 |
| Pixel 7 | Arm Mali-G710 | Current Tensor generation |
| Samsung Galaxy A55 | Exynos 1480 (Xclipse 540) | Mid-range ARM |
| Qualcomm reference device (any Snapdragon 8 Gen) | Adreno 730+ | Qualcomm path |

Tests to run on physical devices:

- `WebGpuSmokeTest` — `IsometricScene(config = SceneConfig(renderBackend = RenderBackend.WebGpu))` renders without crash
- `WebGpuFrameBudgetTest` — 1000-face scene produces frame time < 5ms (p95) over 300 frames
- `WebGpuResizeTest` — surface resize mid-render does not black-screen or crash
- `WebGpuTouchBridgeTest` — `onTap` and per-node `onClick` (WS10) fire correctly via overlay on WebGPU backend

### Canvas Regression Guard

Existing benchmarks in `benchmarks/` cover the Canvas path. No new infrastructure required.
Before each WS11/WS12/WS13 merge, confirm:

```
./gradlew :benchmarks:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=CanvasRenderBenchmark
```

Passes with < 5% regression on the N=100 and N=200 prepare-time benchmarks.

---

## 9. Risk Register

| Risk | Severity | Mitigation |
|---|---|---|
| `alpha04` renames types between releases (happened in alpha02: `BindGroupDescriptor` → `GPUBindGroupDescriptor`) | Medium | Pin version; grep for compiler errors on upgrade; fix in place per the no-deprecation-cycle preference |
| `mapAndAwait` timeout on slow devices in Phase 1's GPU sort | Medium | `withTimeoutOrNull(frameDeadlineMs)` — if sort doesn't complete within budget, fall back to `cpuFallbackSort()` silently |
| Black screen on surface reconfiguration (known alpha bug) | Medium | Add `queue.onSubmittedWorkDone()` await after `configure()`; check `surfaceTexture.status` before rendering |
| ~23% of devices lack Vulkan 1.1+ | Low | `RenderBackend.Canvas` remains permanent; auto-detect in `GpuContext.create()` and surface `IllegalStateException` for clean error message directing users to Canvas backend |
| Isometric faces are not always convex after projection (e.g. custom `Path` nodes) | Low | Add convexity check in `RenderCommandTriangulator`; fall back to ear clipping for non-convex polygons |
| Float depth keys produce incorrect sort for negative projected depths | Low | Apply standard sign-bit XOR in `buildDepthKeys()` before upload; document the assumption |
| `indirectBuffer` draw count not synchronized between compute and render passes | High | Use `GPUCommandEncoder` (single encoder covers both compute and render passes) — WebGPU's implicit barrier between passes within one submit guarantees ordering |
| Workgroup size 256 exceeds actual device `maxComputeInvocationsPerWorkgroup` | Low | Query device limits in `GpuContext.create()`; clamp workgroup size to `min(256, device.limits.maxComputeInvocationsPerWorkgroup)` |

---

## 10. API Accuracy Reference — androidx.webgpu

Verified against the actual source in `vendor/androidx-webgpu/` (downloaded 2026-03-18,
`androidx-main` branch). Apply these corrections to any code that references the WebGPU API.

### 10.1 Instance Creation

```kotlin
// ❌ Wrong — no public constructor
val instance = GPUInstance()

// ✅ Correct
androidx.webgpu.helper.initLibrary()  // must be called before any WebGPU call
val instance = GPU.createInstance()   // GPU is the top-level companion object in Functions.kt
```

### 10.2 requestAdapter / requestDevice Error Semantics

Both are `suspend fun` that **throw `WebGpuException`** on failure — they never return `null`.
Remove `?: error(...)` null-guards.

```kotlin
// ❌ Wrong — requestAdapter() never returns null
val adapter = instance.requestAdapter() ?: error("...")

// ✅ Correct — throws WebGpuException on failure
val adapter = instance.requestAdapter(
    GPURequestAdapterOptions(
        powerPreference = PowerPreference.HighPerformance,  // PowerPreference, not GPUPowerPreference
        backendType = BackendType.Vulkan
    )
)
val device = adapter.requestDevice()  // also suspend, throws on failure
```

### 10.3 Android Surface → GPUSurface

`GPUInstance` has no overload that accepts `android.view.Surface` directly. Must use
`Util.windowFromSurface()` (a JNI external function that returns the `ANativeWindow*` as `Long`):

```kotlin
// ❌ Wrong — no such overload
val gpuSurface = ctx.instance.createSurface(androidSurface)

// ✅ Correct
val nativeWindow = androidx.webgpu.helper.Util.windowFromSurface(androidSurface)
val gpuSurface = ctx.instance.createSurface(
    GPUSurfaceDescriptor(
        surfaceSourceAndroidNativeWindow = GPUSurfaceSourceAndroidNativeWindow(nativeWindow)
    )
)
```

### 10.4 IntDef Type Names — No GPU Prefix

All enum-like types in `androidx.webgpu` are `@IntDef annotation class` with constants in the
companion object. None have a `GPU` prefix:

| Plan used (❌ Wrong) | Actual name (✅ Correct) |
|---|---|
| `GPUTextureFormat.BGRA8Unorm` | `TextureFormat.BGRA8Unorm` |
| `GPUTextureUsage.RenderAttachment` | `TextureUsage.RenderAttachment` |
| `GPUCompositeAlphaMode.Opaque` | `CompositeAlphaMode.Opaque` |
| `GPULoadOp.Clear` | `LoadOp.Clear` |
| `GPUStoreOp.Store` | `StoreOp.Store` |
| `GPUBufferUsage.Storage` | `BufferUsage.Storage` |
| `GPUMapMode.Read` | `MapMode.Read` |
| `GPUPowerPreference.HighPerformance` | `PowerPreference.HighPerformance` |

### 10.5 Shader Module WGSL Source

`GPUShaderModuleDescriptor` has **no `code` field**. WGSL source is nested inside `GPUShaderSourceWGSL`:

```kotlin
// ❌ Wrong — no 'code' parameter on GPUShaderModuleDescriptor
device.createShaderModule(GPUShaderModuleDescriptor(code = wgslSource))

// ✅ Correct
device.createShaderModule(
    GPUShaderModuleDescriptor(
        shaderSourceWGSL = GPUShaderSourceWGSL(code = wgslSource)
    )
)
```

### 10.6 Buffer Map — Suspend vs Callback

```kotlin
// Callback version (not a suspend fun):
buffer.mapAsync(MapMode.Read, 0L, size, executor, callback)

// Suspend version (coroutine-friendly):
buffer.mapAndAwait(MapMode.Read, 0L, size)   // ← name is mapAndAwait, not mapAsync
```

### 10.7 writeBuffer — ByteBuffer, not FloatArray

`GPUQueue.writeBuffer(buffer, bufferOffset, data: ByteBuffer)` accepts only `ByteBuffer`.
Must convert `FloatArray`:

```kotlin
val byteBuffer = ByteBuffer
    .allocateDirect(floatArray.size * 4)
    .order(ByteOrder.nativeOrder())
    .also { it.asFloatBuffer().put(floatArray); it.rewind() }
queue.writeBuffer(gpuBuffer, 0L, byteBuffer)
```

### 10.8 getCurrentTexture Returns GPUSurfaceTexture

`GPUSurface.getCurrentTexture()` returns `GPUSurfaceTexture` (non-null), which holds:
- `.texture: GPUTexture` — the actual texture to render to
- `.status: Int` — check against `SurfaceGetCurrentTextureStatus` constants

```kotlin
// ❌ Wrong — not nullable, wrong type
val texture = gpuSurface.getCurrentTexture() ?: return

// ✅ Correct
val surfaceTexture = gpuSurface.getCurrentTexture()   // GPUSurfaceTexture, never null
val textureView = surfaceTexture.texture.createView() // access .texture first
```

### 10.9 Array vs List for colorAttachments and queue.submit

```kotlin
// ❌ Wrong — listOf() is List, not Array
GPURenderPassDescriptor(colorAttachments = listOf(...))
queue.submit(listOf(encoder.finish()))

// ✅ Correct — both require Array
GPURenderPassDescriptor(colorAttachments = arrayOf(...))
queue.submit(arrayOf(encoder.finish()))
```

### 10.10 Surface Format — Query Capabilities First

Never hardcode `TextureFormat.BGRA8Unorm`. Query the surface's supported formats:

```kotlin
val caps = gpuSurface.getCapabilities(ctx.adapter)
val format = if (TextureFormat.BGRA8Unorm in caps.formats) {  // formats is IntArray
    TextureFormat.BGRA8Unorm
} else {
    caps.formats.first()
}
```

### 10.11 processEvents() Polling

Dawn does not self-drive its callback loop. `GPUInstance.processEvents()` must be called
periodically on the main thread (~100ms) for any async callback (requestAdapter, requestDevice,
mapAndAwait) to fire. See `WebGpu.kt` in the helper package for the reference implementation.

```kotlin
val handler = Handler(Looper.getMainLooper())
fun scheduleProcess() {
    handler.postDelayed({ instance.processEvents(); scheduleProcess() }, 100L)
}
scheduleProcess()
```

Without this, all `awaitGPURequest` calls will hang indefinitely.

### 10.12 Surface Format Caps — IntArray, Not Int Collection

`GPUSurfaceCapabilities.formats` is `IntArray`, not `List<Int>`. Use `in` operator carefully:

```kotlin
// IntArray.contains() works with 'in' operator in Kotlin — this is fine:
if (TextureFormat.BGRA8Unorm in caps.formats) { ... }
```

### 10.13 Adapter Lifecycle — One Device Per Adapter

`GPUAdapter.requestDevice()` can only be called **once per adapter instance**. After the first
successful call, any subsequent `requestDevice()` throws `WebGpuException`. Design `GpuContext`
to keep the device alive rather than destroying and recreating it.
