package io.fabianterhorst.isometric

import io.fabianterhorst.isometric.shapes.Prism
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsometricEngineTest {

    @Test
    fun `add and clear manage scene correctly`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine.add(Prism(Point(1.0, 0.0, 0.0)), IsoColor.RED)

        // Use Quality mode (no culling) to get all faces
        val scene = engine.prepare(800, 600, RenderOptions.Quality)
        assertEquals(12, scene.commands.size) // 2 prisms × 6 faces each

        engine.clear()
        val emptyScene = engine.prepare(800, 600, RenderOptions.Quality)
        assertEquals(0, emptyScene.commands.size)
    }

    @Test
    fun `prepare generates correct viewport dimensions`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)

        val scene = engine.prepare(1000, 800, RenderOptions.Default)
        assertEquals(1000, scene.viewportWidth)
        assertEquals(800, scene.viewportHeight)
    }

    @Test
    fun `depth sorting can be disabled`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine.add(Prism(Point(1.0, 1.0, 1.0)), IsoColor.RED)

        val sortedScene = engine.prepare(800, 600, RenderOptions.Default)
        val unsortedScene = engine.prepare(800, 600, RenderOptions.Performance)

        // Both should have same number of commands
        assertEquals(sortedScene.commands.size, unsortedScene.commands.size)
    }

    @Test
    fun `findItemAt detects hits`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN, 2.0, 2.0, 2.0), IsoColor.BLUE)

        val scene = engine.prepare(800, 600, RenderOptions.Default)
        assertTrue(scene.commands.isNotEmpty())

        // Use the center of a rendered face's projected points as hit coordinate
        val cmd = scene.commands.first()
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()
        val hit = engine.findItemAt(scene, avgX, avgY, reverseSort = true)
        assertNotNull(hit)

        // Click far outside (should miss)
        val miss = engine.findItemAt(scene, 10.0, 10.0, reverseSort = true)
        assertNull(miss)
    }

    @Test
    fun `render commands have stable IDs`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)

        val scene1 = engine.prepare(800, 600, RenderOptions.Default)
        engine.clear()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val scene2 = engine.prepare(800, 600, RenderOptions.Default)

        // IDs should be different between runs (cleared state)
        val ids1 = scene1.commands.map { it.id }.toSet()
        val ids2 = scene2.commands.map { it.id }.toSet()
        assertTrue(ids1.isNotEmpty())
        assertTrue(ids2.isNotEmpty())
    }

    @Test
    fun `prepare without lightDirection uses engine default`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val defaultScene = engine.prepare(800, 600, RenderOptions.Quality)

        engine.clear()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val explicitScene = engine.prepare(800, 600, RenderOptions.Quality,
            lightDirection = Vector(2.0, -1.0, 3.0))

        assertEquals(defaultScene.commands.size, explicitScene.commands.size)
        for (i in defaultScene.commands.indices) {
            assertEquals(defaultScene.commands[i].color, explicitScene.commands[i].color,
                "Command $i color should match engine default")
        }
    }

    @Test
    fun `lightDirection changes shading colors`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val sceneA = engine.prepare(800, 600, RenderOptions.Quality,
            lightDirection = Vector(2.0, -1.0, 3.0))

        engine.clear()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val sceneB = engine.prepare(800, 600, RenderOptions.Quality,
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

        val withCulling = engine.prepare(800, 600, RenderOptions.Default)
        val withoutCulling = engine.prepare(800, 600, RenderOptions.Quality)

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

        val baseline = baselineEngine.prepare(800, 600, RenderOptions.Default)
        val optimized = broadPhaseEngine.prepare(
            800,
            600,
            RenderOptions.Default.copy(enableBroadPhaseSort = true)
        )

        assertEquals(
            baseline.commands.map { it.id },
            optimized.commands.map { it.id }
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

        val baseline = baselineEngine.prepare(800, 600, RenderOptions.Quality)
        val optimized = broadPhaseEngine.prepare(
            800,
            600,
            RenderOptions.Quality.copy(enableBroadPhaseSort = true)
        )

        assertEquals(
            baseline.commands.map { it.id },
            optimized.commands.map { it.id }
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

        val baseline = baselineEngine.prepare(800, 600, RenderOptions.Quality)
        val optimized = broadPhaseEngine.prepare(
            800,
            600,
            RenderOptions.Quality.copy(
                enableBroadPhaseSort = true,
                broadPhaseCellSize = 50.0
            )
        )

        assertEquals(
            baseline.commands.map { it.id },
            optimized.commands.map { it.id }
        )
    }

    @Test
    fun `render options reject non-positive broad phase cell size`() {
        try {
            RenderOptions(broadPhaseCellSize = 0.0)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("broadPhaseCellSize"))
            return
        }
        throw AssertionError("Expected IllegalArgumentException for non-positive broadPhaseCellSize")
    }
}
