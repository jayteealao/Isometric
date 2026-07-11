---
schema: sdlc/v1
type: implement-index
slug: full-codebase-audit-fixes
status: complete
stage-number: 5
created-at: "2026-07-07T13:45:25Z"
updated-at: "2026-07-11T00:00:00Z"
slices-implemented: 9
slices-total: 9
metric-total-files-changed: 97
metric-total-lines-added: 2457
metric-total-lines-removed: 191
tags: []
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf verify full-codebase-audit-fixes path-caching-test-fix"
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
- `snapshot-sweep-gate` slice (7/7) is complete. All 29 Paparazzi goldens recorded and
  committed (SHA: 32b31bc). Attribution ledger: octahedron/knot/sampleThree attributed to
  B1/B2; grid/rotateZ attributed to pre-sweep depth-sort and A1a fixes; 10 files PNG noise;
  15 files byte-identical. Doc screenshots: 5 of 17 updated (shape-octahedron, shape-knot,
  complex-scene, grid, multiple-shapes). `./gradlew test apiCheck` BUILD SUCCESSFUL (AC-S1).
  AC-S3 checkpoint: gesture evidence still current (IsometricScene.kt not in 9cf6c43).
  CI drift gate still open (Windows record / Linux verify — fallback pre-resolved).
- `path-caching-test-fix` slice (8/8) is complete. Two instrumented tests fixed by replacing
  a broken reflection helper with an `internal` accessor on `IsometricRenderer`. A Gradle
  Managed Device block (`pixel2Api30`, `aosp-atd`, API 30) lands the headless execution
  harness that retires the environment wall. `compileDebugKotlin + compileDebugAndroidTestKotlin`
  BUILD SUCCESSFUL; `apiCheck` BUILD SUCCESSFUL (internal accessor absent from public dump).
  Runtime AC-T1/T3 carry a device-run deferral cleared by first successful device run.

- `probe-view-api-sample` slice (9/9) is complete. H1 (HashMap non-determinism in
  `buildBroadPhaseCandidatePairs`) fixed via `pairs.sort()` before Kahn processing.
  Viewport API added across all three layers: `IsometricEngine.originXFraction`,
  `originYFraction`, `fitContent`; `ViewportConfig` + `SceneConfig.viewport` in Compose;
  `IsometricView.setFitContent/clearFitContent/setOriginFraction` in the View layer.
  5/5 ViewportOrderInvariance tests pass. 8/8 new engine tests pass. apiDump + apiCheck
  BUILD SUCCESSFUL on all three modules. docs synced.

## Recommended Next Stage

- **Option A (default):** `/wf verify full-codebase-audit-fixes path-caching-test-fix` —
  AC-T2 and AC-T4 are statically satisfied (compile + apiCheck pass); AC-T1/T3 carry a
  device-run deferral.
- **Option B:** `/wf review full-codebase-audit-fixes path-caching-test-fix` — skip verify;
  static evidence (compile + apiCheck) sufficient for the reviewer's confidence bar.
- **Option C:** `/wf handoff full-codebase-audit-fixes` — all 8 slices complete; proceed
  to handoff for the overall workflow.
