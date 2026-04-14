---
schema: sdlc/v1
type: slice-index
slug: texture-material-shaders
status: active
stage-number: 3
created-at: "2026-04-11T22:30:00Z"
updated-at: "2026-04-14T17:30:20Z"
total-slices: 14
best-first-slice: api-design-fixes
tags: [texture, material, shader, canvas, webgpu]
slices:
  - slug: material-types
    status: complete
    complexity: m
    depends-on: []
  - slug: uv-generation
    status: complete
    complexity: m
    depends-on: [material-types]
  - slug: canvas-textures
    status: complete
    complexity: l
    depends-on: [material-types, uv-generation]
  - slug: webgpu-textures
    status: complete
    complexity: xl
    depends-on: [material-types, uv-generation]
  - slug: per-face-materials
    status: complete
    complexity: m
    depends-on: [material-types, uv-generation, canvas-textures, webgpu-textures]
  - slug: sample-demo
    status: complete
    complexity: s
    depends-on: [per-face-materials]
  - slug: api-design-fixes
    status: defined
    complexity: m
    depends-on: [material-types, uv-generation, canvas-textures, webgpu-textures, per-face-materials, sample-demo]
  - slug: webgpu-uv-transforms
    status: defined
    complexity: m
    depends-on: [api-design-fixes]
    source: extension
    extension-round: 1
  - slug: webgpu-texture-error-callback
    status: defined
    complexity: xs
    depends-on: [api-design-fixes]
    source: extension
    extension-round: 2
  - slug: uv-generation-cylinder
    status: defined
    complexity: m
    depends-on: [uv-generation, api-design-fixes]
    source: extension
    extension-round: 2
  - slug: uv-generation-pyramid
    status: defined
    complexity: s
    depends-on: [uv-generation, api-design-fixes]
    source: extension
    extension-round: 2
  - slug: uv-generation-stairs
    status: defined
    complexity: m
    depends-on: [uv-generation, api-design-fixes]
    source: extension
    extension-round: 2
  - slug: uv-generation-knot
    status: defined
    complexity: m
    depends-on: [uv-generation, api-design-fixes]
    source: extension
    extension-round: 2
  - slug: uv-generation-octahedron
    status: defined
    complexity: s
    depends-on: [uv-generation, api-design-fixes]
    source: extension
    extension-round: 2
refs:
  index: 00-index.md
  shape: 02-shape.md
next-command: wf-plan
next-invocation: "/wf-plan texture-material-shaders api-design-fixes"
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

1. `material-types` — foundation; all other slices depend on it ✅
2. `uv-generation` — pure math; enables both renderers ✅
3. `canvas-textures` — default render mode, immediate user value ✅
4. `webgpu-textures` — high-performance path, can parallel with slice 3 ✅
5. `per-face-materials` — hero scenario (grass top + dirt sides) ✅
6. `sample-demo` — visible proof of the whole pipeline ✅
7. `api-design-fixes` — post-retro API compliance; fixes 25 findings before PR #8 merges

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

- **Option A (default):** `/wf-plan texture-material-shaders api-design-fixes` — plan
  the new API fixes slice. All six original slices are complete; this is the only
  remaining slice.
- **Option B:** `/wf-plan texture-material-shaders all` — equivalent to Option A since
  only one slice is pending.

---

## Extension Round 1 — 2026-04-14
Source: user request (wf-extend)

### New Slices Added

| Slice | Goal | Complexity | Depends On |
|-------|------|------------|------------|
| `webgpu-uv-transforms` | Apply TextureTransform (scale/offset/rotation) in WGSL shader with Canvas visual parity | m | api-design-fixes |

### Motivation

`TextureTransform` was implemented on the Canvas path in the `api-design-fixes` slice but
explicitly deferred for WebGPU — the WebGPU path hardcodes UV quad constants and does not
pack transform data into the GPU buffer. This extension slice closes that gap so that a
`TextureTransform` passed to a textured material produces identical output on both render
paths. Per-face independent transforms are in scope; all three transform operations
(scale, offset, rotation) are required.

---

## Extension Round 2 — 2026-04-14
Source: user request (wf-extend)

### New Slices Added

| Slice | Goal | Complexity | Depends On |
|-------|------|------------|------------|
| `webgpu-texture-error-callback` | Forward onTextureLoadError into GpuTextureManager for Canvas/WebGPU parity | xs | api-design-fixes |
| `uv-generation-cylinder` | UV mapping for Cylinder faces | m | uv-generation, api-design-fixes |
| `uv-generation-pyramid` | UV mapping for Pyramid faces | s | uv-generation, api-design-fixes |
| `uv-generation-stairs` | UV mapping for Stairs faces | m | uv-generation, api-design-fixes |
| `uv-generation-knot` | UV mapping for Knot faces | m | uv-generation, api-design-fixes |
| `uv-generation-octahedron` | UV mapping for Octahedron faces | s | uv-generation, api-design-fixes |

### Motivation

Two capability gaps identified: (1) `onTextureLoadError` was wired into the Canvas path by
`api-design-fixes` but never plumbed into `GpuTextureManager`, leaving WebGPU texture errors
silent. (2) UV generation exists only for Prism; all other shapes fall back to flat-color
rendering when a textured material is applied. All UV gen slices are independent of each
other and can be planned and implemented in parallel after `api-design-fixes` completes.
