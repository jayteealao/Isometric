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

    @Test
    fun nonConvexLShapeProducesFourTriangles() {
        // L-shape: 6 vertices forming a non-convex polygon. A naive triangle fan from
        // v0 would emit triangles covering the inner notch with wrong geometry. The
        // ear-clipping path must produce exactly N-2 = 4 valid triangles staying
        // inside the L footprint.
        //
        //   0 --- 1
        //   |     |
        //   |     2 --- 3
        //   |           |
        //   5 --------- 4
        val triangulator = RenderCommandTriangulator()
        val scene = PreparedScene(
            commands = listOf(
                RenderCommand(
                    commandId = "L",
                    points = doubleArrayOf(
                        0.0, 0.0,
                        10.0, 0.0,
                        10.0, 10.0,
                        20.0, 10.0,
                        20.0, 20.0,
                        0.0, 20.0,
                    ),
                    color = IsoColor(0.0, 200.0, 0.0),
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

        // 6-vertex polygon → 4 triangles → 12 vertices.
        assertEquals(12, packed.vertexCount)
        assertEquals(12 * RenderCommandTriangulator.BYTES_PER_VERTEX, packed.buffer.remaining())
    }

    @Test
    fun stairsZigzagSideProducesCorrectTriangleCount() {
        // Stairs side face for stepCount=3: zigzag with 2*N+2 = 8 vertices.
        // This is the actual non-convex shape that surfaced as I-2 in the stairs verify.
        // Coordinates are a synthetic right-side zigzag in 2D screen space (top-down):
        //
        //   0 ---- 1
        //   |     |
        //   |     2 -- 3
        //   |          |
        //   |          4 -- 5
        //   |               |
        //   7 -------------- 6
        val triangulator = RenderCommandTriangulator()
        val scene = PreparedScene(
            commands = listOf(
                RenderCommand(
                    commandId = "stairs-side-stepCount3",
                    points = doubleArrayOf(
                        0.0,  0.0,
                        10.0, 0.0,
                        10.0, 10.0,
                        20.0, 10.0,
                        20.0, 20.0,
                        30.0, 20.0,
                        30.0, 30.0,
                        0.0,  30.0,
                    ),
                    color = IsoColor(0.0, 0.0, 200.0),
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

        // 8-vertex polygon → 6 triangles → 18 vertices.
        assertEquals(18, packed.vertexCount)
        assertEquals(18 * RenderCommandTriangulator.BYTES_PER_VERTEX, packed.buffer.remaining())
    }

    @Test
    fun convexHexagonStaysOnFanFastPath() {
        // Regression guard: convex polygons must continue to take the O(n) fan path
        // with N-2 triangles. A 6-vertex hexagon → 4 triangles → 12 vertices.
        val triangulator = RenderCommandTriangulator()
        val scene = PreparedScene(
            commands = listOf(
                RenderCommand(
                    commandId = "hex",
                    points = doubleArrayOf(
                        0.0,  10.0,
                        10.0, 0.0,
                        20.0, 0.0,
                        30.0, 10.0,
                        20.0, 20.0,
                        10.0, 20.0,
                    ),
                    color = IsoColor(255.0, 255.0, 0.0),
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

        assertEquals(12, packed.vertexCount)
        assertEquals(12 * RenderCommandTriangulator.BYTES_PER_VERTEX, packed.buffer.remaining())
    }
}
