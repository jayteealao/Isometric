package io.github.jayteealao.isometric.webgpu.pipeline

import android.util.Log
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUCommandEncoder
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.webgpu.GpuContext
import io.github.jayteealao.isometric.webgpu.sort.BitonicSortNetwork
import io.github.jayteealao.isometric.webgpu.sort.GpuBitonicSort

/**
 * Orchestrates the full GPU compute pipeline for the M3→M4→M5 pass sequence.
 *
 * Owns and manages the lifecycle of all sub-pipeline components:
 * - [GpuSceneDataBuffer]       — M1/M2: scene geometry upload
 * - [GpuSceneUniforms]         — M1/M2: projection + lighting uniform upload
 * - [GpuTransformPipeline]     — M3:    transform + cull + light
 * - [GpuBitonicSort]           — M4b:   GPU-only bitonic depth sort
 * - [GpuSortKeyPacker]         — M4a:   pack sort keys from transformed faces
 * - [GpuTriangulateEmitPipeline] — M5:  triangulate + emit + write indirect args
 *
 * ## Per-frame call sequence
 *
 * ```kotlin
 * // When scene or viewport changes (upload path):
 * fullPipeline.upload(scene, viewportWidth, viewportHeight)
 *
 * // Every frame (inside ctx.withGpu { ... }):
 * val encoder = device.createCommandEncoder()
 * fullPipeline.dispatch(encoder)              // encodes M3 → M4 → M5 compute passes
 * // ... begin render pass ...
 * pass.setVertexBuffer(0, fullPipeline.vertexBuffer)
 * pass.drawIndirect(fullPipeline.indirectArgsBuffer, 0L)
 * // ... end render pass ...
 * queue.submit(arrayOf(encoder.finish()))
 * ```
 *
 * ## Initialization
 *
 * ```kotlin
 * val pipeline = GpuFullPipeline(ctx)
 * ctx.withGpu {
 *     pipeline.ensurePipelines()   // compile all 4 compute shaders (async, one-time)
 * }
 * ```
 *
 * ## Lifetime
 *
 * Create once per renderer session. Call [close] on teardown to release all GPU resources.
 * All Dawn API calls must run on the GPU thread ([GpuContext.withGpu]).
 */
internal class GpuFullPipeline(
    private val ctx: GpuContext,
) : AutoCloseable {

    companion object {
        private const val TAG = "GpuFullPipeline"
    }

    // ── Sub-pipeline components ───────────────────────────────────────────────

    private val sceneData  = GpuSceneDataBuffer(ctx.device, ctx.queue)
    private val uniforms   = GpuSceneUniforms(ctx.device, ctx.queue)
    private val transform  = GpuTransformPipeline(ctx)
    private val sort       = GpuBitonicSort(ctx)
    private val packer     = GpuSortKeyPacker(ctx)
    private val emit       = GpuTriangulateEmitPipeline(ctx)

    // ── Per-frame dispatch state (cached from last upload) ────────────────────

    /**
     * Face count from the last [upload] call.
     * Zero before the first [upload] or if the last scene was empty.
     */
    private var lastFaceCount: Int = 0

    /**
     * True after a successful [upload] with a non-empty scene.
     * Use this to gate [dispatch] and `drawIndirect` calls rather than
     * maintaining a parallel face-count mirror in the caller.
     */
    val hasScene: Boolean get() = lastFaceCount > 0

    /**
     * Power-of-two padded sort array size from the last [upload] call.
     * Always `BitonicSortNetwork.nextPowerOfTwo(lastFaceCount)`.
     */
    private var lastPaddedCount: Int = 0

    // ── Exposed buffers ───────────────────────────────────────────────────────

    /**
     * The GPU vertex buffer written by [dispatch].
     *
     * Bind as vertex buffer slot 0 in the render pass after [dispatch] is submitted.
     * Usage: `Storage | Vertex | CopySrc`.
     *
     * Valid after [ensurePipelines] and [upload] have been called at least once.
     */
    val vertexBuffer: GPUBuffer get() = emit.vertexBuffer

    /**
     * The GPU indirect draw args buffer written by [dispatch].
     *
     * Pass to `renderPass.drawIndirect(indirectArgsBuffer, 0L)`.
     * Contains `{ vertexCount, instanceCount=1, firstVertex=0, firstInstance=0 }`.
     * Usage: `Storage | Indirect`.
     *
     * Valid after [dispatch] has been called at least once.
     */
    val indirectArgsBuffer: GPUBuffer get() = emit.indirectArgsBuffer

    // ── Pipeline init ─────────────────────────────────────────────────────────

    /**
     * Compile all compute pipelines if not already done.
     *
     * Calls [ensurePipeline]/[ensurePipelines] on all four compute stages.
     * Safe to call multiple times — each sub-pipeline is a no-op on subsequent calls.
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     */
    suspend fun ensurePipelines() {
        transform.ensurePipeline()
        sort.ensurePipeline()
        packer.ensurePipeline()
        emit.ensurePipelines()
        Log.d(TAG, "All compute pipelines ready")
    }

    // ── Per-scene upload ──────────────────────────────────────────────────────

    /**
     * Upload [scene] geometry and uniforms to GPU buffers, and ensure all per-capacity
     * bind groups are up to date for the given viewport.
     *
     * Call this whenever [scene] changes or [viewportWidth]/[viewportHeight] change.
     * No-op paths inside each sub-component avoid redundant GPU allocations.
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`), before [dispatch].
     *
     * @param scene         The prepared scene to upload. Uses [PreparedScene.commands]
     *                      for 3D geometry and [PreparedScene.projectionParams] +
     *                      [PreparedScene.lightDirection] for M3 uniforms.
     * @param viewportWidth  Current render surface width in pixels.
     * @param viewportHeight Current render surface height in pixels.
     */
    fun upload(scene: PreparedScene, viewportWidth: Int, viewportHeight: Int) {
        // Upload 3D geometry (uses RenderCommand.originalPath.points).
        sceneData.upload(scene.commands)
        val faceCount = sceneData.faceCount
        lastFaceCount = faceCount
        if (faceCount == 0) return

        // Upload projection + lighting uniforms. queue.writeBuffer is ordered before
        // the next queue.submit so M3 sees the updated values in the same frame.
        uniforms.update(
            params        = scene.projectionParams,
            lightDirection = scene.lightDirection,
            viewportWidth  = viewportWidth,
            viewportHeight = viewportHeight,
            faceCount      = faceCount,
        )

        // paddedCount must be a power of two for the bitonic sort network to be correct.
        // nextPowerOfTwo(faceCount) ≤ capacity (since capacity is always a power-of-two
        // multiple of INITIAL_CAPACITY_FACES and capacity ≥ faceCount by construction).
        val paddedCount = BitonicSortNetwork.nextPowerOfTwo(faceCount)
        lastPaddedCount = paddedCount
        val capacity    = sceneData.capacity

        // Ensure all GPU buffers are correctly sized for this frame.
        // Order: sort before packer (packer binds sort.primaryBuffer as output),
        //        transform before packer+emit (both bind transform.transformedBuffer).
        sort.ensureBuffers(paddedCount)
        transform.ensureBuffers(
            capacity       = capacity,
            sceneBuffer    = sceneData.buffer!!,
            uniformsBuffer = uniforms.buffer,
        )
        packer.ensureBuffers(
            paddedCount        = paddedCount,
            transformedBuffer  = transform.transformedBuffer,
            visibleCountBuffer = transform.visibleCountBuffer,
            sortKeyOutputBuffer = sort.primaryBuffer,
        )
        emit.ensureBuffers(
            faceCapacity      = capacity,
            paddedCount       = paddedCount,
            viewportWidth     = viewportWidth,
            viewportHeight    = viewportHeight,
            transformedBuffer = transform.transformedBuffer,
            sortedKeysBuffer  = sort.resultBuffer,
        )

    }

    // ── Per-frame dispatch ────────────────────────────────────────────────────

    /**
     * Reset atomic counters and encode all M3→M4→M5 compute passes into [encoder].
     *
     * **Must be called after [upload].**
     * Encodes (in order):
     * 1. M3 — Transform + Cull + Light (`GpuTransformPipeline.dispatch`)
     * 2. M4a — Pack sort keys (`GpuSortKeyPacker.dispatch`)
     * 3. M4b — Bitonic sort (`GpuBitonicSort.dispatch`)
     * 4. M5a — Triangulate + Emit (`GpuTriangulateEmitPipeline.dispatch`, sub-pass 1)
     * 5. M5b — Write indirect args (`GpuTriangulateEmitPipeline.dispatch`, sub-pass 2)
     *
     * After the encoder is submitted, [vertexBuffer] and [indirectArgsBuffer] are ready
     * for use in the render pass.
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     *
     * @param encoder The command encoder to record compute passes into.
     */
    fun dispatch(encoder: GPUCommandEncoder) {
        val faceCount = lastFaceCount
        require(faceCount > 0) { "No faces to dispatch — call upload with a non-empty scene first" }

        // Reset atomic counters before encoding. queue.writeBuffer is ordered before
        // the subsequent queue.submit, ensuring M3 and M5 start with counters at zero.
        transform.resetVisibleCount()
        emit.resetVertexCursor()

        // M3: Transform + Cull + Light — one thread per face.
        transform.dispatch(encoder, faceCount)

        // M4a: Pack sort keys — one thread per padded entry.
        packer.dispatch(encoder, lastPaddedCount)

        // M4b: Bitonic sort — all stages in one encoder.
        sort.dispatch(encoder)

        // M5a + M5b: Triangulate+Emit + WriteIndirectArgs — two sub-passes.
        emit.dispatch(encoder)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun close() {
        // Close in reverse-dependency order: consumers before producers.
        emit.close()
        packer.close()
        sort.close()
        transform.close()
        uniforms.close()
        sceneData.close()

        lastFaceCount   = 0
        lastPaddedCount = 0
    }
}
