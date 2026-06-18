package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the drag-to-autopan delta contract against the current runtime.
 *
 * The scene's drag handler dispatches a [DragEvent] per pointer-move event and, when no
 * `onDrag` callback is supplied, feeds each event's [DragEvent.delta] straight into
 * [CameraState.pan], which accumulates them. These tests pin that observable behaviour —
 * per-event deltas in, summed pan out — so the additive [DragEvent] shape change and the
 * autopan wiring that reads [DragEvent.delta] stay provably behaviour-neutral.
 *
 * They are pure JVM unit tests: no Compose test rule, no Robolectric, no Android resources.
 * See `InteractionHarnessReadme` for why this module tests interactions this way.
 */
class AutopanDeltaContractTest {

    /**
     * An `onDrag` [DragEvent] carries the per-event translation in [DragEvent.delta], while
     * [DragEvent.x]/[DragEvent.y] hold the absolute pointer position. The autopan path
     * accumulates [DragEvent.delta]; this structural lock guards that field contract.
     */
    @Test
    fun `onDrag DragEvent carries per-event translation in delta`() {
        val event = DragEvent(x = 100.0, y = 200.0, delta = DragDelta(5.0, -3.0))
        assertThat(event.x).isEqualTo(100.0)
        assertThat(event.y).isEqualTo(200.0)
        assertThat(event.delta).isEqualTo(DragDelta(5.0, -3.0))
        assertThat(event.delta!!.dx).isEqualTo(5.0)
        assertThat(event.delta!!.dy).isEqualTo(-3.0)
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
     * Mirrors what the scene's move handler now does — one [DragEvent] per move, each event's
     * [DragEvent.delta] fed into [CameraState.pan] — and confirms three sequential deltas
     * accumulate to their sum. The absolute x/y move independently and must NOT reach pan: if
     * autopan ever regressed to reading x/y, panX would be 325 (108 + 110 + 107), not 7.
     */
    @Test
    fun `autopan accumulates three sequential drag deltas`() {
        val camera = CameraState()
        val events = listOf(
            DragEvent(x = 108.0, y = 104.0, delta = DragDelta(8.0, 4.0)),
            DragEvent(x = 110.0, y = 103.0, delta = DragDelta(2.0, -1.0)),
            DragEvent(x = 107.0, y = 109.0, delta = DragDelta(-3.0, 6.0)),
        )
        events.forEach { camera.pan(it.delta!!.dx, it.delta!!.dy) }
        assertThat(camera.panX).isWithin(0.001).of(7.0)  // 8 + 2 - 3
        assertThat(camera.panY).isWithin(0.001).of(9.0)  // 4 - 1 + 6
    }

    /**
     * The canonical callback/state pattern: register a recording `onDrag`, invoke it with
     * constructed events, and assert the dispatched sequence — including each event's
     * [DragEvent.delta]. Reusable by every later interaction test in this module.
     */
    @Test
    fun `GestureConfig onDrag records dispatched events`() {
        val recorded = mutableListOf<DragEvent>()
        val config = GestureConfig(onDrag = { recorded.add(it) })
        config.onDrag!!.invoke(DragEvent(x = 100.0, y = 50.0, delta = DragDelta(5.0, 10.0)))
        config.onDrag!!.invoke(DragEvent(x = 98.0, y = 53.0, delta = DragDelta(-2.0, 3.0)))
        assertThat(recorded).hasSize(2)
        assertThat(recorded[0].delta).isEqualTo(DragDelta(5.0, 10.0))
        assertThat(recorded[1].delta).isEqualTo(DragDelta(-2.0, 3.0))
    }
}
