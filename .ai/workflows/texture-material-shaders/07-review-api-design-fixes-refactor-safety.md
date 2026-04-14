---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: api-design-fixes
review-command: refactor-safety
status: complete
updated-at: "2026-04-14T22:14:55Z"
metric-findings-total: 2
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 0
metric-findings-low: 0
metric-findings-nit: 2
result: clean
tags: [refactor-safety, api-design, kotlin]
refs:
  review-master: 07-review-api-design-fixes.md
---

## Findings

| # | ID | Severity | Confidence | File | Description |
|---|-----|----------|------------|------|-------------|
| 1 | RF-1 | NIT | High | `isometric-compose/…/IsometricNode.kt:240` | KDoc still references removed `` `FlatColor.color` `` type |
| 2 | RF-2 | NIT | High | `isometric-shader/…/UvCoord.kt` | File name does not match its primary public type (`TextureTransform`) |

## Detailed Findings

### RF-1 — Stale KDoc: `FlatColor.color` (NIT, High confidence)

**File:** `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricNode.kt:240`

```
*   (e.g., `FlatColor.color` for flat materials, `LocalDefaultColor` for textured).
```

`IsometricMaterial.FlatColor` was removed in this slice. The KDoc comment on the `@property color`
doc-block for `ShapeNode` still references it by name. This is a documentation error only — it does
not affect compilation or runtime behavior.

**Fix:** Update the KDoc to e.g.: `` `material.baseColor()` when material is non-null, or
`LocalDefaultColor` for bare flat-color renders ``.

---

### RF-2 — File name `UvCoord.kt` hosts public `TextureTransform` (NIT, High confidence)

**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvCoord.kt`

The file contains two top-level declarations:
- `internal data class UvCoord` — internal, not exported
- `data class TextureTransform` — **public**, the primary type introduced in this slice

Kotlin convention is to name a file after its primary public declaration. Since `UvCoord` is `internal`
and `TextureTransform` is the exported type, the file should be renamed to `TextureTransform.kt`.
This does not affect compilation or behavior but creates confusion when navigating imports and the
file tree.

**Fix:** Rename `UvCoord.kt` → `TextureTransform.kt` (or split into two files if `UvCoord` grows).

---

## Verified Clean

The following items from the review checklist were confirmed **clean** (no stale references found):

| Item | Result |
|------|--------|
| `FlatColor` in main-source `.kt` files | No matches (1 stale KDoc only — RF-1) |
| `UvTransform` in any `.kt` file | No matches |
| `BitmapSource` in any `.kt` file | No matches |
| `flatColor(` function call | No matches |
| `textured(` old bare form in any `.kt` | No matches |
| `FlatColor` branch in `when(material)` blocks | Absent — all `when` blocks match only `Textured` and `PerFace` |
| `PerFace.resolve()` in non-test main source | `IsometricMaterial.kt:74` only — declared `internal` there; no external call sites |
| `PerFace.resolve()` in test source | Present (19 calls across `PerFaceMaterialTest.kt` and `IsometricMaterialTest.kt`) — correct: tests are in same module as the `internal` declaration; Kotlin `internal` is visible within the same compilation unit |
| `PerFace.resolve()` in `SceneDataPacker` | Inlined to `m.faceMap[face] ?: m.default` — correct |
| `PerFace.resolve()` in `GpuTextureManager` | Inlined to `m.faceMap[face] ?: m.default` — correct |
| `PerFace.resolve()` in `TexturedCanvasDrawHook` | Inlined to `material.faceMap[face] ?: material.default` — correct |
| `UNASSIGNED_FACE_DEFAULT` accessed by tests | `internal` constant; test files are in same module — valid |
| `shaderCache` key includes `tileV` | Fixed: key is `Triple(source, tileU, tileV)` — correct |
| `data class PerFace` component functions in API dump | Absent — class is `class PerFace`, no `componentN` methods in `isometric-shader.api` |
| `resolve` in `isometric-shader.api` | Absent — `internal` methods do not appear in the public API dump |
| `UvCoord` or `UvGenerator` in API dump | Absent — both are `internal` |
| `FlatColor`, `flatColor`, `UvTransform`, `BitmapSource`, old `textured` in API dump | All absent |
| `Shape(color: IsoColor)` parameter in `isometric-compose.api` | Absent — `Shape` in compose API takes `MaterialData` (second param type is `Lio/github/jayteealao/isometric/MaterialData;`) |
| `UvCoord` / `UvGenerator` imported from external modules | No external imports found |

## Summary

The api-design-fixes slice is **refactor-safe**. All renamed, removed, and visibility-changed items
are consistently applied across the four modules (isometric-core, isometric-shader, isometric-webgpu,
isometric-compose). No stale `UvTransform`, `BitmapSource`, `FlatColor`, `flatColor`, or old `textured()`
references exist in any `.kt` source file. The `PerFace.resolve()` inlining was correctly applied in
`SceneDataPacker`, `GpuTextureManager`, and `TexturedCanvasDrawHook`. The `shaderCache` triple-key fix
(previously flagged as a low finding) is already present in the current code.

Two nit-level findings remain: a stale KDoc mention of the removed `FlatColor.color` type, and a
file-naming inconsistency (`UvCoord.kt` primarily hosts the public `TextureTransform` class). Neither
affects correctness or the public API surface.
