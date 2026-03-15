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
 * - [HitTester] — hit testing with convex hull and touch radius
 */
class IsometricEngine(
    private val angle: Double = PI / 6,  // 30 degrees
    private val scale: Double = 70.0,
    private val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION,
    private val colorDifference: Double = 0.20,
    private val lightColor: IsoColor = IsoColor.WHITE
) : SceneProjector {
    companion object {
        /** Default light direction used when none is specified. */
        val DEFAULT_LIGHT_DIRECTION: Vector = SceneProjector.DEFAULT_LIGHT_DIRECTION
    }

    init {
        require(angle.isFinite()) { "angle must be finite, got $angle" }
        require(scale.isFinite() && scale > 0.0) { "scale must be positive and finite, got $scale" }
        require(colorDifference.isFinite() && colorDifference >= 0.0) {
            "colorDifference must be non-negative and finite, got $colorDifference"
        }
        require(lightDirection.magnitude() > 0.0) { "lightDirection must be non-zero" }
    }

    private val sceneGraph = SceneGraph()
    private val projection = IsometricProjection(angle, scale, colorDifference, lightColor)
    private val defaultLightDirection: Vector = lightDirection.normalize()

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
