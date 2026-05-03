---
title: Per-Node Interactions
description: Per-node alpha, click handlers, test tags, and stable identifiers
sidebar:
  order: 6
---

`Shape`, `Path`, `Batch`, and `CustomNode` each accept five optional properties for
attaching behavior and identity to individual nodes inside a scene:

- `alpha` &mdash; opacity multiplier
- `onClick` &mdash; tap handler
- `onLongClick` &mdash; long-press handler
- `testTag` &mdash; tag for tests and diagnostics
- `nodeId` &mdash; caller-supplied stable identifier

These props live on the node itself, not on a separate gesture configuration. Using
them does **not** require a `GestureConfig` on the enclosing `IsometricScene` &mdash;
the pointer-input modifier is installed automatically as soon as any node opts in.

## Alpha

`alpha` is a `Float` opacity multiplier in the `0f..1f` range, applied to every face
of the node at render time:

```kotlin
IsometricScene(modifier = Modifier.fillMaxSize()) {
    // Solid prism
    Shape(
        geometry = Prism(Point.ORIGIN),
        color = IsoColor.BLUE
    )

    // Half-transparent prism stacked on top
    Shape(
        geometry = Prism(Point(0.0, 0.0, 1.0)),
        color = IsoColor.BLUE,
        alpha = 0.5f
    )
}
```

Alpha is multiplied against the existing alpha channel of `color`, so an `IsoColor`
that already has an alpha below 255 stays proportionally translucent. Values outside
`0f..1f` throw `IllegalArgumentException` at the property setter, surfacing the bug
at assignment time rather than at render time.

## onClick and onLongClick

`onClick` and `onLongClick` fire when this specific node is hit-tested under a tap or
long-press gesture. They are independent of any scene-level
`GestureConfig.onTap` &mdash; either or both can be wired without conflict.

```kotlin
@Composable
fun TapToColorChange() {
    var color by remember { mutableStateOf(IsoColor.BLUE) }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Shape(
            geometry = Prism(Point.ORIGIN),
            color = color,
            onClick = {
                color = IsoColor(
                    (0..255).random(),
                    (0..255).random(),
                    (0..255).random()
                )
            }
        )
    }
}
```

Long-press fires once after the platform long-press timeout. The press is cancelled
if the pointer moves beyond the gesture system's threshold or the press is released
early.

```kotlin
@Composable
fun LockableTile() {
    var locked by remember { mutableStateOf(false) }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Shape(
            geometry = Prism(Point.ORIGIN),
            color = if (locked) IsoColor.RED else IsoColor.GREEN,
            alpha = if (locked) 0.4f else 1f,
            onLongClick = { locked = !locked }
        )
    }
}
```

### Combining with scene-level GestureConfig

Per-node handlers and `GestureConfig.onTap` coexist. When a tap lands on a node with
`onClick`, both fire &mdash; the per-node handler first, then the scene handler with
the same hit-tested node attached to the `TapEvent`.

```kotlin
IsometricScene(
    modifier = Modifier.fillMaxSize(),
    config = SceneConfig(
        gestures = GestureConfig(
            onTap = { event ->
                println("Scene-level tap on ${event.node?.nodeId}")
            }
        )
    )
) {
    Shape(
        geometry = Prism(Point.ORIGIN),
        onClick = { println("Per-node tap fired first") }
    )
}
```

Use one or the other for clarity unless you genuinely need both layers.

### Per-node handlers on Batch

`Batch` is one node, not many. A batched group of shapes shares a single
`onClick`/`onLongClick`; tapping any shape in the batch fires the same handler. If
you need per-shape handlers, render the shapes individually rather than batched.

## testTag

`testTag` is an arbitrary string for identifying nodes from tests and diagnostics. It
does not affect rendering, hit testing, or any other runtime behavior &mdash; it
exists purely as a label your tests can grep against:

```kotlin
Shape(
    geometry = Prism(Point.ORIGIN),
    testTag = "factory-prism"
)
```

In a Compose UI test, find a tagged scene primitive via the standard `onNodeWithTag`
finder applied to your wrapping testable composable, or assert against the
`RenderCommand` list returned from `IsometricEngine.projectScene()` and filter by
`ownerNodeId` of the tagged node.

## nodeId

By default every node receives an auto-generated identifier of the form `node_N`,
unique across the process lifetime. `nodeId` lets you supply your own stable id
instead:

```kotlin
Shape(
    geometry = Prism(Point.ORIGIN),
    nodeId = "headquarters"
)
```

Caller-supplied ids must be **non-blank** and **unique within a scene**. Duplicates
are detected at hit-test resolution and throw `IllegalStateException` with a message
naming the colliding id. The auto-generated fallback applies whenever `nodeId` is
omitted or set to `null`, so mixing tagged and untagged nodes in the same scene is
safe.

Use a stable `nodeId` whenever you want render commands or hit-test results to
remain identifiable across recompositions &mdash; for example, when correlating
gesture events back to domain objects:

```kotlin
data class Building(val id: String, val position: Point, val color: IsoColor)

val buildings = remember {
    listOf(
        Building("hq", Point(0.0, 0.0, 0.0), IsoColor.BLUE),
        Building("factory", Point(2.0, 0.0, 0.0), IsoColor.RED)
    )
}

IsometricScene(
    modifier = Modifier.fillMaxSize(),
    config = SceneConfig(
        gestures = GestureConfig(
            onTap = { event ->
                val tappedBuilding = buildings.firstOrNull { it.id == event.node?.nodeId }
                tappedBuilding?.let { /* ... */ }
            }
        )
    )
) {
    ForEach(items = buildings, key = { it.id }) { building ->
        Shape(
            geometry = Prism(building.position),
            color = building.color,
            nodeId = building.id
        )
    }
}
```

## Putting it all together

A node can use any combination of these props:

```kotlin
Shape(
    geometry = Prism(Point.ORIGIN),
    color = IsoColor.BLUE,
    alpha = if (locked) 0.4f else 1f,
    onClick = { selected = true },
    onLongClick = { locked = !locked },
    testTag = "building-hq",
    nodeId = "hq"
)
```

See the [Composables Reference &mdash; Per-Node Interaction Props](../reference/composables.md#per-node-interaction-props)
section for the parameter tables on each composable.
