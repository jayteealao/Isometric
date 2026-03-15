package io.fabianterhorst.isometric

/**
 * A prepared scene ready for rendering.
 * Contains platform-agnostic render commands sorted by depth.
 *
 * @property commands List of render commands, sorted back-to-front for correct depth ordering
 * @property viewportWidth The viewport width used for this preparation
 * @property viewportHeight The viewport height used for this preparation
 */
class PreparedScene(
    val commands: List<RenderCommand>,
    val viewportWidth: Int,
    val viewportHeight: Int
) {
    override fun equals(other: Any?): Boolean =
        other is PreparedScene &&
            commands == other.commands &&
            viewportWidth == other.viewportWidth &&
            viewportHeight == other.viewportHeight

    override fun hashCode(): Int {
        var result = commands.hashCode()
        result = 31 * result + viewportWidth
        result = 31 * result + viewportHeight
        return result
    }

    override fun toString(): String =
        "PreparedScene(commands=$commands, viewportWidth=$viewportWidth, viewportHeight=$viewportHeight)"
}
