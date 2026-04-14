---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: api-design-fixes
review-command: api-contracts
status: complete
updated-at: "2026-04-14T22:14:55Z"
metric-findings-total: 8
metric-findings-blocker: 0
metric-findings-high: 2
metric-findings-med: 3
metric-findings-low: 2
metric-findings-nit: 1
result: issues-found
tags: [api-contracts, api-design, kotlin]
refs:
  review-master: 07-review-api-design-fixes.md
---

## Findings

| # | Severity | Confidence | Location | Summary |
|---|----------|------------|----------|---------|
| AC-1 | HIGH | High | `isometric-compose/api/isometric-compose.api:167` | `Shape(…, MaterialData)` parameter type change is a binary-incompatible ABI break |
| AC-2 | HIGH | High | `isometric-shader/api/isometric-shader.api:172` | `TextureLoader.load()` declared non-null in API dump but source returns `Bitmap?` — contract gap |
| AC-3 | MED | High | `isometric-shader/api/isometric-shader.api:61–79` | `PerFaceMaterialScope` is an unnecessarily public class — DSL scope with no external instantiation path should be `internal` |
| AC-4 | MED | High | `isometric-shader/api/isometric-shader.api:43–45` | `IsometricMaterialComposables.kt` `Shape(geometry, material: IsometricMaterial)` and `IsometricComposables.kt` `Shape(geometry, material: MaterialData)` are two `Shape` overloads in different modules with overlapping parameter types — ambiguity risk |
| AC-5 | MED | Med | `isometric-shader/api/isometric-shader.api:69` | `PerFaceMaterialScope.getSides()` appears in the public API dump despite `sides` being declared write-only with `@Deprecated(ERROR)` getter |
| AC-6 | LOW | High | `isometric-shader/api/isometric-shader.api:11` | `PerFace` private constructor is correctly enforced but the `synthetic <init>` with `DefaultConstructorMarker` still appears in the API dump — normal but should be noted |
| AC-7 | LOW | Med | `isometric-shader/api/isometric-shader.api:23` | `PerFace.of()` factory name — `of()` does not express intent per guideline §4; `fromMap()` would be clearer |
| AC-8 | NIT | High | `isometric-shader/api/isometric-shader.api:155–167` | `TextureCacheConfig.maxSize` default `= 20` has good KDoc in source but sizing rationale is absent from the `@param cacheConfig` note in `ProvideTextureRendering` |

---

## Detailed Findings

### AC-1 — HIGH: `Shape(…, MaterialData)` in `isometric-compose` is a binary-incompatible ABI break

**File:** `isometric-compose/api/isometric-compose.api` line 167
**File:** `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricComposables.kt` line 56

The `Shape` composable in `isometric-compose` changed its second parameter from `IsoColor` to `MaterialData`:

```
// old ABI
public static final fun Shape (…Lio/github/jayteealao/isometric/IsoColor;…)V
// new ABI
public static final fun Shape (…Lio/github/jayteealao/isometric/MaterialData;…)V
```

This changes the JVM method descriptor, which is a **hard binary break**. Any pre-compiled AAR or APK that calls the old `Shape(scope, shape, IsoColor, …)` overload will get a `NoSuchMethodError` at runtime after updating the library. Source compatibility is preserved only for callers that pass `IsoColor` (which now implements `MaterialData`) and recompile from source.

This is intentional given the project's preference for direct breaking changes (per memory `feedback_no_deprecation_cycles.md`), but it must be flagged in release notes and requires a semver **major** version bump.

**Recommendation:** Confirm a semver major bump in release notes. Add a migration note that binary consumers must recompile.

---

### AC-2 — HIGH: `TextureLoader.load()` contract gap — nullable in source, non-null in API dump

**File:** `isometric-shader/api/isometric-shader.api` line 172
**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/TextureLoader.kt` line 35

The `.api` dump shows:
```
public abstract fun load (Lio/github/jayteealao/isometric/shader/TextureSource;)Landroid/graphics/Bitmap;
```

The source declares `fun load(source: TextureSource): Bitmap?` — nullable. The JVM bytecode descriptor does not encode Kotlin nullability so the dump is technically correct at the JVM level, but this creates a contract gap: a reviewer or tooling reading the `.api` file will infer that `load()` guarantees a non-null `Bitmap`. The actual contract is "return `null` on failure." Any implementor who reads only the `.api` dump and is unaware of Kotlin nullability semantics will write a non-returning implementation that crashes callers who do handle `null`.

**Recommendation:** Add `@Nullable` (via `@org.jetbrains.annotations.Nullable`) to the return type, which will make the API dump emit the `@Nullable` annotation and surface the nullable contract. Alternatively, add an explicit `@return null if the source cannot be loaded` in KDoc so the contract is visible in docs tools.

---

### AC-3 — MED: `PerFaceMaterialScope` is unnecessarily in the public API surface

**File:** `isometric-shader/api/isometric-shader.api` lines 61–79

The dump exposes `PerFaceMaterialScope` as `public final class` with all its getters and setters. The constructor is `internal` (correctly absent from the dump — no `<init>` line present), meaning external consumers cannot instantiate it. However, having the full class in the public API surface:

1. Creates migration obligations if any getter/setter is renamed or removed — the API dump will flag it as a breaking change.
2. Leaks the internal structure of the DSL scope (face property names, `default`, `sides`) as a public contract.
3. Violates guideline §8 (prefer small focused pieces): the scope is purely an implementation mechanism for the `perFace { }` DSL, not a value type users interact with outside the lambda.

**Recommendation:** Make `PerFaceMaterialScope` `internal`. The public API for per-face materials is entirely expressed by `perFace() { }`, `PerFace.of()`, and the `PerFace` data type. The scope class does not need to be visible to module consumers.

---

### AC-4 — MED: Two `Shape` overloads in different modules with overlapping parameter types creates call-site ambiguity

**Files:**
- `isometric-shader/src/main/kotlin/.../IsometricMaterialComposables.kt:44` — `fun IsometricScope.Shape(geometry: Shape, material: IsometricMaterial, …)`
- `isometric-compose/src/main/kotlin/.../IsometricComposables.kt:56` — `fun IsometricScope.Shape(geometry: Shape, material: MaterialData = …, …)`
- Reflected in API dumps: `IsometricMaterialComposablesKt.Shape(…IsometricMaterial…)` and `IsometricComposablesKt.Shape(…MaterialData…)`

Since `IsometricMaterial` extends `MaterialData`, both overloads accept an `IsometricMaterial` argument. When a user imports both `isometric-compose` and `isometric-shader`, passing an `IsometricMaterial` will resolve to the more-specific `isometric-shader` overload (Kotlin resolution prefers more-specific types). This is arguably correct, but the existence of two `Shape` overloads from different packages violates guideline §4 (one term per concept) and §5 (readability): a user reading call-site code cannot determine which `Shape` function is in play without checking imports.

Additionally, the `isometric-shader` `Shape` at line 44 is documented with `@see io.github.jayteealao.isometric.compose.runtime.Shape` — the KDoc is aware of the ambiguity. But the API contract for "which Shape should I use for textured content" is not clear from the API surface alone.

**Recommendation:** Per guideline §9 (escape hatches sit on top, not beside), the `isometric-shader` `Shape` should be presented as an override/extension of the compose one, not a parallel entry point. Consider renaming the shader overload to `ShapeTextured()` or `TexturedShape()`, or removing it in favour of making the `isometric-compose` `Shape(…, material: MaterialData)` the single entry point (which now accepts `IsometricMaterial` as `MaterialData`). The latter is already the case from an ABI perspective — the `isometric-shader` overload is now technically redundant since `Shape(…, MaterialData)` in `isometric-compose` accepts `IsometricMaterial` directly.

---

### AC-5 — MED: `PerFaceMaterialScope.getSides()` appears in the public API dump despite write-only declaration

**File:** `isometric-shader/api/isometric-shader.api` line 69
**File:** `isometric-shader/src/main/kotlin/.../IsometricMaterial.kt` lines 202–205

The source declares:
```kotlin
var sides: MaterialData?
    @Deprecated("sides is write-only", level = DeprecationLevel.ERROR)
    get() = error("sides is write-only")
    set(value) { front = value; back = value; left = value; right = value }
```

The API dump shows:
```
public final fun getSides ()Lio/github/jayteealao/isometric/MaterialData;
```

`@Deprecated(level = DeprecationLevel.ERROR)` prevents the getter from being called at the source level, but it does **not** remove it from the binary API — the JVM getter method is still present and callable via reflection or Java interop. Any Java caller that calls `getSides()` will receive a runtime `IllegalStateException` ("sides is write-only") with no compile-time warning.

This is a guideline §6 violation: the invalid state (calling `getSides()`) is expressible and crashes at runtime for Java callers. A write-only property should either be modeled as a method (`fun setSides(value: MaterialData)`) or the getter should throw a more descriptive error. The API dump advertising a getter that always throws is misleading.

**Recommendation:** Convert `sides` from a `var` with deprecated getter to a plain `fun setSides(value: MaterialData?)` method. This removes the getter from the API surface entirely and makes the write-only contract explicit.

---

### AC-6 — LOW: `PerFace` private constructor `synthetic <init>` appears in API dump

**File:** `isometric-shader/api/isometric-shader.api` line 11

```
public synthetic fun <init> (Ljava/util/Map;Lio/github/jayteealao/isometric/MaterialData;Lkotlin/jvm/internal/DefaultConstructorMarker;)V
```

This `synthetic` constructor is the Kotlin compiler mechanism for implementing the `private constructor` with default parameters. It takes `DefaultConstructorMarker` as the third argument — a Kotlin-internal type — so it cannot be called from Java without reflection. The presence of a `public synthetic` entry in the API dump is surprising but does not create a usable public construction path. The `kotlinx-binary-compatibility-validator` includes `synthetic` methods with public visibility; this is a known artifact.

This finding is informational. The `PerFace` factory pattern via `PerFace.of()` is correctly enforced.

**Recommendation:** No action required for the synthetic constructor. If this causes confusion in API review tooling, consider whether `@Suppress("SYNTHETIC_MEMBER")` or a different visibility strategy applies.

---

### AC-7 — LOW (NIT): `PerFace.of()` factory name does not express intent per guideline §4

**File:** `isometric-shader/api/isometric-shader.api` line 21

`of(faceMap, default)` is a common Java value-factory convention (`List.of`, `Map.of`). Per guideline §4, names should express user intent, not implementation convention. `of()` does not communicate that this constructs a `PerFace` from a pre-built map. `fromMap()` or `withFaceMap()` would be clearer, especially since the primary path is the `perFace { }` DSL builder and `of()` is the advanced/escape-hatch path.

**Recommendation:** Rename to `fromMap()`. This is a breaking change but cheap now (the slice is new) and harder later. Low priority given the DSL is the hero path.

---

### AC-8 — NIT: `ProvideTextureRendering` `@param cacheConfig` note lacks sizing rationale

**File:** `isometric-shader/src/main/kotlin/.../render/ProvideTextureRendering.kt` lines 64–67

The `TextureCacheConfig` class KDoc explains the `maxSize = 20` default well ("count distinct TextureSource keys your scene uses"). However, the `@param cacheConfig` note in `ProvideTextureRendering` only says "LRU cache configuration. Controls max in-memory texture count." — callers reading the composable's doc hover in an IDE will not see the sizing rationale without drilling into `TextureCacheConfig`.

**Recommendation:** Expand the `@param cacheConfig` note to: "LRU cache configuration. Defaults to `TextureCacheConfig()` (maxSize=20, sufficient for scenes with up to 20 distinct textures). Increase `maxSize` for large tile sets."

---

## Conformance Summary

| Check | Result |
|-------|--------|
| `IsoColor : MaterialData` in API dump | PASS — `isometric-core.api` line 22 shows `IsoColor : MaterialData` |
| `FlatColor` removed from `IsometricMaterial` | PASS — not present in `isometric-shader.api`; only `Textured` and `PerFace` subtypes |
| `TextureSource.Bitmap` (not `BitmapSource`) | PASS — `isometric-shader.api` line 96 shows `TextureSource$Bitmap` |
| `TextureTransform` (not `UvTransform`) | PASS — `isometric-shader.api` line 121 shows `TextureTransform` |
| `TextureTransform` companion factories | PASS — `IDENTITY`, `tiling()`, `rotated()`, `offset()` all present in `isometric-shader.api` lines 144–149 |
| `TextureTransform.init` validation | PASS — `init` block validates all five fields with `require()` including `isFinite()` and non-zero scale; covers guideline §6 |
| `PerFace.of()` factory present | PASS — `isometric-shader.api` line 21 |
| `PerFace` primary constructor private | PASS — no public `<init>` in dump; only `synthetic` marker present |
| `PerFace` component functions absent | PASS — `PerFace` was converted from `data class` to `class`; `component1()`/`component2()` do NOT appear in `isometric-shader.api` (lines 8–18) |
| `PerFaceMaterialScope` internal constructor | PASS — no `<init>` entry in `PerFaceMaterialScope` section of dump |
| `UvGenerator` internal | PASS — not present in any `.api` dump |
| `UvCoord` internal | PASS — not present in any `.api` dump |
| `PerFace.resolve()` internal | PASS — not present in `isometric-shader.api` |
| `TextureCacheConfig(maxSize: Int = 20)` data class | PASS — `isometric-shader.api` lines 155–167 match |
| `TextureLoader` as `fun interface` | PASS — `isometric-shader.api` line 169 shows `abstract interface`; `fun interface` compiles to the same; SAM conversion works |
| `texturedResource()` present | PASS — `isometric-shader.api` line 57 |
| `ProvideTextureRendering` signature | PASS — `isometric-shader.api` line 152 matches `(TextureCacheConfig, TextureLoader?, Function1?, Function2, Composer, II)` |
| `Shape(geometry, material: MaterialData)` in isometric-compose | PASS — `isometric-compose.api` line 167 |

The `isometric-webgpu.api` dump contains no texture/material types — all GPU pipeline types remain unchanged and are consistent with the described changes.

## Overall Assessment

The API surface is broadly consistent with the documented changes. Two HIGH findings must be addressed before release: the `TextureLoader.load()` nullability contract gap (AC-2) and the ABI break requiring a semver major bump (AC-1). Three MED findings (AC-3, AC-4, AC-5) are design quality issues that should be addressed before 1.0 to avoid accumulating technical debt in the public API surface. The `PerFace` data class to class conversion is correctly executed — the destructuring leak (previously flagged as a finding in the prior draft of this review) does **not** exist: `component1()` and `component2()` are absent from the API dump.
