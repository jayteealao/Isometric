package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.ExperimentalIsometricApi
import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.compose.runtime.UvCoordProvider
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.Knot
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.Stairs

/**
 * Returns a [UvCoordProvider] that generates per-face UVs for [shape], or `null` if
 * per-face texturing is not supported for this shape type.
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
 * ## Supported shapes (5 fully wired + 1 experimental)
 *
 * - [Prism] — six axis-aligned quad faces via [UvGenerator.forPrismFace]
 * - [Octahedron] — eight triangular faces via [UvGenerator.forOctahedronFace]
 * - [Pyramid] — four lateral triangles + one base quad via [UvGenerator.forPyramidFace]
 * - [Cylinder] — two caps (N-gon disk) + N side quads via [UvGenerator.forCylinderFace]
 * - [Stairs] — interleaved riser/tread quads + two zigzag sides via [UvGenerator.forStairsFace]
 * - [Knot] (**experimental**) — bag-of-primitives dispatch: delegates faces 0–17 to
 *   three embedded [Prism] UV generators and faces 18–19 to AABB planar projection
 *   via [UvGenerator.forKnotFace]
 *
 * Shapes outside this list — including user-defined [Shape] subclasses — return `null`
 * and fall back to flat-color rendering. The [Knot] branch is gated by
 * [io.github.jayteealao.isometric.ExperimentalIsometricApi].
 */
@OptIn(ExperimentalIsometricApi::class)
internal fun uvCoordProviderForShape(shape: Shape): UvCoordProvider? = when (shape) {
    is Prism -> UvCoordProvider { _, faceIndex -> UvGenerator.forPrismFace(shape, faceIndex) }
    is Octahedron -> UvCoordProvider { _, faceIndex -> UvGenerator.forOctahedronFace(shape, faceIndex) }
    is Pyramid -> UvCoordProvider { _, faceIndex -> UvGenerator.forPyramidFace(shape, faceIndex) }
    is Cylinder -> UvCoordProvider { _, faceIndex -> UvGenerator.forCylinderFace(shape, faceIndex) }
    is Stairs -> UvCoordProvider { _, faceIndex -> UvGenerator.forStairsFace(shape, faceIndex) }
    is Knot -> UvCoordProvider { _, faceIndex -> UvGenerator.forKnotFace(shape, faceIndex) }
    else -> null
}
