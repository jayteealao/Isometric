package io.github.jayteealao.isometric.webgpu

/**
 * Callback interface for frame-level timing in the WebGPU render loop.
 *
 * Called from [WebGpuSceneRenderer.renderLoop] on [kotlinx.coroutines.Dispatchers.Default].
 * All methods have no-op defaults so non-benchmark callers can ignore them.
 */
interface WebGpuFrameCallback {
    /** Called before uploadScene() — CPU scene packing + GPU buffer upload. */
    fun onUploadStart() {}
    /** Called after uploadScene() completes. */
    fun onUploadEnd() {}
    /** Called before drawFrame() — compute dispatch + render pass + present. */
    fun onDrawFrameStart() {}
    /** Called after drawFrame() completes (after present()). */
    fun onDrawFrameEnd() {}

    /**
     * Called with GPU timestamp results when available.
     * All durations in nanoseconds. Values are -1 if timestamps unavailable.
     */
    fun onGpuTimestamps(computeNanos: Long, renderNanos: Long) {}
}
