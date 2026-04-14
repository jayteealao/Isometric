---
schema: sdlc/v1
type: slice
slug: texture-material-shaders
slice-slug: api-design-fixes
status: defined
stage-number: 3
created-at: "2026-04-13T22:28:45Z"
updated-at: "2026-04-13T23:06:23Z"
complexity: m
depends-on: [material-types, uv-generation, canvas-textures, webgpu-textures, per-face-materials, sample-demo]
tags: [api-design, texture, material, review]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings:
    - 03-slice-material-types.md
    - 03-slice-uv-generation.md
    - 03-slice-canvas-textures.md
    - 03-slice-webgpu-textures.md
    - 03-slice-per-face-materials.md
    - 03-slice-sample-demo.md
  review-source: 07-review-webgpu-textures-api.md
  plan: 04-plan-api-design-fixes.md
  implement: 05-implement-api-design-fixes.md
---

# Slice: api-design-fixes

## Architecture Decision

This slice adopts a revised module architecture that supersedes the original
`po-answers` entry about dependency direction. The full picture:

```
isometric-core  (pure JVM — no Android)
  MaterialData              ← marker interface (unchanged)
  IsoColor : MaterialData   ← IsoColor gains the MaterialData marker (one-line change)
       ↑ api
isometric-compose  (Android + Compose)
  Shape(geometry, material: MaterialData)     ← REPLACES Shape(geometry, color: IsoColor)
       ↑ api
isometric-shader  (Android + Compose)
  IsometricMaterial : MaterialData            ← sealed over Textured + PerFace ONLY
  (IsometricMaterial.FlatColor removed entirely — IsoColor itself is the flat-color material)
       ↑ api
isometric-webgpu
```

**What this enables:**
- No new `FlatColor` wrapper type. `IsoColor` is already the flat-color concept — it
  implements `MaterialData` directly. One fewer type to explain.
- An app with only `isometric-compose` uses `Shape(Prism(), IsoColor.BLUE)` unchanged
  (positional call — `IsoColor` is now a `MaterialData`).
- An app with `isometric-shader` passes `textured(...)` or `perFace { }` to the same
  `Shape()` parameter. No name collision, no import alias.
- `PerFace` face assignments accept `IsoColor` directly: `bottom = IsoColor.GRAY`.
- Dependency graph: unchanged. No new edges, no cycles.

**Breaking changes accepted for this slice:**
- `Shape(geometry, color: IsoColor)` → `Shape(geometry, material: MaterialData)`
  in `isometric-compose`. **Positional call sites are unaffected** — `IsoColor` is
  now a `MaterialData`, so `Shape(Prism(), IsoColor.BLUE)` continues to compile.
  Named-argument call sites (`color = IsoColor.BLUE`) must rename to `material =`.
- `IsometricMaterial.FlatColor` removed from `isometric-shader`. All internal
  references replaced with `IsoColor` directly.

---

## Goal

Resolve all 25 findings from the post-retro API design review
(`07-review-webgpu-textures-api.md`) to bring the `isometric-shader` public
surface into full compliance with `docs/internal/api-design-guideline.md` before
PR #8 merges to `feat/webgpu`.

## Why This Slice Exists

The original six slices focused on shipping working texture/material rendering.
After retro, a systematic review against the 12 API design guideline sections
surfaced 10 HIGH and 12 MEDIUM findings. The most severe (TM-API-2: `UvTransform`
silently does nothing; TM-API-3: `PerFace.default` is transparent black; TM-API-10:
`Shape()` name collision) must be fixed before any external consumer depends on
these API shapes. The branch hasn't merged to master — this is the lowest-cost
window for breaking changes.

## Scope

### In — all 25 findings from `07-review-webgpu-textures-api.md`

**HIGH — must fix (10)**

| ID | Area | Change |
|----|------|--------|
| TM-API-1 | KDoc | Fix broken compile example in `IsometricMaterial` (line 17): remove fake `{ uvScale(...) }` overload |
| TM-API-2 | Implementation | Apply `TextureTransform` (renamed from `UvTransform`) in `computeAffineMatrix` on the Canvas path — scale, offset, rotation composited onto the affine matrix after `setPolyToPoly` |
| TM-API-3 | Default | Change `PerFace.default` and `PerFaceMaterialScope.default` from `FlatColor(IsoColor(0,0,0,0))` to `IsoColor(128,128,128,255)` (visible gray — `IsoColor` is now the flat-color material directly); extract to a shared `internal val UNASSIGNED_FACE_DEFAULT: MaterialData = IsoColor(128,128,128,255)` constant |
| TM-API-4 | Visibility | Mark `UvGenerator` `internal`; remove from public API surface |
| TM-API-5 | Type system | Decision: make `UvCoord` `internal` (it is disconnected from all data paths); revisit in a future UV-typed-API slice |
| TM-API-6 | Errors | Add `Log.w("IsometricShader", "Failed to load texture: $source", e)` in each catch block of `TextureLoader`; add optional `onTextureLoadError` callback to `ProvideTextureRendering` |
| TM-API-7 | Composition | Extract `fun MaterialData.toBaseColor(): IsoColor` as internal extension; the `when` covers `is IsoColor -> this` (no unwrapping — `IsoColor` is already the color), `is IsometricMaterial.Textured -> tint`, `is IsometricMaterial.PerFace -> IsoColor.WHITE`, and `else -> IsoColor.WHITE`; replace the two duplicated blocks in `IsometricMaterialComposables.kt` with a single call |
| TM-API-8 | Errors | Add `@throws IllegalArgumentException` to `UvGenerator.forPrismFace` KDoc; add explicit bounds check with diagnostic message at the top of the function; wrap the `UvCoordProvider` call site in a try-catch that rethrows with shape context |
| TM-API-9 | Validation | Add `init` block to `TextureTransform` (was `UvTransform`) requiring all five fields are `isFinite()` and `scaleU`/`scaleV` are non-zero |
| TM-API-10 | Naming / API | **Revised approach** (see Architecture Decision above): change `isometric-compose.Shape()` to `Shape(geometry, material: MaterialData)`; `IsoColor : MaterialData` so **positional call sites are unchanged** — `Shape(Prism(), IsoColor.BLUE)` compiles without modification; only named-arg call sites (`color = IsoColor.BLUE`) must rename to `material =`; no bridge extension needed; no import alias needed for shader users; delete `isometric-shader.Shape(geometry, material: IsometricMaterial)` overload — one `Shape()` in `isometric-compose` covers all cases |

**MEDIUM — should fix (12)**

| ID | Area | Change |
|----|------|--------|
| TM-API-11 | KDoc | Add "Requires `ProvideTextureRendering`" note to `textured()`, `texturedAsset()`, `texturedBitmap()` KDoc |
| TM-API-12 | Naming | Add `texturedResource()` as an alias for `textured()` with a `@Deprecated("Use texturedResource()", ReplaceWith("texturedResource(...)"))` on the short form — or rename directly. Decide in plan phase. |
| TM-API-13 | Naming | Rename `TextureSource.BitmapSource` → `TextureSource.Bitmap`; update all call sites; run `apiDump` |
| TM-API-14 | Naming | Rename `UvTransform` → `TextureTransform` in `UvCoord.kt`; add companion factory functions: `tiling(horizontal, vertical)`, `rotated(degrees)`, `offset(u, v)`; rename parameter `uvTransform` → `transform` at all call sites; run `apiDump` |
| TM-API-15 | Visibility | Mark `PerFace.resolve()` `internal` |
| TM-API-16 | DSL | Add `@DslMarker annotation class IsometricMaterialDsl`; annotate `PerFaceMaterialScope` with it |
| TM-API-17 | KDoc | Expand `maxCacheSize` KDoc: unit (distinct TextureSources), eviction behavior (LRU, sync decode on miss), sizing guidance |
| TM-API-18 | KDoc | Document `alpha` vs `tint.alpha` interaction in `Shape()` and `Path()` KDoc |
| TM-API-19 | Invalid states | Add `require(material !is IsometricMaterial.Textured && material !is IsometricMaterial.PerFace) { ... }` in the `Path()` overload — or document the silent fallback prominently; decide in plan phase |
| TM-API-20 | KDoc | Add sealed-type evolution note to `TextureSource` matching the one on `IsometricMaterial` |
| TM-API-21 | Evolution | Introduce `TextureCacheConfig(val maxSize: Int = 20)` data class; change `ProvideTextureRendering` signature to `cacheConfig: TextureCacheConfig = TextureCacheConfig()` |
| TM-API-22 | KDoc | Expand `ProvideTextureRendering` KDoc with scoping rules (single provider per subtree, nesting semantics, cache sharing) |

**LOW — fix or document (3)**

| ID | Area | Change |
|----|------|--------|
| TM-API-23 | Escape hatch | Promote `TextureLoader` to a public `fun interface TextureLoader { suspend fun load(source: TextureSource): Bitmap? }`; add `loader: TextureLoader = defaultTextureLoader(context)` parameter to `ProvideTextureRendering`; document `LocalMaterialDrawHook` escape hatch in KDoc |
| TM-API-24 | Composition | Move `BitmapShader` creation from `TextureCache.put()` into `TexturedCanvasDrawHook.resolveTexture()`; `CachedTexture` stores only `Bitmap`; hook creates shader on first draw |
| TM-API-25 | API surface | Make `PerFace` primary constructor `internal`; expose `PerFace.of(faceMap, default)` named factory for advanced users who need map construction |

### Out — explicitly deferred

- Implementing `TextureTransform` on the WebGPU path (Canvas-only in this slice; WebGPU UV transform is a separate shader change)
- UV generation for non-Prism shapes (Cylinder, Pyramid, etc.)
- Multi-atlas support, animated UVs, network texture loading
- Maestro test flows and pixel readback tests (tracked in retro)

## Acceptance Criteria

- **AC1: TM-API-2 applied** — Given `textured(R.drawable.brick, transform = TextureTransform(scaleU = 2f))`, when rendered in Canvas mode, then the texture is tiled 2× horizontally across the face (visually verifiable on device).
- **AC2: TM-API-3 default** — Given `perFace { top = texturedBitmap(grass) }`, when rendered, then unassigned faces render gray (not transparent/invisible).
- **AC3: TM-API-10 / architecture — positional `Shape()` call sites unchanged** — Given existing code `Shape(Prism(origin), IsoColor.BLUE)` (positional), it compiles without modification after the `color: IsoColor` → `material: MaterialData` parameter rename, because `IsoColor : MaterialData`.
- **AC4: TM-API-10 / architecture — one `Shape()`, no import alias** — Given a file that imports only `isometric-compose`, both `Shape(Prism(origin), IsoColor.BLUE)` and `Shape(Prism(origin), texturedBitmap(img))` (with `isometric-shader` on classpath) compile with no ambiguity and no import alias.
- **AC5: `IsoColor` implements `MaterialData`** — Given `val m: MaterialData = IsoColor.BLUE`, this compiles from `isometric-core` alone with no Android or shader dependency.
- **AC6: `IsometricMaterial` no longer contains `FlatColor`** — `IsometricMaterial` is sealed over `Textured` and `PerFace` only; `IsometricMaterial.FlatColor` does not exist; `apiDump` for `isometric-shader` does not list it.
- **AC7: Renames compile** — `TextureTransform`, `TextureSource.Bitmap`, `texturedResource` (or equivalent) are the public names; old names are absent or deprecated.
- **AC8: `UvGenerator` not in public API** — `apiCheck` passes after running `apiDump`; `UvGenerator` does not appear in the public API dump.
- **AC9: Texture load errors logged** — A missing drawable resource produces a `Log.w` with the source and exception; no crash.
- **AC10: `toBaseColor()` covers all `MaterialData`** — The extracted `MaterialData.toBaseColor()` handles `FlatColor` (from core), `Textured`, `PerFace`, and an `else` branch for unknown implementations.
- **AC11: `TextureCacheConfig` exists** — `ProvideTextureRendering(cacheConfig = TextureCacheConfig(maxSize = 50))` compiles and configures the cache.
- **AC12: All existing tests pass** — No regressions after call-site migration from `IsoColor` to `FlatColor`/`asMaterial()`.
- **AC13: `TextureTransform.init` validates** — `TextureTransform(scaleU = Float.NaN)` throws `IllegalArgumentException` at construction.

## Dependencies on Other Slices

All six prior slices must be complete (they are). This slice touches all four modules:

| Module | Change scope |
|--------|-------------|
| `isometric-core` | Add `FlatColor(color: IsoColor) : MaterialData` (new file) |
| `isometric-compose` | `Shape()` signature change; add `IsoColor.asMaterial()`; migrate all internal `IsoColor` usages to `FlatColor`; update tests |
| `isometric-shader` | Remove `IsometricMaterial.FlatColor`; update all call sites; `TextureTransform` rename; all other 22 findings |
| `isometric-webgpu` | `TextureTransform` rename propagation; `FlatColor` import path update |

## Module Dependency Direction

The dependency graph is **unchanged** from the original design:

```
isometric-core ← isometric-compose ← isometric-shader ← isometric-webgpu
```

No new edges are introduced. `FlatColor` moving to `isometric-core` means it travels
**down** the graph (toward fewer dependencies), which is always safe. `isometric-compose`
continues to depend only on `isometric-core`. `isometric-shader` continues to depend
on `isometric-compose`. The constraint "isometric-compose must not depend on
isometric-shader" is preserved — in fact it is reinforced, because the base `Shape()`
now accepts `MaterialData` (a core type), not `IsometricMaterial` (a shader type).

## Risks

- **`IsoColor` call-site migration surface:** `Shape(geometry, color: IsoColor)` is used in every existing sample, test, and internal call site across all four modules. Each becomes `Shape(geometry, FlatColor(IsoColor.X))` or `Shape(geometry, IsoColor.X.asMaterial())`. The scope is mechanical but large — plan phase should inventory all call sites before committing to the approach.

- **TM-API-2 (TextureTransform application):** Matrix composition order matters — applying scale/rotation on top of `setPolyToPoly` without understanding the UV coordinate frame could produce skewed output. Plan phase must derive the correct post-multiply order.

- **`IsometricMaterial` sealed losing `FlatColor`:** Renderers that currently `when`-match on `IsometricMaterial` exhaustively over three cases now match over two. Any `when` that used `FlatColor` as a branch must be updated. `toBaseColor()` (TM-API-7) handles this centrally, but non-color dispatches (e.g., `TexturedCanvasDrawHook`'s material-type check) also need auditing.

- **`FlatColor` in `isometric-core` must remain pure JVM:** `isometric-core` has no Android dependencies. `FlatColor` can only hold `IsoColor` — it cannot hold `androidx.compose.ui.graphics.Color`. The Compose-Color conversion lives in `isometric-compose` as an extension. Any temptation to add `@ColorInt` or `android.graphics.Color` to `FlatColor` would break `isometric-core`'s pure-JVM constraint.

- **TM-API-14 (Rename UvTransform → TextureTransform):** The field name `uvTransform` appears in the `Textured` data class — renaming to `transform` affects `copy()` calls and named-argument call sites. All internal usages must be updated atomically.

- **apiCheck regressions:** Removing `IsometricMaterial.FlatColor`, renaming `BitmapSource`, and changing `Shape()` signatures are all public API removals/changes. Run `apiDump` before the first change, verify the delta with `apiCheck` after each rename, and record intentional removals in the `.api` file.

## Implementation Cross-link
- Implemented in: [05-implement-api-design-fixes.md](05-implement-api-design-fixes.md)
