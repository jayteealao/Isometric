package io.github.jayteealao.isometric.webgpu.triangulation

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.ProjectionParams
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.Vector
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderCommandTriangulatorTest {
    @Test
    fun quadProducesTwoTriangles() {
        val triangulator = RenderCommandTriangulator()
        val scene = PreparedScene(
            commands = listOf(
                RenderCommand(
                    commandId = "quad",
                    points = doubleArrayOf(
                        0.0, 0.0,
                        10.0, 0.0,
                        10.0, 10.0,
                        0.0, 10.0,
                    ),
                    color = IsoColor(255.0, 0.0, 0.0),
                    originalPath = Path(listOf(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0))),
                    originalShape = null,
                )
            ),
            width = 100,
            height = 100,
            projectionParams = ProjectionParams(1.0, 0.5, -1.0, 0.5, 20.0, 0.5, IsoColor.WHITE),
            lightDirection = Vector(1.0, 1.0, 1.0),
        )

        val packed = triangulator.pack(scene)

        assertEquals(6, packed.vertexCount)
        assertEquals(6 * RenderCommandTriangulator.BYTES_PER_VERTEX, packed.buffer.remaining())
    }
}
