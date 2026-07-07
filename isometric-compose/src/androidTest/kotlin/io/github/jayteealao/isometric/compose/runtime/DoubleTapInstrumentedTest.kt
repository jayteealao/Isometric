package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.Prism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AC-S3 gate — live pointer routing through the merged gesture handler on an emulator.
 *
 * These tests prove the post-fix dispatch contract under real Compose pointer routing.
 * Touch events are injected via `performTouchInput`, which drives actual pointer events through
 * the `pointerInput` block (not the state-machine simulation in `DoubleTapDisambiguationTest`).
 *
 * ## Tap target strategy
 *
 * Per-node callbacks (`onClick`, `onDoubleClick`) only fire when the touch lands on a rendered
 * polygon. Tests that verify per-node callbacks use `onPreparedSceneReady` to capture the actual
 * projected polygon centroids (in Compose canvas pixels), then inject touch at those coordinates.
 * The Compose canvas coordinate space matches the `performTouchInput` coordinate space — both
 * are in layout pixels at the composable's local density.
 *
 * Requires: an Android emulator (AVD) or device connected via adb.
 * Run via: `./gradlew :isometric-compose:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class DoubleTapInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private data class TapTarget(val x: Float, val y: Float)

    /**
     * Sets up a scene, waits for the first draw, and returns the centroid of the top polygon
     * of the rendered prism in Compose canvas pixels. This centroid is guaranteed to be a
     * valid hit-test target for per-node callback dispatch.
     *
     * @param onClickRef Mutable reference cell for an onClick counter.
     * @param onDoubleClickRef Mutable reference cell for an onDoubleClick counter.
     */
    private fun setupSceneAndGetTapTarget(
        onClickRef: IntArray,
        onDoubleClickRef: IntArray? = null
    ): TapTarget {
        val latch = CountDownLatch(1)
        var tapTarget: TapTarget? = null
        val fired = booleanArrayOf(false)

        composeRule.setContent {
            IsometricScene(
                modifier = Modifier.fillMaxSize(),
                config = AdvancedSceneConfig(
                    onPreparedSceneReady = { scene ->
                        if (!fired[0] && scene.commands.isNotEmpty()) {
                            fired[0] = true
                            // Use the top-face polygon (last command, which renders front-most).
                            // Any polygon centroid in the prepared scene is valid for hit-testing.
                            val cmd = scene.commands.last()
                            val cx = cmd.points.map { it.x }.average().toFloat()
                            val cy = cmd.points.map { it.y }.average().toFloat()
                            tapTarget = TapTarget(cx, cy)
                            latch.countDown()
                        }
                    }
                )
            ) {
                Shape(
                    geometry = Prism(Point(0.0, 0.0, 0.0), 4.0, 4.0, 2.0),
                    color = IsoColor.BLUE,
                    onClick = { onClickRef[0]++ },
                    onDoubleClick = if (onDoubleClickRef != null) {
                        { onDoubleClickRef[0]++ }
                    } else {
                        null
                    }
                )
            }
        }

        assertTrue("Scene should draw within 5 seconds", latch.await(5, TimeUnit.SECONDS))
        composeRule.waitForIdle()

        return checkNotNull(tapTarget) { "Tap target was not set — scene may not have drawn" }
    }

    /**
     * Double-tap on the prism fires `onDoubleClick` exactly once and `onClick` zero times.
     *
     * Pre-fix failure: `onClick` fired twice (once per Release in the hand-rolled loop) before
     * the independent `detectTapGestures` block fired `onDoubleClick`. Post-fix: the merged
     * handler uses the double-tap window check; the second Release cancels the pending onClick
     * job and fires `onDoubleClick` instead.
     *
     * ## Clock note
     * The two presses are injected in a single `performTouchInput` block, so the real-time
     * gap between them is near-zero (sub-millisecond) — well within the platform
     * double-tap window. `System.currentTimeMillis()` is used for the real-time check inside
     * the gesture handler, so this test does NOT need to manipulate the virtual test clock.
     */
    @Test
    fun doubleTap_firesOnDoubleClickOnce_andZeroOnClick() {
        val onClickCount = intArrayOf(0)
        val onDoubleClickCount = intArrayOf(0)
        val target = setupSceneAndGetTapTarget(onClickCount, onDoubleClickCount)

        composeRule.onRoot().performTouchInput {
            down(Offset(target.x, target.y))
            up()
            down(Offset(target.x, target.y))
            up()
        }
        // Advance the virtual clock past the disambiguation window to flush any pending job.
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()

        assertEquals("onDoubleClick should fire exactly once on double-tap", 1, onDoubleClickCount[0])
        assertEquals("onClick must not fire on a double-tap", 0, onClickCount[0])
    }

    /**
     * Single-tap on the prism fires `onClick` exactly once after the disambiguation window.
     *
     * ## Clock note
     * The `pendingTapJob` inside the gesture handler uses `delay(doubleTapWindowMs)` on the
     * real Android main dispatcher (not the virtual test clock). Both `mainClock.advanceTimeBy`
     * (for the Compose frame clock) and `Thread.sleep` (for real wall-clock time) are applied
     * to ensure the delay fires regardless of which clock `delay()` uses in instrumented tests.
     */
    @Test
    fun singleTap_onNode_firesOnClickOnce() {
        val onClickCount = intArrayOf(0)
        val target = setupSceneAndGetTapTarget(onClickCount, onDoubleClickRef = null)

        composeRule.onRoot().performTouchInput {
            down(Offset(target.x, target.y))
            up()
        }
        // Advance both virtual and real time to cover both possible delay() implementations.
        composeRule.mainClock.advanceTimeBy(600L)
        Thread.sleep(600L)
        composeRule.waitForIdle()

        assertEquals("onClick should fire once on single-tap", 1, onClickCount[0])
    }

    /**
     * Two separate single-taps on the prism each fire `onClick` once — total two fires.
     *
     * Verifies the double-tap window check does not accidentally suppress onClick for
     * two distinct, well-separated single taps.
     *
     * ## Clock note
     * The real-time gap between taps is enforced by `Thread.sleep(500L)` to advance
     * `System.currentTimeMillis()` past the double-tap window (so the second Press is NOT
     * detected as a double-tap). `mainClock.advanceTimeBy(600L)` advances the Compose frame
     * clock to fire the `delay()` in the `pendingTapJob` for each tap.
     */
    @Test
    fun twoSeparateSingleTaps_eachFireOnClickOnce() {
        val onClickCount = intArrayOf(0)
        val onDoubleClickCount = intArrayOf(0)
        val target = setupSceneAndGetTapTarget(onClickCount, onDoubleClickCount)

        // First tap.
        composeRule.onRoot().performTouchInput {
            down(Offset(target.x, target.y))
            up()
        }
        // Real time advances past the double-tap window so the second tap's
        // System.currentTimeMillis() check does NOT fire as a double-tap.
        Thread.sleep(500L)
        // Virtual clock advances past the disambiguation window to fire pendingTapJob.
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()

        // Second tap — well separated in both real time and virtual time.
        composeRule.onRoot().performTouchInput {
            down(Offset(target.x, target.y))
            up()
        }
        Thread.sleep(500L)
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()

        assertEquals("Two separate single-taps must fire onClick twice", 2, onClickCount[0])
        assertEquals("No onDoubleClick should fire for well-separated taps", 0, onDoubleClickCount[0])
    }

    /**
     * Drag gesture: `onDragEnd` fires on release and `onClick` does NOT fire.
     *
     * The merged handler's isDragging path takes the drag-end branch on Release, not the
     * tap branch. Neither onClick nor onDoubleClick is dispatched.
     */
    @Test
    fun dragGesture_firesOnDragEnd_andNotOnClick() {
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
                    geometry = Prism(Point(0.0, 0.0, 0.0), 4.0, 4.0, 2.0),
                    color = IsoColor.BLUE,
                    onClick = { singleTapCount++ },
                    onDoubleClick = { doubleTapCount++ }
                )
            }
        }

        composeRule.waitForIdle()

        // Perform a drag: swipe right across the center of the scene.
        composeRule.onRoot().performTouchInput {
            swipeRight()
        }
        // Advance past the disambiguation window to ensure no pending onClick fires.
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()

        assertTrue("onDragEnd should fire at least once on a drag", dragEndCount >= 1)
        assertEquals("onClick must not fire after a drag gesture", 0, singleTapCount)
        assertEquals("onDoubleClick must not fire after a drag gesture", 0, doubleTapCount)
    }
}
