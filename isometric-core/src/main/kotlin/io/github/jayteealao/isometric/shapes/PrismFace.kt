package io.github.jayteealao.isometric.shapes

/**
 * Identifies which face of a [Prism] a path corresponds to.
 *
 * Face roles map to stable path indices in the order produced by [Prism.createPaths]:
 * index 0 = FRONT, 1 = BACK, 2 = LEFT, 3 = RIGHT, 4 = BOTTOM, 5 = TOP.
 */
enum class PrismFace : FaceIdentifier {
    FRONT,
    BACK,
    LEFT,
    RIGHT,
    BOTTOM,
    TOP;

    public companion object {
        /**
         * Returns the [PrismFace] for the given 0-based path index within [Prism.paths].
         *
         * @throws IllegalArgumentException if [index] is outside 0..5
         */
        public fun fromPathIndex(index: Int): PrismFace = when (index) {
            0 -> FRONT
            1 -> BACK
            2 -> LEFT
            3 -> RIGHT
            4 -> BOTTOM
            5 -> TOP
            else -> throw IllegalArgumentException(
                "Prism has exactly 6 faces (indices 0..5); got index $index"
            )
        }
    }
}
