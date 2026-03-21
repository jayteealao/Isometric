package io.github.jayteealao.isometric.webgpu

import io.github.jayteealao.isometric.SortingComputeBackend
import io.github.jayteealao.isometric.webgpu.sort.GpuDepthSorter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GPU-accelerated compute backend using WebGPU radix sort.
 *
 * Lazily initializes a [GpuContext] on first use, protected by a [Mutex] to prevent
 * concurrent coroutines from racing to create multiple GPU devices.
 *
 * Falls back to CPU sorting when:
 * - GPU initialization fails (no Vulkan support, emulator, etc.)
 * - GPU sort throws at runtime (driver bug, timeout, etc.)
 * - Array size < [GpuDepthSorter.GPU_SORT_THRESHOLD] (CPU is faster for small arrays)
 *
 * ## Usage
 *
 * ```kotlin
 * IsometricScene(config = SceneConfig(computeBackend = ComputeBackend.WebGpu)) { ... }
 * ```
 *
 * `ComputeBackend.WebGpu` is defined as a companion extension property in
 * [ComputeBackendExtensions].
 */
class WebGpuComputeBackend : SortingComputeBackend {
    override val isAsync: Boolean = true

    private val initMutex = Mutex()
    private var gpuContext: GpuContext? = null
    private var gpuSorter: GpuDepthSorter? = null
    private var initAttempted = false

    /**
     * Lazy, mutex-guarded GPU initialization.
     *
     * Concurrent coroutines don't race to create multiple GPU devices.
     * After first attempt (success or failure), returns the cached result.
     */
    private suspend fun ensureContext(): GpuDepthSorter? = initMutex.withLock {
        if (initAttempted) return@withLock gpuSorter
        initAttempted = true
        gpuContext = runCatching { GpuContext.create() }.getOrNull()
        gpuSorter = gpuContext?.let { GpuDepthSorter(it) }
        gpuSorter
    }

    override suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray {
        val sorter = ensureContext()
            ?: return GpuDepthSorter.cpuFallbackSort(depthKeys)

        return runCatching { sorter.sortByDepthKeys(depthKeys) }
            .getOrElse { GpuDepthSorter.cpuFallbackSort(depthKeys) }
    }

    override fun toString(): String = "ComputeBackend.WebGpu"
}
