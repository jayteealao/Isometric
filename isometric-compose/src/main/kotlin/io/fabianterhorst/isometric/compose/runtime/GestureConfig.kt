package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Stable

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

    val enabled: Boolean
        get() = onTap != null || onDrag != null || onDragStart != null || onDragEnd != null

    companion object {
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
