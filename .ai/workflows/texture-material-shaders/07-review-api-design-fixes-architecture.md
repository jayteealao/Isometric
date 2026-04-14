---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: api-design-fixes
review-command: architecture
status: complete
updated-at: "2026-04-14T22:14:55Z"
metric-findings-total: 4
metric-findings-blocker: 0
metric-findings-high: 1
metric-findings-med: 1
metric-findings-low: 1
metric-findings-nit: 1
result: issues-found
tags: [architecture, api-design, kotlin, module-boundaries]
refs:
  review-master: 07-review-api-design-fixes.md
---

## Findings

| # | ID | Severity | Confidence | Location | Summary |
|---|-----|----------|------------|----------|---------|
| 1 | ARCH-01 | HIGH | High | `IsometricMaterial.kt:74`, `TexturedCanvasDrawHook.kt:97`, `SceneDataPacker.kt:202-205`, `GpuTextureManager.kt:270-273` | Dual `PerFace` resolution paths: `internal resolve()` in shader vs. inlined `faceMap[face] ?: default` in webgpu. Semantically identical today but will silently diverge if `resolve()` semantics change. |
| 2 | ARCH-02 | MED | High | `IsometricMaterial.kt:60-107`, `MaterialData.kt:24` | `IsometricMaterial.PerFace` has no `baseColor()` override. Inherits `MaterialData` default of `IsoColor.WHITE`. Both the compose-level and shader-level `Shape()` overloads call `material.baseColor()` to derive the flat-color tint — `PerFace` materials always produce a white tint regardless of `default` or face colors. |
| 3 | ARCH-03 | LOW | High | `IsometricComposables.kt:71-98` (compose module) | `Shape(material: MaterialData)` derives `color = material.baseColor()` and then tracks both `color` and `material` as independent `set()` entries in the update lambda. `color` is silently derived from `material`; if a future maintainer removes `set(color)` as apparently redundant, Canvas rendering breaks (it reads `ShapeNode.color`). The dependency is implicit and undocumented. |
| 4 | ARCH-04 | NIT | High | `IsometricMaterial.kt:74`, `CompositionLocals.kt:24-26` | `internal fun resolve()` has no comment explaining it is the intra-module helper used by `TexturedCanvasDrawHook`. `LocalDefaultColor` is typed as `staticCompositionLocalOf<IsoColor>` — the `Shape(material: MaterialData)` overload's default uses `LocalDefaultColor.current` which is `IsoColor`, correctly satisfying `MaterialData` via subtype. Good; recorded as NIT for completeness. |

---

## Dependency Boundary Verification

### Q1: Does `isometric-compose` depend on `isometric-shader`?

**Result: NO — boundary is clean.**

`isometric-compose/build.gradle.kts` declares only `api(project(":isometric-core"))` plus Compose UI/runtime libs. There is no `isometric-shader` dependency (runtime, implementation, or api).

Grep over all Kotlin source files in `isometric-compose/src/main` confirms: zero `import io.github.jayteealao.isometric.shader.*` statements exist. The two references to `isometric-shader` in compose source code are in KDoc comments (`@see` and prose) — not real imports.

### Q2: Circular dependency risk?

**Result: None.** The module graph is:

```
isometric-core  (pure JVM)
      ↑
isometric-compose  (Android + Compose)
      ↑
isometric-shader  (Android + Compose)
      ↑
isometric-webgpu  (Android + WebGPU)
```

`isometric-shader/build.gradle.kts` uses `api(project(":isometric-compose"))`. `isometric-webgpu/build.gradle.kts` uses `api(project(":isometric-compose"))` and `implementation(project(":isometric-shader"))`. No cycle.

---

## Detailed Findings

### ARCH-01 — Dual `PerFace` resolution paths (HIGH)

**Files:** `IsometricMaterial.kt:74`, `TexturedCanvasDrawHook.kt:97`, `SceneDataPacker.kt:202-205`, `GpuTextureManager.kt:270-273`

`PerFace` exposes two paths to resolve a face's effective sub-material:

1. **Intra-shader path** (`TexturedCanvasDrawHook.kt:97`):
   ```kotlin
   val sub = material.faceMap[face] ?: material.default
   ```
   This is the inlined form — `resolve()` is *not* called here. (The earlier review draft was incorrect: `TexturedCanvasDrawHook` does NOT call `resolve()`, it also inlines the map lookup directly.)

2. **`internal fun resolve()`** (`IsometricMaterial.kt:74`):
   ```kotlin
   internal fun resolve(face: PrismFace): MaterialData = faceMap[face] ?: default
   ```
   This function exists but has no callers in `isometric-shader` main sources. Both `TexturedCanvasDrawHook` and the webgpu callers have inlined the pattern independently.

**Actual state:** `resolve()` is effectively dead code in main sources — it is referenced only in tests (`PerFaceMaterialTest.kt`). This is the inverse of a divergence risk: the function exists but nothing calls it at runtime. The risk is that `resolve()` will be deleted as dead code without realizing the test references it, or conversely kept indefinitely as an orphaned `internal` function with no clear ownership.

The three inlined sites (`TexturedCanvasDrawHook`, `SceneDataPacker`, `GpuTextureManager`) are all `faceMap[face] ?: default` — they are identical and thus consistent today. Future changes to resolution semantics (e.g., adding a null-check or a fallback chain) must be applied to all three independently, with no compile-time guarantee.

**Recommendation:** Either (a) promote `resolve()` to an `internal` convenience that all three callers use (keeps logic in one place, but requires `resolve()` to be accessible — all three callers are in same or downstream modules), or (b) document the three-site inline pattern as intentional and close `resolve()` with a deprecation note pointing to the inlined pattern.

---

### ARCH-02 — `PerFace.baseColor()` missing override (MED)

**Files:** `IsometricMaterial.kt:60-107`, `MaterialData.kt:24`, `IsometricComposables.kt:71`, `IsometricMaterialComposables.kt:59`

`MaterialData` defines:
```kotlin
fun baseColor(): IsoColor = IsoColor.WHITE
```

`IsoColor` overrides: `override fun baseColor(): IsoColor = this`
`IsometricMaterial.Textured` overrides: `override fun baseColor(): IsoColor = tint`
`IsometricMaterial.PerFace` has **no override** — inherits `IsoColor.WHITE`.

Impact:

- The compose-level `Shape(geometry, material: MaterialData)` calls `val color = material.baseColor()` at line 71. For any `PerFace` material, `color` will be `IsoColor.WHITE` regardless of what colors are configured on the faces or the default.
- The shader-level `Shape(geometry, material: IsometricMaterial)` in `IsometricMaterialComposables.kt` also calls `val color = material.baseColor()` at line 59 — same bug applies.
- `ShapeNode.color` (which Canvas rendering reads for the base tint pass) is always set to `WHITE` for `PerFace` materials. The Canvas draw hook resolves per-face colors at draw time from `faceMap`, so the face colors are still correct. However, the tint color used for Canvas shapes that fall through to the base color path will be white.

The semantic contract of `baseColor()` for `PerFace` is ambiguous — there is no single "base color" for a per-face material. A reasonable implementation would be `default.baseColor()` (delegates to the default sub-material's base color). The current absence of an override is a silent incorrect default.

**Note:** This does not cause a visible regression in the Canvas path because `TexturedCanvasDrawHook` resolves per-face at draw time and overwrites the `color` tint. But it is a latent correctness hazard for any code path that reads `baseColor()` on a `PerFace` material for decisions other than Canvas drawing.

---

### ARCH-03 — Implicit `color`/`material` dual-tracking in compose `Shape` (LOW)

**File:** `IsometricComposables.kt:71-98`

```kotlin
val color = material.baseColor()
ReusableComposeNode<ShapeNode, IsometricApplier>(
    factory = { ShapeNode(geometry, color).also { it.material = material } },
    update = {
        set(geometry) { ... }
        set(color) { this.color = it; markDirty() }
        set(material) { this.material = it; markDirty() }
        ...
    }
)
```

`color` is derived from `material` but is tracked as an independent captured value in the `update` lambda. Compose's `set()` uses value equality to decide whether to call the setter — this works correctly. However:

1. `color` and `material` can change independently from Compose's perspective (if two successive materials have the same `baseColor()` but different `source`/`faceMap`, only `material` fires — correct).
2. If a maintainer removes `set(color)` as "redundant" (reasoning: `material` already sets the node), Canvas rendering will break because `IsometricNode.renderTo()` reads `this.color` for the tint pass. The `material` setter alone does not update `color`.

The invariant "color must equal material.baseColor()" is implicit. A comment explaining why both are tracked would prevent this accidental removal.

---

### ARCH-04 — `resolve()` KDoc gap / `LocalDefaultColor` type (NIT)

**Files:** `IsometricMaterial.kt:74`, `CompositionLocals.kt:24-26`

`internal fun resolve()` has no comment or KDoc explaining its relationship to `TexturedCanvasDrawHook` or why it is `internal`. Given it has no main-source callers, a reader cannot determine its purpose. A brief comment would suffice.

`LocalDefaultColor` is declared as `staticCompositionLocalOf<IsoColor> { IsoColor(33.0, 150.0, 243.0) }`. The `Shape(material: MaterialData)` overload uses `material: MaterialData = LocalDefaultColor.current` — this compiles because `IsoColor : MaterialData`. The type contract is satisfied. This is correct and by design.

---

## Q3 — `IsometricMaterialComposables.kt` Shape overload resolution

**ISSUE-1 (from review brief): When both `Shape(geometry, material: MaterialData)` and `Shape(geometry, material: IsometricMaterial)` are in scope, which takes precedence for `Shape(Prism(), someIsometricMaterial)`?**

Both are extension functions on `IsometricScope`. Kotlin resolves overloads by most-specific type. `IsometricMaterial` is more specific than `MaterialData` (it is a subtype), so:

```kotlin
Shape(Prism(origin), material = someIsometricMaterial)  // → shader Shape()
Shape(Prism(origin), material = isoColor)                // → compose Shape()
Shape(Prism(origin))                                     // → compose Shape() (MaterialData default)
```

This is correct overload resolution. **No architectural issue if both are imported.** The risk is only if a user imports `isometric-compose` but not `isometric-shader` and passes an `IsometricMaterial` value — this is impossible at compile time because `IsometricMaterial` is not visible without `isometric-shader`.

If a user imports BOTH `io.github.jayteealao.isometric.compose.runtime.Shape` and `io.github.jayteealao.isometric.shader.Shape`, Kotlin will prefer the shader overload for `IsometricMaterial` arguments. This is the intended behavior — no ambiguity.

---

## Q4 — `MaterialData.baseColor()` and Android dependency purity

**Result: CLEAN.** `MaterialData.kt` and `IsoColor.kt` are in `isometric-core`, which uses `id("org.jetbrains.kotlin.jvm")` only. The `baseColor()` default method signature is `fun baseColor(): IsoColor = IsoColor.WHITE`. `IsoColor` is a pure Kotlin `data class` with no Android imports. No Android dependency was introduced in core.

---

## Q5 — `faceMap[face] ?: default` duplication in `GpuTextureManager`

**File:** `GpuTextureManager.kt:270-273`

The inlined `m.faceMap[face] ?: m.default` pattern exposes `PerFace`'s internal resolution logic to `isometric-webgpu`. This is the mechanism chosen when `PerFace.resolve()` was made `internal` to prevent cross-module calls.

The `faceMap` and `default` properties are `val` members of `PerFace` (public), so accessing them is not a violation. However, the resolution semantics ("lookup in map, fall back to default") are now embedded in three places: `TexturedCanvasDrawHook`, `SceneDataPacker`, and `GpuTextureManager`. This is the "knowledge duplication" concern. See ARCH-01 for full analysis.

A cleaner alternative architecture: expose `fun resolve(face: PrismFace?): MaterialData` as a **public** method on `PerFace` (removing the `internal` modifier). The concern that drove making it `internal` was apparently preventing external callers from depending on it — but since `faceMap` and `default` are already public, the logic is already observable. Making `resolve()` public with a stable KDoc contract is no worse than the current state and removes the three-site duplication.

---

## Summary

The critical architectural invariant — `isometric-compose` does NOT depend on `isometric-shader` — is fully preserved. Dependency boundaries verified via both build files and import-level grep. No circular dependencies exist.

Four findings identified. The most impactful is ARCH-01 (three-site duplication of `PerFace` face resolution logic with no single canonical source) and ARCH-02 (`PerFace.baseColor()` missing override returns WHITE silently). Neither is a blocker for shipping, but ARCH-02 in particular is a correctness gap that will surface if any future rendering path relies on `baseColor()` for per-face materials in non-Canvas contexts.
