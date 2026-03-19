---
title: Advanced Patterns
description: Custom composables, direct engine access, and escape hatches
sidebar:
  order: 4
---

These recipes go beyond basic usage: custom composable extensions, direct engine access, `CustomNode`, batch rendering, and lifecycle hooks.

## Custom Composable Extension: Tower

Create reusable domain-specific composables by writing extension functions on `IsometricScope`. This `Tower` stacks multiple `Prism` shapes vertically with alternating colors.

```kotlin
@Composable
fun IsometricScope.Tower(
    base: Point,
    floors: Int = 3,
    floorHeight: Double = 1.0,
    colorA: IsoColor = IsoColor(33, 150, 243),
    colorB: IsoColor = IsoColor(100, 180, 255)
) {
    Group(position = base) {
        ForEach((0 until floors).toList(), key = { it }) { floor ->
            Shape(
                geometry = Prism(
                    Point(0.0, 0.0, floor * floorHeight),
                    1.0, 1.0, floorHeight
                ),
                color = if (floor % 2 == 0) colorA else colorB
            )
        }
        // Roof cap
        Shape(
            geometry = Pyramid(
                Point(0.0, 0.0, floors * floorHeight),
                1.0, 1.0, 0.5
            ),
            color = IsoColor(160, 60, 50)
        )
    }
}

@Composable
fun TowerDemo() {
    IsometricScene {
        Tower(base = Point(0.0, 0.0, 0.0), floors = 4)
        Tower(base = Point(3.0, 0.0, 0.0), floors = 2, colorA = IsoColor.ORANGE)
        Tower(base = Point(0.0, 3.0, 0.0), floors = 6)
    }
}
```

Because `Tower` is a `@Composable` extension on `IsometricScope`, it composes naturally inside `IsometricScene` and can be mixed with other shapes and groups.

## CustomNode with RenderCommands

`CustomNode` is the escape hatch for geometry that does not fit the built-in `Shape`/`Path`/`Batch` composables. The render function receives a `RenderContext` with accumulated transforms and returns `RenderCommand` entries.

```kotlin
@Composable
fun CustomGroundPlane() {
    IsometricScene {
        CustomNode(render = { context, nodeId ->
            val ground = Path(
                Point(-5.0, -5.0, 0.0),
                Point(5.0, -5.0, 0.0),
                Point(5.0, 5.0, 0.0),
                Point(-5.0, 5.0, 0.0)
            )
            val transformed = context.applyTransformsToPath(ground)

            listOf(
                RenderCommand(
                    commandId = "ground_$nodeId",
                    points = emptyList(),
                    color = IsoColor(220, 220, 220),
                    originalPath = transformed,
                    originalShape = null,
                    ownerNodeId = nodeId
                )
            )
        })

        // Regular shapes on top of the custom ground
        Shape(
            geometry = Prism(Point.ORIGIN, 2.0, 2.0, 2.0),
            color = IsoColor(33, 150, 243)
        )
    }
}
```

Key points:

- Use `context.applyTransformsToPath` to apply the accumulated parent transforms (position, rotation, scale) to your custom geometry.
- Set `ownerNodeId = nodeId` so hit testing can identify which `CustomNode` was tapped.
- The `points` field is left empty because `projectScene` produces new commands with projected 2D points.

## Batch for Bulk Rendering

`Batch` renders many shapes with shared color and transforms, producing fewer Compose nodes than individual `Shape` calls. Use it for grids, particle systems, or any large collection of same-colored geometry.

```kotlin
@Composable
fun BatchGrid() {
    val cubes = remember {
        (0 until 10).flatMap { x ->
            (0 until 10).map { y ->
                Prism(Point(x * 1.2, y * 1.2, 0.0), 1.0, 1.0, 0.5)
            }
        }
    }

    IsometricScene {
        Batch(
            shapes = cubes,
            color = IsoColor(33, 150, 243)
        )
    }
}
```

This creates a single `BatchNode` with 100 shapes instead of 100 individual `ShapeNode` instances, reducing composition overhead.

> **Tip**
>
`Batch` works best when all shapes share the same color. If you need different colors, group shapes by color into multiple `Batch` calls.

## Per-Subtree Render Options

Use `Group` with `renderOptions` to override depth sorting or culling for a specific subtree. This is useful when you know a group of shapes does not overlap and can skip the expensive depth sort.

```kotlin
@Composable
fun RenderOptionsDemo() {
    IsometricScene {
        // This group uses full depth sorting (default)
        Group(position = Point(0.0, 0.0, 0.0)) {
            Shape(geometry = Prism(Point.ORIGIN, 2.0, 2.0, 1.0), color = IsoColor.BLUE)
            Shape(geometry = Prism(Point(1.0, 1.0, 0.5), 2.0, 2.0, 1.0), color = IsoColor.RED)
        }

        // This group skips depth sorting for performance
        // Safe because these shapes are spread out and don't overlap
        Group(
            position = Point(5.0, 0.0, 0.0),
            renderOptions = RenderOptions.NoDepthSorting
        ) {
            ForEach((0 until 5).toList()) { i ->
                Shape(
                    geometry = Prism(Point(i * 2.0, 0.0, 0.0)),
                    color = IsoColor(0, 200, 100)
                )
            }
        }
    }
}
```

Available presets: `RenderOptions.Default` (all optimizations), `RenderOptions.NoDepthSorting` (skip sort), `RenderOptions.NoCulling` (show all faces). You can also construct custom options:

```kotlin
val custom = RenderOptions(
    enableDepthSorting = true,
    enableBackfaceCulling = false, // show back faces
    enableBoundsChecking = true,
    enableBroadPhaseSort = true
)
```

## Lifecycle Hooks: onBeforeDraw / onAfterDraw

Use `AdvancedSceneConfig` to run custom drawing code before or after the scene renders. These callbacks execute inside the `DrawScope`, so you can use the full Compose Canvas drawing API.

```kotlin
@Composable
fun LifecycleHooksDemo() {
    IsometricScene(
        modifier = Modifier.fillMaxSize(),
        config = AdvancedSceneConfig(
            onBeforeDraw = {
                // Draw a gradient background before the scene
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF87CEEB),  // sky blue
                            Color(0xFFE8E8E8)   // light gray
                        )
                    )
                )
            },
            onAfterDraw = {
                // Draw a debug border after the scene
                drawRect(
                    color = Color.Red,
                    style = Stroke(width = 2f)
                )
            }
        )
    ) {
        Shape(
            geometry = Prism(Point.ORIGIN, 3.0, 3.0, 2.0),
            color = IsoColor(33, 150, 243)
        )
    }
}
```

## Direct Engine Usage Outside Compose

For server-side rendering, screenshot generation, or non-Compose UI frameworks, use `IsometricEngine` directly without the Compose runtime.

```kotlin
fun generateScene(): PreparedScene {
    val engine = IsometricEngine(scale = 80.0)

    // Build scene
    engine.add(Prism(Point.ORIGIN, 3.0, 3.0, 2.0), IsoColor(33, 150, 243))
    engine.add(Pyramid(Point(0.0, 0.0, 2.0), 3.0, 3.0, 1.5), IsoColor.RED)
    engine.add(
        Cylinder(Point(4.0, 0.0, 0.0), 0.5, 3.0),
        IsoColor(76, 175, 80)
    )

    // Project to 2D screen space
    val scene = engine.projectScene(
        width = 1024,
        height = 768,
        renderOptions = RenderOptions.Default
    )

    // Each command has screen-space polygon points and a lit color
    scene.commands.forEach { cmd ->
        // cmd.points -> List<Point2D> for polygon drawing
        // cmd.color -> IsoColor after lighting
    }

    return scene
}
```

The `PreparedScene` contains a flat list of `RenderCommand` entries sorted back-to-front, ready for drawing on any 2D canvas that supports filled polygons.
