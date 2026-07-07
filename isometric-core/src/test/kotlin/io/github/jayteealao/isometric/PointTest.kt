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
    fun `rotateX CCW around X axis right-handed convention`() {
        // CCW right-handed: Point(0,1,0).rotateX(PI/2) == Point(0,0,1).
        // This test MUST fail against the pre-fix implementation where
        // newY = pZ*sin + pY*cos and newZ = pZ*cos - pY*sin (gives (0,1,0)).
        val result = Point(0.0, 1.0, 0.0).rotateX(Point.ORIGIN, PI / 2)
        assertEquals(0.0, result.x, 1e-10)
        assertEquals(0.0, result.y, 1e-10)
        assertEquals(1.0, result.z, 1e-10)
    }

    @Test
    fun `rotateY CCW around Y axis right-handed convention`() {
        // CCW right-handed: Point(0,0,1).rotateY(PI/2) == Point(1,0,0).
        // This test MUST fail against the pre-fix implementation where
        // newX = pX*cos - pZ*sin and newZ = pX*sin + pZ*cos (gives (0,0,1) for this input? Let's check:
        // pX=0, pZ=1 → newX=0*cos-1*sin=−sin(π/2)=−1, newZ=0*sin+1*cos=0. Pre-fix gives (-1,0,0) ≠ (1,0,0).
        val result = Point(0.0, 0.0, 1.0).rotateY(Point.ORIGIN, PI / 2)
        assertEquals(1.0, result.x, 1e-10)
        assertEquals(0.0, result.y, 1e-10)
        assertEquals(0.0, result.z, 1e-10)
    }

    @Test
    fun `distanceToSegmentSquared includes z component`() {
        // AC-A2: segment from (0,0,0) to (0,0,2), query (0,0,1): on segment, distance=0.
        // A 2D-only implementation would use only x/y in the dot product and would
        // project to the segment endpoints (all have x=y=0), returning distance-to-point
        // rather than 0 — this is the constructive proof that the fix is load-bearing.
        val vZ = Point(0.0, 0.0, 0.0)
        val wZ = Point(0.0, 0.0, 2.0)
        assertEquals(0.0, Point.distanceToSegmentSquared(Point(0.0, 0.0, 1.0), vZ, wZ), 1e-12)

        // Segment from (0,0,0) to (0,0,4). Point at (0,0,2): on segment, distance=0.
        val v = Point(0.0, 0.0, 0.0)
        val w = Point(0.0, 0.0, 4.0)
        val onSeg = Point(0.0, 0.0, 2.0)
        assertEquals(0.0, Point.distanceToSegmentSquared(onSeg, v, w), 1e-12)

        // Point at (1,0,2): perpendicular foot is (0,0,2), squared distance = 1.
        val offSeg = Point(1.0, 0.0, 2.0)
        assertEquals(1.0, Point.distanceToSegmentSquared(offSeg, v, w), 1e-12)

        // Degenerate segment (v == v, i.e., w same as v at origin): distance-to-point.
        // Distance from (0,0,5) to (0,0,0) squared = 5^2 = 25.
        val degen = Point(0.0, 0.0, 5.0)
        assertEquals(25.0, Point.distanceToSegmentSquared(degen, v, v), 1e-12)
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
