package io.fabianterhorst.isometric.compose.runtime

data class TapEvent(
    val x: Double,
    val y: Double,
    val node: IsometricNode? = null
)

data class DragEvent(
    val x: Double,
    val y: Double
)
