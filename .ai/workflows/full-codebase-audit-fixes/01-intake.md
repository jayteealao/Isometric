---
schema: sdlc/v1
type: intake
slug: full-codebase-audit-fixes
status: complete
stage-number: 1
created-at: "2026-07-07T00:20:07Z"
updated-at: "2026-07-07T00:20:07Z"
revision-count: 1
tags: [audit-findings, isometric-core, isometric-compose, isometric-android-view, gestures, docs, tests]
refs:
  index: 00-index.md
  next: 02-shape.md
  findings-source: audit-findings.json
next-command: wf-shape
next-invocation: "/wf shape full-codebase-audit-fixes"
---

# Intake

## The Intake

A 111-agent fresh-eyes audit of the entire Isometric codebase (2026-07-06) surfaced 75 raw
findings; adversarial verification refuted 42 and confirmed 29, with 4 left as split-verdict
judgment calls. This workflow exists to burn down that confirmed list. After deduplication —
three reviewers independently found the same double-tap bug, and two found the same
2D/3D mix in `distanceToSegmentSquared` — the real inventory is **24 distinct confirmed
defects plus 4 judgment calls**, spanning four zones: legacy core-math utilities (the oldest
code, where most contract-drift lives), gesture *coordination* in the Compose runtime (the
math survived scrutiny; the stacking of independent `pointerInput` blocks did not), test
rigor (diagnostic tests that cannot fail), and docs/changelog drift.

The riskiest calls are already made: the PO accepted **direct breaking changes** for the three
fixes that alter observable behavior — flipping `rotateX`/`rotateY` to match `rotateZ`'s
direction, correcting the Octahedron's distorted geometry, and removing Knot's hardcoded
cosmetic offset. All three will churn Paparazzi snapshots and change rendered scenes for any
existing caller; no deprecation shims. The work rides the existing `feat/ws10-interaction-props`
branch (shared strategy), which means these fixes land in the same PR as the pending WS10
interaction work — the one caveat worth watching, since that branch already carries the
`fresh-eyes-review-fixes` workflow awaiting handoff.

The full machine-readable audit output — including all 42 refutations with verifier
reasoning — is preserved in [audit-findings.json](audit-findings.json) so downstream
stages can drill into any finding without chat context.

## Restated Request

Intake all findings from the 2026-07-06 full-codebase multi-agent audit: 29 confirmed
defects (24 after dedup) and 4 judgment-call items, covering isometric-core math and
shapes, Compose gesture handling, the Android View module, sample code, test quality,
API annotations, and documentation.

## Intended Outcome

Every confirmed finding is either fixed (with a regression test where behavior was wrong)
or explicitly waived with a recorded reason. Docs, CHANGELOG, and API dumps are back in
sync with the code. The library's documented contracts (KDoc) match actual behavior.

## Primary User / Actor

Library consumers (app developers using isometric-core/-compose/-android-view), who
currently hit silent wrong results (black faces, opposite rotations, missed interior hits,
spurious onClick) and misleading documentation. Secondary: the maintainer, who gains a
test suite that can actually fail.

## Findings Inventory (deduplicated, grouped by zone)

Severity and file:line anchors from the audit; full detail + verifier notes in
[audit-findings.json](audit-findings.json).

### Zone A — Core math & geometry (isometric-core)
- **A1 (high)** `Point.kt:147,164` — `rotateX`/`rotateY` apply the transposed (inverse)
  rotation; positive angles rotate CW while `rotateZ` rotates CCW. Fix: flip to match
  `rotateZ` (BREAKING — accepted).
- **A2 (high)** `Point.kt:59-67` — `distanceToSegmentSquared` mixes a 2D dot product with a
  3D squared length and reconstructs the closest point with z=0; wrong for any segment with
  z-extent. Public API (also `distanceToSegment`).
- **A3 (high)** `IntersectionUtils.kt:18` — `isPointCloseToPoly` KDoc/@return promise an
  inside-test the implementation doesn't perform; interior points far from edges return false.
- **A4 (medium)** `IsoColor.kt:103` — `lighten()` lacks a floor clamp; negative lightness
  drives all RGB channels to 0 (solid black) for back-lit dark faces.
- **A5 (medium)** `Vector.kt:47` — `normalize()` KDoc claims "Throws if magnitude is zero";
  code silently returns the zero vector.
- **A6 (low→medium)** `IsometricProjection.kt:92` — `cullPath` winding uses only the first 3
  vertices; the built-in Stairs concave zigzag silhouette can be mis-culled.
- **A7 (low)** `TileCoordinate.kt:48` — weak `hashCode` (0 for ORIGIN; collides on
  `(k, k*1_000_003)` patterns).

### Zone B — Shapes
- **B1 (high)** `Octahedron.kt:43` — non-uniform post-scale (√2/2, √2/2, 1.0) elongates the
  shape along Z, contradicting the "inscribed in a unit cube" KDoc (BREAKING — accepted).
- **B2 (medium)** `Knot.kt:49` — hardcoded cosmetic offset means `Knot(Point.ORIGIN)` is not
  at ORIGIN (BREAKING — accepted).
- **B3 (medium)** `Cylinder.kt:25` — its `require` messages are unreachable; `Circle`'s fire first.

### Zone C — Compose gesture coordination
- **C1 (high)** `IsometricScene.kt:492` — double-tap fires spurious `onClick` twice before
  `onDoubleClick` (uncoordinated sibling `pointerInput` blocks). Found by 3 independent
  reviewers. Docs at `interactions.mdx:171` describe suppression that doesn't exist (→ E1).
- **C2 (medium)** `IsometricScene.kt:439` — drag events consumed even with no drag handler
  and no camera, blocking parent gesture detectors (scene inside scrollable).
- **C3 (low)** `IsometricScene.kt:449` — stale `isDragging`/`draggedNode` after a
  long-press → drag → release sequence.

### Zone D — View module & samples
- **D1 (medium)** `AndroidCanvasRenderer.kt:44` — new `Paint` per RenderCommand per frame;
  Compose renderer caches, View renderer doesn't.
- **D2 (medium)** `InteractionSamplesActivity.kt:612` — DragLifecycleSample KDoc misstates
  `DragEvent.x/y` semantics (same error class fixed in docs by commit 278ea1a).
- **D3 (low)** `IsometricView.kt:36` — `setSort`/`setCull`/`setBoundsCheck` invalidate
  without rebuilding `cachedScene`.

### Zone E — Docs, changelog, API annotations
- **E1 (medium)** `site/src/content/docs/guides/interactions.mdx:171` — `onDoubleClick`
  description falsely implies single-tap suppression (pairs with C1; doc must match the fix).
- **E2 (medium)** `site/src/content/docs/reference/scene-config.mdx:10` — SceneConfig
  reference table missing the `nodeDragState` parameter.
- **E3 (medium)** `CHANGELOG.md:33` — Unreleased Features section omits three major new
  APIs added on the WS10 branch.
- **E4 (medium)** `AdvancedSceneConfig.kt:57` — `@Stable` subclass of `@Immutable`
  SceneConfig holding a mutable engine + callbacks; neither annotation honest.

### Zone F — Test rigor
- **F1 (high)** `DepthSorterTest.kt:13` — 6x6 TileGrid diagnostic test has zero assertions.
- **F2 (high)** `DepthSorterTest.kt:255,370` — tower assertions guarded by
  `if (faceIndex >= 0)` with no existence assertion; pass vacuously if culling regresses.
- **F3 (medium)** `IsometricNodeRenderTest.kt:175` — alpha test asserts direction (< 255)
  only, not the scaled value.
- **F4 (low)** `DragANodeTest.kt` — no coverage that node selection suppresses camera autopan.

### Zone G — Judgment calls (all IN scope per PO)
- **G1** GroupNode ignores `alpha`: `IsometricNode.alpha` KDoc says unconditional render-time
  application; composables reference says Group doesn't accept alpha. Reconcile (pick one).
- **G2** `IsometricView.onTouchEvent` returns false on ACTION_UP — mostly harmless under the
  touch-target model, but return true for clarity.
- **G3** Document that callback-only `AdvancedSceneConfig` changes don't re-register
  (deliberate equals() exclusion — needs a sentence of documentation).
- **G4** `DragEvent.copy()` ABI break — verify the existing CHANGELOG migration note is
  sufficient; likely a no-op.

## Known Constraints

- **Shared branch:** all commits land on `feat/ws10-interaction-props` (base `master`);
  no separate PR. The branch already carries WS10 interaction work and the
  `fresh-eyes-review-fixes` workflow output awaiting handoff.
- **Breaking changes accepted** for A1, B1, B2 — no deprecation cycles (standing PO
  preference). Migration notes belong in CHANGELOG.
- **Docs are authored in `site/**/*.mdx`** (canonical); `docs/*.md` are generated by
  `scripts/sync-docs.js` — never hand-edit the mirrors.
- **API dumps** (`*/api/*.api`, binary-compatibility-validator) must be regenerated for any
  signature/annotation change.
- Paparazzi snapshot churn from A1/B1/B2 is expected and accepted; snapshots must be
  re-recorded deliberately, not rubber-stamped.

## Assumptions

- The audit's working tree (this branch as of 2026-07-06) is the fix baseline; findings
  were verified against code that already includes the fresh-eyes-review-fixes work.
- The 42 refuted findings need no action (each carries verifier reasoning in
  audit-findings.json if re-litigation is ever wanted).
- `fresh-eyes-review-fixes` proceeds to handoff independently; this workflow does not
  block or reopen it.

## Product Owner Questions Asked

- Routing (new fix workflow / attach slice / full intake), branch strategy, appetite,
  review scope, success-criteria bar, breaking-change posture, judgment-call scope,
  stack confirmation. See [po-answers.md](po-answers.md).

## Product Owner Answers

- Full intake; shared branch on `feat/ws10-interaction-props`; **Large** appetite;
  **slug-wide** review; fixed-with-regression-test bar (waivers must be recorded);
  direct breaking changes for A1/B1/B2; all 4 judgment calls in scope; stack confirmed.

## Unknowns / Open Questions

- Whether A1's rotation flip should also add a KDoc statement of the direction convention
  (shape stage should decide the documented contract, not just the sign).
- Whether F1/F2's diagnostic tests get assertions added or get deleted (they were
  scaffolding for a since-fixed bug hunt).
- How to order fixes so Paparazzi re-recordings happen once, not per-slice.

## Dependencies / External Factors

- None external. All fixes are self-contained in this repo; no dependency upgrades,
  no vendor API involvement.

## Risks if Misunderstood

- Flipping rotation signs without a documented convention just moves the ambiguity.
- Fixing C1 (double-tap) by naive delay-based suppression would add latency to every
  single tap — the fix approach needs care at plan time.
- Landing 24 fixes on an already-large shared branch could bloat the WS10 PR past
  reviewability; slicing and commit hygiene matter more than usual.

## Success Criteria

- Each of the 24 confirmed findings: fixed + regression test (behavioral), or fixed
  (docs/annotations), or waived with recorded reason.
- All 4 judgment calls resolved (fix or documented decision).
- `./gradlew apiCheck` passes with regenerated dumps; doc mirrors regenerated via
  `scripts/sync-docs.js`; CHANGELOG carries migration notes for A1/B1/B2.
- Full test suite green, including deliberately re-recorded Paparazzi snapshots.

## Out of Scope for Now

- The 42 refuted findings.
- Any new features; perf work beyond D1's allocation fix.
- Handoff/ship of the `fresh-eyes-review-fixes` workflow (separate, already queued).
- WebGPU roadmap work.

## Freshness Research

- Source: n/a — deliberately skipped.
  Why it matters: this workflow touches only internal code, tests, and docs; no external
  dependency, platform API, or standard is implicated.
  Takeaway: no freshness pass needed at intake; if plan-stage work touches Compose
  pointer-input internals, verify against the pinned Compose version then.

## Recommended Next Stage

- **Option A (default):** `/wf shape full-codebase-audit-fixes` — 28 items across 7 zones
  need scoping decisions (fix-vs-waive per item, the C1 fix approach, snapshot re-record
  strategy) and slicing; this is exactly what shape + slice exist for.
- **Option B:** `/wf plan full-codebase-audit-fixes` — only if shape feels redundant
  because the inventory above is already acceptance-criteria-shaped; NOT recommended at
  Large appetite (slicing is needed, and slice requires shape's output).
