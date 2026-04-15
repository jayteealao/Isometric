package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import io.github.jayteealao.isometric.IsometricEngine
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
    // @Volatile: written by the prepare LaunchedEffect on Dispatchers.Default,
    // read by the WebGPU render loop on a different Dispatchers.Default thread.
    // Without volatile, the render loop thread may cache a stale null reference
    // indefinitely, causing a permanent black screen in the WebGPU path.
    @Volatile
    var currentPreparedScene: PreparedScene? = null
        private set
    var cachedPaths: List<CachedPath>? = null
        private set
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var cacheValid: Boolean = false
    private var cachedPrepareInputs: PrepareInputs? = null
    private var cachedProjectionVersion: Long = -1L

    /** Reusable list for collecting render commands from the node tree (F1.1). */
    private var reusableCommandList: ArrayList<RenderCommand>? = null

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
            // Reuse the backing list to avoid per-frame ArrayList allocation (F1.1).
            val commands = reusableCommandList
                ?: ArrayList<RenderCommand>().also { reusableCommandList = it }
            commands.clear()
            rootNode.renderTo(commands, context)

            // --- Point of no return: commit changes ---
            engine.clear()

            for (command in commands) {
                engine.add(
                    path = command.originalPath,
                    color = command.color,
                    originalShape = command.originalShape,
                    id = command.commandId,
                    ownerNodeId = command.ownerNodeId,
                    material = command.material,
                    uvCoords = command.uvCoords,
                    faceType = command.faceType,
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
            // Reuse the backing list to avoid per-frame ArrayList allocation (F1.1).
            val commands = reusableCommandList
                ?: ArrayList<RenderCommand>().also { reusableCommandList = it }
            commands.clear()
            rootNode.renderTo(commands, context)

            // Keep currentPreparedScene alive during the build — the Canvas reads
            // it directly (not via Compose state) and may draw concurrently while
            // this method runs on Dispatchers.Default. The old scene is replaced
            // atomically at line 190 after the new scene is fully constructed.
            cachedPaths = null

            engine.clear()
            for (command in commands) {
                engine.add(
                    path = command.originalPath,
                    color = command.color,
                    originalShape = command.originalShape,
                    id = command.commandId,
                    ownerNodeId = command.ownerNodeId,
                    material = command.material,
                    uvCoords = command.uvCoords,
                    faceType = command.faceType,
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
     * Lightweight rebuild for the Full WebGPU pipeline.
     *
     * Collects render commands from the node tree and wraps them in a [PreparedScene]
     * with projection parameters and light direction, but **skips** the expensive CPU
     * work that the GPU pipeline re-does anyway:
     * - No CPU 3D-to-2D projection
     * - No CPU back-face culling or bounds checking
     * - No CPU lighting
     * - No CPU depth sorting
     * - No path caching (Full WebGPU doesn't use Canvas draw)
     *
     * The GPU's M3 (transform+cull+light) and M4 (sort) stages handle all of this from
     * the original 3D vertices in [RenderCommand.originalPath].
     *
     * **baseColor contract (R-07):** Commands emitted by standard nodes (`ShapeNode`,
     * `PathNode`, `BatchNode`) set `baseColor == color` — both are the raw, unlit
     * material color. The GPU's M3 shader applies lighting once from `baseColor`.
     * `CustomRenderNode` implementations that return pre-lit `RenderCommand.color` values
     * **must** set `baseColor` to the original material color explicitly, or the GPU will
     * double-light (apply lighting on top of an already-lit color). See `SceneDataPacker`
     * which reads `cmd.baseColor` for the GPU color upload.
     */
    fun rebuildForGpu(
        rootNode: GroupNode,
        context: RenderContext,
        width: Int,
        height: Int,
        onRenderError: ((String, Throwable) -> Unit)?
    ): PreparedScene? {
        return try {
            val commands = reusableCommandList
                ?: ArrayList<RenderCommand>().also { reusableCommandList = it }
            commands.clear()
            rootNode.renderTo(commands, context)

            // Get projection params directly from the engine. The GPU needs these as
            // uniforms for its own projection pass (M3).
            val concreteEngine = engine as IsometricEngine
            val scene = PreparedScene(
                commands = commands.toList(),
                width = width,
                height = height,
                projectionParams = concreteEngine.projectionParams,
                lightDirection = context.lightDirection,
                isProjected = false,
            )

            currentPreparedScene = scene
            cachedWidth = width
            cachedHeight = height
            cachedPrepareInputs = PrepareInputs(context.renderOptions, context.lightDirection)
            cachedProjectionVersion = engine.projectionVersion

            rootNode.markClean()
            cacheValid = true

            scene
        } catch (e: Exception) {
            onRenderError?.invoke("rebuildForGpu", e)
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
        reusableCommandList = null
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
