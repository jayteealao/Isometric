package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.MaterialData
import io.github.jayteealao.isometric.shapes.PrismFace
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
    fun `textured named params apply uvTransform`() {
        val mat = textured(42, uvTransform = UvTransform(scaleU = 2f, scaleV = 3f))
        assertEquals(2f, mat.uvTransform.scaleU)
        assertEquals(3f, mat.uvTransform.scaleV)
    }

    @Test
    fun `textured named params apply tint`() {
        val mat = textured(42, tint = IsoColor.RED)
        assertEquals(IsoColor.RED, mat.tint)
    }

    @Test
    fun `textured named params apply full uvTransform`() {
        val mat = textured(42, uvTransform = UvTransform(
            offsetU = 0.5f, offsetV = 0.25f, rotationDegrees = 45f
        ))
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
        val mat = perFace {
            top = green
            default = gray
        }
        assertIs<IsometricMaterial.PerFace>(mat)
        assertEquals(green, mat.faceMap[PrismFace.TOP])
        assertEquals(gray, mat.default)
        assertEquals(1, mat.faceMap.size)
    }

    @Test
    fun `perFace default is transparent FlatColor`() {
        val mat = perFace { top = flatColor(IsoColor.BLUE) }
        assertEquals(IsoColor(0, 0, 0, 0), (mat.default as IsometricMaterial.FlatColor).color)
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
    fun `TextureSource Asset rejects path traversal`() {
        assertFailsWith<IllegalArgumentException> {
            TextureSource.Asset("../../databases/app.db")
        }
        assertFailsWith<IllegalArgumentException> {
            TextureSource.Asset("textures/../../../etc/passwd")
        }
    }

    @Test
    fun `TextureSource Asset rejects absolute path`() {
        assertFailsWith<IllegalArgumentException> {
            TextureSource.Asset("/etc/passwd")
        }
    }

    @Test
    fun `TextureSource Asset accepts valid relative path`() {
        val asset = TextureSource.Asset("textures/grass.png")
        assertEquals("textures/grass.png", asset.path)
    }

    @Test
    fun `TextureSource Resource rejects zero resId`() {
        assertFailsWith<IllegalArgumentException> {
            TextureSource.Resource(0)
        }
    }

    @Test
    fun `PerFace rejects nested PerFace in faceMap`() {
        val inner = IsometricMaterial.PerFace(
            faceMap = mapOf(PrismFace.TOP to flatColor(IsoColor.RED)),
            default = flatColor(IsoColor.GRAY)
        )
        assertFailsWith<IllegalArgumentException> {
            IsometricMaterial.PerFace(
                faceMap = mapOf(PrismFace.FRONT to inner),
                default = flatColor(IsoColor.GRAY)
            )
        }
    }

    @Test
    fun `perFace sides sets all four side faces`() {
        val dirt = flatColor(IsoColor.RED)
        val mat = perFace { sides = dirt }
        assertEquals(dirt, mat.faceMap[PrismFace.FRONT])
        assertEquals(dirt, mat.faceMap[PrismFace.BACK])
        assertEquals(dirt, mat.faceMap[PrismFace.LEFT])
        assertEquals(dirt, mat.faceMap[PrismFace.RIGHT])
        assertEquals(4, mat.faceMap.size)
    }

    @Test
    fun `PerFace resolve returns face material or default`() {
        val grass = flatColor(IsoColor.GREEN)
        val gray = flatColor(IsoColor.GRAY)
        val mat = IsometricMaterial.PerFace(
            faceMap = mapOf(PrismFace.TOP to grass),
            default = gray,
        )
        assertEquals(grass, mat.resolve(PrismFace.TOP))
        assertEquals(gray, mat.resolve(PrismFace.FRONT))
        assertEquals(gray, mat.resolve(PrismFace.BOTTOM))
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
        val a = textured(42, uvTransform = UvTransform(scaleU = 2f, scaleV = 2f))
        val b = textured(42, uvTransform = UvTransform(scaleU = 2f, scaleV = 2f))
        assertEquals(a, b)
    }
}
