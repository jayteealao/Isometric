package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.jayteealao.isometric.ComputeBackend
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.compose.runtime.render.RenderBackend
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.SceneProjector
import io.github.jayteealao.isometric.Vector

/**
 * Extended scene configuration that adds engine-level tuning and lifecycle hook callbacks
 * on top of [SceneConfig].
 *
 * Use this when you need fine-grained control over the rendering pipeline, such as
 * enabling path caching, configuring the spatial index, or receiving callbacks at
 * specific points in the render lifecycle.
 *
 * @param renderOptions Controls rendering behaviour such as sorting and face culling.
 * @param lightDirection Normalized direction vector for the scene's light source.
 * @param defaultColor Fallback [IsoColor] for shapes without an explicit color.
 * @param colorPalette Named semantic colors for consistent theming.
 * @param strokeStyle Determines how shape outlines are drawn.
 * @param gestures Tap and drag interaction configuration.
 * @param useNativeCanvas When `true`, renders to the platform's native canvas.
 * @param cameraState Optional pan/zoom camera state.
 * @param engine The [SceneProjector] implementation used for world-to-screen projection.
 *   Defaults to [IsometricEngine].
 * @param enablePathCaching When `true`, caches computed paths between frames to reduce
 *   allocation pressure. Useful for static or slowly-changing scenes.
 * @param enableSpatialIndex When `true`, builds a spatial index for efficient hit testing.
 * @param spatialIndexCellSize Cell size (in world units) of the spatial-index grid.
 *   Must be positive and finite.
 * @param forceRebuild When `true`, forces a full scene rebuild on the next frame,
 *   bypassing incremental diffing.
 * @param frameVersion Monotonically increasing version counter used to detect changes
 *   that require a re-render.
 * @param onHitTestReady Called once the hit-test function is available. The provided
 *   lambda maps screen coordinates to the [IsometricNode] at that position, or `null`
 *   if nothing was hit.
 * @param onFlagsReady Called with a [RuntimeFlagSnapshot] once runtime flags have been resolved.
 * @param onRenderError Called when a render command fails. Receives the command ID and
 *   the thrown [Throwable].
 * @param onEngineReady Called with the [SceneProjector] instance after the engine has
 *   been initialised for the current frame.
 * @param onRendererReady Called with the [IsometricRenderer] instance after the renderer
 *   has been initialised for the current frame.
 * @param onBeforeDraw Invoked inside the [DrawScope] immediately before the scene is drawn.
 *   Useful for drawing background layers or debug overlays.
 * @param onAfterDraw Invoked inside the [DrawScope] immediately after the scene is drawn.
 *   Useful for drawing foreground overlays or debug information.
 * @param onPreparedSceneReady Called with the fully-built [PreparedScene] before it is
 *   rendered, allowing inspection or serialisation of the scene graph.
 */
@Stable
class AdvancedSceneConfig(
    renderOptions: RenderOptions = RenderOptions.Default,
    lightDirection: Vector = SceneProjector.DEFAULT_LIGHT_DIRECTION.normalize(),
    defaultColor: IsoColor = IsoColor(33.0, 150.0, 243.0),
    colorPalette: ColorPalette = ColorPalette(),
    strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
    gestures: GestureConfig = GestureConfig.Disabled,
    useNativeCanvas: Boolean = false,
    cameraState: CameraState? = null,
    renderBackend: RenderBackend = RenderBackend.Canvas,
    computeBackend: ComputeBackend = ComputeBackend.Cpu,
    val engine: SceneProjector = IsometricEngine(),
    val enablePathCaching: Boolean = false,
    val enableSpatialIndex: Boolean = true,
    val spatialIndexCellSize: Double = IsometricRenderer.DEFAULT_SPATIAL_INDEX_CELL_SIZE,
    val forceRebuild: Boolean = false,
    val frameVersion: Long = 0L,
    val onHitTestReady: ((hitTest: (x: Double, y: Double) -> IsometricNode?) -> Unit)? = null,
    val onFlagsReady: ((RuntimeFlagSnapshot) -> Unit)? = null,
    val onRenderError: ((commandId: String, error: Throwable) -> Unit)? = null,
    val onEngineReady: ((SceneProjector) -> Unit)? = null,
    val onRendererReady: ((IsometricRenderer) -> Unit)? = null,
    val onBeforeDraw: (DrawScope.() -> Unit)? = null,
    val onAfterDraw: (DrawScope.() -> Unit)? = null,
    val onPreparedSceneReady: ((PreparedScene) -> Unit)? = null
) : SceneConfig(
    renderOptions = renderOptions,
    lightDirection = lightDirection,
    defaultColor = defaultColor,
    colorPalette = colorPalette,
    strokeStyle = strokeStyle,
    gestures = gestures,
    useNativeCanvas = useNativeCanvas,
    cameraState = cameraState,
    renderBackend = renderBackend,
    computeBackend = computeBackend,
) {
    init {
        require(spatialIndexCellSize.isFinite() && spatialIndexCellSize > 0.0) {
            "spatialIndexCellSize must be positive and finite, got $spatialIndexCellSize"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AdvancedSceneConfig &&
            super.equals(other) &&
            engine == other.engine &&
            enablePathCaching == other.enablePathCaching &&
            enableSpatialIndex == other.enableSpatialIndex &&
            spatialIndexCellSize == other.spatialIndexCellSize &&
            forceRebuild == other.forceRebuild &&
            frameVersion == other.frameVersion

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + engine.hashCode()
        result = 31 * result + enablePathCaching.hashCode()
        result = 31 * result + enableSpatialIndex.hashCode()
        result = 31 * result + spatialIndexCellSize.hashCode()
        result = 31 * result + forceRebuild.hashCode()
        result = 31 * result + frameVersion.hashCode()
        return result
    }

    override fun toString(): String =
        "AdvancedSceneConfig(enablePathCaching=$enablePathCaching, enableSpatialIndex=$enableSpatialIndex, spatialIndexCellSize=$spatialIndexCellSize, forceRebuild=$forceRebuild, frameVersion=$frameVersion, ${super.toString()})"
}
