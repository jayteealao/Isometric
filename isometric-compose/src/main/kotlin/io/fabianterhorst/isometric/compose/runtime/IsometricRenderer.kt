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
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderCommand
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector
import kotlin.math.max
import kotlin.math.min

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
    private val enablePathCaching: Boolean = true,
    private val enableSpatialIndex: Boolean = true
) {
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
        val strokeColor: Color,
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

    // Spatial index for O(log n) hit testing
    private var spatialIndex: SpatialGrid? = null

    // Maps node IDs to nodes for O(1) hit test lookups
    private var nodeIdMap: Map<String, IsometricNode> = emptyMap()

    // TODO(KMP): Move to androidMain source set
    // Reusable paint objects for native rendering (Android-only)
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    /**
     * Render the node tree to a DrawScope with path caching
     */
    fun DrawScope.render(
        rootNode: GroupNode,
        context: RenderContext,
        strokeWidth: Float = 1f,
        drawStroke: Boolean = true
    ) {
        val width = size.width.toInt()
        val height = size.height.toInt()

        if (needsUpdate(rootNode, context, width, height)) {
            rebuildCache(rootNode, context, width, height)
        }

        // FAST PATH: Render from cached paths (minimal allocations!)
        if (enablePathCaching && cachedPaths != null) {
            val paths = cachedPaths!!
            // Use indexed loop to avoid iterator allocation
            for (i in paths.indices) {
                val cached = paths[i]
                drawPath(cached.path, cached.fillColor, style = Fill)

                if (drawStroke) {
                    drawPath(cached.path, cached.strokeColor, style = Stroke(width = strokeWidth))
                }
            }
        } else {
            // Fallback: Render without path caching
            cachedPreparedScene?.let { scene ->
                renderPreparedScene(scene, strokeWidth, drawStroke)
            }
        }
    }

    /**
     * Render using native Android canvas (2x faster, Android-only)
     *
     * **ANDROID-ONLY:** This function uses `android.graphics.Canvas` and will not work
     * on non-Android platforms. Use `render()` for cross-platform compatibility.
     *
     * @throws NoClassDefFoundError on non-Android platforms
     */
    fun DrawScope.renderNative(
        rootNode: GroupNode,
        context: RenderContext,
        strokeWidth: Float = 1f,
        drawStroke: Boolean = true
    ) {
        drawIntoCanvas { canvas ->
            val width = size.width.toInt()
            val height = size.height.toInt()

            if (needsUpdate(rootNode, context, width, height)) {
                rebuildCache(rootNode, context, width, height)
            }

            // Render using native canvas (faster than Compose on Android)
            cachedPreparedScene?.commands?.forEach { command ->
                val nativePath = command.toNativePath()

                // Fill
                fillPaint.color = command.color.toAndroidColor()
                canvas.nativeCanvas.drawPath(nativePath, fillPaint)

                // Stroke
                if (drawStroke) {
                    strokePaint.strokeWidth = strokeWidth
                    strokePaint.color = android.graphics.Color.argb(25, 0, 0, 0)
                    canvas.nativeCanvas.drawPath(nativePath, strokePaint)
                }
            }
        }
    }

    /**
     * Perform hit testing with optional spatial indexing
     */
    fun hitTest(
        rootNode: GroupNode,
        x: Double,
        y: Double,
        context: RenderContext
    ): IsometricNode? {
        if (cachedPreparedScene == null || !cacheValid) {
            return null
        }

        if (enableSpatialIndex && spatialIndex != null) {
            // Fast path: Use spatial index O(1) + O(k)
            val candidates = spatialIndex!!.query(x, y)

            // Test candidates in reverse order (front to back)
            for (commandId in candidates.asReversed()) {
                val hit = engine.findItemAt(
                    preparedScene = cachedPreparedScene!!,
                    x = x,
                    y = y,
                    reverseSort = true,
                    useRadius = true,
                    radius = 8.0
                )

                if (hit != null && hit.id == commandId) {
                    return findNodeByCommandId(commandId)
                }
            }
        } else {
            // Slow path: Linear search O(n)
            val hit = engine.findItemAt(
                preparedScene = cachedPreparedScene!!,
                x = x,
                y = y,
                reverseSort = true,
                useRadius = true,
                radius = 8.0
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
            engine.add(command.originalPath, command.color, command.originalShape, command.id)
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

        // Build path cache and spatial index
        if (enablePathCaching) {
            buildCacheAndIndex(cachedPreparedScene!!)
        }

        // Clear dirty flags
        rootNode.clearDirty()
        cacheValid = true
    }

    /**
     * Pre-convert all paths and build spatial index
     */
    private fun buildCacheAndIndex(scene: PreparedScene) {
        // Convert all render commands to cached paths
        cachedPaths = scene.commands.map { command ->
            CachedPath(
                path = command.toComposePath(),
                fillColor = command.color.toComposeColor(),
                strokeColor = Color.Black.copy(alpha = 0.1f),
                commandId = command.id
            )
        }

        // Build spatial index for fast hit testing
        if (enableSpatialIndex) {
            spatialIndex = SpatialGrid(
                width = cachedWidth.toDouble(),
                height = cachedHeight.toDouble(),
                cellSize = 100.0 // Tune based on average shape size
            )

            scene.commands.forEach { command ->
                val bounds = command.getBounds()
                if (bounds != null) {
                    spatialIndex!!.insert(command.id, bounds)
                }
            }
        }
    }

    /**
     * Render a prepared scene to DrawScope (without path caching)
     */
    private fun DrawScope.renderPreparedScene(
        scene: PreparedScene,
        strokeWidth: Float,
        drawStroke: Boolean
    ) {
        scene.commands.forEach { command ->
            val path = command.toComposePath()
            val color = command.color.toComposeColor()

            // Draw fill
            drawPath(path, color, style = Fill)

            // Optionally draw stroke
            if (drawStroke) {
                drawPath(
                    path,
                    Color.Black.copy(alpha = 0.1f),
                    style = Stroke(width = strokeWidth)
                )
            }
        }
    }

    /**
     * Find the node that produced a render command, using the cached ID map.
     * Command IDs have the format "nodeId_suffix", so we match by prefix.
     */
    private fun findNodeByCommandId(commandId: String): IsometricNode? {
        return nodeIdMap.entries.find { (nodeId, _) ->
            commandId.startsWith(nodeId)
        }?.value
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
     * Spatial grid for O(1) hit testing
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
            val minCol = max(0, (bounds.minX / cellSize).toInt())
            val maxCol = min(cols - 1, (bounds.maxX / cellSize).toInt())
            val minRow = max(0, (bounds.minY / cellSize).toInt())
            val maxRow = min(rows - 1, (bounds.maxY / cellSize).toInt())

            for (row in minRow..maxRow) {
                for (col in minCol..maxCol) {
                    grid[row][col].add(id)
                }
            }
        }

        fun query(x: Double, y: Double): List<String> {
            val col = (x / cellSize).toInt()
            val row = (y / cellSize).toInt()

            if (row < 0 || row >= rows || col < 0 || col >= cols) {
                return emptyList()
            }

            return grid[row][col]
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

    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = Double.MIN_VALUE
    var maxY = Double.MIN_VALUE

    points.forEach { point ->
        minX = min(minX, point.x)
        minY = min(minY, point.y)
        maxX = max(maxX, point.x)
        maxY = max(maxY, point.y)
    }

    return ShapeBounds(minX, minY, maxX, maxY)
}

/**
 * Extension: Convert IsoColor to Compose Color
 */
private fun io.fabianterhorst.isometric.IsoColor.toComposeColor(): Color {
    return Color(
        red = (r.toFloat() / 255f).coerceIn(0f, 1f),
        green = (g.toFloat() / 255f).coerceIn(0f, 1f),
        blue = (b.toFloat() / 255f).coerceIn(0f, 1f),
        alpha = (a.toFloat() / 255f).coerceIn(0f, 1f)
    )
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
