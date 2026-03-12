package io.fabianterhorst.isometric.compose.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector
import io.fabianterhorst.isometric.shapes.Prism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsometricRendererPathCachingTest {

    private val context = RenderContext(
        width = 800,
        height = 600,
        renderOptions = RenderOptions.Quality,
        lightDirection = Vector(2.0, -1.0, 3.0).normalize()
    )

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

    private fun cachedPathCount(renderer: IsometricRenderer): Int {
        val field = renderer.javaClass.getDeclaredField("cachedPaths")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(renderer) as? List<*>)?.size ?: 0
    }

    @Test
    fun rebuildCache_buildsCachedPathsOnlyWhenEnabled() {
        val root = buildSceneRoot()

        val cachedRenderer = IsometricRenderer(IsometricEngine(), enablePathCaching = true)
        cachedRenderer.rebuildCache(root, context, 800, 600)
        assertEquals(
            "Path cache should contain one entry per prepared command when enabled",
            cachedRenderer.currentPreparedScene!!.commands.size,
            cachedPathCount(cachedRenderer)
        )

        root.markDirty()

        val uncachedRenderer = IsometricRenderer(IsometricEngine(), enablePathCaching = false)
        uncachedRenderer.rebuildCache(root, context, 800, 600)
        assertEquals(
            "Path cache should stay empty when path caching is disabled",
            0,
            cachedPathCount(uncachedRenderer)
        )
    }

    @Test
    fun invalidate_clearsCachedPaths() {
        val renderer = IsometricRenderer(IsometricEngine(), enablePathCaching = true)
        val root = buildSceneRoot()

        renderer.rebuildCache(root, context, 800, 600)
        assertTrue("Path cache should be populated after rebuild", cachedPathCount(renderer) > 0)

        renderer.invalidate()
        assertEquals("invalidate should clear cached paths", 0, cachedPathCount(renderer))
    }

    @Test
    fun pathCaching_preservesPreparedSceneAndHitTestSemantics() {
        val root = buildSceneRoot()

        val cachedRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = true,
            enableSpatialIndex = false
        )
        cachedRenderer.rebuildCache(root, context, 800, 600)
        val cachedScene = cachedRenderer.currentPreparedScene!!

        root.markDirty()

        val uncachedRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = false
        )
        uncachedRenderer.rebuildCache(root, context, 800, 600)
        val uncachedScene = uncachedRenderer.currentPreparedScene!!

        assertEquals(uncachedScene.commands.size, cachedScene.commands.size)
        assertEquals(uncachedScene.commands.map { it.color }, cachedScene.commands.map { it.color })
        assertEquals(
            uncachedScene.commands.map { it.points.toList() },
            cachedScene.commands.map { it.points.toList() }
        )

        val cmd = cachedScene.commands.first()
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()

        val cachedHit = cachedRenderer.hitTest(root, avgX, avgY, context, 800, 600)
        val uncachedHit = uncachedRenderer.hitTest(root, avgX, avgY, context, 800, 600)

        assertNotNull("Cached renderer should hit the shape centroid", cachedHit)
        assertNotNull("Uncached renderer should hit the shape centroid", uncachedHit)
        assertEquals(
            "Path caching must not change hit-test results",
            uncachedHit!!.nodeId,
            cachedHit!!.nodeId
        )
    }
}
