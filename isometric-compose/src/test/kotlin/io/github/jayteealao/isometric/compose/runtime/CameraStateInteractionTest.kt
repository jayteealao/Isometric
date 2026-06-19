package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the device-free contract behind the camera demos slice: the [CameraState] mutations the
 * Camera tab drives directly ([CameraState.zoomBy] / [CameraState.reset]), the built-in drag-to-pan
 * accumulation ([CameraState.pan]) that the Camera tab and the reworked `RuntimeInteractiveSample`
 * rely on, and the pinch recipe's `detectTransformGestures` zoom-factor → `zoomBy` mapping.
 *
 * Device-free by design — callback/state pattern, see [InteractionHarnessReadme] for why this
 * module avoids Robolectric. What is NOT covered here: that a real two-finger scale routed through
 * `pointerInput { detectTransformGestures … }` reaches `zoomBy`, that a real drag pans the camera
 * through the scene's default drag handler, and that hover fires for a mouse / stylus. Those
 * pipelines need a Compose host / emulator (hover needs real non-touch hardware) and are verified
 * interactively by the sample; these tests pin the state math the scene delegates to [CameraState].
 */
class CameraStateInteractionTest {

    // --- zoomBy: the Camera tab's Zoom In / Zoom Out buttons ------------------------------

    @Test
    fun `zoomBy multiplies the current zoom by the factor`() {
        val camera = CameraState()                       // zoom starts at 1.0
        camera.zoomBy(1.5)
        assertThat(camera.zoom).isWithin(1e-9).of(1.5)
    }

    @Test
    fun `zoomBy is multiplicative across successive calls`() {
        val camera = CameraState()
        camera.zoomBy(2.0)                               // 1.0 → 2.0
        camera.zoomBy(0.5)                               // 2.0 → 1.0 (Zoom In then Zoom Out)
        assertThat(camera.zoom).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `zoomBy rejects a non-positive factor`() {
        // A degenerate factor must fail fast rather than corrupt the viewport into a
        // zero / negative zoom from which the scene cannot recover.
        listOf(0.0, -1.0, -0.5).forEach { bad ->
            val result = runCatching { CameraState().zoomBy(bad) }
            assertThat(result.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    // --- reset: the Camera tab's Reset button ---------------------------------------------

    @Test
    fun `reset restores pan and zoom to defaults`() {
        val camera = CameraState(panX = 50.0, panY = 30.0, zoom = 2.5)
        camera.reset()
        assertThat(camera.zoom).isWithin(1e-9).of(1.0)
        assertThat(camera.panX).isWithin(1e-9).of(0.0)
        assertThat(camera.panY).isWithin(1e-9).of(0.0)
    }

    // --- pan: built-in drag-to-pan (Camera tab + RuntimeInteractiveSample rework) ---------

    @Test
    fun `pan accumulates screen deltas`() {
        // The scene's default drag handler calls cameraState.pan(deltaX, deltaY) per drag event
        // when no onDrag is supplied; a drag is the sum of those deltas.
        val camera = CameraState()
        camera.pan(10.0, -5.0)
        camera.pan(3.0, 2.0)
        assertThat(camera.panX).isWithin(1e-9).of(13.0)
        assertThat(camera.panY).isWithin(1e-9).of(-3.0)
    }

    // --- the pinch recipe's gesture-factor mapping ----------------------------------------

    @Test
    fun `the pinch recipe forwards the gesture zoom factor to zoomBy`() {
        val camera = CameraState()
        // Reproduces PinchZoomRecipeSample's detectTransformGestures lambda exactly:
        //   { _, _, zoomFactor, _ -> cameraState.zoomBy(zoomFactor.toDouble()) }
        fun onPinch(zoomFactor: Float) = camera.zoomBy(zoomFactor.toDouble())

        // The gesture factor is a Float; expecteds go through the same Float→Double widening
        // the recipe applies, so the assertion isn't fooled by 1.2f ≠ 1.2.
        val zoomIn = 1.5f
        val zoomInFine = 1.2f
        val zoomOut = 0.5f

        onPinch(zoomIn)                                  // spreading fingers zooms in
        onPinch(zoomInFine)
        assertThat(camera.zoom).isWithin(1e-9)
            .of(zoomIn.toDouble() * zoomInFine.toDouble())

        onPinch(zoomOut)                                 // pinching fingers zooms back out
        assertThat(camera.zoom).isWithin(1e-9)
            .of(zoomIn.toDouble() * zoomInFine.toDouble() * zoomOut.toDouble())
    }
}
