package io.github.jayteealao.isometric.compose.runtime.sort

import io.github.jayteealao.isometric.Path

/**
 * CPU-based depth sort strategy using centroid depth ordering.
 *
 * Sorts faces by their centroid depth (`avg(x + y - 2z)` over all vertices), descending
 * (further faces first — painter's algorithm). This is the same scalar key the GPU radix
 * sort uses, providing behavioral parity for the common case.
 *
 * For the full intersection-based topological sort, the existing synchronous
 * [IsometricEngine.projectScene][io.github.jayteealao.isometric.IsometricEngine.projectScene]
 * path continues to use `isometric-core`'s internal
 * [DepthSorter][io.github.jayteealao.isometric.DepthSorter].
 */
internal class CpuDepthSortStrategy : DepthSortStrategy {
    override fun sort(paths: MutableList<Path>) {
        paths.sortByDescending { path ->
            path.points.sumOf { it.x + it.y - 2 * it.z } / path.points.size
        }
    }
}
