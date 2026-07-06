package io.github.jayteealao.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid

class ShapeTest {

    @Test
    fun `extrude creates 3D shape from 2D path`() {
        val square = Path(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            Point(1.0, 1.0, 0.0),
            Point(0.0, 1.0, 0.0)
        )
        val extruded = Shape.extrude(square, 1.0)
        // Square has 4 sides + 2 faces (top/bottom) = 6 paths
        assertEquals(6, extruded.paths.size)
    }

    @Test
    fun `translate moves all paths`() {
        val path1 = Path(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            Point(0.0, 1.0, 0.0)
        )
        val shape = Shape(path1)
        val translated = shape.translate(1.0, 2.0, 3.0)
        assertEquals(Point(1.0, 2.0, 3.0), translated.paths[0].points[0])
    }

    @Test
    fun `orderedPaths sorts by depth`() {
        val farPath = Path(
            Point(0.0, 0.0, 10.0),
            Point(1.0, 0.0, 10.0),
            Point(0.0, 1.0, 10.0)
        )
        val nearPath = Path(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            Point(0.0, 1.0, 0.0)
        )
        val shape = Shape(nearPath, farPath)
        val ordered = shape.orderedPaths()
        // Far path should come first (drawn first, appears behind)
        assertTrue(ordered[0].depth > ordered[1].depth)
    }

    @Test
    fun `shape requires at least one path`() {
        assertFailsWith<IllegalArgumentException> { Shape(emptyList()) }
    }

    @Test
    fun `built in shape translate preserves subtype`() {
        val translated: Prism = Prism().translate(1.0, 2.0, 3.0)
        assertEquals(Point(1.0, 2.0, 3.0), translated.position)
    }

    @Test
    fun `rotate still returns generic shape`() {
        val rotated = Prism().rotateZ(Point.ORIGIN, 0.5)
        assertTrue(rotated !is Prism)
    }

    // M6 — Pyramid base face
    @Test
    fun `Pyramid has 5 faces`() {
        assertEquals(5, Pyramid().paths.size, "Pyramid must have 4 triangular faces + 1 base quad")
    }

    @Test
    fun `Pyramid base face vertices are all at position z`() {
        val pos = Point(1.0, 2.0, 3.0)
        val pyramid = Pyramid(pos, 2.0, 2.0, 1.0)
        // The base is the 5th face (index 4)
        val base = pyramid.paths[4]
        assertEquals(4, base.points.size, "Pyramid base must be a quad (4 vertices)")
        for (v in base.points) {
            assertEquals(pos.z, v.z, 1e-12, "All base vertices must be at z=${pos.z}, got ${v.z}")
        }
    }

    @Test
    fun `Pyramid base winding produces downward normal`() {
        // The base winding order should produce a negative Z normal (downward-facing),
        // which is back-face culled from the standard isometric view from above.
        val pyramid = Pyramid(Point.ORIGIN, 1.0, 1.0, 1.0)
        val base = pyramid.paths[4]
        val p0 = base.points[0]
        val p1 = base.points[1]
        val p2 = base.points[2]
        val ux = p1.x - p0.x; val uy = p1.y - p0.y; val uz = p1.z - p0.z
        val vx = p2.x - p0.x; val vy = p2.y - p0.y; val vz = p2.z - p0.z
        val nz = ux * vy - uy * vx  // Z component of cross product
        assertTrue(nz < 0.0, "Pyramid base normal must point downward (negative Z), got nz=$nz")
    }
}
