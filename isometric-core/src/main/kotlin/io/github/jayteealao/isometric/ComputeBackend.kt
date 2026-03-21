package io.github.jayteealao.isometric

/**
 * Strategy for compute-intensive scene preparation (depth sorting).
 *
 * - [isAsync] == `false` → [IsometricScene] uses the existing synchronous Canvas draw path.
 * - [isAsync] == `true`  → [IsometricScene] uses a `LaunchedEffect` async path that calls
 *   [SortingComputeBackend.sortByDepthKeys] on a background coroutine.
 *
 * The default is [ComputeBackend.Cpu], which is synchronous and requires no GPU.
 *
 * **Guideline alignment (§2 Progressive Disclosure, §3 Defaults):**
 * Users interact only with `ComputeBackend.Cpu` or `ComputeBackend.WebGpu`.
 * The [SortingComputeBackend] extension interface is internal to the implementation —
 * callers never reference it by name.
 */
interface ComputeBackend {
    /**
     * Whether this backend requires async scene preparation.
     * When `true`, [IsometricScene] uses a `LaunchedEffect` + `Dispatchers.Default` path.
     * When `false`, the existing synchronous `DrawScope` path is used unchanged.
     */
    val isAsync: Boolean get() = false

    companion object {
        /** Synchronous CPU-based depth sorting. Always available. Zero GPU dependency. */
        val Cpu: ComputeBackend = CpuComputeBackend()
    }
}

/**
 * Extension interface for backends that sort faces by a scalar depth key.
 *
 * The engine extracts one float depth key per face (`Point.depth = x + y - 2z`),
 * calls [sortByDepthKeys], and reorders its internal item list by the returned indices.
 *
 * The `isometric-webgpu` module implements this interface, but callers access it
 * only through [ComputeBackend.Companion] extensions.
 */
interface SortingComputeBackend : ComputeBackend {
    override val isAsync: Boolean get() = true

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

/**
 * Default synchronous CPU compute backend.
 * Uses the existing [DepthSorter] topological sort in [IsometricEngine.projectScene].
 * No async path, no GPU dependency.
 */
private class CpuComputeBackend : ComputeBackend {
    override val isAsync: Boolean = false

    override fun toString(): String = "ComputeBackend.Cpu"
}
