package io.github.jayteealao.isometric.webgpu.pipeline

import android.util.Log
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPUTextureView
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.TextureSource
import io.github.jayteealao.isometric.webgpu.GpuContext
import io.github.jayteealao.isometric.webgpu.shader.TriangulateEmitShader
import io.github.jayteealao.isometric.webgpu.sort.BitonicSortNetwork
import io.github.jayteealao.isometric.webgpu.sort.GpuBitonicSort
import io.github.jayteealao.isometric.webgpu.texture.GpuTextureBinder
import io.github.jayteealao.isometric.webgpu.texture.GpuTextureStore
import io.github.jayteealao.isometric.webgpu.texture.TextureAtlasManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 * - [GpuTextureStore]          — GPU texture upload (BGRA8Unorm)
 * - [GpuTextureBinder]         — sampler + @group(0) bind group
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
 * pass.setBindGroup(0, fullPipeline.textureBindGroup)
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

        /**
         * Diagnostic: when true, skip M3 (transform) in [dispatchIndividualSubmits].
         * Use with [DEBUG_SKIP_M5] to bisect which atomicAdd stage causes TDR.
         * - Both true → only packer+sort run (confirmed stable)
         * - Only M3 true → run packer+sort+M5 (tests if M5 atomicAdd is the cause)
         * - Only M5 true → run M3+packer+sort (tests if M3 atomicAdd is the cause)
         */
        private const val DEBUG_SKIP_M3 = false

        /**
         * Diagnostic: when true, skip M5 (triangulate-emit + indirect-args) in
         * [dispatchIndividualSubmits]. See [DEBUG_SKIP_M3].
         */
        private const val DEBUG_SKIP_M5 = false
    }

    // ── Sub-pipeline components ───────────────────────────────────────────────

    private val sceneData  = GpuSceneDataBuffer(ctx.device, ctx.queue)
    private val uniforms   = GpuSceneUniforms(ctx.device, ctx.queue)
    private val transform  = GpuTransformPipeline(ctx)
    private val sort       = GpuBitonicSort(ctx)
    private val packer     = GpuSortKeyPacker(ctx)
    private val emit       = GpuTriangulateEmitPipeline(ctx)

    // ── Texture components ───────────────────────────────────────────────────

    val textureStore  = GpuTextureStore(ctx)
    private lateinit var textureBinder: GpuTextureBinder
    private lateinit var atlasManager: TextureAtlasManager


    // ── Texture state ────────────────────────────────────────────────────────

    /** Signature of the last atlas build, to detect changes. */
    private var lastAtlasSignature: Set<TextureSource>? = null

    /** Current texture bind group for the render pass. */
    private var _textureBindGroup: GPUBindGroup? = null

    /** The texture bind group to set on the render pass at `@group(0)`. */
    val textureBindGroup: GPUBindGroup
        get() = _textureBindGroup ?: run {
            // Lazily build fallback bind group on first access
            rebuildBindGroup(textureStore.fallbackTextureView)
        }

    /** Close the current bind group (if any) and create a new one for [view]. */
    private fun rebuildBindGroup(view: GPUTextureView): GPUBindGroup {
        _textureBindGroup?.close()
        val bg = textureBinder.buildBindGroup(view)
        _textureBindGroup = bg
        return bg
    }

    // ── Tex-index buffer ─────────────────────────────────────────────────────

    private var texIndexGpuBuffer: GPUBuffer? = null
    private var texIndexCpuBuffer: ByteBuffer? = null
    private var texIndexCapacity: Int = 0

    // ── UV region buffer ─────────────────────────────────────────────────────

    private var uvRegionGpuBuffer: GPUBuffer? = null
    private var uvRegionCpuBuffer: ByteBuffer? = null
    private var uvRegionCapacity: Int = 0

    // ── Reusable indirect-args staging buffer ─────────────────────────────────

    /**
     * Pre-allocated 16-byte CPU buffer for writing [GpuTriangulateEmitPipeline.indirectArgsBuffer].
     * Layout: `{ vertexCount, instanceCount=1, firstVertex=0, firstInstance=0 }`.
     */
    private val indirectArgsStagingBuf =
        java.nio.ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())

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
     * Compile all compute pipelines and wire the texture bind group layout.
     *
     * Calls [GpuRenderPipeline.ensurePipeline] to guarantee the render pipeline
     * and its auto-derived bind group layout are available, then transfers layout
     * ownership to [GpuTextureBinder] (created here on first call).
     *
     * Safe to call multiple times — each sub-pipeline is a no-op on subsequent calls.
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     */
    suspend fun ensurePipelines(renderPipeline: GpuRenderPipeline) {
        ctx.assertGpuThread()
        renderPipeline.ensurePipeline()
        if (!::textureBinder.isInitialized) {
            val layout = renderPipeline.takeTextureBindGroupLayout()
            textureBinder = GpuTextureBinder(ctx, layout)
        }
        if (!::atlasManager.isInitialized) {
            atlasManager = TextureAtlasManager(ctx, textureStore)
        }
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
        ctx.assertGpuThread()
        // Upload 3D geometry (uses RenderCommand.originalPath.points).
        sceneData.upload(scene.commands)
        val faceCount = sceneData.faceCount
        if (faceCount == 0) {
            clearScene()
            return
        }
        lastFaceCount = faceCount

        // Upload projection + lighting uniforms. queue.writeBuffer is ordered before
        // the next queue.submit so M3 sees the updated values in the same frame.
        uniforms.update(
            params        = scene.projectionParams,
            lightDirection = scene.lightDirection,
            viewportWidth  = viewportWidth,
            viewportHeight = viewportHeight,
            faceCount      = faceCount,
        )

        // ── Texture upload ───────────────────────────────────────────────────
        uploadTextures(scene)

        // ── Compact tex-index buffer ─────────────────────────────────────────
        uploadTexIndexBuffer(scene, faceCount)

        // ── Compact UV region buffer ─────────────────────────────────────────
        uploadUvRegionBuffer(scene, faceCount)

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
            paddedCount         = paddedCount,
            faceCount           = faceCount,
            transformedBuffer   = transform.transformedBuffer,
            sortKeyOutputBuffer = sort.primaryBuffer,
        )
        emit.ensureBuffers(
            paddedCount       = paddedCount,
            viewportWidth     = viewportWidth,
            viewportHeight    = viewportHeight,
            transformedBuffer = transform.transformedBuffer,
            sortedKeysBuffer  = sort.resultBuffer,
            texIndexBuffer    = texIndexGpuBuffer!!,
            uvRegionBuffer    = uvRegionGpuBuffer!!,
        )

        // Write indirectArgs: { vertexCount, instanceCount=1, firstVertex=0, firstInstance=0 }.
        // With fixed-stride M5, the vertex count is always paddedCount × MAX_VERTICES_PER_FACE.
        // queue.writeBuffer is ordered before the next queue.submit so the render pass sees
        // the correct value even though the compute submit comes after this call.
        val totalVertexCount = paddedCount * TriangulateEmitShader.MAX_VERTICES_PER_FACE
        indirectArgsStagingBuf.rewind()
        indirectArgsStagingBuf.putInt(totalVertexCount)
        indirectArgsStagingBuf.putInt(1)   // instanceCount
        indirectArgsStagingBuf.putInt(0)   // firstVertex
        indirectArgsStagingBuf.putInt(0)   // firstInstance
        indirectArgsStagingBuf.rewind()
        ctx.queue.writeBuffer(emit.indirectArgsBuffer, 0L, indirectArgsStagingBuf)
    }

    /**
     * Collect all distinct textured materials from the scene, pack them into an atlas,
     * and rebuild the texture bind group. Caches by texture source set to avoid
     * rebuilding when the same textures are used across frames.
     */
    private fun uploadTextures(scene: PreparedScene) {
        // Collect all distinct TextureSource references from the scene
        val textureSources = mutableSetOf<TextureSource>()
        for (cmd in scene.commands) {
            collectTextureSources(cmd.material, cmd.faceType, textureSources)
        }

        if (textureSources.isEmpty()) {
            // No textures in scene — use fallback
            if (lastAtlasSignature != null) {
                atlasManager.destroy()
                lastAtlasSignature = null
            }
            rebuildBindGroup(textureStore.fallbackTextureView)
            return
        }

        // Cache check: avoid re-building atlas if same set of textures
        if (textureSources == lastAtlasSignature) return

        // Resolve all TextureSources to Bitmaps
        val entries = mutableMapOf<TextureSource, android.graphics.Bitmap>()
        for (source in textureSources) {
            val bitmap = when (source) {
                is TextureSource.BitmapSource -> {
                    source.ensureNotRecycled()
                    source.bitmap
                }
                else -> {
                    Log.w(TAG, "TextureSource ${source::class.simpleName} not yet supported in WebGPU — skipping")
                    null
                }
            }
            if (bitmap != null) entries[source] = bitmap
        }

        if (entries.isEmpty()) {
            rebuildBindGroup(textureStore.fallbackTextureView)
            lastAtlasSignature = null
            return
        }

        // Rebuild atlas
        val built = atlasManager.rebuild(entries)
        if (built) {
            val atlasView = atlasManager.textureView!!
            rebuildBindGroup(atlasView)
        } else {
            rebuildBindGroup(textureStore.fallbackTextureView)
        }
        lastAtlasSignature = textureSources
    }

    /**
     * Recursively collect [TextureSource] references from a material, expanding
     * [IsometricMaterial.PerFace] into its constituent sub-materials.
     */
    private fun collectTextureSources(
        material: io.github.jayteealao.isometric.MaterialData?,
        faceType: io.github.jayteealao.isometric.shapes.PrismFace?,
        out: MutableSet<TextureSource>,
    ) {
        when (val m = material) {
            is IsometricMaterial.Textured -> out.add(m.source)
            is IsometricMaterial.PerFace -> {
                // Collect from all face entries and default
                for (sub in m.faceMap.values) {
                    if (sub is IsometricMaterial.Textured) out.add(sub.source)
                }
                if (m.default is IsometricMaterial.Textured) {
                    out.add((m.default as IsometricMaterial.Textured).source)
                }
            }
            else -> {}
        }
    }

    /**
     * Pack and upload the compact per-face texture index buffer for the emit shader.
     */
    private fun uploadTexIndexBuffer(scene: PreparedScene, faceCount: Int) {
        if (faceCount == 0) return

        // Ensure GPU buffer capacity (x2 growth to avoid per-frame reallocation)
        if (faceCount > texIndexCapacity) {
            texIndexGpuBuffer?.close()
            val newCapacity = maxOf(faceCount, faceCount * 2)
            texIndexGpuBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Storage or BufferUsage.CopyDst,
                    size = (newCapacity * 4).toLong(),
                )
            ).also { it.setLabel("IsometricTexIndices") }
            texIndexCapacity = newCapacity
        }

        // Ensure CPU staging buffer capacity (x2 growth to avoid per-frame allocateDirect)
        val requiredBytes = faceCount * 4
        val cpu = texIndexCpuBuffer
        if (cpu == null || cpu.capacity() < requiredBytes) {
            val newSize = maxOf(requiredBytes, requiredBytes * 2)
            texIndexCpuBuffer = ByteBuffer.allocateDirect(newSize).order(ByteOrder.nativeOrder())
        }

        SceneDataPacker.packTexIndicesInto(scene.commands, texIndexCpuBuffer!!)
        ctx.queue.writeBuffer(texIndexGpuBuffer!!, 0L, texIndexCpuBuffer!!)
    }

    /**
     * Pack and upload the compact per-face UV region buffer for the emit shader.
     * Each face gets 4 floats: [uvOffsetU, uvOffsetV, uvScaleU, uvScaleV] = 16 bytes.
     */
    private fun uploadUvRegionBuffer(scene: PreparedScene, faceCount: Int) {
        if (faceCount == 0) return

        // Ensure GPU buffer capacity (x2 growth)
        val entryBytes = 16 // 4 floats × 4 bytes
        if (faceCount > uvRegionCapacity) {
            uvRegionGpuBuffer?.close()
            val newCapacity = maxOf(faceCount, faceCount * 2)
            uvRegionGpuBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Storage or BufferUsage.CopyDst,
                    size = (newCapacity * entryBytes).toLong(),
                )
            ).also { it.setLabel("IsometricUvRegions") }
            uvRegionCapacity = newCapacity
        }

        // Ensure CPU staging buffer capacity (x2 growth)
        val requiredBytes = faceCount * entryBytes
        val cpu = uvRegionCpuBuffer
        if (cpu == null || cpu.capacity() < requiredBytes) {
            val newSize = maxOf(requiredBytes, requiredBytes * 2)
            uvRegionCpuBuffer = ByteBuffer.allocateDirect(newSize).order(ByteOrder.nativeOrder())
        }

        // Pack UV regions: for each command, resolve atlas region and write 4 floats
        val buf = uvRegionCpuBuffer!!
        buf.rewind()
        buf.limit(requiredBytes)
        for (cmd in scene.commands) {
            val region = resolveAtlasRegion(cmd)
            if (region != null) {
                buf.putFloat(region.uvOffset[0])
                buf.putFloat(region.uvOffset[1])
                buf.putFloat(region.uvScale[0])
                buf.putFloat(region.uvScale[1])
            } else {
                // Identity transform: offset (0,0), scale (1,1)
                buf.putFloat(0f)
                buf.putFloat(0f)
                buf.putFloat(1f)
                buf.putFloat(1f)
            }
        }
        buf.rewind()

        ctx.queue.writeBuffer(uvRegionGpuBuffer!!, 0L, buf)
    }

    /**
     * Resolve the atlas region for a command's effective texture. Returns null for
     * non-textured faces. Expands [IsometricMaterial.PerFace] using the command's
     * [RenderCommand.faceType].
     */
    private fun resolveAtlasRegion(cmd: io.github.jayteealao.isometric.RenderCommand): TextureAtlasManager.AtlasRegion? {
        val effective = when (val m = cmd.material) {
            is IsometricMaterial.PerFace -> {
                val face = cmd.faceType
                if (face != null) m.resolve(face) else m.default
            }
            else -> m
        }
        return when (effective) {
            is IsometricMaterial.Textured -> atlasManager.getRegion(effective.source)
            else -> null
        }
    }

    /**
     * Reset draw-visible state so subsequent render passes cannot draw stale geometry.
     *
     * This is used when the scene becomes empty/null. We do not need to clear every backing
     * GPU buffer; clearing indirect args and resetting the cached counts is sufficient to make
     * both the compute dispatch and the render draw path inert.
     */
    fun clearScene() {
        lastFaceCount = 0
        lastPaddedCount = 0
        indirectArgsStagingBuf.rewind()
        indirectArgsStagingBuf.putInt(0)
        indirectArgsStagingBuf.putInt(1)
        indirectArgsStagingBuf.putInt(0)
        indirectArgsStagingBuf.putInt(0)
        indirectArgsStagingBuf.rewind()
        ctx.queue.writeBuffer(emit.indirectArgsBuffer, 0L, indirectArgsStagingBuf)

        // Revert to fallback texture bind group
        rebuildBindGroup(textureStore.fallbackTextureView)
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
     * 4. M5 — Triangulate + Emit (`GpuTriangulateEmitPipeline.dispatch`; fixed-stride, no atomics)
     *
     * After the encoder is submitted, [vertexBuffer] and [indirectArgsBuffer] are ready
     * for use in the render pass.
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     *
     * @param encoder The command encoder to record compute passes into.
     */
    fun dispatch(encoder: GPUCommandEncoder, profiler: GpuTimestampProfiler? = null) {
        ctx.assertGpuThread()
        val faceCount = lastFaceCount
        require(faceCount > 0) { "No faces to dispatch — call upload with a non-empty scene first" }

        // M3: Transform + Cull + Light — one thread per face.
        transform.dispatch(encoder, faceCount, profiler?.timestampWritesFor(0))

        // M4a: Pack sort keys — one thread per padded entry.
        packer.dispatch(encoder, lastPaddedCount, profiler?.timestampWritesFor(1))

        // M4b: Bitonic sort — all stages in one encoder.
        sort.dispatch(encoder, profiler?.timestampWritesFor(2))

        // M5: Triangulate+Emit — fixed-stride, no atomicAdd, no separate indirect-args pass.
        emit.dispatch(encoder, profiler?.timestampWritesFor(3))
    }

    /**
     * Diagnostic: submit each compute stage group as a separate [GPUQueue.submit] call.
     *
     * Submits in order:
     * 1. M3 — Transform + Cull + Light (one submit)
     * 2. M4a — Pack sort keys (one submit)
     * 3. M4b — Bitonic sort stages, **one submit per stage** via [GpuBitonicSort.dispatchIndividually]
     * 4. M5 — Triangulate+Emit (one submit; fixed-stride, no atomics)
     *
     * Dawn serialises submits on the same queue with Vulkan timeline semaphores, so each
     * stage is guaranteed to see the writes from all prior stages. This bypasses the Adreno
     * driver bug where compute→compute barriers inside a single VkCommandBuffer are broken.
     *
     * If this resolves the `VK_ERROR_DEVICE_LOST` TDR that [dispatch] triggers, the root
     * cause is the Adreno compute-barrier bug (compute→compute within one command buffer).
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     */
    fun dispatchIndividualSubmits() {
        ctx.assertGpuThread()
        val faceCount = lastFaceCount
        require(faceCount > 0) { "No faces to dispatch — call upload with a non-empty scene first" }


        var submitIndex = 0
        fun submitStage(label: String, block: (GPUCommandEncoder) -> Unit) {
            val idx = ++submitIndex
            Log.d(TAG, "dispatchIndividualSubmits: submit #$idx ($label) — before")
            val enc = ctx.device.createCommandEncoder()
            block(enc)
            val buf = enc.finish()
            ctx.queue.submit(arrayOf(buf))
            buf.close()
            enc.close()
            Log.d(TAG, "dispatchIndividualSubmits: submit #$idx ($label) — after")
        }

        if (!DEBUG_SKIP_M3) submitStage("M3-transform") { transform.dispatch(it, faceCount) }
        submitStage("M4a-packer")   { packer.dispatch(it, lastPaddedCount) }
        Log.d(TAG, "dispatchIndividualSubmits: entering M4b sort (${sort.stageCount} stages)")
        sort.dispatchIndividually()
        Log.d(TAG, "dispatchIndividualSubmits: M4b sort done")
        if (!DEBUG_SKIP_M5) submitStage("M5-emit")      { emit.dispatch(it) }
        Log.d(TAG, "dispatchIndividualSubmits: all ${submitIndex + sort.stageCount} submits done")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun close() {
        // Close texture resources
        _textureBindGroup?.close()
        _textureBindGroup = null
        if (::atlasManager.isInitialized) atlasManager.destroy()
        lastAtlasSignature = null
        if (::textureBinder.isInitialized) textureBinder.close()
        textureStore.close()

        // Close tex-index buffer
        texIndexGpuBuffer?.close()
        texIndexGpuBuffer = null
        texIndexCapacity = 0

        // Close UV region buffer
        uvRegionGpuBuffer?.close()
        uvRegionGpuBuffer = null
        uvRegionCapacity = 0

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
