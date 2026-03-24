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
 * 1. Each thread reads `sortedKeys[i]`. Sentinel entries (`originalIndex == 0xFFFFFFFF`)
 *    are skipped; they sort to the tail and carry no real face data.
 * 2. The thread fetches `transformed[originalIndex]` — the [TransformedFace] written
 *    by M3 with screen-space 2D coordinates (`s0`–`s5`) and pre-computed `litColor`.
 * 3. `atomicAdd(&vertexCursor, triCount * 3)` reserves a contiguous slice of the
 *    vertex buffer for this face's triangles. The slice is thread-private; no other
 *    thread writes to the same range.
 * 4. Fan triangulation: pivot = `s0`; triangles = `(s0, s_{t+1}, s_{t+2})` for
 *    `t` in `0..triCount-1`.
 * 5. Screen-space to NDC: `ndcX = (sx / viewportWidth) * 2 - 1`,
 *    `ndcY = 1 - (sy / viewportHeight) * 2`. Matches [RenderCommandTriangulator] exactly.
 *
 * ## Vertex buffer layout
 *
 * Vertices are written as a flat `array<u32>` (8 × u32 = 32 bytes per vertex) to
 * avoid WGSL storage-buffer alignment constraints that would otherwise insert padding
 * between `position: vec2<f32>` (offset 0) and `color: vec4<f32>` (offset 8), since
 * `vec4` requires 16-byte alignment in WGSL structs but the render pipeline expects
 * it at offset 8.
 *
 * ```
 *  u32 offset  bytes  field
 *       0-1      8    position  vec2<f32>  (vertex attribute location 0)
 *       2-5     16    color     vec4<f32>  (vertex attribute location 1)
 *       6-7      8    uv        vec2<f32>  (vertex attribute location 2, always zero)
 * ```
 *
 * ## Bindings
 *
 * ```wgsl
 * @group(0) @binding(0) var<storage, read>       transformed:  array<TransformedFace>
 * @group(0) @binding(1) var<storage, read>        sortedKeys:   array<SortKey>
 * @group(0) @binding(2) var<storage, read_write>  vertices:     array<u32>
 * @group(0) @binding(3) var<storage, read_write>  vertexCursor: atomic<u32>
 * @group(0) @binding(4) var<uniform>              params:       EmitUniforms
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
        //   88-95    8   _p3, _p4 (2 × u32)
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
            _p3: u32,
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

        @group(0) @binding(0) var<storage, read>       transformed:  array<TransformedFace>;
        @group(0) @binding(1) var<storage, read>        sortedKeys:   array<SortKey>;
        // Flat u32 array: 8 u32 per vertex (32 bytes), written via bitcast<u32>(f32) to
        // preserve the render pipeline's vertex layout without WGSL struct-alignment gaps.
        @group(0) @binding(2) var<storage, read_write>  vertices:     array<u32>;
        @group(0) @binding(3) var<storage, read_write>  vertexCursor: atomic<u32>;
        @group(0) @binding(4) var<uniform>              params:       EmitUniforms;

        // Returns screen-space point at [idx] from a TransformedFace (idx in 0..5).
        fn getScreenPoint(face: TransformedFace, idx: u32) -> vec2<f32> {
            if (idx == 0u) { return face.s0; }
            if (idx == 1u) { return face.s1; }
            if (idx == 2u) { return face.s2; }
            if (idx == 3u) { return face.s3; }
            if (idx == 4u) { return face.s4; }
            return face.s5;
        }

        // Writes one vertex at flat u32 offset [base] (base must be a multiple of 8).
        // Vertex layout (32 bytes, 8 × u32, matches GpuRenderPipeline vertex attributes):
        //   u32[0-1] position vec2<f32>  (location 0, offset  0)
        //   u32[2-5] color    vec4<f32>  (location 1, offset  8)
        //   u32[6-7] uv       vec2<f32>  (location 2, offset 24, always 0)
        fn writeVertex(base: u32, x: f32, y: f32, r: f32, g: f32, b: f32, a: f32) {
            vertices[base + 0u] = bitcast<u32>(x);
            vertices[base + 1u] = bitcast<u32>(y);
            vertices[base + 2u] = bitcast<u32>(r);
            vertices[base + 3u] = bitcast<u32>(g);
            vertices[base + 4u] = bitcast<u32>(b);
            vertices[base + 5u] = bitcast<u32>(a);
            vertices[base + 6u] = 0u;
            vertices[base + 7u] = 0u;
        }

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn triangulateEmit(@builtin(global_invocation_id) gid: vec3<u32>) {
            let i = gid.x;
            if (i >= params.paddedCount) { return; }

            let key = sortedKeys[i];
            // Sentinel entries (originalIndex == 0xFFFFFFFF) were written by the M4 packer
            // for power-of-two padding.  After descending-depth sort they land at the tail;
            // each thread checks independently because threads are not ordered.
            if (key.originalIndex == 0xFFFFFFFFu) { return; }

            let face = transformed[key.originalIndex];
            let vc = face.vertexCount;
            if (vc < 3u) { return; }

            let triCount  = vc - 2u;
            let vertCount = triCount * 3u;

            // Atomically reserve a contiguous vertex slice for this face.
            // baseVertex is the index of the first reserved vertex (not the u32 offset).
            let baseVertex = atomicAdd(&vertexCursor, vertCount);

            let wF = f32(params.viewportWidth);
            let hF = f32(params.viewportHeight);

            // Fan triangulation: (s0, s_{t+1}, s_{t+2}) for t in 0..triCount-1.
            // Convert pivot (s0) to NDC once; inner vertices inside the loop.
            // NDC: x = (sx / width) * 2 - 1,  y = 1 - (sy / height) * 2.
            // Matches RenderCommandTriangulator.toNdcX/toNdcY exactly.
            let p0  = face.s0;
            let nx0 = (p0.x / wF) * 2.0 - 1.0;
            let ny0 = 1.0 - (p0.y / hF) * 2.0;

            let r = face.litColor.r;
            let g = face.litColor.g;
            let b = face.litColor.b;
            let a = face.litColor.a;

            for (var t = 0u; t < triCount; t++) {
                let p1  = getScreenPoint(face, t + 1u);
                let p2  = getScreenPoint(face, t + 2u);
                let nx1 = (p1.x / wF) * 2.0 - 1.0;
                let ny1 = 1.0 - (p1.y / hF) * 2.0;
                let nx2 = (p2.x / wF) * 2.0 - 1.0;
                let ny2 = 1.0 - (p2.y / hF) * 2.0;

                // Each vertex occupies 8 u32 slots (32 bytes).
                let vi = (baseVertex + t * 3u) * 8u;
                writeVertex(vi,        nx0, ny0, r, g, b, a);
                writeVertex(vi + 8u,   nx1, ny1, r, g, b, a);
                writeVertex(vi + 16u,  nx2, ny2, r, g, b, a);
            }
        }
    """.trimIndent()
}
