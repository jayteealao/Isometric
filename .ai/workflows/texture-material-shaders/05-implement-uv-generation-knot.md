---
schema: sdlc/v1
type: implement
slug: texture-material-shaders
slice-slug: uv-generation-knot
status: complete
stage-number: 5
created-at: "2026-04-20T21:02:21Z"
updated-at: "2026-04-20T21:02:21Z"
metric-files-changed: 6
metric-lines-added: 281
metric-lines-removed: 11
metric-deviations-from-plan: 1
metric-review-fixes-applied: 0
commit-sha: "e5cf72a"
tags: [uv, texture, knot, uv-generation, experimental, bag-of-primitives]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-uv-generation-knot.md
  plan: 04-plan-uv-generation-knot.md
  siblings:
    - 05-implement-uv-generation-shared-api.md
    - 05-implement-uv-generation-pyramid.md
    - 05-implement-uv-generation-cylinder.md
    - 05-implement-uv-generation-stairs.md
    - 05-implement-uv-generation-octahedron.md
  verify: 06-verify-uv-generation-knot.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders uv-generation-knot"
---

# Implement: uv-generation-knot

## Summary of Changes

Knot — an experimental compound shape built from three nested `Prism`s plus two
custom closing quads — now supports `textured()` materials end-to-end via
`UvGenerator.forKnotFace`. The implementation is a bag-of-primitives
decomposition: the 18 sub-prism faces delegate to the already-tested
`UvGenerator.forPrismFace` using three newly-exposed `Knot.sourcePrisms`, while
the two custom quads at indices 18–19 use axis-aligned bounding-box planar
projection.

Key design points:

1. **`Knot.sourcePrisms` as a first-class public val.** The companion's
   `createPaths()` previously built the three source Prisms as throwaway
   locals, then scaled + translated their `paths` out — the Prism dimensions
   were unrecoverable from `knot.paths` at UV-generation time. Exposing
   `sourcePrisms` (annotated `@ExperimentalIsometricApi`, same opt-in as `Knot`)
   makes the invariant explicit and lets UV generation consume the pre-transform
   dimensions directly. The values must stay in lock-step with the constants in
   `createPaths`; a regression guard unit test pins this.
2. **`UvGenerator.forKnotFace(Knot, Int)`** — dispatches by face range:
   - `0..5`   → `forPrismFace(sourcePrisms[0], faceIndex)`
   - `6..11`  → `forPrismFace(sourcePrisms[1], faceIndex - 6)`
   - `12..17` → `forPrismFace(sourcePrisms[2], faceIndex - 12)`
   - `18..19` → `quadBboxUvs(knot.paths[faceIndex])`
   plus `forAllKnotFaces(Knot)` returning 20 FloatArrays. Both are
   `@OptIn(ExperimentalIsometricApi::class) @ExperimentalIsometricApi`,
   preserving the experimental contract of `Knot` itself.
3. **`quadBboxUvs` private helper.** Projects a 4-vertex path onto the two
   largest-extent axes (reducing the 3D bounding box to a 2D UV plane).
   Degenerate spans collapse to 0 to avoid divide-by-zero. Winding order is
   source-preserved — may not be canonical `(0,0)(1,0)(1,1)(0,1)`, accepted as
   documented because affine UV mapping is winding-agnostic.
4. **`uvCoordProviderForShape` — `is Knot` branch added.** The factory now has
   a non-null provider for every stock shape (Prism, Octahedron, Pyramid,
   Cylinder, Stairs, Knot); only user-defined `Shape` subclasses fall through
   to `else -> null`. File-level `@OptIn(ExperimentalIsometricApi::class)` added
   for the Knot branch. The KDoc `## Extension` section (which listed
   `uv-generation-knot → is Knot` as a future hook) is replaced with a prose
   paragraph describing the current terminal state.
5. **`perFace {}` guardrail — documentation only.** `IsometricNode.renderTo`
   sets `faceType` only for `Prism`, `Pyramid`, `Cylinder`, and `Stairs`. Knot
   is not in that dispatch, so `faceType = null` for all Knot commands, which
   in turn routes `PerFace.faceMap[null]` through the documented `default`
   fallback. No code change required — Knot's class-level KDoc now documents
   that `perFace {}` resolves every face to `PerFace.default`.
6. **Test suite updated.** `PerFaceSharedApiTest` previously asserted
   `uvCoordProviderForShape(Knot()) == null` — that assumption is exactly what
   this slice inverts. Replaced with (a) a positive `Knot` provider test
   mirroring the Prism template and (b) a `CustomShape : Shape(listOf(Path(...)))`
   local class to cover the remaining `else -> null` branch for user-defined
   Shape subclasses.
7. **`UvGeneratorKnotTest` — 11 new cases** covering provider non-null,
   sub-prism array sizes, delegation identity for face 0/6/12, bbox
   projection output range on custom quads, `forAllKnotFaces` size, invalid
   index rejection at both ends, and the `sourcePrisms`-vs-`createPaths`
   regression guard.

## Files Changed

| File | +/− | Action |
|------|-----|--------|
| `isometric-core/src/main/kotlin/.../shapes/Knot.kt` | +25 | MODIFY — `sourcePrisms` val, KDoc note, drift-warning comment |
| `isometric-shader/src/main/kotlin/.../UvGenerator.kt` | +92 | MODIFY — `forKnotFace`, `forAllKnotFaces`, `quadBboxUvs`, imports |
| `isometric-shader/src/main/kotlin/.../UvCoordProviderForShape.kt` | +8 / −7 | MODIFY — `is Knot` branch, `@OptIn`, KDoc prose update, imports |
| `isometric-shader/src/test/kotlin/.../PerFaceSharedApiTest.kt` | +16 / −4 | MODIFY — positive Knot test, replace null-for-Knot with CustomShape |
| `isometric-shader/src/test/kotlin/.../UvGeneratorKnotTest.kt` | +139 | CREATE — 11 unit cases |
| `isometric-core/api/isometric-core.api` | +1 | UPDATE (apiDump) — `Knot.getSourcePrisms()` |

`isometric-shader/api/isometric-shader.api` was not modified — `UvGenerator` is
`internal object`, so `forKnotFace` / `forAllKnotFaces` do not leak into the
public surface. This matches the sibling stairs/cylinder/pyramid slices.

## Shared Files (also touched by sibling slices)

- `UvGenerator.kt` — grown across all five UV slices; this slice only appends
  a new section, no changes to existing Prism/Pyramid/Cylinder/Stairs/Octahedron
  generators or caches.
- `UvCoordProviderForShape.kt` — each shape slice adds one `when` arm here.
  Knot's arm is the last addition — the "Extension" KDoc section is now empty
  and rewritten into prose.
- `PerFaceSharedApiTest.kt` — the "null for shapes without per-face UV support"
  test has been maintained by sibling slices as each new shape graduated. With
  Knot now covered, the test switches from "shape enum" to "generic Shape
  subclass" to remain meaningful.

## Notes on Design Choices

- **Bag-of-primitives delegation vs. recomputing.** The plan considered doing
  the UV math from scratch for each Knot face, but the three sub-prism blocks
  are geometrically identical to real Prisms in their own local coordinate
  space — delegating to `forPrismFace` reuses 95% of the existing, verified UV
  math and automatically inherits any future Prism UV refinements.
- **Two-annotation opt-in pattern.** `forKnotFace` carries both
  `@OptIn(ExperimentalIsometricApi::class)` (to consume `Knot` itself) and
  `@ExperimentalIsometricApi` (to propagate the requirement to callers).
  `uvCoordProviderForShape` uses a file-level `@OptIn` because the compiler
  required the opt-in at the `is Knot` arm.
- **`perFace {}` as a silent-fallback.** Rather than fail-fast when a caller
  passes `PerFace` to `Shape(Knot(...), material)`, the existing
  `PerFace.faceMap[null] ?: default` plumbing already handles this cleanly —
  every face resolves to `default`. Documented in `Knot`'s KDoc; no new guard
  code.
- **Non-canonical winding on custom quads.** `quadBboxUvs` picks the two
  largest-extent axes and projects in source order. For indices 18–19 this
  produces a correct planar mapping but not a guaranteed
  `(0,0)(1,0)(1,1)(0,1)` winding. Test coverage asserts range-in-`[0,1]` only,
  matching the plan's Risk 3 decision.

## Deviations from Plan

1. **`knotTextured()` Paparazzi snapshot deferred.** Sibling slices (pyramid,
   cylinder, stairs, octahedron) all deferred their `<shape>Textured()`
   Paparazzi snapshots — compose-→-shader dependency-inversion blocks the
   `IsometricMaterial.Textured` call site from the snapshot module. Plan Step 6
   proposed adding one, but the precedent is to defer. Canvas/WebGPU visual
   verification is handled in the `06-verify` stage through interactive sample
   runs and Maestro flows. Metric-deviations-from-plan: 1.

## Anything Deferred

- **Maestro visual flow (`.maestro/verify-knot.yaml`).** Sibling shapes
  received a Maestro flow during the verify stage — Knot can follow the same
  pattern at `/wf-verify`.
- **`TexturedDemoActivity` Knot tab.** Optional; belongs with verify rather
  than implement. The existing `TexturedDemoActivity` has Prism/Pyramid/
  Cylinder tabs; adding a Knot tab there would be a small follow-on.

## Known Risks / Caveats

- **Depth-sort bug interaction with UV.** `Knot` carries a documented
  depth-sorting issue — faces may render in incorrect order. Any visual
  glitches observed during verify should first be ruled against the depth-sort
  bug before attributing them to UV generation. This is a pre-existing
  condition unrelated to this slice.
- **`sourcePrisms` drift.** The three Prism constants are now duplicated
  between `Knot.createPaths()` and `Knot.sourcePrisms`. A unit test regression
  guard (`sourcePrisms dimensions match createPaths constants`) pins this; the
  companion carries an explanatory comment directing editors to update both
  sites.
- **`perFace { }` silent fallback.** Passing a `PerFace` material to
  `Shape(Knot(...), material)` compiles and runs, but every face resolves to
  `PerFace.default`. Documented in `Knot` KDoc; no runtime error.

## Freshness Research

- `UvGenerator.kt` current state (read 2026-04-20): five UV generators already
  present with consistent shape: public `forXFace`/`forAllXFaces` pair,
  optional private caches, single-responsibility per shape. New Knot
  generator matches the pattern exactly.
- `PerFaceSharedApiTest.kt` (read 2026-04-20): the
  `uvCoordProviderForShape returns null for shapes without per-face UV support`
  test has been maintained across slices by dropping the graduating shape
  (Octahedron → Pyramid → Cylinder → Stairs). With Knot graduating, the
  pattern inverts — we shift from shape-subtraction to generic-Shape coverage.
- `Shape.kt` (inspected via stack trace): `Shape` constructor rejects empty
  path lists (`require`d at construction). The `CustomShape` fixture uses a
  single degenerate `Path(Point.ORIGIN, Point.ORIGIN, Point.ORIGIN)` to
  satisfy the constraint without exercising geometry.
- Kotlin `@RequiresOptIn` (Kotlin 1.8+ docs, April 2026): the two-annotation
  pattern (`@OptIn` to consume + propagate annotation to continue raising the
  flag) remains the canonical idiom. No changes since `uv-generation-stairs`
  landed.

## Recommended Next Stage

- **Option A (default):** `/wf-verify texture-material-shaders uv-generation-knot`
  — confirm AC1–AC5 for the knot slice. 11 new shader unit tests + passing
  sibling test suites give strong automated coverage; Canvas AC1/AC2 and
  WebGPU AC3 parity need interactive sample-run or Maestro confirmation.
  Consider `/compact` first — implementation-pass context (test-update
  debugging, CustomShape fixture discovery) is noise for verify.
- **Option B:** `/wf-review texture-material-shaders uv-generation-knot` —
  skip verify if 11 green tests + `apiCheck` + four-module test suite is
  sufficient evidence. Review focus: the bag-of-primitives delegation idiom,
  `sourcePrisms` drift-guard design, `quadBboxUvs` non-canonical-winding
  acceptance, and the `PerFaceSharedApiTest` pivot from shape-subtraction to
  CustomShape coverage.
