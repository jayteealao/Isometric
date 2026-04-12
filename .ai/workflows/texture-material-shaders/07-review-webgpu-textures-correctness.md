---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: webgpu-textures
review-command: correctness
status: complete
updated-at: "2026-04-12T00:00:00Z"
metric-findings-total: 9
metric-findings-blocker: 0
metric-findings-high: 3
result: issues-found
tags: []
refs:
  review-master: 07-review.md
---

# Review: webgpu-textures — Correctness

## Findings

| ID | Sev | Conf | File:Line | Issue |
|----|-----|------|-----------|-------|
| WT-CR-1 | HIGH | High | `WebGpuSceneRenderer.kt:457` | `pipeline.pipeline!!` force-unwrap can NPE if `ensurePipeline()` was never awaited |
| WT-CR-2 | HIGH | High | `GpuFullPipeline.kt:120-125` | `textureBindGroup` lazy getter calls `textureBinder.buildBindGroup` before `bindGroupLayout` is set — throws `checkNotNull` |
| WT-CR-3 | HIGH | Med | `GpuTextureStore.kt:107-113` | `close()` calls `tex.destroy()` + `tex.close()` on every texture including `fallbackTexture`, but then also calls `fallbackTextureView.close()` — double-destroy of fallback texture is possible |
| WT-CR-4 | MED | High | `GpuFullPipeline.kt:297-359` | `uploadTextures` replaces `_textureBindGroup` without closing the previous one in the non-null→fallback transition path |
| WT-CR-5 | MED | High | `GpuFullPipeline.kt:374-398` | `uploadTexIndexBuffer` uses `scene.commands.size` as `faceCount`, but `upload()` already computes the true `faceCount` from `sceneData.faceCount` — they can diverge |
| WT-CR-6 | MED | Med | `IsometricFragmentShader.kt:22` | Unconditional `textureSample` on a 2×2 fallback with `Linear` filter will produce interpolated magenta at boundaries — visually wrong for geometry near UV=0 or UV=1 edges when no texture is bound |
| WT-CR-7 | LOW | High | `GpuFullPipeline.kt:346` | Cache key uses `System.identityHashCode(bitmap)` which is not guaranteed to be unique — two bitmaps can share the same hash code and bypass the cache guard |
| WT-CR-8 | LOW | Med | `GpuTextureBinder.kt:53-66` | `buildBindGroup` returns a new `GPUBindGroup` every call; callers in `GpuFullPipeline` close the old group before replacing, but the fallback-rebuild path in `clearScene` does not guard against a `null` old group being double-closed via the lazy getter |
| WT-CR-9 | NIT | Med | `WebGpuSampleActivity.kt:419-430` | Checkerboard bitmap created with `Bitmap.createBitmap(pixels, size, size, Config.ARGB_8888)` — `remember` protects against recomposition but bitmap is never recycled on disposal |

---

## Detailed Findings

### WT-CR-1 — HIGH: `pipeline.pipeline!!` force-unwrap with no guarantee `ensurePipeline()` has run

**Location:** `WebGpuSceneRenderer.kt:457`

**Evidence:**
```kotlin
pass.setPipeline(pipeline.pipeline!!)
```

`pipeline` is the `GpuRenderPipeline` local variable captured from `renderPipeline`, and `renderPipeline` is only set after the `context.withGpu { ... }` block in `ensureInitialized()` fully completes. However, `ensureInitialized()` checks all four fields at the top:

```kotlin
if (gpuContext != null && gpuSurface != null && renderPipeline != null && fullPipeline != null) return
```

Because this guard is a simple four-way non-null check with no happens-before guarantee (all four assignments happen inside the same `context.withGpu` block), the check is fine for the happy path. The risk is **if `ensurePipeline()` throws** after the pipeline was already assigned. In that case `renderPipeline` holds a `GpuRenderPipeline` whose internal `pipeline` is still `null` (assignment happens at the end of `ensurePipeline()`), and the `!!` operator would throw `NullPointerException` inside the render pass — which would propagate up through `ctx.withGpu` as an unrecoverable error, bypassing pass clean-up.

Additionally, `ensureInitialized` is called before `drawFrame`, but nothing prevents a future refactor that calls `drawFrame` on a renderer that failed mid-initialization. Replacing `pipeline.pipeline!!` with `checkNotNull(pipeline.pipeline) { "pipeline not initialized" }` would give a diagnostic message rather than a bare NPE.

**Severity:** HIGH | **Confidence:** High

---

### WT-CR-2 — HIGH: `textureBindGroup` lazy getter fires before `bindGroupLayout` is set

**Location:** `GpuFullPipeline.kt:119-125` / `GpuTextureBinder.kt:53-56`

**Evidence:**
```kotlin
val textureBindGroup: GPUBindGroup
    get() = _textureBindGroup ?: run {
        val bg = textureBinder.buildBindGroup(textureStore.fallbackTextureView)
        _textureBindGroup = bg
        bg
    }
```

And inside `buildBindGroup`:
```kotlin
val layout = checkNotNull(bindGroupLayout) {
    "bindGroupLayout not set — call after GpuRenderPipeline creation"
}
```

The lazy getter is declared `val` with a property getter — it will fire on first access of `textureBindGroup`. In `drawFrame`, the render pass sets:
```kotlin
pass.setBindGroup(0, gp!!.textureBindGroup)
```

This is reached only when `shouldDraw == true`, which requires `gp.hasScene == true`, which requires a successful `upload()`. `upload()` calls `uploadTextures()` which always sets `_textureBindGroup` before returning. So the lazy getter should never need to fire at render time in practice.

However, `clearScene()` also calls:
```kotlin
_textureBindGroup = textureBinder.buildBindGroup(textureStore.fallbackTextureView)
```

If `clearScene()` is called before `rp.textureBindGroupLayout` has been assigned to `gp.textureBinder.bindGroupLayout` — for instance if `upload()` is called concurrently with `ensureInitialized()` from a race condition, or if `clearScene()` is called directly before the first successful `ensurePipelines()` — the `checkNotNull` inside `buildBindGroup` will throw with a confusing message and leave `_textureBindGroup` null.

The deeper issue is the init ordering dependency: `WebGpuSceneRenderer.ensureInitialized()` assigns `gp.textureBinder.bindGroupLayout = rp.textureBindGroupLayout` after `rp.ensurePipeline()` but before `gp.ensurePipelines()`. If `gp.ensurePipelines()` throws, `bindGroupLayout` is set but compute pipelines are not ready. If the caller somehow invokes `upload` between these steps (not possible given the current single `withGpu` block, but fragile) the dependency is violated.

**Severity:** HIGH | **Confidence:** High

---

### WT-CR-3 — HIGH: Double-destroy of `fallbackTexture` in `GpuTextureStore.close()`

**Location:** `GpuTextureStore.kt:106-113`

**Evidence:**
```kotlin
override fun close() {
    for (tex in ownedTextures) {
        tex.destroy()
        tex.close()
    }
    ownedTextures.clear()
    fallbackTextureView.close()
}
```

`fallbackTexture` is added to `ownedTextures` in `init`:
```kotlin
ownedTextures += fallbackTexture
```

So the `for` loop calls `fallbackTexture.destroy()` + `fallbackTexture.close()`. Then `fallbackTextureView.close()` is called on the view that was created from the now-destroyed texture. In WebGPU / Dawn, `GPUTextureView.close()` calls `wgpuTextureViewRelease`. Calling it on a view whose backing texture is already destroyed is typically safe (the view holds its own reference), but immediately after the loop the `fallbackTexture` object itself has already been closed — any subsequent access (e.g., a frame that races with `close()`) would use a released handle.

More critically, if `uploadBitmap` textures are released via `releaseTexture` before `close()`, those textures have already been removed from `ownedTextures` and destroyed. But if `close()` is called without calling `releaseTexture` for the uploaded texture first (as happens in `GpuFullPipeline.close()` — it calls `releaseUploadedTexture()` before `textureStore.close()`), the upload texture is in `ownedTextures` and will be double-destroyed: once by `releaseTexture` and once by the `close()` loop. Tracing the call order in `GpuFullPipeline.close()`:

```kotlin
releaseUploadedTexture()   // calls textureStore.releaseTexture(uploadedTexture!!) → destroy + close + remove from ownedTextures
textureStore.close()       // loops over ownedTextures (fallback only at this point) — OK
```

This order is actually safe because `releaseTexture` removes from `ownedTextures`. However, the fallback texture view is closed *after* the fallback texture is destroyed. If any bind group was holding the view alive and Dawn tries to access the view after `close()`, this creates a use-after-free. The view should be closed before the texture is destroyed, or the texture's own reference counting should keep the view valid. The ordering is fragile.

**Severity:** HIGH | **Confidence:** Med

---

### WT-CR-4 — MED: Bind group not closed in `uploadTextures` no-texture path

**Location:** `GpuFullPipeline.kt:312-319`

**Evidence:**
```kotlin
if (texturedMaterial == null) {
    // No textures in scene — use fallback
    if (lastUploadedBitmapIdentity != null) {
        releaseUploadedTexture()
    }
    _textureBindGroup?.close()
    _textureBindGroup = textureBinder.buildBindGroup(textureStore.fallbackTextureView)
    return
}
```

This path correctly closes `_textureBindGroup` before replacing it. However, when `lastUploadedBitmapIdentity != null` (meaning we had a texture, now we don't), `releaseUploadedTexture()` is called but does *not* reset `lastUploadedBitmapIdentity` to `null`. Looking at the implementation:

```kotlin
private fun releaseUploadedTexture() {
    uploadedTextureView?.close()
    uploadedTextureView = null
    if (uploadedTexture != null) {
        textureStore.releaseTexture(uploadedTexture!!)
        uploadedTexture = null
    }
    lastUploadedBitmapIdentity = null   // ← does reset it
}
```

`releaseUploadedTexture` does reset `lastUploadedBitmapIdentity` to `null`. So this path is actually fine. However, the `bitmap == null` path below (line 337-343) does:

```kotlin
if (bitmap == null) {
    _textureBindGroup?.close()
    _textureBindGroup = textureBinder.buildBindGroup(textureStore.fallbackTextureView)
    lastUploadedBitmapIdentity = null
    return
}
```

It resets `lastUploadedBitmapIdentity = null` but does **not** call `releaseUploadedTexture()`. If there was a previously uploaded GPU texture (`uploadedTexture != null`), it will leak — it stays in `textureStore.ownedTextures` but is neither in the active bind group nor tracked for cleanup, until `close()` destroys everything. For the "unsupported TextureSource" case (e.g. Resource/Asset not yet supported), this means every frame will attempt the texture path, find `bitmap == null`, leak the old GPU texture, and build a new fallback bind group.

**Severity:** MED | **Confidence:** High

---

### WT-CR-5 — MED: `uploadTexIndexBuffer` uses `scene.commands.size` instead of `sceneData.faceCount`

**Location:** `GpuFullPipeline.kt:374`

**Evidence:**
```kotlin
private fun uploadTexIndexBuffer(scene: PreparedScene) {
    val faceCount = scene.commands.size
    if (faceCount == 0) return
    ...
}
```

In `upload()`, the authoritative face count is:
```kotlin
sceneData.upload(scene.commands)
val faceCount = sceneData.faceCount
```

`GpuSceneDataBuffer.faceCount` may differ from `scene.commands.size` if the buffer upload clamps or filters commands (e.g., if `GpuSceneDataBuffer` applies a capacity limit). Using `scene.commands.size` in `uploadTexIndexBuffer` can produce a tex-index buffer with a different element count than the scene data buffer, causing M5 (emit shader) to read out-of-bounds indices from `sceneTexIndices` for the faces beyond the clamped count. This is a latent index-out-of-bounds GPU bug that may silently produce wrong colors or crash the GPU.

The fix is to pass the already-computed `faceCount` into `uploadTexIndexBuffer`, or compute it from `sceneData.faceCount` directly.

**Severity:** MED | **Confidence:** High

---

### WT-CR-6 — MED: `textureSample` on fallback with `Linear` filter produces wrong output near UV boundaries

**Location:** `IsometricFragmentShader.kt:22` / `GpuTextureBinder.kt:33-40`

**Evidence:**
```kotlin
// Shader: always samples
let sampled = textureSample(diffuseTexture, diffuseSampler, in.uv);
let textured = sampled * in.color;
return select(textured, in.color, in.textureIndex == 0xFFFFFFFFu);
```

```kotlin
// Sampler: linear filtering
magFilter = FilterMode.Linear,
minFilter = FilterMode.Linear,
```

The fallback texture is a 2×2 checkerboard (magenta/black). With `ClampToEdge` addressing and `Linear` magnification, the center of the texture samples pure magenta or pure black, but edges and corners blend between the two. Any fragment whose `in.uv` is outside [0,1] or near the [0,1] boundary will receive a blended magenta/black sample. Since `select(textured, in.color, in.textureIndex == 0xFFFFFFFFu)` discards this when `textureIndex == 0xFFFFFFFF`, it is not a visual issue for the NO_TEXTURE case.

However, for faces with `textureIndex == 0` (i.e., supposedly textured but the bound texture is the fallback because loading failed or no bitmap was provided), the sampled checkerboard color will be multiplied with `in.color`. On a face whose UVs are computed as (0,0)–(1,0)–(1,1)–(0,1), the magnified 2×2 checkerboard will render as a blurry half-magenta, half-black gradient rather than a crisp checkerboard. This will be visually confusing during development as a "loading failed" indicator.

This is expected behavior given the design intent, but it means the fallback is not a reliable error indicator — it can look like a subtle color tint rather than an obvious missing-texture pattern. Consider using `Nearest` filter for the fallback, or accepting the visual ambiguity as a known limitation.

**Severity:** MED | **Confidence:** Med

---

### WT-CR-7 — LOW: `identityHashCode` is not a unique key for bitmap cache

**Location:** `GpuFullPipeline.kt:346`

**Evidence:**
```kotlin
val identity = System.identityHashCode(bitmap)
if (identity == lastUploadedBitmapIdentity) return
```

`System.identityHashCode` is the default `Object.hashCode()`, which is not guaranteed unique — two different live `Bitmap` objects can have the same identity hash code. When this happens, a new bitmap with the same hash as the cached one will be treated as the cached one: the GPU will continue displaying the old texture even though the bitmap reference changed.

The chance of collision for two simultaneously live objects is approximately 1/(2^32), so this is unlikely in practice with small bitmaps. However, the fix is trivial: store the bitmap reference directly as the cache key rather than its identity hash.

```kotlin
private var lastUploadedBitmap: Bitmap? = null
// ...
if (bitmap === lastUploadedBitmap) return
```

**Severity:** LOW | **Confidence:** High

---

### WT-CR-8 — LOW: Potential double-close of old bind group when transitioning via lazy getter

**Location:** `GpuFullPipeline.kt:419-422`

**Evidence:**
```kotlin
fun clearScene() {
    // ...
    _textureBindGroup?.close()
    _textureBindGroup = textureBinder.buildBindGroup(textureStore.fallbackTextureView)
}
```

If `clearScene()` is called when `_textureBindGroup` is `null` (e.g., before any `upload()`) this is safe. The `textureBindGroup` property getter is a computed getter — accessing it would trigger the lazy build, but `clearScene()` directly accesses `_textureBindGroup` (the backing field), so the lazy getter is not involved here. This is actually fine.

The concern is if external code accesses `textureBindGroup` (the property) before `upload()`, triggering the lazy build which calls `buildBindGroup` and sets `_textureBindGroup`. Then `clearScene()` is called: it closes `_textureBindGroup` and builds a new one. If the bind group from the lazy getter was already set on a render pass that is still in flight, closing it while Dawn holds a reference could trigger a use-after-free in native code.

Dawn's WebGPU spec says bind groups should not be freed while a command buffer referencing them is in flight. The current code structure means `drawFrame` accesses `gp!!.textureBindGroup` inside a `ctx.withGpu` block, and `clearScene` / `upload` are called from `uploadScene` which also runs inside `ctx.withGpu`. Since all GPU thread work is serialized by `withGpu`, there is no real concurrency risk. However the lifetime of the bind group relative to command buffer submission is still implicit.

**Severity:** LOW | **Confidence:** Med

---

### WT-CR-9 — NIT: Checkerboard bitmap in sample activity is never recycled

**Location:** `WebGpuSampleActivity.kt:419-430`

**Evidence:**
```kotlin
val checkerboard = remember {
    val size = 16
    // ...
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
```

`remember` keeps the bitmap alive for the lifetime of the composable. When the `WebGpuTexturedSample` composable leaves the composition (tab switch, Activity finish), the `Bitmap` is released to GC but not explicitly recycled. For a 16×16 bitmap (1 KB) this is trivially small, but `Bitmap.recycle()` should be called on `DisposableEffect` cleanup in production code to release the native pixel buffer immediately.

**Severity:** NIT | **Confidence:** Med

---

## Summary

- **Total findings:** 9
- **Blockers:** 0
- **High:** 3 (WT-CR-1, WT-CR-2, WT-CR-3)
- **Medium:** 3 (WT-CR-4, WT-CR-5, WT-CR-6)
- **Low:** 2 (WT-CR-7, WT-CR-8)
- **Nit:** 1 (WT-CR-9)

The most urgent issues before shipping are:

**WT-CR-1** (`pipeline!!` NPE): replace with `checkNotNull` with a diagnostic message so a failed `ensurePipeline` produces a clear error instead of a bare NPE at draw time.

**WT-CR-3** (double-destroy in `GpuTextureStore.close`): the fallback texture view is closed after the fallback texture is destroyed. This is likely safe with Dawn's reference counting but is fragile; the view should be closed first.

**WT-CR-5** (tex-index buffer size mismatch): using `scene.commands.size` instead of `sceneData.faceCount` as the tex-index element count can produce GPU index-out-of-bounds reads in M5. The fix is a one-liner: pass the already-computed face count.

**WT-CR-2** (lazy bind group build before layout is set) and **WT-CR-4** (leaked GPU texture on unsupported source type) are medium-urgency but should be resolved before the slice is promoted to a release candidate.
