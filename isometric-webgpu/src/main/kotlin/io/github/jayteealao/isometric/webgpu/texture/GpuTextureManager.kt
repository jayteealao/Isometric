package io.github.jayteealao.isometric.webgpu.texture

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBuffer
import io.github.jayteealao.isometric.MaterialData
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.resolveForFace
import io.github.jayteealao.isometric.shader.TextureSource
import io.github.jayteealao.isometric.shader.TextureTransform
import io.github.jayteealao.isometric.webgpu.GpuContext
import io.github.jayteealao.isometric.webgpu.pipeline.GpuRenderPipeline
import io.github.jayteealao.isometric.webgpu.pipeline.GrowableGpuStagingBuffer
import io.github.jayteealao.isometric.webgpu.pipeline.SceneDataLayout
import io.github.jayteealao.isometric.webgpu.pipeline.SceneDataPacker

/**
 * Resolves [source] to its backing [android.graphics.Bitmap] if supported by the
 * WebGPU upload path, or `null` for any type not yet supported.
 *
 * A `null` return means the source is skipped and the `onTextureLoadError` callback
 * is fired by the caller. Extracted as a package-level function so the source-classification
 * decision can be unit-tested on the JVM without a [GpuContext] or Android Looper.
 */
internal fun resolveSourceToBitmap(source: TextureSource): android.graphics.Bitmap? = when (source) {
    is TextureSource.Bitmap -> {
        source.ensureNotRecycled()
        source.bitmap
    }
    else -> null
}

/**
 * Encapsulates all texture-related concerns for the GPU pipeline:
 * - Atlas management ([TextureAtlasManager])
 * - Texture bind group lifecycle ([GpuTextureBinder])
 * - Per-face tex-index buffer ([texIndexGpuBuffer])
 * - Per-face UV region buffer ([uvRegionGpuBuffer])
 *
 * ## Lifecycle
 *
 * 1. Construct once per renderer session.
 * 2. Call [ensurePipelines] after the render pipeline is compiled (one-time, GPU thread).
 * 3. Call [uploadTextures] each frame/scene upload (GPU thread).
 * 4. Call [close] on teardown.
 *
 * @param onTextureLoadError Optional callback invoked when a texture source cannot be
 *   loaded or packed into the atlas. Always dispatched to the **main thread** via
 *   `Handler(Looper.getMainLooper()).post {}` — safe to update UI directly.
 *   **Bulk-fire semantics:** on atlas overflow, the callback fires once for *each* source
 *   in the failing batch, not just the source that caused the capacity constraint.
 */
internal class GpuTextureManager(
    private val ctx: GpuContext,
    private val onTextureLoadError: ((TextureSource) -> Unit)? = null,
    /** Maximum atlas dimension in pixels. Defaults to 2048. Exposed for testing. */
    internal val maxAtlasSizePx: Int = 2048,
) : AutoCloseable {

    companion object {
        private const val TAG = "GpuTextureManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Core texture components ───────────────────────────────────────────────

    val textureStore = GpuTextureStore(ctx)
    private lateinit var textureBinder: GpuTextureBinder
    private lateinit var atlasManager: TextureAtlasManager

    // ── Texture bind-group state ──────────────────────────────────────────────

    /** Signature of the last atlas build, to detect changes. */
    private var lastAtlasSignature: Set<TextureSource>? = null

    /**
     * Dirty flag that gates the per-face tex-index and UV region buffer uploads.
     * Initialised to `true` so the first frame always uploads.
     * Cleared to `false` after a successful upload; set back to `true` on reset/close.
     *
     * Replaces the former hashCode-based skip guard, which was susceptible to
     * hash collisions causing stale UV transform data to be silently retained on the GPU.
     *
     * TODO: set uvRegionsDirty = true from a scene-mutation callback when the scene
     *       mutation API is available, so unchanged frames can be skipped safely.
     */
    private var uvRegionsDirty: Boolean = true

    /** Current texture bind group for the render pass. */
    private var _textureBindGroup: GPUBindGroup? = null

    /**
     * The texture bind group to set on the render pass at `@group(0)`.
     * Lazily builds a fallback bind group on first access if none exists.
     */
    val textureBindGroup: GPUBindGroup
        get() = _textureBindGroup ?: rebuildBindGroup(textureStore.fallbackTextureView)

    // ── Per-face index / UV buffers ───────────────────────────────────────────

    private val texIndexBuf = GrowableGpuStagingBuffer(ctx, label = "IsometricTexIndices")
    private val uvRegionBuf = GrowableGpuStagingBuffer(ctx, label = "IsometricUvRegions")

    /** The backing [GPUBuffer] for per-face texture indices. Null until first [uploadTextures]. */
    val texIndexGpuBuffer: GPUBuffer? get() = texIndexBuf.gpuBuffer

    /** The backing [GPUBuffer] for per-face UV regions. Null until first [uploadTextures]. */
    val uvRegionGpuBuffer: GPUBuffer? get() = uvRegionBuf.gpuBuffer

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Initialise [GpuTextureBinder] and [TextureAtlasManager] from the compiled
     * [renderPipeline]. Safe to call multiple times — subsequent calls are no-ops.
     *
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     */
    fun ensurePipelines(renderPipeline: GpuRenderPipeline) {
        if (!::textureBinder.isInitialized) {
            val layout = renderPipeline.takeTextureBindGroupLayout()
            textureBinder = GpuTextureBinder(ctx, layout)
        }
        if (!::atlasManager.isInitialized) {
            atlasManager = TextureAtlasManager(ctx, textureStore, maxAtlasSizePx)
        }
    }

    // ── Per-scene upload ──────────────────────────────────────────────────────

    /**
     * Scan [scene] for textures, rebuild the atlas if needed, pack and upload
     * the per-face tex-index and UV region buffers.
     *
     * Combines the three separate upload steps (atlas, tex-index, UV region) into
     * one call. Must be called from the GPU thread, after [ensurePipelines].
     *
     * @param scene     The prepared scene whose materials to inspect.
     * @param faceCount Number of faces to pack (must match the scene's command count).
     */
    fun uploadTextures(scene: PreparedScene, faceCount: Int) {
        // Atlas rebuild has its own cache (lastAtlasSignature) — always run it independently.
        uploadAtlasAndBindGroup(scene)

        // TODO: add dirty flag when scene mutation API is available
        uploadTexIndexBuffer(scene, faceCount)

        // F-24: scene-level IDENTITY shortcut — skip UV region re-upload when every face
        // resolves to TextureTransform.IDENTITY and the buffer was already populated on a
        // prior frame (uvRegionGpuBuffer != null).  On the very first frame the buffer is
        // null, so the guard below does not fire and the initial upload always proceeds.
        val allIdentity = scene.commands.all { cmd ->
            resolveTextureTransform(resolveEffectiveMaterial(cmd)) == TextureTransform.IDENTITY
        }
        if (allIdentity && uvRegionGpuBuffer != null) {
            // All faces use IDENTITY — buffer already contains IDENTITY matrices from last upload.
            // Skip re-upload to save GPU bus bandwidth.
            uvRegionsDirty = false
            return
        }

        uploadUvRegionBuffer(scene, faceCount)
        uvRegionsDirty = false
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Collect all distinct textured materials from the scene, pack them into an atlas,
     * and rebuild the texture bind group. Caches by texture source set to avoid
     * rebuilding when the same textures are used across frames.
     */
    private fun uploadAtlasAndBindGroup(scene: PreparedScene) {
        // Quick check: does this scene have any textured materials?
        val hasTextured = scene.commands.any { cmd ->
            cmd.material is IsometricMaterial.Textured || cmd.material is IsometricMaterial.PerFace
        }
        if (!hasTextured) {
            if (lastAtlasSignature != null) {
                atlasManager.destroy()
                lastAtlasSignature = null
            }
            rebuildBindGroup(textureStore.fallbackTextureView)
            return
        }

        // Full scan only when textures are present
        val textureSources = mutableSetOf<TextureSource>()
        for (cmd in scene.commands) {
            collectTextureSources(cmd.material, textureSources)
        }

        if (textureSources.isEmpty()) {
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
            val bitmap = resolveSourceToBitmap(source)
            if (bitmap == null) {
                Log.w(TAG, "TextureSource ${source::class.simpleName} not yet supported in WebGPU — skipping")
                mainHandler.post {
                    try { onTextureLoadError?.invoke(source) }
                    catch (t: Throwable) { Log.e(TAG, "onTextureLoadError threw: ${t.message}", t) }
                }
            } else {
                entries[source] = bitmap
            }
        }

        if (entries.isEmpty()) {
            rebuildBindGroup(textureStore.fallbackTextureView)
            // Record the failing source set as "known bad" so subsequent frames with the
            // same sources skip the dispatch path. Without this, textureSources != null
            // every frame and the callback fires at render frame rate (SPAM-1 fix).
            lastAtlasSignature = textureSources
            return
        }

        if (!atlasManager.rebuild(entries)) {
            Log.w(TAG, "GpuTextureManager: atlas rebuild failed — using fallback bind group")
            // Bulk-fire: the atlas failure is a GPU capacity constraint affecting all entries,
            // not an individual load failure. We report every source in the batch so callers
            // can distinguish partial atlas failures from individual source decode errors.
            // Each source gets its own Handler.post to match the per-source contract, but
            // all N messages are enqueued in a single frame — callers should be idempotent.
            entries.keys.forEach { source ->
                mainHandler.post {
                    try { onTextureLoadError?.invoke(source) }
                    catch (t: Throwable) { Log.e(TAG, "onTextureLoadError threw: ${t.message}", t) }
                }
            }
            rebuildBindGroup(textureStore.fallbackTextureView)
            // Record the failing source set so subsequent frames with the same sources skip
            // re-triggering the rebuild and re-firing the callback (SPAM-1 fix).
            lastAtlasSignature = textureSources
            return
        }
        rebuildBindGroup(atlasManager.textureView!!)
        lastAtlasSignature = textureSources
    }

    /**
     * Recursively collect [TextureSource] references from a material, expanding
     * [IsometricMaterial.PerFace] into its constituent sub-materials.
     *
     * **Per-slot textures on the `Stairs` `PerFace` variant are not yet collected into
     * the atlas.** Stairs ships as an empty dispatch stub: constructing one with
     * per-slot [IsometricMaterial.Textured] entries is legal, but those textures never
     * make it into the atlas and the faces render with the material's
     * [IsometricMaterial.PerFace.default] at runtime. The corresponding
     * `uv-generation-stairs` slice will enable per-slot collection. `Prism`, `Octahedron`,
     * `Pyramid`, and `Cylinder` are already wired.
     *
     * To avoid silently dropping textures, this function emits a single Log.w warning
     * the first time Stairs is seen carrying at least one `Textured` slot, listing
     * the slot sources. Callers that need textured Stairs rendering should wait for
     * the `uv-generation-stairs` slice.
     */
    private fun collectTextureSources(
        material: MaterialData?,
        out: MutableSet<TextureSource>,
    ) {
        when (val m = material) {
            is IsometricMaterial.Textured -> out.add(m.source)
            is IsometricMaterial.PerFace -> {
                when (m) {
                    is IsometricMaterial.PerFace.Prism -> {
                        for (sub in m.faceMap.values) {
                            if (sub is IsometricMaterial.Textured) out.add(sub.source)
                        }
                    }
                    is IsometricMaterial.PerFace.Octahedron -> {
                        for (sub in m.byIndex.values) {
                            if (sub is IsometricMaterial.Textured) out.add(sub.source)
                        }
                    }
                    is IsometricMaterial.PerFace.Pyramid -> {
                        (m.base as? IsometricMaterial.Textured)?.let { out.add(it.source) }
                        for (sub in m.laterals.values) {
                            if (sub is IsometricMaterial.Textured) out.add(sub.source)
                        }
                    }
                    is IsometricMaterial.PerFace.Cylinder -> {
                        (m.top as? IsometricMaterial.Textured)?.let { out.add(it.source) }
                        (m.bottom as? IsometricMaterial.Textured)?.let { out.add(it.source) }
                        (m.side as? IsometricMaterial.Textured)?.let { out.add(it.source) }
                    }
                    is IsometricMaterial.PerFace.Stairs -> {
                        (m.tread as? IsometricMaterial.Textured)?.let { out.add(it.source) }
                        (m.riser as? IsometricMaterial.Textured)?.let { out.add(it.source) }
                        (m.side as? IsometricMaterial.Textured)?.let { out.add(it.source) }
                    }
                }
                val default = m.default
                if (default is IsometricMaterial.Textured) out.add(default.source)
            }
            else -> {}
        }
    }

    /**
     * Pack and upload the compact per-face texture index buffer for the emit shader.
     */
    private fun uploadTexIndexBuffer(scene: PreparedScene, faceCount: Int) {
        if (faceCount == 0) return
        val requiredBytes = faceCount * 4
        texIndexBuf.ensureCapacity(faceCount, entryBytes = 4)
        val cpu = texIndexBuf.cpuBuffer
            ?: error("texIndexBuf.cpuBuffer is null after ensureCapacity — this is a bug")
        val gpu = texIndexBuf.gpuBuffer
            ?: error("texIndexBuf.gpuBuffer is null after ensureCapacity — this is a bug")
        cpu.rewind()
        cpu.limit(requiredBytes)
        SceneDataPacker.packTexIndicesInto(scene.commands, cpu, faceCount)
        cpu.rewind()
        ctx.queue.writeBuffer(gpu, 0L, cpu)
    }

    /**
     * Pack and upload the compact per-face UV region buffer for the emit shader.
     * Each face gets one `UvRegion` = 10 floats = [SceneDataLayout.UV_REGION_STRIDE] = 40 bytes.
     * Stores the user [TextureTransform] and atlas region separately (not composed).
     * The fragment shader applies `fract(rawUV) * atlasScale + atlasOffset` per-fragment.
     */
    private fun uploadUvRegionBuffer(scene: PreparedScene, faceCount: Int) {
        ctx.assertGpuThread()
        if (faceCount == 0) return

        val entryBytes = SceneDataLayout.UV_REGION_STRIDE
        uvRegionBuf.ensureCapacity(faceCount, entryBytes)

        val cpu = uvRegionBuf.cpuBuffer
            ?: error("uvRegionBuf.cpuBuffer is null after ensureCapacity — this is a bug")
        val gpu = uvRegionBuf.gpuBuffer
            ?: error("uvRegionBuf.gpuBuffer is null after ensureCapacity — this is a bug")

        val requiredBytes = faceCount * entryBytes
        cpu.rewind()
        cpu.limit(requiredBytes)
        for (i in 0 until faceCount) {
            val cmd = scene.commands[i]
            val effective = resolveEffectiveMaterial(cmd)
            val region = resolveAtlasRegion(effective)
            val transform = resolveTextureTransform(effective)
            UvRegionPacker.pack(
                buf          = cpu,
                atlasScaleU  = region?.uvScale?.get(0)  ?: 1f,
                atlasScaleV  = region?.uvScale?.get(1)  ?: 1f,
                atlasOffsetU = region?.uvOffset?.get(0) ?: 0f,
                atlasOffsetV = region?.uvOffset?.get(1) ?: 0f,
                transform    = transform,
            )
        }
        cpu.rewind()
        ctx.queue.writeBuffer(gpu, 0L, cpu)
    }

    /**
     * Resolve the effective material for a command, expanding [IsometricMaterial.PerFace]
     * using [RenderCommand.faceType]. Called once per face so that [resolveAtlasRegion]
     * and [resolveTextureTransform] can share the result without a second HashMap lookup.
     */
    private fun resolveEffectiveMaterial(cmd: RenderCommand): MaterialData? =
        when (val m = cmd.material) {
            is IsometricMaterial.PerFace -> m.resolveForFace(cmd.faceType)
            else -> m
        }

    /**
     * Resolve the atlas region for a pre-resolved effective material. Returns null for
     * non-textured faces.
     */
    private fun resolveAtlasRegion(effective: MaterialData?): TextureAtlasManager.AtlasRegion? =
        when (effective) {
            is IsometricMaterial.Textured -> atlasManager.getRegion(effective.source)
            else -> null
        }

    /** Close the current bind group (if any) and create a new one for [view]. */
    private fun rebuildBindGroup(view: androidx.webgpu.GPUTextureView): GPUBindGroup {
        // Safe to close the old bind group immediately even if a prior frame's command buffer
        // is still in-flight on the GPU queue: Dawn internally reference-counts the underlying
        // native object and keeps it alive until all in-flight GPU commands that reference it
        // have completed. The Java-side close() only decrements the refcount; it does NOT free
        // the native memory while any GPU work still holds a reference.
        // TODO: verify this behaviour holds across Dawn alpha releases — see b/webgpu-lifecycle.
        _textureBindGroup?.close()
        val bg = textureBinder.buildBindGroup(view)
        _textureBindGroup = bg
        return bg
    }

    /**
     * Revert to the fallback texture bind group. Call when the scene becomes empty.
     */
    fun resetToFallback() {
        if (lastAtlasSignature != null) {
            atlasManager.destroy()
            lastAtlasSignature = null
        }
        uvRegionsDirty = true
        rebuildBindGroup(textureStore.fallbackTextureView)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun close() {
        _textureBindGroup?.close()
        _textureBindGroup = null
        if (::atlasManager.isInitialized) atlasManager.destroy()
        lastAtlasSignature = null
        uvRegionsDirty = true
        if (::textureBinder.isInitialized) textureBinder.close()
        textureStore.close()
        texIndexBuf.close()
        uvRegionBuf.close()
    }
}
