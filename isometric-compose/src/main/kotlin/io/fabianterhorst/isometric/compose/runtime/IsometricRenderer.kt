package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import io.fabianterhorst.isometric.HitOrder
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderCommand
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.SceneProjector
import io.fabianterhorst.isometric.Vector
import io.fabianterhorst.isometric.compose.toComposeColor
import io.fabianterhorst.isometric.compose.toComposePath

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
 * using a [SceneProjector] for projection and depth sorting.
 *
 * Coordinates between focused collaborators:
 * - [SpatialGrid] — O(k) hit-test candidate lookup
 * - [NativeSceneRenderer] — Android-native rendering backend
 * - Shared extensions in [io.fabianterhorst.isometric.compose.RenderExtensions]
 *
 * **Platform notes:**
 * - [DrawScope.render] uses Compose `DrawScope` APIs and is cross-platform compatible.
 * - [DrawScope.renderNative] uses `android.graphics.Canvas` directly and is Android-only.
 *   It provides ~2x faster rendering but will throw [NoClassDefFoundError] on non-Android platforms.
 */
class IsometricRenderer(
    private val engine: SceneProjector,
    private val enablePathCaching: Boolean = false,
    private val enableSpatialIndex: Boolean = true,
    private val spatialIndexCellSize: Double = DEFAULT_SPATIAL_INDEX_CELL_SIZE
) : java.io.Closeable {
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
     * Optional callback invoked when a render command fails to draw.
     * Receives the command ID and the exception. The command is skipped.
     * Null in production (errors are silently skipped).
     */
    var onRenderError: ((commandId: String, error: Throwable) -> Unit)? = null

    /**
     * Optional benchmark hooks for instrumentation. Set via [IsometricScene]'s SideEffect
     * from [LocalBenchmarkHooks]. Null in production (zero overhead).
     */
    var benchmarkHooks: RenderBenchmarkHooks? = null

    /**
     * When true, forces a cache rebuild every frame (disables PreparedScene caching).
     * Used by benchmarks to measure projectScene() cost independently of caching.
     */
    var forceRebuild: Boolean = false

    /** Bundles inputs to engine.projectScene() for cache invalidation. */
    private data class PrepareInputs(
        val renderOptions: RenderOptions,
        val lightDirection: Vector
    )

    /**
     * Cached path with pre-converted objects to avoid reallocations.
     */
    private data class CachedPath(
        val path: Path,
        val fillColor: Color,
        val commandId: String
    )

    // Closed flag — once true, the renderer must not be used
    private var closed = false

    // Cache state
    private var cachedPreparedScene: PreparedScene? = null
    private var cachedPaths: List<CachedPath>? = null
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var cacheValid: Boolean = false
    private var cachedPrepareInputs: PrepareInputs? = null

    internal val currentPreparedScene: PreparedScene? get() = cachedPreparedScene

    // Spatial index for O(k) hit testing
    private var spatialIndex: SpatialGrid? = null

    // Maps for hit-test resolution
    private var commandIdMap: Map<String, RenderCommand> = emptyMap()
    private var commandOrderMap: Map<String, Int> = emptyMap()
    private var commandToNodeMap: Map<String, IsometricNode> = emptyMap()
    private var nodeIdMap: Map<String, IsometricNode> = emptyMap()

    // Android-native rendering delegate
    private val nativeRenderer = NativeSceneRenderer()

    // --- Public API ---

    /**
     * Render the node tree to a DrawScope with path caching.
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

        val strokeComposeColor = when (strokeStyle) {
            is StrokeStyle.FillOnly -> null
            is StrokeStyle.Stroke -> strokeStyle.color.toComposeColor()
            is StrokeStyle.FillAndStroke -> strokeStyle.color.toComposeColor()
        }
        val strokeDrawStyle = when (strokeStyle) {
            is StrokeStyle.FillOnly -> null
            is StrokeStyle.Stroke -> Stroke(width = strokeStyle.width)
            is StrokeStyle.FillAndStroke -> Stroke(width = strokeStyle.width)
        }

        val paths = if (enablePathCaching) cachedPaths else null
        if (paths == null) {
            cachedPreparedScene?.let { scene ->
                renderPreparedScene(scene, strokeStyle, strokeComposeColor, strokeDrawStyle)
            }
            benchmarkHooks?.onDrawEnd()
            return
        }

        // FAST PATH: Render from cached paths (minimal allocations!)
        for (i in paths.indices) {
            val cached = paths[i]
            try {
                when (strokeStyle) {
                    is StrokeStyle.FillOnly -> {
                        drawPath(cached.path, cached.fillColor, style = Fill)
                    }
                    is StrokeStyle.Stroke -> {
                        drawPath(cached.path, strokeComposeColor!!, style = strokeDrawStyle!!)
                    }
                    is StrokeStyle.FillAndStroke -> {
                        drawPath(cached.path, cached.fillColor, style = Fill)
                        drawPath(cached.path, strokeComposeColor!!, style = strokeDrawStyle!!)
                    }
                }
            } catch (e: Exception) {
                onRenderError?.invoke(cached.commandId, e)
            }
        }

        benchmarkHooks?.onDrawEnd()
    }

    /**
     * Render using native Android canvas (2x faster, Android-only).
     *
     * Delegates to [NativeSceneRenderer] for the actual Android-specific draw calls.
     *
     * @throws IllegalStateException if called on a non-Android platform
     * @throws NoClassDefFoundError if android.graphics.Canvas is unavailable
     */
    fun DrawScope.renderNative(
        rootNode: GroupNode,
        context: RenderContext,
        strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke()
    ) {
        val width = size.width.toInt()
        val height = size.height.toInt()

        ensurePreparedScene(rootNode, context, width, height)
            ?: return

        benchmarkHooks?.onDrawStart()

        cachedPreparedScene?.let { scene ->
            with(nativeRenderer) {
                renderNative(scene, strokeStyle, onRenderError)
            }
        }

        benchmarkHooks?.onDrawEnd()
    }

    /**
     * Perform hit testing with optional spatial indexing.
     *
     * Self-sufficient: rebuilds the prepared scene if the cache is stale,
     * so callers do not need to call [DrawScope.render] first.
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

        if (enableSpatialIndex) {
            hitTestSpatial(x, y)?.let { return it }
        }

        val hit = engine.findItemAt(
            preparedScene = cachedPreparedScene!!,
            x = x,
            y = y,
            order = HitOrder.FRONT_TO_BACK,
            touchRadius = HIT_TEST_RADIUS_PX
        ) ?: return null

        return findNodeByCommandId(hit.commandId)
    }

    /**
     * Check whether the cache is stale and needs a rebuild.
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
     * Invalidate cache (call when render options change).
     */
    fun clearCache() {
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
     * Release all cached resources. Idempotent — safe to call multiple times.
     * After close(), the renderer must not be used for rendering; any attempt
     * will throw [IllegalStateException].
     */
    override fun close() {
        closed = true
        clearCache()
        benchmarkHooks = null
        onRenderError = null
    }

    // --- Internal / Cache Management ---

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
        check(!closed) { "Renderer has been closed and cannot be used for rendering" }
        if (width <= 0 || height <= 0) return null
        if (forceRebuild) clearCache()
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

    /**
     * Rebuild cache when scene changes.
     *
     * If any step fails (e.g. a custom node's [IsometricNode.renderTo] throws),
     * the previous valid cache is preserved and [onRenderError] is notified.
     */
    internal fun rebuildCache(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int
    ) {
        check(!closed) { "Renderer has been closed and cannot be used for rendering" }
        try {
            // Collect all render commands from the tree FIRST (may throw).
            val commands = mutableListOf<RenderCommand>()
            rootNode.renderTo(commands, context)

            // --- Point of no return: commit changes ---
            engine.clear()

            commands.forEach { command ->
                engine.add(
                    path = command.originalPath,
                    color = command.color,
                    originalShape = command.originalShape,
                    id = command.commandId,
                    ownerNodeId = command.ownerNodeId
                )
            }

            nodeIdMap = buildNodeIdMap(rootNode)

            cachedPreparedScene = engine.projectScene(
                width = width,
                height = height,
                renderOptions = context.renderOptions,
                lightDirection = context.lightDirection
            )

            cachedWidth = width
            cachedHeight = height
            cachedPrepareInputs = PrepareInputs(context.renderOptions, context.lightDirection)

            buildCommandMaps(cachedPreparedScene!!)

            if (enablePathCaching) {
                buildPathCache(cachedPreparedScene!!)
            }

            if (enableSpatialIndex) {
                buildSpatialIndex(cachedPreparedScene!!)
            }

            rootNode.markClean()
            cacheValid = true
        } catch (e: Exception) {
            onRenderError?.invoke("rebuild", e)
        }
    }

    private fun buildPathCache(scene: PreparedScene) {
        cachedPaths = scene.commands.map { command ->
            CachedPath(
                path = command.toComposePath(),
                fillColor = command.color.toComposeColor(),
                commandId = command.commandId
            )
        }
    }

    private fun buildCommandMaps(scene: PreparedScene) {
        val cmdMap = HashMap<String, RenderCommand>(scene.commands.size)
        val orderMap = HashMap<String, Int>(scene.commands.size)
        val cmdToNode = HashMap<String, IsometricNode>(scene.commands.size)

        for ((index, command) in scene.commands.withIndex()) {
            cmdMap[command.commandId] = command
            orderMap[command.commandId] = index
            command.ownerNodeId
                ?.let { nodeIdMap[it] }
                ?.let { node -> cmdToNode[command.commandId] = node }
        }
        commandIdMap = cmdMap
        commandOrderMap = orderMap
        commandToNodeMap = cmdToNode
    }

    private fun buildSpatialIndex(scene: PreparedScene) {
        spatialIndex = SpatialGrid(
            width = cachedWidth.toDouble(),
            height = cachedHeight.toDouble(),
            cellSize = spatialIndexCellSize
        )

        for (command in scene.commands) {
            val bounds = command.getBounds()
            if (bounds != null) {
                spatialIndex!!.insert(command.commandId, bounds)
            }
        }
    }

    private fun DrawScope.renderPreparedScene(
        scene: PreparedScene,
        strokeStyle: StrokeStyle,
        strokeComposeColor: Color?,
        strokeDrawStyle: Stroke?
    ) {
        scene.commands.forEach { command ->
            try {
                val path = command.toComposePath()
                val color = command.color.toComposeColor()

                when (strokeStyle) {
                    is StrokeStyle.FillOnly -> {
                        drawPath(path, color, style = Fill)
                    }
                    is StrokeStyle.Stroke -> {
                        drawPath(path, strokeComposeColor!!, style = strokeDrawStyle!!)
                    }
                    is StrokeStyle.FillAndStroke -> {
                        drawPath(path, color, style = Fill)
                        drawPath(path, strokeComposeColor!!, style = strokeDrawStyle!!)
                    }
                }
            } catch (e: Exception) {
                onRenderError?.invoke(command.commandId, e)
            }
        }
    }

    private fun hitTestSpatial(x: Double, y: Double): IsometricNode? {
        val index = spatialIndex ?: return null
        val candidateIds = index.query(x, y, HIT_TEST_RADIUS_PX)
        if (candidateIds.isEmpty()) return null

        val candidateCommands = candidateIds
            .mapNotNull { commandIdMap[it] }
            .sortedBy { command -> commandOrderMap[command.commandId] ?: Int.MAX_VALUE }
        if (candidateCommands.isEmpty()) return null

        val preparedScene = cachedPreparedScene ?: return null
        val filteredScene = PreparedScene(
            commands = candidateCommands,
            width = preparedScene.width,
            height = preparedScene.height
        )

        val hit = engine.findItemAt(
            preparedScene = filteredScene,
            x = x,
            y = y,
            order = HitOrder.FRONT_TO_BACK,
            touchRadius = HIT_TEST_RADIUS_PX
        ) ?: return null

        return findNodeByCommandId(hit.commandId)
    }

    private fun findNodeByCommandId(commandId: String): IsometricNode? {
        return commandToNodeMap[commandId]
    }

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
}
