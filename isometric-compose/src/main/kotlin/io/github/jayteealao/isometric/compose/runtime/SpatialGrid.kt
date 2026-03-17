package io.github.jayteealao.isometric.compose.runtime

import io.github.jayteealao.isometric.RenderCommand
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Axis-aligned bounding box for spatial indexing.
 */
internal data class ShapeBounds(
    val minX: Double, val minY: Double,
    val maxX: Double, val maxY: Double
)

/**
 * Compute the axis-aligned bounding box of a render command's projected points.
 * Returns null if the command has no points or contains NaN coordinates.
 */
internal fun RenderCommand.getBounds(): ShapeBounds? {
    if (points.isEmpty()) return null

    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY

    points.forEach { point ->
        minX = min(minX, point.x)
        minY = min(minY, point.y)
        maxX = max(maxX, point.x)
        maxY = max(maxY, point.y)
    }

    if (minX.isNaN() || minY.isNaN() || maxX.isNaN() || maxY.isNaN()) {
        return null
    }

    return ShapeBounds(minX, minY, maxX, maxY)
}

/**
 * 2D spatial grid for O(k) hit-test candidate lookup.
 * Cells are axis-aligned squares of [cellSize] pixels.
 *
 * Items are inserted by their bounding box, which may span multiple cells.
 * Queries return all item IDs in the cells touched by the query point ± radius.
 */
internal class SpatialGrid(
    private val width: Double,
    private val height: Double,
    private val cellSize: Double
) {
    private val cols = (width / cellSize).toInt() + 1
    private val rows = (height / cellSize).toInt() + 1
    private val grid = Array(rows) { Array(cols) { mutableListOf<String>() } }

    fun insert(id: String, bounds: ShapeBounds) {
        val minCol = max(0, floor(bounds.minX / cellSize).toInt())
        val maxCol = min(cols - 1, floor(bounds.maxX / cellSize).toInt())
        val minRow = max(0, floor(bounds.minY / cellSize).toInt())
        val maxRow = min(rows - 1, floor(bounds.maxY / cellSize).toInt())

        if (minCol > maxCol || minRow > maxRow) {
            return
        }

        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                grid[row][col].add(id)
            }
        }
    }

    fun query(x: Double, y: Double, radius: Double = 0.0): List<String> {
        val rawMinCol = floor((x - radius) / cellSize).toInt()
        val rawMaxCol = floor((x + radius) / cellSize).toInt()
        val rawMinRow = floor((y - radius) / cellSize).toInt()
        val rawMaxRow = floor((y + radius) / cellSize).toInt()

        // Early return for queries entirely outside the grid (check before clamping)
        if (rawMaxCol < 0 || rawMinCol >= cols || rawMaxRow < 0 || rawMinRow >= rows) {
            return emptyList()
        }

        val minCol = max(0, rawMinCol)
        val maxCol = min(cols - 1, rawMaxCol)
        val minRow = max(0, rawMinRow)
        val maxRow = min(rows - 1, rawMaxRow)

        val ids = LinkedHashSet<String>()
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                ids.addAll(grid[row][col])
            }
        }

        return ids.toList()
    }
}
