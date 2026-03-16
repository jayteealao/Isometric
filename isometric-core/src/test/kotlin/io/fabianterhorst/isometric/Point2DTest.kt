package io.fabianterhorst.isometric

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Point2DTest {

    @Test
    fun `plus operator adds components`() {
        val result = Point2D(1.0, 2.0) + Point2D(3.0, 4.0)
        assertEquals(Point2D(4.0, 6.0), result)
    }

    @Test
    fun `minus operator subtracts components`() {
        val result = Point2D(5.0, 7.0) - Point2D(2.0, 3.0)
        assertEquals(Point2D(3.0, 4.0), result)
    }

    @Test
    fun `times operator scales components`() {
        val result = Point2D(2.0, 3.0) * 4.0
        assertEquals(Point2D(8.0, 12.0), result)
    }

    @Test
    fun `div operator divides components`() {
        val result = Point2D(8.0, 12.0) / 4.0
        assertEquals(Point2D(2.0, 3.0), result)
    }

    @Test
    fun `unaryMinus negates components`() {
        val result = -Point2D(3.0, -5.0)
        assertEquals(Point2D(-3.0, 5.0), result)
    }

    @Test
    fun `distanceTo computes euclidean distance`() {
        val a = Point2D(0.0, 0.0)
        val b = Point2D(3.0, 4.0)
        assertEquals(5.0, a.distanceTo(b), 0.0001)
    }

    @Test
    fun `distanceTo is symmetric`() {
        val a = Point2D(1.0, 2.0)
        val b = Point2D(4.0, 6.0)
        assertEquals(a.distanceTo(b), b.distanceTo(a), 0.0001)
    }

    @Test
    fun `distanceTo self is zero`() {
        val p = Point2D(7.0, 11.0)
        assertEquals(0.0, p.distanceTo(p), 0.0001)
    }

    @Test
    fun `distanceToSquared avoids sqrt`() {
        val a = Point2D(0.0, 0.0)
        val b = Point2D(3.0, 4.0)
        assertEquals(25.0, a.distanceToSquared(b), 0.0001)
    }

    @Test
    fun `distanceToSquared equals distanceTo squared`() {
        val a = Point2D(1.5, 2.5)
        val b = Point2D(4.5, 6.5)
        val dist = a.distanceTo(b)
        assertEquals(dist * dist, a.distanceToSquared(b), 0.0001)
    }

    @Test
    fun `ZERO constant is origin`() {
        assertEquals(Point2D(0.0, 0.0), Point2D.ZERO)
    }

    @Test
    fun `midpoint computation via operators`() {
        val a = Point2D(2.0, 4.0)
        val b = Point2D(6.0, 8.0)
        val midpoint = (a + b) / 2.0
        assertEquals(Point2D(4.0, 6.0), midpoint)
    }

    @Test
    fun `operator chaining works`() {
        val a = Point2D(1.0, 1.0)
        val b = Point2D(3.0, 3.0)
        // Lerp: a + (b - a) * 0.5
        val lerp = a + (b - a) * 0.5
        assertEquals(Point2D(2.0, 2.0), lerp)
    }

    @Test
    fun `within radius check using distanceToSquared`() {
        val center = Point2D(5.0, 5.0)
        val nearby = Point2D(5.5, 5.5)
        val faraway = Point2D(50.0, 50.0)
        val radiusSq = 2.0 * 2.0

        assertTrue(center.distanceToSquared(nearby) < radiusSq)
        assertTrue(center.distanceToSquared(faraway) > radiusSq)
    }
}
