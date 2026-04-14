---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: api-design-fixes
review-command: code-simplification
status: complete
updated-at: "2026-04-14T22:14:55Z"
metric-findings-total: 7
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 3
metric-findings-low: 3
metric-findings-nit: 1
result: issues-found
tags: [code-simplification, api-design, kotlin]
refs:
  review-master: 07-review-api-design-fixes.md
---

## Findings

| # | Severity | Confidence | Location | Summary |
|---|----------|------------|----------|---------|
| CS-1 | MED | High | `TexturedCanvasDrawHook.kt:120–125` | `tileU`/`tileV` are always identical; `Triple` key and asymmetric-tiling comment are misleading dead weight |
| CS-2 | MED | High | `ProvideTextureRendering.kt:25` + `TextureCache.kt:34` | `maxSize > 0` validation duplicated between `TextureCacheConfig` and `TextureCache` |
| CS-3 | MED | High | `GpuTextureManager.kt:272` + `SceneDataPacker.kt:205` | PerFace resolution logic inlined identically at two webgpu sites instead of using `PerFace.resolve()` |
| CS-4 | LOW | High | `TextureLoader.kt:43` | `defaultTextureLoader()` is unnecessary indirection — single call site, `private` class, no override point |
| CS-5 | LOW | Med | `UvCoord.kt:69–88` | `TextureTransform` factory companions (`tiling`, `rotated`, `offset`) have no test coverage |
| CS-6 | LOW | High | `MaterialData.kt:24` | Default `baseColor()` silently returns WHITE for unknown implementors with no compile-time nudge |
| CS-7 | NIT | High | `IsometricMaterialComposables.kt:131` | `Path()` accepts `IsometricMaterial` but guards two subtypes at runtime; type constraint could be compile-time |

---

## Detailed Findings

### CS-1 [MED] `tileU`/`tileV` are always identical; asymmetric-tiling key is misleading

**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/TexturedCanvasDrawHook.kt`, lines 120–125

```kotlin
val tileMode = if (material.transform != TextureTransform.IDENTITY) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
val tileU = tileMode
val tileV = tileMode
val shaderKey = Triple(material.source, tileU, tileV)
val shader = shaderCache.getOrPut(shaderKey) {
    BitmapShader(cached.bitmap, tileU, tileV)
}
```

`tileU` and `tileV` are always the same value — both are assigned from the single `tileMode` expression. The `Triple` key and the KDoc comment on lines 48–50 ("Both tile modes are included in the key so that asymmetric tiling … never collides") create an expectation of asymmetric tiling support that the code does not actually deliver. A user with `scaleU=2, scaleV=1` gets `BitmapShader(REPEAT, REPEAT)` — `tileV` is REPEAT even though the V axis has no repetition — and there is no way to obtain `BitmapShader(REPEAT, CLAMP)` through this path.

The `Triple` key is not wrong for correctness at present (since both legs are always equal), but it is misleading complexity that sets a false expectation. If asymmetric tiling is intentionally deferred, the code should reflect that honestly: use a single `Boolean isTiling` key, remove the asymmetric-tiling KDoc, and leave a `// TODO: asymmetric tiling` marker.

**Recommendation:** Simplify to `Pair(material.source, tileMode)` (or a two-field data class) as long as U and V tile modes are forced to be equal. Update the KDoc comment to match actual behaviour. Track true asymmetric tiling support as a separate future enhancement.

---

### CS-2 [MED] `maxSize > 0` validation duplicated between `TextureCacheConfig` and `TextureCache`

**Files:**
- `ProvideTextureRendering.kt:25`: `require(maxSize > 0) { "maxSize must be positive, got $maxSize" }`
- `TextureCache.kt:34`: `require(maxSize > 0) { "maxSize must be positive, got $maxSize" }`

`TextureCacheConfig` is the public validated boundary for the cache configuration. `TextureCache` is `internal` and its only callers (`ProvideTextureRendering`) always pass `cacheConfig.maxSize` — a value that has already been validated. The duplicate `require` in `TextureCache.init` adds no safety and creates a divergence risk if the constraint is ever tightened (e.g., `maxSize >= 5`).

`TextureCacheConfig` as a type is well-justified: it gives `remember(cacheConfig)` a stable key and can absorb future fields. But it should be the single validation point.

**Recommendation:** Remove the `require` from `TextureCache.init`. The assertion is already guaranteed by `TextureCacheConfig`.

---

### CS-3 [MED] PerFace resolution logic inlined identically at two webgpu call sites

**Files:**
- `isometric-webgpu/.../GpuTextureManager.kt:272`: `if (face != null) m.faceMap[face] ?: m.default else m.default`
- `isometric-webgpu/.../SceneDataPacker.kt:205`: `if (face != null) m.faceMap[face] ?: m.default else m.default`

`PerFace.resolve(face: PrismFace)` exists precisely to encapsulate this logic, but it is `internal` to `isometric-shader`, making it invisible to `isometric-webgpu`. Both webgpu sites re-express the same three-clause pattern. This is a maintenance liability: if the resolution semantics change (e.g., a new face role, a null-face policy), both sites must be updated in sync.

The root cause is visibility scope. `internal` is correct to prevent external library users from depending on `resolve()`, but it closes it off from the sibling module that legitimately needs it.

**Recommendation (two options):**
- Option A (minimal): Add a `// mirrors PerFace.resolve() — keep in sync` comment at both sites, making the coupling explicit.
- Option B (preferred): Expose `PerFace.resolve()` as `public` or move it to a cross-module shared function if `isometric-webgpu` is considered a trusted sibling module. A `@PublishedApi` or a package-split approach is heavier than warranted; making it `public` with a clear KDoc ("for renderer use") is likely the right trade-off.

---

### CS-4 [LOW] `defaultTextureLoader()` factory is unnecessary indirection

**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/TextureLoader.kt`, line 43

```kotlin
internal fun defaultTextureLoader(context: Context): TextureLoader = DefaultTextureLoader(context)
```

`DefaultTextureLoader` is `private` to this file; `defaultTextureLoader()` is `internal`. There is exactly one call site (`ProvideTextureRendering.kt:83`). The factory wrapper adds a layer of indirection that cannot be overridden, is not tested independently, and performs no argument transformation. Making `DefaultTextureLoader` `internal` and calling it directly at the single use site removes the wrapper with no behavioural change.

**Recommendation:** Make `DefaultTextureLoader` `internal` and remove `defaultTextureLoader()`. The one call site becomes `DefaultTextureLoader(context.applicationContext)`.

---

### CS-5 [LOW] `TextureTransform` factory companions have no test coverage

**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvCoord.kt`, lines 69–88

```kotlin
fun tiling(horizontal: Float, vertical: Float) = TextureTransform(scaleU = horizontal, scaleV = vertical)
fun rotated(degrees: Float) = TextureTransform(rotationDegrees = degrees)
fun offset(u: Float, v: Float) = TextureTransform(offsetU = u, offsetV = v)
```

These are the only three public `TextureTransform` construction helpers. No production code calls them yet (the test suite and sample code use the primary constructor directly), and there are no tests asserting their field mappings. They are intentional API ergonomics per the progressive-disclosure guideline — not harmful dead code — but their parameter-to-field mapping is untested. A future field rename would leave the factories silently wrong.

**Recommendation:** Add unit tests asserting the field mappings, e.g.:
```kotlin
assertEquals(TextureTransform(scaleU = 2f, scaleV = 3f), TextureTransform.tiling(2f, 3f))
assertEquals(TextureTransform(rotationDegrees = 45f), TextureTransform.rotated(45f))
assertEquals(TextureTransform(offsetU = 0.5f, offsetV = 0f), TextureTransform.offset(0.5f, 0f))
```

---

### CS-6 [LOW] `baseColor()` default returns WHITE silently for unknown `MaterialData` implementors

**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/MaterialData.kt`, line 24

```kotlin
fun baseColor(): IsoColor = IsoColor.WHITE
```

The documented contract ("Unknown implementors: returns WHITE") is explicit, so this is a deliberate design decision. However, the default implementation provides no compile-time nudge to third-party `MaterialData` implementors that they should override this method. A custom implementor that forgets to override `baseColor()` silently renders as white — visually indistinguishable from an intentional white material.

**Recommendation (optional):** The current default is acceptable. If stronger guidance is desired, strengthen the KDoc to: _"If you implement `MaterialData` for a non-trivial material, you SHOULD override `baseColor()` to return a representative color."_ Alternatively, consider whether `baseColor()` should be `abstract` (breaking) or annotated `@OverrideMe` (non-standard) to surface the expectation at the declaration site.

---

### CS-7 [NIT] `Path()` runtime type guard should be a compile-time type constraint

**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialComposables.kt`, line 131

```kotlin
fun IsometricScope.Path(
    path: Path,
    material: IsometricMaterial,
    ...
) {
    require(material !is IsometricMaterial.Textured && material !is IsometricMaterial.PerFace) { ... }
```

The parameter type is `IsometricMaterial` but two of its three concrete subtypes are rejected at runtime. The effective accepted type is the one remaining concrete subtype, yet `IsoColor` (the only flat-color `MaterialData`) does not implement `IsometricMaterial` — so no valid `IsometricMaterial` value actually passes the `require`. Callers are guided by the type signature toward values that will throw.

A compile-time solution is to accept `MaterialData` (which `IsoColor` implements) instead of `IsometricMaterial`. The KDoc already notes the restriction; the type should enforce it.

**Recommendation:** Change the parameter type to `MaterialData` and remove the `require`. This makes the valid inputs self-documenting and shifts the error from runtime to the call site.

---

## Summary

The api-design-fixes slice is solid. The previous high-severity finding (shaderCache key dropping `tileV`) was resolved — the key now uses `Triple(source, tileU, tileV)`. However, this introduced a new MED finding (CS-1): since `tileU` and `tileV` are always identical, the `Triple` and its asymmetric-tiling KDoc create a misleading impression of supported behaviour.

The remaining findings are maintenance-grade issues: duplicated `maxSize` validation (CS-2), duplicated PerFace resolution in the webgpu module (CS-3), a redundant factory wrapper (CS-4), untested companion factories (CS-5), a silent WHITE fallback with no override nudge (CS-6), and a runtime type guard that should be a compile-time constraint (CS-7). None block correctness of the current implementation.
