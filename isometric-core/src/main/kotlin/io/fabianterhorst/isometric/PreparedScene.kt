package io.fabianterhorst.isometric

/**
 * A prepared scene ready for rendering.
 * Contains platform-agnostic render commands sorted by depth.
 *
 * @property commands List of render commands, sorted back-to-front for correct depth ordering
 * @property width The viewport width used for this preparation
 * @property height The viewport height used for this preparation
 */
class PreparedScene(
    val commands: List<RenderCommand>,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean =
        other is PreparedScene &&
            commands == other.commands &&
            width == other.width &&
            height == other.height

    override fun hashCode(): Int {
        var result = commands.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }

    override fun toString(): String =
        "PreparedScene(commands=$commands, width=$width, height=$height)"
}
