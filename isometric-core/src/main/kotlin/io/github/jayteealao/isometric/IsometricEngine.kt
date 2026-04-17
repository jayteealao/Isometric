package io.github.jayteealao.isometric

import io.github.jayteealao.isometric.shapes.FaceIdentifier
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
     * Current projection and lighting parameters for this engine instance.
     *
     * Updated atomically alongside [projectionVersion] whenever [angle] or [scale] changes.
     * GPU backends should re-upload their uniform buffer whenever [projectionVersion] ticks.
     *
     * @see ProjectionParams
     */
    @Volatile
    var projectionParams: ProjectionParams = projection.toProjectionParams(colorDifference, lightColor)
        private set

    private fun rebuildProjection() {
        projection = IsometricProjection(this.angle, this.scale, colorDifference, lightColor)
        // Read matrix coefficients from the newly built projection rather than recomputing
        // cos/sin — the values are already stored in IsometricProjection.transformation.
        projectionParams = projection.toProjectionParams(colorDifference, lightColor)
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
        ownerNodeId: String?,
        material: MaterialData?,
        uvCoords: FloatArray?,
        faceType: FaceIdentifier?,
    ) = sceneGraph.add(path, color, originalShape, id, ownerNodeId, material, uvCoords, faceType)

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
                ownerNodeId = transformedItem.item.ownerNodeId,
                baseColor = transformedItem.item.baseColor,
                material = transformedItem.item.material,
                uvCoords = transformedItem.item.uvCoords,
                faceType = transformedItem.item.faceType,
            )
        }

        return PreparedScene(commands, width, height, projectionParams, lightDirection)
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
     * Async projection with GPU-accelerated depth sorting.
     *
     * Extracts a scalar depth key per face (`Point.depth = x + y - 2z` averaged over vertices),
     * delegates to [SortingComputeBackend.sortByDepthKeys] for GPU radix sort, then reorders
     * the transformed items by the returned indices.
     */
    // Reusable buffers for projectSceneAsync to reduce per-frame allocations.
    // These are only accessed from the main thread (the async pipeline runs on
    // LaunchedEffect's coroutine context), so no synchronization is needed.
    private var cachedDepthKeys: FloatArray? = null
    private var cachedTransformedItems: ArrayList<DepthSorter.TransformedItem>? = null
    private var cachedSortedItems: ArrayList<DepthSorter.TransformedItem>? = null

    override suspend fun projectSceneAsync(
        width: Int,
        height: Int,
        renderOptions: RenderOptions,
        lightDirection: Vector,
        computeBackend: SortingComputeBackend,
    ): PreparedScene {
        val normalizedLight = lightDirection.normalize()
        val originX = width / 2.0
        val originY = height * 0.9

        // Transform all items to 2D screen space, reusing the list to avoid
        // allocating a new ArrayList every frame.
        val items = sceneGraph.items
        val transformedItems = cachedTransformedItems
            ?: ArrayList<DepthSorter.TransformedItem>(items.size).also { cachedTransformedItems = it }
        transformedItems.clear()
        transformedItems.ensureCapacity(items.size)
        for (item in items) {
            val transformed = projectAndCull(item, originX, originY, renderOptions, normalizedLight, width, height)
            if (transformed != null) {
                transformedItems.add(transformed)
            }
        }

        // Sort by depth if enabled (same guard as synchronous path)
        val sortedItems = if (renderOptions.enableDepthSorting && transformedItems.isNotEmpty()) {
            // Extract depth keys for GPU sort, reusing the FloatArray when the
            // count is unchanged (typical for animated scenes with a fixed model).
            val count = transformedItems.size
            var depthKeys = cachedDepthKeys
            if (depthKeys == null || depthKeys.size != count) {
                depthKeys = FloatArray(count)
                cachedDepthKeys = depthKeys
            }
            for (i in 0 until count) {
                val pts = transformedItems[i].item.path.points
                var sum = 0.0
                for (pt in pts) {
                    sum += pt.x + pt.y - 2 * pt.z
                }
                depthKeys[i] = (sum / pts.size).toFloat()
            }

            // GPU radix sort — returns back-to-front indices
            val sortedIndices = computeBackend.sortByDepthKeys(depthKeys)

            // Reorder by GPU-sorted indices, reusing the list (F2.3).
            val sorted = cachedSortedItems
                ?: ArrayList<DepthSorter.TransformedItem>(count).also { cachedSortedItems = it }
            sorted.clear()
            sorted.ensureCapacity(count)
            for (idx in sortedIndices) {
                sorted.add(transformedItems[idx])
            }
            sorted
        } else {
            transformedItems
        }

        // Convert to render commands. This list is stored inside PreparedScene
        // and read by the Canvas on a later frame, so it cannot be reused — the
        // previous scene still holds a reference to it.
        val commands = ArrayList<RenderCommand>(sortedItems.size)
        for (transformedItem in sortedItems) {
            commands.add(
                RenderCommand(
                    commandId = transformedItem.item.id,
                    points = transformedItem.transformedPoints,
                    color = transformedItem.litColor,
                    originalPath = transformedItem.item.path,
                    originalShape = transformedItem.item.originalShape,
                    ownerNodeId = transformedItem.item.ownerNodeId,
                    baseColor = transformedItem.item.baseColor,
                    material = transformedItem.item.material,
                    uvCoords = transformedItem.item.uvCoords,
                    faceType = transformedItem.item.faceType,
                )
            )
        }

        return PreparedScene(commands, width, height, projectionParams, lightDirection, isGpuSorted = true)
    }

    private fun projectAndCull(
        item: SceneGraph.SceneItem,
        originX: Double,
        originY: Double,
        renderOptions: RenderOptions,
        normalizedLight: Vector,
        width: Int,
        height: Int
    ): DepthSorter.TransformedItem? {
        // Project all vertices into a flat DoubleArray [x0, y0, x1, y1, ...]
        // to avoid per-vertex Point2D object allocation.
        val points = item.path.points
        val screenPoints = DoubleArray(points.size * 2)
        for (i in points.indices) {
            projection.translatePointInto(points[i], originX, originY, screenPoints, i * 2)
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
