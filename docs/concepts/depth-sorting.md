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

Each face's depth is the mean depth of its vertices. At the default 30° projection angle this reduces to:

```
depth = x + y - 2 * z
```

**Higher depth means farther from the viewer**, so higher-depth faces are painted first and end up behind:

- **Higher x or y** *increases* depth → farther away, drawn behind.
- **Higher z** *decreases* depth (the `-2z` term) → closer to the viewer, drawn on top.

The general formula is `x + y - z / sin(angle)`. At the default 30°, `sin(30°) = 0.5`, so `z / sin(30°) = 2z` and the formula reduces exactly to `x+y-2z`. The engine threads its configured projection angle through the sort, so a scene rendered at a non-30° angle still orders correctly. See [Coordinate System](../getting-started/coordinate-system.md) for the full projection math.

## The Sorting Pipeline

Sorting happens in four stages, each progressively more precise:

### Stage 1: Compute Per-Face Depth

Every face gets a depth value from the formula above — the mean of its vertices' depths, computed with the engine's configured projection angle. Faces are pre-sorted back-to-front by this scalar, which also fixes the draw order for any pair the later stages cannot decide.

### Stage 2: Broad Phase (Spatial Grid)

Faces are bucketed into a 2D spatial grid based on their screen-space bounding boxes. Only faces that share a grid cell are candidates for overlap. For typical scenes — shapes spread out and roughly cell-sized — this reduces the pairwise comparisons from O(n^2) to approximately O(n); heavily clustered scenes, or single shapes spanning many cells, see less benefit because the per-cell candidate lists grow.

The grid cell size is controlled by `broadPhaseCellSize` (default: `100.0` pixels).

### Stage 3: Narrow Phase (Strict Screen-Overlap Gate)

For each pair of candidate faces within the same cell, `IntersectionUtils.hasInteriorIntersection()` performs a strict screen-overlap test: AABB rejection, then a strict edge-crossing test (cross-product sign checks with a small epsilon, rejecting mere boundary grazes), then a strict-inside fallback. Pairs that share only a boundary edge or vertex in screen space are correctly rejected — they cannot paint over each other regardless of depth, so adding a draw-order edge for them would produce spurious dependencies that can push unrelated faces to extreme positions in the final order. The lenient `hasIntersection()` variant (which treats any boundary contact as overlap) remains available for callers that want any-contact semantics.

### Stage 4: Topological Sort

The remaining interior-overlapping pairs are analyzed by `Path.closerThan` (a reduced Newell cascade — iso-depth extent minimax followed by plane-side tests) to determine which face is "in front of" the other. These relationships form a directed acyclic graph (DAG). A topological sort of this DAG produces the final draw order.

When back-face culling is enabled, a deterministic pre-pass first orders pairs that share a real 3D edge between a horizontal face and a vertical wall — walls below a horizontal face draw before it, walls above draw after. This keeps stacked prisms and tile grids stable at their shared edges, where `closerThan` alone would report "ambiguous."

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

The cell size trades cell count against candidates per cell — a knob worth turning only when shape density is unusual in either direction. For the tuning table and configuration examples, see [Performance — Tuning broadPhaseCellSize](../guides/performance.md#tuning-broadphasecellsize).

## Known Limitations

The sorting algorithm handles the vast majority of scenes correctly, but there are inherent limitations of isometric depth sorting:

- **Intersecting faces** -- when two faces physically intersect in 3D space, there is no correct draw order. Neither face is entirely in front of the other. This can produce visual artifacts (flickering or incorrect overlap). The Knot example shape self-intersects and demonstrates this.
- **Cyclic overlap** -- three or more faces can form a cycle where A is in front of B, B is in front of C, and C is in front of A. The topological sort breaks cycles arbitrarily.
- **Very large shapes** -- a single shape spanning many grid cells degrades broad-phase efficiency because it becomes a candidate in every cell it touches.

For intersecting geometry, the workaround is to split the shapes so they no longer intersect, or to accept minor artifacts.

## Disabling Depth Sort

Two flags control the pipeline, and they disable different halves of it. `enableDepthSorting = false` switches sorting off entirely, so faces paint in declaration order — the right choice when the scene *is* its own correct ordering (flat overlays, hand-ordered scenes) or when sorting cost matters more than correctness. `enableBroadPhaseSort = false` keeps the sort but removes the spatial-grid shortcut, falling back to pairwise testing — a debugging lever for isolating whether an artifact comes from the bucketing or from the sort logic itself.

For configuration snippets and when-to-use guidance, see
[Performance — Disabling depth sorting or the broad phase](../guides/performance.md#disabling-depth-sorting-or-the-broad-phase).

> **Tip**
>
If you see depth sorting artifacts, first check whether the shapes intersect in 3D space. Intersecting faces are the most common cause of visual glitches and cannot be fixed by tuning parameters -- the geometry must be split.
