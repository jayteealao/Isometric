package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector

@Immutable
class AdvancedSceneConfig(
    renderOptions: RenderOptions = RenderOptions.Default,
    lightDirection: Vector = IsometricEngine.DEFAULT_LIGHT_DIRECTION.normalize(),
    defaultColor: IsoColor = IsoColor(33.0, 150.0, 243.0),
    colorPalette: ColorPalette = ColorPalette(),
    strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
    gestures: GestureConfig = GestureConfig.Disabled,
    useNativeCanvas: Boolean = false,
    val engine: IsometricEngine = IsometricEngine(),
    val enablePathCaching: Boolean = false,
    val enableSpatialIndex: Boolean = true,
    val spatialIndexCellSize: Double = IsometricRenderer.DEFAULT_SPATIAL_INDEX_CELL_SIZE,
    val forceRebuild: Boolean = false,
    val frameVersion: Long = 0L,
    val onHitTestReady: ((hitTest: (x: Double, y: Double) -> IsometricNode?) -> Unit)? = null,
    val onFlagsReady: ((RuntimeFlagSnapshot) -> Unit)? = null,
    val onEngineReady: ((IsometricEngine) -> Unit)? = null,
    val onRendererReady: ((IsometricRenderer) -> Unit)? = null
) : SceneConfig(
    renderOptions = renderOptions,
    lightDirection = lightDirection,
    defaultColor = defaultColor,
    colorPalette = colorPalette,
    strokeStyle = strokeStyle,
    gestures = gestures,
    useNativeCanvas = useNativeCanvas
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
