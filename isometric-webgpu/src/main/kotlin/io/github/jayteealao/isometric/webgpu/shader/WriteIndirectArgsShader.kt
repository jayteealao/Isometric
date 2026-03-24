package io.github.jayteealao.isometric.webgpu.shader

/**
 * WGSL compute shader for the M5b write-indirect-args pass.
 *
 * A single-thread dispatch that runs after [TriangulateEmitShader] has finished
 * writing all vertices. Reads the final `vertexCursor` value (the total number of
 * vertices emitted) and writes a [GPUDrawIndirectArgs] struct so the render pass can
 * call `drawIndirect` without a CPU readback.
 *
 * ## Why a separate pass?
 *
 * `vertexCursor` is an `atomic<u32>` written by up to `paddedCount` concurrent
 * threads in the emit pass. Its final value is only stable once all emit threads
 * have completed. Encoding this write as a separate compute pass — after `emitPass.end()`
 * — provides the WebGPU-guaranteed inter-pass barrier needed to observe the stable count.
 *
 * ## Bindings
 *
 * ```wgsl
 * @group(0) @binding(0) var<storage, read>       vertexCount: array<u32>
 * @group(0) @binding(1) var<storage, read_write>  indirectArgs: DrawIndirectArgs
 * ```
 *
 * The `vertexCount` binding points to the same GPU buffer as `TriangulateEmitShader`'s
 * `vertexCursor` binding, but declared as `array<u32>` (ReadOnlyStorage) since the
 * atomic operations are complete by the time this pass executes.
 */
internal object WriteIndirectArgsShader {

    /** Compute shader entry point name. */
    const val ENTRY_POINT = "writeIndirectArgs"

    /** Size of the [DrawIndirectArgs] struct in bytes (4 × u32). */
    const val INDIRECT_ARGS_BYTES = 16L

    val WGSL: String = """
        // ── DrawIndirectArgs ──────────────────────────────────────────────────────
        // Matches the GPUDrawIndirectArgs layout required by drawIndirect (16 bytes).
        struct DrawIndirectArgs {
            vertexCount:   u32,
            instanceCount: u32,
            firstVertex:   u32,
            firstInstance: u32,
        }

        // Reads the same buffer as TriangulateEmitShader's vertexCursor, but as a
        // plain ReadOnlyStorage array<u32> — atomics are complete before this pass.
        @group(0) @binding(0) var<storage, read>       vertexCount: array<u32>;
        @group(0) @binding(1) var<storage, read_write>  indirectArgs: DrawIndirectArgs;

        @compute @workgroup_size(1)
        fn writeIndirectArgs() {
            indirectArgs.vertexCount   = vertexCount[0];
            indirectArgs.instanceCount = 1u;
            indirectArgs.firstVertex   = 0u;
            indirectArgs.firstInstance = 0u;
        }
    """.trimIndent()
}
