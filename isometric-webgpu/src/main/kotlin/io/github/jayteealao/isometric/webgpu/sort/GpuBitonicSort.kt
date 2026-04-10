package io.github.jayteealao.isometric.webgpu.sort

import android.util.Log
import androidx.webgpu.BufferBindingType
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferBindingLayout
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPUComputePipeline
import androidx.webgpu.GPUComputePipelineDescriptor
import androidx.webgpu.GPUComputeState
import androidx.webgpu.GPUPipelineLayout
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.ShaderStage
import io.github.jayteealao.isometric.webgpu.GpuContext
import kotlin.math.ceil

/**
 * GPU-only bitonic sort over 16-byte [SortKey] tuples.
 *
 * This is the Phase 3 equivalent of [GpuDepthSorter]'s sort dispatch, stripped of
 * all CPU I/O (no `writeBuffer` upload, no `mapAsync` readback). The sort result
 * stays in GPU memory and is consumed directly by the M5 triangulate-emit pass.
 *
 * ## Usage
 *
 * ```kotlin
 * val sort = GpuBitonicSort(ctx)
 *
 * // Inside ctx.withGpu { ... } at pipeline init time:
 * sort.ensurePipeline()
 * sort.ensureBuffers(paddedCount)  // paddedCount = BitonicSortNetwork.nextPowerOfTwo(capacity)
 *
 * // Let GpuSortKeyPacker write sort keys into sort.primaryBuffer, then in one encoder:
 * packer.dispatch(encoder, paddedCount)
 * sort.dispatch(encoder)           // encodes all bitonic stages
 *
 * // Read back-to-front sorted SortKey array from:
 * val result: GPUBuffer = sort.resultBuffer
 * ```
 *
 * ## Buffer ownership
 *
 * [primaryBuffer] and [resultBuffer] are owned by this class and released in [close].
 * [GpuSortKeyPacker] writes its output directly into [primaryBuffer]; there is no copy.
 * After [dispatch], [resultBuffer] holds the fully sorted array (may be [primaryBuffer]
 * or the internal scratch buffer depending on stage-count parity).
 *
 * ## Buffer usages
 *
 * Both primary and scratch carry `Storage | CopySrc`. `CopySrc` is not needed for
 * production rendering but enables GPU→CPU readback during M8 validation without
 * reallocating buffers.
 *
 * ## Lifetime
 *
 * Create once alongside [GpuSortKeyPacker]. Call [close] on renderer teardown.
 * All Dawn API calls must be made from the GPU thread ([GpuContext.withGpu]).
 */
internal class GpuBitonicSort(
    private val ctx: GpuContext,
) : AutoCloseable {

    companion object {
        private const val TAG = "GpuBitonicSort"
    }

    // ── Pipeline objects (created once) ─────────────────────────────────────

    private var shaderModule: GPUShaderModule? = null
    private var bindGroupLayout: GPUBindGroupLayout? = null
    private var pipelineLayout: GPUPipelineLayout? = null
    private var sortPipeline: GPUComputePipeline? = null

    // ── Per-capacity resources (recreated when paddedCount changes) ──────────

    private var primaryBuf: GPUBuffer? = null
    private var scratchBuf: GPUBuffer? = null
    private var paramsBuffer: GPUBuffer? = null
    private var cachedBindGroups: Array<GPUBindGroup>? = null
    private var cachedPaddedCount: Int = 0

    /**
     * The primary sort buffer.
     *
     * [GpuSortKeyPacker] writes its packed sort keys into this buffer before [dispatch]
     * is called. Stage 0 of the bitonic sort reads from this buffer.
     *
     * Valid after [ensureBuffers] has been called.
     */
    /** Number of bitonic sort stages for the current [cachedPaddedCount]. 0 before [ensureBuffers]. */
    val stageCount: Int get() = cachedBindGroups?.size ?: 0

    val primaryBuffer: GPUBuffer
        get() = checkNotNull(primaryBuf) {
            "GpuBitonicSort: primaryBuffer not allocated — call ensureBuffers first"
        }

    /**
     * The buffer containing the fully sorted [SortKey] array after [dispatch] returns.
     *
     * Due to the ping-pong pattern, the final result lands in [primaryBuffer] when the
     * total stage count is even, and in the scratch buffer when odd. This property
     * always returns the correct buffer.
     *
     * Valid after [ensureBuffers] and [dispatch] have been called.
     */
    val resultBuffer: GPUBuffer
        get() {
            val stageCount = cachedBindGroups?.size ?: 0
            return checkNotNull(if (stageCount % 2 == 0) primaryBuf else scratchBuf) {
                "GpuBitonicSort: resultBuffer not available — call ensureBuffers first"
            }
        }

    // ── Pipeline init ────────────────────────────────────────────────────────

    /**
     * Create the compute pipeline if not already built.
     *
     * Reuses [GPUBitonicSortShader] — the same shader used by [GpuDepthSorter]
     * for the Phase 1 CPU-upload path.
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`). Safe to call
     * multiple times — no-op after the first successful call.
     */
    suspend fun ensurePipeline() {
        if (sortPipeline != null) return

        shaderModule = ctx.device.createShaderModule(
            GPUShaderModuleDescriptor(
                shaderSourceWGSL = GPUShaderSourceWGSL(code = GPUBitonicSortShader.WGSL)
            )
        )

        val bgl = ctx.device.createBindGroupLayout(
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
        bindGroupLayout = bgl

        pipelineLayout = ctx.device.createPipelineLayout(
            GPUPipelineLayoutDescriptor(bindGroupLayouts = arrayOf(bgl))
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
        Log.d(TAG, "Bitonic sort pipeline ready")
    }

    // ── Buffer management ────────────────────────────────────────────────────

    /**
     * Ensure sort buffers are sized for [paddedCount] entries.
     *
     * Allocates/resizes [primaryBuffer], the internal scratch buffer, the packed params
     * uniform buffer, and all per-stage bind groups. No-op if [paddedCount] is unchanged.
     *
     * [paddedCount] **must** be a power of two — use [BitonicSortNetwork.nextPowerOfTwo].
     * Passing a non-power-of-two value produces a broken bitonic network with silently
     * incorrect sort results.
     *
     * Must be called from the GPU thread after [ensurePipeline] has succeeded.
     * Must be called before [GpuSortKeyPacker.ensureBuffers] since the packer binds
     * [primaryBuffer] as its output.
     */
    fun ensureBuffers(paddedCount: Int) {
        require(paddedCount > 0) { "paddedCount must be > 0, got $paddedCount" }
        require(paddedCount and (paddedCount - 1) == 0) {
            "paddedCount must be a power of two, got $paddedCount — use BitonicSortNetwork.nextPowerOfTwo"
        }
        if (paddedCount == cachedPaddedCount) return

        // Release old per-capacity resources before allocating new ones.
        // Note: close() only (no destroy()) — the previous frame's GPU commands may still
        // reference these buffers. Dawn's internal ref-counting keeps the underlying GPU
        // memory alive until all pending commands complete; destroy() would mark the buffer
        // as "dead" to Dawn's validation layer while it is still in-flight.
        cachedBindGroups?.forEach { it.close() }
        cachedBindGroups = null
        primaryBuf?.close()
        primaryBuf = null
        scratchBuf?.close()
        scratchBuf = null
        paramsBuffer?.close()
        paramsBuffer = null

        val sortBufferSize = paddedCount.toLong() * BitonicSortNetwork.SORT_KEY_BYTES

        // CopySrc enables GPU→CPU readback for M8 validation without reallocating.
        primaryBuf = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage or BufferUsage.CopySrc,
                size = sortBufferSize,
            )
        ).also { it.setLabel("IsometricSortPrimary") }

        scratchBuf = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage or BufferUsage.CopySrc,
                size = sortBufferSize,
            )
        ).also { it.setLabel("IsometricSortScratch") }

        val stages = BitonicSortNetwork.buildStages(paddedCount)
        paramsBuffer = BitonicSortNetwork.createParamsBuffer(
            ctx.device, stages, paddedCount, label = "IsometricBitonicParams"
        )
        cachedBindGroups = BitonicSortNetwork.buildBindGroups(
            ctx.device, stages.size,
            primaryBuf!!, scratchBuf!!, paramsBuffer!!,
            checkNotNull(bindGroupLayout) { "Bind group layout is null — call ensurePipeline first" },
        )
        cachedPaddedCount = paddedCount

        Log.d(
            TAG,
            "Buffers ready: paddedCount=$paddedCount stages=${stages.size} " +
                "bufferSize=${sortBufferSize}B"
        )
    }

    // ── Per-frame dispatch ───────────────────────────────────────────────────

    /**
     * Encode all bitonic sort stages into [encoder].
     *
     * The first stage reads from [primaryBuffer] (filled by [GpuSortKeyPacker]) and
     * writes to scratch. Subsequent stages alternate. After the last stage, [resultBuffer]
     * holds the fully sorted array.
     *
     * The pass encoder is closed after each stage to release the JNI wrapper immediately.
     *
     * Preconditions:
     * - [ensurePipeline] has returned.
     * - [ensureBuffers] has been called with the current padded count.
     * - [GpuSortKeyPacker.dispatch] has been encoded into [encoder] before this call,
     *   filling [primaryBuffer] with packed sort keys.
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     */
    fun dispatch(
        encoder: GPUCommandEncoder,
        timestampWrites: androidx.webgpu.GPUPassTimestampWrites? = null,
    ) {
        checkNotNull(sortPipeline) { "Pipeline not ready — call ensurePipeline first" }
        val bindGroups = checkNotNull(cachedBindGroups) {
            "Bind groups not ready — call ensureBuffers first"
        }

        val workgroupCount =
            ceil(cachedPaddedCount.toDouble() / GPUBitonicSortShader.WORKGROUP_SIZE).toInt()

        for ((idx, bg) in bindGroups.withIndex()) {
            // Timestamp the first pass (beginning) and last pass (end) to measure total sort time
            val passTimestamps = when {
                timestampWrites == null -> null
                idx == 0 && bindGroups.size == 1 -> timestampWrites // single pass: both timestamps
                idx == 0 -> androidx.webgpu.GPUPassTimestampWrites(
                    querySet = timestampWrites.querySet,
                    beginningOfPassWriteIndex = timestampWrites.beginningOfPassWriteIndex,
                )
                idx == bindGroups.size - 1 -> androidx.webgpu.GPUPassTimestampWrites(
                    querySet = timestampWrites.querySet,
                    endOfPassWriteIndex = timestampWrites.endOfPassWriteIndex,
                )
                else -> null
            }
            val pass = passTimestamps?.let {
                encoder.beginComputePass(androidx.webgpu.GPUComputePassDescriptor(timestampWrites = it))
            } ?: encoder.beginComputePass()
            pass.setPipeline(sortPipeline!!)
            pass.setBindGroup(0, bg)
            pass.dispatchWorkgroups(workgroupCount)
            pass.end()
            pass.close()   // release JNI wrapper immediately (GPU_OBJECT_LIFETIME.md)
        }
    }

    /**
     * Diagnostic: dispatch each sort stage in its own [GPUQueue.submit] call.
     *
     * On Adreno, compute→compute barriers within a single VkCommandBuffer may not be
     * honoured, causing subsequent stages to read stale data from the previous stage.
     * This can trigger the GPU watchdog (TDR, 2-second default) and produce
     * `VK_ERROR_DEVICE_LOST`. Submitting each stage individually forces Dawn to insert
     * a Vulkan timeline semaphore between every pair of consecutive sort stages,
     * guaranteeing visibility of writes before the next stage's reads.
     *
     * Use this instead of [dispatch] when [dispatch] causes `VK_ERROR_DEVICE_LOST`.
     * If this resolves the crash, the root cause is the Adreno compute-barrier bug.
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     */
    fun dispatchIndividually() {
        val pipeline = checkNotNull(sortPipeline) { "Pipeline not ready — call ensurePipeline first" }
        val bindGroups = checkNotNull(cachedBindGroups) {
            "Bind groups not ready — call ensureBuffers first"
        }
        val workgroupCount =
            ceil(cachedPaddedCount.toDouble() / GPUBitonicSortShader.WORKGROUP_SIZE).toInt()

        for ((stageIdx, bg) in bindGroups.withIndex()) {
            Log.d(TAG, "dispatchIndividually: stage $stageIdx/${bindGroups.size} — before submit")
            val enc = ctx.device.createCommandEncoder()
            val pass = enc.beginComputePass()
            pass.setPipeline(pipeline)
            pass.setBindGroup(0, bg)
            pass.dispatchWorkgroups(workgroupCount)
            pass.end()
            pass.close()
            val buf = enc.finish()
            ctx.queue.submit(arrayOf(buf))
            buf.close()
            enc.close()
            Log.d(TAG, "dispatchIndividually: stage $stageIdx/${bindGroups.size} — after submit")
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun close() {
        // Per-capacity resources
        cachedBindGroups?.forEach { it.close() }
        cachedBindGroups = null
        primaryBuf?.close()
        primaryBuf = null
        scratchBuf?.close()
        scratchBuf = null
        paramsBuffer?.close()
        paramsBuffer = null

        // Pipeline objects
        sortPipeline?.close()
        sortPipeline = null
        pipelineLayout?.close()
        pipelineLayout = null
        bindGroupLayout?.close()
        bindGroupLayout = null
        shaderModule?.close()
        shaderModule = null

        cachedPaddedCount = 0
    }
}
