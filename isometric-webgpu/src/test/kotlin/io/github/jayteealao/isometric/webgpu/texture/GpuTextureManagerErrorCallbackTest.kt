package io.github.jayteealao.isometric.webgpu.texture

import io.github.jayteealao.isometric.shader.TextureSource
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * JVM unit tests for `onTextureLoadError` callback behaviour in [GpuTextureManager].
 *
 * ## Coverage split
 *
 * | Test | Location | Status |
 * |------|----------|--------|
 * | T-01: Unsupported source resolves to null | this class | ✓ JVM |
 * | T-02: Atlas rebuild failure fires callback | `GpuTextureManagerInstrumentedTest` | ✓ instrumented |
 * | T-05: Valid Bitmap source does not fire | `GpuTextureManagerInstrumentedTest` | ✓ instrumented |
 *
 * T-02 and T-05 require a real [io.github.jayteealao.isometric.webgpu.GpuContext] and a
 * compiled WGSL shader pipeline. Run them with:
 * ```
 * ./gradlew :isometric-webgpu:connectedDebugAndroidTest \
 *     --tests "*.GpuTextureManagerInstrumentedTest"
 * ```
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
     * **What this does NOT cover:** the actual `mainHandler.post` dispatch — covered by
     * the instrumented `GpuTextureManagerInstrumentedTest`.
     */
    @Test
    fun `resolveSourceToBitmap_returnsNull_forUnsupportedSourceTypes`() {
        // TextureSource.Asset uses only pure Java string validation — safe to construct on JVM
        assertNull(resolveSourceToBitmap(TextureSource.Asset("textures/test.png")))

        // TextureSource.Resource uses only an Int — safe to construct on JVM (resId must be non-zero)
        assertNull(resolveSourceToBitmap(TextureSource.Resource(1)))
    }
}
