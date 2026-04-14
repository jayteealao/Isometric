package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.PrismFace

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
