package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.SceneProjector
import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.Vector

/**
 * Context for rendering that accumulates transforms through the tree hierarchy
 *
 * Marked as @Immutable to prevent unnecessary recomposition when instances don't change
 */
@Immutable
class RenderContext(
    val width: Int,
    val height: Int,
    val renderOptions: RenderOptions,
    val lightDirection: Vector = SceneProjector.DEFAULT_LIGHT_DIRECTION.normalize(),

    // Accumulated transforms
    private val accumulatedPosition: Point = Point(0.0, 0.0, 0.0),
    private val accumulatedRotation: Double = 0.0,
    private val accumulatedScale: Double = 1.0,
    private val rotationOrigin: Point? = null,
    private val scaleOrigin: Point? = null,

    // Accumulated opacity from ancestor GroupNodes
    private val accumulatedAlpha: Float = 1f
) {
    fun copy(
        width: Int = this.width,
        height: Int = this.height,
        renderOptions: RenderOptions = this.renderOptions,
        lightDirection: Vector = this.lightDirection
    ): RenderContext = RenderContext(
        width = width,
        height = height,
        renderOptions = renderOptions,
        lightDirection = lightDirection,
        accumulatedPosition = accumulatedPosition,
        accumulatedRotation = accumulatedRotation,
        accumulatedScale = accumulatedScale,
        rotationOrigin = rotationOrigin,
        scaleOrigin = scaleOrigin,
        accumulatedAlpha = accumulatedAlpha
    )

    /**
     * The effective opacity accumulated from all ancestor [GroupNode]s.
     * Leaf nodes multiply this against their own [IsometricNode.alpha] to obtain
     * the final per-command alpha.
     */
    val effectiveAlpha: Float get() = accumulatedAlpha

    /**
     * Returns a new [RenderContext] whose [accumulatedAlpha] is the product of
     * the current [accumulatedAlpha] and [alpha], clamped to [0, 1].
     *
     * Called by [GroupNode] during tree traversal so every descendant inherits
     * the group's opacity. Multiple nested calls multiply naturally:
     * `withAlpha(0.5f).withAlpha(0.5f)` yields `accumulatedAlpha = 0.25f`.
     *
     * @param alpha The group node's own opacity (must be in 0..1).
     */
    fun withAlpha(alpha: Float): RenderContext = RenderContext(
        width = width,
        height = height,
        renderOptions = renderOptions,
        lightDirection = lightDirection,
        accumulatedPosition = accumulatedPosition,
        accumulatedRotation = accumulatedRotation,
        accumulatedScale = accumulatedScale,
        rotationOrigin = rotationOrigin,
        scaleOrigin = scaleOrigin,
        accumulatedAlpha = (accumulatedAlpha * alpha).coerceIn(0f, 1f)
    )

    /**
     * Create a new context with overridden render options.
     */
    fun withRenderOptions(options: RenderOptions): RenderContext {
        return RenderContext(
            width = width,
            height = height,
            renderOptions = options,
            lightDirection = lightDirection,
            accumulatedPosition = accumulatedPosition,
            accumulatedRotation = accumulatedRotation,
            accumulatedScale = accumulatedScale,
            rotationOrigin = rotationOrigin,
            scaleOrigin = scaleOrigin,
            accumulatedAlpha = accumulatedAlpha
        )
    }

    /**
     * Create a new context with additional transforms applied on top of the accumulated state.
     *
     * @param position Offset to apply in local coordinate space.
     * @param rotation Rotation angle in radians, accumulated with any parent rotation.
     * @param scale Uniform scale factor, accumulated multiplicatively with any parent scale.
     * @param rotationOrigin Pivot point for this node's rotation, in parent coordinate space.
     *   If null, this node rotates around its accumulated position — the parent's rotationOrigin
     *   is **not** inherited (each node's rotation origin is independent). Callers that want the
     *   parent's origin must read and copy it explicitly.
     * @param scaleOrigin Pivot point for scaling. If null, inherits the parent's scaleOrigin.
     */
    fun withTransform(
        position: Point = Point(0.0, 0.0, 0.0),
        rotation: Double = 0.0,
        scale: Double = 1.0,
        rotationOrigin: Point? = null,
        scaleOrigin: Point? = null
    ): RenderContext {
        // Transform child's local position into parent's coordinate space:
        // first scale by accumulated scale, then rotate by accumulated rotation.
        var childPosInParentSpace = position

        if (accumulatedScale != 1.0) {
            childPosInParentSpace = Point(
                childPosInParentSpace.x * accumulatedScale,
                childPosInParentSpace.y * accumulatedScale,
                childPosInParentSpace.z * accumulatedScale
            )
        }

        if (accumulatedRotation != 0.0) {
            childPosInParentSpace = childPosInParentSpace.rotateZ(
                Point.ORIGIN, accumulatedRotation
            )
        }

        // Accumulate position (now correctly in world space)
        val newPosition = Point(
            accumulatedPosition.x + childPosInParentSpace.x,
            accumulatedPosition.y + childPosInParentSpace.y,
            accumulatedPosition.z + childPosInParentSpace.z
        )

        // Accumulate rotation
        val newRotation = accumulatedRotation + rotation

        // Accumulate scale
        val newScale = accumulatedScale * scale

        // L6: rotationOrigin does NOT inherit from parent. A child with null rotationOrigin
        // rotates around its own accumulated position (handled in applyTransformsToShape/Path/Point),
        // not around whatever pivot the parent happened to use. Inheritance was surprising:
        // a parent's explicit rotation pivot silently bled into all descendants that did not
        // set their own, producing incorrect rotation centers. Call sites that want the
        // parent's origin must read and copy it explicitly.
        return RenderContext(
            width = width,
            height = height,
            renderOptions = renderOptions,
            lightDirection = lightDirection,
            accumulatedPosition = newPosition,
            accumulatedRotation = newRotation,
            accumulatedScale = newScale,
            rotationOrigin = rotationOrigin,
            scaleOrigin = scaleOrigin ?: this.scaleOrigin,
            accumulatedAlpha = accumulatedAlpha
        )
    }

    /**
     * Apply accumulated transforms to a shape
     */
    fun applyTransformsToShape(shape: Shape): Shape {
        var result = shape

        // Apply accumulated translation
        if (accumulatedPosition.x != 0.0 ||
            accumulatedPosition.y != 0.0 ||
            accumulatedPosition.z != 0.0) {
            result = result.translate(
                accumulatedPosition.x,
                accumulatedPosition.y,
                accumulatedPosition.z
            )
        }

        // Apply accumulated rotation
        if (accumulatedRotation != 0.0) {
            val origin = rotationOrigin ?: accumulatedPosition
            result = result.rotateZ(origin, accumulatedRotation)
        }

        // Apply accumulated scale
        if (accumulatedScale != 1.0) {
            val origin = scaleOrigin ?: accumulatedPosition
            result = result.scale(origin, accumulatedScale)
        }

        return result
    }

    /**
     * Apply accumulated transforms to a path
     */
    fun applyTransformsToPath(path: Path): Path {
        var result = path

        // Apply accumulated translation
        if (accumulatedPosition.x != 0.0 ||
            accumulatedPosition.y != 0.0 ||
            accumulatedPosition.z != 0.0) {
            result = result.translate(
                accumulatedPosition.x,
                accumulatedPosition.y,
                accumulatedPosition.z
            )
        }

        // Apply accumulated rotation
        if (accumulatedRotation != 0.0) {
            val origin = rotationOrigin ?: accumulatedPosition
            result = result.rotateZ(origin, accumulatedRotation)
        }

        // Apply accumulated scale
        if (accumulatedScale != 1.0) {
            val origin = scaleOrigin ?: accumulatedPosition
            result = result.scale(origin, accumulatedScale)
        }

        return result
    }

    /**
     * Apply accumulated transforms to a point
     */
    fun applyTransformsToPoint(point: Point): Point {
        var result = point

        // Apply accumulated translation
        result = result.translate(
            accumulatedPosition.x,
            accumulatedPosition.y,
            accumulatedPosition.z
        )

        // Apply accumulated rotation
        if (accumulatedRotation != 0.0) {
            val origin = rotationOrigin ?: accumulatedPosition
            result = result.rotateZ(origin, accumulatedRotation)
        }

        // Apply accumulated scale
        if (accumulatedScale != 1.0) {
            val origin = scaleOrigin ?: accumulatedPosition
            result = result.scale(origin, accumulatedScale)
        }

        return result
    }

    override fun equals(other: Any?): Boolean =
        other is RenderContext &&
            width == other.width &&
            height == other.height &&
            renderOptions == other.renderOptions &&
            lightDirection == other.lightDirection &&
            accumulatedPosition == other.accumulatedPosition &&
            accumulatedRotation == other.accumulatedRotation &&
            accumulatedScale == other.accumulatedScale &&
            rotationOrigin == other.rotationOrigin &&
            scaleOrigin == other.scaleOrigin &&
            accumulatedAlpha == other.accumulatedAlpha

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + renderOptions.hashCode()
        result = 31 * result + lightDirection.hashCode()
        result = 31 * result + accumulatedPosition.hashCode()
        result = 31 * result + accumulatedRotation.hashCode()
        result = 31 * result + accumulatedScale.hashCode()
        result = 31 * result + (rotationOrigin?.hashCode() ?: 0)
        result = 31 * result + (scaleOrigin?.hashCode() ?: 0)
        result = 31 * result + accumulatedAlpha.hashCode()
        return result
    }

    override fun toString(): String =
        "RenderContext(width=$width, height=$height, renderOptions=$renderOptions, lightDirection=$lightDirection)"
}
