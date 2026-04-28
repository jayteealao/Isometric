---
title: Depth Sorting
description: How isometric depth ordering works and how to tune it
sidebar:
  order: 2
---

Isometric projection has no z-buffer. Unlike perspective 3D rendering where the GPU tracks depth per pixel, an isometric renderer must explicitly decide the draw order of every face. This page explains how the library solves that problem and how to tune it.

## Why Depth Sorting Matters

Without sorting, faces are drawn in insertion order -- the order you declare `Shape` composables in your code. This is almost always wrong. A shape at the front of the scene would be drawn behind a shape at the back if it appears first in the tree.

The library sorts automatically by default (`enableDepthSorting = true`), so shapes overlap correctly without manual ordering.

## The Depth Formula

Each face's depth is computed from the mean position of its vertices:

```
depth = x + y - 2 * z
```

- **Higher x or y** pushes the face closer to the viewer (drawn on top).
- **Higher z** pushes the face further from the viewer (drawn behind).

This formula follows directly from the isometric projection geometry. A shape at ground level in the foreground (high x, high y, low z) has a large depth value and is drawn last, appearing on top. See [Coordinate System](../getting-started/coordinate-system.md) for the full projection math.

## The Sorting Pipeline

Sorting happens in four stages, each progressively more precise:

### Stage 1: Compute Per-Face Depth

Every face in the scene gets a depth value from the formula above, calculated from the mean of its vertex positions. This gives a single scalar per face for initial ordering.

### Stage 2: Broad Phase (Spatial Grid)

Faces are bucketed into a 2D spatial grid based on their screen-space bounding boxes. Only faces that share a grid cell are candidates for overlap. This reduces the number of pairwise comparisons from O(n^2) to roughly O(n).

The grid cell size is controlled by `broadPhaseCellSize` (default: `100.0` pixels).

### Stage 3: Narrow Phase (Strict Screen-Overlap Gate)

For each pair of candidate faces within the same cell, `IntersectionUtils.hasInteriorIntersection()` performs a strict screen-overlap test: AABB rejection, then a SAT edge-crossing check, then a strict-inside fallback. Pairs that share only a boundary edge or vertex in screen space are correctly rejected — they cannot paint over each other regardless of depth, so adding a draw-order edge for them would produce spurious dependencies that can push unrelated faces to extreme positions in the final order. The lenient `hasIntersection()` variant (which treats any boundary contact as overlap) remains available for callers that want any-contact semantics.

### Stage 4: Topological Sort

The remaining interior-overlapping pairs are analyzed by `Path.closerThan` (a reduced Newell cascade — iso-depth extent minimax followed by plane-side tests) to determine which face is "in front of" the other. These relationships form a directed acyclic graph (DAG). A topological sort of this DAG produces the final draw order.

```
All faces
  |
  v  [Broad phase: bucket into grid cells]
Candidate pairs
  |
  v  [Narrow phase: strict interior-overlap gate]
Interior-overlapping pairs
  |
  v  [Build DAG via Newell-cascade comparator, topological sort]
Final draw order
```

## Tuning broadPhaseCellSize

The `broadPhaseCellSize` parameter controls the grid granularity of the broad-phase pass:

| Value | Effect |
|-------|--------|
| Smaller (e.g., 50.0) | Fewer candidates per cell, but more cells to iterate. Better for dense scenes with many small shapes. |
| Default (100.0) | Good general-purpose value for typical scenes. |
| Larger (e.g., 200.0) | Fewer cells, but more candidates per cell. Reduces overhead for sparse scenes with large shapes. |

```kotlin
IsometricScene(
    config = SceneConfig(
        renderOptions = RenderOptions(
            broadPhaseCellSize = 50.0  // finer grid for dense scenes
        )
    )
) {
    // Hundreds of small shapes
    ForEach((0 until 20).toList()) { i ->
        ForEach((0 until 20).toList()) { j ->
            Shape(geometry = Prism(
                Point(i * 0.5, j * 0.5, 0.0), 0.4, 0.4, 0.4
            ))
        }
    }
}
```

## Known Limitations

The sorting algorithm handles the vast majority of scenes correctly, but there are inherent limitations of isometric depth sorting:

- **Intersecting faces** -- when two faces physically intersect in 3D space, there is no correct draw order. Neither face is entirely in front of the other. This can produce visual artifacts (flickering or incorrect overlap). The Knot example shape self-intersects and demonstrates this.
- **Cyclic overlap** -- three or more faces can form a cycle where A is in front of B, B is in front of C, and C is in front of A. The topological sort breaks cycles arbitrarily.
- **Very large shapes** -- a single shape spanning many grid cells degrades broad-phase efficiency because it becomes a candidate in every cell it touches.

For intersecting geometry, the workaround is to split the shapes so they no longer intersect, or to accept minor artifacts.

## Disabling Depth Sort

Two flags control the sorting pipeline. For full tuning guidance including when to change each flag, see the [Performance guide](../guides/performance.md).

### enableDepthSorting = false

Disables sorting entirely. Faces are drawn in insertion order (the order composables appear in the tree). This is useful for:

- Flat 2D overlays where you control the order manually
- Scenes where you know the insertion order is already correct
- Performance-critical paths where sorting cost is unacceptable

```kotlin
IsometricScene(
    config = SceneConfig(
        renderOptions = RenderOptions.NoDepthSorting
    )
) {
    // These render in declaration order: blue behind, then green on top
    Shape(geometry = Prism(Point(0.0, 0.0, 0.0)), color = IsoColor.BLUE)
    Shape(geometry = Prism(Point(1.0, 0.0, 0.0)), color = IsoColor.GREEN)
}
```

### enableBroadPhaseSort = false

Keeps depth sorting enabled but disables the spatial grid optimization. Falls back to O(n^2) pairwise intersection testing. This is a debugging tool -- if you see sorting artifacts, disabling the broad phase can help determine whether the issue is in the broad-phase bucketing or in the sort logic itself.

```kotlin
IsometricScene(
    config = SceneConfig(
        renderOptions = RenderOptions(
            enableDepthSorting = true,
            enableBroadPhaseSort = false  // O(n^2) fallback
        )
    )
) {
    Shape(geometry = Prism(Point.ORIGIN))
}
```

> **Tip**
>
If you see depth sorting artifacts, first check whether the shapes intersect in 3D space. Intersecting faces are the most common cause of visual glitches and cannot be fixed by tuning parameters -- the geometry must be split.
