package io.github.jayteealao.isometric.webgpu.pipeline

import android.util.Log
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
import io.github.jayteealao.isometric.webgpu.diagnostics.WgslDiagnostics
import io.github.jayteealao.isometric.webgpu.shader.IsometricFragmentShader
import io.github.jayteealao.isometric.webgpu.shader.IsometricVertexShader
import io.github.jayteealao.isometric.webgpu.triangulation.RenderCommandTriangulator

// ---------------------------------------------------------------------------
// Vertex attribute byte offsets — must stay in sync with the write order in
// RenderCommandTriangulator.writeVertex(), which is authoritative.
//
// Layout (each field is 4 bytes wide):
//   [0 ..  7]  position   : Float32x2  (x, y)
//   [8 .. 23]  color      : Float32x4  (r, g, b, a)
//   [24 .. 31] uv         : Float32x2  (u, v)
//   [32 .. 47] atlasRegion: Float32x4  (scaleU, scaleV, offsetU, offsetV)
//   [48 .. 51] textureIdx : Uint32
//   [52 .. 55] padding    : (1 × u32, unused)
// Total: 56 bytes == RenderCommandTriangulator.BYTES_PER_VERTEX
// ---------------------------------------------------------------------------

/** Byte offset of the position attribute (first field; nothing precedes it). */
private const val ATTR_POSITION_OFFSET = 0L

/** Byte offset of the color attribute — follows position: 2 × Float32 = 8 bytes. */
private const val ATTR_COLOR_OFFSET = 8L

/** Byte offset of the UV attribute — follows position (8 B) + color (16 B) = 24 bytes. */
private const val ATTR_UV_OFFSET = 24L

/** Byte offset of the atlasRegion attribute — follows position (8 B) + color (16 B) + uv (8 B) = 32 bytes. */
private const val ATTR_ATLAS_REGION_OFFSET = 32L

/** Byte offset of the textureIndex attribute — follows position (8 B) + color (16 B) + uv (8 B) + atlasRegion (16 B) = 48 bytes. */
private const val ATTR_TEXTURE_INDEX_OFFSET = 48L

// Increment VERTEX_FORMAT_VERSION whenever the vertex layout changes (stride, attributes, offsets) to force pipeline recompilation.
private const val VERTEX_FORMAT_VERSION = 1

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

    companion object {
        private const val TAG = "GpuRenderPipeline"
    }

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

    private var compiledFormatVersion: Int = -1

    private var vertexModule: GPUShaderModule? = null
    private var fragmentModule: GPUShaderModule? = null

    /**
     * Create the render pipeline asynchronously if not already built.
     * Must be called from the GPU thread (`ctx.withGpu { ... }`).
     *
     * On any partial failure during pipeline assembly, all partially-allocated handles are
     * closed and nulled so the next [ensurePipeline] call starts from a clean state.
     */
    suspend fun ensurePipeline() {
        ctx.assertGpuThread()
        if (pipeline != null && compiledFormatVersion == VERTEX_FORMAT_VERSION) return

        var ok = false
        try {
            vertexModule = device.createShaderModule(
                GPUShaderModuleDescriptor(
                    label = "IsometricVertexShader",
                    shaderSourceWGSL = GPUShaderSourceWGSL(IsometricVertexShader.WGSL),
                )
            )
            WgslDiagnostics.logCompilation(vertexModule!!, TAG)
            fragmentModule = device.createShaderModule(
                GPUShaderModuleDescriptor(
                    label = "IsometricFragmentShader",
                    shaderSourceWGSL = GPUShaderSourceWGSL(IsometricFragmentShader.WGSL),
                )
            )
            WgslDiagnostics.logCompilation(fragmentModule!!, TAG)

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
                                offset = ATTR_POSITION_OFFSET,
                                shaderLocation = 0,
                            ),
                            GPUVertexAttribute(
                                format = VertexFormat.Float32x4,
                                offset = ATTR_COLOR_OFFSET,
                                shaderLocation = 1,
                            ),
                            GPUVertexAttribute(
                                format = VertexFormat.Float32x2,
                                offset = ATTR_UV_OFFSET,
                                shaderLocation = 2,
                            ),
                            GPUVertexAttribute(
                                // atlasRegion: (scaleU, scaleV, offsetU, offsetV) flat per face.
                                // Fragment shader applies fract(uv) * atlasRegion.xy + atlasRegion.zw
                                format = VertexFormat.Float32x4,
                                offset = ATTR_ATLAS_REGION_OFFSET,
                                shaderLocation = 3,
                            ),
                            GPUVertexAttribute(
                                format = VertexFormat.Uint32,
                                offset = ATTR_TEXTURE_INDEX_OFFSET,
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
            compiledFormatVersion = VERTEX_FORMAT_VERSION
            ok = true
        } finally {
            if (!ok) {
                // Close any partially-allocated handles so the next ensurePipeline() call
                // starts from a clean slate and does not hold dangling GPU object references.
                vertexModule?.close(); vertexModule = null
                fragmentModule?.close(); fragmentModule = null
                textureBindGroupLayout?.close(); textureBindGroupLayout = null
                pipeline?.close(); pipeline = null
            }
        }
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
