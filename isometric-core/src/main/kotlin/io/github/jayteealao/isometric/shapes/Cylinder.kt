package io.github.jayteealao.isometric.shapes

import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.Shape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A cylindrical shape created by extruding a circular cross-section along the z-axis.
 *
 * The circular base is approximated as a regular polygon; the [vertices] parameter
 * controls the resolution of that approximation. Higher values produce a smoother
 * appearance at the cost of more polygons.
 *
 * The side-face strip uses **seam vertex duplication** so UV texture coordinates
 * wrap continuously around the barrel without a smearing artifact at the u=1 seam.
 * Internally each ring (base + top) carries `vertices + 1` distinct [Point] instances;
 * the point at slot `vertices` is geometrically coincident with the point at slot 0
 * but is a distinct object, letting per-face UV assignment emit `u=0` for the first
 * side quad and `u=1` for the last side quad without aliasing.
 *
 * **Textured rendering ceiling:** `RenderCommand` validates `faceVertexCount in 3..24`
 * (see `isometric-shader` `RenderCommand.init`). Because the cap faces are N-gons with
 * `faceVertexCount = vertices`, values above 24 are rejected at render time. This class
 * pre-validates to surface the error at construction rather than at first draw.
 *
 * @param position The center of the base circle (default [Point.ORIGIN])
 * @param radius The radius of the circular cross-section (must be positive, default 1.0)
 * @param height The extent along the z-axis (must be positive, default 1.0)
 * @param vertices The number of sides used to approximate the circle (must be in 3..24,
 *   default 20). Higher values produce a smoother cylinder but generate more polygons.
 *   The vertex range was tightened from a permissive `Int.MAX_VALUE` to `3..24` in the
 *   WebGPU pipeline-cleanup slice; values outside this range now throw
 *   `IllegalArgumentException` at construction.
 */
class Cylinder @JvmOverloads constructor(
    val position: Point = Point.ORIGIN,
    val radius: Double = 1.0,
    val height: Double = 1.0,
    val vertices: Int = 20
) : Shape(buildCylinderPaths(position, radius, height, vertices)) {
    init {
        require(radius > 0.0) { "Cylinder radius must be positive, got $radius" }
        require(vertices in 3..24) {
            "Cylinder vertices must be in 3..24 for textured rendering; got $vertices"
        }
        require(height > 0.0) { "Cylinder height must be positive, got $height" }
    }

    override fun translate(dx: Double, dy: Double, dz: Double): Cylinder =
        Cylinder(position.translate(dx, dy, dz), radius, height, vertices)
}

private fun buildCylinderPaths(
    position: Point,
    radius: Double,
    height: Double,
    vertices: Int,
): List<Path> {
    val basePoints = Array(vertices + 1) { i ->
        val angle = (i % vertices) * 2.0 * PI / vertices
        Point(
            radius * cos(angle) + position.x,
            radius * sin(angle) + position.y,
            position.z,
        )
    }
    val topPoints = Array(vertices + 1) { i ->
        val angle = (i % vertices) * 2.0 * PI / vertices
        Point(
            radius * cos(angle) + position.x,
            radius * sin(angle) + position.y,
            position.z + height,
        )
    }
    val bottomCap = Path(basePoints.take(vertices).reversed())
    val topCap = Path(topPoints.take(vertices).toList())
    val sides = (0 until vertices).map { k ->
        Path(listOf(topPoints[k], basePoints[k], basePoints[k + 1], topPoints[k + 1]))
    }
    return listOf(bottomCap, topCap) + sides
}
