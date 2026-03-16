package io.fabianterhorst.isometric.compose.runtime

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.RenderCommand
import io.fabianterhorst.isometric.Shape
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Base node for the isometric scene graph.
 * This is the fundamental building block that Compose Runtime manages.
 * Open for extension to support custom node types via low-level ComposeNode primitives.
 */
abstract class IsometricNode {
    companion object {
        private val nextId = AtomicLong(0)

        /**
         * Shared immutable empty list for leaf nodes. Avoids per-node ArrayList allocation.
         * Throws [UnsupportedOperationException] on mutation — correct behavior since
         * leaf nodes should never have children added by the Applier.
         */
        @Suppress("UNCHECKED_CAST")
        private val LEAF_CHILDREN: MutableList<IsometricNode> =
            Collections.emptyList<IsometricNode>() as MutableList<IsometricNode>
    }

    /**
     * Parent node in the tree
     */
    var parent: IsometricNode? = null

    /**
     * Mutable children list — only accessed by the Applier for mutations.
     * Internal to prevent external consumers from bypassing dirty tracking.
     * Provides a default empty list for leaf nodes; container nodes (e.g. [GroupNode])
     * override this with their own mutable list.
     */
    internal open val children: MutableList<IsometricNode> = LEAF_CHILDREN

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
     * Unique identifier for this node.
     * Uses a monotonically increasing atomic counter to guarantee collision-free IDs
     * across the lifetime of the process, even under concurrent creation.
     */
    val nodeId: String = "node_${nextId.getAndIncrement()}"

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
    fun markClean() {
        isDirty = false
        childrenSnapshot.forEach { it.markClean() }
    }

    /**
     * Render this node and its children into the given accumulator list.
     * Eliminates intermediate list allocations compared to a returning `render()` method.
     */
    abstract fun renderTo(output: MutableList<RenderCommand>, context: RenderContext)

    protected fun applyLocalTransforms(shape: Shape): Shape {
        var result = shape.translate(position.x, position.y, position.z)
        if (rotation != 0.0) {
            result = result.rotateZ(rotationOrigin ?: position, rotation)
        }
        if (scale != 1.0) {
            result = result.scale(scaleOrigin ?: position, scale)
        }
        return result
    }

    protected fun applyLocalTransforms(path: Path): Path {
        var result = path.translate(position.x, position.y, position.z)
        if (rotation != 0.0) {
            result = result.rotateZ(rotationOrigin ?: position, rotation)
        }
        if (scale != 1.0) {
            result = result.scale(scaleOrigin ?: position, scale)
        }
        return result
    }
}

/**
 * Container node that groups other nodes and applies transforms
 */
class GroupNode : IsometricNode() {
    internal override val children = mutableListOf<IsometricNode>()

    override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
        if (!isVisible) return

        // Create child context with accumulated transforms
        val childContext = context.withTransform(
            position = position,
            rotation = rotation,
            scale = scale,
            rotationOrigin = rotationOrigin,
            scaleOrigin = scaleOrigin
        )

        // Render all children from the thread-safe snapshot — zero intermediate allocations
        for (child in childrenSnapshot) {
            child.renderTo(output, childContext)
        }
    }

}

/**
 * Node representing a 3D shape
 */
class ShapeNode(
    var shape: Shape,
    var color: IsoColor
) : IsometricNode() {

    override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
        if (!isVisible) return

        val transformedShape = applyLocalTransforms(context.applyTransformsToShape(shape))

        // Convert shape to render commands — adds directly to accumulator
        for (path in transformedShape.paths) {
            output.add(
                RenderCommand(
                    commandId = "${nodeId}_${path.hashCode()}",
                    points = emptyList(), // Template — engine.projectScene() produces new commands with projected points
                    color = color,
                    originalPath = path,
                    originalShape = transformedShape,
                    ownerNodeId = nodeId
                )
            )
        }
    }

}

/**
 * Node representing a raw 2D path
 */
class PathNode(
    var path: Path,
    var color: IsoColor
) : IsometricNode() {

    override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
        if (!isVisible) return

        val transformedPath = applyLocalTransforms(context.applyTransformsToPath(path))

        output.add(
            RenderCommand(
                commandId = nodeId,
                points = emptyList(), // Template — engine.projectScene() produces new commands with projected points
                color = color,
                originalPath = transformedPath,
                originalShape = null,
                ownerNodeId = nodeId
            )
        )
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

    override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
        if (!isVisible) return

        shapes.forEachIndexed { index, shape ->
            val transformedShape = applyLocalTransforms(context.applyTransformsToShape(shape))

            for (path in transformedShape.paths) {
                output.add(
                    RenderCommand(
                        commandId = "${nodeId}_${index}_${path.hashCode()}",
                        points = emptyList(),
                        color = color,
                        originalPath = path,
                        originalShape = transformedShape,
                        ownerNodeId = nodeId
                    )
                )
            }
        }
    }

}
