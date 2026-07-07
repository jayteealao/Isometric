---
schema: sdlc/v1
type: plan
slug: full-codebase-audit-fixes
slice-slug: docs-and-changelog
status: complete
stage-number: 4
created-at: "2026-07-07T12:11:43Z"
updated-at: "2026-07-07T12:12:00Z"
metric-files-to-touch: 11
metric-step-count: 12
has-blockers: false
revision-count: 0
tags: [docs, mdx, changelog, migrations, sync-docs]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-docs-and-changelog.md
  siblings:
    - 04-plan-core-math.md
    - 04-plan-gesture-coordination.md
    - 04-plan-compose-contracts.md
    - 04-plan-view-module.md
    - 04-plan-shape-geometry.md
    - 04-plan-snapshot-sweep-gate.md
  implement: 05-implement-docs-and-changelog.md
next-command: wf-implement
next-invocation: "/wf implement full-codebase-audit-fixes docs-and-changelog"
---

# Plan: Docs & Changelog

## The Plan

Docs come last because accuracy has to be measured against code that already landed, not code
that was planned. Every sentence this slice writes about disambiguation windows, rotation
directions, and geometry proportions is only as good as the slice it follows — which means the
most dangerous step here is the first one: at implement time, read the landed diffs from the
four dependency slices before writing a single word. The plan is concrete about *where* each
edit lives and *what it must cover*, but final wording is verified against the actual
implementations, not against what the other plans intended.

The three missing WS10 APIs are `NodeDragState`/`rememberNodeDragState`/`NodeDragBounds`
(the single-node tap-to-select-then-drag affordance from commit `42842f9`), `onDoubleClick`
per-node handler, and `GestureConfig.longPressTimeoutMs` (both from commit `fadd467`). None of
these appear in the CHANGELOG Unreleased Features section — they shipped on the branch with no
trace. The interactions.mdx `onDoubleClick` section still carries the false claim that the
gesture system disambiguates for you; the `scene-config.mdx` table still lacks the
`nodeDragState` row; and `composables.mdx`'s Group table will need its `alpha` row once the
compose-contracts slice lands it. These are the four concrete content gaps, with the Migration
subsection for A1/B1/B2/C1 as the largest single block of new prose.

One thing to note plainly: the CHANGELOG working tree already has an uncommitted hunk — the
`DragEvent.x/y` Migration Note. That content is the G4 verify target. At implement time, fold
it into the E3 Migration subsection if it belongs there, or confirm it stands alone as
sufficient. Do not assume it is final just because it is in the working tree.

The mechanics gate is automated: one `node scripts/sync-docs.js` run at the end of all .mdx
edits regenerates every `docs/*.md` mirror. Markdownlint and lychee then gate the mirrors in
CI. The accuracy ACs are all manual-review residual — human read-through of rendered pages
against landed behavior, which no linter can substitute for.

## Current State

**Per-finding classification (as of 2026-07-07T12:11:43Z, branch feat/ws10-interaction-props):**

### E1 — interactions.mdx onDoubleClick false disambiguation claim
**Status: STILL DRIFTED.** Lines 169–171 of `site/src/content/docs/guides/interactions.mdx` read:
> "A single tap still routes to `onClick` — the two are independent, and the gesture system
> disambiguates a single tap from the first half of a double tap for you."

This is the false claim identified in the audit. The `gesture-coordination` slice must land
(fixing the actual code) before this can be rewritten to describe the delayed-disambiguation
contract accurately.

### E2 — scene-config.mdx missing nodeDragState row
**Status: STILL DRIFTED.** The SceneConfig parameter table in `site/src/content/docs/reference/scene-config.mdx`
(lines 15–25) lists eight parameters and omits `nodeDragState: NodeDragState? = null` entirely.
Confirmed by direct inspection; 9b30c52 did not add this row.

### E3 — CHANGELOG missing three WS10 APIs and Migration subsection
**Status: PARTIALLY FIXED (uncommitted).** The committed CHANGELOG has only one Features entry
("Add per-node interaction props") and no Migration Notes subsection. The git diff shows an
uncommitted hunk adding the `DragEvent.x/y` Migration Note. Still missing:
- `NodeDragState`/`rememberNodeDragState`/`NodeDragBounds`/`SceneConfig.nodeDragState` (Features)
- `onDoubleClick` per-node handler (Features)
- `GestureConfig.longPressTimeoutMs` (Features)
- Migration subsection entries for A1 (rotation direction), B1 (Octahedron proportions),
  B2 (Knot positioning), C1 (tap timing / disambiguation contract)

**The three missing WS10 APIs by name:**
1. `NodeDragState` / `rememberNodeDragState` / `NodeDragBounds` (single-node drag affordance)
2. `onDoubleClick` (per-node double-tap handler)
3. `GestureConfig.longPressTimeoutMs` (configurable long-press timeout)

### G4 — DragEvent.copy() migration note sufficiency
**Status: VERIFY ONLY (uncommitted content exists, not yet committed).** The uncommitted git
diff adds the `DragEvent.x/y` semantic-change Migration Note. This does not directly address
the binary ABI break (copy() descriptor change from `copy(DD)DragEvent` to
`copy(DDLDragDelta;)DragEvent`). At implement time: compare the uncommitted note against the
API dump diff to determine if the descriptor change is called out explicitly. Amend if not
sufficient.

### Companion: gestures.mdx consume-only-when-acting
**Status: STILL MISSING.** `gestures.mdx` documents the drag event API correctly but has no
note explaining the consume-only-when-acting contract (C2). This must be added after
`gesture-coordination` lands.

### Companion: shapes.mdx rotation-direction note
**Status: STILL MISSING.** `shapes.mdx` mentions `rotateX`/`rotateY` are available (line 91)
but has no note on direction convention. Must be added after `core-math` lands the A1 fix.

### Companion: composables.mdx Group alpha parameter
**Status: NOT YET APPLICABLE.** The Group table in `composables.mdx` correctly reflects the
current state (no alpha param). The alpha exclusion note at line 95 is accurate today. After
`compose-contracts` lands G1 (alpha propagation through GroupNode), add the `alpha` row and
remove/update the exclusion note.

## Simplicity Ladder

No new capabilities — this slice is text/copy changes only. Ladder N/A.

## Applied Learnings

No applicable learnings found. (`.ai/solutions/INDEX.md` is absent.)

## Likely Files / Areas to Touch

- `site/src/content/docs/guides/interactions.mdx` — E1: rewrite onDoubleClick section (lines 167–188)
- `site/src/content/docs/guides/gestures.mdx` — companion: add consume-only-when-acting note (after Drag Handling section)
- `site/src/content/docs/guides/shapes.mdx` — companion: add rotation-direction note (under rotateZ)
- `site/src/content/docs/reference/scene-config.mdx` — E2: add nodeDragState row to SceneConfig table
- `site/src/content/docs/reference/composables.mdx` — companion: add alpha row to Group table, update exclusion note
- `CHANGELOG.md` — E3: add three Features entries, add Migration subsection with A1/B1/B2/C1 before/after; G4: verify/amend DragEvent note
- `docs/guides/interactions.md` — auto-generated mirror (via sync-docs.js, never hand-edited)
- `docs/guides/gestures.md` — auto-generated mirror
- `docs/guides/shapes.md` — auto-generated mirror
- `docs/reference/scene-config.md` — auto-generated mirror
- `docs/reference/composables.md` — auto-generated mirror

## Proposed Change Strategy

Edit all .mdx sources and CHANGELOG in a single implement session, sequenced by dependency
(reference before guides, CHANGELOG last after verifying G4), then run `node scripts/sync-docs.js`
once at the end to regenerate all mirrors in a single pass. This avoids running sync multiple
times (each run rewrites all mirrors; multiple runs on a partially-complete state would produce
intermediate mirror states that fail lychee link checks if companion pages are not yet updated).

The sequence minimizes the chance of a partial-state mirror drift into CI. The alternative
(run sync after each .mdx edit) multiplies the rewrite count without benefit since mirrors are
always regenerated from the full .mdx tree.

CHANGELOG edits are independent of the sync pipeline — CHANGELOG.md is not in sync-docs.js
scope (confirmed: the script reads `site/src/content/docs/**/*.mdx` and writes `docs/**/*.md`;
CHANGELOG.md is in the repo root).

## Step-by-Step Plan

1. **Read dependency slice diffs.** Before writing any prose, read the landed diffs from
   `gesture-coordination` (for E1/C1 contract), `core-math` (for A1 rotation note),
   `compose-contracts` (for Group alpha), and `shape-geometry` (for B1/B2 migration notes).
   Extract: exact disambiguation window value, exact CCW convention wording, Group alpha
   parameter signature, final Octahedron/Knot geometry contract.

2. **Fix E2: add nodeDragState row to scene-config.mdx.** Insert a row for
   `nodeDragState | NodeDragState? | null | State object for the single-node
   tap-to-select-then-drag affordance. See [Drag & Camera how-to](/guides/drag-and-camera/).`
   after the `cameraState` row in the SceneConfig table.

3. **Add Group alpha to composables.mdx.** After `compose-contracts` lands G1, add an `alpha`
   row to the Group parameter table (`| alpha | Float | 1f | Opacity multiplier in 0..1.
   Multiplied against each descendant node's color alpha at render time. |`) and update the
   exclusion note ("Group does not accept `onClick` or `onLongClick` directly…") to remove
   the alpha exclusion.

4. **Fix E1: rewrite onDoubleClick section in interactions.mdx.** Replace lines 169–171 with
   the delayed-disambiguation contract as implemented by `gesture-coordination`. Exact wording
   depends on the landed implementation; structure:
   - When a node has `onDoubleClick`, `onClick` is deferred until the double-tap window
     (~Xms, `ViewConfiguration.doubleTapTimeoutMillis`) expires without a second tap.
   - If a second tap arrives within the window, `onDoubleClick` fires and `onClick` is
     suppressed entirely.
   - When a node has only `onClick` (no `onDoubleClick`), it fires immediately with no delay.

5. **Add consume-only-when-acting note to gestures.mdx.** After the Drag Handling section,
   add a short note or caution block: the scene consumes pointer events only when a gesture
   handler (`GestureConfig.onDrag`/`onTap`), camera, or node-drag state (`nodeDragState`)
   actually processes them. A scene with none of these configured lets parent scrollables
   inherit the gesture unobstructed.

6. **Add rotation-direction note to shapes.mdx.** Under the `rotateZ` subsection, add one or
   two sentences: positive angles rotate counter-clockwise when viewed from the positive axis
   (right-handed convention). This applies to `rotateX`, `rotateY`, and `rotateZ` uniformly
   — all three now share the same CCW convention. Reference commit wording from `core-math`.

7. **Verify G4: check DragEvent.copy() migration note sufficiency.** Compare the existing
   uncommitted Migration Note (compose/drag: DragEvent.x/y semantics) against the API dump
   diff for the `copy()` descriptor change. The ABI break is `copy(DD)DragEvent` →
   `copy(DDLDragDelta;)DragEvent`. If the existing note only covers the semantic change (x/y
   now absolute) without mentioning the binary descriptor break and what callers using
   `dragEvent.copy(x = newX)` need to do, amend it.

8. **Fix E3: add three missing Features entries to CHANGELOG Unreleased.** Under Features:
   - `Add NodeDragState, rememberNodeDragState, NodeDragBounds, and SceneConfig.nodeDragState
     for single-node tap-to-select-then-drag affordance`
   - `Add per-node onDoubleClick handler for double-tap gestures`
   - `Add GestureConfig.longPressTimeoutMs for configurable long-press timeout (default 500ms)`

9. **Fix E3: add Migration subsection with per-breaking-fix before/after.** After the Features
   section, restructure Migration Notes into a Migration subsection with one entry per break:

   **A1 — rotateX/rotateY direction (BREAKING)**
   - Before: positive angle rotated clockwise (inverse convention)
   - After: positive angle rotates counter-clockwise (right-handed, matching rotateZ)
   - Migration: negate any positive angle passed to `rotateX`/`rotateY` to preserve the
     old visual output: `shape.rotateX(origin, -angle)`

   **B1 — Octahedron proportions (BREAKING — visual)**
   - Before: `Octahedron` had non-uniform proportions (XY ~0.707 × Z 1.0)
   - After: inscribed in a unit cube — all axes span [0,1]
   - Migration: re-record Paparazzi goldens; no API change required

   **B2 — Knot positioning (BREAKING — visual)**
   - Before: `Knot(position)` was offset from `position` by a hardcoded cosmetic delta
   - After: `Knot(position)` is geometrically centered at `position`
   - Migration: re-record Paparazzi goldens; if you explicitly compensated for the old
     offset, remove the compensation

   **C1 — tap timing (BREAKING — behavioral)**
   - Before: `onClick` fired immediately on every Release event regardless of `onDoubleClick`
   - After: when both `onClick` and `onDoubleClick` are present, `onClick` is delayed by the
     double-tap window (~Xms); a confirmed double-tap suppresses `onClick` entirely
   - Migration: if your node had both callbacks and you relied on `onClick` firing
     immediately, consider whether the delay is acceptable or split the handlers

10. **Run `node scripts/sync-docs.js`.** From the repo root:
    ```
    node scripts/sync-docs.js
    ```
    Confirm that all five `docs/*.md` mirrors were rewritten. Diff each mirror to confirm
    the changes match the .mdx edits and no hand-edits survived.

11. **Run markdownlint on mirrors.** Per the CI gate in `.github/workflows/ci.yml`:
    all `**/*.md` files are linted. Confirm no new violations from the edits.

12. **Spot-check lychee targets.** The lychee gate checks links in `docs/*.md`. Confirm that
    any new cross-references added in the edits resolve (e.g., the `[Drag & Camera how-to]`
    link in the nodeDragState description points to an existing page).

## Verification Strategy

| AC | Tool / method + ladder rung | Environment need — satisfiable in target env? | What must be BUILT | Fallback chain |
|----|------------------------------|-----------------------------------------------|---------------------|----------------|
| AC-E1: interactions.mdx onDoubleClick describes delayed-disambiguation contract | Human read-through of rendered page vs. landed gesture-coordination diff (manual-review residual rung) | Local `npm run dev` in `site/` — yes, no external deps | No fixture needed; reading the landed diff and the rendered page suffices | n/a — no automated proxy covers prose accuracy |
| AC-E2: scene-config.mdx includes nodeDragState row | Human read-through of rendered reference page vs. SceneConfig.kt source (manual-review residual rung) | Local `npm run dev` in `site/` — yes | No fixture needed | n/a |
| AC-E3: CHANGELOG Unreleased has three Features entries + Migration subsection | Human read-through of CHANGELOG.md against landed commits and API diffs (manual-review residual rung) | CHANGELOG.md in repo — always available | No fixture needed | n/a |
| AC-G4: DragEvent.copy() note covers ABI break | Human comparison of Migration Note text against `isometric-compose/api/isometric-compose.api` diff (manual-review residual rung) | API dump file in repo — yes | No fixture needed | n/a |
| Companion ACs (gestures.mdx consumption, shapes.mdx rotation, composables.mdx Group alpha) | Human read-through against landed behavior in respective slices (manual-review residual rung) | Local `npm run dev` in `site/` — yes | No fixture needed | n/a |
| Mechanics: sync-docs.js run + markdownlint + lychee | `node scripts/sync-docs.js` (automated — rung 3 existing tooling); `markdownlint-cli2` via CI or local; lychee via CI | Node.js in repo, CI pipeline — yes | No fixture needed | Run locally before pushing |

**Constraint-resolution per AC:**
- AC-E1: `constraint-resolution: po-accepted: prose accuracy is always a manual-review residual; no automated proxy can verify that docs describe the correct behavioral contract`
- AC-E2: `constraint-resolution: po-accepted: same as AC-E1; reference table accuracy is human-judged against source`
- AC-E3: `constraint-resolution: po-accepted: CHANGELOG editorial sufficiency is human-judged`
- AC-G4: `constraint-resolution: po-accepted: ABI note sufficiency is human-judged against API dump diff`
- Companions: `constraint-resolution: po-accepted: same manual-review class`

## Test / Verification Plan

### Automated checks

- **sync-docs.js:** `node scripts/sync-docs.js` — confirms all mirrors regenerated; script
  exits non-zero on write failure
- **markdownlint:** run locally via `npx markdownlint-cli2 "docs/**/*.md"` or push and let
  CI gate it — flags any heading/link/style violations introduced by the edits
- **lychee link check:** CI gate; spot-check new internal links (nodeDragState how-to
  reference, rotation note cross-references) before pushing to avoid CI failures

### Interactive verification (human-in-the-loop)

**AC-E1 — interactions.mdx onDoubleClick section**
- Platform & tool: local Astro dev server (`cd site && npm run dev`)
- Steps: open http://localhost:4321/guides/interactions/, navigate to the onDoubleClick section,
  read against the landed gesture-coordination diff
- Pass criteria: no claim that the system automatically disambiguates for you; states the
  delayed-disambiguation window; states that onClick-only nodes remain instant; states that
  double-tap fires onDoubleClick only

**AC-E2 — scene-config.mdx nodeDragState row**
- Platform & tool: local Astro dev server
- Steps: open /reference/scene-config/, find SceneConfig table, confirm nodeDragState row
  present with correct type (NodeDragState?), default (null), and description
- Pass criteria: row present, type matches SceneConfig.kt source signature

**AC-E3 — CHANGELOG Features + Migration subsection**
- Platform & tool: `CHANGELOG.md` in editor/terminal
- Steps: open CHANGELOG.md, confirm Unreleased Features lists all three API names
  (NodeDragState, onDoubleClick, longPressTimeoutMs); confirm Migration subsection has
  before/after for A1, B1, B2, C1
- Pass criteria: all four migration entries present with before/after code or description

**AC-G4 — DragEvent.copy() ABI note**
- Platform & tool: `CHANGELOG.md` + `isometric-compose/api/isometric-compose.api` diff
- Steps: read the Migration Note for DragEvent; compare against the API dump diff for the
  copy() descriptor change; confirm the note covers both the semantic change (x/y now
  absolute) and the binary descriptor change
- Pass criteria: note is sufficient for a developer migrating from master to this branch

**Companions**
- gestures.mdx: local dev server — /guides/gestures/ — confirm consume-only-when-acting note
  present after Drag Handling
- shapes.mdx: /guides/shapes/ — confirm rotation-direction note present under rotateZ
- composables.mdx: /reference/composables/ — confirm Group table has alpha row (only after
  compose-contracts lands)

## Risks / Watchouts

- **Docs describe intent not reality.** Every accuracy AC is judged against *landed* code.
  If a dependency slice's implementation shifted from what the plan described — e.g., the
  disambiguation window ended up 350ms not 300ms, or Group.alpha applies only at the leaf
  level not via context propagation — the doc must follow the code, not the plan. Step 1
  (read dependency diffs first) exists solely to prevent this.
- **Mirror drift.** `docs/*.md` files are generated; any hand-edit is overwritten by the
  next sync-docs.js run. The CI markdownlint gate runs against these mirrors, so a failed
  run could be caused by a stale mirror from a prior hand-edit rather than the current
  .mdx source. Always verify mirrors came from sync-docs.js, not a hand-edit.
- **Uncommitted CHANGELOG hunk.** The working tree has an uncommitted Migration Note
  for DragEvent.x/y. This hunk may be a correct draft or a partial draft from an earlier
  workflow stage. At implement time: stage the full CHANGELOG edit including this hunk;
  do not assume it is final.
- **compose-contracts dependency for Group alpha.** If compose-contracts does not land
  Group.alpha in time, skip composables.mdx Group alpha edit and note the deferral — do not
  add an alpha row that describes unimplemented behavior.

## Dependencies on Other Slices

This slice is explicitly sequenced AFTER all four content slices:

- **gesture-coordination** (must land first): E1 interactions.mdx describes its C1 tap
  contract; gestures.mdx consume-only-when-acting note describes its C2 behavior.
- **core-math** (must land first): shapes.mdx rotation-direction note documents its A1 CCW
  convention; CHANGELOG Migration A1 before/after references its exact direction fix.
- **compose-contracts** (must land first): composables.mdx Group alpha row documents its G1
  alpha propagation implementation.
- **shape-geometry** (must land first): CHANGELOG Migration B1/B2 before/after references
  its Octahedron and Knot geometry changes.

If any dependency slice is not yet landed at implement time, skip its corresponding doc edit
and note the deferral in the implement artifact. Do not write docs that describe planned but
not-yet-landed behavior.

## Assumptions

- `scripts/sync-docs.js` regenerates all `docs/*.md` from `site/src/content/docs/**/*.mdx`
  with no selective filtering — confirmed by reading the script; it processes all .mdx files.
- CHANGELOG.md is NOT in the sync-docs.js scope — confirmed; the script reads/writes
  `site/src/content/docs/` and `docs/` only; CHANGELOG.md is at the repo root.
- The local Astro dev server (`cd site && npm run dev`) is sufficient for rendered-page
  review — no deploy required.
- `node scripts/sync-docs.js` requires Node.js in the shell environment — available per the
  existing `scripts/package.json` and CI setup.
- `compose-contracts` will land Group.alpha before this slice implements; if not, the Group
  table edit is deferred.

## Blockers

None at plan time. All content gaps are solvable text edits; no new tooling, no new schema,
no PO decisions outstanding.

## Freshness Research

**Web research sub-agent: SKIPPED.** This slice meets all three skip criteria:
- Text/copy changes only — no dependency changes, no new API surface, no library usage changes.
The skip is recorded here explicitly per the plan.md rules: "text/copy/i18n changes only"
is one of the three documented skip conditions, and this slice is purely doc prose edits.

## Revision History

*(appended by review-and-fix mode)*

## Recommended Next Stage

- **Option A (default):** `/wf implement full-codebase-audit-fixes docs-and-changelog` — plan
  is complete and all dependencies are named; implement once the four content slices have landed.
  Consider running `/compact` first — planning research is noise for implementation.
- **Option B:** Wait for sibling slices — if core-math, gesture-coordination, compose-contracts,
  or shape-geometry have not yet been implemented, implement those first. This slice's implement
  will fail Step 1 (read dependency diffs) if the slices haven't landed.
