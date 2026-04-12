---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: webgpu-textures
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-12T15:40:47Z"
metric-files-to-touch: 10
metric-step-count: 11
has-blockers: false
revision-count: 3
tags: [webgpu, texture, shader, wgsl]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-webgpu-textures.md
  siblings: [04-plan-material-types.md, 04-plan-uv-generation.md, 04-plan-canvas-textures.md, 04-plan-per-face-materials.md, 04-plan-sample-demo.md]
  implement: 05-implement-webgpu-textures.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders webgpu-textures"
---

# Plan: WebGPU Textured Rendering

## Goal

Wire texture sampling into the existing WebGPU render pipeline so that faces carrying
a `IsometricMaterial.Textured` render with their bitmap applied via UV-interpolated
`textureSample()` in the fragment shader. Non-textured faces take a zero-cost fast path
(`textureIndex == 0xFFFFFFFF`). A 2x2 checkerboard fallback GPU texture stands in for
any texture slot that has not been loaded yet.

## Dependency contract

This plan assumes (all confirmed shipped as of rev 2 auto-review):
- `material-types` slice has shipped: `RenderCommand` carries `material: MaterialData?`,
  `uvCoords: FloatArray?`, and `baseColor: IsoColor` (defaults to `color`).
  `isometric-compose` does NOT depend on `isometric-shader`.
  `isometric-shader` depends on `isometric-compose`. Overloaded `Shape(geometry, material)`
  composables live in `isometric-shader`.
- `uv-generation` slice has shipped: UV coordinates are populated for Prism faces on
  `RenderCommand.uvCoords` before `SceneDataPacker.packInto()` is called. Standard Prism
  quad UVs are `(0,0)(1,0)(1,1)(0,1)`.
- `canvas-textures` slice has shipped: `TextureCache`, `TextureLoader`,
  `TexturedCanvasDrawHook`, `ProvideTextureRendering` composable all exist in
  `isometric-shader`. The Canvas path draws textured faces via `BitmapShader` + affine
  `Matrix.setPolyToPoly`.
- `isometric-webgpu` must add `implementation(project(":isometric-shader"))` to its
  `build.gradle.kts` so it can cast `MaterialData?` to `IsometricMaterial` when interpreting
  render commands.

### Material resolution for this slice

| Material type | GPU behavior |
|---|---|
| `IsometricMaterial.Textured` | Upload bitmap, write `textureIndex = 0`, sample in fragment shader |
| `IsometricMaterial.FlatColor` | Write `textureIndex = NO_TEXTURE`, fragment shader returns `in.color` |
| `IsometricMaterial.PerFace` | Resolve `.default` sub-material: if `Textured` → texture path; otherwise → flat color. Full per-face-group resolution deferred to `per-face-materials` slice. |
| `null` (no material) | Write `textureIndex = NO_TEXTURE` |

## Assumptions about existing scaffolding (confirmed by code inspection, rev 2)

| What | Where | Status |
|------|-------|--------|
| UV slots in vertex buffer | `TriangulateEmitShader` bytes 24-31 (u32[6-7]), always `0u` | Exists, always zero |
| `textureIndex` in `FaceData` | `SceneDataPacker` offset 124, value `NO_TEXTURE = 0xFFFFFFFF` | Exists, always NO_TEXTURE |
| UV `@location(2)` in vertex shader | `IsometricVertexShader.WGSL` `in.uv` passthrough to `out.uv` | Exists |
| UV `@location(1)` in fragment shader | `IsometricFragmentShader.WGSL` `in: FragmentInput { uv }` declared | Exists, unused |
| Vertex stride | `RenderCommandTriangulator.BYTES_PER_VERTEX` = 32 bytes (8 x u32) | **Changes to 36 bytes** (9 x u32) — adds `textureIndex` at offset 32 |
| `TransformedFace` struct | 96 bytes; no UV fields | No UV there — UV lives in the vertex buffer emitted by M5 |
| Render pipeline layout | `GpuRenderPipeline` — implicit layout, no bind groups | Must switch to explicit layout with `@group(0)` for texture+sampler |
| `SceneDataPacker` color source | Uses `cmd.baseColor` (not `cmd.color`) since commit `bf07382` | Correct for GPU — avoids double-lighting |
| `SceneItem` type | Plain `class` (not `data class`) with `material`/`uvCoords` fields | Confirmed post canvas-textures fixes |

The vertex stride **does** change: from 32 to 36 bytes. The `textureIndex` (`u32`) is
appended at offset 32 so the fragment shader can branch on flat-color vs textured per
fragment. UV coordinates are already allocated at offset 24-31 — only the values change
from zero to real UVs.

The texture+sampler binding uses **`@group(0)`** (not `@group(1)`) because the render
pipeline currently has no bind groups at all. Compute pipelines (M3/M4/M5) use their own
separate pipeline layouts — there is no conflict.

---

## Step 0 — Add `isometric-shader` dependency to `isometric-webgpu`

**File to modify:** `isometric-webgpu/build.gradle.kts`

Add dependency so `SceneDataPacker` and `GpuFullPipeline` can cast `MaterialData?` to
`IsometricMaterial.Textured` and access bitmap data:

```kotlin
implementation(project(":isometric-shader"))
```

**Import needed in `SceneDataPacker.kt` and `GpuFullPipeline.kt`:**
```kotlin
import io.github.jayteealao.isometric.shader.IsometricMaterial
```

---

## Step 1 — Add `GpuTextureStore`: GPU texture upload helper

**File to create:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureStore.kt`

Create an `internal class GpuTextureStore(private val ctx: GpuContext) : AutoCloseable`.

### Responsibilities

1. **Checkerboard fallback texture** — created once in `init`:
   - 2x2 `BGRA8Unorm` texture with pixel pattern `[magenta, black, black, magenta]`.
   - Created with `TextureUsage.TextureBinding or TextureUsage.CopyDst`.
   - Written via `ctx.queue.writeTexture(...)` immediately after creation.
   - Pixel bytes in BGRA order: magenta = `[0xFF, 0x00, 0xFF, 0xFF]`, black = `[0x00, 0x00, 0x00, 0xFF]`.

2. **`uploadBitmap(bitmap: android.graphics.Bitmap): GPUTexture`** — uploads one bitmap:
   - `Bitmap.Config.ARGB_8888` on little-endian ARM/x86 stores bytes as BGRA in memory.
   - Use `TextureFormat.BGRA8Unorm` so the GPU interprets the native byte order correctly
     — **no CPU-side channel swizzle needed**.
   - Convert `Bitmap` to byte array via `copyPixelsToBuffer` into a direct `ByteBuffer`
     (native byte order).
   - Create GPU texture and upload via `writeTexture`.
   - Return the `GPUTexture`. Caller owns lifetime.

3. **`fallbackTexture: GPUTexture`** — exposes the checkerboard.
4. **`fallbackTextureView: GPUTextureView`** — a `createView()` of the fallback.
5. **`close()`** — calls `destroy()` then `close()` on all owned textures.

### Exact vendor API calls

```kotlin
// Create texture (vendor: GPUTextureDescriptor takes usage + size as required params)
device.createTexture(
    GPUTextureDescriptor(
        usage = TextureUsage.TextureBinding or TextureUsage.CopyDst,
        size  = GPUExtent3D(width = w, height = h, depthOrArrayLayers = 1),
        format = TextureFormat.BGRA8Unorm,
        mipLevelCount = 1,
        sampleCount   = 1,
        dimension     = TextureDimension._2D,
    )
)

// Upload pixels (vendor: writeTexture parameter order is destination, data, writeSize, dataLayout)
ctx.queue.writeTexture(
    destination = GPUTexelCopyTextureInfo(texture = gpuTex),   // mipLevel=0, origin default
    data        = pixelByteBuffer,
    writeSize   = GPUExtent3D(width = w, height = h, depthOrArrayLayers = 1),
    dataLayout  = GPUTexelCopyBufferLayout(bytesPerRow = w * 4, rowsPerImage = h),
)
```

**Vendor API note:** `writeTexture` parameter order is `(destination, data, writeSize,
dataLayout)` — `writeSize` comes before `dataLayout`, opposite of the JS WebGPU spec.

---

## Step 2 — Add `GpuTextureBinder`: sampler + bind group for `@group(0)`

**File to create:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureBinder.kt`

Create an `internal class GpuTextureBinder(private val ctx: GpuContext) : AutoCloseable`.

### Responsibilities

1. **Sampler** — created once in `init`:
   ```kotlin
   device.createSampler(
       GPUSamplerDescriptor(
           magFilter    = FilterMode.Linear,
           minFilter    = FilterMode.Linear,
           mipmapFilter = MipmapFilterMode.Nearest,
           addressModeU = AddressMode.ClampToEdge,
           addressModeV = AddressMode.ClampToEdge,
       )
   )
   ```
   No mipmaps (single-level textures); clamp to edge prevents atlas bleeding.

2. **`bindGroupLayout: GPUBindGroupLayout`** — created once, describes `@group(0)`:
   ```
   binding 0 — texture_2d<f32>, TextureSampleType.Float, visible = Fragment
   binding 1 — sampler (Filtering), visible = Fragment
   ```
   Using vendor `GPUBindGroupLayoutEntry` with `texture = GPUTextureBindingLayout(sampleType = TextureSampleType.Float, viewDimension = TextureViewDimension._2D)` and `sampler = GPUSamplerBindingLayout(type = SamplerBindingType.Filtering)`.

   **Note:** Vendor `GPUBindGroupLayoutEntry` defaults use `BindingNotUsed` sentinel values
   (not `null`). Only the field being activated (`texture` or `sampler`) needs to be set —
   the other sentinel defaults mean "this entry does not use that binding type."

3. **`buildBindGroup(textureView: GPUTextureView): GPUBindGroup`** — creates a bind group
   pairing the given `textureView` with the shared sampler:
   ```kotlin
   device.createBindGroup(
       GPUBindGroupDescriptor(
           layout  = bindGroupLayout,
           entries = arrayOf(
               GPUBindGroupEntry(binding = 0, textureView = textureView),
               GPUBindGroupEntry(binding = 1, sampler     = sampler),
           ),
       )
   )
   ```

4. **`close()`** — closes `bindGroupLayout`, `sampler`.

---

## Step 3 — Extend `GpuRenderPipeline` to declare `@group(0)` bind group layout

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuRenderPipeline.kt`

### Changes

Currently `GpuRenderPipeline` creates its pipeline with an implicit (`null`) pipeline
layout, which means Dawn auto-derives layouts. Texture binding requires an **explicit**
pipeline layout so `@group(0)` is formally declared.

1. Add a `textureBindGroupLayout: GPUBindGroupLayout` parameter to the constructor
   (passed from `GpuTextureBinder.bindGroupLayout`).
2. Create an explicit `GPUPipelineLayout`:
   ```kotlin
   val pipelineLayout = device.createPipelineLayout(
       GPUPipelineLayoutDescriptor(
           bindGroupLayouts = arrayOf(
               textureBindGroupLayout, // @group(0) — texture + sampler
           )
       )
   )
   ```
   The render pipeline has only one bind group (texture+sampler at group 0). Compute
   pipelines (M3/M4/M5) use entirely separate pipeline layouts — no conflict.
3. Pass `layout = pipelineLayout` to `GPURenderPipelineDescriptor`.
4. Close `pipelineLayout` in `close()`.

---

## Step 4 — Modify `IsometricFragmentShader`: add texture sampling with NO_TEXTURE fast path

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/IsometricFragmentShader.kt`

### New WGSL

```wgsl
@group(0) @binding(0) var diffuseTexture: texture_2d<f32>;
@group(0) @binding(1) var diffuseSampler: sampler;

struct FragmentInput {
    @location(0) color:        vec4<f32>,
    @location(1) uv:           vec2<f32>,
    @location(2) @interpolate(flat) textureIndex: u32,  // NO_TEXTURE = 0xFFFFFFFFu
}

@fragment
fn fragmentMain(in: FragmentInput) -> @location(0) vec4<f32> {
    if (in.textureIndex == 0xFFFFFFFFu) {
        // Fast path: flat color (non-textured face). Zero texture bandwidth.
        return in.color;
    }
    // Textured path: sample and multiply by tint color.
    let sampled = textureSample(diffuseTexture, diffuseSampler, in.uv);
    return sampled * in.color;
}
```

Key decisions:
- `@group(0)` (not `@group(1)`) — render pipeline has no other bind groups.
- `@interpolate(flat)` on `textureIndex` — integer attributes must use flat interpolation
  in WGSL. This ensures all fragments in a triangle see the same `textureIndex` value
  (the provoking vertex's value). This also guarantees uniform control flow for the
  `textureSample` call.
- `sampled * in.color` lets the lit base color act as a tint multiplier (matches the
  Canvas `BitmapShader` + color tint pattern).
- Single texture slot — no indirection needed. Multi-texture / atlas is a future slice.

### Vertex layout change

`FragmentInput` gains `@location(2) textureIndex: u32`. This requires a matching
`@location(2)` vertex attribute in `IsometricVertexShader` (see Step 5) and a new
vertex attribute declaration in `GpuRenderPipeline` (see Step 6).

**Note:** The existing `@location(2) uv: vec2<f32>` in `IsometricVertexShader` becomes
`@location(2)` for UV and `@location(3)` for textureIndex. The fragment input locations
are renumbered accordingly.

---

## Step 5 — Modify `IsometricVertexShader`: pass `textureIndex` through

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/IsometricVertexShader.kt`

### New WGSL

```wgsl
struct VertexInput {
    @location(0) position:     vec2<f32>,
    @location(1) color:        vec4<f32>,
    @location(2) uv:           vec2<f32>,
    @location(3) textureIndex: u32,
}

struct VertexOutput {
    @builtin(position) clipPosition: vec4<f32>,
    @location(0) color:        vec4<f32>,
    @location(1) uv:           vec2<f32>,
    @location(2) @interpolate(flat) textureIndex: u32,
}

@vertex
fn vertexMain(in: VertexInput) -> VertexOutput {
    var out: VertexOutput;
    out.clipPosition = vec4<f32>(in.position, 0.0, 1.0);
    out.color        = in.color;
    out.uv           = in.uv;
    out.textureIndex = in.textureIndex;
    return out;
}
```

Note: `@location(2) @interpolate(flat) textureIndex: u32` in `VertexOutput` must match
`FragmentInput` `@location(2) @interpolate(flat) textureIndex: u32`.

---

## Step 6 — Update vertex buffer stride and attributes in `GpuRenderPipeline`

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuRenderPipeline.kt`
**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/triangulation/RenderCommandTriangulator.kt`

### Vertex buffer layout change

The vertex buffer gains a 4th attribute: `textureIndex` as `u32` at offset 32.
The stride grows from 32 bytes (8 x u32) to 36 bytes (9 x u32).

New layout:
```
u32 offset  bytes  field                          shader location
     0-1      8    position  vec2<f32>             location 0
     2-5     16    color     vec4<f32>             location 1
     6-7      8    uv        vec2<f32>             location 2
       8       4    textureIndex u32                location 3
             --
Total: 36 bytes (9 x u32)
```

Update `RenderCommandTriangulator`:
- `FLOATS_PER_VERTEX` from 8 to 9 (rename to `U32S_PER_VERTEX` since slot 8 is u32 not float)
- `BYTES_PER_VERTEX` from 32 to 36

Add the new attribute to `GPUVertexBufferLayout` in `GpuRenderPipeline`:
```kotlin
GPUVertexAttribute(
    format         = VertexFormat.Uint32,
    offset         = 32L,
    shaderLocation = 3,
),
```

---

## Step 7 — Update `TriangulateEmitShader`: emit real UVs and textureIndex

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/TriangulateEmitShader.kt`

### Summary of changes

1. **`BYTES_PER_VERTEX`** — delegates to `RenderCommandTriangulator.BYTES_PER_VERTEX`
   which is now 36. The constant itself is already a delegation, so no change here;
   the update flows automatically once Step 6 is done.

2. **`writeVertex` function** — gains three new parameters `u: f32, v: f32, texIdx: u32`
   and writes 9 u32 per vertex instead of 8:
   ```wgsl
   fn writeVertex(base: u32, x: f32, y: f32, r: f32, g: f32, b: f32, a: f32,
                  u: f32, v: f32, texIdx: u32) {
       vertices[base + 0u] = bitcast<u32>(x);
       vertices[base + 1u] = bitcast<u32>(y);
       vertices[base + 2u] = bitcast<u32>(r);
       vertices[base + 3u] = bitcast<u32>(g);
       vertices[base + 4u] = bitcast<u32>(b);
       vertices[base + 5u] = bitcast<u32>(a);
       vertices[base + 6u] = bitcast<u32>(u);
       vertices[base + 7u] = bitcast<u32>(v);
       vertices[base + 8u] = texIdx;
   }
   ```
   The stride constant `8u` in all per-vertex index arithmetic (`* 8u`) must become `9u`.

3. **UV values: constant per-vertex for standard Prism quads** — the `uv-generation` slice
   generates standard `(0,0)(1,0)(1,1)(0,1)` UV coords for Prism faces (stored on
   `RenderCommand.uvCoords` on the CPU). Rather than uploading a per-face UV buffer to the
   GPU, the emit shader uses these same values as **constants** for this slice:
   - Fan triangulation pivot = v0 (UV = 0,0)
   - v1 = (1,0); v2 = (1,1); v3 = (0,1)
   - For triangles beyond the quad (v4, v5): degenerate UV (0.5, 0.5).

   This produces identical results to the CPU-side UVs for all Prism faces. Custom UV
   transforms (rotation, scaling) will require a GPU-side UV buffer in a future slice.

4. **`textureIndex` passthrough** — the `TransformedFace` struct does not carry
   `textureIndex` (it is an output of M3, not M5). The emit shader reads `textureIndex`
   from a **compact per-face index buffer** (separate from FaceData):
   - Add `@group(0) @binding(4) var<storage, read> sceneTexIndices: array<u32>` to
     the emit shader. This buffer is a compact array of `u32` values indexed by
     `originalIndex`, holding only the `textureIndex` field from each face.
   - `GpuTriangulateEmitPipeline.ensureBuffers` gets a new `texIndexBuffer: GPUBuffer`
     parameter, bound at binding 4.

5. **Degenerate vertex UVs** — all `writeVertex` calls for degenerate (zero-area)
   sentinel vertices use `u=0.0, v=0.0, texIdx=0xFFFFFFFFu`.

6. **Constant string update** — the inline comment documenting the vertex layout in
   `TriangulateEmitShader` must be updated from 8 to 9 u32 per vertex.

### UV layout for standard isometric quads (fan triangulation order)

```
Quad vertices:  v0(0,0)  v1(1,0)  v2(1,1)  v3(0,1)
Triangle 0: (v0, v1, v2) -> UV: (0,0), (1,0), (1,1)
Triangle 1: (v0, v2, v3) -> UV: (0,0), (1,1), (0,1)
```

This is hardcoded in the emit shader for `vertexCount == 4`. For `vertexCount == 3`,
Triangle 0 only: `(0,0), (1,0), (0.5,1)`. For `vertexCount >= 5`, additional
triangles use UV (0,0) for v0 and equal-arc positions for subsequent vertices.

---

## Step 8 — Update `SceneDataPacker`: write real `textureIndex`

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/SceneDataPacker.kt`

### Changes

1. **`textureIndex` field** (offset 124): change from always writing `NO_TEXTURE` to
   reading `cmd.material`:
   ```kotlin
   val texIndex = when (val m = cmd.material) {
       is IsometricMaterial.Textured -> 0  // single texture slot for this slice
       is IsometricMaterial.PerFace -> {
           val sub = m.default
           if (sub is IsometricMaterial.Textured) 0 else SceneDataLayout.NO_TEXTURE
       }
       else -> SceneDataLayout.NO_TEXTURE
   }
   buffer.putInt(texIndex)
   ```
   For this slice, the single bound texture is always slot 0. Write `0` when the
   material resolves to `IsometricMaterial.Textured`, `NO_TEXTURE` otherwise.

   **Note:** `SceneDataPacker` already uses `cmd.baseColor` (not `cmd.color`) for the
   face color fields — this is correct for GPU rendering (avoids double-lighting).

2. **`FaceData` struct** does not change size (144 bytes). The UV coordinates do NOT
   live in `FaceData` — they are emitted per-vertex in M5 (see Step 7). No stride
   change to the scene-data buffer.

3. **Compact `texIndexBuffer`** — `SceneDataPacker` is also responsible for producing
   the compact `u32` array of texture indices used by `GpuTriangulateEmitPipeline`
   (Step 7 binding 4). Add `fun packTexIndicesInto(commands: List<RenderCommand>, buffer: ByteBuffer)`:
   writes one `u32` per command (4 bytes each). Buffer size = `commands.size * 4`.

---

## Step 9 — Wire everything together in `GpuFullPipeline` and `WebGpuSceneRenderer`

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuFullPipeline.kt`
**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/WebGpuSceneRenderer.kt`

### `GpuFullPipeline` additions

1. Hold a `GpuTextureStore` and `GpuTextureBinder`.
2. On `upload(scene, w, h)`:
   a. Scan `scene.commands` for `IsometricMaterial.Textured` entries (including inside
      `PerFace.default`). If none, use the fallback checkerboard texture and write
      `NO_TEXTURE` to all slots.
   b. For the first (and currently only) `IsometricMaterial.Textured` found, resolve its
      `TextureSource` to a `Bitmap`:
      - `TextureSource.BitmapSource` — use directly
      - `TextureSource.Resource` / `TextureSource.Asset` — load via `TextureLoader`
        (reuse from canvas-textures slice, or use `BitmapFactory` inline)
   c. Upload bitmap via `textureStore.uploadBitmap(bitmap)`. Cache the result keyed by
      bitmap identity to avoid re-uploading on every frame.
   d. Build `textureBinder.buildBindGroup(uploadedTexture.createView())`.
   e. Pack the compact tex-index buffer via `SceneDataPacker.packTexIndicesInto(...)`.
   f. Upload the compact tex-index buffer to GPU via `ctx.queue.writeBuffer(...)`.
3. Expose `val textureBindGroup: GPUBindGroup` for the render pass.
4. On `clearScene()`, revert to the fallback bind group.
5. Close `textureStore`, `textureBinder`, uploaded textures in `close()`.

### `WebGpuSceneRenderer.drawFrame` additions

After `pass.setPipeline(pipeline.pipeline)` and before `pass.drawIndirect(...)`:
```kotlin
pass.setBindGroup(0, gp.textureBindGroup)
```

This is the only change to `WebGpuSceneRenderer`.

---

## Step 10 — Update `GpuTriangulateEmitPipeline`: add `texIndexBuffer` binding

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuTriangulateEmitPipeline.kt`

### Changes

1. **Bind group layout** — add a 5th entry at binding 4:
   ```kotlin
   GPUBindGroupLayoutEntry(
       binding    = 4,
       visibility = ShaderStage.Compute,
       buffer     = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
   )
   ```

2. **`ensureBuffers`** — add parameter `texIndexBuffer: GPUBuffer`. Include it in the
   bind group at entry binding 4.

3. **Vertex buffer size** — grows from `paddedCount * 12 * 32` to
   `paddedCount * 12 * 36` (reflecting the 36-byte stride). This is derived
   automatically from `BYTES_PER_VERTEX` once Step 6 is done.

4. Change-detection state: add `lastTexIndexBuffer: GPUBuffer?` and include it in the
   `same` guard.

---

## File change summary

| File | Action | Reason |
|------|--------|--------|
| `texture/GpuTextureStore.kt` | Create | GPU texture upload (BGRA8Unorm) + checkerboard fallback |
| `texture/GpuTextureBinder.kt` | Create | Sampler + `@group(0)` bind group factory |
| `shader/IsometricFragmentShader.kt` | Modify | `textureSample` + NO_TEXTURE fast path at `@group(0)` |
| `shader/IsometricVertexShader.kt` | Modify | `textureIndex` u32 passthrough with `@interpolate(flat)` |
| `shader/TriangulateEmitShader.kt` | Modify | Real UVs + `textureIndex`, 9-u32 stride |
| `pipeline/GpuRenderPipeline.kt` | Modify | Explicit layout with `@group(0)`, new vertex attribute |
| `pipeline/GpuTriangulateEmitPipeline.kt` | Modify | Add `texIndexBuffer` binding at binding 4 |
| `pipeline/SceneDataPacker.kt` | Modify | Write real `textureIndex` + PerFace resolution, compact tex-index buf |
| `pipeline/GpuFullPipeline.kt` | Modify | Wire store/binder, upload logic, expose bind group |
| `WebGpuSceneRenderer.kt` | Modify | `pass.setBindGroup(0, ...)` |
| `triangulation/RenderCommandTriangulator.kt` | Modify | `BYTES_PER_VERTEX` 32->36 |

Total: 9 existing files modified, 2 new files.

---

## Risk register

| Risk | Likelihood | Mitigation |
|------|-----------|-----------|
| Vertex stride change breaks existing benchmarks | Medium | Stride is now 36 bytes; all benchmark vertex readback tests must be updated to match. The change is mechanical. |
| Adreno TDR from new storage binding in emit shader | Low | Binding 4 is read-only storage — no atomics, no scatter writes. The existing TDR fix (fixed-stride, no atomicAdd) is unaffected. |
| `writeTexture` pixel format mismatch (Bitmap ARGB vs RGBA) | **Mitigated** | Use `TextureFormat.BGRA8Unorm` to match Android's native `ARGB_8888` byte order on little-endian (BGRA in memory). No CPU-side swizzle needed. |
| WebGPU Dawn alpha API changes to `writeTexture` parameters | Low | Vendor source confirmed: `queue.writeTexture(GPUTexelCopyTextureInfo, ByteBuffer, GPUExtent3D, GPUTexelCopyBufferLayout)` — exact signature verified in `GPUQueue.kt`. Note: param order is `(dest, data, writeSize, dataLayout)` — `writeSize` before `dataLayout`, opposite of JS spec. |
| `textureSample` in non-uniform control flow | **Mitigated** | `textureIndex` uses `@interpolate(flat)` — all fragments in a triangle see the provoking vertex's value. The branch is uniform within each triangle. |
| Single-texture limitation: scenes with multiple textures only show one | Known | Documented in scope. Atlas / texture array deferred to per-face-materials slice. |

---

## NO_TEXTURE performance guarantee

The `if (in.textureIndex == 0xFFFFFFFFu)` branch in the fragment shader returns
immediately without issuing any texture memory fetch. Benchmarks using only flat-colored
shapes will not see any texture bandwidth cost. The existing benchmark suite can
validate this by asserting that GPU render time does not increase for non-textured
scenes after this slice lands.

---

## Checkerboard fallback specification

```
2x2 BGRA8Unorm texture:
  pixel (0,0): BGRA = (255, 0, 255, 255)   -- magenta
  pixel (1,0): BGRA = (0,   0, 0,   255)   -- black
  pixel (0,1): BGRA = (0,   0, 0,   255)   -- black
  pixel (1,1): BGRA = (255, 0, 255, 255)   -- magenta
```

Rendered with `AddressMode.ClampToEdge` and `FilterMode.Linear` (sampler from Step 2),
this produces a smooth magenta-to-black gradient — deliberately unpleasant to signal
"texture not loaded" in development builds.

---

## Sequence diagram (per-frame, textured scene)

```
CPU (uploadScene)                    GPU M3             GPU M5             GPU Render
   |                                    |                  |                   |
   +- packInto (FaceData w/ texIdx) --> scene buffer       |                   |
   +- packTexIndicesInto ----------------------------------------> texIndex buf |
   +- writeTexture (bitmap pixels) ------------------------------------------------> GPU texture
   |                                    |                  |                   |
   +- (frame) dispatch M3 ------------>                    |                   |
   |                                    +- TransformedFace buf -->             |
   +- (frame) dispatch M5 ---------------------------------------->           |
   |                                    |  reads texIndex  +- vertexBuf(uv+texIdx) ->|
   +- (frame) render pass ---------------------------------------------------------->|
   |                                    |                  |   setBindGroup(0, textureBindGroup)
   |                                    |                  |   setVertexBuffer + drawIndirect
   |                                    |                  |   fragmentMain: textureSample
```

## Test / Verification Plan

### Automated checks
- **Build:** `./gradlew build test apiCheck` — must pass (stride change may require apiDump)
- **Existing benchmarks:** Run at N=100 flat-color scene — no performance regression
  (`WebGpuSampleActivity` → Smoke sample → Full WebGpu mode → observe frame time overlay)
- **Unit tests:** `SceneDataPacker` texture index serialization (pure logic, no GPU)
- **apiDump:** Stride change in `RenderCommandTriangulator` and new constructor params in
  `GpuRenderPipeline` may require `./gradlew apiDump` if `apiCheck` fails

### Interactive verification (human-in-the-loop)

**Prerequisite — sample app modification:**
The current `TexturedSample` in `ComposeActivity` uses default Canvas mode. WebGPU
verification requires textured content in a scene that supports `RenderMode.WebGpu`.
Two options (implementer chooses):
- **Option A:** Add a "Textured" tab to `WebGpuSampleActivity` that wraps content in
  `ProvideTextureRendering { ... }` with the same Prisms as `TexturedSample`, plus
  the existing render mode toggle (Canvas / Canvas+GPU / Full WebGpu).
- **Option B:** Add a render mode toggle to the existing `TexturedSample` in
  `ComposeActivity` (like `WebGpuSmokeSample`'s 3-button row).

Either way, the sample must show the same 3 Prisms (textured, flat-color, textured)
renderable in both Canvas and WebGPU modes for side-by-side comparison.

#### Check 1: WebGPU textured rendering (AC6)
- **What to verify:** Textured Prism renders correctly in Full WebGPU mode — texture
  visible on faces, correct UV mapping, no visual artifacts, no crashes
- **Platform & tool:** Android (Samsung SM-F956B), adb screencap + uiautomator
- **Steps:**
  1. `./gradlew :app:installDebug`
  2. Launch the activity with the textured WebGPU sample
  3. Switch to Full WebGpu render mode via the radio button/tab
  4. Wait for WebGPU initialization (status indicator turns green)
  5. Capture screenshot: `adb shell screencap -p /sdcard/webgpu-tex.png && adb pull /sdcard/webgpu-tex.png`
- **Pass criteria:**
  - Left prism: checkerboard texture visible on all 3 faces (top, front, side)
  - Center prism: flat blue (no texture, backward compat)
  - Right prism: same checkerboard as left (cache reuse)
  - No magenta/black 2x2 fallback on faces that should be textured
  - No GPU crash, no black screen, no artifacts

#### Check 2: Canvas vs WebGPU visual parity (AC6 cross-mode)
- **What to verify:** WebGPU texture output matches Canvas texture output
- **Steps:**
  1. Switch to Canvas mode → screenshot: `adb shell screencap -p /sdcard/canvas-tex.png`
  2. Switch to Full WebGpu mode → screenshot: `adb shell screencap -p /sdcard/webgpu-tex.png`
  3. Pull both: `adb pull /sdcard/canvas-tex.png && adb pull /sdcard/webgpu-tex.png`
  4. Compare: texture should appear on the same faces with the same orientation in both modes
- **Pass criteria:** Texture coverage and orientation match between Canvas and WebGPU.
  Minor color differences (due to BGRA vs RGBA rendering pipelines or lighting differences)
  are acceptable. Gross differences (wrong faces textured, flipped UVs, missing texture)
  are failures.

#### Check 3: Mixed textured + flat-color scene (AC acceptance criteria 3)
- **What to verify:** Non-textured shapes render as flat color, textured shapes show texture
- **Steps:** Same scene as Check 1 — the center prism (flat blue) should be unaffected
  by the texture pipeline changes
- **Pass criteria:** Center prism is solid blue, not textured, not magenta checkerboard

#### Check 4: NO_TEXTURE performance (AC acceptance criteria 4)
- **What to verify:** Non-textured scenes have no performance regression
- **Steps:**
  1. Open `WebGpuSampleActivity` → Dense Grid (N=100, flat colors only)
  2. Switch to Full WebGpu mode
  3. Observe frame time in the benchmark overlay (or add `Log.d` for `prepareMs`/`renderMs`)
  4. Compare with pre-change baseline (from existing benchmarks)
- **Pass criteria:** Frame time at N=100 does not increase by more than 10% vs baseline.
  The `textureIndex == NO_TEXTURE` fast path should keep GPU render time unchanged.

### Evidence storage
All screenshots go to `.ai/workflows/texture-material-shaders/verify-evidence/`
with descriptive names: `webgpu-tex-check1.png`, `canvas-tex-parity.png`, etc.

## Freshness Research

- **Vendor API verified (2026-04-12):** All WebGPU API signatures confirmed against
  `vendor/androidx-webgpu/` snapshot (alpha04). Key finding: `@IntDef` annotations (not
  Kotlin enums) for `TextureUsage`, `FilterMode`, `TextureSampleType`, etc.
- **`BGRA8Unorm` format:** Standard WebGPU texture format. Matches Android `ARGB_8888`
  native byte order on little-endian without CPU swizzle.

## Revision History

### 2026-04-11 — Cohesion Review (rev 1)
- Mode: Review-All (cohesion check after material-types dependency inversion)
- Issues found: 4 (1 HIGH, 2 MED, 1 LOW)
  1. **HIGH:** Missing `isometric-shader` dependency step for `isometric-webgpu/build.gradle.kts`.
     Fix: added Step 0 to declare the dependency.
  2. **MED:** All references to `Material.Textured` should be `IsometricMaterial.Textured`.
     Fix: replaced all 6 occurrences.
  3. **MED:** `SceneDataPacker` and `GpuFullPipeline` need shader imports, not documented.
     Fix: added import notes to Step 0.
  4. **LOW:** Dependency contract said `material: Material?` instead of `material: MaterialData?`.
     Fix: updated dependency contract section.

### 2026-04-12 — Auto-Review post canvas-textures (rev 2)
- Mode: Auto-Review (codebase re-inspection after 3 sibling slices implemented)
- Issues found: 6 (2 HIGH, 3 MED, 1 LOW)
  1. **HIGH:** Stride contradiction — assumptions table said "NO stride change" but Steps 4-6
     added `textureIndex` vertex attribute requiring 32->36 bytes. Fix: updated assumptions
     table to reflect the stride change (36 bytes). The stride DOES change.
  2. **HIGH:** `@group(1)` with null group 0 is invalid — `GPUPipelineLayoutDescriptor.
     bindGroupLayouts` is `Array<GPUBindGroupLayout>` (non-nullable). Fix: changed all
     `@group(1)` references to `@group(0)`. Render pipeline has no other bind groups.
  3. **MED:** UV source now known — plan pre-dated uv-generation. UVs on `RenderCommand.
     uvCoords` are standard `(0,0)(1,0)(1,1)(0,1)` for Prism quads. Constant UVs in
     emit shader produce identical results. Fix: documented simplification, noted future
     GPU-side UV buffer for custom transforms.
  4. **MED:** `PerFace` material not addressed — plan only handled `Textured`/`FlatColor`.
     Fix: added material resolution table and `PerFace.default` unwrapping to Step 8.
  5. **MED:** BGRA pixel format mitigation incomplete — risk noted but no solution. Fix:
     changed `TextureFormat.RGBA8Unorm` to `TextureFormat.BGRA8Unorm` throughout. Android
     `ARGB_8888` on little-endian = BGRA in memory. No CPU swizzle needed.
  6. **LOW:** `baseColor` not referenced — `SceneDataPacker` uses `cmd.baseColor` since
     `bf07382`. Fix: noted in Step 8 and dependency contract.
- Additional: Added `@interpolate(flat)` to `textureIndex` in vertex/fragment shaders
  (required by WGSL for integer inter-stage variables). Added Test/Verification Plan
  section. Added Freshness Research with vendor API verification results.

### 2026-04-12 — Directed Fix: visual verification plan (rev 3)
- Mode: Directed Fix
- Feedback: "is there a visual verification plan"
- What was changed: Expanded `## Test / Verification Plan` from a brief 7-line section
  to a detailed 4-check verification plan covering:
  1. WebGPU textured rendering (AC6) — step-by-step adb screencap flow
  2. Canvas vs WebGPU visual parity — side-by-side screenshot comparison
  3. Mixed textured + flat-color scene — regression check
  4. NO_TEXTURE performance — flat-color scene benchmark comparison
  Added prerequisite noting the sample app needs a textured WebGPU scene (either via
  `WebGpuSampleActivity` tab or render mode toggle on `TexturedSample`). Added evidence
  storage location. Modeled on the canvas-textures verification experience (uiautomator,
  device screenshots, evidence directory).

## Recommended Next Stage
- **Option A (default):** `/wf-implement texture-material-shaders webgpu-textures` — plan is current and execution-ready
- **Option B:** `/compact` then Option A — recommended to clear plan review context
