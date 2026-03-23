package io.github.jayteealao.isometric.webgpu

import io.github.jayteealao.isometric.compose.runtime.render.RenderBackend

val RenderBackend.Companion.WebGpu: RenderBackend
    get() = WebGpuRenderBackendHolder.instance

private object WebGpuRenderBackendHolder {
    val instance: RenderBackend = WebGpuRenderBackend()
}
