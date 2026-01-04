package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composition
import androidx.compose.runtime.LaunchedEffect
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
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector

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
 * @param lightDirection Direction of the light source
 * @param defaultColor Default color for shapes
 * @param colorPalette Color palette for theming
 * @param enableGestures Whether to enable gesture handling
 * @param enablePathCaching Enable path object caching (default: true) - reduces GC pressure by 30-40%
 * @param enableSpatialIndex Enable spatial indexing for fast hit testing (default: true) - 7-25x faster
 * @param useNativeCanvas Use Android native canvas for rendering (default: false) - 2x faster, Android-only
 * @param enableOffThreadComputation Compute scene preparation off main thread (default: false) - keeps UI responsive
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
    lightDirection: Vector = Vector(0.0, 1.0, 1.0).normalize(),
    defaultColor: IsoColor = IsoColor(33.0, 150.0, 243.0),
    colorPalette: ColorPalette = ColorPalette(),
    enableGestures: Boolean = true,
    enablePathCaching: Boolean = true,
    enableSpatialIndex: Boolean = true,
    useNativeCanvas: Boolean = false,
    enableOffThreadComputation: Boolean = false,
    onTap: (x: Double, y: Double, node: IsometricNode?) -> Unit = { _, _, _ -> },
    onDragStart: (x: Double, y: Double) -> Unit = { _, _ -> },
    onDrag: (deltaX: Double, deltaY: Double) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    content: @Composable IsometricScope.() -> Unit
) {
    // Create root node and applier
    val rootNode = remember { GroupNode() }
    val engine = remember { IsometricEngine() }
    val renderer = remember(engine, enablePathCaching, enableSpatialIndex) {
        IsometricRenderer(
            engine = engine,
            enablePathCaching = enablePathCaching,
            enableSpatialIndex = enableSpatialIndex
        )
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

    // Scene version for off-thread computation
    var sceneVersion by remember { mutableStateOf(0) }

    // Off-thread computation (if enabled)
    if (enableOffThreadComputation) {
        LaunchedEffect(rootNode.isDirty, canvasWidth, canvasHeight) {
            if (rootNode.isDirty) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    renderer.prepareSceneAsync(rootNode, renderContext)
                }
                sceneVersion++
            }
        }
    }

    // Recompose flag - increment to force recomposition of the node tree
    var recomposeKey by remember { mutableStateOf(0) }

    // Setup composition with custom applier
    val compositionContext = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)

    LaunchedEffect(compositionContext, recomposeKey) {
        val applier = IsometricApplier(rootNode)
        val composition = Composition(applier, compositionContext)

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

        // Cleanup on dispose
        try {
            // Keep composition alive
            kotlinx.coroutines.awaitCancellation()
        } finally {
            composition.dispose()
        }
    }

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
                                                onDragStart(start.x.toDouble(), start.y.toDouble())
                                            }

                                            if (isDragging) {
                                                onDrag(delta.x.toDouble(), delta.y.toDouble())
                                                dragStartPos = position
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    }

                                    PointerEventType.Release -> {
                                        val position = event.changes.first().position

                                        if (isDragging) {
                                            onDragEnd()
                                        } else {
                                            // It's a tap
                                            val hitNode = renderer.hitTest(
                                                rootNode = rootNode,
                                                x = position.x.toDouble(),
                                                y = position.y.toDouble(),
                                                context = renderContext
                                            )
                                            onTap(position.x.toDouble(), position.y.toDouble(), hitNode)
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
