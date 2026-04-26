package io.github.jayteealao.isometric.webgpu.pipeline

import androidx.webgpu.GPUBuffer
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.webgpu.GpuContext

/**
 * Manages the per-vertex UV coordinates for the M5 emit shader via a **variable-stride
 * offset+length layout** (bindings 6 and 7).
 *
 * Post `webgpu-ngon-faces`: faces with `faceVertexCount in 3..24` render full UVs.
 * The previous fixed 48-byte-per-face slot (max 6 UV pairs) is replaced by:
 *
 * - **Pool buffer** (binding 6) — `array<vec2<f32>>` holding every face's UV pairs
 *   concatenated in scene order. Size = `sumOf { faceVertexCount } × 8 bytes`.
 * - **Table buffer** (binding 7) — `array<vec2<u32>>` with one entry per face:
 *   `(offsetPairs, vertCount)`. The shader indexes `table[originalIndex]` and then
 *   loops `uvPool[offsetPairs + k]` for `k in 0..vertCount-1`.
 *
 * ## Invariants
 *
 * - **Slot i ↔ originalIndex = i.** `table[i]` MUST describe the UV range for
 *   `scene.commands[i]`. Violating this produces silently-wrong UVs — AC5 tests
 *   exercise this across heterogeneous face-vertex counts.
 * - **Monotonic offsets.** `table[i].offsetPairs = sum(commands[0..i-1].vertCount)`.
 *   The packing walk computes this cumulatively so the pool is tightly packed.
 * - **Default fallback.** When a command has `uvCoords == null`, the pool receives
 *   4 UV pairs `(0,0)(1,0)(1,1)(0,1)` (a standard quad) and `table[i].vertCount = 4`.
 *
 * This is a geometry/projection concern (how the texture wraps a face), not a
 * texture-atlas concern (where the texture lives in the atlas). Kept separate from
 * [io.github.jayteealao.isometric.webgpu.texture.GpuTextureManager] which owns
 * atlas state.
 */
internal class GpuUvCoordsBuffer(
    ctx: GpuContext,
) : AutoCloseable {

    /** Flat concatenated UV pool, `array<vec2<f32>>` — binding 6. */
    private val pool = GrowableGpuStagingBuffer(ctx, label = "IsometricUvPool")

    /** Per-face `(offsetPairs, vertCount)` table, `array<vec2<u32>>` — binding 7. */
    private val table = GrowableGpuStagingBuffer(ctx, label = "IsometricUvTable")

    private val ctx = ctx

    /** Pool GPU buffer — bind at group 0 binding 6. */
    val poolBuffer: GPUBuffer? get() = pool.gpuBuffer

    /** Offset+length table GPU buffer — bind at group 0 binding 7. */
    val tableBuffer: GPUBuffer? get() = table.gpuBuffer

    /**
     * Total `vec2<f32>` entries written into the pool during the last [upload].
     *
     * Used to size dispatches / validate downstream invariants. Not read by the
     * shader — shader uses only `table[originalIndex].vertCount`.
     */
    var totalUvPairs: Int = 0
        private set

    fun upload(scene: PreparedScene, faceCount: Int) {
        require(faceCount >= 0) { "faceCount must be non-negative, got $faceCount" }
        if (faceCount == 0) {
            totalUvPairs = 0
            return
        }
        require(faceCount <= scene.commands.size) {
            "faceCount $faceCount > scene.commands.size ${scene.commands.size}"
        }

        // Compute total pool size first so both GPU buffers can be grown exactly once.
        // UvFaceTablePacker's `totalEffectiveVertCount` is the single source of truth
        // for "how many vec2 slots will the pool contain" — same function the packer
        // uses internally, so no drift between sizing and packing.
        val totalPairs = UvFaceTablePacker.totalEffectiveVertCount(scene.commands, faceCount)
        totalUvPairs = totalPairs

        pool.ensureCapacity(totalPairs, SceneDataLayout.UV_POOL_STRIDE)
        table.ensureCapacity(faceCount, SceneDataLayout.UV_TABLE_STRIDE)

        val poolCpu = pool.cpuBuffer!!
        val tableCpu = table.cpuBuffer!!
        UvFaceTablePacker.packInto(scene.commands, faceCount, poolCpu, tableCpu)

        ctx.queue.writeBuffer(pool.gpuBuffer!!, 0L, poolCpu)
        ctx.queue.writeBuffer(table.gpuBuffer!!, 0L, tableCpu)
    }

    override fun close() {
        pool.close()
        table.close()
    }
}
