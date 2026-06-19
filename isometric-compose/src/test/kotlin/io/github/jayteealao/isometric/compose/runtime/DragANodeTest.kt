package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import io.github.jayteealao.isometric.Point
import org.junit.Test

/**
 * Locks the device-free contract behind the single-node drag hero (`DragNodeSample` +
 * [SceneConfig.nodeDragState]): the selection state machine and the screen→engine drag math,
 * exercised through the very [NodeDragState] members the scene calls.
 *
 * Device-free by design — callback/state pattern, see `InteractionHarnessReadme` for why this
 * module avoids Robolectric. What is NOT covered here: that a real touch routed through
 * `pointerInput` selects on tap, moves the node on drag, and leaves the camera un-panned while
 * a node is dragged. That pipeline needs a Compose host / emulator and is verified
 * interactively by the sample; these tests pin the selection semantics, the clamp, and the
 * delta conversion that the scene delegates to [NodeDragState].
 */
class DragANodeTest {

    private fun dragState(bounds: NodeDragBounds = NodeDragBounds.Default) =
        NodeDragState(bounds)

    // --- Selection state machine: tap → select / deselect / single-select -----------------

    @Test
    fun `tap on a node selects it`() {
        val state = dragState()
        assertThat(state.selectedNodeId).isNull()        // nothing selected initially
        state.select("node-center")                      // scene routes hitNode.nodeId here
        assertThat(state.selectedNodeId).isEqualTo("node-center")
    }

    @Test
    fun `tap on empty space clears the selection`() {
        val state = dragState()
        state.select("node-center")
        state.select(null)                               // hitNode == null → deselect
        assertThat(state.selectedNodeId).isNull()
    }

    @Test
    fun `selecting a second node replaces the first — single selection only`() {
        val state = dragState()
        state.select("node-w")
        state.select("node-e")
        assertThat(state.selectedNodeId).isEqualTo("node-e")   // never two at once
    }

    // --- Drag math: screen→engine conversion, clamp, z preservation -----------------------

    @Test
    fun `dragging moves the node by the screen delta at zoom 1`() {
        val state = dragState()
        val moved = state.draggedPosition(
            current = Point(0.0, 0.0, 0.1),
            screenDx = 2.0, screenDy = -1.5, zoom = 1.0
        )
        assertThat(moved.x).isWithin(1e-9).of(2.0)
        assertThat(moved.y).isWithin(1e-9).of(-1.5)
        assertThat(moved.z).isEqualTo(0.1)               // z is preserved
    }

    @Test
    fun `screen delta is converted to engine space by dividing by zoom`() {
        val state = dragState()
        // At 2x zoom a 10px screen drag is a 5-unit engine move.
        val moved = state.draggedPosition(
            current = Point.ORIGIN, screenDx = 10.0, screenDy = 4.0, zoom = 2.0
        )
        assertThat(moved.x).isWithin(1e-9).of(5.0)
        assertThat(moved.y).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `a non-positive or non-finite zoom is treated as 1`() {
        val state = dragState()
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { badZoom ->
            val moved = state.draggedPosition(Point.ORIGIN, 3.0, 3.0, badZoom)
            assertThat(moved.x).isWithin(1e-9).of(3.0)
            assertThat(moved.y).isWithin(1e-9).of(3.0)
        }
    }

    @Test
    fun `dragging past the bounds clamps the node and keeps it reachable`() {
        val state = dragState(NodeDragBounds(minX = -4.0, maxX = 4.0, minY = -4.0, maxY = 4.0))
        // A huge drag toward +x/+y stops at the box corner, not off-scene.
        val maxed = state.draggedPosition(Point(3.0, 3.0, 0.1), 100.0, 100.0, 1.0)
        assertThat(maxed.x).isEqualTo(4.0)
        assertThat(maxed.y).isEqualTo(4.0)
        assertThat(maxed.z).isEqualTo(0.1)
        // And toward -x/-y stops at the opposite corner.
        val mined = state.draggedPosition(Point(-3.0, -3.0, 0.1), -100.0, -100.0, 1.0)
        assertThat(mined.x).isEqualTo(-4.0)
        assertThat(mined.y).isEqualTo(-4.0)
    }

    // --- The scene's exact mutation, reproduced on a live node ----------------------------

    @Test
    fun `applying draggedPosition mutates the node position — select then immediately drag`() {
        val state = dragState()
        val node: IsometricNode = GroupNode().apply { explicitNodeId = "node-center" }
        state.select("node-center")
        // Reproduces the scene's Move branch:
        //   node.position = state.draggedPosition(node.position, dx, dy, zoom)
        repeat(3) {
            node.position = state.draggedPosition(node.position, 1.0, 0.5, 1.0)
        }
        // Three +1.0,+0.5 steps accumulated from the origin offset.
        assertThat(node.position.x).isWithin(1e-9).of(3.0)
        assertThat(node.position.y).isWithin(1e-9).of(1.5)
        // The selection that gated the move is still the node just dragged — no stale-null.
        assertThat(state.selectedNodeId).isEqualTo(node.nodeId)
    }

    // --- Disambiguation proxy: a node move reads only zoom, never pans the camera ---------

    @Test
    fun `moving a node leaves the camera untouched`() {
        val state = dragState()
        val camera = CameraState(panX = 10.0, panY = 20.0, zoom = 2.0)
        val node = GroupNode()
        // The scene's node-move branch consults only camera.zoom (a read) and never pans.
        node.position = state.draggedPosition(node.position, 8.0, 8.0, camera.zoom)
        assertThat(camera.panX).isEqualTo(10.0)
        assertThat(camera.panY).isEqualTo(20.0)
        assertThat(node.position.x).isWithin(1e-9).of(4.0)   // 8px / zoom(2) → 4 engine units
    }

    // --- Bounds validation ----------------------------------------------------------------

    @Test
    fun `invalid bounds are rejected`() {
        val badX = runCatching { NodeDragBounds(minX = 5.0, maxX = -5.0) }
        assertThat(badX.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        val badY = runCatching { NodeDragBounds(minY = 5.0, maxY = -5.0) }
        assertThat(badY.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}
