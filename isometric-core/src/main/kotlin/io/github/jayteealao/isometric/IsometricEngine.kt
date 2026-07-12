package io.github.jayteealao.isometric

import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.floor

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
 *  (left-up) (right-up)
 * ```
 *
 * - **x-axis**: points right-and-up on screen (+x increases screenX, decreases screenY)
 * - **y-axis**: points left-and-up on screen (+y decreases screenX, decreases screenY)
 * - **z-axis**: points straight up on screen (+z decreases screenY)
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
 * Faces are sorted back-to-front using [Point.depth]: `x + y - z / sin(α)`.
 * At the default 30° angle, `sin(30°) = 0.5`, so `z / sin(30°) = 2z` and the formula
 * reduces exactly to the legacy `x+y−2z` (zero snapshot churn at the default angle).
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
            require(value.isFinite() && value > 0.0) { "angle must be finite and positive, got $value" }
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

    /**
     * Horizontal position of the scene origin as a fraction of the viewport width.
     *
     * `0.0` places the origin at the left edge; `1.0` at the right edge; the
     * default `0.5` centres the scene horizontally. Changing this at runtime
     * bumps [projectionVersion] so downstream caches invalidate.
     *
     * Used by [projectScene], [worldToScreen], and [screenToWorld] as a single
     * shared origin so hit-testing stays consistent with rendering.
     *
     * @see originYFraction
     * @see fitContent
     */
    var originXFraction: Double = 0.5
        set(value) {
            require(value.isFinite()) { "originXFraction must be finite, got $value" }
            field = value
            projectionVersion++
        }

    /**
     * Vertical position of the scene origin as a fraction of the viewport height.
     *
     * `0.0` places the origin at the top edge; `1.0` at the bottom edge; the
     * default `0.9` anchors the floor of the scene 90% down the viewport, leaving
     * ~10% headroom below the floor for sub-zero geometry while keeping most
     * positive-Z content inside the canvas. Changing this bumps [projectionVersion].
     *
     * @see originXFraction
     * @see fitContent
     */
    var originYFraction: Double = 0.9
        set(value) {
            require(value.isFinite()) { "originYFraction must be finite, got $value" }
            field = value
            projectionVersion++
        }

    init {
        require(angle.isFinite() && angle > 0.0) { "angle must be finite and positive, got $angle" }
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
    // Single-thread invariant: these caches are accessed only on the Compose draw thread.
    // If cullSharedInteriorFaces ever moves off-thread, synchronization is required.
    private val faceKeyPrimaryCache = IdentityHashMap<Path, FaceKey>()
    private val faceKeyBumpedCache  = IdentityHashMap<Path, FaceKey>()
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
        val originX = viewportWidth * originXFraction
        val originY = viewportHeight * originYFraction
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
        val originX = viewportWidth * originXFraction
        val originY = viewportHeight * originYFraction
        return projection.screenToWorld(screenPoint, originX, originY, z)
    }

    /**
     * Adjusts [scale], [originXFraction], and [originYFraction] so the scene's
     * content fills the viewport with optional [padding] on all sides.
     *
     * Unlike [AwtRenderer]'s post-projection centering (which only shrinks
     * overflowing content), this method also **enlarges** undersized content
     * so small scenes fill their container. The fit is **proportional**: the
     * larger of the two scale-to-fit ratios is used, so the content touches
     * the padding boundary in one axis and is centred in the other.
     *
     * Call this **after** adding shapes to the scene graph and **before** calling
     * [projectScene]. In Compose, use `SceneConfig.viewport`; in the Android View
     * surface, use `IsometricView.setFitContent(padding)`. Both surfaces call this
     * automatically when the viewport config is active.
     *
     * The method is a no-op when the scene graph is empty.
     *
     * @param width  Viewport width in pixels.
     * @param height Viewport height in pixels.
     * @param padding Uniform inset applied to all four sides (pixels, default 0.0).
     */
    fun fitContent(width: Int, height: Int, padding: Double = 0.0) {
        val items = sceneGraph.items
        if (items.isEmpty()) return
        if (width <= 0 || height <= 0) return

        // Project all vertices at scale=1, origin=(0,0) to find content bounds.
        // The projection at unit scale is:
        //   normX = (x - y) * cos(angle)      [since cos(PI-a) = -cos(a)]
        //   normY = -(x + y) * sin(angle) - z  [since sin(PI-a) = sin(a)]
        val cosA = kotlin.math.cos(this.angle)
        val sinA = kotlin.math.sin(this.angle)
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (item in items) {
            for (point in item.path.points) {
                val nx = (point.x - point.y) * cosA
                val ny = -(point.x + point.y) * sinA - point.z
                if (nx < minX) minX = nx
                if (nx > maxX) maxX = nx
                if (ny < minY) minY = ny
                if (ny > maxY) maxY = ny
            }
        }

        val contentW = maxX - minX
        val contentH = maxY - minY
        if (contentW <= 0.0 || contentH <= 0.0) return

        val availW = width - 2.0 * padding
        val availH = height - 2.0 * padding
        if (availW <= 0.0 || availH <= 0.0) return

        // Scale so the larger axis fits the available space (proportional fit).
        val newScale = minOf(availW / contentW, availH / contentH)

        // Centre the scaled content within the padded viewport.
        //   originX + minX * newScale = padding  →  originX = padding - minX * newScale
        //   originY + minY * newScale = padding  →  originY = padding - minY * newScale
        // But also centre in the perpendicular axis:
        //   scaled extent in each axis:
        val scaledW = contentW * newScale
        val scaledH = contentH * newScale
        val originX = (width - scaledW) / 2.0 - minX * newScale
        val originY = (height - scaledH) / 2.0 - minY * newScale

        // Store as fractions of the viewport so that worldToScreen / screenToWorld
        // use the same origin as projectScene for this frame's dimensions.
        this.scale = newScale
        this.originXFraction = originX / width
        this.originYFraction = originY / height
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
    override fun clear() {
        sceneGraph.clear()
        faceKeyPrimaryCache.clear()
        faceKeyBumpedCache.clear()
    }

    /** Returns the combined entry count of both faceKey memo maps. For test introspection only. */
    internal fun faceKeyCacheSize(): Int =
        faceKeyPrimaryCache.size + faceKeyBumpedCache.size

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
        // Origin position is configurable via [originXFraction] / [originYFraction];
        // defaults (0.5 / 0.9) reproduce the historical hardcoded values exactly,
        // preserving byte-identical output for callers that have not set either field.
        val originX = width * originXFraction
        val originY = height * originYFraction

        val sourceItems = if (renderOptions.enableBackfaceCulling) {
            cullSharedInteriorFaces(sceneGraph.items)
        } else {
            sceneGraph.items
        }

        // Transform all items to 2D screen space, applying culling and lighting
        val transformedItems = sourceItems.mapNotNull { item ->
            projectAndCull(item, originX, originY, renderOptions, normalizedLight, width, height)
        }

        // Sort by depth if enabled. Threading the engine's projection angle into
        // DepthSorter keeps Path.closerThan's Z-extent step in sync with this
        // engine instance's actual projection, instead of a baked 30° default.
        val sortedItems = if (renderOptions.enableDepthSorting) {
            DepthSorter.sort(transformedItems, renderOptions, this.angle)
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
        val edgeEq = IntersectionUtils.EdgeEquations2D.of(screenPoints)
        return DepthSorter.TransformedItem(item, screenPoints, litColor, edgeEq)
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
        //
        // Each face is indexed under TWO keys:
        //   1. Its primary floor-bucket key (faceKey).
        //   2. A "bumped" key (faceKeyBumped) where any coordinate whose fractional
        //      position within its floor bucket exceeds 0.5 is advanced to the next
        //      bucket. This ensures that two vertices whose coordinates straddle a
        //      bucket boundary — i.e. one sits near the top of bucket k while the
        //      other sits near the bottom of bucket k+1, with abs(a - b) < epsilon —
        //      share at least one key and therefore appear in the same candidate group.
        //
        // A subsequent per-pair call to verticesCoincide() performs an explicit
        // coordinate-level epsilon check, so only truly coincident faces (all vertices
        // pairwise within SHARED_FACE_EPSILON) are ever culled.
        val groups = linkedMapOf<FaceKey, MutableList<Int>>()
        for (index in items.indices) {
            val primaryKey = faceKey(items[index].path)
            groups.getOrPut(primaryKey) { mutableListOf() }.add(index)
            val bumpedKey = faceKeyBumped(items[index].path)
            if (bumpedKey != primaryKey) {
                groups.getOrPut(bumpedKey) { mutableListOf() }.add(index)
            }
        }

        val culled = BooleanArray(items.size)
        for (indices in groups.values) {
            if (indices.size < 2) continue
            for (a in 0 until indices.lastIndex) {
                for (b in a + 1 until indices.size) {
                    val indexA = indices[a]
                    val indexB = indices[b]
                    if (indexA == indexB) continue
                    if (isVerticalFace(items[indexA].path) &&
                        isVerticalFace(items[indexB].path) &&
                        oppositeNormals(items[indexA].path, items[indexB].path) &&
                        verticesCoincide(items[indexA].path, items[indexB].path)
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
     * Builds a canonical identity key for a face's vertex set using floor-bucketing,
     * independent of winding order or the choice of starting vertex.
     *
     * Two faces with vertices `[P, Q, R, S]` and `[R, S, P, Q]` (or any rotation
     * or reversal) produce the same key. Coordinates are floor-quantized (see
     * [quantize]). Use [faceKeyBumped] alongside this key to cover cross-boundary
     * coincidences (see [cullSharedInteriorFaces]).
     */
    private fun faceKey(path: Path): FaceKey =
        faceKeyPrimaryCache.getOrPut(path) {
            FaceKey(
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
     * Like [faceKey] but advances each coordinate's bucket by one whenever that
     * coordinate sits in the upper half of its floor bucket (fractional part > 0.5).
     *
     * When indexed alongside [faceKey], the bumped key guarantees that two vertices
     * whose coordinates straddle a floor-bucket boundary while being within
     * [SHARED_FACE_EPSILON] of each other share at least one key — the lower
     * vertex's bumped bucket equals the upper vertex's primary (floor) bucket.
     */
    private fun faceKeyBumped(path: Path): FaceKey =
        faceKeyBumpedCache.getOrPut(path) {
            FaceKey(
                path.points.map { point ->
                    QuantizedPoint(
                        quantizeBumped(point.x),
                        quantizeBumped(point.y),
                        quantizeBumped(point.z)
                    )
                }.sortedWith(compareBy<QuantizedPoint> { it.x }.thenBy { it.y }.thenBy { it.z })
            )
        }

    /**
     * Returns `true` when the two paths have the same number of vertices and every
     * corresponding pair of sorted vertices is within [SHARED_FACE_EPSILON] in each
     * coordinate. Vertices are sorted by (x, y, z) so the check is winding-order
     * and start-vertex independent.
     *
     * Used as an explicit guard after bucket-based grouping to rule out false
     * positives that can arise from the bumped-key overlap (see [faceKeyBumped]).
     */
    private fun verticesCoincide(pathA: Path, pathB: Path): Boolean {
        if (pathA.points.size != pathB.points.size) return false
        val cmp = compareBy<Point> { it.x }.thenBy { it.y }.thenBy { it.z }
        val sortedA = pathA.points.sortedWith(cmp)
        val sortedB = pathB.points.sortedWith(cmp)
        return sortedA.zip(sortedB).all { (a, b) ->
            kotlin.math.abs(a.x - b.x) < SHARED_FACE_EPSILON &&
            kotlin.math.abs(a.y - b.y) < SHARED_FACE_EPSILON &&
            kotlin.math.abs(a.z - b.z) < SHARED_FACE_EPSILON
        }
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
        if (path.points.size < 3) return FaceNormal(0.0, 0.0, 0.0)
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
     * Maps a continuous world-coordinate to the floor integer bucket of width
     * [SHARED_FACE_EPSILON]. Any two values that fall within the same
     * `[k * epsilon, (k+1) * epsilon)` interval produce the same bucket.
     *
     * Using floor (rather than round) moves the coarse bucket boundary from
     * half-integer positions to integer positions, ensuring that two values
     * such as `0.49e-6` and `0.51e-6` — which differ by less than epsilon but
     * would round to different buckets — both land in bucket 0.
     * Cross-boundary pairs (e.g. `0.99e-6` → bucket 0 and `1.01e-6` → bucket 1)
     * are resolved by also indexing faces under [quantizeBumped] / [faceKeyBumped].
     */
    private fun quantize(value: Double): Long {
        return floor(value / SHARED_FACE_EPSILON).toLong()
    }

    /**
     * Like [quantize] but advances the bucket by one whenever the coordinate's
     * fractional position within its floor bucket exceeds 0.5.
     *
     * Combined with [quantize] (see [faceKeyBumped]), this ensures that two
     * coordinates which straddle a floor-bucket boundary while being within
     * [SHARED_FACE_EPSILON] of each other share at least one canonical bucket
     * and therefore appear in the same candidate group during face culling.
     */
    private fun quantizeBumped(value: Double): Long {
        val scaled = value / SHARED_FACE_EPSILON
        val floorBucket = floor(scaled).toLong()
        return if (scaled - floorBucket > 0.5) floorBucket + 1L else floorBucket
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
