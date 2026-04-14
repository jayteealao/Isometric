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
interface MaterialData
