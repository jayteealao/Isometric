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
 * // Full GPU pipeline (Mailbox — lowest latency, default)
 * SceneConfig(renderMode = RenderMode.WebGpu())
 *
 * // Full GPU pipeline with VSync (battery-friendly)
 * SceneConfig(renderMode = RenderMode.WebGpu(vsync = true))
 * ```
 *
 * ## Guideline alignment
 *
 * - §2 Progressive Disclosure: `Canvas()` covers most users; `compute =` is the advanced knob.
 *   `WebGpu()` is the simple GPU path; `vsync =` is the configurable knob.
 * - §3 Defaults: `Canvas()` with CPU sort is safe and requires no GPU. `WebGpu()` defaults
 *   to Mailbox (non-blocking present), which on Android is tear-free via SurfaceFlinger.
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
     *
     * @param vsync When `true`, locks frame presentation to the display refresh rate
     *   (VSync / Fifo mode). When `false` (default), presents frames immediately without
     *   waiting for vsync (Mailbox mode). Mailbox gives lower input latency and avoids
     *   the ~14ms vsync wait per frame; Fifo saves battery on static scenes. On Android,
     *   SurfaceFlinger composites the final output tear-free regardless of this setting.
     */
    data class WebGpu(val vsync: Boolean = false) : RenderMode

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
