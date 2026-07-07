---
schema: sdlc/v1
type: slice
slug: full-codebase-audit-fixes
slice-slug: view-module
status: defined
stage-number: 3
created-at: "2026-07-07T11:54:55Z"
updated-at: "2026-07-07T11:54:55Z"
revision-count: 1
complexity: s
depends-on: []
tags: [isometric-android-view, samples, paint-reuse, touch-handling]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-core-math.md, 03-slice-gesture-coordination.md, 03-slice-compose-contracts.md, 03-slice-shape-geometry.md, 03-slice-docs-and-changelog.md, 03-slice-snapshot-sweep-gate.md]
  plan: 04-plan-view-module.md
  implement: 05-implement-view-module.md
---

# Slice: View Module & Samples

## The Slice

The View wrapper accumulated the small stuff: it allocates a fresh `Paint` per
RenderCommand per frame while the Compose renderer caches one, its option setters
(`setSort`/`setCull`/`setBoundsCheck`) invalidate without rebuilding the cached scene so
the next draw renders stale options, and `onTouchEvent` returns false for a handled
ACTION_UP. None of these is individually dramatic — that is exactly why they get one shared
slice with three small, separately-committable fixes rather than three workflow cycles.

The fourth item is prose: the DragLifecycleSample KDoc misstates `DragEvent.x/y` semantics,
the same error class a docs commit (278ea1a) already fixed elsewhere. Per the hybrid docs
decision, sample KDoc travels with the module it documents, so it lands here — the .mdx
guides stay in the docs slice. The Paint fix carries the sweep's one non-functional
requirement worth restating: no new allocations per frame in either renderer, which is the
whole point of D1.

## Goal

The View renderer reuses a single Paint (D1); option setters take effect on the next draw
(D3); ACTION_UP reports handled (G2); the drag sample's KDoc matches GestureEvents.kt
semantics (D2).

## Why This Slice Exists

Zone D plus the one View-side judgment call (G2) form a coherent, independently verifiable
unit in a module no other slice touches — cheap to land any time, with zero coupling to the
riskier Compose work.

## Scope

- **In:** `AndroidCanvasRenderer.kt` (single reused Paint, mutated per command),
  `IsometricView.kt` (setters rebuild `cachedScene`; ACTION_UP returns true when handled),
  `app/.../InteractionSamplesActivity.kt` (DragLifecycleSample KDoc), view-module unit
  tests for the setter rebuild and touch return.
- **Out:** Compose renderer (already caches); gesture semantics themselves (→
  `gesture-coordination`); site docs (→ `docs-and-changelog`).

## Acceptance Criteria

- AndroidCanvasRenderer allocates zero Paint objects per draw call — a single reused
  instance is mutated per command; existing render tests pass. (AC-D1)
  <!-- observable: false — allocation behavior is code-review verifiable plus existing render tests; no live runtime needed -->
- After `setSort`/`setCull`/`setBoundsCheck` on IsometricView, the next draw reflects the
  new option — `cachedScene` is rebuilt; a unit test toggles an option and asserts the
  rebuilt output. (AC-D3)
  <!-- observable: false — cache rebuild is assertable in a JVM/unit test on the view's draw path -->
- `IsometricView.onTouchEvent` returns true for handled ACTION_UP; existing touch tests
  pass. (AC-G2)
  <!-- observable: false — return-value assertion in a unit test -->
- DragLifecycleSample KDoc/comments state `DragEvent.x/y` as absolute position and delta as
  per-event movement, matching GestureEvents.kt. (AC-D2)
  <!-- observable: true — sample prose read by consumers; accuracy is human-judged against GestureEvents.kt -->
  verify: { method: human read-through at review stage cross-checked against GestureEvents.kt KDoc, env: source KDoc in app module, fixture: n/a, rung: manual-review (residual — prose judgment) }

## Dependencies on Other Slices

- None.

## Risks

- **D3's rebuild cost:** rebuilding `cachedScene` on every setter call is correct but could
  surprise callers who batch-set options; plan should confirm the rebuild is lazy (on next
  draw) rather than eager per setter — the AC is written to allow either as long as the
  next draw is correct.
- **G2's behavior shift** is deliberately cosmetic under the touch-target model, but any
  parent view relying on the false return would see a change — accepted per the PO's
  judgment-call decision.
