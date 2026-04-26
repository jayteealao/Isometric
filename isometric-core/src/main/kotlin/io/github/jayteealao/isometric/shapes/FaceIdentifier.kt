package io.github.jayteealao.isometric.shapes

import io.github.jayteealao.isometric.Shape

/**
 * Marker interface for shape-specific face identifiers carried by
 * [io.github.jayteealao.isometric.RenderCommand.faceType].
 *
 * Each shape family defines its own face-identifying type — e.g. [PrismFace],
 * [CylinderFace], [PyramidFace], [StairsFace], [OctahedronFace] — and those types
 * implement this interface so the core pipeline can carry any of them through
 * `RenderCommand` without leaking shape-specific detail into the core module.
 *
 * Consumers that need to dispatch on face identity (such as
 * `io.github.jayteealao.isometric.shader.resolveForFace`) downcast to the concrete
 * face type relevant to the current material variant. Adding a new shape family
 * means the new face enum declares `: FaceIdentifier`; no change to `RenderCommand`.
 *
 * This interface has no members by design — it exists solely as a stable supertype
 * constraint on the `faceType` field. A richer contract (e.g. `pathIndex(): Int`)
 * may be added in a future shape slice when a shared need surfaces; until then, the
 * empty marker keeps concrete face types free to define their own invariants.
 */
public sealed interface FaceIdentifier {
    public companion object {
        /**
         * Returns the [FaceIdentifier] for the given shape at the given 0-based path index,
         * or `null` for shapes with no face taxonomy (e.g. [Knot]).
         *
         * This thin dispatch is the single canonical cross-module hook that lets
         * `isometric-compose` emit correct `faceType` values without importing any
         * concrete face type directly. Each arm delegates to the shape family's own
         * `fromPathIndex` (or equivalent) function.
         *
         * Added in G3 (webgpu-pipeline-cleanup) to fix [M-02]: `BatchNode.renderTo`
         * was emitting `faceType = null` for every shape because the dispatch
         * was missing. G9 (`ShapeUvDescriptor` registry) will consolidate all
         * 5 raw dispatch sites; until then this function acts as the shared
         * cross-module entry point.
         *
         * @param shape The (pre-transform) shape whose face is being identified.
         *   Must be the original shape, not a transform-derived copy, because
         *   `Shape.rotateZ`/`scale` return the base `Shape` type and would fail
         *   every `is <SubType>` check.
         * @param faceIndex 0-based index into `shape.paths`.
         * @return The [FaceIdentifier] for this face, or `null` if the shape has no
         *   named face taxonomy ([Knot] delegates to sub-prism faceType instead).
         */
        public fun forShape(shape: Shape?, faceIndex: Int): FaceIdentifier? = when (shape) {
            is Prism -> PrismFace.fromPathIndex(faceIndex)
            is Cylinder -> CylinderFace.fromPathIndex(faceIndex)
            is Pyramid -> PyramidFace.fromPathIndex(faceIndex)
            is Octahedron -> OctahedronFace.fromPathIndex(faceIndex)
            is Stairs -> StairsFace.fromPathIndex(faceIndex, shape.stepCount)
            else -> null  // Knot delegates to sub-prism faceType; unknown shapes return null
        }
    }
}
