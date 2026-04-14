package io.github.jayteealao.isometric

/**
 * Marker interface for material data carried through the render pipeline.
 *
 * Implemented by [IsoColor] (this module, flat-color rendering with zero overhead)
 * and by `IsometricMaterial` in the `isometric-shader` module (textured / per-face).
 * `RenderCommand` holds a reference typed to this interface so that
 * `isometric-core` remains free of Android dependencies while still
 * providing compile-time safety for the material field.
 */
interface MaterialData {
    /**
     * Returns a representative flat color for this material.
     *
     * Used by renderers that do not support full material rendering — for example,
     * the base color pass for lighting, or a fallback when no texture hook is installed.
     *
     * - [IsoColor]: returns itself
     * - Textured materials: returns the tint color
     * - Per-face materials: returns the base color of the `default` face material
     * - Unknown implementors: returns [IsoColor.WHITE]
     */
    fun baseColor(): IsoColor = IsoColor.WHITE
}
