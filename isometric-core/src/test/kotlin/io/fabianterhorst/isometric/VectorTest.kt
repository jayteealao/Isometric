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
        assertEquals(Vector(0.0, 0.0, 1.0), cross)
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
        assertEquals(0.6, normalized.i, 0.0001)
        assertEquals(0.8, normalized.j, 0.0001)
    }

    @Test
    fun `magnitude calculates correctly`() {
        val v = Vector(3.0, 4.0, 0.0)
        assertEquals(5.0, v.magnitude(), 0.0001)
    }
}
