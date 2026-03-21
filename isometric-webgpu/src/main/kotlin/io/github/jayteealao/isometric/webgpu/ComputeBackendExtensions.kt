package io.github.jayteealao.isometric.webgpu

import io.github.jayteealao.isometric.ComputeBackend
import io.github.jayteealao.isometric.SortingComputeBackend

/**
 * GPU-accelerated compute backend using WebGPU radix sort.
 *
 * Available only when the `isometric-webgpu` artifact is on the classpath.
 * If it is absent, `ComputeBackend.WebGpu` is a compile error — not a runtime crash.
 *
 * Returns a singleton instance — safe to call repeatedly. The underlying [WebGpuComputeBackend]
 * lazily initializes the GPU context on first use, protected by a mutex.
 *
 * This follows the extension-on-companion-object pattern from §9 (Escape Hatches) of
 * the API design guideline: reads identically to `ComputeBackend.Cpu` at the call site,
 * with zero magic.
 */
val ComputeBackend.Companion.WebGpu: SortingComputeBackend
    get() = WebGpuComputeBackendHolder.instance

private object WebGpuComputeBackendHolder {
    val instance: SortingComputeBackend = WebGpuComputeBackend()
}
