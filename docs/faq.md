---
title: FAQ
description: Frequently asked questions and troubleshooting
---

## "Unresolved reference: Path"

Kotlin's standard library has `kotlin.io.path.Path`. Use an import alias:

```kotlin
import io.github.jayteealao.isometric.Path as IsoPath
```

## "Color is ambiguous"

Compose has `androidx.compose.ui.graphics.Color`. The library uses `IsoColor`:

```kotlin
import io.github.jayteealao.isometric.IsoColor
// Use IsoColor(r, g, b) everywhere in isometric scenes
```

Convert between them: `composeColor.toIsoColor()` / `isoColor.toComposeColor()`

## "Shapes render in wrong order"

Isometric uses depth sorting: `depth = x + y - 2z`. Higher depth means *farther* from the viewer, so higher-depth faces render **behind**; raising a shape's z (or lowering x+y) brings it toward the front. If shapes overlap incorrectly:

- Check that `RenderOptions.enableDepthSorting` is true (default)
- Adjust positions so overlapping shapes have clearly different depths
- Known limitation: the Knot shape has depth-sorting issues with its internal faces

## "Scene is blank / nothing renders"

- Ensure `IsometricScene` is in a Compose context
- Check shape positions — shapes at very large coordinates may be off-screen
- Verify colors have non-zero alpha (default is 255)
- Make sure the `IsometricScene` has non-zero size (use `Modifier.fillMaxSize()`)

## "How do I handle a tap on a specific shape?"

Attach `onClick` directly to the shape — no `GestureConfig` needed:

```kotlin
Shape(geometry = Prism(Point.ORIGIN), onClick = { /* tapped */ })
```

Use a scene-level `GestureConfig.onTap` only when you need background taps or raw screen
coordinates. When both are set, `onTap` runs first, then the node's `onClick`. See
[Per-Node Interactions](guides/interactions.md).

## "How do I make a shape semi-transparent?"

Set the per-node `alpha` (in the `0f..1f` range):

```kotlin
Shape(geometry = Prism(Point.ORIGIN), color = IsoColor.BLUE, alpha = 0.5f)
```

## "How do I identify a shape from a test?"

Give it a `testTag` (a label that does not affect rendering) or a stable `nodeId`:

```kotlin
Shape(geometry = Prism(Point.ORIGIN), testTag = "hero", nodeId = "hero")
```

## "Is this published to Maven Central?"

Yes. All three modules are published to [Maven Central](https://central.sonatype.com/artifact/io.github.jayteealao/isometric-compose). See the [Installation guide](getting-started/installation.md) for coordinates and version catalog setup.

## "Can I use this without Compose?"

Yes. The `isometric-core` module is pure Kotlin/JVM with no Android dependency. Use `IsometricEngine` directly to project shapes to 2D coordinates, then render with your own backend.

## "How do I use the old View API?"

The `isometric-android-view` module provides `IsometricView` for the traditional Android View system. See the [Migration guide](migration/view-to-compose.md) for details on moving to Compose.
