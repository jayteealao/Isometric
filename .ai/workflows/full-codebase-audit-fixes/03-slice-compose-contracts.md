---
schema: sdlc/v1
type: slice
slug: full-codebase-audit-fixes
slice-slug: compose-contracts
status: defined
stage-number: 3
created-at: "2026-07-07T11:54:55Z"
updated-at: "2026-07-07T11:54:55Z"
revision-count: 1
complexity: m
depends-on: []
tags: [isometric-compose, group-alpha, stability-annotations, kdoc, api-dump]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-core-math.md, 03-slice-gesture-coordination.md, 03-slice-view-module.md, 03-slice-shape-geometry.md, 03-slice-docs-and-changelog.md, 03-slice-snapshot-sweep-gate.md]
  plan: 04-plan-compose-contracts.md
  implement: 05-implement-compose-contracts.md
---

# Slice: Compose Contracts

## The Slice

The Compose module's remaining findings are all contract honesty, and one of them requires
actually building something. `IsometricNode.alpha` KDoc promises unconditional render-time
application while the composables reference says Group doesn't accept alpha — two documents,
two different lies, and the PO resolved it by making the capability real: GroupNode learns
to multiply `alpha` down through its children's render context, and the `Group` composable
exposes the parameter (additive API, apiDump regenerated). The semantics questions the shape
flagged get decided at plan time and written down: nested Groups multiply (never overwrite),
and alpha=0 gets an explicit documented behavior.

The rest is truth-telling in annotations and KDoc. `AdvancedSceneConfig` is a `@Stable`
subclass of an `@Immutable` base while holding a mutable engine and callbacks — neither
annotation is currently honest, so the actual mutability gets verified first and the
annotations follow reality (E4), with an empirical apiDump check since the dumps carry
`$stable` synthetic fields whose sensitivity to annotation swaps is undocumented. G3 adds
the missing sentence: callback-only config changes deliberately don't re-register (the
equals() exclusion), and what callers should do instead. F3 rides along because it lives in
the same territory — the alpha-scaling render test currently asserts only a direction
(`< 255`) and gets pinned to the exact scaled value, which matters now that Group alpha
composition is real behavior worth guarding.

## Goal

Group alpha is a real, tested, documented capability (G1); stability annotations match
actual mutability with dumps regenerated (E4); the callback re-registration semantics are
documented (G3); the alpha render test asserts exact values (F3).

## Why This Slice Exists

These four items share one module and one theme — making the Compose API's stated contracts
true — and G1's apiDump churn plus E4's possible dump churn belong in the same commit
neighborhood so API diffs stay attributable.

## Scope

- **In:** `IsometricNode.kt` (GroupNode.renderTo alpha propagation + KDoc),
  `IsometricComposables.kt` (Group signature), RenderContext (alpha propagation channel),
  `AdvancedSceneConfig.kt` (annotation honesty + callback-semantics KDoc),
  `IsometricNodeRenderTest.kt` (F3 exact-value assertion + new GroupNode alpha tests),
  `./gradlew apiDump` for isometric-compose.
- **Out:** the composables.mdx reference-table update for Group alpha (→
  `docs-and-changelog`); any golden that ends up exercising group alpha (→
  `snapshot-sweep-gate`); gesture code (→ `gesture-coordination`).

## Acceptance Criteria

- Given a Group with `alpha = 0.5` containing children, When the scene renders, Then
  descendant render output carries the multiplied alpha; nested Groups multiply (not
  overwrite); Group alpha composes with child alpha; alpha = 0 behavior is decided,
  implemented, and documented. Unit tests on GroupNode.renderTo cover all three. (AC-G1 behavior)
  <!-- observable: false — render-output alpha values are directly assertable in JVM render tests; the visual confirmation, if any golden exercises it, is owned by the sweep-gate slice -->
- The `Group` composable exposes `alpha`; `./gradlew apiDump` regenerated and `apiCheck`
  passes; the dump diff shows only the intended additive change. (AC-G1 API)
  <!-- observable: false — apiCheck is a deterministic gradle gate; dump diff reviewed in the review stage -->
- `IsometricNode.alpha` KDoc and the Group composable KDoc state the (now true) contract,
  including nested-multiplication and alpha=0 semantics. (AC-G1 docs half)
  <!-- observable: true — consumer-facing prose; accuracy is human-judged -->
  verify: { method: human read-through at review stage against the implemented behavior, env: source KDoc, fixture: n/a, rung: manual-review (residual — prose judgment) }
- Stability annotations on `AdvancedSceneConfig` (and `SceneConfig` if implicated) verified
  against actual mutability and corrected; `./gradlew apiCheck` passes with dumps
  regenerated if the swap changed them (empirical check — budget for it). (AC-E4)
  <!-- observable: false — annotation state + apiCheck are statically/automatedly provable; recomposition-behavior implications are compile-time contracts -->
- `AdvancedSceneConfig` KDoc documents that callback-only changes don't re-register
  (deliberate equals() exclusion) and names the caller's alternative. (AC-G3)
  <!-- observable: true — consumer-facing prose; accuracy is human-judged -->
  verify: { method: human read-through at review stage against GestureConfig/AdvancedSceneConfig equals() behavior, env: source KDoc, fixture: n/a, rung: manual-review (residual — prose judgment) }
- The alpha scaling test asserts the exact expected alpha value, not just `< 255`. (AC-F3)
  <!-- observable: false — exact-value JVM render assertion -->

## Dependencies on Other Slices

- None hard. `docs-and-changelog` picks up the composables.mdx table row after this lands;
  `snapshot-sweep-gate` attributes any golden churn from G1.

## Risks

- **G1 is quietly a feature, not a fix:** alpha propagation needs a channel through
  RenderContext that may not exist yet; if the plumbing grows beyond the module boundary,
  plan-stage should flag scope, not absorb it silently.
- **E4 dump sensitivity is unknown:** the `$stable` synthetic fields may or may not move
  when annotations swap — the empirical apiDump check is budgeted, and any diff must be
  deliberate, not rubber-stamped.
- **alpha=0 semantics** (skip children vs render fully transparent) is a behavioral choice
  with perf implications; it must be decided once, at plan, and documented — not left to
  fall out of the implementation.
