package io.github.jayteealao.isometric.shapes

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
public sealed interface FaceIdentifier
