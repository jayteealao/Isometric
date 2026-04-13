---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: per-face-materials
review-command: correctness
status: complete
updated-at: "2026-04-13T11:40:00Z"
metric-findings-total: 8
metric-findings-blocker: 0
metric-findings-high: 2
result: issues-found
tags: [correctness, per-face, material]
refs:
  review-master: 07-review-per-face-materials.md
---

## Summary

The per-face-materials slice is functionally correct for the primary use case (Prism shapes with up to 4-vertex quad faces). Struct alignment is valid, the UV transform formula is correct, per-face resolution is consistent between Canvas and WebGPU paths, and `PrismFace.fromPathIndex` maps correctly to `Prism.createPaths` order. Two high-severity findings were identified: (1) a stale inline comment in `TransformCullLightShader.kt` that claims `FaceData` is 144 bytes when it is actually 160 bytes, and (2) a broken atlas overflow fallback in `TextureAtlasManager` that maps all textures to placement `(0,0)` when the atlas cannot fit all textures, making every face show only the last-written texture. Additionally, several low/medium issues were found including dead fields in `FaceData`, a UV degeneracy for 5/6-vertex faces (outside Prism scope), a spurious unused parameter, and missing packer test coverage.

---

## Findings Table

| ID | Severity | File | Line(s) | Area | Summary |
|----|----------|------|---------|------|---------|
| COR-1 | HIGH | `TransformCullLightShader.kt` | 30 | Documentation | Stale doc comment claims FaceData is "144 bytes, stride 144"; actual struct is 160 bytes |
| COR-2 | HIGH | `TextureAtlasManager.kt` | 191 | Atlas packing | Overflow fallback maps all placements to `(0,0)` — multiple textures alias to same atlas region |
| COR-3 | MEDIUM | `SceneDataPacker.kt` | 160–165 | Dead data | `FaceData.uvOffsetU/V/ScaleU/V` are always 0,0,1,1 (cmd.uvOffset/uvScale are never populated); these 16 bytes are structural dead weight not read by any shader |
| COR-4 | MEDIUM | `SceneDataPacker.kt` | 154 | Dead data | `FaceData.textureIndex` at offset 124 is packed but never read by M3 (TransformCullLight) or M5 (TriangulateEmit); M5 reads from the compact `sceneTexIndices` buffer (binding 4) instead |
| COR-5 | MEDIUM | `TriangulateEmitShader.kt` | 282–293 | UV correctness | For 5-vertex and 6-vertex faces, triangles 2 and 3 map both interior vertices to `uvMid = (0.5, 0.5)` — the two interior vertices are UV-degenerate (collapse to a point in texture space) |
| COR-6 | LOW | `GpuFullPipeline.kt` | 379–397 | Dead parameter | `collectTextureSources` accepts `faceType: PrismFace?` but never uses it; the comment "Recursively collect … using the command's faceType" is misleading |
| COR-7 | LOW | `SceneDataPackerTest.kt` | — | Test gap | No test for `resolveTextureIndex` with `IsometricMaterial.PerFace` + a specific `faceType`; no test verifying the `uvOffset`/`uvScale` bytes at offsets 132–147 in the packed buffer |
| COR-8 | NIT | `TextureAtlasManager.kt` | 180 | Atlas sizing | `nextPowerOfTwo(maxOf(maxW + paddingPx, 512))` adds `paddingPx` to the widest texture to compute the initial atlas width, but `paddingPx` is a gutter between textures, not a border. No left-edge gutter exists; only a half-pixel UV inset compensates. Harmless but semantically off. |

---

## Detailed Findings

### COR-1 — HIGH: Stale "144 bytes" comment in TransformCullLightShader (doc desync)

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/TransformCullLightShader.kt` line 30

**Finding:**
The KDoc comment in the `WGSL` object's doc block reads:
```
 * - `FaceData` ← [SceneDataPacker] / [SceneDataLayout] (144 bytes, stride 144)
```
This comment was not updated when `FaceData` grew from 144 to 160 bytes in this slice. The actual WGSL struct defined just below (lines 76–110) is 160 bytes and is correctly labeled in its inline comment block. The discrepancy is between the outer KDoc summary (stale) and the inner struct comments (correct).

**Risk:** A developer reading the class-level doc will believe FaceData is 144 bytes and may miscalculate buffer sizes or stride values. The mismatch between the KDoc and the inline struct layout comment increases cognitive load during future buffer-size debugging.

**Fix:** Update line 30 to read `(160 bytes, stride 160)`.

---

### COR-2 — HIGH: Atlas overflow fallback maps all placements to (0,0)

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/TextureAtlasManager.kt` line 191

**Finding:**
When `tryPack` returns `null` at the maximum atlas width (i.e., all textures cannot fit within `maxAtlasSizePx × maxAtlasSizePx`), the fallback is:
```kotlin
return tryPack(sizes, maxAtlasSizePx)
    ?: ShelfLayout(maxAtlasSizePx, maxAtlasSizePx, sizes.map { Placement(0, 0) })
```
The fallback assigns `Placement(0, 0)` to every texture. In `rebuild`, each entry is drawn onto the atlas `Canvas` at its placement position, and then a UV region is computed from that position. Since all placements are `(0, 0)`:

1. All textures are blitted to `(0, 0)` on the `atlasBitmap`, each overwriting the previous. Only the last texture drawn survives in the pixel data.
2. Every `regionMap` entry computes `u0 = halfPixelU`, `v0 = halfPixelV`, `uScale = bitmap.width/atlasWidth - 1/atlasWidth`. Since different textures have different dimensions, their UV regions will have different scales but the same offset. Each face's texture will sample from the same top-left region of the atlas — all rendering the last-written texture.

**Risk:** In practice this fallback fires only when the total texture surface area exceeds 2048×2048 px (the default `maxAtlasSizePx`). While this is an edge case (it requires many large textures), the failure mode is silent pixel corruption — wrong textures are rendered with no error logged.

**Fix:** When the fallback is reached, log an error with the count and total area of textures that don't fit, and either: (a) render those textures using the fallback checkerboard texture, or (b) clip the placement list so that only textures that fit are packed, leaving the rest as fallback.

---

### COR-3 — MEDIUM: FaceData.uvOffsetU/V/ScaleU/V are dead bytes in every frame

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/SceneDataPacker.kt` lines 160–165

**Finding:**
`SceneDataPacker.packInto` writes `cmd.uvOffset` and `cmd.uvScale` to `FaceData` offsets 132–147:
```kotlin
buffer.putFloat(cmd.uvOffset?.get(0) ?: 0f)  // always 0
buffer.putFloat(cmd.uvOffset?.get(1) ?: 0f)  // always 0
buffer.putFloat(cmd.uvScale?.get(0) ?: 1f)   // always 1
buffer.putFloat(cmd.uvScale?.get(1) ?: 1f)   // always 1
```
`RenderCommand.uvOffset` and `RenderCommand.uvScale` are never populated by any code path. Neither `IsometricNode.kt` (which builds `RenderCommand`s in `renderTo`) nor `GpuFullPipeline` sets these fields. The actual atlas UV region data is packed separately by `GpuFullPipeline.uploadUvRegionBuffer` into the compact `sceneUvRegions` GPU buffer (binding 5). The M5 emit shader reads from binding 5 — it never reads `FaceData.uvOffsetU/V/ScaleU/V`.

**Risk:** 16 bytes per face of the 160-byte `FaceData` struct carry perpetually identity-value data that is never read. This wastes ~10% of the struct size per face (16/160). For a scene with 1000 faces, 16 KB of GPU upload and storage is wasted per frame. More importantly, the `RenderCommand.uvOffset` / `uvOffset` doc comments describe these as "Set by the WebGPU pipeline during per-face material resolution," implying they should eventually be set — but the pipeline populates the compact buffer instead.

**Fix:** Either (a) remove `FaceData.uvOffsetU/V/ScaleU/V` and shrink the struct back to 144 bytes (while keeping the compact UV buffer approach), or (b) populate `cmd.uvOffset` / `cmd.uvScale` from `resolveAtlasRegion` before packing, removing the compact buffer. Option (a) is cleaner.

---

### COR-4 — MEDIUM: FaceData.textureIndex at offset 124 is dead

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/SceneDataPacker.kt` line 154

**Finding:**
`SceneDataPacker.packInto` writes `resolveTextureIndex(cmd)` into `FaceData.textureIndex` (offset 124). However, no shader reads this field:
- The M3 shader (`TransformCullLightShader`) reads `v0..v5`, `vertexCount`, `baseColor`, `normal`, and `faceIndex` only — it does not read `textureIndex`.
- The M5 shader (`TriangulateEmitShader`) reads the compact `sceneTexIndices` buffer at binding 4, not `FaceData.textureIndex`.

**Risk:** 4 bytes per face are packed unnecessarily. The `resolveTextureIndex` function is called once per face during packing for this dead field, and then again to populate the compact `texIndices` buffer — so the per-face resolution is computed twice. This is an efficiency issue and an API confusion risk (the field looks like it matters but doesn't).

**Fix:** Remove `FaceData.textureIndex` from the struct and remove its packing in `SceneDataPacker.packInto`. The compact texture-index buffer (binding 4) is the sole source of truth.

---

### COR-5 — MEDIUM: UV degenerate for 5-vertex and 6-vertex faces

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/TriangulateEmitShader.kt` lines 282–293

**Finding:**
For the fan triangulation of faces with 5 or 6 vertices:
```wgsl
// Triangle 2: (s0, s3, s4) — for 5+ vertex faces (pentagon)
if (triCount >= 3u) {
    let uvMid = vec2<f32>(0.5, 0.5) * uvSc + uvOff;
    writeVertex((base + 6u) * 9u, nx0, ny0, r, g, b, a, uv00.x, uv00.y, texIdx);
    writeVertex((base + 7u) * 9u, nx3, ny3, r, g, b, a, uvMid.x, uvMid.y, texIdx);
    writeVertex((base + 8u) * 9u, nx4, ny4, r, g, b, a, uvMid.x, uvMid.y, texIdx); // <-- s3 and s4 both at uvMid
}
// Triangle 3: (s0, s4, s5) — for 6-vertex faces (hexagon)
if (triCount >= 4u) {
    let uvMid = vec2<f32>(0.5, 0.5) * uvSc + uvOff;
    writeVertex((base + 9u)  * 9u, nx0, ny0, r, g, b, a, uv00.x, uv00.y, texIdx);
    writeVertex((base + 10u) * 9u, nx4, ny4, r, g, b, a, uvMid.x, uvMid.y, texIdx); // <-- s4 and s5 both at uvMid
    writeVertex((base + 11u) * 9u, nx5, ny5, r, g, b, a, uvMid.x, uvMid.y, texIdx);
}
```
Vertices s3, s4, s5 in triangles 2 and 3 all receive `uvMid = (0.5, 0.5)`. Two vertices of the same triangle sharing an identical UV forms a UV-degenerate triangle (collapses to a line in texture space), causing incorrect texture sampling for those triangles.

**Scope:** Standard `Prism` faces are 4-vertex quads (`triCount = 2`), so triangles 2 and 3 are never reached in normal Prism rendering. This bug only affects any future custom shapes (pentagon/hexagon faces) that use the same GPU pipeline. It is latent but will silently produce wrong texture output if such shapes are ever introduced.

**Fix:** Assign distinct UV coordinates to s3, s4, s5 based on their fan position. For a regular fan from a 4-vertex base-UV quad, reasonable approximations are: `s3 → uv01`, `s4 → vec2(0.5, 0.0)*uvSc + uvOff`, `s5 → vec2(0.5, 1.0)*uvSc + uvOff`. The exact mapping depends on the target geometry.

---

### COR-6 — LOW: Unused `faceType` parameter in `collectTextureSources`

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuFullPipeline.kt` lines 379–397

**Finding:**
```kotlin
private fun collectTextureSources(
    material: MaterialData?,
    faceType: PrismFace?,       // never read inside the function
    out: MutableSet<TextureSource>,
)
```
The function collects all `TextureSource` references from a material (expanding `PerFace` by iterating all face entries and the default), but the `faceType` parameter is not used anywhere in the body. The callsite passes `cmd.faceType` which is also unused.

**Risk:** The KDoc for the function says it "Recursively collect[s] TextureSource references from a material, expanding IsometricMaterial.PerFace into its constituent sub-materials" — which is accurate and does not mention `faceType`. Collecting all textures regardless of the current face's type is the correct behavior for atlas building. However, the dead parameter creates a misleading API and may confuse future maintainers into thinking face-specific filtering is possible or required here.

**Fix:** Remove the `faceType` parameter from the function signature and the callsite.

---

### COR-7 — LOW: Missing packer tests for PerFace resolution and UV region packing

**File:** `isometric-webgpu/src/test/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/SceneDataPackerTest.kt`

**Finding:**
The existing `SceneDataPackerTest` covers: 6-vertex packing, `baseColor` vs `color` regression, and struct size. There are no tests for:

1. `resolveTextureIndex` with `IsometricMaterial.PerFace` and a specific `faceType` — including the fallback-to-`default` path.
2. The bytes at offsets 128–159 in the packed buffer (`faceIndex`, `uvOffsetU/V`, `uvScaleU/V`, padding). Given that COR-3 and COR-4 identify these fields as dead data, a test at offset 128 that confirms `faceIndex` equals the command's index would at least guard the `faceIndex` chain (used by M5 to look up `sceneTexIndices` and `sceneUvRegions`).

**Risk:** The `faceIndex` field (offset 128) is critical for the emit shader's lookup. Its value is the command list index, which determines which compact buffer slot M5 reads. A packer regression there would cause wrong-texture rendering with no type error.

**Fix:** Add `SceneDataPackerTest` cases for: (a) `PerFace` material with `faceType = FRONT` resolves to the face-specific sub-material, and (b) `faceIndex` at offset 128 equals the command's position in the list.

---

### COR-8 — NIT: `nextPowerOfTwo(maxW + paddingPx)` off-by-semantic in atlas sizing

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/TextureAtlasManager.kt` line 180

**Finding:**
```kotlin
var atlasW = nextPowerOfTwo(maxOf(maxW + paddingPx, 512).coerceAtMost(maxAtlasSizePx))
```
`paddingPx` is a gutter added *between* entries (each entry occupies `w + paddingPx` on the shelf). Adding `paddingPx` to the initial atlas width estimate is a minor semantic confusion: it ensures the narrowest possible atlas is slightly wider than the widest texture, which is correct in practice but for the wrong reason. There is no left-edge border; the half-pixel UV inset compensates for edge bleeding on the left side of the first texture. The formula happens to work because `paddingPx=2` is small relative to `512`.

**Fix:** Cosmetic — use `nextPowerOfTwo(maxOf(maxW, 512).coerceAtMost(maxAtlasSizePx))` and document the half-pixel inset as the bleeding mitigation strategy.

---

## Key Areas Verified Correct

- **WGSL struct alignment (160 bytes):** All field offsets are valid. Each `vec3<f32>` + explicit `f32` pad pair occupies exactly 16 bytes. The `normal: vec3<f32>` at offset 112 is 16-byte aligned (`112 / 16 = 7`). Total struct size 160 is a multiple of 16 (max alignment = `vec4<f32>`). Kotlin packer and WGSL struct comments agree on all byte offsets.

- **UV transform formula** (`atlasUV = baseUV * uvScale + uvOffset`): Correct. For `baseUV = 0`: `atlasU = uvOffset.x = x0/W + 0.5/W` (left texel center). For `baseUV = 1`: `atlasU = uvScale.x + uvOffset.x = (w−1)/W + x0/W + 0.5/W = (x0+w−0.5)/W` (right texel center). Half-pixel inset is correctly applied on both edges.

- **Per-face resolution consistency:** `PerFace.resolve(face)` is called identically in both the Canvas path (`TexturedCanvasDrawHook`) and the WebGPU path (`GpuFullPipeline.resolveAtlasRegion` and `SceneDataPacker.resolveTextureIndex`). Both fall back to `PerFace.default` when `faceType` is null.

- **`faceType` population:** `PrismFace.fromPathIndex` at indices 0–5 maps FRONT, BACK, LEFT, RIGHT, BOTTOM, TOP respectively. `Prism.createPaths` emits paths in exactly that order (face1 at y=0 = FRONT, face1 translated = BACK, face2 at x=0 = LEFT, face2 translated = RIGHT, face3 reversed = BOTTOM, face3 translated = TOP). Mapping is correct; no off-by-one errors.

- **Buffer capacity growth:** `texIndexCapacity` and `uvRegionCapacity` both use `maxOf(faceCount, faceCount * 2)` which correctly doubles on every resize (since `faceCount * 2 ≥ faceCount` always). Initial capacity 0 triggers resize on first call. Edge case `faceCount == capacity` does not resize (correct: buffer is exactly sufficient).

- **Binding 5 index alignment:** M5 reads `sceneUvRegions[key.originalIndex]`. `key.originalIndex` originates from `TransformedFace.faceIndex`, which is set to `face.faceIndex` in M3. `face.faceIndex` is packed from the command's `index` in `SceneDataPacker.packInto`. `uploadUvRegionBuffer` iterates `scene.commands` in the same order and writes one entry per command. The index chain is consistent.

- **`PerFace` init validation:** Nesting of `PerFace` in `faceMap` or `default` is rejected by `require` in the `init` block. This prevents recursive per-face structures that would cause infinite recursion in `resolve`.

- **Canvas PerFace null faceType handling:** `TexturedCanvasDrawHook.draw` uses `material.default` when `command.faceType == null` (non-Prism shapes). This is the correct fallback.
