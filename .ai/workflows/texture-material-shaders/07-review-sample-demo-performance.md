# Performance Review — sample-demo slice

**Scope:** `970b91e..feat/texture`
**Date:** 2026-04-13
**Reviewer:** Claude (claude-sonnet-4-6)

---

## Summary

The sample-demo slice adds a fourth GPU buffer (`uvCoordsBuf`), three extra `sceneUvCoords`
storage reads per face in the emit shader, procedurally-generated texture bitmaps, and a new
hard-coded `IsoColor.WHITE` path for textured materials. The changes are functionally correct
for the demo scale, but carry several real performance costs and one latent allocation issue
that should be addressed before the API is declared stable.

---

## Finding 1 — UV coords buffer is uploaded every frame, even when the scene is static

**Severity: Medium**

**Location:** `GpuTextureManager.uploadUvCoordsBuffer` (called unconditionally from `uploadTextures`)

**Detail:**

`uploadTextures` is called each frame from `GpuFullPipeline`. It always calls
`uploadUvCoordsBuffer`, which always calls `ctx.queue.writeBuffer`. There is no dirty-check or
frame-equality guard on the UV coords buffer.

The existing `uvRegionBuf` and `texIndexBuf` paths have the same structure, so this is a
pre-existing pattern, but the UV coords buffer is 3× wider (48 bytes/face vs 16 and 4 bytes).
For a 4×4 demo grid that is 16 prisms × 3 faces each = 48 faces × 48 bytes = **2,304 bytes
transferred to the GPU every frame** for UV coords alone, even on static frames.

The atlas-rebuild path already has a `textureSources == lastAtlasSignature` cache. The same
pattern should be applied to the three per-face buffers: track a dirty flag or compare a
scene generation counter; skip `writeBuffer` when nothing changed.

**Recommendation:**

Add a `lastSceneVersion: Long` field to `GpuTextureManager`. Expose a monotonic version number
from `PreparedScene` (or hash the command list). Skip all three `writeBuffer` calls when the
version has not changed. Given the WebGPU constraint that `writeBuffer` is always valid, the
skip should be conditioned on the buffer not having been reallocated either.

---

## Finding 2 — UV coords buffer is redundant for the common quad case

**Severity: Low-Medium**

**Location:** `GpuTextureManager.uploadUvCoordsBuffer` / `TriangulateEmitShader`

**Detail:**

For every face where `uvCoords == null` (non-Prism geometry or flat-color materials), the code
writes the hard-coded identity quad `(0,0)(1,0)(1,1)(0,1)(0,0)(0,0)` into the buffer and then
the shader reads those values, applies `uvSc + uvOff`, and emits them. For the uv-generation
slice that already packs correct coordinates, those coordinates are then re-transformed through
the atlas `uvSc + uvOff` step at emit time.

The pre-existing `uvRegionBuf` already encodes the atlas offset/scale. The new `uvCoordsBuffer`
encodes per-vertex canonical face UVs **before** that transform. For the standard quad, those
are always `(0,0)(1,0)(1,1)(0,1)` — identical every frame. No GPU bandwidth is saved by
uploading them; the old hard-coded approach in the shader was correct for that case.

Consider adding a flag bit into the existing `sceneTexIndices` buffer (high bit of the u32) to
signal "use canonical quad UVs", and only fall back to the `sceneUvCoords` read when the flag
is set. This eliminates the `writeBuffer` call and the storage read for non-textured faces.

---

## Finding 3 — Three extra storage reads per face in the emit shader

**Severity: Low**

**Location:** `TriangulateEmitShader` WGSL, emit function

**Detail:**

The new code adds:

```wgsl
let uvBase = key.originalIndex * 3u;
let uvPack0 = sceneUvCoords[uvBase + 0u];
let uvPack1 = sceneUvCoords[uvBase + 1u];
let uvPack2 = sceneUvCoords[uvBase + 2u];
```

Each invocation now does three extra `array<vec4<f32>>` loads from a storage buffer in
addition to the existing reads of `sceneTexIndices` and `sceneUvRegions`. On mobile GPUs,
non-sequential storage buffer reads (the index is `originalIndex`, which is sorted-order, not
insertion-order) can cause cache misses.

For the 4×4 demo scene (48 faces, one dispatch of 48 invocations) the absolute cost is
negligible — three storage reads across 48 threads is trivially hidden by the other work.
At larger scene scales (e.g. a 32×32 map = 3,072 faces) the scattered reads via
`originalIndex` can diverge across a warp and serialise.

**Recommendation:**

At demo scale: no action required. At production scale, consider re-ordering the uvCoords
buffer to be in depth-sorted order (same as the key buffer) so reads are sequential, or merge
the six UV floats into the existing vertex data earlier in the pipeline to avoid the extra
storage binding entirely.

---

## Finding 4 — IsoColor.WHITE is a static field, not a new allocation per recomposition

**Severity: None (non-issue)**

**Location:** `IsometricMaterialComposables.kt`, lines 60–61 and 147–148

**Detail:**

```kotlin
is IsometricMaterial.Textured -> IsoColor.WHITE
is IsometricMaterial.PerFace -> IsoColor.WHITE
```

`IsoColor.WHITE` is declared as `@JvmField val WHITE = IsoColor(255, 255, 255)` in the
`IsoColor.Companion` object. It is a singleton; reading it returns the same heap object on
every call. No allocation occurs per recomposition.

The `color` local variable is a val computed in the composable body; Compose's Kotlin compiler
plugin will inline it — it does not survive recomposition as an extra object. No issue here.

**One genuine concern adjacent to this:** the exhaustive `when` block now covers all three
sealed subclasses without an `else` branch. This is correct but means any future new subclass
of `IsometricMaterial` will produce a compile error until the `when` is updated — which is the
desired behaviour per the API design guidelines (section 6, invalid states). Keep as-is.

---

## Finding 5 — TextureAssets lazy init risks a main-thread stall

**Severity: Medium**

**Location:** `app/.../sample/TextureAssets.kt`

**Detail:**

```kotlin
val grassTop: Bitmap by lazy { buildGrassTop() }
val dirtSide: Bitmap by lazy { buildDirtSide() }
```

These lazy properties are first accessed inside a `remember { }` block in `TexturedDemoScreen`:

```kotlin
val tileMaterial = remember {
    perFace {
        top = texturedBitmap(TextureAssets.grassTop)
        sides = texturedBitmap(TextureAssets.dirtSide)
        ...
    }
}
```

`remember {}` executes on the composition thread, which is the main thread. `buildGrassTop`
and `buildDirtSide` each:

1. Allocate a 64×64 `Bitmap.Config.ARGB_8888` (16,384 bytes each via native heap).
2. Construct a `Canvas` wrapping that bitmap.
3. Call `drawColor`, `drawRect`, and 80/60 `drawCircle` calls using `android.graphics`.

The total work is small (sub-millisecond on modern hardware), but it is CPU work on the main
thread during the first composition pass. On slow devices or during a cold start with many
concurrent initializations this can contribute to a missed frame.

`kotlin.lazy` uses `LazyThreadSafetyMode.SYNCHRONIZED` by default, which adds a
double-checked lock — harmless but unnecessary for a sample-only object.

**Recommendation for production-quality code:**

Pre-warm the lazy properties on a background coroutine before navigation, or decode them into
a `ViewModel` with `viewModelScope`. For the demo this is acceptable. For a public API
`TextureSource.Bitmap` the callers should supply pre-decoded bitmaps.

**No main-thread ANR risk** exists here for the demo; the 64×64 pure-CPU drawing path is
fast enough that it will not trigger the 5-second ANR watchdog. The risk is limited to a
1–5 ms jank spike on first composition.

---

## Finding 6 — GrowableGpuStagingBuffer 2× over-allocation is correct but not size-capped

**Severity: Low**

**Location:** `GrowableGpuStagingBuffer.ensureCapacity`

**Detail:**

When `entryCount > capacity`, the buffer is reallocated at `entryCount * 2`. For
`uvCoordsBuf` at 48 bytes/entry, a scene growing from 0 to 48 faces allocates a 96-entry
GPU buffer = 4,608 bytes. This is fine. However, there is no upper cap: a scene briefly
spiking to 10,000 faces would allocate a 20,000-entry buffer (960 KB) that is never
reclaimed until `close()`. The same concern exists for the two pre-existing staging buffers,
but the 48-byte entry size makes it 3× more impactful for uvCoordsBuf.

**Recommendation:** Add a shrink policy (e.g. shrink to `entryCount * 2` after N frames of
consistently lower usage) or document the known limitation. Not urgent for the demo.

---

## Finding 7 — clear-color change is a functional regression risk

**Severity: Low**

**Location:** `WebGpuSceneRenderer.kt`

**Detail:**

The diff changes the render pass clear color from `(0,0,0,0)` (transparent black) to
`(1,1,1,1)` (opaque white). This was done to make textures visible against the background
for the demo. However, this value is now hardcoded at the renderer level, meaning any other
usage of the renderer that previously relied on transparent clear (e.g. compositing over a
Compose background) will now render with a white background instead.

This is not a performance issue but a correctness concern adjacent to the demo slice. The
clear color should be a configurable `SceneConfig` parameter rather than a constant.

---

## Quantified Overhead at Demo Scale (4×4 grid, 48 faces/frame)

| Item | Per-frame cost | Notes |
|---|---|---|
| uvCoordsBuf CPU pack | ~2–4 µs | 48 × 48 bytes, sequential ByteBuffer.putFloat |
| uvCoordsBuf writeBuffer | ~5–15 µs | 2,304 bytes DMA to GPU | 
| 3 extra storage reads/face (48 faces) | ~1–3 µs GPU | Hidden by shader occupancy at this scale |
| TextureAssets lazy init | ~0.5–2 ms | One-time, first composition only |
| IsoColor.WHITE access | 0 | Static field read |

At demo scale (48 faces) none of these individually threatens 60 fps. The compounding concern
is that **all three buffer uploads (texIndex, uvRegion, uvCoords) happen every frame
unconditionally**. When the scene becomes non-trivially large the per-frame CPU + DMA cost
will accumulate.

---

## Action Items (priority order)

| # | Finding | Recommended Fix | Priority |
|---|---|---|---|
| 1 | UV coords uploaded every static frame | Add scene-version dirty check, skip writeBuffer | Medium |
| 2 | uvCoordsBuffer redundant for non-UV faces | Flag bit in texIndices to skip storage read | Low-Medium |
| 3 | Clear color hardcoded at white | Expose as SceneConfig field | Low |
| 4 | GrowableGpuStagingBuffer no shrink policy | Document or add shrink policy | Low |
| 5 | TextureAssets main-thread init | Pre-warm on bg thread for production use | Low (demo only) |
| 6 | Shader scattered reads at scale | Re-order uvCoords buffer to depth-sorted order | Defer to scale |
