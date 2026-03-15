package io.fabianterhorst.isometric.compose.runtime

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.shapes.Prism
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

    @Test
    fun shapeNodeProducesCommandsWithCorrectIdPrefix() {
        val node = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        val commands = node.render(baseContext())
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
        val commands = node.render(baseContext())
        assertTrue("Invisible node should produce no commands", commands.isEmpty())
    }

    @Test
    fun groupNodeAppliesPositionOffsetToChildShape() {
        // Render shape alone at origin
        val shapeAlone = ShapeNode(
            shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        val aloneCommands = shapeAlone.render(baseContext())

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

        val groupCommands = group.render(baseContext())
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

        val commands = group.render(baseContext())
        assertTrue("Invisible group should produce no commands", commands.isEmpty())
    }

    @Test
    fun groupNodeRotationAffectsChildShapePaths() {
        // Render shape at (5,0,0) with no rotation
        val shapeNoRotation = ShapeNode(
            shape = Prism(Point(5.0, 0.0, 0.0), 1.0, 1.0, 1.0),
            color = IsoColor.BLUE
        )
        val noRotCommands = shapeNoRotation.render(baseContext())

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

        val rotatedCommands = group.render(baseContext())
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
        val commands = group.render(baseContext())
        assertTrue("Empty group should produce no commands", commands.isEmpty())
    }
}
