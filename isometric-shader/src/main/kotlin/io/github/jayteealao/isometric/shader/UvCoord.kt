package io.github.jayteealao.isometric.shader

/**
 * A texture coordinate.
 *
 * Values are typically in [0, 1] for a single texture fill, but may exceed this range
 * when tiling is enabled (e.g., `UvTransform.scaleU = 2f` produces UVs in [0, 2]).
 * No range validation is applied — the shader's `TileMode` (REPEAT/CLAMP/MIRROR)
 * determines how out-of-range values are interpreted.
 *
 * @property u Horizontal texture coordinate (0 = left edge, 1 = right edge)
 * @property v Vertical texture coordinate (0 = top edge, 1 = bottom edge)
 */
data class UvCoord(val u: Float, val v: Float) {
    companion object {
        val TOP_LEFT     = UvCoord(0f, 0f)
        val TOP_RIGHT    = UvCoord(1f, 0f)
        val BOTTOM_RIGHT = UvCoord(1f, 1f)
        val BOTTOM_LEFT  = UvCoord(0f, 1f)
    }
}

/**
 * Affine UV transform applied to texture coordinates before sampling.
 *
 * Applied in order: scale -> rotate -> offset.
 *
 * @property scaleU Horizontal scale factor (1.0 = no repeat, 2.0 = tile twice)
 * @property scaleV Vertical scale factor
 * @property offsetU Horizontal offset (0.0 = no offset)
 * @property offsetV Vertical offset
 * @property rotationDegrees Rotation in degrees around the texture center (0.0 = no rotation)
 */
data class UvTransform(
    val scaleU: Float = 1f,
    val scaleV: Float = 1f,
    val offsetU: Float = 0f,
    val offsetV: Float = 0f,
    val rotationDegrees: Float = 0f,
) {
    companion object {
        val IDENTITY = UvTransform()
    }
}
