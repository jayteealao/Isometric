---
schema: sdlc/v1
type: plan-index
slug: texture-material-shaders
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-11T22:50:00Z"
planning-mode: all
slices-planned: 6
slices-total: 6
implementation-order: [material-types, uv-generation, canvas-textures, webgpu-textures, per-face-materials, sample-demo]
conflicts-found: 0
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders material-types"
---

# Plan Index

## Slice Plan Summaries

### `material-types`
- **Files:** 9 (5 new, 4 modified)
- **Steps:** 12
- **Strategy:** New `isometric-shader` Android library module. `IsometricMaterial` sealed
  interface with `FlatColor`/`Textured`/`PerFace`. `TextureSource` sealed interface.
  `RenderCommand` extended with `material`/`uvCoords` fields. `Shape()` composable gets
  `material` parameter. Zero rendering changes.
- **Key risk:** Module must be Android library (not pure JVM) because `TextureSource`
  references `android.graphics.Bitmap` and `@DrawableRes`.

### `uv-generation`
- **Files:** 7 (5 new, 2 modified)
- **Steps:** 8
- **Strategy:** `PrismFace` enum in `isometric-core` with stable index mapping (0=FRONT
  through 5=TOP). `UvGenerator` in `isometric-shader` computes per-vertex UVs from 3D
  `Path.points` before projection (correct for orthographic). Wire into `ShapeNode.renderTo()`.
- **Key risk:** Prism face ordering stability across transforms — verified stable.

### `canvas-textures`
- **Files:** 12 (4 new, 3 modified, 5 test)
- **Steps:** 22
- **Strategy:** `TextureCache` (LRU, `LinkedHashMap`), `MaterialResolver` with fallback
  chain. `CanvasRenderBackend` uses `drawIntoCanvas` with `BitmapShader` + 3-point affine
  `Matrix.setPolyToPoly`. Shader created once at cache-put time; only `setLocalMatrix`
  per draw. Checkerboard fallback (16x16, magenta/black). 3 Paparazzi snapshot tests.
- **Key risk:** `DrawScope.drawIntoCanvas` integration — must not break existing flat-color
  draw path.

### `webgpu-textures`
- **Files:** 9 modified + 2 new
- **Steps:** 10
- **Strategy:** Vertex stride grows 32→36 bytes (new `textureIndex` attribute at location 3).
  `GpuTextureStore` uploads bitmaps to `GPUTexture`. Fragment shader: `if (textureIndex == NO_TEXTURE) return color; else textureSample`. Bind group 1 for texture+sampler.
  `SceneDataPacker` writes real `textureIndex` from `RenderCommand.material`. Emit shader
  writes actual UVs (not zeros).
- **Key risk:** Vertex stride change cascades to emit shader, render pipeline layout, and
  CPU-side triangulator. Must coordinate carefully.

### `per-face-materials`
- **Files:** 12 (5 new, 7 modified)
- **Steps:** 14
- **Strategy:** `PerFace.resolve(PrismFace)` maps face roles to materials. `perFace {}`
  DSL with `top`/`sides`/`bottom`/`front`/`back`/`left`/`right` properties. WebGPU
  multi-texture via texture atlas (Shelf packing, 512-2048px, 2px padding + half-pixel
  UV inset). FaceData extended 144→160 bytes with `uvOffset`/`uvScale` fields.
- **Key risk:** Atlas packing correctness (texture bleeding). Mitigated by padding +
  half-pixel correction.

### `sample-demo`
- **Files:** 7 (2 new, 5 modified)
- **Steps:** 9
- **Strategy:** `TexturedDemoActivity` with 4x4 grid of Prisms. Procedurally generated
  grass/dirt textures (64x64 bitmaps, no external assets needed). Three-button render
  mode toggle (Canvas / Canvas+GPU Sort / WebGPU). Added to `MainActivity` chooser.
- **Key risk:** Minimal — integration only.

## Cross-Cutting Concerns

- **Backward compatibility:** All slices preserve `Shape(shape, color)` API. The
  `material` parameter defaults to `null` everywhere.
- **`apiCheck`:** Slices 1 and 2 add new public API. Must run `apiDump` + `apiCheck`.
- **Test coverage:** Each slice includes its own tests. Total new tests across all slices:
  ~30+ unit tests, 3 snapshot tests.
- **API design guidelines:** All plans reference relevant sections of
  `docs/internal/api-design-guideline.md` (§2 progressive disclosure, §3 defaults,
  §6 invalid states, §10 host language, §11 evolution).

## Integration Points Between Slices

| Producer Slice | Consumer Slice | Shared Interface |
|---|---|---|
| material-types | all others | `IsometricMaterial`, `TextureSource`, `RenderCommand.material` |
| uv-generation | canvas-textures, webgpu-textures | `RenderCommand.uvCoords`, `PrismFace` enum |
| canvas-textures | per-face-materials | `MaterialResolver`, `TextureCache` |
| webgpu-textures | per-face-materials | `GpuTextureStore`, `SceneDataPacker.textureIndex` |
| per-face-materials | sample-demo | `perFace {}` DSL, atlas support |

## Recommended Implementation Order

1. `material-types` — dependency root, all slices need these types
2. `uv-generation` — pure math, enables both renderers
3. `canvas-textures` — default render mode, immediate visual validation
4. `webgpu-textures` — can run in parallel with canvas-textures if desired
5. `per-face-materials` — hero feature, requires both renderers working
6. `sample-demo` — end-to-end proof

## Conflicts Found

None. The 6 sub-agents produced compatible plans with no overlapping file modifications
or contradictory assumptions. Key coordination points are well-defined:
- `RenderCommand` fields added in slice 1, consumed by slices 2-5
- `PrismFace` enum added in slice 2, consumed by slices 3-5
- Vertex stride change in slice 4 is self-contained within the webgpu module

## Freshness Research

- AndroidX WebGPU vendor API verified: `queue.writeTexture(GPUTexelCopyTextureInfo,
  ByteBuffer, GPUExtent3D, GPUTexelCopyBufferLayout)` confirmed in vendor source.
- `BitmapShader` + `Matrix.setPolyToPoly` approach verified against current Android API.
- Compose `DrawScope.drawIntoCanvas` available in current Compose BOM.

## Recommended Next Stage

- **Option A (default):** `/wf-implement texture-material-shaders material-types` — start
  with the foundation slice. All other slices depend on it.
  **Consider `/compact` first** — planning research is noise for implementation.
- **Option B:** `/wf-implement texture-material-shaders material-types` then sequential
  through all 6 slices in order.
