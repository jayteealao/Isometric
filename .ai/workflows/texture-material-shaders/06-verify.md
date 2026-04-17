---
schema: sdlc/v1
type: verify-index
slug: texture-material-shaders
status: in-progress
stage-number: 6
created-at: "2026-04-11T23:44:32Z"
updated-at: "2026-04-17T22:50:00Z"
slices-verified: 12
slices-total: 12
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders uv-generation-pyramid"
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

### `uv-generation-pyramid` — FAIL (2/5 ACs met, 3/5 not met; I-03 surfaced)
- One verify pass: `2026-04-17T22:50Z` (automated + interactive on `emulator-5554` with Maestro 2.2.0).
- Checks: 5/5 passed (457 tests green across 4 modules — +15 new `UvGeneratorPyramidTest` cases; apiCheck green; `:app:assembleDebug` green).
- Acceptance: AC4, AC5 MET via unit tests + apiCheck + Paparazzi. AC1, AC2, AC3 NOT MET — both Canvas and WebGPU render the textured Pyramid AND the per-face-colored Pyramid as flat gray (only engine directional shading visible). Assigned bright colors (220,50,50 red / 50,180,50 green / ...) and grass/dirt textures never reach pixels.
- Interactive: 1 Maestro run + adb-fallback screenshots. Maestro `tapOn: "Pyramid"` (text selector) did not route to the Compose `ScrollableTabRow` Tab onClick — substituted `tapOn: { point: "73%,4%" }` (workaround, not a project bug). Evidence: `screenshots/verify-pyramid-canvas.png`, `screenshots/verify-pyramid-webgpu.png`, `screenshots/verify-pyramid-canvas-gpusort.png`, `screenshots/verify-pyramid-canvas-cycle-back.png`, and adb fallbacks `verify-pyramid-03b-canvas.png`, `verify-pyramid-04-webgpu.png`. Flow: `.maestro/verify-pyramid.yaml`.
- Issues: **1 CRITICAL (I-03)** — Pyramid textured + per-face materials do not render at runtime, invariant across Canvas, Canvas+GPU-Sort, and Full WebGPU backends. The mixed-vertex-count stress that plan Risk 1 (HIGH) flagged has materialised. Unit tests for `resolve`/`resolveForFace`/`uvCoordProviderForShape`/DSL are green — the bug lives between `RenderCommand` construction and the draw hooks. Three candidate root causes (see verify record): CR-5 `SceneGraph.add(shape, color)` convenience overload hard-coding `faceVertexCount=4`, a missing Pyramid branch in `SceneDataPacker`/`NativeSceneRenderer`, or a BatchNode faceType gap (pre-existing M-02). Regression gap: no JVM-level test projects a Pyramid scene and asserts the resulting `RenderCommand[]` shape.
- Record: [06-verify-uv-generation-pyramid.md](06-verify-uv-generation-pyramid.md)

## Recommended Next Stage
- **Option A (recommended):** `/wf-implement texture-material-shaders uv-generation-pyramid` — fix I-03. Investigate the three candidate root causes (SceneGraph.add convenience overload, SceneDataPacker Pyramid branch, BatchNode faceType). Add a JVM-level regression test that projects a Pyramid scene and asserts `RenderCommand[]` carries correct `faceType`, `faceVertexCount` (3 for laterals, 4 for base), and `uvCoords.size` (6/8). Re-run `.maestro/verify-pyramid.yaml` for the re-verify.
- **Option B:** `/wf-plan texture-material-shaders uv-generation-pyramid` — only if implement-investigation reveals a design flaw rather than a wiring bug. Unlikely given evidence points at a small, specific dispatch gap.
- **Option C:** `/wf-review` — **not recommended** with CRITICAL render defect outstanding.
- **Option D:** `/wf-handoff` — **not recommended**; slice cannot ship in this state.
