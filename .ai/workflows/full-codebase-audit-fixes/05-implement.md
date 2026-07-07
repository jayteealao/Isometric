---
schema: sdlc/v1
type: implement-index
slug: full-codebase-audit-fixes
status: in-progress
stage-number: 5
created-at: "2026-07-07T13:45:25Z"
updated-at: "2026-07-07T14:15:38Z"
slices-implemented: 2
slices-total: 7
metric-total-files-changed: 17
metric-total-lines-added: 1499
metric-total-lines-removed: 129
tags: []
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf verify full-codebase-audit-fixes gesture-coordination"
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
- Remaining slices (`compose-contracts`, `view-module`, `shape-geometry`, `docs-and-changelog`,
  `snapshot-sweep-gate`) have no code dependency on gesture-coordination being verify-complete
  before they start.
- `docs-and-changelog` (E-zone) will describe the new tap/double-tap contract delivered by
  this slice. It depends on gesture-coordination being implemented (now true).
- `compose-contracts` does not touch `IsometricScene.kt` (only `GestureConfig.kt`,
  `GestureEvents.kt`, `AdvancedSceneConfig.kt`). No conflict with gesture-coordination.

## Recommended Next Stage

- **Option A (default):** `/wf verify full-codebase-audit-fixes gesture-coordination` — run
  `./gradlew :isometric-compose:test` for the JVM gate, then attempt
  `connectedDebugAndroidTest` for the AC-S3 emulator gate. JVM: BUILD SUCCESSFUL (confirmed
  in implement session). Instrumented: requires AVD boot (AC-S3 deferral in place).
- **Option B:** Continue with `compose-contracts` or other remaining slices in parallel
  while verify runs on `gesture-coordination` — those slices have no dependency on this one.
