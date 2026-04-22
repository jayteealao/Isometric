---
schema: sdlc/v1
type: review-command
review-command: api-contracts
slice-slug: uv-generation-knot
slug: texture-material-shaders
status: complete
stage-number: 7
created-at: "2026-04-20T00:00:00Z"
updated-at: "2026-04-20T00:00:00Z"
commits-reviewed: ["e5cf72a"]
tags: [api-contracts, binary-compat, knot, experimental, sourcePrisms, perFace-fallback, progressive-disclosure]
refs:
  index: 00-index.md
  implement: 05-implement-uv-generation-knot.md
  verify: 06-verify-uv-generation-knot.md
  guideline: docs/internal/api-design-guideline.md
  prior-review: 07-review-uv-generation-stairs-api-contracts.md
  review-master: 07-review.md
---

# Review: api-contracts

## Scope

Single commit `e5cf72a` on branch `feat/texture`.

**Commit:** feat(texture-material-shaders): implement uv-generation-knot

**Public API delta (`isometric-core.api`):** One line added — `getSourcePrisms()` on
`Knot`. The entry in the binary API file at the time of review:

```
public final class io/github/jayteealao/isometric/shapes/Knot : io/github/jayteealao/isometric/Shape {
    public static final field Companion Lio/github/jayteealao/isometric/shapes/Knot$Companion;
    public fun <init> ()V
    public fun <init> (Lio/github/jayteealao/isometric/Point;)V
    public synthetic fun <init> (Lio/github/jayteealao/isometric/Point;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
    public final fun getPosition ()Lio/github/jayteealao/isometric/Point;
    public final fun getSourcePrisms ()Ljava/util/List;           ← NEW
    public synthetic fun translate (DDD)Lio/github/jayteealao/isometric/Shape;
    public fun translate (DDD)Lio/github/jayteealao/isometric/shapes/Knot;
}
```

**`isometric-shader.api` delta:** Zero — `UvGenerator` is `internal object`; `forKnotFace`,
`forAllKnotFaces`, and `quadBboxUvs` do not appear in the public binary surface.
`UvCoordProviderForShape.kt` is `internal fun`; the `is Knot` branch is not visible.
`apiCheck` passes on all four modules.

**New API surface reviewed:**

| Symbol | Visibility | Annotation | Location |
|--------|-----------|-----------|----------|
| `Knot.sourcePrisms: List<Prism>` | `public val` | `@ExperimentalIsometricApi` | `isometric-core` |
| `UvGenerator.forKnotFace(Knot, Int): FloatArray` | `internal` | `@ExperimentalIsometricApi` | `isometric-shader` |
| `UvGenerator.forAllKnotFaces(Knot): List<FloatArray>` | `internal` | `@ExperimentalIsometricApi` | `isometric-shader` |
| `quadBboxUvs(Path): FloatArray` | `private` | none | `isometric-shader` |

---

## Primary API Questions This Review Must Answer

The review prompt specifies six focal questions. Each is answered in the findings below.

1. **Does `sourcePrisms` exposure match §6 (Invalid States) — could a user mutate the
   returned `List`, and §10 (Host Language) — should it be `ImmutableList` or read-only?**
2. **Is the name `sourcePrisms` clear about its role?**
3. **Error message quality and consistency.**
4. **Semantic versioning implications — is adding `Knot.sourcePrisms` a breaking change?**
5. **The silent `perFace {}` fallback — does §6 or §7 suggest this should be a compile
   error or warning?**
6. **KDoc completeness for the experimental contract.**

---

## Summary

The `uv-generation-knot` slice adds one public symbol — `Knot.sourcePrisms` — to
`isometric-core.api`. All implementation changes in `isometric-shader` are internal
or private. `apiCheck` passes across all four modules. The commit is purely additive:
no existing public symbol is modified, no semantics change, and no deprecation is needed.

The single **HIGH** finding is that `sourcePrisms` returns `List<Prism>` — a Kotlin
`ArrayList` at runtime — which is mutable via unsafe cast. §10 (Host Language) says
Kotlin APIs should use the type system to communicate intent accurately; the correct
idiomatic return type for a property that must not be mutated is a `List` backed by
`listOf()` (read-only view), which is what the code already produces. However, callers
can up-cast and mutate the underlying `ArrayList`, which could corrupt UV generation
silently. The HIGH finding recommends either documenting the non-mutation contract
explicitly in KDoc (low friction, no API change) or returning
`Collections.unmodifiableList(...)` / a dedicated `persistentListOf(…)` (strong
guarantees). Neither option requires an API-file change since the JVM return type
stays `java/util/List`.

The two **MED** findings cover (a) the silent `perFace {}` fallback — documented in
KDoc but not surfaced as a compile-time opt-in at the call site, which means a user
who passes `PerFace.Prism(...)` to `Shape(Knot(...), material)` gets silent wrong-
material rendering with no IDE warning — and (b) the naming ambiguity of
`sourcePrisms`: the property is specifically the *pre-transform, unscaled* Prisms used
for UV normalization; the name does not distinguish this specialization from, say, a
generic "the Prisms this Knot is made of".

All other findings are LOW or NIT.

**Severity Breakdown:**
- BLOCKER: 0
- HIGH: 1 (`sourcePrisms` returns a mutable-at-runtime `List` — §10 Host Language)
- MED: 2 (silent `perFace {}` fallback not compile-gated — §6/§7; `sourcePrisms` name
  ambiguity — §4 Naming)
- LOW: 2 (dual `else` branch in `forKnotFace` is dead code but not unreachable to the
  compiler; `@ExperimentalIsometricApi` opt-in level is `WARNING`, not `ERROR`)
- NIT: 1 (KDoc for `sourcePrisms` mentions its consumer but omits the post-transform
  note for faces 18–19)

**Merge Recommendation: APPROVE_WITH_COMMENTS**

No blockers. API-01 (HIGH) can be resolved with a one-line KDoc addition or a
`Collections.unmodifiableList` wrapper with no binary API diff. API-02 and API-03
(MED) are design questions worth tracking; API-02 in particular should be revisited
before a public 1.0 release.

---

## Findings

### API-01: `sourcePrisms` returns a runtime-mutable `List` without a mutation contract [HIGH]

**Confidence:** High

**Location:** `isometric-core/src/main/kotlin/.../shapes/Knot.kt:35`

**Issue:**

```kotlin
@ExperimentalIsometricApi
val sourcePrisms: List<Prism> = listOf(
    Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
    Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
    Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
)
```

`listOf(...)` in Kotlin returns `java.util.Arrays$ArrayList` which is **not** the JDK
`java.util.ArrayList` and does not support `add`/`remove`, but it does support
`set(index, element)` for list implementations that override it. More importantly, the
declared return type is `List<Prism>` — the Kotlin read-only interface — but at the JVM
binary level `isometric-core.api` exposes `()Ljava/util/List;`, which any Java caller or
unsafe Kotlin cast can widen to `MutableList<Prism>` and modify. A caller who does:

```kotlin
@OptIn(ExperimentalIsometricApi::class)
val knot = Knot()
(knot.sourcePrisms as MutableList).set(0, Prism(Point.ORIGIN, 99.0, 1.0, 1.0))
```

would corrupt UV generation for all subsequent calls to `UvGenerator.forKnotFace(knot,
...)` for the same instance without any error.

§10 (Host Language) says the API should use "nullability semantics" and the host type
system to express intent correctly. The Kotlin convention for a truly immutable list that
is part of a data object's contract is to either:

(a) Document the non-mutation contract clearly in KDoc (lowest friction, no binary change),
(b) Return `Collections.unmodifiableList(list)` which wraps at runtime and throws
`UnsupportedOperationException` on any write attempt, or
(c) Depend on a library such as `kotlinx.collections.immutable` and return
`ImmutableList<Prism>`.

Option (a) costs one sentence of KDoc. Option (b) costs one import and one wrapper call.
Option (c) adds a new module dependency.

The companion slice `forPyramidFace` documented a mutation contract ("Returns a shared
read-only array. Callers must not mutate the returned FloatArray") for its shared
`LATERAL_CANONICAL_UVS`. The same class of contract is required here, but for
`sourcePrisms`.

The current KDoc for `sourcePrisms` states:

> "The values must stay in sync with the constants in `createPaths`; a regression guard
> pins this in `UvGeneratorKnotTest`."

This says nothing about callers mutating the list. A user reading the property signature
sees `List<Prism>` and knows Kotlin's `List` is read-only by convention — but there is
no explicit prohibition in the KDoc, and no runtime enforcement.

**Impact on §10 Host Language:** The Kotlin type system convention is to use `List<T>` as
a read-only view. The implementation (`listOf(...)`) is correctly Kotlin-idiomatic. The
missing piece is that the KDoc does not state the mutation contract, leaving an implicit
assumption for callers.

**Impact on §6 Invalid States:** A mutated `sourcePrisms[0]` would cause
`UvGenerator.forKnotFace` to silently produce wrong UVs for faces 0–5 without any
error or warning at the call site that caused the corruption. This is an invalid state
that is expressible and silent — exactly what §6 flags.

**Evidence:**

```kotlin
// Knot.kt:34–39
@ExperimentalIsometricApi
val sourcePrisms: List<Prism> = listOf(
    Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
    Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
    Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
)
// KDoc has no "do not mutate" contract statement.
```

**Recommended fix (Option A — minimal, no binary change):**

Append to the `sourcePrisms` KDoc:

```kotlin
/**
 * ...existing KDoc...
 *
 * This list is a read-only view. Callers must not cast it to [MutableList] or
 * modify its elements — doing so corrupts UV generation for this instance without
 * any runtime error. The elements are immutable [Prism] values; only the list
 * reference itself needs guarding.
 */
```

**Recommended fix (Option B — runtime enforcement, no binary change):**

```kotlin
@ExperimentalIsometricApi
val sourcePrisms: List<Prism> = Collections.unmodifiableList(listOf(
    Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
    Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
    Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
))
```

The JVM return type in `isometric-core.api` remains `()Ljava/util/List;` in both options —
no `apiDump` needed.

**Recommendation:** Apply Option A immediately (one KDoc sentence, zero risk). Apply
Option B before a public 1.0 release if defense-in-depth is desired. Option C
(`ImmutableList`) is not recommended for this library at this stage — it adds a module
dependency for a single property.

---

### API-02: Silent `perFace {}` fallback is not surfaced at the call site — §6 Invalid States / §7 Errors [MED]

**Confidence:** High

**Location:** `isometric-core/src/main/kotlin/.../shapes/Knot.kt:8–20` (KDoc),
`isometric-shader/src/main/kotlin/.../shader/UvCoordProviderForShape.kt:32–40` (internal)

**Issue:**

The `Knot` class KDoc states:

> `perFace {}` is not supported — Knot has no named face taxonomy, and the shape's
> depth-sorting bug makes per-face visual results unreliable. If a `PerFace` material
> is passed to a `Shape(Knot(...), material)` composable, every face resolves to the
> `PerFace.default` material.

This is accurate documentation, but the silent fallback violates §6 and §7 in
combination:

- §6 (Invalid States): Passing `PerFace.Prism(top = grass, front = brick)` to
  `Shape(Knot(...), PerFace.Prism(...))` is an expressible combination that is documented
  as unsupported. The API does not make this combination hard to express; it silently
  discards all per-face assignments and renders `PerFace.default` on every face. A user
  who copies the `Prism` per-face pattern onto a `Knot` will see visually wrong results
  with no error.
- §7 (Errors): "Design Errors as a First-Class Part of the API. Good errors should
  answer: What went wrong? Why did it happen? Is it a usage error or a runtime problem?"
  The current behavior answers none of these questions — no error is raised.

The KDoc-only mitigation is consistent with the library's stated approach (no
deprecation cycles, no compile-time additions in this slice), and the depth-sorting bug
on `Knot` itself already limits its production suitability. However, from a pure API
design perspective, the combination of `Shape(Knot(...), PerFace.*)` is a caller
mistake (§7: "distinguish caller mistakes from runtime failures") that the API currently
swallows silently.

Three options exist:

**(a) Status quo — documented KDoc only (current approach).** Acceptable for an
`@ExperimentalIsometricApi` type where the contract is explicitly noted. This is the
pragmatic choice given the experimental scope. The weakness is that the KDoc note is on
`Knot`, not on `Shape()` — a user passing `PerFace` to `Shape()` may not see the note
unless they navigate to `Knot`.

**(b) `require` check in `uvCoordProviderForShape`.** When `shape is Knot` and the
material is `PerFace`, throw `IllegalArgumentException("perFace {} is not supported on
Knot; use textured() or a flat color. See Knot KDoc.")`. This surfaces the mistake at
render time (not compile time). Internal to the shader module; no public API change.

**(c) Separate `UvCoordProvider` return type.** Not feasible without new public types.

For an `@ExperimentalIsometricApi` type, option (a) is defensible. Option (b) is
straightforward and requires a one-line `require` inside the existing `is Knot` arm of
`uvCoordProviderForShape`. It does not affect `isometric-shader.api` (internal function).

**Evidence:**

```kotlin
// UvCoordProviderForShape.kt:38
is Knot -> UvCoordProvider { _, faceIndex -> UvGenerator.forKnotFace(shape, faceIndex) }
// No guard against material being PerFace; the renderer will call this provider regardless.

// Knot.kt:14–19 — correct documentation, but only on the shape class:
// "If a PerFace material is passed to a Shape(Knot(...), material) composable,
//  every face resolves to the PerFace.default material."
```

**Recommended fix (Option B — runtime guard in renderer):**

```kotlin
// In uvCoordProviderForShape, the `is Knot` arm is currently only reachable when a
// UvCoordProvider is needed — i.e., when the material IS textured. The guard belongs
// higher up, in the consumer that calls uvCoordProviderForShape when it sees a
// PerFace material on a Knot shape. For example, in ShapeNode.renderTo:
//
//   if (material is IsometricMaterial.PerFace && transformedShape is Knot) {
//       Log.w("Isometric", "perFace {} is not supported on Knot; " +
//           "material falls back to PerFace.default on every face. " +
//           "See Knot class KDoc for details.")
//   }
//
// This is a single logcat warning (not a throw) since Knot is experimental and
// rendering should not crash for this case.
```

**Recommendation:** For this slice (experimental scope) the current documentation-only
approach is acceptable. Before a public 1.0 release, add a `Log.w` in `ShapeNode.renderTo`
when `material is IsometricMaterial.PerFace && shape is Knot`. This answers §7's "what
went wrong / what should the caller do next" without breaking any existing behavior.

---

### API-03: `sourcePrisms` name does not communicate "pre-transform, UV-only" intent — §4 Naming [MED]

**Confidence:** Med

**Location:** `isometric-core/src/main/kotlin/.../shapes/Knot.kt:25–39`

**Issue:**

The property is named `sourcePrisms`. This is accurate — the Knot is assembled from three
source Prisms. However, the name does not communicate:

1. That the Prisms are in *pre-transform (unscaled, untranslated) space*, not in world space.
2. That the property exists specifically to support UV generation, not general Knot
   decomposition.

A user reading `knot.sourcePrisms` might reasonably assume the Prisms represent the
Knot's structure in scene coordinates and attempt to use them for geometric operations
(hit testing, bounding box computation, collision). Doing so would produce wrong results
because the Prisms have not been scaled by `1/5` or translated by `(-0.1, 0.15, 0.4)`.

§4 (Naming) says: "Names should reflect what the user is trying to do, not how the
internals happen to work." The user-intent here is UV coordinate normalization for the
Knot's sub-prism faces. A more explicit name would be:

- `sourcePrismsForUv` — very direct, low ambiguity
- `uvSourcePrisms` — matches the `uvCoordProvider` naming convention
- `prismBases` — shorter but less clear about the transform space

The KDoc for `sourcePrisms` does state "in pre-transform (unscaled, untranslated) space"
and "Consumed by `UvGenerator.forKnotFace`", so the intent is documented. The naming
concern is that a developer reading the property signature alone (e.g., in IDE autocomplete
or a generated API reference) sees `sourcePrisms: List<Prism>` without the KDoc context
and may infer the wrong usage.

§4 also notes this is a public property on an `@ExperimentalIsometricApi` class, which
gives latitude for a rename before stabilization — renaming after stabilization would
require a deprecation cycle (§11 Evolution).

**Evidence:**

```kotlin
// Knot.kt:34
@ExperimentalIsometricApi
val sourcePrisms: List<Prism> = listOf(...)
// "sourcePrisms" without KDoc → no indication of transform space
```

**Recommended fix:**

Either:

```kotlin
// Option A: rename (easiest before stabilization)
@ExperimentalIsometricApi
val uvSourcePrisms: List<Prism> = listOf(...)
```

Or:

```kotlin
// Option B: keep name, strengthen KDoc opening sentence to front-load the constraint
/**
 * The three [Prism] instances used as UV normalization references for sub-prism
 * face groups, in **pre-transform (unscaled, untranslated) coordinate space**.
 * ...
 */
```

Option B is zero-cost and can be applied immediately. Option A is preferable but
requires updating `isometric-core.api` (one-line rename, zero binary consumers since
`Knot` is experimental and the library is pre-1.0).

**Recommendation:** Apply Option B (KDoc strengthening) now. Consider Option A before
the experimental annotation is lifted — a rename on a stabilized public property would
require a deprecation period (§11).

---

### API-04: Dual `else` branches in `forKnotFace` — dead code but not compiler-unreachable [LOW]

**Confidence:** High

**Location:** `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt:374–384`

**Issue:**

```kotlin
@OptIn(ExperimentalIsometricApi::class)
@ExperimentalIsometricApi
fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray {
    require(faceIndex in knot.paths.indices) {   // guard 1: rejects < 0 and >= 20
        "faceIndex $faceIndex out of bounds for Knot with ${knot.paths.size} faces ..."
    }
    return when (faceIndex) {
        in 0..17 -> { ... }
        18, 19   -> quadBboxUvs(knot.paths[faceIndex])
        else     -> throw IllegalArgumentException(   // guard 2: unreachable given guard 1
            "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
        )
    }
}
```

After `require(faceIndex in knot.paths.indices)` passes, `faceIndex` is in `0..19`. The
`when` branches cover `0..17` and `18, 19`, exhausting all integers in `0..19`. The
`else` branch is therefore dead code — it can never be reached at runtime.

The `else` is required because the Kotlin compiler cannot prove the exhaustion (the
`when` is not on a sealed type or `Boolean`). This is not a defect, but it is worth
noting:

- The two guards emit different error messages for the same logical error (index out of
  bounds). If `Knot` ever gains a 21st face, the `require` would still throw with
  "Knot with 21 faces" while the `else` would throw "Knot has exactly 20 faces" — the
  latter becoming wrong.
- The error messages are inconsistent in format: guard 1 follows the project-wide
  `"$name $idx out of bounds for $Shape with $n faces (valid range: 0 until $n)"` pattern;
  the `else` branch uses a different sentence that hard-codes the count (`"exactly 20"`).

The sibling `forPrismFace`, `forCylinderFace`, `forStairsFace` all use the single-guard
pattern without an inner `else`. Having two guards on `forKnotFace` is inconsistent with
the sibling style.

**Impact on §7 (Errors):** Inconsistent error formats across similar operations is a §7
concern. Both messages convey the right information, but their phrasing differs from the
established pattern used by all other `forXFace` methods.

**Evidence:**

```kotlin
// UvGenerator.kt:371–373 — guard 1 (consistent with siblings):
require(faceIndex in knot.paths.indices) {
    "faceIndex $faceIndex out of bounds for Knot with ${knot.paths.size} faces (valid range: 0 until ${knot.paths.size})"
}
// UvGenerator.kt:381–383 — guard 2 (inconsistent, dead):
else -> throw IllegalArgumentException(
    "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
)
```

**Recommended fix:**

Remove the `else` throw branch and let the `when` be exhaustive via an `else -> error("unreachable")` or, better, an `else` branch with a `check(false) { ... }` that
documents the invariant without a meaningful error path. Alternatively, suppress the
dead-code warning with a comment explaining why the `else` exists (compiler requirement):

```kotlin
// The `require` above ensures faceIndex in 0..19. The `else` is required by the
// Kotlin compiler since `when` is not on a sealed type, but is unreachable at runtime.
else -> error("Unreachable: faceIndex=$faceIndex should have been caught by require above")
```

This keeps the single source of truth for the error message in the `require` and makes
the `else` clearly defensive rather than a real validation branch.

---

### API-05: `@ExperimentalIsometricApi` opt-in level is `WARNING`, not `ERROR` — callers may silently ignore it [LOW]

**Confidence:** Med

**Location:** `isometric-core/src/main/kotlin/.../ExperimentalIsometricApi.kt:7`

**Issue:**

```kotlin
@RequiresOptIn(
    message = "This API is experimental and may change without notice.",
    level = RequiresOptIn.Level.WARNING    // ← WARNING, not ERROR
)
annotation class ExperimentalIsometricApi
```

`RequiresOptIn.Level.WARNING` means a caller who uses `Knot` or `Knot.sourcePrisms`
without `@OptIn` receives a compiler warning, not an error. The warning can be silenced
without opt-in annotation if the project's `build.gradle.kts` suppresses `EXPERIMENTAL_API_USAGE`
warnings globally. Under `ERROR` level, the annotation cannot be bypassed without
`@OptIn`.

This is not specific to this slice — it is a pre-existing library-wide choice. However,
this review is the first opportunity to note it in the context of `sourcePrisms`: because
the property's mutation contract (API-01) relies on callers understanding they have opted
into experimental surface, a `WARNING`-level annotation provides weaker guarantees than
`ERROR` about whether callers have consciously opted in.

For an experimental class (`Knot`) on a pre-1.0 library, `WARNING` is a reasonable
default to avoid blocking callers who need the shape in production apps. The concern is
NIT-adjacent but is noted as LOW because the mutation risk in API-01 is real and would
be slightly better-guarded under `ERROR`.

**Recommended fix:** Consider elevating to `RequiresOptIn.Level.ERROR` before the first
public release. Alternatively, keep `WARNING` but document in `ExperimentalIsometricApi`
KDoc that callers who suppress the warning rather than opting in are not covered by
stability guarantees.

---

### API-06: `sourcePrisms` KDoc omits the post-transform context of faces 18–19 [NIT]

**Confidence:** High

**Location:** `isometric-core/src/main/kotlin/.../shapes/Knot.kt:25–33`

**Issue:**

The `sourcePrisms` KDoc says:

> "Index 0 → faces 0–5, index 1 → faces 6–11, index 2 → faces 12–17. Faces 18 and 19
> are custom quads with no source Prism."

This is correct and complete. However, it does not explain why faces 18–19 have no
source Prism entry in `sourcePrisms` — they use a *post-transform* bounding-box
projection (`quadBboxUvs`) operating on `knot.paths[18]` and `knot.paths[19]` which
are in world (scaled+translated) space. A developer reading `sourcePrisms` might wonder
whether they need to compute something for faces 18–19 analogous to what they do for
faces 0–17.

Adding one sentence closes the gap:

```kotlin
/**
 * ...
 * Faces 18 and 19 are custom closing quads with no corresponding source Prism;
 * their UV coordinates are computed via axis-aligned bounding-box projection in
 * post-transform path space by `UvGenerator.forKnotFace` directly.
 */
```

---

## API Surface Analysis

**Public types added by this commit:** 0  
**Public properties added by this commit:** 1 (`Knot.sourcePrisms`)  
**Public types modified by this commit:** 0 (no existing public symbol changed)  
**Internal types reviewed:** `UvGenerator.forKnotFace`, `UvGenerator.forAllKnotFaces`,
`quadBboxUvs`, `uvCoordProviderForShape`

**Versioning Strategy:** Library is pre-1.0 on branch `feat/texture`, not yet merged
to `master`. All public symbols added by this commit carry `@ExperimentalIsometricApi`,
which is the correct stability marker for unstabilized surface area.

**Semantic versioning — is `Knot.sourcePrisms` a breaking change?**

No. Adding a new property to an existing public class is an *additive* change under
SemVer. It does not break binary compatibility (existing callers compiled against the
prior API continue to work), and it does not break source compatibility (no existing
code references `sourcePrisms` before this commit). The `isometric-core.api` file grows
by exactly one line (`getSourcePrisms()`). No version bump is required beyond what the
library team would do for a minor/patch release. This is confirmed by the `apiCheck`
pass.

**Current Version:** pre-release (branch `feat/texture`, not on `master`)  
**Version Bump Needed:** No — additive only. `@ExperimentalIsometricApi` is the
appropriate signal for callers.

---

## Breaking Changes Summary

| File | Change Type | Severity | Assessment |
|------|------------|----------|------------|
| `Knot.kt` | New public property `sourcePrisms` | Additive | Safe — no existing callers |
| `UvGenerator.kt` | New internal methods `forKnotFace`, `forAllKnotFaces`, `quadBboxUvs` | Additive (internal) | Safe |
| `UvCoordProviderForShape.kt` | `is Knot` arm added — `uvCoordProviderForShape` now returns non-null for `Knot` | Behavioral (internal) | Safe — prior behavior was `null` (no UV), new behavior is non-null (UV enabled). Any caller that tested `uvCoordProviderForShape(Knot()) == null` would fail; `PerFaceSharedApiTest.kt` was updated to reflect this |
| `PerFaceSharedApiTest.kt` | `uvCoordProviderForShape returns null` test replaced | Test-only | Safe — prior assertion was testing a stub state, new assertion tests the graduated behavior |

No public-API breaking changes.

---

## Deprecation Audit

**Deprecated fields found in this commit:** 0  
**Missing deprecation markers:** 0  
**Fields removed without prior deprecation:** 0  
**Deletions:** None (the prior `PerFaceSharedApiTest` assertion was replaced, not
a public API element)

---

## Backwards Compatibility Check

**§11 Evolution — "never silently break semantics":**

The only semantic change visible to existing callers is that `uvCoordProviderForShape(Knot())`
now returns a non-null `UvCoordProvider` where it previously returned `null`. Any code
path that relied on Knot silently falling back to flat-color rendering (because no UV
provider existed) will now execute UV generation instead. This is a correctness improvement:
the prior behavior was a documented TODO (no provider = no texture rendering), not a
semantic contract.

The `perFace {}` fallback behavior is unchanged — `faceType = null` for Knot commands
means `PerFace.faceMap[null] ?: default` still returns `default` for every face. No
existing `PerFace` usage on Knot is broken.

---

## §2 Progressive Disclosure — Full Assessment

The question from §2: "Is there a simple path / configurable path / low-level path?"

For `Knot` UV materials:

| Layer | Knot |
|-------|------|
| Simple (flat color) | `IsoColor.*` — unchanged, works |
| Simple (single texture) | `textured(bitmap)` — this slice enables it |
| Configurable (per-face DSL) | Not supported — documented as unsupported due to no named face taxonomy and depth-sort bug |
| Low-level (direct UV) | `UvGenerator.forKnotFace` — internal, not user-facing |

The absence of a configurable layer (per-face DSL) is *intentional and documented*, not
an oversight. `Knot` has no `KnotFace` enum and no `PerFace.Knot` variant. This is
a deliberate scope decision consistent with the experimental status of the shape. §2 is
satisfied for the two supported layers; the third layer is correctly excluded by design
rather than being a gap.

**§1 Hero Scenario:** `Shape(Knot(origin), textured(bitmap))` renders a textured Knot.
This is achievable in one call. §1 is satisfied.

**§3 Defaults:** No new defaults introduced. The `perFace {}` fallback to `PerFace.default`
is a default but it is a silent one (API-02 above).

**§6 Invalid States:** `sourcePrisms` is mutable-at-runtime (API-01). The `perFace {}`
combination is expressible and silent (API-02). Both are flagged.

**§10 Host Language:** Kotlin `List<T>` is the correct idiomatic read-only type for
`sourcePrisms`. The property is a `val`. The type is `List`, not `ArrayList` or
`MutableList`. §10 is satisfied at the declaration level; the mutation contract should
be documented (API-01).

**§12 Dogfooding:** The implement doc notes the test suite covers 11 unit cases including
delegation identity, bbox range, and the `sourcePrisms`-vs-`createPaths` regression guard.
The Paparazzi snapshot was deferred (consistent with sibling slices). The verify stage
will cover Canvas AC1/AC2 and WebGPU AC3 interactively. §12 is partially satisfied;
full satisfaction requires the verify stage to run.

---

## Recommendations

### Immediate Actions (HIGH)

1. **API-01:** Add one sentence to the `sourcePrisms` KDoc: "Callers must not cast this
   list to `MutableList` or modify its elements — doing so silently corrupts UV generation
   for this instance." This is a documentation-only change, zero binary diff. Optional
   stronger fix: wrap with `Collections.unmodifiableList(...)`.

### Short-term Improvements (MED)

2. **API-02:** Before public release, add a `Log.w` in `ShapeNode.renderTo` when
   `material is IsometricMaterial.PerFace && shape is Knot`. This surfaces the
   "perFace {} is a no-op on Knot" mistake at render time with an actionable message
   instead of silent wrong rendering.

3. **API-03:** Strengthen the KDoc opening sentence for `sourcePrisms` to front-load
   "pre-transform coordinate space" before its consumer note — prevents misuse by
   callers who use the property for geometric rather than UV purposes.

### Short-term Improvements (LOW)

4. **API-04:** Replace the dead `else -> throw` in `forKnotFace` with
   `else -> error("Unreachable: ...")` or remove the branch with a comment explaining the
   compiler requirement. Align the error message format with the sibling `forXFace` pattern.

5. **API-05:** Track the `RequiresOptIn.Level.WARNING` → `ERROR` upgrade as a pre-1.0 action
   item for the `ExperimentalIsometricApi` annotation itself.

### Long-term (NIT)

6. **API-06:** Add one sentence to `sourcePrisms` KDoc clarifying that faces 18–19 use
   post-transform bbox projection rather than `sourcePrisms` entries.

---

## API Contract Health Score

**Overall Score:** 8/10

**Breakdown:**
- Versioning: 10/10 — additive only, `@ExperimentalIsometricApi` correctly applied,
  `apiCheck` green
- Backwards Compatibility: 10/10 — no semantic breaks, all changes additive or
  internal
- Documentation: 7/10 — mutation contract absent (API-01), transform-space ambiguity
  (API-03), perFace fallback documented only on `Knot` not at the `Shape()` call site
  (API-02)
- Deprecation Management: 10/10 — no deprecation concerns, correct experimental
  annotation pattern with `@OptIn` propagation
- Progressive Disclosure: 8/10 — two of three layers implemented; third layer
  (per-face) is intentionally excluded and documented; the silent `perFace {}` fallback
  is the only disclosure gap

---

## Next Steps

1. Apply API-01 fix (KDoc sentence on `sourcePrisms`) as a one-line follow-up commit —
   zero binary diff, zero risk.
2. Track API-02 (`Log.w` for `perFace {}` on Knot) and API-03 (KDoc strengthening) for
   the verify stage or the next slice that exercises `Knot` in the sample app.
3. Track API-04 (dead `else` branch cleanup) alongside any future refactor of
   `UvGenerator.forKnotFace` — it is safe to do at any point.
4. Track API-05 (`WARNING` → `ERROR` upgrade) as a cross-cutting pre-1.0 checklist item.
5. Once `wf-verify uv-generation-knot` completes, confirm Canvas AC1/AC2 and WebGPU AC3
   results so §12 (Dogfooding) is fully satisfied.
