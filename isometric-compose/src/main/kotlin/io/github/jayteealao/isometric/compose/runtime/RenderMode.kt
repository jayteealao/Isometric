package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Immutable

/**
 * Determines how an [IsometricScene] is rendered and computed.
 *
 * This is the single configuration axis that replaces the previous `renderBackend` +
 * `computeBackend` pair. Invalid combinations (e.g. WebGPU render with CPU compute)
 * are impossible to express.
 *
 * ## Variants
 *
 * - [Canvas] — Compose Canvas rendering. Depth sorting is configurable via [Canvas.Compute]:
 *   [Cpu][Canvas.Compute.Cpu] (default, synchronous topological sort) or
 *   [WebGpu][Canvas.Compute.WebGpu] (async GPU bitonic sort).
 *
 * - [WebGpu] — Full GPU-driven pipeline: transform, cull, light, sort, triangulate, and
 *   render entirely on the GPU via WebGPU compute and render passes.
 *   Requires the `isometric-webgpu` artifact on the classpath.
 *
 * ## Usage
 *
 * ```kotlin
 * // Default: Canvas + CPU sort
 * SceneConfig(renderMode = RenderMode.Canvas())
 *
 * // Canvas + GPU sort
 * SceneConfig(renderMode = RenderMode.Canvas(compute = Canvas.Compute.WebGpu))
 *
 * // Full GPU pipeline
 * SceneConfig(renderMode = RenderMode.WebGpu)
 * ```
 *
 * ## Guideline alignment
 *
 * - §2 Progressive Disclosure: `Canvas()` covers most users; `compute =` is the advanced knob.
 * - §3 Defaults: `Canvas()` with CPU sort is safe and requires no GPU.
 * - §6 Invalid States: WebGPU render always uses GPU compute — no way to misconfigure.
 */
@Immutable
sealed interface RenderMode {

    /**
     * Full GPU-driven pipeline: transform, cull, light, sort, triangulate, and render
     * on the GPU via WebGPU compute and render passes.
     *
     * Requires the `isometric-webgpu` artifact. If absent at runtime, [IsometricScene]
     * throws [IllegalStateException] with an actionable message.
     */
    data object WebGpu : RenderMode

    /**
     * Compose Canvas rendering with configurable depth-sort strategy.
     *
     * @param compute Controls how depth sorting is performed. Defaults to [Compute.Cpu].
     */
    data class Canvas(val compute: Compute = Compute.Cpu) : RenderMode {

        /**
         * Depth-sort strategy for the Canvas render path.
         */
        enum class Compute {
            /** Topological sort on the main thread. Always available, zero GPU dependency. */
            Cpu,

            /**
             * GPU bitonic sort via WebGPU compute.
             *
             * Requires the `isometric-webgpu` artifact. If absent at runtime,
             * [IsometricScene] throws [IllegalStateException].
             */
            WebGpu,
        }
    }
}
