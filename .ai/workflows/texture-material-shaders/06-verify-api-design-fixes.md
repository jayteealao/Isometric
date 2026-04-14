---
schema: sdlc/v1
type: verify
slug: texture-material-shaders
slice-slug: api-design-fixes
status: complete
stage-number: 6
created-at: "2026-04-14T07:00:31Z"
updated-at: "2026-04-14T07:00:31Z"
result: partial
metric-checks-run: 3
metric-checks-passed: 3
metric-acceptance-met: 12
metric-acceptance-total: 13
metric-interactive-checks-run: 0
metric-interactive-checks-passed: 0
metric-issues-found: 1
evidence-dir: ".ai/workflows/texture-material-shaders/verify-evidence/"
tags: [api-design, texture, material, shader]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-api-design-fixes.md
  plan: 04-plan-api-design-fixes.md
  implement: 05-implement-api-design-fixes.md
  review: 07-review-api-design-fixes.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders api-design-fixes"
---

# Verify: api-design-fixes

## Verification Summary

**Result: PARTIAL** — 12/13 acceptance criteria met. One AC fails: `isometric-shader.Shape(IsometricMaterial)` was not deleted as planned (AC4). All automated checks (build, apiCheck, unit tests) pass clean. The single open issue is a LOW-severity API surface deviation, not a runtime defect.

## Automated Checks Run

| Check | Command | Result |
|-------|---------|--------|
| Build + apiCheck | `./gradlew :isometric-core:apiCheck :isometric-compose:apiCheck :isometric-shader:apiCheck :isometric-webgpu:compileReleaseKotlin` | **PASS** — BUILD SUCCESSFUL |
| Unit tests (isometric-shader) | `./gradlew :isometric-shader:test` | **PASS** — 90 tests, 0 failures, 0 errors (debug + release variants) |
| API drift | apiCheck on core, compose, shader | **PASS** — no unintended drift |

## Interactive Verification Results

Automated only — AC1 (TextureTransform visual tiling) was explicitly listed as deferred in the implementation record ("visually verifiable on device"). No device or emulator was available for this verification session. The code correctness of the `T^-1` pre-concat in `computeAffineMatrix` was confirmed by code inspection (see AC1 below).

## Acceptance Criteria Status

| ID | Criterion | Result | Method | Evidence |
|----|-----------|--------|--------|---------|
| AC1 | TextureTransform applied in computeAffineMatrix | **MET** | Code inspection | `TexturedCanvasDrawHook.kt:93` calls `computeAffineMatrix(..., material.transform, ...)`. Lines 167–185 apply `T^-1` pre-concat when `transform != TextureTransform.IDENTITY`. |
| AC2 | Unassigned faces render gray | **MET** | Code inspection | `IsometricMaterial.kt:10`: `UNASSIGNED_FACE_DEFAULT = IsoColor(128, 128, 128, 255)`. `PerFaceMaterialScope.default = UNASSIGNED_FACE_DEFAULT`. |
| AC3 | Positional `Shape()` call sites unchanged | **MET** | Code inspection | `IsometricComposables.kt:56`: `fun IsometricScope.Shape(geometry: Shape, material: MaterialData, ...)`. `IsoColor.kt:27`: `data class IsoColor ... : MaterialData`. Positional `Shape(Prism(), IsoColor.BLUE)` compiles. |
| AC4 | One `Shape()`, no import alias | **NOT MET** | Code inspection | `IsometricMaterialComposables.kt:61` still defines `fun IsometricScope.Shape(geometry: Shape, material: IsometricMaterial, ...)`. Two `Shape()` overloads exist; plan intended to delete the shader overload. See Issues section. |
| AC5 | `IsoColor` implements `MaterialData` | **MET** | Code inspection | `IsoColor.kt:27`: `: MaterialData` present. API dump confirms. |
| AC6 | `IsometricMaterial` no longer contains `FlatColor` | **MET** | Code + API dump | No `FlatColor` in `IsometricMaterial.kt` sealed hierarchy. `isometric-shader.api` contains no `FlatColor` entry. |
| AC7 | Renames compile (`TextureTransform`, `TextureSource.Bitmap`, `texturedResource`) | **MET** | Code + API dump | `UvCoord.kt`: `TextureTransform` class present, no `UvTransform`. `TextureSource.kt`: `Bitmap` nested class. `IsometricMaterialComposables.kt`: `texturedResource()` present, no bare `textured()`. |
| AC8 | `UvGenerator` not in public API | **MET** | API dump | `isometric-shader.api` contains no `UvGenerator` entry. |
| AC9 | Texture load errors logged | **MET** | Code inspection | `TextureLoader.kt`: `Log.w(TAG, ...)` in both `loadResource` and `loadAsset` catch blocks. `TexturedCanvasDrawHook.kt:114`: `onTextureLoadError?.invoke(source)` on load failure. |
| AC10 | `toBaseColor()` covers all `MaterialData` | **MET** | Code inspection | `IsometricMaterialComposables.kt:28–33`: `when` has `is IsoColor -> this`, `is IsometricMaterial.Textured -> tint`, `is IsometricMaterial.PerFace -> IsoColor.WHITE`, `else -> IsoColor.WHITE`. All branches covered. |
| AC11 | `TextureCacheConfig` exists | **MET** | Code inspection | `ProvideTextureRendering.kt:22`: `data class TextureCacheConfig(val maxSize: Int = 20)`. Line 71: `fun ProvideTextureRendering(cacheConfig: TextureCacheConfig = TextureCacheConfig(), ...)`. |
| AC12 | All existing tests pass | **MET** | Unit tests | 90 test cases across 8 XML reports (debug + release). 0 failures, 0 errors. `BUILD SUCCESSFUL`. |
| AC13 | `TextureTransform.init` validates | **MET** | Code inspection | `UvCoord.kt` `init` block: `require(scaleU.isFinite() && scaleU != 0f)`, `require(scaleV.isFinite() && scaleV != 0f)`, plus `isFinite()` checks on `offsetU`, `offsetV`, `rotationDegrees`. |

## Issues Found

### ISSUE-1 (LOW): `isometric-shader.Shape(IsometricMaterial)` not deleted

**Severity:** LOW — does not cause a runtime error; Kotlin resolves to more specific overload cleanly. However, it violates the "one Shape()" API design intent (TM-API-10) and the plan step:
> "delete `isometric-shader.Shape(geometry, material: IsometricMaterial)` overload — one `Shape()` in `isometric-compose` covers all cases"

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialComposables.kt:61`

**Current state:**
- `isometric-compose.Shape(geometry, material: MaterialData)` — accepts any `MaterialData` (IsoColor, IsometricMaterial, etc.)
- `isometric-shader.Shape(geometry, material: IsometricMaterial)` — still present, accepts IsometricMaterial specifically

**Impact:** When both packages are star-imported (the common case for an app using textures), `Shape(Prism(), tileMaterial)` where `tileMaterial: IsometricMaterial` silently dispatches to the shader overload instead of the compose overload. This is functional (both do the same thing) but the hidden routing is exactly the API ambiguity the guideline warns against. `TexturedDemoActivity` already imports `isometric-shader.Shape` directly, confirming the issue.

**Fix:** Delete the `Shape()` and `Path()` composables from `isometric-shader/src/main/kotlin/.../IsometricMaterialComposables.kt`. The `isometric-compose.Shape(MaterialData)` already handles `IsometricMaterial` (since it implements `MaterialData`). Verify that `TexturedDemoActivity` updates its import accordingly after deletion.

## Gaps / Unverified Areas

- **AC1 visual verification** — TextureTransform tiling (2× horizontal) was confirmed correct by code inspection but NOT tested on a physical device or emulator. The math is correct (`T^-1` pre-concat). Deferred to manual QA if required.
- **AC4 ambiguity in practice** — No test case verifies the "import only isometric-compose, both calls compile" scenario. The `TexturedDemoActivity` explicitly uses `isometric-shader.Shape`, so the two-overload situation is already in use.

## Freshness Research

Not applicable — this slice is a pure internal API cleanup with no new external dependencies.

## Recommendation

Fix ISSUE-1 (delete `isometric-shader.Shape(IsometricMaterial)` and `Path()`) in a follow-up `/wf-implement` pass before proceeding to review. The fix is surgical (2 function deletions + 1 call-site import update in `TexturedDemoActivity`). All other 24 findings are confirmed resolved.

## Recommended Next Stage
- **Option A (default): `/wf-implement texture-material-shaders api-design-fixes`** — fix ISSUE-1: delete `isometric-shader.Shape(IsometricMaterial)` and `isometric-shader.Path(IsometricMaterial)`, update `TexturedDemoActivity` import. Then re-run verify.
- **Option B: `/wf-review texture-material-shaders api-design-fixes`** — proceed with review accepting ISSUE-1 as a known LOW finding. The one-Shape() intent is documented; reviewer can confirm whether it matters before merge.
- **Option C: `/wf-handoff texture-material-shaders api-design-fixes`** — skip review entirely (solo project context). Only appropriate if ISSUE-1 is accepted as-is.
