---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: webgpu-textures
review-command: "review webgpu-textures backend concurrency"
status: complete
updated-at: "2026-04-12"
metric-findings-total: 7
metric-findings-blocker: 0
metric-findings-high: 2
metric-findings-medium: 3
metric-findings-low: 1
metric-findings-nit: 1
result: conditional-pass
tags:
  - concurrency
  - thread-safety
  - async
  - webgpu
  - coroutines
refs:
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuRenderPipeline.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/WebGpuSceneRenderer.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuFullPipeline.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/GpuContext.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureBinder.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureStore.kt
---

# WebGPU-Textures Backend Concurrency Review

## Findings Table

| ID | Severity | File | Line(s) | Title |
|----|----------|------|---------|-------|
| WT-BC-1 | HIGH | `GpuRenderPipeline.kt` | 55–56 | `ensurePipeline()` has a TOCTOU window — concurrent callers both pass the `null` guard |
| WT-BC-2 | HIGH | `WebGpuSceneRenderer.kt` | 457 | `pipeline.pipeline!!` non-null assert on `var pipeline: GPURenderPipeline?` — no structural guarantee it is set |
| WT-BC-3 | MED | `GpuTextureBinder.kt` | 43 | `bindGroupLayout` is a public mutable `var` with no GPU-thread assertion — can be written from any thread |
| WT-BC-4 | MED | `GpuFullPipeline.kt` | 119–125 | `textureBindGroup` getter builds a fallback bind group before `bindGroupLayout` is set — will throw `checkNotNull` |
| WT-BC-5 | MED | `GpuTextureStore.kt` | 35–57 | `init` block calls `ctx.queue.writeTexture` outside of `ctx.withGpu { }` — Dawn API call on construction thread |
| WT-BC-6 | LOW | `GpuRenderPipeline.kt` | 124 | `createRenderPipelineAndAwait` error propagation unguarded — exception bubbles through `ensurePipeline()` leaving `pipeline = null` but `vertexModule`/`fragmentModule` already allocated |
| WT-BC-7 | NIT | `GpuFullPipeline.kt` | 196–202 | `ensurePipelines()` has no `assertGpuThread()` guard, inconsistent with `upload()` and `dispatch()` |

---

## Detailed Findings

### WT-BC-1 — HIGH: `ensurePipeline()` has a TOCTOU window for concurrent callers

**File:** `isometric-webgpu/.../pipeline/GpuRenderPipeline.kt`, lines 55–56

**Problem:**
`ensurePipeline()` is a suspend function with the following idempotency guard:

```kotlin
suspend fun ensurePipeline() {
    if (pipeline != null) return   // ← read

    vertexModule = device.createShaderModule(...)
    fragmentModule = device.createShaderModule(...)
    ...
    val rp = device.createRenderPipelineAndAwait(descriptor)   // ← suspension point
    pipeline = rp                                               // ← write
    textureBindGroupLayout = rp.getBindGroupLayout(0)
}
```

`createRenderPipelineAndAwait` is a suspending call. If two coroutines both enter `ensurePipeline()` before either has set `pipeline`, both will pass the `if (pipeline != null) return` guard and both will proceed to call `device.createShaderModule` and `createRenderPipelineAndAwait`. The result is two pipelines compiled in parallel, and `pipeline`/`textureBindGroupLayout` are written by whichever coroutine resumes last — leaking the handles from the first completer.

**Current mitigation:** In practice this cannot happen because `ensurePipeline()` is only called from within `context.withGpu { }` (`ensureInitialized`, line 278), and `withGpu` dispatches via `withContext(gpuDispatcher)` which is a **single-threaded** executor (`Executors.newSingleThreadExecutor`). Single-thread confinement means at most one coroutine body executes at a time on the dispatcher, so the window does not exist today.

**Why it is still HIGH:**
The single-thread invariant is not enforced by the `GpuRenderPipeline` API itself. The class KDoc says "Must be called from the GPU thread" but there is no `assertGpuThread()` call at entry to enforce this. If a future caller calls `ensurePipeline()` from `Dispatchers.IO` (a common mistake during refactoring), the race window opens immediately. The correct fix is to either add an `assertGpuThread()` at entry, or restructure as a `Mutex`-protected lazy init.

**Fix options (prefer A):**

Option A — assert thread confinement (lowest cost, matches existing pattern):
```kotlin
suspend fun ensurePipeline() {
    ctx.assertGpuThread()         // ← add this
    if (pipeline != null) return
    ...
}
```
This requires threading `ctx: GpuContext` into `GpuRenderPipeline`, which also aligns it with `GpuFullPipeline`.

Option B — `Mutex`-guarded lazy (safe regardless of dispatcher):
```kotlin
private val initMutex = Mutex()
suspend fun ensurePipeline() {
    if (pipeline != null) return
    initMutex.withLock {
        if (pipeline != null) return
        ...
    }
}
```

---

### WT-BC-2 — HIGH: `pipeline.pipeline!!` non-null assert has no structural guarantee

**File:** `isometric-webgpu/.../WebGpuSceneRenderer.kt`, line 457

**Problem:**
```kotlin
pass.setPipeline(pipeline.pipeline!!)
```

`pipeline` is `var pipeline: GPURenderPipeline? = null` in `GpuRenderPipeline`. The `!!` operator will throw `NullPointerException` if `ensurePipeline()` was not successfully called before `drawFrame()` reaches this line.

The control flow path that reaches this line is:
1. `renderLoop()` calls `ensureInitialized()`, which calls `rp.ensurePipeline()` inside `context.withGpu { }`.
2. After `ensureInitialized()` returns, `renderLoop()` enters the frame loop and calls `drawFrame()` on every iteration.
3. `drawFrame()` reads `renderPipeline` via `val pipeline = checkNotNull(renderPipeline)` (line 391) and calls `pass.setPipeline(pipeline.pipeline!!)`.

**The gap:** `ensureInitialized()` has an early-return guard:
```kotlin
if (gpuContext != null && gpuSurface != null && renderPipeline != null && fullPipeline != null) return
```
This guard checks `renderPipeline != null` but does **not** check `renderPipeline.pipeline != null`. If `ensurePipeline()` throws partway through (e.g., `createRenderPipelineAndAwait` fails — see WT-BC-6), the `GpuRenderPipeline` instance is assigned to `renderPipeline` before the exception escapes (it is set at line 279 before `ensurePipeline()` is called at line 278... wait, examining the diff order):

```kotlin
val rp = GpuRenderPipeline(context.device, surfaceFormat)
rp.ensurePipeline()          // if this throws, rp.pipeline == null
renderPipeline = rp          // this line is not reached on exception
```

Actually `renderPipeline = rp` comes *after* `rp.ensurePipeline()`. If `ensurePipeline()` throws, `renderPipeline` remains `null`, the exception propagates out of `context.withGpu { }`, and `ensureInitialized()` throws. The `renderLoop()` try/finally calls `cleanup()`. So in the normal throw path, `renderPipeline` stays null and `checkNotNull(renderPipeline)` in `drawFrame` would be the guard.

**The real gap** is subtler: `ensurePipeline()` itself modifies `vertexModule` and `fragmentModule` before the await. If re-entered after a partial failure (hypothetically), those references would be re-assigned without closing the old objects. More pressingly, `pipeline.pipeline!!` is not covered by any explicit pre-condition check. The `checkNotNull(renderPipeline)` guard at line 391 only proves `renderPipeline` is a non-null `GpuRenderPipeline` instance, not that its inner `pipeline` field is set. A future refactor that adds a code path creating `GpuRenderPipeline` without calling `ensurePipeline()` would silently compile and then NPE at runtime in `drawFrame`.

**Fix:** Replace `pipeline.pipeline!!` with a checked access that fails loudly with a descriptive message:
```kotlin
val gpuPipeline = checkNotNull(pipeline.pipeline) {
    "GpuRenderPipeline.ensurePipeline() was not called — pipeline is null in drawFrame"
}
pass.setPipeline(gpuPipeline)
```
Alternatively, expose pipeline access as a non-nullable property that throws `IllegalStateException` when not initialized, removing the nullable `var` from the public surface entirely.

---

### WT-BC-3 — MED: `GpuTextureBinder.bindGroupLayout` is a public mutable `var` with no thread guard

**File:** `isometric-webgpu/.../texture/GpuTextureBinder.kt`, line 43

**Problem:**
```kotlin
var bindGroupLayout: GPUBindGroupLayout? = null
```

This is written from `WebGpuSceneRenderer.ensureInitialized()` after render pipeline creation:
```kotlin
gp.textureBinder.bindGroupLayout = rp.textureBindGroupLayout
```
This write happens inside `context.withGpu { }`, so it is on the GPU thread. However, `buildBindGroup()` — which reads `bindGroupLayout` — is called from both:
- `uploadTextures()` (inside `upload()`, which is called from `uploadScene()`, which calls `ctx.withGpu { ... }`) — safe
- `textureBindGroup` lazy getter (via `clearScene()`, also GPU-thread-confined) — safe

The current call graph keeps all accesses on the GPU thread. The risk is identical to WT-BC-1: the `var` is publicly settable from any thread, and `buildBindGroup()` has no `assertGpuThread()` guard. A misuse would produce a data race on the Dawn `GPUBindGroupLayout` pointer with no diagnostic.

**Fix:** Restrict mutability or add a thread assertion to `buildBindGroup`:
```kotlin
fun buildBindGroup(textureView: GPUTextureView): GPUBindGroup {
    ctx.assertGpuThread()
    val layout = checkNotNull(bindGroupLayout) { ... }
    ...
}
```

---

### WT-BC-4 — MED: `textureBindGroup` getter can be invoked before `bindGroupLayout` is set

**File:** `isometric-webgpu/.../pipeline/GpuFullPipeline.kt`, lines 119–125

**Problem:**
```kotlin
val textureBindGroup: GPUBindGroup
    get() = _textureBindGroup ?: run {
        val bg = textureBinder.buildBindGroup(textureStore.fallbackTextureView)
        _textureBindGroup = bg
        bg
    }
```

`buildBindGroup` calls `checkNotNull(bindGroupLayout)`. `bindGroupLayout` is set in `ensureInitialized()` **after** `gp.ensurePipelines()` returns:

```kotlin
val rp = GpuRenderPipeline(context.device, surfaceFormat)
rp.ensurePipeline()
renderPipeline = rp
val gp = GpuFullPipeline(context)
gp.textureBinder.bindGroupLayout = rp.textureBindGroupLayout   // ← set here
gp.ensurePipelines()
fullPipeline = gp
```

The `GpuTextureStore.init` block runs `ctx.queue.writeTexture` during `GpuFullPipeline` construction (see WT-BC-5 below), and `GpuTextureBinder` creates its `sampler` during construction — both before `bindGroupLayout` is set.

The lazy `textureBindGroup` getter is only accessed from `drawFrame` (line 460), which runs in the frame loop after `ensureInitialized()` completes. So the `checkNotNull` will not actually throw in the normal path. However, `clearScene()` at line 421 calls `textureBinder.buildBindGroup(...)` synchronously, and `clearScene()` can be called from `upload()`, which is called from `uploadScene()`, which can theoretically be called concurrently with initialization on a misuse path.

**Fix:** Add a lifecycle check or document ordering contract. The safest fix is to require `bindGroupLayout` at construction time (pass it as a constructor parameter to `GpuTextureBinder`), making the precondition structurally impossible to violate:

```kotlin
internal class GpuTextureBinder(
    private val ctx: GpuContext,
    val bindGroupLayout: GPUBindGroupLayout,   // non-nullable, required at construction
) : AutoCloseable
```

This requires changing the init order so `GpuRenderPipeline.ensurePipeline()` runs before `GpuFullPipeline` is constructed — which is exactly what `ensureInitialized` already does. The refactor would make the current implicit ordering explicit.

---

### WT-BC-5 — MED: `GpuTextureStore.init` performs Dawn API calls outside of `ctx.withGpu { }`

**File:** `isometric-webgpu/.../texture/GpuTextureStore.kt`, lines 35–57

**Problem:**
The `init` block of `GpuTextureStore` calls:
```kotlin
fallbackTexture = createTexture(2, 2)          // ctx.device.createTexture(...)
ctx.queue.writeTexture(...)                    // Dawn API call
fallbackTextureView = fallbackTexture.createView()
```

`GpuTextureStore` is constructed as `val textureStore = GpuTextureStore(ctx)` inside `GpuFullPipeline`'s property initializer, which runs when `GpuFullPipeline(context)` is called from `ensureInitialized()`. That construction is already inside `context.withGpu { }` (line 280 of `WebGpuSceneRenderer`), so in the current call path these Dawn calls happen on the GPU thread.

However:
1. `GpuTextureStore` takes a `ctx: GpuContext` constructor parameter and calls Dawn APIs in `init`. Nothing about the class signature or KDoc signals that it must be constructed on the GPU thread. A future test or alternate callee constructing `GpuTextureStore` off-thread would silently race with the polling loop.
2. `GpuTextureBinder.sampler` has the same issue — `ctx.device.createSampler(...)` is called in a property initializer.

**Fix:** Document the construction-thread requirement clearly in KDoc, or add an `assertGpuThread()` call at the top of both `init` blocks:
```kotlin
init {
    ctx.assertGpuThread()
    ...
}
```

---

### WT-BC-6 — LOW: `ensurePipeline()` partial failure leaks shader module handles

**File:** `isometric-webgpu/.../pipeline/GpuRenderPipeline.kt`, lines 58–129

**Problem:**
If `createRenderPipelineAndAwait` throws (driver error, OOM, shader compilation failure), the function exits with:
- `vertexModule != null` — allocated and not closed
- `fragmentModule != null` — allocated and not closed
- `pipeline == null` — not set
- `textureBindGroupLayout == null` — not set

`close()` will correctly release `vertexModule` and `fragmentModule` via the null-safe closes, but only if `close()` is called on the instance. In `ensureInitialized()`, if `rp.ensurePipeline()` throws, `renderPipeline = rp` is never reached, and `rp` is a local variable that will be GC'd. `cleanup()` in `renderLoop`'s finally block calls `renderPipeline?.close()` — but `renderPipeline` is still `null` because the assignment never happened. The `rp` local goes out of scope with its Dawn JNI handles unreleased until the GC finalizer runs (if one exists).

This is a low-severity handle leak on the error path, not a crash or correctness bug in the happy path.

**Fix:** Use a try-catch in `ensureInitialized()` to close `rp` on failure:
```kotlin
val rp = GpuRenderPipeline(context.device, surfaceFormat)
try {
    rp.ensurePipeline()
} catch (t: Throwable) {
    rp.close()
    throw t
}
renderPipeline = rp
```

---

### WT-BC-7 — NIT: `GpuFullPipeline.ensurePipelines()` lacks `assertGpuThread()` guard

**File:** `isometric-webgpu/.../pipeline/GpuFullPipeline.kt`, lines 196–202

**Problem:**
`upload()` and `dispatch()` both call `ctx.assertGpuThread()` at entry:
```kotlin
fun upload(scene: PreparedScene, ...) {
    ctx.assertGpuThread()
    ...
}
fun dispatch(encoder: GPUCommandEncoder, ...) {
    ctx.assertGpuThread()
    ...
}
```

`ensurePipelines()` is a `suspend fun` that delegates to sub-pipeline `ensurePipeline()` methods, which call async Dawn APIs. It lacks the same guard:
```kotlin
suspend fun ensurePipelines() {
    transform.ensurePipeline()   // no assertGpuThread()
    sort.ensurePipeline()
    packer.ensurePipeline()
    emit.ensurePipelines()
}
```

The call site in `ensureInitialized()` is inside `context.withGpu { }`, so the thread is correct in practice. Adding the assertion would catch future callers invoking `ensurePipelines()` from a non-GPU context at the earliest possible moment, consistent with the existing enforcement pattern in the same class.

**Fix:**
```kotlin
suspend fun ensurePipelines() {
    ctx.assertGpuThread()
    transform.ensurePipeline()
    ...
}
```

---

## Threading Model Summary

The overall threading design is sound. All GPU API calls are confined to a single-threaded `ExecutorCoroutineDispatcher` via `GpuContext.withGpu { }`. The `processEvents()` polling loop runs on the same dispatcher, which correctly avoids the SIGSEGV that would result from processing events on a separate thread while GPU commands are in flight. `assertGpuThread()` is used correctly in the hot paths (`upload`, `dispatch`, `dispatchIndividualSubmits`).

The findings above are primarily about the **absence of enforcement** on the init path (`ensurePipeline`, `ensurePipelines`, `GpuTextureStore.init`, `GpuTextureBinder` property init) and one structural fragility (`pipeline.pipeline!!`). None of the findings represent an active race condition under the current call graph — they are latent hazards that become real bugs under misuse or future refactoring.

## Lifecycle Guard Assessment

The question "can render happen before init completes?" has a clear answer: no, because `ensureInitialized()` is called and awaited synchronously at the top of `renderLoop()` before the frame loop is entered. `renderPipeline` is only assigned inside `context.withGpu { }` after `ensurePipeline()` returns. `drawFrame()` calls `checkNotNull(renderPipeline)` as its first read of this value.

The `pipeline.pipeline!!` at line 457 is the one place where the guard one level deeper is an assertion rather than a structured guarantee.

## Error Propagation Assessment

`createRenderPipelineAndAwait` is a suspend function from the vendored `androidx.webgpu` source. The WebGPU spec requires that async pipeline creation failures reject the promise (Kotlin: throw). A thrown exception from `ensurePipeline()` will propagate through `context.withGpu { }` (which is `withContext` — it does not swallow exceptions), through `ensureInitialized()`, and into `renderLoop()`'s outer `try { ensureInitialized(...) }`. There is no `catch` for this outer try — the exception falls through to the `finally { cleanup() }` block. `cleanup()` correctly nulls all fields and destroys the context. The exception then propagates to `renderLoop()`'s caller.

This is correct behavior. The only deficiency is the handle leak described in WT-BC-6.
