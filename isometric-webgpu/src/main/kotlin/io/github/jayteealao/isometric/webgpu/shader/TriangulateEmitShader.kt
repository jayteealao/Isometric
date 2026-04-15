package io.github.jayteealao.isometric.webgpu.shader

import io.github.jayteealao.isometric.webgpu.triangulation.RenderCommandTriangulator

/**
 * WGSL compute shader for the M5 triangulate-and-emit pass.
 *
 * Reads the M4 sorted [SortKey] array (back-to-front order) and for each visible
 * face fan-triangulates the projected screen-space polygon into a flat `array<u32>`
 * vertex buffer consumed by the render pass. One GPU thread per sorted entry.
 *
 * ## Algorithm
 *
 * 1. Each thread reads `sortedKeys[i]`. Threads are assigned a **fixed stride** slot
 *    in the vertex buffer: `base = i * MAX_VERTICES_PER_FACE`.
 * 2. Sentinel entries (`originalIndex == 0xFFFFFFFF`) and culled faces write
 *    [MAX_VERTICES_PER_FACE] = 12 degenerate (zero-area) vertices so the render pass
 *    draws the full `paddedCount × MAX_VERTICES_PER_FACE` vertex range without gaps.
 * 3. Visible entries fetch `transformed[originalIndex]`, fan-triangulate `triCount = vc−2`
 *    triangles into the slot, and fill the remaining `12 − triCount×3` vertex positions
 *    with degenerate vertices.
 * 4. Fan triangulation: pivot = `s0`; triangles = `(s0, s_{t+1}, s_{t+2})` for
 *    `t` in `0..triCount-1`.
 * 5. Screen-space to NDC: `ndcX = (sx / viewportWidth) * 2 - 1`,
 *    `ndcY = 1 - (sy / viewportHeight) * 2`. Matches [RenderCommandTriangulator] exactly.
 *
 * ## UV coordinates
 *
 * Per-vertex UVs are read from `sceneUvCoords` (binding 6), packed as 3 × vec4 per face.
 * The user transform is applied per-vertex (`rawUV = userMatrix * vec3(baseUV, 1.0)`)
 * without `fract()`. The atlas region (scaleU, scaleV, offsetU, offsetV) from `sceneUvRegions`
 * (binding 5) is emitted as a flat `vec4<f32>` vertex attribute. The fragment shader applies
 * `atlasUV = fract(rawUV) * atlasScale + atlasOffset` per-fragment. Applying `fract()`
 * per-fragment (rather than per-vertex) is required for correct tiling: per-vertex fract
 * collapses all corners with UV=1.0 to UV=0.0, making the entire face sample one texel.
 * Faces without UV data receive default quad UVs `(0,0)(1,0)(1,1)(0,1)` padded to 6 vertex
 * slots by the CPU-side packer.
 *
 * ## Why fixed stride (no atomicAdd)?
 *
 * `atomicAdd(&vertexCursor, vertCount)` with ~290 concurrent threads contending on a
 * single GPU-side atomic causes a GPU watchdog (TDR) on Adreno 750 (Snapdragon 8 Gen 3),
 * resulting in `VK_ERROR_DEVICE_LOST`. The fixed-stride approach eliminates all atomics
 * from M5 — each thread writes to a deterministic, non-overlapping range.
 *
 * The draw call always submits `paddedCount × MAX_VERTICES_PER_FACE` vertices.
 * Degenerate triangles (three vertices at the same position) produce no visible pixels
 * and are discarded by the rasterizer at negligible cost.
 *
 * ## Why fully unrolled (no loop, no array)?
 *
 * The final emit path is kept fully unrolled up to the current face cap (6 vertices → 4
 * triangles). This avoids driver-sensitive dynamic indexing in WGSL and keeps the emitted
 * triangle fan deterministic and easy to validate.
 *
 * ## Vertex buffer layout
 *
 * Vertices are written as a flat `array<u32>` (14 × u32 = 56 bytes per vertex) to
 * avoid WGSL storage-buffer alignment constraints that would otherwise insert padding
 * between `position: vec2<f32>` (offset 0) and `color: vec4<f32>` (offset 8), since
 * `vec4` requires 16-byte alignment in WGSL structs but the render pipeline expects
 * it at offset 8.
 *
 * ```
 *  u32 offset  bytes  field
 *       0-1      8    position      vec2<f32>  (vertex attribute location 0)
 *       2-5     16    color         vec4<f32>  (vertex attribute location 1)
 *       6-7      8    rawUV         vec2<f32>  (vertex attribute location 2, pre-atlas UV)
 *      8-11     16    atlasRegion   vec4<f32>  (vertex attribute location 3, flat: scaleU,scaleV,offsetU,offsetV)
 *        12      4    textureIndex  u32        (vertex attribute location 4)
 *        13      4    _padding      u32
 * ```
 *
 * ## Bindings
 *
 * ```wgsl
 * @group(0) @binding(0) var<storage, read>       transformed:      array<TransformedFace>
 * @group(0) @binding(1) var<storage, read>        sortedKeys:       array<SortKey>
 * @group(0) @binding(2) var<storage, read_write>  vertices:         array<u32>
 * @group(0) @binding(3) var<uniform>              params:           EmitUniforms
 * @group(0) @binding(4) var<storage, read>        sceneTexIndices:  array<u32>
 * @group(0) @binding(5) var<storage, read>        sceneUvRegions:   array<UvRegion>
 * @group(0) @binding(6) var<storage, read>        sceneUvCoords:    array<vec4<f32>>
 * ```
 *
 * ## Struct compatibility
 *
 * `TransformedFace` here **must** match `TransformCullLightShader.WGSL` byte-for-byte.
 * `SortKey` here **must** match `GPUBitonicSortShader.WGSL` byte-for-byte.
 */
internal object TriangulateEmitShader {

    /** Compute shader entry point name. */
    const val ENTRY_POINT = "triangulateEmit"

    /** Threads per workgroup. */
    const val WORKGROUP_SIZE = 256

    /**
     * Bytes per vertex in the output buffer.
     * Delegates to [RenderCommandTriangulator.BYTES_PER_VERTEX] — the single authoritative
     * source also used by [GpuRenderPipeline]'s vertex buffer stride declaration.
     */
    const val BYTES_PER_VERTEX = RenderCommandTriangulator.BYTES_PER_VERTEX

    /**
     * Maximum triangles producible from a single face (6-vertex hexagonal face → 4 triangles).
     * Used to size the output vertex buffer conservatively.
     */
    const val MAX_TRIANGLES_PER_FACE = 4

    /** Maximum vertices a single face can emit. */
    const val MAX_VERTICES_PER_FACE = MAX_TRIANGLES_PER_FACE * 3  // = 12

    val WGSL: String = """
        // ── TransformedFace ───────────────────────────────────────────────────────
        // 96 bytes per face.  Must match TransformCullLightShader.WGSL exactly.
        //
        //  offset  size  field
        //    0-47   48   s0–s5 (6 × vec2<f32>)
        //   48-63   16   vertexCount + _p0–_p2 (4 × u32)
        //   64-79   16   litColor (vec4<f32>)
        //   80-83    4   depthKey (f32)
        //   84-87    4   faceIndex (u32)
        //   88-91    4   visible (u32)   ← 1 = passed cull, 0 = culled; not read here
        //   92-95    4   _p4 (u32)
        struct TransformedFace {
            s0: vec2<f32>,
            s1: vec2<f32>,
            s2: vec2<f32>,
            s3: vec2<f32>,
            s4: vec2<f32>,
            s5: vec2<f32>,
            vertexCount: u32,
            _p0: u32,
            _p1: u32,
            _p2: u32,
            litColor: vec4<f32>,
            depthKey:  f32,
            faceIndex: u32,
            visible:   u32,
            _p4: u32,
        }

        // ── SortKey ───────────────────────────────────────────────────────────────
        // 16 bytes per entry.  Must match GPUBitonicSortShader.WGSL exactly.
        struct SortKey {
            depth:         f32,
            originalIndex: u32,
            pad0:          u32,
            pad1:          u32,
        }

        // ── EmitUniforms ──────────────────────────────────────────────────────────
        // Rebuilt in GpuTriangulateEmitPipeline.ensureBuffers on any change.
        struct EmitUniforms {
            viewportWidth:  u32,
            viewportHeight: u32,
            paddedCount:    u32,
            _pad:           u32,
        }

        // ── UvRegion ──────────────────────────────────────────────────────────────
        // 40 bytes per entry: user transform (mat3x2 = 24 bytes) + atlas region (2×vec2 = 16 bytes).
        // CPU layout: [col0.x, col0.y, col1.x, col1.y, col2.x, col2.y, scaleU, scaleV, offsetU, offsetV]
        // Apply as: atlasUV = fract(userMatrix * vec3(baseUV, 1.0)) * atlasScale + atlasOffset
        struct UvRegion {
            userMatrix  : mat3x2<f32>,
            atlasScale  : vec2<f32>,
            atlasOffset : vec2<f32>,
        }

        // Read transformed data as a flat vec4<f32> array. TransformedFace is 96 bytes = 6 ×
        // vec4<f32>, so the layout is:
        // TransformedFace is 96 bytes = 6 × vec4<f32>, so:
        //   raw[i*6+0] = (s0.x, s0.y, s1.x, s1.y)
        //   raw[i*6+1] = (s2.x, s2.y, s3.x, s3.y)
        //   raw[i*6+2] = (s4.x, s4.y, s5.x, s5.y)
        //   raw[i*6+3] = (bitcast vertexCount, _p0, _p1, _p2)
        //   raw[i*6+4] = litColor (r, g, b, a)
        //   raw[i*6+5] = (depthKey, bitcast faceIndex, bitcast visible, _p4)
        @group(0) @binding(0) var<storage, read>       transformedRaw: array<vec4<f32>>;
        @group(0) @binding(1) var<storage, read>        sortedKeys:  array<SortKey>;
        // Flat u32 array: 9 u32 per vertex (36 bytes), written via bitcast<u32>(f32) to
        // preserve the render pipeline's vertex layout without WGSL struct-alignment gaps.
        @group(0) @binding(2) var<storage, read_write>  vertices:    array<u32>;
        @group(0) @binding(3) var<uniform>              params:      EmitUniforms;
        // Compact per-face texture index buffer, indexed by originalIndex.
        @group(0) @binding(4) var<storage, read>        sceneTexIndices: array<u32>;
        // Compact per-face UV region buffer, indexed by originalIndex.
        // Each UvRegion stores the user TextureTransform and atlas sub-region separately.
        // Apply as: fract(userMatrix * vec3(baseUV, 1.0)) * atlasScale + atlasOffset
        @group(0) @binding(5) var<storage, read>        sceneUvRegions: array<UvRegion>;
        // Per-vertex UV coordinates: 3 × vec4 per face = (u0,v0,u1,v1)(u2,v2,u3,v3)(u4,v4,u5,v5)
        @group(0) @binding(6) var<storage, read>        sceneUvCoords: array<vec4<f32>>;

        // Writes one vertex at flat u32 offset [base] (base must be a multiple of 14).
        // Vertex layout (56 bytes, 14 × u32, matches GpuRenderPipeline vertex attributes):
        //   u32[0-1]  position     vec2<f32>  (location 0, offset  0)
        //   u32[2-5]  color        vec4<f32>  (location 1, offset  8)
        //   u32[6-7]  rawUV        vec2<f32>  (location 2, offset 24) — pre-atlas UV after user matrix
        //   u32[8-11] atlasRegion  vec4<f32>  (location 3, offset 32) — flat: scaleU,scaleV,offsetU,offsetV
        //   u32[12]   textureIndex u32        (location 4, offset 48)
        //   u32[13]   _padding     u32
        fn writeVertex(base: u32, x: f32, y: f32, r: f32, g: f32, b: f32, a: f32,
                       u: f32, v: f32, asU: f32, asV: f32, aoU: f32, aoV: f32, texIdx: u32) {
            vertices[base + 0u]  = bitcast<u32>(x);
            vertices[base + 1u]  = bitcast<u32>(y);
            vertices[base + 2u]  = bitcast<u32>(r);
            vertices[base + 3u]  = bitcast<u32>(g);
            vertices[base + 4u]  = bitcast<u32>(b);
            vertices[base + 5u]  = bitcast<u32>(a);
            vertices[base + 6u]  = bitcast<u32>(u);
            vertices[base + 7u]  = bitcast<u32>(v);
            vertices[base + 8u]  = bitcast<u32>(asU);
            vertices[base + 9u]  = bitcast<u32>(asV);
            vertices[base + 10u] = bitcast<u32>(aoU);
            vertices[base + 11u] = bitcast<u32>(aoV);
            vertices[base + 12u] = texIdx;
            vertices[base + 13u] = 0u;
        }

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn triangulateEmit(@builtin(global_invocation_id) gid: vec3<u32>) {
            let i = gid.x;
            if (i >= params.paddedCount) { return; }

            // Fixed-stride slot: each sort entry owns exactly MAX_VERTICES_PER_FACE=12
            // vertex positions.  No atomicAdd — no contention, no Adreno TDR.
            let base = i * 12u;
            let key = sortedKeys[i];
            if (key.originalIndex == 0xFFFFFFFFu) {
                for (var j = 0u; j < 12u; j++) {
                    writeVertex((base + j) * 14u, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0xFFFFFFFFu);
                }
                return;
            }

            let ri = key.originalIndex * 6u;
            let wF = f32(params.viewportWidth);
            let hF = f32(params.viewportHeight);

            let v01 = transformedRaw[ri + 0u];  // (s0.x, s0.y, s1.x, s1.y)
            let v23 = transformedRaw[ri + 1u];  // (s2.x, s2.y, s3.x, s3.y)
            let v45 = transformedRaw[ri + 2u];  // (s4.x, s4.y, s5.x, s5.y)
            let metaVec = transformedRaw[ri + 3u];
            let clr = transformedRaw[ri + 4u];  // litColor
            let tail = transformedRaw[ri + 5u];

            let vertexCount = bitcast<u32>(metaVec.x);
            let visible = bitcast<u32>(tail.z);
            if (visible == 0u || vertexCount < 3u) {
                for (var j = 0u; j < 12u; j++) {
                    writeVertex((base + j) * 14u, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0xFFFFFFFFu);
                }
                return;
            }

            // Read per-face texture index and UV region from compact buffers
            let texIdx   = sceneTexIndices[key.originalIndex];
            let uvRegion = sceneUvRegions[key.originalIndex];

            let nx0 = (v01.x / wF) * 2.0 - 1.0;
            let ny0 = 1.0 - (v01.y / hF) * 2.0;
            let nx1 = (v01.z / wF) * 2.0 - 1.0;
            let ny1 = 1.0 - (v01.w / hF) * 2.0;
            let nx2 = (v23.x / wF) * 2.0 - 1.0;
            let ny2 = 1.0 - (v23.y / hF) * 2.0;
            let nx3 = (v23.z / wF) * 2.0 - 1.0;
            let ny3 = 1.0 - (v23.w / hF) * 2.0;
            let nx4 = (v45.x / wF) * 2.0 - 1.0;
            let ny4 = 1.0 - (v45.y / hF) * 2.0;
            let nx5 = (v45.z / wF) * 2.0 - 1.0;
            let ny5 = 1.0 - (v45.w / hF) * 2.0;

            let r = clr.x;
            let g = clr.y;
            let b = clr.z;
            let a = clr.w;

            // Write real triangles first, then fill remaining slots with degenerates.
            // This avoids the previous pattern of clearing all 12 slots then overwriting,
            // reducing storage writes by ~33% for the common quad case.

            // Per-vertex UVs from UvGenerator, packed as 3 × vec4 per face.
            // Apply user matrix per-vertex without fract(). fract() must happen per-fragment in
            // the render pass because fract(1.0) == 0.0 — applying it per-vertex collapses all
            // face corners (UV = 1.0) to UV = 0.0, making the entire face sample one texel.
            // The atlas region is emitted as a flat vertex attribute for the fragment shader.
            let uvBase   = key.originalIndex * 3u;
            let uvPack0  = sceneUvCoords[uvBase + 0u]; // (u0,v0,u1,v1)
            let uvPack1  = sceneUvCoords[uvBase + 1u]; // (u2,v2,u3,v3)
            let uvPack2  = sceneUvCoords[uvBase + 2u]; // (u4,v4,u5,v5)
            let uv0 = uvRegion.userMatrix * vec3<f32>(uvPack0.x, uvPack0.y, 1.0);
            let uv1 = uvRegion.userMatrix * vec3<f32>(uvPack0.z, uvPack0.w, 1.0);
            let uv2 = uvRegion.userMatrix * vec3<f32>(uvPack1.x, uvPack1.y, 1.0);
            let uv3 = uvRegion.userMatrix * vec3<f32>(uvPack1.z, uvPack1.w, 1.0);
            let uv4 = uvRegion.userMatrix * vec3<f32>(uvPack2.x, uvPack2.y, 1.0);
            let uv5 = uvRegion.userMatrix * vec3<f32>(uvPack2.z, uvPack2.w, 1.0);
            let asU = uvRegion.atlasScale.x;
            let asV = uvRegion.atlasScale.y;
            let aoU = uvRegion.atlasOffset.x;
            let aoV = uvRegion.atlasOffset.y;

            // Triangle 0: (s0, s1, s2) — always present (vertexCount >= 3)
            writeVertex((base + 0u) * 14u, nx0, ny0, r, g, b, a, uv0.x, uv0.y, asU, asV, aoU, aoV, texIdx);
            writeVertex((base + 1u) * 14u, nx1, ny1, r, g, b, a, uv1.x, uv1.y, asU, asV, aoU, aoV, texIdx);
            writeVertex((base + 2u) * 14u, nx2, ny2, r, g, b, a, uv2.x, uv2.y, asU, asV, aoU, aoV, texIdx);

            // Compute how many real triangles we have (1–4 based on vertexCount 3–6)
            let triCount = vertexCount - 2u;

            // Triangle 1: (s0, s2, s3)
            if (triCount >= 2u) {
                writeVertex((base + 3u) * 14u, nx0, ny0, r, g, b, a, uv0.x, uv0.y, asU, asV, aoU, aoV, texIdx);
                writeVertex((base + 4u) * 14u, nx2, ny2, r, g, b, a, uv2.x, uv2.y, asU, asV, aoU, aoV, texIdx);
                writeVertex((base + 5u) * 14u, nx3, ny3, r, g, b, a, uv3.x, uv3.y, asU, asV, aoU, aoV, texIdx);
            }

            // Triangle 2: (s0, s3, s4) — for 5+ vertex faces (pentagon)
            if (triCount >= 3u) {
                writeVertex((base + 6u) * 14u, nx0, ny0, r, g, b, a, uv0.x, uv0.y, asU, asV, aoU, aoV, texIdx);
                writeVertex((base + 7u) * 14u, nx3, ny3, r, g, b, a, uv3.x, uv3.y, asU, asV, aoU, aoV, texIdx);
                writeVertex((base + 8u) * 14u, nx4, ny4, r, g, b, a, uv4.x, uv4.y, asU, asV, aoU, aoV, texIdx);
            }

            // Triangle 3: (s0, s4, s5) — for 6-vertex faces (hexagon)
            if (triCount >= 4u) {
                writeVertex((base + 9u) * 14u, nx0, ny0, r, g, b, a, uv0.x, uv0.y, asU, asV, aoU, aoV, texIdx);
                writeVertex((base + 10u) * 14u, nx4, ny4, r, g, b, a, uv4.x, uv4.y, asU, asV, aoU, aoV, texIdx);
                writeVertex((base + 11u) * 14u, nx5, ny5, r, g, b, a, uv5.x, uv5.y, asU, asV, aoU, aoV, texIdx);
            }

            // Fill remaining degenerate slots (from triCount*3 to 11)
            let firstDegen = triCount * 3u;
            for (var j = firstDegen; j < 12u; j++) {
                writeVertex((base + j) * 14u, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0xFFFFFFFFu);
            }
        }
    """.trimIndent()
}
