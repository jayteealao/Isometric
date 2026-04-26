---
title: Composables Reference
description: Complete reference for all Isometric composables
sidebar:
  order: 1
---

### IsometricScene

Entry point composable. Two overloads:

| Param | Type | Default | Description |
|---|---|---|---|
| modifier | Modifier | Modifier | Standard Compose modifier |
| config | SceneConfig | SceneConfig() | Scene configuration |
| content | IsometricScope.() -> Unit | — | Scene content |

### Shape

| Param | Type | Default | Description |
|---|---|---|---|
| geometry | Shape | — | Required. The 3D shape geometry |
| color | IsoColor | LocalDefaultColor | Shape color |
| position | Point | Point(0,0,0) | Additional position offset |
| rotation | Double | 0.0 | Rotation angle in radians |
| scale | Double | 1.0 | Uniform scale factor |
| rotationOrigin | Point? | null | Center of rotation |
| scaleOrigin | Point? | null | Center of scale |
| visible | Boolean | true | Visibility toggle |

### Shape (textured overload)

`isometric-shader` adds a `material` parameter for bitmap textures and per-face
materials. Requires `ProvideTextureRendering` somewhere up the composition tree.
See the [Textured Materials guide](../guides/textured-materials.md).

| Param | Type | Default | Description |
|---|---|---|---|
| geometry | Shape | — | Required. The 3D shape geometry |
| material | MaterialData | LocalDefaultColor | `IsoColor` for flat color, or an `IsometricMaterial` (`Textured`, `PerFace.Prism`, `PerFace.Cylinder`, etc.) for bitmap rendering |
| position | Point | Point(0,0,0) | Additional position offset |
| rotation | Double | 0.0 | Rotation angle in radians |
| scale | Double | 1.0 | Uniform scale factor |
| rotationOrigin | Point? | null | Center of rotation |
| scaleOrigin | Point? | null | Center of scale |
| visible | Boolean | true | Visibility toggle |

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
| content | IsometricScope.() -> Unit | — | Child shapes and groups |

Transforms accumulate through the hierarchy. A shape inside a rotated group inherits the group's rotation.

### Path (composable)

Renders a 2D polygon face positioned in 3D space. Used for flat surfaces like floors, walls, or labels.

| Param | Type | Default | Description |
|---|---|---|---|
| path | Path | — | Required. The 2D polygon face |
| color | IsoColor | LocalDefaultColor | Face color |
| position | Point | Point(0,0,0) | Additional position offset |
| rotation | Double | 0.0 | Rotation angle in radians |
| scale | Double | 1.0 | Uniform scale factor |
| rotationOrigin | Point? | null | Center of rotation |
| scaleOrigin | Point? | null | Center of scale |
| visible | Boolean | true | Visibility toggle |

> **Caution**
>
Name collision with `kotlin.io.path.Path` — use an import alias:
```kotlin
import io.github.jayteealao.isometric.Path as IsoPath
```

### Path (textured overload)

`isometric-shader` adds a `material` parameter to `Path` matching the
[Shape textured overload](#shape-textured-overload). Same caveats — requires
`ProvideTextureRendering` in the composition.

### ProvideTextureRendering

Installs the texture rendering hook, LRU `TextureCache`, and optional error
callback. Wrap the part of your composition that uses textured materials.

| Param | Type | Default | Description |
|---|---|---|---|
| config | TextureCacheConfig | TextureCacheConfig() | Cache size / byte limit |
| loader | TextureLoader | defaultTextureLoader() | Decoder for `TextureSource` → `Bitmap` |
| onTextureLoadError | ((TextureSource) -> Unit)? | null | Fired on the main thread for decode and atlas-rebuild failures |
| content | @Composable () -> Unit | — | Composition that may use textured materials |

```kotlin
ProvideTextureRendering(
    config = TextureCacheConfig(maxSize = 32, maxBytes = 16L * 1024 * 1024),
    onTextureLoadError = { src -> Log.w("MyApp", "Texture failed: $src") }
) {
    IsometricScene { /* ... */ }
}
```

### Texture material factories

These helper functions construct `Textured` materials. They are not composables
— call them anywhere a `MaterialData` is expected.

| Factory | Source | Description |
|---|---|---|
| `texturedResource(resId, tint?, transform?)` | Drawable resource | Validates `resId != 0`. Recommended for app-bundled textures. |
| `texturedAsset(path, tint?, transform?)` | Asset file | Path is rejected if it contains `..`, leading `/`, or null bytes. |
| `texturedBitmap(bitmap, tint?, transform?)` | In-memory bitmap | For procedural or runtime-decoded sources. |

### Per-face material DSLs

Each shape that supports per-face materials has its own scope DSL:

| DSL | Slots | Returns |
|---|---|---|
| `prismPerFace { top; sides; bottom; default }` | 4 named slots | `PerFace.Prism` |
| `cylinderPerFace { side; topCap; bottomCap; default }` | 3 named slots + default | `PerFace.Cylinder` |
| `pyramidPerFace { sides; base; default }` | 2 named slots + default | `PerFace.Pyramid` |
| `stairsPerFace { riser; tread; sides; default }` | 3 named slots + default | `PerFace.Stairs` |
| `octahedronPerFace { ... }` | per-triangle slots | `PerFace.Octahedron` |

Faces with no DSL entry fall through to `default`, then to flat color.

### LocalTextureErrorCallback

`CompositionLocal<((TextureSource) -> Unit)?>` providing the texture-load error
callback to renderers. Set via `ProvideTextureRendering(onTextureLoadError = ...)`
rather than reading directly.

### Batch

Takes `shapes: List<Shape>` instead of single geometry. Efficient for rendering many shapes with the same transforms.

| Param | Type | Default | Description |
|---|---|---|---|
| shapes | List\<Shape\> | — | Required. Shapes to render |
| color | IsoColor | LocalDefaultColor | Color applied to all shapes |
| position | Point | Point(0,0,0) | Position offset |
| rotation | Double | 0.0 | Rotation angle |
| scale | Double | 1.0 | Scale factor |
| rotationOrigin | Point? | null | Center of rotation |
| scaleOrigin | Point? | null | Center of scale |
| visible | Boolean | true | Visibility toggle |

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
| content | IsometricScope.() -> Unit | Content to conditionally render |

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
| content | IsometricScope.(T) -> Unit | Content for each item |

```kotlin
ForEach(items = buildings, key = { it.id }) { building ->
    Shape(geometry = Prism(Point(building.x, building.y, 0.0)), color = building.color)
}
```

### CustomNode

Escape hatch for custom rendering. Takes a `render` lambda that returns `List<RenderCommand>`.

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
