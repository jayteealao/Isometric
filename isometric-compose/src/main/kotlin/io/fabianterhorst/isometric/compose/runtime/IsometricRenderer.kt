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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * - Off-thread scene preparation
 */
class IsometricRenderer(
    private val engine: IsometricEngine,
    private val enablePathCaching: Boolean = true,
    private val enableSpatialIndex: Boolean = true
) {
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

    // Spatial index for O(log n) hit testing
    private var spatialIndex: SpatialGrid? = null

    // Reusable paint objects for native rendering (Android)
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

        // Check if we need to regenerate the scene
        val needsUpdate = rootNode.isDirty ||
                !cacheValid ||
                width != cachedWidth ||
                height != cachedHeight

        if (needsUpdate) {
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

            val needsUpdate = rootNode.isDirty ||
                    !cacheValid ||
                    width != cachedWidth ||
                    height != cachedHeight

            if (needsUpdate) {
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
     * Prepare scene asynchronously (can be called from background thread)
     */
    suspend fun prepareSceneAsync(
        rootNode: GroupNode,
        context: RenderContext
    ) {
        withContext(Dispatchers.Default) {
            // Clear engine
            engine.clear()

            // Collect all render commands from the tree
            val commands = rootNode.render(context)

            // Add commands to engine
            commands.forEach { command ->
                engine.add(command.originalPath, command.color, command.originalShape)
            }

            // Prepare scene (CPU-intensive, runs off main thread)
            val scene = engine.prepare(
                width = context.width,
                height = context.height,
                options = context.renderOptions
            )

            // Cache it
            cachedPreparedScene = scene
            cachedWidth = context.width
            cachedHeight = context.height

            // Pre-convert paths and build spatial index
            if (enablePathCaching) {
                buildCacheAndIndex(scene)
            }

            cacheValid = true
        }

        // Mark root as clean (back on caller's context)
        rootNode.clearDirty()
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
                    return findNodeById(rootNode, commandId)
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
                return findNodeById(rootNode, hit.id)
            }
        }

        return null
    }

    /**
     * Invalidate cache (call when render options change)
     */
    fun invalidate() {
        cacheValid = false
        cachedPreparedScene = null
        cachedPaths = null
        spatialIndex = null
    }

    /**
     * Rebuild cache when scene changes
     */
    private fun rebuildCache(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int
    ) {
        // Clear engine
        engine.clear()

        // Collect all render commands from the tree
        val commands = rootNode.render(context)

        // Add commands to engine
        commands.forEach { command ->
            engine.add(command.originalPath, command.color, command.originalShape)
        }

        // Prepare scene (projects 3D -> 2D, sorts by depth)
        cachedPreparedScene = engine.prepare(
            width = width,
            height = height,
            options = context.renderOptions
        )

        cachedWidth = width
        cachedHeight = height

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
     * Find a node by its ID or render command ID
     */
    private fun findNodeById(node: IsometricNode, id: String): IsometricNode? {
        if (id.startsWith(node.nodeId)) {
            return node
        }

        for (child in node.children) {
            val found = findNodeById(child, id)
            if (found != null) return found
        }

        return null
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

        data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

        fun insert(id: String, bounds: Bounds) {
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
 * Extension: Get bounds of a render command
 */
private fun RenderCommand.getBounds(): IsometricRenderer.SpatialGrid.Bounds? {
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

    return IsometricRenderer.SpatialGrid.Bounds(minX, minY, maxX, maxY)
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
 */
private fun io.fabianterhorst.isometric.IsoColor.toAndroidColor(): Int {
    return android.graphics.Color.argb(
        a.toInt().coerceIn(0, 255),
        r.toInt().coerceIn(0, 255),
        g.toInt().coerceIn(0, 255),
        b.toInt().coerceIn(0, 255)
    )
}
