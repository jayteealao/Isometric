package io.github.jayteealao.isometric.shapes

/**
 * Identifies which logical face of a [Cylinder] a path corresponds to.
 *
 * [Cylinder] is constructed via `Cylinder(position, radius, height, vertices)`,
 * which emits the reversed base ring at index 0 and the top ring at index 1.
 * Indices 2..(vertices + 1) are the rectangular side quads that wrap the barrel.
 *
 * The three values (`TOP`, `BOTTOM`, `SIDE`) intentionally collapse every side quad
 * into a single logical face so callers writing per-face materials can target
 * "cap vs. barrel" without enumerating every side slice individually.
 */
enum class CylinderFace : FaceIdentifier {
    TOP,
    BOTTOM,
    SIDE;

    public companion object {
        /**
         * Returns the [CylinderFace] for the given 0-based path index within
         * `Cylinder.paths`.
         *
         * @param index 0 = bottom cap (reversed base ring), 1 = top cap, >= 2 = side quad
         * @throws IllegalArgumentException if [index] is negative
         *
         * **Breaking change:** earlier releases returned the inverted mapping
         * (`0 → TOP, 1 → BOTTOM`), which did not match the cylinder's actual
         * path layout. The current mapping is the corrected one; callers that
         * hard-coded `fromPathIndex(0) == TOP` must be updated to expect
         * `BOTTOM` (and conversely for index 1).
         */
        public fun fromPathIndex(index: Int): CylinderFace = when {
            index < 0 -> throw IllegalArgumentException(
                "CylinderFace path index must be non-negative; got $index"
            )
            index == 0 -> BOTTOM
            index == 1 -> TOP
            else -> SIDE
        }
    }
}
