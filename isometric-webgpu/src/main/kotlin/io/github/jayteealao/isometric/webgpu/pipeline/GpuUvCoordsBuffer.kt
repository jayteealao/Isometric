package io.github.jayteealao.isometric.webgpu.pipeline

import androidx.webgpu.GPUBuffer
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.webgpu.GpuContext

/**
 * Manages the per-vertex UV coordinates GPU buffer for the emit shader (binding 6).
 *
 * Each face gets 3 × vec4<f32> = 48 bytes, packing up to 6 UV pairs:
 * `(u0,v0,u1,v1)`, `(u2,v2,u3,v3)`, `(u4,v4,u5,v5)`.
 *
 * This is a geometry/projection concern (how the texture wraps a face), not a
 * texture-atlas concern (where the texture lives in the atlas). Kept separate from
 * [io.github.jayteealao.isometric.webgpu.texture.GpuTextureManager] which owns
 * atlas state.
 *
 * **Invariant:** UV slot `i` must correspond to the face at GPU sort key
 * `originalIndex = i`. This holds when `scene.commands` is in the same
 * traversal order as the `SceneDataPacker` output.
 */
internal class GpuUvCoordsBuffer(
    ctx: GpuContext,
) : AutoCloseable {

    private val buf = GrowableGpuStagingBuffer(ctx, label = "IsometricUvCoords")
    private val ctx = ctx

    val gpuBuffer: GPUBuffer? get() = buf.gpuBuffer

    fun upload(scene: PreparedScene, faceCount: Int) {
        if (faceCount == 0) return
        require(faceCount <= scene.commands.size) {
            "faceCount $faceCount > scene.commands.size ${scene.commands.size}"
        }

        val entryBytes = 48 // 3 × vec4<f32> = 12 floats × 4 bytes
        buf.ensureCapacity(faceCount, entryBytes)

        val requiredBytes = faceCount * entryBytes
        val cpu = buf.cpuBuffer!!
        cpu.rewind()
        cpu.limit(requiredBytes)
        for (i in 0 until faceCount) {
            val cmd = scene.commands[i]
            val uv = cmd.uvCoords
            val vertCount = cmd.faceVertexCount
            // TODO(uv-variable-stride): 48-byte slot caps faces at 6 verts. Shapes that
            // exceed this (Cylinder caps when vertices > 6, Stairs zigzag sides when
            // stepCount > 2, Knot) silently truncate here. When those cases become
            // real, switch to variable-stride packing or a scatter-gather layout.
            if (uv != null && vertCount > 0 && uv.size >= 2 * vertCount) {
                for (j in 0 until 12) {
                    cpu.putFloat(if (j < uv.size) uv[j] else 0f)
                }
            } else {
                // Default quad UVs: (0,0)(1,0)(1,1)(0,1)(0,0)(0,0)
                cpu.putFloat(0f); cpu.putFloat(0f); cpu.putFloat(1f); cpu.putFloat(0f)
                cpu.putFloat(1f); cpu.putFloat(1f); cpu.putFloat(0f); cpu.putFloat(1f)
                cpu.putFloat(0f); cpu.putFloat(0f); cpu.putFloat(0f); cpu.putFloat(0f)
            }
        }
        cpu.rewind()

        ctx.queue.writeBuffer(buf.gpuBuffer!!, 0L, cpu)
    }

    override fun close() {
        buf.close()
    }
}
