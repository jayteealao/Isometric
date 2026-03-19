---
title: Performance
description: Optimization strategies, RenderOptions tuning, and benchmarking
sidebar:
  order: 7
---

Isometric ships with three built-in performance systems that work automatically. Most scenes need no tuning at all. This page explains what the systems do, how to configure them, and when to reach for advanced options.

## Performance Model Overview

Three systems keep rendering fast without manual intervention:

1. **Per-node dirty tracking** -- only changed subtrees trigger recomposition. If a single `Shape` changes color, the rest of the tree is untouched.
2. **PreparedScene caching** -- the full projection (3D to 2D) is cached as a `PreparedScene`. When the node tree is clean, projection is skipped entirely and the cached commands are drawn directly.
3. **Spatial indexing** -- hit testing uses a grid-based spatial index, giving O(1) lookup regardless of scene size.

These systems compose: a scene with 200 shapes where one shape animates will dirty-track that single node, re-project only if needed, and still provide instant hit testing.

## RenderOptions Tuning

`RenderOptions` controls the rendering pipeline. Pass it inside `SceneConfig` or `AdvancedSceneConfig`:

```kotlin
IsometricScene(
    config = SceneConfig(
        renderOptions = RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = true,
            enableBoundsChecking = true,
            enableBroadPhaseSort = true,
            broadPhaseCellSize = 100.0
        )
    )
) {
    Shape(geometry = Prism(Point.ORIGIN))
}
```

| Flag | Default | Impact | When to change |
|------|---------|--------|----------------|
| `enableDepthSorting` | `true` | Sorts faces by depth for correct overlap | Disable for flat 2D overlays where you control draw order manually |
| `enableBackfaceCulling` | `true` | Skips faces pointing away from the camera | Disable if you need to see inside hollow shapes |
| `enableBoundsChecking` | `true` | Skips shapes entirely outside the viewport | Disable only for debugging -- it is always beneficial |
| `enableBroadPhaseSort` | `true` | Uses spatial grid to reduce sort comparisons | Disable to fall back to O(n^2) pairwise sort for debugging |
| `broadPhaseCellSize` | `100.0` | Grid cell size for the broad-phase pass | Decrease for dense scenes with many overlapping shapes |

Three presets cover common scenarios:

- **`RenderOptions.Default`** -- all optimizations enabled, suitable for most scenes
- **`RenderOptions.NoDepthSorting`** -- insertion-order rendering, useful for flat overlays
- **`RenderOptions.NoCulling`** -- backface culling disabled, useful for transparent or hollow geometry

## Native Canvas

Setting `useNativeCanvas = true` bypasses Compose `DrawScope` and renders directly to `android.graphics.Canvas` with reused `Paint` objects. This eliminates per-frame allocations and produces roughly a 2x speedup on Android.

```kotlin
IsometricScene(
    config = SceneConfig(useNativeCanvas = true)
) {
    Shape(geometry = Prism(Point.ORIGIN))
}
```

This flag is Android-only. On other platforms it is silently ignored, so you can set it unconditionally in shared code.

## Path Caching

When `enablePathCaching = true`, the renderer converts projected `RenderCommand.points` into `androidx.compose.ui.graphics.Path` objects once and caches them between frames. This avoids per-frame `Path` allocation and reduces GC pressure in scenes with many faces.

```kotlin
IsometricScene(
    config = AdvancedSceneConfig(
        enablePathCaching = true
    )
) {
    // Paths are allocated once and reused until the scene changes
    ForEach((0 until 10).toList()) { i ->
        Shape(geometry = Prism(Point(i.toDouble(), 0.0, 0.0)))
    }
}
```

Path caching is most effective in static or mostly-static scenes where geometry does not change every frame.

## Spatial Index Tuning

The spatial index is enabled by default (`enableSpatialIndex = true`) and provides O(1) hit testing for tap and drag gestures. If your scene does not use gestures, the index still builds but has no cost at query time.

`spatialIndexCellSize` controls the grid granularity. The default works well for typical scenes. If you have very small, densely packed shapes, a smaller cell size reduces the number of candidates checked per query:

```kotlin
IsometricScene(
    config = AdvancedSceneConfig(
        enableSpatialIndex = true,
        spatialIndexCellSize = 50.0  // finer grid for dense scenes
    )
) {
    // hundreds of small shapes
}
```

## Cache Control

Two escape hatches let you override the caching system:

- **`forceRebuild = true`** -- bypasses the PreparedScene cache and re-projects every frame. Useful for debugging to confirm a visual issue is not caused by stale cache.
- **`frameVersion`** -- an external cache key. Increment it to force re-projection without setting `forceRebuild`. This is useful when you change something the dirty-tracking system cannot detect, such as a custom engine parameter.

```kotlin
var version by remember { mutableLongStateOf(0L) }

IsometricScene(
    config = AdvancedSceneConfig(
        frameVersion = version
    )
) {
    Shape(geometry = Prism(Point.ORIGIN))
}

// Later, when you need to force a re-render:
version++
```

## Anti-patterns

Avoid these common mistakes that defeat the optimization systems:

- **`delay(16)` loops instead of `withFrameNanos`** -- `delay` is not synchronized to the display refresh rate. Use `withFrameNanos` for smooth, vsync-aligned animation.
- **Recreating geometry every frame** -- constructing `Prism(...)` or `Cylinder(...)` inside a composable that recomposes on every frame wastes allocation. Use `remember` for stable geometry and only vary the properties that change (position, rotation, color).
- **Key-less `ForEach` with changing lists** -- without stable keys, the entire list re-renders when any item changes. Provide a key function so Compose can diff efficiently.

## When to Optimize

| Scenario | Recommendation |
|----------|---------------|
| Simple scenes (fewer than 20 shapes) | Defaults work. No tuning needed. |
| Large static scenes (100+ shapes) | Enable `useNativeCanvas` on Android. Consider `enablePathCaching`. |
| Animated scenes | Profile before changing defaults. Use `withFrameNanos` and `remember` geometry. |
| Android-only targets | Set `useNativeCanvas = true` for a ~2x rendering speedup. |
| Dense interactive scenes | Tune `spatialIndexCellSize` and `broadPhaseCellSize` for your shape density. |
| Debugging visual glitches | Set `forceRebuild = true` temporarily to rule out caching issues. |

> **Tip**
>
Start with defaults. The built-in dirty tracking and caching handle most workloads without configuration. Only tune when profiling shows a specific bottleneck.
