package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.SceneProjector
import io.github.jayteealao.isometric.SortingComputeBackend
import io.github.jayteealao.isometric.compose.fillComposePath
import io.github.jayteealao.isometric.compose.toComposeColor
import io.github.jayteealao.isometric.compose.toComposePath

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
 * - Shared extensions: [toComposePath] and [toComposeColor]
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

    /**
     * Optional hook for material-aware rendering (textured fills).
     * Set via [IsometricScene]'s DisposableEffect from [LocalMaterialDrawHook].
     * Null in production when no texture rendering module is loaded (zero overhead).
     */
    var materialDrawHook: MaterialDrawHook? = null

    // Closed flag — once true, the renderer must not be used
    private var closed = false

    // Focused collaborators
    private val cache = SceneCache(engine, enablePathCaching)
    private val hitTestResolver = HitTestResolver(engine, enableSpatialIndex, spatialIndexCellSize)
    private val nativeRenderer = NativeSceneRenderer()

    /**
     * Pool of reusable Compose [androidx.compose.ui.graphics.Path] objects for the draw path.
     *
     * Each Compose Path wraps a native Skia SkPath (~200+ bytes managed + native allocation).
     * Creating and finalizing ~280 Path objects per frame at 55+ fps overwhelms the GC,
     * causing continuous heap growth and eventual OOM. The pool eliminates this by resetting
     * and refilling existing Path objects via [fillComposePath] instead of allocating new ones.
     */
    private val pathPool = ArrayList<androidx.compose.ui.graphics.Path>()

    /**
     * Pool of reusable native [android.graphics.Path] objects for the textured draw path.
     * Only used in [renderPreparedScene] when the [MaterialDrawHook] is active.
     * Mirrors the Compose [pathPool] to avoid per-frame native path allocation.
     */
    private val nativePathPool = ArrayList<android.graphics.Path>()

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
     * @throws IllegalStateException if the renderer has been [close]d
     * @throws NoClassDefFoundError if android.graphics.Canvas is unavailable (non-Android platform)
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
                renderNative(scene, strokeStyle, onRenderError, materialDrawHook)
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
        pathPool.clear()
        nativePathPool.clear()
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

    // --- Async (GPU compute) API ---

    /**
     * Async scene preparation using a [SortingComputeBackend] for GPU-accelerated depth sorting.
     *
     * Must be called from a coroutine context (typically `Dispatchers.Default` via `withContext`).
     * The result is stored in [currentPreparedScene] and can be drawn with [renderFromScene].
     */
    suspend fun prepareAsync(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int,
        computeBackend: SortingComputeBackend,
        skipHitTest: Boolean = false,
    ) {
        check(!closed) { "Renderer has been closed and cannot be used for rendering" }
        if (width <= 0 || height <= 0) return
        if (forceRebuild) clearCache()

        if (cache.needsUpdate(rootNode, context, width, height)) {
            benchmarkHooks?.onCacheMiss()
            benchmarkHooks?.onPrepareStart()
            val scene = cache.rebuildAsync(rootNode, context, width, height, computeBackend, onRenderError)
            if (scene != null && !skipHitTest) {
                hitTestResolver.rebuildIndices(rootNode, scene)
            }
            benchmarkHooks?.onPrepareEnd()
        } else {
            benchmarkHooks?.onCacheHit()
        }
    }

    /**
     * Prepare a scene without drawing it.
     *
     * Used by non-canvas render backends that still consume the CPU-side
     * [PreparedScene] representation.
     */
    fun prepareScene(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int,
        skipHitTest: Boolean = false,
    ): PreparedScene? {
        check(!closed) { "Renderer has been closed and cannot be used for rendering" }
        if (width <= 0 || height <= 0) return null
        if (forceRebuild) clearCache()

        if (cache.needsUpdate(rootNode, context, width, height)) {
            benchmarkHooks?.onCacheMiss()
            benchmarkHooks?.onPrepareStart()
            val scene = cache.rebuild(rootNode, context, width, height, onRenderError)
            if (scene != null && !skipHitTest) {
                hitTestResolver.rebuildIndices(rootNode, scene)
            }
            benchmarkHooks?.onPrepareEnd()
            return scene
        }

        benchmarkHooks?.onCacheHit()
        return cache.currentPreparedScene
    }

    /**
     * Lightweight prepare for the Full WebGPU pipeline.
     *
     * Collects render commands from the node tree without running the CPU projection,
     * culling, lighting, or depth sort — the GPU handles all of that. Hit-test indices
     * are not built since Full WebGPU does not use the CPU hit-test path.
     *
     * @see SceneCache.rebuildForGpu
     */
    fun prepareSceneForGpu(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int,
    ): PreparedScene? {
        check(!closed) { "Renderer has been closed and cannot be used for rendering" }
        if (width <= 0 || height <= 0) return null
        if (forceRebuild) clearCache()

        if (cache.needsUpdate(rootNode, context, width, height)) {
            benchmarkHooks?.onCacheMiss()
            benchmarkHooks?.onPrepareStart()
            val scene = cache.rebuildForGpu(rootNode, context, width, height, onRenderError)
            benchmarkHooks?.onPrepareEnd()
            return scene
        }

        benchmarkHooks?.onCacheHit()
        return cache.currentPreparedScene
    }

    /**
     * Draw a pre-built scene without running the prepare step.
     *
     * Used by the GPU compute path: the `LaunchedEffect` prepares the scene via [prepareAsync],
     * then the Canvas lambda draws it via this method.
     */
    fun DrawScope.renderFromScene(
        scene: PreparedScene,
        strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
    ) {
        check(!closed) { "Renderer has been closed and cannot be used for rendering" }

        benchmarkHooks?.onDrawStart()
        renderPreparedScene(
            scene = scene,
            strokeStyle = strokeStyle,
            strokeComposeColor = when (strokeStyle) {
                is StrokeStyle.FillOnly -> null
                is StrokeStyle.Stroke -> strokeStyle.color.toComposeColor()
                is StrokeStyle.FillAndStroke -> strokeStyle.color.toComposeColor()
            },
            strokeDrawStyle = when (strokeStyle) {
                is StrokeStyle.FillOnly -> null
                is StrokeStyle.Stroke -> Stroke(width = strokeStyle.width)
                is StrokeStyle.FillAndStroke -> Stroke(width = strokeStyle.width)
            },
        )
        benchmarkHooks?.onDrawEnd()
    }

    /**
     * Draw a pre-built scene using native Android canvas without running the prepare step.
     *
     * Native-canvas variant of [renderFromScene] for the GPU compute + native draw path.
     */
    fun DrawScope.renderNativeFromScene(
        scene: PreparedScene,
        strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
    ) {
        check(!closed) { "Renderer has been closed and cannot be used for rendering" }

        benchmarkHooks?.onDrawStart()
        with(nativeRenderer) {
            renderNative(scene, strokeStyle, onRenderError, materialDrawHook)
        }
        benchmarkHooks?.onDrawEnd()
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
        val commands = scene.commands

        // Grow the path pools to match the command count. Paths are never
        // removed — only new slots are added when the scene grows. This
        // eliminates ~280 Path() allocations per frame after the first.
        while (pathPool.size < commands.size) {
            pathPool.add(androidx.compose.ui.graphics.Path())
        }

        val hook = materialDrawHook

        // Grow native path pool only when a hook is active (textured rendering).
        if (hook != null) {
            while (nativePathPool.size < commands.size) {
                nativePathPool.add(android.graphics.Path())
            }
        }

        for (i in commands.indices) {
            val command = commands[i]
            try {
                val path = pathPool[i]
                command.fillComposePath(path)

                // Try material hook for textured rendering via native canvas bridge.
                // Uses pooled native paths to avoid per-frame allocation.
                val materialHandled = command.material != null
                    && hook != null
                    && run {
                        var handled = false
                        drawIntoCanvas { canvas ->
                            val nativePath = nativePathPool[i]
                            command.fillNativePath(nativePath)
                            handled = hook.draw(canvas.nativeCanvas, command, nativePath)
                        }
                        handled
                    }

                if (materialHandled) {
                    // Hook drew the fill — apply stroke if needed
                    when (strokeStyle) {
                        is StrokeStyle.Stroke, is StrokeStyle.FillAndStroke -> {
                            drawPath(path, strokeComposeColor!!, style = strokeDrawStyle!!)
                        }
                        is StrokeStyle.FillOnly -> { /* no stroke */ }
                    }
                } else {
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
                }
            } catch (e: Exception) {
                onRenderError?.invoke(command.commandId, e)
            }
        }
    }
}
