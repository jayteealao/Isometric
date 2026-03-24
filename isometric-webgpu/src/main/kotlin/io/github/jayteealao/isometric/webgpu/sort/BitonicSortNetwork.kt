package io.github.jayteealao.isometric.webgpu.sort

import androidx.webgpu.BufferBindingType
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUDevice
import java.nio.ByteOrder

/**
 * Shared utilities for building a ping-pong bitonic sort network over 16-byte [SortKey] tuples.
 *
 * Extracted to eliminate duplication between [GpuDepthSorter] (Phase 1 CPU-upload path)
 * and [GpuBitonicSort] (Phase 3 GPU-only path). Both share the same bitonic network
 * topology, params buffer layout, and ping-pong bind group pattern.
 *
 * ## Sort key layout (16 bytes)
 * ```
 *  offset  size  field
 *    0      4    depth (f32)          — sort key
 *    4      4    originalIndex (u32)  — tie-breaker; sentinel = [SENTINEL_INDEX]
 *    8      4    pad0 (u32)
 *   12      4    pad1 (u32)
 * ```
 *
 * ## Params buffer layout (16 bytes per stage, spaced at [UNIFORM_OFFSET_ALIGNMENT])
 * ```
 *  offset  field
 *    0     j (u32)
 *    4     k (u32)
 *    8     count (u32)
 *   12     _pad (u32)
 * ```
 */
internal object BitonicSortNetwork {

    /** Bytes per SortKey entry in the sort buffer. */
    const val SORT_KEY_BYTES = 16L

    /** Bytes used by the per-stage params struct `{ j, k, count, _pad }`. */
    const val PARAMS_BYTES = 16

    /**
     * Spacing between per-stage params in the packed uniform buffer.
     * WebGPU mandates `minUniformBufferOffsetAlignment = 256` on all implementations.
     */
    const val UNIFORM_OFFSET_ALIGNMENT = 256L

    /** `originalIndex` value used for padding entries that carry no real face data. */
    const val SENTINEL_INDEX: Int = -1 // 0xFFFFFFFF as u32

    // ── Core algorithms ──────────────────────────────────────────────────────

    /**
     * Return the smallest power of two ≥ [value].
     *
     * @throws IllegalArgumentException if [value] ≤ 0.
     */
    fun nextPowerOfTwo(value: Int): Int {
        require(value > 0) { "value must be positive, got $value" }
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    /**
     * Build the list of `(j, k)` pairs for the full bitonic sort network over [paddedCount]
     * elements. [paddedCount] must be a power of two.
     *
     * The total number of stages is `sum(log2(k) for k = 2, 4, …, paddedCount)`.
     * For paddedCount = 2048 this is 121 stages.
     */
    fun buildStages(paddedCount: Int): List<Pair<Int, Int>> = buildList {
        var k = 2
        while (k <= paddedCount) {
            var j = k / 2
            while (j > 0) {
                add(j to k)
                j /= 2
            }
            k *= 2
        }
    }

    // ── GPU resource builders ────────────────────────────────────────────────

    /**
     * Create a single immutable uniform buffer containing all per-stage params at aligned offsets.
     *
     * Uses `mappedAtCreation` for a zero-copy upload. The content is constant for a given
     * [paddedCount] and [stages] list, so it can be created once and reused across frames.
     *
     * @param device     GPU device to allocate the buffer on.
     * @param stages     Stage list from [buildStages].
     * @param paddedCount Power-of-two element count written into each stage's `count` field.
     * @param label      Optional Dawn debug label.
     */
    fun createParamsBuffer(
        device: GPUDevice,
        stages: List<Pair<Int, Int>>,
        paddedCount: Int,
        label: String? = null,
    ): GPUBuffer {
        val bufferSize = stages.size.toLong() * UNIFORM_OFFSET_ALIGNMENT
        val buffer = device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Uniform,
                size = bufferSize,
                mappedAtCreation = true,
            )
        )
        if (label != null) buffer.setLabel(label)
        val mapped = buffer.getMappedRange(0L, bufferSize).order(ByteOrder.nativeOrder())
        for ((idx, stage) in stages.withIndex()) {
            mapped.position((idx * UNIFORM_OFFSET_ALIGNMENT).toInt())
            mapped.putInt(stage.first)    // j
            mapped.putInt(stage.second)   // k
            mapped.putInt(paddedCount)    // count
            mapped.putInt(0)              // _pad
        }
        buffer.unmap()
        return buffer
    }

    /**
     * Pre-build all bind groups for the bitonic sort stages.
     *
     * The ping-pong pattern is deterministic:
     * - Even stages (0, 2, …): read [primaryBuf] → write [scratchBuf]
     * - Odd  stages (1, 3, …): read [scratchBuf] → write [primaryBuf]
     *
     * All referenced buffers must be stable (not re-allocated) across all frames that
     * use these bind groups. They are invalidated when [primaryBuf] or [scratchBuf]
     * changes (i.e., on capacity growth).
     *
     * The BGL must expose bindings `0` (ReadOnlyStorage), `1` (Storage), `2` (Uniform).
     *
     * @param device        GPU device.
     * @param stageCount    Number of bitonic stages (`stages.size` from [buildStages]).
     * @param primaryBuf    Primary sort buffer (initial data source; read on even stages).
     * @param scratchBuf    Scratch sort buffer (ping-pong target; read on odd stages).
     * @param paramsBuffer  Packed params uniform buffer from [createParamsBuffer].
     * @param bgl           Bind group layout matching the sort shader.
     */
    fun buildBindGroups(
        device: GPUDevice,
        stageCount: Int,
        primaryBuf: GPUBuffer,
        scratchBuf: GPUBuffer,
        paramsBuffer: GPUBuffer,
        bgl: GPUBindGroupLayout,
    ): Array<GPUBindGroup> = Array(stageCount) { idx ->
        val (inputBuf, outputBuf) =
            if (idx % 2 == 0) primaryBuf to scratchBuf
            else scratchBuf to primaryBuf
        device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = bgl,
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, buffer = inputBuf),
                    GPUBindGroupEntry(binding = 1, buffer = outputBuf),
                    GPUBindGroupEntry(
                        binding = 2,
                        buffer = paramsBuffer,
                        offset = idx * UNIFORM_OFFSET_ALIGNMENT,
                        size = PARAMS_BYTES.toLong(),
                    ),
                )
            )
        )
    }
}
