package io.github.jayteealao.isometric.webgpu.pipeline

import io.github.jayteealao.isometric.RenderCommand
import java.nio.ByteBuffer

/**
 * Packs per-vertex UV coordinates into the variable-stride dual-buffer layout used by
 * [GpuUvCoordsBuffer] (bindings 6 and 7 of the M5 emit shader).
 *
 * Extracted as a pure static helper — no [io.github.jayteealao.isometric.webgpu.GpuContext]
 * dependency — so the packing invariants can be unit-tested on the JVM without a live
 * WebGPU device.
 *
 * ## Layout contract
 *
 * - **Pool** (binding 6) — flat `array<vec2<f32>>` holding every face's UV pairs
 *   concatenated in scene order. One `vec2<f32>` per vertex, 8 bytes each.
 * - **Table** (binding 7) — one `vec2<u32>` per face: `(offsetPairs, vertCount)`.
 *   `offsetPairs` is in `vec2`-units (not bytes) so the WGSL shader can index the
 *   pool as `uvPool[entry.x + k]` directly.
 *
 * ## Invariants (covered by AC5 tests)
 *
 * 1. **Slot i ↔ originalIndex = i.** `table[i]` MUST describe the UV range for
 *    `commands[i]`. Any off-by-one destroys correctness silently.
 * 2. **Monotonic offsets.** `table[i].offsetPairs == sum(commands[0..i-1].effectiveVertCount)`.
 * 3. **Default fallback.** Commands with `uvCoords == null` (or malformed) receive
 *    a default quad `(0,0)(1,0)(1,1)(0,1)` and `table[i].vertCount = 4`.
 * 4. **Pool consumption matches total.** [totalEffectiveVertCount] equals
 *    `sum(table[i].vertCount)` exactly so the caller sizes the pool buffer correctly.
 */
internal object UvFaceTablePacker {

    private const val DEFAULT_QUAD_VERTS = 4

    /**
     * Returns `true` when [cmd] carries a well-formed UV array that can be packed
     * directly into the pool buffer.
     *
     * A UV array is considered valid when:
     * 1. It is non-null.
     * 2. The face has at least one vertex (`faceVertexCount > 0`).
     * 3. The array is long enough to hold `2 × faceVertexCount` floats (one `u,v` pair
     *    per vertex).
     *
     * Commands that fail this check fall back to the default quad `(0,0)(1,0)(1,1)(0,1)`.
     */
    private fun hasValidUv(cmd: RenderCommand): Boolean {
        val uv = cmd.uvCoords
        val vertCount = cmd.faceVertexCount
        return uv != null && vertCount > 0 && uv.size >= 2 * vertCount
    }

    /**
     * Pack UV data into the supplied [poolBuffer] and [tableBuffer].
     *
     * Buffers must be pre-sized: pool ≥ [totalEffectiveVertCount] × 8 bytes, table ≥
     * [faceCount] × 8 bytes. Both are [ByteBuffer.rewind]ed before and after the write;
     * limits are set to the exact payload length.
     *
     * @param commands    Scene commands in the same order as the M3 scene-data upload.
     * @param faceCount   Number of commands to pack (usually `commands.size`).
     * @param poolBuffer  CPU staging buffer for the flat UV pool (binding 6).
     * @param tableBuffer CPU staging buffer for the per-face table (binding 7).
     */
    fun packInto(
        commands: List<RenderCommand>,
        faceCount: Int,
        poolBuffer: ByteBuffer,
        tableBuffer: ByteBuffer,
    ) {
        require(faceCount >= 0) { "faceCount must be non-negative, got $faceCount" }
        require(faceCount <= commands.size) {
            "faceCount $faceCount > commands.size ${commands.size}"
        }

        val totalPairs = totalEffectiveVertCount(commands, faceCount)
        poolBuffer.rewind(); poolBuffer.limit(totalPairs * SceneDataLayout.UV_POOL_STRIDE)
        tableBuffer.rewind(); tableBuffer.limit(faceCount * SceneDataLayout.UV_TABLE_STRIDE)

        var currentOffset = 0
        for (i in 0 until faceCount) {
            val cmd = commands[i]
            val uv = cmd.uvCoords
            val vertCount = cmd.faceVertexCount

            if (hasValidUv(cmd)) {
                tableBuffer.putInt(currentOffset)
                tableBuffer.putInt(vertCount)
                for (k in 0 until vertCount) {
                    poolBuffer.putFloat(uv!![2 * k])
                    poolBuffer.putFloat(uv[2 * k + 1])
                }
                currentOffset += vertCount
            } else {
                tableBuffer.putInt(currentOffset)
                tableBuffer.putInt(DEFAULT_QUAD_VERTS)
                // Default quad UVs: (0,0)(1,0)(1,1)(0,1) — matches Canvas behaviour.
                poolBuffer.putFloat(0f); poolBuffer.putFloat(0f)
                poolBuffer.putFloat(1f); poolBuffer.putFloat(0f)
                poolBuffer.putFloat(1f); poolBuffer.putFloat(1f)
                poolBuffer.putFloat(0f); poolBuffer.putFloat(1f)
                currentOffset += DEFAULT_QUAD_VERTS
            }
        }

        poolBuffer.rewind()
        tableBuffer.rewind()
    }

    /**
     * Total `vec2<f32>` entries the pool will contain after [packInto] runs.
     *
     * Callers use this to size the pool buffer ahead of the [packInto] call, so the
     * staging buffer is allocated once per frame rather than grown per-entry.
     */
    fun totalEffectiveVertCount(commands: List<RenderCommand>, faceCount: Int = commands.size): Int {
        var total = 0
        for (i in 0 until faceCount) {
            total += effectiveVertCount(commands[i])
        }
        return total
    }

    private fun effectiveVertCount(cmd: RenderCommand): Int =
        if (hasValidUv(cmd)) cmd.faceVertexCount else DEFAULT_QUAD_VERTS
}
