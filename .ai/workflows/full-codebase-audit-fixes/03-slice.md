---
schema: sdlc/v1
type: slice-index
slug: full-codebase-audit-fixes
status: complete
stage-number: 3
created-at: "2026-07-07T11:54:55Z"
updated-at: "2026-07-07T11:54:55Z"
revision-count: 1
total-slices: 7
best-first-slice: core-math
tags: [audit-findings, isometric-core, isometric-compose, isometric-android-view, gestures, docs, tests]
slices:
  - slug: core-math
    status: defined
    complexity: l
    depends-on: []
  - slug: gesture-coordination
    status: defined
    complexity: l
    depends-on: []
  - slug: compose-contracts
    status: defined
    complexity: m
    depends-on: []
  - slug: view-module
    status: defined
    complexity: s
    depends-on: []
  - slug: shape-geometry
    status: defined
    complexity: s
    depends-on: []
  - slug: docs-and-changelog
    status: defined
    complexity: m
    depends-on: [core-math, gesture-coordination, compose-contracts, shape-geometry]
  - slug: snapshot-sweep-gate
    status: defined
    complexity: s
    depends-on: [core-math, gesture-coordination, compose-contracts, view-module, shape-geometry, docs-and-changelog]
refs:
  index: 00-index.md
  shape: 02-shape.md
next-command: wf-plan
next-invocation: "/wf plan full-codebase-audit-fixes core-math"
---

# Slice Index

## The Slices

Twenty-eight audit items became seven slices, and the cut that matters most is the one the
PO made in the interview: slices follow the codebase's own zones rather than finding
severity or fix size. Five content slices are fully independent — core math
(9 items, the foundation everything renders through), the gesture restructure (4 items,
one root cause), Compose contract honesty (4 items including the Group-alpha build), the
View module's small stuff (4 items), and the two breaking shape-geometry fixes (3 items) —
so a failure in any one of them stalls nothing else. Only the last two slices have hard
dependencies: docs describe landed behavior, and the sweep gate closes the branch.

Two decisions shape the ordering more than the dependencies do. First, expensive
verification is distributed, not batched: the instrumented emulator suite runs inside the
gesture slice where a regression is cheapest to attribute, while the single Paparazzi
re-record stays at sweep end per the PO's snapshots-once decision. Second — and this is the
one non-obvious move — `shape-geometry` is deliberately sequenced *fifth* despite being
independent and small, because the moment B1/B2 land, every affected golden goes red and
stays red until the sweep-end re-record. Landing it just before the gate keeps that
known-red window to two slices instead of six.

The top risk is inherited, not created: all seven slices commit to the shared
`feat/ws10-interaction-props` branch, which already carries the WS10 interaction work and
a second workflow awaiting handoff. Slice-per-zone commits with clean conventional messages
are the only thing keeping the eventual PR reviewable — the slicing here is doing double
duty as commit hygiene.

## Slice Strategy

Zone-aligned decomposition (PO decision, 2026-07-07): each slice is one coherent zone of
the audit inventory, independently verifiable, mapping to 1–3 commits.

- [core-math](03-slice-core-math.md) — A1–A7 + F1/F2 (`l`). F1/F2 ride here because the
  shape pins them to the A6 culling change they guard.
- [gesture-coordination](03-slice-gesture-coordination.md) — C1/C2/C3 + F4 (`l`). One
  restructure, one root cause; carries the AC-S3 instrumented gate.
- [compose-contracts](03-slice-compose-contracts.md) — G1 + E4 + G3 + F3 (`m`). E4/G3 are
  Compose-source contract fixes, not site docs, so they travel with the module; F3 pins
  the alpha math G1 makes load-bearing.
- [view-module](03-slice-view-module.md) — D1/D2/D3 + G2 (`s`). G2 joins its module rather
  than an artificial "judgment calls" slice; D2's sample KDoc travels per the hybrid docs
  decision.
- [shape-geometry](03-slice-shape-geometry.md) — B1/B2/B3 (`s`). Golden inspection
  pre-registered as deferred to the sweep gate.
- [docs-and-changelog](03-slice-docs-and-changelog.md) — E1/E2/E3 + G4 + companion .mdx
  notes (`m`). Site docs only; KDoc went with the code.
- [snapshot-sweep-gate](03-slice-snapshot-sweep-gate.md) — AC-S1/S2 + S3 checkpoint (`s`).
  Owns the once-at-end re-record and the closing green.

Zone G was dissolved rather than sliced: each judgment call went to the module it touches
(G1/G3 → compose-contracts, G2 → view-module, G4 → docs-and-changelog). A "judgment calls"
slice would have crossed three modules for no verification benefit.

Coverage check: 9 + 4 + 4 + 4 + 3 + 4 = 28 items; every finding from
[01-intake.md](01-intake.md) has exactly one owning slice.

## Recommended Order

1. `core-math` — foundation with the widest blast radius; the F1/F2 net lands before the
   A6 change it guards; missing rotate tests get written first (PO: core math first).
2. `gesture-coordination` — the riskiest restructure starts its long feedback loop
   (emulator gate) early, right after the foundation is trustworthy.
3. `compose-contracts` — G1's alpha plumbing and apiDump churn land while the gesture
   slice's API context is fresh; any golden impact is known before the re-record.
4. `view-module` — small and independent; a natural pressure-release slice between the
   heavy Compose work and the breaking visual changes.
5. `shape-geometry` — deliberately late: B1/B2 turn goldens red until the sweep re-record;
   this keeps the known-red window to two slices.
6. `docs-and-changelog` — describes landed behavior; touches CHANGELOG and
   interactions.mdx exactly once.
7. `snapshot-sweep-gate` — the closing gates; nothing lands behind it.

## Cross-Cutting Concerns

- **Constructive-proof rule (all behavioral fixes):** every new regression test must fail
  against the pre-fix implementation before the fix lands — the shape's observation model,
  enforced at implement/verify in every slice.
- **Commit hygiene on the shared branch:** one logical fix (or coherent fix group) per
  commit, conventional-commit messages (commitlint gates PRs), snapshot re-record as its
  own commit. The slicing is the commit plan.
- **apiDump discipline:** compose-contracts (G1 additive, E4 empirical) and any signature
  change regenerate dumps in the owning slice; the sweep gate's apiCheck is confirmation,
  never the first discovery.
- **KDoc travels with code** (PO hybrid decision): A1b/A5 in core-math, G1/G3/E4 KDoc in
  compose-contracts, D2 in view-module. Site .mdx + CHANGELOG batch in docs-and-changelog.
- **Known-red goldens window:** between shape-geometry and the sweep gate, full
  `./gradlew test` is expectedly red on snapshot verification; slice verifies in that
  window scope to unit tests with the red goldens explicitly attributed.
- **Design floor (stack.ui non-empty):** no slice introduces a new UI surface or state —
  this is a correctness sweep on existing surfaces, so the state-completeness lens adds no
  extra slice boundaries; register/product rules apply only if docs pages gain new visual
  content (they don't).

## Dependencies Between Slices

- `docs-and-changelog` ← {core-math, gesture-coordination, compose-contracts,
  shape-geometry}: doc accuracy is judged against landed behavior.
- `snapshot-sweep-gate` ← all six content slices: the closing gates run last.
- Soft (ordering, not blocking): shape-geometry late to shorten the red-golden window;
  F1/F2 inside core-math sequence before/with A6; AC-S3 evidence produced in
  gesture-coordination and checkpointed at the gate.
- All five content slices are mutually independent — any can proceed if another stalls.

## Deferred / Optional Slices

- None. All 28 items are in scope per the PO (including all four judgment calls). The only
  deferrals are *pre-registered verification deferrals*, not scope cuts: B1/B2/G1 golden
  inspection is deferred from its owning slice to `snapshot-sweep-gate` by the PO's
  snapshots-once-at-end decision.

## Freshness Research

- Source: none this stage (deliberate).
  Why it matters: slicing decisions here depend on internal sequencing constraints, not
  external APIs; the external facts that *do* shape slice boundaries were researched at
  shape and are inherited (Compose detectTapGestures disambiguation → C1/C2/C3 as one
  slice; Paparazzi 1.3.0 cross-platform drift → dedicated sweep-gate slice with a CI
  verification step).
  Takeaway: no new external constraint affects the decomposition; plan-stage should
  re-verify Compose pointer-input specifics against the pinned 1.5.0 source when engineering
  the gesture slice.

## Recommended Next Stage

- **Option A (default):** `/wf plan full-codebase-audit-fixes core-math` — plan the best
  first slice; it has no dependencies, the widest blast radius, and its F1/F2 net must
  precede the A6 change.
- **Option B:** `/wf plan full-codebase-audit-fixes all` — the five content slices are
  mutually independent, so parallel planning is genuinely viable; costs more up-front
  context but front-loads discovery of any cross-slice surprise (e.g., G1's RenderContext
  plumbing).
- **Option C:** `/wf shape full-codebase-audit-fixes` — not recommended; no slicing
  question surfaced a gap in the shape (all four interview answers landed on the
  recommended options without new constraints).
