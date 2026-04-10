# WebGPU Benchmark Timing Instrumentation

**Date:** 2026-04-03
**Status:** Draft
**Branch:** `feat/webgpu` (continuation of benchmark harness integration)

## Problem

The benchmark harness collects four per-frame timing metrics: **prepare**, **draw**, **frame**, and **hitTest**. In Canvas render modes (CPU and WebGPU compute), these are captured by `BenchmarkHooksImpl` callbacks wired into `IsometricRenderer`'s draw pass. In **full WebGPU render mode** (`RenderMode.WebGpu`), the render loop runs inside `WebGpuSceneRenderer.renderLoop()` on `Dispatchers.Default`, completely bypassing the Canvas `DrawScope` where hooks fire. This causes two failures:

1. **No timing data** — `onPrepareStart/End` and `onDrawStart/End` never fire, so `prepareTimes[]` and `drawTimes[]` are all zeros.
2. **Frame sync deadlock** — `BenchmarkOrchestrator.awaitNextDraw()` spins waiting for `drawPassCount` to increment, but `onDrawEnd()` never fires, so the benchmark hangs.

Additionally, the Canvas path only provides CPU wall-clock timing. For WebGPU, we also want **GPU-side timing** via timestamp queries to measure actual shader execution time independently of CPU/driver overhead.

## Goals

1. **Backward-compatible output** — CSV and JSON schemas are unchanged. Existing columns (`avgPrepareMs`, `avgDrawMs`, `avgFrameMs`, etc.) are populated for all three render modes using the same units and semantics, so results are directly comparable.
2. **CPU wall-clock timing (Option A)** — works on all devices, provides the same prepare/draw/frame breakdown as Canvas modes.
3. **GPU timestamp queries (Option B)** — optional per-pass GPU timing when the device supports `FeatureName.TimestampQuery`. Appears as **additional** columns/fields in the output, never replacing the core metrics.
4. **Minimal cross-module coupling** — `isometric-webgpu` defines a callback interface; `isometric-benchmark` provides the implementation. No benchmark code leaks into the library.

## Architecture

### Current data flow (Canvas path, working)

```
BenchmarkScreen
  → CompositionLocalProvider(LocalBenchmarkHooks provides benchmarkHooks)
    → IsometricScene
        renderer.benchmarkHooks = hooks (via DisposableEffect)
      → Canvas lambda → renderer.render()
          ensurePreparedScene() → hooks.onPrepareStart/End, onCacheHit/Miss
          hooks.onDrawStart()
          renderPreparedScene()
          hooks.onDrawEnd() → drawPassCount++
            → orchestrator.awaitNextDraw() unblocks
```

### Target data flow (WebGPU path)

```
BenchmarkScreen
  → CompositionLocalProvider(LocalBenchmarkHooks provides benchmarkHooks)
    → IsometricScene
        // Prepare hooks still fire via renderer.prepareScene() (existing, works today)
        renderer.benchmarkHooks = hooks
      → webGpuRenderBackend.Surface(frameCallback = webGpuFrameCallback)
          → WebGpuSceneRenderer.renderLoop()
              // Option A: CPU wall-clock
              callback.onUploadStart()
              uploadScene(scene)
              callback.onUploadEnd()
              callback.onDrawFrameStart()
              drawFrame(surface)
              callback.onDrawFrameEnd() → drawPassCount++

              // Option B: GPU timestamps (inside drawFrame, when available)
              computePass.timestampWrites = GPUPassTimestampWrites(querySet, 0, 1)
              renderPass.timestampWrites  = GPUPassTimestampWrites(querySet, 2, 3)
              resolveQuerySet → readback → callback.onGpuTimestamps(compute, render)
```

## Detailed Design

### M1: CPU Wall-Clock Timing (Option A)

#### M1.1: Define `WebGpuFrameCallback` interface

**File:** `isometric-webgpu/src/main/kotlin/.../webgpu/WebGpuFrameCallback.kt` (new)

```kotlin
package io.github.jayteealao.isometric.webgpu

/**
 * Callback interface for frame-level timing in the WebGPU render loop.
 *
 * Called from WebGpuSceneRenderer.renderLoop() on Dispatchers.Default.
 * All methods have no-op defaults so non-benchmark callers can ignore them.
 */
interface WebGpuFrameCallback {
    /** Called before uploadScene() — CPU scene packing + GPU buffer upload. */
    fun onUploadStart() {}
    /** Called after uploadScene() completes. */
    fun onUploadEnd() {}
    /** Called before drawFrame() — compute dispatch + render pass + present. */
    fun onDrawFrameStart() {}
    /** Called after drawFrame() completes (after present()). */
    fun onDrawFrameEnd() {}

    /**
     * Called with GPU timestamp results when available (Option B).
     * All durations in nanoseconds. Values are -1 if timestamps unavailable.
     */
    fun onGpuTimestamps(
        computeNanos: Long,
        renderNanos: Long,
    ) {}
}
```

**Why a new interface instead of reusing `RenderBenchmarkHooks`:**
- `RenderBenchmarkHooks` is defined in `isometric-compose`. `isometric-webgpu` does not depend on `isometric-compose`, and adding that dependency would create a circular path.
- The semantic split is different: `uploadScene` ≠ `prepare` (prepare happens earlier in `IsometricScene`'s `LaunchedEffect`), and `drawFrame` includes both compute and render. Using distinct names (`onUploadStart` vs `onPrepareStart`) prevents confusion about what's being measured.

#### M1.2: Wire callback into `WebGpuSceneRenderer`

**File:** `isometric-webgpu/.../WebGpuSceneRenderer.kt`

Changes:
1. Add parameter `frameCallback: WebGpuFrameCallback = object : WebGpuFrameCallback {}` to `renderLoop()`
2. Wrap `uploadScene()` call with `frameCallback.onUploadStart()` / `frameCallback.onUploadEnd()`
3. Wrap `drawFrame()` call with `frameCallback.onDrawFrameStart()` / `frameCallback.onDrawFrameEnd()`

The callback fires on every iteration of the render loop, including frames where the scene hasn't changed (in that case `uploadScene` is skipped but `drawFrame` still runs — the callback still fires to keep frame counting consistent).

Specifically in the loop body (current lines ~125-145):

```kotlin
// Before:
if (scene !== lastScene || viewportChanged) {
    uploadScene(scene)
}
drawFrame(androidSurface)

// After:
if (scene !== lastScene || viewportChanged) {
    frameCallback.onUploadStart()
    uploadScene(scene)
    frameCallback.onUploadEnd()
}
frameCallback.onDrawFrameStart()
drawFrame(androidSurface)
frameCallback.onDrawFrameEnd()
```

#### M1.3: Thread `frameCallback` through `WebGpuRenderBackend.Surface()`

**File:** `isometric-webgpu/.../WebGpuRenderBackend.kt`

Add `frameCallback: WebGpuFrameCallback` parameter to `Surface()` composable. Pass it through to `renderer.renderLoop(...)`.

**File:** `isometric-compose/.../render/RenderBackend.kt`

The `RenderBackend` interface's `Surface()` method needs a way to accept the callback. Options:
- **Option i:** Add `frameCallback` parameter to `RenderBackend.Surface()` with a default no-op. Simple, slightly leaky (Canvas backend ignores it).
- **Option ii:** Cast-based — `IsometricScene` checks `if (backend is WebGpuRenderBackend)` and passes callback through a backend-specific method. Avoids changing the interface.

**Recommended:** Option i — add the parameter to `RenderBackend.Surface()` with a default no-op value. The parameter type is `Any?` to avoid a dependency on `isometric-webgpu` types (the `RenderBackend` interface is in `isometric-compose`). The WebGPU backend casts it internally. This is ugly but pragmatic — or we can define `WebGpuFrameCallback` in `isometric-core` (which both modules depend on) if we want type safety.

**Better alternative:** Define `WebGpuFrameCallback` in `isometric-core` since it's a pure interface with no dependencies. Then `RenderBackend.Surface()` can have a typed `frameCallback: WebGpuFrameCallback?` parameter.

#### M1.4: Bridge hooks in `IsometricScene`

**File:** `isometric-compose/.../runtime/IsometricScene.kt`

In the `!isCanvasBackend` branch (WebGPU render path), create an adapter that bridges `WebGpuFrameCallback` → `RenderBenchmarkHooks` + `MetricsCollector`:

```kotlin
val webGpuFrameCallback = remember(currentBenchmarkHooks) {
    if (currentBenchmarkHooks == null) null
    else object : WebGpuFrameCallback {
        override fun onUploadStart() {
            // uploadScene timing maps to "prepare" in the metrics
            // (Note: renderer.prepareScene() already fires onPrepareStart/End
            //  for CPU scene preparation. uploadScene is the GPU buffer upload
            //  portion. We record it as prepare time since the existing
            //  onPrepareStart/End won't fire in the WebGPU draw path.)
        }
        override fun onUploadEnd() {}
        override fun onDrawFrameStart() {
            currentBenchmarkHooks!!.onDrawStart()
        }
        override fun onDrawFrameEnd() {
            currentBenchmarkHooks!!.onDrawEnd()
            // This increments drawPassCount → unblocks awaitNextDraw()
        }
    }
}
```

**Timing semantics mapping:**

| Metric | Canvas path (existing) | WebGPU path (new) |
|--------|----------------------|-------------------|
| `prepareTimes[]` | `onPrepareStart→End` in `ensurePreparedScene()` | `onPrepareStart→End` in `renderer.prepareScene()` (already works — fires in the `LaunchedEffect`) |
| `drawTimes[]` | `onDrawStart→End` in `DrawScope.render()` | `onDrawFrameStart→End` wrapping `drawFrame()` (via adapter above) |
| `frameTimes[]` | Orchestrator's Choreographer vsync interval | Same — unchanged |
| `drawPassCount` | Incremented in `onDrawEnd()` | Incremented in `onDrawFrameEnd()` adapter |

**Key insight:** `prepareTimes[]` already works for WebGPU because `renderer.prepareScene()` (called in the `LaunchedEffect` at IsometricScene line ~400) fires `onPrepareStart/End` through the existing `renderer.benchmarkHooks`. We do NOT need to double-count `uploadScene` as prepare — it's part of the GPU draw pipeline. The adapter only needs to bridge the draw timing and frame sync.

Wait — re-examining this: In the Canvas path, `ensurePreparedScene()` (which fires prepare hooks) is called *inside* the `DrawScope`, so prepare and draw are both captured per-frame within the same draw pass. In the WebGPU path, `renderer.prepareScene()` runs in a separate `LaunchedEffect` coroutine, which may not align 1:1 with render loop frames. This means:

- `prepareScene()` fires once when the scene changes → prepare hooks fire once
- `renderLoop()` runs every frame → draw hooks fire every frame
- For static scenes (mutationRate=0), prepare fires once during warmup, then never again during measurement

This is actually fine for benchmarking: **prepare time measures the cost of CPU scene projection**, which only happens when the scene changes. For frames where the scene hasn't changed, prepare time is correctly 0 (cache hit). The Canvas path behaves the same way when `enablePreparedSceneCache=true`.

**However**, for the WebGPU path there's a subtlety: `uploadScene()` (GPU buffer upload) happens inside the render loop and has real cost even though it's technically "prepare" work. We should capture this. Two approaches:

1. **Fold upload into draw** — simplest, slightly inflates draw time. Upload is fast (~0.1ms) so the distortion is minimal.
2. **Record upload separately** — add `uploadTimes[]` to MetricsCollector. Cleanest but changes the output schema.

**Decision: Fold upload into draw for M1.** The upload cost is small relative to compute+render, and keeping the schema identical preserves backward compatibility. If upload cost becomes interesting later, we can add it as an optional field (like GPU timestamps).

#### M1.5: Fix `onFrame` / `frameVersion` for WebGPU

The orchestrator calls `onFrame()` → `frameVersion++` to trigger Compose recomposition, which the Canvas path uses to force a redraw of static scenes. The WebGPU path doesn't read `frameVersion`. However, the WebGPU render loop runs continuously anyway (it calls `drawFrame()` every iteration regardless of whether the scene changed), so this isn't actually a problem. The render loop doesn't need external invalidation signals.

But the orchestrator's frame loop structure needs adjustment for WebGPU:

```kotlin
// Current (Canvas path):
framePacer.awaitNextFrame { ... }     // wait for vsync
awaitNextDraw(framePacer, ...)        // wait for draw pass to complete

// Problem in WebGPU:
// The render loop runs independently of Choreographer. awaitNextDraw will
// detect when drawFrame completes (via the adapter incrementing drawPassCount),
// but the Choreographer vsync and the WebGPU present are not synchronized.
```

**This actually works correctly as-is.** The orchestrator's frame pacing:
1. `awaitNextFrame` waits for vsync (Choreographer callback)
2. Inside the callback, it records frame time and applies mutations
3. `awaitNextDraw` waits for `drawPassCount` to increment

In WebGPU mode, the render loop runs on a background thread. Mutations applied in step 2 update the SnapshotStateList, which triggers `renderer.prepareScene()` in its LaunchedEffect, which updates `backendPreparedSceneState`, which the render loop picks up on its next iteration. The `awaitNextDraw` spin loop will unblock once `drawFrame()` completes (because the adapter increments `drawPassCount`).

The frame cadence may differ (render loop may run faster or slower than vsync), but `awaitNextDraw` ensures the orchestrator doesn't count a frame until the GPU has actually drawn it. This is correct behavior.

#### M1.6: Handle `uploadScene` skip (no scene change)

When the scene hasn't changed between frames, `renderLoop()` skips `uploadScene()` but still calls `drawFrame()`. The callback should still fire for draw:

```kotlin
// In renderLoop():
val sceneChanged = scene !== lastScene || viewportChanged
if (sceneChanged) {
    frameCallback.onUploadStart()
    uploadScene(scene)
    frameCallback.onUploadEnd()
}
// drawFrame always runs
frameCallback.onDrawFrameStart()
drawFrame(androidSurface)
frameCallback.onDrawFrameEnd()
```

This means `onUploadStart/End` may fire 0 or 1 times per frame, while `onDrawFrameStart/End` always fire. The MetricsCollector records prepare time as 0 for frames with no upload, which matches the Canvas path's behavior for cache hits.

---

### M2: GPU Timestamp Queries (Option B)

#### M2.1: Request `TimestampQuery` feature

**File:** `isometric-webgpu/.../GpuContext.kt`

Modify `buildDeviceDescriptor()` to conditionally request the timestamp query feature:

```kotlin
private fun buildDeviceDescriptor(
    lastFailure: AtomicReference<Throwable?>,
    enableTimestamps: Boolean = false,
): GPUDeviceDescriptor {
    val features = if (enableTimestamps) intArrayOf(FeatureName.TimestampQuery) else intArrayOf()
    return GPUDeviceDescriptor(
        requiredFeatures = features,
        // ... existing callbacks ...
    )
}
```

The adapter must also be checked for timestamp support before requesting it:

```kotlin
val supportsTimestamps = adapter.features.contains(FeatureName.TimestampQuery)
```

**Note:** If the device doesn't support timestamps and we request them, `requestDevice` will fail. So the flow is:
1. Request adapter (as today)
2. Check `adapter.features` for `TimestampQuery`
3. If supported AND benchmark requested timestamps, include in `requiredFeatures`
4. Expose `val supportsTimestamps: Boolean` on `GpuContext`

#### M2.2: Create `GpuTimestampProfiler`

**File:** `isometric-webgpu/.../pipeline/GpuTimestampProfiler.kt` (new)

Encapsulates all timestamp query management:

```kotlin
internal class GpuTimestampProfiler(
    private val device: GPUDevice,
) : AutoCloseable {
    // Query set: 2 slots per compute pass × 3 passes + 2 slots for render = 8
    // Slot layout:
    //   0,1 = M3 (transform-cull-light)
    //   2,3 = M4a (pack-sort-keys)
    //   4,5 = M4b (bitonic sort) — Note: sort has multiple dispatches, timestamp only outer
    //   6,7 = M5 (triangulate-emit)
    //   8,9 = render pass
    private val QUERY_COUNT = 10

    private val querySet = device.createQuerySet(
        GPUQuerySetDescriptor(type = QueryType.Timestamp, count = QUERY_COUNT)
    )

    // Resolve buffer: QUERY_COUNT × 8 bytes (each timestamp is a u64)
    private val resolveBuffer = device.createBuffer(GPUBufferDescriptor(
        size = (QUERY_COUNT * 8).toLong(),
        usage = BufferUsage.QueryResolve or BufferUsage.CopySrc,
    ))

    // Readback buffer (mappable)
    private val readbackBuffer = device.createBuffer(GPUBufferDescriptor(
        size = (QUERY_COUNT * 8).toLong(),
        usage = BufferUsage.CopyDst or BufferUsage.MapRead,
    ))

    /** Get GPUPassTimestampWrites for a given pass (by slot pair index). */
    fun timestampWritesFor(slotPairIndex: Int): GPUPassTimestampWrites {
        val beginIdx = slotPairIndex * 2
        val endIdx = beginIdx + 1
        return GPUPassTimestampWrites(
            querySet = querySet,
            beginningOfPassWriteIndex = beginIdx,
            endOfPassWriteIndex = endIdx,
        )
    }

    /**
     * Encode resolve + copy commands after all passes are done.
     * Call this on the command encoder AFTER all compute/render passes have ended.
     */
    fun encodeResolveAndCopy(encoder: GPUCommandEncoder) {
        encoder.resolveQuerySet(querySet, 0, QUERY_COUNT, resolveBuffer, 0)
        encoder.copyBufferToBuffer(resolveBuffer, 0, readbackBuffer, 0, (QUERY_COUNT * 8).toLong())
    }

    /**
     * Read back timestamp results. Must be called AFTER queue.submit() and
     * the GPU work has completed.
     *
     * @return GpuTimestampResult with per-pass durations in nanoseconds,
     *         or null if readback fails
     */
    suspend fun readResults(): GpuTimestampResult? {
        // Map the readback buffer
        readbackBuffer.mapAsync(MapMode.Read)
        // Note: mapAsync is a suspend function in androidx.webgpu

        val mapped = readbackBuffer.getMappedRange()
        val timestamps = LongArray(QUERY_COUNT)
        // Read QUERY_COUNT × 8 bytes as Long array
        val byteBuffer = mapped.asByteBuffer().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until QUERY_COUNT) {
            timestamps[i] = byteBuffer.getLong()
        }
        readbackBuffer.unmap()

        return GpuTimestampResult(
            transformCullLightNanos = timestamps[1] - timestamps[0],
            packSortKeysNanos = timestamps[3] - timestamps[2],
            bitonicSortNanos = timestamps[5] - timestamps[4],
            triangulateEmitNanos = timestamps[7] - timestamps[6],
            renderPassNanos = timestamps[9] - timestamps[8],
        )
    }

    override fun close() {
        querySet.close()
        resolveBuffer.close()
        readbackBuffer.close()
    }
}

data class GpuTimestampResult(
    val transformCullLightNanos: Long,   // M3
    val packSortKeysNanos: Long,         // M4a
    val bitonicSortNanos: Long,          // M4b
    val triangulateEmitNanos: Long,      // M5
    val renderPassNanos: Long,           // render
) {
    val totalComputeNanos: Long get() =
        transformCullLightNanos + packSortKeysNanos + bitonicSortNanos + triangulateEmitNanos
    val totalGpuNanos: Long get() = totalComputeNanos + renderPassNanos
}
```

#### M2.3: Thread timestamp writes through dispatch methods

The compute sub-pipelines currently call `encoder.beginComputePass()` with no arguments. They need to accept optional `GPUPassTimestampWrites`:

**Files to modify:**
- `GpuTransformPipeline.dispatch()` — accept optional `timestampWrites: GPUPassTimestampWrites?`
- `GpuSortKeyPacker.dispatch()` — same
- `GpuTriangulateEmitPipeline.dispatch()` — same
- `GpuBitonicSort.dispatch()` — same (wraps the outermost begin/end, inner passes don't get timestamps)

Each changes from:
```kotlin
val pass = encoder.beginComputePass()
```
to:
```kotlin
val descriptor = timestampWrites?.let { GPUComputePassDescriptor(timestampWrites = it) }
val pass = if (descriptor != null) encoder.beginComputePass(descriptor) else encoder.beginComputePass()
```

**`GpuFullPipeline.dispatch()`** threads the timestamps through:
```kotlin
fun dispatch(encoder: GPUCommandEncoder, profiler: GpuTimestampProfiler? = null) {
    transform.dispatch(encoder, faceCount, profiler?.timestampWritesFor(0))
    packer.dispatch(encoder, lastPaddedCount, profiler?.timestampWritesFor(1))
    sort.dispatch(encoder, profiler?.timestampWritesFor(2))
    emit.dispatch(encoder, profiler?.timestampWritesFor(3))
}
```

#### M2.4: Thread timestamps through `drawFrame()`

**File:** `WebGpuSceneRenderer.kt`

`drawFrame()` creates the render pass descriptor. Add timestamp writes:

```kotlin
val renderDescriptor = GPURenderPassDescriptor(
    colorAttachments = arrayOf(...),
    timestampWrites = profiler?.timestampWritesFor(4),  // slot pair 4 = indices 8,9
)
```

After the render command buffer is submitted, encode resolve+copy and submit:
```kotlin
// After both compute and render submits:
if (profiler != null) {
    val resolveEncoder = device.createCommandEncoder()
    profiler.encodeResolveAndCopy(resolveEncoder)
    queue.submit(arrayOf(resolveEncoder.finish()))
}
```

**Important:** The resolve must happen after the compute and render submissions. Since we submit compute and render as separate command buffers, the resolve goes in a third submission. WebGPU guarantees command buffer ordering within a queue.

#### M2.5: Async readback pipeline

GPU timestamp readback requires `mapAsync` which is asynchronous. We don't want to stall the render loop waiting for readback. Design:

```
Frame N: dispatch compute + render + resolve + copy
         ↓ (immediately start frame N+1)
Frame N+1: before dispatching, check if frame N's readback is ready
           if ready: read timestamps, deliver to callback
           submit new frame's work
```

This means timestamps are delivered **one frame late**, which is acceptable for benchmarking — we're measuring steady-state per-pass costs, and the one-frame delay doesn't affect the statistics.

Implementation: `GpuTimestampProfiler` uses double-buffered readback buffers (2 sets of resolve+readback buffers, alternating per frame). While frame N's buffer is being mapped, frame N+1 writes to the other buffer.

#### M2.6: Deliver timestamps via `WebGpuFrameCallback`

After readback completes, call:
```kotlin
frameCallback.onGpuTimestamps(
    computeNanos = result.totalComputeNanos,
    renderNanos = result.renderPassNanos
)
```

The benchmark harness records these via new arrays in `MetricsCollector` (see M3).

---

### M3: Output Schema (Backward-Compatible Extension)

#### M3.1: Core metrics — unchanged

The following CSV columns and JSON fields remain **exactly as they are** for all render modes:

```
avgPrepareMs, p50PrepareMs, p95PrepareMs, p99PrepareMs, minPrepareMs, maxPrepareMs, stdDevPrepareMs
avgDrawMs, p50DrawMs, p95DrawMs, p99DrawMs, minDrawMs, maxDrawMs, stdDevDrawMs
avgFrameMs, p50FrameMs, p95FrameMs, p99FrameMs, minFrameMs, maxFrameMs, stdDevFrameMs
avgHitTestMs, p95HitTestMs
cacheHits, cacheMisses, cacheHitRate, observedMutationRate
warmupFrames, measurementFrames, iterations, allocatedMB, gcInvocations
deviceModel, androidVersion, isEmulator, timestamp
```

**Semantic mapping for WebGPU mode:**
- `prepareMs` = CPU scene projection time (from `renderer.prepareScene()` hooks, same as Canvas). For static frames, this is 0 (cache hit). This measures the same work across all render modes.
- `drawMs` = wall-clock time around `drawFrame()` (compute dispatch + render pass + present). This is the closest analog to the Canvas path's draw time, though it includes GPU work that the Canvas path doesn't have.
- `frameMs` = Choreographer vsync interval (identical across modes — measures wall-clock frame cadence).
- `cacheHits/Misses` = from `renderer.prepareScene()` (same as Canvas). In WebGPU mode, every scene change is a cache miss. `enablePreparedSceneCache` controls this.

#### M3.2: New GPU timestamp columns — appended

**CSV:** New columns appended after `timestamp` (last existing column):

```
gpuComputeMs, gpuRenderMs, gpuTotalMs, gpuTimestampsAvailable
```

- `gpuComputeMs` — mean GPU compute time across all frames (M3+M4a+M4b+M5), in ms
- `gpuRenderMs` — mean GPU render pass time, in ms
- `gpuTotalMs` — mean total GPU time, in ms
- `gpuTimestampsAvailable` — `true`/`false` (allows downstream analysis to know if GPU data is real or zero)

For Canvas modes and devices without timestamp support, these columns are `0.0000, 0.0000, 0.0000, false`.

**JSON:** New optional object in each iteration:

```json
{
  "iterations": [{
    "prepare": { ... },
    "draw": { ... },
    "frame": { ... },
    // ... existing fields ...

    "gpuTimestamps": {
      "available": true,
      "compute": { "mean": 1.234, "p50": 1.1, "p95": 1.8, ... },
      "render": { "mean": 0.567, ... },
      "total": { "mean": 1.801, ... },
      "breakdown": {
        "transformCullLight": { "mean": 0.4, ... },
        "packSortKeys": { "mean": 0.1, ... },
        "bitonicSort": { "mean": 0.5, ... },
        "triangulateEmit": { "mean": 0.2, ... }
      }
    },

    "rawTimings": {
      "prepareTimes": [...],
      "drawTimes": [...],
      "frameTimes": [...],
      "hitTestTimes": [...],
      "gpuComputeTimes": [...],   // NEW: per-frame GPU compute nanos
      "gpuRenderTimes": [...]     // NEW: per-frame GPU render nanos
    }
  }]
}
```

#### M3.3: MetricsCollector changes

Add two new pre-allocated arrays:

```kotlin
private val gpuComputeTimes = LongArray(maxFrames)  // nanoseconds
private val gpuRenderTimes = LongArray(maxFrames)   // nanoseconds
var gpuTimestampsAvailable: Boolean = false
```

New recording methods:
```kotlin
fun recordGpuComputeTime(nanos: Long) { ... }
fun recordGpuRenderTime(nanos: Long) { ... }
```

`snapshot()` produces new `StatSummary` fields. `rawTimings()` includes the new arrays.

#### M3.4: FrameMetrics / RawTimings extension

```kotlin
data class FrameMetrics(
    // ... all existing fields unchanged ...
    val gpuComputeTimeMs: StatSummary = ZERO_STATS,   // NEW
    val gpuRenderTimeMs: StatSummary = ZERO_STATS,     // NEW
    val gpuTimestampsAvailable: Boolean = false,        // NEW
)

data class RawTimings(
    // ... all existing fields unchanged ...
    val gpuComputeTimes: LongArray = LongArray(0),  // NEW
    val gpuRenderTimes: LongArray = LongArray(0),   // NEW
)
```

Default values ensure existing code that constructs `FrameMetrics` or `RawTimings` without the new fields continues to compile and produces zero/empty values.

#### M3.5: ResultsExporter changes

**CSV:** Append new columns to `buildCsvHeader()` and `buildCsvRow()`. Columns go after `timestamp`:

```kotlin
// In buildCsvHeader():
columns.addAll(listOf(
    // ... existing ...
    "timestamp",
    "gpuComputeMs",    // NEW
    "gpuRenderMs",     // NEW
    "gpuTotalMs",      // NEW
    "gpuTimestampsAvailable"  // NEW
))

// In buildCsvRow():
values.add("%.4f".format(metrics.gpuComputeTimeMs.mean))
values.add("%.4f".format(metrics.gpuRenderTimeMs.mean))
values.add("%.4f".format(metrics.gpuComputeTimeMs.mean + metrics.gpuRenderTimeMs.mean))
values.add(metrics.gpuTimestampsAvailable.toString())
```

**JSON:** Add `gpuTimestamps` object to each iteration entry, and extend `rawTimings`.

#### M3.6: HarnessValidator — no changes needed

The validator's five checks (cache, flags, consistency, sanity, mutation) all operate on the existing `FrameMetrics` fields. GPU timestamps are informational — no validation rules needed initially.

#### M3.7: Aggregation in `aggregateIterations()`

New fields aggregate the same way as existing StatSummary fields (mean of means, min of mins, max of maxes). `gpuTimestampsAvailable` is `true` if ALL iterations have it `true`.

---

### M4: BenchmarkScreen / Orchestrator Integration

#### M4.1: Create WebGpuFrameCallback adapter in BenchmarkScreen

In `BenchmarkScreen`, create the adapter that bridges `WebGpuFrameCallback` to `BenchmarkHooksImpl` and `MetricsCollector`:

```kotlin
val webGpuFrameCallback = remember(benchmarkHooks, collector) {
    object : WebGpuFrameCallback {
        private var drawStartNanos = 0L

        override fun onUploadStart() {
            // Upload timing is folded into draw for schema compatibility
        }
        override fun onUploadEnd() {}

        override fun onDrawFrameStart() {
            drawStartNanos = System.nanoTime()
        }
        override fun onDrawFrameEnd() {
            val elapsed = System.nanoTime() - drawStartNanos
            collector.recordDrawTime(elapsed)
            // Increment drawPassCount to unblock awaitNextDraw()
            benchmarkHooks.incrementDrawPassCount()
        }

        override fun onGpuTimestamps(computeNanos: Long, renderNanos: Long) {
            collector.recordGpuComputeTime(computeNanos)
            collector.recordGpuRenderTime(renderNanos)
            collector.gpuTimestampsAvailable = true
        }
    }
}
```

**Note:** `benchmarkHooks.incrementDrawPassCount()` is a new public method on `BenchmarkHooksImpl` that does `drawPassCount++`. Currently `drawPassCount` is only incremented inside `onDrawEnd()`, but in WebGPU mode we need to increment it from the adapter without going through the full `onDraw` flow. Alternative: just call `benchmarkHooks.onDrawEnd()` from the adapter — but that would double-record draw time since `onDrawEnd()` also records `collector.recordDrawTime()`.

**Cleaner approach:** Extract `drawPassCount` incrementing from `BenchmarkHooksImpl.onDrawEnd()`:

```kotlin
class BenchmarkHooksImpl(private val collector: MetricsCollector) : RenderBenchmarkHooks {
    var drawPassCount: Long = 0
        private set

    fun signalDrawComplete() { drawPassCount++ }

    override fun onDrawEnd() {
        val elapsed = System.nanoTime() - drawStartNanos
        collector.recordDrawTime(elapsed)
        signalDrawComplete()
    }
}
```

Then the WebGPU adapter calls `benchmarkHooks.signalDrawComplete()` instead.

#### M4.2: Pass callback through IsometricScene → WebGpuRenderBackend

The adapter is created in `BenchmarkScreen` and needs to reach `WebGpuSceneRenderer.renderLoop()`. Path:

```
BenchmarkScreen creates adapter
  → passes to IsometricScene via AdvancedSceneConfig.webGpuFrameCallback
    → IsometricScene passes to webGpuRenderBackend.Surface(frameCallback = ...)
      → WebGpuRenderBackend passes to renderer.renderLoop(frameCallback = ...)
```

**File:** `isometric-compose/.../runtime/AdvancedSceneConfig.kt` (or wherever it's defined)
Add: `val webGpuFrameCallback: WebGpuFrameCallback? = null`

**File:** `isometric-compose/.../runtime/IsometricScene.kt`
In the `!isCanvasBackend` branch, pass `config.webGpuFrameCallback` to `webGpuRenderBackend.Surface()`.

#### M4.3: Orchestrator — no changes needed

The orchestrator's `awaitNextDraw()` works via `getDrawPassCount()` which reads `benchmarkHooks.drawPassCount`. Since the WebGPU adapter calls `signalDrawComplete()` after each `drawFrame()`, the spin loop unblocks correctly. No changes to `BenchmarkOrchestrator`.

---

### M5: benchmark-runner.sh Changes

#### M5.1: Add `--gpu-timestamps` flag (optional)

```bash
ENABLE_GPU_TIMESTAMPS=false

# In parse arguments:
--gpu-timestamps) ENABLE_GPU_TIMESTAMPS=true; shift ;;
```

Pass as flag in JSON config:
```json
"flags": { ..., "enableGpuTimestamps": ${ENABLE_GPU_TIMESTAMPS} }
```

This flag controls whether `GpuContext` requests `TimestampQuery` and whether `GpuTimestampProfiler` is created. When `false`, no timestamp query infrastructure is created and performance is unaffected.

#### M5.2: No other shell changes needed

The CSV column additions are backward-compatible (new columns appended at the end). Existing analysis scripts that parse by column index will need updating, but scripts that parse by column name are unaffected.

---

## Implementation Order

| Step | Description | Files | Risk |
|------|-------------|-------|------|
| **M1.1** | Define `WebGpuFrameCallback` interface | `isometric-webgpu` (1 new file) | None |
| **M1.2** | Wire callback into `WebGpuSceneRenderer.renderLoop()` | `WebGpuSceneRenderer.kt` | Low |
| **M1.3** | Thread callback through `WebGpuRenderBackend.Surface()` | `WebGpuRenderBackend.kt`, `RenderBackend.kt` | Low |
| **M1.4** | Bridge adapter in `IsometricScene` or pass via `AdvancedSceneConfig` | `IsometricScene.kt`, `AdvancedSceneConfig` | Low |
| **M1.5** | `BenchmarkHooksImpl.signalDrawComplete()` | `BenchmarkHooksImpl.kt` | None |
| **M1.6** | Create adapter in `BenchmarkScreen`, pass to scene config | `BenchmarkScreen.kt` | Low |
| **M1.7** | Test: verify Canvas modes still produce identical output | Manual | Gate |
| **M1.8** | Test: verify WebGPU mode produces prepare/draw/frame data and doesn't hang | Manual | Gate |
| **M2.1** | Request `TimestampQuery` feature in `GpuContext` | `GpuContext.kt` | Medium — device may not support |
| **M2.2** | Create `GpuTimestampProfiler` | New file | Medium — async readback complexity |
| **M2.3** | Thread timestamp writes through dispatch methods | 4 pipeline files + `GpuFullPipeline` | Low |
| **M2.4** | Thread timestamps through `drawFrame()` | `WebGpuSceneRenderer.kt` | Low |
| **M2.5** | Double-buffered async readback | `GpuTimestampProfiler` | Medium |
| **M2.6** | Deliver timestamps via callback | `WebGpuSceneRenderer.kt` | Low |
| **M3.1-3.5** | Extend MetricsCollector, FrameMetrics, RawTimings, ResultsExporter | 4 benchmark files | Low |
| **M3.7** | Extend aggregateIterations() | `ResultsExporter.kt` | Low |
| **M4.1-4.3** | BenchmarkScreen adapter, AdvancedSceneConfig, IsometricScene wiring | 3 files | Low |
| **M5.1** | Shell script `--gpu-timestamps` flag | `benchmark-runner.sh` | None |

**Total files modified:** ~15
**New files:** 2 (`WebGpuFrameCallback.kt`, `GpuTimestampProfiler.kt`)

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Device doesn't support `TimestampQuery` | GPU timestamps unavailable | Graceful fallback: check adapter features, skip profiler creation, output `gpuTimestampsAvailable=false` |
| `mapAsync` stalls render loop | Frame time spikes | Double-buffered readback (M2.5) — never stall on current frame's data |
| Timestamp values are device-specific (different clock domains) | Absolute values not comparable across devices | Only use deltas (end - begin), which are in GPU nanoseconds regardless of clock epoch |
| WebGPU render loop runs at different cadence than Choreographer | Frame time metrics diverge from Canvas baseline | Expected and correct — document in analysis. `frameMs` (Choreographer) vs `drawMs` (render loop) difference reveals scheduling overhead. |
| `drawPassCount` increment races with orchestrator read | Missed or double-counted frames | Safe: `drawPassCount` is read by orchestrator on main thread, incremented by render loop on Default dispatcher. The orchestrator uses `>` comparison (not `==`), so a missed poll just means the next poll catches it. AtomicLong would be more correct but the current `Long` with volatile-like read-via-lambda works in practice. |
| `renderer.prepareScene()` and render loop are on different coroutines | Prepare time and draw time not aligned to same frame | This is inherent to the WebGPU architecture. Document that for WebGPU mode, `prepareMs` and `drawMs` measure overlapping but not synchronous work. For comparison purposes, `frameMs` (end-to-end) is the apples-to-apples metric. |

## Comparability Matrix

How to compare results across render modes:

| Metric | Canvas+CPU | Canvas+WebGPU | Full WebGPU | Comparable? |
|--------|-----------|---------------|-------------|-------------|
| `frameMs` | Choreographer vsync | Choreographer vsync | Choreographer vsync | **Yes** — same measurement, same semantics |
| `prepareMs` | CPU scene projection | CPU scene projection | CPU scene projection | **Yes** — same `prepareScene()` code path |
| `drawMs` | Canvas draw (CPU) | Canvas draw (CPU, from pre-sorted data) | GPU compute+render+present (wall-clock) | **Partially** — measures "cost of producing a frame" but the work differs |
| `cacheHitRate` | PreparedScene cache | PreparedScene cache | PreparedScene cache | **Yes** — same cache mechanism |
| `gpuComputeMs` | N/A (0) | N/A (0) | M3+M4+M5 GPU time | **WebGPU only** |
| `gpuRenderMs` | N/A (0) | N/A (0) | Render pass GPU time | **WebGPU only** |

**Key insight for analysis:** To compare "how fast is each mode at producing frames", use `frameMs` (p50, p95). To understand *where time goes* within a mode, use `prepareMs + drawMs` for Canvas modes and `prepareMs + gpuComputeMs + gpuRenderMs` for WebGPU mode.
