---
schema: sdlc/v1
type: slice-index
slug: texture-material-shaders
status: complete
stage-number: 3
created-at: "2026-04-11T22:30:00Z"
updated-at: "2026-04-11T22:30:00Z"
total-slices: 6
best-first-slice: material-types
tags: [texture, material, shader, canvas, webgpu]
slices:
  - slug: material-types
    status: defined
    complexity: m
    depends-on: []
  - slug: uv-generation
    status: defined
    complexity: m
    depends-on: [material-types]
  - slug: canvas-textures
    status: defined
    complexity: l
    depends-on: [material-types, uv-generation]
  - slug: webgpu-textures
    status: defined
    complexity: xl
    depends-on: [material-types, uv-generation]
  - slug: per-face-materials
    status: defined
    complexity: m
    depends-on: [material-types, uv-generation, canvas-textures, webgpu-textures]
  - slug: sample-demo
    status: defined
    complexity: s
    depends-on: [per-face-materials]
refs:
  index: 00-index.md
  shape: 02-shape.md
next-command: wf-plan
next-invocation: "/wf-plan texture-material-shaders material-types"
---

# Slice Index

## Slice Strategy

Six vertical slices, ordered to reduce risk and build incrementally:

1. **Types first** — establish the module and type system before any rendering
2. **UV generation** — pure math, heavily unit-testable, no platform dependencies
3. **Canvas + WebGPU rendering in parallel** — independent render paths that share types/UVs
4. **Per-face materials** — the hero feature, requires both renderers working
5. **Sample demo** — end-to-end proof, last because it integrates everything

Slices 3 and 4 (canvas-textures and webgpu-textures) are independent of each other
and could be planned/implemented in parallel.

## Recommended Order

1. `material-types` — foundation; all other slices depend on it
2. `uv-generation` — pure math; enables both renderers
3. `canvas-textures` — default render mode, immediate user value
4. `webgpu-textures` — high-performance path, can parallel with slice 3
5. `per-face-materials` — hero scenario (grass top + dirt sides)
6. `sample-demo` — visible proof of the whole pipeline

## Cross-Cutting Concerns

- **Backward compatibility:** Every slice must preserve existing `Shape(shape, color)` API
- **API design guidelines:** All public API follows `docs/internal/api-design-guideline.md`
- **Test coverage:** Each slice adds unit tests for its scope; no slice ships without tests
- **apiCheck:** Every slice that changes public API must run `apiDump` + `apiCheck`

## Dependencies Between Slices

```
material-types ──→ uv-generation ──→ canvas-textures ──┐
                                  ──→ webgpu-textures ──┤
                                                        ├──→ per-face-materials ──→ sample-demo
```

Canvas and WebGPU texture slices are independent of each other (both depend on types + UV).

## Deferred / Optional Slices

These are explicitly out of scope but could be added as future slices:
- UV generation for Pyramid, Cylinder, Octahedron, Stairs, Knot
- Texture atlas packing (Shelf/MaxRects)
- Texture compression (ETC2/ASTC)
- AGSL RuntimeShader effects (Canvas API 33+)
- Normal map lighting
- Animated textures
- Procedural texture generation

## Recommended Next Stage

- **Option A (default):** `/wf-plan texture-material-shaders material-types` — plan the
  foundation slice first. It's the dependency root for everything else.
- **Option B:** `/wf-plan texture-material-shaders all` — plan all 6 slices in parallel.
  Efficient since slices are well-defined, but generates a lot of artifact at once.
