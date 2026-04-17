package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.PrismFace
import io.github.jayteealao.isometric.shapes.Pyramid

/**
 * Generates per-vertex UV coordinates for [Prism] faces. Internal implementation detail.
 *
 * UV coordinates are computed in 3D space (before isometric projection). Affine
 * mapping in screen space is correct for orthographic projection — no foreshortening
 * correction is required.
 *
 * Output is a [FloatArray] of 8 floats: `[u0,v0, u1,v1, u2,v2, u3,v3]` matching the
 * vertex order of [Prism.paths] at the given face index.
 */
internal object UvGenerator {

    /**
     * Generates UV coordinates for a single Prism face identified by its 0-based
     * path index within [Prism.paths].
     *
     * @param prism The source Prism (provides dimensional extents for normalization)
     * @param faceIndex 0-based index into [Prism.paths] (0=FRONT, 1=BACK, 2=LEFT, 3=RIGHT, 4=BOTTOM, 5=TOP)
     * @return [FloatArray] of 8 floats `[u0,v0, u1,v1, u2,v2, u3,v3]`
     * @throws IllegalArgumentException if [faceIndex] is outside `0 until prism.paths.size`
     */
    fun forPrismFace(prism: Prism, faceIndex: Int): FloatArray {
        require(faceIndex in prism.paths.indices) {
            "faceIndex $faceIndex out of bounds for Prism with ${prism.paths.size} faces (valid range: 0 until ${prism.paths.size})"
        }
        return try {
            val face = PrismFace.fromPathIndex(faceIndex)
            val path = prism.paths[faceIndex]
            computeUvs(prism, face, path)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "UV generation failed for Prism at ${prism.position}, faceIndex=$faceIndex", e
            )
        }
    }

    /**
     * Generates UV coordinates for all six Prism faces in [Prism.paths] order.
     *
     * @return List of 6 [FloatArray], each with 8 floats, indexed 0=FRONT through 5=TOP
     */
    fun forAllPrismFaces(prism: Prism): List<FloatArray> =
        PrismFace.entries.indices.map { forPrismFace(prism, it) }

    /**
     * Generates UV coordinates for a single [Octahedron] face.
     *
     * Every face maps to the canonical equilateral-triangle UV layout:
     * `vertex 0 → (0,0)`, `vertex 1 → (1,0)`, `vertex 2 → (0.5,1)`. All 8 faces are
     * congruent in a regular octahedron, so no per-face orientation tracking is needed.
     *
     * @param octahedron The source Octahedron (used only for bounds validation)
     * @param faceIndex 0-based index into `Octahedron.paths` (0..7, interleaved upper/lower)
     * @return [FloatArray] of 6 floats `[u0,v0, u1,v1, u2,v2]`
     * @throws IllegalArgumentException if [faceIndex] is outside `0 until octahedron.paths.size`
     */
    fun forOctahedronFace(octahedron: Octahedron, faceIndex: Int): FloatArray {
        require(faceIndex in octahedron.paths.indices) {
            "faceIndex $faceIndex out of bounds for Octahedron with ${octahedron.paths.size} faces (valid range: 0 until ${octahedron.paths.size})"
        }
        return floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.5f, 1.0f,
        )
    }

    /**
     * Generates UV coordinates for all eight Octahedron faces in `Octahedron.paths` order.
     */
    fun forAllOctahedronFaces(octahedron: Octahedron): List<FloatArray> =
        octahedron.paths.indices.map { forOctahedronFace(octahedron, it) }

    /**
     * Generates UV coordinates for a single [Pyramid] face.
     *
     * Lateral faces (indices 0..3) share the canonical triangle layout
     * `(0,1)–(1,1)–(0.5,0)` — apex at UV-top, so the texture's top edge maps to
     * the pyramid's peak. The BASE face (index 4) uses a planar top-down
     * projection yielding `(0,0)–(1,0)–(1,1)–(0,1)` for any unit pyramid.
     *
     * @param pyramid The source Pyramid (provides dimensional extents for base normalization)
     * @param faceIndex 0..3 for laterals, 4 for the rectangular base
     * @return [FloatArray] of 6 floats for laterals, 8 floats for the base
     * @throws IllegalArgumentException if [faceIndex] is outside `0 until pyramid.paths.size`
     */
    fun forPyramidFace(pyramid: Pyramid, faceIndex: Int): FloatArray {
        require(faceIndex in pyramid.paths.indices) {
            "faceIndex $faceIndex out of bounds for Pyramid with ${pyramid.paths.size} faces (valid range: 0 until ${pyramid.paths.size})"
        }
        return if (faceIndex == 4) {
            computePyramidBaseUvs(pyramid)
        } else {
            LATERAL_CANONICAL_UVS.copyOf()
        }
    }

    /**
     * Generates UV coordinates for all five Pyramid faces in `Pyramid.paths` order
     * (0..3 laterals, 4 base).
     */
    fun forAllPyramidFaces(pyramid: Pyramid): List<FloatArray> =
        pyramid.paths.indices.map { forPyramidFace(pyramid, it) }

    private val LATERAL_CANONICAL_UVS: FloatArray = floatArrayOf(
        0.0f, 1.0f,   // v[0] base-left
        1.0f, 1.0f,   // v[1] base-right
        0.5f, 0.0f,   // v[2] apex
    )

    private fun computePyramidBaseUvs(pyramid: Pyramid): FloatArray {
        val ox = pyramid.position.x
        val oy = pyramid.position.y
        val w = pyramid.width
        val d = pyramid.depth
        val path = pyramid.paths[4]
        val result = FloatArray(8)
        for (i in 0..3) {
            val pt = path.points[i]
            result[i * 2] = ((pt.x - ox) / w).toFloat()
            result[i * 2 + 1] = ((pt.y - oy) / d).toFloat()
        }
        return result
    }

    private fun computeUvs(prism: Prism, face: PrismFace, path: Path): FloatArray {
        val ox = prism.position.x
        val oy = prism.position.y
        val oz = prism.position.z
        val w = prism.width
        val d = prism.depth
        val h = prism.height

        val result = FloatArray(8)
        for (i in 0..3) {
            val pt = path.points[i]
            val u: Double
            val v: Double
            when (face) {
                PrismFace.FRONT -> {
                    u = (pt.x - ox) / w
                    v = (pt.z - oz) / h
                }
                PrismFace.BACK -> {
                    u = 1.0 - (pt.x - ox) / w
                    v = 1.0 - (pt.z - oz) / h
                }
                PrismFace.LEFT -> {
                    u = (pt.y - oy) / d
                    v = (pt.z - oz) / h
                }
                PrismFace.RIGHT -> {
                    u = 1.0 - (pt.y - oy) / d
                    v = (pt.z - oz) / h
                }
                PrismFace.BOTTOM -> {
                    u = 1.0 - (pt.x - ox) / w
                    v = (pt.y - oy) / d
                }
                PrismFace.TOP -> {
                    u = (pt.x - ox) / w
                    v = (pt.y - oy) / d
                }
            }
            result[i * 2] = u.toFloat()
            result[i * 2 + 1] = v.toFloat()
        }
        return result
    }
}
