package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.ExperimentalIsometricApi
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.CylinderFace
import io.github.jayteealao.isometric.shapes.Knot
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
import kotlin.test.assertNotEquals
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
            laterals = mapOf(
                PyramidFace.LATERAL_0 to green,
                PyramidFace.LATERAL_2 to blue,
            ),
            default = gray,
        )
        assertEquals(red, mat.resolve(PyramidFace.BASE))
        assertEquals(green, mat.resolve(PyramidFace.LATERAL_0))
        assertEquals(gray, mat.resolve(PyramidFace.LATERAL_1)) // unassigned
        assertEquals(blue, mat.resolve(PyramidFace.LATERAL_2))
        assertEquals(gray, mat.resolve(PyramidFace.LATERAL_3))
    }

    @Test
    fun `PyramidFace_Lateral rejects out-of-range indices`() {
        // Slot validity is now enforced at the PyramidFace.Lateral constructor, not at
        // the PerFace.Pyramid map level — invalid Int keys are a compile-time impossibility.
        assertFailsWith<IllegalArgumentException> { PyramidFace.Lateral(4) }
        assertFailsWith<IllegalArgumentException> { PyramidFace.Lateral(-1) }
    }

    @Test
    fun `PyramidFace_Lateral accepts inclusive boundary indices 0 and 3`() {
        // Complements the negative-boundary test above: proves the boundary is [0, 3]
        // inclusive, not a stricter 1..2 range. Also pins the companion constants to
        // the same identity as fresh constructions (equality via data class semantics).
        assertEquals(0, PyramidFace.Lateral(0).index)
        assertEquals(3, PyramidFace.Lateral(3).index)
        assertEquals(PyramidFace.LATERAL_0, PyramidFace.Lateral(0))
        assertEquals(PyramidFace.LATERAL_3, PyramidFace.Lateral(3))
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

    // ---------- Structural equality for PerFace.Prism ----------

    @Test
    fun `PerFace_Prism equals hashCode and toString cover all fields`() {
        val a = IsometricMaterial.PerFace.Prism.of(
            faceMap = mapOf(PrismFace.TOP to red, PrismFace.FRONT to green),
            default = gray,
        )
        val b = IsometricMaterial.PerFace.Prism.of(
            faceMap = mapOf(PrismFace.TOP to red, PrismFace.FRONT to green),
            default = gray,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val s = a.toString()
        assertTrue("faceMap=" in s && "default=" in s,
            "toString must cover every field, got: $s")
        assertNotEquals(a, IsometricMaterial.PerFace.Prism.of(
            faceMap = mapOf(PrismFace.TOP to blue, PrismFace.FRONT to green),
            default = gray,
        ))
        assertNotEquals(a, IsometricMaterial.PerFace.Prism.of(
            faceMap = mapOf(PrismFace.TOP to red, PrismFace.FRONT to green),
            default = red,
        ))
    }

    // ---------- Structural equality for non-Prism PerFace subclasses ----------
    // Each subclass ships a manually-written equals/hashCode/toString triple (they are
    // not data classes — the `PerFace` sealed base makes data-class generation impossible).
    // These tests pin the field list covered by each triple so a silently-dropped field
    // is caught before it regresses.

    @Test
    fun `PerFace_Cylinder equals hashCode and toString cover all fields`() {
        val a = IsometricMaterial.PerFace.Cylinder(top = red, bottom = green, side = blue, default = gray)
        val b = IsometricMaterial.PerFace.Cylinder(top = red, bottom = green, side = blue, default = gray)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val s = a.toString()
        assertTrue("top=" in s && "bottom=" in s && "side=" in s && "default=" in s,
            "toString must cover every field, got: $s")
        assertNotEquals(a, IsometricMaterial.PerFace.Cylinder(top = blue, bottom = green, side = blue, default = gray))
        assertNotEquals(a, IsometricMaterial.PerFace.Cylinder(top = red, bottom = red, side = blue, default = gray))
        assertNotEquals(a, IsometricMaterial.PerFace.Cylinder(top = red, bottom = green, side = red, default = gray))
        assertNotEquals(a, IsometricMaterial.PerFace.Cylinder(top = red, bottom = green, side = blue, default = red))
    }

    @Test
    fun `PerFace_Pyramid equals hashCode and toString cover all fields`() {
        val a = IsometricMaterial.PerFace.Pyramid(base = red, laterals = mapOf(PyramidFace.LATERAL_0 to green, PyramidFace.LATERAL_2 to blue), default = gray)
        val b = IsometricMaterial.PerFace.Pyramid(base = red, laterals = mapOf(PyramidFace.LATERAL_0 to green, PyramidFace.LATERAL_2 to blue), default = gray)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val s = a.toString()
        assertTrue("base=" in s && "laterals=" in s && "default=" in s,
            "toString must cover every field, got: $s")
        assertNotEquals(a, IsometricMaterial.PerFace.Pyramid(base = blue, laterals = mapOf(PyramidFace.LATERAL_0 to green, PyramidFace.LATERAL_2 to blue), default = gray))
        assertNotEquals(a, IsometricMaterial.PerFace.Pyramid(base = red, laterals = mapOf(PyramidFace.LATERAL_0 to red), default = gray))
        assertNotEquals(a, IsometricMaterial.PerFace.Pyramid(base = red, laterals = mapOf(PyramidFace.LATERAL_0 to green, PyramidFace.LATERAL_2 to blue), default = red))
    }

    @Test
    fun `PerFace_Stairs equals hashCode and toString cover all fields`() {
        val a = IsometricMaterial.PerFace.Stairs(tread = red, riser = green, side = blue, default = gray)
        val b = IsometricMaterial.PerFace.Stairs(tread = red, riser = green, side = blue, default = gray)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val s = a.toString()
        assertTrue("tread=" in s && "riser=" in s && "side=" in s && "default=" in s,
            "toString must cover every field, got: $s")
        assertNotEquals(a, IsometricMaterial.PerFace.Stairs(tread = blue, riser = green, side = blue, default = gray))
        assertNotEquals(a, IsometricMaterial.PerFace.Stairs(tread = red, riser = red, side = blue, default = gray))
        assertNotEquals(a, IsometricMaterial.PerFace.Stairs(tread = red, riser = green, side = red, default = gray))
        assertNotEquals(a, IsometricMaterial.PerFace.Stairs(tread = red, riser = green, side = blue, default = red))
    }

    @Test
    fun `PerFace_Octahedron equals hashCode and toString cover all fields`() {
        val a = IsometricMaterial.PerFace.Octahedron(
            byIndex = mapOf(OctahedronFace.UPPER_0 to red, OctahedronFace.LOWER_3 to blue),
            default = gray,
        )
        val b = IsometricMaterial.PerFace.Octahedron(
            byIndex = mapOf(OctahedronFace.UPPER_0 to red, OctahedronFace.LOWER_3 to blue),
            default = gray,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val s = a.toString()
        assertTrue("byIndex=" in s && "default=" in s,
            "toString must cover every field, got: $s")
        assertNotEquals(a, IsometricMaterial.PerFace.Octahedron(
            byIndex = mapOf(OctahedronFace.UPPER_0 to green),
            default = gray,
        ))
        assertNotEquals(a, IsometricMaterial.PerFace.Octahedron(
            byIndex = mapOf(OctahedronFace.UPPER_0 to red, OctahedronFace.LOWER_3 to blue),
            default = red,
        ))
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

    @OptIn(ExperimentalIsometricApi::class)
    @Test
    fun `uvCoordProviderForShape returns null for shapes without per-face UV support`() {
        // Shapes not yet wired by their uv-generation-<shape> slice still return null.
        // Octahedron, Pyramid, Cylinder, and Stairs dropped from this list as their
        // shape slices landed.
        assertNull(uvCoordProviderForShape(Knot()))
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
    fun `RenderCommand equality hashCode and toString distinguish by faceVertexCount`() {
        // Round-trip the faceVertexCount field through equals/hashCode/toString so a
        // future refactor that accidentally drops it from any of the three is caught
        // here rather than surfacing as a silent render regression on non-quad shapes.
        val quad = stubRenderCommand(faceVertexCount = 4)
        val tri = stubRenderCommand(faceVertexCount = 3)
        assertNotEquals(quad, tri)
        assertNotEquals(quad.hashCode(), tri.hashCode(),
            "hashCode must distinguish by faceVertexCount")
        assertTrue("faceVertexCount=4" in quad.toString(),
            "toString must include faceVertexCount, got: ${quad.toString()}")
        assertTrue("faceVertexCount=3" in tri.toString())

        val quadCopy = stubRenderCommand(faceVertexCount = 4)
        assertEquals(quad, quadCopy)
        assertEquals(quad.hashCode(), quadCopy.hashCode())
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

    private fun stubRenderCommand(
        faceVertexCount: Int = 4,
        path: Path = sharedStubPath,
    ): RenderCommand =
        RenderCommand(
            commandId = "test",
            points = DoubleArray(0),
            color = red,
            originalPath = path,
            originalShape = null,
            faceVertexCount = faceVertexCount,
        )

    private companion object {
        // Path uses identity equality; sharing one instance across stubRenderCommand
        // calls lets the RenderCommand round-trip assertions round-trip cleanly.
        private val sharedStubPath: Path = Point(0.0, 0.0, 0.0).let { p0 ->
            Path(p0, p0, p0, p0)
        }
    }
}
