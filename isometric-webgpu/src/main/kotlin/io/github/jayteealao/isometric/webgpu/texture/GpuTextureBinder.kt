package io.github.jayteealao.isometric.webgpu.texture

import androidx.webgpu.AddressMode
import androidx.webgpu.FilterMode
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUSampler
import androidx.webgpu.GPUSamplerDescriptor
import androidx.webgpu.GPUTextureView
import androidx.webgpu.MipmapFilterMode
import io.github.jayteealao.isometric.webgpu.GpuContext

/**
 * Manages the sampler and bind group creation for texture sampling in the
 * render pipeline's fragment shader (`@group(0)`).
 *
 * The bind group layout is provided externally — typically auto-derived from the
 * render pipeline via `GPURenderPipeline.getBindGroupLayout(0)`.
 *
 * ## Bind group entries
 * ```
 * binding 0 — texture_2d<f32>  (fragment-visible)
 * binding 1 — sampler          (fragment-visible, filtering)
 * ```
 */
internal class GpuTextureBinder(
    private val ctx: GpuContext,
) : AutoCloseable {

    val sampler: GPUSampler = ctx.device.createSampler(
        GPUSamplerDescriptor(
            magFilter = FilterMode.Linear,
            minFilter = FilterMode.Linear,
            mipmapFilter = MipmapFilterMode.Nearest,
            addressModeU = AddressMode.ClampToEdge,
            addressModeV = AddressMode.ClampToEdge,
        )
    )

    /** The bind group layout to use for creating bind groups. Set after render pipeline creation. */
    var bindGroupLayout: GPUBindGroupLayout? = null

    /**
     * Create a bind group pairing the given [textureView] with the shared [sampler].
     *
     * [bindGroupLayout] must be set before calling this method.
     *
     * The returned [GPUBindGroup] should be set on the render pass via
     * `pass.setBindGroup(0, bindGroup)`.
     */
    fun buildBindGroup(textureView: GPUTextureView): GPUBindGroup {
        val layout = checkNotNull(bindGroupLayout) {
            "bindGroupLayout not set — call after GpuRenderPipeline creation"
        }
        return ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = layout,
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, textureView = textureView),
                    GPUBindGroupEntry(binding = 1, sampler = sampler),
                ),
            )
        )
    }

    override fun close() {
        // bindGroupLayout is owned by GpuRenderPipeline — don't close it here
        sampler.close()
    }
}
