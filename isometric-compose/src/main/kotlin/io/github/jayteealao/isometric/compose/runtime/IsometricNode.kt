package io.github.jayteealao.isometric.compose.runtime

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.Shape
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
     * Optional per-node render options override.
     * When non-null, overrides the inherited render options for this node and its subtree.
     * When null (default), inherits from the parent context.
     */
    var renderOptions: RenderOptions? = null

    /**
     * Dirty tracking for efficient rendering.
     * Volatile to ensure visibility across threads (Applier vs Canvas draw).
     */
    @Volatile
    var isDirty: Boolean = true
        private set

    /**
     * Internal auto-generated identifier. Guaranteed unique across the process lifetime.
     */
    private val generatedNodeId: String = "node_${nextId.getAndIncrement()}"

    /**
     * Optional caller-supplied identifier. When non-null, becomes the effective [nodeId].
     * Must be non-blank when set.
     */
    var explicitNodeId: String? = null
        set(value) {
            require(value == null || value.isNotBlank()) { "nodeId must be non-blank when provided" }
            field = value
        }

    /**
     * Effective identifier for this node.
     *
     * Returns [explicitNodeId] if the caller provided one, otherwise falls back to
     * the auto-generated [generatedNodeId]. Used by hit-test resolution, render command
     * ownership, and diagnostics.
     */
    val nodeId: String
        get() = explicitNodeId ?: generatedNodeId

    /**
     * Opacity multiplier for this node's rendered output.
     * Applied at render time by scaling the command color's alpha channel.
     * Must be in 0..1 range.
     */
    var alpha: Float = 1f
        set(value) {
            require(value in 0f..1f) { "alpha must be in 0..1, got $value" }
            field = value
        }

    /**
     * Callback invoked when this node is tapped.
     * Dispatched by [IsometricScene] after hit-test resolution.
     */
    var onClick: (() -> Unit)? = null

    /**
     * Callback invoked when this node is long-pressed.
     * Dispatched by [IsometricScene] after long-press detection and hit-test resolution.
     */
    var onLongClick: (() -> Unit)? = null

    /**
     * Optional tag for testing and diagnostics.
     * Does not affect rendering or hit testing.
     */
    var testTag: String? = null

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

}

/**
 * Container node that groups other nodes and applies transforms
 */
class GroupNode : IsometricNode() {
    internal override val children = mutableListOf<IsometricNode>()

    override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
        if (!isVisible) return

        // Apply per-node render options override if set
        val effectiveContext = if (renderOptions != null) {
            context.withRenderOptions(renderOptions!!)
        } else {
            context
        }

        // Create child context with accumulated transforms
        val childContext = effectiveContext.withTransform(
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

        val effectiveContext = if (renderOptions != null) {
            context.withRenderOptions(renderOptions!!)
        } else {
            context
        }
        val localContext = effectiveContext.withTransform(
            position = position,
            rotation = rotation,
            scale = scale,
            rotationOrigin = rotationOrigin,
            scaleOrigin = scaleOrigin
        )
        val transformedShape = localContext.applyTransformsToShape(shape)
        val effectiveColor = if (alpha < 1f) color.withAlpha(alpha) else color

        // Convert shape to render commands — adds directly to accumulator
        for (path in transformedShape.paths) {
            output.add(
                RenderCommand(
                    commandId = "${nodeId}_${path.hashCode()}",
                    points = DoubleArray(0), // Template — engine.projectScene() produces new commands with projected points
                    color = effectiveColor,
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

        val effectiveContext = if (renderOptions != null) {
            context.withRenderOptions(renderOptions!!)
        } else {
            context
        }
        val localContext = effectiveContext.withTransform(
            position = position,
            rotation = rotation,
            scale = scale,
            rotationOrigin = rotationOrigin,
            scaleOrigin = scaleOrigin
        )
        val transformedPath = localContext.applyTransformsToPath(path)
        val effectiveColor = if (alpha < 1f) color.withAlpha(alpha) else color

        output.add(
            RenderCommand(
                commandId = nodeId,
                points = DoubleArray(0), // Template — engine.projectScene() produces new commands with projected points
                color = effectiveColor,
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

        val effectiveContext = if (renderOptions != null) {
            context.withRenderOptions(renderOptions!!)
        } else {
            context
        }
        val localContext = effectiveContext.withTransform(
            position = position,
            rotation = rotation,
            scale = scale,
            rotationOrigin = rotationOrigin,
            scaleOrigin = scaleOrigin
        )

        val effectiveColor = if (alpha < 1f) color.withAlpha(alpha) else color

        shapes.forEachIndexed { index, shape ->
            val transformedShape = localContext.applyTransformsToShape(shape)

            for (path in transformedShape.paths) {
                output.add(
                    RenderCommand(
                        commandId = "${nodeId}_${index}_${path.hashCode()}",
                        points = DoubleArray(0),
                        color = effectiveColor,
                        originalPath = path,
                        originalShape = transformedShape,
                        ownerNodeId = nodeId
                    )
                )
            }
        }
    }

}

/**
 * Node that delegates rendering to a user-provided function.
 *
 * This is the escape hatch for users who need geometry beyond the built-in shapes.
 * The [renderFunction] receives the accumulated [RenderContext] and the node's
 * [nodeId] (for use in [RenderCommand.ownerNodeId]), and returns render commands
 * that will be included in the scene's depth sorting and drawing.
 */
class CustomRenderNode(
    var renderFunction: (context: RenderContext, nodeId: String) -> List<RenderCommand>
) : IsometricNode() {

    override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
        if (!isVisible) return

        // Apply per-node render options override if set
        val effectiveContext = if (renderOptions != null) {
            context.withRenderOptions(renderOptions!!)
        } else {
            context
        }

        val localContext = effectiveContext.withTransform(
            position = position,
            rotation = rotation,
            scale = scale,
            rotationOrigin = rotationOrigin,
            scaleOrigin = scaleOrigin
        )

        val commands = renderFunction(localContext, nodeId)
        if (alpha < 1f) {
            for (cmd in commands) {
                output.add(
                    RenderCommand(
                        commandId = cmd.commandId,
                        points = cmd.points,
                        color = cmd.color.withAlpha(alpha),
                        originalPath = cmd.originalPath,
                        originalShape = cmd.originalShape,
                        ownerNodeId = cmd.ownerNodeId
                    )
                )
            }
        } else {
            output += commands
        }
    }
}
