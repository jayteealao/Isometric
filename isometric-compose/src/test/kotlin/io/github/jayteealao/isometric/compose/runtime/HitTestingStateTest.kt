package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the device-free contract behind the `onHitTestReady` escape hatch: the caller hands
 * [AdvancedSceneConfig.onHitTestReady] a callback, and the scene delivers a `(x, y) -> IsometricNode?`
 * the caller can invoke *imperatively, outside any gesture* — the whole point of the hatch.
 *
 * Device-free by design — callback/state pattern, see [InteractionHarnessReadme] for why this module
 * avoids Robolectric. What is NOT covered here: that the renderer builds a *correct* hit-test function
 * over the live composed scene (i.e. that querying the delivered function at a real screen coordinate
 * returns the node actually rendered there). That requires a Compose host / emulator and is verified
 * interactively by the sample; this test pins the config plumbing the caller depends on.
 */
class HitTestingStateTest {

    @Test
    fun `onHitTestReady is opt-in and defaults to null`() {
        // The hatch is off unless the caller asks for it, so a default config never publishes a fn.
        assertThat(AdvancedSceneConfig().onHitTestReady).isNull()
    }

    @Test
    fun `onHitTestReady hands the caller a hit-test function it can query outside a gesture`() {
        var capturedHitFn: ((Double, Double) -> IsometricNode?)? = null
        val config = AdvancedSceneConfig(
            onHitTestReady = { hitFn -> capturedHitFn = hitFn },
        )
        assertThat(config.onHitTestReady).isNotNull()

        // Stand in for the renderer-delivered function so the caller path is asserted end to end:
        // the scene fires onHitTestReady, the caller keeps the function, and resolves hits on demand.
        val sentinel: IsometricNode = GroupNode()
        config.onHitTestReady!!.invoke { x, y -> if (x >= 0.0 && y >= 0.0) sentinel else null }

        assertThat(capturedHitFn).isNotNull()
        // No gesture, no event — the caller queries the hatch directly.
        assertThat(capturedHitFn!!.invoke(120.0, 115.0)).isSameInstanceAs(sentinel)
        assertThat(capturedHitFn!!.invoke(-1.0, -1.0)).isNull()
    }
}
