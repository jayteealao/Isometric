package io.github.jayteealao.isometric.webgpu.texture

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.TextureSource
import io.github.jayteealao.isometric.shader.TextureTransform
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the [resolveTextureTransform] top-level function.
 *
 * The function maps a pre-resolved [io.github.jayteealao.isometric.MaterialData]? to a
 * [TextureTransform] with four distinct branches:
 *
 * | Branch | Input                              | Expected                          |
 * |--------|------------------------------------|-----------------------------------|
 * | 1      | Textured with non-IDENTITY transform | that transform                  |
 * | 2      | Textured with IDENTITY transform   | [TextureTransform.IDENTITY]       |
 * | 3      | FlatColor (non-Textured MaterialData) | [TextureTransform.IDENTITY]    |
 * | 4      | null                               | [TextureTransform.IDENTITY]       |
 *
 * These tests run entirely on the JVM — no Android context or GPU device required.
 */
class UvTransformResolverTest {

    /**
     * Branch 1: A [IsometricMaterial.Textured] material with a non-IDENTITY transform must
     * return exactly the transform stored on the material, not IDENTITY.
     *
     * This is the most critical branch — if the resolver fell through to the `else` arm,
     * every textured face would silently render with IDENTITY UV mapping regardless of the
     * user-specified tiling, rotation, or offset.
     */
    @Test
    fun `branch1 Textured with non-IDENTITY transform returns that transform`() {
        val expected = TextureTransform.tiling(2f, 3f)
        val material = IsometricMaterial.Textured(
            source    = TextureSource.Asset("tex.png"),
            transform = expected,
        )
        val actual = resolveTextureTransform(material)
        assertEquals(expected, actual, "Textured material must return its own transform")
    }

    /**
     * Branch 2: A [IsometricMaterial.Textured] material whose transform is exactly
     * [TextureTransform.IDENTITY] must return IDENTITY (no accidental copy / new instance issue).
     */
    @Test
    fun `branch2 Textured with IDENTITY transform returns IDENTITY`() {
        val material = IsometricMaterial.Textured(
            source    = TextureSource.Asset("tex.png"),
            transform = TextureTransform.IDENTITY,
        )
        val actual = resolveTextureTransform(material)
        assertEquals(TextureTransform.IDENTITY, actual, "Textured with IDENTITY must return IDENTITY")
    }

    /**
     * Branch 3: A flat-color material ([IsoColor]) is not [IsometricMaterial.Textured], so
     * the resolver must fall through to the `else` arm and return [TextureTransform.IDENTITY].
     *
     * Covers any [io.github.jayteealao.isometric.MaterialData] that is not Textured (IsoColor
     * is the canonical flat-color implementation used by core).
     */
    @Test
    fun `branch3 FlatColor material returns IDENTITY`() {
        val material = IsoColor.RED
        val actual = resolveTextureTransform(material)
        assertEquals(TextureTransform.IDENTITY, actual, "FlatColor material must return IDENTITY")
    }

    /**
     * Branch 4: A `null` effective material (face with no material assigned) must return
     * [TextureTransform.IDENTITY] — the `else` arm handles `null`.
     */
    @Test
    fun `branch4 null material returns IDENTITY`() {
        val actual = resolveTextureTransform(null)
        assertEquals(TextureTransform.IDENTITY, actual, "null material must return IDENTITY")
    }
}
