package io.github.jayteealao.isometric.shapes

import io.github.jayteealao.isometric.Point
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Asserts that Cylinder's own validation messages are reachable (B3).
 *
 * Before the B3 fix, Circle's validation fires first because the primary constructor
 * delegates to Shape(Shape.extrude(Circle(…), height).paths) — Circle's requires
 * fire before Cylinder's init block runs. The companion factory pattern moves
 * Cylinder's requires to execute before Circle is instantiated, making these
 * message assertions pass.
 */
class CylinderValidationTest {

    @Test
    fun `Cylinder rejects negative radius with Cylinder message`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Cylinder(Point.ORIGIN, radius = -1.0)
        }
        assertTrue(
            ex.message!!.contains("Cylinder"),
            "Expected 'Cylinder' in message, got: ${ex.message}"
        )
    }

    @Test
    fun `Cylinder rejects too few vertices with Cylinder message`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Cylinder(Point.ORIGIN, radius = 1.0, vertices = 2)
        }
        assertTrue(
            ex.message!!.contains("Cylinder"),
            "Expected 'Cylinder' in message, got: ${ex.message}"
        )
    }

    @Test
    fun `Cylinder rejects non-positive height with Cylinder message`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Cylinder(Point.ORIGIN, radius = 1.0, height = -1.0)
        }
        assertTrue(
            ex.message!!.contains("Cylinder"),
            "Expected 'Cylinder' in message, got: ${ex.message}"
        )
    }

    @Test
    fun `Cylinder with valid parameters constructs without error`() {
        // Should not throw
        val c = Cylinder(Point.ORIGIN, radius = 1.0, height = 1.0, vertices = 3)
        assertTrue(c.paths.isNotEmpty())
    }
}
