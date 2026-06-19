@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.compose.runtime.Batch
import io.github.jayteealao.isometric.compose.runtime.CustomNode
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.compose.runtime.Path as IsoPath
import io.github.jayteealao.isometric.shapes.Prism

/**
 * Static geometry of `InteractionSamplesActivity.PerNodeCallbackSample`: a ground slab plus one
 * [io.github.jayteealao.isometric.compose.runtime.Path] tile, one
 * [io.github.jayteealao.isometric.compose.runtime.Batch] of two prisms, and one
 * [io.github.jayteealao.isometric.compose.runtime.CustomNode] tile — the three hittable node
 * types. The sample wires `onClick`/`onLongClick`/`onDoubleClick` on each; those callbacks have
 * no visual footprint, so this Paparazzi baseline pins the render only. Per-node-type dispatch
 * is covered device-free in `NodeCallbacksInteractionTest`.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` :: `PerNodeCallbackSample`.
 * If the sample changes, update here to match.
 */
@Composable
fun IsometricScope.PerNodeCallbackScene() {
    // Ground
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 8.0, 8.0, 0.1), color = IsoColor.LIGHT_GRAY)

    // Path node — a closed quad tile.
    IsoPath(
        path = Path(
            listOf(
                Point(0.0, 0.0, 0.4),
                Point(2.0, 0.0, 0.4),
                Point(2.0, 2.0, 0.4),
                Point(0.0, 2.0, 0.4)
            )
        ),
        color = IsoColor.GREEN
    )

    // Batch node — two prisms sharing one color.
    Batch(
        shapes = listOf(
            Prism(position = Point(4.0, 0.0, 0.1), width = 1.0, depth = 1.0, height = 1.0),
            Prism(position = Point(4.0, 1.5, 0.1), width = 1.0, depth = 1.0, height = 1.5)
        ),
        color = IsoColor.PURPLE
    )

    // CustomNode — a user-rendered quad (the escape hatch).
    CustomNode(
        render = { context, nodeId ->
            val quad = Path(
                listOf(
                    Point(0.0, 4.0, 0.4),
                    Point(2.0, 4.0, 0.4),
                    Point(2.0, 6.0, 0.4),
                    Point(0.0, 6.0, 0.4)
                )
            )
            listOf(
                RenderCommand(
                    commandId = nodeId,
                    points = emptyList(),
                    color = IsoColor.CYAN,
                    originalPath = context.applyTransformsToPath(quad),
                    originalShape = null,
                    ownerNodeId = nodeId
                )
            )
        }
    )
}
