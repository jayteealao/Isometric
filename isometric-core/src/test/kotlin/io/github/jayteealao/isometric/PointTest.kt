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
    fun `depth(angle) at 30 degrees gives same relative order as depth()`() {
        // At α=30°, (x+y)*sin(30°)-2z = 0.5*(x+y-2z). A positive constant
        // factor preserves ordering, so depth(PI/6) and depth() must agree
        // on which point is farther for every pair.
        val points = listOf(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            Point(0.0, 1.0, 0.0),
            Point(2.0, 2.0, 1.0),
            Point(0.0, 0.0, 5.0),
            Point(3.0, 3.0, 3.0)
        )
        val alpha = PI / 6.0
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val orderDefault = points[i].depth().compareTo(points[j].depth())
                val orderAngle = points[i].depth(alpha).compareTo(points[j].depth(alpha))
                // Signs must agree (both positive, both negative, or both zero)
                assertTrue(
                    orderDefault * orderAngle >= 0,
                    "depth() and depth(PI/6) disagree on order for ${points[i]} vs ${points[j]}"
                )
            }
        }
    }

    @Test
    fun `depth(angle) is symmetric in x and y`() {
        // For any a, b, z, α: Point(a,b,z).depth(α) == Point(b,a,z).depth(α)
        // because the formula (x+y)*sin(α)-2z is invariant under x↔y swap.
        val testCases = listOf(
            Triple(1.0, 2.0, 0.5),
            Triple(3.0, 0.0, 1.0),
            Triple(-1.0, 4.0, 2.0),
            Triple(0.0, 0.0, 0.0)
        )
        for (alpha in listOf(PI / 6.0, PI / 4.0, PI / 3.0)) {
            for ((a, b, z) in testCases) {
                val d1 = Point(a, b, z).depth(alpha)
                val d2 = Point(b, a, z).depth(alpha)
                assertEquals(d1, d2, abs(d1) * 1e-12 + 1e-12,
                    "depth(angle) must be symmetric in x and y for ($a,$b,$z) at alpha=$alpha")
            }
        }
    }

    @Test
    fun `depth(angle) at 45 degrees matches formula`() {
        // At α=45°: x+y-z/sin(45°) = x+y-z*sqrt(2)
        val p = Point(2.0, 3.0, 1.0)
        val expected = 2.0 + 3.0 - 1.0 / kotlin.math.sin(PI / 4.0)
        assertEquals(expected, p.depth(PI / 4.0), 1e-10)
    }

    @Test
    fun `distanceToSegmentSquared includes z component`() {
        // Segment from (0,0,0) to (0,0,4). Point at (0,0,2): on segment, distance=0.
        val v = Point(0.0, 0.0, 0.0)
        val w = Point(0.0, 0.0, 4.0)
        val onSeg = Point(0.0, 0.0, 2.0)
        assertEquals(0.0, Point.distanceToSegmentSquared(onSeg, v, w), 1e-12)

        // Point at (1,0,2): perpendicular foot is (0,0,2), squared distance = 1.
        val offSeg = Point(1.0, 0.0, 2.0)
        assertEquals(1.0, Point.distanceToSegmentSquared(offSeg, v, w), 1e-12)
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
