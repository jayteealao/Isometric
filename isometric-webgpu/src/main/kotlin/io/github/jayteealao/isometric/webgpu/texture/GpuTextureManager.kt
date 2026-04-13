package io.github.jayteealao.isometric.webgpu.texture

import android.util.Log
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBuffer
import io.github.jayteealao.isometric.MaterialData
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.TextureSource
import io.github.jayteealao.isometric.webgpu.GpuContext
import io.github.jayteealao.isometric.webgpu.pipeline.GpuRenderPipeline
import io.github.jayteealao.isometric.webgpu.pipeline.GrowableGpuStagingBuffer
import io.github.jayteealao.isometric.webgpu.pipeline.SceneDataPacker

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
 */
internal class GpuTextureManager(
    private val ctx: GpuContext,
) : AutoCloseable {

    companion object {
        private const val TAG = "GpuTextureManager"
    }

    // ── Core texture components ───────────────────────────────────────────────

    val textureStore = GpuTextureStore(ctx)
    private lateinit var textureBinder: GpuTextureBinder
    private lateinit var atlasManager: TextureAtlasManager

    // ── Texture bind-group state ──────────────────────────────────────────────

    /** Signature of the last atlas build, to detect changes. */
    private var lastAtlasSignature: Set<TextureSource>? = null

    /**
     * Combined hash of [PreparedScene.commands.size] and [PreparedScene.commands.hashCode]
     * at the last [uploadTextures] call. Used to skip redundant [writeBuffer] calls when
     * the command list is unchanged between frames. Note: [uploadAtlasAndBindGroup] has
     * its own cache ([lastAtlasSignature]) and is not guarded by this hash.
     */
    private var lastUploadedCommandsHash: Int = Int.MIN_VALUE

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
            atlasManager = TextureAtlasManager(ctx, textureStore)
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

        // Skip the three writeBuffer calls when the command list is identical to last frame.
        val commandsHash = 31 * scene.commands.size + scene.commands.hashCode()
        if (commandsHash == lastUploadedCommandsHash) return
        lastUploadedCommandsHash = commandsHash

        uploadTexIndexBuffer(scene, faceCount)
        uploadUvRegionBuffer(scene, faceCount)
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

        val built = atlasManager.rebuild(entries)
        if (built) {
            rebuildBindGroup(atlasManager.textureView!!)
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
        material: MaterialData?,
        out: MutableSet<TextureSource>,
    ) {
        when (val m = material) {
            is IsometricMaterial.Textured -> out.add(m.source)
            is IsometricMaterial.PerFace -> {
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
        val requiredBytes = faceCount * 4
        texIndexBuf.ensureCapacity(faceCount, entryBytes = 4)
        val buf = texIndexBuf.cpuBuffer!!
        buf.rewind()
        buf.limit(requiredBytes)
        SceneDataPacker.packTexIndicesInto(scene.commands, buf, faceCount)
        buf.rewind()
        ctx.queue.writeBuffer(texIndexBuf.gpuBuffer!!, 0L, buf)
    }

    /**
     * Pack and upload the compact per-face UV region buffer for the emit shader.
     * Each face gets 4 floats: [uvOffsetU, uvOffsetV, uvScaleU, uvScaleV] = 16 bytes.
     */
    private fun uploadUvRegionBuffer(scene: PreparedScene, faceCount: Int) {
        if (faceCount == 0) return

        val entryBytes = 16 // 4 floats × 4 bytes
        uvRegionBuf.ensureCapacity(faceCount, entryBytes)

        val requiredBytes = faceCount * entryBytes
        val buf = uvRegionBuf.cpuBuffer!!
        buf.rewind()
        buf.limit(requiredBytes)
        for (i in 0 until faceCount) {
            val cmd = scene.commands[i]
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

        ctx.queue.writeBuffer(uvRegionBuf.gpuBuffer!!, 0L, buf)
    }

    /**
     * Resolve the atlas region for a command's effective texture. Returns null for
     * non-textured faces. Expands [IsometricMaterial.PerFace] using the command's
     * [RenderCommand.faceType].
     */
    private fun resolveAtlasRegion(cmd: RenderCommand): TextureAtlasManager.AtlasRegion? {
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

    /** Close the current bind group (if any) and create a new one for [view]. */
    private fun rebuildBindGroup(view: androidx.webgpu.GPUTextureView): GPUBindGroup {
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
        lastUploadedCommandsHash = Int.MIN_VALUE
        rebuildBindGroup(textureStore.fallbackTextureView)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun close() {
        _textureBindGroup?.close()
        _textureBindGroup = null
        if (::atlasManager.isInitialized) atlasManager.destroy()
        lastAtlasSignature = null
        lastUploadedCommandsHash = Int.MIN_VALUE
        if (::textureBinder.isInitialized) textureBinder.close()
        textureStore.close()
        texIndexBuf.close()
        uvRegionBuf.close()
    }
}
