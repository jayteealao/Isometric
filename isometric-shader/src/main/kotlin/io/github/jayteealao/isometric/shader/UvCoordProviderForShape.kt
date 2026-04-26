package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.compose.runtime.UvCoordProvider
import io.github.jayteealao.isometric.shader.internal.ShapeRegistry

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
 * - [io.github.jayteealao.isometric.shapes.Prism] — six axis-aligned quad faces via
 *   [UvGenerator.forPrismFace]
 * - [io.github.jayteealao.isometric.shapes.Octahedron] — eight triangular faces via
 *   [UvGenerator.forOctahedronFace]
 * - [io.github.jayteealao.isometric.shapes.Pyramid] — four lateral triangles + one base quad via
 *   [UvGenerator.forPyramidFace]
 * - [io.github.jayteealao.isometric.shapes.Cylinder] — two caps (N-gon disk) + N side quads via
 *   [UvGenerator.forCylinderFace]
 * - [io.github.jayteealao.isometric.shapes.Stairs] — interleaved riser/tread quads + two zigzag
 *   sides via [UvGenerator.forStairsFace]
 * - [io.github.jayteealao.isometric.shapes.Knot] (**experimental**) — bag-of-primitives dispatch:
 *   delegates faces 0–17 to three embedded Prism UV generators and faces 18–19 to AABB planar
 *   projection via [UvGenerator.forKnotFace]
 *
 * Shapes outside this list — including user-defined [Shape] subclasses — return `null`
 * and fall back to flat-color rendering.
 *
 * ## Dispatch
 *
 * Dispatches through [ShapeRegistry.byClass] (G9 — webgpu-pipeline-cleanup). Adding a
 * new shape requires registering a [io.github.jayteealao.isometric.shader.internal.ShapeUvDescriptor]
 * in [ShapeRegistry]; no change to this function. Unregistered shapes continue to
 * receive `null` — preserving the existing extension-point contract (U-15).
 */
internal fun uvCoordProviderForShape(shape: Shape): UvCoordProvider? =
    ShapeRegistry.byClass[shape::class]?.uvCoordProvider(shape)
