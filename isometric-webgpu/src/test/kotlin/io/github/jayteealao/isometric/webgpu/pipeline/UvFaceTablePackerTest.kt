package io.github.jayteealao.isometric.webgpu.pipeline

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AC5 contract tests for the variable-stride UV layout used by the M5 emit shader.
 *
 * Invariants under test:
 * 1. **Slot-i ↔ originalIndex-i:** `table[i]` describes `commands[i]` exactly.
 * 2. **Monotonic offsets:** `table[i].offsetPairs == sum(commands[0..i-1].vertCount)`.
 * 3. **Heterogeneous packing:** mixed vertCount scenes (4, 8, 24, 4) pack without drift.
 * 4. **Default-quad fallback:** `uvCoords == null` → 4 default pairs, `table.vertCount = 4`.
 * 5. **Empty-scene edge:** faceCount = 0 writes nothing and reports totalPairs = 0.
 * 6. **Pool contents match source:** UV floats land at the offset the table advertises.
 */
class UvFaceTablePackerTest {

    @Test
    fun `totalEffectiveVertCount sums valid UV counts and fallback 4s`() {
        val commands = listOf(
            command(vertCount = 4, uv = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)),
            command(vertCount = 24, uv = FloatArray(48) { it.toFloat() / 100f }),
            command(vertCount = 8, uv = null),   // fallback → 4
            command(vertCount = 3, uv = floatArrayOf(0f, 0f, 1f, 0f, 0.5f, 1f)),
        )
        // 4 + 24 + 4 (fallback) + 3 = 35
        assertEquals(35, UvFaceTablePacker.totalEffectiveVertCount(commands))
    }

    @Test
    fun `packInto produces monotonic offsets matching effective vertCount`() {
        val commands = listOf(
            command(vertCount = 4, uv = makeUv(4, seed = 1)),
            command(vertCount = 24, uv = makeUv(24, seed = 2)),
            command(vertCount = 8, uv = makeUv(8, seed = 3)),
        )
        val total = UvFaceTablePacker.totalEffectiveVertCount(commands)
        val (pool, table) = allocateBuffers(total, commands.size)

        UvFaceTablePacker.packInto(commands, commands.size, pool, table)

        // table[0] = (0, 4), table[1] = (4, 24), table[2] = (28, 8)
        assertEntry(table, faceIndex = 0, expectedOffset = 0, expectedVertCount = 4)
        assertEntry(table, faceIndex = 1, expectedOffset = 4, expectedVertCount = 24)
        assertEntry(table, faceIndex = 2, expectedOffset = 28, expectedVertCount = 8)
    }

    @Test
    fun `packInto preserves slot-i invariant under heterogeneous vertex counts`() {
        // Mixed scene: (4, 24, 4, 8) — simulates Prism + Cylinder cap + Prism + Stairs zigzag.
        val c0 = command(vertCount = 4, uv = makeUv(4, seed = 10))
        val c1 = command(vertCount = 24, uv = makeUv(24, seed = 20))
        val c2 = command(vertCount = 4, uv = makeUv(4, seed = 30))
        val c3 = command(vertCount = 8, uv = makeUv(8, seed = 40))
        val commands = listOf(c0, c1, c2, c3)
        val total = UvFaceTablePacker.totalEffectiveVertCount(commands)
        val (pool, table) = allocateBuffers(total, commands.size)

        UvFaceTablePacker.packInto(commands, commands.size, pool, table)

        // Walk each command and verify the pool slice at table[i].offsetPairs equals
        // the source UV bytes for commands[i]. This is the slot-i invariant in its
        // strongest form: every byte arriving in the pool traces back to the correct
        // RenderCommand.
        val sources = listOf(c0.uvCoords!!, c1.uvCoords!!, c2.uvCoords!!, c3.uvCoords!!)
        val offsets = intArrayOf(0, 4, 28, 32)
        for (i in commands.indices) {
            assertEntry(table, i, expectedOffset = offsets[i], expectedVertCount = commands[i].faceVertexCount)
            assertPoolSlice(pool, offsetPairs = offsets[i], expected = sources[i])
        }
    }

    @Test
    fun `packInto writes default quad when uvCoords is null`() {
        val commands = listOf(
            command(vertCount = 4, uv = null),   // triggers fallback
            command(vertCount = 24, uv = null),  // bigger face still falls back to 4-quad
        )
        val total = UvFaceTablePacker.totalEffectiveVertCount(commands)
        // total = 4 + 4 = 8
        assertEquals(8, total)

        val (pool, table) = allocateBuffers(total, commands.size)
        UvFaceTablePacker.packInto(commands, commands.size, pool, table)

        assertEntry(table, faceIndex = 0, expectedOffset = 0, expectedVertCount = 4)
        assertEntry(table, faceIndex = 1, expectedOffset = 4, expectedVertCount = 4)

        val defaultQuad = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertPoolSlice(pool, offsetPairs = 0, expected = defaultQuad)
        assertPoolSlice(pool, offsetPairs = 4, expected = defaultQuad)
    }

    @Test
    fun `packInto treats malformed uvCoords (too short) as fallback`() {
        val malformed = floatArrayOf(0f, 0f, 1f)   // only 3 floats, need 2 × 4 = 8
        val commands = listOf(command(vertCount = 4, uv = malformed))
        val total = UvFaceTablePacker.totalEffectiveVertCount(commands)
        assertEquals(4, total, "malformed uv should fall back to 4 default quad pairs")

        val (pool, table) = allocateBuffers(total, commands.size)
        UvFaceTablePacker.packInto(commands, commands.size, pool, table)

        assertEntry(table, faceIndex = 0, expectedOffset = 0, expectedVertCount = 4)
        assertPoolSlice(pool, offsetPairs = 0, expected = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
    }

    @Test
    fun `packInto with faceCount zero sets limits to zero`() {
        val commands = listOf(command(vertCount = 4, uv = makeUv(4, seed = 99)))
        val pool = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        val table = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())

        UvFaceTablePacker.packInto(commands, faceCount = 0, pool, table)

        assertEquals(0, pool.limit(), "pool must have zero payload bytes for empty scene")
        assertEquals(0, table.limit(), "table must have zero payload bytes for empty scene")
    }

    @Test
    fun `packInto writes pool bytes matching source uvCoords for max-sized face`() {
        // 24-vertex face — the headline case this slice fixes. Verifies every UV pair
        // arrives intact.
        val uv = makeUv(24, seed = 7)
        val commands = listOf(command(vertCount = 24, uv = uv))
        val total = UvFaceTablePacker.totalEffectiveVertCount(commands)
        assertEquals(24, total)

        val (pool, table) = allocateBuffers(total, commands.size)
        UvFaceTablePacker.packInto(commands, commands.size, pool, table)

        assertEntry(table, faceIndex = 0, expectedOffset = 0, expectedVertCount = 24)
        assertPoolSlice(pool, offsetPairs = 0, expected = uv)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun command(vertCount: Int, uv: FloatArray?): RenderCommand {
        // Path points are not consulted by the UV packer — a single point per face is fine.
        val path = Path(List(vertCount) { Point(0.0, 0.0, 0.0) })
        val points = DoubleArray(vertCount * 2)
        return RenderCommand(
            commandId = "test-$vertCount",
            points = points,
            color = IsoColor(200.0, 200.0, 200.0, 255.0),
            originalPath = path,
            originalShape = null,
            uvCoords = uv,
            faceVertexCount = vertCount,
        )
    }

    private fun makeUv(vertCount: Int, seed: Int): FloatArray =
        FloatArray(vertCount * 2) { i -> (seed * 100 + i).toFloat() / 1000f }

    private fun allocateBuffers(totalPairs: Int, faceCount: Int): Pair<ByteBuffer, ByteBuffer> {
        val pool = ByteBuffer
            .allocateDirect(totalPairs * SceneDataLayout.UV_POOL_STRIDE)
            .order(ByteOrder.nativeOrder())
        val table = ByteBuffer
            .allocateDirect(faceCount * SceneDataLayout.UV_TABLE_STRIDE)
            .order(ByteOrder.nativeOrder())
        return pool to table
    }

    private fun assertEntry(
        table: ByteBuffer,
        faceIndex: Int,
        expectedOffset: Int,
        expectedVertCount: Int,
    ) {
        table.position(faceIndex * SceneDataLayout.UV_TABLE_STRIDE)
        assertEquals(expectedOffset, table.getInt(), "table[$faceIndex].offsetPairs")
        assertEquals(expectedVertCount, table.getInt(), "table[$faceIndex].vertCount")
    }

    private fun assertPoolSlice(pool: ByteBuffer, offsetPairs: Int, expected: FloatArray) {
        pool.position(offsetPairs * SceneDataLayout.UV_POOL_STRIDE)
        for (i in expected.indices) {
            assertEquals(expected[i], pool.getFloat(), "pool[offsetPairs=$offsetPairs, float #$i]")
        }
    }
}
