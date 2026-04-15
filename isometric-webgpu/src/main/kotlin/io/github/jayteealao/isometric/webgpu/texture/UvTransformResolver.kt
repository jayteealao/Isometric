package io.github.jayteealao.isometric.webgpu.texture

import io.github.jayteealao.isometric.MaterialData
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.TextureTransform

/**
 * Resolves the user [TextureTransform] for a pre-resolved effective material.
 *
 * This is a pure function: it depends only on [MaterialData] (no GPU context, no Android APIs)
 * and is therefore testable on the JVM without a device.
 *
 * ## Branch table
 *
 * | effective type              | result                     |
 * |-----------------------------|----------------------------|
 * | [IsometricMaterial.Textured] | `effective.transform`     |
 * | any other [MaterialData]    | [TextureTransform.IDENTITY] |
 * | `null`                      | [TextureTransform.IDENTITY] |
 *
 * [IsometricMaterial.PerFace] is never passed here — the caller ([GpuTextureManager])
 * first resolves the per-face material via `resolveEffectiveMaterial()`, which expands
 * [IsometricMaterial.PerFace] into one of its constituent sub-materials before delegating
 * to this function.
 */
internal fun resolveTextureTransform(effective: MaterialData?): TextureTransform =
    when (effective) {
        is IsometricMaterial.Textured -> effective.transform
        else -> TextureTransform.IDENTITY
    }
