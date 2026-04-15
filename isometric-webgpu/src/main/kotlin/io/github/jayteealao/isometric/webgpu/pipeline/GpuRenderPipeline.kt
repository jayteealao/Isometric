package io.github.jayteealao.isometric.webgpu.pipeline

import androidx.webgpu.CullMode
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.GPURenderPipeline
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUVertexAttribute
import androidx.webgpu.GPUVertexBufferLayout
import androidx.webgpu.GPUVertexState
import androidx.webgpu.PrimitiveTopology
import androidx.webgpu.TextureFormat
import androidx.webgpu.VertexFormat
import androidx.webgpu.VertexStepMode
import io.github.jayteealao.isometric.webgpu.GpuContext
import io.github.jayteealao.isometric.webgpu.shader.IsometricFragmentShader
import io.github.jayteealao.isometric.webgpu.shader.IsometricVertexShader
import io.github.jayteealao.isometric.webgpu.triangulation.RenderCommandTriangulator

/**
 * Creates the render pipeline with auto-derived layout via the async API
 * (`createRenderPipelineAndAwait`). The sync `createRenderPipeline` triggers a
 * Scudo double-free on Adreno 750 (Dawn alpha04) when the fragment shader declares
 * texture/sampler bindings.
 *
 * Use [textureBindGroupLayout] to create compatible bind groups after [ensurePipeline].
 */
internal class GpuRenderPipeline(
    private val ctx: GpuContext,
    @TextureFormat private val surfaceFormat: Int,
) : AutoCloseable {

    private val device: GPUDevice get() = ctx.device

    var pipeline: GPURenderPipeline? = null
        private set

    /**
     * Auto-derived bind group layout for `@group(0)` (texture + sampler).
     * Available after [ensurePipeline] completes. Use [takeTextureBindGroupLayout]
     * to transfer ownership to the caller.
     */
    var textureBindGroupLayout: GPUBindGroupLayout? = null
        private set

    /**
     * Transfer ownership of [textureBindGroupLayout] to the caller.
     * After this call, the layout is no longer closed by [close] —
     * the caller is responsible for closing it.
     */
    fun takeTextureBindGroupLayout(): GPUBindGroupLayout {
        val layout = checkNotNull(textureBindGroupLayout) {
            "textureBindGroupLayout not available — call ensurePipeline() first"
        }
        textureBindGroupLayout = null
        return layout
    }

    private var vertexModule: GPUShaderModule? = null
    private var fragmentModule: GPUShaderModule? = null

    /**
     * Create the render pipeline asynchronously if not already built.
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     */
    suspend fun ensurePipeline() {
        ctx.assertGpuThread()
        if (pipeline != null) return

        vertexModule = device.createShaderModule(
            GPUShaderModuleDescriptor(
                label = "IsometricVertexShader",
                shaderSourceWGSL = GPUShaderSourceWGSL(IsometricVertexShader.WGSL),
            )
        )
        fragmentModule = device.createShaderModule(
            GPUShaderModuleDescriptor(
                label = "IsometricFragmentShader",
                shaderSourceWGSL = GPUShaderSourceWGSL(IsometricFragmentShader.WGSL),
            )
        )

        val vertexState = GPUVertexState(
            module = vertexModule!!,
            entryPoint = IsometricVertexShader.ENTRY_POINT,
            buffers = arrayOf(
                GPUVertexBufferLayout(
                    arrayStride = RenderCommandTriangulator.BYTES_PER_VERTEX.toLong(),
                    stepMode = VertexStepMode.Vertex,
                    attributes = arrayOf(
                        GPUVertexAttribute(
                            format = VertexFormat.Float32x2,
                            offset = 0L,
                            shaderLocation = 0,
                        ),
                        GPUVertexAttribute(
                            format = VertexFormat.Float32x4,
                            offset = 8L,
                            shaderLocation = 1,
                        ),
                        GPUVertexAttribute(
                            format = VertexFormat.Float32x2,
                            offset = 24L,
                            shaderLocation = 2,
                        ),
                        GPUVertexAttribute(
                            // atlasRegion: (scaleU, scaleV, offsetU, offsetV) flat per face.
                            // Fragment shader applies fract(uv) * atlasRegion.xy + atlasRegion.zw
                            format = VertexFormat.Float32x4,
                            offset = 32L,
                            shaderLocation = 3,
                        ),
                        GPUVertexAttribute(
                            format = VertexFormat.Uint32,
                            offset = 48L,
                            shaderLocation = 4,
                        ),
                    ),
                )
            ),
        )

        val fragmentState = GPUFragmentState(
            module = fragmentModule!!,
            entryPoint = IsometricFragmentShader.ENTRY_POINT,
            targets = arrayOf(
                GPUColorTargetState(format = surfaceFormat)
            ),
        )

        val descriptor = GPURenderPipelineDescriptor(
            label = "IsometricRenderPipeline",
            vertex = vertexState,
            primitive = GPUPrimitiveState(
                topology = PrimitiveTopology.TriangleList,
                cullMode = CullMode.None,
            ),
            fragment = fragmentState,
        )

        // Use async API — the sync createRenderPipeline triggers a Scudo double-free
        // on Adreno 750 (Dawn alpha04) when the fragment shader declares texture bindings.
        val rp = device.createRenderPipelineAndAwait(descriptor)
        rp.setLabel("IsometricRenderPipeline")
        pipeline = rp

        // Extract the auto-derived bind group layout for @group(0).
        textureBindGroupLayout = rp.getBindGroupLayout(0)
    }

    override fun close() {
        textureBindGroupLayout?.close()
        textureBindGroupLayout = null
        pipeline?.close()
        pipeline = null
        vertexModule?.close()
        vertexModule = null
        fragmentModule?.close()
        fragmentModule = null
    }
}
