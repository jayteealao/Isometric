package io.github.jayteealao.isometric.webgpu.shader

/**
 * WGSL compute shader that packs the M3 `TransformedFace` output into sort key tuples
 * consumable by [GPUBitonicSortShader].
 *
 * One GPU thread per entry in the padded sort key array:
 * - Threads where `i < params.faceCount && transformed[i].visible != 0u`: copy
 *   `transformed[i].depthKey` and use `i` as `originalIndex` (the slot index,
 *   used by M5 to fetch the face from `transformed[originalIndex]`).
 * - All other threads (culled, out-of-range, or padding): write a sentinel
 *   (`Float.NEGATIVE_INFINITY`, `0xFFFFFFFF`) so they sort to the end of the
 *   descending-depth bitonic sort and are skipped by M5.
 *
 * ## Bindings
 *
 * ```wgsl
 * @group(0) @binding(0) var<storage, read>       transformed: array<TransformedFace>
 * @group(0) @binding(1) var<storage, read_write>  sortKeys:    array<SortKey>
 * @group(0) @binding(2) var<uniform>              params:      PackParams
 * ```
 *
 * ## Struct compatibility
 *
 * `TransformedFace` here **must** match `TransformCullLightShader.WGSL` byte-for-byte.
 * `SortKey` here **must** match `GPUBitonicSortShader.WGSL` byte-for-byte.
 * Any layout change requires updating both shaders together.
 */
internal object PackSortKeysShader {

    /** Compute shader entry point name. */
    const val ENTRY_POINT = "packSortKeys"

    /** Threads per workgroup — matches [GPUBitonicSortShader.WORKGROUP_SIZE]. */
    const val WORKGROUP_SIZE = 256

    val WGSL: String = """
        // ── TransformedFace ───────────────────────────────────────────────────────
        // 96 bytes per face.  Must match TransformCullLightShader.WGSL exactly.
        //
        //  offset  size  field
        //    0-47   48   s0–s5 (6 × vec2<f32>)
        //   48-63   16   vertexCount + _p0–_p2 (4 × u32)
        //   64-79   16   litColor (vec4<f32>)
        //   80-83    4   depthKey (f32)   ← extracted here
        //   84-87    4   faceIndex (u32)
        //   88-91    4   visible (u32)    ← 1 = passed cull, 0 = culled
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

        // ── PackParams ────────────────────────────────────────────────────────────
        // paddedCount: power-of-2 sort array size.
        // faceCount:   actual scene face count (bounds-check for transformed[]).
        struct PackParams {
            paddedCount: u32,
            faceCount:   u32,
            pad0:        u32,
            pad1:        u32,
        }

        @group(0) @binding(0) var<storage, read>       transformed: array<TransformedFace>;
        @group(0) @binding(1) var<storage, read_write>  sortKeys:    array<SortKey>;
        @group(0) @binding(2) var<uniform>              params:      PackParams;

        @compute @workgroup_size(256)
        fn packSortKeys(@builtin(global_invocation_id) gid: vec3<u32>) {
            let i = gid.x;
            if (i >= params.paddedCount) { return; }

            if (i < params.faceCount && transformed[i].visible != 0u) {
                // Real visible face: depth key from M3, slot index as originalIndex so
                // M5 can fetch transformed[originalIndex] in back-to-front order.
                sortKeys[i] = SortKey(transformed[i].depthKey, i, 0u, 0u);
            } else {
                // Sentinel: Float.NEGATIVE_INFINITY = 0xFF800000 in IEEE 754.
                // Descending sort places these at the end; M5 skips them via
                // the sentinel check (originalIndex == 0xFFFFFFFF).
                //
                // Use a var (mutable) to prevent Tint from constant-folding
                // bitcast<f32>(0xFF800000u) to -inf, which Tint rejects in
                // constant expressions.
                var sentinelBits: u32 = 0xFF800000u;
                sortKeys[i] = SortKey(bitcast<f32>(sentinelBits), 0xFFFFFFFFu, 0u, 0u);
            }
        }
    """.trimIndent()
}
