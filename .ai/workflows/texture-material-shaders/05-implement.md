---
schema: sdlc/v1
type: implement-index
slug: texture-material-shaders
status: in-progress
stage-number: 5
created-at: "2026-04-11T22:32:12Z"
updated-at: "2026-04-14T06:46:32Z"
slices-implemented: 7
slices-total: 7
metric-total-files-changed: 72
metric-total-lines-added: 2599
metric-total-lines-removed: 860
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders sample-demo"
---

# Implement Index

## Slices Implemented

### `material-types` — complete (reworked)
- Files: 8 modified/created (phase 2 rework of dependency inversion)
- Summary: `isometric-compose` no longer depends on `isometric-shader`. Shader provides
  overloaded `Shape(geometry, material)` composables. Nodes use `MaterialData?` from core.
- Record: [05-implement-material-types.md](05-implement-material-types.md)

### `uv-generation` — complete
- Files: 9 (4 new, 5 modified/generated)
- Summary: PrismFace enum, UvGenerator object, uvProvider lambda on ShapeNode, wired from shader's Shape() overload
- Record: [05-implement-uv-generation.md](05-implement-uv-generation.md)
### `canvas-textures` — complete
- Files: 12 (6 new, 6 modified including API dumps)
- Summary: MaterialDrawHook strategy injection in compose, TexturedCanvasDrawHook with BitmapShader + affine matrix in shader, ProvideTextureRendering composable, TextureCache LRU, TextureLoader
- Record: [05-implement-canvas-textures.md](05-implement-canvas-textures.md)

### `webgpu-textures` — complete
- Files: 10 (2 new, 8 modified)
- Summary: GPU texture upload (BGRA8Unorm), @group(0) bind group, fragment shader textureSample with NO_TEXTURE fast path, emit shader real UVs + textureIndex, vertex stride 32→36, SceneDataPacker material resolution
- Record: [05-implement-webgpu-textures.md](05-implement-webgpu-textures.md)

### `per-face-materials` — complete
- Files: 15 (2 new, 13 modified)
- Summary: PerFace uses PrismFace keys + resolve() method, PerFaceMaterialScope DSL, faceType on RenderCommand, Canvas per-face resolution, TextureAtlasManager for WebGPU multi-texture, FaceData 144→160 bytes, emit shader UV transform
- Record: [05-implement-per-face-materials.md](05-implement-per-face-materials.md)

### `sample-demo` — complete
- Files: 4 (2 new, 2 modified)
- Summary: TextureAssets (procedural grass/dirt bitmaps), TexturedDemoActivity (render mode toggle + 4x4 perFace prism grid), manifest + MainActivity entries
- Record: [05-implement-sample-demo.md](05-implement-sample-demo.md)

### `api-design-fixes` — complete
- Files: 23 modified/created (including API dumps)
- Summary: 25 API design findings resolved: FlatColor removed, IsoColor:MaterialData, UvTransform→TextureTransform, BitmapSource→Bitmap, textured→texturedResource, TextureLoader fun interface, TextureCacheConfig, @DslMarker, PerFace.of() factory, Shape(material:MaterialData), TileMode per-draw shader cache
- Record: [05-implement-api-design-fixes.md](05-implement-api-design-fixes.md)

## Cross-Slice Integration Notes
- Dependency graph: `core → compose → shader → webgpu`
- `isometric-compose` has zero shader imports — fully usable standalone
- `ShapeNode.material: MaterialData?` is the cross-module bridge
- `MaterialDrawHook` is the render-time bridge: compose defines interface, shader implements
- `NativeSceneRenderer.renderNative()` now accepts optional `MaterialDrawHook` parameter

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders sample-demo`
- **Option B:** `/compact` then Option A — clear implementation context
