package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.SceneProjector
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
    fun onPrepareStart() {}
    fun onPrepareEnd() {}
    fun onDrawStart() {}
    fun onDrawEnd() {}
    fun onCacheHit() {}
    fun onCacheMiss() {}
}

/**
 * Renderer that converts the isometric node tree to visual output
 * using a [SceneProjector] for projection and depth sorting.
 *
 * Coordinates between focused collaborators:
 * - [SceneCache] — prepared-scene caching and invalidation
 * - [HitTestResolver] — spatial indexing and command-to-node hit-test resolution
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
    enableSpatialIndex: Boolean = true,
    spatialIndexCellSize: Double = DEFAULT_SPATIAL_INDEX_CELL_SIZE
) : java.io.Closeable {
    init {
        require(spatialIndexCellSize.isFinite() && spatialIndexCellSize > 0.0) {
            "spatialIndexCellSize must be positive and finite, got $spatialIndexCellSize"
        }
    }

    companion object {
        const val DEFAULT_SPATIAL_INDEX_CELL_SIZE: Double = 100.0
    }

    /**
     * Optional callback invoked when a render command fails to draw.
     * Receives the command ID and the exception. The command is skipped.
     * Null in production (errors are silently skipped).
     */
    var onRenderError: ((commandId: String, error: Throwable) -> Unit)? = null

    /**
     * Optional benchmark hooks for instrumentation. Set via [IsometricScene]'s DisposableEffect
     * from [LocalBenchmarkHooks]. Null in production (zero overhead).
     */
    var benchmarkHooks: RenderBenchmarkHooks? = null

    /**
     * When true, forces a cache rebuild every frame (disables PreparedScene caching).
     * Used by benchmarks to measure projectScene() cost independently of caching.
     */
    var forceRebuild: Boolean = false

    // Closed flag — once true, the renderer must not be used
    private var closed = false

    // Focused collaborators
    private val cache = SceneCache(engine, enablePathCaching)
    private val hitTestResolver = HitTestResolver(engine, enableSpatialIndex, spatialIndexCellSize)
    private val nativeRenderer = NativeSceneRenderer()

    internal val currentPreparedScene: PreparedScene? get() = cache.currentPreparedScene

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

        val paths = if (enablePathCaching) cache.cachedPaths else null
        if (paths == null) {
            cache.currentPreparedScene?.let { scene ->
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

        cache.currentPreparedScene?.let { scene ->
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

        return hitTestResolver.hitTest(x, y, cache.currentPreparedScene!!)
    }

    /**
     * Check whether the cache is stale and needs a rebuild.
     */
    internal fun needsUpdate(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int
    ): Boolean = cache.needsUpdate(rootNode, context, width, height)

    /**
     * Invalidate cache (call when render options change).
     */
    fun clearCache() {
        cache.clearCache()
        hitTestResolver.clearIndices()
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

    // --- Internal / Orchestration ---

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
        if (cache.needsUpdate(rootNode, context, width, height)) {
            benchmarkHooks?.onCacheMiss()
            benchmarkHooks?.onPrepareStart()
            rebuildAll(rootNode, context, width, height)
            benchmarkHooks?.onPrepareEnd()
        } else {
            benchmarkHooks?.onCacheHit()
        }
        return cache.currentPreparedScene
    }

    /**
     * Rebuild the cache and all hit-test indices.
     *
     * If the cache rebuild fails (e.g. a custom node's [IsometricNode.renderTo] throws),
     * the previous valid cache is preserved and [onRenderError] is notified.
     */
    internal fun rebuildCache(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int
    ) {
        check(!closed) { "Renderer has been closed and cannot be used for rendering" }
        rebuildAll(rootNode, context, width, height)
    }

    private fun rebuildAll(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int
    ) {
        val scene = cache.rebuild(rootNode, context, width, height, onRenderError)
        if (scene != null) {
            hitTestResolver.rebuildIndices(rootNode, scene)
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
}
