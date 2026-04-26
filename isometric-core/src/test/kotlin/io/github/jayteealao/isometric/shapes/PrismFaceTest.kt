package io.github.jayteealao.isometric.shapes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PrismFaceTest {

    @Test
    fun `path index 0 maps to FRONT`() =
        assertEquals(PrismFace.FRONT, PrismFace.fromPathIndex(0))

    @Test
    fun `path index 1 maps to BACK`() =
        assertEquals(PrismFace.BACK, PrismFace.fromPathIndex(1))

    @Test
    fun `path index 2 maps to LEFT`() =
        assertEquals(PrismFace.LEFT, PrismFace.fromPathIndex(2))

    @Test
    fun `path index 3 maps to RIGHT`() =
        assertEquals(PrismFace.RIGHT, PrismFace.fromPathIndex(3))

    @Test
    fun `path index 4 maps to BOTTOM`() =
        assertEquals(PrismFace.BOTTOM, PrismFace.fromPathIndex(4))

    @Test
    fun `path index 5 maps to TOP`() =
        assertEquals(PrismFace.TOP, PrismFace.fromPathIndex(5))

    @Test
    fun `fromPathIndex covers all entries`() {
        PrismFace.entries.forEachIndexed { i, expected ->
            assertEquals(expected, PrismFace.fromPathIndex(i))
        }
    }

    @Test
    fun `invalid index 6 throws with descriptive message`() {
        val ex = assertFailsWith<IllegalArgumentException> { PrismFace.fromPathIndex(6) }
        assertTrue(ex.message!!.contains("6 faces"))
    }

    @Test
    fun `negative index throws`() {
        assertFailsWith<IllegalArgumentException> { PrismFace.fromPathIndex(-1) }
    }
}
