@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose

import app.cash.paparazzi.Paparazzi
import com.google.common.truth.Truth.assertThat
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import io.github.jayteealao.isometric.compose.scenes.CameraControlScene
import io.github.jayteealao.isometric.compose.scenes.NodeIdRowScene
import org.junit.Rule
import org.junit.Test

/**
 * Guards the [IsometricSnapshotHarness] itself — the helper that exists so the Paparazzi baselines
 * capture real geometry instead of `IsometricScene`'s deferred (blank) first frame.
 *
 * These assert at the *node-tree* level (deterministic, no pixel goldens): that
 * [IsometricSnapshotHarness.renderToGroupNode] composes and applies the scene **synchronously**, so
 * the returned tree is populated the instant it returns. If a future change reverted the harness to
 * a deferred composition, these fail immediately — long before anyone notices the snapshot PNGs have
 * gone blank again.
 *
 * The [Paparazzi] rule is present only to supply a real Android runtime for the test method:
 * driving a Compose composition touches `android.os.Trace`, which throws under the bare
 * `testDebugUnitTest` stub. No `snapshot()` call is needed — the assertions are on the node tree.
 * As with every Paparazzi test in this module, this runs on Linux/CI (Paparazzi 1.3.0 ships no
 * Windows native LayoutLib).
 */
class IsometricSnapshotHarnessTest {

    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun `renderToGroupNode populates the node tree synchronously`() {
        val root = IsometricSnapshotHarness.renderToGroupNode { NodeIdRowScene() }

        // NodeIdRowScene emits a ground slab plus four buildings. A deferred (blank) composition
        // would leave the snapshot empty; a synchronous one has every child available now.
        assertThat(root.childrenSnapshot).isNotEmpty()
    }

    @Test
    fun `built node tree produces render geometry`() {
        val root = IsometricSnapshotHarness.renderToGroupNode { CameraControlScene() }

        val commands = mutableListOf<RenderCommand>()
        root.renderTo(commands, RenderContext(width = 800, height = 600, renderOptions = RenderOptions.Default))

        // The slab + prism + pyramid + cylinder must yield face geometry — proof the synchronous
        // composition actually built renderable nodes, not just empty containers.
        assertThat(commands).isNotEmpty()
    }
}
