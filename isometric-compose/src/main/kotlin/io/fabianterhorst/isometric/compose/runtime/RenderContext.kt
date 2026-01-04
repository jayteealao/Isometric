package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Shape
import io.fabianterhorst.isometric.Vector

/**
 * Context for rendering that accumulates transforms through the tree hierarchy
 *
 * Marked as @Immutable to prevent unnecessary recomposition when instances don't change
 */
@Immutable
data class RenderContext(
    val width: Int,
    val height: Int,
    val renderOptions: RenderOptions,
    val lightDirection: Vector = Vector(0.0, 1.0, 1.0).normalize(),

    // Accumulated transforms
    private val accumulatedPosition: Point = Point(0.0, 0.0, 0.0),
    private val accumulatedRotation: Double = 0.0,
    private val accumulatedScale: Double = 1.0,
    private val rotationOrigin: Point? = null,
    private val scaleOrigin: Point? = null
) {

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
        // Accumulate position
        val newPosition = Point(
            accumulatedPosition.x + position.x,
            accumulatedPosition.y + position.y,
            accumulatedPosition.z + position.z
        )

        // Accumulate rotation
        val newRotation = accumulatedRotation + rotation

        // Accumulate scale
        val newScale = accumulatedScale * scale

        return copy(
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
            result = Path(
                origin = result.origin.translate(
                    accumulatedPosition.x,
                    accumulatedPosition.y,
                    accumulatedPosition.z
                ),
                points = result.points.map {
                    it.translate(
                        accumulatedPosition.x,
                        accumulatedPosition.y,
                        accumulatedPosition.z
                    )
                },
                fillColor = result.fillColor
            )
        }

        // Apply accumulated rotation
        if (accumulatedRotation != 0.0) {
            val origin = rotationOrigin ?: accumulatedPosition
            result = Path(
                origin = result.origin.rotateZ(origin, accumulatedRotation),
                points = result.points.map { it.rotateZ(origin, accumulatedRotation) },
                fillColor = result.fillColor
            )
        }

        // Apply accumulated scale
        if (accumulatedScale != 1.0) {
            val origin = scaleOrigin ?: accumulatedPosition
            result = Path(
                origin = result.origin.scale(origin, accumulatedScale),
                points = result.points.map { it.scale(origin, accumulatedScale) },
                fillColor = result.fillColor
            )
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
}
