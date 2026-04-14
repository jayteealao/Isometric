---
schema: sdlc/v1
type: verify
slug: texture-material-shaders
slice-slug: api-design-fixes
status: complete
stage-number: 6
created-at: "2026-04-14T07:00:31Z"
updated-at: "2026-04-14T21:41:49Z"
result: partial
verify-round: 2
verify-round-reason: post-review-fixes (19 findings applied via wf-implement reviews)
metric-checks-run: 3
metric-checks-passed: 3
metric-acceptance-met: 12
metric-acceptance-total: 13
metric-interactive-checks-run: 0
metric-interactive-checks-passed: 0
metric-issues-found: 2
commit-sha: "097d905"
evidence-dir: ".ai/workflows/texture-material-shaders/verify-evidence/"
tags: [api-design, texture, material, shader]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-api-design-fixes.md
  plan: 04-plan-api-design-fixes.md
  implement: 05-implement-api-design-fixes.md
  review: 07-review-api-design-fixes.md
next-command: wf-review
next-invocation: "/wf-review texture-material-shaders api-design-fixes"
---

# Verify: api-design-fixes (Round 2 — Post Review Fixes)

## Verification Summary

**Result: PARTIAL** — 12/13 acceptance criteria met. One AC fails: `isometric-shader.Shape(IsometricMaterial)` still exists as a second overload (AC4), carried forward from Round 1. All automated checks (build, apiCheck, unit tests) pass clean after a Kotlin explicit API mode correction to the MED-13 fix. The single open AC issue is LOW severity and does not affect runtime behaviour.

**Round 2 context:** This verification re-runs after applying 19 review findings (8 HIGH + 11 MED) from `07-review-api-design-fixes.md`. Commit `379ab2b`. The build sub-agent also corrected an invalid MED-13 fix (see Issues below).

## Automated Checks Run

| Check | Command | Result |
|-------|---------|--------|
| Build + apiCheck | `./gradlew :isometric-core:apiCheck :isometric-compose:apiCheck :isometric-shader:apiCheck :isometric-webgpu:compileReleaseKotlin` | **PASS** — BUILD SUCCESSFUL |
| Unit tests (isometric-shader) | `./gradlew :isometric-shader:test` | **PASS** — 68 tests (debug), 0 failures, 0 errors |
| API drift | `./gradlew :isometric-shader:apiDump` + `git diff .api` | **PASS** — only `PerFaceMaterialScope` public class entry added (see Issues section) |

## Interactive Verification Results

Automated only — AC1 (TextureTransform visual tiling) was explicitly listed as deferred in the implementation record ("visually verifiable on device"). No device or emulator was available for this verification session. The code correctness of the `T^-1` pre-concat in `computeAffineMatrix` was confirmed by code inspection and the 15 TextureTransform unit tests (H-08) passing.

## Acceptance Criteria Status

| ID | Criterion | Result | Method | Evidence |
|----|-----------|--------|--------|---------|
| AC1 | TextureTransform applied in computeAffineMatrix | **MET** | Code inspection + unit tests | `TexturedCanvasDrawHook.kt:93` calls `computeAffineMatrix(..., material.transform, ...)`. Lines ~167–185 apply `T^-1` pre-concat when `transform != TextureTransform.IDENTITY` (H-01 fix confirms REPEAT trigger). |
| AC2 | Unassigned faces render gray | **MET** | Code inspection + MED-08 test | `IsometricMaterial.kt`: `PerFace.Companion.UNASSIGNED_FACE_DEFAULT = IsoColor(128, 128, 128, 255)` (H-04 moved to Companion). `perFace_of_noDefault_usesFallback` test passes. |
| AC3 | Positional `Shape()` call sites unchanged | **MET** | Code inspection | `IsometricComposables.kt:56`: `fun IsometricScope.Shape(geometry: Shape, material: MaterialData, ...)`. `IsoColor.kt`: `: MaterialData`. Positional `Shape(Prism(), IsoColor.BLUE)` compiles. |
| AC4 | One `Shape()`, no import alias | **NOT MET** | Code inspection | `IsometricMaterialComposables.kt:44` defines `fun IsometricScope.Shape(geometry: Shape, material: IsometricMaterial, ...)`. Two `Shape()` overloads remain. |
| AC5 | `IsoColor` implements `MaterialData` | **MET** | Code + API dump | `IsoColor.kt:27`: `: MaterialData` present. API dump confirms. |
| AC6 | `IsometricMaterial` no longer contains `FlatColor` | **MET** | Code + API dump | No `FlatColor` in `IsometricMaterial.kt` sealed hierarchy. `isometric-shader.api` contains no `FlatColor` entry. |
| AC7 | Renames compile (`TextureTransform`, `TextureSource.Bitmap`, `texturedResource`) | **MET** | Code + API dump | `UvCoord.kt`: `TextureTransform` class with `absoluteValue > 0f` guards (MED-01). `TextureSource.kt`: `Bitmap` nested class. `IsometricMaterialComposables.kt`: `texturedResource()` present. |
| AC8 | `UvGenerator` not in public API | **MET** | API dump | `isometric-shader.api` contains no `UvGenerator` entry. |
| AC9 | Texture load errors logged | **MET** | Code inspection | `TextureLoader.kt`: `Log.w` with `Asset(path=<redacted>)` / `Resource(id=$resId)` (MED-05). `TexturedCanvasDrawHook.kt`: `onTextureLoadError?.invoke(source)` (H-06/TM-API-6). |
| AC10 | `toBaseColor()` covers all `MaterialData` | **MET** | Code inspection + H-09 tests | `IsometricMaterialComposables.kt`: `when` covers `is IsoColor`, `is Textured`, `is PerFace`, `else`. All 4 H-09 tests pass: `baseColor_isoColor_returnsSelf`, `baseColor_textured_returnsTint`, `baseColor_perFace_returnsWhite`, `baseColor_unknown_returnsWhite`. |
| AC11 | `TextureCacheConfig` exists | **MET** | Code inspection + MED-09 tests | `ProvideTextureRendering.kt:22`: `data class TextureCacheConfig(val maxSize: Int = 20)`. 3 MED-09 tests pass (`defaultMaxSize_is20`, `zeroMaxSize_throws`, `negativeMaxSize_throws`). |
| AC12 | All existing tests pass | **MET** | Unit tests | 68 tests (debug variant, identical for release), 0 failures, 0 errors. Includes 19 new tests from review fixes. `BUILD SUCCESSFUL`. |
| AC13 | `TextureTransform.init` validates | **MET** | Unit tests (H-08 ×15) | All 15 H-08 tests pass including `negativeZeroScaleU_throws` (MED-01 `-0f` fix: `absoluteValue > 0f`), NaN, ±Infinity. Separate `isFinite()` + `absoluteValue > 0f` requires confirm correctness. |

## Issues Found

### ISSUE-1 (LOW — carry forward): `isometric-shader.Shape(IsometricMaterial)` not deleted

**Severity:** LOW — does not cause a runtime error; Kotlin resolves to more specific overload cleanly.

**Location:** `isometric-shader/src/main/kotlin/.../IsometricMaterialComposables.kt:44`

**Current state:** Two `Shape()` overloads exist:
- `isometric-compose.Shape(geometry, material: MaterialData)` — accepts any `MaterialData`
- `isometric-shader.Shape(geometry, material: IsometricMaterial)` — still present

**Impact:** When both packages are star-imported, `Shape(Prism(), tileMaterial)` where `tileMaterial: IsometricMaterial` silently dispatches to the shader overload instead of the compose overload. Functional but violates "one Shape()" API design intent (TM-API-10). `TexturedDemoActivity` already imports `isometric-shader.Shape` directly.

**Fix:** Delete the `Shape()` and `Path()` composables from `IsometricMaterialComposables.kt`. Update `TexturedDemoActivity` import.

---

### ISSUE-2 (LOW — corrected by build agent): MED-13 partial revert — `PerFaceMaterialScope` public class

**Severity:** LOW — correctly resolved during this verification pass.

**What happened:** Review fix MED-13 changed `PerFaceMaterialScope` to `internal class`. This violates Kotlin's explicit API mode (`explicitApi()` in build.gradle): a public function (`perFace(block: PerFaceMaterialScope.() -> Unit)`) cannot use an `internal` type as its receiver parameter type. The compile error was:

```
Function 'public' exposes its 'internal' parameter type 'PerFaceMaterialScope'.
```

**Resolution (applied by build sub-agent):** Changed to `class PerFaceMaterialScope internal constructor()`. The class is now public (required by explicit API mode) but the constructor is `internal` (external callers cannot instantiate it directly — only the `perFace { }` builder can). The `apiDump` was re-run: `PerFaceMaterialScope` appears in `isometric-shader.api` as a public class exposing DSL setters only. No external caller can `new PerFaceMaterialScope()`.

**Net effect on MED-13 intent:** The class name is visible in the API surface, but instantiation is locked out. The `@IsometricMaterialDsl` annotation remains. This is the correct Kotlin idiom for "public DSL scope, no direct construction."

## Test Count Summary

| Class | Tests (debug) | New since Round 1 | Notes |
|-------|--------------|------------------|-------|
| `IsometricMaterialTest` | 43 | ~8 (H-08 net after dedup, H-09 ×4) | Full TextureTransform + baseColor coverage |
| `PerFaceMaterialTest` | 9 | 1 (MED-08) | `perFace_of_noDefault_usesFallback` added |
| `UvGeneratorTest` | 10 | 0 | Unchanged |
| `TextureRenderUtilsTest` | 6 | 3 (MED-09) | `TextureCacheConfig` min/default/max tests |
| **Total** | **68** | **~12 net new** | 0 failures, 0 errors |

## Gaps / Unverified Areas

- **AC1 visual verification** — TextureTransform tiling (2× horizontal) confirmed correct by code inspection + unit tests but NOT tested on a physical device or emulator. Deferred to manual QA.
- **AC4 ambiguity in practice** — No test case verifies the "import only isometric-compose, both calls compile" scenario. `TexturedDemoActivity` explicitly uses `isometric-shader.Shape`, confirming the two-overload situation is in use.
- **MED-16 (deferred by PO)** — `PerFace.baseColor()` still returns `IsoColor.WHITE` rather than `default.baseColor()`. Intentional PO decision, not a test gap.

## Freshness Research

Not applicable — this slice is a pure internal API cleanup with no new external dependencies.

## Recommendation

Fix ISSUE-1 (delete `isometric-shader.Shape(IsometricMaterial)` and `Path()`) before or during the next review pass. ISSUE-2 has been corrected. The 19 review findings are resolved. At 12/13 AC, the slice is ready for final review.

## Recommended Next Stage

- **Option A (default): `/wf-review texture-material-shaders api-design-fixes`** — proceed with review; ISSUE-1 is LOW severity and AC4 was already documented in Round 1. Reviewer can confirm whether the second overload matters before merge to `feat/webgpu`.
- **Option B: `/wf-implement texture-material-shaders api-design-fixes`** — fix ISSUE-1 first (delete shader `Shape(IsometricMaterial)` + `Path()`, update `TexturedDemoActivity` import), then re-run verify. Surgical change, ~10 min.
- **Option C: `/wf-handoff texture-material-shaders api-design-fixes`** — skip review (solo project). Only appropriate if ISSUE-1 is accepted permanently.
