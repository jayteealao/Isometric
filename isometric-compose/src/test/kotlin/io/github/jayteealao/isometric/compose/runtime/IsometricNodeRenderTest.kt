package io.github.jayteealao.isometric.compose.runtime

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.shapes.Prism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class IsometricNodeRenderTest {

    private fun baseContext() = RenderContext(
        width = 800,
        height = 600,
        renderOptions = RenderOptions.Default
    )

    /** Helper: collect render commands from a node using the accumulator pattern. */
    private fun IsometricNode.collectCommands(context: RenderContext): List<RenderCommand> {
        val output = mutableListOf<RenderCommand>()
        renderTo(output, context)
        return output
    }

    @Test
    fun shapeNodeProducesCommandsWithCorrectIdPrefix() {
        val node = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        val commands = node.collectCommands(baseContext())
        assertTrue("Prism should produce render commands", commands.isNotEmpty())
        commands.forEach { cmd ->
            assertTrue(
                "Command ID '${cmd.commandId}' should start with node ID '${node.nodeId}'",
                cmd.commandId.startsWith(node.nodeId)
            )
        }
    }

    @Test
    fun invisibleShapeNodeReturnsEmptyCommands() {
        val node = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        node.isVisible = false
        val commands = node.collectCommands(baseContext())
        assertTrue("Invisible node should produce no commands", commands.isEmpty())
    }

    @Test
    fun groupNodeAppliesPositionOffsetToChildShape() {
        // Render shape alone at origin
        val shapeAlone = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        val aloneCommands = shapeAlone.collectCommands(baseContext())

        // Render same shape inside a group offset by (5,0,0)
        val group = GroupNode()
        group.position = Point(5.0, 0.0, 0.0)
        val childShape = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        group.children.add(childShape)
        childShape.parent = group
        group.updateChildrenSnapshot()

        val groupCommands = group.collectCommands(baseContext())
        assertTrue("Group should produce commands from child", groupCommands.isNotEmpty())

        // Each path point in the group version should be offset by (5,0,0)
        for (i in aloneCommands.indices) {
            val alonePoints = aloneCommands[i].originalPath.points
            val groupPoints = groupCommands[i].originalPath.points
            assertEquals(alonePoints.size, groupPoints.size)
            for (j in alonePoints.indices) {
                assertEquals(alonePoints[j].x + 5.0, groupPoints[j].x, 0.001)
                assertEquals(alonePoints[j].y, groupPoints[j].y, 0.001)
                assertEquals(alonePoints[j].z, groupPoints[j].z, 0.001)
            }
        }
    }

    @Test
    fun invisibleGroupNodeReturnsEmptyCommands() {
        val group = GroupNode()
        group.isVisible = false
        val childShape = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        group.children.add(childShape)
        childShape.parent = group
        group.updateChildrenSnapshot()

        val commands = group.collectCommands(baseContext())
        assertTrue("Invisible group should produce no commands", commands.isEmpty())
    }

    @Test
    fun groupNodeRotationAffectsChildShapePaths() {
        // Render shape at (5,0,0) with no rotation
        val shapeNoRotation = ShapeNode(
            shape = Prism(Point(5.0, 0.0, 0.0), 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        val noRotCommands = shapeNoRotation.collectCommands(baseContext())

        // Render same shape inside a group rotated PI/2
        val group = GroupNode()
        group.rotation = PI / 2
        val childShape = ShapeNode(
            shape = Prism(Point(5.0, 0.0, 0.0), 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        group.children.add(childShape)
        childShape.parent = group
        group.updateChildrenSnapshot()

        val rotatedCommands = group.collectCommands(baseContext())
        assertTrue("Rotated group should produce commands", rotatedCommands.isNotEmpty())

        // The rotated shape should have different X/Y coordinates
        val noRotAvgX = noRotCommands.flatMap { it.originalPath.points }.map { it.x }.average()
        val rotatedAvgX = rotatedCommands.flatMap { it.originalPath.points }.map { it.x }.average()
        assertTrue(
            "Rotation should change X coordinates significantly",
            abs(noRotAvgX - rotatedAvgX) > 1.0
        )
    }

    @Test
    fun emptyGroupNodeReturnsEmptyCommands() {
        val group = GroupNode()
        group.updateChildrenSnapshot()
        val commands = group.collectCommands(baseContext())
        assertTrue("Empty group should produce no commands", commands.isEmpty())
    }

    // --- AC-18: Engine parameter mutation detected by SceneCache -------------------
    //
    // The compose-layer redraw on an idle scene (no dirty nodes) requires a polling
    // bridge in IsometricScene.kt that detects projectionVersion changes and increments
    // sceneVersion. This test proves the CACHE side of that contract: SceneCache.needsUpdate
    // returns true after engine.scale changes, regardless of node dirtiness.

    @Test
    fun `AC-18 SceneCache detects engine scale mutation via projectionVersion`() {
        val engine = IsometricEngine()
        val cache = SceneCache(engine, enablePathCaching = false)
        val rootNode = GroupNode()
        rootNode.updateChildrenSnapshot()
        val context = baseContext()

        // Prime the cache: one rebuild to establish a baseline projectionVersion.
        cache.rebuild(rootNode, context, 800, 600, onRenderError = null)
        rootNode.markClean()

        // Mutate the engine scale — this bumps projectionVersion inside IsometricEngine.
        engine.scale = 100.0

        // SceneCache.needsUpdate must detect the projectionVersion change.
        assertTrue(
            "needsUpdate must return true after engine.scale mutation",
            cache.needsUpdate(rootNode, context, 800, 600)
        )
    }

    @Test
    fun `AC-18 SceneCache detects engine angle mutation via projectionVersion`() {
        val engine = IsometricEngine()
        val cache = SceneCache(engine, enablePathCaching = false)
        val rootNode = GroupNode()
        rootNode.updateChildrenSnapshot()
        val context = baseContext()

        cache.rebuild(rootNode, context, 800, 600, onRenderError = null)
        rootNode.markClean()

        // Mutate angle — also bumps projectionVersion
        engine.angle = kotlin.math.PI / 4

        assertTrue(
            "needsUpdate must return true after engine.angle mutation",
            cache.needsUpdate(rootNode, context, 800, 600)
        )
    }

    // --- IsometricNode.alpha: validation and render propagation ----------------------

    @Test
    fun alphaSetterRejectsNegativeValue() {
        val node = ShapeNode(shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0), color = IsoColor.BLUE)
        var threw = false
        try {
            node.alpha = -0.1f
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("Setting alpha = -0.1f must throw IllegalArgumentException", threw)
    }

    @Test
    fun alphaSetterRejectsValueAboveOne() {
        val node = ShapeNode(shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0), color = IsoColor.BLUE)
        var threw = false
        try {
            node.alpha = 1.5f
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("Setting alpha = 1.5f must throw IllegalArgumentException", threw)
    }

    @Test
    fun alphaHalfScalesCommandColorAlphaBelowOriginal() {
        // ShapeNode with fully-opaque color (a = 255).
        val node = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor(200.0, 100.0, 50.0, 255.0)
        )
        node.alpha = 0.5f
        val commands = node.collectCommands(baseContext())
        assertTrue("Prism must produce at least one render command", commands.isNotEmpty())
        // IsoColor.withAlpha(0.5f): a = (255.0 * 0.5).coerceIn(0.0, 255.0) = 127.5
        for (cmd in commands) {
            assertEquals(
                "Command color alpha must equal 127.5 when node.alpha = 0.5 and base alpha = 255",
                127.5,
                cmd.color.a,
                0.5
            )
        }
    }

    // --- AC-G1: GroupNode alpha propagation -----------------------------------------

    @Test
    fun groupAlphaHalfScalesChildCommandAlpha() {
        // GroupNode alpha=0.5, child ShapeNode alpha=1.0, base color a=255.
        // Expected: cmd.color.a = 255 * 0.5 * 1.0 = 127.5
        val group = GroupNode()
        group.alpha = 0.5f
        val child = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor(200.0, 100.0, 50.0, 255.0)
        )
        child.alpha = 1.0f
        group.children.add(child)
        child.parent = group
        group.updateChildrenSnapshot()

        val commands = group.collectCommands(baseContext())
        assertTrue("Group with child must produce commands", commands.isNotEmpty())
        for (cmd in commands) {
            assertEquals(
                "Group alpha=0.5 with child alpha=1.0 and base a=255 must yield cmd.color.a ≈ 127.5",
                127.5,
                cmd.color.a,
                0.5
            )
        }
    }

    @Test
    fun nestedGroupAlphaMultiplies() {
        // Outer Group alpha=0.5, inner Group alpha=0.5, leaf ShapeNode alpha=1.0, base color a=255.
        // Expected: 255 * 0.5 * 0.5 * 1.0 = 63.75
        val outer = GroupNode()
        outer.alpha = 0.5f
        val inner = GroupNode()
        inner.alpha = 0.5f
        val leaf = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor(200.0, 100.0, 50.0, 255.0)
        )
        leaf.alpha = 1.0f
        inner.children.add(leaf)
        leaf.parent = inner
        inner.updateChildrenSnapshot()
        outer.children.add(inner)
        inner.parent = outer
        outer.updateChildrenSnapshot()

        val commands = outer.collectCommands(baseContext())
        assertTrue("Nested groups must produce commands", commands.isNotEmpty())
        for (cmd in commands) {
            assertEquals(
                "Outer alpha=0.5, inner alpha=0.5, leaf alpha=1.0, base a=255 must yield cmd.color.a ≈ 63.75",
                63.75,
                cmd.color.a,
                0.5
            )
        }
    }

    @Test
    fun groupAlphaZeroSkipsChildren() {
        // GroupNode with alpha=0 must produce no render commands (early return).
        val group = GroupNode()
        group.alpha = 0.0f
        val child = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        group.children.add(child)
        child.parent = group
        group.updateChildrenSnapshot()

        val commands = group.collectCommands(baseContext())
        assertTrue(
            "GroupNode with alpha=0 must skip children and produce no render commands",
            commands.isEmpty()
        )
    }
}
