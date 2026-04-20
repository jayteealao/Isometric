---
schema: sdlc/v1
type: slice
slug: texture-material-shaders
slice-slug: uv-generation-knot
status: implemented
stage-number: 3
created-at: "2026-04-14T17:30:20Z"
updated-at: "2026-04-20T21:02:21Z"
complexity: m
depends-on: [uv-generation, api-design-fixes]
source: extension
source-ref: "user request (wf-extend 2026-04-14)"
extension-round: 2
tags: [uv, texture, knot, uv-generation]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  source: ""
  plan: 04-plan-uv-generation-knot.md
  implement: 05-implement-uv-generation-knot.md
---

# Slice: uv-generation-knot

## Goal

Extend `UvGenerator` with UV coordinate generation for Knot faces, enabling
`textured()` materials to render correctly on Knot shapes in both Canvas and
WebGPU modes.

## Why This Slice Exists

UV generation for Knot was listed as deferred in `03-slice.md`. Without it,
`textured()` on a Knot silently falls back to flat-color rendering.

## Scope

### In

- `UvGenerator.forKnotFace(face, vertices)` — returns UV coordinates for the given
  face of a Knot shape
- A single uniform material applied across all Knot faces (primary use case)
- `PerFace` support to the extent that Knot exposes distinct named faces; if the
  shape has no named face taxonomy, `perFace {}` is documented as unsupported
- Integration into Canvas and WebGPU render paths
- Unit tests for UV generation on Knot face regions

### Out

- UV generation for other shapes
- Per-strand UV mapping (treating each section of the knot path independently)
- `TextureTransform` application — automatic once UV coords exist

## Acceptance Criteria

- **AC1: Texture renders on Knot** — Given `Shape(Knot(origin), texturedResource(R.drawable.marble))`, when rendered in Canvas mode, the marble texture appears on the Knot shape faces without falling back to flat-color.
- **AC2: No UV discontinuity** — The texture does not exhibit gross tearing or discontinuous jumps between adjacent faces in the rendered output.
- **AC3: WebGPU parity** — Texture appearance is consistent between Canvas and WebGPU render modes.
- **AC4: Unit tests pass** — UV coordinate tests cover at least one face from each distinct region of the Knot mesh.
- **AC5: No regression** — All existing UV generation tests pass.

## Dependencies on Other Slices

- `uv-generation`: provides the `UvGenerator` extension pattern.
- `api-design-fixes`: locks the API.

## Risks

- **Knot face topology is unknown**: plan phase must be the first step — inspect `Knot`'s
  geometry in `isometric-core` to understand its face decomposition before committing to
  a UV algorithm. Knot may be a swept tube (high face count, parameterized UV along the
  path) or a simpler polyhedral approximation. The complexity estimate `m` may revise up
  to `l` if the topology is complex.
- **Per-face addressing**: if `Knot` has no clean named-face taxonomy (e.g. it's a smooth
  mesh), `perFace {}` may not be meaningful. Plan phase should decide whether to support
  it or document it as unsupported.
