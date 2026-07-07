---
title: Scene Configuration
description: SceneConfig, AdvancedSceneConfig, and RenderOptions reference
sidebar:
  order: 2
---

### SceneConfig

`SceneConfig` is `@Stable` (not `@Immutable`): Compose tracks equality correctly, but mutable
properties inside — like the engine's `angle` and `scale` — can change after construction. Compose
will not recompose automatically on engine mutations; use `AdvancedSceneConfig.engine.projectionVersion`
as a change signal when you need to react to engine parameter changes.

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
| nodeDragState | NodeDragState? | null | State object for the single-node tap-to-select-then-drag affordance. See [Drag & Camera how-to](../guides/drag-and-camera.md). |

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

> **Note**
>
**Custom `SceneProjector` implementors must override `projectionVersion`** (it is `abstract`).
Increment this value whenever your projection parameters change so the scene cache detects the
change and rebuilds. Failing to do so leaves the cache stale and changes are not reflected on screen.

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
- **Stroke(width: Float = 1f, color: IsoColor = DefaultStrokeColor)** — edges only, no fill
- **FillAndStroke(width: Float = 1f, color: IsoColor = DefaultStrokeColor)** — filled shapes with edges (default)

`width` must be positive. `DefaultStrokeColor` is near-transparent black,
`IsoColor(0.0, 0.0, 0.0, 25.0)` (~10% opacity).

### GestureConfig

Scene-level gesture handlers, passed via `SceneConfig.gestures`. Any callback left `null` is
ignored; `enabled` is `true` when at least one is set. The default is `GestureConfig.Disabled`,
a shared no-op instance.

| Param | Type | Default | Description |
|---|---|---|---|
| onTap | ((TapEvent) -> Unit)? | null | Tap handler. `TapEvent` carries screen `x`/`y` and the hit `node` (nullable). |
| onDrag | ((DragEvent) -> Unit)? | null | Fires continuously during a drag. The `DragEvent` carries the live absolute pointer position in `x`/`y` and the per-event movement in `delta` (`delta.dx`/`delta.dy`); accumulate `delta` to track travel. |
| onDragStart | ((DragEvent) -> Unit)? | null | Fires once when a drag is first recognized. The `DragEvent` carries the absolute start position in `x`/`y`; `delta` is `null`. |
| onDragEnd | (() -> Unit)? | null | Fires once when the drag finishes. |
| dragThreshold | Float | 8f | Pixels the pointer must move before a drag is recognized. Must be non-negative. |
| longPressTimeoutMs | Long | 500L | Milliseconds a press must be held before a node's `onLongClick` fires. Must be positive. Default matches the platform `ViewConfiguration`. |

`GestureConfig` has no long-press callback — long-press is a per-node prop (`onLongClick`), but its
timeout is configured here via `longPressTimeoutMs`. Double-tap is likewise a per-node prop
(`onDoubleClick`). See [Per-Node Interactions](../guides/interactions.md).

### CameraState

Mutable pan/zoom state, passed via `SceneConfig.cameraState`. All properties are Compose
snapshot state, so mutations trigger recomposition.

| Member | Type | Default | Description |
|---|---|---|---|
| panX | Double | 0.0 | Horizontal pan offset in pixels. Must be finite. |
| panY | Double | 0.0 | Vertical pan offset in pixels. Must be finite. |
| zoom | Double | 1.0 | Zoom factor. Must be positive and finite. |
| pan(deltaX, deltaY) | method | — | Pans by a delta. |
| zoomBy(factor) | method | — | Multiplies `zoom` by a positive factor. |
| reset() | method | — | Resets pan to 0 and zoom to 1. |

For guidance on when to use `AdvancedSceneConfig` vs `SceneConfig`, see the [Advanced Config guide](../guides/advanced-config.md).
