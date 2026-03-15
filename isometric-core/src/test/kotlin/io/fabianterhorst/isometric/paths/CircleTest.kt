package io.fabianterhorst.isometric.paths

import io.fabianterhorst.isometric.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CircleTest {

    @Test
    fun `constructor rejects non-positive radius with circle message`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Circle(Point.ORIGIN, radius = 0.0, vertices = 20)
        }

        assertEquals("Circle radius must be positive, got 0.0", error.message)
    }

    @Test
    fun `constructor rejects too few vertices with circle message`() {
        val error = assertFailsWith<IllegalArgumentException> {
            Circle(Point.ORIGIN, radius = 1.0, vertices = 2)
        }

        assertEquals("Circle needs at least 3 vertices, got 2", error.message)
    }
}
