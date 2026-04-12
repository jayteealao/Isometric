package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.PrismFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UvGeneratorTest {

    private val origin = Point(0.0, 0.0, 0.0)
    private val unitPrism = Prism(origin, width = 1.0, depth = 1.0, height = 1.0)

    @Test
    fun `FRONT face produces canonical UVs`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 0)
        assertUvAt(uvs, 0, 0f, 0f)
        assertUvAt(uvs, 1, 1f, 0f)
        assertUvAt(uvs, 2, 1f, 1f)
        assertUvAt(uvs, 3, 0f, 1f)
    }

    @Test
    fun `TOP face produces canonical UVs`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 5)
        assertUvAt(uvs, 0, 0f, 0f)
        assertUvAt(uvs, 1, 1f, 0f)
        assertUvAt(uvs, 2, 1f, 1f)
        assertUvAt(uvs, 3, 0f, 1f)
    }

    @Test
    fun `RIGHT face UVs`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 3)
        // RIGHT vertices: (1,1,0), (1,1,1), (1,0,1), (1,0,0)
        // u = 1-(y-oy)/d, v = (z-oz)/h
        assertUvAt(uvs, 0, 0f, 0f)  // (1,1,0): u=0, v=0
        assertUvAt(uvs, 1, 0f, 1f)  // (1,1,1): u=0, v=1
        assertUvAt(uvs, 2, 1f, 1f)  // (1,0,1): u=1, v=1
        assertUvAt(uvs, 3, 1f, 0f)  // (1,0,0): u=1, v=0
    }

    @Test
    fun `LEFT face UVs`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 2)
        assertUvAt(uvs, 0, 0f, 0f)
        assertUvAt(uvs, 1, 0f, 1f)
        assertUvAt(uvs, 2, 1f, 1f)
        assertUvAt(uvs, 3, 1f, 0f)
    }

    @Test
    fun `BACK face UVs`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 1)
        // BACK: u = 1-(x-ox)/w, v = 1-(z-oz)/h
        // Vertices: (0,1,1), (1,1,1), (1,1,0), (0,1,0)
        assertUvAt(uvs, 0, 1f, 0f)
        assertUvAt(uvs, 1, 0f, 0f)
        assertUvAt(uvs, 2, 0f, 1f)
        assertUvAt(uvs, 3, 1f, 1f)
    }

    @Test
    fun `BOTTOM face UVs`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 4)
        // BOTTOM = face3.reverse(): (0,1,0), (1,1,0), (1,0,0), (0,0,0)
        // u = 1-(x-ox)/w, v = (y-oy)/d
        assertUvAt(uvs, 0, 1f, 1f)  // (0,1,0): u=1, v=1
        assertUvAt(uvs, 1, 0f, 1f)  // (1,1,0): u=0, v=1
        assertUvAt(uvs, 2, 0f, 0f)  // (1,0,0): u=0, v=0
        assertUvAt(uvs, 3, 1f, 0f)  // (0,0,0): u=1, v=0
    }

    @Test
    fun `non-unit prism normalises correctly`() {
        val p = Prism(origin, width = 3.0, depth = 2.0, height = 4.0)
        val uvs = UvGenerator.forPrismFace(p, faceIndex = 0) // FRONT
        assertUvAt(uvs, 0, 0f, 0f)
        assertUvAt(uvs, 1, 1f, 0f)
        assertUvAt(uvs, 2, 1f, 1f)
        assertUvAt(uvs, 3, 0f, 1f)
    }

    @Test
    fun `translated prism produces same UVs as origin prism`() {
        val translated = Prism(Point(5.0, 7.0, 3.0), width = 1.0, depth = 1.0, height = 1.0)
        val uvs = UvGenerator.forPrismFace(translated, faceIndex = 0)
        assertUvAt(uvs, 0, 0f, 0f)
        assertUvAt(uvs, 1, 1f, 0f)
        assertUvAt(uvs, 2, 1f, 1f)
        assertUvAt(uvs, 3, 0f, 1f)
    }

    @Test
    fun `forAllPrismFaces returns 6 arrays of 8 floats`() {
        val all = UvGenerator.forAllPrismFaces(unitPrism)
        assertEquals(6, all.size)
        all.forEach { assertEquals(8, it.size) }
    }

    @Test
    fun `invalid face index throws`() {
        assertFailsWith<IllegalArgumentException> {
            UvGenerator.forPrismFace(unitPrism, faceIndex = 6)
        }
    }

    private fun assertUvAt(uvs: FloatArray, vertex: Int, expectedU: Float, expectedV: Float) {
        assertEquals(expectedU, uvs[vertex * 2], absoluteTolerance = 0.0001f,
            message = "vertex $vertex u: expected $expectedU got ${uvs[vertex * 2]}")
        assertEquals(expectedV, uvs[vertex * 2 + 1], absoluteTolerance = 0.0001f,
            message = "vertex $vertex v: expected $expectedV got ${uvs[vertex * 2 + 1]}")
    }
}
