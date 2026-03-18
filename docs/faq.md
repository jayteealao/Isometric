---
title: FAQ
description: Frequently asked questions and troubleshooting
---

### "Unresolved reference: Path"

Kotlin's standard library has `kotlin.io.path.Path`. Use an import alias:

```kotlin
import io.fabianterhorst.isometric.Path as IsoPath
```

### "Color is ambiguous"

Compose has `androidx.compose.ui.graphics.Color`. The library uses `IsoColor`:

```kotlin
import io.fabianterhorst.isometric.IsoColor
// Use IsoColor(r, g, b) everywhere in isometric scenes
```

Convert between them: `composeColor.toIsoColor()` / `isoColor.toComposeColor()`

### "Shapes render in wrong order"

Isometric uses depth sorting: `depth = x + y - 2z`. Shapes with higher depth render on top. If shapes overlap incorrectly:

- Check that `RenderOptions.enableDepthSorting` is true (default)
- Adjust positions so overlapping shapes have clearly different depths
- Known limitation: the Knot shape has depth-sorting issues with its internal faces

### "Scene is blank / nothing renders"

- Ensure `IsometricScene` is in a Compose context
- Check shape positions — shapes at very large coordinates may be off-screen
- Verify colors have non-zero alpha (default is 255)
- Make sure the `IsometricScene` has non-zero size (use `Modifier.fillMaxSize()`)

### "Is this published to Maven Central?"

Not yet. Use a composite build for now — see the Installation guide.

### "Can I use this without Compose?"

Yes. The `isometric-core` module is pure Kotlin/JVM with no Android dependency. Use `IsometricEngine` directly to project shapes to 2D coordinates, then render with your own backend.

### "How do I use the old View API?"

The `isometric-android-view` module provides `IsometricView` for the traditional Android View system. See the [Migration guide](migration/view-to-compose.md) for details on moving to Compose.
