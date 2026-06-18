package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the self-documenting [DragEvent] contract and the drag-lifecycle consumer logic that
 * `InteractionSamplesActivity.DragLifecycleSample` relies on.
 *
 * Two things are pinned here, both device-free (callback/state pattern — see
 * `InteractionHarnessReadme` for why this module avoids Robolectric):
 *
 *  1. The field contract: an `onDragStart` [DragEvent] carries `delta == null` and an absolute
 *     `x`/`y`; an `onDrag` [DragEvent] carries a non-null [DragEvent.delta] (the per-event
 *     movement) alongside the absolute live `x`/`y`. The two are independent.
 *  2. The consumer state machine: driving a recording [GestureConfig] through the canonical
 *     start → drag* → end sequence reproduces the sample's accumulated-delta total and the
 *     captured absolute start.
 *
 * What is NOT covered here, by design: that a real touch routed through `pointerInput` fires the
 * callbacks in this order. That path needs a Compose host and is exercised interactively on a
 * device/emulator by the gesture samples — these tests lock the contract and the accumulation
 * math, not raw input routing.
 */
class DragEventClarityTest {

    /** An `onDragStart` event reports the absolute drag-start position and has no delta yet. */
    @Test
    fun `onDragStart event carries absolute position and null delta`() {
        // Constructed exactly as IsometricScene's onDragStart path does.
        val start = DragEvent(x = 120.0, y = 340.0, delta = null)
        assertThat(start.delta).isNull()
        assertThat(start.x).isEqualTo(120.0)
        assertThat(start.y).isEqualTo(340.0)
    }

    /** An `onDrag` event reports the live absolute position AND the per-event delta separately. */
    @Test
    fun `onDrag event carries absolute position and non-null delta`() {
        // Constructed exactly as IsometricScene's onDrag path does: x/y = live position,
        // delta = movement since the previous event.
        val drag = DragEvent(x = 130.0, y = 337.0, delta = DragDelta(10.0, -3.0))
        assertThat(drag.delta).isNotNull()
        assertThat(drag.delta!!.dx).isEqualTo(10.0)
        assertThat(drag.delta!!.dy).isEqualTo(-3.0)
        // Absolute position is independent of the delta.
        assertThat(drag.x).isEqualTo(130.0)
        assertThat(drag.y).isEqualTo(337.0)
    }

    /**
     * Accumulating each `onDrag` [DragEvent.delta] reconstructs the total pointer travel — the
     * absolute distance from the drag-start position to the final pointer position. This is the
     * property camera autopan depends on.
     */
    @Test
    fun `accumulated deltas equal total pointer travel`() {
        val startX = 100.0
        val startY = 200.0
        // Pointer walks +20,+15,+25 in x and +5,+5,+0 in y over three move events.
        val drags = listOf(
            DragEvent(x = 120.0, y = 205.0, delta = DragDelta(20.0, 5.0)),
            DragEvent(x = 135.0, y = 210.0, delta = DragDelta(15.0, 5.0)),
            DragEvent(x = 160.0, y = 210.0, delta = DragDelta(25.0, 0.0)),
        )
        var sumDx = 0.0
        var sumDy = 0.0
        drags.forEach { event -> event.delta?.let { sumDx += it.dx; sumDy += it.dy } }
        // Sum of deltas == final absolute position - start absolute position.
        assertThat(sumDx).isWithin(0.001).of(drags.last().x - startX)  // 60.0
        assertThat(sumDy).isWithin(0.001).of(drags.last().y - startY)  // 10.0
    }

    /**
     * Drives a recording [GestureConfig] through the canonical lifecycle — start, three drags,
     * end — exactly as `DragLifecycleSample` wires it, and asserts the ordering, callback counts,
     * captured absolute start, and accumulated delta total.
     */
    @Test
    fun `drag lifecycle consumer records ordered events and accumulates delta`() {
        val order = mutableListOf<String>()
        var startX = 0.0
        var startY = 0.0
        var accDx = 0.0
        var accDy = 0.0

        val config = GestureConfig(
            dragThreshold = 32f,
            onDragStart = { event ->
                order.add("START")
                startX = event.x
                startY = event.y
                accDx = 0.0
                accDy = 0.0
            },
            onDrag = { event ->
                order.add("DRAG")
                event.delta?.let { accDx += it.dx; accDy += it.dy }
            },
            onDragEnd = { order.add("END") }
        )

        // Canonical sequence as IsometricScene would dispatch it.
        config.onDragStart!!.invoke(DragEvent(x = 50.0, y = 80.0, delta = null))
        config.onDrag!!.invoke(DragEvent(x = 90.0, y = 80.0, delta = DragDelta(40.0, 0.0)))
        config.onDrag!!.invoke(DragEvent(x = 90.0, y = 110.0, delta = DragDelta(0.0, 30.0)))
        config.onDrag!!.invoke(DragEvent(x = 100.0, y = 115.0, delta = DragDelta(10.0, 5.0)))
        config.onDragEnd!!.invoke()

        assertThat(order).containsExactly("START", "DRAG", "DRAG", "DRAG", "END").inOrder()
        assertThat(order.count { it == "START" }).isEqualTo(1)
        assertThat(order.count { it == "DRAG" }).isAtLeast(1)
        assertThat(order.count { it == "END" }).isEqualTo(1)
        // Absolute start captured from onDragStart x/y.
        assertThat(startX).isEqualTo(50.0)
        assertThat(startY).isEqualTo(80.0)
        // Accumulated per-event deltas.
        assertThat(accDx).isWithin(0.001).of(50.0)  // 40 + 0 + 10
        assertThat(accDy).isWithin(0.001).of(35.0)  // 0 + 30 + 5
    }

    /**
     * The sample uses a non-default 32px [GestureConfig.dragThreshold]; pin that it round-trips.
     * (The 8px default and the non-negative require() guard are locked by GestureConfig's own
     * tests — this only fixes the value the lifecycle sample depends on.)
     */
    @Test
    fun `gesture config retains non-default drag threshold`() {
        val config = GestureConfig(dragThreshold = 32f)
        assertThat(config.dragThreshold).isEqualTo(32f)
    }
}
