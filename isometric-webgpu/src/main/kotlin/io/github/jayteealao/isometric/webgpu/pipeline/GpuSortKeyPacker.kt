package io.github.jayteealao.isometric.webgpu.pipeline

import android.util.Log
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
import io.github.jayteealao.isometric.webgpu.shader.PackSortKeysShader
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * GPU compute pipeline for the M4 sort-key packing pass.
 *
 * Reads the [M3 TransformedFace output][GpuTransformPipeline] and packs each visible
 * face into a 16-byte [SortKey] tuple consumable by [GpuBitonicSort]:
 *
 * ```
 * sortKeys[i] = SortKey(depth = transformed[i].depthKey, originalIndex = i)
 * ```
 *
 * Invisible padding entries (`i ≥ visibleCount`) receive sentinel values
 * (`Float.NEGATIVE_INFINITY`, `0xFFFFFFFF`) so they sort to the end of the
 * descending-depth bitonic sort and are skipped by the M5 triangulate-emit pass.
 *
 * ## Usage
 *
 * ```kotlin
 * val packer = GpuSortKeyPacker(ctx)
 *
 * // Inside ctx.withGpu { ... } at pipeline init time:
 * sort.ensurePipeline()    // must come first — provides sort.primaryBuffer
 * sort.ensureBuffers(paddedCount)
 * packer.ensurePipeline()
 * packer.ensureBuffers(paddedCount, transformedBuf, visibleCountBuf, sort.primaryBuffer)
 *
 * // Per frame (inside a single command encoder):
 * packer.dispatch(encoder, paddedCount)   // pack into sort.primaryBuffer
 * sort.dispatch(encoder, paddedCount)     // sort in-place
 * // sort.resultBuffer now holds back-to-front SortKey array
 * ```
 *
 * ## Bind group layout
 *
 * ```wgsl
 * @group(0) @binding(0) var<storage, read>       transformed:  array<TransformedFace>
 * @group(0) @binding(1) var<storage, read_write>  sortKeys:     array<SortKey>
 * @group(0) @binding(2) var<storage, read>        visibleCount: u32
 * @group(0) @binding(3) var<uniform>              params:       PackParams
 * ```
 *
 * ## Lifetime
 *
 * Create once alongside [GpuBitonicSort] and [GpuTransformPipeline].
 * Call [close] on renderer teardown. All Dawn API calls must run on the GPU thread.
 */
internal class GpuSortKeyPacker(
    private val ctx: GpuContext,
) : AutoCloseable {

    companion object {
        private const val TAG = "GpuSortKeyPacker"

        /**
         * Minimum size for the params uniform buffer. WebGPU requires uniform buffers
         * to be at least 16 bytes (vec4 alignment). We only use 4 bytes (paddedCount: u32)
         * but pad the allocation to meet the requirement.
         */
        private const val PARAMS_BUFFER_SIZE = 16L
    }

    // ── Pipeline objects (created once) ─────────────────────────────────────

    private var shaderModule: GPUShaderModule? = null
    private var bindGroupLayout: GPUBindGroupLayout? = null
    private var pipelineLayout: GPUPipelineLayout? = null
    private var computePipeline: GPUComputePipeline? = null

    // ── Per-capacity resources (rebuilt when any input changes) ──────────────

    private var paramsBuffer: GPUBuffer? = null
    private var bindGroup: GPUBindGroup? = null

    /** Last inputs used to build the bind group — detected changes trigger a rebuild. */
    private var lastPaddedCount: Int = 0
    private var lastTransformedBuffer: GPUBuffer? = null
    private var lastVisibleCountBuffer: GPUBuffer? = null
    private var lastSortKeyOutputBuffer: GPUBuffer? = null

    // ── Pipeline init ────────────────────────────────────────────────────────

    /**
     * Create the compute pipeline if not already built.
     *
     * Must be called from the GPU thread. Safe to call multiple times — no-op after the
     * first successful call.
     */
    suspend fun ensurePipeline() {
        if (computePipeline != null) return

        shaderModule = ctx.device.createShaderModule(
            GPUShaderModuleDescriptor(
                shaderSourceWGSL = GPUShaderSourceWGSL(code = PackSortKeysShader.WGSL)
            )
        )

        val bgl = ctx.device.createBindGroupLayout(
            GPUBindGroupLayoutDescriptor(
                entries = arrayOf(
                    // binding 0 — TransformedFace array (read-only)
                    GPUBindGroupLayoutEntry(
                        binding = 0,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                    ),
                    // binding 1 — SortKey output array (read-write)
                    GPUBindGroupLayoutEntry(
                        binding = 1,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Storage),
                    ),
                    // binding 2 — visibleCount u32 (read-only; 4-byte storage buffer)
                    GPUBindGroupLayoutEntry(
                        binding = 2,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                    ),
                    // binding 3 — PackParams uniform (paddedCount)
                    GPUBindGroupLayoutEntry(
                        binding = 3,
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

        computePipeline = ctx.device.createComputePipelineAndAwait(
            GPUComputePipelineDescriptor(
                compute = GPUComputeState(
                    module = shaderModule!!,
                    entryPoint = PackSortKeysShader.ENTRY_POINT,
                ),
                layout = pipelineLayout,
            )
        )
        Log.d(TAG, "Pack sort keys pipeline ready")
    }

    // ── Buffer management ────────────────────────────────────────────────────

    /**
     * Ensure the bind group references the current buffers and [paddedCount].
     *
     * Rebuilds the params uniform buffer and bind group whenever any of the following
     * change: [paddedCount], [transformedBuffer], [visibleCountBuffer], or
     * [sortKeyOutputBuffer]. No-op if nothing changed.
     *
     * Must be called after [GpuBitonicSort.ensureBuffers] (which allocates
     * [sortKeyOutputBuffer] = [GpuBitonicSort.primaryBuffer]).
     *
     * @param paddedCount         Power-of-2 sort array size (`nextPowerOfTwo(sceneCapacity)`).
     * @param transformedBuffer   M3 `TransformedFace` output buffer.
     * @param visibleCountBuffer  M3 4-byte `atomic<u32>` counter (read here as plain `u32`).
     * @param sortKeyOutputBuffer [GpuBitonicSort.primaryBuffer] — receives the packed keys.
     */
    fun ensureBuffers(
        paddedCount: Int,
        transformedBuffer: GPUBuffer,
        visibleCountBuffer: GPUBuffer,
        sortKeyOutputBuffer: GPUBuffer,
    ) {
        val same = paddedCount == lastPaddedCount &&
            transformedBuffer    === lastTransformedBuffer &&
            visibleCountBuffer   === lastVisibleCountBuffer &&
            sortKeyOutputBuffer  === lastSortKeyOutputBuffer
        if (same) return

        // Release stale resources before allocating new ones.
        bindGroup?.close()
        bindGroup = null
        paramsBuffer?.destroy()
        paramsBuffer?.close()
        paramsBuffer = null

        // Params buffer: 16 bytes, mapped at creation — zero-copy, no CopyDst needed.
        // Content: [paddedCount, 0, 0, 0] — only the first u32 is used by the shader.
        paramsBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Uniform,
                size = PARAMS_BUFFER_SIZE,
                mappedAtCreation = true,
            )
        ).also { buf ->
            buf.setLabel("IsometricPackSortParams")
            val mapped = buf.getMappedRange(0L, PARAMS_BUFFER_SIZE).order(ByteOrder.nativeOrder())
            mapped.putInt(paddedCount)  // paddedCount
            mapped.putInt(0)            // pad
            mapped.putInt(0)            // pad
            mapped.putInt(0)            // pad
            buf.unmap()
        }

        bindGroup = ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = checkNotNull(bindGroupLayout) {
                    "Bind group layout is null — call ensurePipeline first"
                },
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, buffer = transformedBuffer),
                    GPUBindGroupEntry(binding = 1, buffer = sortKeyOutputBuffer),
                    GPUBindGroupEntry(binding = 2, buffer = visibleCountBuffer),
                    GPUBindGroupEntry(binding = 3, buffer = paramsBuffer!!),
                )
            )
        )

        lastPaddedCount           = paddedCount
        lastTransformedBuffer     = transformedBuffer
        lastVisibleCountBuffer    = visibleCountBuffer
        lastSortKeyOutputBuffer   = sortKeyOutputBuffer

        Log.d(TAG, "Buffers ready: paddedCount=$paddedCount")
    }

    // ── Per-frame dispatch ───────────────────────────────────────────────────

    /**
     * Encode the sort-key packing compute pass into [encoder].
     *
     * Dispatches `ceil(paddedCount / WORKGROUP_SIZE)` workgroups. Each invocation
     * packs one sort key entry: real key for visible faces, sentinel for padding.
     *
     * Must be called before [GpuBitonicSort.dispatch] in the same encoder so that
     * [GpuBitonicSort.primaryBuffer] is fully written before the first sort stage reads it.
     *
     * Must be called from the GPU thread.
     *
     * @param encoder      The command encoder to record into.
     * @param paddedCount  Power-of-2 sort array size — must match [ensureBuffers].
     */
    fun dispatch(encoder: GPUCommandEncoder, paddedCount: Int) {
        require(paddedCount > 0) { "paddedCount must be > 0, got $paddedCount" }
        checkNotNull(computePipeline) { "Pipeline not ready — call ensurePipeline first" }
        checkNotNull(bindGroup) { "Bind group not ready — call ensureBuffers first" }

        val workgroupCount =
            ceil(paddedCount.toDouble() / PackSortKeysShader.WORKGROUP_SIZE).toInt()

        val pass = encoder.beginComputePass()
        pass.setPipeline(computePipeline!!)
        pass.setBindGroup(0, bindGroup!!)
        pass.dispatchWorkgroups(workgroupCount)
        pass.end()
        pass.close()   // release JNI wrapper immediately (GPU_OBJECT_LIFETIME.md)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun close() {
        // Per-capacity resources
        bindGroup?.close()
        bindGroup = null
        paramsBuffer?.destroy()
        paramsBuffer?.close()
        paramsBuffer = null

        // Pipeline objects
        computePipeline?.close()
        computePipeline = null
        pipelineLayout?.close()
        pipelineLayout = null
        bindGroupLayout?.close()
        bindGroupLayout = null
        shaderModule?.close()
        shaderModule = null

        lastTransformedBuffer   = null
        lastVisibleCountBuffer  = null
        lastSortKeyOutputBuffer = null
        lastPaddedCount         = 0
    }
}
