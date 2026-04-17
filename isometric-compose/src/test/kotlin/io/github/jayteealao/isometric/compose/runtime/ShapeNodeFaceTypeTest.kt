package io.github.jayteealao.isometric.compose.runtime

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.OctahedronFace
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.PrismFace
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.PyramidFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Regression tests for `ShapeNode.renderTo` `faceType` emission.
 *
 * Motivation (I-03): `Shape.rotateZ` and `Shape.scale` are non-open methods on the
 * base `Shape` class that return the base `Shape` type. When a `ShapeNode` carrying
 * a `Pyramid`/`Octahedron` is rendered under a `RenderContext` with non-zero
 * `accumulatedRotation` or non-unit `accumulatedScale`, `applyTransformsToShape`
 * erases the concrete Kotlin type. If `faceType` dispatch in `ShapeNode.renderTo`
 * ran on the post-transform shape, every `is <Shape>` arm would miss and faceType
 * would silently become `null` — cascading into `PerFace.resolveForFace(null)`
 * returning `default`, causing flat-gray rendering on non-identity-transformed
 * Pyramids and Octahedrons.
 *
 * These tests pin the pre-transform dispatch so regressing would immediately fail.
 */
class ShapeNodeFaceTypeTest {

    private fun baseContext() = RenderContext(
        width = 800,
        height = 600,
        renderOptions = RenderOptions.Default,
    )

    private fun ShapeNode.collect(context: RenderContext = baseContext()): List<RenderCommand> {
        val output = mutableListOf<RenderCommand>()
        renderTo(output, context)
        return output
    }

    // ---- Identity transform baseline ----

    @Test
    fun `pyramid emits typed PyramidFace faceType under identity transform`() {
        val node = ShapeNode(shape = Pyramid(Point.ORIGIN), color = IsoColor.WHITE)
        val commands = node.collect()
        assertEquals("Pyramid should emit 5 face commands (4 laterals + base)", 5, commands.size)
        for ((i, cmd) in commands.withIndex()) {
            val ft = cmd.faceType
            assertNotNull("faceType must be non-null for Pyramid face $i", ft)
            assertTrue(
                "faceType for face $i must be PyramidFace, got ${ft!!::class.simpleName}",
                ft is PyramidFace,
            )
            assertEquals(
                "faceType for face $i must equal PyramidFace.fromPathIndex($i)",
                PyramidFace.fromPathIndex(i),
                ft,
            )
        }
    }

    @Test
    fun `octahedron emits typed OctahedronFace faceType under identity transform`() {
        val node = ShapeNode(shape = Octahedron(Point.ORIGIN), color = IsoColor.WHITE)
        val commands = node.collect()
        assertEquals("Octahedron emits 8 face commands", 8, commands.size)
        for ((i, cmd) in commands.withIndex()) {
            assertNotNull("faceType must be non-null for Octahedron face $i", cmd.faceType)
            assertTrue(cmd.faceType is OctahedronFace)
            assertEquals(OctahedronFace.fromPathIndex(i), cmd.faceType)
        }
    }

    @Test
    fun `prism emits typed PrismFace faceType under identity transform`() {
        val node = ShapeNode(shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0), color = IsoColor.WHITE)
        val commands = node.collect()
        assertEquals(6, commands.size)
        for ((i, cmd) in commands.withIndex()) {
            assertEquals(PrismFace.fromPathIndex(i), cmd.faceType)
        }
    }

    // ---- Scale != 1.0 — erases Shape subtype through Shape.scale ----

    @Test
    fun `pyramid scale emits typed PyramidFace faceType after scale transform`() {
        // Reproduces the exact scenario that broke I-03: the sample app's
        // Shape(geometry = Pyramid(...), material = pyramidPerFace { ... }, scale = 3.0)
        // exercises applyTransformsToShape → Shape.scale which erases the Pyramid type.
        val node = ShapeNode(shape = Pyramid(Point.ORIGIN), color = IsoColor.WHITE).apply {
            scale = 3.0
        }
        val commands = node.collect()
        assertEquals(5, commands.size)
        for ((i, cmd) in commands.withIndex()) {
            assertNotNull(
                "Under scale=3.0, faceType must still resolve to PyramidFace (I-03 regression)",
                cmd.faceType,
            )
            assertEquals(PyramidFace.fromPathIndex(i), cmd.faceType)
        }
    }

    @Test
    fun `pyramid faceVertexCount is 3 for laterals and 4 for base even under scale`() {
        // Mixed-vertex-count property of Pyramid must survive the transform round trip.
        val node = ShapeNode(shape = Pyramid(Point.ORIGIN), color = IsoColor.WHITE).apply {
            scale = 3.0
        }
        val commands = node.collect()
        for (i in 0..3) {
            assertEquals("Lateral $i must carry faceVertexCount=3", 3, commands[i].faceVertexCount)
        }
        assertEquals("Base must carry faceVertexCount=4", 4, commands[4].faceVertexCount)
    }

    @Test
    fun `octahedron scale emits typed OctahedronFace faceType after scale transform`() {
        val node = ShapeNode(shape = Octahedron(Point.ORIGIN), color = IsoColor.WHITE).apply {
            scale = 3.0
        }
        val commands = node.collect()
        assertEquals(8, commands.size)
        for ((i, cmd) in commands.withIndex()) {
            assertNotNull(cmd.faceType)
            assertEquals(OctahedronFace.fromPathIndex(i), cmd.faceType)
        }
    }

    // ---- Rotation != 0 — erases Shape subtype through Shape.rotateZ ----

    @Test
    fun `pyramid rotation emits typed PyramidFace faceType after rotateZ transform`() {
        val node = ShapeNode(shape = Pyramid(Point.ORIGIN), color = IsoColor.WHITE).apply {
            rotation = PI / 4.0
        }
        val commands = node.collect()
        assertEquals(5, commands.size)
        for ((i, cmd) in commands.withIndex()) {
            assertNotNull(cmd.faceType)
            assertEquals(PyramidFace.fromPathIndex(i), cmd.faceType)
        }
    }

    @Test
    fun `pyramid combined rotation plus scale still emits typed PyramidFace`() {
        val node = ShapeNode(shape = Pyramid(Point.ORIGIN), color = IsoColor.WHITE).apply {
            rotation = PI / 3.0
            scale = 2.5
        }
        val commands = node.collect()
        assertEquals(5, commands.size)
        for ((i, cmd) in commands.withIndex()) {
            assertNotNull(cmd.faceType)
            assertEquals(PyramidFace.fromPathIndex(i), cmd.faceType)
        }
    }

    // ---- Parent-group accumulated transform ----

    @Test
    fun `pyramid under rotated GroupNode parent still emits typed PyramidFace`() {
        // Simulates an IsometricScene where the viewport/parent group applies a rotation
        // above the leaf Shape. GroupNode.renderTo pushes accumulatedRotation onto the
        // RenderContext, which then feeds applyTransformsToShape.
        val shapeNode = ShapeNode(shape = Pyramid(Point.ORIGIN), color = IsoColor.WHITE)
        val group = GroupNode().apply {
            rotation = PI / 6.0
            scale = 1.7
        }
        group.children.add(shapeNode)
        // GroupNode.renderTo iterates `childrenSnapshot`, which is updated only
        // after Applier mutations via updateChildrenSnapshot() (copy-on-write).
        group.updateChildrenSnapshot()
        val output = mutableListOf<RenderCommand>()
        group.renderTo(output, baseContext())
        assertEquals(5, output.size)
        for ((i, cmd) in output.withIndex()) {
            assertNotNull(
                "GroupNode-applied transforms must not erase the leaf shape's faceType dispatch",
                cmd.faceType,
            )
            assertEquals(PyramidFace.fromPathIndex(i), cmd.faceType)
        }
    }
}
