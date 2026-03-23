package io.github.jayteealao.isometric.webgpu

import io.github.jayteealao.isometric.SortingComputeBackend
import io.github.jayteealao.isometric.webgpu.sort.GpuDepthSorter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GPU-accelerated compute backend using WebGPU radix sort.
 *
 * Lazily initializes a [GpuContext] on first use, protected by a [Mutex] to prevent
 * concurrent coroutines from racing to create multiple GPU devices.
 *
 * This backend is strict: when selected, sorting must succeed on the GPU.
 * GPU initialization or runtime failures are surfaced to the caller so that
 * `ComputeBackend.WebGpu` never silently degrades to CPU behavior.
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
    enum class Status {
        Uninitialized,
        Initializing,
        Ready,
        Failed,
    }

    data class StatusSnapshot(
        val status: Status,
        val detail: String? = null,
        val depthKeyCount: Int? = null,
    )

    override val isAsync: Boolean = true

    private val initMutex = Mutex()
    private var gpuContext: GpuContext? = null
    private var gpuSorter: GpuDepthSorter? = null
    private var initAttempted = false
    private val _status = MutableStateFlow(StatusSnapshot(Status.Uninitialized))
    val status: StateFlow<StatusSnapshot> = _status.asStateFlow()

    /** Tracks the last reported depth key count to avoid redundant StateFlow emissions. */
    private var lastReportedCount: Int? = null

    /**
     * Returns the live [GpuContext] if initialization has already succeeded, or null.
     * Used by [WebGpuSceneRenderer] to share the same device/thread when both backends
     * are active, avoiding a second Dawn device on the same process.
     * Callers must NOT destroy the returned context — ownership stays with this backend.
     */
    internal fun getContextIfReady(): GpuContext? = gpuContext

    /**
     * Awaits GPU initialization and returns the shared [GpuContext], or null if init fails.
     * Used by [WebGpuSceneRenderer] to borrow this context rather than creating a second
     * Dawn device. Safe to call concurrently — protected by [initMutex].
     * Callers must NOT destroy the returned context.
     */
    internal suspend fun awaitContextForSharing(): GpuContext? {
        ensureContext() // blocks until ready or failed
        return gpuContext
    }

    private fun invalidateContext() {
        gpuSorter?.destroyCachedBuffers()
        gpuContext?.destroy()
        gpuContext = null
        gpuSorter = null
        initAttempted = false
        lastReportedCount = null
    }

    /**
     * Emit a status update only when the depth key count changes.
     * Avoids flooding the StateFlow (and triggering UI recomposition) on every frame
     * during animated scenes where the count is stable.
     */
    private fun reportReady(detail: String, depthKeyCount: Int? = null) {
        if (depthKeyCount != null && depthKeyCount == lastReportedCount) return
        lastReportedCount = depthKeyCount
        _status.value = StatusSnapshot(
            status = Status.Ready,
            detail = detail,
            depthKeyCount = depthKeyCount,
        )
    }

    /**
     * Lazy, mutex-guarded GPU initialization.
     *
     * Concurrent coroutines don't race to create multiple GPU devices.
     * After first attempt (success or failure), returns the cached result.
     */
    private suspend fun ensureContext(): GpuDepthSorter? = initMutex.withLock {
        if (initAttempted) return@withLock gpuSorter
        initAttempted = true
        _status.value = StatusSnapshot(Status.Initializing, "Requesting adapter and device")

        gpuContext = try {
            GpuContext.create()
        } catch (cancellation: CancellationException) {
            initAttempted = false
            _status.value = StatusSnapshot(Status.Uninitialized)
            throw cancellation
        } catch (error: Throwable) {
            _status.value = StatusSnapshot(Status.Failed, error.message ?: error::class.java.simpleName)
            null
        }

        gpuSorter = gpuContext?.let { context ->
            GpuDepthSorter(context) { detail, depthKeyCount ->
                reportReady(detail, depthKeyCount)
            }
        }
        if (gpuSorter != null) {
            reportReady("Adapter + device ready")
        }
        gpuSorter
    }

    override suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray {
        val sorter = ensureContext()
            ?: error("WebGPU backend initialization failed")

        return try {
            gpuContext?.checkHealthy()
            reportReady("Sorting ${depthKeys.size} depth keys", depthKeys.size)
            sorter.sortByDepthKeys(depthKeys)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            initMutex.withLock {
                invalidateContext()
                _status.value = StatusSnapshot(
                    status = Status.Failed,
                    detail = t.message ?: t::class.java.simpleName,
                    depthKeyCount = depthKeys.size,
                )
            }
            throw t
        }
    }

    override fun toString(): String = "ComputeBackend.WebGpu"
}
