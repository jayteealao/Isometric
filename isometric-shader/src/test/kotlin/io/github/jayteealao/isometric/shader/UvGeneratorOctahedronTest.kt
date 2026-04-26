package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.OctahedronFace
import io.github.jayteealao.isometric.shapes.PrismFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class UvGeneratorOctahedronTest {

    private val unitOctahedron = Octahedron(Point.ORIGIN)

    private fun assertUvAt(
        uvs: FloatArray,
        vertex: Int,
        expectedU: Float,
        expectedV: Float,
    ) {
        assertEquals(expectedU, uvs[vertex * 2], absoluteTolerance = 0.0001f)
        assertEquals(expectedV, uvs[vertex * 2 + 1], absoluteTolerance = 0.0001f)
    }

    @Test
    fun `uvCoordProviderForShape returns non-null provider for Octahedron`() {
        val provider = uvCoordProviderForShape(unitOctahedron)
        assertNotNull(provider)
        val uvs = provider.provide(unitOctahedron, faceIndex = 0)
        assertNotNull(uvs)
        assertEquals(6, uvs.size)
    }

    @Test
    fun `UPPER_0 face (index 0) maps to canonical triangle UVs`() {
        val uvs = UvGenerator.forOctahedronFace(unitOctahedron, faceIndex = 0)
        assertEquals(6, uvs.size)
        assertUvAt(uvs, 0, 0.0f, 0.0f)
        assertUvAt(uvs, 1, 1.0f, 0.0f)
        assertUvAt(uvs, 2, 0.5f, 1.0f)
    }

    @Test
    fun `all 8 faces produce identical canonical UVs`() {
        for (i in 0..7) {
            val uvs = UvGenerator.forOctahedronFace(unitOctahedron, faceIndex = i)
            assertEquals(6, uvs.size)
            assertUvAt(uvs, 0, 0.0f, 0.0f)
            assertUvAt(uvs, 1, 1.0f, 0.0f)
            assertUvAt(uvs, 2, 0.5f, 1.0f)
        }
    }

    @Test
    fun `translated octahedron produces same UVs as origin octahedron`() {
        val translated = Octahedron(Point(5.0, 7.0, 3.0))
        for (i in 0..7) {
            val originUvs = UvGenerator.forOctahedronFace(unitOctahedron, i)
            val translatedUvs = UvGenerator.forOctahedronFace(translated, i)
            assertEquals(originUvs.toList(), translatedUvs.toList())
        }
    }

    @Test
    fun `forAllOctahedronFaces returns 8 arrays of 6 floats`() {
        val all = UvGenerator.forAllOctahedronFaces(unitOctahedron)
        assertEquals(8, all.size)
        all.forEach { assertEquals(6, it.size) }
    }

    @Test
    fun `invalid face index -1 throws`() {
        assertFailsWith<IllegalArgumentException> {
            UvGenerator.forOctahedronFace(unitOctahedron, faceIndex = -1)
        }
    }

    @Test
    fun `invalid face index 8 throws`() {
        assertFailsWith<IllegalArgumentException> {
            UvGenerator.forOctahedronFace(unitOctahedron, faceIndex = 8)
        }
    }

    @Test
    fun `PerFace Octahedron resolves byIndex when present (direct resolve)`() {
        val red = IsoColor.RED
        val gray = IsoColor(128, 128, 128, 255)
        val perFace = IsometricMaterial.PerFace.Octahedron(
            byIndex = mapOf(OctahedronFace.UPPER_0 to red),
            default = gray,
        )
        assertEquals(red, perFace.resolve(OctahedronFace.UPPER_0))
        assertEquals(gray, perFace.resolve(OctahedronFace.LOWER_0))
        assertEquals(gray, perFace.resolve(OctahedronFace.LOWER_3))
    }

    @Test
    fun `resolveForFace dispatches Octahedron via OctahedronFace`() {
        val red = IsoColor.RED
        val gray = IsoColor(128, 128, 128, 255)
        val perFace: IsometricMaterial.PerFace = IsometricMaterial.PerFace.Octahedron(
            byIndex = mapOf(OctahedronFace.LOWER_2 to red),
            default = gray,
        )
        // Matching faceType resolves to the mapped material.
        assertEquals(red, perFace.resolveForFace(OctahedronFace.LOWER_2))
        // Unmapped face falls back to default.
        assertEquals(gray, perFace.resolveForFace(OctahedronFace.UPPER_1))
        // Mismatched FaceIdentifier type falls back to default (PrismFace on Octahedron PerFace).
        assertEquals(gray, perFace.resolveForFace(PrismFace.TOP))
        // Null faceType falls back to default.
        assertEquals(gray, perFace.resolveForFace(null))
    }
}
