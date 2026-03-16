package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape
import io.fabianterhorst.isometric.paths.Circle

/**
 * A cylindrical shape
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
