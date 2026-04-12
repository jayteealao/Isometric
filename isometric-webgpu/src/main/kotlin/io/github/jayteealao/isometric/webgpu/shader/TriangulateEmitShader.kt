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
 * Standard Prism quad UVs are emitted as constants: `(0,0)(1,0)(1,1)(0,1)` for the
 * 4-vertex fan. This matches the CPU-side uv-generation output for all standard Prism
 * faces. Custom UV transforms require a GPU-side UV buffer in a future slice.
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
 * Vertices are written as a flat `array<u32>` (9 × u32 = 36 bytes per vertex) to
 * avoid WGSL storage-buffer alignment constraints that would otherwise insert padding
 * between `position: vec2<f32>` (offset 0) and `color: vec4<f32>` (offset 8), since
 * `vec4` requires 16-byte alignment in WGSL structs but the render pipeline expects
 * it at offset 8.
 *
 * ```
 *  u32 offset  bytes  field
 *       0-1      8    position      vec2<f32>  (vertex attribute location 0)
 *       2-5     16    color         vec4<f32>  (vertex attribute location 1)
 *       6-7      8    uv            vec2<f32>  (vertex attribute location 2)
 *         8       4    textureIndex  u32        (vertex attribute location 3)
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
 * @group(0) @binding(5) var<storage, read>        sceneUvRegions:   array<vec4<f32>>
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
        // Each vec4 = (uvOffsetU, uvOffsetV, uvScaleU, uvScaleV).
        @group(0) @binding(5) var<storage, read>        sceneUvRegions: array<vec4<f32>>;

        // Writes one vertex at flat u32 offset [base] (base must be a multiple of 9).
        // Vertex layout (36 bytes, 9 × u32, matches GpuRenderPipeline vertex attributes):
        //   u32[0-1] position     vec2<f32>  (location 0, offset  0)
        //   u32[2-5] color        vec4<f32>  (location 1, offset  8)
        //   u32[6-7] uv           vec2<f32>  (location 2, offset 24)
        //   u32[8]   textureIndex u32        (location 3, offset 32)
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
                    writeVertex((base + j) * 9u, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0xFFFFFFFFu);
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
                    writeVertex((base + j) * 9u, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0xFFFFFFFFu);
                }
                return;
            }

            // Read per-face texture index and UV region from compact buffers
            let texIdx = sceneTexIndices[key.originalIndex];
            let uvRegion = sceneUvRegions[key.originalIndex];
            let uvOff = uvRegion.xy;   // (uvOffsetU, uvOffsetV)
            let uvSc  = uvRegion.zw;   // (uvScaleU, uvScaleV)

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

            // Base UVs for quad fan: (0,0)(1,0)(1,1)(0,1)
            // Atlas transform: atlasUV = baseUV * uvScale + uvOffset
            let uv00 = vec2<f32>(0.0, 0.0) * uvSc + uvOff;
            let uv10 = vec2<f32>(1.0, 0.0) * uvSc + uvOff;
            let uv11 = vec2<f32>(1.0, 1.0) * uvSc + uvOff;
            let uv01 = vec2<f32>(0.0, 1.0) * uvSc + uvOff;

            // Triangle 0: (s0, s1, s2) — always present (vertexCount >= 3)
            writeVertex((base + 0u) * 9u, nx0, ny0, r, g, b, a, uv00.x, uv00.y, texIdx);
            writeVertex((base + 1u) * 9u, nx1, ny1, r, g, b, a, uv10.x, uv10.y, texIdx);
            writeVertex((base + 2u) * 9u, nx2, ny2, r, g, b, a, uv11.x, uv11.y, texIdx);

            // Compute how many real triangles we have (1–4 based on vertexCount 3–6)
            let triCount = vertexCount - 2u;

            // Triangle 1: (s0, s2, s3)
            if (triCount >= 2u) {
                writeVertex((base + 3u) * 9u, nx0, ny0, r, g, b, a, uv00.x, uv00.y, texIdx);
                writeVertex((base + 4u) * 9u, nx2, ny2, r, g, b, a, uv11.x, uv11.y, texIdx);
                writeVertex((base + 5u) * 9u, nx3, ny3, r, g, b, a, uv01.x, uv01.y, texIdx);
            }

            // Triangle 2: (s0, s3, s4) — for 5+ vertex faces (pentagon)
            if (triCount >= 3u) {
                let uvMid = vec2<f32>(0.5, 0.5) * uvSc + uvOff;
                writeVertex((base + 6u) * 9u, nx0, ny0, r, g, b, a, uv00.x, uv00.y, texIdx);
                writeVertex((base + 7u) * 9u, nx3, ny3, r, g, b, a, uvMid.x, uvMid.y, texIdx);
                writeVertex((base + 8u) * 9u, nx4, ny4, r, g, b, a, uvMid.x, uvMid.y, texIdx);
            }

            // Triangle 3: (s0, s4, s5) — for 6-vertex faces (hexagon)
            if (triCount >= 4u) {
                let uvMid = vec2<f32>(0.5, 0.5) * uvSc + uvOff;
                writeVertex((base + 9u) * 9u, nx0, ny0, r, g, b, a, uv00.x, uv00.y, texIdx);
                writeVertex((base + 10u) * 9u, nx4, ny4, r, g, b, a, uvMid.x, uvMid.y, texIdx);
                writeVertex((base + 11u) * 9u, nx5, ny5, r, g, b, a, uvMid.x, uvMid.y, texIdx);
            }

            // Fill remaining degenerate slots (from triCount*3 to 11)
            let firstDegen = triCount * 3u;
            for (var j = firstDegen; j < 12u; j++) {
                writeVertex((base + j) * 9u, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0xFFFFFFFFu);
            }
        }
    """.trimIndent()
}
