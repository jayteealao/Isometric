@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.compose.runtime.GroupNode
import io.github.jayteealao.isometric.compose.runtime.IsometricApplier
import io.github.jayteealao.isometric.compose.runtime.IsometricRenderer
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.IsometricScopeImpl
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import kotlinx.coroutines.Dispatchers

/**
 * Test-only render path that makes Paparazzi capture real isometric geometry.
 *
 * ### Why this exists
 * [io.github.jayteealao.isometric.compose.runtime.IsometricScene] builds its node tree in a *child
 * composition* created inside a `DisposableEffect`: the `Composition(IsometricApplier(rootNode), …)`
 * is remembered, but its `setContent { … }` runs from an effect that fires *after* the first
 * composition commits. On a real device that is invisible — the `Canvas` simply repaints one frame
 * later when the geometry arrives. Paparazzi, however, captures a single settled frame, so it draws
 * an empty `rootNode` and every snapshot comes out as a byte-identical blank device frame. The blank
 * baselines silently guard nothing: a real depth-sort or geometry regression would not change a
 * blank pixel.
 *
 * ### What this does
 * [renderToGroupNode] runs the *same* applier and the *same* scene-content lambda, but composes it
 * **eagerly and synchronously** before the snapshot is taken. `Composition.setContent` performs the
 * initial composition and applies it through [IsometricApplier] on the calling thread, so the
 * returned [GroupNode] is fully populated immediately. [IsometricSnapshotCanvas] then draws that
 * already-built tree through the production [IsometricRenderer], so the captured frame contains the
 * real depth-sorted scene.
 *
 * The CompositionLocal defaults (`LocalDefaultColor`, `LocalRenderOptions`, `LocalLightDirection`)
 * are defined to equal the [io.github.jayteealao.isometric.compose.runtime.SceneConfig] defaults, so
 * a fixture rendered here matches what the same fixture renders inside `IsometricScene` on device —
 * no provider wiring is needed. The trade-off is fidelity scope: these snapshots exercise the
 * geometry → depth-sort → render path, not `IsometricScene`'s composition/effect wiring. That is the
 * same boundary `HitTestingEscapeHatchTest` already relies on, and the interaction contracts are
 * covered separately by the device-free state/callback tests.
 *
 * Usage:
 * ```
 * val root = IsometricSnapshotHarness.renderToGroupNode { NodeIdRowScene() }
 * paparazzi.snapshot {
 *     Box(Modifier.size(800.dp, 600.dp)) { IsometricSnapshotCanvas(root) }
 * }
 * ```
 */
object IsometricSnapshotHarness {

    /**
     * Compose [content] through an [IsometricApplier] and return the populated root [GroupNode].
     *
     * The composition is driven to its first applied frame synchronously: `setContent` composes and
     * applies the inserts on the current thread, so the returned node's `childrenSnapshot` is ready
     * to render the moment this function returns. The fixtures are static (no state, no effects), so
     * the [Recomposer] is only the required parent context — its recompose loop is never started.
     *
     * The composition is intentionally left undisposed: disposal could instruct the applier to
     * detach the very children we return, and the test JVM is short-lived, so the small leak is
     * harmless and the node tree is guaranteed intact.
     */
    fun renderToGroupNode(content: @Composable IsometricScope.() -> Unit): GroupNode {
        val rootNode = GroupNode()
        val recomposer = Recomposer(Dispatchers.Unconfined)
        val composition = Composition(IsometricApplier(rootNode), recomposer)
        composition.setContent {
            IsometricScopeImpl.content()
        }
        return rootNode
    }
}

/**
 * Draw a pre-built isometric [rootNode] (produced by [IsometricSnapshotHarness.renderToGroupNode])
 * into a [Canvas], using the production renderer and the default render options / light direction —
 * the same configuration `IsometricScene` uses by default, so the captured frame matches on-device.
 */
@Composable
fun IsometricSnapshotCanvas(
    rootNode: GroupNode,
    modifier: Modifier = Modifier,
    renderOptions: RenderOptions = RenderOptions.Default,
) {
    val engine = remember { IsometricEngine() }
    val renderer = remember(engine) { IsometricRenderer(engine) }
    Canvas(modifier.fillMaxSize()) {
        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width > 0 && height > 0) {
            val context = RenderContext(width = width, height = height, renderOptions = renderOptions)
            with(renderer) {
                render(rootNode = rootNode, context = context)
            }
        }
    }
}
