---
schema: sdlc/v1
type: implement
slug: texture-material-shaders
slice-slug: per-face-materials
status: complete
stage-number: 5
created-at: "2026-04-12T23:26:45Z"
updated-at: "2026-04-12T23:26:45Z"
metric-files-changed: 15
metric-lines-added: 627
metric-lines-removed: 230
metric-deviations-from-plan: 3
metric-review-fixes-applied: 0
commit-sha: "5917113"
tags: [material, per-face, atlas, webgpu, canvas]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-per-face-materials.md
  plan: 04-plan-per-face-materials.md
  siblings: [05-implement-material-types.md, 05-implement-uv-generation.md, 05-implement-canvas-textures.md, 05-implement-webgpu-textures.md]
  verify: 06-verify-per-face-materials.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders per-face-materials"
---

# Implement: Per-Face Materials

## Summary of Changes

Upgraded the material system to support different materials per face of a Prism shape.
Key additions:
- `PerFace` data class now uses `Map<PrismFace, IsometricMaterial>` keys (was `Map<Int, ...>`)
  with a `resolve(face)` method for type-safe per-face lookup
- `PerFaceMaterialScope` DSL with named properties (`top`, `sides`, `bottom`, etc.)
- `RenderCommand.faceType: PrismFace?` identifies which face each command represents
- Canvas renderer resolves per-face materials via `faceType` in `TexturedCanvasDrawHook`
- WebGPU `TextureAtlasManager` packs multiple textures into a single GPU atlas
- `FaceData` struct grows from 144 to 160 bytes to carry per-face UV atlas coordinates
- Emit shader transforms base UVs using per-face atlas region data

## Files Changed

### New files (2)
- `isometric-webgpu/.../texture/TextureAtlasManager.kt`: Shelf-packing atlas manager.
  Packs multiple bitmaps into a single GPU texture with 2px gutter and half-pixel UV
  inset to prevent bilinear bleeding.
- `isometric-shader/src/test/.../PerFaceMaterialTest.kt`: 8 unit tests covering
  `resolve()` for all faces, default fallback, `sides` convenience, DSL ergonomics.

### Modified files (13)
- `isometric-shader/.../IsometricMaterial.kt`: `PerFace` uses `Map<PrismFace, ...>` keys,
  default changed to `FlatColor(transparent)`, added `resolve()` method. Replaced
  `PerFaceBuilder` with `PerFaceMaterialScope` (named properties: top, sides, etc.).
- `isometric-core/.../RenderCommand.kt`: Added `faceType: PrismFace?`, `uvOffset: FloatArray?`,
  `uvScale: FloatArray?` fields. Updated `equals`/`hashCode`/`toString`.
- `isometric-compose/.../runtime/IsometricNode.kt`: `ShapeNode.renderTo()` populates
  `faceType = PrismFace.fromPathIndex(index)` for Prism shapes.
- `isometric-shader/.../render/TexturedCanvasDrawHook.kt`: `PerFace` branch resolves
  using `cmd.faceType` instead of always falling back to `material.default`.
- `isometric-shader/.../IsometricMaterialComposables.kt`: UV provider now triggers for
  `PerFace` materials (not just `Textured`).
- `isometric-webgpu/.../pipeline/SceneDataPacker.kt`: `FACE_DATA_BYTES` 144 -> 160.
  Added `uvOffsetU/V`, `uvScaleU/V` fields at offsets 132-147. `resolveTextureIndex()`
  expands `PerFace` using `cmd.faceType`.
- `isometric-webgpu/.../shader/TransformCullLightShader.kt`: WGSL `FaceData` struct gains
  `uvOffsetU`, `uvOffsetV`, `uvScaleU`, `uvScaleV` fields (f32), replacing 4 of 12
  padding bytes. Struct grows from 144 to 160 bytes.
- `isometric-webgpu/.../shader/TriangulateEmitShader.kt`: Added binding 5 `sceneUvRegions`
  (compact per-face UV region array). Base UVs transformed: `atlasUV = baseUV * uvScale + uvOffset`.
- `isometric-webgpu/.../pipeline/GpuTriangulateEmitPipeline.kt`: Added binding 5 in layout
  and bind group. `ensureBuffers()` takes `uvRegionBuffer` parameter.
- `isometric-webgpu/.../pipeline/GpuFullPipeline.kt`: Replaced single-texture `uploadTextures()`
  with atlas-based approach. Added `TextureAtlasManager`, `collectTextureSources()`,
  `resolveAtlasRegion()`, `uploadUvRegionBuffer()`. Removed unused single-texture fields.
- `isometric-shader/api/isometric-shader.api`: Updated for new `PerFaceMaterialScope` class
  and changed `PerFace` constructor signature.
- `isometric-core/api/isometric-core.api`: Updated for new `RenderCommand` fields.
- `isometric-shader/src/test/.../IsometricMaterialTest.kt`: Updated existing tests for
  `PrismFace` key type, new DSL API, and transparent default.
- `isometric-webgpu/src/test/.../SceneDataPackerTest.kt`: Updated `FACE_DATA_BYTES` assertion
  from 144 to 160.

## Shared Files (also touched by sibling slices)
- `RenderCommand.kt` — also touched by uv-generation (added `uvCoords`)
- `GpuFullPipeline.kt` — also touched by webgpu-textures (texture upload, bind groups)
- `SceneDataPacker.kt` — also touched by webgpu-textures (textureIndex, resolveTextureIndex)
- `TriangulateEmitShader.kt` — also touched by webgpu-textures (vertex UV + textureIndex)

## Notes on Design Choices

- **`Map<PrismFace, ...>` over `Map<Int, ...>`**: Type-safe keys prevent invalid face indices
  at compile time. Breaking change from the material-types slice's original API, but the
  user prefers direct breaking changes over deprecation cycles.
- **`FACE_DATA_BYTES = 160` not 152**: The plan originally computed 152, but WGSL struct
  alignment requires the struct size to be a multiple of 16 (max alignment of vec4 fields).
  152 is not 16-aligned; 160 is.
- **Individual f32 fields over vec2**: UV offset/scale use 4 separate `f32` fields instead
  of 2 `vec2<f32>` to avoid 8-byte alignment padding at offset 132 (which is not 8-aligned).
- **Compact UV region buffer (binding 5)**: Parallels the existing `sceneTexIndices` pattern
  at binding 4. Each face gets a vec4 `(offsetU, offsetV, scaleU, scaleV)` indexed by
  `originalIndex` in the emit shader. This avoids modifying the `TransformedFace` struct.
- **Atlas rebuilt on texture set change**: No incremental updates — the atlas is rebuilt
  from scratch when the set of distinct `TextureSource` references changes. This is simple
  and correct for the v1 feature; LRU eviction is deferred.

## Deviations from Plan

1. **FACE_DATA_BYTES = 160 (not 152)**: Plan computed 152, but 16-byte struct alignment
   requires 160. Used individual f32 fields instead of vec2 to fit the layout.
2. **Compact UV region buffer instead of FaceData-only**: Plan envisioned UV data only in
   FaceData. Implementation adds a parallel compact buffer (binding 5) for M5 emit shader
   access, since TransformedFace doesn't carry UV data.
3. **T12-T14 (integration + snapshot tests) deferred**: These require androidTest
   infrastructure and device runs. Will be covered in the verify stage.

## Anything Deferred
- Integration tests (Canvas PerFace rendering, WebGPU atlas packing) — verify stage
- Snapshot tests (Paparazzi golden images) — verify stage
- `TextureSource.Resource`/`Asset` loading in WebGPU atlas — requires Android Context
- Multi-atlas-page support (overflow when atlas > 2048px)
- LRU eviction for atlas entries

## Known Risks / Caveats
- `FaceData` size change (144 -> 160) affects buffer sizing across the GPU pipeline.
  All existing tests updated, but any external tools or benchmarks that hardcode 144
  will need updating.
- Single atlas page: all textures must fit in one 2048x2048 atlas. Overflow logs a
  warning and uses fallback coordinates.
- `faceType` is null for non-Prism shapes — `PerFace.default` is always the fallback.

## Freshness Research

No external dependency changes since webgpu-textures slice.

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders per-face-materials` — build, test, on-device visual verification
- **Option B:** `/compact` then Option A — recommended to clear implementation context
