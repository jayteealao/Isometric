package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.SceneProjector

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
            nodeDragState = config.nodeDragState
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

    // Engine projection-version bridge: polls the engine's projectionVersion every frame
    // boundary and increments sceneVersion when a change is detected, so a pure engine
    // parameter mutation (e.g. engine.scale = X, engine.angle = X) triggers a Canvas
    // redraw even when no node is marked dirty. Without this bridge the cache would
    // detect the stale projectionVersion on the NEXT draw, but that draw would never
    // come if the scene was otherwise idle.
    // Keyed on engine so a new engine instance resets the tracked version.
    LaunchedEffect(engine) {
        var lastVersion = engine.projectionVersion
        while (true) {
            delay(16L) // one frame boundary
            val current = engine.projectionVersion
            if (current != lastVersion) {
                lastVersion = current
                sceneVersion++
            }
        }
    }

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

    // Keep fresh references for the pointer-input coroutine, which is keyed
    // on Unit (never restarts) and would otherwise capture stale values.
    val currentRenderContext by rememberUpdatedState(renderContext)
    val currentCanvasWidth by rememberUpdatedState(canvasWidth)
    val currentCanvasHeight by rememberUpdatedState(canvasHeight)
    val currentGestures by rememberUpdatedState(config.gestures)
    val currentCameraState by rememberUpdatedState(config.cameraState)
    val currentNodeDragState by rememberUpdatedState(config.nodeDragState)

    // Pointer input is always installed so per-node onClick / onLongClick
    // callbacks fire even when no scene-level GestureConfig is supplied.
    // Downstream hit-test and dispatch are no-ops when nothing is registered.
    Canvas(
        modifier = modifier
            .then(
                Modifier.pointerInput(Unit) {
                        // Capture coroutine scope for long-press detection.
                        // pointerInput's lambda is a suspend PointerInputScope.() -> Unit,
                        // so we wrap with coroutineScope to get a scope for launching.
                        coroutineScope {
                        val longPressScope: CoroutineScope = this
                        awaitPointerEventScope {
                            var isDragging = false
                            var longPressFired = false
                            var dragStartPos: Offset? = null
                            var longPressJob: Job? = null
                            // Non-null only while a drag is moving a selected node (the
                            // drag-a-node affordance). When set, this gesture owns the node
                            // and the camera-pan / onDrag lifecycle path is suppressed.
                            var draggedNode: IsometricNode? = null

                            while (true) {
                                val event = awaitPointerEvent()

                                when (event.type) {
                                    PointerEventType.Press -> {
                                        val change = event.changes.firstOrNull() ?: continue
                                        val position = change.position
                                        dragStartPos = position
                                        isDragging = false
                                        longPressFired = false

                                        // Capture press position into a local val so the
                                        // long-press job reads a stable snapshot and never
                                        // races with a Move event that mutates dragStartPos
                                        // before the delay() elapses. (GC-1)
                                        val capturedPressPos = position

                                        // Start long-press detection coroutine
                                        longPressJob?.cancel()
                                        longPressJob = longPressScope.launch {
                                            // Configurable via GestureConfig.longPressTimeoutMs
                                            // (default 500ms). Read here so the value captured is
                                            // the one current at press time, not a stale snapshot.
                                            delay(currentGestures.longPressTimeoutMs)

                                            // Inverse-transform for camera-aware hit testing.
                                            // Uses the locally-captured press position, not the
                                            // shared dragStartPos var that Move can overwrite. (GC-1)
                                            val (hitX, hitY) = screenToEngineCoords(
                                                screenX = capturedPressPos.x,
                                                screenY = capturedPressPos.y,
                                                camera = currentCameraState,
                                                canvasWidth = currentCanvasWidth,
                                                canvasHeight = currentCanvasHeight
                                            )

                                            val hitNode = renderer.hitTest(
                                                rootNode = rootNode,
                                                x = hitX,
                                                y = hitY,
                                                context = currentRenderContext,
                                                width = currentCanvasWidth,
                                                height = currentCanvasHeight
                                            )
                                            val onLongClick = hitNode?.onLongClick
                                            if (onLongClick != null) {
                                                onLongClick.invoke()
                                                // Suppress the trailing tap only after a real
                                                // long-click dispatched. A slow tap on empty
                                                // space or on a node without onLongClick must
                                                // still fall through to onTap / onClick.
                                                longPressFired = true
                                            }
                                        }
                                    }

                                    PointerEventType.Move -> {
                                        val change = event.changes.firstOrNull() ?: continue
                                        val position = change.position
                                        val start = dragStartPos

                                        if (start != null) {
                                            val delta = position - start

                                            // If moved more than threshold, it's a drag
                                            if (!isDragging && delta.getDistance() > currentGestures.dragThreshold) {
                                                isDragging = true
                                                // Reset dragStartPos on the transition frame so the
                                                // per-event delta dispatched on this frame is
                                                // (near-)zero rather than the full press-to-current
                                                // distance. The dispatch block below recomputes delta
                                                // from the updated dragStartPos. (CR-1)
                                                dragStartPos = position
                                                longPressJob?.cancel()

                                                // Drag-a-node: if a node is selected and this
                                                // drag began on it, the gesture moves that node
                                                // instead of panning. Empty-space and other-node
                                                // drags leave draggedNode null and pan as before.
                                                draggedNode = resolveDraggedNode(
                                                    nodeDragState = currentNodeDragState,
                                                    pressPos = start,
                                                    camera = currentCameraState,
                                                    renderer = renderer,
                                                    rootNode = rootNode,
                                                    context = currentRenderContext,
                                                    width = currentCanvasWidth,
                                                    height = currentCanvasHeight
                                                )

                                                // onDragStart belongs to the camera/custom-drag
                                                // surface; a node drag owns the gesture, so only
                                                // fire onDragStart when no node is being moved.
                                                if (draggedNode == null) {
                                                    currentGestures.onDragStart?.invoke(
                                                        // Absolute drag-start position; no movement yet, so delta is null.
                                                        DragEvent(start.x.toDouble(), start.y.toDouble(), delta = null)
                                                    )
                                                }
                                            }

                                            if (isDragging) {
                                                // Recompute delta from the (possibly just-reset) dragStartPos
                                                // so the transition frame dispatches a near-zero movement
                                                // rather than the full press-to-current jump. (CR-1)
                                                val currentStart = dragStartPos ?: position
                                                val currentDelta = position - currentStart
                                                val movingNode = draggedNode
                                                val activeDragState = currentNodeDragState
                                                if (movingNode != null && activeDragState != null) {
                                                    // Move only the selected node: un-project the
                                                    // screen delta through the full isometric inverse
                                                    // projection (zoom + engine.screenToWorld on the
                                                    // node's z-plane), clamp to drag bounds, redraw,
                                                    // and let consume() below keep the pan branch off.
                                                    val iso = currentIsometricEngine
                                                    if (iso != null) {
                                                        movingNode.position = activeDragState.draggedPosition(
                                                            current = movingNode.position,
                                                            screenDx = currentDelta.x.toDouble(),
                                                            screenDy = currentDelta.y.toDouble(),
                                                            engine = iso,
                                                            viewportWidth = currentCanvasWidth,
                                                            viewportHeight = currentCanvasHeight,
                                                            camera = currentCameraState
                                                        )
                                                    }
                                                    movingNode.markDirty()
                                                } else {
                                                    // x/y = live absolute pointer position; delta = per-event movement.
                                                    val dragEvent = DragEvent(
                                                        x = position.x.toDouble(),
                                                        y = position.y.toDouble(),
                                                        delta = DragDelta(currentDelta.x.toDouble(), currentDelta.y.toDouble())
                                                    )
                                                    val onDrag = currentGestures.onDrag
                                                    if (onDrag != null) {
                                                        onDrag.invoke(dragEvent)
                                                    } else {
                                                        // C2: Default drag→pan accumulates the per-event delta when cameraState is active
                                                        dragEvent.delta?.let { currentCameraState?.pan(it.dx, it.dy) }
                                                    }
                                                }
                                                dragStartPos = position
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    }

                                    PointerEventType.Release -> {
                                        longPressJob?.cancel()
                                        val releaseChange = event.changes.firstOrNull() ?: continue
                                        val position = releaseChange.position

                                        if (longPressFired) {
                                            // Long-press already dispatched on the press path;
                                            // do not fire onTap or onDragEnd.
                                        } else if (isDragging) {
                                            // onDragEnd pairs with onDragStart on the camera/
                                            // custom-drag path; a node drag owns the gesture and
                                            // fires neither.
                                            if (draggedNode == null) {
                                                currentGestures.onDragEnd?.invoke()
                                            }
                                        } else {
                                            // S8: Inverse-transform pointer coordinates when camera
                                            // is active, so hit testing uses engine-space coords.
                                            val (hitX, hitY) = screenToEngineCoords(
                                                screenX = position.x,
                                                screenY = position.y,
                                                camera = currentCameraState,
                                                canvasWidth = currentCanvasWidth,
                                                canvasHeight = currentCanvasHeight
                                            )

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

                                            // Drag-a-node selection: a tap on a node selects it;
                                            // a tap on empty space (hitNode == null) clears the
                                            // selection. Single-selection by construction.
                                            currentNodeDragState?.select(hitNode?.nodeId)

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
                                        longPressFired = false
                                        dragStartPos = null
                                        draggedNode = null
                                    }
                                }
                            }
                        }
                        } // coroutineScope
                    }
            )
            .then(
                // Double-tap detection lives in its own pointerInput block, independent of
                // the hand-rolled tap/long-press/drag loop above. Stacking gesture detectors
                // in a single block would dead-code all but the first; separate blocks run
                // independently, so single-tap (onClick) dispatch and long-press timing in the
                // loop above are unchanged.
                Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            // Camera-correct the tap point exactly like the tap / long-press
                            // paths, then dispatch the hit node's onDoubleClick (if any).
                            val (hitX, hitY) = screenToEngineCoords(
                                screenX = offset.x,
                                screenY = offset.y,
                                camera = currentCameraState,
                                canvasWidth = currentCanvasWidth,
                                canvasHeight = currentCanvasHeight
                            )
                            val hitNode = renderer.hitTest(
                                rootNode = rootNode,
                                x = hitX,
                                y = hitY,
                                context = currentRenderContext,
                                width = currentCanvasWidth,
                                height = currentCanvasHeight
                            )
                            hitNode?.onDoubleClick?.invoke()
                        }
                    )
                }
            )
    ) {
        // Read sceneVersion to subscribe to node tree changes.
        // When any node calls markDirty(), this triggers a Canvas redraw.
        @Suppress("UNUSED_EXPRESSION")
        sceneVersion

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

            // Hook: after draw
            config.onAfterDraw?.invoke(this)
        }
    }
}

/**
 * Resolve which node a starting drag should move, or `null` to fall through to the default
 * camera-pan path.
 *
 * A node is returned only when [nodeDragState] has a selection AND the press landed on that
 * exact selected node — so dragging the selected node moves it, while dragging empty space
 * (or any other node) still pans the camera. The press position is camera-corrected before
 * hit testing, matching the tap path.
 */
private fun resolveDraggedNode(
    nodeDragState: NodeDragState?,
    pressPos: Offset,
    camera: CameraState?,
    renderer: IsometricRenderer,
    rootNode: GroupNode,
    context: RenderContext,
    width: Int,
    height: Int
): IsometricNode? {
    val selectedId = nodeDragState?.selectedNodeId ?: return null
    val (hitX, hitY) = screenToEngineCoords(
        screenX = pressPos.x,
        screenY = pressPos.y,
        camera = camera,
        canvasWidth = width,
        canvasHeight = height
    )
    val hitNode = renderer.hitTest(
        rootNode = rootNode,
        x = hitX,
        y = hitY,
        context = context,
        width = width,
        height = height
    )
    return if (hitNode != null && hitNode.nodeId == selectedId) hitNode else null
}

/**
 * Converts screen-space pointer coordinates to engine-space coordinates by
 * applying the inverse of the camera transform (pan + zoom around canvas centre).
 *
 * When [camera] is null the coordinates are returned unchanged.
 *
 * The zoom divisor is guarded: a non-finite or non-positive zoom value falls
 * back to 1.0 so that callers never divide by zero or infinity.
 *
 * @param screenX    Pointer x coordinate in screen space.
 * @param screenY    Pointer y coordinate in screen space.
 * @param camera     Active camera state, or null when no camera is applied.
 * @param canvasWidth  Canvas pixel width (used to locate the zoom pivot).
 * @param canvasHeight Canvas pixel height (used to locate the zoom pivot).
 * @return Engine-space (hitX, hitY) pair.
 */
private fun screenToEngineCoords(
    screenX: Float,
    screenY: Float,
    camera: CameraState?,
    canvasWidth: Int,
    canvasHeight: Int
): Pair<Double, Double> {
    return if (camera != null) {
        val cx = canvasWidth / 2.0
        val cy = canvasHeight / 2.0
        val safeZoom = camera.zoom.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val hitX = (screenX.toDouble() - cx - camera.panX) / safeZoom + cx
        val hitY = (screenY.toDouble() - cy - camera.panY) / safeZoom + cy
        Pair(hitX, hitY)
    } else {
        Pair(screenX.toDouble(), screenY.toDouble())
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
