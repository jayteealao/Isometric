package io.github.jayteealao.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.PI
import kotlin.math.abs

class PointTest {

    @Test
    fun `translate moves point correctly`() {
        val point = Point(1.0, 2.0, 3.0)
        val translated = point.translate(1.0, -1.0, 0.5)
        assertEquals(Point(2.0, 1.0, 3.5), translated)
    }

    @Test
    fun `scale uniform scaling works`() {
        val point = Point(2.0, 4.0, 6.0)
        val scaled = point.scale(Point.ORIGIN, 2.0)
        assertEquals(Point(4.0, 8.0, 12.0), scaled)
    }

    @Test
    fun `rotateZ rotates correctly`() {
        val point = Point(1.0, 0.0, 0.0)
        val rotated = point.rotateZ(Point.ORIGIN, PI / 2)
        assertTrue(abs(rotated.x) < 0.0001) // Near zero
        assertTrue(abs(rotated.y - 1.0) < 0.0001)
        assertEquals(0.0, rotated.z, 0.0001)
    }

    @Test
    fun `depth calculation is correct`() {
        val p1 = Point(1.0, 1.0, 1.0) // depth = 1+1-2*1 = 0
        val p2 = Point(2.0, 2.0, 0.0) // depth = 2+2-2*0 = 4
        assertTrue(p2.depth() > p1.depth()) // p2 is further back (higher depth)
    }

    @Test
    fun `distance between points`() {
        val p1 = Point(0.0, 0.0, 0.0)
        val p2 = Point(3.0, 4.0, 0.0)
        assertEquals(5.0, Point.distance(p1, p2), 0.0001)
    }

    @Test
    fun `point operators work as expected`() {
        val point = Point(1.0, 2.0, 3.0)
        val otherPoint = Point(4.0, 5.0, 6.0)
        val vector = Vector(0.5, 1.5, -1.0)

        assertEquals(Point(5.0, 7.0, 9.0), point + otherPoint)
        assertEquals(Point(1.5, 3.5, 2.0), point + vector)
        assertEquals(Vector(-3.0, -3.0, -3.0), point - otherPoint)
        assertEquals(Point(0.5, 0.5, 4.0), point - vector)
        assertEquals(Point(2.0, 4.0, 6.0), point * 2.0)
        assertEquals(Point(-1.0, -2.0, -3.0), -point)
    }
}
