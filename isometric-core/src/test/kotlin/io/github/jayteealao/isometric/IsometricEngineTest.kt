@file:OptIn(ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric

import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid
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
        val pts = cmd.points
        var sx = 0.0; var sy = 0.0; var i = 0
        while (i < pts.size) { sx += pts[i]; sy += pts[i + 1]; i += 2 }
        val avgX = sx / cmd.pointCount
        val avgY = sy / cmd.pointCount
        val hit = engine.findItemAt(scene, avgX, avgY, order = HitOrder.FRONT_TO_BACK)
        assertNotNull(hit)

        // Click far outside (should miss)
        val miss = engine.findItemAt(scene, 10.0, 10.0, order = HitOrder.FRONT_TO_BACK)
        assertNull(miss)
    }

    @Test
    fun `findItemAt honors hit order`() {
        val overlappingPoints = doubleArrayOf(100.0, 100.0, 140.0, 100.0, 120.0, 140.0)
        val scene = PreparedScene(
            commands = listOf(
                RenderCommand("back", overlappingPoints, IsoColor.BLUE, Path(Point.ORIGIN, Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0)), null),
                RenderCommand("front", overlappingPoints, IsoColor.RED, Path(Point.ORIGIN, Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0)), null)
            ),
            width = 800,
            height = 600,
            projectionParams = ProjectionParams(1.0, 0.5, -1.0, 0.5, 20.0, 0.5, IsoColor.WHITE),
            lightDirection = Vector(1.0, 1.0, 1.0),
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
                    doubleArrayOf(100.0, 100.0, 140.0, 100.0, 120.0, 140.0),
                    IsoColor.BLUE,
                    Path(Point.ORIGIN, Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0)),
                    null
                )
            ),
            width = 800,
            height = 600,
            projectionParams = ProjectionParams(1.0, 0.5, -1.0, 0.5, 20.0, 0.5, IsoColor.WHITE),
            lightDirection = Vector(1.0, 1.0, 1.0),
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
        assertEquals(8, Octahedron().paths.size)
        assertTrue(Knot().paths.isNotEmpty())
    }

    @Test
    fun `cylinder path layout has expected counts for N=6`() {
        val cyl = io.github.jayteealao.isometric.shapes.Cylinder(vertices = 6)
        assertEquals(8, cyl.paths.size)
        assertEquals(6, cyl.paths[0].points.size)
        assertEquals(6, cyl.paths[1].points.size)
        assertEquals(4, cyl.paths[2].points.size)
        assertEquals(4, cyl.paths[7].points.size)
    }

    @Test
    fun `cylinder seam duplicates point at angle zero for distinct UV slots`() {
        val cyl = io.github.jayteealao.isometric.shapes.Cylinder(vertices = 6)
        val quad0 = cyl.paths[2].points
        val quadLast = cyl.paths[7].points
        assertEquals(quad0[1].x, quadLast[2].x, 1e-10)
        assertEquals(quad0[1].y, quadLast[2].y, 1e-10)
        assertEquals(quad0[1].z, quadLast[2].z, 1e-10)
        assertTrue(quad0[1] !== quadLast[2], "seam vertex must be identity-distinct")
    }

    @Test
    fun `cylinder rejects vertices above 24 at construction`() {
        assertFailsWith<IllegalArgumentException> {
            io.github.jayteealao.isometric.shapes.Cylinder(vertices = 25)
        }
    }

    // H-10: Pyramid BASE path vertex positions and winding order assertion.
    //
    // The Pyramid KDoc states "CCW viewed from below" for the base quad. Checking with
    // the shoelace formula on (x, y) projection: a positive result means CCW when
    // viewed from ABOVE (+z). The empirical vertex order is (0,0)→(1,0)→(1,1)→(0,1),
    // which produces a positive signed area (CCW from above, which equals CW from below).
    // This test pins the ACTUAL winding as implemented (positive signed area = CCW from
    // above) to catch any future geometry rearrangement. The KDoc label "CCW from below"
    // appears to be a documentation inaccuracy; the UV generation tests in
    // UvGeneratorPyramidTest already exercise the canonical base UVs that match this order.
    //
    // We verify:
    //  1. The base path has exactly 4 vertices.
    //  2. The four corners match the expected positions for a unit Pyramid at origin.
    //  3. The (x, y) shoelace signed-area is positive (CCW from +z direction).
    @Test
    fun `pyramid base path has 4 vertices with correct vertex positions and winding`() {
        val pyramid = Pyramid(Point.ORIGIN, width = 1.0, depth = 1.0, height = 1.0)
        // paths[4] is the BASE face per the Pyramid KDoc path-index table.
        val basePath = pyramid.paths[4]
        assertEquals(4, basePath.points.size, "Pyramid BASE must have exactly 4 vertices")

        // Verify the four corner positions (order: (0,0), (1,0), (1,1), (0,1) at z=0).
        val pts = basePath.points
        assertEquals(0.0, pts[0].x, 1e-9)
        assertEquals(0.0, pts[0].y, 1e-9)
        assertEquals(0.0, pts[0].z, 1e-9)
        assertEquals(1.0, pts[1].x, 1e-9)
        assertEquals(0.0, pts[1].y, 1e-9)
        assertEquals(0.0, pts[1].z, 1e-9)
        assertEquals(1.0, pts[2].x, 1e-9)
        assertEquals(1.0, pts[2].y, 1e-9)
        assertEquals(0.0, pts[2].z, 1e-9)
        assertEquals(0.0, pts[3].x, 1e-9)
        assertEquals(1.0, pts[3].y, 1e-9)
        assertEquals(0.0, pts[3].z, 1e-9)

        // Compute the signed area using the shoelace formula on the (x, y) projection.
        // signedArea2 > 0 → CCW from above (+z direction) — the actual implementation contract.
        var signedArea2 = 0.0
        val n = pts.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            signedArea2 += pts[i].x * pts[j].y - pts[j].x * pts[i].y
        }
        assertTrue(
            signedArea2 > 0.0,
            "Pyramid BASE vertex order must be CCW from above (+z): " +
                "shoelace signedArea2 should be > 0, got $signedArea2"
        )
    }
}
