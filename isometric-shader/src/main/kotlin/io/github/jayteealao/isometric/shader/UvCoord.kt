package io.github.jayteealao.isometric.shader

import kotlin.math.absoluteValue

/**
 * A texture coordinate. Internal — not part of the public API.
 *
 * Values are typically in [0, 1] for a single texture fill, but may exceed this range
 * when tiling is enabled. No range validation is applied — the shader's `TileMode`
 * (REPEAT/CLAMP/MIRROR) determines how out-of-range values are interpreted.
 *
 * @property u Horizontal texture coordinate (0 = left edge, 1 = right edge)
 * @property v Vertical texture coordinate (0 = top edge, 1 = bottom edge)
 */
internal data class UvCoord(val u: Float, val v: Float) {
    companion object {
        val TOP_LEFT     = UvCoord(0f, 0f)
        val TOP_RIGHT    = UvCoord(1f, 0f)
        val BOTTOM_RIGHT = UvCoord(1f, 1f)
        val BOTTOM_LEFT  = UvCoord(0f, 1f)
    }
}

/**
 * Affine UV transform applied to a texture when rendering a face.
 *
 * Applied in order: scale → rotate → offset. All operations are centered on the
 * texture midpoint (0.5, 0.5 in UV space).
 *
 * Use the companion factory functions for the most common cases:
 * - [tiling] — repeat the texture N times across the face
 * - [rotated] — rotate the texture by N degrees
 * - [offset] — shift the texture origin
 *
 * @property scaleU Horizontal scale factor (1.0 = no repeat, 2.0 = tile twice horizontally)
 * @property scaleV Vertical scale factor (1.0 = no repeat, 2.0 = tile twice vertically)
 * @property offsetU Horizontal offset in normalized UV space (0.0 = no offset)
 * @property offsetV Vertical offset in normalized UV space (0.0 = no offset)
 * @property rotationDegrees Rotation in degrees around the texture center (0.0 = no rotation)
 */
data class TextureTransform(
    val scaleU: Float = 1f,
    val scaleV: Float = 1f,
    val offsetU: Float = 0f,
    val offsetV: Float = 0f,
    val rotationDegrees: Float = 0f,
) {
    init {
        require(scaleU.isFinite()) { "scaleU must be finite, got $scaleU" }
        require(scaleU.absoluteValue > 0f) { "scaleU must be non-zero, got $scaleU" }
        require(scaleV.isFinite()) { "scaleV must be finite, got $scaleV" }
        require(scaleV.absoluteValue > 0f) { "scaleV must be non-zero, got $scaleV" }
        require(offsetU.isFinite()) { "offsetU must be finite, got $offsetU" }
        require(offsetV.isFinite()) { "offsetV must be finite, got $offsetV" }
        require(rotationDegrees.isFinite()) { "rotationDegrees must be finite, got $rotationDegrees" }
    }

    companion object {
        /** No transform — identity. */
        val IDENTITY = TextureTransform()

        /**
         * Repeats the texture [horizontal] times horizontally and [vertical] times vertically.
         *
         * ```kotlin
         * texturedResource(R.drawable.brick, transform = TextureTransform.tiling(2f, 2f))
         * ```
         */
        fun tiling(horizontal: Float, vertical: Float) =
            TextureTransform(scaleU = horizontal, scaleV = vertical)

        /**
         * Rotates the texture [degrees] around its center.
         *
         * ```kotlin
         * texturedResource(R.drawable.wood, transform = TextureTransform.rotated(45f))
         * ```
         */
        fun rotated(degrees: Float) = TextureTransform(rotationDegrees = degrees)

        /**
         * Shifts the texture origin by ([u], [v]) in normalized UV space.
         *
         * ```kotlin
         * texturedResource(R.drawable.tiles, transform = TextureTransform.offset(0.5f, 0f))
         * ```
         */
        fun offset(u: Float, v: Float) = TextureTransform(offsetU = u, offsetV = v)
    }
}
