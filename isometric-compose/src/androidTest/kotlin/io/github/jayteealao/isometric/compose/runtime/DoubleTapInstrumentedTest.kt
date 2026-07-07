package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.Shape as ShapeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AC-S3 gate — live pointer routing through the merged gesture handler on an emulator.
 *
 * These tests prove the post-fix dispatch contract under real Compose pointer routing:
 * `performTouchInput { doubleClick() }` / `click()` drive actual pointer events through the
 * `pointerInput` block (not the state-machine simulation in `DoubleTapDisambiguationTest`).
 *
 * ## Why instrumented tests, not JVM?
 *
 * The JVM tests in `DoubleTapDisambiguationTest` / `ConsumptionGatingTest` /
 * `GestureStateResetTest` exercise the state-machine logic by calling callbacks directly.
 * They cannot prove that the merged `pointerInput` block correctly routes real pointer events
 * to the right callbacks — that requires a live Compose host and real input injection.
 * This file is the live-routing evidence (see `InteractionHarnessReadme` for the split rationale).
 *
 * Requires: an Android emulator (AVD) or device connected via adb.
 * Run via: `./gradlew :isometric-compose:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class DoubleTapInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Double-tap on the scene fires `onDoubleClick` exactly once and `onClick` zero times.
     *
     * Pre-fix failure: `onClick` fired twice (once per Release in the hand-rolled loop) before
     * the independent `detectTapGestures` block fired `onDoubleClick`. Post-fix: the merged
     * handler uses the double-tap window check; the second Release cancels the pending onClick
     * job and fires `onDoubleClick` instead.
     */
    @Test
    fun `double-tap fires onDoubleClick exactly once and zero onClick`() {
        var singleTapCount = 0
        var doubleTapCount = 0

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                // A single prism node centered in the scene, with both callbacks registered.
                Shape(
                    geometry = Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 1.0),
                    color = IsoColor.BLUE,
                    onClick = { singleTapCount++ },
                    onDoubleClick = { doubleTapCount++ }
                )
            }
        }

        // Inject a double-tap at the center of the scene (where the prism renders).
        composeRule.onRoot().performTouchInput { doubleClick() }

        // Wait for the disambiguation window to expire so any spurious pending onClick
        // would have fired — but it must not.
        composeRule.waitForIdle()
        Thread.sleep(400L)  // exceed doubleTapTimeoutMillis (~300ms)
        composeRule.waitForIdle()

        assertEquals("onDoubleClick should fire exactly once on double-tap", 1, doubleTapCount)
        assertEquals("onClick must not fire on a double-tap", 0, singleTapCount)
    }

    /**
     * Single-tap on an onClick-only node fires `onClick` exactly once.
     *
     * For a node with no `onDoubleClick`, the merged handler dispatches `onClick` after the
     * disambiguation window expires without a second tap.
     *
     * This test confirms the onClick-only path is not broken by the C1 fix.
     */
    @Test
    fun `single-tap on onClick-only node fires onClick once`() {
        var singleTapCount = 0

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Shape(
                    geometry = Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 1.0),
                    color = IsoColor.BLUE,
                    onClick = { singleTapCount++ }
                    // onDoubleClick deliberately not set
                )
            }
        }

        composeRule.onRoot().performTouchInput { click() }

        // Wait for the disambiguation window to expire — onClick fires after this.
        composeRule.waitForIdle()
        Thread.sleep(400L)
        composeRule.waitForIdle()

        assertEquals("onClick should fire once on single-tap", 1, singleTapCount)
    }

    /**
     * Two separate single-taps (with a gap longer than the double-tap window between them)
     * each fire `onClick` once — total two onClick fires, zero onDoubleClick.
     *
     * Verifies that the double-tap window check does not accidentally suppress onClick for
     * two distinct, well-separated single taps.
     */
    @Test
    fun `two separate single-taps each fire onClick once`() {
        var singleTapCount = 0
        var doubleTapCount = 0

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Shape(
                    geometry = Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 1.0),
                    color = IsoColor.BLUE,
                    onClick = { singleTapCount++ },
                    onDoubleClick = { doubleTapCount++ }
                )
            }
        }

        // First tap.
        composeRule.onRoot().performTouchInput { click() }
        composeRule.waitForIdle()
        Thread.sleep(400L)  // let disambiguation window expire
        composeRule.waitForIdle()

        // Second tap — well separated from the first.
        composeRule.onRoot().performTouchInput { click() }
        composeRule.waitForIdle()
        Thread.sleep(400L)
        composeRule.waitForIdle()

        assertEquals("Two separate single-taps must fire onClick twice", 2, singleTapCount)
        assertEquals("No onDoubleClick should fire for well-separated taps", 0, doubleTapCount)
    }

    /**
     * Drag gesture: `onDragEnd` fires on release and `onClick` does NOT fire.
     *
     * The merged handler's isDragging path takes the drag-end branch on Release, not the
     * tap branch. Neither onClick nor onDoubleClick is dispatched.
     */
    @Test
    fun `drag gesture fires onDragEnd and not onClick`() {
        var dragEndCount = 0
        var singleTapCount = 0
        var doubleTapCount = 0

        composeRule.setContent {
            IsometricScene(
                modifier = Modifier.fillMaxSize(),
                config = SceneConfig(
                    gestures = GestureConfig(
                        onDragEnd = { dragEndCount++ }
                    )
                )
            ) {
                Shape(
                    geometry = Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 1.0),
                    color = IsoColor.BLUE,
                    onClick = { singleTapCount++ },
                    onDoubleClick = { doubleTapCount++ }
                )
            }
        }

        // Perform a drag: swipe right across the center of the scene.
        composeRule.onRoot().performTouchInput {
            swipeRight()
        }
        composeRule.waitForIdle()
        Thread.sleep(400L)
        composeRule.waitForIdle()

        assertTrue("onDragEnd should fire at least once on a drag", dragEndCount >= 1)
        assertEquals("onClick must not fire after a drag gesture", 0, singleTapCount)
        assertEquals("onDoubleClick must not fire after a drag gesture", 0, doubleTapCount)
    }
}
