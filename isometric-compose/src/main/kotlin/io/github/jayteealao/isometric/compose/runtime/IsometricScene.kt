package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.SceneProjector
import io.github.jayteealao.isometric.SortingComputeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Duration in milliseconds before a press is considered a long press. */
private const val LONG_PRESS_TIMEOUT_MS = 500L

internal data class AsyncPrepareInputs(
    val renderOptions: io.github.jayteealao.isometric.RenderOptions,
    val lightDirection: io.github.jayteealao.isometric.Vector,
    val computeBackend: io.github.jayteealao.isometric.ComputeBackend,
)

internal data class AsyncPrepareRequest(
    val sceneVersion: Long,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val inputs: AsyncPrepareInputs,
)

/**
 * Snapshot of the actual runtime flag configuration applied to the renderer.
 * Used by benchmarks to validate that flags took effect.
 */
data class RuntimeFlagSnapshot(
    val enablePathCaching: Boolean,
    val enableSpatialIndex: Boolean,
    val enableBroadPhaseSort: Boolean,
    val forceRebuild: Boolean,
    val useNativeCanvas: Boolean,
    val canvasWidth: Int,
    val canvasHeight: Int
)

/**
 * High-level entry point for standard scene usage.
 *
 * Uses [SceneConfig] for stable, user-facing options and delegates to the advanced
 * overload with the lower-level renderer and benchmark hooks left at their defaults.
 *
 * @param modifier Standard Compose modifier for sizing and layout.
 * @param config Scene configuration controlling render options, lighting, colors, and gestures.
 * @param content Composable content block scoped to [IsometricScope].
 */
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    config: SceneConfig = SceneConfig(),
    content: @Composable IsometricScope.() -> Unit
) {
    // Remember a stable engine so that AdvancedSceneConfig receives the same
    // instance across recompositions. Without this, AdvancedSceneConfig's
    // default parameter (IsometricEngine()) would create a fresh engine on
    // every call, invalidating remember(config.engine) in the advanced overload
    // and causing renderer teardown/rebuild on every parent recomposition.
    val engine = remember { IsometricEngine() }
    IsometricScene(
        modifier = modifier,
        config = AdvancedSceneConfig(
            engine = engine,
            renderOptions = config.renderOptions,
            lightDirection = config.lightDirection,
            defaultColor = config.defaultColor,
            colorPalette = config.colorPalette,
            strokeStyle = config.strokeStyle,
            gestures = config.gestures,
            useNativeCanvas = config.useNativeCanvas,
            cameraState = config.cameraState,
            renderBackend = config.renderBackend,
            computeBackend = config.computeBackend,
        ),
        content = content
    )
}

/**
 * Advanced entry point exposing the full renderer and benchmark configuration.
 *
 * Prefer the [SceneConfig] overload for typical usage. This overload is intended for
 * benchmarking, custom engine injection, and fine-grained renderer control.
 *
 * @param modifier Standard Compose modifier for sizing and layout.
 * @param config Advanced scene configuration with renderer hooks, engine injection, and benchmark flags.
 * @param content Composable content block scoped to [IsometricScope].
 */
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    config: AdvancedSceneConfig,
    content: @Composable IsometricScope.() -> Unit
) {
    // Validate useNativeCanvas is only used on Android (fail-fast at composition time)
    if (config.useNativeCanvas) {
        remember { validateNativeCanvasPlatform(); true }
    }

    // Create root node and applier
    val rootNode = remember { GroupNode() }
    val tileGestureHub = remember { TileGestureHub() }
    val engine = remember(config.engine) { config.engine }
    val renderer = remember(engine, config.enablePathCaching, config.enableSpatialIndex, config.spatialIndexCellSize) {
        IsometricRenderer(
            engine = engine,
            enablePathCaching = config.enablePathCaching,
            enableSpatialIndex = config.enableSpatialIndex,
            spatialIndexCellSize = config.spatialIndexCellSize
        )
    }
    val currentOnEngineReady by rememberUpdatedState(config.onEngineReady)
    val currentOnRendererReady by rememberUpdatedState(config.onRendererReady)

    LaunchedEffect(engine) {
        currentOnEngineReady?.invoke(engine)
    }

    LaunchedEffect(renderer) {
        currentOnRendererReady?.invoke(renderer)
    }

    // Scene version counter — incremented when the node tree becomes dirty.
    // The Canvas lambda reads this to create a Compose state dependency,
    // ensuring the Canvas redraws when nodes change.
    var sceneVersion by remember { mutableStateOf(0L) }

    // Wire benchmark hooks from CompositionLocal — read during composition,
    // then bridged into the imperative renderer via DisposableEffect.
    val currentBenchmarkHooks = LocalBenchmarkHooks.current
    val currentOnRenderError by rememberUpdatedState(config.onRenderError)

    // Effect 1: Wire dirty notification and renderer config.
    // Keyed on rootNode, renderer, and stable values — re-wires when any change.
    // Callback keys use rememberUpdatedState to avoid churn from inline lambdas.
    // onDispose clears the callback and hooks to prevent stale references when
    // the composable leaves the tree or dependencies are recreated.
    DisposableEffect(rootNode, renderer, currentBenchmarkHooks, config.forceRebuild) {
        rootNode.onDirty = { sceneVersion++ }
        renderer.benchmarkHooks = currentBenchmarkHooks
        renderer.forceRebuild = config.forceRebuild
        renderer.onRenderError = { id, error -> currentOnRenderError?.invoke(id, error) }

        onDispose {
            rootNode.onDirty = null
            renderer.benchmarkHooks = null
            renderer.onRenderError = null
        }
    }

    // Hook: expose prepared scene for inspection/debugging outside the draw phase.
    // Fires after every recomposition. The scene is the latest cached value and
    // may lag by one frame (updated during Canvas draw, observed next composition).
    // Uses rememberUpdatedState so the SideEffect always calls the latest callback.
    val currentOnPreparedSceneReady by rememberUpdatedState(config.onPreparedSceneReady)
    SideEffect {
        renderer.currentPreparedScene?.let { scene ->
            currentOnPreparedSceneReady?.invoke(scene)
        }
    }

    // Track canvas size
    var canvasWidth by remember { mutableStateOf(0) }
    var canvasHeight by remember { mutableStateOf(0) }

    // Create render context
    val renderContext = remember(canvasWidth, canvasHeight, config.renderOptions, config.lightDirection) {
        RenderContext(
            width = canvasWidth,
            height = canvasHeight,
            renderOptions = config.renderOptions,
            lightDirection = config.lightDirection
        )
    }

    // Effect 2: Publish hit-test function and runtime flags to callers.
    // Keyed on all values captured by the hit-test lambda (including renderer/rootNode)
    // so the effect re-publishes a fresh function when the renderer is recreated.
    // Callback keys use rememberUpdatedState to avoid churn from inline lambdas.
    val currentOnHitTestReady by rememberUpdatedState(config.onHitTestReady)
    val currentOnFlagsReady by rememberUpdatedState(config.onFlagsReady)
    DisposableEffect(renderer, rootNode, renderContext, canvasWidth, canvasHeight, config.forceRebuild, config.useNativeCanvas) {
        // Capture at entry so onDispose notifies the same callback that received
        // the real function, not a potentially-different latest callback.
        val capturedOnHitTestReady = currentOnHitTestReady
        capturedOnHitTestReady?.invoke { x, y ->
            renderer.hitTest(
                rootNode = rootNode,
                x = x, y = y,
                context = renderContext,
                width = canvasWidth,
                height = canvasHeight
            )
        }

        currentOnFlagsReady?.invoke(
            RuntimeFlagSnapshot(
                enablePathCaching = config.enablePathCaching,
                enableSpatialIndex = config.enableSpatialIndex,
                enableBroadPhaseSort = config.renderOptions.enableBroadPhaseSort,
                forceRebuild = renderer.forceRebuild,
                useNativeCanvas = config.useNativeCanvas,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight
            )
        )

        onDispose {
            // Publish a no-op so callers don't invoke a stale reference to a closed renderer
            capturedOnHitTestReady?.invoke { _, _ -> null }
        }
    }

    // Setup composition with custom applier
    val compositionContext = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val currentDefaultColor by rememberUpdatedState(config.defaultColor)
    val currentLightDirection by rememberUpdatedState(config.lightDirection)
    val currentRenderOptions by rememberUpdatedState(config.renderOptions)
    val currentStrokeStyle by rememberUpdatedState(config.strokeStyle)
    val currentColorPalette by rememberUpdatedState(config.colorPalette)

    val composition = remember(compositionContext) {
        Composition(IsometricApplier(rootNode), compositionContext)
    }

    // Close renderer when it changes or the scene leaves the tree.
    // Separated from the composition lifecycle to avoid disposing the composition
    // when only the renderer config changes (which would crash on re-setContent).
    DisposableEffect(renderer) {
        onDispose {
            renderer.close()
        }
    }

    // Resolve the engine as IsometricEngine for the CompositionLocal.
    // Uses rememberUpdatedState so the sub-composition's setContent closure
    // never captures a stale reference.
    val currentIsometricEngine by rememberUpdatedState(engine as? IsometricEngine)

    // Create the sub-composition once when this composable enters the tree.
    // The lambda reads rememberUpdatedState-backed values, so the child composition
    // still recomposes when those values change without re-calling setContent().
    DisposableEffect(composition) {
        composition.setContent {
            val providers = buildList {
                add(LocalDefaultColor provides currentDefaultColor)
                add(LocalLightDirection provides currentLightDirection)
                add(LocalRenderOptions provides currentRenderOptions)
                add(LocalStrokeStyle provides currentStrokeStyle)
                add(LocalColorPalette provides currentColorPalette)
                if (currentIsometricEngine != null) {
                    add(LocalIsometricEngine provides currentIsometricEngine!!)
                }
                add(LocalTileGestureHub provides tileGestureHub)
            }
            CompositionLocalProvider(*providers.toTypedArray()) {
                IsometricScopeImpl.currentContent()
            }
        }
        onDispose {
            composition.dispose()
        }
    }

    // Keep fresh references for the pointer-input coroutine, which is keyed on
    // gesturesActive and would otherwise capture stale values.
    val currentRenderContext by rememberUpdatedState(renderContext)
    val currentCanvasWidth by rememberUpdatedState(canvasWidth)
    val currentCanvasHeight by rememberUpdatedState(canvasHeight)
    val currentGestures by rememberUpdatedState(config.gestures)
    val currentCameraState by rememberUpdatedState(config.cameraState)

    // Async prepared scene state for GPU compute path.
    // Called unconditionally to satisfy Compose's rules of hooks — remember count
    // must be stable across recompositions regardless of config changes.
    val isAsyncCompute = config.computeBackend.isAsync && config.computeBackend is SortingComputeBackend
    val asyncPreparedScene = remember { mutableStateOf<PreparedScene?>(null) }
    val asyncPrepareInputs = remember(
        config.renderOptions,
        config.lightDirection,
        config.computeBackend,
    ) {
        AsyncPrepareInputs(
            renderOptions = config.renderOptions,
            lightDirection = config.lightDirection,
            computeBackend = config.computeBackend,
        )
    }

    // Whether gesture/hit-test handling is active — computed once, used by both
    // the async compute path (to skip hit-test index rebuilds) and the Canvas.
    val gesturesActive = config.gestures.enabled || config.cameraState != null || tileGestureHub.hasHandlers

    // Async compute path — prepares scenes with GPU depth sorting.
    // Only activates for SortingComputeBackend. The CPU path is untouched.
    // Requests are conflated so animated scenes do not cancel in-flight GPU work
    // every frame and destroy buffers while the device is still using them.
    //
    // Run scene preparation off the UI thread: projection/culling still does
    // meaningful CPU work before the GPU sort, and keeping that work on the
    // main thread caused visible stutter in animated WebGPU samples.
    //
    // The memory fixes that matter remain in place:
    // - hit-test index rebuilds are skipped when gestures are disabled
    // - SceneCache releases the old prepared scene before constructing the new one
    // - core projection uses reusable buffers and packed DoubleArray points
    if (isAsyncCompute) {
        val computeBackend = config.computeBackend as SortingComputeBackend
        val skipHitTest = !gesturesActive
        LaunchedEffect(renderer, computeBackend, asyncPrepareInputs) {
            snapshotFlow {
                AsyncPrepareRequest(
                    sceneVersion = sceneVersion,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    inputs = asyncPrepareInputs,
                )
            }
                .filter { request -> request.canvasWidth > 0 && request.canvasHeight > 0 }
                .conflate()
                .collect { request ->
                    withContext(Dispatchers.Default) {
                        renderer.prepareAsync(
                            rootNode = rootNode,
                            context = RenderContext(
                                width = request.canvasWidth,
                                height = request.canvasHeight,
                                renderOptions = request.inputs.renderOptions,
                                lightDirection = request.inputs.lightDirection,
                            ),
                            width = request.canvasWidth,
                            height = request.canvasHeight,
                            computeBackend = computeBackend,
                            skipHitTest = skipHitTest,
                        )
                    }
                    asyncPreparedScene.value = renderer.currentPreparedScene
                }
        }
    }

    // Render to canvas with gesture handling.
    // Pointer input is installed when gestures are explicitly enabled OR when a
    // CameraState is provided (for default drag-to-pan behavior).
    Canvas(
        modifier = modifier
            .then(
                if (gesturesActive) {
                    Modifier.pointerInput(gesturesActive) {
                        // Capture coroutine scope for long-press detection.
                        // pointerInput's lambda is a suspend PointerInputScope.() -> Unit,
                        // so we wrap with coroutineScope to get a scope for launching.
                        coroutineScope {
                        val longPressScope: CoroutineScope = this
                        awaitPointerEventScope {
                            var isDragging = false
                            var dragStartPos: Offset? = null
                            var longPressJob: Job? = null

                            while (true) {
                                val event = awaitPointerEvent()

                                when (event.type) {
                                    PointerEventType.Press -> {
                                        val position = event.changes.first().position
                                        dragStartPos = position
                                        isDragging = false

                                        // Start long-press detection coroutine
                                        longPressJob?.cancel()
                                        longPressJob = longPressScope.launch {
                                            delay(LONG_PRESS_TIMEOUT_MS)
                                            val pressPos = dragStartPos ?: return@launch

                                            // Inverse-transform for camera-aware hit testing
                                            val camera = currentCameraState
                                            val hitX: Double
                                            val hitY: Double
                                            if (camera != null) {
                                                val cx = currentCanvasWidth / 2.0
                                                val cy = currentCanvasHeight / 2.0
                                                hitX = (pressPos.x.toDouble() - cx - camera.panX) / camera.zoom + cx
                                                hitY = (pressPos.y.toDouble() - cy - camera.panY) / camera.zoom + cy
                                            } else {
                                                hitX = pressPos.x.toDouble()
                                                hitY = pressPos.y.toDouble()
                                            }

                                            val hitNode = renderer.hitTest(
                                                rootNode = rootNode,
                                                x = hitX,
                                                y = hitY,
                                                context = currentRenderContext,
                                                width = currentCanvasWidth,
                                                height = currentCanvasHeight
                                            )
                                            hitNode?.onLongClick?.invoke()

                                            // Suppress tap on release after long press fires
                                            isDragging = true
                                        }
                                    }

                                    PointerEventType.Move -> {
                                        val position = event.changes.first().position
                                        val start = dragStartPos

                                        if (start != null) {
                                            val delta = position - start

                                            // If moved more than threshold, it's a drag
                                            if (!isDragging && delta.getDistance() > currentGestures.dragThreshold) {
                                                isDragging = true
                                                longPressJob?.cancel()
                                                currentGestures.onDragStart?.invoke(
                                                    DragEvent(start.x.toDouble(), start.y.toDouble())
                                                )
                                            }

                                            if (isDragging) {
                                                val dragEvent = DragEvent(delta.x.toDouble(), delta.y.toDouble())
                                                val onDrag = currentGestures.onDrag
                                                if (onDrag != null) {
                                                    onDrag.invoke(dragEvent)
                                                } else {
                                                    // C2: Default drag→pan when cameraState is active
                                                    currentCameraState?.pan(dragEvent.x, dragEvent.y)
                                                }
                                                dragStartPos = position
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    }

                                    PointerEventType.Release -> {
                                        longPressJob?.cancel()
                                        val position = event.changes.first().position

                                        if (isDragging) {
                                            currentGestures.onDragEnd?.invoke()
                                        } else {
                                            // S8: Inverse-transform pointer coordinates when camera
                                            // is active, so hit testing uses engine-space coords.
                                            val camera = currentCameraState
                                            val hitX: Double
                                            val hitY: Double
                                            if (camera != null) {
                                                val cx = currentCanvasWidth / 2.0
                                                val cy = currentCanvasHeight / 2.0
                                                hitX = (position.x.toDouble() - cx - camera.panX) / camera.zoom + cx
                                                hitY = (position.y.toDouble() - cy - camera.panY) / camera.zoom + cy
                                            } else {
                                                hitX = position.x.toDouble()
                                                hitY = position.y.toDouble()
                                            }

                                            val hitNode = renderer.hitTest(
                                                rootNode = rootNode,
                                                x = hitX,
                                                y = hitY,
                                                context = currentRenderContext,
                                                width = currentCanvasWidth,
                                                height = currentCanvasHeight
                                            )
                                            currentGestures.onTap?.invoke(
                                                TapEvent(
                                                    x = position.x.toDouble(),
                                                    y = position.y.toDouble(),
                                                    node = hitNode
                                                )
                                            )

                                            // Dispatch per-node onClick after scene-level onTap
                                            hitNode?.onClick?.invoke()

                                            // Route to any registered TileGrid tap handlers.
                                            // Uses hitX/hitY (camera-corrected) so screenToTile
                                            // receives engine-space coordinates, matching the
                                            // coordinate space that screenToWorld expects.
                                            val isometricEngine = currentIsometricEngine
                                            if (tileGestureHub.hasHandlers && isometricEngine != null) {
                                                tileGestureHub.dispatch(
                                                    tapX = hitX,
                                                    tapY = hitY,
                                                    viewportWidth = currentCanvasWidth,
                                                    viewportHeight = currentCanvasHeight,
                                                    engine = isometricEngine
                                                )
                                            }
                                        }

                                        isDragging = false
                                        dragStartPos = null
                                    }
                                }
                            }
                        }
                        } // coroutineScope
                    }
                } else {
                    Modifier
                }
            )
    ) {
        // Switch state subscription based on compute backend.
        if (isAsyncCompute) {
            // GPU path: subscribe to asyncPreparedScene state changes.
            // The Canvas redraws when the LaunchedEffect publishes a new snapshot.
            @Suppress("UNUSED_EXPRESSION")
            asyncPreparedScene.value
        } else {
            // CPU path: subscribe to sceneVersion — unchanged from current behavior.
            @Suppress("UNUSED_EXPRESSION")
            sceneVersion
        }

        // Read frameVersion to subscribe to external redraw requests.
        // Benchmarks use this to render static scenes every frame without mutating the tree.
        @Suppress("UNUSED_EXPRESSION")
        config.frameVersion

        // Update canvas size
        canvasWidth = size.width.toInt()
        canvasHeight = size.height.toInt()

        if (canvasWidth > 0 && canvasHeight > 0) {
            // Apply camera transforms if CameraState is provided.
            // Zoom is applied around the canvas center so that zooming in
            // keeps the center of the viewport fixed rather than the top-left corner.
            // Transform order: translate to center+pan → scale → translate back.
            val cameraState = config.cameraState
            if (cameraState != null) {
                // Read state properties to subscribe to changes
                val panX = cameraState.panX
                val panY = cameraState.panY
                val zoom = cameraState.zoom
                val cx = canvasWidth / 2f
                val cy = canvasHeight / 2f

                drawContext.transform.translate(cx + panX.toFloat(), cy + panY.toFloat())
                drawContext.transform.scale(zoom.toFloat(), zoom.toFloat())
                drawContext.transform.translate(-cx, -cy)
            }

            // Hook: before draw
            config.onBeforeDraw?.invoke(this)

            if (isAsyncCompute) {
                // GPU compute path: render from the async-prepared scene snapshot.
                val scene = asyncPreparedScene.value
                if (scene != null) {
                    with(renderer) {
                        if (config.useNativeCanvas) {
                            renderNativeFromScene(scene, config.strokeStyle)
                        } else {
                            renderFromScene(scene, config.strokeStyle)
                        }
                    }
                }
            } else {
                // CPU path: synchronous prepare + render — unchanged.
                with(renderer) {
                    if (config.useNativeCanvas) {
                        renderNative(
                            rootNode = rootNode,
                            context = renderContext,
                            strokeStyle = config.strokeStyle
                        )
                    } else {
                        render(
                            rootNode = rootNode,
                            context = renderContext,
                            strokeStyle = config.strokeStyle
                        )
                    }
                }
            }

            // Hook: after draw
            config.onAfterDraw?.invoke(this)
        }
    }
}

/**
 * Validates that the native Android canvas is available on the current platform.
 * Throws [IllegalStateException] with an actionable message on non-Android JVM.
 *
 * Extracted from the composable for testability.
 */
internal fun validateNativeCanvasPlatform() {
    try {
        Class.forName("android.graphics.Canvas")
    } catch (_: ClassNotFoundException) {
        throw IllegalStateException(
            "useNativeCanvas=true requires Android. " +
            "The android.graphics.Canvas class is not available on this platform. " +
            "Use the default Compose rendering path instead."
        )
    }
}
