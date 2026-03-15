package io.fabianterhorst.isometric

/**
 * Represents a 3D shape composed of multiple 2D paths (faces)
 */
open class Shape(
    val paths: List<Path>
) {
    init {
        require(paths.isNotEmpty()) { "Shape requires at least one path" }
    }

    constructor(vararg paths: Path) : this(paths.toList())

    /**
     * Translate the shape by the given deltas
     */
    open fun translate(dx: Double, dy: Double, dz: Double): Shape {
        return Shape(paths.map { it.translate(dx, dy, dz) })
    }

    /**
     * Rotate about origin on the X axis
     */
    fun rotateX(origin: Point, angle: Double): Shape {
        return Shape(paths.map { it.rotateX(origin, angle) })
    }

    /**
     * Rotate about origin on the Y axis
     */
    fun rotateY(origin: Point, angle: Double): Shape {
        return Shape(paths.map { it.rotateY(origin, angle) })
    }

    /**
     * Rotate about origin on the Z axis
     */
    fun rotateZ(origin: Point, angle: Double): Shape {
        return Shape(paths.map { it.rotateZ(origin, angle) })
    }

    /**
     * Scale about a given origin
     */
    fun scale(origin: Point, dx: Double, dy: Double, dz: Double): Shape {
        return Shape(paths.map { it.scale(origin, dx, dy, dz) })
    }

    fun scale(origin: Point, dx: Double, dy: Double): Shape {
        return Shape(paths.map { it.scale(origin, dx, dy) })
    }

    fun scale(origin: Point, dx: Double): Shape {
        return Shape(paths.map { it.scale(origin, dx) })
    }

    /**
     * Sort the list of faces by distance then return the ordered paths
     */
    fun orderedPaths(): List<Path> = paths.sortedByDescending { it.depth }

    companion object {
        /**
         * Extrude a 2D path along the z-axis to create a 3D shape
         */
        fun extrude(path: Path, height: Double = 1.0): Shape {
            val topPath = path.translate(0.0, 0.0, height)
            val length = path.points.size

            val allPaths = mutableListOf<Path>()

            // Push the top and bottom faces, top face must be oriented correctly
            allPaths.add(path.reverse())
            allPaths.add(topPath)

            // Push each side face
            for (i in 0 until length) {
                val points = listOf(
                    topPath.points[i],
                    path.points[i],
                    path.points[(i + 1) % length],
                    topPath.points[(i + 1) % length]
                )
                allPaths.add(Path(points))
            }

            return Shape(allPaths)
        }
    }
}
