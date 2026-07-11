package io.github.jayteealao.isometric.shapes

import io.github.jayteealao.isometric.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Geometric unit tests for Octahedron (B1).
 *
 * The `scale(center, sqrt(2)/2, sqrt(2)/2, 1.0)` post-scale pulls the equatorial square
 * in from the cube corners (radius √2/2) to radius 0.5, so every one of the six vertices
 * sits on a sphere of radius 0.5 about the center — a *regular* octahedron with twelve
 * equal edges. Because it stands on a vertex, its footprint is ~0.707 across x/y and a
 * full 1.0 on z; it deliberately does NOT fill the unit cube in x/y.
 *
 * The regularity assertion below is the load-bearing guard: it fails on the un-scaled
 * geometry (equator at radius 0.707 ≠ poles at 0.5) and passes only when the shape is
 * genuinely regular. The span assertions pin the resulting 0.707 x/y footprint.
 */
class OctahedronGeometryTest {

    private val epsilon = 1e-9
    private val oct = Octahedron()
    private val allPoints get() = oct.paths.flatMap { it.points }
    private val center = Point(0.5, 0.5, 0.5)

    // --- regularity: the load-bearing guard (fails on the un-scaled square bipyramid) ---

    @Test
    fun `Octahedron is regular — all vertices lie on a radius-0_5 sphere about the center`() {
        for (p in allPoints) {
            val dx = p.x - center.x
            val dy = p.y - center.y
            val dz = p.z - center.z
            val radius = Math.sqrt(dx * dx + dy * dy + dz * dz)
            assertTrue(
                Math.abs(radius - 0.5) < 1e-6,
                "Vertex ($p) is at radius $radius, not 0.5 — geometry is not regular " +
                    "(un-scaled equator sits at ~0.707)"
            )
        }
    }

    // --- span: pins the ~0.707 x/y footprint of the regular octahedron ---

    @Test
    fun `Octahedron X span is the regular ~0_707 footprint (min ~0_146, max ~0_854)`() {
        val minX = allPoints.minOf { it.x }
        val maxX = allPoints.maxOf { it.x }
        assertTrue(Math.abs(minX - 0.14644660940672627) < 1e-6, "Expected minX ~0.1464 but got $minX")
        assertTrue(Math.abs(maxX - 0.8535533905932737) < 1e-6, "Expected maxX ~0.8536 but got $maxX")
    }

    @Test
    fun `Octahedron Y span is the regular ~0_707 footprint (min ~0_146, max ~0_854)`() {
        val minY = allPoints.minOf { it.y }
        val maxY = allPoints.maxOf { it.y }
        assertTrue(Math.abs(minY - 0.14644660940672627) < 1e-6, "Expected minY ~0.1464 but got $minY")
        assertTrue(Math.abs(maxY - 0.8535533905932737) < 1e-6, "Expected maxY ~0.8536 but got $maxY")
    }

    // --- baseline assertions (pass both before and after fix) ---

    @Test
    fun `Octahedron Z span covers full unit cube (min near 0, max near 1)`() {
        val minZ = allPoints.minOf { it.z }
        val maxZ = allPoints.maxOf { it.z }
        assertTrue(minZ <= epsilon, "Expected minZ <= 0+ε but got $minZ")
        assertTrue(maxZ >= 1.0 - epsilon, "Expected maxZ >= 1-ε but got $maxZ")
    }

    @Test
    fun `Octahedron has exactly 8 paths (B1 path-count guard)`() {
        // This guard is co-located from IsometricEngineTest line 298; it must survive
        // the geometry change. Removing the scale() call does not change the loop that
        // produces 4 rotations × 2 triangles = 8 paths.
        assertEquals(8, oct.paths.size)
    }

    @Test
    fun `All 8 Octahedron paths have non-zero projected area (winding guard)`() {
        // Each triangular face should have non-degenerate area in 3D space.
        // Computed as |AB × AC| / 2. If the corrected geometry degenerates any face,
        // this catches it — a B1 depth-sort regression would manifest here.
        for ((i, path) in oct.paths.withIndex()) {
            val pts = path.points
            assertTrue(pts.size >= 3, "Path $i has fewer than 3 points")
            val ax = pts[1].x - pts[0].x; val ay = pts[1].y - pts[0].y; val az = pts[1].z - pts[0].z
            val bx = pts[2].x - pts[0].x; val by = pts[2].y - pts[0].y; val bz = pts[2].z - pts[0].z
            // Cross product magnitude
            val cx = ay * bz - az * by
            val cy = az * bx - ax * bz
            val cz = ax * by - ay * bx
            val area = Math.sqrt(cx * cx + cy * cy + cz * cz) / 2.0
            assertTrue(area > 1e-6, "Path $i has near-zero area ($area) — possible winding collapse")
        }
    }

    @Test
    fun `Octahedron at non-origin position carries the position through`() {
        val pos = Point(2.0, 3.0, 4.0)
        val shifted = Octahedron(pos)
        val minX = shifted.paths.flatMap { it.points }.minOf { it.x }
        val minY = shifted.paths.flatMap { it.points }.minOf { it.y }
        val minZ = shifted.paths.flatMap { it.points }.minOf { it.z }
        assertTrue(minX >= 2.0 - epsilon, "Expected minX >= 2-ε, got $minX")
        assertTrue(minY >= 3.0 - epsilon, "Expected minY >= 3-ε, got $minY")
        assertTrue(minZ >= 4.0 - epsilon, "Expected minZ >= 4-ε, got $minZ")
    }
}
