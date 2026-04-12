package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.shapes.PrismFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PerFaceMaterialTest {

    private val grass = textured(1)
    private val dirt = textured(2)
    private val gray = flatColor(IsoColor.GRAY)

    @Test
    fun `resolve returns face material when present in faceMap`() {
        val mat = IsometricMaterial.PerFace(
            faceMap = mapOf(PrismFace.TOP to grass, PrismFace.FRONT to dirt),
            default = gray,
        )
        assertEquals(grass, mat.resolve(PrismFace.TOP))
        assertEquals(dirt, mat.resolve(PrismFace.FRONT))
    }

    @Test
    fun `resolve returns default for faces not in faceMap`() {
        val mat = IsometricMaterial.PerFace(
            faceMap = mapOf(PrismFace.TOP to grass),
            default = gray,
        )
        assertEquals(gray, mat.resolve(PrismFace.FRONT))
        assertEquals(gray, mat.resolve(PrismFace.BACK))
        assertEquals(gray, mat.resolve(PrismFace.LEFT))
        assertEquals(gray, mat.resolve(PrismFace.RIGHT))
        assertEquals(gray, mat.resolve(PrismFace.BOTTOM))
    }

    @Test
    fun `resolve for all 6 PrismFace values`() {
        val materials = PrismFace.values().associateWith {
            flatColor(IsoColor(it.ordinal.toDouble() * 40, 0.0, 0.0, 255.0))
        }
        val mat = IsometricMaterial.PerFace(faceMap = materials, default = gray)
        for (face in PrismFace.values()) {
            assertEquals(materials.getValue(face), mat.resolve(face))
        }
    }

    @Test
    fun `empty faceMap resolves every face to default`() {
        val mat = perFace {
            default = gray
        }
        for (face in PrismFace.values()) {
            assertEquals(gray, mat.resolve(face))
        }
    }

    @Test
    fun `perFace DSL default is transparent`() {
        val mat = perFace { }
        assertIs<IsometricMaterial.FlatColor>(mat.default)
        assertEquals(IsoColor(0, 0, 0, 0), (mat.default as IsometricMaterial.FlatColor).color)
    }

    @Test
    fun `perFace sides setter sets all four side faces`() {
        val mat = perFace {
            sides = dirt
            top = grass
        }
        assertEquals(grass, mat.resolve(PrismFace.TOP))
        assertEquals(dirt, mat.resolve(PrismFace.FRONT))
        assertEquals(dirt, mat.resolve(PrismFace.BACK))
        assertEquals(dirt, mat.resolve(PrismFace.LEFT))
        assertEquals(dirt, mat.resolve(PrismFace.RIGHT))
    }

    @Test
    fun `individual face overrides sides`() {
        val stone = flatColor(IsoColor.BLUE)
        val mat = perFace {
            sides = dirt
            front = stone  // overrides sides for FRONT
        }
        assertEquals(stone, mat.resolve(PrismFace.FRONT))
        assertEquals(dirt, mat.resolve(PrismFace.BACK))
        assertEquals(dirt, mat.resolve(PrismFace.LEFT))
        assertEquals(dirt, mat.resolve(PrismFace.RIGHT))
    }

    @Test
    fun `resolve is pure and idempotent`() {
        val mat = perFace {
            top = grass
            default = gray
        }
        val first = mat.resolve(PrismFace.TOP)
        val second = mat.resolve(PrismFace.TOP)
        assertEquals(first, second)
    }
}
