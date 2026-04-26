package io.github.jayteealao.isometric.shapes

/**
 * Identifies which of the eight triangular faces of an [Octahedron] a path corresponds to.
 *
 * `Octahedron.createPaths()` emits paths in an **interleaved** upper/lower order over
 * four rotational quadrants:
 *
 * | Index | Face    |
 * |-------|---------|
 * | 0     | UPPER_0 |
 * | 1     | LOWER_0 |
 * | 2     | UPPER_1 |
 * | 3     | LOWER_1 |
 * | 4     | UPPER_2 |
 * | 5     | LOWER_2 |
 * | 6     | UPPER_3 |
 * | 7     | LOWER_3 |
 *
 * `UPPER_i` and `LOWER_i` share an edge on the equator for each quadrant `i`.
 */
enum class OctahedronFace : FaceIdentifier {
    UPPER_0,
    LOWER_0,
    UPPER_1,
    LOWER_1,
    UPPER_2,
    LOWER_2,
    UPPER_3,
    LOWER_3;

    public companion object {
        /**
         * Returns the [OctahedronFace] for the given 0-based path index within
         * `Octahedron.paths`.
         *
         * @throws IllegalArgumentException if [index] is outside 0..7
         */
        public fun fromPathIndex(index: Int): OctahedronFace = when (index) {
            0 -> UPPER_0
            1 -> LOWER_0
            2 -> UPPER_1
            3 -> LOWER_1
            4 -> UPPER_2
            5 -> LOWER_2
            6 -> UPPER_3
            7 -> LOWER_3
            else -> throw IllegalArgumentException(
                "Octahedron path index must be 0..7, got $index"
            )
        }
    }
}
