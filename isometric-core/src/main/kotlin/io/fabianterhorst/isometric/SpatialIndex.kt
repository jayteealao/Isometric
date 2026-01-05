package io.fabianterhorst.isometric

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Grid-based spatial index for accelerating hit testing.
 * Partitions the 2D screen space into cells and indexes render commands by their bounding box.
 *
 * @property width The width of the indexed area in pixels
 * @property height The height of the indexed area in pixels
 * @property cellSize The size of each grid cell in pixels (default: 50)
 */
class SpatialIndex(
    private val width: Int,
    private val height: Int,
    private val cellSize: Int = 50
) {
    private val gridWidth = (width / cellSize) + 1
    private val gridHeight = (height / cellSize) + 1
    private val grid = Array(gridWidth * gridHeight) { mutableListOf<RenderCommand>() }

    /**
     * Insert a render command into the spatial index based on its bounding box
     */
    fun insert(command: RenderCommand) {
        if (command.points.isEmpty()) return

        // Calculate bounding box
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var maxY = Double.MIN_VALUE

        for (point in command.points) {
            minX = min(minX, point.x)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        }

        // Convert to grid coordinates
        val startCol = max(0, floor(minX / cellSize).toInt())
        val startRow = max(0, floor(minY / cellSize).toInt())
        val endCol = min(gridWidth - 1, floor(maxX / cellSize).toInt())
        val endRow = min(gridHeight - 1, floor(maxY / cellSize).toInt())

        // Insert into all cells that the bounding box overlaps
        for (row in startRow..endRow) {
            for (col in startCol..endCol) {
                val cellIndex = row * gridWidth + col
                if (cellIndex >= 0 && cellIndex < grid.size) {
                    grid[cellIndex].add(command)
                }
            }
        }
    }

    /**
     * Query the spatial index to find all commands in the cell containing the given point
     *
     * @param x The x coordinate to query
     * @param y The y coordinate to query
     * @return List of render commands in the cell containing this point
     */
    fun query(x: Double, y: Double): List<RenderCommand> {
        val col = floor(x / cellSize).toInt()
        val row = floor(y / cellSize).toInt()

        if (col < 0 || col >= gridWidth || row < 0 || row >= gridHeight) {
            return emptyList()
        }

        val cellIndex = row * gridWidth + col
        return if (cellIndex >= 0 && cellIndex < grid.size) {
            grid[cellIndex]
        } else {
            emptyList()
        }
    }

    /**
     * Clear the spatial index
     */
    fun clear() {
        for (cell in grid) {
            cell.clear()
        }
    }
}
