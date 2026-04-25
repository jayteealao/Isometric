---
schema: sdlc/v1
type: verify-index
slug: texture-material-shaders
status: in-progress
stage-number: 6
created-at: "2026-04-11T23:44:32Z"
updated-at: "2026-04-22T23:07:41Z"
slices-verified: 16
slices-total: 16
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
next-command: wf-review
next-invocation: "/wf-implement texture-material-shaders webgpu-ngon-faces"
---

# Verify Index

## Slices Verified

### `material-types` — PASS
- Checks: 5/5 passed
- Acceptance: 3/3 met
- Issues: 0
- Record: [06-verify-material-types.md](06-verify-material-types.md)

### `uv-generation` — PASS
- Checks: 4/4 passed
- Acceptance: 4/4 met
- Issues: 0
- Record: [06-verify-uv-generation.md](06-verify-uv-generation.md)

### `canvas-textures` — PASS (after VF-1 fix)
- Checks: 6/6 passed (build, tests, apiCheck — pre and post fix)
- Acceptance: 4/4 met
- Interactive: 2 runs (1 fail pre-fix, 1 pass post-fix) — checkerboard texture visible on device
- Issues: 1 found (VF-1 HIGH: material data dropped in pipeline + hook missing on default path) — **FIXED**
- Evidence: `verify-evidence/verify-textured-fixed.png`
- Record: [06-verify-canvas-textures.md](06-verify-canvas-textures.md)

### `webgpu-textures` — PARTIAL (automated pass, interactive pending)
- Checks: 4/4 passed (build, tests, apiCheck, 7-invariant static shader review)
- Acceptance: 0/4 met (all require on-device visual verification)
- Interactive: 0 runs — requires Android device + textured WebGPU sample scene
- Issues: 0
- Record: [06-verify-webgpu-textures.md](06-verify-webgpu-textures.md)

### `per-face-materials` — PARTIAL (automated pass, visual deferred, post-review-fix)
- Checks: 3/3 passed (build, tests, apiCheck) — re-verified after 8 review fixes
- Acceptance: 1/3 met (AC3 via unit tests), 2/3 deferred (AC1, AC2 need sample scene)
- Interactive: 0 runs — sample app lacks perFace {} scene (sample-demo slice)
- Review fixes: 8/8 applied and verified (PF-1 through PF-9)
- Issues: 0
- Record: [06-verify-per-face-materials.md](06-verify-per-face-materials.md)

### `sample-demo` — PASS (after 4 bugfixes)
- Checks: 3/3 passed (build, tests, apiCheck)
- Acceptance: 3/3 met
- Interactive: 4 runs (Canvas, Canvas+GPU Sort, Full WebGPU, mode cycling) — all pass
- Issues: 4 found (VF-1 through VF-4) — all fixed and committed
- Evidence: `verify-evidence/v-canvas-final-sm.png`, `verify-evidence/v-webgpu-uv-sm.png`
- Record: [06-verify-sample-demo.md](06-verify-sample-demo.md)

### `api-design-fixes` — PASS Round 3 (post-review-fixes-round2, all ACs met)
- Checks: 3/3 passed (build, apiCheck, unit tests 75/75 + 7 @Ignored)
- Acceptance: 13/13 met (AC4 accepted per review triage: two Shape() overloads intentional)
- Interactive: 0 runs (AC1 tiling deferred to device)
- Issues: 0 (all Round 2 findings fixed; 4 in-verify corrections applied cleanly)
- Record: [06-verify-api-design-fixes.md](06-verify-api-design-fixes.md)

### `webgpu-uv-transforms` — PARTIAL (4/6 ACs met on device; AC2/AC3 unit-test only)
- Checks: 4/4 passed (build, test 22/22 pre- and post-fix)
- Acceptance: 4/6 fully met (AC1 tiling ✓, AC4 per-face ✓, AC5 IDENTITY ✓, AC6 automated ✓); 2/6 partial (AC2 offset, AC3 rotation — math only)
- Interactive: 7 screenshots — IDENTITY (3 modes) + tiling-fix (Canvas, WebGPU, Canvas+GPU Sort, cycle-back) all PASS
- Issues: 2 found and fixed — atlas tiling bug (HIGH, `fract()` + UvRegion) + no tiling demo (LOW, extended demo)
- Evidence: `verify-evidence/tiling_fix_canvas_baseline.png`, `verify-evidence/tiling_fix_webgpu.png`, `verify-evidence/tiling_fix_canvas_gpu_sort.png`
- Record: [06-verify-webgpu-uv-transforms.md](06-verify-webgpu-uv-transforms.md)

### `webgpu-texture-error-callback` — PASS (all T-01/T-02/T-05 resolved)
- Checks: build ✓, 31 JVM unit tests ✓, 2 instrumented tests ✓ (T-02/T-05), apiCheck ✓ — commit `f8c1cc7`
- Acceptance: 4/4 met (AC1 code-inspect + Pass 1 logcat, AC2 impl review, AC3 null-check, AC4 tests pass)
- Interactive: Pass 1 device run preserved (AC1 logcat ✓, AC3 Maestro 18-step ✓)
- Issues: 0
- Gaps: T-03/T-04 CompositionLocal stubs remain — not blocking
- Record: [06-verify-webgpu-texture-error-callback.md](06-verify-webgpu-texture-error-callback.md)

### `uv-generation-shared-api` — PASS (pass 3: all 7 ACs met; I-01 fix verified)
- Three verify passes: pass 1 `2026-04-17T12:34Z` (pass, in-verify VF-1 fix), pass 2 `17:04Z` (partial, I-01 surfaced), pass 3 `17:19Z` (pass, post-I-01 fix commit `192f586`).
- Checks: 5/5 passed (compile, apiCheck ×4 modules, core 204/204, shader 104/104 incl. PerFaceSharedApiTest 22/22, consumers compose 93/93 + webgpu 32/32, android-view NO-SOURCE). 433 unit tests, 0 failures.
- Acceptance: 7/7 met (AC1–AC7; L-05 round-trip now passes via shared-Path helper).
- Interactive: 0 runs this pass (AC6 covered by Paparazzi 15/15 golden snapshots; prior pass 1 recorded 3 passing device runs; review-fix and I-01 fix deltas did not touch user-visible render paths).
- Issues: 0 outstanding (I-01 resolved in `192f586`; 13 pass-2 deferred review findings remain pre-triaged for downstream shape slices).
- Record: [06-verify-uv-generation-shared-api.md](06-verify-uv-generation-shared-api.md)

### `uv-generation-octahedron` — PARTIAL (2/5 ACs met, 1/5 split, 2/5 not met; I-02 surfaced)
- Two verify passes: pass 1 `2026-04-17T18:18Z` (automated only; AC1/AC3 deferred), pass 2 `18:36Z` (interactive on `emulator-5554`).
- Checks: 4/4 passed (442 tests green across 4 modules; zero `.api` diff).
- Acceptance: AC2, AC4, AC5 MET via unit + apiCheck + Paparazzi. AC1 SPLIT — passes WebGPU (grass texture visibly renders on all 8 faces), fails Canvas (renders WHITE). AC3 NOT MET — stark Canvas/WebGPU divergence as direct consequence.
- Interactive: 2 runs on `emulator-5554` with temporary sample-app fixture (reverted after capture). Evidence: `verify-octahedron-canvas.png`, `verify-octahedron-webgpu.png`.
- Issues: **1 HIGH (I-02)** — `faceVertexCount` drops through `SceneGraph.SceneItem` + both `IsometricEngine.projectScene()` RenderCommand reconstruction sites. Canvas hook's UV-size gate (`2 * faceVertexCount = 8`) rejects Octahedron's 6-float UV, falling through to `Textured.tint = WHITE`. Shared-api scope (5 sites in isometric-core + 1 in isometric-compose). Will also block the remaining 4 non-quad shape slices (pyramid/cylinder/stairs/knot).
- Record: [06-verify-uv-generation-octahedron.md](06-verify-uv-generation-octahedron.md)

### `uv-generation-pyramid` — PASS on pass 3 (5/5 ACs met; 3/3 BLOCKERs + 6/12 HIGHs verified; commit `f8ec440`)
- Three verify passes: pass 1 `2026-04-17T22:50Z` (**FAIL**, surfaced I-03), pass 2 `2026-04-17T23:41Z` (**PASS**, post-I-03-fix `b411de9`), pass 3 `2026-04-18T12:05Z` (**PASS**, post-review-fix `f8ec440`).
- **Pass 3 checks:** 6/6 passed. 476 unit tests / 0 failures / 0 errors / 7 skipped (core 204 + shader 136+7 + compose 102 + webgpu 34; +10 over pass-2's 466 from BL-2 SceneDataPackerTest +2, BL-3 TexturedCanvasDrawHookColorTest +7, H-5 UvGeneratorPyramidTest identity-cache +1). apiCheck green on all 4 modules (BL-1 rename + new `octahedronPerFace` surface intentional). `:app:assembleDebug` green.
- **Pass 3 acceptance:** AC1, AC2 (with camera-visibility caveat), AC3, AC4, AC5 — all MET.
- **Pass 3 interactive:** 4 Maestro runs on `emulator-5554` via `.maestro/verify-pyramid.yaml` — Canvas, Full WebGPU, Canvas+GPU Sort, and Canvas cycle-back all render correctly and pixel-equivalently. Left pyramid shows green speckled grass texture; right pyramid shows bright blue (LATERAL_2) + bright red (LATERAL_0). Zero logcat warnings. Evidence: `verify-evidence/screenshots/verify-pyramid-{canvas,webgpu,canvas-gpusort,canvas-cycle-back}-post-review-fix.png`.
- **Pass 3 review-fix verification:** 3/3 BLOCKERs verified structurally (BL-1 DSL rename surface in `.api`; BL-2 `resolveEffectiveColor` + 2 new test cases; BL-3 `IsoColor.toAndroidArgbInt` JVM-testable extension + 7 new test cases). 6/12 HIGHs verified structurally (H-1 dead-arm removal, H-4 no-copyOf hot path, H-5 base-UV identity cache, H-7 `SceneGraph.add` `faceVertexCount` derivation, H-9 Pyramid KDoc path layout, H-12 CHANGELOG Canvas IsoColor entry). 6/12 HIGHs deferred to follow-up architectural / test-hardening slices per user triage (H-2, H-3, H-6, H-8, H-10, H-11).
- **Pass 3 issues:** 0 new. I-03 and review BLOCKERs all resolved.
- Record: [06-verify-uv-generation-pyramid.md](06-verify-uv-generation-pyramid.md) (pass-3 body plus archival pass-1 FAIL + pass-2 PASS summaries).

### `uv-generation-cylinder` — PASS (after API-01 review-BLOCKER fix, pass 3)
- Checks: **4/4 passed in pass 3** (pass 1: 3/4; pass 2: 4/4; pass 3: 4/4 byte-identical to pass 2). `:isometric-core:test` ✓ (`compileKotlin` UP-TO-DATE — KDoc-only edit, no bytecode change), shader/compose/webgpu tests ✓ (all UP-TO-DATE), `apiCheck` ✓ (no `.api` diff), `:app:assembleDebug` ✓. Aggregate across 4 modules: **501/501 tests green, 7 skipped, 0 failures** — identical to pass 2.
- Acceptance: AC1 (seam wrap) MET, AC2 (caps) MET Canvas with documented WebGPU cap-truncation caveat, AC3 (per-face) MET, AC4 (unit tests) MET (18/18 UvGeneratorCylinderTest), AC5 (no Prism regression) **MET unconditional**.
- Interactive: **8/8 (pass 1 + pass 2)** — not re-run on pass 3. Justification: API-01 fix is KDoc-only at `CylinderFace.fromPathIndex`; zero `.class` diff (compileKotlin UP-TO-DATE); cannot alter rendered pixels. Pass-2 evidence preserved and authoritative for AC1–AC3.
- Review-fix verified: **API-01 (BLOCKER)** — `**Breaking change:**` KDoc paragraph added at `CylinderFace.kt:27-32` (commit `f98a982`), documenting the inverted prior mapping. No code change; `apiCheck` green; no bytecode diff; no test regression. 18 deferred findings (5 HIGH + 13 MED) remain deferred per user triage.
- Issues: **0 in pass 3.** Historical: VF-1 (MEDIUM) resolved pass 2, API-01 (BLOCKER) resolved pass 3.
- Evidence: `verify-evidence/screenshots/verify-cylinder-{canvas,webgpu,canvas-gpusort,canvas-cycle-back}{,-pass2}.png` (pass 1 + pass 2).
- Record: [06-verify-uv-generation-cylinder.md](06-verify-uv-generation-cylinder.md) (pass-3 body plus archival pass-1/pass-2 summaries).

### `uv-generation-stairs` — PASS (all 6 ACs met on pass 1 with full interactive evidence; 1 NEW issue surfaced by visual diff)
- Checks: **6/6 passed** — targeted `UvGeneratorStairsTest` (15/15 tests, 0 failures, 0.044s), shader debug + release variants, core test, compose debug + release, webgpu test, apiCheck on all four slice modules (zero `.api` diff — `UvGenerator` stays `internal`). Aggregate across slice-relevant modules: **825 tests / 77 suites / 0 failures / 0 errors / 14 pre-existing skips**.
- Acceptance: 6/6 met. AC1 (tread canonical quad), AC2 (riser canonical quad), AC3 (per-face addressing via `PerFace.Stairs` + `resolveForFace`), AC4 (WebGPU parity for TREAD + RISER — AC is scoped to those two face types and they render pixel-clean; side-face non-convex artifact is tracked as I-2 below), AC5 (15 tests, +2 over plan's stated 13), AC6 (825-test regression clean + apiCheck green).
- Interactive: **4 Maestro runs on `Medium_Phone_API_36.0` (emulator-5554)** via new `.maestro/verify-stairs.yaml` — Canvas, Full WebGPU, Canvas + GPU Sort, Canvas cycle-back. Temporary Stairs tab added to `TexturedDemoActivity.kt` (stepCount=2), captured, then fixture reverted (0-line diff vs. committed). Pixel diff confirmed: Canvas↔Canvas-cycle-back 0 diff, Canvas↔Canvas+GPU-Sort differ only in toggle-pill UI (y=480..575), Canvas↔WebGPU differ in scene region. First Maestro attempt failed on `tapOn: "Stairs"` (text-match on `ScrollableTabRow` Tab doesn't route onClick — same bug `verify-cylinder.yaml` documents); fixed with coordinate-point tap at `"76%,3%"`. Evidence: `verify-evidence/screenshots/verify-stairs-{canvas,webgpu,canvas-gpusort,canvas-cycle-back}.png` + `verify-evidence/diffs/side-face-triptych.png`.
- Issues: **1 NEW (I-2, MEDIUM)** + **1 PRE-EXISTING (I-1, out-of-scope)**.
  - **I-2 — WebGPU side-face non-convex triangulation artifact (NEW).** Full WebGPU renders the Stairs zigzag side face with a diagonal slash across the step-notch; Canvas renders correctly. Root cause identified at `isometric-webgpu/.../triangulation/RenderCommandTriangulator.kt:75` — triangle fan `for (i in 1 until pointCount - 1)` emits `(v0, v[i], v[i+1])`, which only works on convex polygons. Stairs side is the first non-convex face polygon in the codebase (prism/pyramid/cylinder/octahedron are all convex), so this latent defect predates the slice but became visible only now. Distinct from Risk 1 (SceneDataPacker 6-vertex cap): at stepCount=2 the zigzag has exactly 6 vertices so nothing is truncated — the fan is simply the wrong algorithm. Fix recommendation: ear-clipping triangulation in `RenderCommandTriangulator` OR shape-aware rectangle decomposition at `ShapeNode.renderTo` layer. Owner: fold into `webgpu-ngon-faces` or new `webgpu-nonconvex-faces` slice. Not blocking for this slice's ACs.
  - **I-1 — PRE-EXISTING compile failure in :isometric-benchmark.** `BenchmarkScreen.kt:165` calls `Shape(color = …)`; parameter was renamed to `material` by `api-design-fixes` slice. Unrelated to Stairs (commit `8f06ed6` touches 0 files under `isometric-benchmark/`).
- Record: [06-verify-uv-generation-stairs.md](06-verify-uv-generation-stairs.md)

### `webgpu-ngon-faces` — FAIL (6 of 7 ACs met; AC2 NOT MET — WGSL fan-triangulation defect on non-convex zigzag)
- Checks: **5/5 passed** — compile (all 4 modules), webgpu unit tests (incl. 7 new `UvFaceTablePackerTest` + 5 new `TriangulateEmitShaderTest`), apiCheck (zero delta), aggregate `./gradlew check`, `:app:installDebug`.
- Acceptance: **6/7 met, 1 NOT MET.** AC1 (24-vert cylinder cap) — direct visual pass. AC3 (Knot) — direct visual pass. AC4 — Paparazzi 22 baselines + Prism tab visual. AC5/AC6/AC7 — automated green. **AC2 (Stairs stepCount=5) — DOES NOT MEET pixel-equivalence with Canvas:** the right palette stairs' blue zigzag side renders as a smooth diagonal slope on Full WebGPU instead of the staircase silhouette Canvas produces.
- Interactive: 4 Maestro runs (verify-cylinder×2, verify-knot, verify-stairs-fixed). Cold-boot post-pass-1 used to clear Vulkan surface transient; `verify-stairs-fixed.yaml` (new flow) successfully lands on Stairs tab — drift in original `verify-stairs.yaml` is filed as I-01.
- Issues: **2 new.** **I-02 (BLOCKER)** — `TriangulateEmitShader.WGSL` emit-loop fan-triangulates from `s[0]`, which is wrong for non-convex polygons (stairs zigzag side at stepCount≥3). Commit B replaced the unrolled fan with a looped fan but kept the algorithm; Commit A's ear-clipping fix is on the CPU `RenderCommandTriangulator`, not the WGSL shader. Cylinder caps + Knot quads pass because they are convex. Fix path: add ear-clipping inside WGSL emit, or pre-triangulate non-convex faces on CPU. **I-01 (LOW)** — Maestro tab-tap coords drifted in original verify-stairs/verify-knot flows; new `.maestro/verify-stairs-fixed.yaml` resolves it for stairs.
- Record: [06-verify-webgpu-ngon-faces.md](06-verify-webgpu-ngon-faces.md)

### `uv-generation-knot` — PASS (all 5 ACs met on pass 1; zero new issues)
- Checks: **5/5 passed** — targeted `UvGeneratorKnotTest` (11/11 tests, 0 failures, 0.047s), shader debug + release variants, core / compose debug+release / webgpu test, apiCheck on all four slice modules. Aggregate across slice-relevant modules: **849 tests / 79 suites / 0 failures / 0 errors / 14 pre-existing skips**. apiCheck delta: single planned addition `+Knot.getSourcePrisms(): List` in `isometric-core.api`; `UvGenerator` stays `internal` as planned.
- Acceptance: 5/5 met. AC1 (texture renders on Knot, Canvas), AC2 (no UV discontinuity), AC3 (WebGPU parity — pixel-equivalent to Canvas across all four backends), AC4 (11 tests cover all 4 distinct mesh regions: sub-prism 0/1/2 + custom quads 18/19), AC5 (zero regression across 849 sibling tests, Paparazzi golden images regenerated cleanly).
- Interactive: **4 Maestro runs on `Medium_Phone_API_36.0` (emulator-5554)** via new `.maestro/verify-knot.yaml` — Canvas, Full WebGPU, Canvas + GPU Sort, Canvas cycle-back. Brick (left, `Knot(Point(-1.5,0,0))`) + grass (right, `Knot(Point(1.5,0,0))`) at `scale = 3.0`. Temporary Knot tab added to `TexturedDemoActivity.kt`, captured, then fixture reverted (0-line diff vs. committed). All four backends render pixel-equivalently for both Knots — no I-2-style triangulation slash (Knot's 20 face polygons are all convex). Maestro flow scrolls the `ScrollableTabRow` via `swipe: 97%,4% → 5%,4%` (1500ms) then taps the Knot tab at `point: "76%,3%"` (text-match unreliable; same precedent as cylinder/stairs). Evidence: `verify-evidence/screenshots/verify-knot-{canvas,webgpu,canvas-gpusort,canvas-cycle-back}.png`.
- Issues: **0 new.** Pre-existing: depth-sort artifact in every screenshot (Knot's documented depth-sort bug, slice Risk 1) — UV-correct on visible arms, sort-occluded elsewhere. Stairs-surfaced I-2 (non-convex triangulation) does NOT affect Knot because all Knot face polygons are convex. Stairs-surfaced I-1 (`:isometric-benchmark` pre-existing compile failure) unchanged and out of slice scope.
- Record: [06-verify-uv-generation-knot.md](06-verify-uv-generation-knot.md)

## Recommended Next Stage

**Update (2026-04-25):** I-02 fix landed in Commit C — WGSL ear-clipping
inside `TriangulateEmitShader.WGSL` replaces the fan-from-`s[0]` algorithm
that broke non-convex polygons. See
`05-implement-webgpu-ngon-faces.md` § "Commit C" for details. Pass-2 verify
is now the next step.

- **Option A (default, recommended):** `/wf-verify texture-material-shaders webgpu-ngon-faces` — pass-2 verify. Re-run `.maestro/verify-stairs-fixed.yaml` to confirm AC2 (zigzag silhouette parity) now passes on Full WebGPU. Also re-run cylinder + knot flows to confirm no regression on convex polygons.
- **Option B:** `/wf-plan texture-material-shaders webgpu-ngon-faces` — revisit the plan only if pass-2 verify exposes a deeper algorithmic gap. The plan's §Step 7 fan prescription has now been deviated-from (deviation #5 in implement record).
- **Option C:** `/wf-review texture-material-shaders webgpu-ngon-faces` — review the Commit A + B + C diff as the final slice surface. Not recommended ahead of pass-2 verify since AC2 still has no positive on-device evidence.
