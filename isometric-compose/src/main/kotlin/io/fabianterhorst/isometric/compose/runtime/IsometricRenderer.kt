package io.fabianterhorst.isometric.compose.runtime

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.HitOrder
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderCommand
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector
import io.fabianterhorst.isometric.compose.toComposeColor
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Hooks for benchmark instrumentation of the rendering pipeline.
 *
 * All callbacks fire synchronously on the main thread within the Compose draw pass.
 * Implementations should be lightweight (e.g., recording timestamps) to minimize
 * measurement overhead.
 */
interface RenderBenchmarkHooks {
    fun onPrepareStart()
    fun onPrepareEnd()
    fun onDrawStart()
    fun onDrawEnd()
    fun onCacheHit()
    fun onCacheMiss()
}

/**
 * Renderer that converts the isometric node tree to visual output
 * using the IsometricEngine for projection and depth sorting.
 *
 * Includes multiple performance optimizations:
 * - PreparedScene caching
 * - Path object caching
 * - Spatial indexing for hit testing
 * - Native canvas rendering (Android)
 *
 * **Platform notes:**
 * - [DrawScope.render] uses Compose `DrawScope` APIs and is cross-platform compatible.
 * - [DrawScope.renderNative] uses `android.graphics.Canvas` directly and is Android-only.
 *   It provides ~2x faster rendering but will throw [NoClassDefFoundError] on non-Android platforms.
 *
 * TODO(KMP): When converting to Kotlin Multiplatform, extract renderNative() and its
 *            Android-specific helpers (fillPaint, strokePaint, toNativePath, toAndroidColor)
 *            into an androidMain source set. The render() method is already cross-platform.
 */
class IsometricRenderer(
    private val engine: IsometricEngine,
    private val enablePathCaching: Boolean = false,
    private val enableSpatialIndex: Boolean = true,
    private val spatialIndexCellSize: Double = DEFAULT_SPATIAL_INDEX_CELL_SIZE
) {
    init {
        require(spatialIndexCellSize.isFinite() && spatialIndexCellSize > 0.0) {
            "spatialIndexCellSize must be positive and finite, got $spatialIndexCellSize"
        }
    }

    companion object {
        const val DEFAULT_SPATIAL_INDEX_CELL_SIZE: Double = 100.0
        private const val HIT_TEST_RADIUS_PX: Double = 8.0
    }
    /**
     * Optional benchmark hooks for instrumentation. Set via [IsometricScene]'s SideEffect
     * from [LocalBenchmarkHooks]. Null in production (zero overhead).
     */
    var benchmarkHooks: RenderBenchmarkHooks? = null

    /**
     * When true, forces a cache rebuild every frame (disables PreparedScene caching).
     * Used by benchmarks to measure prepare() cost independently of caching.
     */
    var forceRebuild: Boolean = false

    /** Bundles inputs to engine.prepare() for cache invalidation. */
    private data class PrepareInputs(
        val renderOptions: RenderOptions,
        val lightDirection: Vector
    )

    /**
     * Cached path with pre-converted objects to avoid reallocations
     */
    private data class CachedPath(
        val path: Path,
        val fillColor: Color,
        val commandId: String
    )

    // Triple-layer cache for maximum performance
    private var cachedPreparedScene: PreparedScene? = null
    private var cachedPaths: List<CachedPath>? = null
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var cacheValid: Boolean = false
    private var cachedPrepareInputs: PrepareInputs? = null

    internal val currentPreparedScene: PreparedScene? get() = cachedPreparedScene

    /**
     * Ensure the prepared scene is up-to-date, rebuilding the cache if needed.
     * Returns null only when [width] or [height] are non-positive (no valid viewport).
     */
    private fun ensurePreparedScene(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int
    ): PreparedScene? {
        if (width <= 0 || height <= 0) return null
        if (forceRebuild) invalidate()
        if (needsUpdate(rootNode, context, width, height)) {
            benchmarkHooks?.onCacheMiss()
            benchmarkHooks?.onPrepareStart()
            rebuildCache(rootNode, context, width, height)
            benchmarkHooks?.onPrepareEnd()
        } else {
            benchmarkHooks?.onCacheHit()
        }
        return cachedPreparedScene
    }

    // Spatial index for O(k) hit testing
    private var spatialIndex: SpatialGrid? = null

    // Maps command IDs to RenderCommands for O(1) lookup when building filtered scenes
    private var commandIdMap: Map<String, RenderCommand> = emptyMap()

    // Maps command IDs to stable scene order for preserving hit-test precedence
    private var commandOrderMap: Map<String, Int> = emptyMap()

    // Maps command IDs directly to the IsometricNode that produced them for O(1) hit-test resolution
    private var commandToNodeMap: Map<String, IsometricNode> = emptyMap()

    // Maps node IDs to nodes for O(1) command-to-node resolution
    private var nodeIdMap: Map<String, IsometricNode> = emptyMap()

    // TODO(KMP): Move to androidMain source set
    // Reusable paint objects for native rendering (Android-only)
    // Lazy to avoid UnsatisfiedLinkError when constructing on non-Android JVM (e.g. unit tests)
    private val fillPaint by lazy {
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    private val strokePaint by lazy {
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
    }

    /**
     * Render the node tree to a DrawScope with path caching
     */
    fun DrawScope.render(
        rootNode: GroupNode,
        context: RenderContext,
        strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke()
    ) {
        val width = size.width.toInt()
        val height = size.height.toInt()

        ensurePreparedScene(rootNode, context, width, height)
            ?: return

        benchmarkHooks?.onDrawStart()

        // FAST PATH: Render from cached paths (minimal allocations!)
        if (enablePathCaching && cachedPaths != null) {
            val paths = cachedPaths!!
            // Use indexed loop to avoid iterator allocation
            for (i in paths.indices) {
                val cached = paths[i]
                when (strokeStyle) {
                    is StrokeStyle.FillOnly -> {
                        drawPath(cached.path, cached.fillColor, style = Fill)
                    }
                    is StrokeStyle.Stroke -> {
                        drawPath(
                            cached.path,
                            strokeStyle.color.toComposeColor(),
                            style = Stroke(width = strokeStyle.width)
                        )
                    }
                    is StrokeStyle.FillAndStroke -> {
                        drawPath(cached.path, cached.fillColor, style = Fill)
                        drawPath(
                            cached.path,
                            strokeStyle.color.toComposeColor(),
                            style = Stroke(width = strokeStyle.width)
                        )
                    }
                }
            }
        } else {
            // Fallback: Render without path caching
            cachedPreparedScene?.let { scene ->
                renderPreparedScene(scene, strokeStyle)
            }
        }

        benchmarkHooks?.onDrawEnd()
    }

    /**
     * Render using native Android canvas (2x faster, Android-only)
     *
     * **ANDROID-ONLY:** This function uses `android.graphics.Canvas` and will not work
     * on non-Android platforms. Use `render()` for cross-platform compatibility.
     *
     * Note: when benchmarks run with prepared-scene caching disabled (`forceRebuild=true`),
     * this path still rebuilds the prepared scene every frame and converts each command to a
     * native `android.graphics.Path` during draw. In that mode the benchmark measures native
     * draw-path overhead, not cross-frame native-path reuse.
     *
     * @throws NoClassDefFoundError on non-Android platforms
     */
    fun DrawScope.renderNative(
        rootNode: GroupNode,
        context: RenderContext,
        strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke()
    ) {
        drawIntoCanvas { canvas ->
            val width = size.width.toInt()
            val height = size.height.toInt()

            ensurePreparedScene(rootNode, context, width, height)
                ?: return@drawIntoCanvas

            benchmarkHooks?.onDrawStart()

            // Render using native canvas (faster than Compose on Android)
            cachedPreparedScene?.commands?.forEach { command ->
                val nativePath = command.toNativePath()

                when (strokeStyle) {
                    is StrokeStyle.FillOnly -> {
                        fillPaint.color = command.color.toAndroidColor()
                        canvas.nativeCanvas.drawPath(nativePath, fillPaint)
                    }
                    is StrokeStyle.Stroke -> {
                        strokePaint.strokeWidth = strokeStyle.width
                        strokePaint.color = strokeStyle.color.toAndroidColor()
                        canvas.nativeCanvas.drawPath(nativePath, strokePaint)
                    }
                    is StrokeStyle.FillAndStroke -> {
                        fillPaint.color = command.color.toAndroidColor()
                        canvas.nativeCanvas.drawPath(nativePath, fillPaint)
                        strokePaint.strokeWidth = strokeStyle.width
                        strokePaint.color = strokeStyle.color.toAndroidColor()
                        canvas.nativeCanvas.drawPath(nativePath, strokePaint)
                    }
                }
            }

            benchmarkHooks?.onDrawEnd()
        }
    }

    /**
     * Perform hit testing with optional spatial indexing.
     *
     * Self-sufficient: rebuilds the prepared scene if the cache is stale,
     * so callers do not need to call [DrawScope.render] first.
     *
     * @param rootNode the root of the node tree
     * @param x screen-space x coordinate
     * @param y screen-space y coordinate
     * @param context render context (options, light direction, transforms)
     * @param width viewport width in pixels
     * @param height viewport height in pixels
     * @return the frontmost [IsometricNode] at (x, y), or null if nothing is hit
     *         or the viewport dimensions are non-positive
     */
    fun hitTest(
        rootNode: GroupNode,
        x: Double,
        y: Double,
        context: RenderContext,
        width: Int,
        height: Int
    ): IsometricNode? {
        ensurePreparedScene(rootNode, context, width, height)
            ?: return null

        if (enableSpatialIndex && spatialIndex != null) {
            // Fast path: Use spatial index for O(k) hit testing
            val candidateIds = spatialIndex!!.query(x, y, HIT_TEST_RADIUS_PX)

            if (candidateIds.isNotEmpty()) {
                // Resolve candidate IDs to commands, preserving the original scene order so
                // engine.findItemAt(order = FRONT_TO_BACK) still returns the frontmost command.
                val candidateCommands = candidateIds
                    .mapNotNull { id -> commandIdMap[id] }
                    .sortedBy { command -> commandOrderMap[command.id] ?: Int.MAX_VALUE }

                if (candidateCommands.isNotEmpty()) {
                    val filteredScene = PreparedScene(
                        commands = candidateCommands,
                        viewportWidth = cachedPreparedScene!!.viewportWidth,
                        viewportHeight = cachedPreparedScene!!.viewportHeight
                    )

                    val hit = engine.findItemAt(
                        preparedScene = filteredScene,
                        x = x,
                        y = y,
                        order = HitOrder.FRONT_TO_BACK,
                        touchRadius = HIT_TEST_RADIUS_PX
                    )

                    if (hit != null) {
                        return findNodeByCommandId(hit.id)
                    }
                }
            }
        } else {
            // Slow path: Linear search O(n)
            val hit = engine.findItemAt(
                preparedScene = cachedPreparedScene!!,
                x = x,
                y = y,
                order = HitOrder.FRONT_TO_BACK,
                touchRadius = HIT_TEST_RADIUS_PX
            )

            if (hit != null) {
                return findNodeByCommandId(hit.id)
            }
        }

        return null
    }

    /**
     * Check whether the cache is stale and needs a rebuild.
     * Extracted so both render paths share one check and tests can verify it.
     *
     * Cache reuse is allowed only when all prepare inputs are stable:
     * - the node tree is clean
     * - viewport width/height are unchanged
     * - render options are unchanged
     * - light direction is unchanged
     *
     * External redraw requests alone do not invalidate the prepared-scene cache.
     */
    internal fun needsUpdate(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int
    ): Boolean {
        val currentInputs = PrepareInputs(context.renderOptions, context.lightDirection)
        return rootNode.isDirty ||
                !cacheValid ||
                width != cachedWidth ||
                height != cachedHeight ||
                currentInputs != cachedPrepareInputs
    }

    /**
     * Invalidate cache (call when render options change)
     */
    fun invalidate() {
        cacheValid = false
        cachedPreparedScene = null
        cachedPaths = null
        cachedPrepareInputs = null
        spatialIndex = null
        nodeIdMap = emptyMap()
        commandIdMap = emptyMap()
        commandOrderMap = emptyMap()
        commandToNodeMap = emptyMap()
    }

    /**
     * Rebuild cache when scene changes
     */
    internal fun rebuildCache(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int
    ) {
        // Clear engine
        engine.clear()

        // Collect all render commands from the tree
        val commands = rootNode.render(context)

        // Add commands to engine, preserving node-level IDs for hit testing
        commands.forEach { command ->
            engine.add(
                path = command.originalPath,
                color = command.color,
                originalShape = command.originalShape,
                id = command.id,
                ownerNodeId = command.ownerNodeId
            )
        }

        // Build node ID lookup map for hit testing
        nodeIdMap = buildNodeIdMap(rootNode)

        // Prepare scene (projects 3D -> 2D, sorts by depth)
        cachedPreparedScene = engine.prepare(
            width = width,
            height = height,
            options = context.renderOptions,
            lightDirection = context.lightDirection
        )

        cachedWidth = width
        cachedHeight = height
        cachedPrepareInputs = PrepareInputs(context.renderOptions, context.lightDirection)

        // Build command maps used by both slow and fast hit-test paths.
        buildCommandMaps(cachedPreparedScene!!)

        // Build path cache (rendering optimization)
        if (enablePathCaching) {
            buildPathCache(cachedPreparedScene!!)
        }

        // Build spatial index (hit-testing optimization) — independent of path caching
        if (enableSpatialIndex) {
            buildSpatialIndex(cachedPreparedScene!!)
        }

        // Clear dirty flags
        rootNode.clearDirty()
        cacheValid = true
    }

    /**
     * Pre-convert all render commands to Compose Path objects for rendering.
     *
     * Note: when benchmarks run with prepared-scene caching disabled (`forceRebuild=true`),
     * this cache is rebuilt every frame. In that mode path caching measures per-frame
     * path pre-conversion plus draw reuse, not long-lived cross-frame path reuse.
     */
    private fun buildPathCache(scene: PreparedScene) {
        cachedPaths = scene.commands.map { command ->
            CachedPath(
                path = command.toComposePath(),
                fillColor = command.color.toComposeColor(),
                commandId = command.id
            )
        }
    }

    /**
     * Build command maps used by both fast and slow hit-test paths.
     */
    private fun buildCommandMaps(scene: PreparedScene) {
        val cmdMap = HashMap<String, RenderCommand>(scene.commands.size)
        val orderMap = HashMap<String, Int>(scene.commands.size)
        val cmdToNode = HashMap<String, IsometricNode>(scene.commands.size)

        for ((index, command) in scene.commands.withIndex()) {
            cmdMap[command.id] = command
            orderMap[command.id] = index
            command.ownerNodeId
                ?.let { nodeIdMap[it] }
                ?.let { node -> cmdToNode[command.id] = node }
        }
        commandIdMap = cmdMap
        commandOrderMap = orderMap
        commandToNodeMap = cmdToNode
    }

    /**
     * Build spatial grid for O(k) hit testing.
     */
    private fun buildSpatialIndex(scene: PreparedScene) {
        // Build spatial grid
        spatialIndex = SpatialGrid(
            width = cachedWidth.toDouble(),
            height = cachedHeight.toDouble(),
            cellSize = spatialIndexCellSize
        )

        for (command in scene.commands) {
            val bounds = command.getBounds()
            if (bounds != null) {
                spatialIndex!!.insert(command.id, bounds)
            }
        }
    }

    /**
     * Render a prepared scene to DrawScope (without path caching)
     */
    private fun DrawScope.renderPreparedScene(
        scene: PreparedScene,
        strokeStyle: StrokeStyle
    ) {
        scene.commands.forEach { command ->
            val path = command.toComposePath()
            val color = command.color.toComposeColor()

            when (strokeStyle) {
                is StrokeStyle.FillOnly -> {
                    drawPath(path, color, style = Fill)
                }
                is StrokeStyle.Stroke -> {
                    drawPath(
                        path,
                        strokeStyle.color.toComposeColor(),
                        style = Stroke(width = strokeStyle.width)
                    )
                }
                is StrokeStyle.FillAndStroke -> {
                    drawPath(path, color, style = Fill)
                    drawPath(
                        path,
                        strokeStyle.color.toComposeColor(),
                        style = Stroke(width = strokeStyle.width)
                    )
                }
            }
        }
    }

    /**
     * Find the node that produced a render command via O(1) map lookup.
     */
    private fun findNodeByCommandId(commandId: String): IsometricNode? {
        return commandToNodeMap[commandId]
    }

    /**
     * Build a flat map from nodeId to IsometricNode by walking the tree.
     */
    private fun buildNodeIdMap(root: IsometricNode): Map<String, IsometricNode> {
        val map = mutableMapOf<String, IsometricNode>()
        fun visit(node: IsometricNode) {
            map[node.nodeId] = node
            for (child in node.childrenSnapshot) {
                visit(child)
            }
        }
        visit(root)
        return map
    }

    /**
     * Spatial grid for O(1) cell lookup, yielding k candidates for point-in-polygon testing.
     */
    private class SpatialGrid(
        private val width: Double,
        private val height: Double,
        private val cellSize: Double
    ) {
        private val cols = (width / cellSize).toInt() + 1
        private val rows = (height / cellSize).toInt() + 1
        private val grid = Array(rows) { Array(cols) { mutableListOf<String>() } }

        fun insert(id: String, bounds: ShapeBounds) {
            val minCol = max(0, floor(bounds.minX / cellSize).toInt())
            val maxCol = min(cols - 1, floor(bounds.maxX / cellSize).toInt())
            val minRow = max(0, floor(bounds.minY / cellSize).toInt())
            val maxRow = min(rows - 1, floor(bounds.maxY / cellSize).toInt())

            if (minCol > maxCol || minRow > maxRow) {
                return
            }

            for (row in minRow..maxRow) {
                for (col in minCol..maxCol) {
                    grid[row][col].add(id)
                }
            }
        }

        fun query(x: Double, y: Double, radius: Double = 0.0): List<String> {
            val minCol = max(0, floor((x - radius) / cellSize).toInt())
            val maxCol = min(cols - 1, floor((x + radius) / cellSize).toInt())
            val minRow = max(0, floor((y - radius) / cellSize).toInt())
            val maxRow = min(rows - 1, floor((y + radius) / cellSize).toInt())

            if (maxRow < 0 || maxCol < 0 || minRow >= rows || minCol >= cols) {
                return emptyList()
            }

            val ids = LinkedHashSet<String>()
            for (row in minRow..maxRow) {
                for (col in minCol..maxCol) {
                    ids.addAll(grid[row][col])
                }
            }

            return ids.toList()
        }
    }
}

/**
 * Extension: Convert RenderCommand to Compose Path
 */
private fun RenderCommand.toComposePath(): Path {
    return Path().apply {
        if (points.isEmpty()) return@apply

        moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        for (i in 1 until points.size) {
            lineTo(points[i].x.toFloat(), points[i].y.toFloat())
        }
        close()
    }
}

/**
 * Extension: Convert RenderCommand to Android native Path
 * TODO(KMP): Move to androidMain source set
 */
private fun RenderCommand.toNativePath(): android.graphics.Path {
    return android.graphics.Path().apply {
        if (points.isEmpty()) return@apply

        moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        for (i in 1 until points.size) {
            lineTo(points[i].x.toFloat(), points[i].y.toFloat())
        }
        close()
    }
}

/**
 * Axis-aligned bounding box for spatial indexing
 */
private data class ShapeBounds(
    val minX: Double, val minY: Double,
    val maxX: Double, val maxY: Double
)

/**
 * Extension: Get bounds of a render command
 */
private fun RenderCommand.getBounds(): ShapeBounds? {
    if (points.isEmpty()) return null

    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY

    points.forEach { point ->
        minX = min(minX, point.x)
        minY = min(minY, point.y)
        maxX = max(maxX, point.x)
        maxY = max(maxY, point.y)
    }

    if (minX.isNaN() || minY.isNaN() || maxX.isNaN() || maxY.isNaN()) {
        return null
    }

    return ShapeBounds(minX, minY, maxX, maxY)
}

/**
 * Extension: Convert IsoColor to Android Color
 * TODO(KMP): Move to androidMain source set
 */
private fun io.fabianterhorst.isometric.IsoColor.toAndroidColor(): Int {
    return android.graphics.Color.argb(
        a.toInt().coerceIn(0, 255),
        r.toInt().coerceIn(0, 255),
        g.toInt().coerceIn(0, 255),
        b.toInt().coerceIn(0, 255)
    )
}
