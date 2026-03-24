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
import io.github.jayteealao.isometric.webgpu.shader.WriteIndirectArgsShader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * GPU compute pipeline for the M5 triangulate-and-emit pass.
 *
 * Operates in two sub-passes encoded into the same [GPUCommandEncoder]:
 *
 * **M5a — triangulateEmit** ([TriangulateEmitShader]):
 * Reads the M4 sorted [SortKey] array, fan-triangulates each visible [TransformedFace],
 * and writes NDC vertices into [vertexBuffer] via an atomic bump allocator.
 * One thread per sorted entry; sentinels are skipped.
 *
 * **M5b — writeIndirectArgs** ([WriteIndirectArgsShader]):
 * A single-thread pass that runs after M5a. Reads the final `vertexCursor` value and
 * writes a [GPUDrawIndirectArgs] struct into [indirectArgsBuffer], so M6 can call
 * `drawIndirect` without any CPU readback.
 *
 * ## Usage
 *
 * ```kotlin
 * val emit = GpuTriangulateEmitPipeline(ctx)
 *
 * // Inside ctx.withGpu { ... } at pipeline init time:
 * emit.ensurePipelines()
 * emit.ensureBuffers(
 *     faceCapacity      = sceneCapacity,
 *     paddedCount       = paddedCount,   // value previously passed to sort.ensureBuffers()
 *     viewportWidth     = surfaceWidth,
 *     viewportHeight    = surfaceHeight,
 *     transformedBuffer = transform.transformedBuffer,
 *     sortedKeysBuffer  = sort.resultBuffer,
 * )
 *
 * // Per frame (inside a single command encoder, after sort.dispatch):
 * emit.resetVertexCursor()  // zero the atomic counter before encoding
 * emit.dispatch(encoder)    // encodes both sub-passes
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
 * | Buffer              | Usages                        | Reason                                 |
 * |---------------------|-------------------------------|----------------------------------------|
 * | [vertexBuffer]      | Storage \| Vertex \| CopySrc  | Compute write; render read; M8 readback|
 * | `vertexCursorBuf`   | Storage \| CopyDst            | Atomic read_write; `writeBuffer` zero  |
 * | [indirectArgsBuffer]| Storage \| Indirect           | Compute write; `drawIndirect` source   |
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

        /** Size of the `vertexCursor` atomic buffer in bytes (single `u32`). */
        private const val VERTEX_CURSOR_BYTES = 4L
    }

    // ── Emit pipeline objects (created once) ─────────────────────────────────

    private var emitShaderModule: GPUShaderModule? = null
    private var emitBindGroupLayout: GPUBindGroupLayout? = null
    private var emitPipelineLayout: GPUPipelineLayout? = null
    private var emitPipeline: GPUComputePipeline? = null

    // ── Indirect-args pipeline objects (created once) ─────────────────────────

    private var indirectShaderModule: GPUShaderModule? = null
    private var indirectBindGroupLayout: GPUBindGroupLayout? = null
    private var indirectPipelineLayout: GPUPipelineLayout? = null
    private var indirectPipeline: GPUComputePipeline? = null

    // ── Per-capacity GPU buffers (recreated when faceCapacity changes) ─────────

    private var vertexBuf: GPUBuffer? = null

    // ── Persistent buffers (size-independent; allocated once) ─────────────────

    /**
     * GPU buffer holding the `atomic<u32>` vertex cursor.
     *
     * Zeroed before each emit dispatch via [resetVertexCursor] (`queue.writeBuffer`).
     * After [dispatch], `vertexCursor[0]` holds the total vertices written.
     *
     * Usage: `Storage | CopyDst`.
     */
    private var vertexCursorBuf: GPUBuffer? = ctx.device.createBuffer(
        GPUBufferDescriptor(
            usage = BufferUsage.Storage or BufferUsage.CopyDst,
            size = VERTEX_CURSOR_BYTES,
        )
    ).also {
        it.setLabel("IsometricVertexCursor")
        Log.d(TAG, "Allocated vertexCursor buffer: $VERTEX_CURSOR_BYTES bytes")
    }

    /**
     * GPU buffer holding the [DrawIndirectArgs] struct written by M5b.
     *
     * Always 16 bytes (4 × u32). Size does not vary with scene capacity, so it is
     * allocated once at construction like [vertexCursorBuf].
     *
     * Usage: `Storage | Indirect`.
     */
    private var indirectArgsBuf: GPUBuffer? = ctx.device.createBuffer(
        GPUBufferDescriptor(
            // Storage:  compute shader writes DrawIndirectArgs fields.
            // Indirect: consumed by renderPass.drawIndirect.
            usage = BufferUsage.Storage or BufferUsage.Indirect,
            size = WriteIndirectArgsShader.INDIRECT_ARGS_BYTES,
        )
    ).also {
        it.setLabel("IsometricIndirectArgs")
        Log.d(TAG, "Allocated indirectArgs buffer: ${WriteIndirectArgsShader.INDIRECT_ARGS_BYTES} bytes")
    }

    // ── Per-input bind groups (rebuilt when any input changes) ─────────────────

    private var emitBindGroup: GPUBindGroup? = null
    private var indirectBindGroup: GPUBindGroup? = null
    private var paramsBuffer: GPUBuffer? = null

    // ── Change-detection state ────────────────────────────────────────────────

    private var lastFaceCapacity: Int = 0
    private var lastPaddedCount: Int = 0
    private var lastViewportWidth: Int = 0
    private var lastViewportHeight: Int = 0
    private var lastTransformedBuffer: GPUBuffer? = null
    private var lastSortedKeysBuffer: GPUBuffer? = null

    // ── Reusable CPU zero buffer for vertexCursor reset ───────────────────────

    /** Pre-allocated 4-byte zero buffer; avoids per-frame allocation. */
    private val zeroBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

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
     * The GPU indirect draw args buffer written by [dispatch].
     *
     * Pass to `renderPass.drawIndirect(indirectArgsBuffer, 0L)` in M6.
     * Usage: `Storage | Indirect`.
     *
     * Valid after [ensureBuffers] and [dispatch] have been called.
     */
    val indirectArgsBuffer: GPUBuffer
        get() = checkNotNull(indirectArgsBuf) {
            "GpuTriangulateEmitPipeline: indirectArgsBuffer not allocated — call ensureBuffers first"
        }

    // ── Pipeline init ─────────────────────────────────────────────────────────

    /**
     * Create both compute pipelines if not already built.
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     * Safe to call multiple times — no-op after the first successful call.
     */
    suspend fun ensurePipelines() {
        if (emitPipeline != null && indirectPipeline != null) return

        if (emitPipeline == null) {
            // Close any partially-built objects left by a prior failed attempt.
            emitShaderModule?.close(); emitShaderModule = null
            emitBindGroupLayout?.close(); emitBindGroupLayout = null
            emitPipelineLayout?.close(); emitPipelineLayout = null

            emitShaderModule = ctx.device.createShaderModule(
                GPUShaderModuleDescriptor(
                    shaderSourceWGSL = GPUShaderSourceWGSL(code = TriangulateEmitShader.WGSL)
                )
            )

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
                        // binding 3 — vertexCursor atomic<u32> (read_write)
                        GPUBindGroupLayoutEntry(
                            binding = 3,
                            visibility = ShaderStage.Compute,
                            buffer = GPUBufferBindingLayout(type = BufferBindingType.Storage),
                        ),
                        // binding 4 — EmitUniforms (viewportWidth, viewportHeight, paddedCount)
                        GPUBindGroupLayoutEntry(
                            binding = 4,
                            visibility = ShaderStage.Compute,
                            buffer = GPUBufferBindingLayout(type = BufferBindingType.Uniform),
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

        if (indirectPipeline == null) {
            // Close any partially-built objects left by a prior failed attempt.
            indirectShaderModule?.close(); indirectShaderModule = null
            indirectBindGroupLayout?.close(); indirectBindGroupLayout = null
            indirectPipelineLayout?.close(); indirectPipelineLayout = null

            indirectShaderModule = ctx.device.createShaderModule(
                GPUShaderModuleDescriptor(
                    shaderSourceWGSL = GPUShaderSourceWGSL(code = WriteIndirectArgsShader.WGSL)
                )
            )

            val bgl = ctx.device.createBindGroupLayout(
                GPUBindGroupLayoutDescriptor(
                    entries = arrayOf(
                        // binding 0 — vertexCursor read as array<u32> after atomics complete
                        GPUBindGroupLayoutEntry(
                            binding = 0,
                            visibility = ShaderStage.Compute,
                            buffer = GPUBufferBindingLayout(type = BufferBindingType.ReadOnlyStorage),
                        ),
                        // binding 1 — DrawIndirectArgs output (read_write)
                        GPUBindGroupLayoutEntry(
                            binding = 1,
                            visibility = ShaderStage.Compute,
                            buffer = GPUBufferBindingLayout(type = BufferBindingType.Storage),
                        ),
                    )
                )
            )
            indirectBindGroupLayout = bgl

            indirectPipelineLayout = ctx.device.createPipelineLayout(
                GPUPipelineLayoutDescriptor(bindGroupLayouts = arrayOf(bgl))
            )

            indirectPipeline = ctx.device.createComputePipelineAndAwait(
                GPUComputePipelineDescriptor(
                    compute = GPUComputeState(
                        module = indirectShaderModule!!,
                        entryPoint = WriteIndirectArgsShader.ENTRY_POINT,
                    ),
                    layout = indirectPipelineLayout,
                )
            )
            Log.d(TAG, "Indirect-args pipeline ready")
        }
    }

    // ── Buffer management ─────────────────────────────────────────────────────

    /**
     * Ensure buffers and bind groups are ready for [faceCapacity] faces.
     *
     * Rebuilds bind groups whenever any input changes. Reallocates [vertexBuffer] and
     * [indirectArgsBuffer] only when [faceCapacity] changes (the params buffer and bind
     * groups are always rebuilt on any change because viewport dims and sorted-keys
     * reference can also change independently).
     *
     * [faceCapacity] must be > 0 and should match the value used to allocate M3's
     * [GpuTransformPipeline] transformed buffer (i.e., the scene's face capacity before
     * power-of-two padding).
     *
     * Must be called from the GPU thread after [ensurePipelines] has succeeded and
     * after [GpuBitonicSort.ensureBuffers] has been called (since [sortedKeysBuffer]
     * is [GpuBitonicSort.resultBuffer], which is only valid after that call).
     *
     * @param faceCapacity     Max faces the scene can currently hold (non-padded).
     * @param paddedCount      Power-of-two sort array size from [BitonicSortNetwork.nextPowerOfTwo].
     * @param viewportWidth    Current surface width in pixels.
     * @param viewportHeight   Current surface height in pixels.
     * @param transformedBuffer M3 [GpuTransformPipeline.transformedBuffer].
     * @param sortedKeysBuffer  M4 [GpuBitonicSort.resultBuffer].
     */
    fun ensureBuffers(
        faceCapacity: Int,
        paddedCount: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        transformedBuffer: GPUBuffer,
        sortedKeysBuffer: GPUBuffer,
    ) {
        require(faceCapacity > 0) { "faceCapacity must be > 0, got $faceCapacity" }
        require(paddedCount > 0) { "paddedCount must be > 0, got $paddedCount" }

        val same = faceCapacity    == lastFaceCapacity &&
            paddedCount            == lastPaddedCount &&
            viewportWidth          == lastViewportWidth &&
            viewportHeight         == lastViewportHeight &&
            transformedBuffer      === lastTransformedBuffer &&
            sortedKeysBuffer       === lastSortedKeysBuffer
        if (same) return

        // Release stale bind groups before rebuilding (params buffer handled separately below).
        emitBindGroup?.close()
        emitBindGroup = null
        indirectBindGroup?.close()
        indirectBindGroup = null

        // Vertex buffer is only sized by faceCapacity — recreate only when capacity grows.
        // Worst case: every face is a 6-vertex hexagon → 4 triangles → 12 vertices.
        val vertexBufSize =
            faceCapacity.toLong() *
                TriangulateEmitShader.MAX_VERTICES_PER_FACE *
                TriangulateEmitShader.BYTES_PER_VERTEX
        if (faceCapacity != lastFaceCapacity) {
            vertexBuf?.destroy()
            vertexBuf?.close()
            vertexBuf = null
        }
        if (vertexBuf == null) {
            vertexBuf = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    // Storage: compute write via atomic allocation.
                    // Vertex:  consumed by the render pass as a vertex buffer.
                    // CopySrc: enables GPU→CPU readback for M8 validation.
                    usage = BufferUsage.Storage or BufferUsage.Vertex or BufferUsage.CopySrc,
                    size = vertexBufSize,
                )
            ).also { it.setLabel("IsometricEmitVertices") }
        }

        // EmitUniforms only depends on viewport dims and paddedCount — not on the buffer
        // references.  Rebuild the params buffer only when those three values change to
        // avoid a GPU allocation when only transformedBuffer/sortedKeysBuffer reference
        // changes (e.g., GpuBitonicSort.resultBuffer ping-ponging on capacity growth).
        val paramsChanged = viewportWidth   != lastViewportWidth  ||
            viewportHeight  != lastViewportHeight ||
            paddedCount     != lastPaddedCount
        if (paramsChanged || paramsBuffer == null) {
            paramsBuffer?.destroy()
            paramsBuffer?.close()
            paramsBuffer = null
        }
        if (paramsBuffer == null) {
            // 16 bytes (viewportWidth, viewportHeight, paddedCount, _pad).
            // Written once via mappedAtCreation; stable until viewport or paddedCount changes.
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
                    GPUBindGroupEntry(binding = 3, buffer = vertexCursorBuf!!),
                    GPUBindGroupEntry(binding = 4, buffer = paramsBuffer!!),
                )
            )
        )

        indirectBindGroup = ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = checkNotNull(indirectBindGroupLayout) {
                    "Indirect bind group layout is null — call ensurePipelines first"
                },
                entries = arrayOf(
                    // vertexCursor read as array<u32> after M5a atomics complete
                    GPUBindGroupEntry(binding = 0, buffer = vertexCursorBuf!!),
                    GPUBindGroupEntry(binding = 1, buffer = indirectArgsBuf!!),
                )
            )
        )

        lastFaceCapacity    = faceCapacity
        lastPaddedCount     = paddedCount
        lastViewportWidth   = viewportWidth
        lastViewportHeight  = viewportHeight
        lastTransformedBuffer = transformedBuffer
        lastSortedKeysBuffer  = sortedKeysBuffer

        Log.d(
            TAG,
            "Buffers ready: faceCapacity=$faceCapacity paddedCount=$paddedCount " +
                "viewport=${viewportWidth}×${viewportHeight} vertexBufSize=${vertexBufSize}B"
        )
    }

    // ── Per-frame operations ──────────────────────────────────────────────────

    /**
     * Zero the vertex cursor via `queue.writeBuffer`.
     *
     * Must be called before [dispatch] on every frame to reset the atomic bump
     * allocator. `queue.writeBuffer` is ordered before all encoded commands in the
     * same `queue.submit`, so calling this before recording the encoder is safe.
     *
     * Must be called from the GPU thread (inside `ctx.withGpu { ... }`).
     */
    fun resetVertexCursor() {
        zeroBuffer.rewind()
        ctx.queue.writeBuffer(vertexCursorBuf!!, 0L, zeroBuffer)
    }

    /**
     * Encode both M5 sub-passes into [encoder].
     *
     * **Sub-pass 1 — triangulateEmit**: `ceil(paddedCount / WORKGROUP_SIZE)` workgroups.
     * Reads the sorted key array, emits vertices into [vertexBuffer].
     *
     * **Sub-pass 2 — writeIndirectArgs**: 1 workgroup of 1 thread. Reads the final
     * vertex cursor and writes [indirectArgsBuffer] for the render pass.
     *
     * The two passes share the same [encoder], so the GPU serialises them. No
     * explicit barrier is needed — `pass.end()` is a sufficient synchronisation point.
     *
     * Preconditions:
     * - [ensurePipelines] has returned.
     * - [ensureBuffers] has been called with the current inputs.
     * - [resetVertexCursor] has been called on this frame.
     * - M4 sort has been encoded before this call (sort result is in [sortedKeysBuffer]).
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     *
     * @param encoder The command encoder to record into.
     */
    fun dispatch(encoder: GPUCommandEncoder) {
        check(emitPipeline != null && indirectPipeline != null) {
            "Pipelines not ready — call ensurePipelines first"
        }
        check(emitBindGroup != null && indirectBindGroup != null) {
            "Bind groups not ready — call ensureBuffers first"
        }

        // M5a: triangulate and emit — one thread per sorted entry.
        val workgroupCount =
            ceil(lastPaddedCount.toDouble() / TriangulateEmitShader.WORKGROUP_SIZE).toInt()

        val emitPass = encoder.beginComputePass()
        emitPass.setPipeline(emitPipeline!!)
        emitPass.setBindGroup(0, emitBindGroup!!)
        emitPass.dispatchWorkgroups(workgroupCount)
        emitPass.end()
        emitPass.close()   // release JNI wrapper immediately (GPU_OBJECT_LIFETIME.md)

        // M5b: write indirect args — single workgroup, runs after emitPass is complete.
        val indirectPass = encoder.beginComputePass()
        indirectPass.setPipeline(indirectPipeline!!)
        indirectPass.setBindGroup(0, indirectBindGroup!!)
        indirectPass.dispatchWorkgroups(1)
        indirectPass.end()
        indirectPass.close()   // release JNI wrapper immediately
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun close() {
        // Per-input bind groups and params buffer
        emitBindGroup?.close()
        emitBindGroup = null
        indirectBindGroup?.close()
        indirectBindGroup = null
        paramsBuffer?.destroy()
        paramsBuffer?.close()
        paramsBuffer = null

        // Per-capacity buffer
        vertexBuf?.destroy()
        vertexBuf?.close()
        vertexBuf = null

        // Persistent buffers (fixed-size, allocated once)
        vertexCursorBuf?.destroy()
        vertexCursorBuf?.close()
        vertexCursorBuf = null
        indirectArgsBuf?.destroy()
        indirectArgsBuf?.close()
        indirectArgsBuf = null

        // Emit pipeline objects
        emitPipeline?.close()
        emitPipeline = null
        emitPipelineLayout?.close()
        emitPipelineLayout = null
        emitBindGroupLayout?.close()
        emitBindGroupLayout = null
        emitShaderModule?.close()
        emitShaderModule = null

        // Indirect pipeline objects
        indirectPipeline?.close()
        indirectPipeline = null
        indirectPipelineLayout?.close()
        indirectPipelineLayout = null
        indirectBindGroupLayout?.close()
        indirectBindGroupLayout = null
        indirectShaderModule?.close()
        indirectShaderModule = null

        lastFaceCapacity      = 0
        lastPaddedCount       = 0
        lastViewportWidth     = 0
        lastViewportHeight    = 0
        lastTransformedBuffer = null
        lastSortedKeysBuffer  = null
    }
}
