package io.github.jayteealao.isometric

/**
 * Marker interface for material data carried through the render pipeline.
 *
 * Implemented by `IsometricMaterial` in the `isometric-shader` module.
 * `RenderCommand` holds a reference typed to this interface so that
 * `isometric-core` remains free of Android dependencies while still
 * providing compile-time safety for the material field.
 */
interface MaterialData
