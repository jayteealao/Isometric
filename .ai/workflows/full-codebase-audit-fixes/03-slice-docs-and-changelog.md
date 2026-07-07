---
schema: sdlc/v1
type: slice
slug: full-codebase-audit-fixes
slice-slug: docs-and-changelog
status: defined
stage-number: 3
created-at: "2026-07-07T11:54:55Z"
updated-at: "2026-07-07T11:54:55Z"
revision-count: 1
complexity: m
depends-on: [core-math, gesture-coordination, compose-contracts, shape-geometry]
tags: [docs, mdx, changelog, migrations, sync-docs]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-core-math.md, 03-slice-gesture-coordination.md, 03-slice-compose-contracts.md, 03-slice-view-module.md, 03-slice-shape-geometry.md, 03-slice-snapshot-sweep-gate.md]
  plan: 04-plan-docs-and-changelog.md
  implement: 05-implement-docs-and-changelog.md
---

# Slice: Docs & Changelog

## The Slice

Docs come last on purpose: every page this slice touches describes behavior another slice
just changed, and writing them earlier would mean documenting code that doesn't exist yet.
The interactions guide currently describes a single-tap suppression that the gesture code
never implemented — after `gesture-coordination` lands, the page gets the real contract
(delayed disambiguation only when both handlers are present). The scene-config reference is
missing its `nodeDragState` row; the composables reference gains Group's new `alpha`
parameter; the gestures guide documents consume-only-when-acting; and the shapes guide gets
a one-line rotation-direction note pointing at the now-documented CCW convention.

The CHANGELOG work is the bigger half. The Unreleased section omits three major WS10 APIs
entirely — features that shipped on this branch with no trace — and the sweep adds four
behavior breaks that each need a before/after migration note (A1 rotation direction, B1
Octahedron proportions, B2 Knot positioning, C1 tap timing). G4 is verify-only: confirm
the existing DragEvent.copy() migration note actually covers the ABI break, and amend only
if it doesn't.

One rule governs the mechanics, from the standing project memory: the .mdx files under
`site/` are canonical and `docs/*.md` are generated — every edit goes through
`node scripts/sync-docs.js`, never a hand-edited mirror.

## Goal

Every guide, reference table, and CHANGELOG entry matches post-sweep reality: E1, E2, E3
fixed; G4 verified; Group alpha and the consumption/rotation notes added; mirrors
regenerated via sync-docs.js.

## Why This Slice Exists

Doc accuracy is only checkable against landed behavior — the shape's sequencing note pins
E-zone edits after the code slices. Batching them also touches CHANGELOG and
interactions.mdx exactly once, avoiding merge noise on the shared branch.

## Scope

- **In:** `site/src/content/docs/guides/interactions.mdx` (double-tap contract, E1),
  `guides/gestures.mdx` (consumption behavior), `guides/shapes.mdx` (rotation-direction
  note), `reference/scene-config.mdx` (nodeDragState row, E2), `reference/composables.mdx`
  (Group alpha), `CHANGELOG.md` (missing WS10 features + Migration subsection per breaking
  fix, E3; G4 verification), `node scripts/sync-docs.js` run regenerating `docs/*.md`
  mirrors.
- **Out:** KDoc (travels with each code slice per the hybrid decision); doc *screenshots*
  (→ `snapshot-sweep-gate`, regenerated with the goldens); wiring sync-docs.js into CI
  (out of scope for the workflow).

## Acceptance Criteria

- interactions.mdx's onDoubleClick section describes the delayed-disambiguation contract as
  implemented — no false suppression claim; matches AC-C1 behavior exactly. (AC-E1)
  <!-- observable: true — consumer-facing rendered docs; accuracy vs implemented behavior is human-judged -->
  verify: { method: human read-through of the rendered page against the landed gesture contract; markdownlint/lychee CI gates for mechanics, env: site dev build (npm) or rendered preview, fixture: n/a, rung: manual-review (residual — prose accuracy) }
- scene-config.mdx's parameter table includes `nodeDragState` with correct type/default/
  description. (AC-E2)
  <!-- observable: true — reference-table accuracy is human-judged against the source signature -->
  verify: { method: human read-through cross-checked against SceneConfig source, env: rendered site page, fixture: n/a, rung: manual-review (residual — prose accuracy) }
- CHANGELOG Unreleased lists the three missing WS10 APIs under Features AND a Migration
  subsection with before/after for each breaking fix (A1, B1, B2, C1 timing). (AC-E3)
  <!-- observable: true — migration-note sufficiency is human-judged -->
  verify: { method: human read-through; each migration note checked against the actual diff of its fix, env: CHANGELOG.md in repo, fixture: n/a, rung: manual-review (residual — editorial sufficiency) }
- The existing DragEvent.copy() CHANGELOG migration note verifiably covers the descriptor
  change; amended only if insufficient. (AC-G4)
  <!-- observable: true — sufficiency judgment against the ABI diff -->
  verify: { method: human comparison of the note against the DragEvent API diff (apiDump history), env: CHANGELOG.md + api dumps, fixture: n/a, rung: manual-review (residual — editorial sufficiency) }
- gestures.mdx documents consume-only-when-acting; shapes.mdx carries the rotation-direction
  note; composables.mdx documents Group's alpha parameter consistent with IsometricNode.alpha
  KDoc. (companion doc updates)
  <!-- observable: true — same class as E1/E2 -->
  verify: { method: human read-through against landed behavior, env: rendered site pages, fixture: n/a, rung: manual-review (residual — prose accuracy) }
- `node scripts/sync-docs.js` run after all .mdx edits; `docs/*.md` mirrors regenerated
  with no hand edits; docs CI gates (markdownlint, lychee) pass. (mechanics)
  <!-- observable: false — script run + lint gates are automated checks -->

## Dependencies on Other Slices

- `gesture-coordination`: E1 and gestures.mdx describe its landed contract.
- `core-math`: the rotation note documents A1's landed convention.
- `compose-contracts`: composables.mdx documents the landed Group alpha parameter.
- `shape-geometry`: E3's B1/B2 migration notes describe its landed changes.
- (`view-module` has no doc dependency — D2's KDoc traveled with that slice.)

## Risks

- **Docs describing intent instead of reality:** every accuracy AC is judged against the
  *landed* code, not the plan — if a fix shifted during implement, the doc follows the code.
- **Mirror drift:** any hand-edit to `docs/*.md` gets silently overwritten by the next
  sync run; the mechanics AC exists to make the regeneration explicit and diff-reviewed.
