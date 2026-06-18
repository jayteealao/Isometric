package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the drag-to-autopan delta contract against the current runtime.
 *
 * The scene's drag handler dispatches a [DragEvent] per pointer-move event and, when no
 * `onDrag` callback is supplied, feeds those values straight into [CameraState.pan], which
 * accumulates them. These tests pin that observable behaviour — per-event values in, summed
 * pan out — so any later change to the [DragEvent] shape or the autopan wiring is provably
 * behaviour-neutral.
 *
 * They are pure JVM unit tests: no Compose test rule, no Robolectric, no Android resources.
 * See `InteractionHarnessReadme` for why this module tests interactions this way.
 */
class AutopanDeltaContractTest {

    /**
     * [DragEvent] exposes the two values it was constructed with. The autopan path relies on
     * these being the per-event translation it accumulates; this structural lock guards the
     * field contract before any additive change to the event shape.
     */
    @Test
    fun `DragEvent fields carry per-event delta values`() {
        val event = DragEvent(x = 5.0, y = -3.0)
        assertThat(event.x).isEqualTo(5.0)
        assertThat(event.y).isEqualTo(-3.0)
    }

    /** [CameraState.pan] sums successive deltas rather than replacing the offset. */
    @Test
    fun `CameraState pan accumulates successive deltas`() {
        val camera = CameraState()
        camera.pan(10.0, 5.0)
        camera.pan(3.0, -2.0)
        camera.pan(-1.0, 7.0)
        assertThat(camera.panX).isWithin(0.001).of(12.0)  // 10 + 3 - 1
        assertThat(camera.panY).isWithin(0.001).of(10.0)  // 5 - 2 + 7
    }

    /**
     * Mirrors what the scene's move handler does — one [DragEvent] per move, each fed into
     * [CameraState.pan] — and confirms three sequential deltas accumulate to their sum.
     */
    @Test
    fun `autopan accumulates three sequential drag deltas`() {
        val camera = CameraState()
        val deltas = listOf(DragEvent(8.0, 4.0), DragEvent(2.0, -1.0), DragEvent(-3.0, 6.0))
        deltas.forEach { camera.pan(it.x, it.y) }
        assertThat(camera.panX).isWithin(0.001).of(7.0)  // 8 + 2 - 3
        assertThat(camera.panY).isWithin(0.001).of(9.0)  // 4 - 1 + 6
    }

    /**
     * The canonical callback/state pattern: register a recording `onDrag`, invoke it with
     * constructed events, and assert the dispatched sequence. Reusable by every later
     * interaction test in this module.
     */
    @Test
    fun `GestureConfig onDrag records dispatched events`() {
        val recorded = mutableListOf<DragEvent>()
        val config = GestureConfig(onDrag = { recorded.add(it) })
        config.onDrag!!.invoke(DragEvent(5.0, 10.0))
        config.onDrag!!.invoke(DragEvent(-2.0, 3.0))
        assertThat(recorded).hasSize(2)
        assertThat(recorded[0].x).isEqualTo(5.0)
        assertThat(recorded[1].x).isEqualTo(-2.0)
    }
}
