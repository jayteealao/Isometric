package io.github.jayteealao.isometric.shapes

/**
 * Identifies which logical face of a [Cylinder] a path corresponds to.
 *
 * [Cylinder] is modelled as an extruded circle: index 0 is the top cap, index 1 is
 * the bottom cap, and indices 2..(vertices + 1) are the rectangular side quads that
 * wrap the barrel.
 *
 * The three values (`TOP`, `BOTTOM`, `SIDE`) intentionally collapse every side quad
 * into a single logical face so callers writing per-face materials can target
 * "cap vs. barrel" without enumerating every side slice individually.
 */
enum class CylinderFace {
    TOP,
    BOTTOM,
    SIDE;

    public companion object {
        /**
         * Returns the [CylinderFace] for the given 0-based path index within
         * `Cylinder.paths`.
         *
         * @param index 0 = top cap, 1 = bottom cap, >= 2 = side quad
         * @throws IllegalArgumentException if [index] is negative
         */
        public fun fromPathIndex(index: Int): CylinderFace = when {
            index < 0 -> throw IllegalArgumentException(
                "CylinderFace path index must be non-negative; got $index"
            )
            index == 0 -> TOP
            index == 1 -> BOTTOM
            else -> SIDE
        }
    }
}
