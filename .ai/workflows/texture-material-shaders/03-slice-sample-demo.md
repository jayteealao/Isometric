---
schema: sdlc/v1
type: slice
slug: texture-material-shaders
slice-slug: sample-demo
status: defined
stage-number: 3
created-at: "2026-04-11T22:30:00Z"
updated-at: "2026-04-11T22:30:00Z"
complexity: s
depends-on: [per-face-materials]
tags: [sample, demo]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-material-types.md, 03-slice-uv-generation.md, 03-slice-canvas-textures.md, 03-slice-webgpu-textures.md, 03-slice-per-face-materials.md]
  plan: 04-plan-sample-demo.md
  implement: 05-implement-sample-demo.md
---

# Slice: Sample App Demo

## Goal

Add a textured scene to the sample app that demonstrates per-face materials on a Prism
(grass top, dirt sides) in both Canvas and WebGPU render modes. Include texture assets
(grass, dirt bitmaps) in the app resources.

## Why This Slice Exists

The success criterion from intake is "textured Prism in sample app with grass top + dirt
sides." This slice delivers the visible proof that the entire pipeline works end-to-end.

## Scope

**In:**
- Texture assets: grass and dirt bitmap resources (drawable or asset)
- New sample screen/activity showing a textured isometric scene
- Toggle between Canvas and WebGPU modes to demonstrate both paths
- Multiple textured Prisms in a small grid to show texture reuse

**Out:**
- Benchmark integration (future)
- Advanced shader effects demo (future)

## Acceptance Criteria

- Given the sample app running on SM-F956B
- When the user navigates to the textured demo
- Then a Prism grid displays with grass tops and dirt sides

- Given Canvas mode selected
- When the scene renders
- Then textures appear correctly via BitmapShader

- Given WebGPU mode selected
- When the scene renders
- Then textures appear correctly via GPU fragment shader

## Dependencies on Other Slices

- `per-face-materials`: full per-face material pipeline in both renderers

## Risks

- Minimal — this is integration and demo work, not new architecture.
