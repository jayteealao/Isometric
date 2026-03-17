package io.github.jayteealao.isometric.sample

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * Unit tests for [MultiShapeNode], which was added/updated in this PR as part of
 * the package migration from io.fabianterhorst to io.github.jayteealao, and the
 * renderTo() API migration from render() returning a list to the accumulator pattern.
 */
class MultiShapeNodeTest {

    private fun baseContext() = RenderContext(
        width = 800,
        height = 600,
        renderOptions = RenderOptions.Default
    )

    private fun MultiShapeNode.collectCommands(context: RenderContext = baseContext()): List<RenderCommand> {
        val output = mutableListOf<RenderCommand>()
        renderTo(output, context)
        return output
    }

    // ── Visibility ────────────────────────────────────────────────────────────

    @Test
    fun `invisible node produces no commands`() {
        val node = MultiShapeNode(
            shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )
        node.isVisible = false

        val commands = node.collectCommands()

        assertTrue("Invisible MultiShapeNode must produce no render commands", commands.isEmpty())
    }

    @Test
    fun `visible node with one shape produces commands`() {
        val node = MultiShapeNode(
            shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )

        val commands = node.collectCommands()

        assertFalse("Visible MultiShapeNode with one shape should produce render commands", commands.isEmpty())
    }

    // ── Empty input ───────────────────────────────────────────────────────────

    @Test
    fun `empty shapes list produces no commands`() {
        val node = MultiShapeNode(
            shapes = emptyList(),
            colors = emptyList()
        )

        val commands = node.collectCommands()

        assertTrue("MultiShapeNode with empty shapes list should produce no commands", commands.isEmpty())
    }

    // ── Command ownership and ID format ───────────────────────────────────────

    @Test
    fun `all commands have the correct ownerNodeId`() {
        val node = MultiShapeNode(
            shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )

        val commands = node.collectCommands()

        commands.forEach { cmd ->
            assertEquals(
                "Every RenderCommand must report ownerNodeId == nodeId",
                node.nodeId,
                cmd.ownerNodeId
            )
        }
    }

    @Test
    fun `command IDs start with the node ID`() {
        val node = MultiShapeNode(
            shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )

        val commands = node.collectCommands()

        commands.forEach { cmd ->
            assertTrue(
                "commandId '${cmd.commandId}' must start with nodeId '${node.nodeId}'",
                cmd.commandId.startsWith(node.nodeId)
            )
        }
    }

    // ── Color assignment ──────────────────────────────────────────────────────

    @Test
    fun `colors are assigned to their respective shapes by zip index`() {
        val blueShape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0)
        val redShape = Prism(Point(2.0, 0.0, 0.0), 1.0, 1.0, 1.0)

        val node = MultiShapeNode(
            shapes = listOf(blueShape, redShape),
            colors = listOf(IsoColor.BLUE, IsoColor.RED)
        )

        val commands = node.collectCommands()

        // All commands from the first shape (lowest path hash values) should be BLUE,
        // and all commands from the second shape should be RED.
        // We verify by checking that both IsoColor.BLUE and IsoColor.RED appear.
        val colorsUsed = commands.map { it.color }.toSet()
        assertTrue("BLUE should appear in commands", colorsUsed.contains(IsoColor.BLUE))
        assertTrue("RED should appear in commands", colorsUsed.contains(IsoColor.RED))
    }

    @Test
    fun `extra colors beyond shapes count are ignored via zip`() {
        // zip() stops at the shorter list — extra colors should be silently dropped
        val node = MultiShapeNode(
            shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE, IsoColor.RED, IsoColor(255.0, 0.0, 255.0))
        )

        val commands = node.collectCommands()

        // Only one shape, so only BLUE commands should appear
        assertFalse("Commands should not be empty when one shape is present", commands.isEmpty())
        commands.forEach { cmd ->
            assertEquals("Only the first color (BLUE) should be used", IsoColor.BLUE, cmd.color)
        }
    }

    @Test
    fun `extra shapes beyond colors count are ignored via zip`() {
        // zip() stops at the shorter list — extra shapes should be silently dropped
        val node = MultiShapeNode(
            shapes = listOf(
                Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
                Prism(Point(2.0, 0.0, 0.0), 1.0, 1.0, 1.0)
            ),
            colors = listOf(IsoColor.BLUE)
        )

        val commandsFromOnlyOneShape = node.collectCommands()
        val commandsFromSingleShape = run {
            val singleNode = MultiShapeNode(
                shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
                colors = listOf(IsoColor.BLUE)
            )
            singleNode.collectCommands()
        }

        assertEquals(
            "When colors list is shorter, command count should match one shape",
            commandsFromSingleShape.size,
            commandsFromOnlyOneShape.size
        )
    }

    // ── Multiple shapes ───────────────────────────────────────────────────────

    @Test
    fun `multiple shapes each produce their own set of commands`() {
        val singleNode = MultiShapeNode(
            shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )
        val singleCommands = singleNode.collectCommands()

        val doubleNode = MultiShapeNode(
            shapes = listOf(
                Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
                Prism(Point(2.0, 0.0, 0.0), 1.0, 1.0, 1.0)
            ),
            colors = listOf(IsoColor.BLUE, IsoColor.RED)
        )
        val doubleCommands = doubleNode.collectCommands()

        assertEquals(
            "Two identical shapes should produce exactly twice as many commands",
            singleCommands.size * 2,
            doubleCommands.size
        )
    }

    // ── Position translation ──────────────────────────────────────────────────

    @Test
    fun `node position translates all shape path points`() {
        val atOrigin = MultiShapeNode(
            shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )
        val originCommands = atOrigin.collectCommands()

        val offset = Point(5.0, 3.0, 1.0)
        val translated = MultiShapeNode(
            shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )
        translated.position = offset
        val translatedCommands = translated.collectCommands()

        assertEquals(originCommands.size, translatedCommands.size)
        for (i in originCommands.indices) {
            val origPoints = originCommands[i].originalPath.points
            val transPoints = translatedCommands[i].originalPath.points
            assertEquals(origPoints.size, transPoints.size)
            origPoints.zip(transPoints).forEach { (orig, trans) ->
                assertEquals("X should be offset by ${offset.x}", orig.x + offset.x, trans.x, 0.001)
                assertEquals("Y should be offset by ${offset.y}", orig.y + offset.y, trans.y, 0.001)
                assertEquals("Z should be offset by ${offset.z}", orig.z + offset.z, trans.z, 0.001)
            }
        }
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    @Test
    fun `zero rotation leaves path points unchanged`() {
        val noRotation = MultiShapeNode(
            shapes = listOf(Prism(Point(3.0, 0.0, 0.0), 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )
        // rotation defaults to 0.0 — should skip the rotation branch entirely
        val commands = noRotation.collectCommands()

        // Position-only shift: no rotation applied
        val avgX = commands.flatMap { it.originalPath.points }.map { it.x }.average()
        assertTrue("Shape at x=3 with no rotation should have positive average X", avgX > 0)
    }

    @Test
    fun `non-zero rotation changes shape path coordinates`() {
        val noRotNode = MultiShapeNode(
            shapes = listOf(Prism(Point(3.0, 0.0, 0.0), 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )
        val noRotCommands = noRotNode.collectCommands()

        val rotNode = MultiShapeNode(
            shapes = listOf(Prism(Point(3.0, 0.0, 0.0), 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )
        rotNode.rotation = PI / 2
        val rotCommands = rotNode.collectCommands()

        val noRotAvgX = noRotCommands.flatMap { it.originalPath.points }.map { it.x }.average()
        val rotAvgX = rotCommands.flatMap { it.originalPath.points }.map { it.x }.average()
        assertTrue(
            "PI/2 rotation should significantly shift the average X coordinate",
            abs(noRotAvgX - rotAvgX) > 1.0
        )
    }

    @Test
    fun `rotation uses rotationOrigin when set`() {
        val customOrigin = Point(10.0, 10.0, 0.0)

        val defaultOriginNode = MultiShapeNode(
            shapes = listOf(Prism(Point(3.0, 0.0, 0.0), 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )
        defaultOriginNode.rotation = PI / 2
        val defaultCommands = defaultOriginNode.collectCommands()

        val customOriginNode = MultiShapeNode(
            shapes = listOf(Prism(Point(3.0, 0.0, 0.0), 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )
        customOriginNode.rotation = PI / 2
        customOriginNode.rotationOrigin = customOrigin
        val customCommands = customOriginNode.collectCommands()

        val defaultAvgX = defaultCommands.flatMap { it.originalPath.points }.map { it.x }.average()
        val customAvgX = customCommands.flatMap { it.originalPath.points }.map { it.x }.average()
        assertFalse(
            "Rotating around different origins should produce different coordinates",
            abs(defaultAvgX - customAvgX) < 0.001
        )
    }

    // ── originalShape on commands ─────────────────────────────────────────────

    @Test
    fun `commands carry a non-null originalShape`() {
        val node = MultiShapeNode(
            shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )

        val commands = node.collectCommands()

        commands.forEach { cmd ->
            assertTrue(
                "RenderCommand.originalShape must be non-null for MultiShapeNode commands",
                cmd.originalShape != null
            )
        }
    }

    // ── Varied shape types ────────────────────────────────────────────────────

    @Test
    fun `pyramid and prism shapes both produce commands`() {
        val node = MultiShapeNode(
            shapes = listOf(
                Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
                Pyramid(Point(3.0, 0.0, 0.0), 1.0, 1.0, 1.0)
            ),
            colors = listOf(IsoColor.BLUE, IsoColor.RED)
        )

        val commands = node.collectCommands()

        assertFalse("Mixed shape types should still produce commands", commands.isEmpty())
    }

    // ── Context-accumulated transforms ────────────────────────────────────────

    @Test
    fun `context transforms are applied before node-local position`() {
        // Shape with no local offset; context offset should still shift paths
        val node = MultiShapeNode(
            shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )

        val contextWithOffset = baseContext().withTransform(position = Point(7.0, 0.0, 0.0))
        val commandsWithOffset = node.collectCommands(contextWithOffset)
        val commandsNoOffset = node.collectCommands(baseContext())

        val avgXWithOffset = commandsWithOffset.flatMap { it.originalPath.points }.map { it.x }.average()
        val avgXNoOffset = commandsNoOffset.flatMap { it.originalPath.points }.map { it.x }.average()
        assertTrue(
            "Context-level position offset should shift path X coordinates",
            abs(avgXWithOffset - avgXNoOffset) > 1.0
        )
    }

    // ── Regression: accumulator pattern ──────────────────────────────────────

    @Test
    fun `renderTo appends to existing output list without clearing it`() {
        val node = MultiShapeNode(
            shapes = listOf(Prism(Point.ORIGIN, 1.0, 1.0, 1.0)),
            colors = listOf(IsoColor.BLUE)
        )

        val output = mutableListOf<RenderCommand>()
        // Pre-populate the output list with a sentinel command
        val sentinel = RenderCommand(
            commandId = "sentinel",
            points = emptyList(),
            color = IsoColor.BLUE,
            originalPath = io.github.jayteealao.isometric.Path(Point.ORIGIN, Point(1.0, 0.0, 0.0), Point(0.0, 1.0, 0.0)),
            originalShape = null,
            ownerNodeId = null
        )
        output.add(sentinel)

        node.renderTo(output, baseContext())

        assertTrue("Sentinel command must still be in output after renderTo()", output.contains(sentinel))
        assertTrue("renderTo() must append new commands, not replace the list", output.size > 1)
    }
}