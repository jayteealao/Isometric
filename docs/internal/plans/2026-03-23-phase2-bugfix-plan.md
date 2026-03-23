# Phase 2 Bug Fix Plan

> Status: complete
> Date: 2026-03-23
> Source: post-review findings from full P1+P2 implementation audit

---

## Fix List

### F1 — `cleanup()` calls WebGPU API off the GPU thread  [CRITICAL]
**File:** `WebGpuSceneRenderer.kt`
**Problem:** `cleanup()` is called from the `finally` block of `renderLoop()`, which runs on
`Dispatchers.Default`. It calls `gpuSurface?.unconfigure()`, `renderPipeline?.close()`,
`vertexBuffer?.close()`, and `gpuSurface?.close()` — all raw Dawn calls that must be on the
dedicated GPU thread.
**Fix:** Dispatch the Dawn portions of `cleanup()` onto the GPU thread via
`runBlocking(gpuDispatcher) { ... }` (same pattern used by `GpuContext.destroy()`). Must be
partial-init safe: skip if `gpuContext` is null.
- [x] Done

---

### F2 — `awaitContextForSharing()` triggers compute init unconditionally  [HIGH]
**File:** `WebGpuSceneRenderer.kt`
**Problem:** `WebGpuComputeBackendHolder.instance` is always a `WebGpuComputeBackend` regardless
of whether the user selected WebGPU compute. Calling `awaitContextForSharing()` on it
unconditionally forces GPU device creation even when `ComputeBackend.Cpu` is the active backend.
Also: if the render surface is already initialized (CPU+WebGPU render, then user switches to
WebGPU compute), the shared context path is never reached and two devices coexist.
**Fix:** Replace `awaitContextForSharing()` with `getContextIfReady()` (opportunistic, no-trigger).
This prevents forcing compute init. Two devices may still coexist on a compute-backend switch
mid-session, but the primary crash case (cold-start WebGPU+WebGPU) is already fixed by F1+the
`isCanvasBackend` LaunchedEffect guard.
- [x] Done

---

### F3 — Surface resize not handled dynamically  [HIGH]
**File:** `WebGpuSceneRenderer.kt`, `WebGpuRenderBackend.kt`
**Problem:** `renderLoop()` configures the surface at `onSurface` time with the initial
`surfaceWidth`/`surfaceHeight`. When `IsometricScene`'s `canvasWidth`/`canvasHeight` change
(window resize, fold/unfold), a new `PreparedScene` is built with updated NDC coordinates and
re-uploaded, but the `GPUSurface` stays configured at the old dimensions. Rendering is geometrically
correct only if the surface is destroyed and recreated (e.g., rotation).
**Fix:** Add `onSizeChanged(width, height)` to `WebGpuSceneRenderer` that reconfigures the surface
when dimensions differ from `currentWidth`/`currentHeight`. Call it from `WebGpuRenderBackend`
via a `LaunchedEffect` or `snapshotFlow` on `renderContext`.
- [x] Done

---

### F4 — `GpuDepthSorter.pipelineLayout` leaked  [MEDIUM]
**File:** `GpuDepthSorter.kt`
**Problem:** `ensurePipelineBuilt()` creates a `GPUPipelineLayout` but never stores or closes it.
The JNI wrapper object is dropped after the `createComputePipelineAndAwait` call. Dawn keeps the
native object alive via ref-counting as long as the pipeline exists, but the JNI wrapper `close()`
is never called.
**Fix:** Store `pipelineLayout` as a field. Close it in `destroyCachedBuffers()`.
- [x] Done

---

### F5 — `GpuDepthSorter` pipeline/shader/bindgroup objects never explicitly closed  [MEDIUM]
**File:** `GpuDepthSorter.kt`
**Problem:** `shaderModule`, `sortPipeline`, `bindGroupLayout`, and `cachedBindGroups` are never
closed in `destroyCachedBuffers()` or `invalidateContext()`. Device destruction reclaims the
native resources, but the JNI wrapper objects accumulate unclosed.
**Fix:** Add explicit `close()` calls on these objects in `destroyCachedBuffers()`. Reset fields
to null. Also close in `ensureBuffers()` when they need to be recreated (though pipeline is
size-independent — only buffers/bind-groups need recreation on paddedCount change; pipeline is
created once).
- [x] Done

---

## Notes

- Frame pacing (`delay(16L)`) is a known gap from the Phase 2 plan (§Gap 4). Not addressed here —
  requires AndroidExternalSurface continuous-render research.
- `currentVertexCount` memory visibility: safe per coroutine dispatch happens-before guarantee.
  Adding `@Volatile` is cosmetic but documents the intent. Deferred.
- Phase 1 sorter `SORT_KEY_BYTES = 16` format and descending sort order are correct for
  painter's algorithm back-to-front rendering. No fix needed.
