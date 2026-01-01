package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape
import io.fabianterhorst.isometric.paths.Circle

/**
 * A cylindrical shape
 */
class Cylinder(
    origin: Point,
    radius: Double = 1.0,
    vertices: Int = 20,
    height: Double = 1.0
) : Shape(Shape.extrude(Circle(origin, radius, vertices), height).paths) {

    constructor(origin: Point, vertices: Int, height: Double) : this(origin, 1.0, vertices, height)
}
