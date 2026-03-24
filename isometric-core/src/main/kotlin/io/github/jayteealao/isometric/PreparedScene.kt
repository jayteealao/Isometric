package io.github.jayteealao.isometric

/**
 * A prepared scene ready for rendering.
 * Contains platform-agnostic render commands sorted by depth.
 *
 * @property commands List of render commands, sorted back-to-front for correct depth ordering
 * @property width The viewport width used for this preparation
 * @property height The viewport height used for this preparation
 * @property projectionParams Projection and lighting parameters used to generate this scene.
 *   GPU backends upload these into their uniform buffer to replicate the CPU projection in
 *   the Transform+Cull+Light compute shader.
 * @property lightDirection Unnormalized light direction used to generate this scene.
 *   GPU backends normalize this internally when uploading to the uniform buffer.
 */
class PreparedScene(
    val commands: List<RenderCommand>,
    val width: Int,
    val height: Int,
    val projectionParams: ProjectionParams,
    val lightDirection: Vector,
) {
    override fun equals(other: Any?): Boolean =
        other is PreparedScene &&
            commands == other.commands &&
            width == other.width &&
            height == other.height &&
            projectionParams == other.projectionParams &&
            lightDirection == other.lightDirection

    override fun hashCode(): Int {
        var result = commands.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + projectionParams.hashCode()
        result = 31 * result + lightDirection.hashCode()
        return result
    }

    override fun toString(): String =
        "PreparedScene(commands=$commands, width=$width, height=$height, " +
            "projectionParams=$projectionParams, lightDirection=$lightDirection)"
}
