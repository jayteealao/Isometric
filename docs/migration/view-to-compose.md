---
title: "View \u2192 Compose Migration"
description: Migrate from the Android View API to Jetpack Compose
sidebar:
  order: 1
---

> **Note**
>
The View API used the `io.fabianterhorst.isometric` package. The Compose API uses `io.github.jayteealao.isometric`. All imports change accordingly.

### Module Dependencies

Before:

```groovy
implementation 'io.fabianterhorst:isometric:0.1.0'
```

After:

```kotlin
implementation("io.github.jayteealao:isometric-compose:<version>")
```

### Layout

Before (XML):

```xml
<io.fabianterhorst.isometric.IsometricView
    android:id="@+id/isometric_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

After (Compose):

```kotlin
IsometricScene(modifier = Modifier.fillMaxSize()) {
    Shape(geometry = Prism(Point.ORIGIN), color = IsoColor.BLUE)
}
```

### Adding Shapes

Before:

```kotlin
isometricView.add(Prism(Point(0.0, 0.0, 0.0)), Color(33, 150, 243))
```

After:

```kotlin
Shape(
    geometry = Prism(Point(0.0, 0.0, 0.0)),
    color = IsoColor(33, 150, 243)
)
```

### Color Class

`Color` was renamed to `IsoColor` to avoid collision with Compose's Color class.

### Click Handling

Before:

```kotlin
isometricView.setClickListener { renderCommand ->
    // handle click
}
```

After:

```kotlin
IsometricScene(
    config = SceneConfig(
        gestures = GestureConfig(
            onTap = { event -> /* event.node is the tapped shape */ }
        )
    )
) { ... }
```

### Key Differences Summary

| Aspect | View API | Compose API |
|---|---|---|
| Entry point | IsometricView (XML) | IsometricScene (Composable) |
| Adding shapes | view.add(shape, color) | Shape(geometry, color) composable |
| Transforms | shape.translate/rotate/scale | Shape params + Group hierarchy |
| State updates | Imperative: view.clear() + re-add | Declarative: recomposition |
| Click handling | setClickListener | GestureConfig.onTap |
| Color type | Color | IsoColor |
| Animation | Manual invalidation | withFrameNanos + recomposition |

### Backward Compatibility

The `isometric-android-view` module still provides the View API for projects that aren't ready to migrate.
