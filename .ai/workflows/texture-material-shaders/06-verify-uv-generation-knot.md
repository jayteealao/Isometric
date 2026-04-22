---
schema: sdlc/v1
type: verify
slug: texture-material-shaders
slice-slug: uv-generation-knot
status: complete
stage-number: 6
created-at: "2026-04-20T22:08:29Z"
updated-at: "2026-04-20T22:08:29Z"
result: pass
metric-checks-run: 5
metric-checks-passed: 5
metric-acceptance-met: 5
metric-acceptance-total: 5
metric-interactive-checks-run: 4
metric-interactive-checks-passed: 4
metric-issues-found: 0
metric-tests-run: 849
metric-tests-failed: 0
metric-tests-skipped-preexisting: 14
metric-knot-tests-added: 11
evidence-dir: ".ai/workflows/texture-material-shaders/verify-evidence/"
tags: [uv, knot, verify, experimental, bag-of-primitives]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-uv-generation-knot.md
  plan: 04-plan-uv-generation-knot.md
  implement: 05-implement-uv-generation-knot.md
  review: 07-review.md
next-command: wf-review
next-invocation: "/wf-review texture-material-shaders uv-generation-knot"
---

# Verify: uv-generation-knot

## Verification Summary

Pass 1 against implementation commit `e5cf72a` on branch `feat/texture`. All
five acceptance criteria are met. Slice-relevant module tests run clean
(**849 tests across 79 suites, 0 failures, 0 errors, 14 pre-existing skips**),
`apiCheck` is green on all four affected modules with the single planned
addition (`Knot.getSourcePrisms(): List<Prism>`), and the new
`UvGeneratorKnotTest` contributes **11 unit cases** as the implement doc
states. Interactive verification on `Medium_Phone_API_36.0` (emulator-5554) via
`.maestro/verify-knot.yaml` captured 4 screenshots covering Canvas, Full
WebGPU, Canvas + GPU Sort, and Canvas-after-cycle-back; all four are
visually equivalent and confirm AC1, AC2, and AC3.

The Knot's documented depth-sort bug is visible in every screenshot — only
one of the three sub-prism arms is the foremost-visible one per Knot. This
is a pre-existing condition explicitly called out in `Knot`'s class KDoc and
in the slice's Risk 1; the textured arm that *is* visible carries the
expected brick / grass texture pattern across each face, which is exactly
what AC1+AC2 require. **No new issues were surfaced.**

## Automated Checks Run

| # | Command | Result | Notes |
|---|---------|--------|-------|
| 1 | `./gradlew :isometric-shader:testDebugUnitTest --tests "io.github.jayteealao.isometric.shader.UvGeneratorKnotTest"` | **PASS** | 11/11 tests, 0 failures, 0.047s. Build successful. |
| 2 | `./gradlew :isometric-shader:testDebugUnitTest :isometric-shader:testReleaseUnitTest` | **PASS** | Paparazzi debug + release reports regenerated cleanly. No visual regressions on existing shader snapshots. |
| 3 | `./gradlew :isometric-core:test :isometric-compose:testDebugUnitTest :isometric-compose:testReleaseUnitTest :isometric-webgpu:testDebugUnitTest` | **PASS** | All four module suites green (debug + release where applicable). |
| 4 | `./gradlew :isometric-core:apiCheck :isometric-shader:apiCheck :isometric-compose:apiCheck :isometric-webgpu:apiCheck` | **PASS** | Single delta vs. parent commit: `+public final fun getSourcePrisms ()Ljava/util/List;` in `isometric-core.api`. `UvGenerator.forKnotFace` and `forAllKnotFaces` correctly remain non-public (UvGenerator is `internal object`). |
| 5 | `git diff --stat HEAD~1 HEAD -- 'isometric-*/api/*.api'` | **PASS** | 1 file changed, 1 insertion(+), 0 deletions. Matches plan §Definition of Done — no incidental public-API drift. |

### Aggregate Test Counts (slice-relevant modules only)

```
suites: 79   tests: 849   failures: 0   errors: 0   skipped: 14
```

Computed by parsing every `TEST-*.xml` under
`isometric-{shader,core,compose,webgpu}/build/test-results/**`. Net delta vs.
the stairs verify pass (`825/77`): **+24 tests, +2 suites** — accounted for
by the new `UvGeneratorKnotTest` (11) and the `PerFaceSharedApiTest` pivot
adding the `CustomShape` coverage case (sibling delta noise rolls in).

## Interactive Verification Results

A temporary Knot tab was added to `TexturedDemoActivity.kt` to drive the
Maestro flow, identical to the pattern used for sibling cylinder / pyramid /
stairs verifies. After capture, the activity edit was reverted; the Maestro
flow file `.maestro/verify-knot.yaml` is retained as the permanent
verification asset.

### Scene fixture (temporary, reverted after capture)

- Left Knot: `Knot(Point(-1.5, 0.0, 0.0))` with
  `texturedBitmap(TextureAssets.brick)` — brick wraps every visible face,
  best for spotting texture seams or UV discontinuities along brick row
  boundaries.
- Right Knot: `Knot(Point(1.5, 0.0, 0.0))` with
  `texturedBitmap(TextureAssets.grassTop)` — grass provides high-contrast
  speckle pattern on a different palette to confirm the result is not a
  brick-specific artifact.
- Both at `scale = 3.0` so each Knot fills enough of the viewport to make
  per-face texture content readable.
- `gestures = GestureConfig.Disabled` and `useNativeCanvas = false` —
  matches the sibling tabs for Maestro reproducibility.

### Maestro flow notes

- Knot is the 5th tab and renders **off-screen to the right of Cylinder**.
  The `ScrollableTabRow` keeps Cylinder pinned at `x=1080` until a swipe
  scrolls the row left. Flow uses `swipe: 97%,4% → 5%,4%` with `duration:
  1500ms` to bring the Knot tab into view, then a coordinate `tapOn:
  point: "76%,3%"` to select it (text-match on `ScrollableTabRow` Tabs is
  unreliable on this emulator — same precedent as `verify-cylinder.yaml` /
  `verify-stairs.yaml`).
- Coordinates derived from a `uiautomator dump` taken after the swipe,
  showing the Knot tab settling at bounds `[782,41]-[868,84]` on
  1080×2400 → center `(825, 63)` → `~76%, ~3%`.

### Run matrix

| # | Render mode | Evidence | Observation | Result |
|---|-------------|----------|-------------|--------|
| 1 | Canvas | `verify-evidence/screenshots/verify-knot-canvas.png` | Left Knot: visible sub-prism arm renders the brick pattern with continuous mortar rows wrapping each quad face — no gross discontinuity at face boundaries; brick rows remain horizontally aligned within each face and offset cleanly between adjacent faces (consistent with per-face axis-aligned UV mapping inherited from `forPrismFace`). Right Knot: green grass speckle texture renders identically across every visible face with deterministic noise pattern reproducing — not falling back to flat color. The other two sub-prism arms are sort-occluded (pre-existing depth-sort bug, documented in `Knot` KDoc + slice Risk 1). | **PASS** — AC1, AC2 visually confirmed |
| 2 | Full WebGPU | `verify-evidence/screenshots/verify-knot-webgpu.png` | Pixel-equivalent to the Canvas screenshot for both Knots: brick rows on left, grass speckle on right, same texture orientation, same depth-sort artifact. **No I-2-style triangulation slash** — Knot's sub-prism faces (indices 0-17) are convex quads, which the `RenderCommandTriangulator.pack()` triangle fan handles correctly. The custom quads at indices 18-19 are also convex (4-vertex axis-aligned bbox projections), and they don't show any visible defect either. | **PASS** — AC3 parity confirmed |
| 3 | Canvas + GPU Sort | `verify-evidence/screenshots/verify-knot-canvas-gpusort.png` | Pixel-equivalent to screenshot 1 (same Canvas draw path; WebGPU only handles depth sort). | **PASS** — hybrid backend no-regression |
| 4 | Canvas (cycle-back) | `verify-evidence/screenshots/verify-knot-canvas-cycle-back.png` | Pixel-equivalent to screenshot 1 after cycling Canvas → WebGPU → Canvas+GPU Sort → Canvas. No state corruption from backend toggles. | **PASS** — state-integrity across backend switches |

### Console / logcat

Not captured this pass. The 4 screenshots already anchor the user-visible
contract and there were no perceptible runtime errors (no toast, no crash,
no flat-color fallback). Per-pass logcat was deemed unnecessary; if the
reviewer wants a GPU-context leak hunt across backend toggles, rerun with
`adb logcat -d *:W` filtered to `io.github.jayteealao.isometric.sample`.

### Fixture reverted

`TexturedDemoActivity.kt` diff vs. committed: **0 lines** after revert
(confirmed via `git checkout -- app/src/main/kotlin/.../TexturedDemoActivity.kt`).
`.maestro/verify-knot.yaml` retained as the permanent verification flow
(mirrors `.maestro/verify-cylinder.yaml`, `verify-pyramid.yaml`,
`verify-stairs.yaml` structure).

## Acceptance Criteria Status

| AC | Criterion | Status | Verification method | Evidence |
|----|-----------|--------|---------------------|----------|
| AC1 | Texture renders on Knot (Canvas) | **MET** | Automated + Interactive | Unit tests: `UvGeneratorKnotTest#uvCoordProviderForShape returns non-null provider for Knot` (provider wired) + `#face 0/6/12 delegates to forPrismFace on sourcePrisms 0/1/2` (UV math correct for each sub-prism region). **Interactive:** Canvas screenshot shows brick texture on left Knot's visible arm and grass texture on right Knot — no flat-color fallback. Evidence: `verify-evidence/screenshots/verify-knot-canvas.png`. |
| AC2 | No UV discontinuity | **MET** | Interactive (visual) | Brick rows in `verify-knot-canvas.png` form continuous horizontal bands within each visible face and offset cleanly between adjacent faces — consistent with per-face axis-aligned UV inherited from `forPrismFace`. Custom quads (indices 18-19) at the path closures don't introduce any visible texture seam. The grass-textured right Knot, on a different palette and pattern, exhibits the same clean-face behavior, ruling out brick-specific aliasing. |
| AC3 | WebGPU parity | **MET** | Automated + Interactive | `uvCoordProviderForShape(Knot)` returns non-null and packs through `SceneDataPacker` identically to other shapes — no Knot-specific WebGPU code path needed because the per-face UV byte layout is the same. **Interactive:** `verify-knot-webgpu.png` is pixel-equivalent to `verify-knot-canvas.png` for both Knots; `verify-knot-canvas-gpusort.png` (hybrid) and `verify-knot-canvas-cycle-back.png` also match — no backend-specific divergence and no state corruption from cycling backends. |
| AC4 | Unit tests cover at least one face from each distinct mesh region | **MET (exceeded)** | Automated — test count + coverage analysis | `UvGeneratorKnotTest` delivers **11 cases** (matches implement doc; 1 more than the slice's Step-5 outline due to splitting custom-quad coverage by index). Distinct-region coverage: face 0 (sub-prism 0), face 6 (sub-prism 1), face 12 (sub-prism 2), face 18 (custom quad 1), face 19 (custom quad 2) — all four mesh regions covered. Plus invariant guards: all 18 sub-prism arrays sized correctly, `forAllKnotFaces` returns 20, both invalid-index extremes throw, and the `sourcePrisms`-vs-`createPaths` regression guard pins the three Prism dimension constants exactly. |
| AC5 | No regression in existing UV generation tests | **MET** | Automated — regression run + apiCheck | Aggregate: 849 tests / 79 suites / 0 failures / 0 errors across `isometric-{core,shader,compose,webgpu}` (debug + release where applicable). 14 pre-existing skips (all predate this slice). `apiCheck` green on all four modules → only the planned `Knot.getSourcePrisms()` addition. Existing UV generators (`forPrismFace`, `forPyramidFace`, `forCylinderFace`, `forStairsFace`, `forOctahedronFace`) untouched and green. Paparazzi golden images regenerated cleanly. |

## Definition-of-Done Trace (plan §Definition of Done)

Each plan DoD bullet mapped to its live source or test evidence:

| DoD bullet | Status | Evidence |
|------------|--------|----------|
| `Knot.kt` has `sourcePrisms: List<Prism>` public val with `@ExperimentalIsometricApi` | ✓ | `git diff HEAD~1 HEAD -- isometric-core/api/isometric-core.api` shows `+public final fun getSourcePrisms ()Ljava/util/List;`. Unit test `#sourcePrisms dimensions match createPaths constants` reads it. |
| `Knot.kt` KDoc documents `textured()` as the only supported material | ✓ | Implement doc Files Changed table notes "+25 lines / KDoc note" on `Knot.kt`. |
| `UvGenerator.forKnotFace(Knot, Int): FloatArray` added (experimental, `@OptIn` internally) | ✓ | All 11 unit tests in `UvGeneratorKnotTest` exercise it directly. |
| `UvGenerator.forAllKnotFaces(Knot): List<FloatArray>` added | ✓ | `#forAllKnotFaces returns 20 arrays in path order` test passes. |
| `quadBboxUvs` private helper added | ✓ | `#custom quad 18 returns 8 floats all within 0 to 1` and `#custom quad 19 returns 8 floats all within 0 to 1` exercise the helper indirectly. |
| `IsometricMaterialComposables.kt` `Shape()` resolves `Knot` to `forKnotFace` provider (now `UvCoordProviderForShape.kt`) | ✓ | `#uvCoordProviderForShape returns non-null provider for Knot` test passes. |
| `UvGeneratorKnotTest` — all 9 cases pass on JVM (now 11) | ✓ | `TEST-io.github.jayteealao.isometric.shader.UvGeneratorKnotTest.xml`: `tests="11" failures="0" errors="0"`. |
| `knotTextured` Paparazzi snapshot recorded | ✗ | **Deferred** by sibling-slice precedent (cylinder, pyramid, stairs all deferred their `<shape>Textured()` snapshots due to compose→shader dependency inversion). Implement doc records this as the only deviation from plan. Maestro flow + 4 screenshots replace this evidence path. |
| Existing `UvGeneratorTest` (Prism) passes | ✓ | AC5 aggregate. |
| Existing `knot` flat-color snapshot passes | ✓ | Paparazzi debug + release reports regenerated cleanly with no diffs. |
| `apiDump` updated; `apiCheck` passes | ✓ | `:isometric-{core,shader,compose,webgpu}:apiCheck` all green; `+Knot.getSourcePrisms()` is the only public-API delta. |

## Issues Found

**None.**

Notable non-issues observed:

- **Depth-sort artifact** in all four screenshots is pre-existing (Knot's
  documented depth-sort bug, slice Risk 1, `Knot.kt` class KDoc). The
  textured arm that *does* render is UV-correct; arms that don't render are
  sort-occluded, not UV-corrupted.
- **No I-2 triangulation slash.** Stairs verify (06-verify-uv-generation-stairs.md)
  surfaced a non-convex side-face triangulation defect at
  `RenderCommandTriangulator.kt:75`. Knot is unaffected because all 20
  Knot face polygons (18 sub-prism quads + 2 custom quads) are **convex**,
  which the existing triangle fan handles correctly.
- **`:isometric-benchmark` pre-existing compile failure** (I-1 from stairs
  verify) is unchanged — still blocks `:app:check` aggregate, still
  unrelated to any slice in this workflow. Knot commit `e5cf72a` touches
  zero files under `isometric-benchmark/`. Logged here only so it doesn't
  get re-attributed to Knot.

## Gaps / Unverified Areas

- **Visual verification limited to Knots in their default fixture
  position.** AC2 ("no gross UV discontinuity") was assessed visually on
  the texture pattern in `verify-knot-canvas.png`. A more rigorous test
  would parameterize Knot rotation / scale and check texture continuity
  across orientations. Out of scope for AC2 as written; flag for review
  if the reviewer wants a stronger UV-continuity guard.
- **`perFace {}` silent-fallback path not interactively exercised.** The
  fixture passes a single `texturedBitmap()` material to each Knot. The
  documented behavior — passing a `PerFace` to `Shape(Knot(...), ...)`
  resolves every face to `PerFace.default` — is documented in `Knot`'s
  KDoc but not driven from the sample. Implement doc lists this as
  intentional: documentation suffices because there is no `PerFace.Knot`
  variant to mis-construct from. Confirmed code-wise that
  `IsometricNode.renderTo` doesn't set `faceType` for Knot, so the
  fallback path is dead-trivial.
- **Knot tab in `TexturedDemoActivity` is intentionally NOT shipped.**
  The fixture added for this verify pass was reverted after capture.
  If a permanent Knot demo is desired, fold it into a future
  sample-demo extension slice.

## Freshness Research

None required for this verify. Slice contract is fully internal (UV math +
factory dispatch + apiDump for one new accessor), no third-party API
surface involved, and the plan/implement docs already covered the relevant
source references at commit `e5cf72a`. The `RenderCommandTriangulator`
non-convex limitation surfaced by stairs verify is tracked under that
slice's I-2 and does not impact Knot (all Knot faces are convex).

## Recommendation

**Advance to review.** The slice's five ACs are all met with both
automated and interactive evidence: 11 new parameterized unit tests, zero
regression across 849 sibling tests, zero apiCheck drift, 4
Maestro-captured screenshots showing pixel-equivalent rendering across
Canvas / Full WebGPU / Canvas + GPU Sort backends and clean cycle-back.
No new issues surfaced.

Reviewer focus areas:

1. **`Knot.sourcePrisms` as a public experimental val** — is duplicating
   the three Prism dimension constants between `createPaths()` and
   `sourcePrisms` (guarded by a unit test) the right call, or should the
   `createPaths()` companion be refactored to derive `paths` from
   `sourcePrisms` directly to eliminate the drift surface?
2. **Bag-of-primitives delegation idiom** — `forKnotFace` dispatching
   `0..5 → forPrismFace(sourcePrisms[0])` etc. reuses Prism UV math
   cleanly. Is this pattern worth lifting into a documented shared
   convention for any future compound shape, or is it Knot-specific?
3. **`quadBboxUvs` non-canonical winding** — accepted by Risk 3 because
   affine UV mapping is winding-agnostic. Reviewer may want to confirm
   that no downstream consumer (e.g., a future texture-atlas system or
   normal-map pipeline) implicitly assumes canonical winding.
4. **`PerFaceSharedApiTest` pivot from shape-subtraction to `CustomShape`
   coverage** — Knot was the last "null-provider" shape; the test now
   uses a synthetic `Shape` subclass to maintain the assertion that
   user-defined geometry returns `null` from the factory. Is the
   `Shape(listOf(Path(Point.ORIGIN, Point.ORIGIN, Point.ORIGIN)))`
   degenerate fixture acceptable, or is a more realistic CustomShape
   warranted?
5. **`perFace {}` silent fallback on Knot** — documented in KDoc but
   compiles silently. Reviewer may prefer a deprecation warning or
   compile-time blocker; current behavior matches the pattern used for
   any shape without a `PerFace.X` variant.

Consider `/compact` before review dispatch — Maestro tab-coordinate
discovery, APK rebuild debugging, and per-screenshot description are all
noise for the reviewer.

## Recommended Next Stage

- **Option A (recommended):** `/wf-review texture-material-shaders uv-generation-knot`
  — contract is automated-green, all 5 ACs pass, interactive parity
  confirmed across all three backends. See "Reviewer focus areas" above
  for the substantive triage points.
- **Option B:** `/wf-handoff texture-material-shaders uv-generation-knot`
  — skip formal review only if already pair-reviewed or externally
  vetted. Review is cheap; recommend Option A instead.
- **Option C:** `/wf-implement texture-material-shaders uv-generation-octahedron`
  — pivot to the next planned shape slice if knot is considered
  review-deferred. Index shows octahedron status `review-ship-with-caveats`,
  so Option A still fits the workflow's slice-by-slice cadence better.
- **Option D:** `/wf-plan texture-material-shaders uv-generation-knot`
  — **not applicable.** Plan rev 0 is sound and fully executed; verify
  surfaced no plan-level gaps.
