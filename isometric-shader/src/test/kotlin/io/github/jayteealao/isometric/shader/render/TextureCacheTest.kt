package io.github.jayteealao.isometric.shader.render

import io.github.jayteealao.isometric.IsoColor
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Pure-logic tests for texture rendering utilities.
 *
 * Tests that require `android.graphics.Bitmap`, `Matrix`, or `BitmapShader` are
 * deferred to instrumented tests (connectedAndroidTest) or Paparazzi snapshot tests,
 * since the Paparazzi plugin does not provide Android native classes to regular JVM tests.
 */
class TextureRenderUtilsTest {

    @Test
    fun `toColorFilterOrNull returns null for white`() {
        assertNull(IsoColor.WHITE.toColorFilterOrNull())
    }

    @Test
    fun `toColorFilterOrNull returns filter for near-white`() {
        // 254 is not white — MULTIPLY with 254/255 has a measurable effect
        assertNotNull(IsoColor(254.0, 255.0, 255.0).toColorFilterOrNull())
    }

    @Test
    fun `toColorFilterOrNull returns filter for non-white`() {
        assertNotNull(IsoColor(200.0, 100.0, 50.0).toColorFilterOrNull())
    }
}
