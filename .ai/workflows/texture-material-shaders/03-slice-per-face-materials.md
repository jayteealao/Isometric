---
schema: sdlc/v1
type: slice
slug: texture-material-shaders
slice-slug: per-face-materials
status: defined
stage-number: 3
created-at: "2026-04-11T22:30:00Z"
updated-at: "2026-04-11T22:30:00Z"
complexity: m
depends-on: [material-types, uv-generation, canvas-textures, webgpu-textures]
tags: [material, per-face]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-material-types.md, 03-slice-uv-generation.md, 03-slice-canvas-textures.md, 03-slice-webgpu-textures.md, 03-slice-sample-demo.md]
  plan: 04-plan-per-face-materials.md
  implement: 05-implement-per-face-materials.md
---

# Slice: Per-Face Materials

## Goal

Enable different materials on different faces of a shape. The `PerFace` material variant
maps face roles (top, sides, bottom) to individual materials. The `perFace {}` DSL
builder provides the ergonomic API. Both Canvas and WebGPU renderers resolve per-face
materials correctly.

## Why This Slice Exists

The hero scenario is a Prism with grass on top and dirt on sides. This requires per-face
material resolution — each face of the shape gets a different texture based on its role
(top/side/bottom). This is the feature that makes the material system useful beyond
"same texture on all faces."

## Scope

**In:**
- `PerFace` material resolution: maps `PrismFace` → `IsometricMaterial` per face
- `perFace {}` DSL builder with `top`, `sides`, `bottom`, `default` properties
- Canvas renderer: resolve per-face material per RenderCommand
- WebGPU renderer: per-face texture index in SceneDataPacker
- Multi-texture support on WebGPU: texture atlas or texture array for multiple textures
  in a single draw call

**Out:**
- Per-face materials for non-Prism shapes (future)
- Custom face roles for user-defined shapes (future)

## Acceptance Criteria

- Given `perFace { top = textured(grass); sides = textured(dirt) }`
- When the Prism renders in Canvas mode
- Then top face shows grass, side faces show dirt, bottom uses default

- Given the same material in WebGPU mode
- When the Prism renders
- Then the same face-to-texture mapping applies

- Given a `PerFace` with no `bottom` specified
- When the Prism renders
- Then bottom face uses the `default` material (flat color if unset)

## Dependencies on Other Slices

- `material-types`: `PerFace` material type, `PrismFace` enum
- `uv-generation`: face-type identification for Prism faces
- `canvas-textures`: Canvas texture rendering pipeline
- `webgpu-textures`: WebGPU texture rendering pipeline

## Risks

- Multi-texture on WebGPU: a single draw call with multiple textures requires either a
  texture atlas or `texture_2d_array`. Atlas adds complexity (packing, bleeding). Texture
  array requires uniform dimensions. May need a simple approach first (sort faces by
  texture, multiple draw calls) before optimizing.
