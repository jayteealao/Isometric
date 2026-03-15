package io.fabianterhorst.isometric

/**
 * A platform-agnostic rendering command representing a single polygon to draw.
 * Contains the 2D screen-space points and color, ready for rendering.
 *
 * @property id Stable identifier for this command (for hit testing and tracking)
 * @property points The 2D screen-space polygon vertices
 * @property color The color to render (with lighting applied)
 * @property originalPath Reference to the original 3D path (for callbacks/hit testing)
 * @property originalShape Reference to the original shape (if this path belongs to one)
 */
class RenderCommand(
    val id: String,
    val points: List<Point2D>,
    val color: IsoColor,
    val originalPath: Path,
    val originalShape: Shape?,
    val ownerNodeId: String? = null
) {
    override fun equals(other: Any?): Boolean =
        other is RenderCommand &&
            id == other.id &&
            points == other.points &&
            color == other.color &&
            originalPath == other.originalPath &&
            originalShape == other.originalShape &&
            ownerNodeId == other.ownerNodeId

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + points.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + originalPath.hashCode()
        result = 31 * result + (originalShape?.hashCode() ?: 0)
        result = 31 * result + (ownerNodeId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RenderCommand(id=$id, points=$points, color=$color, originalPath=$originalPath, originalShape=$originalShape, ownerNodeId=$ownerNodeId)"
}
