package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.shapes.PrismFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PerFaceMaterialTest {

    private val grass = texturedResource(1)
    private val dirt = texturedResource(2)
    private val gray = IsoColor.GRAY

    @Test
    fun `faceMap lookup returns face material when present`() {
        val mat = IsometricMaterial.PerFace.Prism.of(
            faceMap = mapOf(PrismFace.TOP to grass, PrismFace.FRONT to dirt),
            default = gray,
        )
        assertEquals(grass, mat.faceMap[PrismFace.TOP] ?: mat.default)
        assertEquals(dirt, mat.faceMap[PrismFace.FRONT] ?: mat.default)
    }

    @Test
    fun `faceMap lookup returns default for faces not in faceMap`() {
        val mat = IsometricMaterial.PerFace.Prism.of(
            faceMap = mapOf(PrismFace.TOP to grass),
            default = gray,
        )
        assertEquals(gray, mat.faceMap[PrismFace.FRONT] ?: mat.default)
        assertEquals(gray, mat.faceMap[PrismFace.BACK] ?: mat.default)
        assertEquals(gray, mat.faceMap[PrismFace.LEFT] ?: mat.default)
        assertEquals(gray, mat.faceMap[PrismFace.RIGHT] ?: mat.default)
        assertEquals(gray, mat.faceMap[PrismFace.BOTTOM] ?: mat.default)
    }

    @Test
    fun `faceMap lookup for all 6 PrismFace values`() {
        val materials = PrismFace.values().associateWith {
            IsoColor(it.ordinal.toDouble() * 40, 0.0, 0.0, 255.0)
        }
        val mat = IsometricMaterial.PerFace.Prism.of(faceMap = materials, default = gray)
        for (face in PrismFace.values()) {
            assertEquals(materials.getValue(face), mat.faceMap[face] ?: mat.default)
        }
    }

    @Test
    fun `empty faceMap returns default for every face`() {
        val mat = prismPerFace {
            default = gray
        }
        for (face in PrismFace.values()) {
            assertEquals(gray, mat.faceMap[face] ?: mat.default)
        }
    }

    @Test
    fun `prismPerFace DSL default is mid-gray`() {
        val mat = prismPerFace {}
        assertIs<IsoColor>(mat.default)
        assertEquals(IsometricMaterial.PerFace.UNASSIGNED_FACE_DEFAULT, mat.default)
    }

    @Test
    fun `prismPerFace sides setter sets all four side faces`() {
        val mat = prismPerFace {
            sides = dirt
            top = grass
        }
        assertEquals(grass, mat.faceMap[PrismFace.TOP] ?: mat.default)
        assertEquals(dirt, mat.faceMap[PrismFace.FRONT] ?: mat.default)
        assertEquals(dirt, mat.faceMap[PrismFace.BACK] ?: mat.default)
        assertEquals(dirt, mat.faceMap[PrismFace.LEFT] ?: mat.default)
        assertEquals(dirt, mat.faceMap[PrismFace.RIGHT] ?: mat.default)
    }

    @Test
    fun `individual face overrides sides`() {
        val stone = IsoColor.BLUE
        val mat = prismPerFace {
            sides = dirt
            front = stone  // overrides sides for FRONT
        }
        assertEquals(stone, mat.faceMap[PrismFace.FRONT] ?: mat.default)
        assertEquals(dirt, mat.faceMap[PrismFace.BACK] ?: mat.default)
        assertEquals(dirt, mat.faceMap[PrismFace.LEFT] ?: mat.default)
        assertEquals(dirt, mat.faceMap[PrismFace.RIGHT] ?: mat.default)
    }

    @Test
    fun `perFace_of_noDefault_usesFallback`() {
        val mat = IsometricMaterial.PerFace.Prism.of(
            faceMap = mapOf(PrismFace.TOP to grass),
        )
        assertEquals(IsometricMaterial.PerFace.UNASSIGNED_FACE_DEFAULT, mat.faceMap[PrismFace.FRONT] ?: mat.default)
    }

    @Test
    fun `faceMap lookup is pure and idempotent`() {
        val mat = prismPerFace {
            top = grass
            default = gray
        }
        val first = mat.faceMap[PrismFace.TOP] ?: mat.default
        val second = mat.faceMap[PrismFace.TOP] ?: mat.default
        assertEquals(first, second)
    }

    @Test
    fun `perFace_of_perFaceAsDefault_throws`() {
        val inner = IsometricMaterial.PerFace.Prism.of(
            faceMap = mapOf(PrismFace.TOP to grass),
            default = gray,
        )
        assertFailsWith<IllegalArgumentException> {
            IsometricMaterial.PerFace.Prism.of(
                faceMap = mapOf(PrismFace.FRONT to dirt),
                default = inner,
            )
        }
    }
}
