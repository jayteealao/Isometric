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
                checkDepthDependency(depthSorted[i], depthSorted[j], i, j, drawBefore, observer)
            }
        } else {
            for (i in 0 until length) {
                for (j in 0 until i) {
                    checkDepthDependency(depthSorted[i], depthSorted[j], i, j, drawBefore, observer)
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
        observer: Point
    ) {
        // Check if 2D projections share a non-trivial INTERIOR overlap.
        //
        // hasInteriorIntersection (rather than the lenient hasIntersection) gates
        // edge insertion: face pairs that only touch at a shared edge or vertex
        // in screen-space cannot paint over each other regardless of closerThan's
        // verdict, so adding a draw-order edge for them produces spurious
        // dependencies. Pairs that pass the gate then go through Path.closerThan's
        // reduced Newell cascade (Z-extent minimax + plane-side test) for the
        // actual depth verdict.
        //
        // Coincident-polygon special case: when both faces project to the SAME
        // screen polygon (every vertex of one matches a vertex of the other —
        // e.g., the TOP of one prism vs the BOTTOM of a prism stacked above, or
        // the RIGHT wall of one tile vs the LEFT wall of an adjacent tile),
        // hasInteriorIntersection returns false because all vertices fall on
        // each other's boundary. These pairs share their entire 2D interior and
        // MUST be ordered by closerThan, not gate-rejected.
        val pointsA = itemA.transformedPoints
        val pointsB = itemB.transformedPoints
        val coincident = areCoincidentScreenPolygons(pointsA, pointsB)
        val intersects = coincident || IntersectionUtils.hasInteriorIntersection(
            pointsA.map { Point(it.x, it.y, 0.0) },
            pointsB.map { Point(it.x, it.y, 0.0) }
        )
        if (intersects) {
            // Use 3D depth comparison
            val cmpPath = itemA.item.path.closerThan(itemB.item.path, observer)
            if (cmpPath < 0) {
                drawBefore[i].add(j)
            } else if (cmpPath > 0) {
                drawBefore[j].add(i)
            }
            // When cmpPath == 0 (coplanar or ambiguous), intentionally add no edge.
            // Adding edges for these pairs disrupts correct top-face vs side-face
            // ordering in tile grids. Kahn's algorithm handles the resulting
            // zero-in-degree nodes in index order, which is deterministic enough.
        }
    }

    /**
     * Tests whether two projected screen polygons are vertex-for-vertex
     * coincident (every vertex of one matches a vertex of the other within
     * COINCIDENT_TOLERANCE). Used by [checkDepthDependency] to bypass the
     * strict-interior gate for fully-coincident pairs whose 100% overlap
     * the gate's "vertices on the other polygon's boundary" tests reject
     * incorrectly. O(n²) but n is the face vertex count (typically 3–4
     * for the prism corpus).
     */
    private fun areCoincidentScreenPolygons(a: List<Point2D>, b: List<Point2D>): Boolean {
        if (a.size != b.size || a.isEmpty()) return false
        val tol2 = COINCIDENT_TOLERANCE * COINCIDENT_TOLERANCE
        for (pa in a) {
            var matched = false
            for (pb in b) {
                val dx = pa.x - pb.x
                val dy = pa.y - pb.y
                if (dx * dx + dy * dy < tol2) {
                    matched = true
                    break
                }
            }
            if (!matched) return false
        }
        return true
    }

    /** Squared-distance tolerance for treating two screen vertices as coincident. */
    private const val COINCIDENT_TOLERANCE: Double = 1e-6

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
}
