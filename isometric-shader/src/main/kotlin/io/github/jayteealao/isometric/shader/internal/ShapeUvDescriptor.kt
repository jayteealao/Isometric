package io.github.jayteealao.isometric.shader.internal

import io.github.jayteealao.isometric.MaterialData
import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.compose.runtime.UvCoordProvider
import io.github.jayteealao.isometric.shapes.FaceIdentifier
import io.github.jayteealao.isometric.shader.TextureSource

/**
 * Polymorphic descriptor for shape-specific UV / per-face / texture-source dispatch.
 *
 * Each concrete [Shape] subclass (Prism, Cylinder, Pyramid, Stairs, Octahedron, Knot)
 * has exactly one registered [ShapeUvDescriptor] in [ShapeRegistry]. The descriptor
 * encapsulates the shape-specific logic that was previously duplicated across the
 * 5 raw `when (shape) { is X -> ... }` dispatch sites.
 *
 * ## Extension point
 *
 * User-defined [Shape] subclasses that have no registered descriptor cause the
 * registry to return `null` for [ShapeRegistry.byClass] lookups. Call-sites that
 * receive `null` should fall back to flat-color / no-UV rendering, matching the
 * behaviour of the old `else -> null` arm.
 *
 * ## Migration status (G9 — webgpu-pipeline-cleanup)
 *
 * - **Migrated:** [io.github.jayteealao.isometric.shader.uvCoordProviderForShape]
 *   (site a) delegates to [ShapeRegistry] instead of a raw `when (shape)`.
 * - **Deferred:** `IsometricMaterial.PerFace.resolveForFace` (site b) and
 *   `collectTextureSourcesFromMaterial` (site c) already use exhaustive sealed-class
 *   `when` over `PerFace` subclasses — migration would add complexity without
 *   reducing risk; deferred to a follow-up slice.
 */
internal interface ShapeUvDescriptor {
    /**
     * Returns a [UvCoordProvider] that generates per-face UV coordinates for [shape],
     * or `null` if UV generation is not supported for this shape type.
     *
     * Used by [io.github.jayteealao.isometric.shader.uvCoordProviderForShape] (site a).
     */
    fun uvCoordProvider(shape: Shape): UvCoordProvider?

    /**
     * Returns the [FaceIdentifier] for the given [shape] at the given 0-based path index,
     * or `null` for shapes whose face taxonomy is not indexed (e.g. [io.github.jayteealao.isometric.shapes.Knot]).
     */
    fun faceIdentifier(shape: Shape, faceIndex: Int): FaceIdentifier?

    /**
     * Collects [TextureSource] references that the given [material] contributes for this
     * shape. Returns an empty list if [material] carries no texture sources relevant to
     * this shape (e.g. it is an [io.github.jayteealao.isometric.IsoColor]).
     *
     * This hook is a follow-up migration point for `collectTextureSourcesFromMaterial`
     * (site c). It is present in the interface to ensure descriptor implementations
     * stay current when site c is migrated; call-sites do not yet use it.
     */
    fun collectTextureSourcesContribution(material: MaterialData): List<TextureSource>
}
