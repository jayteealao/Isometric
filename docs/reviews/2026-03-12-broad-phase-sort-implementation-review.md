# Broad-Phase Sort Implementation Review

**Date:** 2026-03-12
**Scope:** Multi-pass code review of the broad-phase depth sort optimization across
`IsometricEngine.sortPaths`, `RenderOptions`, benchmark integration, and `IsometricEngineTest`.
**Branch:** `perf/benchmark-harness`

## Files Reviewed

| File | Lines | Role |
|------|------:|------|
| `isometric-core/.../IsometricEngine.kt` | 483 | `sortPaths` broad-phase path, `buildBroadPhaseCandidatePairs`, grid construction |
| `isometric-core/.../RenderOptions.kt` | 51 | `enableBroadPhaseSort` flag, `broadPhaseCellSize` config |
| `isometric-core/.../IntersectionUtils.kt` | 152 | `hasIntersection` — the narrow phase shared by both paths |
| `isometric-core/.../Path.kt` | 112+ | `closerThan` — 3D depth comparison shared by both paths |
| `isometric-core/.../IsometricEngineTest.kt` | 224 | Parity tests for broad-phase vs baseline ordering |
| `isometric-benchmark/benchmark-runner.sh` | 220 | `--enable-broad-phase-sort` CLI flag |
| `isometric-benchmark/.../BenchmarkFlags.kt` | 58 | Flag definition and serialization |
| `isometric-benchmark/.../BenchmarkScreen.kt` | ~110 | `RenderOptions.copy(enableBroadPhaseSort=...)` |
| `isometric-benchmark/.../HarnessValidator.kt` | ~180 | Runtime flag validation |
| `isometric-benchmark/.../ResultsExporter.kt` | ~380 | Flag state in JSON output |
| `isometric-compose/.../IsometricScene.kt` | 338 | `RuntimeFlagSnapshot.enableBroadPhaseSort` |

## Review Approach

Five passes:

1. **Correctness** — does the broad-phase path produce identical ordering to the baseline path?
2. **Performance** — asymptotic improvement, allocation overhead, worst-case behavior
3. **Robustness** — edge cases, degenerate inputs, state consistency
4. **Integration** — flag threading from CLI to engine, runtime validation
5. **Test coverage** — what is tested, what is missing

---

## Overall Assessment

The implementation is clean and architecturally well-placed: it changes only the candidate-pair
generation step while leaving the narrow-phase intersection test, 3D depth comparison, and
topological sort completely untouched. This is the safest possible insertion point for a broad-phase
optimization.

The benchmark integration is thorough — flag plumbing works end-to-end from CLI through runner,
activity, screen, render options, runtime snapshot, harness validation, and results export.

11 findings: 1 high, 5 medium, 5 low.

---

## Pass 1: Correctness

### HIGH — C1. Candidate-pair ordering is swapped compared to baseline inner loop

**File:** `IsometricEngine.kt:291–313` (broad-phase path) vs `IsometricEngine.kt:314–336` (baseline)

**Issue:** The baseline inner loop iterates:

```kotlin
for (i in 0 until length) {
    for (j in 0 until i) {  // j < i always
```

So the pair is always `(i=larger, j=smaller)` and `i > j`. The dependency edges are:
- `cmpPath < 0` → `drawBefore[i].add(j)` — "j must be drawn before i"
- `cmpPath > 0` → `drawBefore[j].add(i)` — "i must be drawn before j"

The broad-phase path generates pairs via:

```kotlin
val first = minOf(bucket[a], bucket[b])
val second = maxOf(bucket[a], bucket[b])
// ...
pairs.add(second to first)  // second > first, so Pair(larger, smaller)
```

Then iterates:

```kotlin
for (pair in candidatePairs) {
    val i = pair.first   // = second = larger index
    val j = pair.second  // = first = smaller index
```

So `i > j` holds, matching the baseline convention. The dependency edges use the same `i`/`j`
semantics:
- `cmpPath < 0` → `drawBefore[i].add(j)` — "j before i"
- `cmpPath > 0` → `drawBefore[j].add(i)` — "i before j"

This appears correct. **However**, the naming is confusing and fragile:

```kotlin
val first = minOf(bucket[a], bucket[b])
val second = maxOf(bucket[a], bucket[b])
val key = pairKey(first, second)
if (seen.add(key)) {
    pairs.add(second to first)  // SWAPPED: second is first element of Pair
}
```

The variable names `first`/`second` are the min/max indices for dedup, but `pairs.add(second to
first)` reverses them so that `pair.first` is the **larger** index and `pair.second` is the
**smaller** — matching the baseline `(i, j)` where `i > j`. This swap is correct but undocumented
and easy to misread.

If a future change accidentally writes `pairs.add(first to second)`, the `drawBefore` edges would
be inverted, silently breaking depth ordering without failing tests unless those specific scenes
happen to have asymmetric depth relationships.

**Recommendation:** Add a comment documenting the convention:
```kotlin
// Convention: pair = (larger_index, smaller_index) to match baseline (i > j)
pairs.add(second to first)
```
Or better, use named fields instead of `Pair<Int, Int>` to make the contract self-documenting.

---

### Correctness items verified as correct

1. **Narrow phase is identical.** Both paths call the same `IntersectionUtils.hasIntersection()` and
   `Path.closerThan()`. The broad phase only changes *which* pairs are tested, not *how* they are
   tested.

2. **Topological sort is shared.** Both paths build `drawBefore[i]` lists using the same convention.
   The sort loop (lines 338–365) runs identically regardless of which path populated the graph.

3. **The `.map { Point(it.x, it.y, 0.0) }` allocation for `hasIntersection` is present in both
   paths.** This is redundant work (bounds are already computed for broad-phase) but does not affect
   correctness.

4. **Circular dependency handling is shared.** The fallback at lines 357–362 appends any undrawn
   items in original order, regardless of which path built the dependency graph.

5. **Broad-phase pair dedup is correct.** `pairKey(first, second)` uses `first = min, second = max`,
   guaranteeing the same key regardless of which cell the pair was found in. The `HashSet<Long>`
   prevents duplicate intersection tests.

---

## Pass 2: Performance

### MEDIUM — P1. `hasIntersection` still runs its own AABB check after broad-phase already proved AABB overlap

**File:** `IsometricEngine.kt:299–303`, `IntersectionUtils.kt:50–82`

**Issue:** The broad-phase grid guarantees that candidate pairs share a grid cell, meaning their
AABBs overlap (within `cellSize` quantization). But `hasIntersection()` starts with its own bounding
box overlap check (lines 53–82). For broad-phase candidates, this check will almost always pass — it
is redundant work.

For the baseline path, this early AABB check is the primary fast-rejection mechanism and is very
valuable. For the broad-phase path, it adds ~20 comparisons per pair for no benefit.

**Impact:** Minor. The bounding box check is cheap relative to the edge-crossing and
point-in-polygon checks that follow. But for large scenes with many candidate pairs, the wasted
work adds up.

**Recommendation:** Consider adding an `IntersectionUtils.hasIntersectionSkipBounds()` variant or a
boolean parameter to skip the bounds check when the caller has already proven overlap. Low priority.

---

### MEDIUM — P2. Broad-phase `.map { Point(it.x, it.y, 0.0) }` allocates per pair, same as baseline

**File:** `IsometricEngine.kt:301–302`

```kotlin
if (IntersectionUtils.hasIntersection(
    itemA.transformedPoints.map { Point(it.x, it.y, 0.0) },
    itemB.transformedPoints.map { Point(it.x, it.y, 0.0) }
))
```

**Issue:** For every candidate pair, two new `List<Point>` objects are allocated by mapping
`Point2D` to `Point`. This allocation happens in the tight inner loop of the sort. The broad-phase
path reduces the number of pairs (good), but each pair still does two list allocations (unchanged).

The baseline path has the same allocation pattern, so this is not a regression. But it is the
dominant allocation cost in `sortPaths`, and fixing it would amplify the broad-phase speedup.

**Recommendation:** Change `IntersectionUtils.hasIntersection` to accept `List<Point2D>` directly,
or pre-convert points during the transform phase. This would benefit both paths but is most impactful
for broad-phase since the pair-generation savings shouldn't be offset by allocation overhead.

---

### MEDIUM — P3. `buildBroadPhaseCandidatePairs` generates O(k^2) pairs per cell for dense cells

**File:** `IsometricEngine.kt:389–400`

```kotlin
for (bucket in grid.values) {
    if (bucket.size < 2) continue
    for (a in 0 until bucket.lastIndex) {
        for (b in a + 1 until bucket.size) {
```

**Issue:** For a cell containing k items, the inner loop generates k*(k-1)/2 pairs. For well-
distributed scenes, k is small per cell and the total pair count across all cells is much less than
n*(n-1)/2. This is the intended win.

But in degenerate cases (all items in one cell, or very large `cellSize`), k = n and the broad-phase
path generates n*(n-1)/2 pairs — the same as baseline — plus the overhead of grid construction,
bounds computation, and pair dedup. The broad-phase path would be strictly slower than baseline.

**Impact:** This is the expected worst case documented in the plan. It won't produce incorrect
results, just slower performance. The `broadPhaseCellSize` default of 100.0 pixels is tuned for
typical isometric scenes where objects are spread across the viewport.

**Recommendation:** No code change needed, but the Phase 3 benchmark analysis should report the
average number of candidate pairs generated by broad-phase vs the baseline n*(n-1)/2, to quantify
the pair-reduction ratio. Consider adding a warning log if all items land in a single cell.

---

### Low — P4. `getBounds()` computes AABB per item during pair generation, not cached

**File:** `IsometricEngine.kt:373–374, 414–428`

```kotlin
items.forEachIndexed { index, item ->
    val bounds = item.getBounds()
```

**Issue:** `getBounds()` iterates all transformed points for each item. This runs once per item
during `buildBroadPhaseCandidatePairs`. The cost is O(total points across all items) — same order as
the transform step. Not a bottleneck, but the bounds could be precomputed during the transform phase
and stored alongside `TransformedItem` to avoid the extra pass.

**Recommendation:** Low priority. Pre-caching bounds would marginally reduce build cost.

---

### Low — P5. `HashMap<Long, MutableList<Int>>` allocates a list per occupied cell

**File:** `IsometricEngine.kt:371, 382`

```kotlin
val grid = HashMap<Long, MutableList<Int>>()
// ...
grid.getOrPut(cellKey(col, row)) { mutableListOf() }.add(index)
```

**Issue:** Each occupied cell gets a `MutableList<Int>`. For a scene with 1000 items each spanning
~4 cells, this creates ~1000 lists (many cells shared). The `getOrPut` pattern avoids creating lists
for empty cells, which is efficient. The total allocation count is proportional to unique occupied
cells, not total grid area.

**Recommendation:** No action needed. This is the standard approach and performs well.

---

## Pass 3: Robustness

### MEDIUM — R1. `cellKey` and `pairKey` can collide for extreme index values

**File:** `IsometricEngine.kt:406–412`

```kotlin
private fun cellKey(col: Int, row: Int): Long {
    return (col.toLong() shl 32) xor (row.toLong() and 0xffffffffL)
}

private fun pairKey(first: Int, second: Int): Long {
    return (first.toLong() shl 32) xor (second.toLong() and 0xffffffffL)
}
```

**Issue:** `cellKey` uses XOR to combine col and row into a Long. This can collide:
`cellKey(0, x) == cellKey(x, 0)` when `x` fits in 32 bits, because `(0 shl 32) xor x == x` and
`(x shl 32) xor 0 == x shl 32` — actually these don't collide, let me reconsider.

`cellKey(a, b)` = `(a.toLong() shl 32) xor (b.toLong() and 0xffffffffL)`:
- `cellKey(0, 5)` = `0 xor 5` = `5`
- `cellKey(5, 0)` = `(5L shl 32) xor 0` = `21474836480`

These are different. Good.

But what about negative values? Grid coordinates can be negative (items projecting offscreen). For
`col = -1`: `(-1).toLong() shl 32` = `0xFFFFFFFF00000000`. For `row = -1`:
`(-1).toLong() and 0xffffffffL` = `0x00000000FFFFFFFF`. XOR = `0xFFFFFFFFFFFFFFFF`.

`cellKey(-1, 0)` = `0xFFFFFFFF00000000 xor 0` = `0xFFFFFFFF00000000`.
`cellKey(0, -1)` = `0 xor 0x00000000FFFFFFFF` = `0x00000000FFFFFFFF`.

These are different. So negative coordinates don't cause collisions either.

Actually, the XOR scheme can collide when the top 32 bits of the row (after sign extension) match
the col bits. Since `row.toLong() and 0xffffffffL` masks to 32 bits, the scheme is equivalent to
packing two 32-bit ints into a 64-bit long. This is collision-free for all 32-bit int values.

**Revised:** No collision issue. The XOR with mask is safe. Downgrading to informational.

---

### MEDIUM — R2. No guard against NaN in bounds during broad-phase grid binning

**File:** `IsometricEngine.kt:373–384`

**Issue:** If a `TransformedItem` has NaN in its points (from degenerate geometry), `getBounds()`
returns NaN-valued `ItemBounds`. Then `floor(NaN / cellSize).toInt()` evaluates to `0` on JVM,
inserting the item into cell (0, 0). This could cause false pair generation with unrelated items.

The same NaN risk exists for the `SpatialGrid` in the renderer (flagged in the spatial-index review).
Here the consequence is milder — a false pair just triggers an unnecessary `hasIntersection` check
that will likely return false.

**Recommendation:** Add a NaN guard in `getBounds()`:
```kotlin
if (minX.isNaN() || minY.isNaN() || maxX.isNaN() || maxY.isNaN()) {
    return ItemBounds(0.0, 0.0, 0.0, 0.0)  // degenerate item, no overlap
}
```

---

### Low — R3. `observer` position is hardcoded and undocumented

**File:** `IsometricEngine.kt:285`

```kotlin
val observer = Point(-10.0, -10.0, 20.0)
```

**Issue:** The observer position used for depth comparison is a magic constant. It is shared between
both paths (not broad-phase-specific), so it does not affect parity. But the value is undocumented
and changing it would alter depth ordering globally.

**Recommendation:** Move to a named constant with documentation explaining why this position was
chosen (it's a point "behind and above" the origin, consistent with the isometric projection
direction).

---

## Pass 4: Integration

### Low — R4. Runner hardcodes all other flags to `false` when `--enable-broad-phase-sort` is used

**File:** `benchmark-runner.sh:188`

```bash
config_json="...\"flags\":{\"enablePathCaching\":false,\"enableSpatialIndex\":false,
\"enablePreparedSceneCache\":false,\"enableNativeCanvas\":false,
\"enableBroadPhaseSort\":${ENABLE_BROAD_PHASE_SORT}}"
```

**Issue:** When `--enable-broad-phase-sort` is passed, only `enableBroadPhaseSort` is set to the
CLI value. All other flags remain `false`. This is correct for Phase 3 isolated measurement (one
flag at a time). But the runner doesn't support enabling multiple flags simultaneously
(e.g., `--enable-spatial-index --enable-broad-phase-sort` for a combo branch).

Self-tests are unaffected — they use hardcoded JSON strings that don't reference
`ENABLE_BROAD_PHASE_SORT` (lines 141, 149).

**Recommendation:** For now, this is fine — each Phase 3 branch tests one flag. For future combo
branches, the runner should accept multiple `--enable-*` flags. Document this limitation.

---

### Verified correct

1. **Flag threading is complete.** `enableBroadPhaseSort` flows:
   `benchmark-runner.sh` → JSON config → `BenchmarkActivity` → `BenchmarkFlags` →
   `BenchmarkScreen` → `RenderOptions.copy(enableBroadPhaseSort=...)` → `IsometricEngine.prepare()`
   → `sortPaths(items, options)` → `options.enableBroadPhaseSort` branch.

2. **Runtime flag validation works.** `HarnessValidator` checks
   `runtimeFlags.enableBroadPhaseSort != flags.enableBroadPhaseSort` (line 166). The runtime
   snapshot reads `renderOptions.enableBroadPhaseSort` via `IsometricScene` (line 159).

3. **Results export records the flag.** `ResultsExporter` writes
   `put("enableBroadPhaseSort", config.flags.enableBroadPhaseSort)` in JSON output (line 293).
   `FLAG_NAMES` includes `"enableBroadPhaseSort"` for CSV header (line 46).

4. **`--label` creates distinct result directories.** Lines 40–44 append the label to the
   timestamp: `TIMESTAMP="${TIMESTAMP}-${LABEL}"`. Self-tests and matrix results share this
   directory.

5. **Self-tests are isolated from branch overrides.** Self-test JSON strings (lines 141, 149) are
   hardcoded with `"enableBroadPhaseSort":false`. The `ENABLE_BROAD_PHASE_SORT` variable is only
   interpolated in the matrix config (line 188).

---

## Pass 5: Test Coverage

### MEDIUM — T1. No large-scene parity test

**File:** `IsometricEngineTest.kt:137–212`

**Issue:** The three parity tests use 3 prisms each (18 faces max before culling). These are useful
for verifying basic correctness but do not stress the broad-phase under realistic conditions:

- **Sparse** (line 137): 3 prisms spread far apart — most cells have ≤1 item, so few candidate
  pairs are generated. Tests the "no overlap" case.
- **Overlapping** (line 162): 3 prisms sharing x/y footprint — all items likely in the same cell(s),
  so candidate pairs ≈ baseline pairs. Tests the "dense overlap" case.
- **Adjacent-cell** (line 187): 3 prisms with custom `cellSize=50` — tests cross-cell pair
  generation. Good edge case.

Missing: a test with N=20+ items where the broad-phase meaningfully reduces pair count vs baseline.
Without this, the tests don't verify that the grid partitioning actually works at scale — they only
verify it doesn't break for trivial inputs.

**Recommendation:** Add a parity test with 10–20 randomly placed prisms across a large grid
(e.g., 5x5 positions). Verify command ID ordering matches baseline exactly.

---

### Low — T2. No test for `broadPhaseCellSize` effect on pair generation

**Issue:** The adjacent-cell test uses `broadPhaseCellSize = 50.0`, but no test verifies the
relationship between cell size and pair count. For example:
- Very large cell size → all items in one cell → pair count = baseline
- Very small cell size → most items span many cells → more overhead but same correctness
- Optimal cell size → fewest candidate pairs

No test asserts that the pair count decreases when cell size is well-matched to item spacing.

**Recommendation:** Low priority. Pair count is an implementation detail, not a public contract.
The parity tests already ensure correctness regardless of cell size.

---

### Low — T3. No test for single-item scene

**Issue:** When a scene has only 1 item, `bucket.size < 2` skips all cells and no pairs are
generated. The dependency graph is empty. The topological sort adds the single item. This is correct
but untested for the broad-phase path specifically.

**Recommendation:** Trivial to add. `engine.prepare(800, 600, RenderOptions.Default.copy(enableBroadPhaseSort=true))` with 1 prism.

---

### Low — T4. No test for empty scene

**Issue:** With 0 items, both paths skip pair generation entirely. The topological sort returns an
empty list. Correct but untested for the broad-phase path.

**Recommendation:** Trivial to add.

---

## Errata: Spatial-Index Plan Review Finding #6

The spatial-index plan review (`docs/reviews/2026-03-11-phase3-spatial-index-plan-review.md`,
finding #5) stated that `enableBroadPhaseSort` "has no implementation yet" and that
"BenchmarkActivity logs a warning if it is enabled."

This is incorrect. The broad-phase sort is fully implemented in `IsometricEngine.sortPaths()` with
grid-based candidate-pair generation, complete benchmark integration, and 4 unit tests. The
`BenchmarkActivity` warning for unimplemented flags applies only to `enableNativeCanvas` when not on
Android — not to broad-phase sort.

This finding should be struck from the plan review.

---

## Summary

| # | Severity | Pass | Finding |
|---|----------|------|---------|
| C1 | HIGH | Correctness | Pair ordering convention is correct but undocumented and fragile |
| P1 | MEDIUM | Performance | `hasIntersection` re-checks AABB after broad-phase already proved overlap |
| P2 | MEDIUM | Performance | `Point2D` → `Point` list allocation per pair in tight loop |
| P3 | MEDIUM | Performance | O(k^2) pairs per cell degrades to baseline for dense cells |
| R2 | MEDIUM | Robustness | No NaN guard in bounds computation |
| T1 | MEDIUM | Test coverage | No large-scene parity test |
| P4 | LOW | Performance | Per-item bounds not cached across phases |
| P5 | LOW | Performance | Per-cell list allocation — standard and acceptable |
| R3 | LOW | Robustness | Observer position is a magic constant |
| R4 | LOW | Integration | Runner supports only one flag at a time |
| T2–T4 | LOW | Test coverage | Missing cell-size effect, single-item, and empty-scene tests |

---

## Disposition

The implementation is **correct and ready for Phase 3 benchmarking.** The core design — changing
only candidate-pair generation while preserving the narrow phase and topological sort — minimizes
correctness risk. The HIGH finding (C1) is about documentation, not behavior: the swap in
`pairs.add(second to first)` is correct but should be commented.

The most impactful performance improvement opportunity (P2: eliminating `Point2D → Point` list
allocations) would benefit both the broad-phase and baseline paths. It is independent of the
broad-phase work and could be addressed separately.

No blocker for proceeding with Phase 3.
