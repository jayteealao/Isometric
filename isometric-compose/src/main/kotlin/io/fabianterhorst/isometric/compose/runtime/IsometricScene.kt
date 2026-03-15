package io.fabianterhorst.isometric.compose.runtime

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
import io.fabianterhorst.isometric.IsometricEngine

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
            useNativeCanvas = config.useNativeCanvas
        ),
        content = content
    )
}

@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    config: AdvancedSceneConfig,
    content: @Composable IsometricScope.() -> Unit
) {
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

    // Wire up dirty notification: when markDirty() reaches the root,
    // increment sceneVersion to trigger Canvas invalidation.
    SideEffect {
        rootNode.onDirty = { sceneVersion++ }
    }

    // Wire benchmark hooks from CompositionLocal to the renderer.
    // LocalBenchmarkHooks.current is read during composition (required for CompositionLocals),
    // then assigned in SideEffect to bridge into the imperative renderer.
    val currentBenchmarkHooks = LocalBenchmarkHooks.current
    SideEffect {
        renderer.benchmarkHooks = currentBenchmarkHooks
        renderer.forceRebuild = config.forceRebuild
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

    // Provide hit-test function to benchmarks (bypasses Compose pointer input overhead)
    SideEffect {
        config.onHitTestReady?.invoke { x, y ->
            renderer.hitTest(
                rootNode = rootNode,
                x = x, y = y,
                context = renderContext,
                width = canvasWidth,
                height = canvasHeight
            )
        }
    }

    // Report actual runtime flag state to benchmarks for validation
    SideEffect {
        config.onFlagsReady?.invoke(
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

    // Create the sub-composition once when this composable enters the tree.
    // The lambda reads rememberUpdatedState-backed values, so the child composition
    // still recomposes when those values change without re-calling setContent().
    DisposableEffect(composition) {
        composition.setContent {
            CompositionLocalProvider(
                LocalDefaultColor provides currentDefaultColor,
                LocalLightDirection provides currentLightDirection,
                LocalRenderOptions provides currentRenderOptions,
                LocalStrokeStyle provides currentStrokeStyle,
                LocalColorPalette provides currentColorPalette
            ) {
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

    // Render to canvas with gesture handling
    Canvas(
        modifier = modifier
            .then(
                if (config.gestures.enabled) {
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
                                                currentGestures.onDrag?.invoke(
                                                    DragEvent(delta.x.toDouble(), delta.y.toDouble())
                                                )
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
                                            val hitNode = renderer.hitTest(
                                                rootNode = rootNode,
                                                x = position.x.toDouble(),
                                                y = position.y.toDouble(),
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
    }
}
