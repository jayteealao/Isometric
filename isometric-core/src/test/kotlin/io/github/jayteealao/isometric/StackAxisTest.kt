package io.github.jayteealao.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StackAxisTest {

    // ── Enum contract ─────────────────────────────────────────────────────────

    @Test
    fun `exactly three values exist`() {
        assertEquals(3, StackAxis.values().size)
    }

    @Test
    fun `X Y Z values exist`() {
        val names = StackAxis.values().map { it.name }.toSet()
        assertTrue(names.contains("X"))
        assertTrue(names.contains("Y"))
        assertTrue(names.contains("Z"))
    }

    @Test
    fun `valueOf returns correct value`() {
        assertEquals(StackAxis.X, StackAxis.valueOf("X"))
        assertEquals(StackAxis.Y, StackAxis.valueOf("Y"))
        assertEquals(StackAxis.Z, StackAxis.valueOf("Z"))
    }

    // ── unitPoint ─────────────────────────────────────────────────────────────

    @Test
    fun `X unitPoint is (1, 0, 0)`() {
        assertEquals(Point(1.0, 0.0, 0.0), StackAxis.X.unitPoint())
    }

    @Test
    fun `Y unitPoint is (0, 1, 0)`() {
        assertEquals(Point(0.0, 1.0, 0.0), StackAxis.Y.unitPoint())
    }

    @Test
    fun `Z unitPoint is (0, 0, 1)`() {
        assertEquals(Point(0.0, 0.0, 1.0), StackAxis.Z.unitPoint())
    }

    @Test
    fun `unitPoint components sum to 1`() {
        for (axis in StackAxis.values()) {
            val p = axis.unitPoint()
            assertEquals(1.0, p.x + p.y + p.z, "Sum of components for $axis should be 1")
        }
    }

    @Test
    fun `unitPoint has exactly one non-zero component`() {
        for (axis in StackAxis.values()) {
            val p = axis.unitPoint()
            val nonZero = listOf(p.x, p.y, p.z).count { it != 0.0 }
            assertEquals(1, nonZero, "Expected exactly one non-zero component for $axis")
        }
    }

    @Test
    fun `unitPoint is consistent across multiple calls`() {
        // unitPoint must return the same value on every call (no mutable state)
        assertEquals(StackAxis.Z.unitPoint(), StackAxis.Z.unitPoint())
        assertEquals(StackAxis.X.unitPoint(), StackAxis.X.unitPoint())
    }
}
