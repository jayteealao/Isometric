---
schema: sdlc/v1
type: slice
slug: texture-material-shaders
slice-slug: canvas-textures
status: defined
stage-number: 3
created-at: "2026-04-11T22:30:00Z"
updated-at: "2026-04-11T22:30:00Z"
complexity: l
depends-on: [material-types, uv-generation]
tags: [canvas, texture, rendering]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-material-types.md, 03-slice-uv-generation.md, 03-slice-webgpu-textures.md, 03-slice-per-face-materials.md, 03-slice-sample-demo.md]
  plan: 04-plan-canvas-textures.md
  implement: 05-implement-canvas-textures.md
---

# Slice: Canvas Textured Rendering

## Goal

Make the Canvas render path draw textured faces using BitmapShader + Matrix. This
includes texture loading, caching, the affine matrix computation, and the missing-texture
fallback. After this slice, `Shape(Prism(origin), material = textured(R.drawable.brick))`
produces a visually textured Prism in Canvas mode.

## Why This Slice Exists

Canvas is the default and most widely available render mode. Getting textures working
here first provides immediate user value and validates the material → UV → render
pipeline end-to-end before tackling the more complex WebGPU path.

## Scope

**In:**
- `TextureCache` in `isometric-shader`: LRU bitmap cache with configurable max size
- `TextureLoader`: loads `TextureSource` → `Bitmap` (resource, asset, raw bitmap)
- `MaterialResolver`: resolves `IsometricMaterial` → rendering instructions for Canvas
- `CanvasRenderBackend` changes: detect textured material, create BitmapShader,
  compute affine Matrix via `setPolyToPoly` (3-point for parallelograms), draw with shader
- Missing texture fallback: magenta/black checkerboard pattern
- Snapshot tests (Paparazzi): textured Prism, missing texture fallback

**Out:**
- WebGPU textured rendering (slice 4)
- Per-face material resolution (slice 5 — this slice applies the same material to all faces)
- Texture atlas packing (deferred)
- AGSL shader effects (deferred)

## Acceptance Criteria

- Given `Shape(Prism(origin), material = textured(R.drawable.brick))` in Canvas mode
- When the scene renders
- Then each face displays the brick texture affine-mapped to the face polygon

- Given the same texture used by 5 shapes
- When textures are loaded
- Then only 1 bitmap is in memory (cache hit)

- Given `textured(R.drawable.nonexistent)`
- When texture loading fails
- Then the face renders with a magenta/black checkerboard fallback, no crash

- Given Paparazzi snapshot tests
- When run
- Then textured Prism snapshot matches golden image

## Dependencies on Other Slices

- `material-types`: Material types, TextureSource, RenderCommand.material field
- `uv-generation`: UV coordinates on RenderCommand for affine matrix computation

## Risks

- BitmapShader matrix computation for non-rectangular (parallelogram) faces may have
  edge cases with `setPolyToPoly`. Research says 3-point affine is sufficient and stable.
- Compose `DrawScope.drawPath` with `ShaderBrush` may have performance implications
  if the shader is recreated per-frame. Must cache Paint/Brush objects.
