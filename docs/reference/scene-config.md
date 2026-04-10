---
title: Scene Configuration
description: SceneConfig, AdvancedSceneConfig, and RenderOptions reference
sidebar:
  order: 2
---

### SceneConfig

| Param | Type | Default | Description |
|---|---|---|---|
| renderOptions | RenderOptions | RenderOptions.Default | Depth sorting, culling, bounds checking |
| lightDirection | Vector | Vector(2,-1,3).normalize() | Directional light for face shading |
| defaultColor | IsoColor | IsoColor(33,150,243) | Default shape color when none specified |
| colorPalette | ColorPalette | ColorPalette() | Theme color palette |
| strokeStyle | StrokeStyle | FillAndStroke() | Edge rendering style |
| gestures | GestureConfig | GestureConfig.Disabled | Gesture handlers |
| useNativeCanvas | Boolean | false | Use Android native Canvas (faster on Android) |
| cameraState | CameraState? | null | Camera pan/zoom state |

### RenderOptions

| Param | Type | Default | Description |
|---|---|---|---|
| enableDepthSorting | Boolean | true | Sort shapes by depth for correct overlap |
| enableBackfaceCulling | Boolean | true | Skip rendering faces pointing away |
| enableBoundsChecking | Boolean | true | Skip shapes entirely outside viewport |
| enableBroadPhaseSort | Boolean | true | Use spatial grid for faster sorting |
| broadPhaseCellSize | Double | 100.0 | Grid cell size for broad-phase sorting |

Presets: `RenderOptions.Default`, `RenderOptions.NoDepthSorting`, `RenderOptions.NoCulling`

### AdvancedSceneConfig

Extends SceneConfig with additional fields:

| Param | Type | Default | Description |
|---|---|---|---|
| engine | SceneProjector | IsometricEngine() | Custom projection engine |
| enablePathCaching | Boolean | false | Cache path projections |
| enableSpatialIndex | Boolean | true | Spatial index for hit testing |
| spatialIndexCellSize | Double | default | Grid cell size |
| forceRebuild | Boolean | false | Force scene rebuild every frame |
| frameVersion | Long | 0L | Manual frame versioning |
| onFlagsReady | ((RuntimeFlagSnapshot) -> Unit)? | null | Receives active runtime flags after config is applied |
| onRenderError | ((String, Throwable) -> Unit)? | null | Called when a render command fails (commandId + exception) |
| onHitTestReady | ((hitTest: (x: Double, y: Double) -> IsometricNode?) -> Unit)? | null | Receive hit-test function |
| onEngineReady | callback? | null | Receive engine reference |
| onRendererReady | callback? | null | Receive renderer reference |
| onBeforeDraw | DrawScope callback? | null | Custom drawing before scene |
| onAfterDraw | DrawScope callback? | null | Custom drawing after scene |
| onPreparedSceneReady | callback? | null | Receive projected scene |

### StrokeStyle

Sealed class with three variants:

- **FillOnly** — shapes rendered without edges
- **Stroke(width: Float = 1f, color: IsoColor)** — edges only, no fill
- **FillAndStroke(width: Float = 1f, color: IsoColor)** — filled shapes with edges (default)

`DefaultStrokeColor`: near-transparent black `IsoColor(0, 0, 0, 25)`

For guidance on when to use `AdvancedSceneConfig` vs `SceneConfig`, see the [Advanced Config guide](../guides/advanced-config.md).
