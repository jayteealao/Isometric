---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: api-design-fixes
review-command: maintainability
status: complete
updated-at: "2026-04-14T22:14:55Z"
metric-findings-total: 8
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 3
metric-findings-low: 3
metric-findings-nit: 2
result: issues-found
tags: [maintainability, api-design, kotlin]
refs:
  review-master: 07-review-api-design-fixes.md
---

## Re-review Note

This file supersedes the earlier run (timestamp `2026-04-14T17:42:09Z`) which reviewed the code
_before_ the fixes from the master review were applied. The current run reviews the post-fix state
of all four files and re-assesses each prior finding plus any new observations.

**Prior findings resolved:** MNT-01 (UNASSIGNED_FACE_DEFAULT), MNT-03 (opaque Pair key),
MNT-08 (shaderCache KDoc gap).  
**Prior findings still open:** MNT-02, MNT-04, MNT-05, MNT-06, MNT-07.  
**New finding added:** MNT-09 (Triple key tileU/tileV always equal — asymmetric tiling still not
achievable).

---

## Findings

| ID | Severity | Confidence | Location | Summary |
|----|----------|------------|----------|---------|
| MNT-01 | ~~HIGH~~ | ~~High~~ | `IsometricMaterial.kt` | RESOLVED — `UNASSIGNED_FACE_DEFAULT` moved to `PerFace.Companion` |
| MNT-02 | MED | High | `IsometricMaterial.kt:185-186` | `@IsometricMaterialDsl` declared at bottom of a 219-line file; should be in its own top-level file |
| MNT-03 | ~~MED~~ | ~~High~~ | `TexturedCanvasDrawHook.kt` | RESOLVED — shaderCache key upgraded to `Triple<TextureSource,TileMode,TileMode>` with full KDoc |
| MNT-04 | MED | Med | `IsometricMaterial.kt:94-106` | `PerFace.of()` KDoc still does not explain why the primary constructor is private |
| MNT-05 | LOW | Med | `UvCoord.kt:60` | `TextureTransform.IDENTITY` KDoc one-liner gives no naming rationale |
| MNT-06 | LOW | Med | `UvGenerator.kt:17` | Class-level KDoc missing note that BACK/RIGHT/BOTTOM UV axes are deliberately mirrored |
| MNT-07 | NIT | High | workflow docs | `toBaseColor()` referenced in workflow plan/verify docs but real implementation is `baseColor()` on `MaterialData` |
| MNT-08 | ~~NIT~~ | ~~High~~ | `TexturedCanvasDrawHook.kt` | RESOLVED — shaderCache KDoc now documents both tile mode slots and asymmetric tiling constraints |
| MNT-09 | MED | High | `TexturedCanvasDrawHook.kt:120-122` | `tileU` and `tileV` are always assigned the same value; `Triple` key never actually encodes asymmetric tiling, rendering H-02 fix incomplete |

---

## Detailed Findings

### MNT-02 — MED: `@IsometricMaterialDsl` buried at file bottom

**File:** `isometric-shader/.../shader/IsometricMaterial.kt:185-186`

```kotlin
@DslMarker
annotation class IsometricMaterialDsl
```

The `@DslMarker` annotation is at the very end of a 219-line file, after the `PerFaceMaterialScope`
class. Kotlin conventions (and Android conventions mirrored in `DslMarker` examples) place marker
annotations in their own top-level file (e.g., `IsometricMaterialDsl.kt`). The present location
makes the annotation invisible to contributors who know to search for it by file name, and it
cannot be found with a typical IDE "Go to class" lookup without knowing to look inside
`IsometricMaterial.kt`. This was untriaged as MED-10 in the master review and has not been
addressed.

**Fix:** Move to a new one-file `IsometricMaterialDsl.kt` in the same package.

**Severity:** MED | **Confidence:** High

---

### MNT-04 — MED: `PerFace.of()` KDoc missing explanation of private constructor

**File:** `isometric-shader/.../shader/IsometricMaterial.kt:94-106`

```kotlin
/**
 * Factory for callers who need direct map construction (advanced use case).
 *
 * Prefer the [perFace] DSL for typical usage.
 *
 * @param faceMap Map from [PrismFace] to material for that face
 * @param default Fallback material for faces absent from [faceMap]
 */
fun of(...)
```

The KDoc tells *what* the factory does but not *why* it is required. A new contributor seeing
`PerFace.of()` alongside `PerFaceMaterialScope` will ask: "Why not just call
`PerFace(faceMap, default)` directly?" The answer — the primary constructor is `private` to
ensure every instance passes the nesting invariant in the `init` block and to steer most callers
toward the DSL — is not present anywhere in the immediate context. Without this explanation,
contributors may attempt to add a second public constructor or work around `of()`. This was
untriaged as MED-12 in the master review and remains unaddressed.

**Suggested KDoc addition:**

> The primary constructor is private so that all instances are validated by the `init` nesting
> constraint. `of()` is the single public construction path for non-DSL callers.

**Severity:** MED | **Confidence:** Med

---

### MNT-05 — LOW: `TextureTransform.IDENTITY` naming rationale absent

**File:** `isometric-shader/.../shader/UvCoord.kt:60`

```kotlin
/** No transform — identity. */
val IDENTITY = TextureTransform()
```

`IDENTITY` is the mathematically correct term. However, several Compose-adjacent APIs use `None`
or a default-instance pattern for the same concept (e.g., `ContentScale.None`,
`TextAlign.Unspecified`). The one-line KDoc does not record why `IDENTITY` was chosen, meaning
future maintainers cannot distinguish intentional API guideline compliance from accidental naming.
Adding one sentence ("Follows mathematical convention for transform identity; not `None` or
`Default`.") would prevent future naming churn and align with the guideline requirement to
document deliberate choices (Section 11 — Evolution). This was L-11 in the master review.

**Severity:** LOW | **Confidence:** Med

---

### MNT-06 — LOW: `UvGenerator` missing mirroring rationale

**File:** `isometric-shader/.../shader/UvGenerator.kt:17` and `computeUvs()` at line 64

The class-level KDoc correctly explains the UV coordinate format and the 3D→screen coordinate
reasoning. However, it does not document that `BACK`, `RIGHT`, and `BOTTOM` faces deliberately
apply `u = 1.0 - ...` mirroring:

```kotlin
PrismFace.BACK -> {
    u = 1.0 - (pt.x - ox) / w   // mirrored
    v = 1.0 - (pt.z - oz) / h   // mirrored
}
```

The mirroring is correct — it prevents texture flip on faces that appear on the opposite side of
the prism in isometric projection. Without a comment explaining the invariant, a future
contributor can plausibly read the `1.0 -` as a sign error and "fix" it, breaking visual
correctness. A two-sentence note at the class level or above the `when` block would safeguard
this. This was L-10 in the master review.

**Severity:** LOW | **Confidence:** Med

---

### MNT-07 — NIT: `toBaseColor()` name in workflow docs does not match implementation

**Scope:** workflow documentation only (no code change required)

The workflow plan and verify documents reference `MaterialData.toBaseColor()` as an extension
added in this slice. The actual implementation is a default method `fun baseColor(): IsoColor`
declared directly on the `MaterialData` interface in `isometric-core`. There is no `toBaseColor()`
anywhere in the codebase. The mismatch is confined to workflow markdown and does not affect
compiled code, but it will mislead future reviewers auditing the slice or searching the codebase
for `toBaseColor`. This was N-07 in the master review.

**Severity:** NIT | **Confidence:** High

---

### MNT-09 — MED: `Triple` key always has `tileU == tileV`; asymmetric tiling remains impossible

**File:** `isometric-shader/.../render/TexturedCanvasDrawHook.kt:120-123`

```kotlin
val tileMode = if (material.transform != TextureTransform.IDENTITY) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
val tileU = tileMode
val tileV = tileMode
val shaderKey = Triple(material.source, tileU, tileV)
```

The H-02 fix changed the shader-cache key from `Pair<TextureSource, TileMode>` to
`Triple<TextureSource, TileMode, TileMode>` to support asymmetric tiling (where `tileU !=
tileV`). However, the tile-mode selection (line 120) still assigns a single binary value to both
`tileU` and `tileV`. As a result, the two `TileMode` slots in the Triple are always identical —
`tileU == tileV` at every call site. The fix correctly prevents the old collision (correct key
shape), but asymmetric tiling (e.g., `REPEAT` horizontally, `CLAMP` vertically) is still not
achievable through the current `TextureTransform` API, and there is no comment explaining that
this is intentional.

The root cause is that `TextureTransform.scaleU` and `scaleV` are separate, so per-axis tile mode
_could_ be computed, but the code maps both axes to the same mode from the identity check. The
discrepancy between the key structure (which implies asymmetry is possible) and the assignment
(which makes it impossible) is a readability and accuracy issue: future contributors will
reasonably expect the Triple to encode independent modes and be surprised to find they are always
equal.

**Options:**
- (a) Keep the Triple but add a comment: "tileU == tileV always; asymmetric tiling is a future
  enhancement. The Triple structure is retained to avoid a future key migration."
- (b) Derive tileU/tileV independently from scaleU/scaleV (`REPEAT` iff that axis's scale != 1f)
  and let the Triple actually encode asymmetric modes. This would fix `TextureTransform.tiling(2f, 1f)` rendering.
- (c) Revert to `Pair` and note asymmetric tiling is deferred.

Option (b) is the most correct and only requires one line change in the mode-selection logic.
Option (a) is the minimal documentation fix. Either is acceptable; the current silent inconsistency
is the problem.

**Severity:** MED | **Confidence:** High

---

## Summary

Three of the eight prior findings were resolved by the fix pass: `UNASSIGNED_FACE_DEFAULT` is
now correctly scoped to `PerFace.Companion` (MNT-01), the `shaderCache` key was upgraded to a
`Triple` with a clear KDoc (MNT-03), and the inline tile-mode limitation is now surfaced in
the field comment (MNT-08).

One new issue was found (MNT-09): the `Triple` shader-cache key always carries `tileU == tileV`
because tile-mode selection uses a single boolean predicate for both axes. This means the H-02
fix resolved the key collision correctness bug but does not deliver the asymmetric tiling
capability the key structure implies. This should either be completed (derive per-axis modes) or
documented as explicitly deferred.

Five pre-existing documentation gaps remain open (MNT-02, MNT-04, MNT-05, MNT-06, MNT-07).
None are blocking. The highest-priority open items are MNT-09 (new, MED — correctness-adjacent)
and MNT-02/MNT-04 (structural documentation that protects against future contributor confusion).
`PerFaceMaterialScope` and `ProvideTextureRendering` are well-structured and approachable for
new maintainers. The `resolveTexture → computeAffineMatrix → draw` flow in
`TexturedCanvasDrawHook` is clearly commented and easy to follow, particularly with the
matrix-math explanation in `computeAffineMatrix()`'s KDoc.
