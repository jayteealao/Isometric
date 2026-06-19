@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism

/**
 * Shared fixture for the single-node drag hero: a ground slab plus five named prisms in a
 * cross. The prism whose `nodeId` equals [selectedId] is tinted, mirroring the
 * `DragNodeSample` highlight so the Paparazzi screenshot and the live sample stay in
 * lockstep. Default ([selectedId] = `null`) renders the unselected baseline.
 */
@Composable
fun IsometricScope.DragNodeScene(selectedId: String? = null) {
    // Ground slab.
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)

    // Five draggable prisms in a cross. Geometry + ids mirror DragNodeSample.
    DRAG_NODE_PRISMS.forEach { (id, pos) ->
        Shape(
            geometry = Prism(pos, 1.0, 1.0, 1.0),
            color = if (id == selectedId) IsoColor.YELLOW else IsoColor.BLUE,
            nodeId = id
        )
    }
}

/** The hero scene's draggable prisms: stable id → base (engine-space) position. */
private val DRAG_NODE_PRISMS: List<Pair<String, Point>> = listOf(
    "node-center" to Point(3.0, 2.0, 0.1),
    "node-n" to Point(3.0, 0.0, 0.1),
    "node-s" to Point(3.0, 4.0, 0.1),
    "node-w" to Point(1.0, 2.0, 0.1),
    "node-e" to Point(5.0, 2.0, 0.1)
)
