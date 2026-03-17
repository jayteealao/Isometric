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
 * @param onDrag Called continuously as the user drags across the scene, providing
 *   the current [DragEvent] with screen coordinates.
 * @param onDragStart Called once when a drag gesture is first recognised, providing
 *   the initial [DragEvent] with screen coordinates.
 * @param onDragEnd Called once when the drag gesture finishes (finger lifted).
 * @param dragThreshold Minimum distance in pixels the pointer must move before a
 *   drag is recognised. Must be non-negative. Defaults to `8f`.
 */
@Stable
class GestureConfig(
    val onTap: ((TapEvent) -> Unit)? = null,
    val onDrag: ((DragEvent) -> Unit)? = null,
    val onDragStart: ((DragEvent) -> Unit)? = null,
    val onDragEnd: (() -> Unit)? = null,
    val dragThreshold: Float = 8f
) {
    init {
        require(dragThreshold >= 0f) { "dragThreshold must be non-negative, got $dragThreshold" }
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
            dragThreshold == other.dragThreshold

    override fun hashCode(): Int {
        var result = onTap?.hashCode() ?: 0
        result = 31 * result + (onDrag?.hashCode() ?: 0)
        result = 31 * result + (onDragStart?.hashCode() ?: 0)
        result = 31 * result + (onDragEnd?.hashCode() ?: 0)
        result = 31 * result + dragThreshold.hashCode()
        return result
    }

    override fun toString(): String = "GestureConfig(enabled=$enabled, dragThreshold=$dragThreshold)"
}
