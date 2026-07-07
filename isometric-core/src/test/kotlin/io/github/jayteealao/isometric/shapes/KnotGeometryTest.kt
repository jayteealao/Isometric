@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.shapes

import io.github.jayteealao.isometric.Point
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Geometric unit tests for Knot (B2).
 *
 * The pre-fix Knot applied translate(-0.1, 0.15, 0.4) after the 1/5 scale as a
 * cosmetic centering step invisible to callers. This means Knot(Point.ORIGIN) was
 * actually offset from ORIGIN — the minimum X was approximately -0.02 instead of 0.0.
 *
 * Removing the intermediate translate collapses the two-step translation into one:
 * scaledPaths.map { it.translate(position.x, position.y, position.z) }.
 * After the fix, Knot(Point.ORIGIN) has its minimum X >= 0 (within floating-point
 * accumulation tolerance through scale + translate).
 *
 * The constructive proof assertions below fail on pre-fix geometry and pass after.
 */
class KnotGeometryTest {

    private val epsilon = 1e-9

    @Test
    fun `Knot at ORIGIN has non-negative minimum X (B2 constructive proof)`() {
        val knot = Knot(Point.ORIGIN)
        val minX = knot.paths.flatMap { it.points }.minOf { it.x }
        assertTrue(
            minX >= -epsilon,
            "Expected minX >= -ε (ORIGIN-placed), got $minX (pre-fix: ~-0.02 due to offset)"
        )
    }

    @Test
    fun `Knot at ORIGIN has non-negative minimum Y`() {
        val knot = Knot(Point.ORIGIN)
        val minY = knot.paths.flatMap { it.points }.minOf { it.y }
        assertTrue(
            minY >= -epsilon,
            "Expected minY >= -ε (ORIGIN-placed), got $minY"
        )
    }

    @Test
    fun `Knot at non-origin position shifts Z by the position offset`() {
        // The knot geometry includes a prism at Z=-2.0 (scaled to -0.4) by design.
        // A knot at position (0,0,1) should shift all Z coords by +1, so minZ increases by 1.
        val knotAtOrigin = Knot(Point.ORIGIN)
        val knotShiftedZ = Knot(Point(0.0, 0.0, 1.0))
        val minZOrigin = knotAtOrigin.paths.flatMap { it.points }.minOf { it.z }
        val minZShifted = knotShiftedZ.paths.flatMap { it.points }.minOf { it.z }
        assertTrue(
            Math.abs(minZShifted - minZOrigin - 1.0) < epsilon,
            "Expected minZ to shift by exactly +1.0 when position.z += 1.0; got shift=${minZShifted - minZOrigin}"
        )
    }

    @Test
    fun `Knot at non-origin position carries X position through (B2 carry-through)`() {
        // At position (1,0,0), min X should shift by exactly +1.0 from Knot(ORIGIN).
        val knotAtOrigin = Knot(Point.ORIGIN)
        val knotShiftedX = Knot(Point(1.0, 0.0, 0.0))
        val minXOrigin = knotAtOrigin.paths.flatMap { it.points }.minOf { it.x }
        val minXShifted = knotShiftedX.paths.flatMap { it.points }.minOf { it.x }
        assertTrue(
            Math.abs(minXShifted - minXOrigin - 1.0) < epsilon,
            "Expected minX to shift by exactly +1.0 when position.x += 1.0; got shift=${minXShifted - minXOrigin}"
        )
    }

    @Test
    fun `Knot produces at least one path`() {
        val knot = Knot()
        assertTrue(knot.paths.isNotEmpty())
    }
}
