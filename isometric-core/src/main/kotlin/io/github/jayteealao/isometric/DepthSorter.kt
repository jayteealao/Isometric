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
        // Pre-sort by depth descending so that faces farther from the viewer get
        // lower indices. When Kahn's algorithm has multiple zero-in-degree nodes,
        // it picks the lowest index first — this ensures a back-to-front default
        // order, which is correct for the painter's algorithm. Without this,
        // shared-edge face pairs that closerThan() cannot resolve (returns 0)
        // would fall back to insertion order, causing side faces of later prisms
        // to paint over top faces of earlier prisms.
        val depthSorted = items.sortedByDescending { it.item.path.depth }

        val sortedItems = mutableListOf<TransformedItem>()
        val observer = Point(-10.0, -10.0, 20.0)
        val length = depthSorted.size

        // Build dependency graph: drawBefore[i] = list of items that must be drawn before item i
        val drawBefore = List(length) { mutableListOf<Int>() }

        if (options.enableBroadPhaseSort) {
            val candidatePairs = buildBroadPhaseCandidatePairs(depthSorted, options.broadPhaseCellSize)
            for (packed in candidatePairs) {
                val i = (packed ushr 32).toInt()
                val j = (packed and 0xFFFFFFFFL).toInt()
                checkDepthDependency(depthSorted[i], depthSorted[j], i, j, drawBefore, observer, options)
            }
        } else {
            for (i in 0 until length) {
                for (j in 0 until i) {
                    checkDepthDependency(depthSorted[i], depthSorted[j], i, j, drawBefore, observer, options)
                }
            }
        }

        // Kahn's algorithm — O(V+E) topological sort
        // Build in-degree counts and total edge count
        val inDegree = IntArray(length)
        var totalEdges = 0
        for (i in 0 until length) {
            val edgeCount = drawBefore[i].size
            inDegree[i] = edgeCount
            totalEdges += edgeCount
        }

        // Build reverse adjacency in CSR (Compressed Sparse Row) format — zero boxing
        // depOffsets[j] = start index in depEdges for node j's dependents
        val depCount = IntArray(length)
        for (i in 0 until length) {
            for (j in drawBefore[i]) {
                depCount[j]++
            }
        }
        val depOffsets = IntArray(length + 1)
        for (i in 0 until length) {
            depOffsets[i + 1] = depOffsets[i] + depCount[i]
        }
        val depEdges = IntArray(totalEdges)
        val depFill = IntArray(length) // tracks fill position per node
        for (i in 0 until length) {
            for (j in drawBefore[i]) {
                depEdges[depOffsets[j] + depFill[j]] = i
                depFill[j]++
            }
        }

        // IntArray ring buffer queue — zero boxing
        val queue = IntArray(length)
        var qHead = 0
        var qTail = 0
        for (i in 0 until length) {
            if (inDegree[i] == 0) {
                queue[qTail++] = i
            }
        }

        // Process queue
        while (qHead < qTail) {
            val node = queue[qHead++]
            sortedItems.add(depthSorted[node])
            val depStart = depOffsets[node]
            val depEnd = depOffsets[node + 1]
            for (k in depStart until depEnd) {
                val dep = depEdges[k]
                inDegree[dep]--
                if (inDegree[dep] == 0) {
                    queue[qTail++] = dep
                }
            }
        }

        // Append any remaining items (circular dependencies — fallback)
        for (i in 0 until length) {
            if (inDegree[i] > 0) {
                sortedItems.add(depthSorted[i])
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
        observer: Point,
        options: RenderOptions
    ) {
        // Check if 2D projections share a non-trivial INTERIOR overlap.
        //
        // hasInteriorIntersection (rather than the lenient hasIntersection)
        // gates general edge insertion. Pairs that pass the gate go through
        // Path.closerThan's reduced Newell cascade for the depth verdict.
        // Boundary-only pairs remain rejected except on the culled render path
        // for exact 3D shared edges that still need deterministic paint order
        // in stacked and tiled prism scenes.
        val intersects = IntersectionUtils.hasInteriorIntersection(
            itemA.transformedPoints.map { Point(it.x, it.y, 0.0) },
            itemB.transformedPoints.map { Point(it.x, it.y, 0.0) }
        )
        val sharedEdgeOrder = if (options.enableBackfaceCulling) {
            sharedHorizontalVerticalEdgeOrder(itemA.item.path, itemB.item.path)
        } else {
            0
        }
        val cmpPath = when {
            sharedEdgeOrder != 0 -> sharedEdgeOrder
            intersects -> itemA.item.path.closerThan(itemB.item.path, observer)
            else -> 0
        }
        if (cmpPath < 0) {
            drawBefore[i].add(j)
        } else if (cmpPath > 0) {
            drawBefore[j].add(i)
        } else {
            // When cmpPath == 0 (coplanar or ambiguous), intentionally add no edge.
            // Kahn's algorithm handles the resulting zero-in-degree nodes in
            // deterministic depth-pre-sort order.
        }
    }

    /**
     * Orders axis-aligned vertical walls against horizontal faces when they share
     * a real 3D edge.
     *
     * The strict interior-overlap gate is still the right default for general
     * topological dependency generation, but stacked prisms and flat grids need
     * deterministic ordering at physical wall/top shared edges: walls below a
     * horizontal face draw before that face, and walls above a horizontal face
     * draw after it.
     */
    private fun sharedHorizontalVerticalEdgeOrder(pathA: Path, pathB: Path): Int {
        val horizontalA = horizontalZ(pathA)
        val horizontalB = horizontalZ(pathB)
        val verticalA = verticalZRange(pathA)
        val verticalB = verticalZRange(pathB)

        if (horizontalA != null && verticalB != null && shareAtLeastTwoVertices(pathA, pathB)) {
            return horizontalVsVerticalOrder(horizontalA, verticalB)
        }
        if (horizontalB != null && verticalA != null && shareAtLeastTwoVertices(pathA, pathB)) {
            return -horizontalVsVerticalOrder(horizontalB, verticalA)
        }
        return 0
    }

    private fun horizontalVsVerticalOrder(horizontalZ: Double, wallZRange: Range): Int {
        return when {
            nearlyEqual(wallZRange.min, horizontalZ) && wallZRange.max > horizontalZ + EDGE_EPSILON -> {
                1 // horizontal face is below the wall, so it draws before the wall.
            }
            nearlyEqual(wallZRange.max, horizontalZ) && wallZRange.min < horizontalZ - EDGE_EPSILON -> {
                -1 // horizontal face is above the wall, so it draws after the wall.
            }
            else -> 0
        }
    }

    private fun horizontalZ(path: Path): Double? {
        val z = path.points[0].z
        return if (path.points.all { nearlyEqual(it.z, z) }) z else null
    }

    private fun verticalZRange(path: Path): Range? {
        val x = path.points[0].x
        val y = path.points[0].y
        val verticalPlane = path.points.all { nearlyEqual(it.x, x) } ||
            path.points.all { nearlyEqual(it.y, y) }
        if (!verticalPlane) return null

        var minZ = Double.POSITIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        for (point in path.points) {
            if (point.z < minZ) minZ = point.z
            if (point.z > maxZ) maxZ = point.z
        }
        return if (maxZ > minZ + EDGE_EPSILON) Range(minZ, maxZ) else null
    }

    private fun shareAtLeastTwoVertices(pathA: Path, pathB: Path): Boolean {
        var matches = 0
        for (a in pathA.points) {
            for (b in pathB.points) {
                if (samePoint(a, b)) {
                    matches++
                    if (matches >= 2) return true
                    break
                }
            }
        }
        return false
    }

    private fun samePoint(a: Point, b: Point): Boolean {
        return nearlyEqual(a.x, b.x) && nearlyEqual(a.y, b.y) && nearlyEqual(a.z, b.z)
    }

    private fun nearlyEqual(a: Double, b: Double): Boolean {
        return kotlin.math.abs(a - b) <= EDGE_EPSILON
    }

    /**
     * Returns candidate pairs as Long-packed values: high 32 bits = larger index,
     * low 32 bits = smaller index. Avoids Pair boxing entirely.
     */
    private fun buildBroadPhaseCandidatePairs(
        items: List<TransformedItem>,
        cellSize: Double
    ): LongArray {
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
        val pairs = mutableListOf<Long>()
        for (bucket in grid.values) {
            if (bucket.size < 2) continue
            for (a in 0 until bucket.lastIndex) {
                for (b in a + 1 until bucket.size) {
                    val first = minOf(bucket[a], bucket[b])
                    val second = maxOf(bucket[a], bucket[b])
                    val key = symmetricPairHash(first, second)
                    if (seen.add(key)) {
                        pairs.add(packPair(second, first))
                    }
                }
            }
        }

        return pairs.toLongArray()
    }

    private fun cellKey(col: Int, row: Int): Long {
        return (col.toLong() shl 32) xor (row.toLong() and 0xffffffffL)
    }

    /** Commutative hash for dedup: order of first/second does not matter. */
    private fun symmetricPairHash(first: Int, second: Int): Long {
        return (first.toLong() shl 32) xor (second.toLong() and 0xffffffffL)
    }

    /** Pack two indices into a Long: high 32 bits = a, low 32 bits = b. */
    private fun packPair(a: Int, b: Int): Long {
        return (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
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

    private data class Range(
        val min: Double,
        val max: Double
    )

    private const val EDGE_EPSILON: Double = 1e-6
}
