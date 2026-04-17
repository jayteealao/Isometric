package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.CylinderFace
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.OctahedronFace
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.PrismFace
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.PyramidFace
import io.github.jayteealao.isometric.shapes.Stairs
import io.github.jayteealao.isometric.shapes.StairsFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the `uv-generation-shared-api` slice's shared surface: abstract sealed
 * [IsometricMaterial.PerFace] + 5 subclasses, [uvCoordProviderForShape] factory, and the
 * new [RenderCommand.faceVertexCount] field.
 *
 * Per-shape UV behaviour (cylinder/pyramid/stairs/octahedron/knot resolution) lives in
 * each shape slice's dedicated test file.
 */
class PerFaceSharedApiTest {

    private val red = IsoColor.RED
    private val green = IsoColor.GREEN
    private val blue = IsoColor.BLUE
    private val gray = IsoColor.GRAY

    // ---------- PerFace.Prism ----------

    @Test
    fun `PerFace_Prism resolves via faceMap with default fallback`() {
        val mat = IsometricMaterial.PerFace.Prism.of(
            faceMap = mapOf(PrismFace.TOP to red),
            default = gray,
        )
        assertEquals(red, mat.faceMap[PrismFace.TOP] ?: mat.default)
        assertEquals(gray, mat.faceMap[PrismFace.FRONT] ?: mat.default)
    }

    // ---------- PerFace.Cylinder ----------

    @Test
    fun `PerFace_Cylinder resolves TOP BOTTOM SIDE with named overrides`() {
        val mat = IsometricMaterial.PerFace.Cylinder(
            top = red,
            bottom = green,
            side = blue,
            default = gray,
        )
        assertEquals(red, mat.resolve(CylinderFace.TOP))
        assertEquals(green, mat.resolve(CylinderFace.BOTTOM))
        assertEquals(blue, mat.resolve(CylinderFace.SIDE))
    }

    @Test
    fun `PerFace_Cylinder falls back to default for unassigned slots`() {
        val mat = IsometricMaterial.PerFace.Cylinder(top = red, default = gray)
        assertEquals(red, mat.resolve(CylinderFace.TOP))
        assertEquals(gray, mat.resolve(CylinderFace.BOTTOM))
        assertEquals(gray, mat.resolve(CylinderFace.SIDE))
    }

    // ---------- PerFace.Pyramid ----------

    @Test
    fun `PerFace_Pyramid resolves BASE and laterals`() {
        val mat = IsometricMaterial.PerFace.Pyramid(
            base = red,
            laterals = mapOf(0 to green, 2 to blue),
            default = gray,
        )
        assertEquals(red, mat.resolve(PyramidFace.BASE))
        assertEquals(green, mat.resolve(PyramidFace.LATERAL_0))
        assertEquals(gray, mat.resolve(PyramidFace.LATERAL_1)) // unassigned
        assertEquals(blue, mat.resolve(PyramidFace.LATERAL_2))
        assertEquals(gray, mat.resolve(PyramidFace.LATERAL_3))
    }

    @Test
    fun `PerFace_Pyramid rejects out-of-range lateral keys`() {
        assertFailsWith<IllegalArgumentException> {
            IsometricMaterial.PerFace.Pyramid(laterals = mapOf(4 to red))
        }
        assertFailsWith<IllegalArgumentException> {
            IsometricMaterial.PerFace.Pyramid(laterals = mapOf(-1 to red))
        }
    }

    // ---------- PerFace.Stairs ----------

    @Test
    fun `PerFace_Stairs resolves TREAD RISER SIDE with named overrides`() {
        val mat = IsometricMaterial.PerFace.Stairs(
            tread = red,
            riser = green,
            side = blue,
            default = gray,
        )
        assertEquals(red, mat.resolve(StairsFace.TREAD))
        assertEquals(green, mat.resolve(StairsFace.RISER))
        assertEquals(blue, mat.resolve(StairsFace.SIDE))
    }

    @Test
    fun `PerFace_Stairs falls back to default for unassigned slots`() {
        val mat = IsometricMaterial.PerFace.Stairs(riser = green, default = gray)
        assertEquals(gray, mat.resolve(StairsFace.TREAD))
        assertEquals(green, mat.resolve(StairsFace.RISER))
        assertEquals(gray, mat.resolve(StairsFace.SIDE))
    }

    // ---------- PerFace.Octahedron ----------

    @Test
    fun `PerFace_Octahedron resolves byIndex with default fallback`() {
        val mat = IsometricMaterial.PerFace.Octahedron(
            byIndex = mapOf(
                OctahedronFace.UPPER_0 to red,
                OctahedronFace.LOWER_3 to blue,
            ),
            default = gray,
        )
        assertEquals(red, mat.resolve(OctahedronFace.UPPER_0))
        assertEquals(blue, mat.resolve(OctahedronFace.LOWER_3))
        assertEquals(gray, mat.resolve(OctahedronFace.UPPER_1))
        assertEquals(gray, mat.resolve(OctahedronFace.LOWER_0))
    }

    // ---------- Nesting ban ----------

    @Test
    fun `PerFace base rejects PerFace as default for every variant`() {
        val inner = IsometricMaterial.PerFace.Prism.of(
            faceMap = mapOf(PrismFace.TOP to red),
            default = gray,
        )
        assertFailsWith<IllegalArgumentException> {
            IsometricMaterial.PerFace.Prism.of(faceMap = emptyMap(), default = inner)
        }
        assertFailsWith<IllegalArgumentException> {
            IsometricMaterial.PerFace.Cylinder(default = inner)
        }
        assertFailsWith<IllegalArgumentException> {
            IsometricMaterial.PerFace.Pyramid(default = inner)
        }
        assertFailsWith<IllegalArgumentException> {
            IsometricMaterial.PerFace.Stairs(default = inner)
        }
        assertFailsWith<IllegalArgumentException> {
            IsometricMaterial.PerFace.Octahedron(default = inner)
        }
    }

    // ---------- uvCoordProviderForShape() ----------

    @Test
    fun `uvCoordProviderForShape returns non-null provider for Prism`() {
        val prism = Prism(Point.ORIGIN, 1.0, 1.0, 1.0)
        val provider = uvCoordProviderForShape(prism)
        assertNotNull(provider)
        val uvs = provider.provide(prism, 0)
        assertNotNull(uvs)
        assertEquals(8, uvs.size)
    }

    @Test
    fun `uvCoordProviderForShape returns null for non-Prism shapes pre-slice`() {
        assertNull(uvCoordProviderForShape(Cylinder()))
        assertNull(uvCoordProviderForShape(Pyramid()))
        assertNull(uvCoordProviderForShape(Stairs(stepCount = 3)))
        assertNull(uvCoordProviderForShape(Octahedron()))
    }

    // ---------- RenderCommand.faceVertexCount ----------

    @Test
    fun `RenderCommand faceVertexCount defaults to 4 (Prism quad)`() {
        val cmd = stubRenderCommand()
        assertEquals(4, cmd.faceVertexCount)
    }

    @Test
    fun `RenderCommand honors explicit faceVertexCount`() {
        val cmd = stubRenderCommand(faceVertexCount = 3)
        assertEquals(3, cmd.faceVertexCount)
    }

    @Test
    fun `sealed hierarchy when-exhaustive over PerFace variants compiles`() {
        val materials: List<IsometricMaterial.PerFace> = listOf(
            IsometricMaterial.PerFace.Prism.of(emptyMap()),
            IsometricMaterial.PerFace.Cylinder(),
            IsometricMaterial.PerFace.Pyramid(),
            IsometricMaterial.PerFace.Stairs(),
            IsometricMaterial.PerFace.Octahedron(),
        )
        val kinds = materials.map { m ->
            when (m) {
                is IsometricMaterial.PerFace.Prism -> "prism"
                is IsometricMaterial.PerFace.Cylinder -> "cylinder"
                is IsometricMaterial.PerFace.Pyramid -> "pyramid"
                is IsometricMaterial.PerFace.Stairs -> "stairs"
                is IsometricMaterial.PerFace.Octahedron -> "octahedron"
            }
        }
        assertEquals(
            listOf("prism", "cylinder", "pyramid", "stairs", "octahedron"),
            kinds,
        )
    }

    @Test
    fun `PerFace is a sealed subtype of IsometricMaterial`() {
        val mat: IsometricMaterial = IsometricMaterial.PerFace.Cylinder(default = gray)
        assertIs<IsometricMaterial.PerFace>(mat)
        assertTrue(mat.baseColor() == gray.baseColor())
    }

    private fun stubRenderCommand(faceVertexCount: Int = 4): RenderCommand {
        val p0 = Point(0.0, 0.0, 0.0)
        val path = Path(p0, p0, p0, p0)
        return RenderCommand(
            commandId = "test",
            points = DoubleArray(0),
            color = red,
            originalPath = path,
            originalShape = null,
            faceVertexCount = faceVertexCount,
        )
    }
}
