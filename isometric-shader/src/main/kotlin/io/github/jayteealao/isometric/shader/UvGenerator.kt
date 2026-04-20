package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.ExperimentalIsometricApi
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.Knot
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.PrismFace
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.Stairs
import io.github.jayteealao.isometric.shapes.StairsFace
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
     * ## Mutation contract
     *
     * Returns a **shared read-only array**. Callers must not mutate the returned
     * `FloatArray`; the same instance is returned for every lateral call of every
     * pyramid, and for the base slot for every call against the same pyramid.
     * The render hot path (Canvas `computeAffineMatrix`, WebGPU
     * `GpuUvCoordsBuffer.uploadUvCoordsBuffer`) only reads the array; no in-tree
     * caller mutates it. Returning shared instances keeps `forPyramidFace` allocation-free
     * on the hot path — ~9.4 KB/frame at 100 pyramids previously allocated by the
     * defensive copy / per-call `FloatArray(8)`.
     *
     * @param pyramid The source Pyramid (provides dimensional extents for base normalization)
     * @param faceIndex 0..3 for laterals, 4 for the rectangular base
     * @return shared read-only [FloatArray] — do not mutate. 6 floats for laterals, 8 floats for the base.
     * @throws IllegalArgumentException if [faceIndex] is outside `0 until pyramid.paths.size`
     */
    fun forPyramidFace(pyramid: Pyramid, faceIndex: Int): FloatArray {
        require(faceIndex in pyramid.paths.indices) {
            "faceIndex $faceIndex out of bounds for Pyramid with ${pyramid.paths.size} faces (valid range: 0 until ${pyramid.paths.size})"
        }
        return if (faceIndex == 4) {
            getOrComputeBaseUvs(pyramid)
        } else {
            LATERAL_CANONICAL_UVS
        }
    }

    /**
     * Generates UV coordinates for all five Pyramid faces in `Pyramid.paths` order
     * (0..3 laterals, 4 base).
     */
    fun forAllPyramidFaces(pyramid: Pyramid): List<FloatArray> =
        pyramid.paths.indices.map { forPyramidFace(pyramid, it) }

    /**
     * Generates UV coordinates for a single [Cylinder] face.
     *
     * Face index layout (N = [Cylinder.vertices]):
     * - `0`              — bottom cap (N-gon, planar disk projection, reversed winding)
     * - `1`              — top cap (N-gon, planar disk projection)
     * - `2..(N + 1)`     — side quad `k = faceIndex - 2` (4-vertex wrap strip)
     *
     * Side UV convention: `u = k/N` at the left edge, `(k+1)/N` at the right edge;
     * `v = 0` at the top ring, `v = 1` at the base ring. The seam at `u = 1` is
     * produced correctly because [Cylinder] duplicates the geometric vertex at
     * angle 0 — `basePoints[N]` is identity-distinct from `basePoints[0]`, so slot
     * assignment emits `u = 0` for quad 0 and `u = 1` for quad N-1 without aliasing.
     *
     * Cap UV convention: planar disk projection centered at `(0.5, 0.5)` with
     * radius `0.5`, matching Unity's built-in Cylinder mesh convention. Cap results
     * are cached by identity on the [Cylinder] instance (H-5 pattern from pyramid)
     * — the cache covers both caps of the same instance.
     *
     * Note: Cylinder side `v = 0` is at the top ring, which is the opposite of
     * [PrismFace.FRONT] (which places `v = 0` at the bottom). Documented as a
     * known cross-shape inconsistency; correcting it would break the Prism baseline.
     *
     * @param cylinder The source Cylinder (provides `vertices` for N)
     * @param faceIndex 0-based index into [Cylinder.paths] (`0 until cylinder.paths.size`)
     * @return [FloatArray] of `N*2` floats for caps, 8 floats for sides
     * @throws IllegalArgumentException if [faceIndex] is outside `0 until cylinder.paths.size`
     */
    fun forCylinderFace(cylinder: Cylinder, faceIndex: Int): FloatArray {
        require(faceIndex in cylinder.paths.indices) {
            "faceIndex $faceIndex out of bounds for Cylinder with ${cylinder.paths.size} faces (valid range: 0 until ${cylinder.paths.size})"
        }
        val n = cylinder.vertices
        return when (faceIndex) {
            0 -> getOrComputeCapUvs(cylinder, reversed = true)
            1 -> getOrComputeCapUvs(cylinder, reversed = false)
            else -> cylinderSideUvs(faceIndex - 2, n)
        }
    }

    /**
     * Generates UV coordinates for all `N + 2` Cylinder faces in `Cylinder.paths` order.
     */
    fun forAllCylinderFaces(cylinder: Cylinder): List<FloatArray> =
        cylinder.paths.indices.map { forCylinderFace(cylinder, it) }

    @Volatile private var lastCapCylinder: Cylinder? = null
    @Volatile private var lastBottomCapUvs: FloatArray? = null
    @Volatile private var lastTopCapUvs: FloatArray? = null

    private fun getOrComputeCapUvs(cylinder: Cylinder, reversed: Boolean): FloatArray {
        if (lastCapCylinder !== cylinder) {
            lastCapCylinder = cylinder
            lastBottomCapUvs = null
            lastTopCapUvs = null
        }
        val cached = if (reversed) lastBottomCapUvs else lastTopCapUvs
        if (cached != null) return cached
        val fresh = computeCylinderCapUvs(cylinder.vertices, reversed)
        if (reversed) lastBottomCapUvs = fresh else lastTopCapUvs = fresh
        return fresh
    }

    private fun computeCylinderCapUvs(n: Int, reversed: Boolean): FloatArray {
        val result = FloatArray(n * 2)
        val order = if (reversed) (n - 1 downTo 0) else (0 until n)
        var slot = 0
        for (i in order) {
            val theta = i * 2.0 * PI / n
            result[slot * 2] = (0.5 + 0.5 * cos(theta)).toFloat()
            result[slot * 2 + 1] = (0.5 + 0.5 * sin(theta)).toFloat()
            slot++
        }
        return result
    }

    private fun cylinderSideUvs(k: Int, n: Int): FloatArray {
        val u0 = k.toFloat() / n
        val u1 = (k + 1).toFloat() / n
        return floatArrayOf(
            u0, 0f,
            u0, 1f,
            u1, 1f,
            u1, 0f,
        )
    }

    private val LATERAL_CANONICAL_UVS: FloatArray = floatArrayOf(
        0.0f, 1.0f,   // v[0] base-left
        1.0f, 1.0f,   // v[1] base-right
        0.5f, 0.0f,   // v[2] apex
    )

    // Single-slot identity cache for the base-face UVs. Pyramid is immutable after
    // construction, so `computePyramidBaseUvs` is referentially transparent per
    // Pyramid instance; the last-computed result is reused until a different Pyramid
    // arrives. Per-scene renders typically touch one or a few Pyramid instances per
    // frame, so hit rate is high. Not thread-safe, but UvGenerator is only called
    // from the UI / render thread in `ShapeNode.renderTo`.
    @Volatile private var lastBasePyramid: Pyramid? = null
    @Volatile private var lastBaseUvs: FloatArray? = null

    private fun getOrComputeBaseUvs(pyramid: Pyramid): FloatArray {
        val cached = lastBaseUvs
        if (lastBasePyramid === pyramid && cached != null) return cached
        val fresh = computePyramidBaseUvs(pyramid)
        lastBasePyramid = pyramid
        lastBaseUvs = fresh
        return fresh
    }

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

    /**
     * Generates UV coordinates for a single [Stairs] face.
     *
     * Stairs always occupy a `1 x 1 x 1` bounding box regardless of
     * [Stairs.stepCount], so every per-face UV calculation normalises directly
     * against the local step extent without consulting the shape's dimensions.
     *
     * Face index layout for `N = stairs.stepCount`:
     * - even `0..2N-2` — RISER (vertical quad, 4 vertices)
     * - odd `1..2N-1`  — TREAD (horizontal quad, 4 vertices)
     * - `2N`            — SIDE, left zigzag (`2N + 2` vertices)
     * - `2N + 1`        — SIDE, right zigzag (`2N + 2` vertices, `u` mirrored)
     *
     * UV conventions:
     * - RISER: `u = (x - pos.x)`, `v = (z - zBot) / riserHeight`. v0..v3 produce
     *   `(0,1), (0,0), (1,0), (1,1)`. `v = 0` is at the bottom of the riser,
     *   matching [PrismFace.FRONT] (unlike [forCylinderFace]'s `v = 0` at the top).
     * - TREAD: `u = (x - pos.x)`, `v = (y - yStart) / treadDepth`. v0..v3 produce
     *   `(0,0), (1,0), (1,1), (0,1)`.
     * - SIDE: planar `(y, z)` projection over the unit `1 x 1` extent. The right
     *   side uses `u = 1 - (y - pos.y)` so both walls read the texture
     *   left-to-right when viewed from outside the staircase.
     *
     * @param stairs The source Stairs (provides `position` and `stepCount`)
     * @param faceIndex 0-based index into [Stairs.paths] (`0 until stairs.paths.size`)
     * @return [FloatArray] of 8 floats for RISER/TREAD; `2 * (2 * stepCount + 2)`
     *   floats for SIDE faces
     * @throws IllegalArgumentException if [faceIndex] is outside `0 until stairs.paths.size`
     */
    fun forStairsFace(stairs: Stairs, faceIndex: Int): FloatArray {
        require(faceIndex in stairs.paths.indices) {
            "faceIndex $faceIndex out of bounds for Stairs with ${stairs.paths.size} faces (valid range: 0 until ${stairs.paths.size})"
        }
        val face = StairsFace.fromPathIndex(faceIndex, stairs.stepCount)
        val path = stairs.paths[faceIndex]
        val pos = stairs.position
        val n = stairs.stepCount

        return when (face) {
            StairsFace.RISER -> {
                val stepI = faceIndex / 2
                val riserHeight = 1.0 / n
                val zBot = pos.z + stepI * riserHeight
                val result = FloatArray(8)
                for (k in 0..3) {
                    val pt = path.points[k]
                    result[k * 2] = (pt.x - pos.x).toFloat()
                    result[k * 2 + 1] = ((pt.z - zBot) / riserHeight).toFloat()
                }
                result
            }
            StairsFace.TREAD -> {
                val stepI = faceIndex / 2
                val treadDepth = 1.0 / n
                val yStart = pos.y + stepI * treadDepth
                val result = FloatArray(8)
                for (k in 0..3) {
                    val pt = path.points[k]
                    result[k * 2] = (pt.x - pos.x).toFloat()
                    result[k * 2 + 1] = ((pt.y - yStart) / treadDepth).toFloat()
                }
                result
            }
            StairsFace.SIDE -> {
                val isRightSide = faceIndex == 2 * n + 1
                val vertCount = 2 * n + 2
                val result = FloatArray(2 * vertCount)
                for (k in 0 until vertCount) {
                    val pt = path.points[k]
                    val uNorm = pt.y - pos.y
                    val vNorm = pt.z - pos.z
                    result[k * 2] = (if (isRightSide) 1.0 - uNorm else uNorm).toFloat()
                    result[k * 2 + 1] = vNorm.toFloat()
                }
                result
            }
        }
    }

    /**
     * Generates UV coordinates for every face of [stairs] in `Stairs.paths` order
     * (`2 * stepCount` interleaved riser/tread quads, followed by the left and
     * right zigzag side faces).
     */
    fun forAllStairsFaces(stairs: Stairs): List<FloatArray> =
        stairs.paths.indices.map { forStairsFace(stairs, it) }

    /**
     * Generates UV coordinates for a single [Knot] face.
     *
     * `Knot` is a bag-of-primitives composite: 18 sub-prism faces (three
     * [Prism]s stored in [Knot.sourcePrisms]) followed by 2 custom quads that
     * close the shape. UV generation mirrors that decomposition:
     *
     * - faces `0..5`   delegate to `forPrismFace(knot.sourcePrisms[0], faceIndex)`
     * - faces `6..11`  delegate to `forPrismFace(knot.sourcePrisms[1], faceIndex - 6)`
     * - faces `12..17` delegate to `forPrismFace(knot.sourcePrisms[2], faceIndex - 12)`
     * - faces `18..19` use axis-aligned bounding-box planar projection
     *   ([quadBboxUvs]) in post-transform path space
     *
     * Sub-prism delegation uses the pre-transform `sourcePrisms` because the
     * prism dimensions are required for UV normalisation, and those dimensions
     * are not recoverable from the scaled+translated paths that [Knot.paths]
     * exposes at runtime.
     *
     * @param knot The source Knot
     * @param faceIndex 0-based index into [Knot.paths] (`0 until 20`)
     * @return [FloatArray] of 8 floats `[u0,v0, u1,v1, u2,v2, u3,v3]`
     * @throws IllegalArgumentException if [faceIndex] is outside `0 until knot.paths.size`
     */
    @OptIn(ExperimentalIsometricApi::class)
    @ExperimentalIsometricApi
    fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray {
        require(faceIndex in knot.paths.indices) {
            "faceIndex $faceIndex out of bounds for Knot with ${knot.paths.size} faces (valid range: 0 until ${knot.paths.size})"
        }
        return when (faceIndex) {
            in 0..17 -> {
                val prismIndex = faceIndex / 6
                val localFaceIndex = faceIndex % 6
                forPrismFace(knot.sourcePrisms[prismIndex], localFaceIndex)
            }
            18, 19 -> quadBboxUvs(knot.paths[faceIndex])
            else -> throw IllegalArgumentException(
                "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
            )
        }
    }

    /**
     * Generates UV coordinates for every face of [knot] in [Knot.paths] order
     * (20 arrays: 18 sub-prism faces followed by 2 custom quads).
     */
    @OptIn(ExperimentalIsometricApi::class)
    @ExperimentalIsometricApi
    fun forAllKnotFaces(knot: Knot): List<FloatArray> =
        knot.paths.indices.map { forKnotFace(knot, it) }

    // Axis-aligned bounding-box planar projection for a 4-vertex path. Projects
    // onto the two largest-extent axes; winding order is preserved from the
    // source path, which may not produce a canonical (0,0)(1,0)(1,1)(0,1)
    // ordering — callers must accept non-canonical winding. Degenerate spans
    // collapse to 0 to avoid division-by-zero.
    private fun quadBboxUvs(path: Path): FloatArray {
        val pts = path.points
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val minZ = pts.minOf { it.z }; val maxZ = pts.maxOf { it.z }

        val spanX = maxX - minX; val spanY = maxY - minY; val spanZ = maxZ - minZ

        val result = FloatArray(8)
        for (i in 0..3) {
            val pt = pts[i]
            val (u, v) = when {
                spanZ <= spanX && spanZ <= spanY ->
                    Pair(
                        if (spanX > 0.0) (pt.x - minX) / spanX else 0.0,
                        if (spanY > 0.0) (pt.y - minY) / spanY else 0.0,
                    )
                spanY <= spanX ->
                    Pair(
                        if (spanX > 0.0) (pt.x - minX) / spanX else 0.0,
                        if (spanZ > 0.0) (pt.z - minZ) / spanZ else 0.0,
                    )
                else ->
                    Pair(
                        if (spanY > 0.0) (pt.y - minY) / spanY else 0.0,
                        if (spanZ > 0.0) (pt.z - minZ) / spanZ else 0.0,
                    )
            }
            result[i * 2] = u.toFloat()
            result[i * 2 + 1] = v.toFloat()
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
