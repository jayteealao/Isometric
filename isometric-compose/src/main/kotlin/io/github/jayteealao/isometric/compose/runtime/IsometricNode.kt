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
     *
     * This property is the backing store for the `nodeId` parameter accepted by the
     * `IsoShape`, `IsoPath`, and related composables. Reading [nodeId] always returns
     * the effective identifier — [explicitNodeId] when the caller supplied one, or the
     * auto-generated id otherwise.
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
     * Opacity multiplier for this node's rendered output. Must be in 0..1 range.
     *
     * **Leaf nodes** ([ShapeNode], [PathNode], [BatchNode], [CustomRenderNode]): the node's own
     * alpha is multiplied against the accumulated group alpha from all ancestor [GroupNode]s,
     * then applied to the render command color. A fully-opaque leaf (`alpha = 1.0`) inside a
     * half-transparent group (`alpha = 0.5`) produces a command color with alpha × 0.5.
     *
     * **GroupNode**: the value is multiplied into the [RenderContext] and propagated to all
     * descendants. Nested groups multiply their alphas — a group with `alpha = 0.5` inside
     * another group with `alpha = 0.5` yields an effective opacity of `0.25` for all leaves.
     * A [GroupNode] with `alpha = 0` skips rendering its entire subtree entirely (no render
     * commands are produced, at zero traversal cost).
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
     * Callback invoked when this node is double-tapped.
     * Dispatched by [IsometricScene] after double-tap detection and hit-test resolution.
     */
    var onDoubleClick: (() -> Unit)? = null

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
     * Applies this node's [alpha] to [color], returning a tinted copy only when
     * [alpha] is less than 1. Avoids an object allocation on fully-opaque nodes.
     */
    protected fun applyAlpha(color: IsoColor): IsoColor =
        if (alpha < 1f) color.withAlpha(alpha) else color

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
        // alpha=0 skips the entire subtree — no traversal cost, no render commands.
        if (alpha == 0f) return

        // Apply per-node render options override if set
        val effectiveContext = if (renderOptions != null) {
            context.withRenderOptions(renderOptions!!)
        } else {
            context
        }

        // Propagate this group's alpha into the child context before applying transforms.
        // withAlpha multiplies accumulatedAlpha * alpha so nested groups multiply naturally.
        val alphaContext = if (alpha < 1f) effectiveContext.withAlpha(alpha) else effectiveContext

        // Create child context with accumulated transforms (preserves accumulatedAlpha)
        val childContext = alphaContext.withTransform(
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
        val effectiveAlpha = context.effectiveAlpha * alpha
        val effectiveColor = if (effectiveAlpha < 1f) color.withAlpha(effectiveAlpha) else color

        // Convert shape to render commands — adds directly to accumulator
        for (path in transformedShape.paths) {
            output.add(
                RenderCommand(
                    commandId = "${nodeId}_${path.hashCode()}",
                    points = emptyList(), // Template — engine.projectScene() produces new commands with projected points
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
        val effectiveAlpha = context.effectiveAlpha * alpha
        val effectiveColor = if (effectiveAlpha < 1f) color.withAlpha(effectiveAlpha) else color

        output.add(
            RenderCommand(
                commandId = nodeId,
                points = emptyList(), // Template — engine.projectScene() produces new commands with projected points
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

        val effectiveAlpha = context.effectiveAlpha * alpha
        val effectiveColor = if (effectiveAlpha < 1f) color.withAlpha(effectiveAlpha) else color

        shapes.forEachIndexed { index, shape ->
            val transformedShape = localContext.applyTransformsToShape(shape)

            for (path in transformedShape.paths) {
                output.add(
                    RenderCommand(
                        commandId = "${nodeId}_${index}_${path.hashCode()}",
                        points = emptyList(),
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
        val effectiveAlpha = context.effectiveAlpha * alpha
        if (effectiveAlpha < 1f) {
            for (cmd in commands) {
                output.add(
                    RenderCommand(
                        commandId = cmd.commandId,
                        points = cmd.points,
                        color = cmd.color.withAlpha(effectiveAlpha),
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
