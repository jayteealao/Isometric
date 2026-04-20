---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: uv-generation-knot
status: implemented
stage-number: 4
created-at: "2026-04-17T00:00:00Z"
updated-at: "2026-04-20T21:02:21Z"
metric-files-to-touch: 7
metric-step-count: 7
has-blockers: false
revision-count: 0
tags: [uv, geometry, knot, experimental]
depends-on: [uv-generation-shared-api, uv-generation, api-design-fixes]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-uv-generation-knot.md
  siblings:
    - 04-plan-uv-generation-shared-api.md
    - 04-plan-uv-generation-cylinder.md
    - 04-plan-uv-generation-pyramid.md
    - 04-plan-uv-generation-stairs.md
    - 04-plan-uv-generation-octahedron.md
  implement: 05-implement-uv-generation-knot.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders uv-generation-knot"
---

# Plan: UV Coordinate Generation — Knot

## Slice Goal

Extend `UvGenerator` with `forKnotFace(knot, faceIndex)` so `textured()` materials
render correctly on `Knot` shapes in both Canvas and WebGPU modes. Delegate UV
generation for the 18 sub-prism paths to the existing `forPrismFace` and apply
bounding-box planar projection for the 2 custom quad paths (indices 18–19).
`perFace {}` is explicitly unsupported on Knot and documented as such.

## Prerequisite: What the uv-generation-shared-api Slice Provides

- `RenderCommand.faceVertexCount: Int` field
- Abstract `IsometricMaterial.PerFace` sealed base with `PerFace.Prism` subclass
- **No `PerFace.Knot` variant** — Knot intentionally excluded
- `UvCoordProvider.forShape(shape: Shape): UvCoordProvider?` factory skeleton
- Consumer migration off `uvCoords.size == 8` assumption

And `uv-generation` (original Prism UV) delivers:

- `UvGenerator.forPrismFace(prism, faceIndex)` — the core delegate this slice reuses
- `UvCoordProvider` `fun interface`
- `UvGenerator` as `internal object` in `isometric-shader`

## Current State: Knot Composition and Path Index Ranges

`Knot.kt` is annotated `@ExperimentalIsometricApi` with a documented depth-sorting bug.
The companion's `createPaths()` builds 20 paths as temporary local values — it does
**NOT** store references to the source `Prism` objects:

```
Prism(Point.ORIGIN, 5.0, 1.0, 1.0).paths          → indices 0..5  (prism1)
Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0).paths  → indices 6..11 (prism2)
Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0).paths → indices 12..17 (prism3)
Path(4 points: custom quad 1)                       → index 18
Path(4 points: custom quad 2)                       → index 19
```

All 20 paths are then scaled by `1/5` and translated by `(-0.1, 0.15, 0.4)` plus `position`.

The source Prism dimensions are **not recoverable** from `knot.paths` without reversing
transforms. Conclusion: **add `sourcePrisms` as an internal val on `Knot`**.

## Sub-Prism Reference Storage: Recommendation

Expose `sourcePrisms` as a `public val` on `Knot`, annotated `@ExperimentalIsometricApi`
(same opt-in as the class itself):

```kotlin
@ExperimentalIsometricApi
class Knot(val position: Point = Point.ORIGIN) : Shape(createPaths(position)) {

    /**
     * The three source [Prism] instances composing the knot's body, in pre-transform
     * (unscaled, untranslated) space. Index 0 → faces 0–5, index 1 → faces 6–11,
     * index 2 → faces 12–17.
     */
    @ExperimentalIsometricApi
    val sourcePrisms: List<Prism> = listOf(
        Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
        Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
        Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
    )

    // ...existing translate() and companion...
}
```

## Reuse Opportunities

- Indices 0..5: `UvGenerator.forPrismFace(knot.sourcePrisms[0], faceIndex % 6)`
- Indices 6..11: `UvGenerator.forPrismFace(knot.sourcePrisms[1], faceIndex % 6)`
- Indices 12..17: `UvGenerator.forPrismFace(knot.sourcePrisms[2], faceIndex % 6)`
- Indices 18..19: bounding-box planar projection (new logic, ~10 lines)

~95% of the UV math is already implemented and tested.

## Likely Files / Areas to Touch

| File | Action | Module |
|------|--------|--------|
| `isometric-core/src/main/kotlin/.../shapes/Knot.kt` | MODIFY — add `sourcePrisms` val; KDoc update | isometric-core |
| `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt` | MODIFY — add `forKnotFace` + `quadBboxUvs` helper | isometric-shader |
| `isometric-shader/src/main/kotlin/.../shader/IsometricMaterialComposables.kt` | MODIFY — add `is Knot` branch to `UvCoordProvider.forShape()` | isometric-shader |
| `isometric-shader/src/test/kotlin/.../shader/UvGeneratorKnotTest.kt` | CREATE | isometric-shader |
| `isometric-compose/src/test/kotlin/.../IsometricCanvasSnapshotTest.kt` | MODIFY — add `knotTextured` snapshot | isometric-compose |
| `isometric-core/api/isometric-core.api` | UPDATE (apiDump) | isometric-core |
| `isometric-shader/api/isometric-shader.api` | UPDATE (apiDump) | isometric-shader |

## Proposed Change Strategy

1. Modify `Knot.kt`: add `sourcePrisms` public val with `@ExperimentalIsometricApi`
2. Add `forKnotFace` to `UvGenerator`, annotated `@OptIn` + `@ExperimentalIsometricApi`
3. Wire `is Knot` branch in `UvCoordProvider.forShape()` with `@OptIn`
4. Document `perFace {}` unsupported behavior (falls through to default via
   `faceType = null` — no code change needed, see Step 4)

## Step-by-Step Plan

### Step 1 — Modify `Knot.kt`: expose `sourcePrisms`

Add `sourcePrisms` as shown above. Add KDoc paragraph:

> **Texture support:** `textured()` (via `IsometricMaterial.Textured`) is the only
> material option for `Knot`. `perFace {}` is not supported — `Knot` has no named
> face taxonomy, and the shape's depth-sorting bug makes per-face visual results
> unreliable. If a `PerFace` material is passed to a `Shape(Knot(...), material)`
> composable, every face resolves to the `PerFace.default` material.

### Step 2 — Add `forKnotFace` to `UvGenerator`

```kotlin
@OptIn(ExperimentalIsometricApi::class)
@ExperimentalIsometricApi
fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray {
    require(faceIndex in knot.paths.indices) {
        "faceIndex $faceIndex out of bounds for Knot with ${knot.paths.size} faces"
    }
    return when (faceIndex) {
        in 0..17 -> {
            val prismIndex = faceIndex / 6
            val localFaceIndex = faceIndex % 6
            forPrismFace(knot.sourcePrisms[prismIndex], localFaceIndex)
        }
        18, 19 -> quadBboxUvs(knot.paths[faceIndex])
        else -> throw IllegalArgumentException(
            "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
        )
    }
}

@OptIn(ExperimentalIsometricApi::class)
@ExperimentalIsometricApi
fun forAllKnotFaces(knot: Knot): List<FloatArray> =
    knot.paths.indices.map { forKnotFace(knot, it) }

private fun quadBboxUvs(path: Path): FloatArray {
    val pts = path.points
    val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
    val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
    val minZ = pts.minOf { it.z }; val maxZ = pts.maxOf { it.z }

    val spanX = maxX - minX; val spanY = maxY - minY; val spanZ = maxZ - minZ

    val result = FloatArray(8)
    for (i in 0..3) {
        val pt = pts[i]
        val (u, v) = when {
            spanZ <= spanX && spanZ <= spanY ->
                Pair(
                    if (spanX > 0.0) (pt.x - minX) / spanX else 0.0,
                    if (spanY > 0.0) (pt.y - minY) / spanY else 0.0
                )
            spanY <= spanX ->
                Pair(
                    if (spanX > 0.0) (pt.x - minX) / spanX else 0.0,
                    if (spanZ > 0.0) (pt.z - minZ) / spanZ else 0.0
                )
            else ->
                Pair(
                    if (spanY > 0.0) (pt.y - minY) / spanY else 0.0,
                    if (spanZ > 0.0) (pt.z - minZ) / spanZ else 0.0
                )
        }
        result[i * 2]     = u.toFloat()
        result[i * 2 + 1] = v.toFloat()
    }
    return result
}
```

### Step 3 — Wire into `UvCoordProvider.forShape()` factory

```kotlin
@OptIn(ExperimentalIsometricApi::class)
is Knot -> UvCoordProvider { _, faceIndex ->
    UvGenerator.forKnotFace(geometry, faceIndex)
}
```

`@OptIn` is required because `Knot` and `forKnotFace` are `@ExperimentalIsometricApi`.
The `Shape()` composable itself is not experimental — the opt-in is internal.

### Step 4 — `perFace {}` guardrail (no code change)

`ShapeNode.renderTo()` sets `faceType = PrismFace.fromPathIndex(index)` only when
`transformedShape is Prism`. Knot is not a Prism, so `faceType = null` for all Knot
commands. `PerFace.Prism.faceMap[null] ?: default` returns `default` — the documented
fallback behavior. No code change required; only documentation in Step 1.

### Step 5 — Unit Tests

`UvGeneratorKnotTest.kt` covering:
- All 18 sub-prism faces return 8-float arrays
- Face 0 delegates to `forPrismFace(sourcePrisms[0], 0)` — exact match
- Face 6 delegates to `forPrismFace(sourcePrisms[1], 0)`
- Face 12 delegates to `forPrismFace(sourcePrisms[2], 0)`
- Custom quads 18, 19 return 8 floats in `[0,1]`
- `forAllKnotFaces` returns 20 arrays
- Invalid indices throw
- `sourcePrisms` dimensions match `createPaths` constants (regression guard)

### Step 6 — Paparazzi Snapshot Test

`knotTextured()` — document depth-sort bug interaction in test comment so maintainers
don't misinterpret artifacts as UV bugs.

### Step 7 — `apiDump`

```bash
./gradlew :isometric-core:apiDump :isometric-shader:apiDump
```

New entries:
- `isometric-core`: `Knot.sourcePrisms: List<Prism>` (experimental)
- `isometric-shader`: `UvGenerator.forKnotFace(Knot, Int): FloatArray`,
  `UvGenerator.forAllKnotFaces(Knot): List<FloatArray>` (experimental)

## Test / Verification Plan

### Automated

- `UvGeneratorKnotTest` — 9 cases (JVM unit)
- `sourcePrisms` dimension check — regression guard against `createPaths` drift
- Existing `UvGeneratorTest` — regression (`forPrismFace` still works)

### Snapshot

- `knotTextured()` — new Paparazzi golden (Canvas mode)
- Existing `knot()` flat-color snapshot — regression guard

### Interactive

Run sample app with `Shape(Knot(...), material = texturedResource(R.drawable.marble))`.
Verify texture appears on visible faces (depth-sort quirks expected).

## Risks / Watchouts

### Risk 1: Depth-sort bug interaction with UV

Knot's known depth-sort bug means some faces render wrong order. A texture that
appears on the "wrong" face is still UV-correct. Snapshot comment documents this.

### Risk 2: `sourcePrisms` diverging from `createPaths` constants

If someone modifies geometry constants in `createPaths` without updating `sourcePrisms`,
UV generation silently produces incorrect results. Guard: dimension unit test in Step 5.
Add KDoc note to `createPaths`: "If you change these constants, update `sourcePrisms`."

### Risk 3: Custom quad UV non-canonical ordering

Bounding-box projection for paths 18–19 is axis-aligned but does not guarantee canonical
`(0,0),(1,0),(1,1),(0,1)` winding. Test verifies all values in `[0,1]` but not ordering.
Accept non-canonical winding on custom quads — affine mapping is winding-agnostic.

### Risk 4: `@ExperimentalIsometricApi` opt-in propagation

Any file that accesses experimental API needs `@OptIn` or `@file:OptIn`. `apiCheck`
is the backstop — experimental annotated items cannot appear in non-opt-in signatures.

### Risk 5: `perFace {}` silent fallback

User passing `perFace { top = textured(...) }` to `Shape(Knot(...), ...)` compiles and
produces `default` material on all faces. Documented in KDoc.

## Dependencies on Other Slices

| Slice | Type | Provides |
|-------|------|----------|
| `api-design-fixes` | hard | Final `IsometricMaterial` API |
| `uv-generation` | hard | `UvGenerator.forPrismFace`, `UvCoordProvider` |
| `uv-generation-shared-api` | hard | `forShape()` factory, `faceVertexCount`, abstract `PerFace` base |

## Assumptions

1. `uv-generation-shared-api` lands `forShape()` factory as expected.
2. `Knot.position` stays `val` — required for `sourcePrisms` initializer determinism.
3. `UvGenerator` stays `internal object`.
4. `@ExperimentalIsometricApi` remains `@RequiresOptIn(level = WARNING)`.

## Blockers

None.

## Freshness Research

- `Knot.kt` read (2026-04-17): confirmed `createPaths` stores no Prism references.
- `UvGenerator.kt` read: `internal object` with `forPrismFace`. Adding `forKnotFace`
  consistent with pattern.
- `IsometricNode.kt` read: `faceType` set only when `transformedShape is Prism`.
  Confirms `perFace {}` fall-through behavior without code change.
- Bag-of-primitives UV pattern: standard in compound mesh UV generation
  (Blender modifier stack, Three.js merged geometry).
- Kotlin `@RequiresOptIn` pattern: `@OptIn` on implementation + `@ExperimentalIsometricApi`
  on public function is the correct two-annotation pattern.

## Definition of Done

- [ ] `Knot.kt` has `sourcePrisms: List<Prism>` public val with `@ExperimentalIsometricApi`
- [ ] `Knot.kt` KDoc documents `textured()` as the only supported material
- [ ] `UvGenerator.forKnotFace(Knot, Int): FloatArray` added (experimental, `@OptIn` internally)
- [ ] `UvGenerator.forAllKnotFaces(Knot): List<FloatArray>` added
- [ ] `quadBboxUvs` private helper added
- [ ] `IsometricMaterialComposables.kt` `Shape()` resolves `Knot` to `forKnotFace` provider
- [ ] `UvGeneratorKnotTest` — all 9 cases pass on JVM
- [ ] `knotTextured` Paparazzi snapshot recorded
- [ ] Existing `UvGeneratorTest` (Prism) passes
- [ ] Existing `knot` flat-color snapshot passes
- [ ] `apiDump` updated; `apiCheck` passes

## Recommended Next Stage

`/wf-implement texture-material-shaders uv-generation-knot`

Implement: Step 1 → 2 → 3 → 4 (KDoc) → 5 (tests) → 6 (snapshot) → 7 (apiDump).
