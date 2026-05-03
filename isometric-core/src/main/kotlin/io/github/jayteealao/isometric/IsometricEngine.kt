package io.github.jayteealao.isometric

import kotlin.math.PI
import kotlin.math.roundToLong

/**
 * Core isometric rendering engine.
 * Platform-agnostic — outputs [PreparedScene] that can be rendered by any platform.
 *
 * This class is a thin facade that delegates to focused collaborators:
 * - [SceneGraph] — mutable scene state accumulation
 * - [IsometricProjection] — 3D-to-2D projection, lighting, culling
 * - [DepthSorter] — intersection-based depth sorting with broad-phase acceleration
 * - [HitTester] — hit testing with point-in-polygon and touch radius
 *
 * ## Coordinate System
 *
 * The engine uses a standard isometric projection with configurable [angle] (default 30°)
 * and [scale] (default 70 pixels per unit).
 *
 * ```
 *          z (up)
 *          |
 *          |
 *         / \
 *        /   \
 *       y     x
 *  (left-down) (right-down)
 * ```
 *
 * - **x-axis**: points right-and-down on screen
 * - **y-axis**: points left-and-down on screen
 * - **z-axis**: points straight up on screen
 *
 * ### Projection formulas
 *
 * The 3D-to-2D projection is:
 * ```
 * screenX = originX + x * scale * cos(angle) + y * scale * cos(PI - angle)
 * screenY = originY - x * scale * sin(angle) - y * scale * sin(PI - angle) - z * scale
 * ```
 *
 * ### Depth sorting
 *
 * Faces are sorted back-to-front using [Point.depth]: `x + y - 2 * z`.
 * Higher depth values are farther from the viewer and drawn first.
 *
 * ### Two-stage culling
 *
 * Culling runs in two passes for different geometric cases:
 *
 * 1. **Pre-projection** ([cullSharedInteriorFaces]) — removes pairs of vertical
 *    walls that occupy the same 3D coordinates with opposing normals (e.g. the
 *    shared wall between two adjacent tiles). Both partners are physically
 *    interior to the composite shape; neither should paint.
 * 2. **Post-projection** (in [projectAndCull] via [IsometricProjection.cullPath])
 *    — applies standard back-face culling using 2D vertex winding in screen
 *    space, removing any single face that turns away from the camera.
 *
 * Stage (1) catches the case where stage (2) would only remove one partner of a
 * coincident pair, leaving the other to spuriously paint over real visible faces.
 *
 * @param angle The isometric projection angle in radians. Default `PI / 6` (30°).
 * @param scale Pixels per world unit. Default `70.0`. Must be positive and finite.
 * @param colorDifference Per-face brightness modulation strength applied during
 *   lighting. `0.0` disables shading and renders every face in its raw colour;
 *   higher values increase contrast between faces with different normals. Must be
 *   non-negative and finite. Default `0.20`.
 * @param lightColor Tint blended into face colours during lighting. Default white.
 */
class IsometricEngine @JvmOverloads constructor(
    angle: Double = PI / 6,  // 30 degrees
    scale: Double = 70.0,
    private val colorDifference: Double = 0.20,
    private val lightColor: IsoColor = IsoColor.WHITE
) : SceneProjector {
    companion object {
        /** Default light direction used when none is specified. */
        @JvmField val DEFAULT_LIGHT_DIRECTION: Vector = SceneProjector.DEFAULT_LIGHT_DIRECTION

        /**
         * Tolerance in world units for treating two coordinates as identical.
         * Used by face-coincidence detection (quantization, normal comparison)
         * to absorb floating-point drift across composed transforms. Must match
         * the equivalent constant in [DepthSorter] so the two stages agree on
         * what "same edge / same vertex" means.
         */
        private const val SHARED_FACE_EPSILON: Double = 1e-6
    }

    /**
     * The isometric projection angle in radians.
     * Changing this at runtime recomputes the internal projection matrix.
     */
    var angle: Double = angle
        set(value) {
            require(value.isFinite()) { "angle must be finite, got $value" }
            field = value
            rebuildProjection()
        }

    /**
     * The isometric scale factor (pixels per world unit).
     * Changing this at runtime recomputes the internal projection matrix.
     */
    var scale: Double = scale
        set(value) {
            require(value.isFinite() && value > 0.0) { "scale must be positive and finite, got $value" }
            field = value
            rebuildProjection()
        }

    init {
        require(angle.isFinite()) { "angle must be finite, got $angle" }
        require(scale.isFinite() && scale > 0.0) { "scale must be positive and finite, got $scale" }
        require(colorDifference.isFinite() && colorDifference >= 0.0) {
            "colorDifference must be non-negative and finite, got $colorDifference"
        }
    }

    /**
     * Monotonically increasing version counter, incremented whenever
     * mutable engine parameters (angle, scale) change.
     * Signals caches that projected output may be stale.
     * Volatile to ensure visibility when read by the renderer on
     * a different thread (e.g. Canvas draw vs main-thread mutation).
     */
    @Volatile
    override var projectionVersion: Long = 0L
        private set

    private val sceneGraph = SceneGraph()
    private var projection = IsometricProjection(angle, scale, colorDifference, lightColor)

    /**
     * Rebuilds the internal [IsometricProjection] after a mutable parameter change
     * and bumps [projectionVersion] so any downstream caches invalidate.
     */
    private fun rebuildProjection() {
        projection = IsometricProjection(this.angle, this.scale, colorDifference, lightColor)
        projectionVersion++
    }

    /**
     * Project a 3D world point to 2D screen coordinates.
     *
     * @param point The 3D world point
     * @param viewportWidth The viewport width in pixels
     * @param viewportHeight The viewport height in pixels
     * @return The 2D screen position
     */
    fun worldToScreen(point: Point, viewportWidth: Int, viewportHeight: Int): Point2D {
        val originX = viewportWidth / 2.0
        val originY = viewportHeight * 0.9
        return projection.translatePoint(point, originX, originY)
    }

    /**
     * Unproject a 2D screen point back to 3D world coordinates on a given plane.
     *
     * The inverse projection is not unique — a screen point corresponds to a line
     * in 3D space. This method returns the intersection of that line with the
     * horizontal plane at the specified Z height.
     *
     * @param screenPoint The 2D screen position
     * @param viewportWidth The viewport width in pixels
     * @param viewportHeight The viewport height in pixels
     * @param z The Z plane to project onto (default: 0.0)
     * @return The 3D world point on the specified Z plane
     */
    fun screenToWorld(
        screenPoint: Point2D,
        viewportWidth: Int,
        viewportHeight: Int,
        z: Double = 0.0
    ): Point {
        val originX = viewportWidth / 2.0
        val originY = viewportHeight * 0.9
        return projection.screenToWorld(screenPoint, originX, originY, z)
    }

    /**
     * Adds all faces of a [Shape] to the scene with the given [color].
     *
     * Each face ([Path]) of the shape is added as a separate scene item so that
     * individual faces can be depth-sorted and lit independently.
     */
    override fun add(shape: Shape, color: IsoColor) = sceneGraph.add(shape, color)

    /**
     * Adds a single [Path] (polygon face) to the scene.
     *
     * @param path The polygon face to add
     * @param color The base color for this face
     * @param originalShape Optional reference to the parent [Shape] (used for hit-test grouping)
     * @param id Optional unique identifier for this scene item
     * @param ownerNodeId Optional identifier of the Compose node that owns this item
     */
    override fun add(
        path: Path,
        color: IsoColor,
        originalShape: Shape?,
        id: String?,
        ownerNodeId: String?
    ) = sceneGraph.add(path, color, originalShape, id, ownerNodeId)

    /**
     * Removes all items from the scene graph.
     */
    override fun clear() = sceneGraph.clear()

    /**
     * Projects the 3D scene to 2D screen space for the given viewport size.
     *
     * Applies back-face culling, bounds checking, lighting, and depth sorting
     * according to [renderOptions], then returns a platform-agnostic [PreparedScene]
     * containing sorted render commands ready for drawing.
     *
     * @param width The viewport width in pixels
     * @param height The viewport height in pixels
     * @param renderOptions Controls culling, sorting, and other rendering options
     * @param lightDirection The direction of the light source (will be normalized internally)
     */
    override fun projectScene(
        width: Int,
        height: Int,
        renderOptions: RenderOptions,
        lightDirection: Vector
    ): PreparedScene {
        val normalizedLight = lightDirection.normalize()
        // World origin maps to horizontally-centred, anchored 90% down the viewport.
        // This gives ~10% headroom below the floor for sub-zero geometry while keeping
        // most positive-Z content (which projects upward on screen) inside the canvas.
        val originX = width / 2.0
        val originY = height * 0.9

        val sourceItems = if (renderOptions.enableBackfaceCulling) {
            cullSharedInteriorFaces(sceneGraph.items)
        } else {
            sceneGraph.items
        }

        // Transform all items to 2D screen space, applying culling and lighting
        val transformedItems = sourceItems.mapNotNull { item ->
            projectAndCull(item, originX, originY, renderOptions, normalizedLight, width, height)
        }

        // Sort by depth if enabled
        val sortedItems = if (renderOptions.enableDepthSorting) {
            DepthSorter.sort(transformedItems, renderOptions)
        } else {
            transformedItems
        }

        // Convert to render commands
        val commands = sortedItems.map { transformedItem ->
            RenderCommand(
                commandId = transformedItem.item.id,
                points = transformedItem.transformedPoints,
                color = transformedItem.litColor,
                originalPath = transformedItem.item.path,
                originalShape = transformedItem.item.originalShape,
                ownerNodeId = transformedItem.item.ownerNodeId
            )
        }

        return PreparedScene(commands, width, height)
    }

    /**
     * Finds the [RenderCommand] at the given screen coordinates in the prepared scene.
     *
     * @param preparedScene The previously projected scene to query
     * @param x The screen x-coordinate to test
     * @param y The screen y-coordinate to test
     * @param order Whether to return the front-most or back-most hit
     * @param touchRadius Pixel radius for fuzzy hit testing (0.0 for exact)
     * @return The matching [RenderCommand], or `null` if nothing is hit
     */
    override fun findItemAt(
        preparedScene: PreparedScene,
        x: Double,
        y: Double,
        order: HitOrder,
        touchRadius: Double
    ): RenderCommand? = HitTester.findItemAt(preparedScene, x, y, order, touchRadius)

    /**
     * Projects a single scene item to screen space and applies per-item culling.
     *
     * Returns `null` when the item should be skipped entirely:
     * - **Back-face cull**: the projected polygon's screen-space vertex winding
     *   indicates the face turns away from the camera.
     * - **Bounds cull**: the projected polygon falls completely outside the
     *   viewport rectangle.
     *
     * Otherwise produces a [DepthSorter.TransformedItem] containing the projected
     * 2D points and the lit colour, ready for depth sorting.
     */
    private fun projectAndCull(
        item: SceneGraph.SceneItem,
        originX: Double,
        originY: Double,
        renderOptions: RenderOptions,
        normalizedLight: Vector,
        width: Int,
        height: Int
    ): DepthSorter.TransformedItem? {
        val screenPoints = item.path.points.map { point ->
            projection.translatePoint(point, originX, originY)
        }

        if (renderOptions.enableBackfaceCulling && projection.cullPath(screenPoints)) {
            return null
        }

        if (renderOptions.enableBoundsChecking && !projection.itemInDrawingBounds(screenPoints, width, height)) {
            return null
        }

        val litColor = projection.transformColor(item.path, item.baseColor, normalizedLight)
        return DepthSorter.TransformedItem(item, screenPoints, litColor)
    }

    /**
     * Removes pairs of vertical faces that occupy the same 3D coordinates with
     * opposing normals — i.e. shared interior walls of a composite shape.
     *
     * When two prisms or tiles are placed adjacent in 3D (a tile grid, a row of
     * stacked prisms, etc.), each side's wall coincides with the neighbour's
     * wall. Both walls are physically interior to the composite shape and
     * neither should be drawn.
     *
     * Why this is needed in addition to standard back-face culling:
     * - Back-face culling tests **one face at a time** in screen space using 2D
     *   vertex winding. From an isometric viewing angle, exactly one wall of a
     *   coincident vertical pair faces the camera and exactly one faces away —
     *   so back-face culling removes the back-facing partner but leaves the
     *   front-facing partner.
     * - The surviving partner has nothing physically behind it (its space is
     *   filled by the neighbour) but the depth-sort graph doesn't know that, so
     *   the surviving wall can be ordered to paint over genuinely visible
     *   faces. The classic symptom is a wall colour bleeding across an
     *   adjacent face's surface.
     *
     * Restricted to **vertical** faces (normal in the XY plane) because that is
     * the case back-face culling fails to fully resolve. Horizontal coincident
     * pairs (e.g. the TOP of one prism vs. the BOTTOM of a stacked prism) are
     * already handled correctly: the BOTTOM normal points down, is back-facing
     * from above, and is removed by single-face back-face culling.
     *
     * Restricted to **opposing normals** (dot product strictly negative) so the
     * pass does not collapse genuine same-direction overlaps such as a
     * decorative panel layered on an exterior wall.
     */
    private fun cullSharedInteriorFaces(items: List<SceneGraph.SceneItem>): List<SceneGraph.SceneItem> {
        if (items.size < 2) return items

        // Bucket items by the canonicalized vertex set of their face. Two faces
        // are candidate partners only if they live in the same bucket — i.e.
        // share an identical (modulo winding) vertex list in 3D.
        val groups = linkedMapOf<FaceKey, MutableList<Int>>()
        for (index in items.indices) {
            val key = faceKey(items[index].path)
            groups.getOrPut(key) { mutableListOf() }.add(index)
        }

        val culled = BooleanArray(items.size)
        for (indices in groups.values) {
            if (indices.size < 2) continue
            for (a in 0 until indices.lastIndex) {
                for (b in a + 1 until indices.size) {
                    val indexA = indices[a]
                    val indexB = indices[b]
                    if (isVerticalFace(items[indexA].path) &&
                        isVerticalFace(items[indexB].path) &&
                        oppositeNormals(items[indexA].path, items[indexB].path)
                    ) {
                        culled[indexA] = true
                        culled[indexB] = true
                    }
                }
            }
        }

        return items.filterIndexed { index, _ -> !culled[index] }
    }

    /**
     * Builds a canonical identity key for a face's vertex set, independent of
     * winding order or the choice of starting vertex.
     *
     * Two faces with vertices `[P, Q, R, S]` and `[R, S, P, Q]` (or any rotation
     * or reversal) produce the same key, so they group together for
     * coincidence detection. Coordinates are quantized to absorb
     * floating-point drift (see [quantize]).
     */
    private fun faceKey(path: Path): FaceKey {
        return FaceKey(
            path.points.map { point ->
                QuantizedPoint(
                    quantize(point.x),
                    quantize(point.y),
                    quantize(point.z)
                )
            }.sortedWith(compareBy<QuantizedPoint> { it.x }.thenBy { it.y }.thenBy { it.z })
        )
    }

    /**
     * Returns `true` when two faces' normals point in strictly opposite
     * directions (dot product `< -SHARED_FACE_EPSILON`).
     *
     * Degenerate (zero-length) normals — possible for collinear or duplicate
     * vertices — are rejected as not-opposite to avoid culling pairs whose
     * orientation cannot be determined.
     */
    private fun oppositeNormals(pathA: Path, pathB: Path): Boolean {
        val normalA = faceNormal(pathA)
        val normalB = faceNormal(pathB)
        val magnitudeA = normalA.x * normalA.x + normalA.y * normalA.y + normalA.z * normalA.z
        val magnitudeB = normalB.x * normalB.x + normalB.y * normalB.y + normalB.z * normalB.z
        if (magnitudeA <= SHARED_FACE_EPSILON || magnitudeB <= SHARED_FACE_EPSILON) return false

        val dot = normalA.x * normalB.x + normalA.y * normalB.y + normalA.z * normalB.z
        return dot < -SHARED_FACE_EPSILON
    }

    /**
     * Returns `true` when a face's normal lies in the XY plane (no Z component
     * within tolerance) — i.e. the face is a wall, not a top or bottom.
     */
    private fun isVerticalFace(path: Path): Boolean {
        val normal = faceNormal(path)
        return kotlin.math.abs(normal.z) <= SHARED_FACE_EPSILON
    }

    /**
     * Computes the unnormalized face normal as the cross product of two edges
     * fanning from the first vertex: `(p1 - p0) × (p2 - p0)`.
     *
     * The result is **not** unit-length — callers that need direction-only
     * comparisons (sign of dot product, sign of Z component) can use it
     * directly; callers that need true magnitudes must normalize.
     */
    private fun faceNormal(path: Path): FaceNormal {
        val a = path.points[0]
        val b = path.points[1]
        val c = path.points[2]
        val ux = b.x - a.x
        val uy = b.y - a.y
        val uz = b.z - a.z
        val vx = c.x - a.x
        val vy = c.y - a.y
        val vz = c.z - a.z
        return FaceNormal(
            x = uy * vz - uz * vy,
            y = uz * vx - ux * vz,
            z = ux * vy - uy * vx
        )
    }

    /**
     * Maps a continuous world-coordinate to an integer bucket of width
     * [SHARED_FACE_EPSILON]. Values that differ by less than the epsilon round
     * to the same bucket and therefore hash equal in [FaceKey].
     */
    private fun quantize(value: Double): Long {
        return (value / SHARED_FACE_EPSILON).roundToLong()
    }

    /**
     * Identity key for grouping faces that share an identical 3D vertex set,
     * independent of winding order. The point list is sorted into a canonical
     * order so any two faces with the same geometry produce equal keys.
     */
    private data class FaceKey(val points: List<QuantizedPoint>)

    /**
     * 3D point with each coordinate quantized into integer buckets of width
     * [SHARED_FACE_EPSILON]. Used as a stable equality key for face vertices,
     * absorbing the floating-point drift that a raw `Point` would expose.
     */
    private data class QuantizedPoint(
        val x: Long,
        val y: Long,
        val z: Long
    )

    /**
     * Unnormalized face normal vector. Magnitude is the parallelogram area of
     * the two edges crossed to compute it; only direction is used by callers.
     */
    private data class FaceNormal(
        val x: Double,
        val y: Double,
        val z: Double
    )

}
