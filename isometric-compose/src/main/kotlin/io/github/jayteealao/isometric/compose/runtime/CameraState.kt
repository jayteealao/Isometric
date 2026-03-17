package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue

/**
 * Mutable camera state for programmatic viewport control.
 *
 * All properties are backed by Compose snapshot state — mutations trigger
 * recomposition of any composable that reads them.
 *
 * @param panX Initial horizontal pan offset in pixels (default: 0.0, must be finite)
 * @param panY Initial vertical pan offset in pixels (default: 0.0, must be finite)
 * @param zoom Initial zoom factor (default: 1.0, must be positive and finite)
 */
@Stable
class CameraState(
    panX: Double = 0.0,
    panY: Double = 0.0,
    zoom: Double = 1.0
) {
    init {
        require(panX.isFinite()) { "panX must be finite, got $panX" }
        require(panY.isFinite()) { "panY must be finite, got $panY" }
        require(zoom.isFinite() && zoom > 0.0) { "zoom must be positive and finite, got $zoom" }
    }

    private var _panX by mutableDoubleStateOf(panX)
    var panX: Double
        get() = _panX
        set(value) {
            require(value.isFinite()) { "panX must be finite, got $value" }
            _panX = value
        }

    private var _panY by mutableDoubleStateOf(panY)
    var panY: Double
        get() = _panY
        set(value) {
            require(value.isFinite()) { "panY must be finite, got $value" }
            _panY = value
        }

    private var _zoom by mutableDoubleStateOf(zoom)
    var zoom: Double
        get() = _zoom
        set(value) {
            require(value.isFinite() && value > 0.0) { "zoom must be positive and finite, got $value" }
            _zoom = value
        }

    /**
     * Pan the camera by a delta.
     */
    fun pan(deltaX: Double, deltaY: Double) {
        panX += deltaX
        panY += deltaY
    }

    /**
     * Zoom the camera by a factor around the current center.
     *
     * @param factor Multiplicative zoom factor (e.g., 1.1 for 10% zoom in)
     */
    fun zoomBy(factor: Double) {
        require(factor > 0.0) { "Zoom factor must be positive, got $factor" }
        zoom *= factor
    }

    /**
     * Reset to default state.
     */
    fun reset() {
        panX = 0.0
        panY = 0.0
        zoom = 1.0
    }
}
