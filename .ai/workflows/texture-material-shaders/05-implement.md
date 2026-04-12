---
schema: sdlc/v1
type: implement-index
slug: texture-material-shaders
status: in-progress
stage-number: 5
created-at: "2026-04-11T22:32:12Z"
updated-at: "2026-04-12T23:26:45Z"
slices-implemented: 5
slices-total: 6
metric-total-files-changed: 45
metric-total-lines-added: 1764
metric-total-lines-removed: 470
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders per-face-materials"
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

### `sample-demo` — not started

## Cross-Slice Integration Notes
- Dependency graph: `core → compose → shader → webgpu`
- `isometric-compose` has zero shader imports — fully usable standalone
- `ShapeNode.material: MaterialData?` is the cross-module bridge
- `MaterialDrawHook` is the render-time bridge: compose defines interface, shader implements
- `NativeSceneRenderer.renderNative()` now accepts optional `MaterialDrawHook` parameter

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders per-face-materials`
- **Option B:** `/compact` then Option A — clear implementation context
