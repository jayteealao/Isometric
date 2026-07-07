---
schema: sdlc/v1
type: slice
slug: full-codebase-audit-fixes
slice-slug: path-caching-test-fix
status: complete
stage-number: 3
created-at: "2026-07-07T19:48:19Z"
updated-at: "2026-07-07T19:48:19Z"
complexity: xs
depends-on: []
source: extension
source-ref: "user description: IsometricRendererPathCachingTest reflection failure after cachedPaths moved to SceneCache"
extension-round: 1
tags: [tests, isometric-compose, path-caching, androidTest]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  source: ""
  plan: 04-plan-path-caching-test-fix.md
  implement: 05-implement-path-caching-test-fix.md
---

# Slice: path-caching-test-fix

## Goal

Repair the two failing instrumented tests in
`isometric-compose/src/androidTest/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricRendererPathCachingTest.kt`
that throw `NoSuchFieldException: No field cachedPaths in class IsometricRenderer`, without
altering the production public API of `IsometricRenderer` or `SceneCache`.

## Why This Slice Exists

A refactor moved the `cachedPaths` field off `IsometricRenderer` and onto the internal
`SceneCache` collaborator ([SceneCache.kt:46](../../../isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/SceneCache.kt)).
The renderer now holds the cache through a **private** field
(`private val cache = SceneCache(...)`, [IsometricRenderer.kt:82](../../../isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricRenderer.kt))
and exposes only `internal val currentPreparedScene` as a read-through proxy
([IsometricRenderer.kt:86](../../../isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricRenderer.kt)).

The test's `cachedPathCount()` helper (lines 38–43) still reflects for `cachedPaths` directly on
`IsometricRenderer`, so it fails at runtime. Two tests call that helper and therefore fail:
`rebuildCache_buildsCachedPathsOnlyWhenEnabled` and `invalidate_clearsCachedPaths`. The third
test (`pathCaching_preservesPreparedSceneAndHitTestSemantics`) does not use the helper and is
unaffected.

This is net-new **test-maintenance** scope: the tests already exist and assert real behavior, but
the reflection seam they depend on no longer exists. It surfaced after the seven audit slices
landed on the shared branch and is not a regression introduced by any one of them — it is fallout
from the pre-existing cache extraction. It gets its own slice rather than an in-place edit to a
completed slice, per extension discipline.

## Scope

- **In:**
  - Replace the reflection-based `cachedPathCount()` helper so it reads the cached-path count
    through a stable, non-reflective seam.
  - Add an `internal`, `@VisibleForTesting` cached-path-count (or cached-path-list) accessor on
    `IsometricRenderer` that delegates to `cache.cachedPaths` — e.g.
    `@VisibleForTesting internal val cachedPathCountForTest: Int get() = cache.cachedPaths?.size ?: 0`.
    `internal` + `@VisibleForTesting` keeps the **public** API surface unchanged (the
    binary-compatibility-validator `.api` dump tracks public/protected members only).
  - Confirm the two repaired tests pass and the third still passes.
- **Out:**
  - Any change to the production **public** API of `IsometricRenderer` or `SceneCache`
    (hard constraint from the request).
  - Behavioral changes to path caching, scene preparation, or hit-testing.
  - Any edit to the six content slices or the sweep-gate slice already completed.
  - The `currentPreparedScene`-as-proxy approach (option (a)) — rejected: `currentPreparedScene`
    is populated regardless of `enablePathCaching`, so it cannot distinguish the
    enabled-vs-disabled and post-`clearCache()` states these tests assert, which would make both
    tests vacuous. See Risks.

## Acceptance Criteria

- **AC-T1 (tests pass):** Given the repaired test file, When
  `./gradlew :isometric-compose:connectedDebugAndroidTest` runs on a connected device/emulator,
  Then `rebuildCache_buildsCachedPathsOnlyWhenEnabled` and `invalidate_clearsCachedPaths` pass,
  and `pathCaching_preservesPreparedSceneAndHitTestSemantics` still passes.
- **AC-T2 (no reflection into moved internals):** Given the repaired test, Then it no longer calls
  `getDeclaredField("cachedPaths")` on `IsometricRenderer` (nor reflects into the private `cache`
  field); it reads the count through the new `@VisibleForTesting` accessor.
- **AC-T3 (semantics preserved):** Given path caching disabled (or after `clearCache()`), Then the
  accessor reports `0`; Given path caching enabled after `rebuildCache`, Then it reports one entry
  per prepared command — i.e. the enabled/disabled distinction the original test asserted is still
  exercised, not made vacuous.
- **AC-T4 (public API unchanged):** Given the change, When `./gradlew :isometric-compose:apiCheck`
  runs, Then it passes with no diff to `isometric-compose.api` (the new member is `internal`, so it
  is absent from the public dump).

## Dependencies on Other Slices

- None (`depends-on: []`). All seven content slices are `complete`; this slice touches only the
  `androidTest` source set plus one test-only `internal` accessor on `IsometricRenderer`. It can be
  planned and implemented independently, and is safe to run after handoff of the main slices.

## Risks

- **Vacuous-test trap (primary):** the request's option (a) — proxy through `currentPreparedScene`
  — silently defeats the tests' purpose because that field is populated whether or not path caching
  is enabled. The AC deliberately mandates option (b) to keep AC-T3's enabled/disabled distinction
  meaningful. A plan/implement that reaches for the proxy anyway would pass CI while testing
  nothing.
- **Runtime-evidence availability:** AC-T1 requires a connected device or booted emulator
  (`connectedDebugAndroidTest`). If none is available in the working environment, AC-T1 becomes a
  runtime-evidence deferral at verify (cleared by a later on-device run), while AC-T2/T4 remain
  fully checkable statically (source inspection + `apiCheck`) and AC-T3 is provable by the JVM-level
  reasoning above plus the accessor's delegation to `cache.cachedPaths`.
- **`@VisibleForTesting` import:** confirm the annotation source at plan time
  (`androidx.annotation.VisibleForTesting` vs `com.google.common.annotations` — this module already
  depends on `androidx.annotation`), a trivial but real detail so the accessor compiles in `main`.

---
