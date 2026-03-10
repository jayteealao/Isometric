package io.fabianterhorst.isometric.benchmark

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape

/**
 * A scene item generated for benchmarking.
 *
 * Wraps a [Shape] with a stable [id] for keyed Compose composition.
 * Shape is NOT a data class, so structural equality is provided by
 * [id], [color], and [position] — not the Shape reference.
 *
 * @property id Stable identifier for keyed composition (e.g., "item_42")
 * @property shape The 3D shape to render
 * @property color The shape's color
 * @property position The shape's position in 3D space
 */
data class GeneratedItem(
    val id: String,
    val shape: Shape,
    val color: IsoColor,
    val position: Point
)
