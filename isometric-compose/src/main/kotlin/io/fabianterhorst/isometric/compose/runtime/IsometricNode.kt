package io.fabianterhorst.isometric.compose.runtime

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.RenderCommand
import io.fabianterhorst.isometric.Shape

/**
 * Base node for the isometric scene graph.
 * This is the fundamental building block that Compose Runtime manages.
 * Open for extension to support custom node types via low-level ComposeNode primitives.
 */
abstract class IsometricNode {
    /**
     * Parent node in the tree
     */
    var parent: IsometricNode? = null

    /**
     * Mutable children list — only accessed by the Applier for mutations.
     */
    abstract val children: MutableList<IsometricNode>

    /**
     * Thread-safe snapshot of children for rendering and traversal.
     * Updated atomically after Applier mutations complete (copy-on-write).
     */
    @Volatile
    var childrenSnapshot: List<IsometricNode> = emptyList()
        private set

    /**
     * Update the snapshot from the current mutable children list.
     * Called by the Applier after structural mutations.
     */
    fun updateChildrenSnapshot() {
        childrenSnapshot = children.toList()
    }

    /**
     * Local transform properties
     */
    var position: Point = Point(0.0, 0.0, 0.0)
    var rotation: Double = 0.0
    var scale: Double = 1.0
    var rotationOrigin: Point? = null
    var scaleOrigin: Point? = null

    /**
     * Visibility flag
     */
    var isVisible: Boolean = true

    /**
     * Dirty tracking for efficient rendering.
     * Volatile to ensure visibility across threads (Applier vs Canvas draw).
     */
    @Volatile
    var isDirty: Boolean = true
        private set

    /**
     * Unique identifier for this node
     */
    val nodeId: String = "node_${System.identityHashCode(this)}"

    /**
     * Callback invoked when dirty propagation reaches a root node (parent == null).
     * Used by IsometricScene to trigger Canvas invalidation via Compose state.
     */
    var onDirty: (() -> Unit)? = null

    /**
     * Mark this node and all ancestors as dirty.
     * When propagation reaches the root (no parent), invokes [onDirty] to
     * trigger a Canvas redraw via Compose's snapshot system.
     */
    fun markDirty() {
        if (!isDirty) {
            isDirty = true
            if (parent != null) {
                parent?.markDirty()
            } else {
                onDirty?.invoke()
            }
        }
    }

    /**
     * Clear dirty flag (called after rendering)
     */
    fun clearDirty() {
        isDirty = false
        childrenSnapshot.forEach { it.clearDirty() }
    }

    /**
     * Render this node and its children to a list of render commands
     */
    abstract fun render(context: RenderContext): List<RenderCommand>

    /**
     * Perform hit testing on this node
     * @return The node that was hit, or null
     */
    abstract fun hitTest(x: Double, y: Double, context: RenderContext): IsometricNode?
}

/**
 * Container node that groups other nodes and applies transforms
 */
class GroupNode : IsometricNode() {
    override val children = mutableListOf<IsometricNode>()

    override fun render(context: RenderContext): List<RenderCommand> {
        if (!isVisible) return emptyList()

        // Create child context with accumulated transforms
        val childContext = context.withTransform(
            position = position,
            rotation = rotation,
            scale = scale,
            rotationOrigin = rotationOrigin,
            scaleOrigin = scaleOrigin
        )

        // Render all children from the thread-safe snapshot
        return childrenSnapshot.flatMap { child ->
            child.render(childContext)
        }
    }

    override fun hitTest(x: Double, y: Double, context: RenderContext): IsometricNode? {
        if (!isVisible) return null

        val childContext = context.withTransform(
            position = position,
            rotation = rotation,
            scale = scale,
            rotationOrigin = rotationOrigin,
            scaleOrigin = scaleOrigin
        )

        // Test children in reverse order (front to back) using thread-safe snapshot
        for (child in childrenSnapshot.asReversed()) {
            val hit = child.hitTest(x, y, childContext)
            if (hit != null) return hit
        }

        return null
    }
}

/**
 * Node representing a 3D shape
 */
class ShapeNode(
    var shape: Shape,
    var color: IsoColor
) : IsometricNode() {
    override val children = mutableListOf<IsometricNode>()

    override fun render(context: RenderContext): List<RenderCommand> {
        if (!isVisible) return emptyList()

        // Apply accumulated transforms from context
        var transformedShape = context.applyTransformsToShape(shape)

        // Apply local transforms
        transformedShape = transformedShape.translate(position.x, position.y, position.z)

        if (rotation != 0.0) {
            val origin = rotationOrigin ?: position
            transformedShape = transformedShape.rotateZ(origin, rotation)
        }

        if (scale != 1.0) {
            val origin = scaleOrigin ?: position
            transformedShape = transformedShape.scale(origin, scale)
        }

        // Convert shape to render commands
        return transformedShape.paths.map { path ->
            RenderCommand(
                id = "${nodeId}_${path.hashCode()}",
                points = emptyList(), // Will be filled by engine
                color = color,
                originalPath = path,
                originalShape = transformedShape
            )
        }
    }

    override fun hitTest(x: Double, y: Double, context: RenderContext): IsometricNode? {
        if (!isVisible) return null
        return null // Stub — will be implemented with engine's findItemAt
    }
}

/**
 * Node representing a raw 2D path
 */
class PathNode(
    var path: Path,
    var color: IsoColor
) : IsometricNode() {
    override val children = mutableListOf<IsometricNode>()

    override fun render(context: RenderContext): List<RenderCommand> {
        if (!isVisible) return emptyList()

        // Apply transforms to path points
        var transformedPath = path

        // Apply context transforms
        transformedPath = context.applyTransformsToPath(transformedPath)

        // Apply local transforms
        if (position.x != 0.0 || position.y != 0.0 || position.z != 0.0) {
            transformedPath = transformedPath.translate(position.x, position.y, position.z)
        }

        if (rotation != 0.0) {
            val origin = rotationOrigin ?: position
            transformedPath = transformedPath.rotateZ(origin, rotation)
        }

        if (scale != 1.0) {
            val origin = scaleOrigin ?: position
            transformedPath = transformedPath.scale(origin, scale)
        }

        return listOf(
            RenderCommand(
                id = nodeId,
                points = emptyList(), // Will be filled by engine
                color = color,
                originalPath = transformedPath,
                originalShape = null
            )
        )
    }

    override fun hitTest(x: Double, y: Double, context: RenderContext): IsometricNode? {
        if (!isVisible) return null
        return null // Will be implemented with proper hit testing
    }
}

/**
 * Node for batch rendering multiple shapes with the same color
 * Useful for performance optimization
 */
class BatchNode(
    var shapes: List<Shape>,
    var color: IsoColor
) : IsometricNode() {
    override val children = mutableListOf<IsometricNode>()

    override fun render(context: RenderContext): List<RenderCommand> {
        if (!isVisible) return emptyList()

        return shapes.flatMapIndexed { index, shape ->
            var transformedShape = context.applyTransformsToShape(shape)
            transformedShape = transformedShape.translate(position.x, position.y, position.z)

            if (rotation != 0.0) {
                val origin = rotationOrigin ?: position
                transformedShape = transformedShape.rotateZ(origin, rotation)
            }

            if (scale != 1.0) {
                val origin = scaleOrigin ?: position
                transformedShape = transformedShape.scale(origin, scale)
            }

            transformedShape.paths.map { path ->
                RenderCommand(
                    id = "${nodeId}_${index}_${path.hashCode()}",
                    points = emptyList(),
                    color = color,
                    originalPath = path,
                    originalShape = transformedShape
                )
            }
        }
    }

    override fun hitTest(x: Double, y: Double, context: RenderContext): IsometricNode? {
        if (!isVisible) return null
        return null
    }
}
