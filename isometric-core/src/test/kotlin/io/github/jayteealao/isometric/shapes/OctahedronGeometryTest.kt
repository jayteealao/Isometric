package io.github.jayteealao.isometric.shapes

import io.github.jayteealao.isometric.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Geometric unit tests for Octahedron (B1).
 *
 * The four equatorial vertices sit at (0,0,0.5), (1,0,0.5), (1,1,0.5), (0,1,0.5)
 * and the two apices at (0.5,0.5,0) and (0.5,0.5,1.0) — a regular octahedron
 * inscribed in the unit cube, as the KDoc documents. The now-removed
 * scale(center, sqrt(2)/2, sqrt(2)/2, 1.0) call compressed X and Y to ~70.7%,
 * producing a 0.707×0.707×1.0 bounding box that contradicts this.
 *
 * The constructive proofs below have assertions that fail on the pre-fix geometry
 * and pass after removing the non-uniform post-scale.
 */
class OctahedronGeometryTest {

    private val epsilon = 1e-9
    private val oct = Octahedron()
    private val allPoints get() = oct.paths.flatMap { it.points }

    // --- constructive proof assertions (fail pre-fix, pass post-fix) ---

    @Test
    fun `Octahedron X span covers full unit cube (min near 0, max near 1)`() {
        val minX = allPoints.minOf { it.x }
        val maxX = allPoints.maxOf { it.x }
        assertTrue(minX <= epsilon, "Expected minX <= 0+ε but got $minX (pre-fix: ~0.146)")
        assertTrue(maxX >= 1.0 - epsilon, "Expected maxX >= 1-ε but got $maxX (pre-fix: ~0.854)")
    }

    @Test
    fun `Octahedron Y span covers full unit cube (min near 0, max near 1)`() {
        val minY = allPoints.minOf { it.y }
        val maxY = allPoints.maxOf { it.y }
        assertTrue(minY <= epsilon, "Expected minY <= 0+ε but got $minY (pre-fix: ~0.146)")
        assertTrue(maxY >= 1.0 - epsilon, "Expected maxY >= 1-ε but got $maxY (pre-fix: ~0.854)")
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
