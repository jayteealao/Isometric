---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: per-face-materials
review-command: security
status: complete
updated-at: "2026-04-13T01:15:00Z"
metric-findings-total: 7
metric-findings-blocker: 0
metric-findings-high: 2
result: issues-found
tags: [security, per-face, material, gpu]
refs:
  review-master: 07-review-per-face-materials.md
---

# Security Review — per-face-materials slice

Commit reviewed: `fde7cc7`  
Branch: `feat/texture`

## Findings Summary

| ID     | Severity | Area                     | Title                                                        | Status |
|--------|----------|--------------------------|--------------------------------------------------------------|--------|
| SEC-01 | HIGH     | ByteBuffer safety        | UV region CPU buffer limit set before rewind, not after      | Open   |
| SEC-02 | HIGH     | GPU buffer overflow      | `uploadUvRegionBuffer` iterates `scene.commands` not capped at `faceCount` | Open |
| SEC-03 | MEDIUM   | Atlas size limit         | Fallback clamped-height layout silently overwrites textures in atlas | Open   |
| SEC-04 | MEDIUM   | Bitmap lifecycle         | `atlasBitmap` recycled before GPU upload completes in async context | Open   |
| SEC-05 | LOW      | Resource leak            | `atlasTextureView` not destroyed before `close()` (only `close()` called) | Open   |
| SEC-06 | LOW      | Input validation         | `uvOffset`/`uvScale` on `RenderCommand` are user-controllable `FloatArray` with no size validation | Open   |
| SEC-07 | NIT      | WGSL buffer access       | `sceneUvRegions[key.originalIndex]` unbounded in shader — relies on buffer sizing contract | Open   |

---

## Detailed Findings

### SEC-01 — HIGH: UV region CPU buffer limit set before `rewind`, leaving stale data window

**File:** `GpuFullPipeline.kt`, `uploadUvRegionBuffer`, lines 460–462

```kotlin
val buf = uvRegionCpuBuffer!!
buf.rewind()
buf.limit(requiredBytes)
```

The sequence is: `rewind()` (position → 0, limit → capacity), then `limit(requiredBytes)`. This is correct for writing. However, after the `for` loop the buffer position will equal `requiredBytes` only if every `scene.commands` entry was written exactly once. If `scene.commands.size != faceCount` (see SEC-02 below) the final `buf.rewind()` before `writeBuffer` sends the full `requiredBytes` window regardless of how many entries were actually written. No `buf.limit(buf.position())` fence is applied before `rewind()`.

**Worst case:** If `scene.commands.size > faceCount` due to an inconsistency between `sceneData.faceCount` and the list size, the buffer position after the loop overshoots `requiredBytes`, and the subsequent `buf.rewind()` hands `writeBuffer` a full `requiredBytes`-sized window that contains partially stale data from a previous frame in the trailing bytes.

**Recommendation:** Replace the loop-then-rewind pattern with:
```kotlin
buf.limit(buf.position())  // seal to exactly what was written
buf.rewind()
```
or assert `buf.position() == requiredBytes` before rewinding.

---

### SEC-02 — HIGH: `uploadUvRegionBuffer` iterates `scene.commands`, not capped to `faceCount`

**File:** `GpuFullPipeline.kt`, lines 434–481

```kotlin
private fun uploadUvRegionBuffer(scene: PreparedScene, faceCount: Int) {
    // GPU buffer sized for faceCount entries
    val newCapacity = maxOf(faceCount, faceCount * 2)
    ...
    val requiredBytes = faceCount * entryBytes
    ...
    // ← loop iterates ALL commands, not first faceCount
    for (cmd in scene.commands) {
        ...
        buf.putFloat(...)  // 4 floats per iteration
    }
}
```

`faceCount` comes from `sceneData.faceCount` (set by `GpuSceneDataBuffer.upload`), which counts how many commands it successfully packed. If `scene.commands.size > faceCount` (e.g. the scene data buffer had to skip commands due to overflow, or a race produces a stale `faceCount` before `sceneData.upload` returns), the `ByteBuffer` write loop will write beyond `requiredBytes`, causing a `BufferOverflowException` (JVM throw) or silent data corruption depending on `ByteBuffer` implementation.

Similarly, `uploadTexIndexBuffer` delegates to `SceneDataPacker.packTexIndicesInto(scene.commands, ...)`, which calls `buffer.limit(commands.size * 4)`. If `commands.size > faceCount`, the limit exceeds the allocated capacity and `writeBuffer` uploads more bytes than the GPU buffer was sized for.

**Recommendation:** Cap both loops to the first `faceCount` commands:
```kotlin
for (cmd in scene.commands.take(faceCount)) { ... }
```
and in `packTexIndicesInto`, enforce `commands.size == faceCount` or accept `faceCount` as a parameter.

---

### SEC-03 — MEDIUM: Atlas overflow fallback silently aliases all textures to position (0,0)

**File:** `TextureAtlasManager.kt`, lines 189–191

```kotlin
return tryPack(sizes, maxAtlasSizePx)
    ?: ShelfLayout(maxAtlasSizePx, maxAtlasSizePx, sizes.map { Placement(0, 0) })
```

When `tryPack` fails even at `maxAtlasSizePx` (i.e. textures collectively exceed 2048×2048), the fallback assigns **every** texture a placement of `(0, 0)`. This means all textures are composited on top of each other at the origin. The resulting atlas is garbage, and all `AtlasRegion` UV calculations reference overlapping, corrupted pixel data uploaded to the GPU.

There is no error log, no exception, and no `return false` to signal failure to the caller. `rebuild()` returns `true` (success), the atlas view is bound to the render pass, and rendering proceeds silently with corrupt textures.

**Recommendation:**
1. Log an error at minimum: `Log.e(TAG, "Atlas overflow: textures exceed ${maxAtlasSizePx}px — atlas will be corrupt")`
2. Return `false` from `rebuild()` on overflow so the caller falls back to the fallback texture bind group.
3. Optionally clamp individual bitmap dimensions before packing via `GpuTextureStore.MAX_TEXTURE_DIMENSION` (4096) vs atlas max (2048) — there is currently a gap where a 3000px texture passes `uploadBitmap` validation but will always cause atlas overflow.

---

### SEC-04 — MEDIUM: `atlasBitmap` recycled before GPU upload pipeline may have completed

**File:** `TextureAtlasManager.kt`, lines 134–140

```kotlin
val gpuTex = textureStore.uploadBitmap(atlasBitmap)
atlasTexture = gpuTex
atlasTextureView = gpuTex.createView()

// Recycle the temporary composite bitmap
atlasBitmap.recycle()
```

`textureStore.uploadBitmap` calls `queue.writeTexture(...)`, which schedules an async GPU transfer. The Kotlin call returns immediately. `atlasBitmap.recycle()` is called on the **next line**, while the transfer may still be in-flight on the GPU thread.

In practice, `copyPixelsToBuffer` in `uploadBitmap` copies pixel data into a new `ByteBuffer` **before** `writeTexture` is called (line 88: `bitmap.copyPixelsToBuffer(pixels)`). This means the GPU transfer reads from the `ByteBuffer`, not the `Bitmap`, so recycling the `Bitmap` immediately is safe in the current `GpuTextureStore` implementation.

**However**, this is a latent risk: if `uploadBitmap` is ever refactored to use zero-copy (e.g. `HardwareBuffer`-backed upload), the recycle will become a use-after-free. The code comment says "Recycle the temporary composite bitmap" with no mention of why it is safe to do so immediately.

**Recommendation:** Add a comment explaining the safety contract:
```kotlin
// Safe to recycle immediately: uploadBitmap copies pixels into a ByteBuffer
// before scheduling the GPU transfer, so the Bitmap data is no longer needed.
atlasBitmap.recycle()
```

---

### SEC-05 — LOW: `releaseAtlas` calls `atlasTextureView?.close()` but not `destroy()` on the texture

**File:** `TextureAtlasManager.kt`, lines 145–154

```kotlin
private fun releaseAtlas() {
    atlasTextureView?.close()
    atlasTextureView = null
    if (atlasTexture != null) {
        textureStore.releaseTexture(atlasTexture!!)
        atlasTexture = null
    }
    ...
}
```

`GpuTextureStore.releaseTexture` calls both `texture.destroy()` and `texture.close()`. The view is only `close()`d but never `destroy()`d. While `GPUTextureView` is a view (not the backing allocation), failing to call `destroy()` on the view may leak Dawn-internal JNI state depending on how the binding is implemented. Consistent with the pattern in `GpuTextureStore.close()`, views should be closed (not destroyed — views don't own GPU memory), so this is a low-priority style concern rather than a genuine leak. The pattern is internally inconsistent, which increases future maintenance risk.

**Recommendation:** Verify the Dawn binding contract for `GPUTextureView.close()` vs `destroy()`. Document why `close()` is sufficient (or add `destroy()` if required).

---

### SEC-06 — LOW: `RenderCommand.uvOffset` / `uvScale` are unvalidated `FloatArray?` fields

**File:** `RenderCommand.kt`, lines 48–49

```kotlin
val uvOffset: FloatArray? = null,
val uvScale: FloatArray? = null,
```

These fields are written by `SceneDataPacker.packInto` without size validation:

```kotlin
buffer.putFloat(cmd.uvOffset?.get(0) ?: 0f)
buffer.putFloat(cmd.uvOffset?.get(1) ?: 0f)
buffer.putFloat(cmd.uvScale?.get(0) ?: 1f)
buffer.putFloat(cmd.uvScale?.get(1) ?: 1f)
```

If a caller constructs a `RenderCommand` with `uvOffset = floatArrayOf(0f)` (size 1), `uvOffset?.get(1)` throws `ArrayIndexOutOfBoundsException`. This would crash the GPU upload thread, leaking any resources already allocated during the current `upload()` call.

Note: The `uvOffset`/`uvScale` fields are documented as set by the WebGPU pipeline during per-face material resolution (not by external callers), but since `RenderCommand` is a public `class` (not `data class`), any caller can construct one with arbitrary array sizes.

**Recommendation:** Either:
- Add `init { require(uvOffset == null || uvOffset.size == 2) { ... } }` to `RenderCommand`, or
- Use `?.getOrNull(0)` / `?.getOrNull(1)` in `SceneDataPacker` for safe access.

---

### SEC-07 — NIT: WGSL `sceneUvRegions[key.originalIndex]` has no bounds check

**File:** `TriangulateEmitShader.kt` (WGSL), line 232

```wgsl
let uvRegion = sceneUvRegions[key.originalIndex];
```

`key.originalIndex` is set by the M4 sort pass from the original face index, which ranges `[0, faceCount)`. The `sceneUvRegions` buffer is sized for `uvRegionCapacity >= faceCount` entries. WebGPU's default runtime bounds checking (required by spec §3.4 "Out of bounds access") will clamp or return zero for out-of-bounds reads, so there is no memory safety issue.

The risk is a silent incorrect UV read if the sort pass or packer produces an `originalIndex >= faceCount` due to a bug. The shader gives no indication when this occurs — the rendered face simply shows wrong UV coordinates.

**Recommendation:** No code change required. Document the implicit bounds contract in `TriangulateEmitShader.kt` KDoc: `sceneUvRegions` and `sceneTexIndices` are sized to `faceCount`, which equals the maximum valid `originalIndex + 1`.

---

## Areas Confirmed Clean

| Area                              | Verdict  | Notes                                                               |
|-----------------------------------|----------|---------------------------------------------------------------------|
| Nested PerFace validation         | Clean    | `init` block in `IsometricMaterial.PerFace` rejects nested PerFace at construction time |
| Atlas bitmap compositing overflow | Clean    | `tryPack` returns null on height overflow; `computeShelfLayout` retries with wider atlas |
| `texIndexCapacity` growth formula | Clean    | `maxOf(faceCount, faceCount * 2)` is correct (doubles from actual need) |
| GPU buffer close ordering         | Clean    | `close()` releases in reverse-dependency order (emit before sort before transform) |
| Bitmap recycled-check at use      | Clean    | `BitmapSource.ensureNotRecycled()` called before bitmap is accessed in `uploadTextures` |
| `PerFace.default` PerFace guard   | Clean    | `require(default !is PerFace)` prevents infinite recursion in `resolve()` |
| Asset path traversal              | Clean    | `TextureSource.Asset` rejects `..` components and null bytes         |
| Resource ID zero guard            | Clean    | `TextureSource.Resource` rejects `resId == 0`                        |
| Atlas max dimension vs GPU max    | Partial  | Atlas max is 2048; `GpuTextureStore` allows 4096 — gap documented in SEC-03 |
