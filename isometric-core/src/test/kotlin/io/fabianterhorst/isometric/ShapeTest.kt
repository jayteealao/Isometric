package io.fabianterhorst.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val path1 = Path(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0))
        val shape = Shape(path1)
        val translated = shape.translate(1.0, 2.0, 3.0)
        assertEquals(Point(1.0, 2.0, 3.0), translated.paths[0].points[0])
    }

    @Test
    fun `orderedPaths sorts by depth`() {
        val farPath = Path(Point(0.0, 0.0, 10.0), Point(1.0, 0.0, 10.0))
        val nearPath = Path(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0))
        val shape = Shape(nearPath, farPath)
        val ordered = shape.orderedPaths()
        // Far path should come first (drawn first, appears behind)
        assertTrue(ordered[0].depth() > ordered[1].depth())
    }
}
