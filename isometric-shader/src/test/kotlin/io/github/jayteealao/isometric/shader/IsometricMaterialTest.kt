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
        assertEquals(IsometricMaterial.PerFace.UNASSIGNED_FACE_DEFAULT, mat.default)
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

    // --- TextureTransform.init validation — full coverage (H-08) ---

    @Test
    fun `textureTransform_infinityScaleU_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(scaleU = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `textureTransform_negativeInfinityScaleU_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(scaleU = Float.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun `textureTransform_zeroScaleU_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(scaleU = 0f)
        }
    }

    @Test
    fun `textureTransform_negativeZeroScaleU_throws`() {
        // IEEE 754: -0f == 0f, so -0f must also be rejected by the non-zero guard
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(scaleU = -0f)
        }
    }

    @Test
    fun `textureTransform_nanScaleV_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(scaleV = Float.NaN)
        }
    }

    @Test
    fun `textureTransform_infinityScaleV_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(scaleV = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `textureTransform_negativeInfinityScaleV_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(scaleV = Float.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun `textureTransform_negativeZeroScaleV_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(scaleV = -0f)
        }
    }

    @Test
    fun `textureTransform_nanOffsetU_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(offsetU = Float.NaN)
        }
    }

    @Test
    fun `textureTransform_infinityOffsetU_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(offsetU = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `textureTransform_negativeInfinityOffsetU_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(offsetU = Float.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun `textureTransform_nanOffsetV_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(offsetV = Float.NaN)
        }
    }

    @Test
    fun `textureTransform_infinityOffsetV_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(offsetV = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `textureTransform_negativeInfinityOffsetV_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(offsetV = Float.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun `textureTransform_nanRotationDegrees_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(rotationDegrees = Float.NaN)
        }
    }

    @Test
    fun `textureTransform_infinityRotationDegrees_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(rotationDegrees = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `textureTransform_negativeInfinityRotationDegrees_throws`() {
        assertFailsWith<IllegalArgumentException> {
            TextureTransform(rotationDegrees = Float.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun `textureTransform_validParams_doesNotThrow`() {
        // Positive confirmation: a fully-specified finite, non-zero transform constructs successfully
        val t = TextureTransform(
            scaleU = 2f,
            scaleV = -3f,
            offsetU = 0.5f,
            offsetV = -0.25f,
            rotationDegrees = 90f,
        )
        assertEquals(2f, t.scaleU)
        assertEquals(-3f, t.scaleV)
        assertEquals(0.5f, t.offsetU)
        assertEquals(-0.25f, t.offsetV)
        assertEquals(90f, t.rotationDegrees)
    }

    // --- TextureTransform companion factory methods ---

    @Test
    fun `textureTransform_tiling_setsScaleUV_andZeroOffsetAndRotation`() {
        val t = TextureTransform.tiling(2f, 3f)
        assertEquals(2f, t.scaleU)
        assertEquals(3f, t.scaleV)
        assertEquals(0f, t.offsetU)
        assertEquals(0f, t.offsetV)
        assertEquals(0f, t.rotationDegrees)
    }

    @Test
    fun `textureTransform_rotated_setsRotation_andIdentityScaleAndOffset`() {
        val t = TextureTransform.rotated(45f)
        assertEquals(1f, t.scaleU)
        assertEquals(1f, t.scaleV)
        assertEquals(0f, t.offsetU)
        assertEquals(0f, t.offsetV)
        assertEquals(45f, t.rotationDegrees)
    }

    @Test
    fun `textureTransform_offset_setsOffsetUV_andIdentityScaleAndZeroRotation`() {
        val t = TextureTransform.offset(0.1f, 0.2f)
        assertEquals(1f, t.scaleU)
        assertEquals(1f, t.scaleV)
        assertEquals(0.1f, t.offsetU)
        assertEquals(0.2f, t.offsetV)
        assertEquals(0f, t.rotationDegrees)
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
    fun `PerFace faceMap lookup returns face material or default`() {
        val grass = IsoColor.GREEN
        val gray = IsoColor.GRAY
        val mat = IsometricMaterial.PerFace.of(
            faceMap = mapOf(PrismFace.TOP to grass),
            default = gray,
        )
        assertEquals(grass, mat.faceMap[PrismFace.TOP] ?: mat.default)
        assertEquals(gray, mat.faceMap[PrismFace.FRONT] ?: mat.default)
        assertEquals(gray, mat.faceMap[PrismFace.BOTTOM] ?: mat.default)
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

    // --- baseColor() branch coverage (H-09) ---

    @Test
    fun `baseColor_isoColor_returnsSelf`() {
        val color = IsoColor(255, 0, 0)
        assertEquals(color, color.baseColor())
    }

    @Test
    fun `baseColor_textured_returnsTint`() {
        val tint = IsoColor(0, 128, 255)
        val mat = texturedResource(42, tint = tint)
        assertEquals(tint, mat.baseColor())
    }

    @Test
    fun `baseColor_perFace_returnsWhite`() {
        val mat = IsometricMaterial.PerFace.of(
            faceMap = mapOf(PrismFace.TOP to IsoColor.RED),
            default = IsoColor.GRAY,
        )
        assertEquals(IsoColor.WHITE, mat.baseColor())
    }

    @Test
    fun `baseColor_unknown_returnsWhite`() {
        // An arbitrary MaterialData implementor that does not override baseColor()
        // exercises the interface default branch (returns IsoColor.WHITE).
        val unknown = object : io.github.jayteealao.isometric.MaterialData {}
        assertEquals(IsoColor.WHITE, unknown.baseColor())
    }
}
