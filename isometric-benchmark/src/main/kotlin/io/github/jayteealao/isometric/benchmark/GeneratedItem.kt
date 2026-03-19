package io.github.jayteealao.isometric.benchmark

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.Shape

/**
 * A scene item generated for benchmarking.
 *
 * Wraps a [Shape] with a stable [id] for keyed Compose composition.
 *
 * **Equality note:** This is a data class, so Kotlin-generated equals()
 * includes all fields. Since [Shape] is NOT a data class, its equals()
 * uses reference identity. For deterministic-scene validation, compare
 * [id], [color], and [position] explicitly — do not rely on
 * GeneratedItem.equals() for shape content equality.
 *
 * @property id Stable identifier for keyed composition (e.g., "gen_42")
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
