package io.github.jayteealao.isometric.webgpu.sort

import androidx.webgpu.BufferBindingType
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferBindingLayout
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUComputePipeline
import androidx.webgpu.GPUComputePipelineDescriptor
import androidx.webgpu.GPUComputeState
import androidx.webgpu.GPUPipelineLayout
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPURequestCallback
import androidx.webgpu.MapMode
import androidx.webgpu.ShaderStage
import io.github.jayteealao.isometric.webgpu.GpuContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * - Packed params uniform buffer (immutable for a given paddedCount)
 * - Bind groups (reference cached buffers, deterministic ping-pong pattern)
 * - CPU-side ByteBuffers for packing sort keys and readback data
 *
 * Per-frame allocations are limited to:
 * - 1 command encoder + 1 command buffer (explicitly closed after use)
 * - N compute pass objects (explicitly closed after each stage)
 * - 1 CompletableDeferred per async callback (lightweight, no JNI cost)
 *
 * All bitonic stages are encoded into a **single command buffer** and submitted with
 * one `queue.submit()`. Only one `onSubmittedWorkDone()` sync point per sort.
 *
 * CPU→GPU uploads use [androidx.webgpu.GPUQueue.writeBuffer] directly. GPU→CPU
 * readback uses [GPUBuffer.readMappedRange] with a reusable ByteBuffer. Both
 * approaches avoid the per-frame [GPUBuffer.mapAndAwait] callback that leaks
 * JNI global refs in the Dawn binding layer.
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
    // F4: stored so it can be closed when the sorter is discarded
    private var pipelineLayout: GPUPipelineLayout? = null

    // ── Cached GPU resources (recreated only when paddedCount changes) ──

    private var cachedPaddedCount = 0
    private var primaryBuffer: GPUBuffer? = null
    private var scratchBuffer: GPUBuffer? = null
    private var resultReadback: GPUBuffer? = null
    private var paramsBuffer: GPUBuffer? = null
    private var cachedBindGroups: Array<GPUBindGroup>? = null

    /**
     * Serializes concurrent [sortByDepthKeys] calls.
     *
     * `ctx.withGpu { }` suspends during `awaitSubmittedWorkDone()` and `awaitBufferMap()`,
     * freeing the GPU dispatcher thread. Without this mutex a second frame can enter
     * [ensureBuffers] while the first frame is suspended, destroying and recreating
     * `resultReadback`. When the first frame resumes it would call `mapAsync` on the
     * destroyed buffer, causing a SIGSEGV in the Dawn JNI layer.
     *
     * Using a Mutex here serializes sorts: Frame N+1 waits for Frame N to finish
     * (including GPU readback) before touching shared GPU buffers. Frame N+1 is still
     * cancellable while waiting for the lock (the `withLock` call is a suspend point);
     * once it acquires the lock the `withContext(NonCancellable)` block runs to completion.
     */
    private val sortMutex = Mutex()

    /** Reusable CPU-side ByteBuffer for packing sort keys. Avoids allocateDirect per frame. */
    private var cachedPackedKeysBuffer: ByteBuffer? = null

    /** Reusable CPU-side ByteBuffer for readback data. Avoids getConstMappedRange per frame. */
    private var cachedReadbackDataBuffer: ByteBuffer? = null

    /**
     * Reusable callback objects for async Dawn operations.
     *
     * The Dawn JNI bindings create JNI global refs to callback and executor objects
     * passed to mapAsync/onSubmittedWorkDone. These global refs are never released,
     * causing ~14 MB/s Dalvik heap growth when using awaitGPURequest (which creates
     * a fresh anonymous callback each frame). By reusing singleton callback objects,
     * all JNI global refs point to the same Java objects, bounding the leak.
     */
    private val reusableExecutor = java.util.concurrent.Executor(Runnable::run)
    private val reusableWorkDoneCallback = ReusableUnitCallback()
    private val reusableMapCallback = ReusableUnitCallback()

    /**
     * Sort depth keys and return back-to-front indices.
     *
     * @param depthKeys One float depth key per face. Higher = further from viewer.
     * @return Indices into [depthKeys] in back-to-front (drawn-first) order.
     */
    suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray {
        require(depthKeys.isNotEmpty()) { "depthKeys must not be empty" }

        // Acquire before entering NonCancellable so the caller can still be cancelled
        // while waiting for a previous frame's sort to complete. Once the lock is held
        // the sort runs to completion regardless of cancellation.
        return sortMutex.withLock {
            withContext(NonCancellable) {
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

                    // Upload packed keys via queue.writeBuffer — a synchronous CPU→GPU
                    // copy that avoids the mapAsync callback leak entirely.
                    // Previous approach used mapAndAwait + getMappedRange + unmap, but
                    // mapAsync's JNI callback global refs are never released by Dawn,
                    // causing ~14 MB/s Dalvik heap growth at 60fps.
                    packedKeys.rewind()
                    ctx.queue.writeBuffer(primaryBuffer!!, 0L, packedKeys)

                    val workgroupCount =
                        ceil(paddedCount.toDouble() / GPUBitonicSortShader.WORKGROUP_SIZE).toInt()

                    val bindGroups = cachedBindGroups!!

                    ctx.checkHealthy()

                    // Submit each bitonic sort stage as a separate queue.submit() call.
                    // On Adreno, compute→compute barriers within a single VkCommandBuffer
                    // may not be honoured, causing subsequent stages to read stale data.
                    // Submitting individually forces Dawn to insert a Vulkan timeline
                    // semaphore between consecutive stages, guaranteeing visibility.
                    // See GpuBitonicSort.dispatchIndividually() for the same workaround.
                    for (idx in bindGroups.indices) {
                        val encoder = ctx.device.createCommandEncoder()
                        val pass = encoder.beginComputePass()
                        pass.setPipeline(sortPipeline!!)
                        pass.setBindGroup(0, bindGroups[idx])
                        pass.dispatchWorkgroups(workgroupCount)
                        pass.end()
                        pass.close()
                        val commandBuffer = encoder.finish()
                        ctx.queue.submit(arrayOf(commandBuffer))
                        commandBuffer.close()
                        encoder.close()
                    }

                    // The final result is in primary or scratch depending on stage count parity.
                    val finalOutputBuffer = if (bindGroups.size % 2 == 0) {
                        primaryBuffer!!
                    } else {
                        scratchBuffer!!
                    }

                    // Copy final sorted result to the readback buffer.
                    val copyEncoder = ctx.device.createCommandEncoder()
                    copyEncoder.copyBufferToBuffer(
                        finalOutputBuffer, 0L, resultReadback!!, 0L, sortBufferSize
                    )
                    val copyCmdBuf = copyEncoder.finish()
                    ctx.queue.submit(arrayOf(copyCmdBuf))
                    copyCmdBuf.close()
                    copyEncoder.close()
                    onProgress("Submitted sort for $count depth keys", count)

                    // Wait for GPU work and map readback buffer using reusable callbacks
                    // to avoid JNI global ref leak from awaitGPURequest/mapAndAwait.
                    awaitSubmittedWorkDone()
                    awaitBufferMap(resultReadback!!, MapMode.Read, 0L, sortBufferSize)

                    // Read results using readMappedRange into a reusable ByteBuffer
                    // to avoid per-frame DirectByteBuffer creation from getConstMappedRange.
                    val readbackData = ensureReadbackDataBuffer(paddedCount)
                    resultReadback!!.readMappedRange(0L, readbackData)
                    readbackData.rewind()
                    val sortedIndices = extractSortedIndices(readbackData, count, paddedCount)
                    resultReadback!!.unmap()
                    onProgress("Completed sort for $count depth keys", count)
                    sortedIndices
                }
            }
        }
    }

    /**
     * Release all cached GPU resources. Called when the sorter is being discarded
     * or the GPU context is being invalidated.
     *
     * F5: explicitly close all AutoCloseable JNI wrappers — shaderModule, sortPipeline,
     * bindGroupLayout, pipelineLayout, and cachedBindGroups — so Dawn native handles are
     * released immediately rather than waiting for GC finalization.
     */
    fun destroyCachedBuffers() {
        primaryBuffer?.destroy()
        scratchBuffer?.destroy()
        resultReadback?.destroy()
        paramsBuffer?.destroy()
        primaryBuffer = null
        scratchBuffer = null
        resultReadback = null
        paramsBuffer = null
        cachedBindGroups?.forEach { it.close() }
        cachedBindGroups = null
        cachedPackedKeysBuffer = null
        cachedReadbackDataBuffer = null
        cachedPaddedCount = 0

        // F4+F5: close pipeline-tier objects — created once, must be closed on discard.
        sortPipeline?.close()
        sortPipeline = null
        pipelineLayout?.close()
        pipelineLayout = null
        bindGroupLayout?.close()
        bindGroupLayout = null
        shaderModule?.close()
        shaderModule = null
    }

    /**
     * Reusable callback for GPURequestCallback<Unit>. A single instance is passed
     * to mapAsync/onSubmittedWorkDone every frame. The JNI global ref always points
     * to the same object, preventing the per-frame leak.
     *
     * Uses a CompletableDeferred as a one-shot signal, replaced each call via [prepare].
     */
    private class ReusableUnitCallback : GPURequestCallback<Unit> {
        @Volatile
        private var deferred: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        fun prepare(): kotlinx.coroutines.CompletableDeferred<Unit> {
            val d = kotlinx.coroutines.CompletableDeferred<Unit>()
            deferred = d
            return d
        }

        override fun onResult(result: Unit) {
            deferred?.complete(Unit)
        }

        override fun onError(exception: Exception) {
            deferred?.completeExceptionally(exception)
        }
    }

    /**
     * Await GPU submitted work done using the reusable callback to avoid JNI leak.
     */
    private suspend fun awaitSubmittedWorkDone() {
        val signal = reusableWorkDoneCallback.prepare()
        ctx.queue.onSubmittedWorkDone(reusableExecutor, reusableWorkDoneCallback)
        signal.await()
    }

    /**
     * Await buffer mapping using the reusable callback to avoid JNI leak.
     */
    private suspend fun awaitBufferMap(
        buffer: GPUBuffer, @MapMode mode: Int, offset: Long, size: Long
    ) {
        val signal = reusableMapCallback.prepare()
        buffer.mapAsync(mode, offset, size, reusableExecutor, reusableMapCallback)
        signal.await()
    }

    private fun ensureReadbackDataBuffer(paddedCount: Int): ByteBuffer {
        val needed = paddedCount * SORT_KEY_BYTES
        val existing = cachedReadbackDataBuffer
        if (existing != null && existing.capacity() >= needed) {
            existing.clear()
            return existing
        }
        val buf = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
        cachedReadbackDataBuffer = buf
        return buf
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
        // G3: also close old bind group JNI wrappers — they reference the old buffers and
        // accumulate unclosed until destroyCachedBuffers() is eventually called otherwise.
        primaryBuffer?.destroy()
        scratchBuffer?.destroy()
        resultReadback?.destroy()
        paramsBuffer?.destroy()
        cachedBindGroups?.forEach { it.close() }
        cachedBindGroups = null

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

        // Build the immutable params buffer and bind groups for this paddedCount.
        val stages = BitonicSortNetwork.buildStages(paddedCount)
        paramsBuffer = BitonicSortNetwork.createParamsBuffer(ctx.device, stages, paddedCount)
        cachedBindGroups = BitonicSortNetwork.buildBindGroups(
            ctx.device, stages.size,
            primaryBuffer!!, scratchBuffer!!, paramsBuffer!!,
            bindGroupLayout!!,
        )

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

        pipelineLayout = ctx.device.createPipelineLayout(
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

    companion object {
        private const val SORT_KEY_BYTES = 16
        private const val ORIGINAL_INDEX_OFFSET = 4

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
                dest.putInt(BitonicSortNetwork.SENTINEL_INDEX)
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
                if (originalIndex != BitonicSortNetwork.SENTINEL_INDEX) {
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

        /** Delegates to [BitonicSortNetwork.nextPowerOfTwo]; kept for test compatibility. */
        internal fun nextPowerOfTwo(value: Int): Int = BitonicSortNetwork.nextPowerOfTwo(value)
    }
}
