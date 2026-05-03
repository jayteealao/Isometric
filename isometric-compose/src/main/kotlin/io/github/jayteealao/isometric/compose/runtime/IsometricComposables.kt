package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.ReusableComposeNode
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.Shape

/**
 * Composable annotation for isometric nodes
 */
@Retention(AnnotationRetention.BINARY)
@ComposableTarget(applier = "io.github.jayteealao.isometric.compose.runtime.IsometricApplier")
annotation class IsometricComposable

/**
 * Add a 3D shape to the isometric scene.
 *
 * **Name disambiguation:** This composable shares its name with [io.github.jayteealao.isometric.Shape],
 * the core geometry class that represents a 3D shape (collection of [io.github.jayteealao.isometric.Path]s). The [geometry]
 * parameter accepts that class. If both are imported in the same file, use an import alias:
 *
 * ```kotlin
 * import io.github.jayteealao.isometric.Shape as ShapeGeometry
 * ```
 *
 * @param geometry The 3D [io.github.jayteealao.isometric.Shape] to render (e.g., [io.github.jayteealao.isometric.shapes.Prism])
 * @param color The color of the shape (defaults to [LocalDefaultColor])
 * @param alpha Opacity multiplier (0 = fully transparent, 1 = fully opaque)
 * @param position Local position offset
 * @param rotation Local rotation around Z axis
 * @param scale Local scale factor
 * @param rotationOrigin Origin point for rotation
 * @param scaleOrigin Origin point for scaling
 * @param visible Whether the shape is visible
 * @param onClick Callback invoked when this shape is tapped
 * @param onLongClick Callback invoked when this shape is long-pressed
 * @param testTag Optional tag for testing and diagnostics
 * @param nodeId Optional stable identifier. Must be unique within the scene when provided.
 * @see Group
 * @see Path
 * @see Batch
 */
@IsometricComposable
@Composable
fun IsometricScope.Shape(
    geometry: Shape,
    color: IsoColor = LocalDefaultColor.current,
    alpha: Float = 1f,
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    testTag: String? = null,
    nodeId: String? = null
) {
    ReusableComposeNode<ShapeNode, IsometricApplier>(
        factory = { ShapeNode(geometry, color) },
        update = {
            set(geometry) { this.shape = it; markDirty() }
            set(color) { this.color = it; markDirty() }
            set(alpha) { this.alpha = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            set(rotation) {
                require(it.isFinite()) { "rotation must be finite, got $it" }
                this.rotation = it
                markDirty()
            }
            set(scale) {
                require(it.isFinite() && it > 0.0) { "scale must be positive and finite, got $it" }
                this.scale = it
                markDirty()
            }
            set(rotationOrigin) { this.rotationOrigin = it; markDirty() }
            set(scaleOrigin) { this.scaleOrigin = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
            set(onClick) { this.onClick = it }
            set(onLongClick) { this.onLongClick = it }
            set(testTag) { this.testTag = it }
            set(nodeId) { this.explicitNodeId = it; markDirty() }
        }
    )
}

/**
 * Create a group that applies transforms to all its children.
 *
 * @param position Local position offset for all children
 * @param rotation Local rotation around Z axis for all children
 * @param scale Local scale factor for all children
 * @param rotationOrigin Origin point for rotation
 * @param scaleOrigin Origin point for scaling
 * @param visible Whether the group and its children are visible
 * @param renderOptions Optional per-subtree render options override (null inherits from parent)
 * @param testTag Optional tag for testing and diagnostics
 * @param nodeId Optional stable identifier. Must be unique within the scene when provided.
 * @param content The child nodes
 * @see Shape
 * @see Path
 * @see Batch
 */
@IsometricComposable
@Composable
fun IsometricScope.Group(
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    renderOptions: RenderOptions? = null,
    testTag: String? = null,
    nodeId: String? = null,
    content: @Composable IsometricScope.() -> Unit
) {
    ReusableComposeNode<GroupNode, IsometricApplier>(
        factory = { GroupNode() },
        update = {
            set(position) { this.position = it; markDirty() }
            set(rotation) {
                require(it.isFinite()) { "rotation must be finite, got $it" }
                this.rotation = it
                markDirty()
            }
            set(scale) {
                require(it.isFinite() && it > 0.0) { "scale must be positive and finite, got $it" }
                this.scale = it
                markDirty()
            }
            set(rotationOrigin) { this.rotationOrigin = it; markDirty() }
            set(scaleOrigin) { this.scaleOrigin = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
            set(renderOptions) { this.renderOptions = it; markDirty() }
            set(testTag) { this.testTag = it }
            set(nodeId) { this.explicitNodeId = it; markDirty() }
        },
        content = {
            IsometricScopeImpl.content()
        }
    )
}

/**
 * Add a raw 2D path to the isometric scene.
 *
 * @param path The 2D path to render
 * @param color The color of the path (defaults to LocalDefaultColor)
 * @param alpha Opacity multiplier (0 = fully transparent, 1 = fully opaque)
 * @param position Local position offset
 * @param rotation Local rotation around Z axis
 * @param scale Local scale factor
 * @param rotationOrigin Origin point for rotation
 * @param scaleOrigin Origin point for scaling
 * @param visible Whether the path is visible
 * @param onClick Callback invoked when this path is tapped
 * @param onLongClick Callback invoked when this path is long-pressed
 * @param testTag Optional tag for testing and diagnostics
 * @param nodeId Optional stable identifier. Must be unique within the scene when provided.
 * @see Shape
 * @see Group
 * @see Batch
 */
@IsometricComposable
@Composable
fun IsometricScope.Path(
    path: Path,
    color: IsoColor = LocalDefaultColor.current,
    alpha: Float = 1f,
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    testTag: String? = null,
    nodeId: String? = null
) {
    ReusableComposeNode<PathNode, IsometricApplier>(
        factory = { PathNode(path, color) },
        update = {
            set(path) { this.path = it; markDirty() }
            set(color) { this.color = it; markDirty() }
            set(alpha) { this.alpha = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            set(rotation) {
                require(it.isFinite()) { "rotation must be finite, got $it" }
                this.rotation = it
                markDirty()
            }
            set(scale) {
                require(it.isFinite() && it > 0.0) { "scale must be positive and finite, got $it" }
                this.scale = it
                markDirty()
            }
            set(rotationOrigin) { this.rotationOrigin = it; markDirty() }
            set(scaleOrigin) { this.scaleOrigin = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
            set(onClick) { this.onClick = it }
            set(onLongClick) { this.onLongClick = it }
            set(testTag) { this.testTag = it }
            set(nodeId) { this.explicitNodeId = it; markDirty() }
        }
    )
}

/**
 * Batch multiple shapes with the same color for performance.
 *
 * @param shapes List of shapes to render
 * @param color The color for all shapes (defaults to LocalDefaultColor)
 * @param alpha Opacity multiplier (0 = fully transparent, 1 = fully opaque)
 * @param position Local position offset
 * @param rotation Local rotation around Z axis
 * @param scale Local scale factor
 * @param rotationOrigin Origin point for rotation
 * @param scaleOrigin Origin point for scaling
 * @param visible Whether the batch is visible
 * @param onClick Callback invoked when this batch is tapped
 * @param onLongClick Callback invoked when this batch is long-pressed
 * @param testTag Optional tag for testing and diagnostics
 * @param nodeId Optional stable identifier. Must be unique within the scene when provided.
 * @see Shape
 * @see Group
 * @see Path
 */
@IsometricComposable
@Composable
fun IsometricScope.Batch(
    shapes: List<Shape>,
    color: IsoColor = LocalDefaultColor.current,
    alpha: Float = 1f,
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    testTag: String? = null,
    nodeId: String? = null
) {
    ReusableComposeNode<BatchNode, IsometricApplier>(
        factory = { BatchNode(shapes, color) },
        update = {
            set(shapes) { this.shapes = it; markDirty() }
            set(color) { this.color = it; markDirty() }
            set(alpha) { this.alpha = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            set(rotation) {
                require(it.isFinite()) { "rotation must be finite, got $it" }
                this.rotation = it
                markDirty()
            }
            set(scale) {
                require(it.isFinite() && it > 0.0) { "scale must be positive and finite, got $it" }
                this.scale = it
                markDirty()
            }
            set(rotationOrigin) { this.rotationOrigin = it; markDirty() }
            set(scaleOrigin) { this.scaleOrigin = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
            set(onClick) { this.onClick = it }
            set(onLongClick) { this.onLongClick = it }
            set(testTag) { this.testTag = it }
            set(nodeId) { this.explicitNodeId = it; markDirty() }
        }
    )
}

/**
 * Conditionally renders [content] based on [condition].
 *
 * When [condition] is `false`, children are removed from the scene graph entirely —
 * they do not consume rendering resources. When [condition] flips from `false` to
 * `true`, children are re-inserted and marked dirty, triggering a re-render of the
 * affected subtree only.
 *
 * @param condition When `false`, the [content] block is not rendered.
 * @param content Composable content to conditionally include in the scene.
 */
@IsometricComposable
@Composable
fun IsometricScope.If(
    condition: Boolean,
    content: @Composable IsometricScope.() -> Unit
) {
    if (condition) {
        IsometricScopeImpl.content()
    }
}

/**
 * Renders [content] once for each item in [items].
 *
 * Providing a stable [key] function is strongly recommended for lists that change
 * over time. Without keys, Compose treats the list positionally — removing an item
 * from the middle causes all subsequent items to recompose. With keys, only the
 * removed item's node is deleted and the rest are reused.
 *
 * @param items The list of data items to iterate over.
 * @param key Optional function that returns a stable, unique key for each item.
 *   When provided, enables efficient add/remove/reorder without full re-render.
 * @param content Composable content block invoked for each item, receiving the
 *   item as a parameter within [IsometricScope].
 */
@IsometricComposable
@Composable
fun <T> IsometricScope.ForEach(
    items: List<T>,
    key: ((T) -> Any)? = null,
    content: @Composable IsometricScope.(T) -> Unit
) {
    items.forEach { item ->
        // If key is provided, use it for identity
        if (key != null) {
            androidx.compose.runtime.key(key(item)) {
                IsometricScopeImpl.content(item)
            }
        } else {
            IsometricScopeImpl.content(item)
        }
    }
}

/**
 * Add a custom-rendered node to the isometric scene.
 *
 * This is the escape hatch for geometry beyond built-in shapes. The [render]
 * function is called during tree traversal with the accumulated [RenderContext]
 * and the node's unique ID (for use in [RenderCommand.ownerNodeId] to enable
 * hit testing), and should return [RenderCommand]s representing the custom geometry.
 *
 * Example — a custom ground plane:
 * ```
 * CustomNode(render = { context, nodeId ->
 *     val groundPath = Path(
 *         Point(-5.0, -5.0, 0.0),
 *         Point(5.0, -5.0, 0.0),
 *         Point(5.0, 5.0, 0.0),
 *         Point(-5.0, 5.0, 0.0)
 *     )
 *     val transformedPath = context.applyTransformsToPath(groundPath)
 *     listOf(
 *         RenderCommand(
 *             commandId = "ground",
 *             points = emptyList(),
 *             color = IsoColor(200.0, 200.0, 200.0),
 *             originalPath = transformedPath,
 *             originalShape = null,
 *             ownerNodeId = nodeId
 *         )
 *     )
 * })
 * ```
 *
 * @param alpha Opacity multiplier (0 = fully transparent, 1 = fully opaque).
 *   Applied by scaling the alpha of each [RenderCommand] returned by [render].
 * @param position Local position offset
 * @param rotation Local rotation around Z axis
 * @param scale Local scale factor
 * @param rotationOrigin Origin point for rotation
 * @param scaleOrigin Origin point for scaling
 * @param visible Whether the node is visible
 * @param renderOptions Optional per-node render options override
 * @param onClick Callback invoked when this node is tapped
 * @param onLongClick Callback invoked when this node is long-pressed
 * @param testTag Optional tag for testing and diagnostics
 * @param nodeId Optional stable identifier. Must be unique within the scene when provided.
 * @param render Function producing render commands from the accumulated context and node ID
 */
@IsometricComposable
@Composable
fun IsometricScope.CustomNode(
    alpha: Float = 1f,
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    renderOptions: RenderOptions? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    testTag: String? = null,
    nodeId: String? = null,
    render: (context: RenderContext, nodeId: String) -> List<RenderCommand>
) {
    ReusableComposeNode<CustomRenderNode, IsometricApplier>(
        factory = { CustomRenderNode(render) },
        update = {
            set(render) { this.renderFunction = it; markDirty() }
            set(alpha) { this.alpha = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            set(rotation) {
                require(it.isFinite()) { "rotation must be finite, got $it" }
                this.rotation = it
                markDirty()
            }
            set(scale) {
                require(it.isFinite() && it > 0.0) { "scale must be positive and finite, got $it" }
                this.scale = it
                markDirty()
            }
            set(rotationOrigin) { this.rotationOrigin = it; markDirty() }
            set(scaleOrigin) { this.scaleOrigin = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
            set(renderOptions) { this.renderOptions = it; markDirty() }
            set(onClick) { this.onClick = it }
            set(onLongClick) { this.onLongClick = it }
            set(testTag) { this.testTag = it }
            set(nodeId) { this.explicitNodeId = it; markDirty() }
        }
    )
}
