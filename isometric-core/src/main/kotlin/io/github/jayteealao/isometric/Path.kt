package io.github.jayteealao.isometric

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Represents a polygon face in 3D space, defined by an ordered list of vertices.
 *
 * A Path requires at least 3 points and is used as the fundamental rendering primitive:
 * each face of a [Shape] is a Path. Paths are immutable; all transform methods return
 * new instances.
 *
 * A variadic constructor `Path(vararg points: Point)` is provided for convenient
 * inline construction.
 *
 * The [depth] property is the average depth of all vertices, used for back-to-front
 * sorting during rendering. Note that this is a sorting metric and is unrelated to the
 * `depth` parameter on shape classes like [io.github.jayteealao.isometric.shapes.Prism].
 *
 * @param points The ordered vertices of this polygon (minimum 3, all coordinates finite).
 */
open class Path(
    points: List<Point>
) {
    val points: List<Point> = points.toList()

    init {
        require(this.points.size >= 3) { "Path requires at least 3 points, got ${this.points.size}" }
        require(this.points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }) {
            "Path coordinates must be finite (no NaN or Infinity)"
        }
    }

    /**
     * Average depth of all points in this path, precalculated at construction time.
     * Uses the default 30° angle.
     */
    val depth: Double = this.points.sumOf { it.depth() } / this.points.size

    /**
     * Average depth for an arbitrary engine angle.
     *
     * @param angle The isometric projection angle in radians
     */
    fun depth(angle: Double): Double {
        return points.sumOf { it.depth(angle) } / points.size
    }

    constructor(vararg points: Point) : this(points.toList())

    /**
     * Returns a new path with the points in reverse order
     */
    fun reverse(): Path {
        return Path(points.reversed())
    }

    /**
     * Translate the path by the given deltas
     */
    fun translate(dx: Double, dy: Double, dz: Double): Path {
        return Path(points.map { it.translate(dx, dy, dz) })
    }

    /**
     * Rotate about origin on the X axis
     */
    fun rotateX(origin: Point, angle: Double): Path {
        return Path(points.map { it.rotateX(origin, angle) })
    }

    /**
     * Rotate about origin on the Y axis
     */
    fun rotateY(origin: Point, angle: Double): Path {
        return Path(points.map { it.rotateY(origin, angle) })
    }

    /**
     * Rotate about origin on the Z axis
     */
    fun rotateZ(origin: Point, angle: Double): Path {
        return Path(points.map { it.rotateZ(origin, angle) })
    }

    /**
     * Scale about a given origin
     */
    fun scale(origin: Point, dx: Double, dy: Double, dz: Double): Path {
        return Path(points.map { it.scale(origin, dx, dy, dz) })
    }

    fun scale(origin: Point, dx: Double, dy: Double): Path {
        return Path(points.map { it.scale(origin, dx, dy) })
    }

    fun scale(origin: Point, dx: Double): Path {
        return Path(points.map { it.scale(origin, dx) })
    }

    /**
     * Newell painter's-algorithm depth comparator.
     *
     * Returns a signed integer indicating which of `this` and [pathA] should be drawn
     * first (farther one) and which last (closer one) for correct painter's-algorithm
     * rendering, looking from [observer]:
     *
     * - **negative** — `this` is closer than [pathA] (so `this` paints AFTER [pathA]).
     * - **positive** — `this` is farther than [pathA] (so `this` paints BEFORE [pathA]).
     * - **zero** — genuine ambiguity Newell cannot resolve without polygon splitting.
     *
     * Implements a reduced form of Newell, Newell, and Sancha's classical Z->X->Y
     * minimax cascade (Newell, M. E., Newell, R. G., Sancha, T. L., 1972, "A solution
     * to the hidden surface problem"), terminating early at whichever step makes a
     * definitive call:
     *
     * 1. **Iso-depth (Z) extent**: if the two polygons' iso-depth (the iso-projection
     *    proxy `x*cos(projectionAngle) + y*sin(projectionAngle) - 2*z`) extents are
     *    strictly disjoint, the one with the smaller depth range is unambiguously
     *    closer.
     * 2. **Plane-side forward**: if all of `this`'s vertices lie on the same side of
     *    [pathA]'s plane as [observer], `this` is closer; if all on the opposite
     *    side, `this` is farther; mixed or coplanar -> fall through.
     * 3. **Plane-side reverse**: same test with roles swapped (and sign inverted).
     * 4. **Polygon split** — DEFERRED. Cycle remnants are absorbed by Kahn's
     *    append-on-cycle fallback in [DepthSorter.sort].
     *
     * Newell's classical screen-x and screen-y extent steps are intentionally omitted.
     * The depth-sort gate ([IntersectionUtils.hasInteriorIntersection]) already
     * rejects pairs whose screen polygons do not interior-overlap, so a screen-extent
     * test inside this comparator could never fire in practice.
     *
     * **Why a strict cascade over a vote-and-subtract predicate?** Three earlier
     * approaches each surfaced a residual regression class:
     *
     * 1. Integer division collapsed 2-of-4 vertex votes to 0 -> under-determined sort.
     * 2. Permissive "any vertex" votes fired for screen-disjoint adjacent faces ->
     *    over-aggressive topological edges in 3x3 grids.
     * 3. Wall-vs-floor symmetric-straddle: each polygon straddles the other's plane
     *    on its respective observer-axis, both vote-counts cancel to 0 -> no edge,
     *    Kahn falls back to centroid pre-sort, ground paints over wall.
     *
     * The strict cascade is structurally immune to all three modes: each step
     * terminates with a definitive sign or continues; only mixed-straddle pairs that
     * pass every step without decision return 0.
     *
     * **[projectionAngle] threading**: step 1's iso-depth proxy uses
     * `cos(projectionAngle) * x + sin(projectionAngle) * y - 2 * z`. Callers in the
     * depth-sort pipeline pass the active [IsometricEngine.angle] so non-default
     * projections produce correct first-stage ordering. The default `PI / 6` (30°)
     * matches [IsometricEngine]'s default and keeps unit tests source-compatible.
     *
     * @param pathA the polygon to compare against.
     * @param observer eye position used to orient the plane-side tests.
     * @param projectionAngle iso projection angle in radians; defaults to 30°.
     */
    @JvmOverloads
    fun closerThan(pathA: Path, observer: Point, projectionAngle: Double = PI / 6.0): Int {
        // Step 1: iso-depth (Z) extent minimax.
        // Inlined depth = x*isoCos + y*isoSin - 2*z to avoid recomputing cos/sin
        // per vertex; LARGER value = farther from observer.
        val isoCos = cos(projectionAngle)
        val isoSin = sin(projectionAngle)
        var selfDepthMin = Double.POSITIVE_INFINITY
        var selfDepthMax = Double.NEGATIVE_INFINITY
        for (p in points) {
            val d = p.x * isoCos + p.y * isoSin - 2.0 * p.z
            if (d < selfDepthMin) selfDepthMin = d
            if (d > selfDepthMax) selfDepthMax = d
        }
        var aDepthMin = Double.POSITIVE_INFINITY
        var aDepthMax = Double.NEGATIVE_INFINITY
        for (p in pathA.points) {
            val d = p.x * isoCos + p.y * isoSin - 2.0 * p.z
            if (d < aDepthMin) aDepthMin = d
            if (d > aDepthMax) aDepthMax = d
        }
        // self entirely smaller depth -> self entirely closer -> self draws after -> -1.
        if (selfDepthMax < aDepthMin - EPSILON) return -1
        // pathA entirely smaller depth -> self entirely farther -> self draws before -> +1.
        if (aDepthMax < selfDepthMin - EPSILON) return 1

        // Step 2: plane-side forward.
        val sFwd = relativePlaneSide(pathA, observer)
        if (sFwd != 0) return sFwd

        // Step 3: plane-side reverse. Sign inverted because the result is from
        // pathA's perspective.
        val sRev = pathA.relativePlaneSide(this, observer)
        if (sRev != 0) return -sRev

        // Step 4 deferred: polygon split. Genuine ambiguity falls through to 0;
        // DepthSorter's append-on-cycle fallback handles any residual cycles.
        return 0
    }

    /**
     * Newell's strict all-on-same-side plane-side test, used by [closerThan] cascade
     * step 2 and (with sign inversion) step 3. Returns:
     *
     * - **+1** if every vertex of `this` lies strictly on the OPPOSITE side of
     *   [pathA]'s plane from [observer] — `this` is entirely behind [pathA] (farther).
     * - **-1** if every vertex of `this` lies strictly on the SAME side of [pathA]'s
     *   plane as [observer] — `this` is entirely in front of [pathA] (closer).
     * - **0** otherwise: at least one vertex on each side, OR every vertex is within
     *   [EPSILON] of the plane (genuinely coplanar). The cascade falls through.
     *
     * The threshold is applied to each signed plane-distance independently
     * (`pPosition` and `observerPosition` are each compared against [EPSILON] before
     * any combination), so the test is invariant to observer distance and reports
     * coplanar vertices as neutral rather than counting them by sign.
     */
    private fun relativePlaneSide(pathA: Path, observer: Point): Int {
        // pathA's plane: normal n = (a1 - a0) x (a2 - a0). Allocated once, outside
        // the per-vertex loop; the per-vertex signed-distance test is inlined as
        // n.x*p.x + n.y*p.y + n.z*p.z - d to avoid Vector.fromTwoPoints allocations.
        val AB = Vector.fromTwoPoints(pathA.points[0], pathA.points[1])
        val AC = Vector.fromTwoPoints(pathA.points[0], pathA.points[2])
        val n = Vector.crossProduct(AB, AC)
        val a0 = pathA.points[0]
        val d = n.x * a0.x + n.y * a0.y + n.z * a0.z

        val observerPosition = n.x * observer.x + n.y * observer.y + n.z * observer.z - d
        val observerSign = when {
            observerPosition > EPSILON -> 1
            observerPosition < -EPSILON -> -1
            else -> return 0   // observer on plane (or degenerate normal) -> undefined.
        }

        var anySameSide = false
        var anyOppositeSide = false
        for (p in points) {
            val pPosition = n.x * p.x + n.y * p.y + n.z * p.z - d
            val pSign = when {
                pPosition > EPSILON -> 1
                pPosition < -EPSILON -> -1
                else -> 0  // vertex on plane — neutral, doesn't vote.
            }
            if (pSign == 0) continue
            if (pSign == observerSign) anySameSide = true
            else anyOppositeSide = true
        }
        return when {
            anyOppositeSide && !anySameSide -> 1   // all opposite -> self farther.
            anySameSide && !anyOppositeSide -> -1  // all same -> self closer.
            else -> 0                              // mixed or all coplanar -> undecided.
        }
    }

    private companion object {
        /**
         * Numerical tolerance for "coplanar" / "extents touch", applied per
         * signed-distance to keep behaviour observer-distance-invariant. Matches the
         * rest of the depth-sort pipeline (e.g., [Point] equality checks and
         * [IntersectionUtils.hasInteriorIntersection]'s edge band).
         */
        private const val EPSILON: Double = 1e-6
    }
}
