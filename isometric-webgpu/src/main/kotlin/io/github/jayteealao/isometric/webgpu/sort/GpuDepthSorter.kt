package io.github.jayteealao.isometric.webgpu.sort

import androidx.webgpu.BufferBindingType
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferBindingLayout
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUComputePipeline
import androidx.webgpu.GPUComputePipelineDescriptor
import androidx.webgpu.GPUComputeState
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.MapMode
import androidx.webgpu.ShaderStage
import io.github.jayteealao.isometric.webgpu.GpuContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * GPU-accelerated depth-key sort using a ping-pong bitonic network.
 *
 * ## Performance design
 *
 * All GPU resources are **cached across frames** and only recreated when the padded
 * element count changes. This eliminates per-frame GPU object allocation that would
 * otherwise cause OOM from JNI wrapper accumulation at high frame rates.
 *
 * Cached per paddedCount:
 * - Sort buffers (primary, scratch, readback)
 * - Upload staging buffer (re-mapped each frame via `mapAndAwait`)
 * - Packed params uniform buffer (immutable for a given paddedCount)
 * - Bind groups (reference cached buffers, deterministic ping-pong pattern)
 * - CPU-side ByteBuffer for packing sort keys (rewritten in-place each frame)
 *
 * Per-frame allocations are limited to:
 * - 1 command encoder + 1 command buffer (unavoidable)
 * - N compute pass objects (lightweight, one per bitonic stage)
 *
 * All bitonic stages are encoded into a **single command buffer** and submitted with
 * one `queue.submit()`. Only one `onSubmittedWorkDone()` sync point per sort.
 *
 * The implementation intentionally avoids [androidx.webgpu.GPUQueue.writeBuffer] because
 * that JNI path has proven unstable on some devices. All CPU→GPU transfers use mapped
 * staging buffers and explicit copy commands instead.
 *
 * All Dawn API calls are confined to the GPU thread via [GpuContext.withGpu] to prevent
 * data races between GPU work and the processEvents polling loop.
 *
 * @param ctx The GPU context providing device, queue, and event polling.
 * @param onProgress Reports coarse milestones for sample/debug surfaces.
 */
class GpuDepthSorter(
    private val ctx: GpuContext,
    private val onProgress: (String, Int?) -> Unit = { _, _ -> },
) {
    private var shaderModule: GPUShaderModule? = null
    private var sortPipeline: GPUComputePipeline? = null
    private var bindGroupLayout: GPUBindGroupLayout? = null

    // ── Cached GPU resources (recreated only when paddedCount changes) ──

    private var cachedPaddedCount = 0
    private var primaryBuffer: GPUBuffer? = null
    private var scratchBuffer: GPUBuffer? = null
    private var resultReadback: GPUBuffer? = null
    private var uploadBuffer: GPUBuffer? = null
    private var paramsBuffer: GPUBuffer? = null
    private var cachedBindGroups: Array<GPUBindGroup>? = null

    /** Reusable CPU-side ByteBuffer for packing sort keys. Avoids allocateDirect per frame. */
    private var cachedPackedKeysBuffer: ByteBuffer? = null

    /** True after the first use — subsequent uses require mapAndAwait to re-map. */
    private var uploadBufferNeedsRemap = false

    /**
     * Sort depth keys and return back-to-front indices.
     *
     * @param depthKeys One float depth key per face. Higher = further from viewer.
     * @return Indices into [depthKeys] in back-to-front (drawn-first) order.
     */
    suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray {
        require(depthKeys.isNotEmpty()) { "depthKeys must not be empty" }

        return withContext(NonCancellable) {
            ctx.checkHealthy()

            val count = depthKeys.size
            val paddedCount = nextPowerOfTwo(count)
            val sortBufferSize = (paddedCount * SORT_KEY_BYTES).toLong()

            // Reuse or allocate the CPU-side ByteBuffer for packing.
            val packedKeys = ensurePackedKeysBuffer(paddedCount)
            packSortKeysInto(depthKeys, paddedCount, packedKeys)

            // All Dawn API calls run on the dedicated GPU thread.
            ctx.withGpu {
                ensurePipelineBuilt()
                ensureBuffers(paddedCount, sortBufferSize)

                // Re-map the upload staging buffer if needed (first use is already mapped).
                if (uploadBufferNeedsRemap) {
                    uploadBuffer!!.mapAndAwait(MapMode.Write, 0L, sortBufferSize)
                }

                // Write packed keys into the mapped staging buffer.
                val target = uploadBuffer!!.getMappedRange(0L, sortBufferSize)
                    .order(ByteOrder.nativeOrder())
                packedKeys.rewind()
                target.put(packedKeys)
                uploadBuffer!!.unmap()
                uploadBufferNeedsRemap = true

                val workgroupCount =
                    ceil(paddedCount.toDouble() / GPUBitonicSortShader.WORKGROUP_SIZE).toInt()

                val bindGroups = cachedBindGroups!!

                ctx.checkHealthy()

                // Encode everything — upload, all compute passes, readback — in one
                // command buffer. Compute passes within a command buffer have implicit
                // barriers, so the ping-pong reads correctly see prior writes.
                val encoder = ctx.device.createCommandEncoder()

                // Copy input data from staging to primary sort buffer.
                encoder.copyBufferToBuffer(
                    uploadBuffer!!, 0L, primaryBuffer!!, 0L, sortBufferSize
                )

                // Encode all bitonic sort stages using cached bind groups.
                for (idx in bindGroups.indices) {
                    val pass = encoder.beginComputePass()
                    pass.setPipeline(sortPipeline!!)
                    pass.setBindGroup(0, bindGroups[idx])
                    pass.dispatchWorkgroups(workgroupCount)
                    pass.end()
                }

                // The final result is in primary or scratch depending on stage count parity.
                // Bind groups are pre-built with the correct ping-pong order, so the last
                // output is the input of a hypothetical next stage. For even stage count,
                // that's primaryBuffer; for odd, it's scratchBuffer.
                val finalOutputBuffer = if (bindGroups.size % 2 == 0) {
                    primaryBuffer!!
                } else {
                    scratchBuffer!!
                }

                // Copy final sorted result to the readback buffer.
                encoder.copyBufferToBuffer(
                    finalOutputBuffer, 0L, resultReadback!!, 0L, sortBufferSize
                )

                // Single submit for the entire sort operation.
                ctx.queue.submit(arrayOf(encoder.finish()))
                onProgress("Submitted sort for $count depth keys", count)

                // Single sync point — wait for all GPU work to complete.
                ctx.queue.onSubmittedWorkDone()

                // Map and read results.
                resultReadback!!.mapAndAwait(MapMode.Read, 0L, sortBufferSize)
                val resultData = resultReadback!!.getConstMappedRange(0L, sortBufferSize)
                val sortedIndices = extractSortedIndices(resultData, count, paddedCount)
                resultReadback!!.unmap()
                onProgress("Completed sort for $count depth keys", count)
                sortedIndices
            }
        }
    }

    /**
     * Release cached GPU buffers. Called when the sorter is being discarded
     * or the GPU context is being invalidated.
     */
    fun destroyCachedBuffers() {
        primaryBuffer?.destroy()
        scratchBuffer?.destroy()
        resultReadback?.destroy()
        uploadBuffer?.destroy()
        paramsBuffer?.destroy()
        primaryBuffer = null
        scratchBuffer = null
        resultReadback = null
        uploadBuffer = null
        paramsBuffer = null
        cachedBindGroups = null
        cachedPackedKeysBuffer = null
        cachedPaddedCount = 0
        uploadBufferNeedsRemap = false
    }

    private fun ensurePackedKeysBuffer(paddedCount: Int): ByteBuffer {
        val needed = paddedCount * SORT_KEY_BYTES
        val existing = cachedPackedKeysBuffer
        if (existing != null && existing.capacity() >= needed) {
            existing.clear()
            return existing
        }
        val buf = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
        cachedPackedKeysBuffer = buf
        return buf
    }

    private fun ensureBuffers(paddedCount: Int, sortBufferSize: Long) {
        if (cachedPaddedCount == paddedCount) return

        // Destroy old buffers (except pipeline which is size-independent).
        primaryBuffer?.destroy()
        scratchBuffer?.destroy()
        resultReadback?.destroy()
        uploadBuffer?.destroy()
        paramsBuffer?.destroy()

        primaryBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                size = sortBufferSize,
            )
        )
        scratchBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                size = sortBufferSize,
            )
        )
        resultReadback = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.MapRead or BufferUsage.CopyDst,
                size = sortBufferSize,
            )
        )
        uploadBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.MapWrite or BufferUsage.CopySrc,
                size = sortBufferSize,
                mappedAtCreation = true,
            )
        )
        uploadBufferNeedsRemap = false

        // Build the immutable params buffer and bind groups for this paddedCount.
        val stages = buildBitonicStages(paddedCount)
        paramsBuffer = createPackedParamsBuffer(stages, paddedCount)
        cachedBindGroups = buildBindGroups(stages.size, paramsBuffer!!)

        cachedPaddedCount = paddedCount
    }

    private suspend fun ensurePipelineBuilt() {
        if (sortPipeline != null) return

        if (shaderModule == null) {
            shaderModule = ctx.device.createShaderModule(
                GPUShaderModuleDescriptor(
                    shaderSourceWGSL = GPUShaderSourceWGSL(code = GPUBitonicSortShader.WGSL)
                )
            )
        }

        val layout = ctx.device.createBindGroupLayout(
            GPUBindGroupLayoutDescriptor(
                entries = arrayOf(
                    GPUBindGroupLayoutEntry(
                        binding = 0,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                    ),
                    GPUBindGroupLayoutEntry(
                        binding = 1,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Storage),
                    ),
                    GPUBindGroupLayoutEntry(
                        binding = 2,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Uniform),
                    ),
                )
            )
        )
        bindGroupLayout = layout

        val pipelineLayout = ctx.device.createPipelineLayout(
            GPUPipelineLayoutDescriptor(
                bindGroupLayouts = arrayOf(layout)
            )
        )

        sortPipeline = ctx.device.createComputePipelineAndAwait(
            GPUComputePipelineDescriptor(
                compute = GPUComputeState(
                    module = shaderModule!!,
                    entryPoint = GPUBitonicSortShader.SORT_ENTRY_POINT,
                ),
                layout = pipelineLayout,
            )
        )
        onProgress("Pipeline ready", null)
    }

    /**
     * Build the list of (j, k) pairs for the full bitonic sort network.
     */
    private fun buildBitonicStages(paddedCount: Int): List<Pair<Int, Int>> = buildList {
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

    /**
     * Create a single uniform buffer containing all per-stage params at aligned offsets.
     *
     * Uses `mappedAtCreation` for zero-copy upload. Each stage's params (j, k, paddedCount, pad)
     * occupy [PARAMS_BYTES] bytes but are spaced at [UNIFORM_OFFSET_ALIGNMENT]-byte intervals
     * to satisfy WebGPU's `minUniformBufferOffsetAlignment` requirement.
     *
     * The buffer content is immutable for a given paddedCount, so it can be cached.
     */
    private fun createPackedParamsBuffer(
        stages: List<Pair<Int, Int>>,
        paddedCount: Int,
    ): GPUBuffer {
        val bufferSize = stages.size.toLong() * UNIFORM_OFFSET_ALIGNMENT
        val buffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Uniform,
                size = bufferSize,
                mappedAtCreation = true,
            )
        )
        val mapped = buffer.getMappedRange(0L, bufferSize).order(ByteOrder.nativeOrder())
        for ((idx, stage) in stages.withIndex()) {
            mapped.position((idx * UNIFORM_OFFSET_ALIGNMENT).toInt())
            mapped.putInt(stage.first)   // j
            mapped.putInt(stage.second)  // k
            mapped.putInt(paddedCount)
            mapped.putInt(0)             // padding
        }
        buffer.unmap()
        return buffer
    }

    /**
     * Pre-build all bind groups for the bitonic sort stages.
     *
     * The ping-pong pattern is deterministic: stage 0 reads primary→writes scratch,
     * stage 1 reads scratch→writes primary, etc. Since all referenced buffers are cached,
     * these bind groups are stable across frames.
     */
    private fun buildBindGroups(stageCount: Int, paramsBuffer: GPUBuffer): Array<GPUBindGroup> {
        return Array(stageCount) { idx ->
            val (inputBuf, outputBuf) = if (idx % 2 == 0) {
                primaryBuffer!! to scratchBuffer!!
            } else {
                scratchBuffer!! to primaryBuffer!!
            }
            ctx.device.createBindGroup(
                GPUBindGroupDescriptor(
                    layout = bindGroupLayout!!,
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

    companion object {
        private const val SORT_KEY_BYTES = 16
        private const val ORIGINAL_INDEX_OFFSET = 4
        private const val PARAMS_BYTES = 16
        private const val SENTINEL_INDEX = -1

        /**
         * WebGPU's `minUniformBufferOffsetAlignment` is 256 bytes on all known devices.
         * Params for each bitonic stage are packed at this alignment within the shared
         * uniform buffer.
         */
        private const val UNIFORM_OFFSET_ALIGNMENT = 256L

        /**
         * Pack sort keys into a reusable ByteBuffer. Unlike [packSortKeys], this writes
         * in-place to avoid allocating a new direct buffer each frame.
         */
        internal fun packSortKeysInto(
            depthKeys: FloatArray,
            paddedCount: Int,
            dest: ByteBuffer,
        ) {
            dest.clear()
            dest.order(ByteOrder.nativeOrder())
            for (i in depthKeys.indices) {
                dest.putFloat(depthKeys[i])
                dest.putInt(i)
                dest.putInt(0)
                dest.putInt(0)
            }
            for (i in depthKeys.size until paddedCount) {
                dest.putFloat(Float.NEGATIVE_INFINITY)
                dest.putInt(SENTINEL_INDEX)
                dest.putInt(0)
                dest.putInt(0)
            }
            dest.rewind()
        }

        internal fun extractSortedIndices(
            resultData: ByteBuffer,
            count: Int,
            paddedCount: Int,
        ): IntArray {
            val sortedIndices = IntArray(count)
            var outIndex = 0
            for (i in 0 until paddedCount) {
                resultData.position(i * SORT_KEY_BYTES + ORIGINAL_INDEX_OFFSET)
                val originalIndex = resultData.getInt()
                if (originalIndex != SENTINEL_INDEX) {
                    check(outIndex < count) {
                        "GPU sort produced more than $count indices"
                    }
                    sortedIndices[outIndex++] = originalIndex
                }
            }
            check(outIndex == count) {
                "GPU sort produced $outIndex indices for $count depth keys"
            }
            return sortedIndices
        }

        internal fun nextPowerOfTwo(value: Int): Int {
            require(value > 0) { "value must be positive, got $value" }
            var result = 1
            while (result < value) {
                result = result shl 1
            }
            return result
        }
    }
}
