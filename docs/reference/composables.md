---
title: Composables Reference
description: Complete reference for all Isometric composables
sidebar:
  order: 1
---

## Per-Node Interaction Props

`Shape`, `Path`, `Batch`, and `CustomNode` all accept six optional properties for
per-node interaction and identity:

| Prop | Type | Default | Behavior |
|---|---|---|---|
| `alpha` | `Float` | `1f` | Opacity multiplier in 0..1 range. Multiplied against the node's color alpha channel at render time. Throws `IllegalArgumentException` if outside 0..1. |
| `onClick` | `(() -> Unit)?` | `null` | Tap handler. Fires once after hit-test resolution if the tap lands on this node. Independent of any scene-level `GestureConfig.onTap`. |
| `onLongClick` | `(() -> Unit)?` | `null` | Long-press handler. Fires after the long-press timeout if the press lands on this node. The timeout is configurable per scene via `GestureConfig.longPressTimeoutMs` (default `500L`). |
| `onDoubleClick` | `(() -> Unit)?` | `null` | Double-tap handler. Fires on the second tap within the system double-tap window. A single tap still routes to `onClick`. |
| `testTag` | `String?` | `null` | Tag for test/diagnostic identification. Does not affect rendering or hit testing. |
| `nodeId` | `String?` | `null` | Caller-supplied stable identifier. When provided, must be non-blank and unique within the scene. Falls back to an auto-generated id when omitted. |

`Group` accepts only `testTag` and `nodeId` &mdash; for opacity or click handling on a
group, apply those props to the contained `Shape`/`Path`/`Batch` nodes individually.

`IsometricScene` always installs its `pointerInput` handler, so per-node `onClick`/`onLongClick`
fire without any `GestureConfig`. When a tap hits a node, the scene-level `onTap` (if any) runs
first, then the node's `onClick`. See the
[Per-Node Interactions guide](../guides/interactions.md) for full examples.

### IsometricScene

Entry point composable. Two overloads, selected by the static type of `config`:

**`IsometricScene(modifier, config: SceneConfig = SceneConfig(), content)`** — standard usage.

| Param | Type | Default | Description |
|---|---|---|---|
| modifier | Modifier | Modifier | Standard Compose modifier |
| config | SceneConfig | SceneConfig() | Scene configuration |
| content | @Composable IsometricScope.() -> Unit | — | Scene content |

**`IsometricScene(modifier, config: AdvancedSceneConfig, content)`** — advanced usage exposing
engine injection, renderer flags, and lifecycle hooks. See
[Scene Config reference](scene-config.md) and the
[Advanced Configuration guide](../guides/advanced-config.md).

| Param | Type | Default | Description |
|---|---|---|---|
| modifier | Modifier | Modifier | Standard Compose modifier |
| config | AdvancedSceneConfig | — | Required. Advanced scene configuration |
| content | @Composable IsometricScope.() -> Unit | — | Scene content |

### Shape

| Param | Type | Default | Description |
|---|---|---|---|
| geometry | Shape | — | Required. The 3D shape geometry |
| color | IsoColor | LocalDefaultColor | Shape color |
| alpha | Float | 1f | Opacity multiplier in 0..1 range. Applied to every face's alpha channel at render time. |
| position | Point | Point(0,0,0) | Additional position offset |
| rotation | Double | 0.0 | Rotation angle in radians |
| scale | Double | 1.0 | Uniform scale factor |
| rotationOrigin | Point? | null | Center of rotation |
| scaleOrigin | Point? | null | Center of scale |
| visible | Boolean | true | Visibility toggle |
| onClick | (() -> Unit)? | null | Tap handler. Fires when this node is hit-tested under a tap gesture. |
| onLongClick | (() -> Unit)? | null | Long-press handler. Fires after the long-press timeout when this node is hit-tested. |
| onDoubleClick | (() -> Unit)? | null | Double-tap handler. Fires on the second tap within the system double-tap window; a single tap still routes to `onClick`. |
| testTag | String? | null | Optional tag for testing and diagnostics. Does not affect rendering or hit testing. |
| nodeId | String? | null | Optional caller-supplied stable identifier. Must be unique within the scene when provided. |

> **Note**
>
`scale` must be positive and finite and `rotation` must be finite, or assignment throws
`IllegalArgumentException`. This validation applies to `Shape`, `Group`, `Path`, `Batch`, and
`CustomNode`.

### Group

| Param | Type | Default | Description |
|---|---|---|---|
| position | Point | Point(0,0,0) | Group position offset |
| rotation | Double | 0.0 | Group rotation (applied to all children) |
| scale | Double | 1.0 | Group scale (applied to all children) |
| rotationOrigin | Point? | null | Center of rotation |
| scaleOrigin | Point? | null | Center of scale |
| visible | Boolean | true | Visibility toggle for entire group |
| renderOptions | RenderOptions? | null | Override render options for this subtree |
| testTag | String? | null | Optional tag for testing and diagnostics. |
| nodeId | String? | null | Optional caller-supplied stable identifier. Must be unique within the scene when provided. |
| content | @Composable IsometricScope.() -> Unit | — | Child shapes and groups |

Transforms accumulate through the hierarchy. A shape inside a rotated group inherits the group's rotation.

`Group` does not accept `alpha`, `onClick`, or `onLongClick` directly &mdash; apply those to the
contained `Shape`, `Path`, or `Batch` nodes individually.

### Path (composable)

Renders a 2D polygon face positioned in 3D space. Used for flat surfaces like floors, walls, or labels.

| Param | Type | Default | Description |
|---|---|---|---|
| path | Path | — | Required. The 2D polygon face |
| color | IsoColor | LocalDefaultColor | Face color |
| alpha | Float | 1f | Opacity multiplier in 0..1 range. Applied to the face's alpha channel at render time. |
| position | Point | Point(0,0,0) | Additional position offset |
| rotation | Double | 0.0 | Rotation angle in radians |
| scale | Double | 1.0 | Uniform scale factor |
| rotationOrigin | Point? | null | Center of rotation |
| scaleOrigin | Point? | null | Center of scale |
| visible | Boolean | true | Visibility toggle |
| onClick | (() -> Unit)? | null | Tap handler. Fires when this face is hit-tested under a tap gesture. |
| onLongClick | (() -> Unit)? | null | Long-press handler. |
| onDoubleClick | (() -> Unit)? | null | Double-tap handler. Fires on the second tap within the system double-tap window. |
| testTag | String? | null | Optional tag for testing and diagnostics. |
| nodeId | String? | null | Optional caller-supplied stable identifier. Must be unique within the scene when provided. |

> **Caution**
>
Name collision with `kotlin.io.path.Path` — use an import alias:
```kotlin
import io.github.jayteealao.isometric.Path as IsoPath
```

### Batch

Takes `shapes: List<Shape>` instead of single geometry. Efficient for rendering many shapes with the same transforms.

| Param | Type | Default | Description |
|---|---|---|---|
| shapes | List\<Shape\> | — | Required. Shapes to render |
| color | IsoColor | LocalDefaultColor | Color applied to all shapes |
| alpha | Float | 1f | Opacity multiplier in 0..1 range. Applied uniformly to every batched shape. |
| position | Point | Point(0,0,0) | Position offset |
| rotation | Double | 0.0 | Rotation angle |
| scale | Double | 1.0 | Scale factor |
| rotationOrigin | Point? | null | Center of rotation |
| scaleOrigin | Point? | null | Center of scale |
| visible | Boolean | true | Visibility toggle |
| onClick | (() -> Unit)? | null | Tap handler. Fires when **any** shape in the batch is hit-tested. The batch is one node — individual shapes are not separately addressable. |
| onLongClick | (() -> Unit)? | null | Long-press handler. Same single-node semantics as `onClick`. |
| onDoubleClick | (() -> Unit)? | null | Double-tap handler. Same single-node semantics as `onClick`. |
| testTag | String? | null | Optional tag for testing and diagnostics. |
| nodeId | String? | null | Optional caller-supplied stable identifier. Must be unique within the scene when provided. |

```kotlin
// Render many shapes efficiently with shared transforms
val buildings = (0 until 10).map { i ->
    Prism(position = Point(i * 2.0, 0.0, 0.0))
}
Batch(
    shapes = buildings,
    color = IsoColor(33, 150, 243),
    position = Point(-10.0, 0.0, 0.0)
)
```

### If

| Param | Type | Description |
|---|---|---|
| condition | Boolean | When false, children are removed from the scene graph |
| content | @Composable IsometricScope.() -> Unit | Content to conditionally render |

```kotlin
var showRoof by remember { mutableStateOf(true) }
// ...
If(showRoof) {
    Shape(geometry = Pyramid(Point(0.0, 0.0, 2.0)), color = IsoColor.RED)
}
```

### ForEach

| Param | Type | Description |
|---|---|---|
| items | List\<T\> | Items to iterate |
| key | ((T) -> Any)? | Optional key function for stable identity |
| content | @Composable IsometricScope.(T) -> Unit | Content for each item |

```kotlin
ForEach(items = buildings, key = { it.id }) { building ->
    Shape(geometry = Prism(Point(building.x, building.y, 0.0)), color = building.color)
}
```

### CustomNode

Escape hatch for custom rendering. The `render` lambda receives the accumulated
`RenderContext` and the node's `nodeId`, and returns the `RenderCommand`s to draw.

| Param | Type | Default | Description |
|---|---|---|---|
| alpha | Float | 1f | Opacity multiplier in 0..1. Applied to every `RenderCommand` the lambda emits. |
| position | Point | Point(0,0,0) | Position offset |
| rotation | Double | 0.0 | Rotation angle in radians |
| scale | Double | 1.0 | Uniform scale factor |
| rotationOrigin | Point? | null | Center of rotation |
| scaleOrigin | Point? | null | Center of scale |
| visible | Boolean | true | Visibility toggle |
| renderOptions | RenderOptions? | null | Per-node render options override (null inherits from parent) |
| onClick | (() -> Unit)? | null | Tap handler. Requires the emitted commands to set `ownerNodeId = nodeId` so hit testing can resolve the tap. |
| onLongClick | (() -> Unit)? | null | Long-press handler. Same `ownerNodeId` requirement as `onClick`. |
| onDoubleClick | (() -> Unit)? | null | Double-tap handler. Same `ownerNodeId` requirement as `onClick`. |
| testTag | String? | null | Optional tag for testing and diagnostics. |
| nodeId | String? | null | Optional caller-supplied stable identifier. Must be unique within the scene when provided. |
| render | (context: RenderContext, nodeId: String) -> List\<RenderCommand\> | — | Required. Produces the node's render commands. |

```kotlin
CustomNode(render = { context, nodeId ->
    val face = context.applyTransformsToPath(myPath)
    listOf(
        RenderCommand(
            commandId = nodeId,
            points = emptyList(),          // engine.projectScene() fills projected points
            color = IsoColor.BLUE,
            originalPath = face,
            originalShape = null,
            ownerNodeId = nodeId
        )
    )
})
```

### TileGrid

`IsometricScope` extension composable. Renders a width × height isometric tile grid and routes
tap events to tile coordinates. No `GestureConfig` is required on the enclosing `IsometricScene`
when `onTileClick` is provided — gesture handling activates automatically.

| Param | Type | Default | Description |
|---|---|---|---|
| `width` | `Int` | — | Required. Number of tile columns. Must be ≥ 1. |
| `height` | `Int` | — | Required. Number of tile rows. Must be ≥ 1. |
| `config` | `TileGridConfig` | `TileGridConfig()` | Tile size, world origin, optional per-tile elevation. |
| `onTileClick` | `((TileCoordinate) -> Unit)?` | `null` | Called when the user taps a tile within the grid bounds. |
| `content` | `@Composable IsometricScope.(TileCoordinate) -> Unit` | — | Required. Rendered in each tile's local coordinate space. |

```kotlin
TileGrid(
    width = 10,
    height = 10,
    onTileClick = { coord -> selectedTile = coord }
) { coord ->
    Shape(
        geometry = Prism(Point.ORIGIN),
        color = if (coord == selectedTile) IsoColor(33, 150, 243) else IsoColor(200, 200, 200)
    )
}
```

> **Caution**
>
`onTileClick` assumes a flat z = 0 ground plane. Elevated terrain requires the escape hatch —
see [Tile Grid — Tap Accuracy with Elevation](../guides/tile-grid.md#tap-accuracy-with-elevation).

### Stack

`IsometricScope` extension composable. Arranges `count` children at equal `gap` spacing along
a world axis.

| Param | Type | Default | Description |
|---|---|---|---|
| `count` | `Int` | — | Required. Number of children. Must be ≥ 1. |
| `axis` | `StackAxis` | `StackAxis.Z` | World axis along which children are arranged. |
| `gap` | `Double` | `1.0` | World-unit distance between consecutive child origins. Must be non-zero and finite. Negative values reverse direction. |
| `content` | `@Composable IsometricScope.(index: Int) -> Unit` | — | Required. Receives zero-based index. |

```kotlin
Stack(count = 5, axis = StackAxis.Z, gap = 1.0) { floor ->
    Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(33, 150, floor * 40))
}
```
