---
schema: sdlc/v1
type: implement-index
slug: texture-material-shaders
status: in-progress
stage-number: 5
created-at: "2026-04-11T22:32:12Z"
updated-at: "2026-04-12T10:49:14Z"
slices-implemented: 3
slices-total: 6
metric-total-files-changed: 20
metric-total-lines-added: 490
metric-total-lines-removed: 49
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders canvas-textures"
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

### `webgpu-textures` — not started
### `per-face-materials` — not started
### `sample-demo` — not started

## Cross-Slice Integration Notes
- Dependency graph: `core → compose → shader → webgpu`
- `isometric-compose` has zero shader imports — fully usable standalone
- `ShapeNode.material: MaterialData?` is the cross-module bridge
- `MaterialDrawHook` is the render-time bridge: compose defines interface, shader implements
- `NativeSceneRenderer.renderNative()` now accepts optional `MaterialDrawHook` parameter

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders canvas-textures`
- **Option B:** `/compact` then Option A — clear implementation context
