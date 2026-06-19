package io.github.jayteealao.isometric.compose

import com.google.common.truth.Truth.assertThat
import io.github.jayteealao.isometric.HitOrder
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.compose.scenes.ELEVATED_TILE_CENTER
import io.github.jayteealao.isometric.compose.scenes.ELEVATED_TILE_Z
import io.github.jayteealao.isometric.compose.scenes.EXPECTED_ELEVATED_TILE
import io.github.jayteealao.isometric.compose.scenes.OCCLUDED_BACK_PRISM
import io.github.jayteealao.isometric.compose.scenes.OCCLUDED_BACK_TILE_ID
import io.github.jayteealao.isometric.compose.scenes.OCCLUDED_FRONT_PRISM
import io.github.jayteealao.isometric.compose.scenes.OCCLUDED_FRONT_TILE_ID
import io.github.jayteealao.isometric.compose.scenes.OCCLUSION_PROBE_WORLD
import io.github.jayteealao.isometric.screenToTile
import org.junit.Test

/**
 * Locks the two purely-computational hit-testing escape hatches the `hit-testing` slice
 * demonstrates, exercised directly against [IsometricEngine] — no Compose runtime, no device.
 *
 * 1. **`findItemAt` + [HitOrder]** — at a screen coordinate where two prisms overlap, the default
 *    `FRONT_TO_BACK` order and `BACK_TO_FRONT` resolve *opposite* tiles, so `BACK_TO_FRONT` reaches
 *    the occluded tile beneath the front one (AC4).
 * 2. **`screenToTile` at elevation** — a tap on a raised tile round-trips to its cell only when the
 *    inverse projection intersects the tile's surface z-plane; intersecting the ground plane lands
 *    elsewhere. This guards the elevated-tile math that the ground-plane round-trip tests miss (AC4).
 *
 * The geometry and the probe point are imported from the shared `*Scene` fixtures so this test, the
 * Paparazzi baselines, and the sample tabs all assert against one source of truth.
 */
class HitTestingEscapeHatchTest {

    // --- findItemAt + HitOrder: occluded pick ---------------------------------------------

    @Test
    fun `findItemAt resolves opposite tiles for FRONT_TO_BACK and BACK_TO_FRONT`() {
        val engine = IsometricEngine()
        // Add each prism the way the renderer does: every face carries its owning node id.
        OCCLUDED_FRONT_PRISM.paths.forEach { face ->
            engine.add(face, IsoColor.BLUE, originalShape = OCCLUDED_FRONT_PRISM, ownerNodeId = OCCLUDED_FRONT_TILE_ID)
        }
        OCCLUDED_BACK_PRISM.paths.forEach { face ->
            engine.add(face, IsoColor.RED, originalShape = OCCLUDED_BACK_PRISM, ownerNodeId = OCCLUDED_BACK_TILE_ID)
        }

        val scene = engine.projectScene(800, 600, RenderOptions.Default)
        // Derive the overlap pixel from the world probe so it tracks the projection, not a constant.
        val probe = engine.worldToScreen(OCCLUSION_PROBE_WORLD, 800, 600)

        val frontHit = engine.findItemAt(scene, probe.x, probe.y, HitOrder.FRONT_TO_BACK, touchRadius = 8.0)
        val backHit = engine.findItemAt(scene, probe.x, probe.y, HitOrder.BACK_TO_FRONT, touchRadius = 8.0)

        // Both orders hit something — the probe genuinely lands inside the overlap.
        assertThat(frontHit).isNotNull()
        assertThat(backHit).isNotNull()

        val frontOwner = frontHit!!.ownerNodeId
        val backOwner = backHit!!.ownerNodeId

        // The two orders diverge (not a vacuous single-candidate hit) and between them cover both
        // tiles, so BACK_TO_FRONT is reaching the tile occluded beneath whatever FRONT_TO_BACK picks.
        assertThat(frontOwner).isNotEqualTo(backOwner)
        assertThat(setOf(frontOwner, backOwner))
            .containsExactly(OCCLUDED_FRONT_TILE_ID, OCCLUDED_BACK_TILE_ID)
    }

    // --- screenToTile at non-zero elevation -----------------------------------------------

    @Test
    fun `screenToTile maps a tap on the raised tile to its cell`() {
        val engine = IsometricEngine()
        val screen = engine.worldToScreen(ELEVATED_TILE_CENTER, 800, 600)

        val tile = engine.screenToTile(
            screenX = screen.x,
            screenY = screen.y,
            viewportWidth = 800,
            viewportHeight = 600,
            elevation = ELEVATED_TILE_Z,
        )

        assertThat(tile).isEqualTo(EXPECTED_ELEVATED_TILE)
    }

    @Test
    fun `screenToTile at ground elevation lands on a different cell than the raised tile`() {
        // Same screen pixel, but intersecting z = 0 instead of the tile's surface. Proving the two
        // differ is what shows the elevation parameter is actually threaded through the inverse
        // projection — a z = 0 round-trip could otherwise pass while elevated taps silently misfire.
        val engine = IsometricEngine()
        val screen = engine.worldToScreen(ELEVATED_TILE_CENTER, 800, 600)

        val groundTile = engine.screenToTile(
            screenX = screen.x,
            screenY = screen.y,
            viewportWidth = 800,
            viewportHeight = 600,
            elevation = 0.0,
        )

        assertThat(groundTile).isNotEqualTo(EXPECTED_ELEVATED_TILE)
    }
}
