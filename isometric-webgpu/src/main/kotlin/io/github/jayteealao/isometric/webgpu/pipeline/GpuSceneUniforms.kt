package io.github.jayteealao.isometric.webgpu.pipeline

import android.util.Log
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUQueue
import io.github.jayteealao.isometric.ProjectionParams
import io.github.jayteealao.isometric.Vector
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages the GPU uniform buffer for scene projection and lighting parameters.
 *
 * The buffer is allocated once at construction (`Uniform | CopyDst`, [UNIFORM_BYTES] bytes)
 * and updated via [update] before the Transform+Cull+Light compute pass whenever projection
 * parameters, viewport dimensions, light direction, or face count change.
 *
 * ## Buffer layout — WGSL struct `SceneUniforms` (96 bytes, 6 × vec4)
 *
 * ```
 * offset  size  field
 *   0      16   projRow0: vec4<f32>       — [t00, t10, 0, 0]
 *                 screen.x = originX + x*projRow0.x + y*projRow0.y
 *  16      16   projRow1: vec4<f32>       — [t01, t11, scale, 0]
 *                 screen.y = originY - x*projRow1.x - y*projRow1.y - z*projRow1.z
 *  32      16   origin:   vec4<f32>       — [originX, originY, 0, 0]
 *                 origin.x = viewportWidth  / 2.0
 *                 origin.y = viewportHeight * 0.9
 *  48      16   lightDirAndDiff: vec4<f32> — [lightDir.xyz, colorDifference]
 *                 lightDir is pre-normalized; colorDifference in [0, ∞)
 *  64      16   lightColor: vec4<f32>     — RGBA in [0, 1]
 *  80      16   viewport:   vec4<u32>     — [width, height, faceCount, 0]
 * Total: 96 bytes
 * ```
 *
 * ## WGSL binding
 *
 * ```wgsl
 * struct SceneUniforms {
 *     projRow0:        vec4<f32>,
 *     projRow1:        vec4<f32>,
 *     origin:          vec4<f32>,
 *     lightDirAndDiff: vec4<f32>,
 *     lightColor:      vec4<f32>,
 *     viewport:        vec4<u32>,
 * }
 * @group(0) @binding(2) var<uniform> uniforms: SceneUniforms;
 * ```
 *
 * ## Lifetime
 *
 * Create once per `WebGpuSceneRendererV2` session alongside [GpuSceneDataBuffer].
 * Call [close] on renderer teardown to release the GPU buffer.
 * [update] must be called from the GPU thread (inside `ctx.withGpu { ... }`).
 */
internal class GpuSceneUniforms(
    private val device: GPUDevice,
    private val queue: GPUQueue,
) : AutoCloseable {

    companion object {
        private const val TAG = "GpuSceneUniforms"

        /**
         * Size of the WGSL `SceneUniforms` struct in bytes.
         * Must match the shader definition exactly — 6 × vec4 × 4 bytes = 96 bytes.
         */
        const val UNIFORM_BYTES = 96
    }

    // Fixed-size CPU staging buffer; reused every update to avoid native allocation.
    private val cpuBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(UNIFORM_BYTES)
        .order(ByteOrder.nativeOrder())

    private var gpuBuffer: GPUBuffer? = device.createBuffer(
        GPUBufferDescriptor(
            usage = BufferUsage.Uniform or BufferUsage.CopyDst,
            size = UNIFORM_BYTES.toLong(),
        )
    ).also {
        it.setLabel("IsometricSceneUniforms")
        Log.d(TAG, "Allocated uniform buffer: $UNIFORM_BYTES bytes")
    }

    /**
     * The underlying [GPUBuffer], valid until [close] is called.
     *
     * Bind as `@group(0) @binding(2) var<uniform> uniforms: SceneUniforms`
     * in the Transform+Cull+Light compute shader.
     */
    val buffer: GPUBuffer get() = checkNotNull(gpuBuffer) { "GpuSceneUniforms already closed" }

    /**
     * Pack projection, lighting, viewport, and face-count data into the uniform buffer
     * and upload via [GPUQueue.writeBuffer].
     *
     * Must be called from the GPU thread (inside `ctx.withGpu { ... }`).
     *
     * @param params       Projection matrix and lighting config from [IsometricEngine.projectionParams].
     * @param lightDirection Unnormalized light direction; normalized inside this call.
     * @param viewportWidth  Canvas width in pixels.
     * @param viewportHeight Canvas height in pixels.
     * @param faceCount      Number of faces in the current scene (written to `uniforms.viewport.z`).
     */
    fun update(
        params: ProjectionParams,
        lightDirection: Vector,
        viewportWidth: Int,
        viewportHeight: Int,
        faceCount: Int,
    ) {
        require(faceCount >= 0) { "faceCount must be non-negative, got $faceCount" }
        checkNotNull(gpuBuffer) { "GpuSceneUniforms already closed" }
        val normLight = lightDirection.normalize()
        val originX = viewportWidth / 2.0
        val originY = viewportHeight * 0.9

        // Reset position before packing — buffer may have been partially read externally.
        cpuBuffer.rewind()

        // projRow0: [t00, t10, 0, 0]
        // screen.x = originX + x*t00 + y*t10
        cpuBuffer.putFloat(params.t00.toFloat())
        cpuBuffer.putFloat(params.t10.toFloat())
        cpuBuffer.putFloat(0f)
        cpuBuffer.putFloat(0f)

        // projRow1: [t01, t11, scale, 0]
        // screen.y = originY - x*t01 - y*t11 - z*scale
        cpuBuffer.putFloat(params.t01.toFloat())
        cpuBuffer.putFloat(params.t11.toFloat())
        cpuBuffer.putFloat(params.scale.toFloat())
        cpuBuffer.putFloat(0f)

        // origin: [originX, originY, 0, 0]
        cpuBuffer.putFloat(originX.toFloat())
        cpuBuffer.putFloat(originY.toFloat())
        cpuBuffer.putFloat(0f)
        cpuBuffer.putFloat(0f)

        // lightDirAndDiff: [ldx, ldy, ldz, colorDifference]
        cpuBuffer.putFloat(normLight.x.toFloat())
        cpuBuffer.putFloat(normLight.y.toFloat())
        cpuBuffer.putFloat(normLight.z.toFloat())
        cpuBuffer.putFloat(params.colorDifference.toFloat())

        // lightColor: RGBA in [0, 1]
        cpuBuffer.putFloat(params.lightColor.r.toFloat() / 255f)
        cpuBuffer.putFloat(params.lightColor.g.toFloat() / 255f)
        cpuBuffer.putFloat(params.lightColor.b.toFloat() / 255f)
        cpuBuffer.putFloat(params.lightColor.a.toFloat() / 255f)

        // viewport: [width, height, faceCount, 0]
        cpuBuffer.putInt(viewportWidth)
        cpuBuffer.putInt(viewportHeight)
        cpuBuffer.putInt(faceCount)
        cpuBuffer.putInt(0)

        // Rewind to position=0 so writeBuffer reads the full [0, UNIFORM_BYTES) range.
        // The packing loop above advanced the position to the end.
        cpuBuffer.rewind()
        queue.writeBuffer(checkNotNull(gpuBuffer), 0L, cpuBuffer)
    }

    override fun close() {
        gpuBuffer?.destroy()
        gpuBuffer?.close()
        gpuBuffer = null
    }
}
