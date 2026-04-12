---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: webgpu-textures
review-command: performance
status: complete
updated-at: "2026-04-12T00:00:00Z"
metric-findings-total: 7
metric-findings-blocker: 0
metric-findings-high: 2
metric-findings-med: 3
metric-findings-low: 2
result: ship-with-caveats
tags: [webgpu, texture, shader, performance, wgsl, gpu]
refs:
  index: 00-index.md
  slice-def: 03-slice-webgpu-textures.md
  plan: 04-plan-webgpu-textures.md
  implement: 05-implement-webgpu-textures.md
  verify: 06-verify-webgpu-textures.md
---

# Performance Review — webgpu-textures slice

## Verdict

**Ship with caveats.**

No blockers. Two HIGH findings warrant fixing before this slice lands in a release
build: the unconditional `textureSample` in the fragment shader, and the double
clear-then-overwrite pattern in the emit shader's visible-face branch. Three MED
findings affect production quality but have safe workarounds or are constrained by
existing architecture. Two LOW findings are optimisation notes.

---

## Findings

### PERF-1 — HIGH — Unconditional `textureSample` pollutes texture cache on non-textured scenes

**File:** `IsometricFragmentShader.kt`

```wgsl
let sampled = textureSample(diffuseTexture, diffuseSampler, in.uv);
let textured = sampled * in.color;
return select(textured, in.color, in.textureIndex == 0xFFFFFFFFu);
```

The comment says "zero visual cost since the sampler fetch is the same for all
fragments in the draw call." That is correct for **uniformity** (required by Dawn /
Vulkan GLSL) but is not correct for **cost** in a non-textured scene:

- Every fragment — even those whose `textureIndex == 0xFFFFFFFF` — issues a real
  bilinear texture fetch. On a scene of 1 000 faces × ~200 px² average = 200 000
  fragments, all 200 000 fetches hit the 2×2 checkerboard fallback. This is trivially
  cheap for the 2×2 texture itself (fits in L1), but it still:
  1. Occupies texture-fetch slots in the fragment pipeline that could service other work.
  2. Forces the fragment shader to run the full `textureSample → multiply → select`
     sequence rather than an early-out `return in.color`.
  3. If the fallback is ever replaced with a real 1×1 white pixel texture the situation
     stays the same, but if someone binds a larger texture as fallback, L1 eviction
     pressure increases.

The Dawn uniformity rule blocks a straightforward `if (texIdx == 0xFFFFFFFF)` branch
around `textureSample`. Accepted workarounds:

**Option A (minimal):** Keep unconditional sample but bind a dedicated 1×1 opaque
white texture (not a visually surprising checkerboard) as the fallback. The GPU
then always samples the same single texel — stays in a single cache line forever.
This removes the incorrect comment about "zero visual cost" and documents the
tradeoff honestly.

**Option B (preferred for larger scenes):** Split into two render pipelines — one
with the texture/sampler bindings for textured draws, one without for solid-color
draws — and issue two `drawIndirect` calls. The vertex buffer already carries
`textureIndex` per vertex, so a CPU-side partition by "any textured face in scene?"
selects the right pipeline. This eliminates the unconditional fetch entirely on
non-textured scenes (the majority of current usage).

Option B requires two pipelines and complicates `GpuFullPipeline.dispatch`, so it is
not a one-liner. Option A is a one-commit fix that removes a misleading comment and
swaps the checkerboard for a plain white 1×1 pixel; ship Option A now, defer Option B
to a future performance slice.

---

### PERF-2 — HIGH — Double-write per vertex slot in emit shader visible path

**File:** `TriangulateEmitShader.kt` (WGSL, visible face branch)

```wgsl
// Clear the slot first, then overwrite only the real triangles.
for (var j = 0u; j < 12u; j++) {
    writeVertex((base + j) * 9u, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0xFFFFFFFFu);
}

// … then writeVertex calls for actual triangle 0, 1, 2, 3 …
```

A visible, 4-vertex (quad) face calls `writeVertex` 12 times (clear) + 6 times
(triangles 0 and 1) = 18 writes totalling 18 × 9 × 4 = 648 bytes of storage-buffer
writes per face. The 6 remaining degenerate slots (triangles 2–3 slots) are written
twice: once in the blanket clear, once — no, they are NOT overwritten for a 4-vertex
face. So the clear is correct but redundant for the first triangle's slots.

The pattern exists to ensure degenerate slots are always well-defined, but it writes
every degenerate slot before the branch checks `vertexCount`. This doubles the write
traffic for the padding region.

**Better pattern:** write real vertices first into their specific slots, then write
only the _remaining_ degenerate vertices:

```wgsl
// write 3 vertices for triangle 0 at (base+0)*9, (base+1)*9, (base+2)*9
// if vertexCount >= 4: write 3 vertices for triangle 1
// if vertexCount >= 5: write 3 vertices for triangle 2
// if vertexCount >= 6: write 3 vertices for triangle 3
// then: fill from (base + triCount*3)*9 to (base+11)*9 with degenerates
```

For the common 4-vertex quad case this reduces storage writes from 18 to 6 real + 6
degenerate = 12 writes (648 → 432 bytes, −33%). For the 3-vertex triangle case it
reduces from 12 clear + 3 real = 15 to 3 real + 9 degenerate = 12 writes. The
sentinel/culled paths are unchanged.

At 290 concurrent threads and 12 u32 writes each the current approach writes 290 × 18
× 36 = ~188 KB per dispatch; the optimised approach reduces that to ~145 KB for the
quad-dominant case.

---

### PERF-3 — MED — Vertex stride 36 bytes is not a power-of-two: cache-line implications

**Files:** `RenderCommandTriangulator.kt`, `GpuRenderPipeline.kt`, `TriangulateEmitShader.kt`

The vertex stride increased from 32 bytes (was: pos + color + uv = 8 × u32) to 36
bytes (now: + textureIndex = 9 × u32). 32 bytes is half a 64-byte cache line;
36 bytes does not divide evenly into cache lines:

- 1 vertex = 36 B → straddles cache lines at vertex 1 (offsets 36–71 → lines 0–1)
- 2nd vertex starts at byte 36, ending at 71 — already crossing the 64 B line boundary
- The GPU's vertex fetch engine compensates with sub-cache-line fetches, but alignment
  efficiency drops vs. 32-byte stride

This was an intentional tradeoff to add `textureIndex` per vertex. The alternatives
are:
- Pad to 48 bytes (add 3 u32 padding): better alignment, worse bandwidth.
- Store `textureIndex` in a separate per-instance or per-face buffer indexed by
  `gl_VertexIndex / 12` in the vertex shader, preserving 32-byte stride.
- Use a texture array and encode the atlas layer as part of the UV (z component) in
  a vec3 UV, keeping stride at 40 B (slightly less bad than 48, better than 36).

None of these alternatives are one-liners. The current 36-byte stride is acceptable
for the slice scope. File as a known performance gap to revisit when texture-atlas
support is added (at which point the per-vertex `textureIndex` approach changes anyway).

---

### PERF-4 — MED — `uploadTextures` scans `scene.commands` linearly every upload call

**File:** `GpuFullPipeline.kt`, `uploadTextures()`

```kotlin
for (cmd in scene.commands) {
    when (val m = cmd.material) {
        is IsometricMaterial.Textured -> { texturedMaterial = m; break }
        is IsometricMaterial.PerFace -> { … }
        else -> {}
    }
}
```

For a scene with 500 solid-color commands followed by 1 textured command, this scans
499 entries before finding the texture. This scan happens on every `upload()` call
(i.e., every frame when the scene is animated). On the GPU thread, any CPU work that
blocks the next `queue.submit` adds frame latency.

The fix is straightforward: short-circuit as soon as a textured material is found
(the `break` already does this for `Textured`, but `PerFace` with a non-`Textured`
default falls through without breaking). The `PerFace` arm should also `break` once
any textured sub-material is found. Additionally, `PreparedScene` could cache a
`hasTexture: Boolean` flag computed during scene preparation so this scan is skipped
entirely for solid-color scenes.

The current guard `if (identity == lastUploadedBitmapIdentity) return` skips the
upload work, but not the scan. The scan itself should be skipped.

---

### PERF-5 — MED — `uploadTexIndexBuffer` allocates CPU staging buffer without capacity growth factor

**File:** `GpuFullPipeline.kt`, `uploadTexIndexBuffer()`

```kotlin
if (cpu == null || cpu.capacity() < requiredBytes) {
    texIndexCpuBuffer = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
}
```

The GPU-side buffer uses exact `faceCount` sizing (also no growth factor), but the
CPU buffer is also sized exactly. If the scene grows by 1 face on successive frames,
a new `allocateDirect` is called every frame — `allocateDirect` is significantly
more expensive than heap allocation because it pins native memory and bypasses the GC.

The GPU buffer has the same issue: `if (faceCount > texIndexCapacity)` reallocates
exactly to `faceCount` with no headroom. The pattern used elsewhere (e.g.,
`RenderCommandTriangulator.ensureBuffer` with `× 2` growth) should be applied here:

```kotlin
val newCapacity = maxOf(requiredBytes, requiredBytes * 2)
texIndexCpuBuffer = ByteBuffer.allocateDirect(newCapacity).order(ByteOrder.nativeOrder())
```

Similarly for the GPU buffer:
```kotlin
val newCapacity = maxOf(faceCount, faceCount * 2).toLong() * 4
```

---

### PERF-6 — LOW — `textureSample` uses bilinear (Linear) filter for pixel-art / isometric tile textures

**File:** `GpuTextureBinder.kt`

```kotlin
magFilter = FilterMode.Linear,
minFilter = FilterMode.Linear,
mipmapFilter = MipmapFilterMode.Nearest,
```

Isometric tile textures are often authored at pixel-art resolution (64×64, 128×128)
and should be magnified without blur. `FilterMode.Linear` produces blurred edges
when a texture is magnified to cover large face areas. `FilterMode.Nearest` would
give crisper results for typical isometric tile art.

There is no API to configure the sampler from user-facing material APIs in this slice.
File as a future `TextureSampler` option on `IsometricMaterial.Textured`. Low severity
because it is a quality-of-life concern and the correct default depends on content type.

---

### PERF-7 — LOW — No mipmap generation; large texture magnification wastes bandwidth

**File:** `GpuTextureStore.kt`, `createTexture()`

```kotlin
GPUTextureDescriptor(
    usage = TextureUsage.TextureBinding or TextureUsage.CopyDst,
    size = GPUExtent3D(width = w, height = h),
    format = TextureFormat.BGRA8Unorm,
)
```

`mipLevelCount` defaults to 1 (no mipmaps). Without mipmaps, distant small-screen
faces sample a full-resolution texture — GPU texture cache footprint is the full
texture size regardless of on-screen face size. For a 512×512 BGRA8 texture that is
1 MB in the cache; with mipmaps the working set drops to ~4 KB for very small faces.

Generating mipmaps on WebGPU requires a compute shader or blit pass (no built-in
`generateMipmaps` API in WebGPU). This is deferred work; note it as a known gap for
the texture-atlas slice.

---

## Summary table

| ID     | Severity | Area                        | Fix effort |
|--------|----------|-----------------------------|------------|
| PERF-1 | HIGH     | Fragment shader: unconditional textureSample | Low (swap 1×1 fallback, update comment) |
| PERF-2 | HIGH     | Emit shader: double write per vertex        | Med (refactor clear loop)               |
| PERF-3 | MED      | Vertex stride 36 B: cache-line alignment   | Deferred (revisit at atlas slice)       |
| PERF-4 | MED      | Linear scan for first texture every upload | Low (add `break` in PerFace arm; cache flag on PreparedScene) |
| PERF-5 | MED      | CPU/GPU staging buffers: no growth factor  | Low (apply ×2 pattern)                  |
| PERF-6 | LOW      | Linear filter wrong for pixel-art tiles    | Future (TextureSampler option)          |
| PERF-7 | LOW      | No mipmaps: full-res cache pressure        | Deferred (needs blit/compute pass)      |

## Recommended actions before release build

1. **PERF-1:** Replace the 2×2 checkerboard fallback with a dedicated 1×1 opaque
   white texture (`BGRA8Unorm`, single pixel `0xFFFFFFFF`). Update the comment to
   accurately describe the cost tradeoff rather than claiming "zero visual cost".
2. **PERF-2:** Reorder the emit shader visible-face branch: write real triangle
   vertices first, then fill only the remaining degenerate slots. Remove the blanket
   12-vertex clear for visible faces.
3. **PERF-4:** Add `break` in the `PerFace` arm of `uploadTextures` once any textured
   sub-material is found. Add `hasTexture` flag to `PreparedScene` to skip the scan
   entirely in solid-color scenes.
4. **PERF-5:** Apply `×2` growth factor to `texIndexCpuBuffer` and `texIndexGpuBuffer`
   allocation in `uploadTexIndexBuffer`.

PERF-3, PERF-6, PERF-7 are deferred to the texture-atlas / shader-options slice.
