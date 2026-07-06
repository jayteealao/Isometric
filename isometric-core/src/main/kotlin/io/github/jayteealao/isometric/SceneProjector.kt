package io.github.jayteealao.isometric

/**
 * Abstraction over the isometric projection pipeline.
 *
 * Decouples the renderer from the concrete [IsometricEngine], enabling
 * tests to provide fake projectors that return canned [PreparedScene] data
 * without running real 3D projection and depth sorting.
 */
interface SceneProjector {
    companion object {
        /** Default light direction used when none is specified. */
        val DEFAULT_LIGHT_DIRECTION: Vector = Vector(2.0, -1.0, 3.0)
    }

    /**
     * Monotonically increasing version counter for internal state changes.
     * Incremented when mutable parameters (e.g., angle, scale) change,
     * signaling caches that projected output may be stale.
     *
     * **Contract:** Implementors MUST override this property. A projector whose
     * parameters never change should return a constant (e.g., `0L`); a projector
     * with mutable parameters MUST increment this value on each change so that
     * downstream caches detect staleness. Returning a stale constant when parameters
     * change causes silent cache misses — the projection appears frozen.
     *
     * This property is `abstract` so that implementors cannot silently inherit a
     * constant zero and accidentally suppress cache invalidation.
     */
    val projectionVersion: Long

    /**
     * Add a shape to the scene graph.
     */
    fun add(shape: Shape, color: IsoColor)

    /**
     * Add a path to the scene graph with optional metadata.
     */
    fun add(
        path: Path,
        color: IsoColor,
        originalShape: Shape? = null,
        id: String? = null,
        ownerNodeId: String? = null
    )

    /**
     * Clear all items from the scene graph.
     */
    fun clear()

    /**
     * Project the scene to 2D and produce sorted render commands.
     *
     * @param lightDirection Normalized light direction for shading.
     */
    fun projectScene(
        width: Int,
        height: Int,
        renderOptions: RenderOptions = RenderOptions.Default,
        lightDirection: Vector = DEFAULT_LIGHT_DIRECTION.normalize()
    ): PreparedScene

    /**
     * Find the frontmost item at a screen position (hit testing).
     */
    fun findItemAt(
        preparedScene: PreparedScene,
        x: Double,
        y: Double,
        order: HitOrder = HitOrder.FRONT_TO_BACK,
        touchRadius: Double = 0.0
    ): RenderCommand?
}
