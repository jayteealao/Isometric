package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Stable

/**
 * Configuration for gesture handling within an isometric scene.
 *
 * Supply callback lambdas for the gestures you want to handle; any callback left
 * `null` is simply ignored. The [enabled] property returns `true` when at least one
 * callback is registered. Use [Disabled] for a shared no-op instance.
 *
 * @param onTap Called when the user taps the scene. The [TapEvent] includes screen
 *   coordinates and, when hit testing is active, the tapped [IsometricNode].
 * @param onDrag Called continuously as the user drags across the scene. The
 *   [DragEvent.x]/[DragEvent.y] carry the live absolute pointer position, while
 *   [DragEvent.delta] carries the per-event movement since the previous drag event —
 *   accumulate the deltas to track total travel (this is what camera autopan sums).
 * @param onDragStart Called once when a drag gesture is first recognised. The
 *   [DragEvent.x]/[DragEvent.y] carry the absolute drag-start position; [DragEvent.delta]
 *   is `null` because no movement has happened yet.
 * @param onDragEnd Called once when the drag gesture finishes (finger lifted).
 * @param dragThreshold Minimum distance in pixels the pointer must move before a
 *   drag is recognised. Must be non-negative. Defaults to `8f`.
 * @param longPressTimeoutMs How long, in milliseconds, the pointer must be held still
 *   before a press becomes a long press (firing a node's `onLongClick`). Must be
 *   positive. Defaults to `500L`, matching the platform `ViewConfiguration` long-press
 *   timeout, so unconfigured scenes behave exactly as before.
 */
@Stable
class GestureConfig(
    val onTap: ((TapEvent) -> Unit)? = null,
    val onDrag: ((DragEvent) -> Unit)? = null,
    val onDragStart: ((DragEvent) -> Unit)? = null,
    val onDragEnd: (() -> Unit)? = null,
    val dragThreshold: Float = 8f,
    val longPressTimeoutMs: Long = 500L
) {
    init {
        require(dragThreshold >= 0f) { "dragThreshold must be non-negative, got $dragThreshold" }
        require(longPressTimeoutMs > 0L) { "longPressTimeoutMs must be positive, got $longPressTimeoutMs" }
    }

    /** `true` when at least one gesture callback is registered. */
    val enabled: Boolean
        get() = onTap != null || onDrag != null || onDragStart != null || onDragEnd != null

    companion object {
        /** A shared [GestureConfig] with no callbacks registered ([enabled] is `false`). */
        val Disabled = GestureConfig()
    }

    override fun equals(other: Any?): Boolean =
        other is GestureConfig &&
            onTap == other.onTap &&
            onDrag == other.onDrag &&
            onDragStart == other.onDragStart &&
            onDragEnd == other.onDragEnd &&
            dragThreshold == other.dragThreshold &&
            longPressTimeoutMs == other.longPressTimeoutMs

    override fun hashCode(): Int {
        var result = onTap?.hashCode() ?: 0
        result = 31 * result + (onDrag?.hashCode() ?: 0)
        result = 31 * result + (onDragStart?.hashCode() ?: 0)
        result = 31 * result + (onDragEnd?.hashCode() ?: 0)
        result = 31 * result + dragThreshold.hashCode()
        result = 31 * result + longPressTimeoutMs.hashCode()
        return result
    }

    override fun toString(): String =
        "GestureConfig(enabled=$enabled, dragThreshold=$dragThreshold, longPressTimeoutMs=$longPressTimeoutMs)"
}
