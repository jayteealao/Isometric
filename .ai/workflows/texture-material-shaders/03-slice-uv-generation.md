---
schema: sdlc/v1
type: slice
slug: texture-material-shaders
slice-slug: uv-generation
status: defined
stage-number: 3
created-at: "2026-04-11T22:30:00Z"
updated-at: "2026-04-11T22:30:00Z"
complexity: m
depends-on: [material-types]
tags: [uv, geometry]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-material-types.md, 03-slice-canvas-textures.md, 03-slice-webgpu-textures.md, 03-slice-per-face-materials.md, 03-slice-sample-demo.md]
  plan: 04-plan-uv-generation.md
  implement: 05-implement-uv-generation.md
---

# Slice: UV Coordinate Generation

## Goal

Implement automatic UV coordinate generation for Prism faces. When a textured material
is assigned, each face gets UV coords that map the texture rectangle onto the isometric
face polygon. Also add face-type metadata to Prism so per-face materials can identify
top/side/bottom faces.

## Why This Slice Exists

Both Canvas and WebGPU renderers need UV coordinates to map textures onto faces. UV
generation is pure math with no platform dependencies — it can be thoroughly unit-tested
before any rendering work begins.

## Scope

**In:**
- `UvGenerator` in `isometric-shader`: generates per-vertex UVs for Prism faces
- Prism face-type identification: enum `PrismFace { TOP, BOTTOM, FRONT, BACK, LEFT, RIGHT }`
- Face-type metadata: either stable index convention or tagged paths
- UV coords populated on `RenderCommand.uvCoords` when material is `Textured`
- Unit tests: UV generation for all 6 Prism face types, edge cases (zero-size, degenerate)

**Out:**
- UV generation for non-Prism shapes (Pyramid, Cylinder, etc.) — future slices
- Isometric foreshortening correction — deferred optimization
- Rendering changes — handled by slices 3 and 4

## Acceptance Criteria

- Given a Prism with a `Textured` material
- When UV coordinates are generated for each face
- Then each quad face gets 4 UV coords mapping [0,0]→[1,0]→[1,1]→[0,1]

- Given a Prism
- When face types are queried
- Then the top face is identified as `PrismFace.TOP`, sides as FRONT/BACK/LEFT/RIGHT,
  bottom as BOTTOM

- Given UV generation unit tests
- When run
- Then all pass with correct UV values for known Prism dimensions

## Dependencies on Other Slices

- `material-types`: `IsometricMaterial`, `UvCoord`, `RenderCommand.uvCoords` field

## Risks

- Prism face ordering may not be stable across shape transforms (rotation, scaling).
  Must verify that `orderedPaths()` doesn't reorder faces in a way that breaks face-type
  identification.
