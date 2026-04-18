package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.compose.runtime.UvCoordProvider
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid

/**
 * Returns a [UvCoordProvider] that generates per-face UVs for [shape], or `null` if
 * per-face texturing is not yet supported for this shape type.
 *
 * ## Contract
 *
 * - Returning `null` means the renderer should skip UV computation and fall back to
 *   flat-color rendering even if the material is [IsometricMaterial.Textured]
 *   or [IsometricMaterial.PerFace].
 * - Returning non-null commits to producing a [FloatArray] of `2 * faceVertexCount`
 *   floats per face in `[u0,v0, u1,v1, ...]` order matching the shape's path
 *   vertex order.
 *
 * ## Extension
 *
 * Each shape slice adds a `when` branch here:
 * - `uv-generation-stairs`   → `is Stairs`
 * - `uv-generation-knot`     → `is Knot`
 *
 * Until those slices land, the remaining shapes return `null` and texturing
 * is a no-op for them at the renderer level.
 */
internal fun uvCoordProviderForShape(shape: Shape): UvCoordProvider? = when (shape) {
    is Prism -> UvCoordProvider { _, faceIndex -> UvGenerator.forPrismFace(shape, faceIndex) }
    is Octahedron -> UvCoordProvider { _, faceIndex -> UvGenerator.forOctahedronFace(shape, faceIndex) }
    is Pyramid -> UvCoordProvider { _, faceIndex -> UvGenerator.forPyramidFace(shape, faceIndex) }
    is Cylinder -> UvCoordProvider { _, faceIndex -> UvGenerator.forCylinderFace(shape, faceIndex) }
    else -> null
}
