package io.fabianterhorst.isometric.compose.runtime

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
 * Describes a drag interaction within an isometric scene.
 *
 * @param x Current horizontal screen coordinate of the pointer, in pixels.
 * @param y Current vertical screen coordinate of the pointer, in pixels.
 */
data class DragEvent(
    val x: Double,
    val y: Double
)
