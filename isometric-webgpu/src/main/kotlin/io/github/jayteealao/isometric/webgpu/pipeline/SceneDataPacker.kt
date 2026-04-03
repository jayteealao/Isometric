package io.github.jayteealao.isometric.webgpu.pipeline

import io.github.jayteealao.isometric.RenderCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Layout constants for the `FaceData` and `TransformedFace` GPU storage buffer structs.
 *
 * These values must match the WGSL struct definitions in `transform_cull_light.wgsl` exactly.
 * Any change here requires a matching change to the shader.
 *
 * ## FaceData memory layout (144 bytes per face)
 *
 * ```
 *  offset  size  field
 *    0      12   v0.xyz  (vec3<f32>)
 *   12       4   _p0     (f32 padding — vec4 alignment)
 *   16      12   v1.xyz
 *   28       4   _p1
 *   32      12   v2.xyz
 *   44       4   _p2
 *   48      12   v3.xyz
 *   60       4   _p3
 *   64      12   v4.xyz
 *   76       4   _p4
 *   80      12   v5.xyz
 *   92       4   vertexCount (u32 — packed into v5's padding slot)
 *   96      16   baseColor   (vec4<f32>, RGBA in [0,1])
 *  112      12   normal      (vec3<f32>)
 *  124       4   textureIndex (u32; NO_TEXTURE = 0xFFFFFFFF)
 *  128       4   faceIndex   (u32)
 *  132      12   _padding    (vec3<u32>)
 * 144  →   144   (already aligned for storage-buffer layout)
 * ```
 */
internal object SceneDataLayout {
    /** Bytes per FaceData struct in the GPU scene-data storage buffer. */
    const val FACE_DATA_BYTES = 144

    /** Bytes per TransformedFace struct in the GPU intermediate buffer. */
    const val TRANSFORMED_FACE_BYTES = 96

    /**
     * Sentinel value for [FaceData.textureIndex] when no texture is bound.
     * Equals `0xFFFFFFFF` as an unsigned 32-bit integer, stored as a signed Kotlin [Int].
     */
    const val NO_TEXTURE: Int = -1 // 0xFFFFFFFF interpreted as u32 in WGSL
}

/**
 * Packs a list of [RenderCommand] objects into a flat [ByteBuffer] matching the GPU's
 * `FaceData` struct layout defined in [SceneDataLayout].
 *
 * ## Phase 3 design
 *
 * Phase 3 operates on the **original 3D geometry** (`RenderCommand.originalPath.points`),
 * not the already-projected 2D screen points (`RenderCommand.points`). The GPU compute
 * passes perform projection, culling, and lighting in parallel on the 3D data. Sending
 * pre-projected 2D points would leave the O(N) projection step on the CPU, defeating the
 * purpose of Phase 3.
 *
 * ## Face normal
 *
 * The face normal is computed from the cross product of two edges:
 * `normal = normalize(cross(v1 − v0, v2 − v0))`
 *
 * For isometric faces (flat coplanar polygons) the normal derived from the first three
 * vertices is correct for the entire face. Degenerate faces (fewer than 3 points, or
 * zero-length edges) fall back to `[0, 0, 1]`.
 *
 * ## Buffer reuse
 *
 * Prefer [packInto] with a pre-allocated [ByteBuffer] to avoid native memory allocation
 * on every frame. [GpuSceneDataBuffer] manages buffer reuse automatically.
 */
internal object SceneDataPacker {

    /**
     * Pack [commands] into a pre-allocated [ByteBuffer] ready for `GPUQueue.writeBuffer()`.
     *
     * The buffer must have capacity `≥ commands.size × FACE_DATA_BYTES` and will be
     * rewound before use. After this call the buffer's position is at 0 and its limit is
     * `commands.size × FACE_DATA_BYTES`.
     *
     * @param commands Render commands from `rootNode.renderTo(commands, context)`.
     *   Uses [RenderCommand.originalPath] for 3D vertex data.
     * @param buffer A direct [ByteBuffer] with native byte order and sufficient capacity.
     */
    fun packInto(commands: List<RenderCommand>, buffer: ByteBuffer) {
        buffer.rewind()
        val limit = commands.size * SceneDataLayout.FACE_DATA_BYTES
        buffer.limit(limit)

        for ((index, cmd) in commands.withIndex()) {
            val pts3d = cmd.originalPath.points
            val n = pts3d.size.coerceAtMost(6)

            // v0–v5: vec3<f32> each, padded to 16 bytes (vec4 alignment).
            // v5's padding slot is repurposed for vertexCount.
            for (i in 0 until 6) {
                if (i < n) {
                    val pt = pts3d[i]
                    buffer.putFloat(pt.x.toFloat())
                    buffer.putFloat(pt.y.toFloat())
                    buffer.putFloat(pt.z.toFloat())
                } else {
                    buffer.putFloat(0f)
                    buffer.putFloat(0f)
                    buffer.putFloat(0f)
                }
                if (i < 5) {
                    buffer.putFloat(0f)    // _p0…_p4 — alignment padding
                } else {
                    buffer.putInt(n)       // vertexCount packed into v5's padding slot
                }
            }

            // baseColor: vec4<f32> RGBA in [0, 1]
            buffer.putFloat(cmd.color.r.toFloat() / 255f)
            buffer.putFloat(cmd.color.g.toFloat() / 255f)
            buffer.putFloat(cmd.color.b.toFloat() / 255f)
            buffer.putFloat(cmd.color.a.toFloat() / 255f)

            // normal: vec3<f32> — cross product of first two edge vectors, inlined to
            // avoid Triple<Float,Float,Float> allocation per face.
            var nx = 0f; var ny = 0f; var nz = 1f
            if (pts3d.size >= 3) {
                val v0 = pts3d[0]; val v1 = pts3d[1]; val v2 = pts3d[2]
                val e1x = (v1.x - v0.x).toFloat()
                val e1y = (v1.y - v0.y).toFloat()
                val e1z = (v1.z - v0.z).toFloat()
                val e2x = (v2.x - v0.x).toFloat()
                val e2y = (v2.y - v0.y).toFloat()
                val e2z = (v2.z - v0.z).toFloat()
                val cx = e1y * e2z - e1z * e2y
                val cy = e1z * e2x - e1x * e2z
                val cz = e1x * e2y - e1y * e2x
                val len = sqrt(cx * cx + cy * cy + cz * cz)
                if (len >= 1e-8f) { nx = cx / len; ny = cy / len; nz = cz / len }
            }
            buffer.putFloat(nx)
            buffer.putFloat(ny)
            buffer.putFloat(nz)

            // textureIndex: u32 — NO_TEXTURE sentinel for now (Phase 3 texture work: M9+)
            buffer.putInt(SceneDataLayout.NO_TEXTURE)

            // faceIndex: u32 — original command list index (used in sort + emit passes)
            buffer.putInt(index)

            // _padding: 12 bytes to bring the struct to 128-byte alignment
            buffer.putInt(0)
            buffer.putInt(0)
            buffer.putInt(0)
        }

        buffer.rewind()
    }

    /**
     * Pack [commands] into a freshly allocated [ByteBuffer] ready for `GPUQueue.writeBuffer()`.
     *
     * Prefer [packInto] when the buffer can be reused across frames to avoid allocating
     * native memory on every call.
     *
     * @param commands Render commands from `rootNode.renderTo(commands, context)`.
     *   Uses [RenderCommand.originalPath] for 3D vertex data.
     * @return A rewound direct [ByteBuffer] of size `commands.size × FACE_DATA_BYTES`.
     *   Returns an empty (zero-capacity) buffer if [commands] is empty.
     */
    fun pack(commands: List<RenderCommand>): ByteBuffer {
        if (commands.isEmpty()) {
            return ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        }
        val buffer = ByteBuffer
            .allocateDirect(commands.size * SceneDataLayout.FACE_DATA_BYTES)
            .order(ByteOrder.nativeOrder())
        packInto(commands, buffer)
        return buffer
    }
}
