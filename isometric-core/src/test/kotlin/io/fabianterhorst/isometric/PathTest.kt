package io.fabianterhorst.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PathTest {

    @Test
    fun `reverse reverses point order`() {
        val path = Path(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0), Point(1.0, 1.0, 0.0))
        val reversed = path.reverse()
        assertEquals(path.points[0], reversed.points[2])
        assertEquals(path.points[2], reversed.points[0])
    }

    @Test
    fun `translate moves all points`() {
        val path = Path(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            Point(0.0, 1.0, 0.0)
        )
        val translated = path.translate(1.0, 2.0, 3.0)
        assertEquals(Point(1.0, 2.0, 3.0), translated.points[0])
        assertEquals(Point(2.0, 2.0, 3.0), translated.points[1])
    }

    @Test
    fun `depth calculates average`() {
        val path = Path(
            Point(0.0, 0.0, 0.0),
            Point(2.0, 2.0, 0.0),
            Point(0.0, 2.0, 0.0)
        )
        val expectedDepth = (0.0 + 4.0 + 2.0) / 3.0
        assertEquals(expectedDepth, path.depth, 0.0001)
    }

    @Test
    fun `path requires at least three points`() {
        assertFailsWith<IllegalArgumentException> {
            Path(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0))
        }
    }
}
