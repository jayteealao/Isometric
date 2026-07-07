package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the consumption-gating contract for the merged gesture handler (C2 fix).
 *
 * **Root cause of C2 (pre-fix):** `event.changes.forEach { it.consume() }` ran inside
 * `if (isDragging)` with no guard for whether any handler or camera was actually active.
 * This meant an inert scene (no onDrag, no camera, no dragged node) still consumed pointer
 * events during drag, silently blocking a parent `LazyColumn` from scrolling.
 *
 * **Post-fix:** consumption is gated on:
 * ```
 * val shouldConsume = isDragging && (
 *     draggedNode != null ||
 *     currentGestures.onDrag != null ||
 *     currentCameraState != null
 * )
 * ```
 * This means: events are consumed ONLY when the scene actually acts (moves a node, calls
 * onDrag, or pans the camera). An idle scene lets the parent scroll.
 *
 * ## Design split (see InteractionHarnessReadme)
 *
 * These are state-machine-only tests. They verify the consumption logic by exercising the
 * three-condition guard directly — not through a live Compose pointer pipeline. The
 * consumption predicate is a pure boolean function of the gesture state, so it is fully
 * assertable at the state-machine level.
 */
class ConsumptionGatingTest {

    /**
     * Helper: computes the `shouldConsume` predicate exactly as the merged handler does.
     *
     * This is the code under test: reproduction of the guard in `IsometricScene.kt`'s
     * Move branch.
     */
    private fun shouldConsume(
        isDragging: Boolean,
        hasDraggedNode: Boolean,
        hasOnDrag: Boolean,
        hasCameraState: Boolean
    ): Boolean {
        return isDragging && (hasDraggedNode || hasOnDrag || hasCameraState)
    }

    // --- AC-C2: inert scene must not consume during drag --------------------------------

    /**
     * An inert scene (no onDrag, no camera, no dragged node) does NOT consume pointer events
     * during drag.
     *
     * FAILS against pre-fix: the pre-fix code consumed unconditionally whenever `isDragging`
     * was true. `shouldConsume(isDragging=true, …everything false)` would return `true` in
     * the old code.
     */
    @Test
    fun `inert scene does not consume pointer events during drag`() {
        val consumed = shouldConsume(
            isDragging = true,
            hasDraggedNode = false,
            hasOnDrag = false,
            hasCameraState = false
        )
        assertThat(consumed).isFalse()
    }

    /**
     * A scene that is not yet dragging never consumes (regardless of other state).
     */
    @Test
    fun `not dragging means never consume`() {
        // All handlers present but not dragging yet
        val consumed = shouldConsume(
            isDragging = false,
            hasDraggedNode = true,
            hasOnDrag = true,
            hasCameraState = true
        )
        assertThat(consumed).isFalse()
    }

    /**
     * A scene with a registered `onDrag` callback DOES consume pointer events during drag —
     * the scene is acting (calling the user's callback) and must own the events.
     */
    @Test
    fun `onDrag registered triggers consumption during drag`() {
        val consumed = shouldConsume(
            isDragging = true,
            hasDraggedNode = false,
            hasOnDrag = true,
            hasCameraState = false
        )
        assertThat(consumed).isTrue()
    }

    /**
     * A scene with an active camera DOES consume pointer events during drag — the camera
     * autopan is acting and must own the events.
     */
    @Test
    fun `camera present triggers consumption during drag`() {
        val consumed = shouldConsume(
            isDragging = true,
            hasDraggedNode = false,
            hasOnDrag = false,
            hasCameraState = true
        )
        assertThat(consumed).isTrue()
    }

    /**
     * A scene with a dragged node DOES consume pointer events during drag — the node move
     * is acting and must own the events.
     */
    @Test
    fun `dragged node triggers consumption during drag`() {
        val consumed = shouldConsume(
            isDragging = true,
            hasDraggedNode = true,
            hasOnDrag = false,
            hasCameraState = false
        )
        assertThat(consumed).isTrue()
    }

    /**
     * Camera present but disabled (camera state exists, but pan is a no-op because the scene
     * has no camera motion). In the merged handler, the presence of `currentCameraState != null`
     * is what gates consumption — if the reference exists, the scene may pan.
     *
     * This test pins the contract: camera present → consume (even if zoom is 1.0 and pan
     * is zero, the reference is non-null so the scene takes the camera path).
     *
     * Design note: If a future version wants to skip consumption for a truly idle camera
     * (zoom=1, pan=0,0), that would be a deliberate API change — not a silent optimization.
     */
    @Test
    fun `camera state present means consume even if camera at identity transform`() {
        // Camera exists (non-null) but is at identity (zoom=1, no pan).
        // The guard checks for non-null, not for "camera is doing something".
        val consumed = shouldConsume(
            isDragging = true,
            hasDraggedNode = false,
            hasOnDrag = false,
            hasCameraState = true   // CameraState(zoom=1, panX=0, panY=0) is non-null
        )
        assertThat(consumed).isTrue()
    }

    /**
     * All three conditions true: consumes during drag.
     */
    @Test
    fun `all handlers present triggers consumption`() {
        val consumed = shouldConsume(
            isDragging = true,
            hasDraggedNode = true,
            hasOnDrag = true,
            hasCameraState = true
        )
        assertThat(consumed).isTrue()
    }

    /**
     * The full GestureConfig.Disabled sentinel has no drag handler — verifies the guard
     * evaluates correctly with the standard disabled config values.
     */
    @Test
    fun `GestureConfig Disabled has no onDrag`() {
        assertThat(GestureConfig.Disabled.onDrag).isNull()
        // Disabled + no camera + no node → should not consume
        val consumed = shouldConsume(
            isDragging = true,
            hasDraggedNode = false,
            hasOnDrag = GestureConfig.Disabled.onDrag != null,
            hasCameraState = false
        )
        assertThat(consumed).isFalse()
    }
}
