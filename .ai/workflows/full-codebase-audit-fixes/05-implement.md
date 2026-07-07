---
schema: sdlc/v1
type: implement-index
slug: full-codebase-audit-fixes
status: in-progress
stage-number: 5
created-at: "2026-07-07T13:45:25Z"
updated-at: "2026-07-07T16:40:52Z"
slices-implemented: 6
slices-total: 7
metric-total-files-changed: 46
metric-total-lines-added: 2050
metric-total-lines-removed: 177
tags: []
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf verify full-codebase-audit-fixes compose-contracts"
---

# Implement Index

## Cross-Slice Integration Notes

- `core-math` slice (1/7) is complete. All nine audit findings (A1a, A1b, A2, A3, A4, A5,
  A7, F1, F2) are fixed with regression tests. The rotation matrices, the edge-only helper
  extraction, the lightness clamp, and the hashCode are all committed (SHA: 36afbbd).
- `gesture-coordination` slice (2/7) is complete. C1/C2/C3/F4 are all fixed. One merged
  `pointerInput(Unit)` block replaces the two sibling blocks. Four new JVM test files,
  one new instrumented test file (AC-S3 gate). All JVM tests pass; instrumented suite
  compiles and is ready for AVD run. Committed (SHA: dc11217).
- `compose-contracts` slice (3/7) is complete. G1 (GroupNode alpha propagation), G3
  (AdvancedSceneConfig callback KDoc), F3 (exact-value alpha test), E4 (secondary
  constructor + annotation honesty) are all implemented. Three new GroupNode alpha tests,
  F3 exact-value pin, apiDump regenerated, 42/42 JVM tests pass. Committed in this session
  (SHA recorded in 05-implement-compose-contracts.md after commit).
- `view-module` slice (4/7) is complete. D2 KDoc fix applied in
  `InteractionSamplesActivity.kt`. D1/D3/G2 confirmed already in place (38c77e1); 16
  Robolectric tests pass (0 failures). AC-D2 prose check deferred to review stage.
- `shape-geometry` slice (5/7) is complete. B1 (Octahedron non-uniform scale), B2 (Knot
  cosmetic offset), B3 (Cylinder validation reachability) all fixed. Three new test classes
  (OctahedronGeometryTest, KnotGeometryTest, CylinderValidationTest) — 247/247 JVM tests
  pass. apiDump regenerated (Cylinder$Companion additive ABI). AC-B1/B2 visual halves
  pre-registered as deferred to snapshot-sweep-gate.
- Remaining slices (`docs-and-changelog`, `snapshot-sweep-gate`)
  have no code dependency on shape-geometry being verify-complete before they start.
- `docs-and-changelog` slice (6/7) is complete. E1 (interactions.mdx onDoubleClick rewrite),
  E2 (scene-config.mdx nodeDragState row), E3 (CHANGELOG three Features + four Migration
  entries), G4 (DragEvent ABI callout), and companion updates (gestures.mdx, shapes.mdx,
  composables.mdx) are all in place. sync-docs.js run and all 33 mirrors regenerated. All
  prose ACs are manual-review residual deferrable to review stage.
- `compose-contracts` did not touch `IsometricScene.kt`. No conflict with gesture-coordination.

## Recommended Next Stage

- **Option A (default):** `/wf verify full-codebase-audit-fixes docs-and-changelog` — mechanics
  AC (sync-docs.js) passes; prose ACs are manual-review residual deferrable to review stage.
- **Option B:** Continue with `snapshot-sweep-gate` — only remaining slice; no dependency on
  docs-and-changelog being verified first.
