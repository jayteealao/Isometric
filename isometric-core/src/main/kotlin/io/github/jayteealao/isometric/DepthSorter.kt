package io.github.jayteealao.isometric

import kotlin.math.floor

/**
 * Intersection-based depth sorting with optional broad-phase acceleration.
 *
 * Determines the painter's-algorithm draw order for overlapping projected polygons
 * using 3D depth comparison and topological sorting.
 */
internal object DepthSorter {

    internal data class TransformedItem(
        val item: SceneGraph.SceneItem,
        val transformedPoints: List<Point2D>,
        val litColor: IsoColor
    )

    /**
     * Sort items by depth using intersection-based comparison and topological sort.
     *
     * The baseline path checks every prior item pair. When broad-phase sorting is enabled,
     * spatial bucketing prunes candidate-pair generation; polygon intersection, depth
     * comparison, and topological-sort behavior stay unchanged.
     */
    fun sort(items: List<TransformedItem>, options: RenderOptions): List<TransformedItem> {
        val sortedItems = mutableListOf<TransformedItem>()
        val observer = Point(-10.0, -10.0, 20.0)
        val length = items.size

        // Build dependency graph: drawBefore[i] = list of items that must be drawn before item i
        val drawBefore = List(length) { mutableListOf<Int>() }

        if (options.enableBroadPhaseSort) {
            val candidatePairs = buildBroadPhaseCandidatePairs(items, options.broadPhaseCellSize)
            for (pair in candidatePairs) {
                val i = pair.first
                val j = pair.second
                checkDepthDependency(items[i], items[j], i, j, drawBefore, observer)
            }
        } else {
            for (i in 0 until length) {
                for (j in 0 until i) {
                    checkDepthDependency(items[i], items[j], i, j, drawBefore, observer)
                }
            }
        }

        // Topological sort
        val drawn = BooleanArray(length) { false }
        var drawThisTurn = true

        while (drawThisTurn) {
            drawThisTurn = false
            for (i in 0 until length) {
                if (!drawn[i]) {
                    val canDraw = drawBefore[i].all { drawn[it] }
                    if (canDraw) {
                        sortedItems.add(items[i])
                        drawn[i] = true
                        drawThisTurn = true
                    }
                }
            }
        }

        // Add any remaining items (circular dependencies)
        for (i in 0 until length) {
            if (!drawn[i]) {
                sortedItems.add(items[i])
            }
        }

        return sortedItems
    }

    private fun checkDepthDependency(
        itemA: TransformedItem,
        itemB: TransformedItem,
        i: Int,
        j: Int,
        drawBefore: List<MutableList<Int>>,
        observer: Point
    ) {
        // Check if 2D projections intersect
        if (IntersectionUtils.hasIntersection(
                itemA.transformedPoints.map { Point(it.x, it.y, 0.0) },
                itemB.transformedPoints.map { Point(it.x, it.y, 0.0) }
            )
        ) {
            // Use 3D depth comparison
            val cmpPath = itemA.item.path.closerThan(itemB.item.path, observer)
            if (cmpPath < 0) {
                drawBefore[i].add(j)
            } else if (cmpPath > 0) {
                drawBefore[j].add(i)
            }
        }
    }

    private fun buildBroadPhaseCandidatePairs(
        items: List<TransformedItem>,
        cellSize: Double
    ): List<Pair<Int, Int>> {
        val grid = hashMapOf<Long, MutableList<Int>>()

        items.forEachIndexed { index, item ->
            val bounds = item.getBounds()
            val minCol = floor(bounds.minX / cellSize).toInt()
            val maxCol = floor(bounds.maxX / cellSize).toInt()
            val minRow = floor(bounds.minY / cellSize).toInt()
            val maxRow = floor(bounds.maxY / cellSize).toInt()

            for (row in minRow..maxRow) {
                for (col in minCol..maxCol) {
                    grid.getOrPut(cellKey(col, row)) { mutableListOf() }.add(index)
                }
            }
        }

        val seen = hashSetOf<Long>()
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (bucket in grid.values) {
            if (bucket.size < 2) continue
            for (a in 0 until bucket.lastIndex) {
                for (b in a + 1 until bucket.size) {
                    val first = minOf(bucket[a], bucket[b])
                    val second = maxOf(bucket[a], bucket[b])
                    val key = pairKey(first, second)
                    if (seen.add(key)) {
                        pairs.add(second to first)
                    }
                }
            }
        }

        return pairs
    }

    private fun cellKey(col: Int, row: Int): Long {
        return (col.toLong() shl 32) xor (row.toLong() and 0xffffffffL)
    }

    private fun pairKey(first: Int, second: Int): Long {
        return (first.toLong() shl 32) xor (second.toLong() and 0xffffffffL)
    }

    private fun TransformedItem.getBounds(): ItemBounds {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        for (point in transformedPoints) {
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }

        return ItemBounds(minX, minY, maxX, maxY)
    }

    private data class ItemBounds(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double
    )
}
