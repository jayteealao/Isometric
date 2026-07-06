package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.Point2D

/**
 * The engine-space box a dragged node is confined to.
 *
 * On every drag step the moved node's [IsometricNode.position] offset is clamped to this
 * rectangle, so a node can never be flung off the scene and lost. Bounds are measured on
 * the node's own position offset — which starts at the origin, since the geometry carries
 * the node's base coordinates — and expressed in engine units.
 *
 * [Default] is deliberately generous, so the simple `rememberNodeDragState()` hero case
 * never surprises a caller with an invisible wall. Pass a tighter box to fence nodes into
 * a known play area:
 *
 * ```
 * rememberNodeDragState(
 *     bounds = NodeDragBounds(minX = -1.0, maxX = 7.0, minY = -1.0, maxY = 5.0)
 * )
 * ```
 *
 * @param minX Minimum allowed x offset, in engine units.
 * @param maxX Maximum allowed x offset, in engine units. Must be `>= minX`.
 * @param minY Minimum allowed y offset, in engine units.
 * @param maxY Maximum allowed y offset, in engine units. Must be `>= minY`.
 */
@Immutable
data class NodeDragBounds(
    val minX: Double = -DEFAULT_EXTENT,
    val maxX: Double = DEFAULT_EXTENT,
    val minY: Double = -DEFAULT_EXTENT,
    val maxY: Double = DEFAULT_EXTENT
) {
    init {
        require(minX <= maxX) { "minX ($minX) must be <= maxX ($maxX)" }
        require(minY <= maxY) { "minY ($minY) must be <= maxY ($maxY)" }
    }

    companion object {
        /** Half-extent of the [Default] bounds box, in engine units. */
        const val DEFAULT_EXTENT: Double = 32.0

        /** A generous default box, ±[DEFAULT_EXTENT] on each axis. */
        val Default: NodeDragBounds = NodeDragBounds()
    }
}

/**
 * Holds the selection and drag configuration for the single-node drag affordance.
 *
 * Create one with [rememberNodeDragState] and hand it to [SceneConfig.nodeDragState]; the
 * scene then wires the whole interaction internally — a tap selects the node under the
 * pointer (tapping empty space clears the selection), and dragging the selected node moves
 * only it, clamped to [bounds], while dragging empty space still pans the camera.
 * Single-selection by construction: there is exactly one [selectedNodeId].
 *
 * [selectedNodeId] is read-only to callers — observe it to highlight the selected node —
 * while only the scene mutates it, so an invalid selection can never be assigned from
 * outside (api-design-guideline §6). This mirrors [CameraState]: a small, focused,
 * `@Stable` holder passed into [SceneConfig] (§1 cleanest hero, §8 composition, §10
 * idiomatic `remember` + snapshot state).
 *
 * @see rememberNodeDragState
 */
@Stable
class NodeDragState internal constructor(
    /** The engine-space box dragged nodes are clamped to. */
    val bounds: NodeDragBounds
) {
    private var _selectedNodeId by mutableStateOf<String?>(null)

    /**
     * The [IsometricNode.nodeId] of the currently selected node, or `null` when nothing is
     * selected. Backed by snapshot state — reading it in a composable highlights the
     * selected node reactively.
     */
    val selectedNodeId: String?
        get() = _selectedNodeId

    /**
     * Select the node identified by [nodeId], or clear the selection when `null`.
     *
     * Internal: only [IsometricScene] drives selection, from its hit-test on tap, which is
     * what keeps single-selection and the read-only public view honest.
     */
    internal fun select(nodeId: String?) {
        _selectedNodeId = nodeId
    }

    /**
     * The node's new position after one screen-space drag step.
     *
     * Converts the screen delta to a world-space displacement by routing it through the
     * full isometric inverse projection: the camera zoom is divided out first to get an
     * engine-space delta, then [IsometricEngine.screenToWorld] inverts the projection on
     * the node's z-plane. The resulting world delta is added to [current] and clamped
     * to [bounds]. Z is preserved.
     *
     * A pure function with no reference to the live selection: the scene's drag handler
     * and the device-free tests both call it, so the move math has a single home.
     *
     * @param current The node's current world position.
     * @param screenDx Screen-space x drag delta in pixels.
     * @param screenDy Screen-space y drag delta in pixels.
     * @param engine The scene's projection engine, used for inverse projection.
     * @param viewportWidth Canvas width in pixels.
     * @param viewportHeight Canvas height in pixels.
     * @param camera Active camera state for zoom correction, or null when no camera is applied.
     */
    internal fun draggedPosition(
        current: Point,
        screenDx: Double,
        screenDy: Double,
        engine: IsometricEngine,
        viewportWidth: Int,
        viewportHeight: Int,
        camera: CameraState?
    ): Point {
        val safeZoom = camera?.zoom?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        // Convert screen delta to engine-space delta by removing camera zoom.
        val engineDx = screenDx / safeZoom
        val engineDy = screenDy / safeZoom
        // Invert the full isometric projection: compute the world-space displacement that
        // corresponds to an (engineDx, engineDy) movement in engine (canvas) space.
        // Reference point (0, 0) and displaced point (engineDx, engineDy) in engine coords;
        // the difference of their world-space counterparts is the world delta.
        // Both use z=current.z so the node stays on its own z-plane during dragging.
        val worldOrigin = engine.screenToWorld(Point2D(0.0, 0.0), viewportWidth, viewportHeight, current.z)
        val worldTarget = engine.screenToWorld(Point2D(engineDx, engineDy), viewportWidth, viewportHeight, current.z)
        val worldDx = worldTarget.x - worldOrigin.x
        val worldDy = worldTarget.y - worldOrigin.y
        val nx = (current.x + worldDx).coerceIn(bounds.minX, bounds.maxX)
        val ny = (current.y + worldDy).coerceIn(bounds.minY, bounds.maxY)
        return Point(nx, ny, current.z)
    }
}

/**
 * Remember a [NodeDragState] across recompositions — the hero entry point for single-node
 * drag.
 *
 * Hand the result to [SceneConfig.nodeDragState] and the scene does the rest; read
 * [NodeDragState.selectedNodeId] to reflect the selection in your own UI:
 *
 * ```
 * val dragState = rememberNodeDragState()
 * IsometricScene(config = SceneConfig(nodeDragState = dragState)) { /* nodes */ }
 * Text("Selected: ${dragState.selectedNodeId ?: "none"}")
 * ```
 *
 * @param bounds The engine-space box dragged nodes are confined to. Defaults to a generous
 *   box ([NodeDragBounds.Default]); pass a tighter one to fence the play area. The holder is
 *   re-created only if a structurally different [bounds] is supplied.
 */
@Composable
fun rememberNodeDragState(
    bounds: NodeDragBounds = NodeDragBounds.Default
): NodeDragState = remember(bounds) { NodeDragState(bounds) }
