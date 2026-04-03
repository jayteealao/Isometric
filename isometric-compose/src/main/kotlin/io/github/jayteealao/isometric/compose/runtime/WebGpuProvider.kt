package io.github.jayteealao.isometric.compose.runtime

import io.github.jayteealao.isometric.SortingComputeBackend
import io.github.jayteealao.isometric.compose.runtime.render.RenderBackend

/**
 * Bridge interface that lets [IsometricScene] resolve [RenderMode.WebGpu] and
 * [RenderMode.Canvas] with [RenderMode.Canvas.Compute.WebGpu] to concrete GPU
 * implementations without a compile-time dependency on `isometric-webgpu`.
 *
 * The `isometric-webgpu` module provides the implementation and registers it via
 * reflective class loading on first access.
 */
interface WebGpuProvider {

    /** GPU-accelerated sorting backend for [RenderMode.Canvas] with [RenderMode.Canvas.Compute.WebGpu]. */
    val sortingBackend: SortingComputeBackend

    /** WebGPU render backend for [RenderMode.WebGpu]. */
    val renderBackend: RenderBackend

    companion object {
        @Volatile
        private var _instance: WebGpuProvider? = null

        /**
         * Returns the registered [WebGpuProvider], loading it via reflection if necessary.
         *
         * @throws IllegalStateException if the `isometric-webgpu` artifact is not on the classpath.
         */
        fun get(): WebGpuProvider {
            _instance?.let { return it }
            synchronized(this) {
                _instance?.let { return it }
                return try {
                    val clazz = Class.forName(
                        "io.github.jayteealao.isometric.webgpu.WebGpuProviderImpl"
                    )
                    // Use Java reflection to access the Kotlin object INSTANCE field,
                    // avoiding a dependency on kotlin-reflect.
                    val impl = clazz.getField("INSTANCE").get(null) as WebGpuProvider
                    _instance = impl
                    impl
                } catch (e: ClassNotFoundException) {
                    throw IllegalStateException(
                        "RenderMode.WebGpu requires the isometric-webgpu artifact. " +
                            "Add it to your dependencies.",
                        e,
                    )
                }
            }
        }
    }
}
