@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism

/** Node id of the large front prism. */
const val OCCLUDED_FRONT_TILE_ID = "front-tile"

/** Node id of the smaller prism nested inside the front one. */
const val OCCLUDED_BACK_TILE_ID = "back-tile"

/**
 * The large prism. Its footprint `[0,2]×[0,2]` fully contains [OCCLUDED_BACK_PRISM], and it
 * rises to `z = 2`, so its visible faces blanket the screen region the back prism projects into.
 */
val OCCLUDED_FRONT_PRISM = Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 2.0)

/**
 * The smaller prism, offset to `(0.5, 0.5)` and only `z = 1` tall, so it sits *inside and beneath*
 * the front prism. Its top face is what a `BACK_TO_FRONT` query reaches past the front prism.
 */
val OCCLUDED_BACK_PRISM = Prism(Point(0.5, 0.5, 0.0), 1.0, 1.0, 1.0)

/**
 * The hit-test probe point in world space: the centre of the back prism's top face (`z = 1`),
 * which is also interior to the front prism's volume. Projected to screen it lands inside *both*
 * prisms' silhouettes, so it is the coordinate where `FRONT_TO_BACK` and `BACK_TO_FRONT` diverge —
 * the former resolving the front prism, the latter the occluded back prism beneath it.
 *
 * Derive the screen coordinate with `engine.worldToScreen(OCCLUSION_PROBE_WORLD, w, h)` rather than
 * hard-coding pixels, so the probe tracks any change to the projection angle or scale.
 */
val OCCLUSION_PROBE_WORLD = Point(1.0, 1.0, 1.0)

/**
 * Two overlapping prisms: [OCCLUDED_FRONT_PRISM] partially occludes [OCCLUDED_BACK_PRISM] on
 * screen. Tapping the overlap resolves the front prism with the default `FRONT_TO_BACK` order and
 * the occluded back prism with `BACK_TO_FRONT` — the escape hatch this fixture demonstrates.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` :: `OccludedPickSample`.
 * If the sample changes, update here to match.
 */
@Composable
fun IsometricScope.OccludedPickScene() {
    Shape(geometry = OCCLUDED_FRONT_PRISM, color = IsoColor.BLUE, nodeId = OCCLUDED_FRONT_TILE_ID)
    Shape(geometry = OCCLUDED_BACK_PRISM, color = IsoColor.RED, nodeId = OCCLUDED_BACK_TILE_ID)
}
