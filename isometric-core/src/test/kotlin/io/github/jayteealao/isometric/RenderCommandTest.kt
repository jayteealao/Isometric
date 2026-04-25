package io.github.jayteealao.isometric

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RenderCommandTest {

    private fun triPath(): Path = Path(
        listOf(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0)),
    )

    @Test
    fun `faceVertexCount below minimum is rejected (M-11)`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            RenderCommand(
                commandId = "below-min",
                points = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.5, 1.0),
                color = IsoColor(255.0, 255.0, 255.0),
                originalPath = triPath(),
                originalShape = null,
                faceVertexCount = 2,
            )
        }
        assert(ex.message?.contains("3..24") == true) { "Expected range hint in: ${ex.message}" }
    }

    @Test
    fun `faceVertexCount above maximum is rejected (M-11)`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            RenderCommand(
                commandId = "above-max",
                points = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.5, 1.0),
                color = IsoColor(255.0, 255.0, 255.0),
                originalPath = triPath(),
                originalShape = null,
                faceVertexCount = 25,
            )
        }
        assert(ex.message?.contains("3..24") == true) { "Expected range hint in: ${ex.message}" }
    }

    @Test
    fun `faceVertexCount at boundaries 3 and 24 is accepted (M-11)`() {
        val low = RenderCommand(
            commandId = "tri",
            points = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.5, 1.0),
            color = IsoColor(255.0, 255.0, 255.0),
            originalPath = triPath(),
            originalShape = null,
            faceVertexCount = 3,
        )
        assertEquals(3, low.faceVertexCount)

        val high = RenderCommand(
            commandId = "ngon",
            points = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.5, 1.0),
            color = IsoColor(255.0, 255.0, 255.0),
            originalPath = triPath(),
            originalShape = null,
            faceVertexCount = 24,
        )
        assertEquals(24, high.faceVertexCount)
    }
}
