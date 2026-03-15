package io.fabianterhorst.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.math.sqrt

class VectorTest {

    @Test
    fun `cross product calculates correctly`() {
        val v1 = Vector(1.0, 0.0, 0.0)
        val v2 = Vector(0.0, 1.0, 0.0)
        val cross = Vector.crossProduct(v1, v2)
        // Use component-wise comparison to avoid -0.0 vs 0.0 data class equality issue
        assertEquals(0.0, cross.x, 0.0001)
        assertEquals(0.0, cross.y, 0.0001)
        assertEquals(1.0, cross.z, 0.0001)
    }

    @Test
    fun `dot product calculates correctly`() {
        val v1 = Vector(1.0, 2.0, 3.0)
        val v2 = Vector(4.0, 5.0, 6.0)
        val dot = Vector.dotProduct(v1, v2)
        assertEquals(32.0, dot, 0.0001) // 1*4 + 2*5 + 3*6 = 32
    }

    @Test
    fun `normalize creates unit vector`() {
        val v = Vector(3.0, 4.0, 0.0)
        val normalized = v.normalize()
        assertEquals(1.0, normalized.magnitude(), 0.0001)
        assertEquals(0.6, normalized.x, 0.0001)
        assertEquals(0.8, normalized.y, 0.0001)
    }

    @Test
    fun `magnitude calculates correctly`() {
        val v = Vector(3.0, 4.0, 0.0)
        assertEquals(5.0, v.magnitude(), 0.0001)
    }

    @Test
    fun `vector operators work as expected`() {
        val v1 = Vector(1.0, 2.0, 3.0)
        val v2 = Vector(4.0, 5.0, 6.0)

        assertEquals(Vector(5.0, 7.0, 9.0), v1 + v2)
        assertEquals(Vector(-3.0, -3.0, -3.0), v1 - v2)
        assertEquals(Vector(2.0, 4.0, 6.0), v1 * 2.0)
        assertEquals(Vector(-1.0, -2.0, -3.0), -v1)
        assertEquals(Vector.crossProduct(v1, v2), v1 cross v2)
        assertEquals(Vector.dotProduct(v1, v2), v1 dot v2)
    }
}
