---
schema: sdlc/v1
type: implement
slug: texture-material-shaders
slice-slug: material-types
status: complete
stage-number: 5
created-at: "2026-04-11T22:32:12Z"
updated-at: "2026-04-11T23:37:39Z"
metric-files-changed: 8
metric-lines-added: 190
metric-lines-removed: 30
metric-deviations-from-plan: 0
metric-review-fixes-applied: 5
commit-sha: "6ba078f"
tags: [texture, material, module]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-material-types.md
  plan: 04-plan-material-types.md
  siblings: []
  verify: 06-verify-material-types.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders material-types"
---

# Implement: Material Type System & Module (rework per plan rev 3)

## Summary of Changes

Two-phase implementation:

**Phase 1 (commit 3dbb876):** Created `isometric-shader` module with material types,
DSL builders, `MaterialData` marker, `RenderCommand` extension, wired into compose.

**Phase 2 (this commit — rework):** Reversed the dependency: compose no longer depends on
shader. Shader now depends on compose and provides overloaded `Shape()`/`Path()` composables.

### Rework changes:
1. Removed `api(project(":isometric-shader"))` from `isometric-compose/build.gradle.kts`
2. Changed `ShapeNode.material` and `PathNode.material` from `IsometricMaterial?` to `MaterialData?`
3. Removed `material` parameter from compose's `Shape()` and `Path()` composables
4. Added `api(project(":isometric-compose"))`, `kotlin.compose` plugin, and `compose.runtime`
   dependency to `isometric-shader/build.gradle.kts`
5. Created `IsometricMaterialComposables.kt` with overloaded `Shape(geometry, material)` and
   `Path(path, material)` composables in `isometric-shader`

## Files Changed

### New files (1)
- `isometric-shader/src/main/kotlin/.../shader/IsometricMaterialComposables.kt`: Overloaded
  `Shape()` and `Path()` composables accepting `IsometricMaterial` instead of `IsoColor`

### Modified files (7)
- `isometric-compose/build.gradle.kts`: Removed `api(project(":isometric-shader"))`
- `isometric-compose/.../IsometricNode.kt`: `ShapeNode`/`PathNode` material type changed
  `IsometricMaterial?` → `MaterialData?`, import changed
- `isometric-compose/.../IsometricComposables.kt`: Removed `material` param from `Shape()`
  and `Path()`, removed shader import, reverted factory/update blocks
- `isometric-shader/build.gradle.kts`: Added `kotlin.compose` plugin, `compose = true`,
  `api(project(":isometric-compose"))`, `implementation(libs.compose.runtime)`
- `isometric-compose/api/isometric-compose.api`: Updated by apiDump (material param removed)
- `isometric-shader/api/isometric-shader.api`: Updated by apiDump (new composables added)

### Unchanged from Phase 1
- `isometric-shader/src/main/kotlin/.../shader/IsometricMaterial.kt` — types + DSL
- `isometric-shader/src/main/kotlin/.../shader/TextureSource.kt` — texture sources
- `isometric-shader/src/main/kotlin/.../shader/UvCoord.kt` — UV types
- `isometric-core/src/main/kotlin/.../MaterialData.kt` — marker interface
- `isometric-core/src/main/kotlin/.../RenderCommand.kt` — `material: MaterialData?` field
- `isometric-shader/src/test/.../IsometricMaterialTest.kt` — 16 unit tests

## Shared Files (also touched by sibling slices)
- `IsometricNode.kt` — uv-generation will add `uvProvider` lambda field
- `RenderCommand.kt` — per-face-materials will add `faceType`, atlas fields

## Notes on Design Choices
- Overloaded `Shape(geometry, material: IsometricMaterial)` in shader module resolves
  `FlatColor` to extract the color; other material types use `LocalDefaultColor.current`
- `ShapeNode.material` typed as `MaterialData?` from core, not `IsometricMaterial?` from
  shader — keeps compose free of shader dependency
- The `.also { it.material = material }` pattern in the factory block sets material after
  construction, since `ShapeNode`'s primary constructor only takes `shape` and `color`

## Deviations from Plan
None. All rework steps (8-12) executed as specified in rev 3.

## Anything Deferred
- `BatchNode` does not get material support — deferred to `per-face-materials` slice
- No rendering changes — material is carried through pipeline but ignored by renderers

## Known Risks / Caveats
- `isometric-shader` now has Compose Runtime as a dependency (heavier than before)
- Overload resolution: `Shape(geo, IsoColor)` vs `Shape(geo, IsometricMaterial)` — types
  are unrelated, Kotlin resolves unambiguously

## Review Fixes Applied

| ID | Severity | Status | Change |
|----|----------|--------|--------|
| SEC-1 | HIGH | Fixed | `TextureSource.Asset` init: reject `..`, leading `/`, null bytes |
| CS-1 | MED | Fixed | Replaced `TexturedBuilder` with named params on `textured()`/`texturedAsset()`/`texturedBitmap()`. Removed `@IsometricMaterialDsl` annotation class. |
| SEC-2 | MED | Fixed | `TextureSource.Resource` init: `require(resId > 0)` |
| API-1 | MED | Fixed | `PerFace` init: reject nested PerFace in `faceMap` and `default` |
| API-2 | MED | Fixed | KDoc on `ShapeNode`/`PathNode` documenting color/material contract |

New tests added: path traversal (2), absolute path (1), valid path (1), resource ID (1), nested PerFace (1).

## Freshness Research
No external dependency changes.

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders material-types` — verify build, tests, and backward compatibility
- **Option B:** `/wf-implement texture-material-shaders uv-generation` — proceed to next slice
