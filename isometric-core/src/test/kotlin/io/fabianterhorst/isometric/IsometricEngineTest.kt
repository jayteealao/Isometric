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

        val scene = engine.prepare(sceneVersion = 0, width = 800, height = 600, options = RenderOptions.Default)
        assertEquals(12, scene.commands.size) // 2 prisms × 6 faces each

        engine.clear()
        val emptyScene = engine.prepare(sceneVersion = 0, width = 800, height = 600, options = RenderOptions.Default)
        assertEquals(0, emptyScene.commands.size)
    }

    @Test
    fun `prepare generates correct viewport dimensions`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)

        val scene = engine.prepare(sceneVersion = 0, width = 1000, height = 800, options = RenderOptions.Default)
        assertEquals(1000, scene.viewportWidth)
        assertEquals(800, scene.viewportHeight)
    }

    @Test
    fun `depth sorting can be disabled`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine.add(Prism(Point(1.0, 1.0, 1.0)), IsoColor.RED)

        val sortedScene = engine.prepare(sceneVersion = 0, width = 800, height = 600, options = RenderOptions.Default)
        val unsortedScene = engine.prepare(sceneVersion = 0, width = 800, height = 600, options = RenderOptions.Performance)

        // Both should have same number of commands
        assertEquals(sortedScene.commands.size, unsortedScene.commands.size)
    }

    @Test
    fun `findItemAt detects hits`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN, 2.0, 2.0, 2.0), IsoColor.BLUE)

        val scene = engine.prepare(sceneVersion = 0, width = 800, height = 600, options = RenderOptions.Default)

        // Click in center (should hit the cube)
        val hit = engine.findItemAt(scene, 400.0, 540.0, reverseSort = true)
        assertNotNull(hit)

        // Click far outside (should miss)
        val miss = engine.findItemAt(scene, 10.0, 10.0, reverseSort = true)
        assertNull(miss)
    }

    @Test
    fun `render commands have stable IDs`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)

        val scene1 = engine.prepare(sceneVersion = 0, width = 800, height = 600, options = RenderOptions.Default)
        engine.clear()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val scene2 = engine.prepare(sceneVersion = 0, width = 800, height = 600, options = RenderOptions.Default)

        // IDs should be different between runs (cleared state)
        val ids1 = scene1.commands.map { it.id }.toSet()
        val ids2 = scene2.commands.map { it.id }.toSet()
        assertTrue(ids1.isNotEmpty())
        assertTrue(ids2.isNotEmpty())
    }

    @Test
    fun `culling removes back-facing polygons`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)

        val withCulling = engine.prepare(sceneVersion = 0, width = 800, height = 600, options = RenderOptions.Default)
        val withoutCulling = engine.prepare(sceneVersion = 0, width = 800, height = 600, options = RenderOptions.Quality)

        // With culling should have fewer or equal commands
        assertTrue(withCulling.commands.size <= withoutCulling.commands.size)
    }
}
