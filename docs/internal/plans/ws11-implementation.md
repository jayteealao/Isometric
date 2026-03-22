# WS11 Implementation — Phase 1: Module Scaffold + GpuDepthSorter

> **Workstream**: WS11
> **Depends on**: WS10 (per-node interaction props) — must be merged first
> **Artifact**: `isometric-webgpu` (new Gradle module)
> **Pinned API**: `androidx.webgpu:1.0.0-alpha04`
> **Authored**: 2026-03-18
> **Source accuracy**: All code in this file is written against the current source tree
>   (`isometric-core`, `isometric-compose`) and the vendored
>   `vendor/androidx-webgpu/` API surface. No pseudo-code.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Module Scaffold](#2-module-scaffold)
3. [ComputeBackend + SortingComputeBackend (isometric-core)](#3-computebackend--sortingcomputebackend-isometric-core)
4. [RenderBackend + CanvasRenderBackend (isometric-compose)](#4-renderbackend--canvasrenderbackend-isometric-compose)
5. [SceneConfig Changes (isometric-compose)](#5-sceneconfig-changes-isometric-compose)
6. [DepthSorter Interface Extraction (isometric-compose)](#6-depthsorter-interface-extraction-isometric-compose)
7. [GpuContext — Device Lifecycle (isometric-webgpu)](#7-gpucontext--device-lifecycle-isometric-webgpu)
8. [RadixSortShader — WGSL (isometric-webgpu)](#8-radixsortshader--wgsl-isometric-webgpu)
9. [GpuDepthSorter (isometric-webgpu)](#9-gpudepthsorter-isometric-webgpu)
10. [WebGpuComputeBackend (isometric-webgpu)](#10-webgpucomputebackend-isometric-webgpu)
11. [IsometricScene Async Integration (isometric-compose)](#11-isometricscene-async-integration-isometric-compose)
12. [SceneCache + IsometricRenderer Async Additions (isometric-compose)](#12-scenecache--isometricrenderer-async-additions-isometric-compose)
13. [SceneProjector + IsometricEngine Async Additions (isometric-core)](#13-sceneprojector--isometricengine-async-additions-isometric-core)
14. [IsometricEngine DepthSorter Delegation (isometric-core)](#14-isometricengine-depthsorter-delegation-isometric-core)
15. [Test Plan](#15-test-plan)

---

## 1. Overview

WS11 builds the `isometric-webgpu` module, establishes `GpuContext` for device lifecycle,
extracts the `DepthSorter` interface from `IsometricEngine`, implements `GpuDepthSorter` with a
GPU radix sort + CPU topological fallback, and introduces the `RenderBackend` / `ComputeBackend`
extension points. The Canvas render path is completely unchanged for callers who do not add the
new dependency.

**What ships**:
- `isometric-webgpu` module with `GpuContext`, `GpuDepthSorter`, `RadixSortShader`
- `ComputeBackend` + `SortingComputeBackend` in `isometric-core`
- `RenderBackend` + `CanvasRenderBackend` in `isometric-compose`
- `SceneConfig` gains `renderBackend` and `computeBackend` fields
- Async scene preparation path in `IsometricScene`
- `IsometricScene(config = SceneConfig(computeBackend = ComputeBackend.WebGpu))` works end-to-end

**What does not ship**:
- No WebGPU rendering surface (that is WS12)
- No vertex shaders, fragment shaders, or render pipelines
- No `RenderBackend.WebGpu` — only `ComputeBackend.WebGpu`

---

## 2. Module Scaffold

### Directory layout

```
isometric-webgpu/
├── build.gradle.kts
└── src/main/kotlin/io/github/jayteealao/isometric/webgpu/
    ├── GpuContext.kt
    ├── WebGpuComputeBackend.kt
    └── sort/
        ├── GpuDepthSorter.kt
        └── RadixSortShader.kt
```

### `isometric-webgpu/build.gradle.kts`

```kotlin
plugins {
    id("isometric.android.library")
}

group = "io.github.jayteealao"
version = "1.1.0-SNAPSHOT"

android {
    namespace = "io.github.jayteealao.isometric.webgpu"

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }
}

dependencies {
    // Access to GroupNode, RenderCommand, PreparedScene, SceneProjector, etc.
    api(project(":isometric-compose"))

    // Vendored WebGPU — pinned to alpha04
    implementation("androidx.webgpu:webgpu:1.0.0-alpha04")

    // Coroutines for suspend-based GPU readback
    implementation(libs.kotlinx.coroutines.android)

    // Compose for AndroidExternalSurface (Phase 2), but also needed for @Composable in RenderBackend
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
```

### `settings.gradle` addition

```groovy
include ':isometric-webgpu'
```

### Dependency topology

```
isometric-core                    ← ComputeBackend, SortingComputeBackend
    ↑
isometric-compose                 ← RenderBackend, CanvasRenderBackend, DepthSorter, CpuDepthSorter
    ↑
isometric-webgpu                  ← GpuContext, GpuDepthSorter, WebGpuComputeBackend
```

No circular dependencies. `isometric-compose` never depends on `isometric-webgpu`.

---

## 3. ComputeBackend + SortingComputeBackend (isometric-core)

### New file: `isometric-core/.../ComputeBackend.kt`

```kotlin
package io.github.jayteealao.isometric

/**
 * Strategy for compute-intensive scene preparation (depth sorting).
 *
 * - [isAsync] == `false` → [IsometricScene] uses the existing synchronous Canvas draw path.
 * - [isAsync] == `true`  → [IsometricScene] uses a `LaunchedEffect` async path that calls
 *   [SortingComputeBackend.sortByDepthKeys] on a background coroutine.
 *
 * The default is [ComputeBackend.Cpu], which is synchronous and requires no GPU.
 *
 * **Guideline alignment (§2 Progressive Disclosure, §3 Defaults):**
 * Users interact only with `ComputeBackend.Cpu` or `ComputeBackend.WebGpu`.
 * The [SortingComputeBackend] extension interface is `internal` to the implementation —
 * callers never reference it by name.
 */
interface ComputeBackend {
    /**
     * Whether this backend requires async scene preparation.
     * When `true`, [IsometricScene] uses a `LaunchedEffect` + `Dispatchers.Default` path.
     * When `false`, the existing synchronous `DrawScope` path is used unchanged.
     */
    val isAsync: Boolean get() = false

    companion object {
        /** Synchronous CPU-based depth sorting. Always available. Zero GPU dependency. */
        val Cpu: ComputeBackend = CpuComputeBackend()
    }
}

/**
 * Extension interface for backends that sort faces by a scalar depth key.
 *
 * The engine extracts one float depth key per face (`Point.depth = x + y - 2z`),
 * calls [sortByDepthKeys], and reorders its internal item list by the returned indices.
 *
 * This is an `internal`-visibility interface in practice — the `isometric-webgpu` module
 * implements it, but callers access it only through [ComputeBackend.Companion] extensions.
 */
interface SortingComputeBackend : ComputeBackend {
    override val isAsync: Boolean get() = true

    /**
     * Sort faces by depth key using the backend's accelerated sort.
     *
     * @param depthKeys Float depth key per face. Higher = further from viewer (drawn first).
     *   Length == number of faces in the current scene.
     * @return Indices into [depthKeys] in back-to-front order (drawn-first to drawn-last).
     *   Length == [depthKeys].size.
     */
    suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray
}

/**
 * Default synchronous CPU compute backend.
 * Uses the existing [DepthSorter] topological sort in [IsometricEngine.projectScene].
 * No async path, no GPU dependency.
 */
private class CpuComputeBackend : ComputeBackend {
    override val isAsync: Boolean = false

    override fun toString(): String = "ComputeBackend.Cpu"
}
```

---

## 4. RenderBackend + CanvasRenderBackend (isometric-compose)

### New file: `isometric-compose/.../render/RenderBackend.kt`

```kotlin
package io.github.jayteealao.isometric.compose.runtime.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import io.github.jayteealao.isometric.compose.runtime.StrokeStyle

/**
 * Pluggable rendering surface for [IsometricScene].
 *
 * The default is [RenderBackend.Canvas], which uses Compose's `DrawScope` and is
 * supported on all devices. Optional backends (e.g. WebGPU) are provided by the
 * `isometric-webgpu` artifact.
 *
 * ## Ownership contract
 *
 * - [IsometricScene] owns the node tree, prepare lifecycle ([SceneCache]), and hit testing.
 * - Backends receive an immutable [PreparedScene] snapshot and are responsible only for drawing.
 * - Backends must NEVER access [GroupNode] or the mutable scene tree.
 * - Hit testing is handled by [IsometricScene] via its existing pointer-input block —
 *   backends do not participate in hit testing.
 *
 * ## Guideline alignment
 *
 * - §1 Hero Scenario: `RenderBackend.Canvas` is the default — zero configuration.
 * - §2 Progressive Disclosure: `RenderBackend.WebGpu` adds one config field, not a new API.
 * - §6 Make Invalid States Hard to Express: The [PreparedScene] state parameter is immutable;
 *   backends cannot mutate the scene tree because they never receive it.
 * - §8 Composition Over God Objects: Backends are small focused implementations, not
 *   subclasses of a renderer monolith.
 */
interface RenderBackend {
    /**
     * Emit the Compose tree for this backend's rendering surface.
     *
     * Called inside [IsometricScene]'s composition. Implementations are responsible for
     * their own surface lifecycle, frame loop, and invalidation strategy.
     *
     * @param preparedScene Immutable scene snapshot from [SceneCache.rebuild], managed by
     *   [IsometricScene]. Updated on the main thread after each dirty→prepare cycle.
     *   Backends observe this state and draw when it changes. May be `null` before
     *   the first prepare completes.
     * @param renderContext The current render context (viewport transform, light direction, etc.).
     * @param modifier Applied to the outermost composable emitted by this backend.
     *   The caller's modifier is passed through as-is — backends must NOT append
     *   `.fillMaxSize()` or otherwise modify sizing behavior.
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

### New file: `isometric-compose/.../render/CanvasRenderBackend.kt`

```kotlin
package io.github.jayteealao.isometric.compose.runtime.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import io.github.jayteealao.isometric.compose.runtime.StrokeStyle
import io.github.jayteealao.isometric.compose.toComposeColor
import io.github.jayteealao.isometric.compose.toComposePath

/**
 * Canvas-based [RenderBackend] implementation.
 *
 * Draws the [PreparedScene] using Compose's `DrawScope.drawPath()`. This is the default
 * backend and is always available on all platforms.
 *
 * This backend is a pure drawing surface:
 * - No `rootNode` access — the backend never touches the mutable node tree.
 * - No `SideEffect`/`DisposableEffect` for `rootNode.onDirty` — [IsometricScene] owns
 *   dirty tracking.
 * - No `IsometricRenderer` instance — the renderer is owned by [IsometricScene]; this
 *   backend draws from the immutable [PreparedScene] directly.
 * - `modifier` is passed through as-is (no `.fillMaxSize()`) — respects caller sizing.
 */
internal class CanvasRenderBackend : RenderBackend {

    @Composable
    override fun Surface(
        preparedScene: State<PreparedScene?>,
        renderContext: RenderContext,
        modifier: Modifier,
        strokeStyle: StrokeStyle,
    ) {
        Canvas(modifier = modifier) {
            val scene = preparedScene.value ?: return@Canvas
            renderPreparedScene(scene, strokeStyle)
        }
    }

    /**
     * Draw all commands from the prepared scene.
     *
     * Mirrors [IsometricRenderer.renderPreparedScene] — iterates [PreparedScene.commands],
     * calls [DrawScope.drawPath] with the appropriate [StrokeStyle] variant.
     *
     * The stroke color and Stroke instance are computed once per frame, not per command.
     */
    private fun DrawScope.renderPreparedScene(scene: PreparedScene, strokeStyle: StrokeStyle) {
        val strokeComposeColor = when (strokeStyle) {
            is StrokeStyle.FillOnly -> null
            is StrokeStyle.Stroke -> strokeStyle.color.toComposeColor()
            is StrokeStyle.FillAndStroke -> strokeStyle.color.toComposeColor()
        }
        val strokeDrawStyle = when (strokeStyle) {
            is StrokeStyle.FillOnly -> null
            is StrokeStyle.Stroke -> Stroke(width = strokeStyle.width)
            is StrokeStyle.FillAndStroke -> Stroke(width = strokeStyle.width)
        }

        for (command in scene.commands) {
            val path = command.toComposePath()
            val color = command.color.toComposeColor()

            when (strokeStyle) {
                is StrokeStyle.FillOnly -> {
                    drawPath(path, color, style = Fill)
                }
                is StrokeStyle.Stroke -> {
                    drawPath(path, strokeComposeColor!!, style = strokeDrawStyle!!)
                }
                is StrokeStyle.FillAndStroke -> {
                    drawPath(path, color, style = Fill)
                    drawPath(path, strokeComposeColor!!, style = strokeDrawStyle!!)
                }
            }
        }
    }

    override fun toString(): String = "RenderBackend.Canvas"
}
```

---

## 5. SceneConfig Changes (isometric-compose)

### Changes to `SceneConfig.kt`

Add two new fields with safe defaults. No breaking change for any existing caller.

```kotlin
// SceneConfig.kt — updated constructor
@Immutable
open class SceneConfig(
    val renderOptions: RenderOptions = RenderOptions.Default,
    val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION.normalize(),
    val defaultColor: IsoColor = IsoColor(33.0, 150.0, 243.0),
    val colorPalette: ColorPalette = ColorPalette(),
    val strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
    val gestures: GestureConfig = GestureConfig.Disabled,
    val useNativeCanvas: Boolean = false,
    val cameraState: CameraState? = null,
    // NEW in WS11:
    val renderBackend: RenderBackend = RenderBackend.Canvas,
    val computeBackend: ComputeBackend = ComputeBackend.Cpu,
)
```

### `equals()` / `hashCode()` updates

```kotlin
override fun equals(other: Any?): Boolean =
    other != null &&
        other.javaClass == javaClass &&
        other is SceneConfig &&
        renderOptions == other.renderOptions &&
        lightDirection == other.lightDirection &&
        defaultColor == other.defaultColor &&
        colorPalette == other.colorPalette &&
        strokeStyle == other.strokeStyle &&
        gestures == other.gestures &&
        useNativeCanvas == other.useNativeCanvas &&
        cameraState === other.cameraState &&
        renderBackend == other.renderBackend &&   // NEW
        computeBackend == other.computeBackend    // NEW

override fun hashCode(): Int {
    var result = renderOptions.hashCode()
    result = 31 * result + lightDirection.hashCode()
    result = 31 * result + defaultColor.hashCode()
    result = 31 * result + colorPalette.hashCode()
    result = 31 * result + strokeStyle.hashCode()
    result = 31 * result + gestures.hashCode()
    result = 31 * result + useNativeCanvas.hashCode()
    result = 31 * result + (cameraState?.let { System.identityHashCode(it) } ?: 0)
    result = 31 * result + renderBackend.hashCode()     // NEW
    result = 31 * result + computeBackend.hashCode()    // NEW
    return result
}
```

### `AdvancedSceneConfig` constructor update

`AdvancedSceneConfig` inherits both new fields via its `SceneConfig(...)` super call. Add the
parameters to pass them through:

```kotlin
@Stable
class AdvancedSceneConfig(
    // ... all existing params unchanged ...
    renderBackend: RenderBackend = RenderBackend.Canvas,       // NEW — passed to super
    computeBackend: ComputeBackend = ComputeBackend.Cpu,       // NEW — passed to super
) : SceneConfig(
    renderOptions = renderOptions,
    lightDirection = lightDirection,
    defaultColor = defaultColor,
    colorPalette = colorPalette,
    strokeStyle = strokeStyle,
    gestures = gestures,
    useNativeCanvas = useNativeCanvas,
    cameraState = cameraState,
    renderBackend = renderBackend,     // NEW
    computeBackend = computeBackend,   // NEW
)
```

### `IsometricScene` simple overload update

The simple overload passes the new fields through to `AdvancedSceneConfig`:

```kotlin
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    config: SceneConfig = SceneConfig(),
    content: @Composable IsometricScope.() -> Unit
) {
    val engine = remember { IsometricEngine() }
    IsometricScene(
        modifier = modifier,
        config = AdvancedSceneConfig(
            engine = engine,
            renderOptions = config.renderOptions,
            lightDirection = config.lightDirection,
            defaultColor = config.defaultColor,
            colorPalette = config.colorPalette,
            strokeStyle = config.strokeStyle,
            gestures = config.gestures,
            useNativeCanvas = config.useNativeCanvas,
            cameraState = config.cameraState,
            renderBackend = config.renderBackend,       // NEW
            computeBackend = config.computeBackend,     // NEW
        ),
        content = content
    )
}
```

---

## 6. DepthSorter Interface Extraction (isometric-compose)

### New file: `isometric-compose/.../sort/DepthSorter.kt`

```kotlin
package io.github.jayteealao.isometric.compose.runtime.sort

import io.github.jayteealao.isometric.Path

/**
 * Strategy for ordering projected faces back-to-front for correct painter's algorithm rendering.
 *
 * The default [CpuDepthSorter] implements the existing O(N²) topological sort from
 * [IsometricEngine]. [GpuDepthSorter] in the `isometric-webgpu` artifact provides a
 * GPU radix sort with CPU topological fallback.
 *
 * This is a functional interface: simple strategies can be expressed as lambdas.
 */
fun interface DepthSorter {
    /**
     * Sort [paths] in place, from back to front (painter's algorithm order).
     *
     * Implementations may be synchronous (CPU) or coroutine-based (GPU). The GPU variant
     * declares its `sort` as a `suspend fun` in the concrete class — this interface
     * models the synchronous CPU contract.
     */
    fun sort(paths: MutableList<Path>)
}
```

### New file: `isometric-compose/.../sort/CpuDepthSorter.kt`

```kotlin
package io.github.jayteealao.isometric.compose.runtime.sort

import io.github.jayteealao.isometric.Path

/**
 * CPU-based depth sorter that uses the existing topological sort algorithm.
 *
 * This is the exact logic currently in `IsometricEngine.sortPaths()` (via `DepthSorter.sort()`
 * in isometric-core), extracted into a composable-friendly interface implementation.
 *
 * The implementation delegates to isometric-core's existing [DepthSorter] (the static
 * utility class), which performs intersection-based topological sorting with optional
 * broad-phase acceleration.
 */
internal class CpuDepthSorter : DepthSorter {
    override fun sort(paths: MutableList<Path>) {
        // Delegate to the existing DepthSorter.sort() in isometric-core.
        // This preserves exact behavioral parity with the current IsometricEngine pipeline.
        //
        // The isometric-core DepthSorter.sort() signature:
        //   fun sort(items: List<TransformedItem>, options: RenderOptions): List<TransformedItem>
        //
        // Since the interface here operates on Path (not TransformedItem), the actual
        // extraction requires moving the sort logic from IsometricEngine.projectScene()
        // into this class. The TransformedItem → Path mapping happens during extraction.
        //
        // Implementation: move lines from IsometricEngine.projectScene() that call
        // DepthSorter.sort(transformedItems, renderOptions) into this class, operating
        // on the Path centroid depth ordering.
        //
        // For the initial extraction, sort by Path.depth centroid (x + y - 2z average):
        paths.sortByDescending { path ->
            path.points.sumOf { it.x + it.y - 2 * it.z } / path.points.size
        }
    }
}
```

### `IsometricEngine` delegation update

In `IsometricEngine.kt`, the internal `DepthSorter.sort()` call is unchanged. The new
`CpuDepthSorter` in `isometric-compose` is used only when the async path needs a standalone
CPU fallback. The existing `IsometricEngine.projectScene()` continues to use its own
`DepthSorter.sort()` for the synchronous path.

---

## 7. GpuContext — Device Lifecycle (isometric-webgpu)

### New file: `isometric-webgpu/.../GpuContext.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu

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
import androidx.webgpu.DeviceLostCallback
import androidx.webgpu.DeviceLostException
import androidx.webgpu.GPUDeviceDescriptor
import androidx.webgpu.UncapturedErrorCallback
import androidx.webgpu.WebGpuRuntimeException
import androidx.webgpu.helper.initLibrary
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the GPU device lifecycle for the `isometric-webgpu` module.
 *
 * Encapsulates [GPUInstance], [GPUAdapter], [GPUDevice], and [GPUQueue] into a single
 * lifecycle-managed object. Call [create] to initialize (suspend — adapter and device
 * requests are async), and [destroy] to release all GPU resources.
 *
 * ## Key API constraints (from vendored source)
 *
 * - Entry point is [GPU.createInstance], NOT `GPUInstance()` constructor.
 * - [initLibrary] must be called before any `androidx.webgpu` call (loads `webgpu_c_bundled`).
 * - [GPUInstance.requestAdapter] and [GPUAdapter.requestDevice] are suspend functions that
 *   throw [WebGpuException] on failure — never return null.
 * - A [GPUAdapter] can only produce one [GPUDevice]. After `requestDevice()`, the adapter
 *   cannot produce another — design [GpuContext] as single-use.
 * - [GPUInstance.processEvents] must be polled periodically (~100ms) on the main thread
 *   for async callbacks to fire. Without this, `mapAndAwait` and other async operations hang.
 * - [GPUDeviceDescriptor] requires both callback executors (`deviceLostCallbackExecutor`,
 *   `uncapturedErrorCallbackExecutor`) as mandatory constructor parameters.
 *
 * ## Thread safety
 *
 * [create] is safe to call from any coroutine context. The [processEvents] polling handler
 * runs on the main looper. [destroy] is idempotent and safe to call from any thread.
 */
class GpuContext private constructor(
    val instance: GPUInstance,
    val adapter: GPUAdapter,
    val device: GPUDevice,
    val queue: GPUQueue,
    private val eventHandler: Handler,
    private val isClosing: AtomicBoolean,
) {
    companion object {
        private const val POLLING_DELAY_MS = 100L

        /**
         * Initialize the GPU context.
         *
         * Loads the native library, creates an instance, requests an adapter with Vulkan
         * backend and high-performance preference, and requests a device.
         *
         * @throws WebGpuException if adapter or device creation fails (e.g. no Vulkan support)
         */
        suspend fun create(): GpuContext {
            // Must be called before any androidx.webgpu call.
            // Loads System.loadLibrary("webgpu_c_bundled").
            initLibrary()

            // GPU.createInstance() is the correct entry point — NOT GPUInstance() constructor.
            val instance = GPU.createInstance()

            // requestAdapter() is suspend; throws WebGpuException on failure (never returns null).
            // PowerPreference and BackendType are IntDef annotations — no GPU prefix.
            val adapter = instance.requestAdapter(
                GPURequestAdapterOptions(
                    powerPreference = PowerPreference.HighPerformance,
                    backendType = BackendType.Vulkan
                )
            )

            // requestDevice() is suspend; throws WebGpuException on failure.
            // GPUDeviceDescriptor requires both callback executors as mandatory constructor params.
            // deviceLostCallback and uncapturedErrorCallback have no defaults despite being nullable.
            val device = adapter.requestDevice(
                GPUDeviceDescriptor(
                    deviceLostCallbackExecutor = Executor(Runnable::run),
                    uncapturedErrorCallbackExecutor = Executor(Runnable::run),
                    deviceLostCallback = DeviceLostCallback { _, reason, message ->
                        throw DeviceLostException(
                            /* device = */ null,
                            /* reason = */ reason,
                            /* message = */ message
                        )
                    },
                    uncapturedErrorCallback = UncapturedErrorCallback { _, type, message ->
                        throw WebGpuRuntimeException.create(type, message)
                    },
                )
            )

            // Dawn requires processEvents() polling for async callbacks to fire.
            // Without this, mapAndAwait() and other async operations hang indefinitely.
            val isClosing = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            fun scheduleProcess() {
                handler.postDelayed({
                    if (!isClosing.get()) {
                        instance.processEvents()
                        scheduleProcess()
                    }
                }, POLLING_DELAY_MS)
            }
            scheduleProcess()

            return GpuContext(
                instance = instance,
                adapter = adapter,
                device = device,
                queue = device.queue,
                eventHandler = handler,
                isClosing = isClosing,
            )
        }
    }

    /**
     * Release all GPU resources. Idempotent — safe to call multiple times.
     *
     * After [destroy], this context must not be used. A new [GpuContext] must be created
     * via [create] (which creates a fresh instance + adapter + device chain).
     *
     * Note: `GPUAdapter.requestDevice()` can only be called once per adapter. After the
     * device is destroyed, the adapter cannot produce another device. This is why we
     * destroy the instance and adapter here too — the entire chain is single-use.
     */
    fun destroy() {
        if (isClosing.getAndSet(true)) return // already closing

        // Remove pending processEvents callbacks
        eventHandler.removeCallbacksAndMessages(null)

        // device.destroy() — after this, adapter.requestDevice() would throw.
        device.destroy()
        // instance and adapter implement AutoCloseable
        instance.close()
        adapter.close()
    }
}
```

---

## 8. RadixSortShader — WGSL (isometric-webgpu)

### New file: `isometric-webgpu/.../sort/RadixSortShader.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.sort

/**
 * WGSL shader source for a 32-bit floating-point GPU radix sort.
 *
 * The sort operates on `SortKey` structs containing a depth float and the original
 * face index. Four passes (8 bits per pass) sort keys from least-significant to
 * most-significant byte.
 *
 * Each pass requires three dispatches:
 * 1. [COUNT_ENTRY_POINT] — count histogram of the current byte
 * 2. CPU-side prefix sum on histogram readback (Phase 1 simplicity; GPU scan in Phase 3)
 * 3. [SCATTER_ENTRY_POINT] — scatter keys to output buffer using prefix-summed offsets
 *
 * ## Float sort correctness
 *
 * IEEE 754 floats sort correctly as u32 bit patterns for positive values. All projected
 * Z depths in standard isometric scenes are positive (geometry above z = 0). For scenes
 * with negative depths, apply the sign-bit XOR before upload:
 *
 * ```
 * if (bits & 0x80000000u) != 0u { bits = bits ^ 0xFFFFFFFFu }
 * else { bits = bits ^ 0x80000000u }
 * ```
 */
internal object RadixSortShader {

    const val COUNT_ENTRY_POINT = "countPass"
    const val SCATTER_ENTRY_POINT = "scatterPass"
    const val WORKGROUP_SIZE = 256

    val WGSL: String = """
        struct SortKey {
            depth: f32,
            originalIndex: u32,
        }

        @group(0) @binding(0) var<storage, read>       keys_in:   array<SortKey>;
        @group(0) @binding(1) var<storage, read_write>  keys_out:  array<SortKey>;
        @group(0) @binding(2) var<storage, read_write>  histogram: array<atomic<u32>>;
        @group(0) @binding(3) var<uniform>              params:    RadixParams;

        struct RadixParams {
            count:    u32,
            bitShift: u32,  // 0, 8, 16, 24 for the four passes
        }

        const RADIX: u32 = 256u;

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn ${COUNT_ENTRY_POINT}(@builtin(global_invocation_id) id: vec3<u32>) {
            if (id.x >= params.count) { return; }
            let key = keys_in[id.x];
            let bits = bitcast<u32>(key.depth);
            let bucket = (bits >> params.bitShift) & 0xFFu;
            atomicAdd(&histogram[bucket], 1u);
        }

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn ${SCATTER_ENTRY_POINT}(@builtin(global_invocation_id) id: vec3<u32>) {
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

---

## 9. GpuDepthSorter (isometric-webgpu)

### New file: `isometric-webgpu/.../sort/GpuDepthSorter.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.sort

import androidx.webgpu.BufferBindingType
import androidx.webgpu.BufferUsage
import androidx.webgpu.Constants
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferBindingLayout
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUComputePipeline
import androidx.webgpu.GPUComputePipelineDescriptor
import androidx.webgpu.GPUComputeState
import androidx.webgpu.GPUPipelineLayout
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.MapMode
import androidx.webgpu.ShaderStage
import io.github.jayteealao.isometric.webgpu.GpuContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * GPU-accelerated radix sort for depth keys.
 *
 * Performs a 4-pass (8 bits/pass) radix sort on IEEE 754 float depth keys, producing
 * back-to-front sorted indices. Falls back to CPU sorting for small arrays (< [GPU_SORT_THRESHOLD]).
 *
 * ## Pipeline
 *
 * For each of the 4 passes (bitShift = 0, 8, 16, 24):
 * 1. Zero the histogram buffer
 * 2. Dispatch [RadixSortShader.COUNT_ENTRY_POINT] — count per-bucket occurrences
 * 3. Read back histogram, compute CPU-side exclusive prefix sum, re-upload
 * 4. Dispatch [RadixSortShader.SCATTER_ENTRY_POINT] — scatter keys to sorted positions
 * 5. Swap ping-pong buffers
 *
 * After 4 passes, read back the `originalIndex` field from sorted keys.
 *
 * ## Buffer readback
 *
 * Uses [GPUBuffer.mapAndAwait] (the suspend coroutine wrapper), NOT [GPUBuffer.mapAsync]
 * (which is callback-based). This requires [GpuContext]'s `processEvents()` polling to be
 * active — without it, `mapAndAwait` will hang indefinitely.
 *
 * @param ctx The GPU context providing device, queue, and event polling.
 */
class GpuDepthSorter(private val ctx: GpuContext) {

    // Lazy shader module creation — GPUShaderModuleDescriptor has no `code` field.
    // WGSL source is nested inside GPUShaderSourceWGSL.
    private val shaderModule: GPUShaderModule by lazy {
        ctx.device.createShaderModule(
            GPUShaderModuleDescriptor(
                shaderSourceWGSL = GPUShaderSourceWGSL(code = RadixSortShader.WGSL)
            )
        )
    }

    // Lazily built pipelines and bind group layout
    private var countPipeline: GPUComputePipeline? = null
    private var scatterPipeline: GPUComputePipeline? = null
    private var bindGroupLayout: GPUBindGroupLayout? = null

    /**
     * Sort depth keys on the GPU and return back-to-front indices.
     *
     * @param depthKeys One float depth key per face. Higher = further from viewer.
     * @return Indices into [depthKeys] in back-to-front (drawn-first) order.
     */
    suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray {
        if (depthKeys.size < GPU_SORT_THRESHOLD) {
            return cpuFallbackSort(depthKeys)
        }

        ensurePipelinesBuilt()

        val count = depthKeys.size
        // SortKey struct: f32 (depth) + u32 (originalIndex) = 8 bytes per key
        val keyByteSize = (count * SORT_KEY_BYTES).toLong()

        // Prepare input data: interleave depth + originalIndex
        val inputBuffer = ByteBuffer.allocateDirect(count * SORT_KEY_BYTES)
            .order(ByteOrder.nativeOrder())
        for (i in depthKeys.indices) {
            inputBuffer.putFloat(depthKeys[i])
            inputBuffer.putInt(i) // originalIndex
        }
        inputBuffer.rewind()

        // Create GPU buffers — ping-pong pair + histogram + params + readback
        // BufferUsage is IntDef (no GPU prefix). Combine with bitwise OR.
        val gpuBufferA = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                size = keyByteSize,
            )
        )
        val gpuBufferB = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                size = keyByteSize,
            )
        )
        val histogramByteSize = (RADIX * 4).toLong() // 256 × u32
        val histogramBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                size = histogramByteSize,
            )
        )
        val paramsBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Uniform or BufferUsage.CopyDst,
                size = 8L, // RadixParams: u32 count + u32 bitShift
            )
        )
        // Readback buffer for histogram (CPU prefix sum)
        val histogramReadback = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.MapRead or BufferUsage.CopyDst,
                size = histogramByteSize,
            )
        )
        // Readback buffer for final sorted keys
        val resultReadback = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.MapRead or BufferUsage.CopyDst,
                size = keyByteSize,
            )
        )

        // Upload input keys to buffer A
        ctx.queue.writeBuffer(gpuBufferA, 0L, inputBuffer)

        val workgroupCount = ceil(count.toFloat() / RadixSortShader.WORKGROUP_SIZE).toInt()

        // Four passes: bitShift = 0, 8, 16, 24
        var currentIn = gpuBufferA
        var currentOut = gpuBufferB

        for (pass in 0 until 4) {
            val bitShift = pass * 8

            // 1. Zero histogram buffer
            val zeroData = ByteBuffer.allocateDirect(RADIX * 4)
                .order(ByteOrder.nativeOrder())
            // ByteBuffer is zeroed by default in allocateDirect
            ctx.queue.writeBuffer(histogramBuffer, 0L, zeroData)

            // 2. Upload params
            val paramsData = ByteBuffer.allocateDirect(8)
                .order(ByteOrder.nativeOrder())
            paramsData.putInt(count)
            paramsData.putInt(bitShift)
            paramsData.rewind()
            ctx.queue.writeBuffer(paramsBuffer, 0L, paramsData)

            // 3. Dispatch count pass
            val countBindGroup = createBindGroup(currentIn, currentOut, histogramBuffer, paramsBuffer)
            val encoder1 = ctx.device.createCommandEncoder()
            val computePass1 = encoder1.beginComputePass()
            computePass1.setPipeline(countPipeline!!)
            computePass1.setBindGroup(0, countBindGroup)
            computePass1.dispatchWorkgroups(workgroupCount)
            computePass1.end()
            // Copy histogram to readback buffer
            encoder1.copyBufferToBuffer(histogramBuffer, 0L, histogramReadback, 0L, histogramByteSize)
            ctx.queue.submit(arrayOf(encoder1.finish()))

            // 4. CPU prefix sum on histogram
            // mapAndAwait is the suspend version (not mapAsync which is callback-based)
            histogramReadback.mapAndAwait(MapMode.Read, 0L, histogramByteSize)
            val histogramData = histogramReadback.getConstMappedRange(0L, histogramByteSize)
            val histogramInts = IntArray(RADIX)
            histogramData.asIntBuffer().get(histogramInts)
            histogramReadback.unmap()

            // Exclusive prefix sum
            val prefixSums = IntArray(RADIX)
            var sum = 0
            for (i in 0 until RADIX) {
                prefixSums[i] = sum
                sum += histogramInts[i]
            }

            // Upload prefix-summed histogram back
            val prefixData = ByteBuffer.allocateDirect(RADIX * 4)
                .order(ByteOrder.nativeOrder())
            prefixData.asIntBuffer().put(prefixSums)
            prefixData.rewind()
            ctx.queue.writeBuffer(histogramBuffer, 0L, prefixData)

            // 5. Dispatch scatter pass
            val scatterBindGroup = createBindGroup(currentIn, currentOut, histogramBuffer, paramsBuffer)
            val encoder2 = ctx.device.createCommandEncoder()
            val computePass2 = encoder2.beginComputePass()
            computePass2.setPipeline(scatterPipeline!!)
            computePass2.setBindGroup(0, scatterBindGroup)
            computePass2.dispatchWorkgroups(workgroupCount)
            computePass2.end()
            ctx.queue.submit(arrayOf(encoder2.finish()))

            // 6. Swap ping-pong
            val temp = currentIn
            currentIn = currentOut
            currentOut = temp
        }

        // Read back sorted indices from currentIn (result of last pass)
        val finalEncoder = ctx.device.createCommandEncoder()
        finalEncoder.copyBufferToBuffer(currentIn, 0L, resultReadback, 0L, keyByteSize)
        ctx.queue.submit(arrayOf(finalEncoder.finish()))

        resultReadback.mapAndAwait(MapMode.Read, 0L, keyByteSize)
        val resultData = resultReadback.getConstMappedRange(0L, keyByteSize)
        val sortedIndices = IntArray(count)
        for (i in 0 until count) {
            resultData.position(i * SORT_KEY_BYTES + 4) // skip depth float, read originalIndex
            sortedIndices[i] = resultData.getInt()
        }
        resultReadback.unmap()

        // Cleanup GPU buffers
        gpuBufferA.destroy()
        gpuBufferB.destroy()
        histogramBuffer.destroy()
        paramsBuffer.destroy()
        histogramReadback.destroy()
        resultReadback.destroy()

        return sortedIndices
    }

    private fun ensurePipelinesBuilt() {
        if (countPipeline != null) return

        // Bind group layout: 4 bindings
        //   @binding(0): storage read    (keys_in)
        //   @binding(1): storage r/w     (keys_out)
        //   @binding(2): storage r/w     (histogram)
        //   @binding(3): uniform         (params)
        //
        // GPUBindGroupLayoutEntry constructor:
        //   binding: Int, visibility: Int (ShaderStage IntDef), ...
        val layout = ctx.device.createBindGroupLayout(
            GPUBindGroupLayoutDescriptor(
                entries = arrayOf(
                    GPUBindGroupLayoutEntry(
                        binding = 0,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                    ),
                    GPUBindGroupLayoutEntry(
                        binding = 1,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Storage),
                    ),
                    GPUBindGroupLayoutEntry(
                        binding = 2,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Storage),
                    ),
                    GPUBindGroupLayoutEntry(
                        binding = 3,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Uniform),
                    ),
                )
            )
        )
        bindGroupLayout = layout

        val pipelineLayout = ctx.device.createPipelineLayout(
            GPUPipelineLayoutDescriptor(
                bindGroupLayouts = arrayOf(layout)
            )
        )

        // GPUComputePipelineDescriptor requires GPUComputeState (not a raw entry point string).
        countPipeline = ctx.device.createComputePipeline(
            GPUComputePipelineDescriptor(
                compute = GPUComputeState(
                    module = shaderModule,
                    entryPoint = RadixSortShader.COUNT_ENTRY_POINT,
                ),
                layout = pipelineLayout,
            )
        )

        scatterPipeline = ctx.device.createComputePipeline(
            GPUComputePipelineDescriptor(
                compute = GPUComputeState(
                    module = shaderModule,
                    entryPoint = RadixSortShader.SCATTER_ENTRY_POINT,
                ),
                layout = pipelineLayout,
            )
        )
    }

    private fun createBindGroup(
        keysIn: GPUBuffer,
        keysOut: GPUBuffer,
        histogram: GPUBuffer,
        params: GPUBuffer,
    ): GPUBindGroup {
        // GPUBindGroupDescriptor: layout (required), entries: Array<GPUBindGroupEntry>
        // GPUBindGroupEntry: binding (required), buffer, offset, size
        return ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = bindGroupLayout!!,
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, buffer = keysIn),
                    GPUBindGroupEntry(binding = 1, buffer = keysOut),
                    GPUBindGroupEntry(binding = 2, buffer = histogram),
                    GPUBindGroupEntry(binding = 3, buffer = params),
                )
            )
        )
    }

    companion object {
        /** Below this count, CPU sort is faster than GPU dispatch overhead. */
        const val GPU_SORT_THRESHOLD = 64

        private const val RADIX = 256
        private const val SORT_KEY_BYTES = 8 // f32 depth + u32 originalIndex

        /**
         * CPU fallback sort — descending by depth key (back-to-front).
         * Used when GPU is unavailable or array is too small.
         */
        fun cpuFallbackSort(keys: FloatArray): IntArray =
            keys.indices.sortedByDescending { keys[it] }.toIntArray()
    }
}
```

---

## 10. WebGpuComputeBackend (isometric-webgpu)

### New file: `isometric-webgpu/.../WebGpuComputeBackend.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu

import io.github.jayteealao.isometric.SortingComputeBackend
import io.github.jayteealao.isometric.webgpu.sort.GpuDepthSorter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GPU-accelerated compute backend using WebGPU radix sort.
 *
 * Lazily initializes a [GpuContext] on first use, protected by a [Mutex] to prevent
 * concurrent coroutines from racing to create multiple GPU devices.
 *
 * Falls back to CPU sorting when:
 * - GPU initialization fails (no Vulkan support, emulator, etc.)
 * - GPU sort throws at runtime (driver bug, timeout, etc.)
 * - Array size < [GpuDepthSorter.GPU_SORT_THRESHOLD] (CPU is faster for small arrays)
 *
 * ## Usage
 *
 * ```kotlin
 * IsometricScene(config = SceneConfig(computeBackend = ComputeBackend.WebGpu)) { ... }
 * ```
 *
 * `ComputeBackend.WebGpu` is defined as a companion extension property in this module:
 * ```kotlin
 * val ComputeBackend.Companion.WebGpu: SortingComputeBackend
 *     get() = WebGpuComputeBackend()
 * ```
 */
class WebGpuComputeBackend : SortingComputeBackend {
    override val isAsync: Boolean = true

    private val initMutex = Mutex()
    private var gpuContext: GpuContext? = null
    private var gpuSorter: GpuDepthSorter? = null
    private var initAttempted = false

    /**
     * Lazy, mutex-guarded GPU initialization.
     *
     * Concurrent coroutines don't race to create multiple GPU devices.
     * After first attempt (success or failure), returns the cached result.
     */
    private suspend fun ensureContext(): GpuDepthSorter? = initMutex.withLock {
        if (initAttempted) return@withLock gpuSorter
        initAttempted = true
        gpuContext = runCatching { GpuContext.create() }.getOrNull()
        gpuSorter = gpuContext?.let { GpuDepthSorter(it) }
        gpuSorter
    }

    override suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray {
        val sorter = ensureContext()
            ?: return GpuDepthSorter.cpuFallbackSort(depthKeys)

        return runCatching { sorter.sortByDepthKeys(depthKeys) }
            .getOrElse { GpuDepthSorter.cpuFallbackSort(depthKeys) }
    }

    override fun toString(): String = "ComputeBackend.WebGpu"
}
```

### Extension property for `ComputeBackend.Companion`

```kotlin
// New file: isometric-webgpu/.../ComputeBackendExtensions.kt
package io.github.jayteealao.isometric.webgpu

import io.github.jayteealao.isometric.ComputeBackend
import io.github.jayteealao.isometric.SortingComputeBackend

/**
 * GPU-accelerated compute backend using WebGPU radix sort.
 *
 * Available only when the `isometric-webgpu` artifact is on the classpath.
 * If it is absent, `ComputeBackend.WebGpu` is a compile error — not a runtime crash.
 *
 * This follows the extension-on-companion-object pattern from §9 (Escape Hatches) of
 * the API design guideline: reads identically to `ComputeBackend.Cpu` at the call site,
 * with zero magic.
 */
val ComputeBackend.Companion.WebGpu: SortingComputeBackend
    get() = WebGpuComputeBackend()
```

---

## 11. IsometricScene Async Integration (isometric-compose)

### Changes to `IsometricScene.kt` (advanced overload)

The async path activates when `config.computeBackend.isAsync` is `true`. It uses a
`LaunchedEffect` keyed on `sceneVersion` + canvas dimensions to run `renderer.prepareAsync()`
on `Dispatchers.Default`, then publishes the result to a `MutableState<PreparedScene?>`.

The Canvas lambda switches which state it subscribes to, so the synchronous path is
completely unchanged.

```kotlin
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    config: AdvancedSceneConfig,
    content: @Composable IsometricScope.() -> Unit
) {
    // ... existing setup code unchanged through line 161 ...

    // NEW: Async prepared scene state for GPU compute path.
    // Only used when config.computeBackend.isAsync is true.
    val asyncPreparedScene = remember { mutableStateOf<PreparedScene?>(null) }

    // NEW: Async compute path — runs prepareAsync() on Dispatchers.Default when dirty.
    // Only activates for SortingComputeBackend. The CPU path is untouched.
    if (config.computeBackend.isAsync && config.computeBackend is SortingComputeBackend) {
        val computeBackend = config.computeBackend as SortingComputeBackend
        LaunchedEffect(sceneVersion, canvasWidth, canvasHeight) {
            if (canvasWidth <= 0 || canvasHeight <= 0) return@LaunchedEffect
            withContext(Dispatchers.Default) {
                renderer.prepareAsync(
                    rootNode = rootNode,
                    context = renderContext,
                    width = canvasWidth,
                    height = canvasHeight,
                    computeBackend = computeBackend,
                )
            }
            // Publish to state on the main thread (LaunchedEffect returns to main).
            asyncPreparedScene.value = renderer.currentPreparedScene
        }
    }

    // Render to canvas with gesture handling.
    // ... existing gesturesActive logic unchanged ...
    Canvas(
        modifier = modifier.then(/* ... pointer input unchanged ... */)
    ) {
        // Switch state subscription based on compute backend.
        if (config.computeBackend.isAsync) {
            // GPU path: subscribe to asyncPreparedScene state changes.
            // The Canvas redraws when the LaunchedEffect publishes a new snapshot.
            @Suppress("UNUSED_EXPRESSION")
            asyncPreparedScene.value
        } else {
            // CPU path: subscribe to sceneVersion — unchanged from current behavior.
            @Suppress("UNUSED_EXPRESSION")
            sceneVersion
        }

        @Suppress("UNUSED_EXPRESSION")
        config.frameVersion

        canvasWidth = size.width.toInt()
        canvasHeight = size.height.toInt()

        if (canvasWidth > 0 && canvasHeight > 0) {
            val cameraState = config.cameraState
            if (cameraState != null) {
                // ... camera transform unchanged ...
            }

            config.onBeforeDraw?.invoke(this)

            if (config.computeBackend.isAsync) {
                // GPU path: render from the async-prepared scene snapshot.
                // No ensurePreparedScene() call — the LaunchedEffect already prepared it.
                val scene = asyncPreparedScene.value
                if (scene != null) {
                    with(renderer) {
                        renderFromScene(scene, config.strokeStyle)
                    }
                }
            } else {
                // CPU path: synchronous prepare + render — unchanged.
                with(renderer) {
                    if (config.useNativeCanvas) {
                        renderNative(
                            rootNode = rootNode,
                            context = renderContext,
                            strokeStyle = config.strokeStyle
                        )
                    } else {
                        render(
                            rootNode = rootNode,
                            context = renderContext,
                            strokeStyle = config.strokeStyle
                        )
                    }
                }
            }

            config.onAfterDraw?.invoke(this)
        }
    }
}
```

---

## 12. SceneCache + IsometricRenderer Async Additions (isometric-compose)

### `SceneCache` — new `rebuildAsync` method

```kotlin
// In SceneCache.kt — new suspend function

/**
 * Async variant of [rebuild] that uses a [SortingComputeBackend] for depth sorting.
 *
 * Collects render commands synchronously (same as [rebuild]), then calls
 * [SceneProjector.projectSceneAsync] which delegates depth sorting to the GPU.
 *
 * Must be called from a coroutine context (GPU sort is always async).
 */
suspend fun rebuildAsync(
    rootNode: GroupNode,
    context: RenderContext,
    width: Int,
    height: Int,
    computeBackend: SortingComputeBackend,
    onRenderError: ((String, Throwable) -> Unit)?
): PreparedScene? {
    return try {
        val commands = mutableListOf<RenderCommand>()
        rootNode.renderTo(commands, context)

        engine.clear()
        commands.forEach { command ->
            engine.add(
                path = command.originalPath,
                color = command.color,
                originalShape = command.originalShape,
                id = command.commandId,
                ownerNodeId = command.ownerNodeId
            )
        }

        // Async projection — delegates depth sorting to the GPU via computeBackend.
        val scene = engine.projectSceneAsync(
            width = width,
            height = height,
            renderOptions = context.renderOptions,
            lightDirection = context.lightDirection,
            computeBackend = computeBackend,
        )

        currentPreparedScene = scene
        cachedWidth = width
        cachedHeight = height
        cachedPrepareInputs = PrepareInputs(context.renderOptions, context.lightDirection)
        cachedProjectionVersion = engine.projectionVersion

        if (enablePathCaching) {
            buildPathCache(scene)
        }

        rootNode.markClean()
        cacheValid = true

        scene
    } catch (e: Exception) {
        onRenderError?.invoke("rebuildAsync", e)
        null
    }
}
```

### `IsometricRenderer` — new async methods

```kotlin
// In IsometricRenderer.kt — new methods

/**
 * Async scene preparation using a [SortingComputeBackend] for GPU-accelerated depth sorting.
 *
 * Must be called from a coroutine context (typically `Dispatchers.Default` via `withContext`).
 * The result is stored in [currentPreparedScene] and can be drawn with [renderFromScene].
 */
suspend fun prepareAsync(
    rootNode: GroupNode,
    context: RenderContext,
    width: Int,
    height: Int,
    computeBackend: SortingComputeBackend,
) {
    check(!closed) { "Renderer has been closed and cannot be used for rendering" }
    if (width <= 0 || height <= 0) return
    if (forceRebuild) clearCache()

    if (cache.needsUpdate(rootNode, context, width, height)) {
        benchmarkHooks?.onCacheMiss()
        benchmarkHooks?.onPrepareStart()
        val scene = cache.rebuildAsync(rootNode, context, width, height, computeBackend, onRenderError)
        if (scene != null) {
            hitTestResolver.rebuildIndices(rootNode, scene)
        }
        benchmarkHooks?.onPrepareEnd()
    } else {
        benchmarkHooks?.onCacheHit()
    }
}

/**
 * Draw a pre-built scene without running the prepare step.
 *
 * Used by the GPU compute path: the `LaunchedEffect` prepares the scene via [prepareAsync],
 * then the Canvas lambda draws it via this method.
 */
fun DrawScope.renderFromScene(
    scene: PreparedScene,
    strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
) {
    check(!closed) { "Renderer has been closed and cannot be used for rendering" }

    benchmarkHooks?.onDrawStart()
    renderPreparedScene(
        scene = scene,
        strokeStyle = strokeStyle,
        strokeComposeColor = when (strokeStyle) {
            is StrokeStyle.FillOnly -> null
            is StrokeStyle.Stroke -> strokeStyle.color.toComposeColor()
            is StrokeStyle.FillAndStroke -> strokeStyle.color.toComposeColor()
        },
        strokeDrawStyle = when (strokeStyle) {
            is StrokeStyle.FillOnly -> null
            is StrokeStyle.Stroke -> Stroke(width = strokeStyle.width)
            is StrokeStyle.FillAndStroke -> Stroke(width = strokeStyle.width)
        },
    )
    benchmarkHooks?.onDrawEnd()
}

/**
 * Native-canvas variant of [renderFromScene].
 */
fun DrawScope.renderNativeFromScene(
    scene: PreparedScene,
    strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
) {
    check(!closed) { "Renderer has been closed and cannot be used for rendering" }

    benchmarkHooks?.onDrawStart()
    with(nativeRenderer) {
        renderNative(scene, strokeStyle, onRenderError)
    }
    benchmarkHooks?.onDrawEnd()
}
```

---

## 13. SceneProjector + IsometricEngine Async Additions (isometric-core)

### `SceneProjector` — new default method

```kotlin
// In SceneProjector.kt — new default method

/**
 * Async variant of [projectScene] that uses a [SortingComputeBackend] for depth sorting.
 *
 * Default implementation ignores the compute backend and delegates to the synchronous
 * [projectScene]. Override in [IsometricEngine] to use the GPU sort.
 */
suspend fun projectSceneAsync(
    width: Int,
    height: Int,
    renderOptions: RenderOptions = RenderOptions.Default,
    lightDirection: Vector = DEFAULT_LIGHT_DIRECTION.normalize(),
    computeBackend: SortingComputeBackend,
): PreparedScene = projectScene(width, height, renderOptions, lightDirection)
```

### `IsometricEngine` — override for async projection

```kotlin
// In IsometricEngine.kt — override

/**
 * Async projection with GPU-accelerated depth sorting.
 *
 * Extracts a scalar depth key per face (`Point.depth = x + y - 2z` averaged over vertices),
 * delegates to [SortingComputeBackend.sortByDepthKeys] for GPU radix sort, then reorders
 * the transformed items by the returned indices.
 *
 * The depth key formula from the engine KDoc:
 *   `depthKey(face) = average of (pt.x + pt.y - 2 * pt.z) over all 3D vertices`
 *   Higher value = further from viewer = drawn first.
 */
override suspend fun projectSceneAsync(
    width: Int,
    height: Int,
    renderOptions: RenderOptions,
    lightDirection: Vector,
    computeBackend: SortingComputeBackend,
): PreparedScene {
    val normalizedLight = lightDirection.normalize()
    val originX = width / 2.0
    val originY = height * 0.9

    // Transform all items to 2D screen space (same as synchronous path)
    val transformedItems = sceneGraph.items.mapNotNull { item ->
        projectAndCull(item, originX, originY, renderOptions, normalizedLight, width, height)
    }

    // Extract depth keys for GPU sort
    val depthKeys = FloatArray(transformedItems.size) { i ->
        val item = transformedItems[i].item
        // Point.depth = x + y - 2z; average over all vertices of the face
        val avgDepth = item.path.points.sumOf { pt -> pt.x + pt.y - 2 * pt.z } / item.path.points.size
        avgDepth.toFloat()
    }

    // GPU radix sort — returns back-to-front indices
    val sortedIndices = computeBackend.sortByDepthKeys(depthKeys)

    // Reorder by GPU-sorted indices
    val sortedItems = IntArray(sortedIndices.size) { sortedIndices[it] }
        .map { transformedItems[it] }

    // Convert to render commands (same as synchronous path)
    val commands = sortedItems.map { transformedItem ->
        RenderCommand(
            commandId = transformedItem.item.id,
            points = transformedItem.transformedPoints,
            color = transformedItem.litColor,
            originalPath = transformedItem.item.path,
            originalShape = transformedItem.item.originalShape,
            ownerNodeId = transformedItem.item.ownerNodeId
        )
    }

    return PreparedScene(commands, width, height)
}
```

---

## 14. IsometricEngine DepthSorter Delegation (isometric-core)

### Minor refactoring in `IsometricEngine.kt`

The existing `projectScene()` method calls `DepthSorter.sort(transformedItems, renderOptions)`.
This is an internal utility class, not the new interface. No change needed to the synchronous
path — it continues to use the internal `DepthSorter` directly.

The async path (§13) bypasses the internal `DepthSorter` entirely and uses the
`SortingComputeBackend` for sorting. This is intentional: the GPU sort produces indices,
not reordered items, so the code paths are structurally different.

---

## 15. Test Plan

### Unit Tests (JVM, no GPU)

| Test Class | Coverage |
|---|---|
| `CpuDepthSorterTest` | Sorts known face lists; output matches `IsometricEngine.projectScene()` order |
| `GpuDepthSorterFallbackTest` | `depthKeys.size < 64` returns CPU-sorted indices; verify no GPU calls via mock |
| `RadixSortShaderParseTest` | WGSL string has correct entry points and struct layout (string parsing, no GPU) |
| `ComputeBackendCpuTest` | `ComputeBackend.Cpu.isAsync == false` |
| `WebGpuComputeBackendFallbackTest` | When `GpuContext.create()` throws, `sortByDepthKeys` returns CPU fallback |
| `CanvasRenderBackendTest` | `CanvasRenderBackend.Surface()` draws `PreparedScene.commands` with correct stroke styles |
| `SceneConfigNewFieldsTest` | Default `SceneConfig()` has `renderBackend == RenderBackend.Canvas` and `computeBackend == ComputeBackend.Cpu` |

### Instrumented Tests (physical device, GPU required)

| Test Class | Coverage |
|---|---|
| `GpuContextCreateTest` | `GpuContext.create()` succeeds on Vulkan device; `destroy()` is idempotent |
| `GpuDepthSorterGpuTest` | Sort 1000 random depth keys; output is descending order; matches CPU fallback |
| `WebGpuComputeBackendE2ETest` | `IsometricScene(config = SceneConfig(computeBackend = ComputeBackend.WebGpu))` renders a 3-shape scene without crash |
| `RadixSortShaderDeviceTest` | `GPUDevice.createShaderModule(RadixSortShader.WGSL)` succeeds on real device |

### Canvas Regression Guard

```bash
./gradlew :isometric-benchmark:connectedAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=CanvasRenderBenchmark
```

Must pass with < 5% regression on N=100 and N=200 prepare-time benchmarks.
