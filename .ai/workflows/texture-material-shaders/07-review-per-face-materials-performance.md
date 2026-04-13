---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: per-face-materials
review-command: performance
status: complete
updated-at: "2026-04-13T00:30:00Z"
metric-findings-total: 8
metric-findings-blocker: 0
metric-findings-high: 1
metric-findings-med: 3
metric-findings-low: 4
result: issues-found
tags: [performance, per-face, material, gpu]
refs:
  review-master: 07-review-per-face-materials.md
---

# Performance Review — per-face-materials slice

## Verdict

**Issues found — no blockers.**

One HIGH finding: `collectTextureSources` + `Set<TextureSource>` construction runs
every frame unconditionally, even for static solid-color scenes. Three MED findings
cover the redundant `maxOf` in buffer growth, the atlas CPU compositing cost, and the
UV region data that duplicates fields now in FaceData. Four LOW findings cover the
FaceData size increase bandwidth, the per-frame `toSet` equality check cost, the lack
of atlas invalidation on bitmap mutation, and the UV region buffer write being issued
every upload call.

---

## Findings table

| ID     | Severity | Area                                               | Fix effort |
|--------|-----------|----------------------------------------------------|------------|
| PERF-1 | HIGH      | `collectTextureSources` runs every frame unconditionally | Low (dirty flag on PreparedScene) |
| PERF-2 | MED       | `maxOf(faceCount, faceCount * 2)` — redundant first arg | Trivial (remove first arg)      |
| PERF-3 | MED       | Atlas CPU composite: `Bitmap.createBitmap` allocates on rebuild | Medium (pool/reuse atlas bitmap) |
| PERF-4 | MED       | UV data duplicated: FaceData (160 B) + UV region buffer (16 B/face) | Future (consolidate)      |
| PERF-5 | LOW       | FaceData 144→160 B: 11% bandwidth increase on every upload | Accepted tradeoff          |
| PERF-6 | LOW       | `lastAtlasSignature` equality check allocates a new `Set` every frame | Low (reuse set reference)  |
| PERF-7 | LOW       | Atlas bitmap mutability: no guard against caller recycling source bitmaps mid-frame | Doc/assert |
| PERF-8 | LOW       | UV region buffer written every `upload()` call, not gated on change | Low (dirty flag)           |

---

## Detailed findings

### PERF-1 — HIGH — `collectTextureSources` runs every frame unconditionally

**File:** `GpuFullPipeline.kt`, `uploadTextures()`

```kotlin
val textureSources = mutableSetOf<TextureSource>()
for (cmd in scene.commands) {
    collectTextureSources(cmd.material, cmd.faceType, textureSources)
}
// …
if (textureSources == lastAtlasSignature) return
```

Every call to `upload()` — which is issued every frame when the scene changes — walks
the entire command list to build a fresh `MutableSet<TextureSource>`. For a scene of
200 prisms × 6 faces = 1 200 commands, this is 1 200 `when` dispatches plus
`mutableSetOf` allocation plus `Set.add` calls, all on the GPU thread before
`queue.submit`.

The problem is that the set is constructed **before** the cache check; the check comes
after. A scene with 1 200 solid-color faces does no atlas work, but still pays the
full scan + set allocation every frame.

The `PerFace` arm iterates `m.faceMap.values` for each command, adding up to 5 more
`when` dispatches plus `Set.add` calls per face. For a scene where every prism uses
`PerFace`, the inner loop doubles the per-command cost.

**Fix:** Add a `hasTexturedMaterial: Boolean` field to `PreparedScene` (computed once
during scene preparation). Gate the entire `collectTextureSources` loop behind that
flag. For solid-color-only scenes the gate short-circuits immediately at `O(1)`.

For textured scenes, additionally cache the `Set<TextureSource>` object between frames
on `PreparedScene` as `textureSourceSet: Set<TextureSource>?` so it is not rebuilt when
the same scene object is re-uploaded on multiple frames (e.g., during animated camera
pans with a static scene).

---

### PERF-2 — MED — `maxOf(faceCount, faceCount * 2)` — redundant first argument

**File:** `GpuFullPipeline.kt`, `uploadTexIndexBuffer()` and `uploadUvRegionBuffer()`

```kotlin
// uploadTexIndexBuffer
val newCapacity = maxOf(faceCount, faceCount * 2)

// uploadTexIndexBuffer CPU staging
val newSize = maxOf(requiredBytes, requiredBytes * 2)

// uploadUvRegionBuffer GPU
val newCapacity = maxOf(faceCount, faceCount * 2)

// uploadUvRegionBuffer CPU staging
val newSize = maxOf(requiredBytes, requiredBytes * 2)
```

`faceCount * 2 >= faceCount` for all non-negative `faceCount`, so `maxOf(x, x * 2)`
always equals `x * 2`. The first argument of `maxOf` is dead code in every case.

This is a correctness-adjacent issue as well as a clarity issue: a future reader may
conclude that the first argument guards an edge case (it does not). In the `faceCount
== 0` case both functions return early, so `maxOf(0, 0)` is never reached.

**Fix:** Replace all four occurrences with plain `faceCount * 2` (GPU capacity) and
`requiredBytes * 2` (CPU staging). Also consider adding a minimum initial capacity
(e.g., `maxOf(faceCount * 2, 64)`) to avoid tiny allocations on small scenes.

---

### PERF-3 — MED — Atlas CPU composite: `Bitmap.createBitmap` allocates a new heap object on every rebuild

**File:** `TextureAtlasManager.kt`, `rebuild()`

```kotlin
val atlasBitmap = Bitmap.createBitmap(atlasWidth, atlasHeight, Bitmap.Config.ARGB_8888)
val canvas = android.graphics.Canvas(atlasBitmap)
// … canvas.drawBitmap for each entry …
val gpuTex = textureStore.uploadBitmap(atlasBitmap)
atlasTexture = gpuTex
atlasBitmap.recycle()
```

When the texture set changes, `rebuild()` creates a new ARGB_8888 `Bitmap` of up to
2048×2048 pixels = 16 MB of native memory, composites all source bitmaps into it via
Android `Canvas`, uploads it to the GPU (another copy into GPU memory), then immediately
recycles the CPU-side bitmap. This is a 16 MB peak allocation spike followed by an
immediate free — expensive for the Android heap manager and for GC pressure if it
triggers during a frame.

For the common case of a fixed texture set across many frames (e.g., a static tilemap),
this is only paid once at scene setup, which is acceptable. However, if any texture
changes (e.g., animated texture, texture streaming), the full 16 MB allocation + Canvas
composite + GPU upload occurs every change.

**Mitigations (in order of effort):**

1. **(Low effort)** Cache the last atlas `Bitmap` and recycle only after a new one is
   successfully uploaded. This avoids a brief window where both old and new bitmaps are
   live, and lets the `releaseAtlas()` path skip re-allocation when re-building with the
   same pixel dimensions.

2. **(Medium effort)** Add a `cachedAtlasBitmap: Bitmap?` field to
   `TextureAtlasManager`. Reuse it if `atlasWidth` and `atlasHeight` are unchanged
   (just `eraseColor(0)` to clear it before redrawing). This avoids the
   `createBitmap` + recycle churn entirely for texture-set changes that don't resize
   the atlas.

3. **(High effort, deferred)** Incremental atlas updates (dirty-rectangle tracking per
   source). Out of scope for this slice.

Option 2 is the recommended action here. The check `atlasWidth == cachedBitmap.width &&
atlasHeight == cachedBitmap.height` is O(1) and avoids the 16 MB spike on every rebuild
when atlas dimensions are stable.

---

### PERF-4 — MED — UV data duplicated between FaceData (160 B) and UV region buffer (16 B/face)

**Files:** `SceneDataPacker.kt`, `GpuFullPipeline.kt` `uploadUvRegionBuffer()`

`FaceData` now includes `uvOffsetU`, `uvOffsetV`, `uvScaleU`, `uvScaleV` at bytes
132–147 (16 bytes). In addition, `GpuFullPipeline` allocates and uploads a separate
`uvRegionBuffer` (binding 5) containing exactly the same 4 floats per face, indexed
by `originalIndex` in the emit shader.

The emit shader reads from `sceneUvRegions[key.originalIndex]` (binding 5), ignoring
the UV fields in `FaceData`. The UV fields in `FaceData` are written by
`SceneDataPacker.packInto` via `cmd.uvOffset` / `cmd.uvScale`, but those fields on
`RenderCommand` appear to be populated from the same atlas region data.

This means for a 1 200-face scene:
- `FaceData` carries 1 200 × 16 B = 19.2 KB of UV data that is never read by any shader
- `uvRegionBuffer` carries another 1 200 × 16 B = 19.2 KB of UV data that is read
- Total: ~38.4 KB of UV data uploaded per frame, where 19.2 KB is dead

**Recommendation:** Either:
- Remove the `uvOffsetU/V/Scale` fields from `FaceData` and read the UV region from
  the `TransformCullLightShader` (M3) if M3 ever needs per-face UV for lighting, or
- Remove the separate `uvRegionBuffer` and have the emit shader read UV fields from
  `TransformedFace` (which already carries `faceIndex` for the lookup)

The second option is cleaner architecturally: `TransformedFace` could carry the UV
region copied from `FaceData` during M3 (at zero extra memory since it is the same
data re-read from the scene buffer). This eliminates binding 5 entirely.

This is deferred work — it requires a M3 shader change and struct layout changes — but
it should be tracked as a known bandwidth inefficiency.

---

### PERF-5 — LOW — FaceData 144→160 B: 11% bandwidth increase on every upload

**File:** `SceneDataPacker.kt`, `SceneDataLayout`

The FaceData struct grew from 144 to 160 bytes per face (+16 bytes, +11%). At
1 200 faces (200 prisms × 6 faces), the scene upload cost increases from:
- 1 200 × 144 B = 172.8 KB → 1 200 × 160 B = 192 KB (+19.2 KB per upload)

The M3 shader (transform + cull + light) also reads 11% more data per face, increasing
its memory bandwidth pressure slightly.

Of the 16 new bytes per face:
- 8 bytes (`uvOffsetU`, `uvOffsetV`, `uvScaleU`, `uvScaleV` as 4 × f32) carry per-face
  UV data
- 8 bytes (`_padding`) exist solely to reach 16-byte struct alignment

As noted in PERF-4, the UV data is redundant with the UV region buffer. The padding
bytes are unavoidable given the WGSL 16-byte alignment rule for storage buffer structs.

**Assessment:** The bandwidth increase is 19.2 KB per upload — well within the PCIe /
AHB bus capacity of a modern Android GPU. For 60 fps with scene updates every frame,
this is 19.2 KB × 60 = ~1.15 MB/s of extra upload bandwidth, which is negligible.
However, the 8 bytes of UV data that M3 reads but does not use represents unnecessary
L1 cache pressure in M3. This is an accepted tradeoff for the current slice scope, but
should be revisited when PERF-4 is addressed (removing the duplicate UV data would
reduce FaceData back toward 144 bytes and recover the alignment padding).

---

### PERF-6 — LOW — `lastAtlasSignature` equality check allocates a new `Set` every frame

**File:** `GpuFullPipeline.kt`, `uploadTextures()`

```kotlin
if (textureSources == lastAtlasSignature) return
```

`textureSources` is a `MutableSet<TextureSource>` built every frame in the loop. The
`==` comparison uses `AbstractSet.equals()`, which is O(min(|A|, |B|)) in the general
case. The comparison itself is fine, but the `textureSources` object is discarded
immediately on cache hit — it was allocated just to be compared and thrown away.

The fix for PERF-1 (caching a `Set` reference on `PreparedScene`) would eliminate this
allocation on cache hits. If PERF-1 is not fixed, an alternative is to compare against
a hash (e.g., `textureSources.hashCode() == lastSignatureHash`) as a fast O(1) pre-check
before the full equality scan, avoiding the scan for the overwhelmingly common
"same textures, same hash" case.

---

### PERF-7 — LOW — No guard against caller recycling source bitmaps between `upload()` and atlas build

**File:** `TextureAtlasManager.kt`, `rebuild()`

```kotlin
for ((source, bitmap) in entries) {
    // bitmap.isRecycled is not checked here
    canvas.drawBitmap(bitmap, placement.x.toFloat(), placement.y.toFloat(), null)
}
```

`GpuFullPipeline.uploadTextures()` calls `source.ensureNotRecycled()` before adding
bitmaps to the `entries` map, which is correct. However, `TextureAtlasManager.rebuild()`
does not validate `bitmap.isRecycled` before calling `canvas.drawBitmap`. If a caller
recycles a `BitmapSource.bitmap` between `upload()` and the `rebuild()` call (both
happen synchronously on the GPU thread in the current design, so this cannot happen
today), `Canvas.drawBitmap` on a recycled bitmap throws `IllegalStateException`.

The risk is low because the entire upload sequence is synchronous on the GPU thread and
there is no async handoff between `ensureNotRecycled` and `canvas.drawBitmap`. Still,
a defensive `check(!bitmap.isRecycled)` at the top of the `for` loop in `rebuild()`
would make the failure mode deterministic and produce a clear error message rather than
a crash from inside Android Canvas.

**Recommendation:** Add an assertion or log-warning at the start of the loop in
`rebuild()`:
```kotlin
check(!bitmap.isRecycled) {
    "TextureAtlasManager.rebuild: bitmap for $source was recycled before atlas build"
}
```

---

### PERF-8 — LOW — UV region buffer written every `upload()` call, not gated on change

**File:** `GpuFullPipeline.kt`, `uploadUvRegionBuffer()`

The `uploadUvRegionBuffer()` function runs on every `upload()` call and unconditionally
writes `faceCount × 16` bytes to GPU memory via `queue.writeBuffer`. There is no early-
exit equivalent to the atlas cache check (`if (textureSources == lastAtlasSignature)
return`).

For a static scene with no texture changes (the common case after initial load), the UV
regions are identical frame over frame, yet the CPU-side loop runs:

```kotlin
for (cmd in scene.commands) {
    val region = resolveAtlasRegion(cmd)
    // … 4 × buf.putFloat …
}
ctx.queue.writeBuffer(uvRegionGpuBuffer!!, 0L, buf)
```

For 1 200 commands, this is 1 200 `resolveAtlasRegion` calls (each involving a `when`
dispatch and a `Map.get`) + 4 800 `putFloat` calls + one `writeBuffer` (1 200 × 16 B =
19.2 KB DMA transfer) per frame.

**Fix:** Track a dirty flag for the UV region buffer (`uvRegionDirty: Boolean`). Set it
to `true` whenever `atlasManager.rebuild()` is called (i.e., when `textureSources !=
lastAtlasSignature`). Clear it after `uploadUvRegionBuffer` writes the buffer. Skip
`uploadUvRegionBuffer` when the flag is false and `faceCount == lastFaceCount`.

This makes the steady-state cost of `uploadUvRegionBuffer` O(1) instead of O(faceCount)
per frame for static scenes.

---

## Summary and recommended actions

**Before release build:**

1. **PERF-1 (HIGH):** Add `hasTexturedMaterial: Boolean` to `PreparedScene` and gate
   the `collectTextureSources` scan behind it. Cache the `Set<TextureSource>` object on
   `PreparedScene` to avoid reconstruction on repeated uploads of the same scene.

2. **PERF-2 (MED):** Remove the redundant first argument in all four `maxOf(x, x * 2)`
   buffer growth expressions. Replace with `x * 2` (or `maxOf(x * 2, MINIMUM)` for a
   sensible floor).

3. **PERF-3 (MED):** Add `cachedAtlasBitmap: Bitmap?` to `TextureAtlasManager`. Reuse
   it when `atlasWidth` and `atlasHeight` are unchanged (call `eraseColor(0)` before
   re-drawing). Recycle the old bitmap only when dimensions change.

**Deferred (file as known gaps):**

4. **PERF-4 (MED):** Track as a known bandwidth inefficiency — UV data in FaceData is
   never read by any shader. Plan consolidation in the next layout-revision slice.

5. **PERF-8 (LOW):** Add UV region dirty flag to skip redundant `writeBuffer` calls on
   static scenes. Low effort, but safe to defer until profiling confirms the 19.2 KB/frame
   DMA cost is measurable.

6. **PERF-7 (LOW):** Add `check(!bitmap.isRecycled)` in `rebuild()` loop for defensive
   programming. One-liner addition.

PERF-5 (FaceData bandwidth) and PERF-6 (set allocation) are implicitly resolved by
PERF-4 and PERF-1 respectively and need no separate action.
