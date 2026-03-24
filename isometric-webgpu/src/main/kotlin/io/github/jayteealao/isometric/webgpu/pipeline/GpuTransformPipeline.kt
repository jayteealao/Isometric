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
import io.github.jayteealao.isometric.webgpu.shader.TransformCullLightShader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * GPU compute pipeline for the fused Transform + Cull + Light + Compact pass.
 *
 * Manages the lifecycle of:
 * - The WGSL compute pipeline (created once from [TransformCullLightShader])
 * - The `TransformedFace` output storage buffer (recreated when scene capacity grows)
 * - The `visibleCount` atomic counter buffer (created once; 4 bytes)
 * - The bind group (rebuilt whenever any referenced buffer changes)
 *
 * ## Usage
 *
 * ```kotlin
 * val pipeline = GpuTransformPipeline(ctx)
 *
 * // Inside ctx.withGpu { ... } after scene + uniforms buffers are ready:
 * pipeline.ensurePipeline()
 * pipeline.ensureBuffers(sceneCapacity, sceneBuffer, uniformsBuffer)
 * pipeline.resetVisibleCount()           // zero the atomic counter
 * pipeline.dispatch(encoder, faceCount)  // encode the compute pass
 * ```
 *
 * ## Bind group layout
 *
 * ```wgsl
 * @group(0) @binding(0) var<storage, read>       scene:        array<FaceData>
 * @group(0) @binding(1) var<storage, read_write>  transformed:  array<TransformedFace>
 * @group(0) @binding(2) var<uniform>              uniforms:     SceneUniforms
 * @group(0) @binding(3) var<storage, read_write>  visibleCount: atomic<u32>
 * ```
 *
 * ## Lifetime
 *
 * Create once alongside [GpuSceneDataBuffer] and [GpuSceneUniforms].
 * Call [close] on renderer teardown to release all GPU resources.
 * All Dawn API calls must be made from the GPU thread ([GpuContext.withGpu]).
 */
internal class GpuTransformPipeline(
    private val ctx: GpuContext,
) : AutoCloseable {

    companion object {
        private const val TAG = "GpuTransformPipeline"

        /** Size of the `visibleCount` atomic buffer in bytes (single `u32`). */
        private const val VISIBLE_COUNT_BYTES = 4L
    }

    // ── Pipeline objects (created once) ─────────────────────────────────────

    private var shaderModule: GPUShaderModule? = null
    private var bindGroupLayout: GPUBindGroupLayout? = null
    private var pipelineLayout: GPUPipelineLayout? = null
    private var computePipeline: GPUComputePipeline? = null

    // ── Atomic counter (created once; always 4 bytes) ────────────────────────

    /**
     * GPU buffer holding the `atomic<u32>` visible-face counter.
     *
     * Written by the transform shader via `atomicAdd`. Read by downstream
     * passes (M4 sort key packer) as a plain `u32`.
     *
     * Usage: `Storage | CopyDst`.
     * - `Storage` for shader read_write access.
     * - `CopyDst` for `queue.writeBuffer` zeroing before each dispatch.
     */
    private var visibleCountBuf: GPUBuffer? = ctx.device.createBuffer(
        GPUBufferDescriptor(
            usage = BufferUsage.Storage or BufferUsage.CopyDst,
            size = VISIBLE_COUNT_BYTES,
        )
    ).also {
        it.setLabel("IsometricVisibleCount")
        Log.d(TAG, "Allocated visibleCount buffer: $VISIBLE_COUNT_BYTES bytes")
    }

    /** The `visibleCount` buffer. Valid until [close] is called. */
    val visibleCountBuffer: GPUBuffer
        get() = checkNotNull(visibleCountBuf) { "GpuTransformPipeline already closed" }

    // ── Per-capacity resources (recreated when scene buffer grows) ───────────

    /**
     * Output `TransformedFace` storage buffer.
     *
     * Written by the transform shader via atomic compaction.
     * Read by the M4 sort-key packer as `var<storage, read>`.
     *
     * Sized to [cachedCapacity] × [SceneDataLayout.TRANSFORMED_FACE_BYTES].
     * Usage: `Storage` only — no CopyDst/CopySrc needed for M3.
     */
    private var transformedBuf: GPUBuffer? = null
    private var bindGroup: GPUBindGroup? = null

    /** Last scene buffer reference used to build the bind group. */
    private var lastSceneBuffer: GPUBuffer? = null

    /** Last uniforms buffer reference used to build the bind group. */
    private var lastUniformsBuffer: GPUBuffer? = null

    /** Face capacity of the current [transformedBuf]. */
    private var cachedCapacity: Int = 0

    /** The `TransformedFace` buffer. Valid after [ensureBuffers] has been called. */
    val transformedBuffer: GPUBuffer
        get() = checkNotNull(transformedBuf) {
            "GpuTransformPipeline: transformedBuffer not allocated — call ensureBuffers first"
        }

    // ── CPU staging ──────────────────────────────────────────────────────────

    /** Reusable 4-byte zero buffer for [resetVisibleCount]. */
    private val zeroBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    // ── Pipeline init ────────────────────────────────────────────────────────

    /**
     * Create the compute pipeline if it has not yet been built.
     *
     * Must be called from the GPU thread (inside `ctx.withGpu { ... }`).
     * Safe to call multiple times — no-op after the first successful call.
     *
     * [ctx.device.createComputePipelineAndAwait] is a suspend function;
     * this method must be `suspend` as a result.
     */
    suspend fun ensurePipeline() {
        if (computePipeline != null) return

        shaderModule = ctx.device.createShaderModule(
            GPUShaderModuleDescriptor(
                shaderSourceWGSL = GPUShaderSourceWGSL(code = TransformCullLightShader.WGSL)
            )
        )

        val bgl = ctx.device.createBindGroupLayout(
            GPUBindGroupLayoutDescriptor(
                entries = arrayOf(
                    // binding 0 — scene FaceData array (read-only)
                    GPUBindGroupLayoutEntry(
                        binding = 0,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                    ),
                    // binding 1 — transformed TransformedFace array (read-write, compaction)
                    GPUBindGroupLayoutEntry(
                        binding = 1,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Storage),
                    ),
                    // binding 2 — SceneUniforms (projection, lighting, viewport)
                    GPUBindGroupLayoutEntry(
                        binding = 2,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Uniform),
                    ),
                    // binding 3 — visibleCount atomic<u32> (read-write)
                    GPUBindGroupLayoutEntry(
                        binding = 3,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Storage),
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
                    entryPoint = TransformCullLightShader.ENTRY_POINT,
                ),
                layout = pipelineLayout,
            )
        )
        Log.d(TAG, "Compute pipeline ready")
    }

    // ── Buffer management ────────────────────────────────────────────────────

    /**
     * Ensure GPU buffers are sized for [capacity] faces and reference [sceneBuffer]
     * and [uniformsBuffer].
     *
     * Creates the `TransformedFace` output buffer and rebuilds the bind group whenever
     * any of the following change:
     * - [capacity] (scene buffer grew since the last call)
     * - [sceneBuffer] reference (buffer was replaced due to capacity growth)
     * - [uniformsBuffer] reference (should be stable, but guarded for safety)
     *
     * No-op if nothing has changed.
     *
     * Must be called from the GPU thread (inside `ctx.withGpu { ... }`)
     * after [ensurePipeline] has succeeded.
     *
     * @param capacity    Number of faces the scene buffer can currently hold.
     * @param sceneBuffer The `FaceData` storage buffer from [GpuSceneDataBuffer].
     * @param uniformsBuffer The `SceneUniforms` uniform buffer from [GpuSceneUniforms].
     */
    fun ensureBuffers(capacity: Int, sceneBuffer: GPUBuffer, uniformsBuffer: GPUBuffer) {
        val sameCapacity  = capacity == cachedCapacity
        val sameScene     = sceneBuffer    === lastSceneBuffer
        val sameUniforms  = uniformsBuffer === lastUniformsBuffer
        if (sameCapacity && sameScene && sameUniforms) return

        // Release old resources before allocating new ones.
        transformedBuf?.destroy()
        transformedBuf = null
        bindGroup?.close()
        bindGroup = null

        val transformedSize = capacity.toLong() * SceneDataLayout.TRANSFORMED_FACE_BYTES
        transformedBuf = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage,
                size = transformedSize,
            )
        ).also { it.setLabel("IsometricTransformedFaces") }

        bindGroup = ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = checkNotNull(bindGroupLayout) {
                    "Bind group layout is null — call ensurePipeline first"
                },
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, buffer = sceneBuffer),
                    GPUBindGroupEntry(binding = 1, buffer = transformedBuf!!),
                    GPUBindGroupEntry(binding = 2, buffer = uniformsBuffer),
                    GPUBindGroupEntry(binding = 3, buffer = visibleCountBuf!!),
                )
            )
        )

        cachedCapacity     = capacity
        lastSceneBuffer    = sceneBuffer
        lastUniformsBuffer = uniformsBuffer

        Log.d(TAG, "Buffers ready: capacity=$capacity, transformedSize=$transformedSize bytes")
    }

    // ── Per-frame operations ─────────────────────────────────────────────────

    /**
     * Zero the [visibleCountBuffer] via `queue.writeBuffer`.
     *
     * Must be called before [dispatch] on every frame to reset the atomic counter
     * to 0. `queue.writeBuffer` is ordered before any subsequent `queue.submit` on
     * the same queue, so calling this before encoding the command buffer is safe.
     *
     * Must be called from the GPU thread (inside `ctx.withGpu { ... }`).
     */
    fun resetVisibleCount() {
        zeroBuffer.rewind()
        ctx.queue.writeBuffer(visibleCountBuf!!, 0L, zeroBuffer)
    }

    /**
     * Encode the transform + cull + light compute dispatch into [encoder].
     *
     * Dispatches `ceil(faceCount / WORKGROUP_SIZE)` workgroups. Each invocation
     * checks its thread index against `uniforms.viewport.z` (faceCount) so any
     * over-dispatch threads are harmless no-ops.
     *
     * The compute pass encoder is closed immediately after [end] to release
     * the JNI wrapper (see `docs/internal/GPU_OBJECT_LIFETIME.md`).
     *
     * Preconditions:
     * - [ensurePipeline] has returned successfully.
     * - [ensureBuffers] has been called with the current scene buffer and capacity.
     * - [resetVisibleCount] has been called on this frame (or the caller has zeroed
     *   `visibleCountBuffer` by another means).
     * - [faceCount] matches the value uploaded to `uniforms.viewport.z` so the
     *   shader correctly bounds-checks its invocation index.
     *
     * Must be called from the GPU thread (inside `ctx.withGpu { ... }`).
     *
     * @param encoder    The command encoder to record into.
     * @param faceCount  Number of active faces in the scene (> 0).
     */
    fun dispatch(encoder: GPUCommandEncoder, faceCount: Int) {
        require(faceCount > 0) { "faceCount must be > 0, got $faceCount" }
        checkNotNull(computePipeline) { "Pipeline not ready — call ensurePipeline first" }
        checkNotNull(bindGroup) { "Bind group not ready — call ensureBuffers first" }

        val workgroups = ceil(faceCount.toDouble() / TransformCullLightShader.WORKGROUP_SIZE)
            .toInt()

        val pass = encoder.beginComputePass()
        pass.setPipeline(computePipeline!!)
        pass.setBindGroup(0, bindGroup!!)
        pass.dispatchWorkgroups(workgroups)
        pass.end()
        pass.close()   // release JNI wrapper immediately (GPU_OBJECT_LIFETIME.md)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun close() {
        // Per-capacity resources
        bindGroup?.close()
        bindGroup = null
        transformedBuf?.destroy()
        transformedBuf?.close()
        transformedBuf = null

        // Persistent atomic counter
        visibleCountBuf?.destroy()
        visibleCountBuf?.close()
        visibleCountBuf = null

        // Pipeline objects
        computePipeline?.close()
        computePipeline = null
        pipelineLayout?.close()
        pipelineLayout = null
        bindGroupLayout?.close()
        bindGroupLayout = null
        shaderModule?.close()
        shaderModule = null

        lastSceneBuffer    = null
        lastUniformsBuffer = null
        cachedCapacity     = 0
    }
}
