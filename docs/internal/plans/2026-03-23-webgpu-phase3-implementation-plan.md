# WebGPU Phase 3 Implementation Plan — WS13: Full GPU-Driven Pipeline

> **Status**: implementation plan
> **Date**: 2026-03-23
> **Scope**: Full GPU-driven compute + render pipeline in `isometric-webgpu`
> **Library pin**: `androidx.webgpu:webgpu:1.0.0-alpha04`
> **Ground truth**: current repo source, vendored `vendor/androidx-webgpu/`, current Android official docs
> **Prerequisite**: Phase 2 (WS12) must be merged and stable on device

---

## 0. Executive Summary

Phase 3 moves the entire `projectSceneAsync` pipeline — vertex transform, back-face culling, frustum culling, depth sorting, lighting, triangulation, and draw dispatch — onto the GPU. After WS13, the CPU only collects scene items from the node tree and uploads a flat `FaceData` buffer once per dirty frame. All heavy computation and rendering happens in a chain of compute passes followed by a single `drawIndirect` call, encoded in one command buffer and submitted once.

Target: **1000-face scene at < 2ms total frame time on Pixel 6**.

---

## 1. Open Gaps From Phase 2 — Resolve First

These must be closed before Phase 3 implementation begins. Each is a prerequisite, not a parallel track.

### G1: GpuContext refactor for `compatibleSurface` adapter request

**Current state**: `GpuContext.create()` accepts an optional `requestAdapterOptionsFactory: ((GPUInstance) -> GPURequestAdapterOptions)?` lambda. The render backend can provide `compatibleSurface` through this factory.

**Gap**: The Phase 2 plan (§4.1 Gap 1) identified that `GpuContext.create()` needed refactoring. The current factory lambda approach works but is undocumented and fragile — the render backend constructs a `GPURequestAdapterOptions` with `compatibleSurface` but there is no contract that the adapter will actually be compatible.

**Resolution**:
1. Add `GpuContext.createForSurface(gpuSurface: GPUSurface): GpuContext` as a dedicated factory
2. This factory calls `requestAdapter(GPURequestAdapterOptions(compatibleSurface = gpuSurface, powerPreference = PowerPreference.HighPerformance, backendType = BackendType.Vulkan))`
3. Falls back to `requestAdapter()` without `compatibleSurface` if the first request throws
4. Document in KDoc that `createForSurface` is preferred when a render surface exists
5. The existing `create()` factory remains for compute-only use (Phase 1 path)

**Validation**: WebGpuSceneRenderer uses `createForSurface` and renders correctly on device.

### G2: Frame pacing model for AndroidExternalSurface

**Current state**: `WebGpuSceneRenderer` uses `PresentMode.Fifo` for vsync back-pressure. The render loop runs `while (isActive)` with no explicit delay — `present()` blocks until the next vsync when the swapchain queue is full.

**Research findings**: `PresentMode.Fifo` is the correct mechanism. The `present()` call provides natural throttling. Combined with `Surface.setFrameRate()` (already implemented for LTPO panels), this is the recommended approach. No additional frame pacing mechanism is needed.

**Gap**: The current render loop does not use `withFrameNanos` — it is a free-running while-loop throttled only by Fifo present. This is acceptable for a dedicated render thread, but the loop should have a yield point to allow cancellation checks.

**Resolution**:
1. Keep `PresentMode.Fifo` as the primary throttle (correct, already working)
2. Add `yield()` after `present()` to ensure cooperative cancellation
3. Document in KDoc that Fifo is the pacing strategy and why `withFrameNanos` is not used (we are not on the Compose frame clock — we are on a background dispatcher)

**Validation**: Confirmed stable frame timing in Phase 1 review (16ms median, 27ms p90).

### G3: Transient wrapper close timing

**Current state**: `WebGpuSceneRenderer.drawFrame()` explicitly closes textureView, surfaceTexture, commandBuffer, encoder, and pass after each frame.

**Research findings**: The vendored API marks all of these as `AutoCloseable`. The Phase 1 audit proved that not closing them causes JNI wrapper accumulation and OOM. Phase 2's render loop already closes them.

**Gap**: The roadmap review (finding #2) noted that the close policy for transient wrappers was undocumented.

**Resolution**:
1. Add a `GPU_OBJECT_LIFETIME.md` doc in `docs/internal/` that formalizes the rule: "All per-frame transient `AutoCloseable` wrappers MUST be explicitly `close()`d after use. This includes: `GPUCommandEncoder`, `GPURenderPassEncoder`, `GPUComputePassEncoder`, `GPUCommandBuffer`, `GPUTextureView`, and the `GPUSurfaceTexture.texture` wrapper."
2. Phase 3 compute passes will follow the same rule — each compute pass encoder is closed after `end()`.

**Validation**: Heap stays flat during 2+ minute endurance runs (already verified in Phase 1 audit).

### G4: `device.destroy()` vs `device.close()`

**Current state**: `GpuContext.destroy()` calls `device.destroy()` followed by `device.close()`. The vendored helper code has `device.close()` commented out with upstream TODO `b/428866400`.

**Research findings**: `device.destroy()` invalidates the device immediately (all further GPU work fails). `device.close()` decrements the JNI wrapper refcount. Both are needed: `destroy()` for immediate cleanup, `close()` for JNI handle release.

**Resolution**: Keep the current `destroy()` then `close()` pattern. This is correct. The upstream TODO is about the helper layer's lifecycle management, not about whether close() should be called. Add a code comment referencing the upstream bug number so future readers understand the discrepancy with the helper layer.

**Validation**: No change needed — current code is correct.

---

## 2. Known Risks — Mitigations

### R1: `indirectBuffer` draw count synchronization

**Risk**: Compute pass writes draw args; render pass reads them. Data must be visible.

**Mitigation**: WebGPU guarantees implicit synchronization between passes within a single command encoder. All compute passes and the render pass are encoded in one encoder and submitted once. No explicit barriers needed. This is a core WebGPU spec guarantee (see gpuweb design doc "The Case for Passes").

**Validation**: Encode compute + render in one encoder. Verify correct rendering on device.

### R2: Workgroup size 256 exceeds device limits

**Risk**: `maxComputeInvocationsPerWorkgroup` may be less than 256 on some devices.

**Mitigation**: The WebGPU spec guarantees a minimum of 256 for `maxComputeInvocationsPerWorkgroup`. All conformant implementations support 256. No dynamic clamping needed.

**Validation**: Assert `device.getLimits().maxComputeInvocationsPerWorkgroup >= 256` in `GpuContext.create()`.

### R3: Float depth keys incorrect for negative projected depths

**Risk**: Faces below the isometric ground plane (z > centroid of scene) may produce negative depth keys.

**Mitigation**: The depth formula `x + y - 2z` can produce negative values. The GPU bitonic sort already handles this correctly because it sorts `f32` values using IEEE 754 comparison (not unsigned bit reinterpretation). The existing `GpuBitonicSortShader` compares keys with `<` which handles negative floats correctly in WGSL.

**Validation**: Unit test with a scene containing faces at z < 0 and z > 0.

### R4: Non-convex faces from custom Path nodes

**Risk**: Fan triangulation produces incorrect geometry for non-convex polygons.

**Mitigation**: Isometric engine shapes (Prism, Pyramid, Cylinder, Stairs, Knot, etc.) produce only convex faces. Custom `Path` nodes are the only source of non-convex faces. Phase 3 keeps fan triangulation (same as Phase 2). If non-convex faces are detected, they render with visual artifacts but do not crash. A convexity check with ear-clipping fallback is deferred to a future workstream.

**Validation**: Document the convex-face assumption in `RenderCommandTriangulator` KDoc.

### R5: Per-frame JNI wrapper accumulation from compute passes

**Risk**: Phase 3 adds 5+ compute pass encoders per frame. Without explicit `close()`, JNI wrappers accumulate.

**Mitigation**: Apply the same discipline from Phase 1's fix: every transient `AutoCloseable` is closed immediately after use. Compute pass encoders are closed after `end()`. The command buffer is closed after `submit()`. Use a `use {}` block or explicit `try/finally` for each encoder.

**Validation**: 2+ minute endurance run with flat heap.

### R6: Lighting approximation differs from CPU path

**Risk**: The CPU lighting path uses `IsoColor.lighten(brightness * colorDifference, lightColor)` which involves RGB→HSL→RGB conversion. Replicating HSL manipulation in WGSL is complex and may produce visually different results.

**Mitigation**: Phase 3 uses a simplified GPU lighting model:
1. Compute diffuse factor: `brightness = max(dot(normal, lightDir), 0.0)`
2. Blend toward light color: `litRgb = baseRgb * mix(1.0, lightColor, brightness * colorDifference)`
3. This is an RGB-space approximation of the CPU's HSL lightness adjustment
4. Accept minor visual differences — the GPU path does not need to be pixel-identical to Canvas

The CPU path remains available via `RenderBackend.Canvas` for pixel-exact rendering.

**Validation**: Side-by-side visual comparison of Canvas vs WebGPU for the same scene.

---

## 3. Phase 3 Architecture

### 3.1 Pipeline overview

```
[CPU — per dirty frame only]
  rootNode.renderTo(commands, renderContext)
      → SceneGraph.items: List<SceneItem>
      → flat FaceData upload buffer (one per face)
  queue.writeBuffer(sceneDataBuffer, ...)

[GPU — single command encoder, single submit]
  Compute Pass 1: Transform + Cull + Light
      → input:  sceneDataBuffer (storage, read)
      → output: transformedBuffer (storage, read_write)
      → output: visibleCount (storage, atomic<u32>)
      Writes transformed 2D vertices, lit color, depth key.
      Performs back-face cull and frustum cull inline.
      Surviving faces written via atomicAdd to compacted output.

  Compute Pass 2: Bitonic Sort (reuse existing GpuBitonicSortShader)
      → input:  sort keys extracted from transformedBuffer
      → output: sorted indices in back-to-front order

  Compute Pass 3: Triangulate + Write Vertex Buffer + Write Indirect Args
      → input:  transformedBuffer (sorted order)
      → output: vertexBuffer (storage | vertex)
      → output: indirectArgsBuffer (storage | indirect)
      Fan-triangulates each visible face directly into the vertex buffer.
      Writes DrawIndirectArgs for a single draw call.

  Render Pass:
      setPipeline(renderPipeline)
      setVertexBuffer(0, vertexBuffer)
      drawIndirect(indirectArgsBuffer, 0)

  present()
```

### 3.2 Why three compute passes, not six

The roadmap sketched six separate compute passes (transform, cull, sort, light, populate-indirect, render). Research and codebase analysis show this is over-decomposed for ~1000 faces:

1. **Transform + Cull + Light are fused** into one pass. Each face is independent — one invocation transforms all vertices, tests back-face cull (signed area of projected polygon), tests frustum cull (AABB vs viewport), computes lighting, computes depth key, and writes the result via atomic compaction. This eliminates two intermediate buffers and two dispatch overhead costs.

2. **Sort** uses the existing `GpuBitonicSortShader` (already proven in Phase 1). For ~1000 faces, bitonic sort in shared memory with a single dispatch is optimal. No multi-pass radix sort needed.

3. **Triangulate + Vertex Write + Indirect Args are fused** into one pass. After sorting, each invocation reads its face in sorted order, fan-triangulates, and writes directly to the vertex buffer using atomic bump allocation. One invocation (thread 0) writes the single `DrawIndirectArgs`.

This reduces dispatch overhead, intermediate buffer count, and total GPU memory.

### 3.3 Why single `drawIndirect`, not multi-draw-indirect

Multi-draw-indirect (MDI) is **not in the WebGPU spec** and has only ~30% Android device support. All faces share one pipeline and one vertex format, so a single `drawIndirect` with the total vertex count is correct and sufficient.

---

## 4. GPU Buffer Layouts

### 4.1 FaceData — CPU upload buffer (per scene item)

This is the data the CPU uploads once per dirty frame.

```wgsl
struct FaceData {
    // 3D vertices (up to 6 for hexagonal faces; most faces are 3-4 vertices)
    v0: vec3<f32>, _p0: f32,     // 16 bytes
    v1: vec3<f32>, _p1: f32,     // 16 bytes
    v2: vec3<f32>, _p2: f32,     // 16 bytes
    v3: vec3<f32>, _p3: f32,     // 16 bytes
    v4: vec3<f32>, _p4: f32,     // 16 bytes
    v5: vec3<f32>, vertexCount: u32,  // 16 bytes

    // Color (0-255 range, matching IsoColor)
    baseColor: vec4<f32>,         // 16 bytes (r, g, b, a in 0-255 scale)

    // Identity
    faceIndex: u32,               // 4 bytes (original index for hit-test correlation)
    _padding: vec3<u32>,          // 12 bytes (alignment)
}
// Total: 128 bytes per face
```

**Design decisions**:
- **6 vertices max**: Covers all current shapes. Prism top/bottom = 4 vertices, sides = 4 vertices. Cylinder caps = up to 6 vertices (hexagonal approximation). Faces with fewer vertices use `vertexCount` to indicate the actual count; unused vertex slots are zeroed.
- **128 bytes per face**: 16-byte aligned for efficient GPU access. For 1000 faces = 128 KB upload.
- **No normal stored**: The normal is computed on GPU from the first 3 vertices (cross product of edges, same as `IsometricProjection.transformColor`).
- **3D points, not 2D**: The GPU handles the full isometric projection.

### 4.2 TransformedFace — GPU intermediate buffer

Written by Compute Pass 1, read by Compute Passes 2 and 3.

```wgsl
struct TransformedFace {
    // 2D screen points (up to 6 vertices)
    s0: vec2<f32>,                // 8 bytes
    s1: vec2<f32>,                // 8 bytes
    s2: vec2<f32>,                // 8 bytes
    s3: vec2<f32>,                // 8 bytes
    s4: vec2<f32>,                // 8 bytes
    s5: vec2<f32>,                // 8 bytes
    vertexCount: u32,             // 4 bytes
    _p0: u32,                     // 4 bytes (pad)

    // Lit color (GPU-computed, 0.0-1.0 range for direct vertex output)
    litColor: vec4<f32>,          // 16 bytes

    // Sort key
    depthKey: f32,                // 4 bytes
    faceIndex: u32,               // 4 bytes (original index, preserved for hit-test)
    _padding: vec2<u32>,          // 8 bytes
}
// Total: 96 bytes per face
```

### 4.3 Sort key buffer — reuse existing format

The existing `GpuBitonicSortShader` sorts 16-byte tuples: `[f32 key, u32 index, u32 pad, u32 pad]`.

Phase 3 extracts depth keys from `TransformedFace.depthKey` and packs them into the sort input buffer in the same format the bitonic sorter already expects. This reuses the proven Phase 1 sort infrastructure without modification.

### 4.4 Vertex buffer — GPU-written, render-read

Same format as Phase 2 (8 floats per vertex, 32 bytes):

```wgsl
struct Vertex {
    position: vec2<f32>,          // NDC x, y
    color: vec4<f32>,             // r, g, b, a (0.0-1.0)
    uv: vec2<f32>,                // reserved (0.0, 0.0)
}
```

Buffer usage: `Storage | Vertex | CopyDst` (writable by compute, readable by render).

### 4.5 Indirect args buffer

```wgsl
struct DrawIndirectArgs {
    vertexCount: u32,
    instanceCount: u32,   // always 1
    firstVertex: u32,     // always 0
    firstInstance: u32,   // always 0
}
```

Buffer usage: `Storage | Indirect` (writable by compute, readable by `drawIndirect`).

### 4.6 Uniform buffer

```wgsl
struct SceneUniforms {
    // Isometric projection matrix (2x3 → stored as 2x vec4 for alignment)
    // Row 0: [transformation[0][0], transformation[1][0], 0, originX]
    // Row 1: [transformation[0][1], transformation[1][1], scale, originY]
    projRow0: vec4<f32>,
    projRow1: vec4<f32>,

    // Lighting
    lightDir: vec3<f32>,
    colorDifference: f32,
    lightColor: vec4<f32>,       // 0-1 range (lightColor / 255)

    // Viewport
    viewportWidth: f32,
    viewportHeight: f32,

    // Counts
    faceCount: u32,
    _pad: u32,
}
```

---

## 5. WGSL Compute Shaders

### 5.1 Compute Pass 1: Transform + Cull + Light + Compact

```wgsl
// transform_cull_light.wgsl

struct FaceData {
    v0: vec3<f32>, _p0: f32,
    v1: vec3<f32>, _p1: f32,
    v2: vec3<f32>, _p2: f32,
    v3: vec3<f32>, _p3: f32,
    v4: vec3<f32>, _p4: f32,
    v5: vec3<f32>, vertexCount: u32,
    baseColor: vec4<f32>,
    faceIndex: u32,
    _padding: vec3<u32>,
}

struct TransformedFace {
    s0: vec2<f32>,
    s1: vec2<f32>,
    s2: vec2<f32>,
    s3: vec2<f32>,
    s4: vec2<f32>,
    s5: vec2<f32>,
    vertexCount: u32,
    _p0: u32,
    litColor: vec4<f32>,
    depthKey: f32,
    faceIndex: u32,
    _padding: vec2<u32>,
}

struct SceneUniforms {
    projRow0: vec4<f32>,
    projRow1: vec4<f32>,
    lightDir: vec3<f32>,
    colorDifference: f32,
    lightColor: vec4<f32>,
    viewportWidth: f32,
    viewportHeight: f32,
    faceCount: u32,
    _pad: u32,
}

@group(0) @binding(0) var<storage, read> scene: array<FaceData>;
@group(0) @binding(1) var<storage, read_write> transformed: array<TransformedFace>;
@group(0) @binding(2) var<uniform> uniforms: SceneUniforms;
@group(0) @binding(3) var<storage, read_write> visibleCount: atomic<u32>;

fn projectPoint(p: vec3<f32>) -> vec2<f32> {
    // Isometric projection:
    //   screenX = originX + x * t[0][0] + y * t[1][0]
    //   screenY = originY - x * t[0][1] - y * t[1][1] - z * scale
    let sx = uniforms.projRow0.w + p.x * uniforms.projRow0.x + p.y * uniforms.projRow0.y;
    let sy = uniforms.projRow1.w - p.x * uniforms.projRow1.x - p.y * uniforms.projRow1.y - p.z * uniforms.projRow1.z;
    return vec2<f32>(sx, sy);
}

fn getVertex(face: FaceData, i: u32) -> vec3<f32> {
    switch (i) {
        case 0u: { return face.v0; }
        case 1u: { return face.v1; }
        case 2u: { return face.v2; }
        case 3u: { return face.v3; }
        case 4u: { return face.v4; }
        case 5u: { return face.v5; }
        default: { return vec3<f32>(0.0); }
    }
}

fn getScreenPoint(t: TransformedFace, i: u32) -> vec2<f32> {
    switch (i) {
        case 0u: { return t.s0; }
        case 1u: { return t.s1; }
        case 2u: { return t.s2; }
        case 3u: { return t.s3; }
        case 4u: { return t.s4; }
        case 5u: { return t.s5; }
        default: { return vec2<f32>(0.0); }
    }
}

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let i = gid.x;
    if (i >= uniforms.faceCount) { return; }

    let face = scene[i];
    let vc = face.vertexCount;

    // --- Project all vertices ---
    var result: TransformedFace;
    result.vertexCount = vc;
    result.faceIndex = face.faceIndex;

    result.s0 = projectPoint(face.v0);
    result.s1 = projectPoint(face.v1);
    result.s2 = projectPoint(face.v2);
    if (vc > 3u) { result.s3 = projectPoint(face.v3); }
    if (vc > 4u) { result.s4 = projectPoint(face.v4); }
    if (vc > 5u) { result.s5 = projectPoint(face.v5); }

    // --- Back-face cull (signed area of first triangle) ---
    // z = x0*y1 + x1*y2 + x2*y0 - x1*y0 - x2*y1 - x0*y2
    let x0 = result.s0.x; let y0 = result.s0.y;
    let x1 = result.s1.x; let y1 = result.s1.y;
    let x2 = result.s2.x; let y2 = result.s2.y;
    let area = x0 * y1 + x1 * y2 + x2 * y0 - x1 * y0 - x2 * y1 - x0 * y2;
    if (area > 0.0) { return; } // back-facing

    // --- Frustum cull (AABB vs viewport) ---
    var minX = min(result.s0.x, min(result.s1.x, result.s2.x));
    var maxX = max(result.s0.x, max(result.s1.x, result.s2.x));
    var minY = min(result.s0.y, min(result.s1.y, result.s2.y));
    var maxY = max(result.s0.y, max(result.s1.y, result.s2.y));
    for (var vi = 3u; vi < vc; vi++) {
        let sp = getScreenPoint(result, vi);
        minX = min(minX, sp.x);
        maxX = max(maxX, sp.x);
        minY = min(minY, sp.y);
        maxY = max(maxY, sp.y);
    }
    if (maxX < 0.0 || minX > uniforms.viewportWidth || maxY < 0.0 || minY > uniforms.viewportHeight) {
        return; // outside viewport
    }

    // --- Depth key (average x + y - 2z over 3D vertices) ---
    var depthSum: f32 = 0.0;
    for (var vi = 0u; vi < vc; vi++) {
        let v = getVertex(face, vi);
        depthSum += f32(v.x + v.y - 2.0 * v.z);
    }
    result.depthKey = depthSum / f32(vc);

    // --- Lighting ---
    // Normal from first 3 vertices (cross product of edges)
    let edge1 = face.v0 - face.v1;  // matches CPU: fromTwoPoints(p[1], p[0])
    let edge2 = face.v1 - face.v2;  // matches CPU: fromTwoPoints(p[2], p[1])
    let normal = normalize(cross(edge1, edge2));
    let brightness = max(dot(normal, uniforms.lightDir), 0.0);

    // Simplified RGB lighting (approximation of CPU HSL path)
    let baseRgb = face.baseColor.rgb / 255.0;
    let lightRgb = uniforms.lightColor.rgb;
    let tinted = baseRgb * lightRgb; // modulate by light color
    // Lighten by brightness * colorDifference (add to lightness)
    let litRgb = tinted + brightness * uniforms.colorDifference;
    result.litColor = vec4<f32>(clamp(litRgb, vec3<f32>(0.0), vec3<f32>(1.0)), face.baseColor.a / 255.0);

    // --- Atomic compaction ---
    let slot = atomicAdd(&visibleCount, 1u);
    transformed[slot] = result;
}
```

### 5.2 Compute Pass 2: Sort

Reuse the existing `GpuBitonicSortShader` from Phase 1 without modification.

**Integration**:
1. After Compute Pass 1, read back `visibleCount` (see §5.4 for the count readback strategy)
2. Pack sort keys from `transformed[0..visibleCount].depthKey` into the sort input buffer
3. Run bitonic sort dispatches (same as `GpuDepthSorter.sortByDepthKeys`)
4. Result: `sortedIndices[0..visibleCount]` in back-to-front order

**Important architectural decision**: The bitonic sort currently requires a CPU readback to extract sorted indices and then a CPU-side reorder. In Phase 3, we eliminate this readback by keeping the sort entirely on GPU:
- Pack sort keys into the sort buffer using a small compute dispatch (not CPU)
- After sort, a subsequent compute pass reads sorted indices directly from the sort output buffer
- No `mapAsync`, no CPU readback, no JNI callback in the hot path

### 5.3 Compute Pass 3: Triangulate + Write Vertex Buffer + Indirect Args

```wgsl
// triangulate_and_emit.wgsl

struct TransformedFace {
    s0: vec2<f32>,
    s1: vec2<f32>,
    s2: vec2<f32>,
    s3: vec2<f32>,
    s4: vec2<f32>,
    s5: vec2<f32>,
    vertexCount: u32,
    _p0: u32,
    litColor: vec4<f32>,
    depthKey: f32,
    faceIndex: u32,
    _padding: vec2<u32>,
}

struct SortEntry {
    key: f32,
    index: u32,
    _pad0: u32,
    _pad1: u32,
}

struct Vertex {
    position: vec2<f32>,
    color: vec4<f32>,
    uv: vec2<f32>,
}

struct DrawIndirectArgs {
    vertexCount: u32,
    instanceCount: u32,
    firstVertex: u32,
    firstInstance: u32,
}

struct EmitUniforms {
    visibleCount: u32,
    viewportWidth: f32,
    viewportHeight: f32,
    _pad: u32,
}

@group(0) @binding(0) var<storage, read> transformed: array<TransformedFace>;
@group(0) @binding(1) var<storage, read> sortedEntries: array<SortEntry>;
@group(0) @binding(2) var<storage, read_write> vertices: array<Vertex>;
@group(0) @binding(3) var<storage, read_write> indirectArgs: DrawIndirectArgs;
@group(0) @binding(4) var<storage, read_write> vertexCursor: atomic<u32>;
@group(0) @binding(5) var<uniform> emitUniforms: EmitUniforms;

fn toNdcX(screenX: f32, width: f32) -> f32 {
    return (screenX / width) * 2.0 - 1.0;
}

fn toNdcY(screenY: f32, height: f32) -> f32 {
    return 1.0 - (screenY / height) * 2.0; // flip Y: screen-down → clip-up
}

fn getScreenPoint(face: TransformedFace, i: u32) -> vec2<f32> {
    switch (i) {
        case 0u: { return face.s0; }
        case 1u: { return face.s1; }
        case 2u: { return face.s2; }
        case 3u: { return face.s3; }
        case 4u: { return face.s4; }
        case 5u: { return face.s5; }
        default: { return vec2<f32>(0.0); }
    }
}

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let i = gid.x;
    if (i >= emitUniforms.visibleCount) { return; }

    // Read face in sorted (back-to-front) order
    let sortEntry = sortedEntries[i];
    let face = transformed[sortEntry.index];

    let vc = face.vertexCount;
    let triCount = vc - 2u;
    let vertCount = triCount * 3u;

    // Allocate space in vertex buffer
    let baseVertex = atomicAdd(&vertexCursor, vertCount);

    let w = emitUniforms.viewportWidth;
    let h = emitUniforms.viewportHeight;

    // Fan triangulation: (v0, v_i, v_{i+1}) for i in 1..vc-1
    let p0 = getScreenPoint(face, 0u);
    let ndcP0 = vec2<f32>(toNdcX(p0.x, w), toNdcY(p0.y, h));

    for (var t = 0u; t < triCount; t++) {
        let p1 = getScreenPoint(face, t + 1u);
        let p2 = getScreenPoint(face, t + 2u);
        let ndcP1 = vec2<f32>(toNdcX(p1.x, w), toNdcY(p1.y, h));
        let ndcP2 = vec2<f32>(toNdcX(p2.x, w), toNdcY(p2.y, h));

        let vi = baseVertex + t * 3u;
        vertices[vi + 0u] = Vertex(ndcP0, face.litColor, vec2<f32>(0.0, 0.0));
        vertices[vi + 1u] = Vertex(ndcP1, face.litColor, vec2<f32>(0.0, 0.0));
        vertices[vi + 2u] = Vertex(ndcP2, face.litColor, vec2<f32>(0.0, 0.0));
    }

    // Thread 0 writes indirect args (after all threads have written vertices)
    // Note: this is a race because other threads may still be writing.
    // The actual vertexCount comes from the atomic cursor, written after all threads finish.
    // We handle this by having a separate single-thread dispatch or by writing after a barrier.
}
```

**Indirect args strategy**: After the triangulate dispatch completes, the `vertexCursor` atomic holds the total vertex count. A separate tiny compute dispatch (1 thread) reads `vertexCursor` and writes the `DrawIndirectArgs`. This avoids the race condition noted above.

```wgsl
// write_indirect_args.wgsl

@group(0) @binding(0) var<storage, read> vertexCursor: atomic<u32>;
@group(0) @binding(1) var<storage, read_write> indirectArgs: DrawIndirectArgs;

@compute @workgroup_size(1)
fn main() {
    indirectArgs.vertexCount = atomicLoad(&vertexCursor);
    indirectArgs.instanceCount = 1u;
    indirectArgs.firstVertex = 0u;
    indirectArgs.firstInstance = 0u;
}
```

### 5.4 Visible count transfer strategy

After Compute Pass 1, the visible count lives in a GPU buffer as an `atomic<u32>`. The sort pass and triangulation pass both need this count. Options:

**Option A (chosen): GPU-side count buffer, no CPU readback.**
- The sort key packing pass reads `visibleCount` directly from the GPU buffer
- The sort dispatches use the maximum possible workgroup count (based on max face count), and the shader early-returns for indices >= visibleCount
- The triangulation pass reads `visibleCount` from a uniform that was copied from the atomic buffer via `copyBufferToBuffer` into a uniform buffer

This eliminates all CPU readback in the hot path. The cost is dispatching sort passes for potentially more elements than visible, but at ~1000 max faces this is negligible (the bitonic sort handles padding with sentinel values already).

**Option B (rejected): CPU readback of visibleCount.**
Requires `mapAsync` → callback → CPU → upload. Reintroduces the JNI callback leak risk we solved in Phase 1. Rejected.

---

## 6. Sort Key Packing — GPU-Side

A small compute pass packs sort keys from the `TransformedFace` buffer into the format expected by `GpuBitonicSortShader`:

```wgsl
// pack_sort_keys.wgsl

struct TransformedFace {
    // ... (same as above, only depthKey and faceIndex used)
    s0: vec2<f32>, s1: vec2<f32>, s2: vec2<f32>,
    s3: vec2<f32>, s4: vec2<f32>, s5: vec2<f32>,
    vertexCount: u32, _p0: u32,
    litColor: vec4<f32>,
    depthKey: f32,
    faceIndex: u32,
    _padding: vec2<u32>,
}

struct SortEntry {
    key: f32,
    index: u32,
    _pad0: u32,
    _pad1: u32,
}

@group(0) @binding(0) var<storage, read> transformed: array<TransformedFace>;
@group(0) @binding(1) var<storage, read_write> sortKeys: array<SortEntry>;
@group(0) @binding(2) var<storage, read> visibleCountBuf: u32;
@group(0) @binding(3) var<uniform> maxCount: u32;

@compute @workgroup_size(256)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let i = gid.x;
    if (i >= maxCount) { return; }

    if (i < visibleCountBuf) {
        let face = transformed[i];
        sortKeys[i] = SortEntry(face.depthKey, i, 0u, 0u);
    } else {
        // Sentinel: sorts to the end (back-to-front means higher = drawn first,
        // so sentinels use -inf to sort last and be ignored)
        sortKeys[i] = SortEntry(bitcast<f32>(0xFF800000u), 0xFFFFFFFFu, 0u, 0u);
    }
}
```

---

## 7. Detailed Implementation Milestones

### Milestone 0: Resolve Phase 2 gaps (G1–G4)

**Files changed:**
- `GpuContext.kt` — add `createForSurface()` factory, add limit assertion
- `WebGpuSceneRenderer.kt` — use `createForSurface()`, add `yield()` after present
- `docs/internal/GPU_OBJECT_LIFETIME.md` — new doc

**Exit criteria:** Phase 2 render backend works correctly with the refactored `GpuContext`. No behavioral change.

### Milestone 1: FaceData packing and upload

**New files:**
- `isometric-webgpu/.../pipeline/GpuSceneDataBuffer.kt`

**What it does:**
- Takes `SceneGraph.items` list
- Packs each `SceneItem` into the `FaceData` struct layout (128 bytes per face)
- Writes 3D points, base color, vertex count, face index into a reusable `ByteBuffer`
- Uploads via `queue.writeBuffer()` into a `Storage | CopyDst` buffer
- Caches the buffer; grows geometrically when face count increases

**Key decisions:**
- Points are extracted from `SceneItem.path.points` (3D, not projected)
- Color is `SceneItem.baseColor` (0-255 range, stored as f32 for GPU)
- Vertex count is `path.points.size` (3-6)
- Face index is the sequential index in the items list

**Exit criteria:** Buffer upload succeeds. GPU buffer contains correct data (verified by readback in a test).

### Milestone 2: Uniform buffer with projection parameters

**New files:**
- `isometric-webgpu/.../pipeline/GpuSceneUniforms.kt`

**What it does:**
- Extracts the isometric projection transformation matrix from `IsometricProjection`
- Packs `projRow0`, `projRow1`, `lightDir`, `colorDifference`, `lightColor`, `viewportWidth`, `viewportHeight`, `faceCount` into a uniform buffer
- Updates only when viewport size, light direction, or projection parameters change

**Key challenge:** `IsometricProjection` is `internal` to `isometric-core`. The transformation matrix values must be exposed.

**Resolution:** Add a new internal API to `IsometricEngine` or `IsometricProjection`:
```kotlin
// In isometric-core, internal visibility
internal data class ProjectionParams(
    val t00: Double, val t10: Double,  // transformation[0][0], transformation[1][0]
    val t01: Double, val t11: Double,  // transformation[0][1], transformation[1][1]
    val scale: Double,
    val colorDifference: Double,
    val lightColor: IsoColor,
)
```
Expose via `IsometricEngine.projectionParams: ProjectionParams` (internal property).

Since `isometric-webgpu` depends on `isometric-compose` which depends on `isometric-core`, and all are in the same project, `internal` visibility works.

**Exit criteria:** Uniform buffer contains correct projection values. Verified by rendering a known scene and comparing screen coordinates to CPU path.

### Milestone 3: Transform + Cull + Light compute shader

**New files:**
- `isometric-webgpu/.../shader/TransformCullLightShader.kt` — WGSL source string
- `isometric-webgpu/.../pipeline/GpuTransformPipeline.kt` — pipeline, bind group, dispatch

**What it does:**
- Creates the compute pipeline from the WGSL shader in §5.1
- Manages bind group layout with 4 bindings (scene, transformed, uniforms, visibleCount)
- Dispatches `ceil(faceCount / 256)` workgroups
- Clears `visibleCount` to 0 before dispatch (via `encoder.clearBuffer`)

**Validation:**
- Unit test: known scene → read back `transformed` buffer → verify projected coordinates match CPU `translatePointInto`
- Unit test: culled faces produce expected visible count
- Unit test: lit colors are within acceptable range of CPU lighting

**Exit criteria:** Transform, cull, and light compute pass produces correct results for all existing sample scenes.

### Milestone 4: Sort key packing + bitonic sort (GPU-only)

**New files:**
- `isometric-webgpu/.../shader/PackSortKeysShader.kt` — WGSL source string
- `isometric-webgpu/.../pipeline/GpuSortKeyPacker.kt` — pipeline and dispatch

**Modified files:**
- `GpuDepthSorter.kt` — extract the bitonic sort dispatch logic into a reusable `GpuBitonicSort` class that accepts pre-packed GPU buffers (not just CPU `FloatArray`)

**What it does:**
1. Small compute dispatch packs `TransformedFace.depthKey` → sort entry buffer (§6)
2. Runs bitonic sort passes on the sort entry buffer (same shader, same logic as Phase 1)
3. Result: sorted entries in GPU memory, no CPU readback

**Key refactor:** The current `GpuDepthSorter.sortByDepthKeys(FloatArray)` does:
1. CPU packs keys → ByteBuffer
2. `queue.writeBuffer` → GPU sort input
3. GPU sort dispatches
4. `mapAsync` + readback → CPU `IntArray`

Phase 3 splits this into:
- `GpuBitonicSort` — reusable class that sorts a pre-existing GPU buffer in-place
- `GpuDepthSorter` — still exists for Phase 1 compute backend (CPU→GPU→CPU path)
- Phase 3 pipeline uses `GpuBitonicSort` directly (GPU→GPU, no readback)

**Exit criteria:** Sort output in GPU memory matches CPU `sortByDepthKeys` for the same input.

### Milestone 5: Triangulate + emit vertex buffer + indirect args

**New files:**
- `isometric-webgpu/.../shader/TriangulateEmitShader.kt` — WGSL source (§5.3)
- `isometric-webgpu/.../shader/WriteIndirectArgsShader.kt` — WGSL source (§5.3)
- `isometric-webgpu/.../pipeline/GpuTriangulateEmitPipeline.kt` — pipeline and dispatch

**What it does:**
1. Dispatch triangulate+emit: each thread reads one face in sorted order, fan-triangulates, writes to vertex buffer via atomic bump allocation
2. Dispatch write-indirect-args (1 thread): reads `vertexCursor` atomic, writes `DrawIndirectArgs`
3. Clears `vertexCursor` to 0 before dispatch

**Key concern:** The vertex buffer must be created with `Storage | Vertex` usage. Verify that the vendored `GPUBufferDescriptor` accepts this flag combination.

**Vendored API check:** `BufferUsage.Storage = 0x80`, `BufferUsage.Vertex = 0x20`. These are bitflags and can be OR'd: `BufferUsage.Storage or BufferUsage.Vertex`. The vendored `GPUBufferDescriptor` accepts `@BufferUsage usage: Int`. ✅ Confirmed valid.

**Exit criteria:** Vertex buffer contains correct triangulated geometry. `DrawIndirectArgs.vertexCount` matches expected triangle count.

### Milestone 6: Full GPU pipeline integration

**Modified files:**
- `WebGpuSceneRenderer.kt` — replace the current per-frame flow with the new GPU pipeline

**Current Phase 2 per-frame flow:**
1. CPU: `IsometricEngine.projectSceneAsync()` → `PreparedScene`
2. CPU: `RenderCommandTriangulator.pack()` → `ByteBuffer`
3. CPU: `queue.writeBuffer()` → GPU vertex buffer
4. GPU: render pass → `draw(vertexCount)`

**New Phase 3 per-frame flow:**
1. CPU: `SceneGraph.items` → `GpuSceneDataBuffer.pack()` → `ByteBuffer`
2. CPU: `queue.writeBuffer()` → GPU scene data buffer
3. CPU: update uniforms if viewport/light changed
4. GPU: Compute Pass 1 (transform + cull + light)
5. GPU: Compute Pass 1b (pack sort keys)
6. GPU: Compute Pass 2 (bitonic sort)
7. GPU: Compute Pass 3 (triangulate + emit)
8. GPU: Compute Pass 3b (write indirect args)
9. GPU: Render Pass (`drawIndirect`)
10. Present

Steps 4-9 are all in one command encoder, one submit.

**Scene change detection:**
- Only re-upload scene data (step 1-2) when `rootNode.isDirty` or `sceneVersion` changes
- Only update uniforms (step 3) when viewport or light changes
- Always run compute + render passes (steps 4-9) even for static scenes, because the GPU pipeline is so fast that skipping it gains nothing and adds complexity

Actually, correction: for truly static scenes (no camera movement, no animation), we can skip re-encoding entirely. Cache the command buffer and re-submit it. But this optimization is deferred — correctness first.

**Exit criteria:** Full scene renders correctly through the GPU pipeline. Visual output matches Phase 2 within acceptable tolerance.

### Milestone 7: Bypass `projectSceneAsync` for GPU render backend

**Modified files:**
- `IsometricScene.kt` — when `renderBackend == RenderBackend.WebGpu`, skip the `prepareAsync` path entirely
- `WebGpuSceneRenderer.kt` — takes raw `SceneGraph.items` instead of `PreparedScene`

**Current flow:**
```
IsometricScene → LaunchedEffect → prepareAsync → projectSceneAsync → PreparedScene
                                                                         ↓
WebGpuSceneRenderer ← State<PreparedScene?> ← asyncPreparedScene
```

**New flow:**
```
IsometricScene → sceneVersion change
                      ↓
WebGpuSceneRenderer ← State<SceneData> ← uploaded scene buffer
```

**Key change:** The renderer no longer receives `PreparedScene`. It receives the raw scene items and does all work on GPU. The `PreparedScene` path remains for `RenderBackend.Canvas` and for `ComputeBackend.WebGpu` + `RenderBackend.Canvas` (Phase 1 path).

**Interface change:**
```kotlin
// New internal interface for Phase 3 scene data
internal class GpuSceneData(
    val items: List<SceneGraph.SceneItem>,
    val projectionParams: ProjectionParams,
    val lightDirection: Vector,
    val width: Int,
    val height: Int,
    val renderOptions: RenderOptions,
)
```

**Exit criteria:** WebGPU render backend no longer calls `projectSceneAsync`. CPU-side work is limited to scene collection and buffer packing.

### Milestone 8: Performance validation and endurance testing

**Test plan:**

| Test | Target | Method |
|------|--------|--------|
| 1000-face scene frame time | < 2ms p95 | Pixel 6, animated towers, 300 frames |
| Heap stability | Flat plateau, no growth | 5-minute animated run, memory profiler |
| JNI wrapper accumulation | None | 5-minute run, monitor Dalvik heap |
| Visual parity | Acceptable | Side-by-side Canvas vs WebGPU screenshot comparison |
| Surface resize | No black screen | Rotate device mid-render |
| Activity lifecycle | No crash | Pause/resume during render |
| Backend toggle | No crash | Switch Canvas↔WebGPU during render |

**Metrics to capture:**
- GPU compute time (per pass, via timestamp queries if available)
- Total frame time (present-to-present)
- Vertex count per frame
- Visible face count per frame
- Upload size per dirty frame

**Exit criteria:** All tests pass. Frame budget met on Pixel 6.

### Milestone 9: Sample integration and documentation

**Modified files:**
- `WebGpuSampleActivity.kt` — add Phase 3 toggle
- `isometric-webgpu/build.gradle.kts` — no changes expected

**Sample changes:**
- Add "GPU Pipeline" toggle to Animated Towers sample
- Display per-frame metrics: visible faces, vertex count, frame time
- Display compute backend + render backend status

**Documentation:**
- Update `docs/internal/plans/2026-03-18-webgpu-roadmap.md` with WS13 completion status
- Add `docs/internal/reports/phase3-performance-report.md` with benchmark results
- Update sample README with Phase 3 usage instructions

---

## 8. Files To Add

```
isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/
├── pipeline/
│   ├── GpuSceneDataBuffer.kt       — FaceData packing and upload (M1)
│   ├── GpuSceneUniforms.kt         — Projection/light uniform buffer (M2)
│   ├── GpuTransformPipeline.kt     — Transform+Cull+Light compute pipeline (M3)
│   ├── GpuSortKeyPacker.kt         — Sort key extraction compute pipeline (M4)
│   ├── GpuBitonicSort.kt           — Reusable GPU-only bitonic sort (M4, extracted from GpuDepthSorter)
│   ├── GpuTriangulateEmitPipeline.kt — Triangulate+emit compute pipeline (M5)
│   └── GpuFullPipeline.kt          — Orchestrates all passes in one encoder (M6)
├── shader/
│   ├── TransformCullLightShader.kt  — WGSL source (M3)
│   ├── PackSortKeysShader.kt        — WGSL source (M4)
│   ├── TriangulateEmitShader.kt     — WGSL source (M5)
│   └── WriteIndirectArgsShader.kt   — WGSL source (M5)
```

## 9. Files To Modify

| File | Change | Milestone |
|------|--------|-----------|
| `GpuContext.kt` | Add `createForSurface()`, add limit assertion | M0 |
| `WebGpuSceneRenderer.kt` | Use `createForSurface()`, add `yield()`, integrate full GPU pipeline | M0, M6, M7 |
| `WebGpuRenderBackend.kt` | Pass scene data instead of PreparedScene for Phase 3 path | M7 |
| `GpuDepthSorter.kt` | Extract bitonic sort dispatch into `GpuBitonicSort` | M4 |
| `IsometricEngine.kt` | Expose `projectionParams` internal property | M2 |
| `IsometricProjection.kt` | Expose transformation matrix values via `ProjectionParams` | M2 |
| `IsometricScene.kt` | Skip `prepareAsync` for Phase 3 GPU pipeline | M7 |

## 10. Files NOT Changed

| File | Reason |
|------|--------|
| `RenderBackend.kt` | Interface unchanged — Phase 3 is an internal optimization |
| `SceneConfig.kt` | No new config fields needed |
| `ComputeBackend.kt` | Phase 3 is a render backend change, not a compute backend change |
| `PreparedScene.kt` | Still used by Canvas path |
| `CanvasRenderBackend.kt` | Untouched — permanent fallback |
| `IsometricVertexShader.kt` | Same vertex shader, same vertex layout |
| `IsometricFragmentShader.kt` | Same fragment shader |
| `RenderCommandTriangulator.kt` | Still used by Phase 2 fallback path; Phase 3 triangulates on GPU |

---

## 11. Testing Plan

### 11.1 Unit tests (JVM, no GPU)

| Test | What it validates |
|------|-------------------|
| `FaceDataPackingTest` | ByteBuffer layout matches WGSL struct alignment |
| `ProjectionParamsTest` | Exported projection matrix matches `translatePointInto` output |
| `NdcConversionTest` | `toNdcX`/`toNdcY` matches `RenderCommandTriangulator.toNdcX`/`toNdcY` |
| `DepthKeyFormulaTest` | GPU depth key formula matches CPU `x + y - 2z` average |

### 11.2 Instrumented tests (device, GPU required)

| Test | What it validates |
|------|-------------------|
| `TransformCullLightTest` | Compute pass output matches CPU `projectAndCull` for known scene |
| `GpuBitonicSortTest` | GPU-only sort matches CPU sort for known depth keys |
| `TriangulateEmitTest` | Vertex buffer matches `RenderCommandTriangulator.pack` output |
| `FullPipelineSmokeTest` | 100-face scene renders without crash |
| `FullPipelineVisualTest` | 100-face scene output matches Canvas baseline (screenshot diff) |

### 11.3 Endurance tests (device)

| Test | Duration | Acceptance |
|------|----------|------------|
| Animated Towers (1000 faces) | 5 minutes | No OOM, heap flat |
| Rapid resize | 2 minutes of rotation | No black screen, no crash |
| Backend toggle stress | 50 toggles | No crash, no leak |

### 11.4 Performance benchmarks (device)

| Benchmark | Target |
|-----------|--------|
| 1000-face frame time (p95) | < 2ms |
| 1000-face GPU compute time | < 1ms |
| Upload time (dirty frame) | < 0.5ms |
| Static frame (no upload) | < 0.5ms |

---

## 12. Risk Mitigations Checklist

| Risk | Mitigation | Verified |
|------|------------|----------|
| R1: Compute→render sync | Single command encoder | M6 |
| R2: Workgroup size | Spec guarantees 256 minimum | M0 |
| R3: Negative depth keys | WGSL f32 comparison handles negatives | M4 |
| R4: Non-convex faces | Document assumption; deferred | M1 |
| R5: JNI wrapper accumulation | Explicit close on all per-frame wrappers | M6 |
| R6: Lighting approximation | Accept minor visual difference | M3 |
| Atomic compaction order | Non-deterministic; sorted afterward | M3 |
| `Storage \| Vertex` buffer | Confirmed valid in vendored API | M5 |
| `Storage \| Indirect` buffer | Confirmed valid (`BufferUsage.Indirect = 0x100`) | M5 |
| Sort padding (sentinels) | Existing bitonic sort handles sentinels | M4 |

---

## 13. Recommended Implementation Order

```
M0: Resolve Phase 2 gaps (G1-G4)
 ↓
M1: FaceData packing and upload
 ↓
M2: Uniform buffer with projection parameters
 ↓  (M1 and M2 can run in parallel)
M3: Transform + Cull + Light compute shader
 ↓
M4: Sort key packing + bitonic sort (GPU-only)
 ↓
M5: Triangulate + emit vertex buffer + indirect args
 ↓
M6: Full GPU pipeline integration
 ↓
M7: Bypass projectSceneAsync for GPU render backend
 ↓
M8: Performance validation and endurance testing
 ↓
M9: Sample integration and documentation
```

Estimated effort: M0 (0.5 day), M1-M2 (1 day), M3 (2 days), M4 (1.5 days), M5 (1.5 days), M6 (2 days), M7 (1 day), M8 (1 day), M9 (0.5 day). Total: ~11 days.

---

## 14. Acceptance Criteria

Phase 3 (WS13) is complete when:

1. `RenderBackend.WebGpu` renders all existing demo scenes through the full GPU pipeline
2. CPU work per frame is limited to: scene item collection, FaceData packing, `queue.writeBuffer`, command encoding, and `queue.submit` — no projection, culling, sorting, lighting, or triangulation on CPU
3. 1000-face scene frame time < 2ms (p95) on Pixel 6
4. No known per-frame resource leak in a 5+ minute animated run
5. Canvas backend is unchanged and all existing Canvas tests pass
6. Phase 1 compute backend (`ComputeBackend.WebGpu` + `RenderBackend.Canvas`) is unchanged
7. Phase 2 render backend still works as a fallback
8. Sample app demonstrates GPU pipeline with metrics overlay

---

## 15. Source Notes

### Repo sources used
- `isometric-webgpu/src/main/kotlin/.../GpuContext.kt`
- `isometric-webgpu/src/main/kotlin/.../GpuDepthSorter.kt`
- `isometric-webgpu/src/main/kotlin/.../WebGpuSceneRenderer.kt`
- `isometric-webgpu/src/main/kotlin/.../WebGpuRenderBackend.kt`
- `isometric-webgpu/src/main/kotlin/.../sort/RadixSortShader.kt` (GPUBitonicSortShader)
- `isometric-webgpu/src/main/kotlin/.../shader/IsometricVertexShader.kt`
- `isometric-webgpu/src/main/kotlin/.../shader/IsometricFragmentShader.kt`
- `isometric-webgpu/src/main/kotlin/.../pipeline/GpuRenderPipeline.kt`
- `isometric-webgpu/src/main/kotlin/.../pipeline/GpuVertexBuffer.kt`
- `isometric-webgpu/src/main/kotlin/.../triangulation/RenderCommandTriangulator.kt`
- `isometric-core/src/main/kotlin/.../IsometricEngine.kt`
- `isometric-core/src/main/kotlin/.../IsometricProjection.kt`
- `isometric-core/src/main/kotlin/.../DepthSorter.kt`
- `isometric-core/src/main/kotlin/.../Path.kt`
- `isometric-core/src/main/kotlin/.../Point.kt`
- `isometric-core/src/main/kotlin/.../SceneGraph.kt`
- `isometric-core/src/main/kotlin/.../RenderCommand.kt`
- `isometric-core/src/main/kotlin/.../IsoColor.kt`
- `isometric-core/src/main/kotlin/.../PreparedScene.kt`
- `isometric-compose/src/main/kotlin/.../render/RenderBackend.kt`
- `isometric-compose/src/main/kotlin/.../SceneConfig.kt`
- `isometric-compose/src/main/kotlin/.../IsometricScene.kt`

### Vendored AndroidX sources verified
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUDevice.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUQueue.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUBuffer.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUCommandEncoder.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUComputePassEncoder.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPURenderPassEncoder.kt` (confirmed `drawIndirect` exists)
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUBufferDescriptor.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/BufferUsage.kt` (confirmed `Storage = 0x80`, `Vertex = 0x20`, `Indirect = 0x100`)

### External research sources
- WebGPU Indirect Draw Best Practices (toji.dev)
- Prefix Sum on Portable Compute Shaders (Raph Levien)
- WebGPU-Radix-Sort (kishimisu/GitHub)
- WGSL Atomic Types (Tour of WGSL)
- WebGPU Command Submission Design (gpuweb/GitHub)
- AndroidExternalSurface (composables.com)
- Multi-Draw-Indirect Investigation (gpuweb #4349)
- Using WebGPU Compute Shaders with Vertex Data (toji.dev)
