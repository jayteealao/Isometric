package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.ExperimentalIsometricApi
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.CylinderFace
import io.github.jayteealao.isometric.shapes.Knot
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.PrismFace
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.Stairs
import io.github.jayteealao.isometric.shapes.StairsFace
import io.github.jayteealao.isometric.shader.internal.IdentityCachedUvProvider
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates per-vertex UV coordinates for shape faces. Internal implementation detail.
 *
 * UV coordinates are computed in 3D space (before isometric projection). Affine
 * mapping in screen space is correct for orthographic projection — no foreshortening
 * correction is required.
 *
 * Output is a [FloatArray] of `2 × faceVertexCount` floats in `[u0,v0, u1,v1, ...]`
 * order matching the vertex order of the shape's [io.github.jayteealao.isometric.Path.points]
 * at the given face index.
 *
 * ## Face-index ordering rationale
 *
 * Each `for<Shape>Face(shape, faceIndex)` function accepts a 0-based path index into
 * `shape.paths`, which is the same ordering used by the isometric scene projector when
 * it emits [io.github.jayteealao.isometric.RenderCommand] instances. Maintaining a
 * single canonical face-index ordering (path list order) avoids N-way translation
 * tables between UV generation, material resolution, and GPU packing. Consumers that
 * need to identify a face semantically (e.g. "top cap") use the corresponding
 * `FaceIdentifier` subtype (e.g. [CylinderFace.fromPathIndex]) rather than relying on
 * raw indices, which keeps semantic and positional concerns separated.
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

    // G8: Migrate from AtomicReference<Triple<Cylinder, FloatArray, FloatArray>> to
    // two separate IdentityCachedUvProvider instances (one per cap). Each provider
    // stores a single (key, array) pair behind an AtomicReference, giving the same
    // TOCTOU protection as the prior Triple approach but with simpler, reusable code.
    // On a cap-miss for one provider, the OTHER cap is NOT eagerly computed here;
    // instead, the two providers each lazily compute their own array on first access.
    // The D-11 test semantics (identity-caching per Cylinder, distinct arrays for top
    // and bottom) are preserved: each provider caches its own FloatArray independently.
    private val cylinderTopCache = IdentityCachedUvProvider<Cylinder>()
    private val cylinderBottomCache = IdentityCachedUvProvider<Cylinder>()

    private fun getOrComputeCapUvs(cylinder: Cylinder, reversed: Boolean): FloatArray {
        return if (reversed) {
            cylinderBottomCache.compute(cylinder) { computeCylinderCapUvs(it.vertices, reversed = true) }
        } else {
            cylinderTopCache.compute(cylinder) { computeCylinderCapUvs(it.vertices, reversed = false) }
        }
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

    // G8: Migrate from two @Volatile fields to IdentityCachedUvProvider<Pyramid>.
    // The prior @Volatile pair had a TOCTOU window between the key read and value
    // read; IdentityCachedUvProvider stores a single (key, array) Pair behind an
    // AtomicReference, closing that window. Semantics (identity-keyed, single-slot)
    // and output (byte-identical FloatArray) are unchanged.
    private val pyramidBaseCache = IdentityCachedUvProvider<Pyramid>()

    private fun getOrComputeBaseUvs(pyramid: Pyramid): FloatArray =
        pyramidBaseCache.compute(pyramid) { computePyramidBaseUvs(it) }

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

        val stepI = faceIndex / 2
        return when (face) {
            StairsFace.RISER -> buildStairsRectUvs(stepI, n, path, pos, isRiser = true)
            StairsFace.TREAD -> buildStairsRectUvs(stepI, n, path, pos, isRiser = false)
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
        val prismFaceCount = knot.sourcePrisms.size * PrismFace.entries.size  // 3 * 6 = 18
        return when (faceIndex) {
            in 0 until prismFaceCount -> {
                val prismIndex = faceIndex / PrismFace.entries.size
                val localFaceIndex = faceIndex % PrismFace.entries.size
                forPrismFace(knot.sourcePrisms[prismIndex], localFaceIndex)
            }
            prismFaceCount, prismFaceCount + 1 -> quadBboxUvs(knot.paths[faceIndex])
            else -> error("unreachable: faceIndex=$faceIndex already validated to be in knot.paths.indices (${knot.paths.size} faces)")
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
    //
    // U-08: NaN/Infinity guard — return the default quad UVs if any coordinate is
    // non-finite rather than propagating NaN through the atlas math. Non-finite
    // coordinates should never appear for valid UvGenerator inputs, but guard here
    // for belt-and-suspenders safety (render-loop-safe).
    // U-08 single forward pass: compute min/max in one loop instead of 6 minOf/maxOf
    // traversals; mathematically identical output, half the iterations.
    private fun quadBboxUvs(path: Path): FloatArray {
        val pts = path.points

        // U-08 NaN guard: detect non-finite coordinates before attempting min/max.
        if (pts.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) {
            return floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        }

        // Single forward pass over pts to find all six min/max values.
        var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY; var maxZ = Double.NEGATIVE_INFINITY
        for (pt in pts) {
            if (pt.x < minX) minX = pt.x; if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y; if (pt.y > maxY) maxY = pt.y
            if (pt.z < minZ) minZ = pt.z; if (pt.z > maxZ) maxZ = pt.z
        }

        val spanX = maxX - minX; val spanY = maxY - minY; val spanZ = maxZ - minZ

        // U-04: hoist the axis-selection `when` out of the per-vertex loop so it
        // executes once per face rather than once per vertex. Also replace the
        // per-iteration `Pair(u, v)` allocation with two plain local vars.
        val result = FloatArray(8)
        when {
            spanZ <= spanX && spanZ <= spanY -> {
                // Project onto XY plane (Z is smallest span).
                val invX = if (spanX > 0.0) 1.0 / spanX else 0.0
                val invY = if (spanY > 0.0) 1.0 / spanY else 0.0
                for (i in 0..3) {
                    val pt = pts[i]
                    result[i * 2]     = ((pt.x - minX) * invX).toFloat()
                    result[i * 2 + 1] = ((pt.y - minY) * invY).toFloat()
                }
            }
            spanY <= spanX -> {
                // Project onto XZ plane (Y is smallest span).
                val invX = if (spanX > 0.0) 1.0 / spanX else 0.0
                val invZ = if (spanZ > 0.0) 1.0 / spanZ else 0.0
                for (i in 0..3) {
                    val pt = pts[i]
                    result[i * 2]     = ((pt.x - minX) * invX).toFloat()
                    result[i * 2 + 1] = ((pt.z - minZ) * invZ).toFloat()
                }
            }
            else -> {
                // Project onto YZ plane (X is smallest span).
                val invY = if (spanY > 0.0) 1.0 / spanY else 0.0
                val invZ = if (spanZ > 0.0) 1.0 / spanZ else 0.0
                for (i in 0..3) {
                    val pt = pts[i]
                    result[i * 2]     = ((pt.y - minY) * invY).toFloat()
                    result[i * 2 + 1] = ((pt.z - minZ) * invZ).toFloat()
                }
            }
        }
        return result
    }

    /**
     * Builds the 8-float UV array for a single RISER or TREAD step quad.
     *
     * Both RISER and TREAD faces map the x-axis of the step to `u` and differ only
     * in which world axis drives `v`:
     *
     * - **RISER** (`isRiser = true`): `v` normalises the Z coordinate over the riser
     *   height (`pt.z - zBot`), where `zBot` is the bottom Z of this riser step.
     * - **TREAD** (`isRiser = false`): `v` normalises the Y coordinate over the tread
     *   depth (`pt.y - yStart`), where `yStart` is the front Y of this tread step.
     *
     * Step origin is computed as `pos.<axis> + stepI * (1.0 / n)` in both cases.
     * The step size (1.0 / n) is the normalised step extent for a unit-bounding-box
     * staircase (width = depth = height = 1.0 regardless of [Stairs.stepCount]).
     *
     * @param stepI   0-based step index (`faceIndex / 2`)
     * @param n       [Stairs.stepCount]
     * @param path    The 4-vertex face path for this step
     * @param pos     The stairs' world position
     * @param isRiser `true` → RISER (Z-axis v); `false` → TREAD (Y-axis v)
     * @return [FloatArray] of 8 floats `[u0,v0, u1,v1, u2,v2, u3,v3]`
     */
    private fun buildStairsRectUvs(
        stepI: Int,
        n: Int,
        path: Path,
        pos: Point,
        isRiser: Boolean,
    ): FloatArray {
        val stepSize = 1.0 / n
        val stepOrigin = if (isRiser) pos.z + stepI * stepSize else pos.y + stepI * stepSize
        val result = FloatArray(8)
        for (k in 0..3) {
            val pt = path.points[k]
            result[k * 2] = (pt.x - pos.x).toFloat()
            result[k * 2 + 1] = (if (isRiser) (pt.z - stepOrigin) / stepSize
                                 else         (pt.y - stepOrigin) / stepSize).toFloat()
        }
        return result
    }

    /**
     * Clears all identity-cached UV arrays held by this generator.
     *
     * Intended to be called from [GpuFullPipeline.clearScene] to release references
     * to shape instances that may otherwise be retained across scene transitions,
     * preventing GC of stale scene objects.
     *
     * Thread-safe: each provider uses an AtomicReference internally.
     */
    fun clearAllCaches() {
        cylinderTopCache.clear()
        cylinderBottomCache.clear()
        pyramidBaseCache.clear()
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
