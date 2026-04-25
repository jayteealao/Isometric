package io.github.jayteealao.isometric.webgpu.pipeline

import android.util.Log
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.MaterialData
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.resolveForFace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Layout constants for the `FaceData` and `TransformedFace` GPU storage buffer structs.
 *
 * These values must match the WGSL struct definitions in `TransformCullLightShader` exactly.
 * Any change here requires a matching change to the shader.
 *
 * ## FaceData memory layout (448 bytes per face)
 *
 * Post `webgpu-ngon-faces`: supports up to [MAX_FACE_VERTICES] = 24 vertices per face.
 * WGSL's `array<vec3<f32>, 24>` element stride is 16 (vec3 pads to vec4 in storage arrays),
 * so the vertex block occupies exactly `24 × 16 = 384` bytes.
 *
 * ```
 *  offset  size  field
 *    0    384   v: array<vec3<f32>, 24>  (element stride 16, xyz packed at +0/+4/+8, +12 pad)
 *  384      4   vertexCount (u32)
 *  388     12   _f0, _f1, _f2  (padding to 16-byte align for vec4 that follows)
 *  400     16   baseColor  (vec4<f32>, RGBA in [0,1])
 *  416     12   normal     (vec3<f32>)
 *  428      4   textureIndex (u32; NO_TEXTURE = 0xFFFFFFFF)
 *  432      4   faceIndex   (u32)
 *  436     12   _pad        (3 × u32 — struct stride multiple of 16)
 * 448  →  448
 * ```
 *
 * ## TransformedFace memory layout (240 bytes per face)
 *
 * `s: array<vec2<f32>, 24>` packs tightly at 8-byte stride → 192 bytes for screen coords.
 *
 * ```
 *  offset  size  field
 *    0    192   s: array<vec2<f32>, 24>  (element stride 8)
 *  192      4   vertexCount (u32)
 *  196     12   _p0, _p1, _p2 (pad to 16-byte align for vec4)
 *  208     16   litColor (vec4<f32>)
 *  224      4   depthKey (f32)
 *  228      4   faceIndex (u32)
 *  232      4   visible (u32)  ← 1 = passed cull, 0 = culled
 *  236      4   _p4 (u32)
 * 240  →  240
 * ```
 */
internal object SceneDataLayout {
    /**
     * Maximum vertices a single face can carry.
     *
     * Matches `RenderCommand.faceVertexCount in 3..24` validator bound. Shared by
     * [FaceData] / [TransformedFace] struct sizes, the packer loop, and the WGSL
     * shader's per-face vertex array lengths.
     */
    const val MAX_FACE_VERTICES = 24

    /** Bytes per FaceData struct in the GPU scene-data storage buffer. */
    const val FACE_DATA_BYTES = 448

    /** Bytes per TransformedFace struct in the GPU intermediate buffer. */
    const val TRANSFORMED_FACE_BYTES = 240

    /**
     * Bytes per entry in the per-face UV offset+count table (binding 7).
     *
     * One `vec2<u32>` per face: `(offsetPairs: u32, vertCount: u32)`.
     * - `offsetPairs` — index into the flat UV pool (in vec2-units, not bytes).
     * - `vertCount`   — number of UV pairs for this face (1..[MAX_FACE_VERTICES], or 4
     *   for the default-quad fallback).
     */
    const val UV_TABLE_STRIDE = 8

    /** Bytes per UV pool entry (binding 6) — one `vec2<f32>` = 8 bytes. */
    const val UV_POOL_STRIDE = 8

    /**
     * Bytes per entry in the per-face UV region buffer.
     *
     * Each entry is one `UvRegion` WGSL struct:
     * - `userMatrix : mat3x2<f32>` — user [TextureTransform] without atlas composition (24 bytes)
     * - `atlasScale : vec2<f32>`   — atlas sub-region UV scale (8 bytes)
     * - `atlasOffset : vec2<f32>`  — atlas sub-region UV offset (8 bytes)
     *
     * Total = 40 bytes, alignOf = 8.
     *
     * The GPU shader applies `fract(userMatrix × vec3(baseUV,1))` before the atlas mapping,
     * so tiling works correctly within atlas sub-regions.
     */
    const val UV_REGION_STRIDE = 40

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
            // Post webgpu-ngon-faces: supports up to MAX_FACE_VERTICES (=24) per face.
            // `RenderCommand.init` enforces faceVertexCount in 3..24 — this matches.
            val n = pts3d.size.coerceAtMost(SceneDataLayout.MAX_FACE_VERTICES)

            // v: array<vec3<f32>, 24> — each element is 16-byte aligned (xyz + 4-byte pad).
            // Total block size: 24 × 16 = 384 bytes.
            for (i in 0 until SceneDataLayout.MAX_FACE_VERTICES) {
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
                buffer.putFloat(0f)    // vec3 padding to 16-byte stride
            }

            // vertexCount (u32) + 3 × u32 pad — aligns the next vec4 (baseColor) to 400.
            buffer.putInt(n)
            buffer.putInt(0)
            buffer.putInt(0)
            buffer.putInt(0)

            // baseColor: vec4<f32> RGBA in [0, 1] — use raw material color (pre-lighting)
            // so the GPU M3 shader applies lighting exactly once. For PerFace materials
            // that resolve to a per-face IsoColor, use that color instead of the command's
            // default-carrying baseColor — otherwise every face renders as the PerFace
            // default (typically mid-gray), erasing distinct per-face colors.
            val effectiveColor = resolveEffectiveColor(cmd)
            buffer.putFloat(effectiveColor.r.toFloat() / 255f)
            buffer.putFloat(effectiveColor.g.toFloat() / 255f)
            buffer.putFloat(effectiveColor.b.toFloat() / 255f)
            buffer.putFloat(effectiveColor.a.toFloat() / 255f)

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

            // textureIndex: u32 — resolved from cmd.material
            buffer.putInt(resolveTextureIndex(cmd))

            // faceIndex: u32 — original command list index (used in sort + emit passes)
            buffer.putInt(index)

            // _padding: 12 bytes to reach 144 (16-byte aligned struct size)
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
    /**
     * Pack a compact `u32` array of texture indices for the M5 emit shader's
     * `sceneTexIndices` binding. One `u32` per command (4 bytes each).
     *
     * Only the first [faceCount] entries of [commands] are packed, so writes never
     * exceed the GPU buffer that was sized for exactly [faceCount] entries.
     *
     * Buffer size must be `≥ faceCount × 4`.
     */
    fun packTexIndicesInto(commands: List<RenderCommand>, buffer: ByteBuffer, faceCount: Int = commands.size) {
        buffer.rewind()
        buffer.limit(faceCount * 4)
        for (i in 0 until faceCount) {
            buffer.putInt(resolveTextureIndex(commands[i]))
        }
        buffer.rewind()
    }

    /**
     * Resolve the GPU texture index for a render command's material.
     *
     * Commands with `IsometricMaterial.Textured` material get index 0 (atlas texture).
     * `PerFace` resolves using the command's [RenderCommand.faceType] to find the
     * per-face sub-material, falling back to the PerFace default.
     * All others get [SceneDataLayout.NO_TEXTURE].
     */
    private fun resolveTextureIndex(cmd: RenderCommand): Int {
        val effective = when (val m = cmd.material) {
            is IsometricMaterial.PerFace -> m.resolveForFace(cmd.faceType)
            else -> m
        }
        return when (effective) {
            is IsometricMaterial.Textured -> 0
            else -> SceneDataLayout.NO_TEXTURE
        }
    }

    /**
     * Resolve the per-face effective color for vertex packing.
     *
     * For `PerFace` materials whose [faceType][RenderCommand.faceType] resolves to a
     * per-face [IsoColor], returns that color so distinct face colors survive the
     * GPU vertex buffer. For `PerFace` that resolves to a [IsometricMaterial.Textured],
     * returns the `tint` (so the fragment shader's `sample * tint` multiplication
     * behaves as the user intends per-face).
     *
     * For all other cases (flat materials, unresolved per-face), returns
     * [RenderCommand.baseColor] so the pre-existing flat-material path is unchanged.
     */
    private fun resolveEffectiveColor(cmd: RenderCommand): IsoColor {
        val perFace = cmd.material as? IsometricMaterial.PerFace ?: return cmd.baseColor
        return when (val sub = perFace.resolveForFace(cmd.faceType)) {
            is IsoColor -> sub
            is IsometricMaterial.Textured -> sub.tint
            else -> {
                Log.w("SceneDataPacker", "Unknown MaterialData fallback for face ${cmd.faceType ?: "?"}: ${sub?.javaClass?.simpleName ?: "null"}")
                cmd.baseColor
            }
        }
    }

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
