---
schema: sdlc/v1
type: implement
slug: full-codebase-audit-fixes
slice-slug: path-caching-test-fix
status: complete
stage-number: 5
created-at: "2026-07-07T22:07:54Z"
updated-at: "2026-07-07T22:07:54Z"
metric-files-changed: 3
metric-lines-added: 17
metric-lines-removed: 5
metric-deviations-from-plan: 0
metric-review-fixes-applied: 0
commit-sha: ""
tags: [tests, isometric-compose, path-caching, androidTest]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-path-caching-test-fix.md
  plan: 04-plan-path-caching-test-fix.md
  siblings: [05-implement-core-math.md, 05-implement-gesture-coordination.md, 05-implement-compose-contracts.md, 05-implement-view-module.md, 05-implement-shape-geometry.md, 05-implement-docs-and-changelog.md, 05-implement-snapshot-sweep-gate.md]
  verify: 06-verify-path-caching-test-fix.md
next-command: wf-verify
next-invocation: "/wf verify full-codebase-audit-fixes path-caching-test-fix"
---

# Implement: path-caching-test-fix

## The Implementation

Two instrumented tests were throwing `NoSuchFieldException: No field cachedPaths in class IsometricRenderer`
because the field had migrated to the internal `SceneCache` collaborator. The fix is three focused
edits: a two-line accessor on `IsometricRenderer` that delegates to `cache.cachedPaths`, a one-line
replacement for the defunct reflection block in the test, and a `testOptions.managedDevices` block
in `build.gradle.kts` that gives the test suite a headless execution path through AGP's built-in
Gradle Managed Devices.

The accessor follows the existing `currentPreparedScene` convention exactly — `internal val` with an
expression getter, a KDoc line noting test-only visibility, no new import, no new dependency. Because
it is `internal`, it is absent from the public `.api` dump: `apiCheck` passes with no diff. The test
helper collapses from five lines of reflection boilerplate to a single delegating call, and the two
calling tests remain structurally unchanged — they still exercise the enabled/disabled and
post-`clearCache()` distinctions that make the tests meaningful.

The managed-device block (`pixel2Api30`, `aosp-atd`, API 30) retires the environment wall rather
than deferring it a second time. It costs nothing when not invoked, and it doubles as the clearing
path for the sibling `gesture-coordination` AC-S3 runtime-evidence deferral when run in a
hardware-acceleration-capable environment.

## Summary of Changes

- Added `internal val cachedPathCountForTest: Int` to `IsometricRenderer.kt` after `currentPreparedScene` — delegates to `cache.cachedPaths?.size ?: 0`; KDoc marks it as testing-only.
- Replaced the five-line reflection block in `IsometricRendererPathCachingTest.kt` with a one-line call: `renderer.cachedPathCountForTest`.
- Added `testOptions.managedDevices.localDevices` block to `isometric-compose/build.gradle.kts` — `pixel2Api30` device, `aosp-atd` system image, API 30.

## Files Changed

- `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricRenderer.kt` — added 4 lines (accessor + KDoc) after line 86
- `isometric-compose/src/androidTest/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricRendererPathCachingTest.kt` — replaced 5-line reflection block with 1-line delegating call (−4 net)
- `isometric-compose/build.gradle.kts` — added 11-line `testOptions.managedDevices` block

## Shared Files (also touched by sibling slices)

- `isometric-compose/build.gradle.kts` — snapshot-sweep-gate slice did not touch this file; no conflict.

## Notes on Design Choices

- **No `@VisibleForTesting` annotation** — per plan Assumptions section: this repo has no existing uses of `@VisibleForTesting`; the `internal` keyword plus KDoc is the established convention (`currentPreparedScene` and `rebuildCache` use the same pattern). Avoided pulling `androidx.annotation` into the compose module for a purely decorative marker.
- **`internal val` not `fun`** — matches `currentPreparedScene` (a property, not a method), keeps the seam minimal.
- **`aosp-atd` API 30** — cross-arch ATD image, no Google Play services required, above `minSdk 24`. The plan confirmed the DSL and task-name derivation for AGP 8.2.2.

## Verification Seams Built

- AC-T1 (both tests pass on a device) → `testOptions.managedDevices.localDevices { create("pixel2Api30") { ... } }` block at `isometric-compose/build.gradle.kts` (enables `./gradlew :isometric-compose:pixel2Api30DebugAndroidTest` to drive the AC headlessly)
- AC-T2 (no reflection into moved internals) → `renderer.cachedPathCountForTest` call at `IsometricRendererPathCachingTest.kt:38` (static: `compileDebugAndroidTestKotlin` confirms the accessor is reachable and the reflection is gone)
- AC-T3 (enabled/disabled distinction preserved) → no additional seam; rides AC-T1's harness; logic provable by delegation to `cache.cachedPaths` (null when caching disabled or after `clearCache()`)
- AC-T4 (public API unchanged) → `apiCheck` gate run (BUILD SUCCESSFUL, no diff to `isometric-compose.api`)

## Deviations from Plan

- None. All three steps implemented exactly as specified in the plan.

## Anything Deferred

- **AC-T1 device run (runtime evidence):** the managed-device harness is built and landed, but the ATD image download and KVM/hardware-acceleration requirement cannot be satisfied in this environment. Falls back to the plan's fallback chain: manual `connectedDebugAndroidTest` on an attached device/emulator, then a pre-registered deferral cleared by the first successful device run.
  Constraint-resolution: prerequisite-harness landed in-slice (plan, AC-T1 row). Same environment wall as `gesture-coordination` AC-S3; clearing event: first successful `pixel2Api30DebugAndroidTest` or `connectedDebugAndroidTest` run in a capable environment.

## Known Risks / Caveats

- **Managed-device first-run download:** the `aosp-atd` API 30 image must be downloaded on first use; needs KVM/HW acceleration for real-device-speed emulation, or `-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect` for software rendering in CI.

## Freshness Research

- None needed beyond what the plan captured (AGP 8.2.2 Kotlin DSL, `aosp-atd` image, task name derivation all confirmed at plan time).

## Recommended Next Stage

- **Option A (default):** `/wf verify full-codebase-audit-fixes path-caching-test-fix` — AC-T2 and AC-T4 are fully satisfiable statically (compile gate + apiCheck both pass); AC-T1 and AC-T3 carry a runtime-evidence deferral for the device run.
- **Option B:** `/wf review full-codebase-audit-fixes path-caching-test-fix` — skip to review; justified if the static evidence (compile + apiCheck) and the delegation logic are sufficient for the reviewer's confidence bar.
