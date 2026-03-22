package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.SceneProjector
import io.github.jayteealao.isometric.SortingComputeBackend
import io.github.jayteealao.isometric.Vector
import io.github.jayteealao.isometric.compose.toComposeColor
import io.github.jayteealao.isometric.compose.toComposePath

/**
 * Encapsulates the prepared-scene cache and its invalidation logic.
 *
 * Responsible for:
 * - Tracking whether the cache is stale (dirty tree, changed dimensions, changed render options)
 * - Rebuilding the cache by collecting render commands from the node tree,
 *   feeding them to the [SceneProjector], and storing the resulting [PreparedScene]
 * - Optionally maintaining a pre-converted path cache for zero-allocation drawing
 *
 * This class does NOT own hit-test state — that belongs to [HitTestResolver].
 */
internal class SceneCache(
    private val engine: SceneProjector,
    private val enablePathCaching: Boolean
) {
    /**
     * Cached path with pre-converted Compose objects to avoid per-frame reallocations.
     */
    data class CachedPath(
        val path: Path,
        val fillColor: Color,
        val commandId: String
    )

    /** Bundles inputs to engine.projectScene() for cache invalidation. */
    private data class PrepareInputs(
        val renderOptions: RenderOptions,
        val lightDirection: Vector
    )

    // Cache state
    var currentPreparedScene: PreparedScene? = null
        private set
    var cachedPaths: List<CachedPath>? = null
        private set
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var cacheValid: Boolean = false
    private var cachedPrepareInputs: PrepareInputs? = null
    private var cachedProjectionVersion: Long = -1L

    /**
     * Check whether the cache is stale and needs a rebuild.
     */
    fun needsUpdate(
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
                currentInputs != cachedPrepareInputs ||
                engine.projectionVersion != cachedProjectionVersion
    }

    /**
     * Rebuild the cache from the node tree.
     *
     * Collects render commands from the tree, feeds them to the engine,
     * projects the scene, and optionally builds the path cache.
     * On success, marks the tree clean and returns the new [PreparedScene].
     * On failure, preserves the previous cache and returns null.
     *
     * @return The new [PreparedScene] on success, or null if the rebuild failed.
     */
    fun rebuild(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int,
        onRenderError: ((String, Throwable) -> Unit)?
    ): PreparedScene? {
        return try {
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

            val scene = engine.projectScene(
                width = width,
                height = height,
                renderOptions = context.renderOptions,
                lightDirection = context.lightDirection
            )

            currentPreparedScene = scene
            cachedWidth = width
            cachedHeight = height
            cachedPrepareInputs = PrepareInputs(context.renderOptions, context.lightDirection)
            cachedProjectionVersion = engine.projectionVersion

            if (enablePathCaching) {
                buildPathCache(scene)
            }

            rootNode.markClean()
            cacheValid = true

            scene
        } catch (e: Exception) {
            onRenderError?.invoke("rebuild", e)
            null
        }
    }

    /**
     * Async variant of [rebuild] that uses a [SortingComputeBackend] for depth sorting.
     *
     * Collects render commands synchronously (same as [rebuild]), then calls
     * [SceneProjector.projectSceneAsync] which delegates depth sorting to the GPU.
     *
     * Must be called from a coroutine context (GPU sort is always async).
     */
    suspend fun rebuildAsync(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int,
        computeBackend: SortingComputeBackend,
        onRenderError: ((String, Throwable) -> Unit)?
    ): PreparedScene? {
        return try {
            val commands = mutableListOf<RenderCommand>()
            rootNode.renderTo(commands, context)

            // Release old scene before building the new one so GC can reclaim
            // old-gen objects (Point2D, TransformedItem, RenderCommand) while the
            // new scene is being constructed. The Canvas still holds its own
            // reference via asyncPreparedScene so drawing is unaffected.
            cachedPaths = null
            currentPreparedScene = null

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

            val scene = engine.projectSceneAsync(
                width = width,
                height = height,
                renderOptions = context.renderOptions,
                lightDirection = context.lightDirection,
                computeBackend = computeBackend,
            )

            currentPreparedScene = scene
            cachedWidth = width
            cachedHeight = height
            cachedPrepareInputs = PrepareInputs(context.renderOptions, context.lightDirection)
            cachedProjectionVersion = engine.projectionVersion

            if (enablePathCaching) {
                buildPathCache(scene)
            }

            rootNode.markClean()
            cacheValid = true

            scene
        } catch (e: Exception) {
            onRenderError?.invoke("rebuildAsync", e)
            null
        }
    }

    /**
     * Invalidate the cache, forcing a rebuild on the next frame.
     */
    fun clearCache() {
        cacheValid = false
        currentPreparedScene = null
        cachedPaths = null
        cachedPrepareInputs = null
        cachedProjectionVersion = -1L
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
}
