package io.fabianterhorst.isometric

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Core isometric rendering engine.
 * Platform-agnostic - outputs PreparedScene that can be rendered by any platform.
 */
class IsometricEngine(
    private val angle: Double = PI / 6,  // 30 degrees
    private val scale: Double = 70.0,
    private val lightPosition: Vector = Vector(2.0, -1.0, 3.0),
    private val colorDifference: Double = 0.20,
    private val lightColor: IsoColor = IsoColor.WHITE
) {
    private val transformation: Array<DoubleArray>
    private val lightAngle: Vector

    private data class SceneItem(
        val path: Path,
        val baseColor: IsoColor,
        val originalShape: Shape?,
        val id: String  // Stable ID for hit testing
    )

    private val items = mutableListOf<SceneItem>()
    private var nextId = 0

    // Cache state (zero-allocation checking)
    private var cachedScene: PreparedScene? = null
    private var cachedVersion: Int = -1
    private var cachedWidth: Int = -1
    private var cachedHeight: Int = -1
    private var cachedOptions: RenderOptions? = null

    init {
        transformation = arrayOf(
            doubleArrayOf(
                scale * cos(angle),
                scale * sin(angle)
            ),
            doubleArrayOf(
                scale * cos(PI - angle),
                scale * sin(PI - angle)
            )
        )
        lightAngle = lightPosition.normalize()
    }

    /**
     * Add a shape to the scene
     */
    fun add(shape: Shape, color: IsoColor) {
        val paths = shape.orderedPaths()
        for (path in paths) {
            add(path, color, shape)
        }
    }

    /**
     * Add a path to the scene
     */
    fun add(path: Path, color: IsoColor, originalShape: Shape? = null) {
        items.add(SceneItem(path, color, originalShape, "item_${nextId++}"))
    }

    /**
     * Clear all items from the scene
     */
    fun clear() {
        items.clear()
        nextId = 0
    }

    /**
     * Prepare the scene for rendering at the given viewport size
     *
     * @param sceneVersion Cache invalidation key that should increment when scene content changes
     * @param width Viewport width in pixels
     * @param height Viewport height in pixels
     * @param options Rendering configuration options
     * @return Platform-agnostic PreparedScene with sorted render commands
     */
    fun prepare(
        sceneVersion: Int,
        width: Int,
        height: Int,
        options: RenderOptions = RenderOptions.Default
    ): PreparedScene {
        // For now, just delegate to internal implementation
        // Cache logic will be added in next task
        return prepareSceneInternal(width, height, options)
    }

    /**
     * Internal implementation that performs scene transformation
     */
    private fun prepareSceneInternal(
        width: Int,
        height: Int,
        options: RenderOptions
    ): PreparedScene {
        val originX = width / 2.0
        val originY = height * 0.9

        val commands = mutableListOf<RenderCommand>()

        // Transform all items to 2D screen space
        val transformedItems = items.mapNotNull { item ->
            val transformedPoints = item.path.points.map { point ->
                translatePoint(point, originX, originY)
            }

            // Apply culling if enabled
            if (options.enableBackfaceCulling && cullPath(transformedPoints)) {
                return@mapNotNull null
            }

            // Apply bounds checking if enabled
            if (options.enableBoundsChecking && !itemInDrawingBounds(transformedPoints, width, height)) {
                return@mapNotNull null
            }

            // Calculate lighting-adjusted color
            val litColor = transformColor(item.path, item.baseColor)

            TransformedItem(item, transformedPoints, litColor)
        }

        // Sort by depth if enabled
        val sortedItems = if (options.enableDepthSorting) {
            sortPaths(transformedItems)
        } else {
            transformedItems
        }

        // Convert to render commands
        for (transformedItem in sortedItems) {
            commands.add(
                RenderCommand(
                    id = transformedItem.item.id,
                    points = transformedItem.transformedPoints,
                    color = transformedItem.litColor,
                    originalPath = transformedItem.item.path,
                    originalShape = transformedItem.item.originalShape
                )
            )
        }

        return PreparedScene(commands, width, height)
    }

    /**
     * Find the item at a given screen position (for hit testing)
     *
     * @param x Screen x coordinate
     * @param y Screen y coordinate
     * @param reverseSort If true, search front-to-back (for click handling)
     * @param useRadius If true, consider points within radius of edges
     * @param radius Touch radius in pixels
     * @return The RenderCommand at this position, or null
     */
    fun findItemAt(
        preparedScene: PreparedScene,
        x: Double,
        y: Double,
        reverseSort: Boolean = true,
        useRadius: Boolean = false,
        radius: Double = 8.0
    ): RenderCommand? {
        val commandsList = if (reverseSort) {
            preparedScene.commands.reversed()
        } else {
            preparedScene.commands
        }

        for (command in commandsList) {
            // Build convex hull of command points
            val hull = buildConvexHull(command.points)

            // Test if point is inside or close to polygon
            val isInside = if (useRadius) {
                IntersectionUtils.isPointCloseToPoly(
                    hull.map { Point(it.x, it.y, 0.0) },
                    x,
                    y,
                    radius
                ) || IntersectionUtils.isPointInPoly(
                    hull.map { Point(it.x, it.y, 0.0) },
                    x,
                    y
                )
            } else {
                IntersectionUtils.isPointInPoly(
                    hull.map { Point(it.x, it.y, 0.0) },
                    x,
                    y
                )
            }

            if (isInside) {
                return command
            }
        }

        return null
    }

    /**
     * X rides along the angle extended from the origin
     * Y rides perpendicular to this angle (in isometric view: PI - angle)
     * Z affects the y coordinate of the drawn point
     */
    private fun translatePoint(point: Point, originX: Double, originY: Double): Point2D {
        return Point2D(
            originX + point.x * transformation[0][0] + point.y * transformation[1][0],
            originY - point.x * transformation[0][1] - point.y * transformation[1][1] - (point.z * scale)
        )
    }

    /**
     * Apply lighting to a color based on the path's surface normal
     */
    private fun transformColor(path: Path, color: IsoColor): IsoColor {
        if (path.points.size < 3) return color

        val p1 = path.points[1]
        val p2 = path.points[0]
        val i = p2.x - p1.x
        val j = p2.y - p1.y
        val k = p2.z - p1.z

        val p3 = path.points[2]
        val p4 = path.points[1]
        val i2 = p4.x - p3.x
        val j2 = p4.y - p3.y
        val k2 = p4.z - p3.z

        // Cross product to get normal
        val i3 = j * k2 - j2 * k
        val j3 = -1 * (i * k2 - i2 * k)
        val k3 = i * j2 - i2 * j

        // Normalize
        val magnitude = sqrt(i3 * i3 + j3 * j3 + k3 * k3)
        val normalI = if (magnitude == 0.0) 0.0 else i3 / magnitude
        val normalJ = if (magnitude == 0.0) 0.0 else j3 / magnitude
        val normalK = if (magnitude == 0.0) 0.0 else k3 / magnitude

        // Dot product with light angle
        val brightness = normalI * lightAngle.i + normalJ * lightAngle.j + normalK * lightAngle.k

        return color.lighten(brightness * colorDifference, lightColor)
    }

    /**
     * Back-face culling test
     * Returns true if the path should be culled (is facing away)
     */
    private fun cullPath(transformedPoints: List<Point2D>): Boolean {
        if (transformedPoints.size < 3) return false

        val a = transformedPoints[0].x * transformedPoints[1].y
        val b = transformedPoints[1].x * transformedPoints[2].y
        val c = transformedPoints[2].x * transformedPoints[0].y

        val d = transformedPoints[1].x * transformedPoints[0].y
        val e = transformedPoints[2].x * transformedPoints[1].y
        val f = transformedPoints[0].x * transformedPoints[2].y

        val z = a + b + c - d - e - f
        return z > 0
    }

    /**
     * Check if any point of the item is within the drawing bounds
     */
    private fun itemInDrawingBounds(
        transformedPoints: List<Point2D>,
        width: Int,
        height: Int
    ): Boolean {
        for (point in transformedPoints) {
            if (point.x >= 0 && point.x <= width && point.y >= 0 && point.y <= height) {
                return true
            }
        }
        return false
    }

    /**
     * Sort paths using intersection-based depth sorting algorithm
     */
    private fun sortPaths(items: List<TransformedItem>): List<TransformedItem> {
        val sortedItems = mutableListOf<TransformedItem>()
        val observer = Point(-10.0, -10.0, 20.0)
        val length = items.size

        // Build dependency graph: drawBefore[i] = list of items that must be drawn before item i
        val drawBefore = List(length) { mutableListOf<Int>() }

        for (i in 0 until length) {
            val itemA = items[i]
            for (j in 0 until i) {
                val itemB = items[j]

                // Check if 2D projections intersect
                if (IntersectionUtils.hasIntersection(
                        itemA.transformedPoints.map { Point(it.x, it.y, 0.0) },
                        itemB.transformedPoints.map { Point(it.x, it.y, 0.0) }
                    )
                ) {
                    // Use 3D depth comparison
                    val cmpPath = itemA.item.path.closerThan(itemB.item.path, observer)
                    if (cmpPath < 0) {
                        drawBefore[i].add(j)
                    } else if (cmpPath > 0) {
                        drawBefore[j].add(i)
                    }
                }
            }
        }

        // Topological sort
        val drawn = BooleanArray(length) { false }
        var drawThisTurn = true

        while (drawThisTurn) {
            drawThisTurn = false
            for (i in 0 until length) {
                if (!drawn[i]) {
                    // Check if all dependencies are drawn
                    val canDraw = drawBefore[i].all { drawn[it] }
                    if (canDraw) {
                        sortedItems.add(items[i])
                        drawn[i] = true
                        drawThisTurn = true
                    }
                }
            }
        }

        // Add any remaining items (circular dependencies)
        for (i in 0 until length) {
            if (!drawn[i]) {
                sortedItems.add(items[i])
            }
        }

        return sortedItems
    }

    /**
     * Build a convex hull for hit testing
     * Returns the extreme points (top, bottom, left, right) plus any edge points
     */
    private fun buildConvexHull(points: List<Point2D>): List<Point2D> {
        if (points.isEmpty()) return emptyList()

        var top: Point2D? = null
        var bottom: Point2D? = null
        var left: Point2D? = null
        var right: Point2D? = null

        // Find extreme points
        for (point in points) {
            if (top == null || point.y > top.y) {
                top = point
            }
            if (bottom == null || point.y < bottom.y) {
                bottom = point
            }
            if (left == null || point.x < left.x) {
                left = point
            }
            if (right == null || point.x > right.x) {
                right = point
            }
        }

        val hull = mutableListOf(left!!, top!!, right!!, bottom!!)

        // Add points on the edges
        for (point in points) {
            if (point.x == left.x && point != left) hull.add(point)
            if (point.x == right.x && point != right) hull.add(point)
            if (point.y == top.y && point != top) hull.add(point)
            if (point.y == bottom.y && point != bottom) hull.add(point)
        }

        return hull.distinct()
    }

    private data class TransformedItem(
        val item: SceneItem,
        val transformedPoints: List<Point2D>,
        val litColor: IsoColor
    )
}
