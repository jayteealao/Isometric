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
 * The [bindGroupLayout] is provided at construction time — typically auto-derived
 * from the render pipeline via `GPURenderPipeline.getBindGroupLayout(0)`.
 * This class takes ownership of the layout and closes it in [close].
 *
 * ## Bind group entries
 * ```
 * binding 0 — texture_2d<f32>  (fragment-visible)
 * binding 1 — sampler          (fragment-visible, filtering)
 * ```
 */
internal class GpuTextureBinder(
    private val ctx: GpuContext,
    /** Auto-derived bind group layout for `@group(0)`. Owned by this class. */
    val bindGroupLayout: GPUBindGroupLayout,
) : AutoCloseable {

    /**
     * Shared sampler for all texture atlas lookups.
     *
     * Mipmaps are not generated; texture sampling uses the base level only.
     * [MipmapFilterMode.Nearest] is set to satisfy the WebGPU sampler descriptor
     * contract but has no effect since the atlas texture is created without mip levels.
     */
    val sampler: GPUSampler = ctx.device.createSampler(
        GPUSamplerDescriptor(
            magFilter = FilterMode.Linear,
            minFilter = FilterMode.Linear,
            mipmapFilter = MipmapFilterMode.Nearest,
            addressModeU = AddressMode.ClampToEdge,
            addressModeV = AddressMode.ClampToEdge,
        )
    )

    /**
     * Create a bind group pairing the given [textureView] with the shared [sampler].
     *
     * The returned [GPUBindGroup] should be set on the render pass via
     * `pass.setBindGroup(0, bindGroup)`.
     */
    fun buildBindGroup(textureView: GPUTextureView): GPUBindGroup {
        return ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = bindGroupLayout,
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, textureView = textureView),
                    GPUBindGroupEntry(binding = 1, sampler = sampler),
                ),
            )
        )
    }

    override fun close() {
        bindGroupLayout.close()
        sampler.close()
    }
}
