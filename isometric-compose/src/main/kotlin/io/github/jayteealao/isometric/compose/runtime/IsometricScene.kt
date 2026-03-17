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
    IsometricScene(
        modifier = modifier,
        config = AdvancedSceneConfig(
            renderOptions = config.renderOptions,
            lightDirection = config.lightDirection,
            defaultColor = config.defaultColor,
            colorPalette = config.colorPalette,
            strokeStyle = config.strokeStyle,
            gestures = config.gestures,
            useNativeCanvas = config.useNativeCanvas,
            cameraState = config.cameraState
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
            }
            CompositionLocalProvider(*providers.toTypedArray()) {
                IsometricScopeImpl.currentContent()
            }
        }
        onDispose {
            composition.dispose()
        }
    }

    // Keep fresh references for the pointer-input coroutine, which is launched once
    // via pointerInput(Unit) and would otherwise capture stale values.
    val currentRenderContext by rememberUpdatedState(renderContext)
    val currentCanvasWidth by rememberUpdatedState(canvasWidth)
    val currentCanvasHeight by rememberUpdatedState(canvasHeight)
    val currentGestures by rememberUpdatedState(config.gestures)
    val currentCameraState by rememberUpdatedState(config.cameraState)

    // Render to canvas with gesture handling.
    // Pointer input is installed when gestures are explicitly enabled OR when a
    // CameraState is provided (for default drag-to-pan behavior).
    val gesturesActive = config.gestures.enabled || config.cameraState != null
    Canvas(
        modifier = modifier
            .then(
                if (gesturesActive) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            var isDragging = false
                            var dragStartPos: Offset? = null

                            while (true) {
                                val event = awaitPointerEvent()

                                when (event.type) {
                                    PointerEventType.Press -> {
                                        val position = event.changes.first().position
                                        dragStartPos = position
                                        isDragging = false
                                    }

                                    PointerEventType.Move -> {
                                        val position = event.changes.first().position
                                        val start = dragStartPos

                                        if (start != null) {
                                            val delta = position - start

                                            // If moved more than threshold, it's a drag
                                            if (!isDragging && delta.getDistance() > currentGestures.dragThreshold) {
                                                isDragging = true
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
                                        }

                                        isDragging = false
                                        dragStartPos = null
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Modifier
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
