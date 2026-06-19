package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.shapes.Prism
import org.junit.Test

/**
 * Locks the device-free contract behind the node-callbacks slice: the configurable long-press
 * timeout ([GestureConfig.longPressTimeoutMs]) the scene reads for its `delay(...)`, and the
 * per-node [IsometricNode.onDoubleClick] dispatch the scene delegates to each hit node, across
 * every hittable node type.
 *
 * Device-free by design — callback/state pattern, see [InteractionHarnessReadme] for why this
 * module avoids Robolectric. What is NOT covered here: that a real touch routed through
 * `pointerInput` fires `onLongClick` at the configured wall-clock time, and that
 * `detectTapGestures` resolves a real double-tap vs. single tap on the live pipeline. That
 * pipeline needs a Compose host / emulator and is verified interactively by the sample; these
 * tests pin the config value the loop reads and the callback dispatch the scene performs.
 */
class NodeCallbacksInteractionTest {

    // --- Configurable long-press timeout: the GestureConfig contract ----------------------

    @Test
    fun `default long-press timeout is 500ms`() {
        // The scene's long-press coroutine delays for this value; the default preserves the
        // prior hardcoded 500ms behaviour for every unconfigured scene.
        assertThat(GestureConfig().longPressTimeoutMs).isEqualTo(500L)
        assertThat(GestureConfig.Disabled.longPressTimeoutMs).isEqualTo(500L)
    }

    @Test
    fun `a custom long-press timeout is carried on the config`() {
        assertThat(GestureConfig(longPressTimeoutMs = 200L).longPressTimeoutMs).isEqualTo(200L)
        assertThat(GestureConfig(longPressTimeoutMs = 1000L).longPressTimeoutMs).isEqualTo(1000L)
    }

    @Test
    fun `a non-positive long-press timeout is rejected`() {
        listOf(0L, -1L, -500L).forEach { bad ->
            val result = runCatching { GestureConfig(longPressTimeoutMs = bad) }
            assertThat(result.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `the long-press timeout does not leak between configs`() {
        // No shared / global state backs the timeout: each scene reads its own per-scene
        // GestureConfig, so a custom timeout on one config cannot affect another. This is the
        // structural no-leak guarantee (the override is not a CompositionLocal mutation).
        val custom = GestureConfig(longPressTimeoutMs = 200L)
        val default = GestureConfig()
        assertThat(custom.longPressTimeoutMs).isEqualTo(200L)
        assertThat(default.longPressTimeoutMs).isEqualTo(500L)
    }

    @Test
    fun `configs differing only in long-press timeout are not equal`() {
        // Compose skips recomposition on structural equality, so the timeout must participate
        // in equals/hashCode or a changed timeout could be silently missed.
        val a = GestureConfig(longPressTimeoutMs = 500L)
        val b = GestureConfig(longPressTimeoutMs = 200L)
        assertThat(a).isNotEqualTo(b)
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode())
        assertThat(a).isEqualTo(GestureConfig(longPressTimeoutMs = 500L))
    }

    // --- onDoubleClick dispatch (mirrors the scene's hitNode?.onDoubleClick?.invoke()) ----

    @Test
    fun `double-tap invokes onDoubleClick without firing onClick`() {
        var singleTaps = 0
        var doubleTaps = 0
        val node: IsometricNode = GroupNode().apply {
            onClick = { singleTaps++ }
            onDoubleClick = { doubleTaps++ }
        }
        // The double-tap pointerInput block dispatches only onDoubleClick.
        node.onDoubleClick?.invoke()
        assertThat(doubleTaps).isEqualTo(1)
        assertThat(singleTaps).isEqualTo(0)
    }

    @Test
    fun `single tap invokes onClick without firing onDoubleClick`() {
        var singleTaps = 0
        var doubleTaps = 0
        val node: IsometricNode = GroupNode().apply {
            onClick = { singleTaps++ }
            onDoubleClick = { doubleTaps++ }
        }
        // The tap path in the gesture loop dispatches only onClick.
        node.onClick?.invoke()
        assertThat(singleTaps).isEqualTo(1)
        assertThat(doubleTaps).isEqualTo(0)
    }

    @Test
    fun `onDoubleClick defaults to null and is a safe no-op when unset`() {
        val node: IsometricNode = GroupNode()
        assertThat(node.onDoubleClick).isNull()
        // Mirrors the scene: hitNode?.onDoubleClick?.invoke() is a no-op when unset.
        node.onDoubleClick?.invoke()  // must not throw
    }

    // --- Per-node-type dispatch (AC4): every callback fires on every hittable node type ----

    @Test
    fun `all three callbacks fire on every hittable node type`() {
        val fired = mutableListOf<String>()
        val nodes: List<IsometricNode> = listOf(
            ShapeNode(Prism(Point.ORIGIN), IsoColor.BLUE),
            PathNode(
                Path(listOf(Point.ORIGIN, Point(1.0, 0.0, 0.0), Point(1.0, 1.0, 0.0))),
                IsoColor.GREEN
            ),
            BatchNode(listOf(Prism(Point.ORIGIN)), IsoColor.PURPLE),
            CustomRenderNode { _, _ -> emptyList<RenderCommand>() }
        )
        nodes.forEach { node ->
            val type = node::class.simpleName
            node.onClick = { fired.add("$type:click") }
            node.onLongClick = { fired.add("$type:long") }
            node.onDoubleClick = { fired.add("$type:double") }
            // Reproduce the scene's per-gesture dispatch order for one node.
            node.onClick?.invoke()
            node.onLongClick?.invoke()
            node.onDoubleClick?.invoke()
        }
        assertThat(fired).containsExactly(
            "ShapeNode:click", "ShapeNode:long", "ShapeNode:double",
            "PathNode:click", "PathNode:long", "PathNode:double",
            "BatchNode:click", "BatchNode:long", "BatchNode:double",
            "CustomRenderNode:click", "CustomRenderNode:long", "CustomRenderNode:double"
        ).inOrder()
    }
}
