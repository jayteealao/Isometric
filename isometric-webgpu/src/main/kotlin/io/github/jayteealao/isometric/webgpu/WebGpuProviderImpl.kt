package io.github.jayteealao.isometric.webgpu

import io.github.jayteealao.isometric.SortingComputeBackend
import io.github.jayteealao.isometric.compose.runtime.WebGpuProvider
import io.github.jayteealao.isometric.compose.runtime.render.RenderBackend

/**
 * Singleton [WebGpuProvider] implementation.
 *
 * Discovered by [WebGpuProvider.get] via `Class.forName`. The fully-qualified class name
 * must remain stable — it is a runtime contract, not a compile-time dependency.
 *
 * Holds the singleton [WebGpuComputeBackend] (for GPU sorting) and [WebGpuRenderBackend]
 * (for the full GPU-driven render pipeline).
 */
object WebGpuProviderImpl : WebGpuProvider {

    private val computeBackendInstance: WebGpuComputeBackend = WebGpuComputeBackend()
    private val renderBackendInstance: RenderBackend = WebGpuRenderBackend()

    override val sortingBackend: SortingComputeBackend
        get() = computeBackendInstance

    override val renderBackend: RenderBackend
        get() = renderBackendInstance

    /** Direct access to [WebGpuComputeBackend] for status observation (sample app). */
    val computeBackend: WebGpuComputeBackend
        get() = computeBackendInstance
}
