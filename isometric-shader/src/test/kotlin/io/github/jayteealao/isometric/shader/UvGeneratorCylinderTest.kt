package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.CylinderFace
import io.github.jayteealao.isometric.shapes.PyramidFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UvGeneratorCylinderTest {

    private val cyl6 = Cylinder(Point.ORIGIN, radius = 1.0, height = 2.0, vertices = 6)
    private val cyl20 = Cylinder(Point.ORIGIN, radius = 1.0, height = 2.0, vertices = 20)

    private fun assertUvAt(
        uvs: FloatArray,
        vertex: Int,
        expectedU: Float,
        expectedV: Float,
    ) {
        assertEquals(expectedU, uvs[vertex * 2], absoluteTolerance = 1e-5f)
        assertEquals(expectedV, uvs[vertex * 2 + 1], absoluteTolerance = 1e-5f)
    }

    @Test
    fun `uvCoordProviderForShape returns non-null provider for Cylinder`() {
        val provider = uvCoordProviderForShape(cyl6)
        assertNotNull(provider)
        val bottomUvs = provider.provide(cyl6, faceIndex = 0)
        val topUvs = provider.provide(cyl6, faceIndex = 1)
        val sideUvs = provider.provide(cyl6, faceIndex = 2)
        assertNotNull(bottomUvs)
        assertNotNull(topUvs)
        assertNotNull(sideUvs)
        assertEquals(12, bottomUvs.size)
        assertEquals(12, topUvs.size)
        assertEquals(8, sideUvs.size)
    }

    @Test
    fun `side face k=0 has u=0 at left edge`() {
        val uvs = UvGenerator.forCylinderFace(cyl6, faceIndex = 2)
        assertEquals(8, uvs.size)
        assertUvAt(uvs, 0, 0.0f, 0.0f)
        assertUvAt(uvs, 1, 0.0f, 1.0f)
        val inv = 1f / 6f
        assertUvAt(uvs, 2, inv, 1.0f)
        assertUvAt(uvs, 3, inv, 0.0f)
    }

    @Test
    fun `side face k=N-1 has u=1 at right edge (seam)`() {
        val uvs = UvGenerator.forCylinderFace(cyl6, faceIndex = 7)
        val expectedU0 = 5f / 6f
        assertUvAt(uvs, 0, expectedU0, 0.0f)
        assertUvAt(uvs, 1, expectedU0, 1.0f)
        assertUvAt(uvs, 2, 1.0f, 1.0f)
        assertUvAt(uvs, 3, 1.0f, 0.0f)
    }

    @Test
    fun `all N side faces have 8-float shape`() {
        for (k in 0 until 6) {
            val uvs = UvGenerator.forCylinderFace(cyl6, faceIndex = 2 + k)
            assertEquals(8, uvs.size, "side $k should have 8 floats")
        }
    }

    @Test
    fun `top cap returns N disk-projected pairs`() {
        val uvs = UvGenerator.forCylinderFace(cyl6, faceIndex = 1)
        assertEquals(12, uvs.size)
        // theta=0 → (1.0, 0.5)
        assertUvAt(uvs, 0, 1.0f, 0.5f)
    }

    @Test
    fun `bottom cap UV is reverse-ordered relative to top`() {
        val top = UvGenerator.forCylinderFace(cyl6, faceIndex = 1)
        val bottom = UvGenerator.forCylinderFace(cyl6, faceIndex = 0)
        assertEquals(top.size, bottom.size)
        // bottom[slot=0] corresponds to top's last vertex (reversed winding)
        val lastIndex = 5
        assertEquals(top[lastIndex * 2], bottom[0], 1e-5f)
        assertEquals(top[lastIndex * 2 + 1], bottom[1], 1e-5f)
    }

    @Test
    fun `cap UVs are symmetric around center point (0_5, 0_5)`() {
        val uvs = UvGenerator.forCylinderFace(cyl6, faceIndex = 1)
        var sumU = 0.0f
        var sumV = 0.0f
        for (i in 0 until 6) {
            sumU += uvs[i * 2]
            sumV += uvs[i * 2 + 1]
        }
        assertEquals(6 * 0.5f, sumU, 1e-4f)
        assertEquals(6 * 0.5f, sumV, 1e-4f)
    }

    @Test
    fun `forAllCylinderFaces returns N+2 arrays with correct sizes`() {
        val all = UvGenerator.forAllCylinderFaces(cyl6)
        assertEquals(8, all.size)
        assertEquals(12, all[0].size)
        assertEquals(12, all[1].size)
        for (k in 0 until 6) assertEquals(8, all[2 + k].size)
    }

    @Test
    fun `invalid face index -1 throws`() {
        assertFailsWith<IllegalArgumentException> {
            UvGenerator.forCylinderFace(cyl6, faceIndex = -1)
        }
    }

    @Test
    fun `invalid face index N+2 throws`() {
        assertFailsWith<IllegalArgumentException> {
            UvGenerator.forCylinderFace(cyl6, faceIndex = 8)
        }
    }

    @Test
    fun `cap UVs are identity-cached per Cylinder instance`() {
        val first = UvGenerator.forCylinderFace(cyl20, faceIndex = 0)
        val second = UvGenerator.forCylinderFace(cyl20, faceIndex = 0)
        assertSame(first, second, "same Cylinder and face → same cached FloatArray")

        val topFirst = UvGenerator.forCylinderFace(cyl20, faceIndex = 1)
        val topSecond = UvGenerator.forCylinderFace(cyl20, faceIndex = 1)
        assertSame(topFirst, topSecond, "top cap also identity-cached")
    }

    @Test
    fun `cap UV cache invalidates on different Cylinder instance`() {
        val fromCyl6 = UvGenerator.forCylinderFace(cyl6, faceIndex = 0)
        val fromCyl20 = UvGenerator.forCylinderFace(cyl20, faceIndex = 0)
        assertNotSame(fromCyl6, fromCyl20)
        assertEquals(12, fromCyl6.size)
        assertEquals(40, fromCyl20.size)
    }

    @Test
    fun `N=4 stress case produces no seam smear`() {
        val cyl4 = Cylinder(Point.ORIGIN, radius = 1.0, height = 1.0, vertices = 4)
        val uvs = UvGenerator.forCylinderFace(cyl4, faceIndex = 5) // k = 3
        assertEquals(0.75f, uvs[0], 1e-5f)
        assertEquals(1.0f, uvs[4], 1e-5f)
        assertEquals(1.0f, uvs[6], 1e-5f)
    }

    @Test
    fun `PerFace Cylinder resolves top bottom side via direct resolve`() {
        val red = IsoColor.RED
        val green = IsoColor.GREEN
        val blue = IsoColor.BLUE
        val gray = IsoColor(128, 128, 128, 255)
        val perFace = IsometricMaterial.PerFace.Cylinder(
            top = red,
            bottom = green,
            side = blue,
            default = gray,
        )
        assertEquals(red, perFace.resolve(CylinderFace.TOP))
        assertEquals(green, perFace.resolve(CylinderFace.BOTTOM))
        assertEquals(blue, perFace.resolve(CylinderFace.SIDE))
    }

    @Test
    fun `PerFace Cylinder falls back to default for null slots`() {
        val gray = IsoColor(128, 128, 128, 255)
        val perFace = IsometricMaterial.PerFace.Cylinder(
            top = IsoColor.RED,
            default = gray,
        )
        assertEquals(IsoColor.RED, perFace.resolve(CylinderFace.TOP))
        assertEquals(gray, perFace.resolve(CylinderFace.BOTTOM))
        assertEquals(gray, perFace.resolve(CylinderFace.SIDE))
    }

    @Test
    fun `resolveForFace dispatches Cylinder via CylinderFace`() {
        val red = IsoColor.RED
        val green = IsoColor.GREEN
        val blue = IsoColor.BLUE
        val gray = IsoColor(128, 128, 128, 255)
        val perFace: IsometricMaterial.PerFace = IsometricMaterial.PerFace.Cylinder(
            top = red,
            bottom = green,
            side = blue,
            default = gray,
        )
        assertEquals(red, perFace.resolveForFace(CylinderFace.TOP))
        assertEquals(green, perFace.resolveForFace(CylinderFace.BOTTOM))
        assertEquals(blue, perFace.resolveForFace(CylinderFace.SIDE))
        // Mismatched FaceIdentifier type falls back to default.
        assertEquals(gray, perFace.resolveForFace(PyramidFace.BASE))
        // Null faceType falls back to default.
        assertEquals(gray, perFace.resolveForFace(null))
    }

    @Test
    fun `cylinderPerFace DSL builds PerFace Cylinder correctly`() {
        val red = IsoColor.RED
        val green = IsoColor.GREEN
        val blue = IsoColor.BLUE
        val gray = IsoColor(128, 128, 128, 255)
        val mat: IsometricMaterial.PerFace.Cylinder = cylinderPerFace {
            top = red
            bottom = green
            side = blue
            default = gray
        }
        assertEquals(red, mat.top)
        assertEquals(green, mat.bottom)
        assertEquals(blue, mat.side)
        assertEquals(gray, mat.default)
        assertEquals(red, mat.resolve(CylinderFace.TOP))
        assertEquals(blue, mat.resolve(CylinderFace.SIDE))
    }

    @Test
    fun `cylinderPerFace DSL leaves unassigned slots null`() {
        val mat = cylinderPerFace {
            top = IsoColor.RED
        }
        assertTrue(mat.top != null)
        assertEquals(null, mat.bottom)
        assertEquals(null, mat.side)
    }
}
