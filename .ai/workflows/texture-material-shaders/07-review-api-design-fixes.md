---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: api-design-fixes
status: complete
stage-number: 7
created-at: "2026-04-14T20:58:39Z"
updated-at: "2026-04-14T22:30:00Z"
review-round: 2
verdict: ship-with-caveats
commands-run: [correctness, security, code-simplification, testing, maintainability, reliability, api-contracts, refactor-safety, architecture, performance]
metric-commands-run: 10
metric-findings-raw: 62
metric-findings-total: 42
metric-findings-blocker: 0
metric-findings-high: 8
metric-findings-med: 20
metric-findings-low: 9
metric-findings-nit: 5
metric-must-fix: 22
metric-deferred: 5
tags: [api-design, texture, material, shader, review]
refs:
  index: 00-index.md
  slice-def: 03-slice-api-design-fixes.md
  implement: 05-implement-api-design-fixes.md
  verify: 06-verify-api-design-fixes.md
  sub-reviews:
    - 07-review-api-design-fixes-correctness.md
    - 07-review-api-design-fixes-security.md
    - 07-review-api-design-fixes-code-simplification.md
    - 07-review-api-design-fixes-testing.md
    - 07-review-api-design-fixes-maintainability.md
    - 07-review-api-design-fixes-reliability.md
    - 07-review-api-design-fixes-architecture.md
    - 07-review-api-design-fixes-refactor-safety.md
    - 07-review-api-design-fixes-api-contracts.md
    - 07-review-api-design-fixes-performance.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders reviews"
---

# Review: api-design-fixes (Round 2)

## Verdict

**Ship with caveats.**

This is a second-round review after 19 fixes from round 1 (8 HIGH + 11 MED) were applied in commits `379ab2b` and `097d905`. The core refactor is clean — `refactor-safety` returned zero stale references to `FlatColor`, `UvTransform`, or `BitmapSource` across all modules. Two new correctness bugs were introduced by the prior fixes: `Path(IsometricMaterial)` became a 100% throw because the `require` guard rejects both concrete subtypes (CR-1), and the shader cache now suffers from stale `BitmapShader` backing after `TextureCache` eviction (CR-2). Two reliability bugs are pre-existing: `TextureLoader` swallows transient failures as permanent null (REL-01), and error fallback checkerboards are cached under the failed source key with no retry (REL-02). No BLOCKERs — 7 HIGH + 15 MED must be fixed before handoff.

## Domain Coverage

| Domain | Command | Result |
|--------|---------|--------|
| Correctness | `correctness` | Issues Found — 2 HIGH |
| Security | `security` | Issues Found — 1 MED |
| Code simplification | `code-simplification` | Issues Found — 2 MED (1 deferred) |
| Testing | `testing` | Issues Found — 4 MED |
| Maintainability | `maintainability` | Issues Found — 1 MED fixed + 1 deferred |
| Reliability | `reliability` | Issues Found — 2 HIGH + 3 MED |
| API contracts | `api-contracts` | Issues Found — 2 HIGH + 2 MED |
| Refactor safety | `refactor-safety` | Clean — migration verified complete |
| Architecture | `architecture` | Issues Found — 1 HIGH + 1 MED |
| Performance | `performance` | Issues Found — 1 HIGH (deferred) + 3 MED/LOW |

## All Findings (Deduplicated)

| ID | Sev | Conf | Source | File:Line | Issue |
|----|-----|------|--------|-----------|-------|
| CR-1 | HIGH | High | Correctness | IsometricMaterialComposables.kt:146 | Path(IsometricMaterial) rejects all concrete subtypes — 100% throw |
| CR-2 | HIGH | High | Correctness | TexturedCanvasDrawHook.kt:120,124 | Shader cache key uses TextureSource reference; stale after TextureCache eviction |
| REL-01 | HIGH | High | Reliability | TextureLoader.kt:56–68 | catch(Exception)+null indistinguishable: transient failures look like success |
| REL-02 | HIGH | High | Reliability | TexturedCanvasDrawHook.kt:143–153 | Error fallback checkerboard cached under failed source key; no retry path |
| ARCH-01 | HIGH | Med | Architecture | IsometricMaterial.kt:74 | resolve() has zero main-source callers after inlining — dead code + 3-site duplication |
| AC-1 | HIGH | Med | API-Contracts | isometric-compose/api/isometric-compose.api:167 | Binary ABI break needs semver note or @Since annotation |
| AC-2 | HIGH | Med | API-Contracts | isometric-shader/api/isometric-shader.api:172 | TextureLoader.load() non-null in dump but returns Bitmap? at runtime |
| PERF-7 | HIGH | High | Performance | TexturedCanvasDrawHook.kt:123 | Triple allocation every draw (~18k objects/sec) — DEFERRED |
| CR-3 | MED | High | Correctness | TexturedCanvasDrawHook.kt:86 | TileMode CLAMP used for rotation/offset-only transforms (should be REPEAT) |
| CR-4 | MED | Med | Correctness+Performance | TexturedCanvasDrawHook.kt | Tint PorterDuffColorFilter recreated every draw for non-white tints |
| REL-03 | MED | High | Reliability+Performance | ProvideTextureRendering.kt | rememberUpdatedState for onTextureLoadError is a no-op — callback not accessed inside effect |
| REL-04 | MED | Med | Reliability | ProvideTextureRendering.kt | Nested ProvideTextureRendering calls silently replace outer hook — undocumented |
| REL-05 | MED | Med | Reliability | TexturedCanvasDrawHook.kt | "is white" predicate for tint check duplicated; can drift with IsoColor.WHITE definition |
| AC-3 | MED | Low | API-Contracts | isometric-shader.api | PerFace.copy() generated by data class exposes internal fields via public API — DEFERRED |
| AC-4 | MED | Low | API-Contracts | IsometricMaterialComposables.kt:44 | Dual Shape() overloads (IsometricMaterial + MaterialData) — ISSUE-1 / NOT SELECTED |
| AC-5 | MED | Med | API-Contracts | isometric-shader.api | getSides() in dump causes NoSuchMethodError in Java callers — should be @JvmSynthetic |
| TST-01 | MED | High | Testing | IsometricMaterialTest.kt | TextureTransform.init: -Infinity missing for offsetU/V/rotationDegrees validation tests |
| TST-02 | MED | High | Testing | IsometricMaterialTest.kt | Factory companion methods (tiling/rotated/offset) have zero tests |
| TST-03 | MED | High | Testing | PerFaceMaterialTest.kt | require(default !is PerFace) guard is untested |
| TST-06 | MED | Med | Testing | — | onTextureLoadError callback invocation has no unit assertion |
| SEC-1 | MED | Med | Security | TextureSource.kt:41–43 | Asset path `..` check doesn't block URL-encoded `%2e%2e` traversal |
| PERF-8 | MED | Med | Performance | TexturedCanvasDrawHook.kt | FloatArray(6) per computeAffineMatrix call — pre-allocate as field |
| ARCH-02 | MED | Med | Architecture | IsometricMaterial.kt | PerFace.baseColor() always returns WHITE — should delegate to default.baseColor() |
| CS-2 | MED | High | Simplification | TextureCache.kt + TextureCacheConfig | maxSize > 0 validation duplicated in TextureCacheConfig.init and TextureCache.init |
| MNT-04 | MED | Low | Maintainability | IsometricMaterial.kt | PerFace.of() KDoc missing rationale for private constructor |
| DEDUP-1 | MED | High | Simplification+Maintainability+Performance | TexturedCanvasDrawHook.kt | tileU always equals tileV (same IDENTITY predicate); Triple key 3rd slot wasted — DEFERRED |
| MNT-02 | MED | Low | Maintainability | IsometricMaterial.kt | @IsometricMaterialDsl annotation declared at file bottom — DEFERRED |
| SEC-2 | LOW | Med | Security | TextureLoader.kt | catch(Exception) too broad — masks internal bugs as load failures |
| SEC-3 | LOW | Low | Security | TextureLoader.kt | No BitmapFactory.Options.inSampleSize cap — OOM risk on large assets |
| SEC-4 | LOW | Low | Security | TextureCache.kt | Custom TextureLoader can pin arbitrarily large Bitmaps with no size accounting |
| TST-04 | LOW | Med | Testing | — | TextureCacheConfig validation tests absent (default maxSize, zero rejection) |
| TST-05 | LOW | Med | Testing | — | UvGenerator bounds check only tests upper overflow (index=6); lower (index=-1) untested |
| CS-3 | LOW | Med | Simplification | GpuTextureManager.kt:272, SceneDataPacker.kt:205 | faceMap[face] ?: default triplication (also in TexturedCanvasDrawHook post-ARCH-01 fix) |
| MNT-05 | LOW | Low | Maintainability | UvGenerator.kt | KDoc missing mirroring rationale for face-index ordering |
| MNT-06 | LOW | Low | Maintainability | UvCoord.kt | TextureTransform.IDENTITY naming rationale absent |
| PERF-9 | LOW | High | Performance | TexturedCanvasDrawHook.kt | tileU == tileV always (corroborates DEDUP-1) — merged |
| PERF-10 | LOW | Low | Performance | UvCoord.kt | IDENTITY check via 5-float data-class equality per draw |
| PERF-11 | LOW | Low | Performance+Reliability | ProvideTextureRendering.kt | rememberUpdatedState dead (no effect callback reads the value — corroborates REL-03) |
| N-01 | NIT | High | Refactor-Safety | IsometricNode.kt:240 | Stale KDoc references deleted FlatColor.color |
| N-02 | NIT | High | Refactor-Safety | UvCoord.kt | File misnamed — now primarily hosts TextureTransform |
| N-03 | NIT | Low | Security | ProvideTextureRendering.kt | onTextureLoadError KDoc example shows source.toString() — could log paths |
| N-04 | NIT | Low | Maintainability | TexturedCanvasDrawHook.kt | tileV discard at shaderCache key construction uncommented |
| N-05 | NIT | Low | Architecture | IsometricMaterial.kt | PerFace.resolve() lost KDoc when made internal |

**Total:** BLOCKER: 0 | HIGH: 8 | MED: 20 | LOW: 11 | NIT: 5
*(42 deduplicated findings from 62 raw across 10 commands; notable cross-reviewer corroboration: tileU==tileV flagged by CS-1+MNT-09+PERF-9+PERF-10 × 4 reviewers; rememberUpdatedState no-op flagged by REL-03+PERF-11 × 2; tint thrash flagged by CR-4+PERF-9 × 2)*

## Findings (Detailed — HIGH only; see sub-review files for MED+)

### CR-1: Path(IsometricMaterial) always throws [HIGH]

**Location:** `isometric-shader/.../shader/IsometricMaterialComposables.kt:146`
**Source:** correctness

**Evidence:**
```kotlin
// IsometricMaterial is sealed with subtypes: Textured, PerFace
require(material !is IsometricMaterial.Textured && material !is IsometricMaterial.PerFace) {
    "Use Shape() for textured or per-face materials"
}
```

**Issue:** `IsometricMaterial` is sealed with exactly `Textured` and `PerFace` as subtypes. The `require` guard rejects both, meaning every `Path(path, material: IsometricMaterial)` call throws `IllegalArgumentException` at runtime. The overload is 100% unusable.

**Fix:** Remove the `Path(path, material: IsometricMaterial)` overload entirely, or change the signature to `Path(path, material: MaterialData)` and route to the flat-color canvas path for `IsoColor` values.

**Severity:** HIGH | **Confidence:** High

---

### CR-2: Shader cache key stale after TextureCache eviction [HIGH]

**Location:** `isometric-shader/.../render/TexturedCanvasDrawHook.kt:120,124`
**Source:** correctness

**Evidence:**
```kotlin
val key = Triple(material.source, tileU, tileV)   // line 120
shaderCache[key]?.let { return it }                 // line 124
```

**Issue:** `shaderCache` is keyed by `TextureSource` reference + tile mode. When `TextureCache` evicts a source and reloads it (new `Bitmap` instance), the `BitmapShader` cached under the old `TextureSource` key still exists in `shaderCache` — but its backing `Bitmap` has been recycled. The returned `BitmapShader` draws from a recycled bitmap, producing undefined rendering behaviour (typically garbage pixels or a crash on API ≤33).

**Fix:** Include the `Bitmap` instance in the shader cache key: `Triple(bitmap, tileU, tileV)`. Alternatively, clear `shaderCache` when the texture cache notifies an eviction.

**Severity:** HIGH | **Confidence:** High

---

### REL-01: TextureLoader catch(Exception) hides failure type [HIGH]

**Location:** `isometric-shader/.../render/TextureLoader.kt:56–68`
**Source:** reliability

**Issue:** `catch (e: Exception) { null }` wraps both legitimate load failures (file not found, OOM) and programming errors (NullPointerException, ClassCastException). The caller receives `null` for both cases and calls `onTextureLoadError` with no error classification. Transient failures cannot be distinguished from permanent ones — the caller cannot decide whether to retry.

**Fix:** Catch `IOException` + `OutOfMemoryError` separately. Rethrow unexpected `RuntimeException` subtypes after logging. Return a typed `Result<Bitmap>` or introduce a sealed `LoadResult` to let callers discriminate.

**Severity:** HIGH | **Confidence:** High

---

### REL-02: Error checkerboard cached under failed source key — no retry [HIGH]

**Location:** `isometric-shader/.../render/TexturedCanvasDrawHook.kt:143–153`
**Source:** reliability

**Issue:** On load failure, a fallback checkerboard `Bitmap` is stored in `TextureCache` under the original `TextureSource` key. On subsequent frames, `resolveToCache` finds the key → hits the checkerboard → never retries the original source. A transient network timeout or slow disk read becomes a permanent render failure for the lifetime of the `TextureCache`.

**Fix:** Do not insert the fallback into the cache. Store only successful loads. Call `onTextureLoadError` and return the checkerboard directly for rendering without caching it.

**Severity:** HIGH | **Confidence:** High

---

### ARCH-01: PerFace.resolve() dead code + 3-site duplication [HIGH]

**Location:** `isometric-shader/.../shader/IsometricMaterial.kt:74`
**Source:** architecture

**Issue:** After the api-design-fixes slice inlined `faceMap[face] ?: default` into `SceneDataPacker.kt:202-205` and `GpuTextureManager.kt:270-273`, `PerFace.resolve()` has zero non-test callers in main source. Only `TexturedCanvasDrawHook` still calls `material.resolve(face)`. The three sites (`TexturedCanvasDrawHook`, `SceneDataPacker`, `GpuTextureManager`) implement the same resolution logic independently — a change to resolution semantics must be applied three times.

**Fix:** Delete `resolve()`. Inline `faceMap[face] ?: default` in `TexturedCanvasDrawHook` to match the other two callers. All three sites use identical logic; no abstraction is lost.

**Severity:** HIGH | **Confidence:** Med

---

### AC-1: Binary ABI break needs semver note [HIGH]

**Location:** `isometric-compose/api/isometric-compose.api:167`
**Source:** api-contracts

**Issue:** `Shape(geometry, material: MaterialData)` is a binary-incompatible change from `Shape(geometry, color: IsoColor)`. The `.api` dump records the new signature but there is no CHANGELOG entry, `@Since` annotation, or migration note for consumers on older compiled bytecode.

**Fix:** Add a `## Breaking Changes` entry to `CHANGELOG.md` for the `Shape` API change. For internal library use, a comment in the `.api` file suffices.

**Severity:** HIGH | **Confidence:** Med

---

### AC-2: TextureLoader.load() nullability absent from API dump [HIGH]

**Location:** `isometric-shader/api/isometric-shader.api:172`
**Source:** api-contracts

**Issue:** `fun interface TextureLoader { suspend fun load(source: TextureSource): Bitmap? }` — the `?` nullability is present in source but absent from the JVM `.api` dump. Java consumers implementing `TextureLoader` see a non-null return type contract in the dump; returning `null` from a Java implementation bypasses Kotlin's null checks and can cause NPE in callers.

**Fix:** Add `@Nullable` / `@return null if load failed` KDoc to `TextureLoader.load()`. Consider adding the `@JvmName` annotation to force the Kotlin compiler to emit null-check bridges for Java implementors.

**Severity:** HIGH | **Confidence:** Med

---

## Triage Decisions

| ID | Sev | Source | Decision | Notes |
|----|-----|--------|----------|-------|
| CR-1 | HIGH | Correctness | **fix** | Remove unusable Path(IsometricMaterial) overload |
| CR-2 | HIGH | Correctness | **fix** | Bitmap-aware shader cache key |
| REL-01 | HIGH | Reliability | **fix** | — |
| REL-02 | HIGH | Reliability | **fix** | — |
| ARCH-01 | HIGH | Architecture | **fix** | Delete resolve(); inline at TexturedCanvasDrawHook |
| AC-1 | HIGH | API-Contracts | **fix** | Changelog entry |
| AC-2 | HIGH | API-Contracts | **fix** | KDoc + nullability annotation |
| PERF-7 | HIGH | Performance | **defer** | Triple allocation — revisit when tileU≠tileV case is real |
| CR-3 | MED | Correctness | **fix** | TileMode REPEAT condition |
| CR-4 | MED | Correctness+Perf | **fix** | Cache PorterDuffColorFilter |
| REL-03 | MED | Reliability+Perf | **fix** | rememberUpdatedState no-op |
| REL-04 | MED | Reliability | **fix** | Document nested provider behavior |
| REL-05 | MED | Reliability | **fix** | Deduplicate "is white" predicate |
| AC-3 | MED | API-Contracts | **defer** | PerFace.copy() via data class — revisit data-class removal |
| AC-4 | MED | API-Contracts | **not-selected** | ISSUE-1 / dual Shape() overloads — accepted as-is |
| AC-5 | MED | API-Contracts | **fix** | @JvmSynthetic on getSides() |
| TST-01 | MED | Testing | **fix** | -Infinity validation tests |
| TST-02 | MED | Testing | **fix** | Factory companion tests |
| TST-03 | MED | Testing | **fix** | require(default !is PerFace) guard test |
| TST-06 | MED | Testing | **fix** | onTextureLoadError invocation assertion |
| SEC-1 | MED | Security | **fix** | Path normalization before .. check |
| PERF-8 | MED | Performance | **fix** | Pre-allocate FloatArray(6) as field |
| ARCH-02 | MED | Architecture | **fix** | PerFace.baseColor() → default.baseColor() |
| CS-2 | MED | Simplification | **fix** | Remove duplicate maxSize validation |
| MNT-04 | MED | Maintainability | **fix** | PerFace.of() KDoc |
| DEDUP-1 | MED | Simplification+Maint+Perf | **defer** | tileU==tileV always — linked to PERF-7 deferral |
| MNT-02 | MED | Maintainability | **defer** | @IsometricMaterialDsl location — cosmetic |
| SEC-2 through SEC-4 | LOW | Security | untriaged | — |
| TST-04, TST-05 | LOW | Testing | untriaged | — |
| CS-3, MNT-05, MNT-06 | LOW | various | untriaged | — |
| PERF-9 through PERF-11 | LOW | Performance | untriaged | Merged into DEDUP-1/PERF-7 deferred |
| N-01 through N-05 | NIT | various | untriaged | — |

## Recommendations

### Must Fix — HIGH (7)
- **CR-1** — Remove `Path(IsometricMaterial)` overload from `IsometricMaterialComposables.kt:146` [xs]
- **CR-2** — Change shader cache key from `Triple(source, tileU, tileV)` to `Triple(bitmap, tileU, tileV)` [xs]
- **REL-01** — Replace catch(Exception){null} with typed error handling in TextureLoader.kt [s]
- **REL-02** — Remove checkerboard caching from error path in TexturedCanvasDrawHook.kt [xs]
- **ARCH-01** — Delete `PerFace.resolve()`; inline `faceMap[face] ?: default` in TexturedCanvasDrawHook [xs]
- **AC-1** — Add CHANGELOG.md breaking-change entry for `Shape()` signature [xs]
- **AC-2** — Add `@return null if load failed` KDoc + consider `@Nullable` annotation for Java consumers [xs]

### Must Fix — MED (15)
- **CR-3** — TileMode: use `REPEAT` when `transform != TextureTransform.IDENTITY` (TexturedCanvasDrawHook.kt:86) [xs]
- **CR-4** — Cache `PorterDuffColorFilter` keyed on tint color to avoid per-draw allocation [xs]
- **REL-03** — Fix `rememberUpdatedState` no-op: move `onTextureLoadError` into `remember()` key or dereference inside an effect [xs]
- **REL-04** — Document nested `ProvideTextureRendering` behavior (inner replaces outer) in KDoc [xs]
- **REL-05** — Extract `isWhite(color)` predicate into one place; remove duplication [xs]
- **AC-5** — Add `@JvmSynthetic` to `getSides()` to hide from Java callers [xs]
- **TST-01** — Add TextureTransform.init tests for -Infinity on offsetU/V/rotationDegrees [xs]
- **TST-02** — Add tests for `TextureTransform.tiling()`, `.rotated()`, `.offset()` factory companions [xs]
- **TST-03** — Add test asserting `PerFace.of(default = somePerFace)` throws [xs]
- **TST-06** — Add unit assertion verifying `onTextureLoadError` is called on load failure [s]
- **SEC-1** — Normalize path via `java.nio.file.Path` before `..` check in `TextureSource.Asset.init` [xs]
- **PERF-8** — Pre-allocate `FloatArray(6)` as a field in `TexturedCanvasDrawHook` for `computeAffineMatrix` [xs]
- **ARCH-02** — Override `baseColor()` in `PerFace` to return `default.baseColor()` [xs]
- **CS-2** — Remove the duplicate `require(maxSize > 0)` from one of TextureCacheConfig.init / TextureCache.init [xs]
- **MNT-04** — Add KDoc to `PerFace.of()` explaining why the constructor is private [xs]

### Deferred
- **PERF-7** — Triple allocation per draw — wait for a real asymmetric tiling use case before optimizing
- **DEDUP-1** — tileU always equals tileV (same IDENTITY predicate) — structural fix coupled to PERF-7
- **AC-3** — PerFace.copy() via data class public API — depends on data-class removal (larger change)
- **MNT-02** — @IsometricMaterialDsl annotation at file bottom — cosmetic, no runtime impact

### Not Selected
- **AC-4 / ISSUE-1** — Dual `Shape(IsometricMaterial)` + `Shape(MaterialData)` overloads — accepted as-is

## Recommended Next Stage
- **Option A (default):** `/wf-implement texture-material-shaders reviews` — fix 22 Must Fix findings sequentially; most are xs/s size and concentrated in `TexturedCanvasDrawHook.kt` + test files. Run `/compact` first — review dispatch context is noise for implementation.
- **Option B:** `/wf-review texture-material-shaders triage` — re-examine deferred findings at a later point.

## Round History

| Round | Date | Commands | Raw Findings | Deduped | Must Fix | Fixed |
|-------|------|----------|-------------|---------|----------|-------|
| 1 | 2026-04-14 (commit 379ab2b) | 10 (incl. backend-concurrency) | 64 | 49 | 19 (8H+11M) | 19 |
| 2 | 2026-04-14 (this review) | 10 (incl. reliability) | 62 | 42 | 22 (7H+15M) | pending |
