---
schema: sdlc/v1
type: review
review-command: performance
slug: texture-material-shaders
slice-slug: uv-generation-knot
commit: e5cf72a
status: complete
stage-number: 7
created-at: "2026-04-20T22:00:00Z"
tags: [performance]
refs:
  slice-def: 03-slice-uv-generation-knot.md
  plan: 04-plan-uv-generation-knot.md
  implement: 05-implement-uv-generation-knot.md
  verify: 06-verify-uv-generation-knot.md
---

# Review: performance — uv-generation-knot

## Scope

Commit `e5cf72a` — `feat(texture-material-shaders): implement uv-generation-knot`.
Hot-path files reviewed:
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvGenerator.kt` — `forKnotFace`, `forAllKnotFaces`, `quadBboxUvs`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Knot.kt` — `sourcePrisms` initializer

Context: UV generation runs once per face per frame for textured Knot rendering.
Knot has exactly 20 faces: 18 sub-prism delegates + 2 custom bbox quads.

---

## P-1 — HIGH: `forKnotFace` allocates a fresh `FloatArray(8)` on every call with no caching

**Severity:** HIGH
**Confidence:** High
**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvGenerator.kt`
**Lines:** `forKnotFace` (lines 370–385), `quadBboxUvs` (lines 401–433), `computeUvs` (lines 435–478)

### Observation

Every call to `forKnotFace` for a sub-prism face (indices 0–17) ultimately reaches `computeUvs`, which unconditionally allocates `FloatArray(8)`:

```kotlin
// UvGenerator.kt:443
val result = FloatArray(8)
```

Every call to `forKnotFace` for a custom quad (indices 18–19) reaches `quadBboxUvs`, which also allocates `FloatArray(8)`:

```kotlin
// UvGenerator.kt:409
val result = FloatArray(8)
```

The hot path is `uvProvider.provide(shape, faceIndex)` at `IsometricNode.renderTo`, called once per face per render frame. For a single Knot with 20 faces that is **20 `FloatArray(8)` heap allocations per frame** — all redundant for a stationary Knot. Because `Knot` is immutable post-construction, its UVs are geometrically fixed; the same 20 arrays should be returned every frame.

### Comparison with siblings

The caching strategy diverges sharply across sibling generators:

| Shape | Cache strategy | Hot-path allocs (stable instance) |
|-------|----------------|-----------------------------------|
| Pyramid | `getOrComputeBaseUvs` — single-slot identity cache for base face; `LATERAL_CANONICAL_UVS` constant for laterals | **0** |
| Cylinder | `getOrComputeCapUvs` — single-slot identity cache for caps; inline `floatArrayOf` for sides | 1 per side face per frame (known weakness) |
| Stairs | **No cache** (P-1 BLOCKER in stairs review) | 20+ per frame |
| Knot | **No cache** | **20 per frame** |

Knot is in the same uncached state that triggered a BLOCKER finding on the Stairs slice. For Knot the face count is fixed at 20 (not user-controlled like `stepCount`), but 20 allocations per frame per Knot instance on the render thread is still meaningful allocation pressure at any reasonable scene density.

### Why it matters

The Kotlin/Android GC is a moving, generational collector. Short-lived arrays created per-frame on the render thread accumulate in the young generation and trigger minor GCs during animation. On low-RAM devices (2–3 GB common in emerging markets), these minor GCs are measurable as dropped frames (~1–2 ms each). For a scene with 5 Knots at 60 fps that is 100 arrays/frame × 32 bytes each × 60 fps = ~192 KB/s of GC-eligible allocation from UV generation alone.

Unlike Stairs (whose side UVs vary per step count), all 20 Knot UVs are **fully determined by the immutable `sourcePrisms` and the fixed custom-quad paths** — there is no dimension that varies after construction. Every single call is redundant after the first.

### Fix — full all-faces cache (single slot)

Apply the same single-slot identity cache pattern used by Pyramid (`getOrComputeBaseUvs`) and Cylinder (`getOrComputeCapUvs`), extended to cache the full 20-face result list:

```kotlin
@Volatile private var lastKnot: Knot? = null
@Volatile private var lastKnotUvs: List<FloatArray>? = null

private fun getOrComputeAllKnotUvs(knot: Knot): List<FloatArray> {
    val cached = lastKnotUvs
    if (lastKnot === knot && cached != null) return cached
    val fresh = (0 until 20).map { i -> computeKnotFaceUncached(knot, i) }
    lastKnot = knot
    lastKnotUvs = fresh
    return fresh
}
```

Where `computeKnotFaceUncached` contains the current dispatch logic from `forKnotFace` without the identity-cache guard. Then `forKnotFace(knot, i)` becomes:

```kotlin
fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray {
    require(faceIndex in knot.paths.indices) { ... }
    return getOrComputeAllKnotUvs(knot)[faceIndex]
}
```

And `forAllKnotFaces` returns the cached list directly:

```kotlin
fun forAllKnotFaces(knot: Knot): List<FloatArray> = getOrComputeAllKnotUvs(knot)
```

This reduces hot-path allocation to **zero** for any stable Knot instance (the common case). The `@Volatile` annotations ensure safe publication on JVM, matching the pattern in use for Pyramid and Cylinder. Per-face indexing into the cached list is O(1) — no regression.

**Mutation contract**: The returned `FloatArray` instances must not be mutated by callers. The same mutation contract already applies to `LATERAL_CANONICAL_UVS` (Pyramid) and the Cylinder cap cache. Verify that `TexturedCanvasDrawHook.computeAffineMatrix` and `GpuUvCoordsBuffer.upload` only read — both are confirmed read-only consumers (setPolyToPoly and ByteBuffer copy respectively), so sharing is safe.

**Expected improvement**: 20 heap allocations per frame per Knot → 0 allocations per frame per Knot (stable instance). At 5 Knots × 60 fps this eliminates ~6 000 array allocations/second.

---

## P-2 — MED: `quadBboxUvs` performs 6 separate collection traversals over a 4-point list

**Severity:** MED
**Confidence:** High
**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvGenerator.kt`
**Lines:** 403–405

### Observation

```kotlin
val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
val minZ = pts.minOf { it.z }; val maxZ = pts.maxOf { it.z }
```

This is 6 independent calls to `minOf`/`maxOf`, each of which traverses the entire 4-point `List<Point>`. The total work is 6 × 4 comparisons = 24 comparisons (plus 6 lambda invocations per call × 4 items = 24 lambda calls). A single pass would require 3 × 2 comparisons = 6 comparisons and 4 × 3 field reads = 12 reads.

The absolute cost is small (4-element list), but this runs on every `quadBboxUvs` call, which is per-frame for faces 18 and 19. With P-1's cache fix applied this becomes a one-time cost at construction, so P-2 is a secondary concern. However, if caching is not implemented (e.g., the fix is deferred), the 6-traversal pattern runs 2 × per frame per Knot.

### Fix — single-pass min/max

```kotlin
private fun quadBboxUvs(path: Path): FloatArray {
    val pts = path.points
    var minX = pts[0].x; var maxX = minX
    var minY = pts[0].y; var maxY = minY
    var minZ = pts[0].z; var maxZ = minZ
    for (i in 1..3) {
        val p = pts[i]
        if (p.x < minX) minX = p.x else if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y else if (p.y > maxY) maxY = p.y
        if (p.z < minZ) minZ = p.z else if (p.z > maxZ) maxZ = p.z
    }
    // ... rest unchanged
}
```

This reduces 6 traversals to 1 and eliminates 6 lambda allocations (on JVM, non-inlined lambdas in `minOf` with a selector can allocate a Function1 object unless the JIT eliminates them; with 4 elements the JIT threshold is uncertain on ART). The fix is low-risk (same semantics) and consistent with the implementation quality of `computeUvs`, which uses a manual loop.

If P-1's caching is implemented, P-2 degrades from "runs per frame" to "runs once at cache-miss time." The fix remains worthwhile for correctness-of-approach but drops in priority.

---

## P-3 — MED: `Knot.sourcePrisms` allocates 3 `Prism` objects (plus their `paths`) at `Knot` construction, duplicating `createPaths` work

**Severity:** MED
**Confidence:** High
**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Knot.kt`
**Lines:** 35–39

### Observation

```kotlin
val sourcePrisms: List<Prism> = listOf(
    Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
    Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
    Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
)
```

Each `Prism` constructor calls `Shape(createPaths(...))`, which calls `Prism.Companion.createPaths(...)`. That function allocates:
- 4 `Path` objects (face1, face1.reverse().translate, face2, face2.reverse().translate, face3.reverse, face3.translate — actually 6 paths, with intermediate allocations for `.reverse()` and `.translate()` intermediates)
- Each `Path` copies its `points` list to an immutable backing list (`this.points = points.toList()`)
- Each `Path` eagerly computes `depth` as `sumOf { it.depth() } / size`

For 3 `Prism` objects that is roughly **18 `Path` allocations** (6 per prism) plus ~36–72 `Point` allocations (6–12 per path × 6 paths × 3 prisms, some shared), all at Knot construction time.

Simultaneously, `createPaths(position)` (called by `Shape(createPaths(position))` in the primary constructor) performs the same 3 Prism allocations implicitly as throwaway locals:

```kotlin
// Knot.kt:51-53
allPaths.addAll(Prism(Point.ORIGIN, 5.0, 1.0, 1.0).paths)
allPaths.addAll(Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0).paths)
allPaths.addAll(Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0).paths)
```

Each `Knot` construction therefore allocates **6 Prism objects** in total (3 in `createPaths`, 3 in `sourcePrisms`), while only 3 are kept alive (the `sourcePrisms` list). The 3 throwaway Prisms from `createPaths` are immediately GC-eligible.

### Impact

If `Knot` is allocated infrequently (once per scene setup), this is a one-time cost. If `Knot` is allocated per Compose recomposition (e.g., `Knot()` inside a composable lambda without `remember {}`), it becomes per-frame: ~6 Prism allocations + ~36 Path allocations + ~72+ Point allocations per frame per Knot.

The per-frame case is a user error (should use `remember {}`), but the architecture does not protect against it. There is no Compose-side guardrail.

### Fix options

**Option A (structural, preferred):** Factor `createPaths` to accept the three Prisms rather than reconstructing them, then drive `sourcePrisms` from the same three instances:

```kotlin
class Knot(val position: Point = Point.ORIGIN) : Shape(createPathsFromPrisms(buildSourcePrisms(), position)) {
    val sourcePrisms: List<Prism> = buildSourcePrisms()  // still duplicated, but...
```

Better: pass the Prisms into the companion:

```kotlin
companion object {
    private fun buildSourcePrisms() = listOf(
        Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
        Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
        Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
    )
    private fun createPaths(prisms: List<Prism>, position: Point): List<Path> {
        val allPaths = mutableListOf<Path>()
        prisms.forEach { allPaths.addAll(it.paths) }
        // ... add custom paths, scale, translate
    }
}
```

Then the `Knot` body becomes:

```kotlin
private val _sourcePrisms = buildSourcePrisms()
val sourcePrisms: List<Prism> = _sourcePrisms
// Shape receives: createPaths(_sourcePrisms, position)
```

This requires `sourcePrisms` to be initialized before `Shape(...)` is called, which is not possible directly in the primary constructor delegation chain (`Shape(...)` is the superclass call). The cleanest workaround is a secondary constructor or a companion factory.

**Option B (pragmatic, lower effort):** Document the allocation cost in `Knot`'s KDoc and add a note directing callers to use `remember { Knot(...) }` in Compose. This is the lower-effort fix matching the project's existing pattern (Prism/Pyramid/Cylinder carry no such note because their allocation cost is much lower). Given the 6× duplication, adding a code comment is the minimum-viable mitigation.

**Option C (full fix, higher complexity):** Introduce a lazy or by-construction pattern using an `init` block (Kotlin allows `init` to reference vals computed in delegated constructors):

```kotlin
class Knot(val position: Point = Point.ORIGIN) : Shape(Knot.buildPaths(position)) {
    val sourcePrisms: List<Prism> get() = SOURCE_PRISMS  // static, shared across instances

    companion object {
        private val SOURCE_PRISMS = listOf(
            Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
            Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
            Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
        )
        internal fun buildPaths(position: Point): List<Path> {
            val allPaths = mutableListOf<Path>()
            SOURCE_PRISMS.forEach { allPaths.addAll(it.paths) }
            // ... custom paths, scale, translate
        }
    }
}
```

`SOURCE_PRISMS` is initialized once at class-load time. `buildPaths` uses them to derive `paths` without allocating additional Prism objects. This eliminates 3 of the 6 Prism allocations per construction and 18 Path allocations per construction — only the scaled+translated `Path` copies remain. Note that `Prism.paths` still retains references to the 6 `Path` objects per prism, but these become shared across all Knot instances.

Caveat: `Knot.translate` currently returns `Knot(position.translate(...))`, which re-runs the full constructor chain. Option C does not change that — the scaled+translated Paths are still re-allocated per translate. To eliminate that you would need `Knot` to carry a mutable transform instead of copying, which is a much larger change.

**Recommended:** Option C for a production fix; Option B as minimum-viable mitigation this slice.

---

## P-4 — LOW: `forKnotFace` `when` dispatch for 20 faces — negligible overhead

**Severity:** LOW
**Confidence:** High
**File:** `UvGenerator.kt`
**Lines:** 374–384

### Observation

```kotlin
return when (faceIndex) {
    in 0..17 -> { val prismIndex = faceIndex / 6; val localFaceIndex = faceIndex % 6; ... }
    18, 19 -> quadBboxUvs(knot.paths[faceIndex])
    else -> throw IllegalArgumentException(...)
}
```

The `when` expression is dispatched once per `forKnotFace` call. There are 3 branches (range check, two explicit values, else). The JVM compiles `in 0..17` to a `IAND`/`ISUB`/comparisons sequence, and the `18, 19` arm to two integer equality checks. This is O(1) with a tiny constant. There is no nested dispatch, no reflection, and no allocation from the `when` itself.

The two arithmetic operations (`faceIndex / 6`, `faceIndex % 6`) are integer divides — slightly expensive on older ARM cores but bounded and not in an inner loop.

This is not an actionable concern. Documented for completeness.

---

## P-5 — OK: `forAllKnotFaces` list allocation — at parity with siblings, not hot-path

**Severity:** OK (no action required)
**File:** `UvGenerator.kt`
**Line:** 393

### Observation

```kotlin
fun forAllKnotFaces(knot: Knot): List<FloatArray> =
    knot.paths.indices.map { forKnotFace(knot, it) }
```

This allocates a `List<FloatArray>` of size 20 on each call. However, `forAllKnotFaces` is not called per-face per-frame on the hot render path — `UvCoordProviderForShape` wires `forKnotFace` (the per-face variant). `forAllKnotFaces` is used in tests and potentially one-shot baking scenarios.

With P-1's caching fix applied, `forAllKnotFaces` can return the cached `List<FloatArray>` directly without any new allocation — making this a zero-cost operation on cache hit.

At parity with `forAllPyramidFaces`, `forAllCylinderFaces`, `forAllStairsFaces` — not a Knot-specific regression.

---

## P-6 — OK: Double→Float conversion in `computeUvs` and `quadBboxUvs` — unavoidable

**Severity:** OK (no action required)
**File:** `UvGenerator.kt`, `computeUvs` (lines 435–478), `quadBboxUvs` (lines 401–433)

### Observation

Both functions perform Double arithmetic (matching the `Point` API which stores `Double`) then call `.toFloat()` for the result array. This is unavoidable without changing `Point` to store `Float`, which would be a breaking API change with wide impact across the library. All sibling generators share this pattern — not a Knot regression.

---

## P-7 — ADVISORY: No `remember {}` guard or documentation for Compose callers

**Severity:** ADVISORY (no action required this slice)
**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Knot.kt`

### Observation

`Knot` has no Compose-visible stability annotation (`@Stable`, `@Immutable`) and no documentation note directing callers to wrap `Knot(...)` in `remember {}`. Compose's recomposition model will reallocate `Knot()` on every recomposition if the call site is not memoized, triggering the P-3 double-allocation chain on every frame.

While this is technically a user error, the allocation cost of `Knot` construction is unusually high compared to `Prism` (3× Prism cost + extra Path chain), making the omission more consequential. Adding a brief KDoc note (`// In Compose: remember { Knot(...) }`) would reduce support burden.

This is advisory because adding Compose annotations or KDoc notes is out of scope for a UV-generation slice.

---

## Summary Table

| ID | Severity | Confidence | Area | Verdict |
|----|----------|------------|------|---------|
| P-1 | **HIGH** | High | `forKnotFace` per-frame FloatArray allocation — no cache | Fix required: add single-slot all-faces identity cache |
| P-2 | MED | High | `quadBboxUvs` 6× traversal vs single pass | Fix recommended: single-pass min/max loop |
| P-3 | MED | High | `Knot.sourcePrisms` double-allocates 3 Prisms at construction | Fix recommended: make SOURCE_PRISMS a companion `val` |
| P-4 | LOW | High | `when` dispatch overhead in `forKnotFace` | No action; O(1) constant cost |
| P-5 | OK | High | `forAllKnotFaces` list allocation | At parity with siblings; not hot-path |
| P-6 | OK | High | Double→Float conversion | Unavoidable given `Point` API |
| P-7 | ADVISORY | Med | No `remember {}` KDoc for Compose callers | Nice-to-have; out of this slice's scope |

---

## Performance Health

- **Algorithm complexity:** PASS (O(1) per face, O(N) per-all-faces where N=20 fixed)
- **Memory management:** FAIL — per-frame allocation, no caching (P-1)
- **I/O operations:** PASS (no I/O)
- **Caching strategy:** FAIL — no cache (P-1); single-pass opportunity missed (P-2)

---

## Recommended Fixes

### P-1 fix (HIGH — required before merge)

Add a `@Volatile private var lastKnot / lastKnotUvs` single-slot identity cache in `UvGenerator`, following the exact pattern of `getOrComputeBaseUvs` (Pyramid, lines 232–238) and `getOrComputeCapUvs` (Cylinder, lines 180–191). Cache the full 20-element `List<FloatArray>` so that both `forKnotFace` and `forAllKnotFaces` benefit from a single cache entry. On a cache hit (same `Knot` instance), both functions return without any allocation. This eliminates 20 heap allocations per frame per Knot instance for stable scenes.

### P-2 fix (MED — recommended before merge)

Replace the 6 separate `minOf`/`maxOf` traversals in `quadBboxUvs` with a single forward pass computing all six bounds simultaneously. The fix is trivial (5 lines → ~10 lines), risk-free, and consistent with the manual-loop style of `computeUvs`.

### P-3 fix (MED — recommended, deferred to follow-on slice acceptable)

Promote the three `Prism` constants in `Knot.sourcePrisms` to a `companion object val SOURCE_PRISMS`. Drive `createPaths` to iterate `SOURCE_PRISMS` rather than constructing three throwaway Prisms. This halves Knot's construction allocation cost and resolves the logical duplication between `createPaths` and `sourcePrisms`. Given the `Knot` constructor chain constraint, a companion factory approach is cleanest. The regression guard test in `UvGeneratorKnotTest` (`sourcePrisms dimensions match createPaths constants`) would no longer be needed — the two sites would share the same objects.

### P-7 (ADVISORY — deferred)

Add `// In Compose contexts, always use remember { Knot(...) }` KDoc note to `Knot` class-level documentation in a follow-on housekeeping slice.
