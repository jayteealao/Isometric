package io.fabianterhorst.isometric

import kotlin.math.PI

/**
 * Core isometric rendering engine.
 * Platform-agnostic — outputs [PreparedScene] that can be rendered by any platform.
 *
 * This class is a thin facade that delegates to focused collaborators:
 * - [SceneGraph] — mutable scene state accumulation
 * - [IsometricProjection] — 3D-to-2D projection, lighting, culling
 * - [DepthSorter] — intersection-based depth sorting with broad-phase acceleration
 * - [HitTester] — hit testing with point-in-polygon and touch radius
 */
class IsometricEngine(
    angle: Double = PI / 6,  // 30 degrees
    scale: Double = 70.0,
    private val colorDifference: Double = 0.20,
    private val lightColor: IsoColor = IsoColor.WHITE
) : SceneProjector {
    companion object {
        /** Default light direction used when none is specified. */
        val DEFAULT_LIGHT_DIRECTION: Vector = SceneProjector.DEFAULT_LIGHT_DIRECTION
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

    override fun add(shape: Shape, color: IsoColor) = sceneGraph.add(shape, color)

    override fun add(
        path: Path,
        color: IsoColor,
        originalShape: Shape?,
        id: String?,
        ownerNodeId: String?
    ) = sceneGraph.add(path, color, originalShape, id, ownerNodeId)

    override fun clear() = sceneGraph.clear()

    /**
     * Project the 3D scene to 2D screen space for the given viewport size.
     * Returns a platform-agnostic [PreparedScene] with sorted render commands.
     */
    override fun projectScene(
        width: Int,
        height: Int,
        renderOptions: RenderOptions,
        lightDirection: Vector
    ): PreparedScene {
        val normalizedLight = lightDirection.normalize()
        val originX = width / 2.0
        val originY = height * 0.9

        // Transform all items to 2D screen space, applying culling and lighting
        val transformedItems = sceneGraph.items.mapNotNull { item ->
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

    override fun findItemAt(
        preparedScene: PreparedScene,
        x: Double,
        y: Double,
        order: HitOrder,
        touchRadius: Double
    ): RenderCommand? = HitTester.findItemAt(preparedScene, x, y, order, touchRadius)

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
}
