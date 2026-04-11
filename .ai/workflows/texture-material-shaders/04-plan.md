---
schema: sdlc/v1
type: plan-index
slug: texture-material-shaders
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-11T22:49:12Z"
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

### `material-types` (rev 3)
- **Files:** 7 (1 new, 6 modified) — rework of already-implemented steps 8-12
- **Steps:** 14 (steps 1-7 already done; steps 8-14 are the rework)
- **Strategy:** New `isometric-shader` Android library module with types + DSL (done).
  **Rework:** Remove compose→shader dependency. `isometric-compose` uses `MaterialData?`
  (core) on nodes, no material param on composables. `isometric-shader` depends on compose
  and provides overloaded `Shape(geometry, material)` composables.
- **Key risk:** Overload resolution between `Shape(geo, color: IsoColor)` and
  `Shape(geo, material: IsometricMaterial)` — types are unrelated, Kotlin handles cleanly.

### `uv-generation` (rev 1)
- **Files:** 7 (5 new, 2 modified)
- **Steps:** 8
- **Strategy:** `PrismFace` enum in `isometric-core`. `UvGenerator` in `isometric-shader`.
  UV wiring via `uvProvider` lambda on `ShapeNode` (set by shader's `Shape()` overload) —
  **not** via direct import of shader types in compose.
- **Key risk:** Prism face ordering stability across transforms — verified stable.

### `canvas-textures` (rev 1)
- **Files:** 12 (4 new, 3 modified, 5 test)
- **Steps:** 22
- **Strategy:** `TextureCache`, `MaterialResolver`, `TextureLoader` all live in
  `isometric-shader` (not compose). `TexturedCanvasRenderBackend` decorator in shader wraps
  compose's base backend. `BitmapShader` + 3-point affine `Matrix.setPolyToPoly`.
- **Key risk:** Decorator pattern for canvas render backend — must hook into compose's draw
  path without breaking existing flat-color rendering.

### `webgpu-textures` (rev 1)
- **Files:** 10 modified + 2 new (added `build.gradle.kts` for shader dep)
- **Steps:** 11 (added Step 0: add `:isometric-shader` dep to webgpu)
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

### `sample-demo` (rev 1)
- **Files:** 8 (2 new, 6 modified — added `app/build.gradle.kts` for shader dep)
- **Steps:** 10 (added Step 0: add `:isometric-shader` dep to app)
- **Strategy:** `TexturedDemoActivity` with 4x4 grid of Prisms. Procedurally generated
  grass/dirt textures (64x64 bitmaps, no external assets needed). Three-button render
  mode toggle (Canvas / Canvas+GPU Sort / WebGPU). Added to `MainActivity` chooser.
- **Key risk:** Minimal — integration only.

## Cross-Cutting Concerns

- **Dependency graph (rev 3):** `core → compose → shader → webgpu`. Compose does NOT
  depend on shader. All material-aware logic (types, DSL, caching, resolution, composable
  overloads) lives in `isometric-shader`. Compose only uses `MaterialData?` (core marker).
- **Backward compatibility:** `Shape(shape, color)` in compose is unchanged. Material
  overloads (`Shape(shape, material)`) live in shader module — additive only.
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

**Post-rev-3 cohesion review (2026-04-11):** 16 issues found across 5 plans (6 HIGH,
5 MED, 5 LOW). All caused by the dependency inversion in `material-types` rev 3. All fixed.

Key coordination points after fix:
- `RenderCommand.material: MaterialData?` (core) — consumed by all slices
- `isometric-compose` has NO shader imports — uses `MaterialData?` and `uvProvider` lambda
- `isometric-shader` provides composable overloads, texture caching, material resolution
- `isometric-webgpu` adds `isometric-shader` dependency in the `webgpu-textures` slice
- `app` adds `isometric-shader` dependency in the `sample-demo` slice

## Freshness Research

- AndroidX WebGPU vendor API verified: `queue.writeTexture(GPUTexelCopyTextureInfo,
  ByteBuffer, GPUExtent3D, GPUTexelCopyBufferLayout)` confirmed in vendor source.
- `BitmapShader` + `Matrix.setPolyToPoly` approach verified against current Android API.
- Compose `DrawScope.drawIntoCanvas` available in current Compose BOM.

## Recommended Next Stage

- **Option A (default):** `/wf-implement texture-material-shaders material-types` — rework
  the already-implemented material-types slice per rev 3 (reverse the dependency).
  **Consider `/compact` first** — planning/review context is noise for implementation.
- **Option B:** Sequential through all 6 slices after material-types rework.
