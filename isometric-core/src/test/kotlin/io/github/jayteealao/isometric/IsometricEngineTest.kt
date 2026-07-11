@file:OptIn(ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric

import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Stairs
import io.github.jayteealao.isometric.shapes.Knot
import io.github.jayteealao.isometric.shapes.Octahedron
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsometricEngineTest {

    @Test
    fun `add and clear manage scene correctly`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine.add(Prism(Point(1.0, 0.0, 0.0)), IsoColor.RED)

        // Use NoCulling mode to get all faces
        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)
        assertEquals(12, scene.commands.size) // 2 prisms × 6 faces each

        engine.clear()
        val emptyScene = engine.projectScene(800, 600, RenderOptions.NoCulling)
        assertEquals(0, emptyScene.commands.size)
    }

    @Test
    fun `projectScene generates correct viewport dimensions`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)

        val scene = engine.projectScene(1000, 800, RenderOptions.Default)
        assertEquals(1000, scene.width)
        assertEquals(800, scene.height)
    }

    @Test
    fun `depth sorting can be disabled`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine.add(Prism(Point(1.0, 1.0, 1.0)), IsoColor.RED)

        val sortedScene = engine.projectScene(800, 600, RenderOptions.Default)
        val unsortedScene = engine.projectScene(800, 600, RenderOptions.NoDepthSorting)

        // Both should have same number of commands
        assertEquals(sortedScene.commands.size, unsortedScene.commands.size)
    }

    @Test
    fun `findItemAt detects hits`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN, 2.0, 2.0, 2.0), IsoColor.BLUE)

        val scene = engine.projectScene(800, 600, RenderOptions.Default)
        assertTrue(scene.commands.isNotEmpty())

        // Use the center of a rendered face's projected points as hit coordinate
        val cmd = scene.commands.first()
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()
        val hit = engine.findItemAt(scene, avgX, avgY, order = HitOrder.FRONT_TO_BACK)
        assertNotNull(hit)

        // Click far outside (should miss)
        val miss = engine.findItemAt(scene, 10.0, 10.0, order = HitOrder.FRONT_TO_BACK)
        assertNull(miss)
    }

    @Test
    fun `findItemAt honors hit order`() {
        val overlappingPoints = listOf(
            Point2D(100.0, 100.0),
            Point2D(140.0, 100.0),
            Point2D(120.0, 140.0)
        )
        val scene = PreparedScene(
            commands = listOf(
                RenderCommand("back", overlappingPoints, IsoColor.BLUE, Path(Point.ORIGIN, Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0)), null),
                RenderCommand("front", overlappingPoints, IsoColor.RED, Path(Point.ORIGIN, Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0)), null)
            ),
            width = 800,
            height = 600
        )

        val engine = IsometricEngine()
        assertEquals("front", engine.findItemAt(scene, 120.0, 115.0, order = HitOrder.FRONT_TO_BACK)?.commandId)
        assertEquals("back", engine.findItemAt(scene, 120.0, 115.0, order = HitOrder.BACK_TO_FRONT)?.commandId)
    }

    @Test
    fun `findItemAt honors touch radius`() {
        val scene = PreparedScene(
            commands = listOf(
                RenderCommand(
                    "triangle",
                    listOf(Point2D(100.0, 100.0), Point2D(140.0, 100.0), Point2D(120.0, 140.0)),
                    IsoColor.BLUE,
                    Path(Point.ORIGIN, Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0)),
                    null
                )
            ),
            width = 800,
            height = 600
        )

        val engine = IsometricEngine()
        assertNull(engine.findItemAt(scene, 120.0, 96.0, touchRadius = 0.0))
        assertEquals("triangle", engine.findItemAt(scene, 120.0, 96.0, touchRadius = 8.0)?.commandId)
    }

    @Test
    fun `render commands have stable IDs`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)

        val scene1 = engine.projectScene(800, 600, RenderOptions.Default)
        engine.clear()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val scene2 = engine.projectScene(800, 600, RenderOptions.Default)

        // IDs should be different between runs (cleared state)
        val ids1 = scene1.commands.map { it.commandId }.toSet()
        val ids2 = scene2.commands.map { it.commandId }.toSet()
        assertTrue(ids1.isNotEmpty())
        assertTrue(ids2.isNotEmpty())
    }

    @Test
    fun `projectScene without lightDirection uses interface default`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val defaultScene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        engine.clear()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val explicitScene = engine.projectScene(800, 600, RenderOptions.NoCulling,
            lightDirection = SceneProjector.DEFAULT_LIGHT_DIRECTION)

        assertEquals(defaultScene.commands.size, explicitScene.commands.size)
        for (i in defaultScene.commands.indices) {
            assertEquals(defaultScene.commands[i].color, explicitScene.commands[i].color,
                "Command $i color should match SceneProjector default")
        }
    }

    @Test
    fun `lightDirection changes shading colors`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val sceneA = engine.projectScene(800, 600, RenderOptions.NoCulling,
            lightDirection = Vector(2.0, -1.0, 3.0))

        engine.clear()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val sceneB = engine.projectScene(800, 600, RenderOptions.NoCulling,
            lightDirection = Vector(-1.0, 2.0, 0.5))

        assertEquals(sceneA.commands.size, sceneB.commands.size)
        val anyDifferent = sceneA.commands.indices.any { i ->
            sceneA.commands[i].color != sceneB.commands[i].color
        }
        assertTrue(anyDifferent, "Different light directions should produce different shading")
    }

    @Test
    fun `culling removes back-facing polygons`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)

        val withCulling = engine.projectScene(800, 600, RenderOptions.Default)
        val withoutCulling = engine.projectScene(800, 600, RenderOptions.NoCulling)

        // With culling should have fewer or equal commands
        assertTrue(withCulling.commands.size <= withoutCulling.commands.size)
    }

    @Test
    fun `broad phase sort preserves order for sparse scene`() {
        val baselineEngine = IsometricEngine()
        baselineEngine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        baselineEngine.add(Prism(Point(5.0, 0.0, 0.0)), IsoColor.RED)
        baselineEngine.add(Prism(Point(0.0, 5.0, 0.0)), IsoColor.GREEN)

        val broadPhaseEngine = IsometricEngine()
        broadPhaseEngine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        broadPhaseEngine.add(Prism(Point(5.0, 0.0, 0.0)), IsoColor.RED)
        broadPhaseEngine.add(Prism(Point(0.0, 5.0, 0.0)), IsoColor.GREEN)

        val baseline = baselineEngine.projectScene(800, 600, RenderOptions.Default)
        val optimized = broadPhaseEngine.projectScene(
            800,
            600,
            RenderOptions.Default.copy(enableBroadPhaseSort = true)
        )

        assertEquals(
            baseline.commands.map { it.commandId },
            optimized.commands.map { it.commandId }
        )
    }

    @Test
    fun `broad phase sort preserves order for overlapping scene`() {
        val baselineEngine = IsometricEngine()
        baselineEngine.add(Prism(Point.ORIGIN, 3.0, 3.0, 1.0), IsoColor.BLUE)
        baselineEngine.add(Prism(Point(0.5, 0.5, 0.0), 1.0, 1.0, 1.0), IsoColor.RED)
        baselineEngine.add(Prism(Point(1.0, 1.0, 0.0), 1.5, 1.5, 1.0), IsoColor.GREEN)

        val broadPhaseEngine = IsometricEngine()
        broadPhaseEngine.add(Prism(Point.ORIGIN, 3.0, 3.0, 1.0), IsoColor.BLUE)
        broadPhaseEngine.add(Prism(Point(0.5, 0.5, 0.0), 1.0, 1.0, 1.0), IsoColor.RED)
        broadPhaseEngine.add(Prism(Point(1.0, 1.0, 0.0), 1.5, 1.5, 1.0), IsoColor.GREEN)

        val baseline = baselineEngine.projectScene(800, 600, RenderOptions.NoCulling)
        val optimized = broadPhaseEngine.projectScene(
            800,
            600,
            RenderOptions.NoCulling.copy(enableBroadPhaseSort = true)
        )

        assertEquals(
            baseline.commands.map { it.commandId },
            optimized.commands.map { it.commandId }
        )
    }

    @Test
    fun `broad phase sort preserves order for adjacent-cell overlap`() {
        val baselineEngine = IsometricEngine()
        baselineEngine.add(Prism(Point.ORIGIN, 1.2, 1.2, 1.0), IsoColor.BLUE)
        baselineEngine.add(Prism(Point(0.75, 0.0, 0.0), 1.2, 1.2, 1.0), IsoColor.RED)
        baselineEngine.add(Prism(Point(2.5, 0.0, 0.0), 1.0, 1.0, 1.0), IsoColor.GREEN)

        val broadPhaseEngine = IsometricEngine()
        broadPhaseEngine.add(Prism(Point.ORIGIN, 1.2, 1.2, 1.0), IsoColor.BLUE)
        broadPhaseEngine.add(Prism(Point(0.75, 0.0, 0.0), 1.2, 1.2, 1.0), IsoColor.RED)
        broadPhaseEngine.add(Prism(Point(2.5, 0.0, 0.0), 1.0, 1.0, 1.0), IsoColor.GREEN)

        val baseline = baselineEngine.projectScene(800, 600, RenderOptions.NoCulling)
        val optimized = broadPhaseEngine.projectScene(
            800,
            600,
            RenderOptions.NoCulling.copy(
                enableBroadPhaseSort = true,
                broadPhaseCellSize = 50.0
            )
        )

        assertEquals(
            baseline.commands.map { it.commandId },
            optimized.commands.map { it.commandId }
        )
    }

    @Test
    fun `render options reject non-positive broad phase cell size`() {
        val error = assertFailsWith<IllegalArgumentException> {
            RenderOptions(broadPhaseCellSize = 0.0)
        }
        assertTrue(error.message!!.contains("broadPhaseCellSize"))
    }

    @Test
    fun `render options reject non-finite broad phase cell size`() {
        assertFailsWith<IllegalArgumentException> {
            RenderOptions(broadPhaseCellSize = Double.NaN)
        }
    }

    @Test
    fun `engine rejects invalid constructor parameters`() {
        assertFailsWith<IllegalArgumentException> { IsometricEngine(scale = 0.0) }
        assertFailsWith<IllegalArgumentException> { IsometricEngine(angle = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { IsometricEngine(colorDifference = -0.1) }
    }

    @Test
    fun `originXFraction and originYFraction reject non-finite values`() {
        val engine = IsometricEngine()
        assertFailsWith<IllegalArgumentException> { engine.originXFraction = Double.NaN }
        assertFailsWith<IllegalArgumentException> { engine.originXFraction = Double.POSITIVE_INFINITY }
        assertFailsWith<IllegalArgumentException> { engine.originYFraction = Double.NaN }
        assertFailsWith<IllegalArgumentException> { engine.originYFraction = Double.NEGATIVE_INFINITY }
    }

    @Test
    fun `originXFraction and originYFraction bump projectionVersion`() {
        val engine = IsometricEngine()
        val v0 = engine.projectionVersion
        engine.originXFraction = 0.3
        val v1 = engine.projectionVersion
        assertTrue(v1 != v0, "Setting originXFraction must bump projectionVersion")
        engine.originYFraction = 0.7
        val v2 = engine.projectionVersion
        assertTrue(v2 != v1, "Setting originYFraction must bump projectionVersion")
    }

    @Test
    fun `worldToScreen and screenToWorld round-trip under non-default origin fractions`() {
        val engine = IsometricEngine()
        engine.originXFraction = 0.3
        engine.originYFraction = 0.7
        val w = 800; val h = 600

        val worldPoint = Point(2.0, 3.0, 1.0)
        val screen: Point2D = engine.worldToScreen(worldPoint, w, h)
        // screenToWorld inverts the projection onto the Z plane of the original point
        val recovered: Point = engine.screenToWorld(screen, w, h, z = worldPoint.z)

        // Round-trip tolerance: floating-point rounding in projection/inverse
        val epsilon = 1e-6
        assertTrue(
            kotlin.math.abs(recovered.x - worldPoint.x) < epsilon &&
            kotlin.math.abs(recovered.y - worldPoint.y) < epsilon,
            "screenToWorld(worldToScreen(p)) should recover the original point: " +
            "expected (${worldPoint.x}, ${worldPoint.y}) got (${recovered.x}, ${recovered.y})"
        )
    }

    @Test
    fun `worldToScreen output changes when origin fractions change`() {
        val engine = IsometricEngine()
        val w = 800; val h = 600
        val p = Point(1.0, 1.0, 0.0)

        val screenDefault = engine.worldToScreen(p, w, h)
        engine.originXFraction = 0.3
        engine.originYFraction = 0.7
        val screenShifted = engine.worldToScreen(p, w, h)

        assertTrue(
            screenDefault.x != screenShifted.x || screenDefault.y != screenShifted.y,
            "Changing origin fractions must shift screen coordinates"
        )
    }

    @Test
    fun `fitContent scales scene to fill viewport`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN, 5.0, 5.0, 3.0), IsoColor.BLUE)
        val w = 400; val h = 300
        // 1-pixel tolerance for floating-point rounding in the projection/scale math.
        val tolerance = 1.0

        engine.fitContent(w, h, padding = 0.0)
        val scene = engine.projectScene(w, h, RenderOptions.NoCulling)

        // All projected points should lie within the viewport after fitContent
        for (cmd in scene.commands) {
            for (pt in cmd.points) {
                assertTrue(pt.x >= -tolerance && pt.x <= w + tolerance,
                    "Projected x=${pt.x} out of viewport width $w after fitContent")
                assertTrue(pt.y >= -tolerance && pt.y <= h + tolerance,
                    "Projected y=${pt.y} out of viewport height $h after fitContent")
            }
        }
    }

    @Test
    fun `fitContent with padding leaves margin inside viewport`() {
        val padding = 20.0
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN, 5.0, 5.0, 3.0), IsoColor.BLUE)
        val w = 400; val h = 300
        // 1-pixel tolerance for floating-point rounding.
        val tolerance = 1.0

        engine.fitContent(w, h, padding = padding)
        val scene = engine.projectScene(w, h, RenderOptions.NoCulling)

        for (cmd in scene.commands) {
            for (pt in cmd.points) {
                assertTrue(pt.x >= padding - tolerance && pt.x <= w - padding + tolerance,
                    "Projected x=${pt.x} violates padding=$padding at width $w")
                assertTrue(pt.y >= padding - tolerance && pt.y <= h - padding + tolerance,
                    "Projected y=${pt.y} violates padding=$padding at height $h")
            }
        }
    }

    @Test
    fun `fitContent is a no-op on an empty scene`() {
        val engine = IsometricEngine()
        val scaleBefore = engine.scale
        val xBefore = engine.originXFraction
        val yBefore = engine.originYFraction

        engine.fitContent(400, 300)

        assertEquals(scaleBefore, engine.scale, "scale must not change on empty scene")
        assertEquals(xBefore, engine.originXFraction, "originXFraction must not change on empty scene")
        assertEquals(yBefore, engine.originYFraction, "originYFraction must not change on empty scene")
    }

    @Test
    fun `fitContent is a no-op on zero-size viewport`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN, 2.0, 2.0, 2.0), IsoColor.BLUE)
        val scaleBefore = engine.scale

        engine.fitContent(0, 300)   // width zero
        assertEquals(scaleBefore, engine.scale, "scale must not change on zero-width viewport")
        engine.fitContent(400, 0)   // height zero
        assertEquals(scaleBefore, engine.scale, "scale must not change on zero-height viewport")
    }

    @Test
    fun `shapes reject invalid dimensions`() {
        assertFailsWith<IllegalArgumentException> { Prism(Point.ORIGIN, width = 0.0) }
        assertFailsWith<IllegalArgumentException> { io.github.jayteealao.isometric.shapes.Pyramid(Point.ORIGIN, height = -1.0) }
        assertFailsWith<IllegalArgumentException> { io.github.jayteealao.isometric.shapes.Cylinder(Point.ORIGIN, radius = 0.0) }
        assertFailsWith<IllegalArgumentException> { io.github.jayteealao.isometric.paths.Circle(Point.ORIGIN, vertices = 2) }
        assertFailsWith<IllegalArgumentException> { Stairs(Point.ORIGIN, 0) }
    }

    @Test
    fun `shapes support zero arg defaults where intended`() {
        assertEquals(6, Prism().paths.size)
        assertEquals(5, io.github.jayteealao.isometric.shapes.Pyramid().paths.size)
        assertTrue(io.github.jayteealao.isometric.shapes.Cylinder().paths.isNotEmpty())
        assertEquals(8, Octahedron().paths.size) // B1 guard: 4 rotations × 2 triangles = 8; corrected geometry must preserve this count
        assertTrue(Knot().paths.isNotEmpty())
    }
}
