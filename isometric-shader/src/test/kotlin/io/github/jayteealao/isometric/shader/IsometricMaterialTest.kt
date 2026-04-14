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
    fun `IsoColor implements MaterialData`() {
        val mat: MaterialData = IsoColor.BLUE
        assertIs<MaterialData>(mat)
    }

    @Test
    fun `texturedResource with resource creates Textured with defaults`() {
        val mat = texturedResource(42)
        assertIs<IsometricMaterial.Textured>(mat)
        assertEquals(TextureSource.Resource(42), mat.source)
        assertEquals(IsoColor.WHITE, mat.tint)
        assertEquals(TextureTransform.IDENTITY, mat.transform)
    }

    @Test
    fun `texturedResource named params apply transform`() {
        val mat = texturedResource(42, transform = TextureTransform(scaleU = 2f, scaleV = 3f))
        assertEquals(2f, mat.transform.scaleU)
        assertEquals(3f, mat.transform.scaleV)
    }

    @Test
    fun `texturedResource named params apply tint`() {
        val mat = texturedResource(42, tint = IsoColor.RED)
        assertEquals(IsoColor.RED, mat.tint)
    }

    @Test
    fun `texturedResource named params apply full transform`() {
        val mat = texturedResource(42, transform = TextureTransform(
            offsetU = 0.5f, offsetV = 0.25f, rotationDegrees = 45f
        ))
        assertEquals(0.5f, mat.transform.offsetU)
        assertEquals(0.25f, mat.transform.offsetV)
        assertEquals(45f, mat.transform.rotationDegrees)
    }

    @Test
    fun `texturedAsset creates Textured with Asset source`() {
        val mat = texturedAsset("textures/grass.png")
        assertIs<TextureSource.Asset>((mat as IsometricMaterial.Textured).source)
        assertEquals("textures/grass.png", (mat.source as TextureSource.Asset).path)
    }

    @Test
    fun `perFace creates PerFace with face map and default`() {
        val green = IsoColor.GREEN
        val gray = IsoColor.GRAY
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
    fun `perFace default is mid-gray`() {
        val mat = perFace { top = IsoColor.BLUE }
        assertEquals(UNASSIGNED_FACE_DEFAULT, mat.default)
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
    fun `TextureTransform IDENTITY has all defaults`() {
        val id = TextureTransform.IDENTITY
        assertEquals(1f, id.scaleU)
        assertEquals(1f, id.scaleV)
        assertEquals(0f, id.offsetU)
        assertEquals(0f, id.offsetV)
        assertEquals(0f, id.rotationDegrees)
    }

    @Test
    fun `TextureTransform rejects NaN scaleU`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(scaleU = Float.NaN)
        }
    }

    @Test
    fun `TextureTransform rejects zero scaleV`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(scaleV = 0f)
        }
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
        val inner = IsometricMaterial.PerFace.of(
            faceMap = mapOf(PrismFace.TOP to IsoColor.RED),
            default = IsoColor.GRAY,
        )
        assertFailsWith<IllegalArgumentException> {
            IsometricMaterial.PerFace.of(
                faceMap = mapOf(PrismFace.FRONT to inner),
                default = IsoColor.GRAY,
            )
        }
    }

    @Test
    fun `perFace sides sets all four side faces`() {
        val dirt = IsoColor.RED
        val mat = perFace { sides = dirt }
        assertEquals(dirt, mat.faceMap[PrismFace.FRONT])
        assertEquals(dirt, mat.faceMap[PrismFace.BACK])
        assertEquals(dirt, mat.faceMap[PrismFace.LEFT])
        assertEquals(dirt, mat.faceMap[PrismFace.RIGHT])
        assertEquals(4, mat.faceMap.size)
    }

    @Test
    fun `PerFace resolve returns face material or default`() {
        val grass = IsoColor.GREEN
        val gray = IsoColor.GRAY
        val mat = IsometricMaterial.PerFace.of(
            faceMap = mapOf(PrismFace.TOP to grass),
            default = gray,
        )
        assertEquals(grass, mat.resolve(PrismFace.TOP))
        assertEquals(gray, mat.resolve(PrismFace.FRONT))
        assertEquals(gray, mat.resolve(PrismFace.BOTTOM))
    }

    @Test
    fun `sealed interface exhaustive when works`() {
        val mat: IsometricMaterial = texturedResource(42)
        val result = when (mat) {
            is IsometricMaterial.Textured -> "textured"
            is IsometricMaterial.PerFace -> "perface"
        }
        assertEquals("textured", result)
    }

    @Test
    fun `IsoColor equality works`() {
        assertEquals(IsoColor.BLUE, IsoColor.BLUE)
        assertTrue(IsoColor.BLUE != IsoColor.RED)
    }

    @Test
    fun `data class equality works for Textured`() {
        val a = texturedResource(42, transform = TextureTransform(scaleU = 2f, scaleV = 2f))
        val b = texturedResource(42, transform = TextureTransform(scaleU = 2f, scaleV = 2f))
        assertEquals(a, b)
    }
}
