package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.MaterialData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IsometricMaterialTest {

    @Test
    fun `flatColor creates FlatColor material`() {
        val mat = flatColor(IsoColor.BLUE)
        assertEquals(IsoColor.BLUE, mat.color)
        assertIs<IsometricMaterial.FlatColor>(mat)
    }

    @Test
    fun `FlatColor implements MaterialData`() {
        val mat: MaterialData = flatColor(IsoColor.RED)
        assertIs<MaterialData>(mat)
    }

    @Test
    fun `textured with resource creates Textured with defaults`() {
        val mat = textured(42)
        assertIs<IsometricMaterial.Textured>(mat)
        assertEquals(TextureSource.Resource(42), mat.source)
        assertEquals(IsoColor.WHITE, mat.tint)
        assertEquals(UvTransform.IDENTITY, mat.uvTransform)
    }

    @Test
    fun `textured builder applies uvScale`() {
        val mat = textured(42) { uvScale(2f, 3f) }
        assertEquals(2f, mat.uvTransform.scaleU)
        assertEquals(3f, mat.uvTransform.scaleV)
    }

    @Test
    fun `textured builder applies tint`() {
        val mat = textured(42) { tint = IsoColor.RED }
        assertEquals(IsoColor.RED, mat.tint)
    }

    @Test
    fun `textured builder applies uvOffset and uvRotate`() {
        val mat = textured(42) {
            uvOffset(0.5f, 0.25f)
            uvRotate(45f)
        }
        assertEquals(0.5f, mat.uvTransform.offsetU)
        assertEquals(0.25f, mat.uvTransform.offsetV)
        assertEquals(45f, mat.uvTransform.rotationDegrees)
    }

    @Test
    fun `texturedAsset creates Textured with Asset source`() {
        val mat = texturedAsset("textures/grass.png")
        assertIs<TextureSource.Asset>((mat as IsometricMaterial.Textured).source)
        assertEquals("textures/grass.png", (mat.source as TextureSource.Asset).path)
    }

    @Test
    fun `perFace creates PerFace with face map and default`() {
        val green = flatColor(IsoColor.GREEN)
        val gray = flatColor(IsoColor.GRAY)
        val mat = perFace(default = gray) {
            face(0, green)
        }
        assertIs<IsometricMaterial.PerFace>(mat)
        assertEquals(green, mat.faceMap[0])
        assertEquals(gray, mat.default)
        assertEquals(1, mat.faceMap.size)
    }

    @Test
    fun `perFace default is gray FlatColor`() {
        val mat = perFace { face(0, flatColor(IsoColor.BLUE)) }
        assertEquals(IsoColor.GRAY, (mat.default as IsometricMaterial.FlatColor).color)
    }

    @Test
    fun `UvCoord allows values beyond 0-1 range for tiling`() {
        val uv = UvCoord(2f, 3f)
        assertEquals(2f, uv.u)
        assertEquals(3f, uv.v)
    }

    @Test
    fun `UvCoord companion values are correct`() {
        assertEquals(UvCoord(0f, 0f), UvCoord.TOP_LEFT)
        assertEquals(UvCoord(1f, 0f), UvCoord.TOP_RIGHT)
        assertEquals(UvCoord(1f, 1f), UvCoord.BOTTOM_RIGHT)
        assertEquals(UvCoord(0f, 1f), UvCoord.BOTTOM_LEFT)
    }

    @Test
    fun `UvTransform IDENTITY has all defaults`() {
        val id = UvTransform.IDENTITY
        assertEquals(1f, id.scaleU)
        assertEquals(1f, id.scaleV)
        assertEquals(0f, id.offsetU)
        assertEquals(0f, id.offsetV)
        assertEquals(0f, id.rotationDegrees)
    }

    @Test
    fun `TextureSource Asset rejects blank path`() {
        assertFailsWith<IllegalArgumentException> {
            TextureSource.Asset("")
        }
        assertFailsWith<IllegalArgumentException> {
            TextureSource.Asset("   ")
        }
    }

    @Test
    fun `PerFaceBuilder rejects negative face index`() {
        assertFailsWith<IllegalArgumentException> {
            perFace { face(-1, flatColor(IsoColor.RED)) }
        }
    }

    @Test
    fun `sealed interface exhaustive when works`() {
        val mat: IsometricMaterial = flatColor(IsoColor.BLUE)
        val result = when (mat) {
            is IsometricMaterial.FlatColor -> "flat"
            is IsometricMaterial.Textured -> "textured"
            is IsometricMaterial.PerFace -> "perface"
        }
        assertEquals("flat", result)
    }

    @Test
    fun `data class equality works for FlatColor`() {
        assertEquals(flatColor(IsoColor.BLUE), flatColor(IsoColor.BLUE))
        assertTrue(flatColor(IsoColor.BLUE) != flatColor(IsoColor.RED))
    }

    @Test
    fun `data class equality works for Textured`() {
        val a = textured(42) { uvScale(2f, 2f) }
        val b = textured(42) { uvScale(2f, 2f) }
        assertEquals(a, b)
    }
}
