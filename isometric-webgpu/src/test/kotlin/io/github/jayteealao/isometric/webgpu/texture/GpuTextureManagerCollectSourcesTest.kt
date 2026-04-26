package io.github.jayteealao.isometric.webgpu.texture

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.TextureSource
import io.github.jayteealao.isometric.shapes.OctahedronFace
import io.github.jayteealao.isometric.shapes.PyramidFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM unit tests for [collectTextureSourcesFromMaterial].
 *
 * Covers three deferred plan items:
 *  - **D-13**: [IsometricMaterial.PerFace.Cylinder] arm — top/bottom/side all contribute their
 *    [TextureSource] to the output set; slots left null do not add entries.
 *  - **H-11**: [IsometricMaterial.PerFace.Pyramid] arm — base and each lateral contribute;
 *    null slots are skipped; a textured [default] is also collected.
 *  - **H-06**: Non-Prism coverage for [IsometricMaterial.PerFace.Stairs] (and
 *    [IsometricMaterial.PerFace.Octahedron]) — same contract verified for remaining
 *    non-Cylinder/Pyramid PerFace variants.
 *
 * Pure JVM: [collectTextureSourcesFromMaterial] is a package-internal function that operates
 * only on Kotlin data types — no Android runtime or Robolectric needed.
 */
class GpuTextureManagerCollectSourcesTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun textured(id: Int) =
        IsometricMaterial.Textured(source = TextureSource.Resource(id))

    private fun collect(material: io.github.jayteealao.isometric.MaterialData?): Set<TextureSource> {
        val out = mutableSetOf<TextureSource>()
        collectTextureSourcesFromMaterial(material, out)
        return out
    }

    // ── D-13: Cylinder PerFace arm ────────────────────────────────────────────

    /**
     * [D-13] All three slots (top, bottom, side) are textured — all three sources collected.
     */
    @Test
    fun `cylinder_allSlots_textured_allCollected`() {
        val top    = TextureSource.Resource(1)
        val bottom = TextureSource.Resource(2)
        val side   = TextureSource.Resource(3)
        val material = IsometricMaterial.PerFace.Cylinder(
            top    = IsometricMaterial.Textured(top),
            bottom = IsometricMaterial.Textured(bottom),
            side   = IsometricMaterial.Textured(side),
        )

        val result = collect(material)

        assertEquals(setOf(top, bottom, side), result,
            "Cylinder: all three textured slots must be collected")
    }

    /**
     * [D-13] Only the side slot is textured; top and bottom are null (fall back to default).
     * Only the side source should appear in the output.
     */
    @Test
    fun `cylinder_onlySideTextured_onlySideCollected`() {
        val sideSource = TextureSource.Resource(10)
        val material = IsometricMaterial.PerFace.Cylinder(
            top    = null,
            bottom = null,
            side   = IsometricMaterial.Textured(sideSource),
        )

        val result = collect(material)

        assertEquals(setOf(sideSource), result,
            "Cylinder: only the textured side slot must be collected; null slots are skipped")
    }

    /**
     * [D-13] All slots null — only the default (if textured) contributes.
     * With a non-textured default (IsoColor) the output should be empty.
     */
    @Test
    fun `cylinder_allSlotsNull_noTexturedDefault_emptyOutput`() {
        val material = IsometricMaterial.PerFace.Cylinder(
            top    = null,
            bottom = null,
            side   = null,
            default = IsoColor(128, 128, 128),
        )

        val result = collect(material)

        assertTrue(result.isEmpty(),
            "Cylinder: no textured slots and non-textured default must produce an empty set")
    }

    /**
     * [D-13] Default slot is Textured — it must also be collected alongside the per-slot sources.
     */
    @Test
    fun `cylinder_texturedDefault_isAlsoCollected`() {
        val sideSource    = TextureSource.Resource(20)
        val defaultSource = TextureSource.Resource(99)
        val material = IsometricMaterial.PerFace.Cylinder(
            side    = IsometricMaterial.Textured(sideSource),
            default = IsometricMaterial.Textured(defaultSource),
        )

        val result = collect(material)

        assertTrue(sideSource in result,   "side source must be collected")
        assertTrue(defaultSource in result, "textured default source must also be collected")
        assertEquals(2, result.size)
    }

    // ── H-11: Pyramid PerFace arm ─────────────────────────────────────────────

    /**
     * [H-11] Base and all four laterals are textured — five distinct sources collected.
     */
    @Test
    fun `pyramid_allSlots_textured_allCollected`() {
        val baseSource = TextureSource.Resource(100)
        val lat0 = TextureSource.Resource(101)
        val lat1 = TextureSource.Resource(102)
        val lat2 = TextureSource.Resource(103)
        val lat3 = TextureSource.Resource(104)
        val material = IsometricMaterial.PerFace.Pyramid(
            base = IsometricMaterial.Textured(baseSource),
            laterals = mapOf(
                PyramidFace.LATERAL_0 to IsometricMaterial.Textured(lat0),
                PyramidFace.LATERAL_1 to IsometricMaterial.Textured(lat1),
                PyramidFace.LATERAL_2 to IsometricMaterial.Textured(lat2),
                PyramidFace.LATERAL_3 to IsometricMaterial.Textured(lat3),
            ),
        )

        val result = collect(material)

        assertEquals(setOf(baseSource, lat0, lat1, lat2, lat3), result,
            "Pyramid: base + all 4 laterals must be collected when textured")
    }

    /**
     * [H-11] Only two laterals are textured; base is null; other laterals use IsoColor.
     */
    @Test
    fun `pyramid_partialLaterals_onlyTexturedCollected`() {
        val lat1Source = TextureSource.Resource(201)
        val lat3Source = TextureSource.Resource(203)
        val material = IsometricMaterial.PerFace.Pyramid(
            base = null,
            laterals = mapOf(
                PyramidFace.LATERAL_1 to IsometricMaterial.Textured(lat1Source),
                PyramidFace.LATERAL_2 to IsoColor(200, 0, 0),
                PyramidFace.LATERAL_3 to IsometricMaterial.Textured(lat3Source),
            ),
        )

        val result = collect(material)

        assertEquals(setOf(lat1Source, lat3Source), result,
            "Pyramid: only textured laterals collected; null base and IsoColor laterals skipped")
    }

    /**
     * [H-11] No textured slots at all — empty output.
     */
    @Test
    fun `pyramid_noTexturedSlots_emptyOutput`() {
        val material = IsometricMaterial.PerFace.Pyramid(
            base = IsoColor(0, 128, 0),
        )

        val result = collect(material)

        assertTrue(result.isEmpty(),
            "Pyramid: no textured slots must produce an empty set")
    }

    // ── H-06: Stairs and Octahedron non-Prism arms ───────────────────────────

    /**
     * [H-06] Stairs — all three logical groups textured.
     */
    @Test
    fun `stairs_allSlots_textured_allCollected`() {
        val tread = TextureSource.Resource(300)
        val riser = TextureSource.Resource(301)
        val side  = TextureSource.Resource(302)
        val material = IsometricMaterial.PerFace.Stairs(
            tread = IsometricMaterial.Textured(tread),
            riser = IsometricMaterial.Textured(riser),
            side  = IsometricMaterial.Textured(side),
        )

        val result = collect(material)

        assertEquals(setOf(tread, riser, side), result,
            "Stairs: all three textured slots must be collected")
    }

    /**
     * [H-06] Stairs — only tread is textured; riser and side are IsoColor.
     */
    @Test
    fun `stairs_onlyTreadTextured_onlyTreadCollected`() {
        val treadSource = TextureSource.Resource(310)
        val material = IsometricMaterial.PerFace.Stairs(
            tread = IsometricMaterial.Textured(treadSource),
            riser = IsoColor(80, 80, 80),
            side  = null,
        )

        val result = collect(material)

        assertEquals(setOf(treadSource), result,
            "Stairs: only the textured tread slot must be collected")
    }

    /**
     * [H-06] Octahedron — four of eight faces are textured.
     */
    @Test
    fun `octahedron_partialFaces_onlyTexturedCollected`() {
        val src0 = TextureSource.Resource(400)
        val src2 = TextureSource.Resource(402)
        val src4 = TextureSource.Resource(404)
        val src6 = TextureSource.Resource(406)
        val material = IsometricMaterial.PerFace.Octahedron(
            byIndex = mapOf(
                OctahedronFace.UPPER_0 to IsometricMaterial.Textured(src0),
                OctahedronFace.LOWER_0 to IsoColor(255, 0, 0),
                OctahedronFace.UPPER_1 to IsometricMaterial.Textured(src2),
                OctahedronFace.LOWER_1 to IsoColor(0, 255, 0),
                OctahedronFace.UPPER_2 to IsometricMaterial.Textured(src4),
                OctahedronFace.LOWER_2 to IsoColor(0, 0, 255),
                OctahedronFace.UPPER_3 to IsometricMaterial.Textured(src6),
            ),
        )

        val result = collect(material)

        assertEquals(setOf(src0, src2, src4, src6), result,
            "Octahedron: only the four textured face slots must be collected; IsoColor slots skipped")
    }

    // ── Flat textured material + null / IsoColor passthrough ─────────────────

    /**
     * A plain [IsometricMaterial.Textured] (not PerFace) adds its own source.
     */
    @Test
    fun `flatTextured_addsOwnSource`() {
        val source = TextureSource.Resource(500)
        val result = collect(IsometricMaterial.Textured(source))

        assertEquals(setOf(source), result)
    }

    /**
     * An [IsoColor] material (non-Textured, non-PerFace) adds nothing.
     */
    @Test
    fun `isoColor_addsNothing`() {
        val result = collect(IsoColor(255, 0, 0))
        assertTrue(result.isEmpty(), "IsoColor must not add any TextureSource")
    }

    /**
     * Null material adds nothing.
     */
    @Test
    fun `null_material_addsNothing`() {
        val result = collect(null)
        assertTrue(result.isEmpty(), "null material must not add any TextureSource")
    }

    /**
     * Calling collectTextureSourcesFromMaterial twice for two distinct materials
     * accumulates sources in the same set (union semantics used by uploadAtlasAndBindGroup).
     */
    @Test
    fun `accumulation_acrossMultipleMaterials`() {
        val src1 = TextureSource.Resource(1)
        val src2 = TextureSource.Resource(2)
        val out = mutableSetOf<TextureSource>()

        collectTextureSourcesFromMaterial(IsometricMaterial.Textured(src1), out)
        collectTextureSourcesFromMaterial(IsometricMaterial.Textured(src2), out)

        assertEquals(setOf(src1, src2), out as Set<TextureSource>,
            "Accumulation: two calls to collectTextureSourcesFromMaterial must union their results")
    }

    /**
     * Duplicate sources (same TextureSource added by two PerFace slots) must be de-duplicated
     * since the output is a Set.
     */
    @Test
    fun `deduplication_sameBitmapSourceUsedByMultipleSlots`() {
        val sharedSource = TextureSource.Resource(999)
        val material = IsometricMaterial.PerFace.Cylinder(
            top    = IsometricMaterial.Textured(sharedSource),
            bottom = IsometricMaterial.Textured(sharedSource),
            side   = IsometricMaterial.Textured(sharedSource),
        )

        val result = collect(material)

        assertEquals(1, result.size,
            "Duplicate TextureSource entries must be de-duplicated in the Set output")
        assertTrue(sharedSource in result)
    }

    /**
     * [D-13] Regression: a Cylinder where only the top is textured must NOT collect
     * sources from the bottom or side (guard against accidental null-deref or default-fallback).
     */
    @Test
    fun `cylinder_regression_onlyTopTextured_doesNotIncludeBottomOrSide`() {
        val topSource = TextureSource.Resource(600)
        val material = IsometricMaterial.PerFace.Cylinder(
            top    = IsometricMaterial.Textured(topSource),
            bottom = IsoColor(100, 100, 100),
            side   = null,
        )

        val result = collect(material)

        assertEquals(1, result.size)
        assertTrue(topSource in result)
        assertFalse(
            result.any { it is TextureSource.Resource && it.resId != 600 },
            "No spurious sources must be collected"
        )
    }
}
