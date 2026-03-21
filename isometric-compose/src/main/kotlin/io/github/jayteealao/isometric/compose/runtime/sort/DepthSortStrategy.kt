package io.github.jayteealao.isometric.compose.runtime.sort

import io.github.jayteealao.isometric.Path

/**
 * Strategy for ordering projected faces back-to-front for correct painter's algorithm rendering.
 *
 * The default [CpuDepthSortStrategy] delegates to the existing intersection-based topological
 * sort in `isometric-core`'s [DepthSorter][io.github.jayteealao.isometric.DepthSorter].
 * The GPU variant in `isometric-webgpu` provides a radix sort with scalar depth keys.
 *
 * This interface is named `DepthSortStrategy` (not `DepthSorter`) to avoid a name collision
 * with the existing internal `DepthSorter` object in `isometric-core`.
 */
fun interface DepthSortStrategy {
    /**
     * Sort [paths] in place, from back to front (painter's algorithm order).
     *
     * Implementations may be synchronous (CPU) or coroutine-based (GPU). The GPU variant
     * uses `suspend fun` in its concrete class; this interface models the synchronous
     * CPU contract.
     */
    fun sort(paths: MutableList<Path>)
}
