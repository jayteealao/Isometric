package io.github.jayteealao.isometric

/**
 * A platform-agnostic rendering command representing a single polygon to draw.
 * Contains the 2D screen-space points and color, ready for rendering.
 *
 * Screen points are stored as a flat [DoubleArray] in `[x0, y0, x1, y1, ...]` layout
 * to avoid per-vertex object allocation in the hot rendering path. Use [pointCount]
 * for the number of vertices and [pointX]/[pointY] for indexed access.
 *
 * @property commandId Stable identifier for this command (for hit testing and tracking)
 * @property points Flat packed 2D screen-space vertices: [x0, y0, x1, y1, ...]
 * @property color The color to render (with lighting applied)
 * @property originalPath Reference to the original 3D path (for callbacks/hit testing)
 * @property originalShape Reference to the original shape (if this path belongs to one)
 */
class RenderCommand(
    val commandId: String,
    val points: DoubleArray,
    val color: IsoColor,
    val originalPath: Path,
    val originalShape: Shape?,
    val ownerNodeId: String? = null
) {
    /** Number of 2D vertices in [points]. */
    val pointCount: Int get() = points.size / 2

    /** X coordinate of the vertex at [index]. */
    fun pointX(index: Int): Double = points[index * 2]

    /** Y coordinate of the vertex at [index]. */
    fun pointY(index: Int): Double = points[index * 2 + 1]

    override fun equals(other: Any?): Boolean =
        other is RenderCommand &&
            commandId == other.commandId &&
            points.contentEquals(other.points) &&
            color == other.color &&
            originalPath == other.originalPath &&
            originalShape == other.originalShape &&
            ownerNodeId == other.ownerNodeId

    override fun hashCode(): Int {
        var result = commandId.hashCode()
        result = 31 * result + points.contentHashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + originalPath.hashCode()
        result = 31 * result + (originalShape?.hashCode() ?: 0)
        result = 31 * result + (ownerNodeId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RenderCommand(commandId=$commandId, pointCount=$pointCount, color=$color, originalPath=$originalPath, originalShape=$originalShape, ownerNodeId=$ownerNodeId)"
}
