package io.github.jayteealao.isometric.webgpu.sort

/**
 * WGSL shader source for a 32-bit floating-point GPU radix sort.
 *
 * The sort operates on `SortKey` structs containing a depth float and the original
 * face index. Four passes (8 bits per pass) sort keys from least-significant to
 * most-significant byte.
 *
 * Each pass requires three dispatches:
 * 1. [COUNT_ENTRY_POINT] — count histogram of the current byte
 * 2. CPU-side prefix sum on histogram readback (Phase 1 simplicity; GPU scan in Phase 3)
 * 3. [SCATTER_ENTRY_POINT] — scatter keys to output buffer using prefix-summed offsets
 *
 * ## Float sort correctness
 *
 * IEEE 754 floats sort correctly as u32 bit patterns for positive values. All projected
 * Z depths in standard isometric scenes are positive (geometry above z = 0).
 */
internal object RadixSortShader {

    const val COUNT_ENTRY_POINT = "countPass"
    const val SCATTER_ENTRY_POINT = "scatterPass"
    const val WORKGROUP_SIZE = 256

    val WGSL: String = """
        struct SortKey {
            depth: f32,
            originalIndex: u32,
        }

        @group(0) @binding(0) var<storage, read>       keys_in:   array<SortKey>;
        @group(0) @binding(1) var<storage, read_write>  keys_out:  array<SortKey>;
        @group(0) @binding(2) var<storage, read_write>  histogram: array<atomic<u32>>;
        @group(0) @binding(3) var<uniform>              params:    RadixParams;

        struct RadixParams {
            count:    u32,
            bitShift: u32,
        }

        const RADIX: u32 = 256u;

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn ${COUNT_ENTRY_POINT}(@builtin(global_invocation_id) id: vec3<u32>) {
            if (id.x >= params.count) { return; }
            let key = keys_in[id.x];
            let bits = bitcast<u32>(key.depth);
            let bucket = (bits >> params.bitShift) & 0xFFu;
            atomicAdd(&histogram[bucket], 1u);
        }

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn ${SCATTER_ENTRY_POINT}(@builtin(global_invocation_id) id: vec3<u32>) {
            if (id.x >= params.count) { return; }
            let key = keys_in[id.x];
            let bits = bitcast<u32>(key.depth);
            let bucket = (bits >> params.bitShift) & 0xFFu;
            let pos = atomicAdd(&histogram[bucket], 1u);
            keys_out[pos] = key;
        }
    """.trimIndent()
}
