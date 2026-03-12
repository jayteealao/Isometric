package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
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
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.IsometricEngine.Companion.DEFAULT_LIGHT_DIRECTION
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector

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
 * Main composable for creating isometric scenes using the runtime-level API.
 *
 * This uses a custom Applier to build a node tree that is efficiently rendered
 * with dirty tracking and incremental updates.
 *
 * @param modifier Modifier to apply to the canvas
 * @param renderOptions Rendering configuration options
 * @param strokeWidth Width of outline strokes
 * @param drawStroke Whether to draw outlines around shapes
 * @param lightDirection Direction of the light source (unit vector).
 *   Affects per-face shading via dot-product lighting. The default matches
 *   the engine's built-in light direction to preserve existing visuals.
 * @param defaultColor Default color for shapes
 * @param colorPalette Color palette for theming
 * @param enableGestures Whether to enable gesture handling
 * @param enablePathCaching Enable path object caching (default: true) - reduces GC pressure by 30-40%
 * @param enableSpatialIndex Enable spatial indexing for fast hit testing (default: true) - 7-25x faster
 * @param spatialIndexCellSize Spatial-index grid cell size in pixels (default: 100.0).
 *   Smaller cells reduce candidate counts but increase grid fan-out and rebuild work.
 * @param useNativeCanvas Use Android native canvas for rendering (default: false) - 2x faster, Android-only
 * @param forceRebuild Force cache rebuild every frame (benchmark use only, default: false)
 * @param frameVersion External redraw signal. When this value changes, the Canvas is
 *   invalidated even if the node tree itself is unchanged. Used by benchmarks to ensure
 *   static scenes still render every measured frame so cache hits are observable.
 * @param onHitTestReady Callback providing a hit-test function when the renderer is ready.
 *   The provided function accepts (x, y) screen coordinates and returns the hit node or null.
 *   Used by benchmarks to invoke hit tests directly without Compose pointer input overhead.
 * @param onTap Callback when the scene is tapped (x, y, node)
 * @param onDragStart Callback when drag starts
 * @param onDrag Callback when dragging (delta x, delta y)
 * @param onDragEnd Callback when drag ends
 * @param content Scene construction lambda
 */
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    renderOptions: RenderOptions = RenderOptions.Default,
    strokeWidth: Float = 1f,
    drawStroke: Boolean = true,
    lightDirection: Vector = DEFAULT_LIGHT_DIRECTION.normalize(),
    defaultColor: IsoColor = IsoColor(33.0, 150.0, 243.0),
    colorPalette: ColorPalette = ColorPalette(),
    enableGestures: Boolean = true,
    enablePathCaching: Boolean = true,
    enableSpatialIndex: Boolean = true,
    spatialIndexCellSize: Double = IsometricRenderer.DEFAULT_SPATIAL_INDEX_CELL_SIZE,
    useNativeCanvas: Boolean = false,
    forceRebuild: Boolean = false,
    frameVersion: Long = 0L,
    onHitTestReady: ((hitTest: (x: Double, y: Double) -> IsometricNode?) -> Unit)? = null,
    onFlagsReady: ((RuntimeFlagSnapshot) -> Unit)? = null,
    onTap: (x: Double, y: Double, node: IsometricNode?) -> Unit = { _, _, _ -> },
    onDragStart: (x: Double, y: Double) -> Unit = { _, _ -> },
    onDrag: (deltaX: Double, deltaY: Double) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    content: @Composable IsometricScope.() -> Unit
) {
    // Create root node and applier
    val rootNode = remember { GroupNode() }
    val engine = remember { IsometricEngine() }
    val renderer = remember(engine, enablePathCaching, enableSpatialIndex, spatialIndexCellSize) {
        IsometricRenderer(
            engine = engine,
            enablePathCaching = enablePathCaching,
            enableSpatialIndex = enableSpatialIndex,
            spatialIndexCellSize = spatialIndexCellSize
        )
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
        renderer.forceRebuild = forceRebuild
    }

    // Track canvas size
    var canvasWidth by remember { mutableStateOf(800) }
    var canvasHeight by remember { mutableStateOf(600) }

    // Create render context
    val renderContext = remember(canvasWidth, canvasHeight, renderOptions, lightDirection) {
        RenderContext(
            width = canvasWidth,
            height = canvasHeight,
            renderOptions = renderOptions,
            lightDirection = lightDirection
        )
    }

    // Provide hit-test function to benchmarks (bypasses Compose pointer input overhead)
    SideEffect {
        onHitTestReady?.invoke { x, y ->
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
        onFlagsReady?.invoke(
            RuntimeFlagSnapshot(
                enablePathCaching = enablePathCaching,
                enableSpatialIndex = enableSpatialIndex,
                enableBroadPhaseSort = renderOptions.enableBroadPhaseSort,
                forceRebuild = renderer.forceRebuild,
                useNativeCanvas = useNativeCanvas,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight
            )
        )
    }

    // Setup composition with custom applier
    val compositionContext = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)

    val composition = remember(compositionContext) {
        Composition(IsometricApplier(rootNode), compositionContext)
    }

    // Create the sub-composition once when this composable enters the tree.
    // The lambda reads rememberUpdatedState-backed values, so the child composition
    // still recomposes when those values change without re-calling setContent().
    DisposableEffect(composition) {
        composition.setContent {
            CompositionLocalProvider(
                LocalDefaultColor provides defaultColor,
                LocalLightDirection provides lightDirection,
                LocalRenderOptions provides renderOptions,
                LocalStrokeWidth provides strokeWidth,
                LocalDrawStroke provides drawStroke,
                LocalColorPalette provides colorPalette
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
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    // Render to canvas with gesture handling
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (enableGestures) {
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
                                            if (!isDragging && delta.getDistance() > 8f) {
                                                isDragging = true
                                                currentOnDragStart(start.x.toDouble(), start.y.toDouble())
                                            }

                                            if (isDragging) {
                                                currentOnDrag(delta.x.toDouble(), delta.y.toDouble())
                                                dragStartPos = position
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    }

                                    PointerEventType.Release -> {
                                        val position = event.changes.first().position

                                        if (isDragging) {
                                            currentOnDragEnd()
                                        } else {
                                            // It's a tap
                                            val hitNode = renderer.hitTest(
                                                rootNode = rootNode,
                                                x = position.x.toDouble(),
                                                y = position.y.toDouble(),
                                                context = currentRenderContext,
                                                width = currentCanvasWidth,
                                                height = currentCanvasHeight
                                            )
                                            currentOnTap(position.x.toDouble(), position.y.toDouble(), hitNode)
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
        frameVersion

        // Update canvas size
        canvasWidth = size.width.toInt()
        canvasHeight = size.height.toInt()

        // Render the scene (choose rendering method)
        with(renderer) {
            if (useNativeCanvas) {
                // Native canvas rendering (Android-only, 2x faster)
                renderNative(
                    rootNode = rootNode,
                    context = renderContext,
                    strokeWidth = strokeWidth,
                    drawStroke = drawStroke
                )
            } else {
                // Standard Compose rendering (multiplatform)
                render(
                    rootNode = rootNode,
                    context = renderContext,
                    strokeWidth = strokeWidth,
                    drawStroke = drawStroke
                )
            }
        }
    }
}

/**
 * Simpler version of IsometricScene without gesture handling
 */
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    renderOptions: RenderOptions = RenderOptions.Default,
    strokeWidth: Float = 1f,
    drawStroke: Boolean = true,
    content: @Composable IsometricScope.() -> Unit
) {
    IsometricScene(
        modifier = modifier,
        renderOptions = renderOptions,
        strokeWidth = strokeWidth,
        drawStroke = drawStroke,
        enableGestures = false,
        content = content
    )
}
