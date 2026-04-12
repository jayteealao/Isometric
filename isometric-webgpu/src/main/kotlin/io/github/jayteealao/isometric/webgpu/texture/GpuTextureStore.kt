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
 * Creates a 2×2 magenta/black checkerboard fallback texture on init (used when no
 * texture is loaded). Provides [uploadBitmap] to upload Android [Bitmap] data as
 * `BGRA8Unorm` GPU textures — matching Android's native `ARGB_8888` byte order on
 * little-endian without CPU-side channel swizzle.
 */
internal class GpuTextureStore(private val ctx: GpuContext) : AutoCloseable {

    private val ownedTextures = mutableListOf<GPUTexture>()

    /** 2×2 checkerboard fallback GPU texture (magenta/black pattern). */
    val fallbackTexture: GPUTexture

    /** View of [fallbackTexture] for bind group creation. */
    val fallbackTextureView: GPUTextureView

    init {
        // 2×2 BGRA8Unorm checkerboard: magenta, black, black, magenta
        val pixels = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.nativeOrder())
        // pixel (0,0): magenta — BGRA = (255, 0, 255, 255)
        pixels.put(0xFF.toByte()); pixels.put(0x00.toByte()); pixels.put(0xFF.toByte()); pixels.put(0xFF.toByte())
        // pixel (1,0): black — BGRA = (0, 0, 0, 255)
        pixels.put(0x00.toByte()); pixels.put(0x00.toByte()); pixels.put(0x00.toByte()); pixels.put(0xFF.toByte())
        // pixel (0,1): black
        pixels.put(0x00.toByte()); pixels.put(0x00.toByte()); pixels.put(0x00.toByte()); pixels.put(0xFF.toByte())
        // pixel (1,1): magenta
        pixels.put(0xFF.toByte()); pixels.put(0x00.toByte()); pixels.put(0xFF.toByte()); pixels.put(0xFF.toByte())
        pixels.rewind()

        fallbackTexture = createTexture(2, 2)
        ctx.queue.writeTexture(
            GPUTexelCopyTextureInfo(texture = fallbackTexture),
            pixels,
            GPUExtent3D(width = 2, height = 2),
            GPUTexelCopyBufferLayout(bytesPerRow = 2 * 4, rowsPerImage = 2),
        )
        fallbackTextureView = fallbackTexture.createView()
        ownedTextures += fallbackTexture
    }

    /**
     * Upload an Android [Bitmap] to a new GPU texture.
     *
     * The bitmap must be [Bitmap.Config.ARGB_8888]. On little-endian (all Android devices),
     * this stores bytes as BGRA in memory — matching [TextureFormat.BGRA8Unorm] exactly.
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
        val byteCount = w * h * 4
        val pixels = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
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
                format = TextureFormat.BGRA8Unorm,
            )
        )

    override fun close() {
        for (tex in ownedTextures) {
            tex.destroy()
            tex.close()
        }
        ownedTextures.clear()
        fallbackTextureView.close()
    }
}
