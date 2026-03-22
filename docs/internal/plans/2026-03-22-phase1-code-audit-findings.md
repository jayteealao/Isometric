# 2026-03-22 Phase 1 Code Audit Findings

Step 5 of the [WebGPU Phase 1 Performance Review Plan](2026-03-22-webgpu-phase1-performance-review-plan.md).

Audit performed against current HEAD before profiled device tests.

---

## Files Audited

- `isometric-compose/.../SceneCache.kt`
- `isometric-compose/.../IsometricRenderer.kt`
- `isometric-compose/.../IsometricScene.kt`
- `isometric-compose/.../HitTestResolver.kt`
- `isometric-compose/.../RenderExtensions.kt` (`toComposePath`)
- `isometric-compose/.../ColorExtensions.kt` (`toComposeColor`)
- `isometric-core/.../IsometricEngine.kt`
- `isometric-core/.../DepthSorter.kt`
- `isometric-core/.../HitTester.kt`
- `isometric-core/.../SceneGraph.kt`
- `isometric-core/.../RenderCommand.kt`
- `isometric-core/.../PreparedScene.kt`
- `isometric-webgpu/.../GpuDepthSorter.kt`
- `isometric-webgpu/.../WebGpuComputeBackend.kt`
- `isometric-webgpu/.../GpuContext.kt`
- `app/.../WebGpuSampleActivity.kt`

---

## Findings

### F7.1 — Per-frame Path + Color allocation on UI thread (HIGH)

**Location:** `IsometricRenderer.renderPreparedScene()` (lines 366-393)

**Problem:** Every draw frame calls `command.toComposePath()` and
`command.color.toComposeColor()` for each face in the scene. Each call
allocates a new `Path` object and a new `Color` object respectively.

For a 12×12 grid scene (~700 faces), this produces ~1400 object allocations
per frame on the UI thread. These are short-lived young-gen objects that
contribute directly to GC churn and potential visible stalls.

The async compute path (`prepareAsync`) runs projection and GPU sort
off-thread, but the Compose `Path`/`Color` conversion is deferred to the
Canvas draw lambda and runs on the UI thread every frame.

**Severity:** High — largest single source of per-frame allocations, runs on
the UI thread where GC pauses cause visible jank.

**Suggested fix:** Pre-build the path cache during `prepareAsync` (already
off-thread). Store `CachedPath` objects in `SceneCache`. The draw lambda
iterates the cached list with zero allocation. This is the same pattern the
sync CPU path already supports via `enablePathCaching`, but currently unused
by the async path.

---

### F2.3 — Per-frame RenderCommand list creation (MEDIUM)

**Location:** `IsometricEngine.projectSceneAsync()` (lines 313-325)

**Problem:** Every frame creates a new `ArrayList<RenderCommand>` and N new
`RenderCommand` data class instances. For ~700 faces this is ~700 heap
allocations plus the backing array.

The `transformedItems` ArrayList and `depthKeys` FloatArray are already
cached and reused — `RenderCommand` list is not.

**Severity:** Medium — significant allocation volume but off-thread (runs on
`Dispatchers.Default` via `withContext`), so GC pressure is real but pauses
are less likely to cause visible jank than F7.1.

**Suggested fix:** Cache the `ArrayList<RenderCommand>` in `IsometricEngine`
the same way `cachedTransformedItems` is cached. Clear-and-refill instead of
reallocate. `RenderCommand` instances themselves are harder to pool (data
classes with varying content), but the list container is easy.

---

### F1.1 — Per-frame command collection list (LOW-MEDIUM)

**Location:** `SceneCache.rebuildAsync()` (line 152)

**Problem:** `val commands = mutableListOf<RenderCommand>()` creates a new
`ArrayList` on every rebuild. The list itself is modest overhead, but it
contributes to the pattern of per-frame young-gen allocations during animated
scenes.

**Severity:** Low-Medium — the list is transient, but at ~700 entries it
requests a non-trivial backing array that gets promoted and collected.

**Suggested fix:** Cache a reusable `ArrayList` in `SceneCache`, clear it
each frame. Same pattern as `cachedTransformedItems` in `IsometricEngine`.

---

### F2.1 — Per-face TransformedItem wrapper allocation (LOW-MEDIUM)

**Location:** `IsometricEngine.projectAndCull()` (line 356)

**Problem:** Each surviving face produces a new `TransformedItem(item,
screenPoints, litColor)` data class instance. The `screenPoints` DoubleArray
is necessarily unique per face per frame. The wrapper object is small but
adds ~700 allocations per frame.

**Severity:** Low-Medium — small objects, young-gen friendly, but adds to
total allocation rate during sustained animated runs.

**Suggested fix (deferred):** Would require an object pool pattern that adds
complexity for modest gain. Better suited as a Phase 2 optimisation if
profiling shows it matters after higher-priority fixes land.

---

### F2.4 — lightDirection.normalize() allocates per frame (LOW)

**Location:** `IsometricEngine.projectSceneAsync()` (line 262)

**Problem:** `lightDirection.normalize()` allocates a new `Vector` every
frame. Single object, trivial.

**Severity:** Low.

---

### F3.1 — JNI wrapper and callback leak per frame (CRITICAL — FIXED)

**Location:** `GpuDepthSorter.sortByDepthKeys()`

**Problem:** Each sort creates JNI wrapper objects (command encoder,
compute passes, command buffer) and async callback objects
(`GPURequestCallback` + `Executor` for `mapAsync`/`onSubmittedWorkDone`).
The JNI wrappers need explicit `close()` — without it, GC finalization
can't keep up at 60fps. The async callbacks leak JNI global refs that
are never released by Dawn's binding layer, causing ~8-14 MB/s Dalvik
heap growth.

**Severity:** Critical — **this was the root cause of the OOM crash.**
Initial assessment of "Low" was wrong; the leak only manifests at
sustained 60fps with animated scenes.

**Fix:** Explicit `close()` on all per-frame JNI wrappers. Replace
`mapAndAwait()`/`onSubmittedWorkDone()` suspend calls with reusable
singleton callbacks. Replace staging buffer upload with
`queue.writeBuffer()`. Replace `getConstMappedRange()` with
`readMappedRange()` into reusable ByteBuffer.

---

### F3.2 — extractSortedIndices allocates IntArray per frame (LOW)

**Location:** `GpuDepthSorter.extractSortedIndices()` (line 438)

**Problem:** `IntArray(count)` allocated fresh each frame for sorted indices.

**Severity:** Low — single pre-sized array. Could be cached but gain is
marginal.

---

## Clean Areas (no issues found)

### GpuDepthSorter buffer lifecycle ✅

All GPU buffers (primary, scratch, readback, upload, params) are cached by
`paddedCount` and only recreated when the face count changes to a different
power-of-two. Bind groups are pre-built. CPU-side `ByteBuffer` is reused.
Upload staging buffer is re-mapped rather than recreated. Single command
buffer submission and single sync point per sort.

No buffer recreation churn detected for animated scenes with stable model
count.

### GpuContext lifecycle ✅

Single-use design. Dedicated GPU thread avoids data races between
`processEvents` polling and API calls. `DeviceLostCallback` and
`UncapturedErrorCallback` capture failures into `AtomicReference` for
`checkHealthy()` propagation. Clean `destroy()` with idempotency and
same-thread detection.

### HitTester (non-interactive samples) ✅

`HitTester.findItemAt()` does minimal allocation (`commands.reversed()` for
front-to-back order). Since gestures are disabled in the WebGPU samples
(`GestureConfig.Disabled`), this code never runs.

### CPU DepthSorter (not on async path) ✅

`DepthSorter.sort()` is only used when `ComputeBackend.Cpu` is active. Not
exercised in the WebGPU path. Its broad-phase allocations (`HashMap`,
`MutableList`, `HashSet`, `LongArray`) are irrelevant to the GPU sort
pipeline.

### Hit-test index rebuilds skipped ✅

`skipHitTest = !gesturesActive` is correctly `true` when
`GestureConfig.Disabled` is set. `HitTestResolver.rebuildIndices()` is
never called during animated WebGPU sample playback.

### Sample animation driver ✅

`rememberPhaseAnimation` uses `withFrameNanos` to drive per-frame state
updates. This is the intended pattern — it fires the snapshotFlow →
conflate → prepareAsync pipeline every frame. Not a defect; the per-frame
allocation findings above are the consequence of this cadence.

### Smoke sample stage transitions ✅

Stage changes every 2 seconds alter grid dimensions, which changes
`paddedCount` and triggers GPU buffer recreation. This is correct behaviour —
buffer recreation only happens on stage transition, not per-frame.

---

## Summary Table

| ID | Location | Issue | Severity | Phase 1 fix? |
|----|----------|-------|----------|--------------|
| F7.1 | `IsometricRenderer.renderPreparedScene()` | Per-frame Path + Color on UI thread (~1400 obj/frame) | High | Yes |
| F2.3 | `IsometricEngine.projectSceneAsync()` | Per-frame RenderCommand list (~700 obj) | Medium | Yes |
| F1.1 | `SceneCache.rebuildAsync()` | Per-frame command collection list | Low-Med | Yes |
| F2.1 | `IsometricEngine.projectAndCull()` | Per-face TransformedItem wrapper | Low-Med | Defer |
| F2.4 | `IsometricEngine.projectSceneAsync()` | Per-frame Vector.normalize() | Low | Optional |
| F3.1 | `GpuDepthSorter` | **JNI wrapper + callback leak (ROOT CAUSE)** | **Critical** | **Yes (FIXED)** |
| F3.2 | `GpuDepthSorter` | IntArray per frame for sorted indices | Low | Optional |

---

## Recommended Fix Order

1. **F7.1** — Pre-build path cache during `prepareAsync`. Eliminates ~1400
   UI-thread allocations per frame.
2. **F2.3 + F1.1** — Reuse RenderCommand and command-collection lists.
   Reduces off-thread young-gen churn.
3. **F2.4 / F3.2** — Minor caching. Only if profiling shows they matter
   after fixes 1-2 land.
4. **F2.1** — Defer to Phase 2 unless profiling shows TransformedItem
   wrappers are a material contributor.

---

## Fixes Applied

All three priority fixes were implemented and validated (build + unit tests
pass). Changes are ready for profiled device testing.

### Fix 1: F7.1 — Pre-build path cache in async path

**Files changed:**
- `SceneCache.kt` — `rebuildAsync()` now always calls `buildPathCache(scene)`
  regardless of the `enablePathCaching` flag. The sync `rebuild()` path is
  unchanged and still respects the flag.
- `IsometricRenderer.kt` — `renderFromScene()` now checks `cache.cachedPaths`
  first. When available, it iterates the pre-built list with zero per-frame
  allocation. Falls back to `renderPreparedScene()` only when no cache exists
  (first frame or cache cleared).

**Expected effect:** Eliminates ~1400 `Path` + `Color` object allocations per
frame on the UI thread for the async compute path.

### Fix 2: F1.1 — Reuse command-collection list

**Files changed:**
- `SceneCache.kt` — Added `reusableCommandList: ArrayList<RenderCommand>?`.
  Both `rebuild()` and `rebuildAsync()` reuse this list via clear-and-refill
  instead of allocating a new `mutableListOf()` each frame. Cleared on
  `clearCache()`.

**Expected effect:** Eliminates 1 ArrayList allocation + backing array per
frame.

### Fix 3: F2.3 — Reuse sorted-items and render-command lists

**Files changed:**
- `IsometricEngine.kt` — Added `cachedSortedItems` and
  `cachedRenderCommands` fields alongside the existing `cachedDepthKeys` and
  `cachedTransformedItems`. Both lists are reused via clear-and-refill in
  `projectSceneAsync()`.

**Expected effect:** Eliminates 2 ArrayList allocations + backing arrays per
frame in the async projection path.

---

## Fix Corrections

### F7.1 — Reverted: path cache causes OOM in animated scenes

The "always build path cache in async" change created ~280 heavyweight
Compose `Path` objects per frame and stored them in `cachedPaths`. Unlike
the per-frame `toComposePath()` calls in the draw lambda (which produce
immediate young-gen garbage), the cached paths were held for one full
prepare cycle before being replaced. This doubled the live set of Path
objects and caused the GC to fall further behind, accelerating OOM.

**Root cause:** Compose `Path` wraps native Skia `SkPath`. These are
heavyweight objects (~200+ bytes managed + native allocation) that are
expensive for GC to finalize. Caching them makes sense for static scenes
(built once, drawn many times) but is actively harmful for animated scenes
that rebuild every frame.

`rebuildAsync()` now respects the `enablePathCaching` flag again (same
as `rebuild()`). `renderFromScene()` uses `renderPreparedScene()` directly.

### F2.3 — Partially reverted: cachedRenderCommands caused blank frames

The `cachedRenderCommands` ArrayList was shared by reference with
`PreparedScene`. Clearing the list on the next frame wiped the previous
scene's command list, causing blank/frozen frames. Reverted to fresh
ArrayList per frame for the render commands. The `cachedSortedItems`
reuse is safe (intermediate-only, not stored in the returned object).

---

## Profiled Device Test Results

### Test Configuration

- **Commit:** 37cf410 (with F1.1 + F2.3 partial fixes, F7.1 reverted)
- **Device:** Samsung SM-F956B (Galaxy Z Fold6)
- **Android:** 14 (API 34)
- **Sample:** Animated Towers (7×7 grid, continuous animation)
- **Backend:** ComputeBackend.WebGpu
- **Date:** 2026-03-22

### Memory Trajectory — Animated Towers (WebGPU)

| Time | Dalvik Alloc (MB) | Java Heap (MB) | Total PSS (MB) |
|------|-------------------|----------------|-----------------|
| T+5s | 57 | 63 | 197 |
| T+15s | 168 | 176 | 331 |
| T+30s | 271 | 291 | 462 |
| T+60s | 509 | 518 | 722 |
| ~T+70s | (OOM crash) | — | — |

**Heap growth rate:** ~8 MB/s sustained, continuous, no plateau.
**Dalvik ceiling:** 512 MB (524288 KB). Hit at T+60s, OOM shortly after.

### Frame Timing — Animated Towers (WebGPU)

At T+30s (1099 frames rendered):

| Metric | Value |
|--------|-------|
| Janky frames (legacy) | 86% |
| 50th percentile | 16ms |
| 90th percentile | 28ms |
| 95th percentile | 32ms |
| 99th percentile | 38ms |

At T+60s (2444 frames rendered):

| Metric | Value |
|--------|-------|
| Janky frames (legacy) | 92% |
| 50th percentile | 20ms |
| 90th percentile | 31ms |

Frame timing degrades over the run as GC pressure increases.

### CPU vs WebGPU Backend Comparison

Same scene (7×7 Animated Towers, continuous animation), same device,
same commit. Only difference: `ComputeBackend.Cpu` vs `ComputeBackend.WebGpu`.

| Time | CPU Java Heap (MB) | WebGPU Java Heap (MB) |
|------|--------------------|-----------------------|
| T+5s | 18 | 63 |
| T+15s | 14 | 176 |
| T+30s | 11 | 291 |
| T+60s | 17 | 518 |
| T+75s | 22 | (OOM crash) |

| Metric | CPU Backend | WebGPU Backend |
|--------|------------|----------------|
| Heap growth | ~0.1 MB/s (flat, plateau) | ~8 MB/s (continuous climb) |
| 50th pctile frame | 44ms | 16ms |
| 90th pctile frame | 67ms | 28ms |

**Key insight:** The CPU backend is memory-stable with a flat heap, proving
the shared pipeline (Compose recomposition, Prism/Point creation, engine
SceneItem creation) does NOT leak. The leak is specific to the WebGPU
async path.

### Primary Question Answers

**Q1: Is the pressure CPU-side or WebGPU-side?**

~~CPU-side shared pipeline~~ **Corrected: WebGPU async path specific.**

The `GpuDepthSorter` is well-optimized: buffers cached by `paddedCount`,
bind groups pre-built, single command submission, reusable ByteBuffer.
GPU percentiles are 2-4ms.

The initial hypothesis was that per-frame allocations in the shared
pipeline (Prism/Point creation, SceneItem, TransformedItem, Path/Color)
were the source. CPU comparison testing disproved this: the CPU backend
runs the identical shared pipeline with flat memory (~11-22 MB over 75s).

The leak is in the **async prepare → Compose state → Canvas render**
lifecycle specific to the WebGPU path. See "Async Retention Pattern
Analysis" below.

**Q2: Per-frame allocations large enough for visible GC churn?**

Yes, but only in the WebGPU async path. At ~8 MB/s sustained object
retention (not just creation), GC runs frequently enough to cause visible
pacing degradation (50th percentile degrades from 16→20ms over the run).
The 90th+ percentile frames (28-38ms) correlate with GC pauses.

The CPU path creates the same per-frame objects but they are collected
normally (flat heap). The difference is object *retention*, not creation.

**Q3: Are samples representative?**

The Animated Towers sample is the worst case: continuous animation driving
the full pipeline every frame. The core issue is that any animated scene
using `ComputeBackend.WebGpu` will exhibit similar growth.

**Q4: Is the WebGPU path stable enough for Phase 2?**

The WebGPU sort itself is stable. The instability is in the async
lifecycle above it — specifically the dual-reference retention pattern
between `SceneCache.currentPreparedScene` and `asyncPreparedScene`
Compose state. Phase 2 cannot proceed until the async path reaches a
heap plateau.

---

## Async Retention Pattern Analysis

### Root Cause: Dual-Reference PreparedScene Lifetime

The WebGPU async path holds **two independent references** to each
PreparedScene:

1. **`SceneCache.currentPreparedScene`** — updated every frame by
   `rebuildAsync()` (runs on `Dispatchers.Default`)
2. **`asyncPreparedScene`** — Compose `MutableState<PreparedScene?>`
   updated after `prepareAsync()` completes, read by Canvas lambda

The CPU sync path only has reference #1. The Canvas lambda calls
`ensurePreparedScene()` → `rebuild()` directly, replacing the single
cache reference in-place.

### Timeline of Dual-Reference Leak

```
Frame N:
  LaunchedEffect: prepareAsync() → cache.currentPreparedScene = SceneN
  After withContext: asyncPreparedScene.value = SceneN
  Canvas: renders SceneN (reads asyncPreparedScene.value)

Frame N+1:
  LaunchedEffect: rebuildAsync() sets currentPreparedScene = null (line 169)
  then sets currentPreparedScene = SceneN+1 (line 190)
  BUT: asyncPreparedScene.value still = SceneN (not yet updated)
  Canvas: still renders SceneN (old reference)
  → SceneN is retained by asyncPreparedScene even though cache dropped it

Frame N+2:
  After withContext completes: asyncPreparedScene.value = SceneN+1
  NOW SceneN can be collected (but may have been promoted to old-gen)
```

### Contributing Factors

**1. Off-thread allocation promotes to old-gen.**
Objects created in `withContext(Dispatchers.Default)` — TransformedItem,
DoubleArray, RenderCommand, ArrayList — are allocated on a thread pool.
If they survive one GC cycle while held by the dual references, they
promote to old-gen. Old-gen collection is much less frequent, so these
objects accumulate.

**2. Each PreparedScene holds a fresh ArrayList<RenderCommand>.**
`IsometricEngine.projectSceneAsync()` creates `ArrayList<RenderCommand>(sortedItems.size)`
every frame (line 319). This list escapes into PreparedScene and cannot
be reused (the F2.3 fix attempt proved this — clearing the shared list
caused blank frames). Each list holds ~280 RenderCommand objects with
DoubleArray point buffers.

**3. SceneCache.rebuildAsync() drops cache ref but not state ref.**
Lines 164-169 set `currentPreparedScene = null` before building the new
scene. This releases the cache reference. But `asyncPreparedScene.value`
still holds the old scene until `collect{}` completes and updates the
state on line 365.

**4. conflate() delays collection.**
`snapshotFlow { ... }.conflate().collect { ... }` means the collector
only processes the latest request. If the GPU sort takes longer than one
frame, the collect block hasn't finished yet, so `asyncPreparedScene.value`
still holds the N-1 scene while scene N is being built. This extends the
old scene's lifetime by the full GPU sort duration.

### Memory Comparison Summary

| Aspect | CPU Sync Path | WebGPU Async Path |
|--------|---------------|-------------------|
| PreparedScene refs | 1 (cache only) | 2 (cache + Compose state) |
| Release timing | Immediate (next rebuild) | Delayed (next state update) |
| Thread affinity | Main thread (young-gen) | Default dispatcher (old-gen promotion) |
| RenderCommand list | Same lifecycle as cache | Extended by state ref |

### ~~Fix Strategy~~ (Superseded)

~~The core fix must eliminate the dual-reference window.~~

**This analysis was disproven by diagnostic testing.** See "Diagnostic
Isolation Tests" below. The dual-reference pattern is NOT the root cause.

---

## Diagnostic Isolation Tests

### Hypothesis Testing

The initial "Async Retention Pattern Analysis" above hypothesized that
the dual-reference between `SceneCache.currentPreparedScene` and
`asyncPreparedScene` Compose state was causing old-gen promotion and
memory growth. Three fix attempts targeting this hypothesis all failed,
leading to targeted diagnostic bypass tests.

### Fix Attempt 1: Remove withContext(Dispatchers.Default) — FAILED

**Change:** Removed `withContext(Dispatchers.Default)` wrapper from
`prepareAsync()`, running GPU sort on the main thread.

**Result:** Memory growth WORSE — 63→195 MB at T+5s, ~10 MB/s. Blocking
the main thread during projection prevented interleaving of Canvas draws
and prepares, increasing GC pressure.

**Conclusion:** Thread affinity is not the root cause. Reverted.

### Fix Attempt 2: Path object pool — NO EFFECT

**Files changed:**
- `IsometricRenderer.kt` — Added `pathPool: ArrayList<Path>()`. The
  `renderPreparedScene()` method reuses pooled Path objects via
  `fillComposePath()` instead of allocating new ones per frame.
- `RenderExtensions.kt` — Added `fillComposePath(target: Path)` method
  that resets and refills an existing Path.

**Result:** Memory trajectory unchanged. Path allocations are normal
young-gen garbage collected efficiently.

**Conclusion:** Compose `Path` allocations are not the leak source.
Changes retained (harmless optimisation, eliminates ~280 Path() calls
per frame).

### Fix Attempt 3: Version counter replaces MutableState — NO EFFECT

**Files changed:**
- `IsometricScene.kt` — Replaced `MutableState<PreparedScene?>` with
  `mutableIntStateOf` version counter. Canvas subscribes to version
  and reads scene directly from `renderer.currentPreparedScene`.
- `SceneCache.kt` — Removed `currentPreparedScene = null` from
  `rebuildAsync()` to keep the scene alive during concurrent Canvas
  reads.

**Result:** Memory trajectory unchanged. Compose snapshot system is not
retaining old PreparedScene values.

**Conclusion:** The dual-reference Compose state hypothesis is wrong.
Changes retained (simpler state flow).

### Diagnostic Bypass Test 1: Engine-level GPU sort bypass — FLAT

**Change:** In `IsometricEngine.projectSceneAsync()`, replaced
`computeBackend.sortByDepthKeys(depthKeys)` with
`IntArray(count) { it }`. This bypassed ALL WebGPU code — no GPU
context, no sort, no JNI calls.

**Result:** 12 MB flat for 75 seconds. Zero growth.

**Conclusion:** The leak is definitively in the WebGPU compute path.

### Diagnostic Bypass Test 2: Sorter-level bypass — FLAT

**Change:** In `GpuDepthSorter.sortByDepthKeys()`, added
`if (true) return IntArray(depthKeys.size) { it }` at the top.
`WebGpuComputeBackend.ensureContext()` still runs — GPU thread starts,
`processEvents()` polling is active — but the actual sort execution
(`withContext(NonCancellable) { ctx.withGpu { ... } }`) is skipped.

**Result:** 8-15 MB flat for 60 seconds. Zero growth.

**Conclusion:** The GPU context lifecycle and `processEvents` polling
are NOT the leak source. The leak is specifically in the per-frame sort
execution block.

### Root Cause: JNI wrapper objects in sort execution

The sort execution block creates per-frame JNI wrapper objects that wrap
native Dawn handles:

| Object | Count/frame | Source line |
|--------|-------------|-------------|
| GPUCommandEncoder | 1 | `device.createCommandEncoder()` |
| GPUComputePassEncoder | ~45 | `encoder.beginComputePass()` (loop) |
| GPUCommandBuffer | 1 | `encoder.finish()` |
| ByteBuffer (upload) | 1 | `uploadBuffer.getMappedRange()` |
| ByteBuffer (readback) | 1 | `resultReadback.getConstMappedRange()` |

Total: ~49 JNI wrapper objects per frame × 60 fps = ~2,940 objects/sec.

All wrapper classes implement `AutoCloseable` with `@FastNative external`
`close()` methods that decrement native reference counts. Without explicit
`close()`, cleanup depends on GC finalization which requires 2 cycles
(mark finalizable → run finalizer → collect). At 60fps the finalization
queue falls behind and native handles accumulate.

---

## Fix Attempts on Root Cause

### Fix Attempt 4: Explicit close() on per-frame JNI objects — REDUCED

**Files changed:**
- `GpuDepthSorter.kt` — Added `pass.close()` after `pass.end()` for
  each compute pass encoder. Added explicit `val commandBuffer =
  encoder.finish()` followed by `commandBuffer.close()` and
  `encoder.close()` after `queue.submit()`.

**Result:** Growth rate reduced but not eliminated. Dalvik Heap grew
45→130 MB in 15s (~5.7 MB/s, down from ~8 MB/s). Native Heap was flat
(13 MB), confirming the leak is in Dalvik/Java objects not native memory.

**Analysis:** The `close()` calls release native Dawn handles correctly,
but the Dalvik Heap growth suggests additional Java objects are
accumulating. Candidates:
- ByteBuffer wrappers from `getMappedRange()`/`getConstMappedRange()`
  (direct ByteBuffers created fresh each call via JNI
  `NewDirectByteBuffer`)
- Continuation/callback objects from `mapAndAwait()` and
  `onSubmittedWorkDone()` suspend functions (each creates coroutine
  machinery + `GPURequestCallback` + `Executor`)
- `arrayOf(commandBuffer)` creates a 1-element Array each frame

**Status:** PARTIALLY EFFECTIVE. Further investigation needed on
remaining allocation sources.

### Diagnostic Test 5: Coroutine dispatch only (no Dawn API calls) — FLAT

**Change:** Kept `withContext(NonCancellable)` + `ctx.withGpu` dispatch
and `ensurePipelineBuilt()` + `ensureBuffers()`, but skipped all
per-frame Dawn API calls (no upload, encode, submit, or readback).

**Result:** 8-12 MB flat for 60 seconds.

**Conclusion:** Coroutine machinery does NOT leak. The leak is in
per-frame Dawn JNI API calls.

### Diagnostic Test 6: Upload path only (mapAndAwait + write + unmap) — LEAKING

**Change:** Kept full upload cycle (mapAndAwait → getMappedRange → write
→ unmap) but skipped encoding, submit, and readback.

**Result:** 160→492 MB in 40s (~8.3 MB/s). OOM crash.

**Conclusion:** The upload path alone causes the full leak.

### Diagnostic Test 7: mapAndAwait + unmap only (no getMappedRange) — LEAKING

**Change:** Called `uploadBuffer.mapAndAwait()` and `uploadBuffer.unmap()`
only, without `getMappedRange` or data write.

**Result:** 238→525 MB in 20s (~14 MB/s). WORSE because faster loop.

**Conclusion:** `mapAndAwait` is the definitive leak source. The JNI
binding for `mapAsync` creates global refs to the `GPURequestCallback`
and `Executor` objects that are never released after the callback fires.
Each call to `awaitGPURequest` (used by `mapAndAwait` and
`onSubmittedWorkDone`) creates a fresh anonymous callback, so every
JNI global ref points to a unique unreachable object, causing continuous
Dalvik heap growth.

### Fix Attempt 5: queue.writeBuffer + reusable callbacks — FIXED ✅

**Root cause:** The `awaitGPURequest` helper creates a fresh anonymous
`GPURequestCallback<Unit>` for every async Dawn operation via
`suspendCancellableCoroutine`. The Dawn JNI binding (`mapAsync`,
`onSubmittedWorkDone`) creates JNI global refs to the callback and
executor objects. These global refs are never deleted after the callback
fires, preventing the Java objects from being GC'd. At 60fps with 2-3
async calls per frame, this produces ~120-180 unreachable but pinned
objects per second, causing ~8-14 MB/s Dalvik heap growth.

**Fix (3 parts):**

1. **Upload: `queue.writeBuffer` instead of staging buffer.** Replaced
   the map→getMappedRange→write→unmap→copyBufferToBuffer cycle with a
   single `ctx.queue.writeBuffer(primaryBuffer, 0, packedKeys)` call.
   This is a synchronous CPU→GPU copy that bypasses `mapAsync` entirely.
   The upload staging buffer and its `mappedAtCreation` management were
   removed.

2. **Readback + sync: Reusable singleton callbacks.** Created
   `ReusableUnitCallback` class that wraps a `CompletableDeferred<Unit>`.
   Two singleton instances (one for `onSubmittedWorkDone`, one for
   `mapAsync`) are reused across all frames. Since the JNI global refs
   always point to the same Java objects, the leak is bounded to a
   constant 2 objects regardless of frame count. New `awaitSubmittedWorkDone()`
   and `awaitBufferMap()` helper methods replace direct
   `mapAndAwait()`/`onSubmittedWorkDone()` suspend calls.

3. **Readback data: `readMappedRange` with reusable ByteBuffer.**
   Replaced `getConstMappedRange()` (which creates a new DirectByteBuffer
   via JNI per frame) with `readMappedRange(0L, cachedReadbackDataBuffer)`
   which copies into a reusable pre-allocated ByteBuffer.

**Additional retained fixes:**
- Explicit `close()` on GPUCommandEncoder, GPUComputePassEncoder, and
  GPUCommandBuffer after use (from Fix Attempt 4)

**Files changed:**
- `GpuDepthSorter.kt` — Complete rewrite of upload/readback/sync paths:
  - Removed `uploadBuffer`, `uploadBufferNeedsRemap` fields
  - Added `cachedReadbackDataBuffer`, `reusableExecutor`,
    `reusableWorkDoneCallback`, `reusableMapCallback` fields
  - Added `ReusableUnitCallback` inner class
  - Added `awaitSubmittedWorkDone()`, `awaitBufferMap()`,
    `ensureReadbackDataBuffer()` methods
  - Updated `ensureBuffers()`, `destroyCachedBuffers()` to remove
    upload buffer
  - Updated class KDoc

**Result:** Dalvik Heap **flat at 6-10 MB** for 2+ minutes. Zero growth.
Native Heap flat at 14 MB. No OOM.

### Memory Trajectory After Fix — Animated Towers (WebGPU)

| Time | Dalvik Heap (MB) | Native Heap (MB) | Total PSS (MB) |
|------|------------------|-------------------|-----------------|
| T+5s | 10 | 22 | 178 |
| T+20s | 6.5 | 22 | 175 |
| T+40s | 10 | 14 | 140 |
| T+60s | 7.9 | 14 | 138 |
| T+90s | 6.6 | 14 | 137 |
| T+120s | 7.3 | 14 | 138 |

**Heap growth rate:** 0 MB/s (flat plateau). Oscillates within GC cycle.

### Frame Timing After Fix — Animated Towers (WebGPU)

At T+120s (6465 frames rendered):

| Metric | Before Fix | After Fix |
|--------|-----------|-----------|
| 50th percentile | 16→20ms (degrading) | 16ms (stable) |
| 90th percentile | 28→31ms (degrading) | 27ms (stable) |
| 95th percentile | 32ms+ | 31ms |
| 99th percentile | 38ms+ | 38ms |
| Janky frames (modern) | N/A (OOM crash) | 44% |
| GPU 50th percentile | — | 2ms |

Frame timing is now **stable** — no degradation over time. The GC
pressure reduction from eliminating the JNI leak removes the progressive
frame timing degradation that was observed before the fix.

---

## Gate Decision

**Phase 1 stability work COMPLETE. WebGPU path is ready for Phase 2.**

### Reasoning

- **Memory trend:** Flat plateau at 6-10 MB Dalvik Heap for 2+ minutes.
  No growth. Matches the CPU backend's memory profile. **PASS.**

- **Root cause fixed:** JNI global ref leak in Dawn `mapAsync` /
  `onSubmittedWorkDone` callbacks. Fixed by:
  1. Replacing staging buffer upload with `queue.writeBuffer`
  2. Using reusable singleton callbacks instead of per-frame anonymous ones
  3. Using `readMappedRange` with reusable ByteBuffer instead of
     `getConstMappedRange`
  4. Explicit `close()` on command encoder, compute passes, command buffer

- **GC behavior:** Normal young-gen collection, no old-gen accumulation.
  Frame timing is stable over the entire run (no degradation).

- **Frame timing:** 16ms median, 27ms 90th percentile. 44% janky frames
  (modern metric). GPU sort itself is 2ms (50th percentile). The WebGPU
  path is 2-3x faster than the CPU path (44ms median).

### Phase 2 Readiness

| Criterion | Status |
|-----------|--------|
| Heap plateau (2min animated run) | ✅ PASS (6-10 MB flat) |
| No OOM crash | ✅ PASS (ran 2+ min, no crash) |
| Frame timing stable (no degradation) | ✅ PASS (16ms stable) |
| Janky frames <50% (modern metric) | ✅ PASS (44%) |

---

## Active Changes (Current HEAD)

Summary of all changes currently active in the codebase:

| File | Change | Status |
|------|--------|--------|
| `GpuDepthSorter.kt` | **Primary fix:** `queue.writeBuffer` upload, reusable callbacks, `readMappedRange`, explicit `close()` | ✅ Final |
| `IsometricRenderer.kt` | Path pool in `renderPreparedScene()` | Active (harmless opt) |
| `RenderExtensions.kt` | `fillComposePath()` method | Active (harmless opt) |
| `IsometricScene.kt` | Version counter instead of MutableState | Active (simpler flow) |
| `SceneCache.kt` | Keep `currentPreparedScene` alive during rebuild | Active |
| `SceneCache.kt` | Reusable command list (F1.1) | Active |
| `IsometricEngine.kt` | `cachedSortedItems` reuse | Active |
| `WebGpuSampleActivity.kt` | Tab order: WebGPU first | Active (profiling aid) |
