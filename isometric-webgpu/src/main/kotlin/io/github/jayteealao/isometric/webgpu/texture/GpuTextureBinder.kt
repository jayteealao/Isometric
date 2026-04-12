package io.github.jayteealao.isometric.webgpu.texture

import androidx.webgpu.AddressMode
import androidx.webgpu.FilterMode
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUSampler
import androidx.webgpu.GPUSamplerBindingLayout
import androidx.webgpu.GPUSamplerDescriptor
import androidx.webgpu.GPUTextureBindingLayout
import androidx.webgpu.GPUTextureView
import androidx.webgpu.MipmapFilterMode
import androidx.webgpu.SamplerBindingType
import androidx.webgpu.ShaderStage
import androidx.webgpu.TextureSampleType
import androidx.webgpu.TextureViewDimension
import io.github.jayteealao.isometric.webgpu.GpuContext

/**
 * Manages the sampler and `@group(0)` bind group layout for texture sampling in the
 * render pipeline's fragment shader.
 *
 * ## Bind group layout (`@group(0)`)
 * ```
 * binding 0 — texture_2d<f32>  (fragment-visible)
 * binding 1 — sampler          (fragment-visible, filtering)
 * ```
 */
internal class GpuTextureBinder(private val ctx: GpuContext) : AutoCloseable {

    val sampler: GPUSampler = ctx.device.createSampler(
        GPUSamplerDescriptor(
            magFilter = FilterMode.Linear,
            minFilter = FilterMode.Linear,
            mipmapFilter = MipmapFilterMode.Nearest,
            addressModeU = AddressMode.ClampToEdge,
            addressModeV = AddressMode.ClampToEdge,
        )
    )

    /** Bind group layout describing `@group(0)` for the render pipeline. */
    val bindGroupLayout: GPUBindGroupLayout = ctx.device.createBindGroupLayout(
        GPUBindGroupLayoutDescriptor(
            entries = arrayOf(
                // binding 0 — texture_2d<f32>
                GPUBindGroupLayoutEntry(
                    binding = 0,
                    visibility = ShaderStage.Fragment,
                    texture = GPUTextureBindingLayout(
                        sampleType = TextureSampleType.Float,
                        viewDimension = TextureViewDimension._2D,
                    ),
                ),
                // binding 1 — sampler (filtering)
                GPUBindGroupLayoutEntry(
                    binding = 1,
                    visibility = ShaderStage.Fragment,
                    sampler = GPUSamplerBindingLayout(
                        type = SamplerBindingType.Filtering,
                    ),
                ),
            )
        )
    )

    /**
     * Create a bind group pairing the given [textureView] with the shared [sampler].
     *
     * The returned [GPUBindGroup] should be set on the render pass via
     * `pass.setBindGroup(0, bindGroup)`.
     */
    fun buildBindGroup(textureView: GPUTextureView): GPUBindGroup =
        ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = bindGroupLayout,
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, textureView = textureView),
                    GPUBindGroupEntry(binding = 1, sampler = sampler),
                ),
            )
        )

    override fun close() {
        bindGroupLayout.close()
        sampler.close()
    }
}
