package io.github.jayteealao.isometric

/**
 * Interface for backends that sort faces by a scalar depth key.
 *
 * The engine extracts one float depth key per face (`Point.depth = x + y - 2z`),
 * calls [sortByDepthKeys], and reorders its internal item list by the returned indices.
 *
 * The `isometric-webgpu` module implements this interface via `WebGpuComputeBackend`.
 * Users select it through [RenderMode.Canvas(compute = Compute.WebGpu)][io.github.jayteealao.isometric.compose.runtime.RenderMode.Canvas].
 */
interface SortingComputeBackend {

    /**
     * Sort faces by depth key using the backend's accelerated sort.
     *
     * @param depthKeys Float depth key per face. Higher = further from viewer (drawn first).
     *   Length == number of faces in the current scene.
     * @return Indices into [depthKeys] in back-to-front order (drawn-first to drawn-last).
     *   Length == [depthKeys].size.
     */
    suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray
}
