package io.github.jayteealao.isometric.compose.runtime

/**
 * Describes a tap interaction within an isometric scene.
 *
 * @param x Horizontal screen coordinate of the tap, in pixels.
 * @param y Vertical screen coordinate of the tap, in pixels.
 * @param node The [IsometricNode] located at the tap position, or `null` if the tap
 *   did not hit any node (e.g. hit testing is disabled or the tap landed on empty space).
 */
data class TapEvent(
    val x: Double,
    val y: Double,
    val node: IsometricNode? = null
)

/**
 * A per-event pointer translation dispatched during a drag.
 *
 * Carries the change in pointer position since the previous drag event — a movement
 * vector, not an absolute coordinate. Camera autopan accumulates these deltas, so the
 * [DragEvent.delta] of each `onDrag` event is exactly what callers sum to track total
 * drag travel.
 *
 * @param dx Horizontal change in pointer position since the previous drag event, in pixels.
 * @param dy Vertical change in pointer position since the previous drag event, in pixels.
 */
data class DragDelta(
    val dx: Double,
    val dy: Double
)

/**
 * Describes a drag interaction within an isometric scene.
 *
 * [x] and [y] are the **absolute** screen position of the pointer: the drag-start
 * position when delivered to `onDragStart`, and the live pointer position when
 * delivered to `onDrag`. The per-event movement is carried separately by [delta],
 * so the two never have to share one pair of fields with conflicting meanings.
 *
 * @param x Absolute horizontal screen coordinate of the pointer, in pixels.
 * @param y Absolute vertical screen coordinate of the pointer, in pixels.
 * @param delta Per-event pointer translation, in pixels — the movement since the
 *   previous drag event. Non-`null` in `onDrag`; `null` in `onDragStart`, where no
 *   movement has happened yet. Accumulate these to track total drag travel; this is
 *   the value camera autopan sums.
 */
data class DragEvent(
    val x: Double,
    val y: Double,
    val delta: DragDelta? = null
)
