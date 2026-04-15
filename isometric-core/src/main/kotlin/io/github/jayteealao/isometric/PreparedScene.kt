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
 * @property isProjected `true` when the commands contain CPU-projected 2D coordinates and are
 *   safe to render on a Canvas. `false` for scenes built by the Full WebGPU lightweight path
 *   ([SceneCache.rebuildForGpu]) whose commands carry only 3D vertices — Canvas rendering with
 *   such a scene produces a blank or garbled frame.
 */
class PreparedScene(
    val commands: List<RenderCommand>,
    val width: Int,
    val height: Int,
    val projectionParams: ProjectionParams,
    val lightDirection: Vector,
    val isProjected: Boolean = true,
) {
    override fun equals(other: Any?): Boolean =
        other is PreparedScene &&
            commands == other.commands &&
            width == other.width &&
            height == other.height &&
            projectionParams == other.projectionParams &&
            lightDirection == other.lightDirection &&
            isProjected == other.isProjected

    override fun hashCode(): Int {
        var result = commands.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + projectionParams.hashCode()
        result = 31 * result + lightDirection.hashCode()
        result = 31 * result + isProjected.hashCode()
        return result
    }

    override fun toString(): String =
        "PreparedScene(commands=$commands, width=$width, height=$height, " +
            "projectionParams=$projectionParams, lightDirection=$lightDirection)"
}
