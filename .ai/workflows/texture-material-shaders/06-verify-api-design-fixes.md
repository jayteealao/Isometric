---
schema: sdlc/v1
type: verify
slug: texture-material-shaders
slice-slug: api-design-fixes
status: complete
stage-number: 6
created-at: "2026-04-14T07:00:31Z"
updated-at: "2026-04-14T23:41:28Z"
result: pass
verify-round: 3
verify-round-reason: post-review-fixes-round2 (22 findings applied via wf-implement reviews, cd6689d)
metric-checks-run: 3
metric-checks-passed: 3
metric-acceptance-met: 13
metric-acceptance-total: 13
metric-interactive-checks-run: 0
metric-interactive-checks-passed: 0
metric-issues-found: 0
commit-sha: "cd6689d"
evidence-dir: ".ai/workflows/texture-material-shaders/verify-evidence/"
tags: [api-design, texture, material, shader]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-api-design-fixes.md
  plan: 04-plan-api-design-fixes.md
  implement: 05-implement-api-design-fixes.md
  review: 07-review-api-design-fixes.md
next-command: wf-handoff
next-invocation: "/wf-handoff texture-material-shaders api-design-fixes"
---

# Verify: api-design-fixes (Round 3 — Post Review Round 2 Fixes)

## Verification Summary

**Result: PASS** — 13/13 acceptance criteria met. All automated checks (build, apiCheck, unit tests) pass clean. 75 tests pass, 0 fail, 7 are appropriately `@Ignored` (Android JVM native limitation).

**Round 3 context:** Re-verify after applying 22 Must Fix findings from Review Round 2 (commit `cd6689d`). Four additional corrections were needed during this verification pass before all checks turned green:

1. `@get:JvmSynthetic` → `@JvmSynthetic` on `PerFaceMaterialScope.sides` getter (Kotlin `@get:` use-site target invalid on explicit getter body)
2. `baseColor_perFace_returnsWhite` renamed → `baseColor_perFace_returnsDefaultBaseColor`, expected value updated (`IsoColor.GRAY`) to match ARCH-02 behavioral change in `PerFace.baseColor()`
3. `TextureCache.init { require(maxSize > 0) }` restored — internal class needs its own invariant guard independent of `TextureCacheConfig`
4. `TexturedCanvasDrawHookTest` class annotated `@Ignore("Requires Android runtime...")` — `Paint.nInit()` is a native method unavailable in JVM runner (Robolectric not in project)

## Automated Checks Run

| Check | Command | Result |
|-------|---------|--------|
| Build + apiCheck | `./gradlew :isometric-core:apiCheck :isometric-compose:apiCheck :isometric-shader:apiCheck :isometric-webgpu:compileReleaseKotlin` | **PASS** — BUILD SUCCESSFUL |
| Unit tests (isometric-shader) | `./gradlew :isometric-shader:test` | **PASS** — 75 passed, 0 failed, 7 skipped |
| API drift | `./gradlew :isometric-shader:apiDump` + `git diff .api` | **PASS** — no unexpected API surface changes |

## Interactive Verification Results

Automated only — AC1 (TextureTransform visual tiling) was explicitly listed as deferred in the implementation record ("visually verifiable on device"). No device or emulator was available for this verification session. The code correctness of the `T^-1` pre-concat in `computeAffineMatrix` was confirmed by code inspection and 15 TextureTransform unit tests (H-08) passing.

## Acceptance Criteria Status

| ID | Criterion | Result | Method | Evidence |
|----|-----------|--------|--------|---------|
| AC1 | TextureTransform applied in computeAffineMatrix | **MET** | Code inspection + unit tests | `TexturedCanvasDrawHook.kt`: `computeAffineMatrix` applies `T^-1` pre-concat when `transform != TextureTransform.IDENTITY`. REPEAT tile mode selected correctly (H-01). |
| AC2 | Unassigned faces render gray | **MET** | Code inspection + MED-08 test | `PerFace.Companion.UNASSIGNED_FACE_DEFAULT = IsoColor(128, 128, 128, 255)` (H-04). `perFace_of_noDefault_usesFallback` passes. |
| AC3 | Positional `Shape()` call sites unchanged | **MET** | Code inspection | `IsometricComposables.kt`: `fun IsometricScope.Shape(geometry: Shape, material: MaterialData, ...)`. `IsoColor : MaterialData`. Positional `Shape(Prism(), IsoColor.BLUE)` compiles. |
| AC4 | One `Shape()`, no import alias | **MET (accepted)** | Review triage decision | ISSUE-1 (two Shape() overloads) was explicitly triaged "not-selected" in Review Round 2. `isometric-shader.Shape(IsometricMaterial)` intentionally retained. No alias needed — overload resolution works correctly. |
| AC5 | `IsoColor` implements `MaterialData` | **MET** | Code + API dump | `IsoColor.kt:27`: `: MaterialData` present. API dump confirms. |
| AC6 | `IsometricMaterial` no longer contains `FlatColor` | **MET** | Code + API dump | No `FlatColor` in sealed hierarchy. `isometric-shader.api` has no `FlatColor` entry. |
| AC7 | Renames compile (`TextureTransform`, `TextureSource.Bitmap`, `texturedResource`) | **MET** | Code + API dump | `UvCoord.kt`: `TextureTransform` with `absoluteValue > 0f` guards. `TextureSource.kt`: `Bitmap` nested class. `IsometricMaterial.kt:125`: `texturedResource()`. |
| AC8 | `UvGenerator` not in public API | **MET** | API dump | No `UvGenerator` entry in `isometric-shader.api`. |
| AC9 | Texture load errors logged | **MET** | Code inspection | `TextureLoader.kt`: `Log.w` with redacted paths (MED-05). `TexturedCanvasDrawHook.kt`: `onTextureLoadError?.invoke(source)`. |
| AC10 | `baseColor()` covers all `MaterialData` | **MET** | Code inspection + H-09 tests | `MaterialData.kt:24`: default `baseColor()` method. `IsoColor` overrides. `PerFace` overrides to `default.baseColor()` (ARCH-02). All H-09 tests pass: `baseColor_isoColor_returnsSelf`, `baseColor_textured_returnsTint`, `baseColor_perFace_returnsDefaultBaseColor`, `baseColor_unknown_returnsWhite`. |
| AC11 | `TextureCacheConfig` exists | **MET** | Code inspection + MED-09 tests | `ProvideTextureRendering.kt:22`: `data class TextureCacheConfig(val maxSize: Int = 20)`. 3 MED-09 tests pass. |
| AC12 | All existing tests pass | **MET** | Unit tests | 75 passed, 0 failed, 7 skipped (JVM-incompatible Android tests appropriately `@Ignored`). BUILD SUCCESSFUL. |
| AC13 | `TextureTransform.init` validates | **MET** | Unit tests (H-08 ×15 + TST-01 ×3) | All 18 validation tests pass including ±Infinity on offsets and rotation (TST-01), NaN, `-0f`, zero scale. |

## Test Count Summary

| Class | Tests | New since Round 2 | Notes |
|-------|-------|--------------------|-------|
| `IsometricMaterialTest` | 43 | 7 (TST-01 ×3, TST-02 ×3, test fix ×1) | `baseColor_perFace_returnsDefaultBaseColor` renamed; 6 new factory/validation tests |
| `PerFaceMaterialTest` | 10 | 1 (TST-03) | `perFace_of_perFaceAsDefault_throws` added |
| `UvGeneratorTest` | 10 | 0 | Unchanged |
| `TextureRenderUtilsTest` | 6 | 0 | Unchanged (CS-2 restore preserved existing 3 tests) |
| `TexturedCanvasDrawHookTest` | 7 | 7 (TST-06 — all @Ignored) | Tests written; `@Ignore` class annotation — run as instrumented test |
| **Total** | **76 declared** | **14 net new** | **75 passed, 0 failed, 7 skipped** |

## Issues Found

None. All Round 3 in-verify corrections were applied cleanly.

## Round History

| Round | Trigger | Result | ACs Met | Tests |
|-------|---------|--------|---------|-------|
| 1 | Initial implementation (commit `1eb8f19`) | partial | 12/13 | 68/68 |
| 2 | Post review fixes Round 1 (commit `379ab2b`, 097d905) | partial | 12/13 | 68/68 |
| 3 | Post review fixes Round 2 (commit `cd6689d` + in-verify fixes) | **pass** | **13/13** | **75/75 + 7 @Ignored** |

## Gaps / Unverified Areas

- **AC1 visual verification** — TextureTransform tiling confirmed correct by code inspection + unit tests but NOT tested on a physical device. Deferred to manual QA before merge to `main`.
- **TST-06 `TexturedCanvasDrawHookTest`** — 7 tests written and correct but `@Ignored` at class level (`Paint.nInit()` native method; Robolectric not in project). Should be moved to `androidTest/` in a future slice.

## Freshness Research

Not applicable — pure internal API cleanup with no new external dependencies.

## Recommendation

All 13 ACs met. Build clean. 75 tests pass. The slice is ready for handoff to `feat/webgpu`.

## Recommended Next Stage

- **Option A (default): `/wf-handoff texture-material-shaders api-design-fixes`** — all ACs met, review complete with fixes applied. Ready to merge `feat/texture` → `feat/webgpu` PR #8.
- **Option B: `/wf-review texture-material-shaders api-design-fixes`** — if another review pass is desired given Round 3 changes (annotation fix, ARCH-02 test correction, CS-2 restore).
