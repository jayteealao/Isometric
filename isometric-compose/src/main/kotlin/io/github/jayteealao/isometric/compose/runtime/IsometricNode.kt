package io.github.jayteealao.isometric.compose.runtime

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.MaterialData
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.OctahedronFace
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.PrismFace
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.PyramidFace
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Provides per-face UV coordinates for textured rendering.
 *
 * Implementations receive the original (pre-transform) [Shape] and a 0-based face index,
 * and return a packed `FloatArray` of `[u0,v0, u1,v1, ...]` or null if no UVs apply.
 *
 * This is a `fun interface` so it can be constructed from a lambda:
 * ```kotlin
 * UvCoordProvider { shape, faceIndex -> floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f) }
 * ```
 */
fun interface UvCoordProvider {
    fun provide(shape: Shape, faceIndex: Int): FloatArray?
}

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
 * Node representing a 3D shape.
 *
 * @property color Base color for this shape. When [material] is null, this is the fill
 *   color (flat-color rendering). When [material] is non-null, this serves as the base
 *   tint — textured renderers multiply the sampled texture color by this value
 *   (e.g., `textureSample * color` in the GPU fragment shader). The `isometric-shader`
 *   module's overloaded `Shape()` composable derives this color from the material
 *   (e.g., `FlatColor.color` for flat materials, `LocalDefaultColor` for textured).
 * @property material Optional material data for textured or per-face rendering. When null,
 *   the renderer uses [color] for flat-color fill. When non-null, the renderer interprets
 *   the material to determine how each face is painted. Set by the `isometric-shader`
 *   module's composable overloads — not typically set directly.
 */
class ShapeNode(
    var shape: Shape,
    var color: IsoColor,
    var material: MaterialData? = null,
    /**
     * Optional UV coordinate provider. When set, called for each face during
     * [renderTo] with the original (pre-transform) shape and the 0-based face index.
     *
     * Set by the `isometric-shader` module's composable overloads — not typically set directly.
     */
    var uvProvider: UvCoordProvider? = null,
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
        for ((index, path) in transformedShape.paths.withIndex()) {
            output.add(
                RenderCommand(
                    commandId = "${nodeId}_${path.hashCode()}",
                    points = DoubleArray(0), // Template — engine.projectScene() produces new commands with projected points
                    color = effectiveColor,
                    originalPath = path,
                    originalShape = transformedShape,
                    ownerNodeId = nodeId,
                    material = material,
                    uvCoords = uvProvider?.provide(shape, index),
                    // Dispatch on the pre-transform `shape` field, not `transformedShape`.
                    // `Shape.rotateZ` / `Shape.scale` return the base `Shape` type and
                    // cannot be overridden, so a transformed Pyramid/Octahedron would
                    // fail the `is <Shape>` check and null out faceType, which in turn
                    // makes `PerFace.resolveForFace(null)` fall back to `default`.
                    // Face identity is structural (path-index order), not transform-dependent.
                    faceType = when (shape) {
                        is Prism -> PrismFace.fromPathIndex(index)
                        is Octahedron -> OctahedronFace.fromPathIndex(index)
                        is Pyramid -> PyramidFace.fromPathIndex(index)
                        else -> null
                    },
                    faceVertexCount = path.points.size,
                )
            )
        }
    }

}

/**
 * Node representing a raw 2D path.
 *
 * @property color Base color / tint. See [ShapeNode] for the color/material contract.
 * @property material Optional material data. See [ShapeNode] for the color/material contract.
 */
class PathNode(
    var path: Path,
    var color: IsoColor,
    var material: MaterialData? = null,
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
                ownerNodeId = nodeId,
                material = material,
                faceVertexCount = transformedPath.points.size,
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
    var color: IsoColor,
    var material: MaterialData? = null,
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
                        ownerNodeId = nodeId,
                        faceVertexCount = path.points.size,
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
                        ownerNodeId = cmd.ownerNodeId,
                        material = cmd.material,
                        uvCoords = cmd.uvCoords,
                        faceType = cmd.faceType,
                        faceVertexCount = cmd.faceVertexCount,
                    )
                )
            }
        } else {
            output += commands
        }
    }
}
