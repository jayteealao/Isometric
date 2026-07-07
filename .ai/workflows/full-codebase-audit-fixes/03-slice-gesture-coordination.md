---
schema: sdlc/v1
type: slice
slug: full-codebase-audit-fixes
slice-slug: gesture-coordination
status: complete
stage-number: 3
created-at: "2026-07-07T11:54:55Z"
updated-at: "2026-07-07T11:54:55Z"
revision-count: 1
complexity: l
depends-on: []
tags: [isometric-compose, gestures, pointer-input, instrumented-tests]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-core-math.md, 03-slice-compose-contracts.md, 03-slice-view-module.md, 03-slice-shape-geometry.md, 03-slice-docs-and-changelog.md, 03-slice-snapshot-sweep-gate.md]
  plan: 04-plan-gesture-coordination.md
  implement: 05-implement-gesture-coordination.md
---

# Slice: Gesture Coordination

## The Slice

Three reviewers independently found the same bug — a double-tap fires `onClick` twice
before `onDoubleClick` — and the root cause is structural: two sibling `pointerInput`
blocks in `IsometricScene.kt` each see every event and don't coordinate. This slice is the
one restructure in the sweep, and the PO explicitly authorized it: the hand-rolled loop and
the `detectTapGestures` block merge into one coordinated gesture handler. The shape's
freshness research says the platform already solved the hard part — Compose 1.5.0's
`detectTapGestures` with both `onTap` and `onDoubleTap` waits out the ~300ms
disambiguation window natively, so no hand-rolled timing is needed. The contract: a node
with only `onClick` fires instantly; a node with both fires `onClick` only after the window
expires; a double-tap fires `onDoubleClick` exactly once and never `onClick`.

Because one coordinated handler owns all pointer state, the restructure subsumes the two
smaller findings for free: consumption gating (C2 — an inert scene inside a scrollable must
let the parent scroll) and state reset (C3 — long-press → drag → release must not leave
stale `isDragging`/`draggedNode`). Slicing them apart would mean restructuring the same
code twice. F4 rides along too: the missing test that node selection suppresses camera
autopan is a gesture-coordination concern with nowhere better to live.

This is the riskiest slice — pointer-input regressions are precisely what JVM tests miss —
so it carries the sweep's instrumented gate: the androidTest suite runs on an emulator as
part of this slice's verify (AC-S3), not deferred to the end. The known trap from the
freshness research: never `forEachGesture` (event loss, issue 251260206); use
`awaitEachGesture` where a hand-rolled loop survives.

## Goal

One coordinated gesture handler in `IsometricScene.kt` delivering the decided tap contract
(C1), consumption only-when-acting (C2), and full state reset (C3); autopan-suppression
coverage added (F4); instrumented suite green on emulator.

## Why This Slice Exists

C1/C2/C3 share one root cause — uncoordinated sibling `pointerInput` blocks — so they are
one restructure, not three fixes. The shape's sequencing note ("C1 restructuring subsumes
C2 and C3") makes the boundary explicit.

## Scope

- **In:** `IsometricScene.kt` pointerInput blocks (~lines 303–575); new
  DoubleTapDisambiguationTest; consumption-flag/parent-scroll interop test; gesture-state
  reset test; autopan-suppression test in/alongside DragANodeTest; instrumented androidTest
  run on emulator (AC-S3 gate lives here per PO decision).
- **Out:** GestureEvents/AdvancedSceneConfig KDoc and annotations (→ `compose-contracts`);
  interactions.mdx / gestures.mdx corrections describing the new contract (→
  `docs-and-changelog`); IsometricView touch handling (→ `view-module`); no new gesture
  types (out of scope for the whole workflow).

## Acceptance Criteria

- Given a node with both `onClick` and `onDoubleClick`: single tap → exactly one `onClick`
  after the disambiguation window; double tap → exactly one `onDoubleClick`, zero `onClick`.
  Given a node with `onClick` only: tap → `onClick` with no added latency. JVM gesture
  tests (new DoubleTapDisambiguationTest) plus existing DragANodeTest /
  DragEventClarityTest / NodeCallbacksInteractionTest all pass. (AC-C1, JVM half)
  <!-- observable: false — callback counts and timing are fully assertable in JVM compose gesture tests injecting pointer events -->
- The tap contract holds under live pointer routing: the existing instrumented androidTest
  suite passes on an emulator with the restructured handler. (AC-C1 interactive half + AC-S3)
  <!-- observable: true — real tap/drag behavior on a running Android surface is what users experience; JVM injection is a proxy, the emulator run is the evidence -->
  verify: { method: instrumented androidTest (connectedDebugAndroidTest) driven via android-cli-managed emulator; lazylogcat if gesture debugging needed, env: AVD boot required (android-cli; no physical device), fixture: existing isometric-compose androidTest interaction suite, rung: android-3 (AVD instrumented) }
- Given a scene with no gesture handlers, no node-drag state, and no camera, When drag
  events arrive, Then no `PointerInputChange.consume()` is called; a parent-scroll interop
  test asserts the consumption flags. Edge cases: camera present but disabled; handler
  removed mid-gesture. (AC-C2)
  <!-- observable: false — consumption flags are directly assertable in a JVM pointer-input test -->
- Given long-press fires, then a drag, then release, Then internal gesture state
  (`isDragging`, `draggedNode`, `longPressFired`) is fully reset and the next gesture
  behaves as from idle. Edge cases: tap racing the long-press timeout inside the double-tap
  window; drag started within the window; rapid triple-tap (doubleClick then pending single
  evaluation); `onDoubleClick` added/removed between recompositions (pointerInput keying). (AC-C3)
  <!-- observable: false — state-machine reset is fully assertable in JVM gesture tests across scripted sequences -->
- A test pins that selecting a node suppresses camera autopan. (AC-F4)
  <!-- observable: false — coverage criterion, provable by the new JVM test failing when suppression is removed -->

## Dependencies on Other Slices

- None hard. `docs-and-changelog` depends on *this* slice (E1 describes the new contract).

## Risks

- **Biggest rewrite in the sweep:** the restructure replaces working (if miscoordinated)
  gesture code; the existing JVM gesture suites + the instrumented emulator run are the
  gate, and both must pass before the slice closes.
- **Recomposition keying:** `pointerInput` keys decide when the handler restarts; wrong
  keying re-introduces C3-style stale state or drops the double-tap window mid-gesture.
- **Emulator availability:** AVD boot via android-cli is assumed by the verify stub; if
  boot fails, climb the ladder (Robolectric covers the state machine) and defer only the
  live-routing residual with the boot failure recorded.
