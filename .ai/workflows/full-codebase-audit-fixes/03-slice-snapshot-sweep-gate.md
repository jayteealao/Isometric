---
schema: sdlc/v1
type: slice
slug: full-codebase-audit-fixes
slice-slug: snapshot-sweep-gate
status: complete
stage-number: 3
created-at: "2026-07-07T11:54:55Z"
updated-at: "2026-07-07T11:54:55Z"
revision-count: 1
complexity: s
depends-on: [core-math, gesture-coordination, compose-contracts, view-module, shape-geometry, docs-and-changelog]
tags: [paparazzi, snapshots, api-check, sweep-gate, doc-screenshots]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-core-math.md, 03-slice-gesture-coordination.md, 03-slice-compose-contracts.md, 03-slice-view-module.md, 03-slice-shape-geometry.md, 03-slice-docs-and-changelog.md]
  plan: 04-plan-snapshot-sweep-gate.md
  implement: 05-implement-snapshot-sweep-gate.md
---

# Slice: Snapshot Sweep Gate

## The Slice

The sweep ends with one deliberate act of attribution. Every golden that changed across the
six content slices gets re-recorded in a single `recordPaparazziDebug` pass, and every
changed PNG must be traceable to a named fix — B1's Octahedron proportions, B2's Knot
positioning, possibly G1's group alpha if a snapshot scene exercises it. A golden that
changed for a reason nobody can name is a regression, and catching it is this slice's
entire job. The PO chose this once-at-end model over per-slice re-records precisely so the
snapshot history is one commit with a complete attribution story instead of five commits of
partial churn.

Two closing gates ride with the re-record: the doc screenshots regenerate via
DocScreenshotGenerator (the shape pages show the corrected geometry), and the final
`./gradlew test apiCheck` proves the whole branch green — the first time since
`shape-geometry` landed that a full-suite green is even possible. One platform caveat from
the freshness research: Paparazzi 1.3.0 has known pixel drift between record hosts, and
these goldens record on Windows while CI verifies on Linux. The gate is not done at local
green — it is done when CI confirms the recorded goldens verify cleanly, or when a
deliberate, minimal `maxPercentDifference` is set with the reason recorded.

## Goal

All goldens re-recorded once with every diff attributed to an intended fix (AC-S2); doc
screenshots regenerated; `./gradlew test apiCheck` green on the final branch state (AC-S1);
instrumented-suite evidence from the gesture slice confirmed still valid (AC-S3 checkpoint).

## Why This Slice Exists

The PO's snapshots-once-at-end decision needs an owner: a slice whose acceptance criteria
are the sweep-level gates themselves, sequenced after every content slice so nothing lands
behind it un-verified.

## Scope

- **In:** `recordPaparazziDebug` re-record pass; visual inspection + per-file attribution
  of every changed golden (isometric-compose snapshot tests: octahedron.png, knot.png,
  composite scenes); DocScreenshotGenerator run for docs/assets/screenshots; final
  `./gradlew test apiCheck`; CI-verification follow-through for the cross-platform drift
  caveat; confirmation that the gesture slice's instrumented evidence is still current
  (re-run only if gesture-touching code changed after that slice's verify).
- **Out:** any code fix (if inspection finds an unattributable diff, the fix goes back to
  the owning slice — this slice never patches code); Git LFS for snapshots (explicitly out
  of workflow scope).

## Acceptance Criteria

- `./gradlew test apiCheck` green on the final branch state — the closing automated gate
  for the whole sweep. (AC-S1)
  <!-- observable: false — deterministic gradle gates -->
- One deliberate `recordPaparazziDebug` pass; every changed golden visually inspected and
  attributed to an intended fix (B1, B2, possibly G1); goldens committed as their own
  snapshot commit; CI verification passes post-record (or a minimal, deliberate
  maxPercentDifference is set with the reason recorded). (AC-S2)
  <!-- observable: true — the rendered goldens are the user-visible outcome of the sweep's visual fixes; inspection is the human evidence -->
  verify: { method: recordPaparazziDebug + human golden-diff inspection (git diff of PNGs / Paparazzi HTML report), env: local Gradle JVM on Windows record host; CI Linux re-verification after the record commit (Paparazzi 1.3.0 cross-platform drift), fixture: existing IsometricCanvasSnapshotTest scenes, rung: android-2 (device-free screenshot goldens) }
- Doc screenshots regenerated via DocScreenshotGenerator; shape-page images show the
  corrected Octahedron/Knot geometry. (AC-S2 doc-screenshot half)
  <!-- observable: true — published doc imagery consumers see -->
  verify: { method: DocScreenshotGenerator run + human inspection of regenerated PNGs, env: local Gradle JVM, fixture: doc screenshot scenes, rung: android-2 (device-free rendering) }
- Instrumented androidTest evidence from `gesture-coordination` confirmed current: no
  gesture-touching code changed after that slice's verify, or the suite is re-run. (AC-S3 checkpoint)
  <!-- observable: false — evidence-currency check (git log over gesture files vs the recorded run); the runtime evidence itself was produced in the gesture slice -->

## Dependencies on Other Slices

- All six content slices — this gate closes the sweep and must run last.

## Risks

- **Cross-platform pixel drift** (Windows record host vs CI Linux, Paparazzi 1.3.0): local
  green does not close the gate; the CI verification after the record commit does. If CI
  fails on drift-only pixels, the decision (record on CI-matching platform vs minimal
  maxPercentDifference) is made deliberately and recorded, never rubber-stamped.
- **Unattributable golden diff:** the failure mode this slice exists to catch. It reopens
  the owning content slice; it is never resolved by re-recording harder.
