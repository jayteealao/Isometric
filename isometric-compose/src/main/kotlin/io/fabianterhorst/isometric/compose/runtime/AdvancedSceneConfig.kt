package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.SceneProjector
import io.fabianterhorst.isometric.Vector

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
    cameraState = cameraState
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
