---
schema: sdlc/v1
type: verify-index
slug: texture-material-shaders
status: in-progress
stage-number: 6
created-at: "2026-04-11T23:44:32Z"
updated-at: "2026-04-18T22:45:00Z"
slices-verified: 13
slices-total: 13
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders uv-generation-cylinder"
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

### `uv-generation-cylinder` — PARTIAL (5/5 ACs met; VF-1 doc-test regression surfaced)
- Checks: 3/4 passed. `:app:assembleDebug` ✓, `./gradlew apiCheck` ✓ (additive `cylinderPerFace` + scope), Maestro `verify-cylinder.yaml` 4 screenshots ✓. Unit tests **FAIL**: 499/501 pass (core 205/207 with 2 VF-1 failures; shader 154/154; compose 106/106; webgpu 34/34).
- Acceptance: AC1 (seam wrap) MET, AC2 (caps) MET Canvas with documented WebGPU cap-truncation caveat, AC3 (per-face) MET, AC4 (unit tests) MET (18/18 UvGeneratorCylinderTest cases), AC5 (no Prism regression) MET strict.
- Interactive: 4 Maestro runs on `emulator-5554` — Canvas, Full WebGPU, Canvas+GPU Sort, Canvas cycle-back. Brick texture wraps continuously on left cylinder; right cylinder shows red top + brick sides (blue bottom hidden by camera angle but math tested). WebGPU sides pixel-equivalent to Canvas; caps show documented N>6 star truncation. Zero app-tag logcat warnings.
- Issues: **1 MEDIUM (VF-1)** — `DocScreenshotGenerator.shapeCylinder` at `isometric-core/.../DocScreenshotGenerator.kt:232` still uses `Cylinder(vertices = 30)`, tripping the new `require(vertices in 3..24)` guard in `Cylinder.init`. Implement stage's "Deviations from Plan" entry updated `IsometricCanvasSnapshotTest.cylinder()` from 30→20 but missed this second caller. Cascades to `DocScreenshotGenerator.generateAll`. One-line fix.
- Evidence: `verify-evidence/screenshots/verify-cylinder-{canvas,webgpu,canvas-gpusort,canvas-cycle-back}.png`
- Record: [06-verify-uv-generation-cylinder.md](06-verify-uv-generation-cylinder.md)

## Recommended Next Stage
- **Option A (recommended):** `/wf-implement texture-material-shaders uv-generation-cylinder` — apply the VF-1 one-line fix (`30 → 20` at `DocScreenshotGenerator.kt:232`), then re-run verify (pass 2). Trivial in-scope fix; all 5 ACs structurally met.
- **Option B:** `/wf-review texture-material-shaders uv-generation-cylinder` — only if user accepts shipping with the Doc-test regression outstanding. Not recommended — CI would fail on `:isometric-core:test`.
- **Option C:** `/wf-handoff texture-material-shaders uv-generation-cylinder` — **not recommended**. Fix VF-1 first.
- **Option D:** `/wf-plan texture-material-shaders uv-generation-cylinder` — **not recommended**. Plan is sound; VF-1 is a missed-caller bug, not a plan flaw.
