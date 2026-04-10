---
title: Rendering Pipeline
description: From Compose recomposition to pixels on screen
sidebar:
  order: 3
---

This page traces the complete path from a Compose state change to pixels on screen. Understanding this pipeline helps you debug rendering issues and make informed performance decisions.

## End-to-End Flow

```
Compose state change
  |
  v
Recomposition: composables execute, node tree updates
  |
  v
Is node tree dirty?  ----[no]----> Cache hit: reuse PreparedScene
  |                                         |
  [yes]                                     |
  |                                         |
  v                                         |
Traverse node tree, engine.add() each shape |
  |                                         |
  v                                         |
engine.projectScene(width, height)          |
  |                                         |
  v                                         |
PreparedScene (sorted RenderCommands)  <----+
  |
  v
DrawScope.drawPath() for each command
  |
  v
Pixels on screen
```

When no state has changed, the entire left branch is skipped. The cached `PreparedScene` is drawn directly, making idle scenes essentially free.

## Cache Invalidation

Three independent conditions must all be clean for a cache hit. If any one changes, the scene re-projects:

| Condition | What changed | How it is detected |
|-----------|-------------|-------------------|
| Dirty tree flag | A node's content changed (color, position, geometry) | `markDirty()` propagates to root, increments `sceneVersion` |
| Projection version | Engine angle or scale changed | `projectionVersion` on `SceneProjector` increments |
| Viewport dimensions | Canvas width or height changed (e.g., device rotation) | Width/height compared against cached `PreparedScene` dimensions |

Additionally, `frameVersion` (set on `AdvancedSceneConfig`) acts as an external cache key. Incrementing it forces re-projection even when the other three conditions are clean.

```kotlin
// All three must match for a cache hit:
val cacheValid = !tree.isDirty
    && engine.projectionVersion == cachedVersion
    && width == cachedScene.width
    && height == cachedScene.height
    && frameVersion == cachedFrameVersion
```

## RenderCommand

`RenderCommand` is the atomic unit of rendering. Each command represents one face to draw:

| Field | Type | Description |
|-------|------|-------------|
| `commandId` | `String` | Unique identifier for this command (used in error reporting) |
| `points` | `List<Point2D>` | Projected 2D screen coordinates of the face vertices |
| `color` | `IsoColor` | Final color after lighting is applied |
| `originalPath` | `Path` | The original 3D geometry before projection |
| `originalShape` | `Shape?` | The parent shape, if this face came from a multi-face shape |
| `ownerNodeId` | `String?` | The node that produced this command (used for hit testing) |

A `Prism` produces six faces (six `RenderCommand` objects). After backface culling, typically three are visible. After bounds checking, commands for off-screen faces are discarded.

```kotlin
// Intercept render commands via onPreparedSceneReady
IsometricScene(
    config = AdvancedSceneConfig(
        onPreparedSceneReady = { scene: PreparedScene ->
            println("Rendering ${scene.commands.size} faces")
            scene.commands.forEach { cmd ->
                println("  ${cmd.commandId}: ${cmd.points.size} vertices, color=${cmd.color}")
            }
        }
    )
) {
    Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(33, 150, 243))
}
```

## Lighting

The light direction vector (default `Vector(2, -1, 3).normalize()`) determines how each face is shaded during projection. For each face, the engine computes the dot product of the face normal and the light direction. Faces angled toward the light are brightened via `IsoColor.lighten()`, while faces angled away are darkened. This produces the classic top-right isometric illumination where top faces are brightest, right-facing faces are mid-tone, and left-facing faces are darkest.

Override the direction via `SceneConfig.lightDirection` or `LocalLightDirection` to simulate different lighting setups (e.g. top-down, side-lighting). See [Theming & Colors](../guides/theming.md) for how to set it.

## Path Caching and Native Canvas

Two optional optimizations reduce per-frame allocation in the draw phase:

- **Path caching** (`enablePathCaching = true`) pre-converts projected point lists into `androidx.compose.ui.graphics.Path` objects and reuses them until the `PreparedScene` changes. Most effective for static or slowly-changing scenes.
- **Native canvas** (`useNativeCanvas = true`) bypasses Compose `DrawScope` entirely on Android, rendering directly to `android.graphics.Canvas` with reused `Paint` objects. Eliminates Compose Path allocation and DrawScope delegation overhead for roughly a 2x speedup.

These are independent and can be combined. See the [Performance guide](../guides/performance.md) for when to enable each and how to configure them.

## PreparedScene

`PreparedScene` is the output of `engine.projectScene()`. It is a self-contained snapshot of the projected scene:

| Field | Type | Description |
|-------|------|-------------|
| `commands` | `List<RenderCommand>` | Sorted list of face-draw commands, ready for rendering |
| `width` | `Int` | Viewport width at the time of projection |
| `height` | `Int` | Viewport height at the time of projection |

The `PreparedScene` is cached between frames and reused when the node tree is clean. It can also be intercepted via the `onPreparedSceneReady` hook for purposes beyond rendering:

```kotlin
@Composable
fun ExportableScene() {
    var lastScene by remember { mutableStateOf<PreparedScene?>(null) }

    IsometricScene(
        config = AdvancedSceneConfig(
            onPreparedSceneReady = { lastScene = it }
        )
    ) {
        Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(33, 150, 243))
        Shape(geometry = Pyramid(Point(2.0, 0.0, 0.0)), color = IsoColor(76, 175, 80))
    }

    // Use the prepared scene for export or analysis
    lastScene?.let { scene ->
        Text("Scene has ${scene.commands.size} render commands")
    }
}
```

Use cases for intercepting the `PreparedScene`:

- **Export** -- serialize the commands to SVG or another vector format
- **Testing** -- assert the number of visible faces, verify colors after lighting
- **Analytics** -- track scene complexity over time
- **Server-side rendering** -- project on the server, send commands to the client

> **Tip**
>
The `PreparedScene` contains fully projected 2D coordinates and final colors. Everything needed to reproduce the rendered image is in the command list, making it a natural serialization boundary.
