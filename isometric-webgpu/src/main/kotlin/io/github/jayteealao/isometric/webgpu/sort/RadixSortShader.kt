package io.github.jayteealao.isometric.webgpu.sort

/**
 * WGSL shader source for a ping-pong GPU bitonic sort over 16-byte sort-key tuples.
 *
 * Kotlin drives the outer `k/j` network loops and writes the current stage parameters to
 * a small uniform buffer before each dispatch. The shader performs a single compare-swap
 * stage from one storage buffer into another. Final order is descending by depth, with
 * `originalIndex` as a deterministic tie-breaker.
 */
internal object GPUBitonicSortShader {

    const val SORT_ENTRY_POINT = "bitonicStage"
    const val WORKGROUP_SIZE = 256

    val WGSL: String = """
        struct SortKey {
            depth: f32,
            originalIndex: u32,
            pad0: u32,
            pad1: u32,
        }

        struct SortParams {
            j: u32,
            k: u32,
            count: u32,
            _pad: u32,
        }

        @group(0) @binding(0) var<storage, read> inputKeys: array<SortKey>;
        @group(0) @binding(1) var<storage, read_write> outputKeys: array<SortKey>;
        @group(0) @binding(2) var<uniform> params: SortParams;

        fn shouldSwapAscending(a: SortKey, b: SortKey) -> bool {
            if (a.depth > b.depth) {
                return true;
            }
            if (a.depth < b.depth) {
                return false;
            }
            return a.originalIndex > b.originalIndex;
        }

        fn shouldSwapDescending(a: SortKey, b: SortKey) -> bool {
            if (a.depth < b.depth) {
                return true;
            }
            if (a.depth > b.depth) {
                return false;
            }
            return a.originalIndex > b.originalIndex;
        }

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn ${SORT_ENTRY_POINT}(@builtin(global_invocation_id) id: vec3<u32>) {
            let i = id.x;
            if (i >= params.count) { return; }

            let partner = i ^ params.j;
            if (partner >= params.count) {
                outputKeys[i] = inputKeys[i];
                return;
            }

            let lowerIndex = min(i, partner);
            let upperIndex = max(i, partner);
            let lowerValue = inputKeys[lowerIndex];
            let upperValue = inputKeys[upperIndex];
            let descending = (lowerIndex & params.k) == 0u;

            var first = lowerValue;
            var second = upperValue;

            if (descending) {
                if (shouldSwapDescending(lowerValue, upperValue)) {
                    first = upperValue;
                    second = lowerValue;
                }
            } else {
                if (shouldSwapAscending(lowerValue, upperValue)) {
                    first = upperValue;
                    second = lowerValue;
                }
            }

            if (i == lowerIndex) {
                outputKeys[i] = first;
            } else {
                outputKeys[i] = second;
            }
        }
    """.trimIndent()
}
