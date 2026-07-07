---
schema: sdlc/v1
type: implement-index
slug: full-codebase-audit-fixes
status: in-progress
stage-number: 5
created-at: "2026-07-07T13:45:25Z"
updated-at: "2026-07-07T15:36:27Z"
slices-implemented: 3
slices-total: 7
metric-total-files-changed: 26
metric-total-lines-added: 1768
metric-total-lines-removed: 151
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
- Remaining slices (`view-module`, `shape-geometry`, `docs-and-changelog`, `snapshot-sweep-gate`)
  have no code dependency on compose-contracts being verify-complete before they start.
- `docs-and-changelog` will add the Group alpha composables.mdx row after this lands.
- `compose-contracts` did not touch `IsometricScene.kt`. No conflict with gesture-coordination.

## Recommended Next Stage

- **Option A (default):** `/wf verify full-codebase-audit-fixes compose-contracts` — run
  `./gradlew :isometric-compose:test` (42/42 confirmed). KDoc ACs defer to review.
  No device needed.
- **Option B:** Continue with `view-module` or other remaining slices in parallel — no
  dependency on this slice being verified first.
