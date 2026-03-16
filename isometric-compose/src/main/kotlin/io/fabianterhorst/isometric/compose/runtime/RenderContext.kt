package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.SceneProjector
import io.fabianterhorst.isometric.Shape
import io.fabianterhorst.isometric.Vector

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
    private val scaleOrigin: Point? = null
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
        scaleOrigin = scaleOrigin
    )

    /**
     * Create a new context with additional transforms
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

        return RenderContext(
            width = width,
            height = height,
            renderOptions = renderOptions,
            lightDirection = lightDirection,
            accumulatedPosition = newPosition,
            accumulatedRotation = newRotation,
            accumulatedScale = newScale,
            rotationOrigin = rotationOrigin ?: this.rotationOrigin,
            scaleOrigin = scaleOrigin ?: this.scaleOrigin
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
            scaleOrigin == other.scaleOrigin

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
        return result
    }

    override fun toString(): String =
        "RenderContext(width=$width, height=$height, renderOptions=$renderOptions, lightDirection=$lightDirection)"
}
