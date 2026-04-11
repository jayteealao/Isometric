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
 * @property color The color to render (with lighting applied in Canvas modes)
 * @property baseColor The raw material color before lighting. GPU backends use this to avoid
 *   double-lighting (the GPU shader applies its own lighting). Defaults to [color] so
 *   existing callers are unaffected.
 * @property originalPath Reference to the original 3D path (for callbacks/hit testing)
 * @property originalShape Reference to the original shape (if this path belongs to one)
 * @property material Material data for textured rendering. Carries an `IsometricMaterial`
 *   instance from `isometric-shader` opaquely through the core pipeline. Renderers that
 *   depend on `isometric-shader` cast this to `IsometricMaterial`. Null means flat-color only.
 * @property uvCoords Per-vertex texture coordinates as a flat packed float array
 *   `[u0, v0, u1, v1, ...]`, matching the vertex order in [originalPath]. Null when
 *   no texture mapping is active.
 */
class RenderCommand(
    val commandId: String,
    val points: DoubleArray,
    val color: IsoColor,
    val originalPath: Path,
    val originalShape: Shape?,
    val ownerNodeId: String? = null,
    val baseColor: IsoColor = color,
    val material: MaterialData? = null,
    val uvCoords: FloatArray? = null,
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
            baseColor == other.baseColor &&
            originalPath == other.originalPath &&
            originalShape == other.originalShape &&
            ownerNodeId == other.ownerNodeId &&
            material == other.material &&
            (uvCoords contentEquals other.uvCoords)

    override fun hashCode(): Int {
        var result = commandId.hashCode()
        result = 31 * result + points.contentHashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + baseColor.hashCode()
        result = 31 * result + originalPath.hashCode()
        result = 31 * result + (originalShape?.hashCode() ?: 0)
        result = 31 * result + (ownerNodeId?.hashCode() ?: 0)
        result = 31 * result + (material?.hashCode() ?: 0)
        result = 31 * result + (uvCoords?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RenderCommand(commandId=$commandId, pointCount=$pointCount, color=$color, baseColor=$baseColor, originalPath=$originalPath, originalShape=$originalShape, ownerNodeId=$ownerNodeId, material=$material, uvCoords=${uvCoords?.size?.let { "${it / 2} coords" }})"
}
