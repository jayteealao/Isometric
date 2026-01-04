package io.fabianterhorst.isometric.compose.runtime

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.RenderCommand
import io.fabianterhorst.isometric.Shape

/**
 * Base node for the isometric scene graph.
 * This is the fundamental building block that Compose Runtime manages.
 */
sealed class IsometricNode {
    /**
     * Parent node in the tree
     */
    var parent: IsometricNode? = null

    /**
     * Children of this node
     */
    abstract val children: MutableList<IsometricNode>

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
     * Dirty tracking for efficient rendering
     */
    var isDirty: Boolean = true
        private set

    /**
     * Unique identifier for this node
     */
    val nodeId: String = "node_${System.identityHashCode(this)}"

    /**
     * Mark this node and all ancestors as dirty
     */
    fun markDirty() {
        if (!isDirty) {
            isDirty = true
            parent?.markDirty()
        }
    }

    /**
     * Clear dirty flag (called after rendering)
     */
    fun clearDirty() {
        isDirty = false
        children.forEach { it.clearDirty() }
    }

    /**
     * Apply local transform to a point
     */
    fun applyLocalTransform(point: Point): Point {
        var result = point

        // Apply translation
        result = result.translate(position.x, position.y, position.z)

        // Apply rotation
        if (rotation != 0.0) {
            val origin = rotationOrigin ?: position
            result = result.rotateZ(origin, rotation)
        }

        // Apply scale
        if (scale != 1.0) {
            val origin = scaleOrigin ?: position
            result = result.scale(origin, scale)
        }

        return result
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

        // Render all children
        return children.flatMap { child ->
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

        // Test children in reverse order (front to back)
        for (child in children.asReversed()) {
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

    /**
     * Cached transformed shape (invalidated when dirty)
     */
    private var cachedShape: Shape? = null
    private var cachedCommands: List<RenderCommand>? = null

    override fun render(context: RenderContext): List<RenderCommand> {
        if (!isVisible) return emptyList()

        // Use cached commands if not dirty
        if (!isDirty && cachedCommands != null) {
            return cachedCommands!!
        }

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

        cachedShape = transformedShape

        // Convert shape to render commands
        val commands = transformedShape.paths.map { path ->
            RenderCommand(
                id = "${nodeId}_${path.hashCode()}",
                points = emptyList(), // Will be filled by engine
                color = color,
                originalPath = path,
                originalShape = transformedShape
            )
        }

        cachedCommands = commands
        return commands
    }

    override fun hitTest(x: Double, y: Double, context: RenderContext): IsometricNode? {
        if (!isVisible) return null

        // Render to get projected paths
        val commands = render(context)

        // Test against each path (will be implemented by engine)
        // For now, return this node if any path contains the point
        // This will be properly implemented with the engine's hit testing

        return null // Will be implemented with proper hit testing
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

    private var cachedPath: Path? = null
    private var cachedCommands: List<RenderCommand>? = null

    override fun render(context: RenderContext): List<RenderCommand> {
        if (!isVisible) return emptyList()

        if (!isDirty && cachedCommands != null) {
            return cachedCommands!!
        }

        // Apply transforms to path points
        var transformedPath = path

        // Apply context transforms
        transformedPath = context.applyTransformsToPath(transformedPath)

        // Apply local transforms
        if (position.x != 0.0 || position.y != 0.0 || position.z != 0.0) {
            transformedPath = Path(
                origin = transformedPath.origin.translate(position.x, position.y, position.z),
                points = transformedPath.points.map {
                    it.translate(position.x, position.y, position.z)
                },
                fillColor = transformedPath.fillColor
            )
        }

        if (rotation != 0.0) {
            val origin = rotationOrigin ?: position
            transformedPath = Path(
                origin = transformedPath.origin.rotateZ(origin, rotation),
                points = transformedPath.points.map { it.rotateZ(origin, rotation) },
                fillColor = transformedPath.fillColor
            )
        }

        if (scale != 1.0) {
            val origin = scaleOrigin ?: position
            transformedPath = Path(
                origin = transformedPath.origin.scale(origin, scale),
                points = transformedPath.points.map { it.scale(origin, scale) },
                fillColor = transformedPath.fillColor
            )
        }

        cachedPath = transformedPath

        val commands = listOf(
            RenderCommand(
                id = nodeId,
                points = emptyList(), // Will be filled by engine
                color = color,
                originalPath = transformedPath,
                originalShape = null
            )
        )

        cachedCommands = commands
        return commands
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

    private var cachedCommands: List<RenderCommand>? = null

    override fun render(context: RenderContext): List<RenderCommand> {
        if (!isVisible) return emptyList()

        if (!isDirty && cachedCommands != null) {
            return cachedCommands!!
        }

        val commands = shapes.flatMapIndexed { index, shape ->
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

        cachedCommands = commands
        return commands
    }

    override fun hitTest(x: Double, y: Double, context: RenderContext): IsometricNode? {
        if (!isVisible) return null
        return null
    }
}
