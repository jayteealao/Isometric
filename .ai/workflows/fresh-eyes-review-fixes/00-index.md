---
schema: sdlc/v1
type: index
slug: fresh-eyes-review-fixes
title: "Fix the defects surfaced by the 2026-07-06 fresh-eyes codebase review"
status: active
current-stage: implement
stage-number: 5
created-at: "2026-07-06T07:47:41Z"
updated-at: "2026-07-06T14:07:20Z"
selected-slice: "docs-housekeeping"
branch-strategy: shared
branch: "feat/ws10-interaction-props"
base-branch: "master"
review-scope: slug-wide
pr-url: ""
pr-number: 0
open-questions:
  - "H3/L3 gate: RESOLVED — derivation in plan artifact, formula x+y-z/sin(α) correct, zero default-angle churn confirmed by Paparazzi"
  - "M2 camera-aware culling mechanism: RESOLVED — culling runs pre-camera; camera pan is a post-projection canvas transform; documented and tested"
  - "L6: RESOLVED — rotationOrigin does NOT inherit from parent; call-site investigation confirms no existing caller relies on inheritance; RenderContextTest added (AC-23)"
  - "View test infra: Robolectric vs instrumented for the greenfield isometric-android-view suite (plan decision; mind the documented Paparazzi/Robolectric conflict pattern) — owned by the view-module slice plan"
  - "A2: RESOLVED — projectionVersion becomes abstract val; breaking change accepted; API dumps to be regenerated at implement time"
tags: [review-findings, isometric-core, isometric-compose, isometric-android-view, depth-sorting, interaction]
stack:
  detected-at: "2026-07-06T07:47:41Z"
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
  available-mcp: []
  user-confirmed: true
recommended-next-stage: verify
next-command: wf-verify
next-invocation: "/wf verify fresh-eyes-review-fixes docs-housekeeping"
workflow-files:
  - 00-index.md
  - 01-intake.md
  - po-answers.md
  - 02-shape.md
  - 02-shape.01-fix-decision-map.html.fragment
  - 03-slice.md
  - 03-slice.01-slice-map.html.fragment
  - 03-slice-depth-correctness.md
  - 03-slice-interaction-api-honesty.md
  - 03-slice-view-module.md
  - 03-slice-docs-housekeeping.md
  - 04-plan.md
  - 04-plan-depth-correctness.md
  - 04-plan-depth-correctness.yaml
  - 04-plan-depth-correctness.html.fragment
  - 04-plan-interaction-api-honesty.md
  - 04-plan-interaction-api-honesty.yaml
  - 04-plan-interaction-api-honesty.html.fragment
  - 04-plan-view-module.md
  - 04-plan-view-module.yaml
  - 04-plan-view-module.html.fragment
  - 04-plan-docs-housekeeping.md
  - 04-plan-docs-housekeeping.yaml
  - 04-plan-docs-housekeeping.html.fragment
  - 05-implement.md
  - 05-implement-depth-correctness.md
  - 05-implement-interaction-api-honesty.md
  - 05-implement-view-module.md
  - 05-implement-docs-housekeeping.md
  - 06-verify.md
  - 06-verify-depth-correctness.md
  - 06-verify-interaction-api-honesty.md
  - 06-verify-view-module.md
progress:
  intake: complete
  shape: complete
  slice: complete
  plan: complete
  implement: complete
  verify: not-started
  review: not-started
  handoff: not-started
  ship: not-started
  retro: not-started
---
