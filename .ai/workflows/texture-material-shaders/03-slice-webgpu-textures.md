---
schema: sdlc/v1
type: slice
slug: texture-material-shaders
slice-slug: webgpu-textures
status: defined
stage-number: 3
created-at: "2026-04-11T22:30:00Z"
updated-at: "2026-04-11T22:30:00Z"
complexity: xl
depends-on: [material-types, uv-generation]
tags: [webgpu, texture, shader, wgsl]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-material-types.md, 03-slice-uv-generation.md, 03-slice-canvas-textures.md, 03-slice-per-face-materials.md, 03-slice-sample-demo.md]
  plan: 04-plan-webgpu-textures.md
  implement: 05-implement-webgpu-textures.md
---

# Slice: WebGPU Textured Rendering

## Goal

Make the WebGPU render path draw textured faces using UV-interpolated fragment shader
texture sampling. This includes uploading textures to the GPU, binding them to the
render pipeline, populating real UV values in the triangulate-emit shader, and modifying
the fragment shader to sample from the bound texture.

## Why This Slice Exists

WebGPU is the high-performance render path. Texture support here completes the
"both Canvas and WebGPU" requirement from the intake. The existing scaffolding (UV slots
in shaders, textureIndex stub in SceneDataPacker) means much of the plumbing is already
in place.

## Scope

**In:**
- GPU texture upload: create `GPUTexture` from bitmap, write pixel data
- Bind group updates: bind texture + sampler at `@group(1)` in render pipeline
- `SceneDataPacker`: write actual per-vertex UV coords (not zeros) and texture index
- `GpuTriangulateEmitPipeline`: emit real UV values from face data into vertex buffer
- `IsometricFragmentShader`: conditional `textureSample` when texture is bound,
  fall back to `in.color` when no texture
- `TransformCullLightShader`: pass through UV data and texture index
- Missing texture fallback on GPU: use a 2x2 checkerboard GPU texture as default

**Out:**
- Texture atlas / multi-texture support (single texture binding for now)
- Per-face material resolution on GPU (slice 5)
- Texture compression (ETC2/ASTC) — deferred
- Canvas rendering (slice 3)

## Acceptance Criteria

- Given `Shape(Prism(origin), material = textured(R.drawable.brick))` in WebGPU mode
- When the scene renders
- Then each face displays the texture with correct UV mapping via fragment shader

- Given the WebGPU render matches the Canvas render
- When both modes render the same textured Prism
- Then the texture appears on the same faces with the same orientation (affine-correct)

- Given a scene with textured and non-textured shapes mixed
- When the scene renders in WebGPU mode
- Then textured faces show the texture, non-textured faces show flat color as before

- Given existing WebGPU benchmarks
- When run after texture changes
- Then no performance regression for non-textured scenes (textureIndex = NO_TEXTURE
  fast path in shader)

## Dependencies on Other Slices

- `material-types`: Material types, RenderCommand.material and uvCoords fields
- `uv-generation`: UV coordinates populated on RenderCommand

## Risks

- Vertex buffer stride change: adding UV data may change the stride, affecting all 5
  compute pipeline stages. However, UV slots already exist (always zero) — so the stride
  should NOT change, only the values written.
- GPU texture upload via AndroidX WebGPU alpha: API may have quirks. Need to verify
  `device.createTexture` + `queue.writeTexture` in vendor source.
- Single texture binding limitation: all faces share one texture in this slice. Multi-
  texture requires atlas or texture array (deferred).
