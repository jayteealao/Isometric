---
schema: sdlc/v1
type: implement
slug: full-codebase-audit-fixes
slice-slug: view-module
status: complete
stage-number: 5
created-at: "2026-07-07T15:55:11Z"
updated-at: "2026-07-07T15:55:11Z"
metric-files-changed: 1
metric-lines-added: 2
metric-lines-removed: 2
metric-deviations-from-plan: 0
metric-review-fixes-applied: 0
commit-sha: ""
tags: [isometric-android-view, samples, paint-reuse, touch-handling, kdoc]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-view-module.md
  plan: 04-plan-view-module.md
  siblings:
    - 05-implement-core-math.md
    - 05-implement-gesture-coordination.md
    - 05-implement-compose-contracts.md
  verify: 06-verify-view-module.md
next-command: wf-verify
next-invocation: "/wf verify full-codebase-audit-fixes view-module"
---

# Implement: View Module & Samples

## The Implementation

Three of the four findings in this slice — Paint reuse (D1), lazy scene rebuild on option
setters (D3), and the ACTION_UP return value fix (G2) — were already delivered by commit
38c77e1 along with 16 Robolectric regression tests. This implement pass adds the one
remaining change: a KDoc correction in `DragLifecycleSample`.

The erroneous clause described `x`/`y` as "the drag-start position, captured in onDragStart"
— which is accurate only for the `onDragStart` callback. In `onDrag`, `x`/`y` is the live
(current) pointer position that moves with the pointer. The fix mirrors the authoritative
definition in `GestureEvents.kt` lines 34–39, which states both semantics in one place.
Two lines were replaced; the surrounding prose about `delta` and the hero use case is
preserved and adjusted to reference `onDrag` explicitly.

All 16 Robolectric tests passed on this run (0 failures, 0 skipped), confirming no
regression from the prior commit's D1/D3/G2 work. AC-D2 (the KDoc correction) defers its
final prose accuracy check to the review stage as planned.

## Summary of Changes

- Fixed `DragLifecycleSample` KDoc in `InteractionSamplesActivity.kt` (D2): clarified that
  `x`/`y` is the drag-start position when delivered to `onDragStart`, and the live pointer
  position when delivered to `onDrag`. Previously only the `onDragStart` semantic was stated.
- Confirmed (code-review) that D1, D3, G2 are all correctly implemented per prior commit.
- Confirmed 16 Robolectric unit tests pass: 0 failures, 0 skipped.

## Files Changed

- `app/src/main/kotlin/io/github/jayteealao/isometric/sample/InteractionSamplesActivity.kt`:
  KDoc of `DragLifecycleSample` (lines 612–616) corrected to match `GestureEvents.kt`
  semantics for `x`/`y` across both `onDragStart` and `onDrag` callbacks.

## Shared Files (also touched by sibling slices)

None. The `app` module is not touched by any other slice.

## Notes on Design Choices

- **KDoc phrasing follows GestureEvents.kt verbatim intent:** the new text mirrors the
  structure of `DragEvent`'s class KDoc ("the drag-start position when delivered to
  `onDragStart`, and the live pointer position when delivered to `onDrag`") to keep the
  two descriptions in sync. A reader who reads the class KDoc and then the sample will
  find matching language.
- **Hero-use-case sentence updated:** the final sentence previously anchored to "reading the
  absolute start from `x`/`y`" — implying `onDragStart` semantics. Updated to "reading the
  live position from `x`/`y` in `onDrag`" which is the primary runtime consumer of that field.
- **D1/D3/G2 code-review gates confirmed:**
  - AC-D1: `val androidPath = Path()` and `val paint = Paint(Paint.ANTI_ALIAS_FLAG)` appear
    exactly once (lines 36–37), before the `for` loop. `androidPath.reset()` at line 41
    (top of loop). All three `paint.*` fields set explicitly in every `when` branch. Zero
    allocations inside the render loop.
  - AC-D3: `setSort`, `setCull`, `setBoundsCheck` (and `setStrokeStyle`, `add`, `clear`)
    all set `sceneDirty = true; invalidate()`. `onDraw` at line 189 reprojects exactly once
    under `if (sceneDirty || cachedScene == null)` then clears the flag at line 195. Lazy
    strategy confirmed.
  - AC-G2: `onTouchEvent` returns `true` at line 229 when `listener != null` and
    `ACTION_UP` is received.

## Verification Seams Built

- AC-D1 → `ac8_renderIsIdenticalOnConsecutiveDrawsAfterM5Reuse` in `IsometricViewLifecycleTest`
  (built by 38c77e1). Code-review gate is the primary acceptance mechanism; proxy test covers
  the surrounding scene-identity invariant.
- AC-D3 → `CountingSceneProjector` seam + `ac7_setSort_*`, `ac7_setCull_*`,
  `ac7_setBoundsCheck_*`, `ac7_multipleAddCalls*` tests (6 tests, built by 38c77e1).
- AC-G2 → `ac9_onTouchEventReturnsTrueWhenListenerRegistered`,
  `ac9_onTouchEventReturnsFalseWhenNoListenerRegistered` (built by 38c77e1).
- AC-D2 → D2 KDoc edit applied in this pass. No automated seam; prose accuracy is
  human-judged at review stage (per slice definition, `verify: { rung: manual-review }`).

## Visual Contract Honored

Not applicable — `02c-craft.md` was not present for this workflow.

## Deviations from Plan

None. Plan step 1 (D2 KDoc fix) applied exactly. Steps 2–4 (test run, code-review D1,
code-review D3/G2) completed with results matching plan expectations. Step 5 (manual D2
read-through) is deferred to review stage as designed.

## Anything Deferred

- AC-D2 prose accuracy: manual read-through at review stage — cross-check edited KDoc
  against `GestureEvents.kt` lines 34–44. Prose judgment is inherently human; no automated
  fallback planned or needed per slice definition.
- AC-D1 true allocation counting (StrictMode profiling / Debug.startAllocCounting): not
  required. Code-review gate is the accepted ceiling; the proxy test covers the invariant.
  Ceiling and upgrade path noted in the plan's Risks/Watchouts section.

## Known Risks / Caveats

- The D3 lazy rebuild strategy means callers who batch-set multiple options before the next
  draw get a single re-projection — correct behavior, but any caller expecting synchronous
  projection after each setter call (e.g., reading `cachedScene` immediately after `setSort`)
  would observe stale state until `onDraw` fires. This is documented in the class-level KDoc
  of `IsometricView` ("marks the scene dirty and calls invalidate; the re-projection occurs
  on the next onDraw").

## Freshness Research

No external dependency freshness check was required for this slice. The plan's freshness
research (Robolectric allocation-assertion patterns, Android StrictMode profiling) was
conducted at plan time and confirmed that code-review is the idiomatic gate for AC-D1.
No new external APIs, SDK methods, or library functions were introduced.

## Recommended Next Stage

- **Option A (default):** `/wf verify full-codebase-audit-fixes view-module` — run
  `./gradlew :isometric-android-view:test` (16/16 confirmed in this pass), plus code-review
  walkthrough of the KDoc edit vs GestureEvents.kt for AC-D2. Verify stage can immediately
  produce a result with no new environment needs.
- **Option B:** `/wf review full-codebase-audit-fixes view-module` — skip verify since the
  only remaining AC (D2) is a manual prose check that verify would defer to review anyway.
