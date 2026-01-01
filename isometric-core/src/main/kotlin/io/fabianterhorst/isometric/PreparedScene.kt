package io.fabianterhorst.isometric

/**
 * A prepared scene ready for rendering.
 * Contains platform-agnostic render commands sorted by depth.
 *
 * @property commands List of render commands, sorted back-to-front for correct depth ordering
 * @property viewportWidth The viewport width used for this preparation
 * @property viewportHeight The viewport height used for this preparation
 */
data class PreparedScene(
    val commands: List<RenderCommand>,
    val viewportWidth: Int,
    val viewportHeight: Int
)
