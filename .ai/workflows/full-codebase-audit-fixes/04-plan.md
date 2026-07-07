---
schema: sdlc/v1
type: plan-index
slug: full-codebase-audit-fixes
status: complete
stage-number: 4
created-at: "2026-07-07T12:46:38Z"
updated-at: "2026-07-07T12:46:38Z"
planning-mode: all
slices-planned: 7
slices-total: 7
implementation-order: [core-math, gesture-coordination, compose-contracts, view-module, shape-geometry, docs-and-changelog, snapshot-sweep-gate]
conflicts-found: 1
tags: [audit-findings, isometric-core, isometric-compose, isometric-android-view, gestures, docs, tests]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
next-command: wf-implement
next-invocation: "/wf implement full-codebase-audit-fixes core-math"
---

# Plan Index

All seven slices planned in one parallel pass (2026-07-07), each against the CURRENT
branch state — which mattered more than expected: the branch moved substantially since the
2026-07-06 audit. Of the 28 audit items, **8 are already fixed** by the fresh-eyes-review
commit series (eea28bb, 187e8f4, 38c77e1) — A2, A5-KDoc, A6, D1, D3, G2, E4, plus partial
E3 — and one is **partially fixed in a dangerous way** (A7: the new
`x * 1_000_003 xor y` hashCode still returns 0 for ORIGIN, the exact case the AC forbids).
Every plan classified its findings against HEAD before planning, so no slice schedules
work that is already done; the view-module slice, notably, reduced to a three-line KDoc
edit plus review-verification of landed fixes.

Five plan-time decisions were resolved with the PO at discovery (po-answers.md Stage 4):
alpha=0 skips children; long-press stays hand-rolled at the configurable timeout; the
double-tap harness is state-machine JVM + instrumented; drift mitigation is a pre-approved
`maxPercentDifference = 0.5f` fallback; and compose-contracts adopts the four orphaned
working-tree files. No PO-DECISION-PENDING items remain in any plan.

## Slice Plan Summaries

### `core-math` — [04-plan-core-math.md](04-plan-core-math.md)
- Files to touch: 12 (Point.kt, IntersectionUtils.kt, IsoColor.kt, TileCoordinate.kt + 6 test files; Vector.kt/IsometricProjection.kt verify-only)
- Strategy: classify-then-fix — A1a flip + KDoc, A3 helper-extraction-before-public-fix, A4 `coerceIn`, A7 `Objects.hash`, F1/F2 assertion conversions; A2/A5/A6 already fixed (residual = constructive-proof pins). Red-before-green discipline on every behavioral fix.
- Key risk: A7 looks fixed but is not — ORIGIN still hashes to 0; the new `ORIGIN != 0` test is the only gate.

### `gesture-coordination` — [04-plan-gesture-coordination.md](04-plan-gesture-coordination.md)
- Files to touch: 5 (IsometricScene.kt restructure + 4 test files, 3 of them new)
- Strategy: merge the two sibling pointerInput blocks into one `awaitEachGesture` handler; `detectTapGestures` natively delivers the C1 disambiguation (verified against 1.5.0 source); C2 = three-condition consumption guard; C3 = `!longPressFired` guard; long-press coroutine stays hand-rolled (PO). Instrumented emulator suite (androidTest source set already exists) is this slice's AC-S3 gate.
- Key risk: consuming Press/Release anywhere in the merged block starves `detectTapGestures`' internal `awaitFirstDown(requireUnconsumed=true)` — the consume-on-Move-only invariant must survive the merge exactly.

### `compose-contracts` — [04-plan-compose-contracts.md](04-plan-compose-contracts.md)
- Files to touch: 6 (RenderContext.kt, IsometricNode.kt, IsometricComposables.kt, AdvancedSceneConfig.kt, IsometricNodeRenderTest.kt, isometric-compose.api)
- Strategy: G1 is the real feature — `RenderContext.accumulatedAlpha` mirroring the `accumulatedScale` pattern, GroupNode multiplies down, leaves compose with their own alpha; alpha=0 early-returns (PO). E4 already landed in 187e8f4. G3 KDoc + F3 exact-value pin. Also adopts + commits the four orphaned working-tree files BEFORE the G1 work so the apiDump diff attributes cleanly.
- Key risk: `accumulatedAlpha` must be a `val` constructor field or `RenderContext`'s `@Immutable` contract breaks.

### `view-module` — [04-plan-view-module.md](04-plan-view-module.md)
- Files to touch: 4 (1 code edit: InteractionSamplesActivity.kt KDoc; 3 review/run-only)
- Strategy: D1/D3/G2 already fixed by 38c77e1 with 16 Robolectric tests; residual = the D2 KDoc correction (x/y is the LIVE pointer position in onDrag) + code-review verification of the landed fixes.
- Key risk: none material — AC-D1's allocation claim is code-review-gated (Robolectric cannot count allocations), accepted per the AC's own observable:false annotation.

### `shape-geometry` — [04-plan-shape-geometry.md](04-plan-shape-geometry.md)
- Files to touch: 6 (Octahedron.kt, Knot.kt, Cylinder.kt + 3 new test classes)
- Strategy: B1 = delete the erroneous `sqrt(2)/2` post-scale (pre-scale vertices are already unit-cube-correct); B2 = delete the hardcoded cosmetic translate; B3 = private companion `create()` validates before Circle construction (API dump unchanged). Visual ACs carry proxy+deferral to the sweep gate.
- Key risk: the known-red-goldens window — 5 attributed snapshot/screenshot tests go red from landing until the sweep-gate re-record; test runs in that window scope to `:isometric-core:test`.

### `docs-and-changelog` — [04-plan-docs-and-changelog.md](04-plan-docs-and-changelog.md)
- Files to touch: 11 (5 .mdx pages + CHANGELOG.md + 5 generated mirrors)
- Strategy: Step 1 reads the LANDED dependency diffs before writing any prose; the three missing WS10 APIs are named (NodeDragState family, per-node onDoubleClick, GestureConfig.longPressTimeoutMs); Migration subsection covers A1/B1/B2/C1; one sync-docs.js run at the end; G4 checks the uncommitted DragEvent note against the ABI descriptor change.
- Key risk: docs describing intent instead of landed reality — mitigated structurally by Step 1.

### `snapshot-sweep-gate` — [04-plan-snapshot-sweep-gate.md](04-plan-snapshot-sweep-gate.md)
- Files to touch: 9 (29 goldens + 16 doc screenshots + conditionally build.gradle.kts)
- Strategy: discard the stale untracked goldens, one `recordPaparazziDebug` pass, attribution ledger (expected-change set: octahedron, knot, sampleThree, conditionally alphaSampleScene; other 25 must be byte-identical), doc-screenshot regen, snapshot commit, final `test apiCheck`, CI verify follow-through with the pre-approved 0.5% drift fallback.
- Key risk: cross-platform pixel drift (Windows record vs Linux CI verify) — local green does not close the gate; CI does.

## Cross-Cutting Concerns

- **Branch reality vs audit:** 8 of 28 findings already fixed, 2 partially — every implement
  stage MUST trust its plan's `## Current State` classification, not the audit text.
- **Constructive-proof rule everywhere:** each new/strengthened test is demonstrated RED
  against pre-fix code (via stash/temporary revert) before the fix lands.
- **Uncommitted working-tree ownership (fully assigned):** IntersectionUtils.kt +
  IsoColorTest.kt → core-math; AdvancedSceneConfig.kt + IsometricNode.kt (applyAlpha) +
  the four adopted orphans (GestureConfig.kt, GestureEvents.kt, IsometricRenderer.kt,
  DragEventClarityTest.kt) → compose-contracts; CHANGELOG.md hunk → docs-and-changelog;
  untracked snapshots dir + modified doc screenshots → snapshot-sweep-gate (discard/regen).
  Nothing in the dirty tree is unowned.
- **apiDump discipline:** only compose-contracts changes the compose dump (G1 additive).
  Its orphan-adoption commit lands first so the G1 dump diff is attributable. Gesture makes
  no public-API change; the gate's apiCheck is confirmation, never first discovery.
- **CI snapshot gating is currently OFF** (goldens never committed) — it turns ON when the
  sweep gate commits the snapshots directory; from that commit forward, `./gradlew test` on
  CI verifies goldens.

## Integration Points Between Slices

- gesture-coordination → docs-and-changelog: E1/gestures.mdx prose is written against the
  landed C1/C2 contract (exact window value, consumption conditions).
- compose-contracts → docs-and-changelog: composables.mdx Group alpha row; skipped with a
  recorded deferral if G1 has not landed.
- shape-geometry → snapshot-sweep-gate: the two visual proxy+deferrals clear at the gate's
  attribution ledger + CI verify.
- compose-contracts → snapshot-sweep-gate: alphaSampleScene.png is the conditional
  attribution entry (G1 should be a no-op for scenes without group alpha — any diff there
  is investigated, not rubber-stamped).
- gesture-coordination → snapshot-sweep-gate: the instrumented-run evidence timestamp is
  the AC-S3 currency checkpoint.

## Recommended Implementation Order

1. `core-math` — foundation, widest blast radius, F1/F2 net precedes the already-landed A6 it guards.
2. `gesture-coordination` — riskiest restructure; starts the emulator feedback loop early.
3. `compose-contracts` — orphan adoption + G1 plumbing + apiDump churn while gesture context is fresh.
4. `view-module` — near-trivial residual; pressure release.
5. `shape-geometry` — deliberately late to shorten the known-red-goldens window.
6. `docs-and-changelog` — prose against landed behavior.
7. `snapshot-sweep-gate` — the closing attribution + CI gates.

## Conflicts Found

1. **Orphaned uncommitted files (RESOLVED at discovery):** GestureConfig.kt,
   GestureEvents.kt, IsometricRenderer.kt, DragEventClarityTest.kt were claimed by no
   slice's commit plan → PO assigned them to compose-contracts (same ABI-compat family as
   its AdvancedSceneConfig constructor).

No file-level edit conflicts: gesture-coordination (IsometricScene.kt only) and
compose-contracts (IsometricNode/RenderContext/AdvancedSceneConfig/IsometricComposables)
are disjoint; all other slices live in separate modules or file sets. Compose-contracts'
preference to commit before gesture-coordination for dump attribution is moot — gesture
makes no public-API change — so the slice-index order stands.

## Freshness Research

Consolidated from the per-slice plans (each carries the detail):

- **Compose 1.5.0 `detectTapGestures`** (primary source: TapGestureDetector.kt): onTap waits
  out `doubleTapTimeoutMillis` (300ms) only when onDoubleTap is registered — the C1 contract
  is the platform default. `awaitFirstDown(requireUnconsumed=true)` starvation confirmed as
  the mechanism behind consumption hazards; `forEachGesture` deprecated (event loss,
  b/251260206) → `awaitEachGesture`.
- **`pointerInput` keying** (SuspendingPointerInputFilter.kt): key change cancels
  synchronously, restarts lazily on the next event — `Unit`-keyed + `rememberUpdatedState`
  is the correct pattern and survives the merge.
- **binary-compatibility-validator 0.17.0:** `.api` dumps do not track `$stable` bitmasks —
  annotation swaps (E4) don't move dumps; only G1's signature change will.
- **Paparazzi 1.3.0:** `maxPercentDifference` is a per-test rule parameter, not global;
  Windows↔Linux drift (issues #1465/#1716) is open and unfixed in 1.3.x; `./gradlew test`
  auto-selects verify mode once goldens are committed.
- **Kotlin/JVM idioms:** `Objects.hash(x, y)` for A7 (961 for ORIGIN, no linear collisions);
  `coerceIn(0.0, 1.0)` for A4.

## Recommended Next Stage

- **Option A (default):** `/wf implement full-codebase-audit-fixes core-math` — the
  recommended first slice; no dependencies, execution-ready, no blockers anywhere in the
  plan set. Consider running `/compact` first — planning research is noise for
  implementation, and workflow state lives in the artifact files on disk.
- **Option B:** implement any of the five content slices in any order — they are mutually
  independent; only docs-and-changelog (needs 4 slices landed) and snapshot-sweep-gate
  (needs all 6) are order-constrained.
- **Option C:** `/wf slice full-codebase-audit-fixes` — NOT recommended; planning confirmed
  the slice boundaries (the one cross-slice conflict was ownership of pre-existing files,
  resolved at discovery, not a boundary problem).
