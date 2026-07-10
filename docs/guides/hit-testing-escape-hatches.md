---
title: Hit-Testing Escape Hatches
description: findItemAt, HitOrder, onHitTestReady, screenToTile — low-level hit-test control
sidebar:
  order: 10
---

Reach for these low-level APIs when per-node `onClick` is not enough: when you need the item
*beneath* the top one, when you must resolve a hit *outside* a gesture, or when a tap lands on
elevated terrain. Every snippet below mirrors a compiled sample in
`app/src/main/kotlin/io/github/jayteealao/isometric/sample/InteractionSamplesActivity.kt`.

## Selecting the Occluded Item (BACK_TO_FRONT)

The scene's default `onTap` resolves the **frontmost** hit (`HitOrder.FRONT_TO_BACK`). To reach the
item occluded beneath it, capture the projected scene via `AdvancedSceneConfig.onPreparedSceneReady`
and query the engine again with `HitOrder.BACK_TO_FRONT`.

```kotlin
// Source: InteractionSamplesActivity.kt — OccludedPickSample
val engine = remember { IsometricEngine() }
var prepared by remember { mutableStateOf<PreparedScene?>(null) }

IsometricScene(
    modifier = Modifier.weight(1f).fillMaxWidth(),
    config = AdvancedSceneConfig(
        engine = engine,
        onPreparedSceneReady = { prepared = it },
        gestures = GestureConfig(
            onTap = { event ->
                topHit = event.node?.nodeId ?: "(miss)"
                bottomHit = prepared?.let { scene ->
                    engine.findItemAt(scene, event.x, event.y, HitOrder.BACK_TO_FRONT, 8.0)
                        ?.ownerNodeId ?: "(miss)"
                } ?: "(scene not ready)"
            }
        )
    )
) {
    Shape(geometry = Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 2.0), color = IsoColor.BLUE, nodeId = "front-tile")
    Shape(geometry = Prism(Point(0.5, 0.5, 0.0), 1.0, 1.0, 1.0), color = IsoColor.RED, nodeId = "back-tile")
}
```

`onHitTestReady` (below) always resolves `FRONT_TO_BACK`, so `BACK_TO_FRONT` is only reachable by
querying the engine directly, as shown here.

## Querying a Coordinate Outside a Gesture (onHitTestReady)

`AdvancedSceneConfig.onHitTestReady` hands you a `(x, y) -> IsometricNode?` function. Keep it and
answer "what is at this coordinate?" at any time — no pointer event required.

```kotlin
// Source: InteractionSamplesActivity.kt — ImperativeHitQuerySample
var hitFn by remember { mutableStateOf<((Double, Double) -> IsometricNode?)?>(null) }

IsometricScene(
    modifier = Modifier.weight(1f).fillMaxWidth(),
    config = AdvancedSceneConfig(
        onHitTestReady = { fn -> hitFn = fn }
    )
) {
    Shape(geometry = Prism(Point(1.0, 1.0, 0.1), 1.5, 1.5, 1.5), color = IsoColor.BLUE, nodeId = "tile-blue")
    Shape(geometry = Prism(Point(4.0, 1.0, 0.1), 1.5, 1.5, 2.5), color = IsoColor.ORANGE, nodeId = "tile-orange")
}

// Later, from a button or any non-gesture code path:
val node = hitFn?.invoke(x, y)   // the IsometricNode at (x, y), or null
```

## Mapping a Tap on Elevated Terrain (screenToTile)

`screenToTile` inverts the projection to a tile coordinate. Its `elevation` parameter is the z-plane
the inverse ray intersects: the default `0.0` answers the ground plane, so a tap on a *raised* tile
maps to a different cell unless you pass the tile's surface height.

```kotlin
// Source: InteractionSamplesActivity.kt — ElevatedTileSample
val engine = remember { IsometricEngine() }
var canvasW by remember { mutableStateOf(0) }
var canvasH by remember { mutableStateOf(0) }

IsometricScene(
    modifier = Modifier.weight(1f).fillMaxWidth(),
    config = AdvancedSceneConfig(
        engine = engine,
        onFlagsReady = { flags ->
            canvasW = flags.canvasWidth
            canvasH = flags.canvasHeight
        },
        gestures = GestureConfig(
            onTap = { event ->
                if (canvasW > 0 && canvasH > 0) {
                    // The tile under the raised surface vs. the same tap on the ground plane.
                    tileAtSurface = engine.screenToTile(
                        event.x, event.y, canvasW, canvasH, elevation = 2.0
                    ).toString()
                    tileAtGround = engine.screenToTile(
                        event.x, event.y, canvasW, canvasH, elevation = 0.0
                    ).toString()
                }
            }
        )
    )
) {
    Shape(geometry = Prism(Point(2.0, 2.0, 2.0), 1.0, 1.0, 0.5), color = IsoColor.ORANGE, nodeId = "elevated-tile")
}
```

For a flat z = 0 grid, prefer `TileGrid`'s `onTileClick` — it does the screen-to-tile conversion for
you. Drop to `screenToTile` only when elevation matters. See
[Tile Grid](tile-grid.md) for the high-level routing.

> **Note**
>
`TileGrid`'s built-in `onTileClick` works with the standard `IsometricEngine` projector. With a
custom `SceneProjector` (via `AdvancedSceneConfig.engine`), tap dispatch is skipped because tile
coordinate mapping depends on the engine's projection geometry and cannot be generalized across
arbitrary projectors.

If you supply a custom `SceneProjector`, you must also **override `projectionVersion`** (it is
`abstract`) and increment it whenever your projection parameters change. Failing to do so leaves
the scene cache stale and changes are not reflected on screen.

## Adjusting the Touch Radius

Scene hit-testing is **forgiving**: an internal 8 px radius means a tap *near* a shape still
registers. The low-level `findItemAt` overload lets you choose a different radius for power-user
selection logic. Its signature, on the `SceneProjector` (default engine: `IsometricEngine`):

```kotlin
// Source: isometric-core/src/main/kotlin/io/github/jayteealao/isometric/SceneProjector.kt
fun findItemAt(
    preparedScene: PreparedScene,
    x: Double,
    y: Double,
    order: HitOrder = HitOrder.FRONT_TO_BACK,
    touchRadius: Double = 0.0
): RenderCommand?
```

Pass an explicit `touchRadius` to widen or tighten the hit area — the occluded-pick recipe above
passes `8.0` to match the scene's own forgiving radius:

```kotlin
engine.findItemAt(prepared, x, y, HitOrder.BACK_TO_FRONT, touchRadius = 12.0)
```

> **Caution**
>
The public touch radius stays internal — `findItemAt(touchRadius = …)` is the escape hatch for
custom selection logic, not routine hit-testing. For ordinary taps, per-node `onClick` and the
scene's built-in radius are enough.

---

See [Scene Configuration](../reference/scene-config.md) for the full `AdvancedSceneConfig` table and
[Per-Node Interactions](interactions.md) for the higher-level `onClick` path.
