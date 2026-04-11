package io.github.jayteealao.isometric.compose.runtime

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.Vector
import io.github.jayteealao.isometric.shapes.Prism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun RenderCommand.avgX(): Double {
    var sum = 0.0; var i = 0
    while (i < points.size) { sum += points[i]; i += 2 }
    return sum / pointCount
}

private fun RenderCommand.avgY(): Double {
    var sum = 0.0; var i = 1
    while (i < points.size) { sum += points[i]; i += 2 }
    return sum / pointCount
}

class IsometricRendererTest {

    private class CountingHooks : RenderBenchmarkHooks {
        var prepareStarts = 0
        var prepareEnds = 0
        var cacheHits = 0
        var cacheMisses = 0

        override fun onPrepareStart() {
            prepareStarts++
        }

        override fun onPrepareEnd() {
            prepareEnds++
        }

        override fun onDrawStart() = Unit
        override fun onDrawEnd() = Unit

        override fun onCacheHit() {
            cacheHits++
        }

        override fun onCacheMiss() {
            cacheMisses++
        }
    }

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

    private fun buildPathNodeRoot(path: Path): Pair<GroupNode, PathNode> {
        val root = GroupNode()
        val node = PathNode(path = path, color = IsoColor.BLUE)
        root.children.add(node)
        node.parent = root
        root.updateChildrenSnapshot()
        return root to node
    }

    private fun buildBatchNodeRoot(shapes: List<io.github.jayteealao.isometric.Shape>): Pair<GroupNode, BatchNode> {
        val root = GroupNode()
        val node = BatchNode(shapes = shapes, color = IsoColor.BLUE)
        root.children.add(node)
        node.parent = root
        root.updateChildrenSnapshot()
        return root to node
    }

    private val dirA = Vector(2.0, -1.0, 3.0).normalize()
    private val dirB = Vector(-1.0, 2.0, 0.5).normalize()

    @Test
    fun `rebuildCache produces different colors for different lightDirection`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
        val root = buildSceneRoot()

        val contextA = RenderContext(
            width = 800, height = 600,
            renderOptions = RenderOptions.NoCulling,
            lightDirection = dirA
        )
        renderer.rebuildCache(root, contextA, 800, 600)
        val colorsA = renderer.currentPreparedScene!!.commands.map { it.color }

        root.markDirty()

        val contextB = RenderContext(
            width = 800, height = 600,
            renderOptions = RenderOptions.NoCulling,
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
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
        val root = buildSceneRoot()

        val contextA = RenderContext(
            width = 800, height = 600,
            renderOptions = RenderOptions.NoCulling,
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
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
        val root = buildSceneRoot()

        val context = RenderContext(
            width = 800, height = 600,
            renderOptions = RenderOptions.Default,
            lightDirection = dirA
        )
        renderer.rebuildCache(root, context, 800, 600)

        assertFalse(renderer.needsUpdate(root, context, 800, 600))

        val changed = context.copy(renderOptions = RenderOptions.NoCulling)
        assertTrue(
            "Different renderOptions should trigger update",
            renderer.needsUpdate(root, changed, 800, 600)
        )
    }

    // --- Hit testing helpers ---

    private val defaultContext = RenderContext(
        width = 800, height = 600,
        renderOptions = RenderOptions.NoCulling,
        lightDirection = dirA
    )

    /**
     * Build a scene with two overlapping prisms. The inner prism is fully inside
     * the outer's x,y footprint, guaranteeing screen-space overlap.
     * Returns (root, outerShape, innerShape).
     */
    private fun buildOverlappingSceneRoot(): Triple<GroupNode, ShapeNode, ShapeNode> {
        val root = GroupNode()
        val outer = ShapeNode(
            shape = Prism(Point.ORIGIN, 3.0, 3.0, 1.0),
            color = IsoColor.BLUE
        )
        val inner = ShapeNode(
            shape = Prism(Point(0.5, 0.5, 0.0), 1.0, 1.0, 1.0),
            color = IsoColor.RED
        )
        root.children.add(outer)
        outer.parent = root
        root.children.add(inner)
        inner.parent = root
        root.updateChildrenSnapshot()
        return Triple(root, outer, inner)
    }

    /** Find a hittable centroid from commands belonging to a given node. */
    private fun findCommandCentroid(
        scene: io.github.jayteealao.isometric.PreparedScene,
        nodeId: String
    ): Pair<Double, Double>? {
        val cmd = scene.commands.firstOrNull { it.ownerNodeId == nodeId }
            ?: return null
        val cx = cmd.avgX()
        val cy = cmd.avgY()
        return cx to cy
    }

    // --- Hit testing tests ---

    @Test
    fun `hitTest with spatial index works independently of path caching`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false, enableSpatialIndex = true)
        val root = buildSceneRoot()

        renderer.rebuildCache(root, defaultContext, 800, 600)

        val scene = renderer.currentPreparedScene!!
        assertTrue("Scene should have commands", scene.commands.isNotEmpty())

        val cmd = scene.commands.first()
        val avgX = cmd.avgX()
        val avgY = cmd.avgY()

        val hit = renderer.hitTest(root, avgX, avgY, defaultContext, 800, 600)
        assertNotNull("hitTest should find item when spatial index is enabled without path caching", hit)
    }

    @Test
    fun `hitTest fast path returns correct node`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false, enableSpatialIndex = true)
        val root = buildSceneRoot()

        renderer.rebuildCache(root, defaultContext, 800, 600)

        val scene = renderer.currentPreparedScene!!
        val cmd = scene.commands.first()
        val avgX = cmd.avgX()
        val avgY = cmd.avgY()

        val hit = renderer.hitTest(root, avgX, avgY, defaultContext, 800, 600)
        assertNotNull("hitTest fast path should find item at shape center", hit)
    }

    @Test
    fun `hitTest slow path returns correct node when spatial index is disabled`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false, enableSpatialIndex = false)
        val root = buildSceneRoot()

        renderer.rebuildCache(root, defaultContext, 800, 600)

        val scene = renderer.currentPreparedScene!!
        val cmd = scene.commands.first()
        val avgX = cmd.avgX()
        val avgY = cmd.avgY()

        val hit = renderer.hitTest(root, avgX, avgY, defaultContext, 800, 600)
        assertNotNull("hitTest slow path should find item at shape center", hit)
    }

    @Test
    fun `hitTest fast path matches slow path on overlapping shapes`() {
        val (root, _, inner) = buildOverlappingSceneRoot()

        // Fast renderer: spatial index ON
        val fastEngine = IsometricEngine()
        val fastRenderer = IsometricRenderer(fastEngine, enablePathCaching = false, enableSpatialIndex = true)
        fastRenderer.rebuildCache(root, defaultContext, 800, 600)

        // Slow renderer: spatial index OFF, path caching OFF
        val slowEngine = IsometricEngine()
        val slowRenderer = IsometricRenderer(slowEngine, enablePathCaching = false, enableSpatialIndex = false)
        root.markDirty()
        slowRenderer.rebuildCache(root, defaultContext, 800, 600)

        // Find a deterministic overlap point: centroid of an inner shape command.
        // Since the inner prism (0.5,0.5 -> 1.5,1.5) is fully inside the outer (0,0 -> 3,3),
        // this point is guaranteed to be in the overlap region.
        val (testX, testY) = findCommandCentroid(fastRenderer.currentPreparedScene!!, inner.nodeId)
            ?: error("Inner shape should have at least one command")

        val fastHit = fastRenderer.hitTest(root, testX, testY, defaultContext, 800, 600)
        val slowHit = slowRenderer.hitTest(root, testX, testY, defaultContext, 800, 600)

        assertNotNull("Fast path should find a hit in the overlap region", fastHit)
        assertNotNull("Slow path should find a hit in the overlap region", slowHit)
        assertEquals(
            "Fast path and slow path must return the same node (frontmost shape)",
            slowHit!!.nodeId,
            fastHit!!.nodeId
        )
    }

    @Test
    fun `hitTest fast path matches slow path near cell boundary within hit radius`() {
        val cellSize = 4.0
        val root = buildSceneRoot()

        val fastEngine = IsometricEngine()
        val fastRenderer = IsometricRenderer(
            fastEngine,
            enablePathCaching = false,
            enableSpatialIndex = true,
            spatialIndexCellSize = cellSize
        )
        fastRenderer.rebuildCache(root, defaultContext, 800, 600)

        val slowEngine = IsometricEngine()
        val slowRenderer = IsometricRenderer(
            slowEngine,
            enablePathCaching = false,
            enableSpatialIndex = false
        )
        root.markDirty()
        slowRenderer.rebuildCache(root, defaultContext, 800, 600)

        val command = fastRenderer.currentPreparedScene!!.commands.first()
        // Find the leftmost point in the flat packed DoubleArray
        val cmdPts = command.points
        var leftX = Double.POSITIVE_INFINITY; var leftY = 0.0
        var k = 0; while (k < cmdPts.size) { if (cmdPts[k] < leftX) { leftX = cmdPts[k]; leftY = cmdPts[k + 1] }; k += 2 }
        val boundaryX = kotlin.math.floor(leftX / cellSize) * cellSize
        val testX = boundaryX - 1.0
        val testY = leftY

        val fastHit = fastRenderer.hitTest(root, testX, testY, defaultContext, 800, 600)
        val slowHit = slowRenderer.hitTest(root, testX, testY, defaultContext, 800, 600)

        assertNotNull("Slow path should hit within radius near boundary", slowHit)
        assertNotNull("Fast path should match slow path near boundary", fastHit)
        assertEquals(slowHit!!.nodeId, fastHit!!.nodeId)
    }

    @Test
    fun `hitTest resolves PathNode via exact owner id`() {
        val path = Prism(Point.ORIGIN, 1.0, 1.0, 1.0).paths.first()
        val (root, node) = buildPathNodeRoot(path)

        val fastRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = true
        )
        fastRenderer.rebuildCache(root, defaultContext, 800, 600)

        val (x, y) = findCommandCentroid(fastRenderer.currentPreparedScene!!, node.nodeId)
            ?: error("PathNode should have a prepared command")

        val hit = fastRenderer.hitTest(root, x, y, defaultContext, 800, 600)
        assertNotNull("PathNode should be hittable via exact command ownership", hit)
        assertEquals(node.nodeId, hit!!.nodeId)
    }

    @Test
    fun `hitTest resolves BatchNode via owner id`() {
        val shapes = listOf(
            Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            Prism(Point(1.25, 0.0, 0.0), 1.0, 1.0, 1.0)
        )
        val (root, node) = buildBatchNodeRoot(shapes)

        val fastRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = true
        )
        fastRenderer.rebuildCache(root, defaultContext, 800, 600)

        val (x, y) = findCommandCentroid(fastRenderer.currentPreparedScene!!, node.nodeId)
            ?: error("BatchNode should have prepared commands")

        val hit = fastRenderer.hitTest(root, x, y, defaultContext, 800, 600)
        assertNotNull("BatchNode should be hittable via command ownership", hit)
        assertEquals(node.nodeId, hit!!.nodeId)
    }

    @Test
    fun `multi-cell query deduplicates candidates and matches slow path`() {
        val root = buildSceneRoot()
        val cellSize = 3.0

        val fastRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = true,
            spatialIndexCellSize = cellSize
        )
        fastRenderer.rebuildCache(root, defaultContext, 800, 600)

        val slowRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = false
        )
        root.markDirty()
        slowRenderer.rebuildCache(root, defaultContext, 800, 600)

        val command = fastRenderer.currentPreparedScene!!.commands.first()
        val pts = command.points
        var sumX = 0.0; var sumY = 0.0
        var i = 0; while (i < pts.size) { sumX += pts[i]; sumY += pts[i + 1]; i += 2 }
        val centerX = sumX / command.pointCount
        val centerY = sumY / command.pointCount

        val fastHit = fastRenderer.hitTest(root, centerX, centerY, defaultContext, 800, 600)
        val slowHit = slowRenderer.hitTest(root, centerX, centerY, defaultContext, 800, 600)

        assertNotNull(fastHit)
        assertNotNull(slowHit)
        assertEquals(
            "A command spanning many cells should still resolve once to the same node",
            slowHit!!.nodeId,
            fastHit!!.nodeId
        )
    }

    @Test
    fun `hitTest returns null for miss`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false, enableSpatialIndex = true)
        val root = buildSceneRoot()

        renderer.rebuildCache(root, defaultContext, 800, 600)

        val hit = renderer.hitTest(root, 10.0, 10.0, defaultContext, 800, 600)
        assertNull("hitTest should return null for coordinates outside all shapes", hit)
    }

    @Test
    fun `hitTest matches slow path for negative screen coordinates`() {
        val root = buildSceneRoot()

        val fastRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = true
        )
        fastRenderer.rebuildCache(root, defaultContext, 800, 600)

        val slowRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = false
        )
        root.markDirty()
        slowRenderer.rebuildCache(root, defaultContext, 800, 600)

        val fastHit = fastRenderer.hitTest(root, -1.0, -1.0, defaultContext, 800, 600)
        val slowHit = slowRenderer.hitTest(root, -1.0, -1.0, defaultContext, 800, 600)

        assertEquals(slowHit?.nodeId, fastHit?.nodeId)
    }

    @Test
    fun `hitTest matches slow path for out of bounds coordinates`() {
        val root = buildSceneRoot()

        val fastRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = true
        )
        fastRenderer.rebuildCache(root, defaultContext, 800, 600)

        val slowRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = false
        )
        root.markDirty()
        slowRenderer.rebuildCache(root, defaultContext, 800, 600)

        val fastHit = fastRenderer.hitTest(root, 900.0, 700.0, defaultContext, 800, 600)
        val slowHit = slowRenderer.hitTest(root, 900.0, 700.0, defaultContext, 800, 600)

        assertEquals("Fast and slow paths should agree out of bounds", slowHit?.nodeId, fastHit?.nodeId)
    }

    @Test
    fun `hitTest matches slow path with culling and bounds checking enabled`() {
        val root = buildSceneRoot()
        val cullingContext = defaultContext.copy(renderOptions = RenderOptions.Default)

        val fastRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = true
        )
        fastRenderer.rebuildCache(root, cullingContext, 800, 600)

        val slowRenderer = IsometricRenderer(
            IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = false
        )
        root.markDirty()
        slowRenderer.rebuildCache(root, cullingContext, 800, 600)

        val scene = fastRenderer.currentPreparedScene!!
        assertTrue("Culling-enabled scene should still produce commands", scene.commands.isNotEmpty())

        val command = scene.commands.first()
        val pts2 = command.points
        var sx = 0.0; var sy = 0.0
        var j = 0; while (j < pts2.size) { sx += pts2[j]; sy += pts2[j + 1]; j += 2 }
        val x = sx / command.pointCount
        val y = sy / command.pointCount

        val fastHit = fastRenderer.hitTest(root, x, y, cullingContext, 800, 600)
        val slowHit = slowRenderer.hitTest(root, x, y, cullingContext, 800, 600)

        assertEquals("Fast and slow paths should match with RenderOptions.Default", slowHit?.nodeId, fastHit?.nodeId)
    }

    @Test
    fun `hitTest returns null for non-positive dimensions`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
        val root = buildSceneRoot()

        val hit1 = renderer.hitTest(root, 400.0, 300.0, defaultContext, 0, 600)
        assertNull("hitTest should return null for zero width", hit1)

        val hit2 = renderer.hitTest(root, 400.0, 300.0, defaultContext, 800, 0)
        assertNull("hitTest should return null for zero height", hit2)

        val hit3 = renderer.hitTest(root, 400.0, 300.0, defaultContext, -1, -1)
        assertNull("hitTest should return null for negative dimensions", hit3)
    }

    @Test
    fun `invalidate clears all cached state and hitTest auto-rebuilds`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
        val root = buildSceneRoot()

        renderer.rebuildCache(root, defaultContext, 800, 600)
        assertNotNull(renderer.currentPreparedScene)

        // Grab a hittable centroid before invalidation
        val scene = renderer.currentPreparedScene!!
        val cmd = scene.commands.first()
        val avgX = cmd.avgX()
        val avgY = cmd.avgY()

        renderer.clearCache()
        assertNull("invalidate should clear prepared scene", renderer.currentPreparedScene)

        // hitTest with valid dimensions should auto-rebuild and find the shape
        val hit = renderer.hitTest(root, avgX, avgY, defaultContext, 800, 600)
        assertNotNull("hitTest should auto-rebuild after invalidation", hit)
    }

    // --- Self-sufficiency tests ---

    @Test
    fun `hitTest rebuilds when root is dirty`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false, enableSpatialIndex = true)
        val root = buildSceneRoot()

        renderer.rebuildCache(root, defaultContext, 800, 600)
        val scene = renderer.currentPreparedScene!!
        val cmd = scene.commands.first()
        val avgX = cmd.avgX()
        val avgY = cmd.avgY()

        // Mark dirty — simulates a node tree mutation
        root.markDirty()
        assertTrue("Cache should need update after markDirty", renderer.needsUpdate(root, defaultContext, 800, 600))

        // hitTest should rebuild automatically, not return null
        val hit = renderer.hitTest(root, avgX, avgY, defaultContext, 800, 600)
        assertNotNull("hitTest should rebuild and find shape when root is dirty", hit)
    }

    @Test
    fun `hitTest rebuilds when renderOptions change`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false, enableSpatialIndex = true)
        val root = buildSceneRoot()

        renderer.rebuildCache(root, defaultContext, 800, 600)
        val scene = renderer.currentPreparedScene!!
        val cmd = scene.commands.first()
        val avgX = cmd.avgX()
        val avgY = cmd.avgY()

        // Change renderOptions — cache becomes stale
        val changedContext = defaultContext.copy(renderOptions = RenderOptions.Default)

        // hitTest with new context should rebuild, not return null
        val hit = renderer.hitTest(root, avgX, avgY, changedContext, 800, 600)
        assertNotNull("hitTest should rebuild when renderOptions change", hit)
    }

    @Test
    fun `hitTest rebuilds when lightDirection changes`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false, enableSpatialIndex = true)
        val root = buildSceneRoot()

        val contextA = RenderContext(
            width = 800, height = 600,
            renderOptions = RenderOptions.NoCulling,
            lightDirection = dirA
        )
        renderer.rebuildCache(root, contextA, 800, 600)
        val scene = renderer.currentPreparedScene!!
        val cmd = scene.commands.first()
        val avgX = cmd.avgX()
        val avgY = cmd.avgY()

        // Change light direction — cache becomes stale
        val contextB = contextA.copy(lightDirection = dirB)

        // hitTest with new light direction should rebuild, not return null
        val hit = renderer.hitTest(root, avgX, avgY, contextB, 800, 600)
        assertNotNull("hitTest should rebuild when lightDirection changes", hit)
    }

    @Test
    fun `hitTest works without prior render call given valid size`() {
        // Use a probe renderer to discover a hittable centroid
        val probeEngine = IsometricEngine()
        val probeRenderer = IsometricRenderer(probeEngine, enablePathCaching = false)
        val root = buildSceneRoot()
        probeRenderer.rebuildCache(root, defaultContext, 800, 600)
        val scene = probeRenderer.currentPreparedScene!!
        val cmd = scene.commands.first()
        val avgX = cmd.avgX()
        val avgY = cmd.avgY()

        // Fresh renderer — no prior rebuildCache or render()
        root.markDirty()
        val freshEngine = IsometricEngine()
        val freshRenderer = IsometricRenderer(freshEngine, enablePathCaching = false, enableSpatialIndex = true)

        val hit = freshRenderer.hitTest(root, avgX, avgY, defaultContext, 800, 600)
        assertNotNull("hitTest should work without any prior render() call", hit)
    }

    @Test
    fun `hitTest rebuilds when viewport size changes`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false, enableSpatialIndex = true)
        val root = buildSceneRoot()

        // Build at 800x600
        renderer.rebuildCache(root, defaultContext, 800, 600)
        assertEquals(800, renderer.currentPreparedScene!!.width)
        assertEquals(600, renderer.currentPreparedScene!!.height)

        // Call hitTest at a different viewport size — coordinates don't matter,
        // we just need hitTest to trigger a rebuild at the new size.
        val newWidth = 1024
        val newHeight = 768
        renderer.hitTest(root, 0.0, 0.0, defaultContext, newWidth, newHeight)

        // Verify the scene was rebuilt at the new viewport size
        assertEquals("width should be updated after hitTest with new size",
            newWidth, renderer.currentPreparedScene!!.width)
        assertEquals("height should be updated after hitTest with new size",
            newHeight, renderer.currentPreparedScene!!.height)

        // Verify hit testing actually works at the new size by computing a fresh centroid
        val scene = renderer.currentPreparedScene!!
        val cmd = scene.commands.first()
        val avgX = cmd.avgX()
        val avgY = cmd.avgY()

        val hit = renderer.hitTest(root, avgX, avgY, defaultContext, newWidth, newHeight)
        assertNotNull("hitTest should find shape at centroid after viewport resize", hit)
    }

    @Test
    fun `render context copy preserves accumulated transforms`() {
        val base = RenderContext(
            width = 800,
            height = 600,
            renderOptions = RenderOptions.Default
        ).withTransform(
            position = Point(3.0, 4.0, 5.0),
            rotation = 0.4,
            scale = 2.0
        )

        val copied = base.copy(renderOptions = RenderOptions.NoDepthSorting)
        val testPoint = Point(1.0, 2.0, 3.0)

        assertEquals(
            "copy() should preserve accumulated transforms when only public fields change",
            base.applyTransformsToPoint(testPoint),
            copied.applyTransformsToPoint(testPoint)
        )
    }

    @Test
    fun `stable hitTest reuses prepared scene unless forceRebuild is enabled`() {
        val root = buildSceneRoot()

        fun commandCentroid(): Pair<Double, Double> {
            val probeRenderer = IsometricRenderer(
                engine = IsometricEngine(),
                enablePathCaching = false,
                enableSpatialIndex = false
            )
            probeRenderer.rebuildCache(root, defaultContext, 800, 600)
            val scene = probeRenderer.currentPreparedScene!!
            val cmd = scene.commands.first()
            return cmd.avgX() to cmd.avgY()
        }

        val (testX, testY) = commandCentroid()

        val stableRenderer = IsometricRenderer(
            engine = IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = false
        )
        val stableHooks = CountingHooks()
        stableRenderer.benchmarkHooks = stableHooks
        stableRenderer.forceRebuild = false

        root.markClean()
        stableRenderer.hitTest(root, testX, testY, defaultContext, 800, 600)
        root.markClean()
        stableRenderer.hitTest(root, testX, testY, defaultContext, 800, 600)

        assertEquals("Stable scene should miss cache only on first access", 1, stableHooks.cacheMisses)
        assertEquals("Stable scene should hit cache on second access", 1, stableHooks.cacheHits)
        assertEquals("Stable scene should rebuild only once", 1, stableHooks.prepareStarts)
        assertEquals("Stable scene should complete one prepare", 1, stableHooks.prepareEnds)

        val forceRenderer = IsometricRenderer(
            engine = IsometricEngine(),
            enablePathCaching = false,
            enableSpatialIndex = false
        )
        val forceHooks = CountingHooks()
        forceRenderer.benchmarkHooks = forceHooks
        forceRenderer.forceRebuild = true

        root.markClean()
        forceRenderer.hitTest(root, testX, testY, defaultContext, 800, 600)
        root.markClean()
        forceRenderer.hitTest(root, testX, testY, defaultContext, 800, 600)

        assertEquals("forceRebuild should miss cache on every stable access", 2, forceHooks.cacheMisses)
        assertEquals("forceRebuild should prevent cache hits", 0, forceHooks.cacheHits)
        assertEquals("forceRebuild should rebuild on every access", 2, forceHooks.prepareStarts)
        assertEquals("forceRebuild should complete two prepares", 2, forceHooks.prepareEnds)
    }

    @Test
    fun `constructor rejects non-positive spatial index cell size`() {
        try {
            IsometricRenderer(
                engine = IsometricEngine(),
                enablePathCaching = false,
                enableSpatialIndex = true,
                spatialIndexCellSize = 0.0
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("spatialIndexCellSize"))
            return
        }
        throw AssertionError("Expected IllegalArgumentException for non-positive cell size")
    }

    // --- WS4 Error Handling Tests ---

    @Test
    fun `onRenderError is invoked when rebuildCache throws`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false, enableSpatialIndex = false)

        val errors = mutableListOf<Pair<String, Throwable>>()
        renderer.onRenderError = { id, error -> errors.add(id to error) }

        // Create a node that throws during render
        val root = GroupNode()
        val badNode = object : IsometricNode() {
            override fun renderTo(output: MutableList<io.github.jayteealao.isometric.RenderCommand>, context: RenderContext) {
                throw RuntimeException("Simulated render error")
            }
        }
        root.children.add(badNode)
        badNode.parent = root
        root.updateChildrenSnapshot()

        // rebuildCache should not throw — the error is caught and reported
        renderer.rebuildCache(root, defaultContext, 800, 600)

        assertEquals(1, errors.size)
        assertEquals("rebuild", errors[0].first)
        assertTrue(errors[0].second.message!!.contains("Simulated render error"))
    }

    @Test
    fun `rebuildCache preserves previous cache on failure`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false, enableSpatialIndex = false)
        renderer.onRenderError = { _, _ -> /* suppress */ }

        // Build a valid scene first
        val root = buildSceneRoot()
        renderer.rebuildCache(root, defaultContext, 800, 600)
        val validScene = renderer.currentPreparedScene
        assertNotNull(validScene)
        assertTrue(validScene!!.commands.isNotEmpty())

        // Now replace with a node that throws
        root.children.clear()
        val badNode = object : IsometricNode() {
            override fun renderTo(output: MutableList<io.github.jayteealao.isometric.RenderCommand>, context: RenderContext) {
                throw RuntimeException("fail")
            }
        }
        root.children.add(badNode)
        badNode.parent = root
        root.updateChildrenSnapshot()
        root.markDirty()

        // Rebuild fails — previous cache should be preserved
        renderer.rebuildCache(root, defaultContext, 800, 600)
        assertEquals(validScene, renderer.currentPreparedScene)
    }

    // --- WS4 Lifecycle Tests ---

    @Test
    fun `close is idempotent`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false, enableSpatialIndex = false)
        val root = buildSceneRoot()
        renderer.rebuildCache(root, defaultContext, 800, 600)

        // Call close twice — should not throw
        renderer.close()
        renderer.close()

        // Verify all cache fields are null
        assertNull(renderer.currentPreparedScene)
    }

    @Test
    fun `close clears callbacks and prevents reuse`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
        renderer.benchmarkHooks = CountingHooks()
        renderer.onRenderError = { _, _ -> }

        renderer.close()

        // After close, all cache is cleared
        assertNull(renderer.currentPreparedScene)

        // Any attempt to use the renderer after close should throw
        val root = buildSceneRoot()
        try {
            renderer.hitTest(root, 0.0, 0.0, defaultContext, 800, 600)
            throw AssertionError("Expected IllegalStateException after close")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("closed"))
        }
    }

    // --- WS4 Atomic ID Tests ---

    @Test
    fun `node IDs are unique across many nodes`() {
        val ids = mutableSetOf<String>()
        repeat(10000) {
            val node = ShapeNode(
                shape = io.github.jayteealao.isometric.shapes.Prism(io.github.jayteealao.isometric.Point.ORIGIN),
                color = IsoColor.BLUE
            )
            assertTrue("Node ID should be unique: ${node.nodeId}", ids.add(node.nodeId))
        }
        assertEquals(10000, ids.size)
    }

    @Test
    fun `node IDs are unique across concurrent creation`() {
        val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val latch = java.util.concurrent.CountDownLatch(2)
        val nodesPerThread = 5000

        val t1 = Thread {
            repeat(nodesPerThread) {
                val node = ShapeNode(
                    shape = io.github.jayteealao.isometric.shapes.Prism(io.github.jayteealao.isometric.Point.ORIGIN),
                    color = IsoColor.BLUE
                )
                ids.add(node.nodeId)
            }
            latch.countDown()
        }
        val t2 = Thread {
            repeat(nodesPerThread) {
                val node = PathNode(
                    path = io.github.jayteealao.isometric.Path(
                        io.github.jayteealao.isometric.Point.ORIGIN,
                        io.github.jayteealao.isometric.Point(1.0, 0.0, 0.0),
                        io.github.jayteealao.isometric.Point(0.0, 1.0, 0.0)
                    ),
                    color = IsoColor.RED
                )
                ids.add(node.nodeId)
            }
            latch.countDown()
        }

        t1.start()
        t2.start()
        latch.await()

        assertEquals(
            "All ${nodesPerThread * 2} IDs should be unique",
            nodesPerThread * 2,
            ids.size
        )
    }

    // --- WS4 fix 5: Platform safety ---

    @Test
    fun `validateNativeCanvasPlatform succeeds on Android classpath`() {
        // Paparazzi / Robolectric puts android.graphics.Canvas on the classpath,
        // so this should not throw. On a pure JVM it would throw IllegalStateException.
        validateNativeCanvasPlatform()
    }

    // --- WS4 fix 4: Leaf node children ---

    @Test
    fun `leaf nodes share immutable children singleton`() {
        val shape1 = ShapeNode(
            shape = Prism(Point.ORIGIN),
            color = IsoColor.BLUE
        )
        val shape2 = ShapeNode(
            shape = Prism(Point.ORIGIN),
            color = IsoColor.RED
        )
        val path = PathNode(
            path = Path(Point.ORIGIN, Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0)),
            color = IsoColor.GREEN
        )

        // All leaf nodes should share the same children list instance (zero-allocation singleton)
        assertTrue(
            "Leaf nodes should share the same children instance",
            shape1.children === shape2.children && shape2.children === path.children
        )
        assertTrue("Leaf children should be empty", shape1.children.isEmpty())
    }

    // --- WS4 fix 3: rebuildCache closed guard ---

    @Test
    fun `rebuildCache throws after close`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine = engine)
        renderer.close()

        val root = GroupNode()
        val error = try {
            renderer.rebuildCache(root, defaultContext, 800, 600)
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertNotNull("rebuildCache after close should throw IllegalStateException", error)
        assertTrue(
            error!!.message!!.contains("closed")
        )
    }

    // --- prepareSceneForGpu tests ---

    @Test
    fun `prepareSceneForGpu produces a PreparedScene with commands`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
        val root = buildSceneRoot()

        val scene = renderer.prepareSceneForGpu(root, defaultContext, 800, 600)

        assertNotNull("prepareSceneForGpu should return a non-null PreparedScene", scene)
        assertTrue(
            "PreparedScene should contain at least one command for a shape node",
            scene!!.commands.isNotEmpty()
        )
    }

    @Test
    fun `prepareSceneForGpu returns cached scene on second call without changes`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
        val root = buildSceneRoot()

        val first = renderer.prepareSceneForGpu(root, defaultContext, 800, 600)
        assertNotNull(first)

        // No mutations — tree is still clean, context and dimensions are identical
        val second = renderer.prepareSceneForGpu(root, defaultContext, 800, 600)

        assertTrue(
            "prepareSceneForGpu should return the same PreparedScene instance on cache hit",
            first === second
        )
    }

    @Test
    fun `prepareSceneForGpu returns empty commands for empty scene`() {
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
        val emptyRoot = GroupNode()
        emptyRoot.updateChildrenSnapshot()

        val scene = renderer.prepareSceneForGpu(emptyRoot, defaultContext, 800, 600)

        assertNotNull("prepareSceneForGpu should return a PreparedScene even for an empty scene", scene)
        assertTrue(
            "PreparedScene for an empty scene should have no commands",
            scene!!.commands.isEmpty()
        )
    }

    @Test
    fun `prepareSceneForGpu uses baseColor not lit color`() {
        // The GPU pipeline skips CPU lighting — commands are emitted directly from
        // ShapeNode.renderTo() without running engine.projectScene(). That means
        // RenderCommand.color and RenderCommand.baseColor are both the raw material
        // color (ShapeNode.color), with no lighting adjustment applied.
        val engine = IsometricEngine()
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
        val root = buildSceneRoot() // ShapeNode with IsoColor.BLUE

        val scene = renderer.prepareSceneForGpu(root, defaultContext, 800, 600)

        assertNotNull(scene)
        assertTrue("Scene should have commands to verify colors on", scene!!.commands.isNotEmpty())
        for (command in scene.commands) {
            assertEquals(
                "In the GPU path, baseColor must equal color — no CPU lighting applied",
                command.baseColor,
                command.color
            )
        }
    }
}
