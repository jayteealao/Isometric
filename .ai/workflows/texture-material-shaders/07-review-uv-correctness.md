# UV-Generation Correctness Review

**Reviewer:** Claude (automated)
**Date:** 2026-04-11
**Files reviewed:**
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/PrismFace.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Prism.kt`
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvGenerator.kt`
- `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricNode.kt`
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialComposables.kt`
- `isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/UvGeneratorTest.kt`
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/shapes/PrismFaceTest.kt`

---

## UV Formula Verification

All six faces verified by tracing actual vertex coordinates from `Prism.createPaths` through the UV formulas.

Given a Prism at `position=(ox,oy,oz)` with `width=w`, `depth=d`, `height=h`:

| Index | Face   | Vertices (from createPaths)                                                                                | UV Formula                                   | Result (unit prism) | Correct? |
|-------|--------|------------------------------------------------------------------------------------------------------------|----------------------------------------------|---------------------|----------|
| 0     | FRONT  | `(ox,oy,oz)`, `(ox+w,oy,oz)`, `(ox+w,oy,oz+h)`, `(ox,oy,oz+h)`                                          | `u=(x-ox)/w`, `v=(z-oz)/h`                   | (0,0)(1,0)(1,1)(0,1)| ✓        |
| 1     | BACK   | `(ox,oy+d,oz+h)`, `(ox+w,oy+d,oz+h)`, `(ox+w,oy+d,oz)`, `(ox,oy+d,oz)` (face1.reverse().translate y+d)  | `u=1-(x-ox)/w`, `v=1-(z-oz)/h`              | (1,0)(0,0)(0,1)(1,1)| ✓        |
| 2     | LEFT   | `(ox,oy,oz)`, `(ox,oy,oz+h)`, `(ox,oy+d,oz+h)`, `(ox,oy+d,oz)` (face2)                                  | `u=(y-oy)/d`, `v=(z-oz)/h`                   | (0,0)(0,1)(1,1)(1,0)| ✓        |
| 3     | RIGHT  | `(ox+w,oy+d,oz)`, `(ox+w,oy+d,oz+h)`, `(ox+w,oy,oz+h)`, `(ox+w,oy,oz)` (face2.reverse().translate x+w)  | `u=1-(y-oy)/d`, `v=(z-oz)/h`                | (0,0)(0,1)(1,1)(1,0)| ✓        |
| 4     | BOTTOM | `(ox,oy+d,oz)`, `(ox+w,oy+d,oz)`, `(ox+w,oy,oz)`, `(ox,oy,oz)` (face3.reverse())                        | `u=1-(x-ox)/w`, `v=(y-oy)/d`                | (1,1)(0,1)(0,0)(1,0)| ✓        |
| 5     | TOP    | `(ox,oy,oz+h)`, `(ox+w,oy,oz+h)`, `(ox+w,oy+d,oz+h)`, `(ox,oy+d,oz+h)` (face3.translate z+h)            | `u=(x-ox)/w`, `v=(y-oy)/d`                   | (0,0)(1,0)(1,1)(0,1)| ✓        |

All six formulas produce UV coordinates that correctly span [0,1]×[0,1] across each face surface. The choice of mirrored formulas for BACK and RIGHT is intentional: without the `1-` flip, the BACK face would be a mirror of FRONT as seen from outside, and similarly for RIGHT vs LEFT. The flip ensures texture orientation is consistent when viewed from outside the prism.

---

## Findings

### UVC-1 — Division by zero: no guard on width/depth/height

**Severity:** Low (won't crash in practice; guarded elsewhere)

`UvGenerator.computeUvs` divides by `prism.width`, `prism.depth`, and `prism.height` with no zero-guard:

```kotlin
u = (pt.x - ox) / w   // w = prism.width
v = (pt.z - oz) / h   // h = prism.height
```

However, `Prism.init` already enforces `width > 0.0`, `depth > 0.0`, `height > 0.0` with a `require(...)`. Any `Prism` instance that reaches `UvGenerator` is guaranteed to have positive dimensions. Division by zero is therefore impossible given the existing invariant.

**Action:** No code change needed. Worth noting in `UvGenerator` KDoc that it assumes a valid `Prism` (dimensions > 0) for documentation clarity, but this is not a correctness bug.

---

### UVC-2 — `shape as Prism` cast in uvProvider lambda: theoretically unsafe window

**Severity:** Low (safe in practice under current Compose update semantics)

In `IsometricMaterialComposables.kt`:

```kotlin
val uvProvider: ((Shape, Int) -> FloatArray?)? = if (
    material is IsometricMaterial.Textured && geometry is Prism
) {
    { shape, faceIndex -> UvGenerator.forPrismFace(shape as Prism, faceIndex) }
} else null
```

And in `ShapeNode.renderTo`:

```kotlin
uvCoords = uvProvider?.invoke(shape, index),
```

Note that `shape` here is `ShapeNode.shape` — the current (possibly updated) shape stored on the node, not the `geometry` captured in the lambda closure. If `ShapeNode.shape` is updated to a non-Prism type between the frame where `uvProvider` was set and when `renderTo` runs, the `shape as Prism` cast will throw `ClassCastException`.

In practice this is safe because:
1. The Compose `update` block sets both `shape` and `uvProvider` in the same recomposition pass, applied atomically by the Applier before any rendering occurs.
2. When `geometry` changes to a non-Prism, `uvProvider` is recomputed to `null`, so the lambda is never called with a non-Prism shape.

However, this relies on Compose's single-threaded update guarantee. If `ShapeNode.shape` is ever mutated externally (outside the Compose update cycle), the cast could fail.

**Recommendation:** Add a defensive `is Prism` check inside the lambda to eliminate the cast risk entirely:

```kotlin
{ shape, faceIndex ->
    if (shape is Prism) UvGenerator.forPrismFace(shape, faceIndex) else null
}
```

This costs nothing at runtime and eliminates the theoretical crash window.

---

### UVC-3 — `uvProvider` invoked with pre-transform `shape`, but path index is from transformed shape

**Severity:** Low (correct, but potentially confusing)

In `ShapeNode.renderTo`:

```kotlin
val transformedShape = localContext.applyTransformsToShape(shape)
for ((index, path) in transformedShape.paths.withIndex()) {
    output.add(RenderCommand(
        ...
        uvCoords = uvProvider?.invoke(shape, index),  // shape = pre-transform
    ))
}
```

`uvProvider` receives the **original** (pre-transform) `shape` and the face `index` from the **transformed** shape's path list. This is intentional and correct: UV normalization uses the prism's canonical dimensions (width/depth/height), which are invariant under the rigid-body transforms applied by `applyTransformsToShape`. Path count and order are also preserved by transforms. However, the contract is subtle and should be documented on the `uvProvider` property.

**Action:** The existing KDoc on `ShapeNode.uvProvider` says "with the original (pre-transform) shape" — this is documented. No correctness issue.

---

### UVC-4 — `forPrismFace` accesses `prism.paths[faceIndex]`: bounds safety

**Severity:** None (safe)

```kotlin
fun forPrismFace(prism: Prism, faceIndex: Int): FloatArray {
    val face = PrismFace.fromPathIndex(faceIndex)   // throws for index > 5
    val path = prism.paths[faceIndex]               // accessed after validation
    return computeUvs(prism, face, path)
}
```

`PrismFace.fromPathIndex` throws `IllegalArgumentException` for any index outside 0..5 before `prism.paths[faceIndex]` is reached. Since `Prism.createPaths` always produces exactly 6 paths (proven by code inspection: 2 + 2 + 2 = 6), `prism.paths[faceIndex]` for any index in 0..5 is always in bounds.

**Action:** No issue.

---

### UVC-5 — BACK face v-coordinate formula appears inverted; re-verified as correct

The BACK face formula `v = 1-(pt.z-oz)/h` initially looks suspicious — why invert v for BACK but not for RIGHT? The reason is face orientation. The BACK face vertices (from `face1.reverse().translate(0,d,0)`) traverse the face in the opposite winding order: they start at the top (`oz+h`) and go down to the bottom (`oz`). The `1-` inversion restores v=0 at the bottom of the face and v=1 at the top, consistent with all other faces. This is correct.

**Action:** No issue.

---

### UVC-6 — BACK face: v-formula is `1-(z-oz)/h` but vertices have z at oz+h and oz

Cross-check with the test assertion for BACK:
- Vertex 0: `(ox,oy+d,oz+h)` → `v = 1-(oz+h-oz)/h = 1-1 = 0` ✓ (test expects 0)
- Vertex 3: `(ox,oy+d,oz)` → `v = 1-(oz-oz)/h = 1-0 = 1` ✓ (test expects 1)

Confirmed correct.

---

## Test Coverage Assessment

| Scenario | Covered? |
|---|---|
| All 6 faces tested individually | ✓ (FRONT, BACK, LEFT, RIGHT, BOTTOM, TOP — all 6) |
| Unit prism at origin | ✓ |
| Non-unit dimensions (3×2×4) | ✓ (`non-unit prism normalises correctly`) |
| Translated prism (position ≠ origin) | ✓ (`translated prism produces same UVs`) |
| `forAllPrismFaces` returns 6 arrays of 8 floats | ✓ |
| Invalid face index throws | ✓ (index 6) |
| Negative face index throws | ✓ (in `PrismFaceTest`) — but not in `UvGeneratorTest` |
| Edge case: very small dimensions (near-zero) | ✗ (not tested; safe due to `Prism.init` guard) |
| Prism with non-zero position, non-unit dimensions, all faces | ✗ (partially covered — translated only tests FRONT) |

**Missing test:** `UvGeneratorTest` has no test for a **translated non-unit prism** on faces other than FRONT. The translated test only checks FRONT face. A rotation of face index (e.g., BACK and TOP for a translated prism) would add confidence that the origin subtraction is correct in all branches. This is a coverage gap, not a correctness bug — the formula is mathematically correct for all faces — but it would be worth adding for completeness.

**Missing test:** `UvGeneratorTest` does not test the negative face index path. `PrismFaceTest` covers it for `fromPathIndex` directly, but `UvGenerator.forPrismFace(-1)` is not explicitly exercised in the UV test file.

---

## Summary

| ID    | Severity | Finding | Action |
|-------|----------|---------|--------|
| UVC-1 | Low (informational) | No division-by-zero risk; Prism.init guards dimensions > 0 | Add KDoc note if desired |
| UVC-2 | Low | `shape as Prism` cast could fail if `ShapeNode.shape` is externally mutated; safe under normal Compose lifecycle | Replace cast with `if (shape is Prism)` guard |
| UVC-3 | Informational | Pre-transform shape passed to uvProvider; correct and documented | No change |
| UVC-4 | None | `forPrismFace` bounds are safe | No change |
| UVC-5 | None | BACK `v`-flip is intentional and correct | No change |
| UVC-6 | None | BACK formula verified by cross-check | No change |

**result: 1 recommendation (UVC-2), 2 test coverage gaps (non-FRONT faces for translated prism; negative index in UvGeneratorTest); no correctness bugs found.**
