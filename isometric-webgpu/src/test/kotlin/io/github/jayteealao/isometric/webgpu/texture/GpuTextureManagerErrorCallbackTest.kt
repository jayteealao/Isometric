package io.github.jayteealao.isometric.webgpu.texture

import io.github.jayteealao.isometric.shader.TextureSource
import org.junit.Ignore
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Tests for `onTextureLoadError` callback behaviour in [GpuTextureManager].
 *
 * ## Test split
 *
 * - **T-01 (JVM):** Tests the source-classification decision via [resolveSourceToBitmap].
 *   This is the JVM-testable proxy for the unsupported-source callback fire site in
 *   [GpuTextureManager.uploadAtlasAndBindGroup]: a `null` return is the precondition
 *   that causes `mainHandler.post { onTextureLoadError?.invoke(source) }` to execute.
 *
 * - **T-02 / T-05 (instrumented, deferred):** Require a real [GpuContext] and atlas
 *   infrastructure unavailable in the JVM runner. See [Ignore] stubs below.
 *
 * ## Running instrumented stubs when migrated
 *
 * ```
 * ./gradlew :isometric-webgpu:connectedDebugAndroidTest \
 *     --tests "*.GpuTextureManagerErrorCallbackTest"
 * ```
 *
 * Use an emulator with API 36 (`emulator-5554 Medium Phone API 36`).
 */
class GpuTextureManagerErrorCallbackTest {

    // ── T-01: Unsupported source type resolves to null (JVM) ──────────────────

    /**
     * [T-01] Source-classification precondition for the unsupported-source callback site.
     *
     * [resolveSourceToBitmap] returns `null` for [TextureSource.Asset] and
     * [TextureSource.Resource] — the condition that triggers `onTextureLoadError` dispatch
     * in `uploadAtlasAndBindGroup`. Testing the extraction point on the JVM proves the
     * classification decision without requiring a real Android [android.os.Looper].
     *
     * **What this covers:** the `else ->` branch routing in [resolveSourceToBitmap].
     * **What this does NOT cover:** the actual `mainHandler.post` dispatch (requires Android
     * runtime — see T-02 stub for the instrumented alternative).
     */
    @Test
    fun `resolveSourceToBitmap_returnsNull_forUnsupportedSourceTypes`() {
        // TextureSource.Asset uses only pure Java string validation — safe to construct on JVM
        assertNull(resolveSourceToBitmap(TextureSource.Asset("textures/test.png")))

        // TextureSource.Resource uses only an Int — safe to construct on JVM (resId must be non-zero)
        assertNull(resolveSourceToBitmap(TextureSource.Resource(1)))
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
    @Ignore("Requires Android runtime + WebGPU device — migrate to androidTest/")
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
    @Ignore("Requires Android runtime + WebGPU device — migrate to androidTest/")
    @Test
    fun `onTextureLoadError_notFired_forValidBitmapSource`() {
        // TODO (instrumented): Upload scene with valid TextureSource.Bitmap(s).
        //   Assert onTextureLoadError is never called throughout the render.
        // Reference: 07-review-webgpu-texture-error-callback-testing.md § T-05
    }
}
