---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: material-types
reviewer-focus: code-simplification
status: complete
stage-number: 7
created-at: "2026-04-11T00:00:00Z"
updated-at: "2026-04-11T00:00:00Z"
result: findings
finding-count: 4
finding-ids: [CS-1, CS-2, CS-3, CS-4]
tags: [texture, material, simplification, review]
---

# Code Simplification Review — material-types slice

## Scope

Files reviewed:

- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterial.kt`
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/TextureSource.kt`
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvCoord.kt`
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialComposables.kt`
- `isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialTest.kt`

---

## Findings

### CS-1 — `TexturedBuilder` is unnecessary; named parameters suffice

**Severity:** moderate  
**File:** `IsometricMaterial.kt`, lines 127–148

`TexturedBuilder` exists solely to expose three mutation methods (`uvScale`, `uvOffset`, `uvRotate`) plus a `tint` var. Because `IsometricMaterial.Textured` is already a `data class` with all those fields, the builder adds an entire intermediate mutable object for no expressiveness gain that named parameters + `UvTransform(...)` can't provide.

The three top-level entry-points (`textured`, `texturedAsset`, `texturedBitmap`) would collapse to simple one-liners:

```kotlin
fun textured(
    @DrawableRes resId: Int,
    tint: IsoColor = IsoColor.WHITE,
    uvTransform: UvTransform = UvTransform.IDENTITY,
): IsometricMaterial.Textured =
    IsometricMaterial.Textured(TextureSource.Resource(resId), tint, uvTransform)
```

Call sites become slightly more verbose for multi-property cases (`uvTransform = UvTransform(scaleU = 2f, scaleV = 3f)`) but entirely readable and less code overall. The builder syntax currently used in tests and docs (`textured(42) { uvScale(2f, 3f) }`) is not materially more ergonomic than `textured(42, uvTransform = UvTransform(scaleU = 2f, scaleV = 3f))`.

**Recommendation:** Delete `TexturedBuilder`. Replace the three `textured*` functions with named-parameter variants. Update tests accordingly.

---

### CS-2 — `PerFaceBuilder` is justified; keep it

**Severity:** none (clean)  
**File:** `IsometricMaterial.kt`, lines 151–162

`PerFaceBuilder` cannot be replaced by a data-class constructor because it accumulates a variable number of `face(index, material)` entries whose count is unknown at the call site. A `Map<Int, IsometricMaterial>` literal (`mapOf(0 to ..., 1 to ...)`) would work as an alternative, but the builder's `face()` function with its non-negative index guard is cleaner and safer. This builder carries its weight.

---

### CS-3 — `@IsometricMaterialDsl` marker is unnecessary at current nesting depth

**Severity:** minor  
**File:** `IsometricMaterial.kt`, line 165; applied at lines 126 and 150

`@DslMarker` prevents implicit receiver leakage when builders are *nested inside each other*. Neither `TexturedBuilder` nor `PerFaceBuilder` accepts a lambda that could itself contain the other builder — their lambdas are `() -> Unit` with no receiver. The `face(index, material)` call inside `PerFaceBuilder` takes a fully constructed `IsometricMaterial` value, not another lambda. There is no nesting depth at which the marker fires. It is dead annotation machinery.

If `TexturedBuilder` is deleted per CS-1, the marker has exactly one remaining user (`PerFaceBuilder`) and still never triggers, making its presence purely aspirational.

**Recommendation:** Delete `@IsometricMaterialDsl` and its annotation class. Restore it only when a future builder accepts a nested builder lambda.

---

### CS-4 — `UvTransform` (and `UvCoord` corner constants) are premature for this slice

**Severity:** moderate  
**File:** `UvCoord.kt`, lines 34–44 (`UvTransform`); lines 16–21 (`UvCoord` companion)

**`UvTransform`** is referenced in `IsometricMaterial.Textured` and exercised through `TexturedBuilder`, so it is not literally dead — but its application is entirely deferred. No renderer in this slice reads `uvTransform` from a `Textured` material; the plan (`04-plan-material-types.md` / roadmap) assigns UV generation to a separate later slice. The field sits in the data class as a round-trip store with no consumer. This inflates the API surface and obligates future renderers to honour semantics (scale → rotate → offset ordering) before any pipeline can test those semantics.

The `UvCoord.TOP_LEFT / TOP_RIGHT / BOTTOM_LEFT / BOTTOM_RIGHT` corner constants in the `UvCoord` companion are entirely unused — no production code or test references them (tests only instantiate `UvCoord(2f, 3f)` directly and verify `IDENTITY`). They appear to be speculative additions for a UV-mapping helper that does not yet exist.

**Recommendation (two options):**

1. **Defer entirely:** Remove `uvTransform: UvTransform` from `IsometricMaterial.Textured` and remove `UvTransform` from this slice. Re-introduce it in the UV-generation slice when a renderer can actually consume it. Remove the four `UvCoord` corner constants.
2. **Keep but mark internal:** Annotate the `uvTransform` property and `UvTransform` class with `@InternalIsometricApi` (or equivalent) so they are not part of the public contract until the UV slice ships. Remove the unused `UvCoord` corner constants regardless.

Option 1 is preferred per the api-design-guideline Section 11 (additive changes) and Section 12 (dogfooding — don't ship API you can't test end-to-end).

---

### CS-5 — `Shape()` and `Path()` composables are structurally identical, with no extraction

**Severity:** minor  
**File:** `IsometricMaterialComposables.kt`, lines 41–85 and 107–153

Both overloads share:
- Identical parameter lists (same names, same defaults, same validation messages)
- Identical `when (material)` color-extraction logic (lines 56–59 / 124–127)
- Identical `update { ... }` blocks — 14 `set(...)` calls each, byte-for-byte the same except for the node type and `geometry`/`path` property name

The color-extraction logic could be pulled into a private extension:

```kotlin
private fun IsometricMaterial.resolveColor(fallback: IsoColor): IsoColor =
    if (this is IsometricMaterial.FlatColor) color else fallback
```

The `update` block duplication is harder to eliminate because `ShapeNode` and `PathNode` are distinct types with no shared interface for `shape`/`path`. If those nodes gain a common supertype or interface in a future refactor, the composable bodies could be merged. For now, a comment cross-referencing the two functions (so they are kept in sync) is the minimum mitigation. The validation lambdas for `rotation` and `scale` are identical and could each be extracted to a named local or file-private val.

**Recommendation:** Extract `resolveColor` helper. Extract the `rotation` and `scale` validation lambdas to named private constants. Add a `// Keep in sync with Path()` comment. Full deduplication is blocked pending a shared node interface.

---

## Summary

| ID   | Severity | Keep / Remove          | Actionable |
|------|----------|------------------------|------------|
| CS-1 | Moderate | Remove `TexturedBuilder` | Yes        |
| CS-2 | None     | Keep `PerFaceBuilder`  | No (clean) |
| CS-3 | Minor    | Remove `@IsometricMaterialDsl` | Yes   |
| CS-4 | Moderate | Defer `UvTransform`; remove `UvCoord` corner constants | Yes |
| CS-5 | Minor    | Extract shared helpers in Composables | Yes |

No finding is a blocker, but CS-1 and CS-4 together remove a builder class, an annotation class, a 12-field data class, and four unused constants — a meaningful reduction in surface area for a slice whose renderer pipeline is not yet complete.
