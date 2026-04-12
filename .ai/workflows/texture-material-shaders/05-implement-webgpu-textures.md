---
schema: sdlc/v1
type: implement
slug: texture-material-shaders
slice-slug: webgpu-textures
status: complete
stage-number: 5
created-at: "2026-04-12T16:27:39Z"
updated-at: "2026-04-12T21:26:08Z"
metric-files-changed: 10
metric-lines-added: 647
metric-lines-removed: 191
metric-deviations-from-plan: 1
metric-review-fixes-applied: 14
commit-sha: "96a8d8a"
tags: [webgpu, texture, shader, wgsl, bgra]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-webgpu-textures.md
  plan: 04-plan-webgpu-textures.md
  siblings: [05-implement-material-types.md, 05-implement-uv-generation.md, 05-implement-canvas-textures.md]
  verify: 06-verify-webgpu-textures.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders webgpu-textures"
---

# Implement: WebGPU Textured Rendering

## Summary of Changes

Wired texture sampling into the WebGPU render pipeline. Faces with
`IsometricMaterial.Textured` now render via `textureSample()` in the fragment shader;
non-textured faces take a zero-cost `textureIndex == NO_TEXTURE` fast path. A 2×2
magenta/black checkerboard GPU texture stands in as fallback.

Key additions:
- `GpuTextureStore`: uploads Android `Bitmap` data as `BGRA8Unorm` GPU textures (no
  CPU-side channel swizzle)
- `GpuTextureBinder`: creates sampler + `@group(0)` bind group layout for the render
  pipeline's fragment shader
- Fragment shader: conditional `textureSample()` with `@interpolate(flat)` textureIndex
- Emit shader: writes real UV constants `(0,0)(1,0)(1,1)(0,1)` for Prism quads + reads
  per-face `textureIndex` from compact buffer at binding 4
- Vertex stride: 32→36 bytes (9 u32 per vertex) adding `textureIndex` at offset 32
- `SceneDataPacker`: resolves `IsometricMaterial` → texture index (including `PerFace.default`)
- Explicit render pipeline layout with `@group(0)` for texture+sampler

## Files Changed

### New files (2)
- `isometric-webgpu/.../texture/GpuTextureStore.kt`: GPU texture creation + BGRA8Unorm
  upload from `Bitmap.copyPixelsToBuffer()`. Checkerboard fallback texture init. Owns
  all uploaded textures via `ownedTextures` list.
- `isometric-webgpu/.../texture/GpuTextureBinder.kt`: Sampler (Linear/Linear/Nearest,
  ClampToEdge) + `GPUBindGroupLayout` for `@group(0)` (texture at binding 0, sampler
  at binding 1). `buildBindGroup(textureView)` factory.

### Modified files (8)
- `isometric-webgpu/build.gradle.kts`: Added `implementation(project(":isometric-shader"))`
  dependency so `SceneDataPacker` and `GpuFullPipeline` can cast `MaterialData?` to
  `IsometricMaterial`.
- `isometric-webgpu/.../shader/IsometricFragmentShader.kt`: Added `@group(0)` texture+sampler
  bindings. `FragmentInput` gains `@location(2) @interpolate(flat) textureIndex: u32`.
  `fragmentMain` branches: `NO_TEXTURE → return in.color`; else `textureSample * in.color`.
- `isometric-webgpu/.../shader/IsometricVertexShader.kt`: `VertexInput` gains
  `@location(3) textureIndex: u32`. `VertexOutput` gains
  `@location(2) @interpolate(flat) textureIndex: u32`. Passthrough in `vertexMain`.
- `isometric-webgpu/.../pipeline/GpuRenderPipeline.kt`: Constructor takes
  `textureBindGroupLayout: GPUBindGroupLayout`. Creates explicit `GPUPipelineLayout`
  with `@group(0)`. Adds 4th vertex attribute (`Uint32`, offset 32, location 3).
- `isometric-webgpu/.../triangulation/RenderCommandTriangulator.kt`: `FLOATS_PER_VERTEX`
  renamed to `U32S_PER_VERTEX = 9`. `BYTES_PER_VERTEX = 36`. `writeVertex` gains
  `u`, `v`, `textureIndex` params with defaults.
- `isometric-webgpu/.../shader/TriangulateEmitShader.kt`: `writeVertex` gains `u`, `v`,
  `texIdx` params, writes 9 u32. Stride arithmetic changed `8u → 9u`. Added
  `@group(0) @binding(4) sceneTexIndices: array<u32>`. Constant UVs for quad fan.
- `isometric-webgpu/.../pipeline/GpuTriangulateEmitPipeline.kt`: Bind group layout gets
  5th entry (binding 4, ReadOnlyStorage). `ensureBuffers` gains `texIndexBuffer` param.
  Change-detection state updated.
- `isometric-webgpu/.../pipeline/SceneDataPacker.kt`: `textureIndex` field now uses
  `resolveTextureIndex(cmd)` instead of hard-coded `NO_TEXTURE`. Added
  `packTexIndicesInto()` for compact per-face tex-index buffer. Material resolution:
  `Textured → 0`, `PerFace(.default is Textured) → 0`, else `NO_TEXTURE`.
- `isometric-webgpu/.../pipeline/GpuFullPipeline.kt`: Holds `GpuTextureStore` +
  `GpuTextureBinder`. `upload()` scans scene for textured materials, uploads bitmap via
  `BitmapSource`, caches by bitmap identity, builds bind group. Manages compact
  `texIndexGpuBuffer`. Exposes `textureBindGroup` for render pass.
- `isometric-webgpu/.../WebGpuSceneRenderer.kt`: Creates `GpuFullPipeline` before
  `GpuRenderPipeline` (binder.bindGroupLayout needed). Render pass calls
  `pass.setBindGroup(0, gp.textureBindGroup)` before draw.

## Shared Files (also touched by sibling slices)
- `SceneDataPacker.kt` — previously had hardcoded `NO_TEXTURE`; now resolves material
- `GpuFullPipeline.kt` — significant additions for texture orchestration
- `WebGpuSceneRenderer.kt` — `ensureInitialized` init order changed

## Notes on Design Choices

- **BGRA8Unorm without swizzle:** Android `Bitmap.Config.ARGB_8888` stores bytes as BGRA
  in memory on little-endian. Using `TextureFormat.BGRA8Unorm` avoids any CPU-side byte
  reordering — a zero-copy upload path.
- **`@group(0)` not `@group(1)`:** The render pipeline had no existing bind groups. Vendor
  API `GPUPipelineLayoutDescriptor.bindGroupLayouts` is `Array<GPUBindGroupLayout>`
  (non-nullable), so `null` for group 0 is impossible.
- **Constant UVs in emit shader:** Standard Prism quad UVs `(0,0)(1,0)(1,1)(0,1)` are
  hardcoded rather than uploaded to the GPU. This matches the CPU-side uv-generation
  output exactly for all Prism faces. Custom UV transforms will require a per-face UV
  buffer in a future slice.
- **`@interpolate(flat)` on textureIndex:** Required by WGSL for integer inter-stage
  variables. Ensures uniform control flow for `textureSample` — all fragments in a
  triangle see the provoking vertex's value.
- **Single texture slot (index 0):** This slice binds one texture at a time. Multi-texture
  / atlas support is deferred to `per-face-materials`.
- **`BitmapSource` only in GPU path:** `TextureSource.Resource` and `.Asset` require an
  Android `Context` for `BitmapFactory`. The GPU pipeline doesn't hold a context; resource
  loading will be wired through in the `sample-demo` slice. `BitmapSource` (procedural
  textures) works directly.

## Deviations from Plan

1. **TextureLoader not used in GpuFullPipeline.** The plan called for reusing
   `TextureLoader` from canvas-textures. However, `TextureLoader` is `internal` to
   `isometric-shader` — invisible to `isometric-webgpu` even with `implementation`
   dependency. Instead, `BitmapSource` is handled directly and Resource/Asset sources
   log a warning and fall back to checkerboard. This is pragmatic for this slice since
   the sample app uses procedural `BitmapSource` textures.

## Anything Deferred
- Multi-texture support (atlas / texture array) — per-face-materials slice
- `TextureSource.Resource`/`Asset` in GPU pipeline — requires Android Context plumbing
- Custom UV transforms on GPU — requires per-face UV buffer upload
- Texture compression (ETC2/ASTC)

## Known Risks / Caveats
- Vertex stride change (32→36) cascades to RenderCommandTriangulator, emit shader, and
  render pipeline. Any test or benchmark that hardcodes the old stride will need update.
- Single texture binding means all textured faces in a scene share one texture.
- `isometric-benchmark` module has a pre-existing dex merge failure unrelated to this change.

## Freshness Research
No external dependency changes. All vendor API calls verified against
`vendor/androidx-webgpu/` alpha04 snapshot.

## Review Fixes Applied

14 findings fixed from `07-review-webgpu-textures.md`:

| ID | Severity | File(s) | Fix |
|----|----------|---------|-----|
| R-1 | HIGH | `WebGpuSceneRenderer.kt` | `pipeline!!` → `checkNotNull` with message |
| R-2 | HIGH | `GpuFullPipeline.kt` | `identityHashCode`+`Any?` → `Bitmap?`+`===` |
| R-3 | HIGH | `GpuTextureBinder.kt`, `GpuFullPipeline.kt` | Layout passed as constructor param; `lateinit var textureBinder` |
| R-4 | HIGH | `GpuRenderPipeline.kt`, `GpuTextureBinder.kt` | `takeTextureBindGroupLayout()` transfers ownership; binder closes layout |
| R-5 | HIGH | `GpuTextureStore.kt` | `w*h*4` widened to `Long` arithmetic + `require` |
| R-6 | HIGH | `GpuTextureStore.kt` | `MAX_TEXTURE_DIMENSION = 4096` cap with `require` |
| R-7 | HIGH | `GpuTextureStore.kt` | `fallbackTextureView.close()` before texture destroy loop |
| R-8 | MED | `GpuFullPipeline.kt` | `releaseUploadedTexture()` in null-bitmap fallback path |
| R-9 | MED | `GpuFullPipeline.kt` | `uploadTexIndexBuffer` receives `faceCount` from caller |
| R-11 | MED | `TriangulateEmitShader.kt` | Real vertices first, degenerate fill after (−33% writes) |
| R-12 | MED | `GpuRenderPipeline.kt`, `GpuTextureStore.kt`, `GpuFullPipeline.kt` | `assertGpuThread()` on init paths |
| R-13 | MED | `GpuFullPipeline.kt` | Extracted `rebuildBindGroup(view)` helper (3 call sites) |
| R-14 | MED | `GpuFullPipeline.kt` | x2 growth factor for texIndex CPU + GPU buffers |
| R-15 | MED | `GpuFullPipeline.kt` | PerFace checks `faceMap` values + outer break guard |

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders webgpu-textures` — re-verify after review fixes
- **Option B:** `/compact` then Option A — recommended to clear implementation context
