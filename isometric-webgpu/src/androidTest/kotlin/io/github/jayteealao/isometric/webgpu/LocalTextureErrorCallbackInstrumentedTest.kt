package io.github.jayteealao.isometric.webgpu

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jayteealao.isometric.shader.TextureSource
import io.github.jayteealao.isometric.shader.render.LocalTextureErrorCallback
import io.github.jayteealao.isometric.shader.render.ProvideTextureRendering
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [LocalTextureErrorCallback] CompositionLocal propagation (T-03)
 * and [SideEffect] synchronisation to a renderer sink (T-04).
 *
 * These tests require the Compose test infrastructure (real Composition, Looper, SideEffect
 * scheduling) and must run on an Android emulator or device.
 *
 * Run with:
 * ```
 * ./gradlew :isometric-webgpu:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *       io.github.jayteealao.isometric.webgpu.LocalTextureErrorCallbackInstrumentedTest
 * ```
 *
 * ## T-03 — LocalTextureErrorCallback propagation
 * Verifies that [ProvideTextureRendering] correctly installs [LocalTextureErrorCallback] in
 * the composition tree. Tests: default null, provided value, innermost-wins nesting.
 *
 * ## T-04 — SideEffect wiring
 * Verifies that reading [LocalTextureErrorCallback.current] inside a SideEffect correctly
 * captures the provided value and updates when it changes. This mirrors the exact two-line
 * SideEffect pattern used in `WebGpuRenderBackend.Surface()` without requiring a real GPU
 * device or `AndroidExternalSurface`.
 */
@RunWith(AndroidJUnit4::class)
class LocalTextureErrorCallbackInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Sentinel — distinguishes "not yet written" from an explicit `null` assignment. */
    private val SENTINEL: (TextureSource) -> Unit = {}

    // ── T-03: LocalTextureErrorCallback propagation ───────────────────────────

    /**
     * [T-03a] Outside any [ProvideTextureRendering] scope the default value must be `null`.
     *
     * [LocalTextureErrorCallback] is declared with `staticCompositionLocalOf { null }`, so
     * any subtree that does not install a provider reads `null`.
     */
    @Test
    fun `LocalTextureErrorCallback_default_isNull`() {
        var observed: ((TextureSource) -> Unit)? = SENTINEL

        composeTestRule.setContent {
            observed = LocalTextureErrorCallback.current
        }
        composeTestRule.waitForIdle()

        assertNull(
            "LocalTextureErrorCallback.current must be null outside ProvideTextureRendering",
            observed,
        )
    }

    /**
     * [T-03b] A callback passed to [ProvideTextureRendering] is available to inner
     * composables via [LocalTextureErrorCallback.current].
     */
    @Test
    fun `LocalTextureErrorCallback_providedByProvideTextureRendering`() {
        val cb: (TextureSource) -> Unit = {}
        var observed: ((TextureSource) -> Unit)? = SENTINEL

        composeTestRule.setContent {
            ProvideTextureRendering(onTextureLoadError = cb) {
                observed = LocalTextureErrorCallback.current
            }
        }
        composeTestRule.waitForIdle()

        assertSame(
            "LocalTextureErrorCallback.current must be the callback provided to ProvideTextureRendering",
            cb,
            observed,
        )
    }

    /**
     * [T-03c] When [ProvideTextureRendering] is nested, the **innermost** provider wins for
     * the subtree it wraps. Providers do not merge — the inner callback completely replaces
     * the outer one.
     *
     * The outer subtree (between the two providers) still reads the outer callback.
     */
    @Test
    fun `LocalTextureErrorCallback_innermostProviderWins`() {
        val outerCb: (TextureSource) -> Unit = {}
        val innerCb: (TextureSource) -> Unit = {}
        var observedOuter: ((TextureSource) -> Unit)? = SENTINEL
        var observedInner: ((TextureSource) -> Unit)? = SENTINEL

        composeTestRule.setContent {
            ProvideTextureRendering(onTextureLoadError = outerCb) {
                observedOuter = LocalTextureErrorCallback.current
                ProvideTextureRendering(onTextureLoadError = innerCb) {
                    observedInner = LocalTextureErrorCallback.current
                }
            }
        }
        composeTestRule.waitForIdle()

        assertSame("outer scope must read outerCb", outerCb, observedOuter)
        assertSame("inner scope must read innerCb (innermost wins)", innerCb, observedInner)
    }

    // ── T-04: SideEffect syncs LocalTextureErrorCallback to a renderer sink ───

    /**
     * [T-04a] After the initial composition, a [SideEffect] reading
     * [LocalTextureErrorCallback.current] writes the provided callback to the sink.
     *
     * This mirrors the exact wiring pattern from `WebGpuRenderBackend.Surface()`:
     * ```kotlin
     * val onError = LocalTextureErrorCallback.current
     * SideEffect { renderer.onTextureLoadError = onError }
     * ```
     * We avoid the real renderer by using a plain `var` as the sink.
     */
    @Test
    fun `sideEffect_syncsPropagatedCallback_toRenderer`() {
        val cb: (TextureSource) -> Unit = {}
        var sink: ((TextureSource) -> Unit)? = SENTINEL

        composeTestRule.setContent {
            ProvideTextureRendering(onTextureLoadError = cb) {
                val onError = LocalTextureErrorCallback.current
                SideEffect { sink = onError }
            }
        }
        composeTestRule.waitForIdle()

        assertSame(
            "SideEffect must write LocalTextureErrorCallback.current to the sink after initial composition",
            cb,
            sink,
        )
    }

    /**
     * [T-04b] When [ProvideTextureRendering] is recomposed with a new callback, the
     * [SideEffect] must update the sink on the next composition.
     *
     * [LocalTextureErrorCallback] uses `staticCompositionLocalOf`, which triggers full-subtree
     * recomposition when its value changes. The SideEffect therefore re-runs with the new
     * callback identity after the provider's value changes.
     */
    @Test
    fun `sideEffect_updatesCallback_onRecomposition`() {
        val cbA: (TextureSource) -> Unit = {}
        val cbB: (TextureSource) -> Unit = {}
        var useB by mutableStateOf(false)
        var sink: ((TextureSource) -> Unit)? = SENTINEL

        composeTestRule.setContent {
            val cb = if (useB) cbB else cbA
            ProvideTextureRendering(onTextureLoadError = cb) {
                val onError = LocalTextureErrorCallback.current
                SideEffect { sink = onError }
            }
        }
        composeTestRule.waitForIdle()
        assertSame("initial composition: sink must equal cbA", cbA, sink)

        useB = true
        composeTestRule.waitForIdle()
        assertSame("after recomposition: sink must equal cbB", cbB, sink)
    }
}
