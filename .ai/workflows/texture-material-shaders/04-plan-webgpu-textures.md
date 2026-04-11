---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: webgpu-textures
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-11T22:49:12Z"
metric-files-to-touch: 10
metric-step-count: 11
has-blockers: false
revision-count: 1
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
(`textureIndex == 0xFFFFFFFF`). A 2×2 checkerboard fallback GPU texture stands in for
any texture slot that has not been loaded yet.

## Dependency contract

This plan assumes:
- `material-types` slice has shipped (rev 3): `RenderCommand` carries `material: MaterialData?`
  and `uvCoords: FloatArray?`. `isometric-compose` does NOT depend on `isometric-shader`.
  `isometric-shader` depends on `isometric-compose`. Overloaded `Shape(geometry, material)`
  composables live in `isometric-shader`.
- `uv-generation` slice has shipped: UV coordinates are populated for Prism faces on
  `RenderCommand.uvCoords` before `SceneDataPacker.packInto()` is called.
- `isometric-webgpu` must add `implementation(project(":isometric-shader"))` to its
  `build.gradle.kts` so it can cast `MaterialData?` to `IsometricMaterial` when interpreting
  render commands.

If either dependency is absent at implementation time, the implementer must shim the
missing fields before proceeding (temporary `null` / zero-UV stubs are acceptable).

## Assumptions about existing scaffolding (confirmed by code inspection)

| What | Where | Status |
|------|-------|--------|
| UV slots in vertex buffer | `TriangulateEmitShader` bytes 24-31 (u32[6-7]), always `0u` | Exists, always zero |
| `textureIndex` in `FaceData` | `SceneDataPacker` offset 124, value `NO_TEXTURE = 0xFFFFFFFF` | Exists, always NO_TEXTURE |
| UV `@location(2)` in vertex shader | `IsometricVertexShader.WGSL` `in.uv` passthrough to `out.uv` | Exists |
| UV `@location(1)` in fragment shader | `IsometricFragmentShader.WGSL` `in: FragmentInput { uv }` declared | Exists, unused |
| Vertex stride | `RenderCommandTriangulator.BYTES_PER_VERTEX` = 32 bytes (8 × u32) | Unchanged — NO stride change in this slice |
| `TransformedFace` struct | 96 bytes; no UV fields | No UV there — UV lives in the vertex buffer emitted by M5 |

The vertex stride does **not** change. UV coordinates are already allocated in the vertex
buffer. The only work is to write real values instead of zeros, bind a texture + sampler
to `@group(1)`, and teach the fragment shader to use them.

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
   - 2×2 `RGBA8Unorm` texture with pixel pattern `[magenta, black, black, magenta]`
     (0xFF_FF_00_FF, 0xFF_00_00_00) — visually obvious for "texture not ready".
   - Created with `TextureUsage.TextureBinding or TextureUsage.CopyDst`.
   - Written via `ctx.queue.writeTexture(...)` immediately after creation.

2. **`uploadBitmap(bitmap: android.graphics.Bitmap): GPUTexture`** — uploads one bitmap:
   - Convert `Bitmap` to RGBA byte array via `copyPixelsToBuffer` into a direct
     `ByteBuffer` (native byte order).
   - `device.createTexture(GPUTextureDescriptor(size = GPUExtent3D(w, h, 1), format = TextureFormat.RGBA8Unorm, usage = TextureUsage.TextureBinding or TextureUsage.CopyDst, mipLevelCount = 1, sampleCount = 1))`.
   - `ctx.queue.writeTexture(GPUTexelCopyTextureInfo(texture = gpuTex), byteBuffer, GPUExtent3D(w, h, 1), GPUTexelCopyBufferLayout(bytesPerRow = w * 4))`.
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
        format = TextureFormat.RGBA8Unorm,
        mipLevelCount = 1,
        sampleCount   = 1,
        dimension     = TextureDimension._2D,
    )
)

// Upload pixels (vendor: writeTexture signature confirmed in GPUQueue.kt)
ctx.queue.writeTexture(
    destination = GPUTexelCopyTextureInfo(texture = gpuTex),   // mipLevel=0, origin default
    data        = pixelByteBuffer,
    writeSize   = GPUExtent3D(width = w, height = h, depthOrArrayLayers = 1),
    dataLayout  = GPUTexelCopyBufferLayout(bytesPerRow = w * 4, rowsPerImage = h),
)
```

---

## Step 2 — Add `GpuTextureBinder`: sampler + bind group for `@group(1)`

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

2. **`bindGroupLayout: GPUBindGroupLayout`** — created once, describes `@group(1)`:
   ```
   binding 0 — texture_2d<f32>, TextureSampleType.Float, visible = Fragment
   binding 1 — sampler (Filtering), visible = Fragment
   ```
   Using vendor `GPUBindGroupLayoutEntry` with `texture = GPUTextureBindingLayout(sampleType = TextureSampleType.Float, viewDimension = TextureViewDimension._2D)` and `sampler = GPUSamplerBindingLayout(type = SamplerBindingType.Filtering)`.

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

## Step 3 — Extend `GpuRenderPipeline` to declare `@group(1)` bind group layout

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuRenderPipeline.kt`

### Changes

Currently `GpuRenderPipeline` creates its pipeline with an implicit (`null`) pipeline
layout, which means the browser/Dawn auto-derives layouts. Texture binding requires an
**explicit** pipeline layout so `@group(1)` is formally declared.

1. Add a `GpuTextureBinder` parameter to the constructor (or accept its
   `bindGroupLayout` directly).
2. Create an explicit `GPUPipelineLayout`:
   ```kotlin
   val pipelineLayout = device.createPipelineLayout(
       GPUPipelineLayoutDescriptor(
           bindGroupLayouts = arrayOf(
               null,                          // @group(0) — auto (no bindings in render pass)
               textureBinder.bindGroupLayout, // @group(1) — texture + sampler
           )
       )
   )
   ```
   Note: the render pipeline itself has **no** `@group(0)` bindings — compute passes own
   those. The render pipeline only consumes the vertex buffer written by M5 and the
   texture/sampler in `@group(1)`.
3. Pass `layout = pipelineLayout` to `GPURenderPipelineDescriptor`.
4. Close `pipelineLayout` in `close()`.

---

## Step 4 — Modify `IsometricFragmentShader`: add texture sampling with NO_TEXTURE fast path

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/IsometricFragmentShader.kt`

### New WGSL

```wgsl
@group(1) @binding(0) var diffuseTexture: texture_2d<f32>;
@group(1) @binding(1) var diffuseSampler: sampler;

struct FragmentInput {
    @location(0) color:        vec4<f32>,
    @location(1) uv:           vec2<f32>,
    @location(2) textureIndex: u32,        // NO_TEXTURE = 0xFFFFFFFFu
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
- `textureSample` is only called inside the `else` branch (uniform control flow for
  WGSL spec compliance — all fragments that reach this branch do so unconditionally,
  since `textureIndex` is a vertex attribute interpolated across the triangle).
- `sampled * in.color` lets the lit base color act as a tint multiplier (matches the
  Canvas `BitmapShader` + color tint pattern).
- Single texture slot — no indirection needed. Multi-texture / atlas is a future slice.

### Vertex layout change

`FragmentInput` gains `@location(2) textureIndex: u32`. This requires a matching
`@location(2)` vertex attribute in `IsometricVertexShader` (see Step 5) and a new
vertex attribute declaration in `GpuRenderPipeline` (see Step 3/6).

---

## Step 5 — Modify `IsometricVertexShader`: pass `textureIndex` through

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/IsometricVertexShader.kt`

### New WGSL

```wgsl
struct VertexInput {
    @location(0) position:    vec2<f32>,
    @location(1) color:       vec4<f32>,
    @location(2) uv:          vec2<f32>,
    @location(3) textureIndex: u32,
}

struct VertexOutput {
    @builtin(position) clipPosition: vec4<f32>,
    @location(0) color:        vec4<f32>,
    @location(1) uv:           vec2<f32>,
    @location(2) textureIndex: u32,
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

Note: `@location(2) textureIndex: u32` in `VertexOutput` must match `FragmentInput`
`@location(2) textureIndex: u32`.

---

## Step 6 — Update vertex buffer stride and attributes in `GpuRenderPipeline`

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuRenderPipeline.kt`
**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/triangulation/RenderCommandTriangulator.kt`

### Vertex buffer layout change

The vertex buffer needs a 4th attribute: `textureIndex` as `u32` at offset 32.
The stride grows from 32 bytes (8 × u32) to 36 bytes (9 × u32).

New layout:
```
u32 offset  bytes  field                          shader location
     0-1      8    position  vec2<f32>             location 0
     2-5     16    color     vec4<f32>             location 1
     6-7      8    uv        vec2<f32>             location 2
       8       4    textureIndex u32                location 3
             ──
Total: 36 bytes (9 × u32)
```

Update `RenderCommandTriangulator.BYTES_PER_VERTEX` from 32 to 36 and
`BYTES_PER_VERTEX`'s companion constant comment. Add the new attribute to
`GPUVertexBufferLayout`:
```kotlin
GPUVertexAttribute(
    format        = VertexFormat.Uint32,
    offset        = 32L,
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

2. **`writeVertex` function** — gains two new parameters `u: f32, v: f32, texIdx: u32`
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

3. **`TransformedFace` — UV passthrough** — the `TransformedFace` struct (96 bytes)
   currently has no UV slots. Since UVs are per-vertex (not per-face), they cannot
   be carried there without a stride change. Instead, the UV values for the 4 standard
   isometric quad vertices are **constants in the emit shader** for this slice, selected
   by a face-vertex assignment table:
   - Fan triangulation pivot = v0 (UV = 0,0)
   - v1 = (1,0); v2 = (1,1); v3 = (0,1)
   - For triangles beyond the quad (v4, v5) use the same centripetal approach.

   This matches the isometric quad convention used by the Canvas backend
   (`Matrix.setPolyToPoly` with corners `(0,0)(1,0)(1,1)(0,1)`). UV generation for
   non-quad faces (triangles, pentagons, hexagons) uses barycentric allocation:
   all vertices receive UV (0.5, 0.5) as a safe degenerate fallback until a future
   slice upgrades the UV generation path.

   **Alternatively**, if the `uv-generation` slice has populated `FaceData` with per-vertex
   UV coords (stored in the padding fields or a separate buffer), read them here. This
   plan assumes the simpler constant-UV path for this slice, deferring GPU-side UV
   lookup to a follow-up.

4. **`textureIndex` passthrough** — the `TransformedFace` struct does not carry
   `textureIndex` (it is an output of M3, not M5). The emit shader must read
   `textureIndex` from the **original `FaceData`** (via a second storage binding) or
   from a separate per-face index buffer. Choose the simpler second binding approach:
   - Add `@group(0) @binding(4) var<storage, read> sceneTexIndices: array<u32>` to
     the emit shader. This buffer is a compact array of `u32` values indexed by
     `originalIndex`, holding only the `textureIndex` field from each `FaceData`.
   - `GpuTriangulateEmitPipeline.ensureBuffers` gets a new `texIndexBuffer: GPUBuffer`
     parameter, bound at binding 4.

5. **Degenerate vertex UVs** — all `writeVertex` calls for degenerate (zero-area)
   sentinel vertices use `u=0.0, v=0.0, texIdx=0xFFFFFFFFu`.

6. **Constant string update** — the inline comment documenting the vertex layout in
   `TriangulateEmitShader` must be updated from 8 to 9 u32 per vertex.

### UV layout for standard isometric quads (fan triangulation order)

```
Quad vertices:  v0(0,0)  v1(1,0)  v2(1,1)  v3(0,1)
Triangle 0: (v0, v1, v2) → UV: (0,0), (1,0), (1,1)
Triangle 1: (v0, v2, v3) → UV: (0,0), (1,1), (0,1)
```

This is hardcoded in the emit shader for `vertexCount == 4`. For `vertexCount == 3`,
Triangle 0 only: `(0,0), (1,0), (0.5,1)`. For `vertexCount >= 5`, additional
triangles use UV (0,0) for v0 and equal-arc positions for subsequent vertices.

---

## Step 8 — Update `SceneDataPacker`: write real UVs and `textureIndex`

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/SceneDataPacker.kt`

### Changes

1. **`textureIndex` field** (offset 124): change from always writing `NO_TEXTURE` to
   reading `cmd.material`:
   ```kotlin
   val texIndex = when (val m = cmd.material) {
       is IsometricMaterial.Textured -> /* resolved GPU texture slot index */
       else -> SceneDataLayout.NO_TEXTURE
   }
   buffer.putInt(texIndex)
   ```
   For this slice, the single bound texture is always slot 0. Write `0` when the
   material is `IsometricMaterial.Textured`, `NO_TEXTURE` otherwise.

2. **`FaceData` struct** does not change size (144 bytes). The UV coordinates do NOT
   live in `FaceData` — they are emitted per-vertex in M5 (see Step 7). No stride
   change to the scene-data buffer.

3. **Compact `texIndexBuffer`** — `SceneDataPacker` is also responsible for producing
   the compact `u32` array of texture indices used by `GpuTriangulateEmitPipeline`
   (Step 7 binding 4). Add `fun packTexIndicesInto(commands: List<RenderCommand>, buffer: ByteBuffer)`:
   writes one `u32` per command (4 bytes each). Buffer size = `commands.size × 4`.

---

## Step 9 — Wire everything together in `GpuFullPipeline` and `WebGpuSceneRenderer`

**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuFullPipeline.kt`
**File to modify:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/WebGpuSceneRenderer.kt`

### `GpuFullPipeline` additions

1. Hold a `GpuTextureStore` and `GpuTextureBinder`.
2. On `upload(scene, w, h)`:
   a. Scan `scene.commands` for `IsometricMaterial.Textured` entries. If none, use the fallback
      checkerboard texture and write `NO_TEXTURE` to all slots.
   b. For the first (and currently only) `IsometricMaterial.Textured` found, upload its bitmap
      via `textureStore.uploadBitmap(bitmap)`. Cache the result keyed by bitmap identity
      to avoid re-uploading on every frame.
   c. Build `textureBinder.buildBindGroup(uploadedTexture.createView())`.
   d. Pack the compact tex-index buffer via `SceneDataPacker.packTexIndicesInto(...)`.
   e. Upload the compact tex-index buffer to GPU via `ctx.queue.writeBuffer(...)`.
3. Expose `val textureBindGroup: GPUBindGroup` for the render pass.
4. On `clearScene()`, revert to the fallback bind group.
5. Close `textureStore`, `textureBinder`, uploaded textures in `close()`.

### `WebGpuSceneRenderer.drawFrame` additions

After `pass.setPipeline(pipeline.pipeline)` and before `pass.drawIndirect(...)`:
```kotlin
pass.setBindGroup(1, gp.textureBindGroup)
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

3. **Vertex buffer size** — grows from `paddedCount × 12 × 32` to
   `paddedCount × 12 × 36` (reflecting the 36-byte stride). This is derived
   automatically from `BYTES_PER_VERTEX` once Step 6 is done.

4. Change-detection state: add `lastTexIndexBuffer: GPUBuffer?` and include it in the
   `same` guard.

---

## File change summary

| File | Action | Reason |
|------|--------|--------|
| `texture/GpuTextureStore.kt` | Create | GPU texture upload + checkerboard fallback |
| `texture/GpuTextureBinder.kt` | Create | Sampler + `@group(1)` bind group factory |
| `shader/IsometricFragmentShader.kt` | Modify | `textureSample` + NO_TEXTURE fast path |
| `shader/IsometricVertexShader.kt` | Modify | `textureIndex` u32 passthrough |
| `shader/TriangulateEmitShader.kt` | Modify | Real UVs + `textureIndex`, 9-u32 stride |
| `pipeline/GpuRenderPipeline.kt` | Modify | Explicit layout, new vertex attribute |
| `pipeline/GpuTriangulateEmitPipeline.kt` | Modify | Add `texIndexBuffer` binding |
| `pipeline/SceneDataPacker.kt` | Modify | Write real `textureIndex`, emit compact tex-index buf |
| `pipeline/GpuFullPipeline.kt` | Modify | Wire store/binder, upload logic, expose bind group |
| `WebGpuSceneRenderer.kt` | Modify | `pass.setBindGroup(1, ...)` |
| `triangulation/RenderCommandTriangulator.kt` | Modify | `BYTES_PER_VERTEX` 32→36 |

Total: 9 existing files modified, 2 new files.

---

## Risk register

| Risk | Likelihood | Mitigation |
|------|-----------|-----------|
| Vertex stride change breaks existing benchmarks | Medium | Stride is now 36 bytes; all benchmark vertex readback tests must be updated to match. The change is mechanical. |
| Adreno TDR from new storage binding in emit shader | Low | Binding 4 is read-only storage — no atomics, no scatter writes. The existing TDR fix (fixed-stride, no atomicAdd) is unaffected. |
| `writeTexture` pixel format mismatch (Bitmap ARGB vs RGBA) | Medium | Android `Bitmap.Config.ARGB_8888` stores bytes in BGRA order on some API levels. Must force `copyPixelsToBuffer` to produce RGBA via a `Bitmap.copy(RGBA_8888, false)` or equivalent before upload. Verify in `GpuTextureStore`. |
| WebGPU Dawn alpha API changes to `writeTexture` parameters | Low | Vendor source confirmed: `queue.writeTexture(GPUTexelCopyTextureInfo, ByteBuffer, GPUExtent3D, GPUTexelCopyBufferLayout)` — exact signature verified in `GPUQueue.kt`. |
| `textureSample` in non-uniform control flow | Low | All fragments in a triangle share the same `textureIndex` value (it is a flat integer attribute). The `if/else` branch is uniform across the draw call when entire triangles are either all-textured or all-flat. WGSL spec requires uniform control flow for `textureSample` only within a quad — this is satisfied. |
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
2×2 RGBA8Unorm texture:
  pixel (0,0): RGBA = (255, 0, 255, 255)   — magenta
  pixel (1,0): RGBA = (0,   0, 0,   255)   — black
  pixel (0,1): RGBA = (0,   0, 0,   255)   — black
  pixel (1,1): RGBA = (255, 0, 255, 255)   — magenta
```

Rendered with `AddressMode.ClampToEdge` and `FilterMode.Linear` (sampler from Step 2),
this produces a smooth magenta-to-black gradient — deliberately unpleasant to signal
"texture not loaded" in development builds.

---

## Sequence diagram (per-frame, textured scene)

```
CPU (uploadScene)                    GPU M3             GPU M5             GPU Render
   │                                    │                  │                   │
   ├─ packInto (FaceData w/ texIdx) ──► scene buffer       │                   │
   ├─ packTexIndicesInto ────────────────────────────────► texIndex buffer     │
   ├─ writeTexture (bitmap pixels) ──────────────────────────────────────────► GPU texture
   │                                    │                  │                   │
   ├─ (frame) dispatch M3 ──────────────►                  │                   │
   │                                    └─ TransformedFace buf ──►             │
   ├─ (frame) dispatch M5 ───────────────────────────────►                     │
   │                                    │  reads texIndex  └─ vertexBuf(uv+texIdx) ►│
   ├─ (frame) render pass ───────────────────────────────────────────────────► │
   │                                    │                  │   setBindGroup(1, textureBindGroup)
   │                                    │                  │   setVertexBuffer + drawIndirect
   │                                    │                  │   fragmentMain: textureSample
```

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
