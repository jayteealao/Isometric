# Spatial Index Implementation Review

**Date:** 2026-03-11
**Scope:** Multi-pass code review of the spatial index implementation across `IsometricRenderer`,
`IsometricEngine`, `IsometricNode`, `IntersectionUtils`, and `IsometricRendererTest`.
**Branch:** `perf/benchmark-harness`

## Files Reviewed

| File | Lines | Role |
|------|------:|------|
| `isometric-compose/.../IsometricRenderer.kt` | 657 | Spatial grid, build, query, hit-test orchestration |
| `isometric-compose/.../IsometricScene.kt` | 338 | Composable entry point, flag pass-through |
| `isometric-compose/.../IsometricNode.kt` | 259 | Node tree, command ID generation |
| `isometric-core/.../IsometricEngine.kt` | 384 | `findItemAt`, `buildConvexHull`, depth sorting |
| `isometric-core/.../IntersectionUtils.kt` | 152 | Point-in-polygon, point-close-to-polygon |
| `isometric-compose/.../IsometricRendererTest.kt` | 479 | Hit-test correctness, cache, boundary tests |

## Review Approach

Five passes:

1. **Correctness** — does the fast path produce the same result as the slow path in all cases?
2. **Performance** — build cost, query cost, allocations on the hot path
3. **Robustness** — edge cases, error handling, state consistency
4. **API design** — configuration surface, coupling, encapsulation
5. **Test coverage** — what is tested, what is missing

---

## Overall Assessment

The spatial index is well-implemented. The query already handles neighboring cells via
radius-expanded cell ranges, uses `LinkedHashSet` for dedup, and re-sorts candidates by scene order
before passing them to the engine. The core correctness argument is sound: the fast path can only
narrow the candidate set, never change the hit-test verdict for any candidate that survives filtering.

14 findings: 2 high, 5 medium, 7 low.

---

## Pass 1: Correctness

### HIGH — C1. `resolveNodeForCommand` can match the wrong node on prefix collision

**File:** `IsometricRenderer.kt:461–466`

```kotlin
private fun resolveNodeForCommand(commandId: String): IsometricNode? {
    return nodeIdMap.entries.find { (nodeId, _) ->
        commandId == nodeId ||
        (commandId.startsWith(nodeId) && commandId.length > nodeId.length && commandId[nodeId.length] == '_')
    }?.value
}
```

**Issue:** `nodeIdMap.entries.find` returns the **first** match in iteration order, which is
undefined for `mutableMapOf`. If two nodes have IDs where one is a valid prefix of the other followed
by `_`, the wrong node can be returned.

Example: Node A has `identityHashCode=1` giving nodeId `"node_1"`. Node B has
`identityHashCode=1234` giving nodeId `"node_1234"`. A command from Node B has ID
`"node_1234_567"`. The prefix check against `"node_1"` tests `commandId[6]` which is `'2'` (not
`'_'`), so this particular case is rejected.

However, the check relies on the fact that `identityHashCode` always produces numeric strings, so the
character after the prefix is always a digit, never `'_'`. This invariant is not documented or
enforced. If nodeId generation ever changes (e.g., to include a group path like `"group_a_node_1"`),
the prefix match would silently break.

More immediately: the `find` call iterates all entries for every command during
`buildSpatialIndex()`, giving O(n*m) total cost where n = commands and m = nodes. For a 200-node
scene with ~1000 commands, this is 200,000 iterations on every cache rebuild.

**Recommendation:**
1. Document the invariant that nodeId must end with digits (no trailing `_` + more chars).
2. Replace the linear scan with a purpose-built lookup: extract the nodeId prefix from the
   command ID by finding the longest matching nodeId. Or better, have `render()` return the
   mapping alongside the commands.

---

### MEDIUM — C2. `buildConvexHull` is not a convex hull — same approximation used in both paths

**File:** `IsometricEngine.kt:341–376`

**Issue:** The method finds the four extreme points (top, bottom, left, right) and adds any
co-extreme points. This produces at most 4–8 points. For isometric faces (typically quadrilaterals),
this is exact. But for shapes with many vertices or concavities, the result may not be convex.

Since `isPointInPoly` uses ray casting (which works on any simple polygon), this doesn't cause
incorrect results for convex or simple concave polygons. But `isPointCloseToPoly` checks distance to
the hull's edges, and a non-convex hull could miss edges that exist in the actual shape.

This is **not spatial-index-specific** — both fast and slow paths call `findItemAt` which uses
`buildConvexHull`. So it cannot cause a discrepancy between paths. But it can cause both paths to
miss a valid hit on a non-quadrilateral shape.

**Recommendation:** No action needed for the spatial-index branch. Flag as a known limitation in the
engine for shapes with more than 4 coplanar vertices.

---

### Correctness items verified as correct

The following were audited and found to be sound:

1. **Radius-expanded query prevents false negatives.** `SpatialGrid.query()` expands the search
   range by `radius` in all directions (`IsometricRenderer.kt:551–555`). Any command whose polygon
   edge is within `radius` of the tap point will be in a cell that the expanded range visits. Proof:
   if a polygon point `q` is within `radius` of the tap, then `q` is within the AABB (which
   determines the command's cells) and within the expanded range (which determines the queried
   cells). The cell containing `q` is in both sets.

2. **Scene order is preserved.** Candidates are re-sorted by `commandOrderMap` before being passed to
   `findItemAt` (`IsometricRenderer.kt:277–279`). The `LinkedHashSet` dedup preserves insertion order
   within cells, but the explicit `sortedBy` restores the authoritative scene order regardless of
   cell iteration order.

3. **`findItemAt(reverseSort=true)` iterates front-to-back.** `PreparedScene.commands` is in
   back-to-front order (painter's algorithm). Both fast and slow paths pass `reverseSort=true`, so
   `findItemAt` reverses to front-to-back and returns the first (frontmost) match. The spatial-index
   path preserves this by sorting candidates in back-to-front order before constructing the filtered
   scene.

4. **`getBounds()` and `buildConvexHull()` use the same point set.** Both operate on
   `RenderCommand.points` from the `PreparedScene`. The hull is a subset of those points, so the
   hull's AABB is contained within the `getBounds()` AABB. No command can be hit-testable at a
   point outside the cells it was inserted into.

5. **Null `spatialIndex` falls back to slow path.** The guard
   `if (enableSpatialIndex && spatialIndex != null)` ensures that if the index wasn't built (e.g.,
   `enableSpatialIndex=false`, or build was skipped), the `else` branch runs the full linear search
   (`IsometricRenderer.kt:270, 302`).

6. **`commandIdMap` and `commandOrderMap` are built from the same iteration.** Both are populated in
   a single `for ((index, command) in scene.commands.withIndex())` loop
   (`IsometricRenderer.kt:425–428`), so they are always consistent.

---

## Pass 2: Performance

### HIGH — P1. `commandToNodeMap` build is O(n * m) due to linear scan in `resolveNodeForCommand`

**File:** `IsometricRenderer.kt:432–440`

```kotlin
val cmdToNode = HashMap<String, IsometricNode>(scene.commands.size)
for (command in scene.commands) {
    val node = resolveNodeForCommand(command.id)
    if (node != null) {
        cmdToNode[command.id] = node
    }
}
```

**Issue:** For each of the n commands, `resolveNodeForCommand` does a linear scan of `nodeIdMap`
(m entries). For a 200-shape scene (~1000 commands, ~201 nodes), this is ~200,000 comparisons on
every cache rebuild. With `enablePreparedSceneCache=false` (the Phase 3 benchmark config), this runs
every frame.

**Impact on benchmarks:** This O(n*m) cost is included in `avgPrepareMs` when the spatial index is
enabled. It will inflate the prepare-time regression reported for Phase 3, obscuring the actual
grid-build cost.

**Recommendation:** Replace the linear scan with an inverted lookup. Since command IDs have a known
format (`${nodeId}` for PathNode, `${nodeId}_${suffix}` for ShapeNode/BatchNode), build the mapping
during `render()` when the node-to-command relationship is already known. Or compute a `HashMap` from
nodeId prefixes at `buildSpatialIndex` time instead of scanning entries for every command.

---

### MEDIUM — P2. `findItemAt` checks `isPointCloseToPoly` before `isPointInPoly`

**File:** `IsometricEngine.kt:166–176`

```kotlin
val isInside = if (useRadius) {
    IntersectionUtils.isPointCloseToPoly(hull.map { Point(it.x, it.y, 0.0) }, x, y, radius)
    || IntersectionUtils.isPointInPoly(hull.map { Point(it.x, it.y, 0.0) }, x, y)
} else {
    IntersectionUtils.isPointInPoly(hull.map { Point(it.x, it.y, 0.0) }, x, y)
}
```

**Issue:** `isPointCloseToPoly` iterates all edges and computes distance-to-segment for each one.
`isPointInPoly` is a simple ray-cast counter. For taps that are clearly inside a polygon (the common
case for direct hits), checking the cheaper `isPointInPoly` first would short-circuit and skip the
edge-distance computation.

This is **not spatial-index-specific** — it affects both paths equally. But the spatial index reduces
the number of commands checked, partially mitigating this. The improvement would be more pronounced
on the slow path.

**Recommendation:** Swap the order: `isPointInPoly(...) || isPointCloseToPoly(...)`.

---

### MEDIUM — P3. `hull.map { Point(it.x, it.y, 0.0) }` allocates a new list per command per hit test

**File:** `IsometricEngine.kt:168, 173, 179`

**Issue:** `findItemAt` is called once per hit test. For each candidate command, it allocates a new
`List<Point>` by mapping `Point2D` to `Point`. This runs on every iteration of the inner loop.

With the spatial index, the candidate count is small (O(k) per cell), so the total allocations per
hit test are proportional to candidate count, not scene size. Without the spatial index, every
command in the scene triggers this allocation.

**Recommendation:** Either:
1. Change `IntersectionUtils` to accept `List<Point2D>` directly (avoid the conversion), or
2. Pre-convert during `buildSpatialIndex` and store alongside the command.

---

### MEDIUM — P4. Fast path allocates three temporary lists per hit test

**File:** `IsometricRenderer.kt:277–279, IsometricEngine.kt:155–159`

The fast path creates:
1. `candidateIds.mapNotNull { ... }` — new list
2. `.sortedBy { ... }` — new list
3. `preparedScene.commands.reversed()` inside `findItemAt` — new list

Three list allocations per hit test, even when the candidate count is small. The slow path allocates
only the `reversed()` list.

**Recommendation:** For the fast path, consider building the candidate list in-place in front-to-back
order (reverse the `sortedBy` comparator) and passing `reverseSort = false` to `findItemAt` to avoid
the internal `reversed()` call.

---

### Low — P5. `SpatialGrid` grid allocation is proportional to viewport, not scene content

**File:** `IsometricRenderer.kt:530–532`

```kotlin
private val cols = (width / cellSize).toInt() + 1
private val rows = (height / cellSize).toInt() + 1
private val grid = Array(rows) { Array(cols) { mutableListOf<String>() } }
```

For an 800x600 viewport with cellSize=100: 9 cols, 7 rows = 63 cells, each with an empty
`MutableList`. This is fine. For a 4K viewport (3840x2160) with cellSize=100: 39 cols, 22 rows = 858
cells. Still reasonable.

But if `spatialIndexCellSize` is set very small (e.g., 1.0), an 800x600 viewport would create
801*601 = 481,401 cells. The constructor validates `cellSize > 0` but doesn't cap the grid size.

**Recommendation:** Add a reasonable upper bound on grid dimensions (e.g., 1000x1000) or a lower
bound on cellSize relative to viewport size.

---

## Pass 3: Robustness

### MEDIUM — R1. NaN points would insert commands into cell (0,0) silently

**File:** `IsometricRenderer.kt:534–548`

**Issue:** If a `RenderCommand` has NaN in its `points` (from degenerate geometry or a math error),
`getBounds()` would produce NaN in `ShapeBounds`. Then `floor(NaN / cellSize).toInt()` evaluates to
`0` in Kotlin/JVM (via `Double.toInt()` which returns 0 for NaN). The `insert()` method would insert
the command into cell (0,0).

This wouldn't crash, but it would cause spurious hit-test results for taps near the origin.

**Likelihood:** Very low in practice — the engine's `translatePoint` produces well-defined coordinates
from valid 3D points.

**Recommendation:** Add a NaN guard in `getBounds()`:
```kotlin
if (minX.isNaN() || minY.isNaN() || maxX.isNaN() || maxY.isNaN()) return null
```

---

### Low — R2. `invalidate()` clears spatial index maps but `rebuildCache` only rebuilds them when `enableSpatialIndex` is true

**File:** `IsometricRenderer.kt:342–352, 395–397`

**Issue:** `invalidate()` unconditionally clears `commandIdMap`, `commandOrderMap`,
`commandToNodeMap`, and `spatialIndex`. But `rebuildCache()` only repopulates them when
`enableSpatialIndex` is true. If the spatial index is disabled, these maps stay empty after a
rebuild.

This is correct behavior — the maps are only needed for the fast path. But `findNodeByCommandId()`
falls back to `resolveNodeForCommand()` when `commandToNodeMap` is empty, which works but is O(m)
per call. The `commandToNodeMap` provides O(1) resolution regardless of whether the spatial grid is
used.

**Recommendation:** Consider building `commandToNodeMap` (and `commandIdMap`, `commandOrderMap`)
unconditionally, since the O(1) node resolution benefits the slow path too. Only the `SpatialGrid`
itself should be gated on `enableSpatialIndex`.

---

### Low — R3. `commandOrderMap` defaults missing entries to `Int.MAX_VALUE`

**File:** `IsometricRenderer.kt:279`

```kotlin
.sortedBy { command -> commandOrderMap[command.id] ?: Int.MAX_VALUE }
```

If a command ID is in the spatial grid but not in `commandOrderMap` (should never happen since both
are built from the same `scene.commands`), it sorts to the end. Multiple such commands would have
identical sort keys and their relative order would be defined by the stable sort's input order
(LinkedHashSet iteration order).

This is a safe defensive default. No action needed.

---

### Low — R4. `System.identityHashCode` is not guaranteed unique across nodes

**File:** `IsometricNode.kt:66`

```kotlin
val nodeId: String = "node_${System.identityHashCode(this)}"
```

`System.identityHashCode` can return the same value for two distinct objects (hash collision).
If two nodes share a nodeId, only the last one in tree traversal order appears in `nodeIdMap`.
Commands from the shadowed node would resolve to the wrong node.

This is a pre-existing design issue, not introduced by the spatial index. The spatial index inherits
the limitation.

**Likelihood:** Extremely low for small scenes. Increases with scene size.

**Recommendation:** No action for the spatial-index branch. For a future hardening pass, switch to a
monotonic counter or UUID.

---

## Pass 4: API Design

### Low — A1. `commandToNodeMap` is coupled to `buildSpatialIndex()`

**File:** `IsometricRenderer.kt:421–455`

**Issue:** The method `buildSpatialIndex()` builds four things:
1. `commandIdMap` — command ID to RenderCommand
2. `commandOrderMap` — command ID to scene index
3. `commandToNodeMap` — command ID to IsometricNode
4. `spatialIndex` — the SpatialGrid

Items 1–3 are useful independent of the spatial grid. The O(1) node resolution
(`findNodeByCommandId`) works via `commandToNodeMap`, which is only populated when
`enableSpatialIndex=true`. This means disabling the spatial index also disables O(1) node
resolution, forcing the slow-path fallback in `findNodeByCommandId()`.

**Recommendation:** Extract the map-building logic (items 1–3) into a separate method
(e.g., `buildCommandMaps`) that runs unconditionally in `rebuildCache()`. Keep only the
`SpatialGrid` construction gated on `enableSpatialIndex`.

---

### Low — A2. `HIT_TEST_RADIUS_PX` couples query expansion to engine hit radius

**File:** `IsometricRenderer.kt:67, 272, 294, 310`

The constant `HIT_TEST_RADIUS_PX = 8.0` is used in three places:
1. `spatialIndex.query(x, y, HIT_TEST_RADIUS_PX)` — grid query expansion
2. `engine.findItemAt(..., radius = HIT_TEST_RADIUS_PX)` — fast path engine call
3. `engine.findItemAt(..., radius = HIT_TEST_RADIUS_PX)` — slow path engine call

All three use the same constant, so they are consistent. If someone changes one call site without
the others, the fast and slow paths would diverge silently.

**Recommendation:** No action needed — the single constant enforces consistency. Worth noting as
a maintenance note: the query radius and engine radius must always match.

---

## Pass 5: Test Coverage

### MEDIUM — T1. No test for BatchNode or PathNode hit testing

**File:** `IsometricRendererTest.kt`

**Issue:** All tests use `ShapeNode` (Prism). The three node types produce different command ID
formats:

| Node type | Command ID format | Tested? |
|-----------|-------------------|---------|
| PathNode | `node_<hash>` | No |
| ShapeNode | `node_<hash>_<pathHash>` | Yes |
| BatchNode | `node_<hash>_<index>_<pathHash>` | No |

`resolveNodeForCommand()` uses prefix matching with `_` separator validation. The PathNode case uses
exact equality (`commandId == nodeId`), while BatchNode adds a second `_` segment. Neither is tested.

**Recommendation:** Add at least one test for PathNode (exact-match ID) and one for BatchNode
(double-underscore ID) to verify the prefix resolution works for all node types.

---

### Low — T2. No test verifies multi-cell dedup correctness

**Issue:** The cell-boundary test (`hitTest fast path matches slow path near cell boundary within hit
radius`, line 255) uses `cellSize=4.0` and tests one boundary point. It does not verify that:
1. The query actually spanned multiple cells (no assertion on candidate count)
2. Dedup produced the correct set (no assertion on intermediate results)
3. A command spanning multiple cells appears exactly once in the candidate list

**Recommendation:** Add a test that constructs a scene where a shape's AABB spans multiple cells,
queries from the overlap region, and asserts the spatial index returns the correct count of unique
candidates.

---

### Low — T3. No test for hit testing with negative coordinates

**Issue:** A tap at negative screen coordinates (e.g., `x=-5, y=-5`) is valid — shapes can project
to negative screen space when the viewport origin is offset. The spatial grid clamps to `max(0, ...)`
in `query()`, which would skip cells at negative indices. No test verifies this edge case.

**Recommendation:** Add a test that taps at `(-1.0, -1.0)` and verifies the result matches the slow
path (likely null, but should not crash or throw).

---

### Low — T4. No test for out-of-bounds coordinates beyond viewport

**Issue:** A tap at `(900, 700)` on an `800x600` viewport is clamped by
`min(cols-1, floor((x+radius)/cellSize).toInt())` in `query()`. No test verifies this behaves
identically to the slow path.

**Recommendation:** Add a test with tap coordinates beyond viewport bounds.

---

### Low — T5. All tests use `RenderOptions.Quality` which disables bounds checking

**File:** `IsometricRendererTest.kt:127`

```kotlin
private val defaultContext = RenderContext(
    width = 800, height = 600,
    renderOptions = RenderOptions.Quality,
    lightDirection = dirA
)
```

`RenderOptions.Quality` has `enableBoundsChecking = false` and `enableBackfaceCulling = false`. In
production, `RenderOptions.Default` enables both, meaning the engine culls off-screen and
back-facing items before they enter the `PreparedScene`. The spatial index only indexes commands
that survive culling, so culled items are never in the grid.

No test verifies that spatial-index hit testing works correctly when bounds checking and backface
culling are active — i.e., that a culled item doesn't produce a false hit in the slow path that the
fast path misses (or vice versa).

**Recommendation:** Add at least one test using `RenderOptions.Default` to confirm that culling +
spatial index still produces correct results.

---

## Summary

| # | Severity | Pass | Finding |
|---|----------|------|---------|
| C1 | HIGH | Correctness | `resolveNodeForCommand` linear scan relies on undocumented nodeId format invariant |
| P1 | HIGH | Performance | `commandToNodeMap` build is O(n*m) per cache rebuild |
| C2 | MEDIUM | Correctness | `buildConvexHull` is approximate — known engine limitation, both paths affected equally |
| P2 | MEDIUM | Performance | `isPointCloseToPoly` checked before cheaper `isPointInPoly` |
| P3 | MEDIUM | Performance | `hull.map { Point(...) }` allocates per command per hit test |
| P4 | MEDIUM | Performance | Fast path creates 3 temporary lists per hit test |
| R1 | MEDIUM | Robustness | NaN points would silently insert into cell (0,0) |
| T1 | MEDIUM | Test coverage | No BatchNode or PathNode hit-test tests |
| P5 | LOW | Performance | No upper bound on grid dimensions for small cellSize |
| R2 | LOW | Robustness | O(1) node resolution only available when spatial index enabled |
| R3 | LOW | Robustness | Missing `commandOrderMap` entry defaults to end — safe defensive behavior |
| R4 | LOW | Robustness | `identityHashCode`-based nodeId not guaranteed unique |
| A1 | LOW | API design | Command maps coupled to spatial grid build |
| T2–T5 | LOW | Test coverage | Missing multi-cell dedup, negative coord, out-of-bounds, and culling tests |

---

## Disposition

The implementation is **correct for its current usage patterns** and ready for Phase 3 benchmarking.
The correctness argument holds: the fast path can only narrow the candidate set, never alter the
hit-test verdict for candidates that pass filtering. Radius-expanded queries and order-preserving
sorts prevent false negatives and ordering discrepancies.

The HIGH findings (C1/P1) are both about `resolveNodeForCommand`: a theoretical correctness concern
if nodeId format changes, and a concrete O(n*m) build cost that inflates prepare-time measurements.
Both can be addressed before or after the Phase 3 benchmark run without affecting the benchmark's
validity — the cost is consistent across runs and measurable in `avgPrepareMs`.

No blocker for proceeding with Phase 3.
