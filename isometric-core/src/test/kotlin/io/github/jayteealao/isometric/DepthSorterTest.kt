package io.github.jayteealao.isometric

import io.github.jayteealao.isometric.shapes.Prism
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthSorterTest {

    @Test
    fun `coplanar adjacent prisms produce deterministic order`() {
        // Two prisms sharing a boundary plane at x=1
        val engine1 = IsometricEngine()
        engine1.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine1.add(Prism(Point(1.0, 0.0, 0.0)), IsoColor.RED)

        val scene1 = engine1.projectScene(800, 600, RenderOptions.NoCulling)

        // Project again with fresh engine (same scene)
        val engine2 = IsometricEngine()
        engine2.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine2.add(Prism(Point(1.0, 0.0, 0.0)), IsoColor.RED)

        val scene2 = engine2.projectScene(800, 600, RenderOptions.NoCulling)

        // Command order should be identical between both projections
        assertEquals(scene1.commands.size, scene2.commands.size)
        for (i in scene1.commands.indices) {
            assertEquals(
                scene1.commands[i].points,
                scene2.commands[i].points,
                "Command $i should have identical projected points"
            )
        }
    }

    @Test
    fun `coplanar tile grid has expected face count and no duplicates`() {
        val engine = IsometricEngine()
        // 3×3 grid of 1×1×1 prisms — full size, sharing boundary planes
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                engine.add(
                    Prism(Point(col.toDouble(), row.toDouble(), 0.0)),
                    IsoColor.BLUE
                )
            }
        }

        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        // 9 prisms × 6 faces = 54 faces without culling
        assertEquals(54, scene.commands.size, "3×3 grid should produce 54 faces")

        // All command IDs should be unique (no duplicates)
        val ids = scene.commands.map { it.commandId }
        assertEquals(ids.size, ids.toSet().size, "All command IDs should be unique")
    }

    @Test
    fun `coplanar tile grid with broad phase matches baseline order`() {
        val engine1 = IsometricEngine()
        val engine2 = IsometricEngine()
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val origin = Point(col.toDouble(), row.toDouble(), 0.0)
                engine1.add(Prism(origin), IsoColor.BLUE)
                engine2.add(Prism(origin), IsoColor.BLUE)
            }
        }

        val baseline = engine1.projectScene(800, 600, RenderOptions.NoCulling)
        val broadPhase = engine2.projectScene(
            800, 600,
            RenderOptions.NoCulling.copy(enableBroadPhaseSort = true)
        )

        assertEquals(
            baseline.commands.map { it.commandId },
            broadPhase.commands.map { it.commandId },
            "Broad phase should produce same order as baseline for coplanar grid"
        )
    }

    @Test
    fun `cycle fallback includes all items`() {
        // Create items that could form dependency cycles by placing prisms
        // in positions that create circular occlusion relationships.
        // Even if cycles exist, all items must appear in the output.
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN, 2.0, 2.0, 1.0), IsoColor.BLUE)
        engine.add(Prism(Point(0.5, 0.5, 0.5), 2.0, 2.0, 1.0), IsoColor.RED)
        engine.add(Prism(Point(1.0, 1.0, 1.0), 2.0, 2.0, 1.0), IsoColor.GREEN)

        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        // All items must appear — none should be lost
        assertTrue(scene.commands.isNotEmpty(), "Scene should have commands")
        // 3 prisms × 6 faces = 18 expected
        assertEquals(18, scene.commands.size, "All faces from all prisms must appear")

        // All IDs unique
        val ids = scene.commands.map { it.commandId }
        assertEquals(ids.size, ids.toSet().size, "No duplicate command IDs")
    }

    @Test
    fun `diagnostic - face count and top face presence with culling`() {
        // Single prism — how many faces survive culling?
        val engine1 = IsometricEngine()
        engine1.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val single = engine1.projectScene(800, 600, RenderOptions.Default)
        println("Single prism with culling: ${single.commands.size} faces")
        for (cmd in single.commands) {
            val path = cmd.originalPath
            val avgZ = path.points.sumOf { it.z } / path.points.size
            val avgX = path.points.sumOf { it.x } / path.points.size
            val avgY = path.points.sumOf { it.y } / path.points.size
            println("  Face ${cmd.commandId}: centroid=(${avgX}, ${avgY}, ${avgZ}) depth=${path.depth}")
        }

        // 2x2 grid with culling
        val engine2 = IsometricEngine()
        for (row in 0 until 2) {
            for (col in 0 until 2) {
                engine2.add(Prism(Point(col.toDouble(), row.toDouble(), 0.0)), IsoColor.BLUE)
            }
        }
        val grid = engine2.projectScene(800, 600, RenderOptions.Default)
        println("\n2x2 grid with culling: ${grid.commands.size} faces")

        // Check which faces are top faces (avgZ == 1.0 for top of 1x1x1 prism)
        var topFaceCount = 0
        for (cmd in grid.commands) {
            val path = cmd.originalPath
            val avgZ = path.points.sumOf { it.z } / path.points.size
            if (avgZ > 0.9) topFaceCount++
        }
        println("Top faces in output: $topFaceCount (expected 4)")
        assertEquals(4, topFaceCount, "All 4 top faces should survive culling in 2x2 grid")

        // 2x2 grid WITHOUT culling for comparison
        val engine3 = IsometricEngine()
        for (row in 0 until 2) {
            for (col in 0 until 2) {
                engine3.add(Prism(Point(col.toDouble(), row.toDouble(), 0.0)), IsoColor.BLUE)
            }
        }
        val gridNoCull = engine3.projectScene(800, 600, RenderOptions.NoCulling)
        println("\n2x2 grid without culling: ${gridNoCull.commands.size} faces")
        var topNoCull = 0
        for (cmd in gridNoCull.commands) {
            val path = cmd.originalPath
            val avgZ = path.points.sumOf { it.z } / path.points.size
            if (avgZ > 0.9) topNoCull++
        }
        println("Top faces without culling: $topNoCull (expected 4)")
    }

    @Test
    fun `kahn algorithm preserves existing broad phase sparse test`() {
        // Same scenario as IsometricEngineTest broad phase sparse test —
        // verifies the new Kahn's sort doesn't break existing behavior
        val engine1 = IsometricEngine()
        engine1.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine1.add(Prism(Point(5.0, 0.0, 0.0)), IsoColor.RED)
        engine1.add(Prism(Point(0.0, 5.0, 0.0)), IsoColor.GREEN)

        val engine2 = IsometricEngine()
        engine2.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine2.add(Prism(Point(5.0, 0.0, 0.0)), IsoColor.RED)
        engine2.add(Prism(Point(0.0, 5.0, 0.0)), IsoColor.GREEN)

        val default = engine1.projectScene(800, 600, RenderOptions.Default)
        val broadPhase = engine2.projectScene(
            800, 600,
            RenderOptions.Default.copy(enableBroadPhaseSort = true)
        )

        assertEquals(
            default.commands.map { it.commandId },
            broadPhase.commands.map { it.commandId }
        )
    }
}
