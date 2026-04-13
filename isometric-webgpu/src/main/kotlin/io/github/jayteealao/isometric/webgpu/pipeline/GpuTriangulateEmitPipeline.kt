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
import io.github.jayteealao.isometric.webgpu.shader.TriangulateEmitShader
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * GPU compute pipeline for the M5 triangulate-and-emit pass.
 *
 * Encodes a single sub-pass ([TriangulateEmitShader]) that uses **fixed-stride vertex
 * allocation** — no `atomicAdd` — to avoid the Adreno 750 TDR caused by contended atomics.
 *
 * Each sort entry `i` writes exactly [TriangulateEmitShader.MAX_VERTICES_PER_FACE] = 12
 * vertex slots at `i × 12`. Visible faces fill `triCount × 3` slots with real triangles
 * and pad the remaining slots with degenerate (zero-area) vertices. Sentinels write all
 * 12 slots as degenerate.
 *
 * The total vertex count written per frame is always
 * `paddedCount × MAX_VERTICES_PER_FACE`, and that value is supplied to the render pass
 * via [indirectArgsBuffer], which is written from Kotlin via `queue.writeBuffer` in
 * [GpuFullPipeline.upload] (no GPU-side compute pass needed).
 *
 * ## Usage
 *
 * ```kotlin
 * val emit = GpuTriangulateEmitPipeline(ctx)
 *
 * // Inside ctx.withGpu { ... } at pipeline init time:
 * emit.ensurePipelines()
 * emit.ensureBuffers(
 *     paddedCount       = paddedCount,
 *     viewportWidth     = surfaceWidth,
 *     viewportHeight    = surfaceHeight,
 *     transformedBuffer = transform.transformedBuffer,
 *     sortedKeysBuffer  = sort.resultBuffer,
 * )
 *
 * // Per frame (inside a single command encoder, after sort.dispatch):
 * emit.dispatch(encoder)    // encodes the emit sub-pass
 *
 * // After queue.submit, the render pass can consume:
 * pass.setVertexBuffer(0, emit.vertexBuffer)
 * pass.drawIndirect(emit.indirectArgsBuffer, 0L)
 * ```
 *
 * ## Buffer ownership
 *
 * All buffers are owned by this class and released in [close].
 *
 * ## Buffer usages
 *
 * | Buffer              | Usages                          | Reason                                  |
 * |---------------------|---------------------------------|-----------------------------------------|
 * | [vertexBuffer]      | Storage \| Vertex \| CopySrc    | Compute write; render read; M8 readback |
 * | [indirectArgsBuffer]| CopyDst \| Indirect             | CPU write via writeBuffer; drawIndirect |
 *
 * ## Lifetime
 *
 * Create once alongside [GpuBitonicSort] and [GpuTransformPipeline].
 * Call [close] on renderer teardown.
 * All Dawn API calls must be made from the GPU thread ([GpuContext.withGpu]).
 */
internal class GpuTriangulateEmitPipeline(
    private val ctx: GpuContext,
) : AutoCloseable {

    companion object {
        private const val TAG = "GpuTriangulateEmitPipeline"

        /** Size of the [indirectArgsBuffer] in bytes (4 × u32 = 16 bytes). */
        private const val INDIRECT_ARGS_BYTES = 16L
    }

    // ── Emit pipeline objects (created once) ─────────────────────────────────

    private var emitShaderModule: GPUShaderModule? = null
    private var emitBindGroupLayout: GPUBindGroupLayout? = null
    private var emitPipelineLayout: GPUPipelineLayout? = null
    private var emitPipeline: GPUComputePipeline? = null

    // ── Per-capacity GPU buffers (recreated when paddedCount changes) ──────────

    private var vertexBuf: GPUBuffer? = null

    // ── Persistent buffer (fixed size, allocated once) ─────────────────────────

    /**
     * GPU indirect draw args buffer.
     *
     * Written from Kotlin via `queue.writeBuffer` in [GpuFullPipeline.upload].
     * Layout: `{ vertexCount, instanceCount=1, firstVertex=0, firstInstance=0 }`.
     *
     * Usage: `CopyDst | Indirect`.
     */
    private var indirectArgsBuf: GPUBuffer? = ctx.device.createBuffer(
        GPUBufferDescriptor(
            usage = BufferUsage.CopyDst or BufferUsage.Indirect,
            size = INDIRECT_ARGS_BYTES,
        )
    ).also {
        it.setLabel("IsometricIndirectArgs")
        Log.d(TAG, "Allocated indirectArgs buffer: $INDIRECT_ARGS_BYTES bytes")
    }

    // ── Per-input bind group (rebuilt when any input changes) ──────────────────

    private var emitBindGroup: GPUBindGroup? = null
    private var paramsBuffer: GPUBuffer? = null

    // ── Change-detection state ────────────────────────────────────────────────

    private var lastPaddedCount: Int = 0
    private var lastViewportWidth: Int = 0
    private var lastViewportHeight: Int = 0
    private var lastTransformedBuffer: GPUBuffer? = null
    private var lastSortedKeysBuffer: GPUBuffer? = null
    private var lastTexIndexBuffer: GPUBuffer? = null
    private var lastUvRegionBuffer: GPUBuffer? = null
    private var lastUvCoordsBuffer: GPUBuffer? = null

    // ── Exposed buffers ───────────────────────────────────────────────────────

    /**
     * The GPU vertex buffer written by [dispatch].
     *
     * Bind as vertex buffer in the M6 render pass after [dispatch] is submitted.
     * Usage: `Storage | Vertex | CopySrc`.
     *
     * Valid after [ensureBuffers] has been called.
     */
    val vertexBuffer: GPUBuffer
        get() = checkNotNull(vertexBuf) {
            "GpuTriangulateEmitPipeline: vertexBuffer not allocated — call ensureBuffers first"
        }

    /**
     * The GPU indirect draw args buffer written by [GpuFullPipeline.upload].
     *
     * Pass to `renderPass.drawIndirect(indirectArgsBuffer, 0L)` in M6.
     * Usage: `CopyDst | Indirect`.
     *
     * Valid after construction.
     */
    val indirectArgsBuffer: GPUBuffer
        get() = checkNotNull(indirectArgsBuf) {
            "GpuTriangulateEmitPipeline: indirectArgsBuffer not allocated"
        }

    // ── Pipeline init ─────────────────────────────────────────────────────────

    /**
     * Create the emit compute pipeline if not already built.
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     * Safe to call multiple times — no-op after the first successful call.
     */
    suspend fun ensurePipelines() {
        if (emitPipeline != null) return

        emitShaderModule?.close(); emitShaderModule = null
        emitBindGroupLayout?.close(); emitBindGroupLayout = null
        emitPipelineLayout?.close(); emitPipelineLayout = null

        emitShaderModule = ctx.device.createShaderModule(
            GPUShaderModuleDescriptor(
                shaderSourceWGSL = GPUShaderSourceWGSL(code = TriangulateEmitShader.WGSL)
            )
        )

        // Storage-buffer slot usage (WebGPU limit: maxStorageBuffersPerShaderStage = 8):
        //   binding 0 — transformed    (ReadOnlyStorage)
        //   binding 1 — sortedKeys     (ReadOnlyStorage)
        //   binding 2 — vertices       (Storage, read_write)
        //   binding 4 — sceneTexIndices (ReadOnlyStorage)
        //   binding 5 — sceneUvRegions  (ReadOnlyStorage)
        //   binding 6 — sceneUvCoords   (ReadOnlyStorage)
        // Total: 6 of 8 storage-buffer slots used; 2 slots remain.
        // Binding 3 is a Uniform buffer and does not count against this limit.
        // If approaching the limit, consider consolidating sceneTexIndices + sceneUvRegions
        // into a single interleaved buffer (5 u32 per face, ~20 bytes, still < 32 b alignment).
        val bgl = ctx.device.createBindGroupLayout(
            GPUBindGroupLayoutDescriptor(
                entries = arrayOf(
                    // binding 0 — TransformedFace array from M3 (read-only)
                    GPUBindGroupLayoutEntry(
                        binding = 0,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                    ),
                    // binding 1 — sorted SortKey array from M4 (read-only)
                    GPUBindGroupLayoutEntry(
                        binding = 1,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                    ),
                    // binding 2 — flat u32 vertex output buffer (read_write)
                    GPUBindGroupLayoutEntry(
                        binding = 2,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Storage),
                    ),
                    // binding 3 — EmitUniforms (viewportWidth, viewportHeight, paddedCount)
                    GPUBindGroupLayoutEntry(
                        binding = 3,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Uniform),
                    ),
                    // binding 4 — compact per-face texture index array (read-only)
                    GPUBindGroupLayoutEntry(
                        binding = 4,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                    ),
                    // binding 5 — compact per-face UV region array (read-only)
                    GPUBindGroupLayoutEntry(
                        binding = 5,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                    ),
                    // binding 6 — per-vertex UV coordinates (read-only, 3 × vec4 per face)
                    GPUBindGroupLayoutEntry(
                        binding = 6,
                        visibility = ShaderStage.Compute,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                    ),
                )
            )
        )
        emitBindGroupLayout = bgl

        emitPipelineLayout = ctx.device.createPipelineLayout(
            GPUPipelineLayoutDescriptor(bindGroupLayouts = arrayOf(bgl))
        )

        emitPipeline = ctx.device.createComputePipelineAndAwait(
            GPUComputePipelineDescriptor(
                compute = GPUComputeState(
                    module = emitShaderModule!!,
                    entryPoint = TriangulateEmitShader.ENTRY_POINT,
                ),
                layout = emitPipelineLayout,
            )
        )
        Log.d(TAG, "Emit pipeline ready")
    }

    // ── Buffer management ─────────────────────────────────────────────────────

    /**
     * Ensure buffers and bind groups are ready for [paddedCount] sorted entries.
     *
     * Rebuilds bind groups whenever any input changes. Reallocates [vertexBuffer]
     * only when [paddedCount] changes. Rebuilds [paramsBuffer] only when viewport
     * dimensions or [paddedCount] change.
     *
     * [paddedCount] must be a power of two and > 0.
     *
     * Must be called from the GPU thread after [ensurePipelines] has succeeded.
     *
     * @param paddedCount       Power-of-two sort array size.
     * @param viewportWidth     Current surface width in pixels.
     * @param viewportHeight    Current surface height in pixels.
     * @param transformedBuffer M3 [GpuTransformPipeline.transformedBuffer].
     * @param sortedKeysBuffer  M4 [GpuBitonicSort.resultBuffer].
     * @param texIndexBuffer    Compact per-face texture index buffer (binding 4).
     * @param uvRegionBuffer   Compact per-face UV region buffer (binding 5). Each entry
     *                         is 4 floats: [uvOffsetU, uvOffsetV, uvScaleU, uvScaleV].
     * @param uvCoordsBuffer   Per-vertex UV coordinates buffer (binding 6). Each face
     *                         gets 3 × vec4<f32> = 48 bytes: [(u0,v0,u1,v1),(u2,v2,u3,v3),(u4,v4,u5,v5)].
     */
    fun ensureBuffers(
        paddedCount: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        transformedBuffer: GPUBuffer,
        sortedKeysBuffer: GPUBuffer,
        texIndexBuffer: GPUBuffer,
        uvRegionBuffer: GPUBuffer,
        uvCoordsBuffer: GPUBuffer,
    ) {
        require(paddedCount > 0) { "paddedCount must be > 0, got $paddedCount" }

        val same = paddedCount       == lastPaddedCount &&
            viewportWidth            == lastViewportWidth &&
            viewportHeight           == lastViewportHeight &&
            transformedBuffer        === lastTransformedBuffer &&
            sortedKeysBuffer         === lastSortedKeysBuffer &&
            texIndexBuffer           === lastTexIndexBuffer &&
            uvRegionBuffer           === lastUvRegionBuffer &&
            uvCoordsBuffer           === lastUvCoordsBuffer
        if (same) return

        // Release stale bind group before rebuilding.
        emitBindGroup?.close()
        emitBindGroup = null

        // Vertex buffer sized by paddedCount: every sort entry gets MAX_VERTICES_PER_FACE
        // vertex slots in the fixed-stride layout.
        val vertexBufSize =
            paddedCount.toLong() *
                TriangulateEmitShader.MAX_VERTICES_PER_FACE *
                TriangulateEmitShader.BYTES_PER_VERTEX
        if (paddedCount != lastPaddedCount) {
            // Note: close() only (no destroy()) — the previous frame's GPU commands may still
            // reference these buffers. Dawn's internal ref-counting keeps the underlying GPU
            // memory alive until all pending commands complete; destroy() would mark the buffer
            // as "dead" to Dawn's validation layer while it is still in-flight.
            vertexBuf?.close()
            vertexBuf = null
        }
        if (vertexBuf == null) {
            vertexBuf = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    // Storage: compute write (fixed-stride, no atomics).
                    // Vertex:  consumed by the render pass as a vertex buffer.
                    // CopySrc: enables GPU→CPU readback for M8 validation.
                    usage = BufferUsage.Storage or BufferUsage.Vertex or BufferUsage.CopySrc,
                    size = vertexBufSize,
                )
            ).also { it.setLabel("IsometricEmitVertices") }
        }

        // EmitUniforms: rebuild only when viewport or paddedCount changes.
        val paramsChanged = viewportWidth  != lastViewportWidth  ||
            viewportHeight != lastViewportHeight ||
            paddedCount    != lastPaddedCount
        if (paramsChanged || paramsBuffer == null) {
            // Note: close() only (no destroy()) — same lifecycle reason as above.
            paramsBuffer?.close()
            paramsBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Uniform,
                    size = 16L,
                    mappedAtCreation = true,
                )
            ).also { buf ->
                buf.setLabel("IsometricEmitParams")
                val mapped = buf.getMappedRange(0L, 16L).order(ByteOrder.nativeOrder())
                mapped.putInt(viewportWidth)
                mapped.putInt(viewportHeight)
                mapped.putInt(paddedCount)
                mapped.putInt(0)  // _pad
                buf.unmap()
            }
        }

        emitBindGroup = ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = checkNotNull(emitBindGroupLayout) {
                    "Emit bind group layout is null — call ensurePipelines first"
                },
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, buffer = transformedBuffer),
                    GPUBindGroupEntry(binding = 1, buffer = sortedKeysBuffer),
                    GPUBindGroupEntry(binding = 2, buffer = vertexBuf!!),
                    GPUBindGroupEntry(binding = 3, buffer = paramsBuffer!!),
                    GPUBindGroupEntry(binding = 4, buffer = texIndexBuffer),
                    GPUBindGroupEntry(binding = 5, buffer = uvRegionBuffer),
                    GPUBindGroupEntry(binding = 6, buffer = uvCoordsBuffer),
                )
            )
        )

        lastPaddedCount       = paddedCount
        lastViewportWidth     = viewportWidth
        lastViewportHeight    = viewportHeight
        lastTransformedBuffer = transformedBuffer
        lastSortedKeysBuffer  = sortedKeysBuffer
        lastTexIndexBuffer    = texIndexBuffer
        lastUvRegionBuffer    = uvRegionBuffer
        lastUvCoordsBuffer    = uvCoordsBuffer

        Log.d(
            TAG,
            "Buffers ready: paddedCount=$paddedCount viewport=${viewportWidth}×${viewportHeight} " +
                "vertexBufSize=${vertexBufSize}B"
        )
    }

    // ── Per-frame dispatch ────────────────────────────────────────────────────

    /**
     * Encode the triangulate-and-emit compute pass into [encoder].
     *
     * Dispatches `ceil(paddedCount / WORKGROUP_SIZE)` workgroups. Each thread writes
     * [TriangulateEmitShader.MAX_VERTICES_PER_FACE] = 12 vertex slots using a fixed
     * stride. No `atomicAdd` is used — no Adreno TDR risk.
     *
     * The draw call vertex count (`paddedCount × MAX_VERTICES_PER_FACE`) must have
     * already been written to [indirectArgsBuffer] via `queue.writeBuffer` before the
     * encoder is submitted (see [GpuFullPipeline.upload]).
     *
     * Preconditions:
     * - [ensurePipelines] has returned.
     * - [ensureBuffers] has been called with the current inputs.
     * - M4 sort has been encoded before this call (sort result is in [sortedKeysBuffer]).
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     *
     * @param encoder The command encoder to record into.
     */
    fun dispatch(
        encoder: GPUCommandEncoder,
        timestampWrites: androidx.webgpu.GPUPassTimestampWrites? = null,
    ) {
        ctx.assertGpuThread()
        check(emitPipeline != null) { "Pipeline not ready — call ensurePipelines first" }
        check(emitBindGroup != null) { "Bind group not ready — call ensureBuffers first" }

        val workgroupCount =
            ceil(lastPaddedCount.toDouble() / TriangulateEmitShader.WORKGROUP_SIZE).toInt()

        val emitPass = timestampWrites?.let {
            encoder.beginComputePass(androidx.webgpu.GPUComputePassDescriptor(timestampWrites = it))
        } ?: encoder.beginComputePass()
        emitPass.setPipeline(emitPipeline!!)
        emitPass.setBindGroup(0, emitBindGroup!!)
        emitPass.dispatchWorkgroups(workgroupCount)
        emitPass.end()
        emitPass.close()   // release JNI wrapper immediately (GPU_OBJECT_LIFETIME.md)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun close() {
        // Per-input bind group and params buffer
        emitBindGroup?.close()
        emitBindGroup = null
        paramsBuffer?.close()
        paramsBuffer = null

        // Per-capacity buffer
        vertexBuf?.close()
        vertexBuf = null

        // Persistent buffer (fixed size, allocated once)
        indirectArgsBuf?.close()
        indirectArgsBuf = null

        // Pipeline objects
        emitPipeline?.close()
        emitPipeline = null
        emitPipelineLayout?.close()
        emitPipelineLayout = null
        emitBindGroupLayout?.close()
        emitBindGroupLayout = null
        emitShaderModule?.close()
        emitShaderModule = null

        lastPaddedCount       = 0
        lastViewportWidth     = 0
        lastViewportHeight    = 0
        lastTransformedBuffer = null
        lastSortedKeysBuffer  = null
        lastTexIndexBuffer    = null
        lastUvRegionBuffer    = null
        lastUvCoordsBuffer    = null
    }
}
