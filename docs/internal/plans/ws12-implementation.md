# WS12 Implementation — Phase 2: WebGpuIsometricRenderer

> **Workstream**: WS12
> **Depends on**: WS11 (Module Scaffold + GpuDepthSorter)
> **Artifact**: `isometric-webgpu` (additions to existing module)
> **Pinned API**: `androidx.webgpu:1.0.0-alpha04`
> **Authored**: 2026-03-18
> **Source accuracy**: All code in this file is written against the current source tree
>   and the vendored `vendor/androidx-webgpu/` API surface. No pseudo-code.

---

## Table of Contents

1. [Overview](#1-overview)
2. [New Files](#2-new-files)
3. [WGSL Vertex Shader](#3-wgsl-vertex-shader)
4. [WGSL Fragment Shader](#4-wgsl-fragment-shader)
5. [RenderCommandTriangulator](#5-rendercommandtriangulator)
6. [GpuVertexBuffer — Buffer Management](#6-gpuvertexbuffer--buffer-management)
7. [GpuRenderPipeline — Pipeline State](#7-gpurenderpipeline--pipeline-state)
8. [WebGpuSceneRenderer](#8-webgpuscenerenderer)
9. [WebGpuRenderBackend](#9-webgpurenderbackend)
10. [RenderBackend.WebGpu Extension](#10-renderbackendwebgpu-extension)
11. [Touch Bridge — Pointer Coordinate Contract](#11-touch-bridge--pointer-coordinate-contract)
12. [IsometricScene Integration](#12-isometricscene-integration)
13. [IsoColor GPU Conversion](#13-isocolor-gpu-conversion)
14. [Test Plan](#14-test-plan)

---

## 1. Overview

WS12 implements the full `WebGpuRenderBackend` — an `AndroidExternalSurface`-based rendering
surface that draws isometric scenes using WebGPU instead of Canvas. The `GpuDepthSorter` from
WS11 activates here inside the async frame loop.

**What ships**:
- `RenderBackend.WebGpu` available when `isometric-webgpu` is on classpath
- `IsometricScene(config = SceneConfig(renderBackend = RenderBackend.WebGpu))` renders scenes
- WGSL vertex/fragment shaders for solid-color isometric faces
- Triangle fan triangulation of N-gon render commands
- `AndroidExternalSurface` embedding with proper frame loop
- Surface resize handling (rotation, window resize)
- Hit testing remains in `IsometricScene` — backends do not participate

**What does not ship**:
- No texture support (`textureIndex` deferred to WS13)
- No GPU-side lighting, culling, or transform compute passes (WS13)
- No `drawIndirect` — explicit `draw()` per frame (WS13)

---

## 2. New Files

```
isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/
├── RenderBackendWebGpu.kt              ← RenderBackend impl
├── WebGpuSceneRenderer.kt              ← Surface lifecycle + frame loop
├── RenderBackendExtensions.kt          ← RenderBackend.Companion.WebGpu
├── triangulation/
│   └── RenderCommandTriangulator.kt    ← N-gon → triangle fan
├── shader/
│   ├── IsometricVertexShader.kt        ← WGSL vertex shader source
│   └── IsometricFragmentShader.kt      ← WGSL fragment shader source
└── pipeline/
    ├── GpuVertexBuffer.kt              ← Vertex buffer management
    └── GpuRenderPipeline.kt           ← Pipeline state object creation
```

---

## 3. WGSL Vertex Shader

### New file: `isometric-webgpu/.../shader/IsometricVertexShader.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.shader

/**
 * WGSL vertex shader for Phase 2 isometric rendering.
 *
 * ## Vertex layout (8 floats = 32 bytes per vertex)
 *
 * | Location | Name     | Type      | Bytes | Purpose                              |
 * |----------|----------|-----------|-------|--------------------------------------|
 * | 0        | position | vec2<f32> | 8     | Screen-space XY (pixels from engine) |
 * | 1        | color    | vec4<f32> | 16    | RGBA (IsoColor → [0,1] floats)       |
 * | 2        | uv       | vec2<f32> | 8     | Reserved for Phase 3 textures        |
 *
 * `textureIndex` is NOT included in Phase 2. It will be added in Phase 3 once the
 * full texture API (atlas layout, sampler binding, shader changes) is designed together.
 *
 * ## Coordinate transform
 *
 * The vertex shader converts pixel coordinates (from `IsometricEngine.projectScene()`)
 * to WebGPU NDC:
 * - X: `[0, viewportWidth]` → `[-1, +1]`
 * - Y: `[0, viewportHeight]` → `[+1, -1]` (WebGPU y-up, engine y-down)
 *
 * ## Uniform buffer
 *
 * A single `Uniforms` struct at `@group(0) @binding(0)`:
 * - `viewportSize: vec2<f32>` — surface width × height in pixels
 * - `_padding: vec2<f32>` — 16-byte alignment for uniform buffer
 */
internal object IsometricVertexShader {

    const val ENTRY_POINT = "vertexMain"

    val WGSL: String = """
        struct Vertex {
            @location(0) position: vec2<f32>,
            @location(1) color:    vec4<f32>,
            @location(2) uv:       vec2<f32>,
        }

        struct VertexOutput {
            @builtin(position) clipPosition: vec4<f32>,
            @location(0) color: vec4<f32>,
            @location(1) uv:    vec2<f32>,
        }

        struct Uniforms {
            viewportSize: vec2<f32>,
            _padding:     vec2<f32>,
        }

        @group(0) @binding(0) var<uniform> uniforms: Uniforms;

        @vertex
        fn ${ENTRY_POINT}(in: Vertex) -> VertexOutput {
            // Convert pixel coords to NDC: x in [-1, 1], y in [-1, 1]
            // WebGPU clip space: x right, y up, z into screen
            // Engine screen space: x right, y down (standard screen coords)
            let ndc = vec2<f32>(
                (in.position.x / uniforms.viewportSize.x) * 2.0 - 1.0,
                1.0 - (in.position.y / uniforms.viewportSize.y) * 2.0
            );
            var out: VertexOutput;
            out.clipPosition = vec4<f32>(ndc, 0.0, 1.0);
            out.color        = in.color;
            out.uv           = in.uv;
            return out;
        }
    """.trimIndent()
}
```

---

## 4. WGSL Fragment Shader

### New file: `isometric-webgpu/.../shader/IsometricFragmentShader.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.shader

/**
 * WGSL fragment shader for Phase 2 isometric rendering.
 *
 * Phase 2 is solid color only — returns the interpolated vertex color directly.
 * Phase 3 will add `textureIndex`, a texture atlas sampler, and texture/color blending.
 */
internal object IsometricFragmentShader {

    const val ENTRY_POINT = "fragmentMain"

    val WGSL: String = """
        struct FragmentInput {
            @location(0) color: vec4<f32>,
            @location(1) uv:    vec2<f32>,
        }

        @fragment
        fn ${ENTRY_POINT}(in: FragmentInput) -> @location(0) vec4<f32> {
            // Phase 2: solid color only.
            // Phase 3 will add texture sampling: sample(atlas, in.uv) * in.color
            return in.color;
        }
    """.trimIndent()
}
```

---

## 5. RenderCommandTriangulator

### New file: `isometric-webgpu/.../triangulation/RenderCommandTriangulator.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.triangulation

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point2D
import io.github.jayteealao.isometric.RenderCommand
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Converts [RenderCommand] polygons (N-gons) into a flat vertex array for GPU upload.
 *
 * All isometric faces are convex polygons — guaranteed by orthographic projection of
 * convex 3D faces. A convex polygon triangulates correctly as a triangle fan from vertex 0:
 * `(0,1,2), (0,2,3), ..., (0,n-2,n-1)` → `n-2` triangles for an n-gon.
 *
 * ## Output format
 *
 * Each vertex is 8 floats (32 bytes):
 * - position: 2 floats (screen-space XY)
 * - color: 4 floats (RGBA in [0,1])
 * - uv: 2 floats (reserved for Phase 3)
 *
 * `textureIndex` is NOT included — deferred to Phase 3.
 *
 * ## No RenderCommand changes
 *
 * `RenderCommand` is not modified in WS12. The current class has:
 * ```kotlin
 * class RenderCommand(
 *     val commandId: String,
 *     val points: List<Point2D>,   // 2D screen-space polygon vertices
 *     val color: IsoColor,         // RGBA color with lighting applied
 *     val originalPath: Path,
 *     val originalShape: Shape?,
 *     val ownerNodeId: String?,
 * )
 * ```
 */
internal object RenderCommandTriangulator {

    /** Floats per vertex: xy(2) + rgba(4) + uv(2) */
    const val FLOATS_PER_VERTEX = 8

    /**
     * Triangulate all commands into a flat float array for GPU vertex buffer upload.
     *
     * @param commands Sorted render commands from [PreparedScene.commands].
     * @return Float array of interleaved vertex data. Length is a multiple of [FLOATS_PER_VERTEX].
     */
    fun triangulate(commands: List<RenderCommand>): FloatArray {
        // Pre-calculate total vertex count for single allocation
        var totalTriangles = 0
        for (cmd in commands) {
            val n = cmd.points.size
            if (n >= 3) totalTriangles += (n - 2)
        }

        val result = FloatArray(totalTriangles * 3 * FLOATS_PER_VERTEX)
        var offset = 0

        for (cmd in commands) {
            val pts = cmd.points
            val n = pts.size
            if (n < 3) continue

            // IsoColor stores components as Double in [0, 255].
            // GPU shaders expect [0, 1] floats.
            val color = cmd.color.toGpuColorArray()
            val uvs = defaultUvs(n)

            // Triangle fan from vertex 0: (0,1,2), (0,2,3), ..., (0,n-2,n-1)
            for (i in 1 until n - 1) {
                offset = appendVertex(result, offset, pts[0], color, uvs[0])
                offset = appendVertex(result, offset, pts[i], color, uvs[i])
                offset = appendVertex(result, offset, pts[i + 1], color, uvs[i + 1])
            }
        }

        return result
    }

    /**
     * Count the total number of triangulated vertices for a list of commands.
     * Useful for pre-allocating GPU buffer capacity.
     */
    fun countVertices(commands: List<RenderCommand>): Int {
        var count = 0
        for (cmd in commands) {
            val n = cmd.points.size
            if (n >= 3) count += (n - 2) * 3
        }
        return count
    }

    /**
     * Append a single vertex to the result array.
     *
     * @return The new offset after this vertex.
     */
    private fun appendVertex(
        out: FloatArray,
        offset: Int,
        pt: Point2D,
        color: FloatArray, // [r, g, b, a] in [0, 1]
        uv: FloatArray,    // [u, v]
    ): Int {
        out[offset + 0] = pt.x.toFloat()  // position.x
        out[offset + 1] = pt.y.toFloat()  // position.y
        out[offset + 2] = color[0]        // color.r
        out[offset + 3] = color[1]        // color.g
        out[offset + 4] = color[2]        // color.b
        out[offset + 5] = color[3]        // color.a
        out[offset + 6] = uv[0]           // uv.u
        out[offset + 7] = uv[1]           // uv.v
        return offset + FLOATS_PER_VERTEX
    }

    /**
     * Default UV layout for a convex N-gon.
     *
     * Vertices are distributed evenly on a unit circle centered at (0.5, 0.5).
     * This provides reasonable UV mapping for flat-colored faces and will serve
     * as default UVs when texture support is added in Phase 3.
     */
    private fun defaultUvs(n: Int): Array<FloatArray> = Array(n) { i ->
        val angle = (2.0 * PI * i / n).toFloat()
        floatArrayOf(0.5f + 0.5f * cos(angle), 0.5f + 0.5f * sin(angle))
    }
}

/**
 * Convert [IsoColor] to GPU-friendly [0, 1] float array.
 *
 * [IsoColor] stores RGBA components as `Double` in [0, 255] range.
 * GPU shaders expect [0, 1] floats.
 */
internal fun IsoColor.toGpuColorArray(): FloatArray = floatArrayOf(
    (r / 255.0).toFloat().coerceIn(0f, 1f),
    (g / 255.0).toFloat().coerceIn(0f, 1f),
    (b / 255.0).toFloat().coerceIn(0f, 1f),
    (a / 255.0).toFloat().coerceIn(0f, 1f),
)
```

---

## 6. GpuVertexBuffer — Buffer Management

### New file: `isometric-webgpu/.../pipeline/GpuVertexBuffer.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.pipeline

import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUQueue
import io.github.jayteealao.isometric.webgpu.triangulation.RenderCommandTriangulator
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages the GPU vertex buffer for isometric scene geometry.
 *
 * Handles:
 * - Buffer creation with appropriate size
 * - Buffer growth when vertex count increases (recreates with larger capacity)
 * - Float array → ByteBuffer conversion for [GPUQueue.writeBuffer]
 * - Cleanup via [destroy]
 *
 * ## API notes
 *
 * - [GPUQueue.writeBuffer] accepts `ByteBuffer`, NOT `FloatArray` — must convert.
 * - [GPUBufferDescriptor] constructor: `usage: Int` (required, IntDef), `size: Long` (required).
 * - [BufferUsage] is IntDef — combine with bitwise OR: `BufferUsage.Vertex or BufferUsage.CopyDst`.
 */
internal class GpuVertexBuffer(
    private val device: GPUDevice,
    private val queue: GPUQueue,
) {
    private var buffer: GPUBuffer? = null
    private var currentCapacityBytes: Long = 0

    /** Number of vertices currently in the buffer. */
    var vertexCount: Int = 0
        private set

    /**
     * Upload vertex data to the GPU buffer.
     *
     * If the buffer is too small (or doesn't exist yet), creates a new one with
     * sufficient capacity. Grows by 1.5x to reduce frequent reallocations.
     *
     * @param vertexData Interleaved vertex data from [RenderCommandTriangulator.triangulate].
     */
    fun upload(vertexData: FloatArray) {
        vertexCount = vertexData.size / RenderCommandTriangulator.FLOATS_PER_VERTEX
        val requiredBytes = (vertexData.size * Float.SIZE_BYTES).toLong()

        if (requiredBytes == 0L) return

        // Grow buffer if needed (1.5x growth factor to reduce churn)
        if (buffer == null || requiredBytes > currentCapacityBytes) {
            buffer?.destroy()
            val newCapacity = maxOf(requiredBytes, (currentCapacityBytes * 3 / 2))
            buffer = device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Vertex or BufferUsage.CopyDst,
                    size = newCapacity,
                )
            )
            currentCapacityBytes = newCapacity
        }

        // GPUQueue.writeBuffer(buffer, bufferOffset, data: ByteBuffer)
        // Must convert FloatArray → ByteBuffer.
        val byteBuffer = ByteBuffer
            .allocateDirect(vertexData.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        byteBuffer.asFloatBuffer().put(vertexData)
        byteBuffer.rewind()

        queue.writeBuffer(buffer!!, 0L, byteBuffer)
    }

    /**
     * Get the underlying GPU buffer for binding to a render pass.
     *
     * @throws IllegalStateException if no data has been uploaded yet.
     */
    fun getBuffer(): GPUBuffer {
        return buffer ?: throw IllegalStateException("No vertex data uploaded yet")
    }

    /**
     * Release the GPU buffer. Safe to call multiple times.
     */
    fun destroy() {
        buffer?.destroy()
        buffer = null
        currentCapacityBytes = 0
        vertexCount = 0
    }
}
```

---

## 7. GpuRenderPipeline — Pipeline State

### New file: `isometric-webgpu/.../pipeline/GpuRenderPipeline.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.pipeline

import androidx.webgpu.BufferBindingType
import androidx.webgpu.ColorWriteMask
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUBlendComponent
import androidx.webgpu.GPUBlendState
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferBindingLayout
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUPipelineLayout
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.GPUQueue
import androidx.webgpu.GPURenderPipeline
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUVertexAttribute
import androidx.webgpu.GPUVertexBufferLayout
import androidx.webgpu.GPUVertexState
import androidx.webgpu.BufferUsage
import androidx.webgpu.BlendFactor
import androidx.webgpu.BlendOperation
import androidx.webgpu.PrimitiveTopology
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.ShaderStage
import androidx.webgpu.TextureFormat
import androidx.webgpu.VertexFormat
import androidx.webgpu.VertexStepMode
import io.github.jayteealao.isometric.webgpu.GpuContext
import io.github.jayteealao.isometric.webgpu.shader.IsometricFragmentShader
import io.github.jayteealao.isometric.webgpu.shader.IsometricVertexShader
import io.github.jayteealao.isometric.webgpu.triangulation.RenderCommandTriangulator
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Creates and manages the WebGPU render pipeline for isometric scenes.
 *
 * Encapsulates:
 * - Shader module creation (vertex + fragment)
 * - Vertex buffer layout definition (matches [RenderCommandTriangulator] output)
 * - Uniform buffer for viewport size
 * - Bind group layout and bind group
 * - Pipeline state object
 *
 * ## Vertex layout (must match WGSL struct exactly)
 *
 * ```
 * @location(0) position: vec2<f32>  → offset 0,  format Float32x2
 * @location(1) color:    vec4<f32>  → offset 8,  format Float32x4
 * @location(2) uv:       vec2<f32>  → offset 24, format Float32x2
 * stride = 32 bytes (8 floats × 4 bytes)
 * ```
 *
 * ## API accuracy notes
 *
 * - [GPUShaderModuleDescriptor] has no `code` field — WGSL goes in `GPUShaderSourceWGSL(code=...)`.
 * - [GPUVertexAttribute]: all 3 params required (format, offset, shaderLocation). No defaults.
 * - [GPURenderPipelineDescriptor]: `vertex` is required; `fragment` is optional.
 * - [GPUFragmentState.targets] is `Array<GPUColorTargetState>`, not List.
 * - [GPUVertexState.buffers] is `Array<GPUVertexBufferLayout>`, not List.
 * - [GPUBindGroupLayoutEntry]: `binding` and `visibility` are required.
 * - All IntDef enums have no GPU prefix: `VertexFormat`, `TextureFormat`, `ShaderStage`, etc.
 */
internal class GpuRenderPipeline(
    private val ctx: GpuContext,
    private val surfaceFormat: Int, // TextureFormat IntDef
) {
    val pipeline: GPURenderPipeline
    val uniformBindGroup: GPUBindGroup
    private val uniformBuffer: GPUBuffer
    private val bindGroupLayout: GPUBindGroupLayout

    init {
        // --- Shader modules ---
        // GPUShaderModuleDescriptor has NO `code` field.
        // WGSL source is nested inside GPUShaderSourceWGSL.
        val vertexShaderModule = ctx.device.createShaderModule(
            GPUShaderModuleDescriptor(
                shaderSourceWGSL = GPUShaderSourceWGSL(code = IsometricVertexShader.WGSL)
            )
        )
        val fragmentShaderModule = ctx.device.createShaderModule(
            GPUShaderModuleDescriptor(
                shaderSourceWGSL = GPUShaderSourceWGSL(code = IsometricFragmentShader.WGSL)
            )
        )

        // --- Vertex buffer layout ---
        // Must match RenderCommandTriangulator output exactly.
        // GPUVertexAttribute: ALL 3 params are required (no defaults).
        val vertexBufferLayout = GPUVertexBufferLayout(
            arrayStride = (RenderCommandTriangulator.FLOATS_PER_VERTEX * Float.SIZE_BYTES).toLong(),
            stepMode = VertexStepMode.Vertex,
            attributes = arrayOf(
                // @location(0) position: vec2<f32>
                GPUVertexAttribute(
                    format = VertexFormat.Float32x2,
                    offset = 0L,
                    shaderLocation = 0,
                ),
                // @location(1) color: vec4<f32>
                GPUVertexAttribute(
                    format = VertexFormat.Float32x4,
                    offset = (2 * Float.SIZE_BYTES).toLong(), // after position (2 floats)
                    shaderLocation = 1,
                ),
                // @location(2) uv: vec2<f32>
                GPUVertexAttribute(
                    format = VertexFormat.Float32x2,
                    offset = (6 * Float.SIZE_BYTES).toLong(), // after position(2) + color(4)
                    shaderLocation = 2,
                ),
            )
        )

        // --- Uniform buffer ---
        // Uniforms struct: vec2<f32> viewportSize + vec2<f32> _padding = 16 bytes
        uniformBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Uniform or BufferUsage.CopyDst,
                size = 16L, // 4 floats × 4 bytes
            )
        )

        // --- Bind group layout ---
        // @group(0) @binding(0): uniform buffer (Uniforms)
        bindGroupLayout = ctx.device.createBindGroupLayout(
            GPUBindGroupLayoutDescriptor(
                entries = arrayOf(
                    GPUBindGroupLayoutEntry(
                        binding = 0,
                        visibility = ShaderStage.Vertex,
                        buffer = GPUBufferBindingLayout(type = BufferBindingType.Uniform),
                    )
                )
            )
        )

        // --- Bind group ---
        uniformBindGroup = ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = bindGroupLayout,
                entries = arrayOf(
                    GPUBindGroupEntry(
                        binding = 0,
                        buffer = uniformBuffer,
                    )
                )
            )
        )

        // --- Pipeline layout ---
        val pipelineLayout = ctx.device.createPipelineLayout(
            GPUPipelineLayoutDescriptor(
                bindGroupLayouts = arrayOf(bindGroupLayout)
            )
        )

        // --- Render pipeline ---
        // GPURenderPipelineDescriptor: vertex is required, fragment is optional.
        // primitive defaults to GPUPrimitiveState() which uses TriangleList topology.
        pipeline = ctx.device.createRenderPipeline(
            GPURenderPipelineDescriptor(
                vertex = GPUVertexState(
                    module = vertexShaderModule,
                    entryPoint = IsometricVertexShader.ENTRY_POINT,
                    buffers = arrayOf(vertexBufferLayout),
                ),
                fragment = GPUFragmentState(
                    module = fragmentShaderModule,
                    entryPoint = IsometricFragmentShader.ENTRY_POINT,
                    // targets is Array<GPUColorTargetState>, NOT List
                    targets = arrayOf(
                        GPUColorTargetState(
                            format = surfaceFormat,
                            blend = GPUBlendState(
                                color = GPUBlendComponent(
                                    srcFactor = BlendFactor.SrcAlpha,
                                    dstFactor = BlendFactor.OneMinusSrcAlpha,
                                    operation = BlendOperation.Add,
                                ),
                                alpha = GPUBlendComponent(
                                    srcFactor = BlendFactor.One,
                                    dstFactor = BlendFactor.OneMinusSrcAlpha,
                                    operation = BlendOperation.Add,
                                ),
                            ),
                            writeMask = ColorWriteMask.All,
                        )
                    ),
                ),
                primitive = GPUPrimitiveState(
                    topology = PrimitiveTopology.TriangleList,
                ),
                layout = pipelineLayout,
            )
        )
    }

    /**
     * Upload viewport size to the uniform buffer.
     *
     * Must be called after surface creation and on every resize.
     */
    fun updateViewportSize(width: Float, height: Float) {
        // Uniforms struct: vec2<f32> viewportSize + vec2<f32> _padding
        val data = ByteBuffer.allocateDirect(16)
            .order(ByteOrder.nativeOrder())
        data.putFloat(width)
        data.putFloat(height)
        data.putFloat(0f) // padding
        data.putFloat(0f) // padding
        data.rewind()

        // GPUQueue.writeBuffer(buffer, bufferOffset, data: ByteBuffer)
        ctx.queue.writeBuffer(uniformBuffer, 0L, data)
    }

    /**
     * Release the uniform buffer. Pipeline and bind group layout are owned by the device
     * and don't need explicit cleanup.
     */
    fun destroy() {
        uniformBuffer.destroy()
    }
}
```

---

## 8. WebGpuSceneRenderer

### New file: `isometric-webgpu/.../WebGpuSceneRenderer.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu

import android.view.Surface
import androidx.webgpu.CompositeAlphaMode
import androidx.webgpu.GPUColor
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPUSurface
import androidx.webgpu.GPUSurfaceConfiguration
import androidx.webgpu.GPUSurfaceDescriptor
import androidx.webgpu.GPUSurfaceSourceAndroidNativeWindow
import androidx.webgpu.LoadOp
import androidx.webgpu.StoreOp
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import androidx.webgpu.helper.Util.windowFromSurface
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.webgpu.pipeline.GpuRenderPipeline
import io.github.jayteealao.isometric.webgpu.pipeline.GpuVertexBuffer
import io.github.jayteealao.isometric.webgpu.sort.GpuDepthSorter
import io.github.jayteealao.isometric.webgpu.triangulation.RenderCommandTriangulator

/**
 * Manages the WebGPU surface lifecycle, geometry updates, and frame rendering.
 *
 * ## Lifecycle
 *
 * 1. [init] — creates the GPU surface, queries format capabilities, configures the surface,
 *    builds the render pipeline, and uploads initial viewport uniforms.
 * 2. [updateGeometry] — triangulates a [PreparedScene] snapshot and uploads to vertex buffer.
 *    Safe to call from any coroutine context (operates on immutable data).
 * 3. [drawFrame] — submits a render pass command buffer. Must be called on the frame-clock
 *    coroutine provided by `AndroidExternalSurface.onSurface`.
 * 4. [resize] — reconfigures the surface for new dimensions.
 * 5. [destroy] — releases all GPU resources.
 *
 * ## Ownership contract
 *
 * This class does NOT own hit testing. Hit testing is handled by [IsometricScene] via its
 * existing pointer-input block and [IsometricRenderer.hitTest] (instance method).
 * The WebGPU backend does not participate in hit testing in any phase.
 *
 * ## API accuracy notes
 *
 * - Surface creation: `GPUInstance.createSurface(GPUSurfaceDescriptor(...))` — no overload
 *   accepts `android.view.Surface` directly. Must use `Util.windowFromSurface()` to get
 *   the `ANativeWindow` pointer.
 * - Surface format: query `gpuSurface.getCapabilities(adapter).formats` (IntArray), don't
 *   hardcode `TextureFormat.BGRA8Unorm`.
 * - `GPUColor` uses `Double` (r, g, b, a), not `Float`.
 * - `colorAttachments` in `GPURenderPassDescriptor` is `Array`, not `List`.
 * - `queue.submit()` takes `Array<GPUCommandBuffer>`, not `List`.
 * - `gpuSurface.present()` pushes the frame. `surfaceTexture.texture.destroy()` would
 *   DISCARD the frame — never call it.
 * - LoadOp, StoreOp, TextureFormat, TextureUsage, CompositeAlphaMode are all IntDef — no GPU prefix.
 */
internal class WebGpuSceneRenderer(
    private val ctx: GpuContext,
    private val depthSorter: GpuDepthSorter,
) {
    private lateinit var gpuSurface: GPUSurface
    private lateinit var renderPipeline: GpuRenderPipeline
    private lateinit var vertexBuffer: GpuVertexBuffer
    private var surfaceFormat: Int = TextureFormat.Undefined
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    /**
     * Initialize the GPU surface, pipeline, and buffers.
     *
     * @param androidSurface The Android surface from `AndroidExternalSurface.onSurface`.
     * @param width Surface width in pixels.
     * @param height Surface height in pixels.
     */
    fun init(androidSurface: Surface, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height

        // --- Create GPU surface ---
        // GPUInstance has no overload that accepts android.view.Surface directly.
        // Must use Util.windowFromSurface() (JNI) to get ANativeWindow pointer.
        val nativeWindow = windowFromSurface(androidSurface)
        gpuSurface = ctx.instance.createSurface(
            GPUSurfaceDescriptor(
                surfaceSourceAndroidNativeWindow = GPUSurfaceSourceAndroidNativeWindow(nativeWindow)
            )
        )

        // --- Query surface capabilities ---
        // Never hardcode TextureFormat.BGRA8Unorm — query capabilities first.
        // GPUSurfaceCapabilities.formats is IntArray, not List<Int>.
        // IntArray.contains() works with Kotlin's `in` operator.
        val caps = gpuSurface.getCapabilities(ctx.adapter)
        surfaceFormat = if (TextureFormat.BGRA8Unorm in caps.formats) {
            TextureFormat.BGRA8Unorm
        } else {
            caps.formats.first()
        }

        // --- Configure surface ---
        // GPUSurfaceConfiguration constructor:
        //   device (required), width (required), height (required),
        //   format, usage, viewFormats, alphaMode, presentMode
        // TextureUsage, CompositeAlphaMode are IntDef — no GPU prefix.
        gpuSurface.configure(
            GPUSurfaceConfiguration(
                device = ctx.device,
                width = width,
                height = height,
                format = surfaceFormat,
                usage = TextureUsage.RenderAttachment,
                alphaMode = CompositeAlphaMode.Opaque,
            )
        )

        // --- Build render pipeline ---
        renderPipeline = GpuRenderPipeline(ctx, surfaceFormat)
        renderPipeline.updateViewportSize(width.toFloat(), height.toFloat())

        // --- Create vertex buffer manager ---
        vertexBuffer = GpuVertexBuffer(ctx.device, ctx.queue)
    }

    /**
     * Handle surface resize (rotation, window resize).
     *
     * Reconfigures the GPU surface and updates the viewport uniform.
     */
    fun resize(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height

        gpuSurface.configure(
            GPUSurfaceConfiguration(
                device = ctx.device,
                width = width,
                height = height,
                format = surfaceFormat,
                usage = TextureUsage.RenderAttachment,
                alphaMode = CompositeAlphaMode.Opaque,
            )
        )

        renderPipeline.updateViewportSize(width.toFloat(), height.toFloat())
    }

    /**
     * Triangulate a [PreparedScene] and upload geometry to the GPU vertex buffer.
     *
     * Safe to call from any coroutine context — operates on immutable [PreparedScene] data.
     *
     * [PreparedScene] is an immutable snapshot produced by [SceneCache.rebuild] on the
     * Compose side. Its field is `PreparedScene.commands: List<RenderCommand>` (see
     * isometric-core PreparedScene.kt).
     *
     * @param scene The immutable scene snapshot to triangulate and upload.
     */
    suspend fun updateGeometry(scene: PreparedScene) {
        // PreparedScene.commands is List<RenderCommand> — NOT renderCommands.
        val commands = scene.commands
        if (commands.isEmpty()) {
            vertexBuffer.vertexCount // leave at 0
            return
        }

        // Triangulate: N-gon polygons → flat float array of triangle vertices.
        // Each vertex is 8 floats: xy(2) + rgba(4) + uv(2).
        val vertexData = RenderCommandTriangulator.triangulate(commands)

        // Upload to GPU. GpuVertexBuffer handles buffer growth internally.
        vertexBuffer.upload(vertexData)
    }

    /**
     * Submit a render pass to draw the current geometry.
     *
     * Must be called on the frame-clock coroutine provided by
     * `AndroidExternalSurface.onSurface`. Specifically, this runs inside
     * `withFrameNanos { }` which is dispatched by the Choreographer-backed
     * coroutine context.
     */
    fun drawFrame() {
        if (vertexBuffer.vertexCount == 0) return

        // --- Get current surface texture ---
        // getCurrentTexture() returns GPUSurfaceTexture (non-null).
        // Access the underlying GPUTexture via .texture, not via direct cast.
        val surfaceTexture = gpuSurface.getCurrentTexture()
        val textureView = surfaceTexture.texture.createView()

        // --- Create command encoder ---
        // GPUCommandEncoderDescriptor is optional — can omit.
        val encoder = ctx.device.createCommandEncoder()

        // --- Begin render pass ---
        // colorAttachments is Array<GPURenderPassColorAttachment>, NOT List — use arrayOf().
        // GPUColor uses Double (r, g, b, a), not Float.
        // LoadOp, StoreOp are IntDef — no GPU prefix.
        val renderPass = encoder.beginRenderPass(
            GPURenderPassDescriptor(
                colorAttachments = arrayOf(
                    GPURenderPassColorAttachment(
                        view = textureView,
                        clearValue = GPUColor(r = 0.0, g = 0.0, b = 0.0, a = 0.0),
                        loadOp = LoadOp.Clear,
                        storeOp = StoreOp.Store,
                    )
                )
            )
        )

        // --- Draw ---
        renderPass.setPipeline(renderPipeline.pipeline)
        renderPass.setBindGroup(0, renderPipeline.uniformBindGroup)
        renderPass.setVertexBuffer(0, vertexBuffer.getBuffer())
        // draw(vertexCount, instanceCount=1, firstVertex=0, firstInstance=0)
        renderPass.draw(vertexBuffer.vertexCount)
        renderPass.end()

        // --- Submit ---
        // queue.submit() takes Array<GPUCommandBuffer>, NOT List — use arrayOf().
        ctx.queue.submit(arrayOf(encoder.finish()))

        // --- Present ---
        // present() pushes the rendered frame to the display surface.
        // surfaceTexture.texture.destroy() would DISCARD the frame — do NOT call it.
        gpuSurface.present()
    }

    /**
     * Release all GPU resources. Safe to call multiple times.
     */
    fun destroy() {
        if (::vertexBuffer.isInitialized) vertexBuffer.destroy()
        if (::renderPipeline.isInitialized) renderPipeline.destroy()
        if (::gpuSurface.isInitialized) gpuSurface.unconfigure()
    }
}
```

---

## 9. WebGpuRenderBackend

### New file: `isometric-webgpu/.../RenderBackendWebGpu.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu

import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import io.github.jayteealao.isometric.compose.runtime.StrokeStyle
import io.github.jayteealao.isometric.compose.runtime.render.RenderBackend
import io.github.jayteealao.isometric.webgpu.sort.GpuDepthSorter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebGPU-based [RenderBackend] implementation.
 *
 * Renders isometric scenes using WebGPU via an [AndroidExternalSurface]. The surface
 * provides a Choreographer-backed frame loop via `onSurface { withFrameNanos { } }`.
 *
 * ## Architecture
 *
 * ```
 * Box(modifier)                      ← caller's modifier applied here (no .fillMaxSize())
 * └── AndroidExternalSurface         ← WebGPU rendering surface (fills the Box)
 *     └── onSurface { ... }          ← frame loop
 *         ├── renderer.init(...)     ← one-time GPU surface + pipeline setup
 *         └── while(true) {
 *             withFrameNanos {       ← Choreographer-dispatched frame callback
 *                 if (snapshot changed) {
 *                     withContext(Dispatchers.Default) {
 *                         renderer.updateGeometry(snapshot)  ← triangulation + upload
 *                     }
 *                 }
 *                 renderer.drawFrame()  ← submit command buffer
 *             }
 *         }
 * ```
 *
 * ## Ownership contract
 *
 * - **No rootNode access.** IsometricScene owns the node tree and prepare lifecycle.
 * - **No onDirty wiring.** That stays in IsometricScene's DisposableEffect.
 * - **No hit testing.** That stays in IsometricScene's pointer-input block.
 *   [IsometricRenderer.hitTest] is an instance method, not a static helper.
 * - **`modifier` passed as-is.** No `.fillMaxSize()` on the outer Box — respects
 *   caller sizing per the WS2 contract.
 *
 * ## Frame loop threading
 *
 * `withFrameNanos` MUST remain on the frame-clock coroutine provided by
 * `AndroidExternalSurface.onSurface`. Never wrap the entire frame loop in
 * `withContext(Dispatchers.Default)` — `withFrameNanos` requires the
 * Choreographer-backed coroutine context and will deadlock otherwise.
 *
 * Only CPU-bound work (triangulation, buffer packing) is offloaded to
 * `Dispatchers.Default` inside the `withFrameNanos` callback.
 *
 * @param gpuContext The GPU context from WS11 providing device, queue, and event polling.
 */
class WebGpuRenderBackend(
    private val gpuContext: GpuContext,
) : RenderBackend {

    @Composable
    override fun Surface(
        preparedScene: State<PreparedScene?>,
        renderContext: RenderContext,
        modifier: Modifier,
        strokeStyle: StrokeStyle, // Phase 2: ignored — WebGPU draws filled faces only
    ) {
        val depthSorter = remember { GpuDepthSorter(gpuContext) }
        val renderer = remember { WebGpuSceneRenderer(gpuContext, depthSorter) }

        // modifier is applied to the outer Box — NOT the AndroidExternalSurface.
        // The caller controls sizing via their modifier. The Box does not add
        // .fillMaxSize() — that would regress the WS2 sizing contract.
        Box(modifier = modifier) {
            // AndroidExternalSurface fills the Box (within the Box's constraints).
            AndroidExternalSurface(modifier = Modifier.fillMaxSize()) {
                onSurface { surface, width, height ->
                    // GPU init is safe to offload — no frame-clock dependency.
                    withContext(Dispatchers.Default) {
                        renderer.init(surface, width, height)
                    }

                    // IMPORTANT: withFrameNanos MUST remain on the frame-clock coroutine
                    // provided by AndroidExternalSurface's onSurface block. Never wrap
                    // the entire frame loop in withContext(Dispatchers.Default) —
                    // withFrameNanos requires the Choreographer-backed coroutine context
                    // that onSurface provides, and will deadlock or miss frames otherwise.
                    try {
                        var lastSnapshot: PreparedScene? = null
                        while (true) {
                            withFrameNanos { _ ->
                                val snapshot = preparedScene.value
                                if (snapshot != null && snapshot !== lastSnapshot) {
                                    // Triangulation + GPU upload are CPU/GPU-bound; offload.
                                    withContext(Dispatchers.Default) {
                                        renderer.updateGeometry(snapshot)
                                    }
                                    lastSnapshot = snapshot
                                }
                                renderer.drawFrame()
                            }
                        }
                    } finally {
                        renderer.destroy()
                    }
                }

                onSurfaceChanged { width, height ->
                    renderer.resize(width, height)
                }
            }

            // ── Hit testing ──────────────────────────────────────────────────
            // The WebGPU backend does NOT own hit testing. IsometricScene's existing
            // pointer-input block (with camera-inverse transform — see IsometricScene.kt
            // lines 332–343) handles all tap/drag events and routes them through
            // renderer.hitTest() (instance method on IsometricRenderer, not static).
            //
            // The pointer-input modifier is installed on the Canvas composable that wraps
            // the backend's Surface call in IsometricScene. Since this Box is a child of
            // that outer composable, pointer events are captured by the parent first.
            //
            // If the AndroidExternalSurface obscures the parent's pointer input,
            // add a zero-alpha Box overlay here that forwards events to IsometricScene.
        }
    }

    override fun toString(): String = "RenderBackend.WebGpu"
}
```

---

## 10. RenderBackend.WebGpu Extension

### New file: `isometric-webgpu/.../RenderBackendExtensions.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu

import io.github.jayteealao.isometric.compose.runtime.render.RenderBackend

/**
 * WebGPU-based render backend.
 *
 * Available only when the `isometric-webgpu` artifact is on the classpath.
 * If absent, `RenderBackend.WebGpu` is a compile error — not a runtime crash.
 *
 * ## Usage
 *
 * ```kotlin
 * IsometricScene(config = SceneConfig(renderBackend = RenderBackend.WebGpu)) { ... }
 * ```
 *
 * This follows the extension-on-companion-object pattern: reads identically to
 * `RenderBackend.Canvas` at the call site, with zero magic.
 *
 * Note: This extension creates a new [GpuContext] lazily on first use. For sharing a
 * single GPU context across multiple scenes, inject a [WebGpuRenderBackend] directly:
 *
 * ```kotlin
 * val sharedContext = remember { GpuContext.create() }
 * val backend = remember { WebGpuRenderBackend(sharedContext) }
 * IsometricScene(config = SceneConfig(renderBackend = backend)) { ... }
 * ```
 */
val RenderBackend.Companion.WebGpu: RenderBackend
    get() {
        // Lazy context creation — the GpuContext is created on the first frame
        // when AndroidExternalSurface's onSurface coroutine runs.
        // For now, create synchronously in a blocking way. In production, consider
        // lazy suspend initialization inside the backend.
        //
        // TODO(ws12): Consider lazy suspend init pattern for GpuContext.
        // For the initial implementation, the context is created eagerly when
        // this property is accessed. The caller should memoize the result:
        //   val backend = remember { RenderBackend.WebGpu }
        return WebGpuRenderBackend(
            gpuContext = LazyGpuContext()
        )
    }

/**
 * Lazy GPU context wrapper that defers initialization to first use.
 *
 * This avoids calling `GpuContext.create()` (which is suspend) at property-access time.
 * The actual initialization happens inside [WebGpuSceneRenderer.init] via a suspend call.
 */
internal class LazyGpuContext : GpuContext {
    // Implementation note: This is a design placeholder. The actual implementation
    // should either:
    // (a) Make WebGpuRenderBackend accept a suspend factory for GpuContext, or
    // (b) Create GpuContext inside the AndroidExternalSurface.onSurface coroutine
    //     (which provides a suspend context).
    //
    // Option (b) is cleaner and matches the current architecture:
    // WebGpuRenderBackend.Surface() → AndroidExternalSurface.onSurface { ... } →
    //   GpuContext.create() → renderer.init(...)
}
```

**Implementation note**: The extension property design above has a tension between the
synchronous property access pattern and `GpuContext.create()` being a suspend function.
The recommended resolution is to move `GpuContext` creation into the `onSurface` coroutine:

```kotlin
// Preferred approach: create GpuContext inside the surface coroutine
class WebGpuRenderBackend : RenderBackend {
    @Composable
    override fun Surface(...) {
        AndroidExternalSurface(modifier = Modifier.fillMaxSize()) {
            onSurface { surface, width, height ->
                // GpuContext.create() is suspend — safe to call here.
                val gpuContext = GpuContext.create()
                val depthSorter = GpuDepthSorter(gpuContext)
                val renderer = WebGpuSceneRenderer(gpuContext, depthSorter)
                renderer.init(surface, width, height)
                try {
                    // ... frame loop ...
                } finally {
                    renderer.destroy()
                    gpuContext.destroy()
                }
            }
        }
    }
}
```

Then the extension property simplifies to:

```kotlin
val RenderBackend.Companion.WebGpu: RenderBackend
    get() = WebGpuRenderBackend()
```

---

## 11. Touch Bridge — Pointer Coordinate Contract

### Ownership

Hit testing is owned by `IsometricScene`, not by the backend. The existing pointer-input
block in `IsometricScene.kt` (lines 278–383) handles all tap/drag events and calls:

```kotlin
// IsometricScene.kt line 345-352 — source-accurate
val hitNode = renderer.hitTest(
    rootNode = rootNode,
    x = hitX,
    y = hitY,
    context = currentRenderContext,
    width = currentCanvasWidth,
    height = currentCanvasHeight
)
```

`renderer.hitTest()` is an **instance method** on `IsometricRenderer`, not a static helper.

### When the overlay is needed

When `WebGpuRenderBackend`'s `AndroidExternalSurface` obscures the parent `Canvas`'s
pointer input, an invisible overlay must be added. This overlay:

1. Captures events in **Compose layout coordinates (dp)**
2. Converts dp → px (`× LocalDensity.current.density`)
3. Applies camera inverse transform (when `AdvancedSceneConfig.cameraState` is non-null)
4. Forwards to the existing hit-test path

### Camera inverse transform (verbatim from IsometricScene.kt lines 332-343)

```kotlin
val camera = currentCameraState
val hitX: Double
val hitY: Double
if (camera != null) {
    val cx = currentCanvasWidth / 2.0
    val cy = currentCanvasHeight / 2.0
    hitX = (position.x.toDouble() - cx - camera.panX) / camera.zoom + cx
    hitY = (position.y.toDouble() - cy - camera.panY) / camera.zoom + cy
} else {
    hitX = position.x.toDouble()
    hitY = position.y.toDouble()
}
```

### Integration with IsometricScene

The recommended approach is to keep the pointer-input modifier on the outermost composable
that wraps the backend's `Surface()` call. Since `IsometricScene` applies `pointerInput`
on the `Canvas` today, and the `RenderBackend.Surface()` replaces the `Canvas`, the
pointer input should move to a wrapper around the `Surface()` call:

```kotlin
// In IsometricScene.kt (advanced overload) — after WS12 integration:
val gesturesActive = config.gestures.enabled || config.cameraState != null || tileGestureHub.hasHandlers

Box(
    modifier = modifier.then(
        if (gesturesActive) {
            Modifier.pointerInput(gesturesActive) {
                // ... existing pointer event handling unchanged ...
            }
        } else Modifier
    )
) {
    config.renderBackend.Surface(
        preparedScene = preparedScene,
        renderContext = renderContext,
        modifier = Modifier.fillMaxSize(), // fills the Box that has the caller's modifier
        strokeStyle = config.strokeStyle,
    )
}
```

This way the pointer-input captures all events regardless of which backend is active,
and the backend never needs its own overlay.

---

## 12. IsometricScene Integration

### Changes to `IsometricScene.kt` for RenderBackend support

The advanced overload gains a `preparedScene` state and routes drawing through the backend:

```kotlin
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    config: AdvancedSceneConfig,
    content: @Composable IsometricScope.() -> Unit
) {
    // ... existing setup through renderer creation unchanged ...

    // NEW: Published PreparedScene state for backends.
    val preparedScene = remember { mutableStateOf<PreparedScene?>(null) }

    // ... existing DisposableEffect, SideEffect, composition setup unchanged ...

    // Track canvas size (still needed for hit testing even with WebGPU backend)
    var canvasWidth by remember { mutableStateOf(0) }
    var canvasHeight by remember { mutableStateOf(0) }

    // Create render context (unchanged)
    val renderContext = remember(canvasWidth, canvasHeight, config.renderOptions, config.lightDirection) {
        RenderContext(
            width = canvasWidth,
            height = canvasHeight,
            renderOptions = config.renderOptions,
            lightDirection = config.lightDirection
        )
    }

    // ... existing DisposableEffect for hit-test/flags unchanged ...

    // Gesture handling wrapper — applies to ALL backends
    val gesturesActive = config.gestures.enabled || config.cameraState != null || tileGestureHub.hasHandlers

    Box(
        modifier = modifier.then(
            if (gesturesActive) {
                Modifier.pointerInput(gesturesActive) {
                    awaitPointerEventScope {
                        // ... existing pointer event handling unchanged ...
                        // Uses renderer.hitTest(rootNode, x, y, ...) — instance method
                    }
                }
            } else Modifier
        )
    ) {
        if (config.renderBackend is CanvasRenderBackend) {
            // CPU path: existing Canvas-based rendering (unchanged behavior)
            Canvas(modifier = Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION")
                sceneVersion
                @Suppress("UNUSED_EXPRESSION")
                config.frameVersion

                canvasWidth = size.width.toInt()
                canvasHeight = size.height.toInt()

                if (canvasWidth > 0 && canvasHeight > 0) {
                    // ... camera transforms, before/after draw hooks ...
                    with(renderer) {
                        if (config.useNativeCanvas) {
                            renderNative(rootNode = rootNode, context = renderContext, strokeStyle = config.strokeStyle)
                        } else {
                            render(rootNode = rootNode, context = renderContext, strokeStyle = config.strokeStyle)
                        }
                    }
                    // Publish prepared scene for backends/hooks
                    preparedScene.value = renderer.currentPreparedScene
                }
            }
        } else {
            // Non-Canvas backend: ensure prepared scene is built, then delegate to backend.
            // The backend handles its own surface lifecycle and frame loop.

            // Size tracking via onSizeChanged
            Box(modifier = Modifier.fillMaxSize().onSizeChanged { size ->
                canvasWidth = size.width
                canvasHeight = size.height
            })

            // Build prepared scene on dirty (same as Canvas path, but separated)
            LaunchedEffect(sceneVersion, canvasWidth, canvasHeight) {
                if (canvasWidth <= 0 || canvasHeight <= 0) return@LaunchedEffect
                val context = RenderContext(
                    width = canvasWidth,
                    height = canvasHeight,
                    renderOptions = config.renderOptions,
                    lightDirection = config.lightDirection
                )
                renderer.rebuildCache(rootNode, context, canvasWidth, canvasHeight)
                preparedScene.value = renderer.currentPreparedScene
            }

            // Delegate to backend
            config.renderBackend.Surface(
                preparedScene = preparedScene,
                renderContext = renderContext,
                modifier = Modifier.fillMaxSize(),
                strokeStyle = config.strokeStyle,
            )
        }
    }
}
```

---

## 13. IsoColor GPU Conversion

### New extension in `isometric-webgpu`

```kotlin
// In RenderCommandTriangulator.kt or a separate file

/**
 * Convert [IsoColor] to GPU-friendly [0, 1] float array.
 *
 * [IsoColor] stores RGBA components as `Double` in [0, 255] range.
 * GPU shaders expect [0, 1] normalized floats.
 *
 * The clamping ensures out-of-range values (e.g., from additive lighting calculations)
 * don't produce NaN or GPU artifacts.
 */
internal fun IsoColor.toGpuColorArray(): FloatArray = floatArrayOf(
    (r / 255.0).toFloat().coerceIn(0f, 1f),
    (g / 255.0).toFloat().coerceIn(0f, 1f),
    (b / 255.0).toFloat().coerceIn(0f, 1f),
    (a / 255.0).toFloat().coerceIn(0f, 1f),
)
```

---

## 14. Test Plan

### Unit Tests (JVM, no GPU)

| Test Class | Coverage |
|---|---|
| `RenderCommandTriangulatorTest` | Triangle: 1 triangle (3 verts). Quad: 2 triangles (6 verts). Pentagon: 3 triangles (9 verts). Empty commands → empty array. |
| `RenderCommandTriangulatorVertexFormatTest` | Output floats-per-vertex == 8. Position at offset 0-1, color at 2-5, UV at 6-7. |
| `IsoColorToGpuTest` | `IsoColor(255, 0, 128, 255)` → `[1.0, 0.0, ~0.502, 1.0]`. Out-of-range clamped. |
| `IsometricVertexShaderTest` | WGSL string contains `vertexMain` entry point and correct struct layout. |
| `IsometricFragmentShaderTest` | WGSL string contains `fragmentMain` entry point. |
| `GpuVertexBufferGrowthTest` | Buffer grows when vertex count increases; doesn't shrink. |

### Instrumented Tests (physical device, GPU required)

| Test Class | Coverage |
|---|---|
| `WebGpuSmokeTest` | `IsometricScene(config = SceneConfig(renderBackend = RenderBackend.WebGpu))` renders 3 shapes without crash |
| `WebGpuShaderCompileTest` | Both shader modules compile on real device via `device.createShaderModule()` |
| `WebGpuPipelineCreateTest` | `GpuRenderPipeline` creates successfully with queried surface format |
| `WebGpuResizeTest` | Surface resize mid-render doesn't crash or black-screen |
| `WebGpuFrameBudgetTest` | 1000-face scene: measure draw-path frame time; target hypothesis < 5ms p95 over 300 frames |
| `WebGpuTouchBridgeTest` | `onTap` and `onClick` fire correctly via overlay on WebGPU backend |

### Canvas Regression Guard

```bash
./gradlew :isometric-benchmark:connectedAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=CanvasRenderBenchmark
```

Must pass with < 5% regression on N=100 and N=200 prepare-time benchmarks.

### Device Matrix

| Device | GPU | Reason |
|---|---|---|
| Pixel 6 | Arm Mali-G78 | High-end ARM Mali, Vulkan 1.1 |
| Pixel 7 | Arm Mali-G710 | Current Tensor generation |
| Samsung Galaxy A55 | Exynos 1480 (Xclipse 540) | Mid-range ARM |
| Snapdragon 8 Gen device | Adreno 730+ | Qualcomm Vulkan path |

### Performance measurement protocol

Frame budget measurements should be reported as:
- **p50** (median) and **p95** (tail) frame times over 300 frames
- **First-frame latency** from `onSurface` to first `present()` call
- **Geometry upload time** for `updateGeometry()` on a 1000-face scene
- Compared against Canvas baseline (~22–29ms for 1000 faces)

The sub-5ms target is a **hypothesis to validate**, not a guaranteed outcome. Phase 2 still
includes CPU triangulation, `ByteBuffer` packing, and buffer uploads that may dominate on
some devices.
