package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.ComposableTarget
import androidx.compose.runtime.ReusableComposeNode
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape

/**
 * Composable annotation for isometric nodes
 */
@Retention(AnnotationRetention.BINARY)
@ComposableTarget(applier = "io.fabianterhorst.isometric.compose.runtime.IsometricApplier")
annotation class IsometricComposable

/**
 * Add a 3D shape to the isometric scene
 *
 * @param shape The 3D shape to render
 * @param color The color of the shape (defaults to LocalDefaultColor)
 * @param position Local position offset
 * @param rotation Local rotation around Z axis
 * @param scale Local scale factor
 * @param rotationOrigin Origin point for rotation
 * @param scaleOrigin Origin point for scaling
 * @param visible Whether the shape is visible
 */
@IsometricComposable
@Composable
fun IsometricScope.Shape(
    shape: Shape,
    color: IsoColor = LocalDefaultColor.current,
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true
) {
    ReusableComposeNode<ShapeNode, IsometricApplier>(
        factory = { ShapeNode(shape, color) },
        update = {
            set(shape) { this.shape = it; markDirty() }
            set(color) { this.color = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            set(rotation) { this.rotation = it; markDirty() }
            set(scale) { this.scale = it; markDirty() }
            set(rotationOrigin) { this.rotationOrigin = it; markDirty() }
            set(scaleOrigin) { this.scaleOrigin = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
        }
    )
}

/**
 * Create a group that applies transforms to all its children
 *
 * @param position Local position offset for all children
 * @param rotation Local rotation around Z axis for all children
 * @param scale Local scale factor for all children
 * @param rotationOrigin Origin point for rotation
 * @param scaleOrigin Origin point for scaling
 * @param visible Whether the group and its children are visible
 * @param content The child nodes
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
    content: @Composable IsometricScope.() -> Unit
) {
    ReusableComposeNode<GroupNode, IsometricApplier>(
        factory = { GroupNode() },
        update = {
            set(position) { this.position = it; markDirty() }
            set(rotation) { this.rotation = it; markDirty() }
            set(scale) { this.scale = it; markDirty() }
            set(rotationOrigin) { this.rotationOrigin = it; markDirty() }
            set(scaleOrigin) { this.scaleOrigin = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
        },
        content = {
            IsometricScopeImpl.content()
        }
    )
}

/**
 * Add a raw 2D path to the isometric scene
 *
 * @param path The 2D path to render
 * @param color The color of the path (defaults to LocalDefaultColor)
 * @param position Local position offset
 * @param rotation Local rotation around Z axis
 * @param scale Local scale factor
 * @param visible Whether the path is visible
 */
@IsometricComposable
@Composable
fun IsometricScope.Path(
    path: Path,
    color: IsoColor = LocalDefaultColor.current,
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    visible: Boolean = true
) {
    ReusableComposeNode<PathNode, IsometricApplier>(
        factory = { PathNode(path, color) },
        update = {
            set(path) { this.path = it; markDirty() }
            set(color) { this.color = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            set(rotation) { this.rotation = it; markDirty() }
            set(scale) { this.scale = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
        }
    )
}

/**
 * Batch multiple shapes with the same color for performance
 *
 * @param shapes List of shapes to render
 * @param color The color for all shapes (defaults to LocalDefaultColor)
 * @param position Local position offset
 * @param rotation Local rotation around Z axis
 * @param scale Local scale factor
 * @param visible Whether the batch is visible
 */
@IsometricComposable
@Composable
fun IsometricScope.Batch(
    shapes: List<Shape>,
    color: IsoColor = LocalDefaultColor.current,
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    visible: Boolean = true
) {
    ReusableComposeNode<BatchNode, IsometricApplier>(
        factory = { BatchNode(shapes, color) },
        update = {
            set(shapes) { this.shapes = it; markDirty() }
            set(color) { this.color = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            set(rotation) { this.rotation = it; markDirty() }
            set(scale) { this.scale = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
        }
    )
}

/**
 * Conditionally include content based on a predicate
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
 * Iterate over a list and create content for each item
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
