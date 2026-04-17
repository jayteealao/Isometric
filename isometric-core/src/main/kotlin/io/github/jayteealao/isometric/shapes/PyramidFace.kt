package io.github.jayteealao.isometric.shapes

/**
 * Identifies which logical face of a [Pyramid] a path corresponds to.
 *
 * A `Pyramid` has four triangular [Lateral] faces plus a rectangular [BASE] quad
 * (the base quad is added in the `uv-generation-pyramid` slice; the `uv-generation-shared-api`
 * slice only ships the type).
 *
 * [PyramidFace] is a `sealed class` rather than an enum because [Lateral] carries an
 * `index` payload identifying which of the four triangular faces it refers to. Use
 * the [LATERAL_0]–[LATERAL_3] constants in the companion when you just want to name a
 * specific lateral without constructing a new instance.
 *
 * ### Path-index numbering
 *
 * `Pyramid.createPaths()` currently emits paths in this order:
 *
 * | Index | Face      | Note                                                    |
 * |-------|-----------|---------------------------------------------------------|
 * | 0     | Lateral 0 | `face1`, parallel to the x-axis                         |
 * | 1     | Lateral 1 | `face1.rotateZ(PI)` — opposite side of Lateral 0        |
 * | 2     | Lateral 2 | `face2`, parallel to the y-axis                         |
 * | 3     | Lateral 3 | `face2.rotateZ(PI)` — opposite side of Lateral 2        |
 * | 4     | BASE      | rectangular base quad (added by pyramid UV slice)       |
 *
 * Note that construction order does not match spatial adjacency: path index 1 is the
 * rotation of path index 0, not the next-clockwise face. Callers that reason about
 * adjacency (e.g. cube-map UVs, shared seams) must consult the 3D geometry rather
 * than relying on index order.
 */
sealed class PyramidFace {
    /** The rectangular base quad of the pyramid. */
    public object BASE : PyramidFace()

    /**
     * One of the four triangular lateral faces.
     *
     * @property index 0..3 — position in the construction order (see class KDoc)
     */
    public data class Lateral(val index: Int) : PyramidFace() {
        init {
            require(index in 0..3) {
                "PyramidFace.Lateral index must be 0..3, got $index"
            }
        }
    }

    public companion object {
        /** Lateral face at path index 0 (`face1`, x-parallel). */
        public val LATERAL_0: Lateral = Lateral(0)

        /** Lateral face at path index 1 (`face1` rotated by PI around Z). */
        public val LATERAL_1: Lateral = Lateral(1)

        /** Lateral face at path index 2 (`face2`, y-parallel). */
        public val LATERAL_2: Lateral = Lateral(2)

        /** Lateral face at path index 3 (`face2` rotated by PI around Z). */
        public val LATERAL_3: Lateral = Lateral(3)

        /**
         * Returns the [PyramidFace] for the given 0-based path index within `Pyramid.paths`.
         *
         * @param index 0..3 for laterals, 4 for the base
         * @throws IllegalArgumentException if [index] is outside 0..4
         */
        public fun fromPathIndex(index: Int): PyramidFace = when (index) {
            0 -> LATERAL_0
            1 -> LATERAL_1
            2 -> LATERAL_2
            3 -> LATERAL_3
            4 -> BASE
            else -> throw IllegalArgumentException(
                "Pyramid path index must be 0..4, got $index"
            )
        }
    }
}
