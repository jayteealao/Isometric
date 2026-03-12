package io.fabianterhorst.isometric.compose.runtime

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector
import io.fabianterhorst.isometric.shapes.Prism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun buildBatchNodeRoot(shapes: List<io.fabianterhorst.isometric.Shape>): Pair<GroupNode, BatchNode> {
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
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
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
        val renderer = IsometricRenderer(engine, enablePathCaching = false)
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

    // --- Hit testing helpers ---

    private val defaultContext = RenderContext(
        width = 800, height = 600,
        renderOptions = RenderOptions.Quality,
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
        scene: io.fabianterhorst.isometric.PreparedScene,
        nodeId: String
    ): Pair<Double, Double>? {
        val cmd = scene.commands.firstOrNull { it.ownerNodeId == nodeId }
            ?: return null
        val cx = cmd.points.map { it.x }.average()
        val cy = cmd.points.map { it.y }.average()
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
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()

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
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()

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
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()

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
        val leftMostPoint = command.points.minBy { it.x }
        val boundaryX = kotlin.math.floor(leftMostPoint.x / cellSize) * cellSize
        val testX = boundaryX - 1.0
        val testY = leftMostPoint.y

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
        val centerX = command.points.map { it.x }.average()
        val centerY = command.points.map { it.y }.average()

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
        val x = command.points.map { it.x }.average()
        val y = command.points.map { it.y }.average()

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
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()

        renderer.invalidate()
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
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()

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
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()

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
            renderOptions = RenderOptions.Quality,
            lightDirection = dirA
        )
        renderer.rebuildCache(root, contextA, 800, 600)
        val scene = renderer.currentPreparedScene!!
        val cmd = scene.commands.first()
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()

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
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()

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
        assertEquals(800, renderer.currentPreparedScene!!.viewportWidth)
        assertEquals(600, renderer.currentPreparedScene!!.viewportHeight)

        // Call hitTest at a different viewport size — coordinates don't matter,
        // we just need hitTest to trigger a rebuild at the new size.
        val newWidth = 1024
        val newHeight = 768
        renderer.hitTest(root, 0.0, 0.0, defaultContext, newWidth, newHeight)

        // Verify the scene was rebuilt at the new viewport size
        assertEquals("viewportWidth should be updated after hitTest with new size",
            newWidth, renderer.currentPreparedScene!!.viewportWidth)
        assertEquals("viewportHeight should be updated after hitTest with new size",
            newHeight, renderer.currentPreparedScene!!.viewportHeight)

        // Verify hit testing actually works at the new size by computing a fresh centroid
        val scene = renderer.currentPreparedScene!!
        val cmd = scene.commands.first()
        val avgX = cmd.points.map { it.x }.average()
        val avgY = cmd.points.map { it.y }.average()

        val hit = renderer.hitTest(root, avgX, avgY, defaultContext, newWidth, newHeight)
        assertNotNull("hitTest should find shape at centroid after viewport resize", hit)
    }

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
            return cmd.points.map { it.x }.average() to cmd.points.map { it.y }.average()
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

        root.clearDirty()
        stableRenderer.hitTest(root, testX, testY, defaultContext, 800, 600)
        root.clearDirty()
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

        root.clearDirty()
        forceRenderer.hitTest(root, testX, testY, defaultContext, 800, 600)
        root.clearDirty()
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
}
