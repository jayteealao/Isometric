---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: api-design-fixes
review-command: testing
status: complete
updated-at: "2026-04-14T22:14:55Z"
metric-findings-total: 7
metric-findings-blocker: 0
metric-findings-high: 0
result: issues-found
tags: [testing, api-design, kotlin]
refs:
  review-master: 07-review-api-design-fixes.md
---

## Context

This is a **re-review** of the testing dimension after 19 fixes were applied to the
`api-design-fixes` slice. The previous pass (2026-04-14T17:42:09Z) raised T-01 (HIGH,
`TextureTransform.init` coverage), T-02 (HIGH, `baseColor()` branch coverage), and T-05
(MED, `TextureCacheConfig` coverage) as the highest-priority gaps. Those three have now
been closed. This pass reviews the updated test suite (68 tests total) and re-evaluates
all prior findings.

## Findings

| ID | Severity | Confidence | Area | Summary |
|----|----------|------------|------|---------|
| TST-01 | MED | High | `TextureTransform` — missing field coverage | `±Infinity` and `NaN` for `offsetU`/`offsetV`/`rotationDegrees` have no `-Infinity` branch; minor but asymmetric |
| TST-02 | MED | High | `TextureTransform` factory companions | `tiling()`, `rotated()`, `offset()` factory functions remain entirely untested |
| TST-03 | MED | High | `PerFace.of()` — default-is-PerFace guard | `require(default !is PerFace)` guard has no test; only the faceMap-nested case is covered |
| TST-04 | LOW | High | `PerFace.of()` — no-default path | `perFace_of_noDefault_usesFallback` covers `resolve()` but does not assert `.default == UNASSIGNED_FACE_DEFAULT` directly |
| TST-05 | LOW | High | `UvGenerator` lower-bound | `faceIndex = -1` lower-overflow case not tested |
| TST-06 | LOW | High | `onTextureLoadError` callback invocation | No test verifies the callback fires when `TextureLoader.load` returns null |
| TST-07 | NIT | Med | `TextureSource.Bitmap` | Constructor guard (`!bitmap.isRecycled`) and `ensureNotRecycled()` untested — Android type, acceptable gap |

---

## Prior Findings Status

| Prior ID | Prior Severity | Resolution |
|----------|----------------|------------|
| T-01 | HIGH | **Closed** — 15 new tests added covering all 5 fields: `NaN`, `+∞`, `−∞`, `zero`, `-0f` for `scaleU`/`scaleV`; `NaN`, `+∞` for `offsetU`/`offsetV`/`rotationDegrees`. All 8 reject-paths covered. |
| T-02 | HIGH | **Closed** — 4 new `baseColor_*` tests cover all branches: `IsoColor.baseColor()`, `Textured.baseColor()`, `PerFace.baseColor()`, anonymous `MaterialData.baseColor()`. |
| T-03 | MED | **Open → TST-02** — `tiling()`, `rotated()`, `offset()` companions still untested. |
| T-04 | MED | **Partially closed → TST-04** — `perFace_of_noDefault_usesFallback` was added and tests `resolve(FRONT) == UNASSIGNED_FACE_DEFAULT`, which exercises the path indirectly. The `.default` field itself is not directly asserted against `UNASSIGNED_FACE_DEFAULT`. |
| T-05 | MED | **Closed** — 3 new `TextureCache` constructor tests added: default `maxSize == 20`, `maxSize = 0` throws, `maxSize = -1` throws. Note: tests are in `TextureRenderUtilsTest`, not a dedicated `TextureCacheTest` class, but the coverage is present. |
| T-06 | LOW | **Open → TST-06** — `onTextureLoadError` callback invocation still untested. |
| T-07 | LOW | **Open → TST-05** — `faceIndex = -1` still missing. |
| T-08 | LOW | **Open → TST-03** — `default !is PerFace` guard still untested. |
| T-09 | NIT | **Open → TST-07** — `TextureSource.Bitmap` still absent from test suite. |
| T-10 | NIT | **Closed** — no action taken; accepted as acceptable gap. |

---

## Detailed Findings

### TST-01 — MED — `TextureTransform` partial coverage for offset and rotation fields

**Source file:** `isometric-shader/src/main/kotlin/.../shader/UvCoord.kt` (`TextureTransform.init`)

**Test file:** `IsometricMaterialTest.kt`

The new tests significantly improved coverage. However, coverage is asymmetric:

- `offsetU`: `NaN` and `+Infinity` tested; **`−Infinity` not tested**
- `offsetV`: only `NaN` tested; **`+Infinity` and `−Infinity` not tested** (there is no `textureTransform_infinityOffsetV_negativeInfinity` or `textureTransform_negativeInfinityOffsetV`)
- `rotationDegrees`: `NaN` and `+Infinity` tested; **`−Infinity` not tested**

The production code uses `isFinite()` which catches both `±Infinity` and `NaN` in a single call, so the gaps are not production bugs. But without the `−Infinity` tests for the three finite-only fields, a reviewer cannot verify the test suite itself is complete. The asymmetry between `scaleU/scaleV` (which test both `+∞` and `−∞`) and `offsetU/offsetV/rotationDegrees` (which test only one or two Infinity variants) creates a misleading impression that the tests are systematic when they are not.

**Recommendation:** Add `textureTransform_negativeInfinityOffsetU_throws`, `textureTransform_infinityOffsetV_throws`, `textureTransform_negativeInfinityOffsetV_throws`, `textureTransform_negativeInfinityRotationDegrees_throws`.

---

### TST-02 — MED — `TextureTransform` factory companions untested

**Source file:** `isometric-shader/src/main/kotlin/.../shader/UvCoord.kt`

`tiling(horizontal, vertical)`, `rotated(degrees)`, and `offset(u, v)` are public API surfaces that delegate field assignment. No test verifies:

- `TextureTransform.tiling(2f, 3f).scaleU == 2f && .scaleV == 3f` (guards against a future `scaleU`/`scaleV` swap)
- `TextureTransform.rotated(45f).rotationDegrees == 45f && .scaleU == 1f`
- `TextureTransform.offset(0.5f, 0.25f).offsetU == 0.5f && .offsetV == 0.25f`

These are the most ergonomic entry points for the `TextureTransform` API. A caller who finds a bug in `tiling()` currently has no regression test to prevent it from being reintroduced.

**Recommendation:** Add three tests — one per factory function — verifying all fields.

---

### TST-03 — MED — `PerFace.of(default = somePerFace)` guard untested

**Source file:** `isometric-shader/src/main/kotlin/.../shader/IsometricMaterial.kt` (line 72)

`PerFace.init` has two guards:
1. `require(faceMap.values.none { it is PerFace })` — covered by `PerFace rejects nested PerFace in faceMap`
2. `require(default !is PerFace)` — **not tested**

This is a symmetric validation. The omission means that if the second guard were accidentally deleted in a refactor, no test would fail. The guard prevents a specific error mode (a `PerFace` used as the fallback for another `PerFace`), and it warrants its own test.

**Recommendation:** Add:
```kotlin
@Test
fun `PerFace rejects PerFace as default`() {
    val inner = IsometricMaterial.PerFace.of(emptyMap())
    assertFailsWith<IllegalArgumentException> {
        IsometricMaterial.PerFace.of(emptyMap(), default = inner)
    }
}
```

---

### TST-04 — LOW — `PerFace.of()` no-default: indirect assertion only

**Source file:** `isometric-shader/src/main/kotlin/.../shader/IsometricMaterial.kt`

**Test:** `perFace_of_noDefault_usesFallback` in `PerFaceMaterialTest.kt`

The new test calls `PerFace.of(faceMap = mapOf(PrismFace.TOP to grass))` and asserts that `resolve(PrismFace.FRONT) == UNASSIGNED_FACE_DEFAULT`. This is a useful behavioral test, but it exercises the fallback indirectly via `resolve()`. If `.default` were assigned a different value but `resolve()` computed the same answer by coincidence, the test would still pass.

Directly asserting `mat.default == UNASSIGNED_FACE_DEFAULT` would pin the contract precisely. This is a minor style issue; the current test is sufficient for behavioral coverage.

**Recommendation:** Add `assertEquals(IsometricMaterial.PerFace.UNASSIGNED_FACE_DEFAULT, mat.default)` assertion to the existing test or a companion test.

---

### TST-05 — LOW — `UvGenerator` lower-bound not tested

**Source file:** `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt`

The `require(faceIndex in prism.paths.indices)` check covers both lower and upper overflow. `UvGeneratorTest` covers `faceIndex = 6` (upper bound) but not `faceIndex = -1` (lower bound). Both sides of a range check warrant explicit testing — the `in` operator performs a `>= 0 && < size` check and either boundary could be accidentally changed to an off-by-one.

**Recommendation:** Add `UvGenerator.forPrismFace(unitPrism, -1)` asserting `IllegalArgumentException`.

---

### TST-06 — LOW — `onTextureLoadError` callback invocation untested

**Source file:** `isometric-shader/src/main/kotlin/.../shader/render/TexturedCanvasDrawHook.kt` (line 149)

`resolveToCache()` calls `onTextureLoadError?.invoke(source)` when the loader returns null, then caches the checkerboard. No test verifies this callback fires. The callback is an observable API contract visible to callers of `ProvideTextureRendering(onTextureLoadError = { ... })`. Without a test, a refactor that silently removes the `invoke()` call would not be caught.

A pure-logic test using a stub loader returning null and a stub bitmap for the checkerboard would require `android.graphics.Bitmap`. This is an Android type, making it unsuitable for the plain JVM test set. It should be filed as a gap for an instrumented or Robolectric test.

**Recommendation:** Add an instrumented or Robolectric test: construct `TexturedCanvasDrawHook` with a `TextureLoader` stub that returns null and verify that the `onTextureLoadError` lambda is called exactly once with the expected `TextureSource`.

---

### TST-07 — NIT — `TextureSource.Bitmap` absent from test suite

**Source file:** `isometric-shader/src/main/kotlin/.../shader/TextureSource.kt`

`TextureSource.Bitmap` has two contract points: `require(!bitmap.isRecycled)` in `init` and `ensureNotRecycled()`. Both require `android.graphics.Bitmap`. No plain JVM tests are feasible. This is an accepted instrumented-test gap.

---

## Untested Areas Assessment

| Area | Coverage | Gap Level |
|------|----------|-----------|
| `TexturedCanvasDrawHook` — TileMode selection | None (Android dep) | Instrumented test gap |
| `TexturedCanvasDrawHook` — `colorFilterFor` caching | None (Android dep) | Instrumented test gap |
| `TexturedCanvasDrawHook` — `shaderCache` LRU eviction | None (Android dep) | Instrumented test gap |
| `computeAffineMatrix` — non-identity `TextureTransform` | None (Android dep) | Instrumented test gap |
| `onTextureLoadError` callback propagation | None | TST-06 (LOW) |
| `PerFace.of()` — empty map | Covered (`empty faceMap resolves every face to default`) | OK |
| `PerFace.of()` — full map (all 6 faces) | Covered (`resolve for all 6 PrismFace values`) | OK |
| `PerFace.of()` — partial map | Covered (`resolve returns default for faces not in faceMap`) | OK |
| `TextureSource.Asset` — path traversal | Covered (blank, `..`, absolute, valid) | OK |
| `TextureSource.Asset` — null byte | **Not tested** | Minor gap |
| `PerFaceMaterialScope` DSL — all face setters | `top`, `sides`, `front`, `back`, `left`, `right` all exercised across the two test files | OK |

**Null byte path:** `TextureSource.Asset` validates `'\u0000' !in path` but no test covers
this. Given the path-traversal tests are already present, this is a small completeness gap.
The guard is defense-in-depth (the primary traversal guards are `..` and absolute path),
so the risk of the null-byte guard being broken silently is low.

---

## Summary

The two HIGH findings from the prior pass (T-01, T-02) have been fully resolved: 15 new
`TextureTransform.init` tests cover all five fields with NaN, ±Infinity, zero, and -0f
variants (though minor asymmetry remains — TST-01 is MED). The four `baseColor()` branch
tests fully cover the dispatch chain. T-05 (`TextureCacheConfig`) is also closed with three
new `TextureCache` constructor tests.

No new HIGH or BLOCKER findings were identified. The suite has three MED gaps (TST-01
asymmetric coverage, TST-02 factory companions, TST-03 default-is-PerFace guard) and three
LOW gaps. None of the remaining gaps represent observable production bugs. The Android-type
constraint correctly defers `TexturedCanvasDrawHook` testing to instrumented tests.

The test suite is in a significantly healthier state after this fix pass.
