package io.github.jayteealao.isometric.webgpu.pipeline

import androidx.webgpu.CullMode
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUPipelineLayout
import androidx.webgpu.GPUPipelineLayoutDescriptor
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
import io.github.jayteealao.isometric.webgpu.shader.IsometricFragmentShader
import io.github.jayteealao.isometric.webgpu.shader.IsometricVertexShader
import io.github.jayteealao.isometric.webgpu.triangulation.RenderCommandTriangulator

internal class GpuRenderPipeline(
    device: GPUDevice,
    @TextureFormat surfaceFormat: Int,
    textureBindGroupLayout: GPUBindGroupLayout,
) : AutoCloseable {
    val pipeline: GPURenderPipeline

    private val vertexModule: GPUShaderModule
    private val fragmentModule: GPUShaderModule
    private val pipelineLayout: GPUPipelineLayout

    init {
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

        // Explicit pipeline layout: @group(0) = texture + sampler
        pipelineLayout = device.createPipelineLayout(
            GPUPipelineLayoutDescriptor(
                bindGroupLayouts = arrayOf(textureBindGroupLayout),
            )
        )

        val vertexState = GPUVertexState(
            module = vertexModule,
            entryPoint = IsometricVertexShader.ENTRY_POINT,
            buffers = arrayOf(
                GPUVertexBufferLayout(
                    arrayStride = RenderCommandTriangulator.BYTES_PER_VERTEX.toLong(),
                    stepMode = VertexStepMode.Vertex,
                    attributes = arrayOf(
                        // location 0: position vec2<f32> at offset 0
                        GPUVertexAttribute(
                            format = VertexFormat.Float32x2,
                            offset = 0L,
                            shaderLocation = 0,
                        ),
                        // location 1: color vec4<f32> at offset 8
                        GPUVertexAttribute(
                            format = VertexFormat.Float32x4,
                            offset = 8L,
                            shaderLocation = 1,
                        ),
                        // location 2: uv vec2<f32> at offset 24
                        GPUVertexAttribute(
                            format = VertexFormat.Float32x2,
                            offset = 24L,
                            shaderLocation = 2,
                        ),
                        // location 3: textureIndex u32 at offset 32
                        GPUVertexAttribute(
                            format = VertexFormat.Uint32,
                            offset = 32L,
                            shaderLocation = 3,
                        ),
                    ),
                )
            ),
        )

        val fragmentState = GPUFragmentState(
            module = fragmentModule,
            entryPoint = IsometricFragmentShader.ENTRY_POINT,
            targets = arrayOf(
                GPUColorTargetState(format = surfaceFormat)
            ),
        )

        pipeline = device.createRenderPipeline(
            GPURenderPipelineDescriptor(
                label = "IsometricRenderPipeline",
                vertex = vertexState,
                primitive = GPUPrimitiveState(
                    topology = PrimitiveTopology.TriangleList,
                    cullMode = CullMode.None,
                ),
                fragment = fragmentState,
                layout = pipelineLayout,
            )
        ).also {
            it.setLabel("IsometricRenderPipeline")
        }
    }

    override fun close() {
        pipeline.close()
        pipelineLayout.close()
        vertexModule.close()
        fragmentModule.close()
    }
}
