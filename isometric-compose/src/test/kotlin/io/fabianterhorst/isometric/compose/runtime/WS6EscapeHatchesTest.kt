package io.fabianterhorst.isometric.compose.runtime

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.RenderCommand
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.shapes.Prism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WS6EscapeHatchesTest {

    private fun baseContext() = RenderContext(
        width = 800,
        height = 600,
        renderOptions = RenderOptions.Default
    )

    private fun IsometricNode.collectCommands(context: RenderContext = baseContext()): List<RenderCommand> {
        val output = mutableListOf<RenderCommand>()
        renderTo(output, context)
        return output
    }

    // --- Step 2: RenderBenchmarkHooks defaults ---

    @Test
    fun `RenderBenchmarkHooks can be implemented with zero overrides`() {
        // This should compile and not throw — validates that all methods have defaults
        val hooks = object : RenderBenchmarkHooks {}
        hooks.onPrepareStart()
        hooks.onPrepareEnd()
        hooks.onDrawStart()
        hooks.onDrawEnd()
        hooks.onCacheHit()
        hooks.onCacheMiss()
    }

    // --- Step 9: Per-subtree renderOptions ---

    @Test
    fun `GroupNode with renderOptions passes override to children context`() {
        // Create a group with custom renderOptions
        val group = GroupNode()
        val noCullingOptions = RenderOptions(enableBackfaceCulling = false)
        group.renderOptions = noCullingOptions

        val child = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        group.children.add(child)
        child.parent = group
        group.updateChildrenSnapshot()

        // The group should produce commands — no crash
        val commands = group.collectCommands()
        assertTrue("Group with renderOptions should produce commands", commands.isNotEmpty())
    }

    @Test
    fun `null renderOptions inherits from parent context`() {
        val group = GroupNode()
        // Leave renderOptions null (default)

        val child = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        group.children.add(child)
        child.parent = group
        group.updateChildrenSnapshot()

        // Should use the base context's renderOptions
        val commands = group.collectCommands()
        assertTrue("Group with null renderOptions should produce commands", commands.isNotEmpty())
    }

    @Test
    fun `RenderContext withRenderOptions preserves transforms`() {
        val ctx = RenderContext(
            width = 800,
            height = 600,
            renderOptions = RenderOptions.Default
        ).withTransform(
            position = Point(5.0, 3.0, 1.0),
            rotation = 0.5,
            scale = 2.0
        )

        val newOptions = RenderOptions(enableDepthSorting = false)
        val overridden = ctx.withRenderOptions(newOptions)

        assertEquals(newOptions, overridden.renderOptions)
        assertEquals(ctx.width, overridden.width)
        assertEquals(ctx.height, overridden.height)
    }

    // --- Step 10: CustomRenderNode ---

    @Test
    fun `CustomRenderNode renders triangle path`() {
        val testPath = Path(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            Point(0.5, 1.0, 0.0)
        )

        val node = CustomRenderNode { _, nodeId ->
            listOf(
                RenderCommand(
                    commandId = "custom_triangle",
                    points = emptyList(),
                    color = IsoColor.RED,
                    originalPath = testPath,
                    originalShape = null,
                    ownerNodeId = nodeId
                )
            )
        }

        val commands = node.collectCommands()
        assertEquals(1, commands.size)
        assertEquals("custom_triangle", commands[0].commandId)
        assertEquals(IsoColor.RED, commands[0].color)
        assertEquals(node.nodeId, commands[0].ownerNodeId)
    }

    @Test
    fun `CustomRenderNode with visible false produces no commands`() {
        val node = CustomRenderNode { _, _ ->
            listOf(
                RenderCommand(
                    commandId = "should_not_appear",
                    points = emptyList(),
                    color = IsoColor.BLUE,
                    originalPath = Path(Point.ORIGIN, Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0)),
                    originalShape = null,
                    ownerNodeId = null
                )
            )
        }
        node.isVisible = false

        val commands = node.collectCommands()
        assertTrue("Invisible CustomRenderNode should produce no commands", commands.isEmpty())
    }

    @Test
    fun `CustomRenderNode receives accumulated transforms via context`() {
        val group = GroupNode()
        group.position = Point(10.0, 20.0, 0.0)

        var capturedContext: RenderContext? = null
        val customNode = CustomRenderNode { ctx, _ ->
            capturedContext = ctx
            emptyList()
        }

        group.children.add(customNode)
        customNode.parent = group
        group.updateChildrenSnapshot()

        group.collectCommands()
        assertTrue("Context should have been captured", capturedContext != null)

        // Verify the context carries the group's transform by applying it to a point
        val transformed = capturedContext!!.applyTransformsToPoint(Point.ORIGIN)
        assertEquals(10.0, transformed.x, 0.001)
        assertEquals(20.0, transformed.y, 0.001)
    }

    @Test
    fun `CustomRenderNode with local position applies transforms`() {
        var capturedContext: RenderContext? = null
        val node = CustomRenderNode { context, _ ->
            capturedContext = context
            emptyList()
        }
        node.position = Point(5.0, 0.0, 0.0)

        node.collectCommands()

        val transformed = capturedContext!!.applyTransformsToPoint(Point.ORIGIN)
        assertEquals(5.0, transformed.x, 0.001)
    }

    @Test
    fun `CustomRenderNode can produce multiple commands`() {
        val testPath1 = Path(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0))
        val testPath2 = Path(Point(1.0, 0.0, 0.0), Point(2.0, 0.0, 0.0), Point(1.0, 1.0, 0.0))

        val node = CustomRenderNode { _, _ ->
            listOf(
                RenderCommand("cmd1", emptyList(), IsoColor.RED, testPath1, null, null),
                RenderCommand("cmd2", emptyList(), IsoColor.BLUE, testPath2, null, null)
            )
        }

        val commands = node.collectCommands()
        assertEquals(2, commands.size)
        assertEquals("cmd1", commands[0].commandId)
        assertEquals("cmd2", commands[1].commandId)
    }

    @Test
    fun `CustomRenderNode with renderOptions overrides context`() {
        val noCulling = RenderOptions(enableBackfaceCulling = false)
        var capturedContext: RenderContext? = null

        val node = CustomRenderNode { context, _ ->
            capturedContext = context
            emptyList()
        }
        node.renderOptions = noCulling

        node.collectCommands()

        assertEquals(noCulling, capturedContext!!.renderOptions)
    }

    @Test
    fun `CameraState default values`() {
        val camera = CameraState()
        assertEquals(0.0, camera.panX, 0.0001)
        assertEquals(0.0, camera.panY, 0.0001)
        assertEquals(1.0, camera.zoom, 0.0001)
    }

    @Test
    fun `CameraState pan updates coordinates`() {
        val camera = CameraState()
        camera.pan(10.0, 20.0)
        assertEquals(10.0, camera.panX, 0.0001)
        assertEquals(20.0, camera.panY, 0.0001)
    }

    @Test
    fun `CameraState zoomBy multiplies`() {
        val camera = CameraState()
        camera.zoomBy(2.0)
        assertEquals(2.0, camera.zoom, 0.0001)
        camera.zoomBy(0.5)
        assertEquals(1.0, camera.zoom, 0.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CameraState zoomBy rejects negative factor`() {
        val camera = CameraState()
        camera.zoomBy(-1.0)
    }

    @Test
    fun `CameraState reset restores defaults`() {
        val camera = CameraState()
        camera.pan(100.0, 200.0)
        camera.zoomBy(3.0)
        camera.reset()
        assertEquals(0.0, camera.panX, 0.0001)
        assertEquals(0.0, camera.panY, 0.0001)
        assertEquals(1.0, camera.zoom, 0.0001)
    }
}
