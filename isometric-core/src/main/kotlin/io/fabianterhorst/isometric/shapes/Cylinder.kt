package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape
import io.fabianterhorst.isometric.paths.Circle

/**
 * A cylindrical shape created by extruding a circular cross-section along the z-axis.
 *
 * The circular base is approximated as a regular polygon; the [vertices] parameter
 * controls the resolution of that approximation. Higher values produce a smoother
 * appearance at the cost of more polygons.
 *
 * @param position The center of the base circle (default [Point.ORIGIN])
 * @param radius The radius of the circular cross-section (must be positive, default 1.0)
 * @param height The extent along the z-axis (must be positive, default 1.0)
 * @param vertices The number of sides used to approximate the circle (minimum 3, default 20).
 *   Higher values produce a smoother cylinder but generate more polygons.
 */
class Cylinder @JvmOverloads constructor(
    val position: Point = Point.ORIGIN,
    val radius: Double = 1.0,
    val height: Double = 1.0,
    val vertices: Int = 20
) : Shape(Shape.extrude(Circle(position, radius, vertices), height).paths) {
    init {
        require(radius > 0.0) { "Cylinder radius must be positive, got $radius" }
        require(vertices >= 3) { "Cylinder needs at least 3 vertices, got $vertices" }
        require(height > 0.0) { "Cylinder height must be positive, got $height" }
    }

    override fun translate(dx: Double, dy: Double, dz: Double): Cylinder =
        Cylinder(position.translate(dx, dy, dz), radius, height, vertices)
}
