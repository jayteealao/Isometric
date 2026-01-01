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
data class RenderCommand(
    val id: String,
    val points: List<Point2D>,
    val color: IsoColor,
    val originalPath: Path,
    val originalShape: Shape?
)
