---
schema: sdlc/v1
type: implement-index
slug: texture-material-shaders
status: in-progress
stage-number: 5
created-at: "2026-04-11T22:32:12Z"
updated-at: "2026-04-11T22:32:12Z"
slices-implemented: 1
slices-total: 6
metric-total-files-changed: 13
metric-total-lines-added: 509
metric-total-lines-removed: 14
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders material-types"
---

# Implement Index

## Slices Implemented

### `material-types` — complete
- Files: 13 (6 new, 7 modified/generated)
- Summary: New `isometric-shader` module with `IsometricMaterial` sealed interface, `TextureSource`, `UvCoord`/`UvTransform`, DSL builders. Wired through `RenderCommand`, nodes, and composables.
- Record: [05-implement-material-types.md](05-implement-material-types.md)

### `uv-generation` — not started
### `canvas-textures` — not started
### `webgpu-textures` — not started
### `per-face-materials` — not started
### `sample-demo` — not started

## Cross-Slice Integration Notes
- `RenderCommand.material` and `RenderCommand.uvCoords` are now available for all downstream slices
- `isometric-shader` module is wired into `isometric-compose` via `api()` — ready for `isometric-webgpu` to depend on it in the `webgpu-textures` slice

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders material-types` — verify the foundation slice
- **Option B:** `/wf-implement texture-material-shaders uv-generation` — proceed to next slice (skip verify)
