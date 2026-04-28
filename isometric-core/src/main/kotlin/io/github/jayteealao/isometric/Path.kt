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
 * @param points The ordered vertices of this polygon (minimum 3)
 */
open class Path(
    points: List<Point>
) {
    val points: List<Point> = points.toList()

    init {
        require(this.points.size >= 3) { "Path requires at least 3 points, got ${this.points.size}" }
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
     * Implements Newell, Newell, and Sancha's classical Z->X->Y minimax cascade
     * (Newell, M. E., Newell, R. G., Sancha, T. L., 1972, "A solution to the hidden
     * surface problem"), terminating early at whichever step makes a definitive call:
     *
     * 1. **Iso-depth (Z) extent**: if the two polygons' iso-depth (`Point.depth(angle)`)
     *    extents are strictly disjoint, the one with the smaller depth range is
     *    unambiguously closer.
     * 2. **Screen-x extent**: same test, projected to iso screen-x.
     * 3. **Screen-y extent**: same test, projected to iso screen-y.
     * 4. **Plane-side forward**: if all of `this`'s vertices lie on the same side of
     *    [pathA]'s plane as [observer], `this` is closer; if all on the opposite side,
     *    `this` is farther; mixed → fall through.
     * 5. **Plane-side reverse**: same test with roles swapped (and sign inverted).
     * 6. **Polygon split** — DEFERRED. Cycle remnants are absorbed by Kahn's
     *    append-on-cycle fallback in [DepthSorter.sort].
     *
     * **Why not the older vote-and-subtract approach?** Three earlier rounds of
     * patches (`countCloserThan` integer division, permissive `result > 0`,
     * screen-overlap gate) each surfaced a residual regression class:
     *
     * 1. Integer division collapsed 2/4 vertex votes to 0 → under-determined sort.
     * 2. Permissive votes fired for screen-disjoint adjacent faces → over-aggressive
     *    topological edges in 3x3 grids.
     * 3. Wall-vs-floor symmetric-straddle: each polygon straddles the other's plane
     *    on its respective observer-axis, both vote-counts cancel to 0 → no edge,
     *    Kahn falls back to centroid pre-sort, ground paints over wall.
     *
     * Newell's cascade is structurally immune to all three modes: each step terminates
     * with a definitive sign or continues; only mixed-straddle pairs that pass all six
     * steps without decision return 0, which is the genuinely-ambiguous case.
     *
     * @param pathA the polygon to compare against.
     * @param observer eye position used to orient the plane-side tests.
     */
    fun closerThan(pathA: Path, observer: Point): Int {
        // Step 1: iso-depth (Z) extent minimax.
        // Point.depth(angle) returns LARGER values for points farther from the
        // observer; a strictly smaller-depth extent unambiguously indicates a
        // closer polygon.
        var selfDepthMin = Double.POSITIVE_INFINITY
        var selfDepthMax = Double.NEGATIVE_INFINITY
        for (p in points) {
            val d = p.depth(ISO_ANGLE)
            if (d < selfDepthMin) selfDepthMin = d
            if (d > selfDepthMax) selfDepthMax = d
        }
        var aDepthMin = Double.POSITIVE_INFINITY
        var aDepthMax = Double.NEGATIVE_INFINITY
        for (p in pathA.points) {
            val d = p.depth(ISO_ANGLE)
            if (d < aDepthMin) aDepthMin = d
            if (d > aDepthMax) aDepthMax = d
        }
        // self entirely smaller depth → self entirely closer → self draws after → -1.
        if (selfDepthMax < aDepthMin - EPSILON) return -1
        // pathA entirely smaller depth → self entirely farther → self draws before → +1.
        if (aDepthMax < selfDepthMin - EPSILON) return 1

        // Steps 2 / 3: screen-x and screen-y extent minimax.
        // Iso projection used by IsometricEngine: screenX = (x - y) * cos(angle);
        // screenY = -(sin(angle) * (x + y) + z). We compute the same scale-free
        // projection here so the cascade decides on actual screen overlap rather
        // than world-coordinate overlap. Disjoint screen-x / screen-y at a
        // strict-inequality threshold means the polygons cannot overpaint each
        // other along that screen axis; the sign convention follows iso-projection
        // semantics (self with larger world x/y is farther in iso depth).
        val cosAngle = cos(ISO_ANGLE)
        val sinAngle = sin(ISO_ANGLE)

        var selfScreenXMin = Double.POSITIVE_INFINITY
        var selfScreenXMax = Double.NEGATIVE_INFINITY
        var selfScreenYMin = Double.POSITIVE_INFINITY
        var selfScreenYMax = Double.NEGATIVE_INFINITY
        for (p in points) {
            val sx = (p.x - p.y) * cosAngle
            val sy = -(sinAngle * (p.x + p.y) + p.z)
            if (sx < selfScreenXMin) selfScreenXMin = sx
            if (sx > selfScreenXMax) selfScreenXMax = sx
            if (sy < selfScreenYMin) selfScreenYMin = sy
            if (sy > selfScreenYMax) selfScreenYMax = sy
        }
        var aScreenXMin = Double.POSITIVE_INFINITY
        var aScreenXMax = Double.NEGATIVE_INFINITY
        var aScreenYMin = Double.POSITIVE_INFINITY
        var aScreenYMax = Double.NEGATIVE_INFINITY
        for (p in pathA.points) {
            val sx = (p.x - p.y) * cosAngle
            val sy = -(sinAngle * (p.x + p.y) + p.z)
            if (sx < aScreenXMin) aScreenXMin = sx
            if (sx > aScreenXMax) aScreenXMax = sx
            if (sy < aScreenYMin) aScreenYMin = sy
            if (sy > aScreenYMax) aScreenYMax = sy
        }

        // Step 2: screen-x. Larger screen-x corresponds to larger world (x - y),
        // which in the standard 30° iso projection points deeper into the scene.
        if (selfScreenXMax < aScreenXMin - EPSILON) return -1
        if (aScreenXMax < selfScreenXMin - EPSILON) return 1

        // Step 3: screen-y. screenY = -(sin*(x+y) + z). Smaller (more negative)
        // screen-y means lower on screen — in iso this dominantly tracks
        // "deeper into the scene" (larger world (x+y) → larger iso depth →
        // farther). Self entirely-below pathA on screen → self farther → +1.
        if (selfScreenYMax < aScreenYMin - EPSILON) return 1
        if (aScreenYMax < selfScreenYMin - EPSILON) return -1

        // Step 4 (DepthSorter already gates on hasInteriorIntersection, so an
        // explicit early-exit on screen-disjoint polygons is intentionally
        // omitted here — keeping it would change the predicate's behaviour for
        // unit callers that pass screen-disjoint faces and expect the
        // plane-side test to decide).

        // Step 5: plane-side forward — are all of self's vertices on the same
        // side of pathA's plane as the observer (self closer), or all on the
        // opposite side (self farther)?
        val sFwd = signOfPlaneSide(pathA, observer)
        if (sFwd != 0) return sFwd

        // Step 6: plane-side reverse — are all of pathA's vertices on the same
        // side of self's plane as the observer? Sign inverted because the
        // result is from pathA's perspective.
        val sRev = pathA.signOfPlaneSide(this, observer)
        if (sRev != 0) return -sRev

        // Step 7 deferred: polygon split. Genuine ambiguity falls through to 0;
        // DepthSorter's append-on-cycle fallback handles any residual cycles.
        return 0
    }

    /**
     * Newell's strict all-on-same-side plane-side test.
     *
     * Returns:
     * - **+1** if every vertex of `this` lies strictly on the OPPOSITE side of [pathA]'s
     *   plane from [observer] (so `this` is entirely behind [pathA] — farther).
     * - **-1** if every vertex of `this` lies strictly on the SAME side of [pathA]'s
     *   plane as [observer] (so `this` is entirely in front of [pathA] — closer).
     *   Vertices exactly on the plane (within [EPSILON]) count as "same side" for this
     *   purpose.
     * - **0** otherwise (some vertices on each side — `this` straddles [pathA]'s plane;
     *   the cascade falls through to the next step).
     *
     * This is STRICTER than the historical [countCloserThan] which returned 1 when ANY
     * vertex was on the observer side. Newell requires ALL vertices to agree before
     * making a directional call; mixed-straddle is a 0 signal that lets the cascade
     * try the reverse direction (or fall through to polygon-split, currently deferred).
     */
    private fun signOfPlaneSide(pathA: Path, observer: Point): Int {
        // pathA's plane: defined by the first three vertices A, B, C, with outward
        // normal n = AB × AC and offset d = n · OA.
        val AB = Vector.fromTwoPoints(pathA.points[0], pathA.points[1])
        val AC = Vector.fromTwoPoints(pathA.points[0], pathA.points[2])
        val n = Vector.crossProduct(AB, AC)
        val OA = Vector.fromTwoPoints(Point.ORIGIN, pathA.points[0])
        val OU = Vector.fromTwoPoints(Point.ORIGIN, observer)
        val d = Vector.dotProduct(n, OA)
        val observerPosition = Vector.dotProduct(n, OU) - d

        var anySameSide = false
        var anyOppositeSide = false
        for (p in points) {
            val OP = Vector.fromTwoPoints(Point.ORIGIN, p)
            val pPosition = Vector.dotProduct(n, OP) - d
            val signed = observerPosition * pPosition
            if (signed > EPSILON) anySameSide = true
            if (signed < -EPSILON) anyOppositeSide = true
        }
        return when {
            anyOppositeSide && !anySameSide -> 1   // all opposite → self farther.
            anySameSide && !anyOppositeSide -> -1  // all same → self closer.
            else -> 0                              // mixed (or all coplanar) → undecided.
        }
    }

    private companion object {
        /**
         * Iso projection angle used by the cascade's screen-extent steps; matches the
         * 30° default that [IsometricEngine] applies in [Point.depth] and screen
         * projection.
         */
        private const val ISO_ANGLE: Double = PI / 6.0

        /**
         * Numerical tolerance for "coplanar" / "extents touch" — matches the rest of
         * the depth-sort pipeline (e.g., [Point] equality checks and the existing
         * 1e-6 plane-side tolerance) so that vertices within sub-pixel noise of a
         * plane do not flip cascade outcomes.
         */
        private const val EPSILON: Double = 0.000001
    }
}
