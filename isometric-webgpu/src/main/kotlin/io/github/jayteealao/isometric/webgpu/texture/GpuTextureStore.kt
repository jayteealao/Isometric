package io.github.jayteealao.isometric.webgpu.texture

import android.graphics.Bitmap
import androidx.webgpu.GPUTexture
import androidx.webgpu.GPUTextureDescriptor
import androidx.webgpu.GPUTextureView
import androidx.webgpu.GPUTexelCopyTextureInfo
import androidx.webgpu.GPUTexelCopyBufferLayout
import androidx.webgpu.GPUExtent3D
import androidx.webgpu.TextureDimension
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import io.github.jayteealao.isometric.webgpu.GpuContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages GPU texture creation and pixel upload for the WebGPU render pipeline.
 *
 * Creates a 1×1 white fallback texture on init (used when no texture is loaded).
 * White is neutral for all multiply/blend operations — it produces no color artifact
 * when an untextured face samples it. Provides [uploadBitmap] to upload Android
 * [Bitmap] data as `RGBA8Unorm` GPU textures — matching Android's
 * `Bitmap.copyPixelsToBuffer()` output which writes pixels as R,G,B,A bytes.
 */
internal class GpuTextureStore(private val ctx: GpuContext) : AutoCloseable {

    companion object {
        /** Maximum allowed bitmap dimension (width or height) for GPU upload. */
        const val MAX_TEXTURE_DIMENSION = 4096
    }

    private val ownedTextures = mutableListOf<GPUTexture>()

    /** 1×1 white fallback GPU texture. */
    val fallbackTexture: GPUTexture

    /** View of [fallbackTexture] for bind group creation. */
    val fallbackTextureView: GPUTextureView

    init {
        ctx.assertGpuThread()
        // 1×1 RGBA8Unorm white pixel — neutral for all multiply/blend operations.
        val pixels = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        pixels.put(0xFF.toByte()); pixels.put(0xFF.toByte()); pixels.put(0xFF.toByte()); pixels.put(0xFF.toByte())
        pixels.rewind()

        fallbackTexture = createTexture(1, 1)
        ctx.queue.writeTexture(
            GPUTexelCopyTextureInfo(texture = fallbackTexture),
            pixels,
            GPUExtent3D(width = 1, height = 1),
            GPUTexelCopyBufferLayout(bytesPerRow = 1 * 4, rowsPerImage = 1),
        )
        fallbackTextureView = fallbackTexture.createView()
        ownedTextures += fallbackTexture
    }

    /**
     * Upload an Android [Bitmap] to a new GPU texture.
     *
     * The bitmap must be [Bitmap.Config.ARGB_8888]. [Bitmap.copyPixelsToBuffer] writes
     * pixels as R,G,B,A bytes — matching [TextureFormat.RGBA8Unorm].
     *
     * @return The created [GPUTexture]. Caller should call [releaseTexture] when done, or
     *         rely on [close] to release all owned textures.
     */
    fun uploadBitmap(bitmap: Bitmap): GPUTexture {
        require(bitmap.config == Bitmap.Config.ARGB_8888) {
            "Bitmap must be ARGB_8888, got ${bitmap.config}"
        }
        val w = bitmap.width
        val h = bitmap.height
        require(w in 1..MAX_TEXTURE_DIMENSION && h in 1..MAX_TEXTURE_DIMENSION) {
            "Bitmap dimensions ${w}x${h} exceed maximum $MAX_TEXTURE_DIMENSION"
        }
        val byteCount = w.toLong() * h.toLong() * 4L
        require(byteCount <= Int.MAX_VALUE) {
            "Bitmap byte count $byteCount exceeds Int.MAX_VALUE"
        }
        val pixels = ByteBuffer.allocateDirect(byteCount.toInt()).order(ByteOrder.nativeOrder())
        bitmap.copyPixelsToBuffer(pixels)
        pixels.rewind()

        val gpuTex = createTexture(w, h)
        ctx.queue.writeTexture(
            GPUTexelCopyTextureInfo(texture = gpuTex),
            pixels,
            GPUExtent3D(width = w, height = h),
            GPUTexelCopyBufferLayout(bytesPerRow = w * 4, rowsPerImage = h),
        )
        ownedTextures += gpuTex
        return gpuTex
    }

    /** Release a single texture previously created by [uploadBitmap]. */
    fun releaseTexture(texture: GPUTexture) {
        ownedTextures.remove(texture)
        texture.destroy()
        texture.close()
    }

    private fun createTexture(w: Int, h: Int): GPUTexture =
        ctx.device.createTexture(
            GPUTextureDescriptor(
                usage = TextureUsage.TextureBinding or TextureUsage.CopyDst,
                size = GPUExtent3D(width = w, height = h),
                format = TextureFormat.RGBA8Unorm,
            )
        )

    override fun close() {
        // Close views before destroying their backing textures
        fallbackTextureView.close()
        for (tex in ownedTextures) {
            tex.destroy()
            tex.close()
        }
        ownedTextures.clear()
    }
}
