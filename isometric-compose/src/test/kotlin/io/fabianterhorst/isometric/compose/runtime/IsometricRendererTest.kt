package io.fabianterhorst.isometric.compose.runtime

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector
import io.fabianterhorst.isometric.shapes.Prism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsometricRendererTest {

    private fun buildSceneRoot(): GroupNode {
        val root = GroupNode()
        val shape = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        root.children.add(shape)
        shape.parent = root
        root.updateChildrenSnapshot()
        return root
    }

    private val dirA = Vector(2.0, -1.0, 3.0).normalize()
    private val dirB = Vector(-1.0, 2.0, 0.5).normalize()

    @Test
    fun `rebuildCache produces different colors for different lightDirection`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine)
        val root = buildSceneRoot()

        val contextA = RenderContext(
            width = 800, height = 600,
            renderOptions = RenderOptions.Quality,
            lightDirection = dirA
        )
        renderer.rebuildCache(root, contextA, 800, 600)
        val colorsA = renderer.currentPreparedScene!!.commands.map { it.color }

        root.markDirty()

        val contextB = RenderContext(
            width = 800, height = 600,
            renderOptions = RenderOptions.Quality,
            lightDirection = dirB
        )
        renderer.rebuildCache(root, contextB, 800, 600)
        val colorsB = renderer.currentPreparedScene!!.commands.map { it.color }

        assertEquals(colorsA.size, colorsB.size)
        val anyDifferent = colorsA.indices.any { colorsA[it] != colorsB[it] }
        assertTrue(
            "Renderer should produce different shading for different light directions",
            anyDifferent
        )
    }

    @Test
    fun `needsUpdate detects lightDirection change even when tree is clean`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine)
        val root = buildSceneRoot()

        val contextA = RenderContext(
            width = 800, height = 600,
            renderOptions = RenderOptions.Quality,
            lightDirection = dirA
        )

        // Initial build populates the cache
        renderer.rebuildCache(root, contextA, 800, 600)

        // Same context -> cache is valid, no update needed
        assertFalse(
            "Same context should not need update",
            renderer.needsUpdate(root, contextA, 800, 600)
        )

        // Different lightDirection -> cache miss, update needed
        val contextB = contextA.copy(lightDirection = dirB)
        assertTrue(
            "Different lightDirection should trigger update",
            renderer.needsUpdate(root, contextB, 800, 600)
        )

        // Rebuild with new direction, then verify stable again
        renderer.rebuildCache(root, contextB, 800, 600)
        assertFalse(
            "After rebuild with new direction, same context should not need update",
            renderer.needsUpdate(root, contextB, 800, 600)
        )
    }

    @Test
    fun `needsUpdate detects renderOptions change even when tree is clean`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine)
        val root = buildSceneRoot()

        val context = RenderContext(
            width = 800, height = 600,
            renderOptions = RenderOptions.Default,
            lightDirection = dirA
        )
        renderer.rebuildCache(root, context, 800, 600)

        assertFalse(renderer.needsUpdate(root, context, 800, 600))

        val changed = context.copy(renderOptions = RenderOptions.Quality)
        assertTrue(
            "Different renderOptions should trigger update",
            renderer.needsUpdate(root, changed, 800, 600)
        )
    }
}
