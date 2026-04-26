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
import io.github.jayteealao.isometric.webgpu.diagnostics.WgslDiagnostics
import io.github.jayteealao.isometric.webgpu.shader.TransformCullLightShader
import kotlin.math.ceil

/**
 * GPU compute pipeline for the fused Transform + Cull + Light pass.
 *
 * Manages the lifecycle of:
 * - The WGSL compute pipeline (created once from [TransformCullLightShader])
 * - The `TransformedFace` output storage buffer (recreated when scene capacity grows)
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
 * pipeline.dispatch(encoder, faceCount)  // encode the compute pass
 * ```
 *
 * ## Bind group layout
 *
 * ```wgsl
 * @group(0) @binding(0) var<storage, read>       scene:       array<FaceData>
 * @group(0) @binding(1) var<storage, read_write>  transformed: array<TransformedFace>
 * @group(0) @binding(2) var<uniform>              uniforms:    SceneUniforms
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
    }

    // ── Pipeline objects (created once) ─────────────────────────────────────

    private var shaderModule: GPUShaderModule? = null
    private var bindGroupLayout: GPUBindGroupLayout? = null
    private var pipelineLayout: GPUPipelineLayout? = null
    private var computePipeline: GPUComputePipeline? = null

    // ── Per-capacity resources (recreated when scene buffer grows) ───────────

    /**
     * Output `TransformedFace` storage buffer.
     *
     * Written sequentially by the transform shader: `transformed[i] = result`.
     * Each entry's `visible` field indicates whether the face passed cull tests.
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
        WgslDiagnostics.logCompilation(shaderModule!!, TAG)

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
        // Note: close() only (no destroy()) — the previous frame's GPU commands may still
        // reference these buffers. Dawn's internal ref-counting keeps the underlying GPU
        // memory alive until all pending commands complete; destroy() would incorrectly mark
        // the buffer as "dead" to Dawn's validation layer while it is still in-flight.
        transformedBuf?.close()
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
                )
            )
        )

        cachedCapacity     = capacity
        lastSceneBuffer    = sceneBuffer
        lastUniformsBuffer = uniformsBuffer

        Log.d(TAG, "Buffers ready: capacity=$capacity, transformedSize=$transformedSize bytes")
    }

    // ── Per-frame dispatch ───────────────────────────────────────────────────

    /**
     * Encode the transform + cull + light compute dispatch into [encoder].
     *
     * Dispatches `ceil(faceCount / WORKGROUP_SIZE)` workgroups. Each invocation
     * checks its thread index against `uniforms.viewport.z` (faceCount) so any
     * over-dispatch threads are harmless no-ops.
     *
     * Each thread writes its result to `transformed[i]` (its own sequential slot)
     * with `visible = 1u` if the face passed all cull tests, `visible = 0u` otherwise.
     * No atomic counter is used — this is safe on Adreno and all other Vulkan drivers.
     *
     * The compute pass encoder is closed immediately after [end] to release
     * the JNI wrapper (see `docs/internal/GPU_OBJECT_LIFETIME.md`).
     *
     * Preconditions:
     * - [ensurePipeline] has returned successfully.
     * - [ensureBuffers] has been called with the current scene buffer and capacity.
     * - [faceCount] matches the value uploaded to `uniforms.viewport.z` so the
     *   shader correctly bounds-checks its invocation index.
     *
     * Must be called from the GPU thread (inside `ctx.withGpu { ... }`).
     *
     * @param encoder    The command encoder to record into.
     * @param faceCount  Number of active faces in the scene (> 0).
     */
    fun dispatch(
        encoder: GPUCommandEncoder,
        faceCount: Int,
        timestampWrites: androidx.webgpu.GPUPassTimestampWrites? = null,
    ) {
        ctx.assertGpuThread()
        require(faceCount > 0) { "faceCount must be > 0, got $faceCount" }
        checkNotNull(computePipeline) { "Pipeline not ready — call ensurePipeline first" }
        checkNotNull(bindGroup) { "Bind group not ready — call ensureBuffers first" }

        val workgroups = ceil(faceCount.toDouble() / TransformCullLightShader.WORKGROUP_SIZE)
            .toInt()

        val pass = timestampWrites?.let {
            encoder.beginComputePass(androidx.webgpu.GPUComputePassDescriptor(timestampWrites = it))
        } ?: encoder.beginComputePass()
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
        transformedBuf?.close()
        transformedBuf = null

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
