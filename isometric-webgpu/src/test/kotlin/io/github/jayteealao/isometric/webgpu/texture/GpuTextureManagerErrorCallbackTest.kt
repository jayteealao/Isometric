package io.github.jayteealao.isometric.webgpu.texture

import org.junit.Ignore
import kotlin.test.Test

/**
 * Tests for `onTextureLoadError` callback behaviour in [GpuTextureManager].
 *
 * ## Android runtime requirement
 *
 * These tests require a real WebGPU device ([GpuContext]) and atlas infrastructure that
 * is not available in the JVM test runner. They are annotated with [Ignore] and must be
 * migrated to `src/androidTest/` as instrumented tests.
 *
 * See: `TexturedCanvasDrawHookTest` for the established precedent in this project.
 *
 * ## Running as instrumented tests
 *
 * ```
 * ./gradlew :isometric-webgpu:connectedDebugAndroidTest \
 *     --tests "*.GpuTextureManagerErrorCallbackTest"
 * ```
 *
 * Use an emulator with API 36 (`emulator-5554 Medium Phone API 36`), which is the
 * same device used for interactive AC verification in `06-verify-webgpu-texture-error-callback.md`.
 */
@Ignore("Requires Android runtime + WebGPU device — migrate to androidTest/")
class GpuTextureManagerErrorCallbackTest {

    // ── T-01: Unsupported source type fires callback ───────────────────────────

    /**
     * [T-01] Unsupported-source callback fire site — `else` branch in `uploadAtlasAndBindGroup`.
     *
     * **Setup:** Construct a scene containing a [TextureSource.Asset] or [TextureSource.Resource]
     * shape. In Full WebGPU mode, these fall through to the `else ->` branch of the `when (source)`
     * in [GpuTextureManager], triggering `mainHandler.post { onTextureLoadError?.invoke(source) }`.
     *
     * **Expected behaviour:**
     * - `onTextureLoadError` is called exactly once for the unsupported source.
     * - It is NOT called for any valid [TextureSource.Bitmap] shapes in the same scene.
     * - The callback is invoked on the main thread.
     *
     * **Regression guard:** a refactor that drops the `mainHandler.post` from the `else` branch
     * would have no automated signal without this test.
     */
    @Test
    fun `onTextureLoadError_fired_forUnsupportedSourceType`() {
        // TODO (instrumented): Create GpuContext, initialise GpuTextureManager with a
        //   captured-callback onTextureLoadError, upload a scene containing
        //   TextureSource.Resource(...), assert callback fires once.
        // Reference: 07-review-webgpu-texture-error-callback-testing.md § T-01
    }

    // ── T-02: Atlas rebuild failure fires callback for every source ────────────

    /**
     * [T-02] Atlas-rebuild-failure callback fire site — `!atlasManager.rebuild(entries)` branch.
     *
     * **Setup:** Construct a scene with N sources whose combined dimensions exceed
     * [TextureAtlasManager]'s max atlas size, forcing `atlasManager.rebuild(entries)` to return
     * `false`. The failure branch fires `onTextureLoadError` once per source key in `entries`.
     *
     * **Expected behaviour:**
     * - `onTextureLoadError` is called exactly N times — once for each source in the failing batch.
     * - The N callbacks all arrive on the main thread (may arrive across multiple Looper ticks).
     * - The callback is NOT fired again on subsequent frames with the same source set (SPAM-1 fix:
     *   `lastAtlasSignature = textureSources` prevents re-triggering).
     *
     * **Bulk-fire semantics note:** the callback fires for all sources in the batch, not just the
     * source that caused the atlas to overflow. Callers must be idempotent across repeated calls
     * for the same source in the same event loop turn.
     */
    @Test
    fun `onTextureLoadError_fired_forEachSourceOnAtlasRebuildFailure`() {
        // TODO (instrumented): Create a bitmap whose size alone exceeds the atlas capacity.
        //   Upload a scene with 2+ such bitmaps. Assert onTextureLoadError fires once per source.
        //   Also verify: same scene on the next frame does NOT re-fire the callback.
        // Reference: 07-review-webgpu-texture-error-callback-testing.md § T-02
    }

    // ── T-05: Valid Bitmap source does not fire callback ──────────────────────

    /**
     * [T-05] Positive path — callback must NOT be invoked for a valid [TextureSource.Bitmap].
     *
     * **Setup:** Construct a scene using only valid `TextureSource.Bitmap` sources that fit in
     * the atlas. Upload and render in Full WebGPU mode.
     *
     * **Expected behaviour:** `onTextureLoadError` is never called.
     *
     * **Regression guard:** a bug that unconditionally fires the callback would be caught here.
     */
    @Test
    fun `onTextureLoadError_notFired_forValidBitmapSource`() {
        // TODO (instrumented): Upload scene with valid TextureSource.Bitmap(s).
        //   Assert onTextureLoadError is never called throughout the render.
        // Reference: 07-review-webgpu-texture-error-callback-testing.md § T-05
    }
}
