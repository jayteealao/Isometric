---
schema: sdlc/v1
type: index
slug: full-codebase-audit-fixes
title: "Fix the confirmed defects from the 2026-07-06 full-codebase multi-agent audit"
status: active
current-stage: plan
stage-number: 4
created-at: "2026-07-06T23:57:04Z"
updated-at: "2026-07-11T08:39:05Z"
selected-slice: "probe-view-api-sample"
branch-strategy: shared
branch: "feat/ws10-interaction-props"
base-branch: "master"
review-scope: slug-wide
pr-url: ""
pr-number: 0
open-questions: []
tags: [audit-findings, isometric-core, isometric-compose, isometric-android-view, gestures, docs, tests]
stack:
  detected-at: "2026-07-06T23:57:04Z"
  platforms: [android, jvm-library]
  languages: [kotlin]
  ui: [compose, android-view]
  build: [gradle]
  package-managers: [gradle]
  testing: [junit, paparazzi]
  observability: [lazylogcat]
  integrations: [binary-compatibility-validator, maven-publishing]
  available-skills:
    - {name: android-cli, hint: "Android project + SDK orchestration; drive adb through it"}
    - {name: lazylogcat, hint: "Non-interactive logcat capture/filter"}
    - {name: prepare-pr, hint: "Isometric PR readiness pipeline (API dumps, doc mirrors, CI gates)"}
    - {name: release, hint: "Isometric release pipeline to Maven Central"}
    - {name: gh-stack, hint: "Stacked-branch / dependent-PR management"}
  available-mcp: []
  user-confirmed: true
next-command: wf-handoff
next-invocation: "/wf handoff full-codebase-audit-fixes"
workflow-files:
  - 00-index.md
  - 01-intake.md
  - 01-intake.01-findings-map.html.fragment
  - 02-shape.md
  - 02-shape.01-decision-ledger.html.fragment
  - 03-slice.md
  - 03-slice.01-slice-map.html.fragment
  - 03-slice-core-math.md
  - 03-slice-gesture-coordination.md
  - 03-slice-compose-contracts.md
  - 03-slice-view-module.md
  - 03-slice-shape-geometry.md
  - 03-slice-docs-and-changelog.md
  - 03-slice-snapshot-sweep-gate.md
  - 03-slice-path-caching-test-fix.md
  - 04-plan-path-caching-test-fix.md
  - 04-plan-path-caching-test-fix.yaml
  - 04-plan-path-caching-test-fix.html.fragment
  - 03-slice-probe-slug-wide-2026-07-07.md
  - audit-findings.json
  - po-answers.md
  - 04-plan-core-math.yaml
  - 04-plan-core-math.md
  - 04-plan-core-math.html.fragment
  - 04-plan-core-math.01-rotation-convention.html.fragment
  - 04-plan-view-module.yaml
  - 04-plan-view-module.md
  - 04-plan-view-module.html.fragment
  - 04-plan-compose-contracts.yaml
  - 04-plan-compose-contracts.md
  - 04-plan-compose-contracts.html.fragment
  - 04-plan-compose-contracts.01-alpha-tree.html.fragment
  - 04-plan-shape-geometry.yaml
  - 04-plan-shape-geometry.md
  - 04-plan-shape-geometry.html.fragment
  - 04-plan-shape-geometry.01-octahedron-proportions.html.fragment
  - 04-plan-gesture-coordination.yaml
  - 04-plan-gesture-coordination.md
  - 04-plan-gesture-coordination.html.fragment
  - 04-plan-gesture-coordination.01-state-machine.html.fragment
  - 04-plan-docs-and-changelog.yaml
  - 04-plan-docs-and-changelog.md
  - 04-plan-docs-and-changelog.html.fragment
  - 04-plan-snapshot-sweep-gate.yaml
  - 04-plan-snapshot-sweep-gate.md
  - 04-plan-snapshot-sweep-gate.html.fragment
  - 04-plan-snapshot-sweep-gate.01-attribution-flow.html.fragment
  - 04-plan.md
  - 05-implement.md
  - 05-implement-core-math.md
  - 05-implement-gesture-coordination.md
  - 05-implement-compose-contracts.md
  - 06-verify.md
  - 06-verify-core-math.md
  - 06-verify-gesture-coordination.md
  - 06-verify-compose-contracts.md
  - 05-implement-view-module.md
  - 06-verify-view-module.md
  - 05-implement-shape-geometry.md
  - 06-verify-shape-geometry.md
  - 05-implement-docs-and-changelog.md
  - 06-verify-docs-and-changelog.md
  - 05-implement-snapshot-sweep-gate.md
  - 06-verify-snapshot-sweep-gate.md
  - 05-implement-path-caching-test-fix.md
  - 06-verify-path-caching-test-fix.md
  - 07-review.md
  - 07-review.yaml
  - 07-review.html.fragment
  - 03-slice-probe-view-api-sample.md
  - 03-slice-probe-view-api-sample.01-divergence.html.fragment
  - 04-plan-probe-view-api-sample.md
  - 04-plan-probe-view-api-sample.yaml
  - 04-plan-probe-view-api-sample.html.fragment
  - 04-plan-probe-view-api-sample.01-consult.html.fragment
  - 05-implement-probe-view-api-sample.md
compressed-slices:
  - {slug: probe-slug-wide-2026-07-07, slice-type: probe, created-at: "2026-07-07T22:45:00Z"}
  - {slug: probe-view-api-sample, slice-type: probe, created-at: "2026-07-11T00:57:56Z"}
runtime-evidence-deferrals:
  - slice: core-math
    reason: "AC-A1b KDoc CCW prose — prose accuracy is human-judged; dokka V2 build clean; CCW/right-handed text confirmed by source inspection on all three rotate functions. Rungs tried: (1) JVM-unit behavioral proof (rotateX/Y CCW assertEquals pass), (2) dokka V2 build clean, (3) source inspection. Residual is irreducibly human prose judgment. Constraint-resolution: po-accepted at plan time."
    deferred-at: "2026-07-07T13:58:52Z"
    cleared-by: null
  - slice: gesture-coordination
    reason: "AC-S3 live multi-touch routing — DoubleTapInstrumentedTest written and compiles; connectedDebugAndroidTest requires AVD boot via android-cli which was not available in this session. JVM state-machine tests cover all branch conditions, consumption guards, and state-reset assertions. Cleared by first successful connectedDebugAndroidTest run on this machine."
    deferred-at: "2026-07-07T14:15:38Z"
    cleared-by: "2026-07-07T16:00:00Z"
  - slice: compose-contracts
    reason: "AC-G1 docs + AC-G3 KDoc prose accuracy — both consumer-facing prose ACs deferred to review-stage read-through. Rungs tried: (1) KDoc present in source (IsometricNode.kt:119-136 for G1; AdvancedSceneConfig.kt:20 for G3); (2) compileDebugKotlin + compileReleaseKotlin clean; (3) source read-through confirms semantic coverage (leaf vs Group semantics for G1; equals()-exclusion with rememberUpdatedState guidance for G3). Residual: prose quality is irreducibly human judgment. Constraint-resolution: po-accepted at plan time (04-plan-compose-contracts.md verification strategy table)."
    deferred-at: "2026-07-07T15:47:26Z"
    cleared-by: null
  - slice: view-module
    reason: "AC-D2 DragLifecycleSample KDoc prose accuracy — prose accuracy is human-judged. Rungs tried: (1) Source read-through of InteractionSamplesActivity.kt lines 608–617 confirms updated KDoc matches GestureEvents.kt lines 34–39 language (absolute pointer position; drag-start in onDragStart, live pointer in onDrag); (2) Kotlin compiler accepts KDoc without error (compileDebugKotlin + compileReleaseKotlin clean). Residual: prose quality is irreducibly human judgment at review stage. Constraint-resolution: po-accepted at plan time (04-plan-view-module.md verification strategy table, AC-D2 row). repeat-of: compose-contracts (same environment wall: prose accuracy judgment)."
    deferred-at: "2026-07-07T16:04:28Z"
    cleared-by: null
    repeat-of: compose-contracts
  - slice: shape-geometry
    reason: "AC-B1-visual and AC-B2-visual (golden re-record + human diff inspection) — pre-registered PO deferral per 'snapshots once at sweep end' decision (po-answers.md Round 3). Geometric unit tests (vertex span, winding, bounding box) prove correctness; visual surface is deterministic from correct geometry. Rungs tried: (1) OctahedronGeometryTest vertex-span and winding assertions (6 tests pass); (2) KnotGeometryTest position carry-through (5 tests pass); (3) ./gradlew :isometric-compose:test BUILD SUCCESSFUL (local goldens auto-updated as untracked). Cleared by snapshot-sweep-gate slice's recordPaparazziDebug + inspection pass."
    deferred-at: "2026-07-07T16:17:07Z"
    cleared-by: "2026-07-07T18:47:51Z"
    repeat-of: null
  - slice: docs-and-changelog
    reason: "All 5 user-observable ACs (rendered-page screenshot format) — prose accuracy of interactions.mdx (AC-E1 corrected in 03cdec0 after c214af8 changed onClick-only behavior), scene-config.mdx, CHANGELOG.md, gestures.mdx, shapes.mdx, and composables.mdx verified by source read-through against landed code. Rungs tried: (1) Source file read-through for all five mdx files and CHANGELOG.md — all content confirmed correct against IsometricScene.kt:612-614, SceneConfig.kt:50, isometric-compose.api:120, IsometricComposables.kt:132, Point.kt:150-199; (2) sync-docs.js ran clean (Synced 33 files, twice); (3) Astro dev server not available (no display). Residual: rendered-page visual presentation format only — prose accuracy is confirmed by source read-through. Plan pre-accepted: constraint-resolution: po-accepted on all five ACs in 04-plan-docs-and-changelog.md. Cleared by human read-through at review stage."
    deferred-at: "2026-07-07T16:49:09Z"
    cleared-by: null
    repeat-of: compose-contracts
  - slice: snapshot-sweep-gate
    reason: "AC-S2a CI drift gate (Paparazzi Linux verify) — recordPaparazziDebug on Windows JVM produced 29 attributed goldens (commit 32b31bc); ./gradlew test BUILD SUCCESSFUL locally and in re-verify run (2026-07-07T22:00:52Z); attribution ledger: 0 unattributable diffs; Paparazzi goldens unaffected by c214af8. Residual: CI ubuntu-latest verifyPaparazziDebug — branch not yet pushed. Plan pre-authorized: constraint-resolution: proxy+deferral in 04-plan-snapshot-sweep-gate.md. Cleared when feat/ws10-interaction-props pushed and CI build job passes. AC-S2b doc visual inspection — DocScreenshotGenerator ran, 5/17 PNGs changed (geometry-attributed); OctahedronGeometryTest 6/6 + KnotGeometryTest 5/5 prove correctness. Residual: human pixel inspection, no display available. Plan pre-accepted: constraint-resolution: po-accepted. Cleared at review stage."
    deferred-at: "2026-07-07T19:01:23Z"
    cleared-by: null
    repeat-of: null
  - slice: snapshot-sweep-gate
    reason: "AC-S3 instrumented re-run (gesture-touching code changed by c214af8) — IsometricScene.kt modified post-original-verify by c214af8 (onClick-only fast-fire path added). Rungs tried: (1) git show c214af8 diff inspection confirms onDoubleClick != null path unchanged — DoubleTapInstrumentedTest evidence (bbc5fda) valid for that specific path; (2) ./gradlew test BUILD SUCCESSFUL (JVM tests pass); (3) AVD boot not available in this session (same wall as gesture-coordination deferral). Residual: live connectedDebugAndroidTest re-run to confirm double-tap path and exercise new onClick-only path. Plan constraint-resolution for AC-S3 names prerequisite-slice: gesture-coordination verify — evidence stands for double-tap; new path is untested. Constraint-resolution: same proxy+deferral wall as gesture-coordination. Cleared by connectedDebugAndroidTest run in capable environment after c214af8. CLEARED by /wf probe (2026-07-07T23:32Z): connectedDebugAndroidTest green 36/0 on Pixel_9_Pro emulator AND 36/0 on physical SM-F956B (isolated rerun); the double-tap and new onClick-only paths both pass on both device classes after c214af8. First simultaneous-device run showed 4 DoubleTapInstrumentedTest flakes (5s scene-draw wait too tight under contention) that did not reproduce in isolation — a test-robustness residual, not a behavioral failure. Evidence: 03-slice-probe-slug-wide-2026-07-07.md."
    deferred-at: "2026-07-07T22:00:52Z"
    cleared-by: "probe-slug-wide-2026-07-07"
    repeat-of: gesture-coordination
progress:
  intake: complete
  shape: complete
  slice: complete
  plan: complete
  implement: complete
  verify: complete
  review: complete
  handoff: not-started
  ship: not-started
  retro: not-started
---
