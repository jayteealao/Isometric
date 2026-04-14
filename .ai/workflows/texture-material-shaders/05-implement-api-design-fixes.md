---
schema: sdlc/v1
type: implement
slug: texture-material-shaders
slice-slug: api-design-fixes
status: complete
stage-number: 5
created-at: "2026-04-14T06:46:32Z"
updated-at: "2026-04-14T21:25:24Z"
metric-files-changed: 23
metric-lines-added: 620
metric-lines-removed: 390
metric-deviations-from-plan: 2
metric-review-fixes-applied: 19
metric-review-fixes-original-slice: 25
commit-sha: "1eb8f19"
commit-sha-review-fixes: ""
tags: [api-design, texture, material, shader]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-api-design-fixes.md
  plan: 04-plan-api-design-fixes.md
  siblings:
    - 05-implement-material-types.md
    - 05-implement-canvas-textures.md
    - 05-implement-per-face-materials.md
    - 05-implement-webgpu-textures.md
    - 05-implement-sample-demo.md
  verify: 06-verify-api-design-fixes.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders api-design-fixes"
review-fixes-status: complete
---

# Implement: API Design Fixes (TM-API-1 through TM-API-25)

This slice applies all 25 API design review findings from `07-review-webgpu-textures-api.md`
to bring the `isometric-shader` module into full compliance with `docs/internal/api-design-guideline.md`.

## Summary of Changes

- **`IsoColor : MaterialData`** — `IsoColor` in `isometric-core` now implements `MaterialData` directly,
  enabling positional `Shape(Prism(origin), IsoColor.BLUE)` without a shader-layer wrapper
- **`FlatColor` removed entirely** — `IsometricMaterial.FlatColor`, `flatColor()`, and all references
  deleted; `IsoColor` fills its role at the core layer
- **`UvTransform` → `TextureTransform`** — renamed with `init` validation (finite + non-zero scale),
  factory companions (`IDENTITY`, `tiling()`, `rotated()`, `offset()`), and made publicly accessible
- **`UvCoord` internalized** — not part of the public API; moved to `internal`
- **`UvGenerator` internalized** — implementation detail; `internal` + bounds check + `@throws` KDoc
- **`TextureSource.BitmapSource` → `TextureSource.Bitmap`** — shorter, idiomatic; evolution KDoc added
- **`textured()` → `texturedResource()`** — intent-expressive name; `texturedAsset()` and
  `texturedBitmap()` complete the trio
- **`TextureLoader` as `fun interface`** — escape hatch for custom loading; `defaultTextureLoader()`
  is the `internal` factory returning `DefaultTextureLoader`
- **`TextureCacheConfig` data class** — replaces raw `maxSize: Int` in `ProvideTextureRendering`
- **`onTextureLoadError` callback** — `((TextureSource) -> Unit)?` param on `ProvideTextureRendering`
- **`@IsometricMaterialDsl` `@DslMarker`** — prevents inadvertent nesting in `PerFaceMaterialScope`
- **`PerFace.faceMap: Map<PrismFace, MaterialData>`** — widened from `IsometricMaterial` to accept
  `IsoColor` directly as face material
- **`PerFace private constructor` + `PerFace.of()`** — factory enforces `init` invariants;
  `@ConsistentCopyVisibility` silences Kotlin 2.0 `copy()` visibility warning
- **`PerFace.resolve()` → `internal`** — implementation detail; cross-module callers inlined to
  `faceMap[face] ?: default`
- **`MaterialData.toBaseColor()` internal extension** — single function replacing duplicated `when` blocks
- **`Shape(geometry, material: MaterialData)` in compose** — parameter renamed `color → material`
- **KDoc sweep** — KDoc on `MaterialData`, `TextureSource`, `TextureTransform`, `TextureLoader`,
  `ProvideTextureRendering`, `TextureCacheConfig`, `PerFaceMaterialScope` updated or added
- **Test files rewritten** — `IsometricMaterialTest.kt` and `PerFaceMaterialTest.kt` updated to
  new API; new tests for `TextureTransform` validation and `UvCoord` companion values
- **API dumps updated** — `apiDump` + `apiCheck` passing; all removals are intentional

## Files Changed

### isometric-core
- `IsoColor.kt` — added `: MaterialData` to class declaration
- `MaterialData.kt` — KDoc updated to list `IsoColor` and `IsometricMaterial` as implementors
- `api/isometric-core.api` — dump updated (additive: `IsoColor` now implements `MaterialData`)

### isometric-shader (main)
- `UvCoord.kt` — `UvCoord` made `internal`; `UvTransform` renamed to `TextureTransform` with
  validation, factory companions; file now exports `TextureTransform` only
- `TextureSource.kt` — `Bitmap` nested class (was `BitmapSource`); evolution KDoc
- `UvGenerator.kt` — `internal`; bounds check; `@throws IllegalArgumentException` KDoc
- `IsometricMaterial.kt` — `FlatColor` removed; `PerFace` reworked (private ctor, `of()` factory,
  widened faceMap, `@ConsistentCopyVisibility`); `@DslMarker`; `UNASSIGNED_FACE_DEFAULT`
- `IsometricMaterialComposables.kt` — `toBaseColor()` extension; `texturedResource/Asset/Bitmap()`;
  `Path()` guard; `Shape()` uses `material: MaterialData`
- `api/isometric-shader.api` — dump updated (FlatColor, flatColor, UvTransform, UvCoord,
  UvGenerator, BitmapSource, textured, PerFace.resolve, PerFace.<init> all removed)

### isometric-shader (render)
- `render/TextureLoader.kt` — `fun interface TextureLoader`; `defaultTextureLoader()` factory;
  `Log.w` on load errors
- `render/TextureCache.kt` — `CachedTexture` stores `Bitmap` only (no `BitmapShader`)
- `render/TexturedCanvasDrawHook.kt` — `TextureTransform` applied via `T^-1` pre-concat in
  `computeAffineMatrix()`; `shaderCache` keyed by `(TextureSource, TileMode)`; `onTextureLoadError`;
  `FlatColor` branch removed from `when`
- `render/ProvideTextureRendering.kt` — `TextureCacheConfig` data class; `loader: TextureLoader?`;
  `onTextureLoadError` forwarded to hook

### isometric-compose
- `compose/runtime/IsometricComposables.kt` — `Shape()` param `color: IsoColor` → `material: MaterialData`
- `api/isometric-compose.api` — dump updated
- `androidTest/TileGridTest.kt` — `color = IsoColor(...)` → `material = IsoColor(...)`
- `androidTest/IsometricRendererNativeCanvasTest.kt` — `color = IsoColor.BLUE` → `material = IsoColor.BLUE`

### isometric-webgpu
- `webgpu/texture/GpuTextureManager.kt` — `TextureSource.BitmapSource` → `TextureSource.Bitmap`;
  `m.resolve(face)` → `m.faceMap[face] ?: m.default`
- `webgpu/pipeline/SceneDataPacker.kt` — `m.resolve(face)` → `m.faceMap[face] ?: m.default`
- `api/isometric-webgpu.api` — dump updated

### app
- `sample/TexturedDemoActivity.kt` — removed `Shape as MaterialShape` alias; uses `texturedBitmap()`

### isometric-shader (tests)
- `test/IsometricMaterialTest.kt` — full rewrite; FlatColor/flatColor removed; UvTransform→TextureTransform;
  textured→texturedResource; PerFace(…)→PerFace.of(…); new TextureTransform validation tests
- `test/PerFaceMaterialTest.kt` — full rewrite; same API rename sweep; faceMap/default widening verified

## Shared Files (also touched by sibling slices)
- `IsometricComposables.kt` — also touched by `material-types` and `uv-generation` slices; this
  slice's change (`color → material` rename) is additive and the last modification
- `GpuTextureManager.kt` — also touched by `webgpu-textures` and `per-face-materials`; this slice
  only inlined `resolve()` call

## Notes on Design Choices

**`PerFace.resolve()` made `internal`:** The method was per the plan. Cross-module callers
(`GpuTextureManager`, `SceneDataPacker`) were updated to inline `faceMap[face] ?: default` — the
full body of `resolve()`. This avoids a public API surface for an implementation detail.

**`PerFace` converted from `data class` to `class` (review fix MED-14):** The post-review fix
converted `PerFace` from a `data class` to a regular `class` with manual `equals`/`hashCode`/`toString`.
This removes the auto-generated `component1()`/`component2()` functions that leaked internal fields.
The `@ConsistentCopyVisibility` annotation was removed along with the `data` modifier.
`copy()` was not added since no call sites existed.

**`T^-1` pre-concat for `TextureTransform`:** The `BitmapShader.setLocalMatrix(M)` contract means
the matrix maps from _screen_ to _bitmap_ coordinates. Applying `T^-1` (the inverse of the UV
transform) before the poly-to-poly mapping correctly applies tiling/offset/rotation in UV space,
not in screen space.

**`TileMode` selected per-draw (corrected post-review):** After review fix H-01, `REPEAT` is now
selected when `transform != TextureTransform.IDENTITY` (covers rotation/offset, not just scale).
After H-02, `shaderCache` key is `Triple(source, tileU, tileV)` — both tile modes are included.
After H-03, `transformMatrix`/`transformMatrixInv` are pre-allocated fields (no per-draw allocation).

## Deviations from Plan

1. **`resolve()` inlining required in webgpu module (post-apiDump fix):** The plan said to make
   `resolve()` `internal` without flagging that `isometric-webgpu` is a separate compilation unit.
   Discovered at `apiDump` time (`compileReleaseKotlin` failed). Fixed by inlining
   `faceMap[face] ?: default` in two webgpu callers (2 additional file edits not in the plan).

2. **`@ConsistentCopyVisibility` added (unplanned):** The plan didn't mention this annotation.
   Added at `apiDump` time to suppress a Kotlin 2.0 compiler warning about `copy()` visibility on
   `data class PerFace private constructor`. Behavioural improvement: `copy()` is now also private.

## Anything Deferred

- `TextureTransform` not yet propagated to the WebGPU pipeline (UV transforms only apply in
  Canvas rendering). When WebGPU UV transforms are needed, `SceneDataPacker` should write the
  transform matrix into the per-face UV region buffer.
- `onTextureLoadError` not yet plumbed into the WebGPU path (`GpuTextureManager` still uses
  `Log.w` directly).

## Known Risks / Caveats

- `Shape(geometry, material: MaterialData)` rename in `isometric-compose` is a **source-breaking**
  change for any caller using `color =` named argument. All known call sites in this repo are updated.
  External consumers will get a compile error on upgrade.
- `copy()` on `PerFace` is now private (via `@ConsistentCopyVisibility`). Any code that called
  `perFaceInstance.copy(...)` will no longer compile. Since `PerFace` was always an opaque returned
  from `perFace { }`, this is unlikely to affect existing callers, but is technically a breaking change.

## Freshness Research

No external API freshness research was needed — this slice is an internal API cleanup with no new
library dependencies. The `@ConsistentCopyVisibility` annotation was verified against Kotlin 2.0
release notes (project uses Kotlin 2.0.21).

## Review Fixes Applied

Applied after `wf-review` verdict (ship-with-caveats). All 8 HIGH + 11 MED triaged findings fixed.

| ID | Sev | File(s) | Fix Summary | Status |
|----|-----|---------|-------------|--------|
| H-01 | HIGH | TexturedCanvasDrawHook.kt:86 | TileMode REPEAT condition → `transform != TextureTransform.IDENTITY`; also resolves MED-02 | Fixed |
| H-02 | HIGH | TexturedCanvasDrawHook.kt | shaderCache key `Pair<src,tile>` → `Triple<src,tileU,tileV>` | Fixed |
| H-03 | HIGH | TexturedCanvasDrawHook.kt | Pre-allocate `transformMatrix`/`transformMatrixInv` as class fields; defaulted params for test compatibility | Fixed |
| H-04 | HIGH | IsometricMaterial.kt | `UNASSIGNED_FACE_DEFAULT` moved from package level → `PerFace.Companion` | Fixed |
| H-05 | HIGH | TextureLoader.kt | Added `@return the loaded [Bitmap], or null if the source could not be loaded` KDoc | Fixed |
| H-07 | HIGH | TexturedCanvasDrawHook.kt:65 | `material.resolve(face)` → `material.faceMap[face] ?: material.default` | Fixed |
| H-08 | HIGH | IsometricMaterialTest.kt | 15 new tests covering all TextureTransform.init require() paths (Infinity, NaN, zero, -0f, offsets, rotation) | Fixed |
| H-09 | HIGH | IsometricMaterialTest.kt | 4 new tests for baseColor() branches (IsoColor, Textured, PerFace, anonymous MaterialData) | Fixed |
| MED-01 | MED | UvCoord.kt | Split combined require → separate `isFinite()` + `absoluteValue > 0f` checks for -0f robustness | Fixed |
| MED-02 | MED | TexturedCanvasDrawHook.kt | Corollary of H-01 — resolved as part of same change | Fixed |
| MED-03 | MED | ProvideTextureRendering.kt | `rememberUpdatedState(loader)` — loader removed from remember() key | Fixed |
| MED-04 | MED | TextureSource.kt | Defense-in-depth: null-byte check + literal `..` check + `File.normalize()` post-normalization | Fixed |
| MED-05 | MED | TextureLoader.kt | Asset path redacted in logs (`Asset(path=<redacted>)`); resource logs show integer ID only | Fixed |
| MED-08 | MED | PerFaceMaterialTest.kt | `perFace_of_noDefault_usesFallback` — verifies unmapped faces return `UNASSIGNED_FACE_DEFAULT` | Fixed |
| MED-09 | MED | TextureCacheTest.kt | 3 tests: default maxSize=20, require fires for maxSize=0 and maxSize=-1 | Fixed |
| MED-13 | MED | IsometricMaterial.kt + api dump | `PerFaceMaterialScope` made `internal`; removed from API dump | Fixed |
| MED-14 | MED | IsometricMaterial.kt + api dump | `data class PerFace` → `class PerFace`; manual equals/hashCode/toString; componentN() removed from dump | Fixed |
| MED-15 | MED | TexturedCanvasDrawHook.kt | `shaderCache` LRU via `LinkedHashMap(accessOrder=true)` + `removeEldestEntry` at `cache.maxSize * 2` | Fixed |
| MED-17 | MED | ProvideTextureRendering.kt | `rememberUpdatedState(onTextureLoadError)` — same pattern as MED-03 | Fixed |
| MED-19 | MED | TexturedCanvasDrawHook.kt | `colorFilterFor(tint)` caches `PorterDuffColorFilter` by last tint color; white short-circuits to null | Fixed |

Total fixed: 19 (8 HIGH + 11 MED including MED-02). MED-16 deferred by PO decision.

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders api-design-fixes` — re-run unit tests
  to confirm review fixes hold; apiCheck to verify API dump changes are consistent
- **Option B:** `/compact` then Option A — clear review context before re-verification
