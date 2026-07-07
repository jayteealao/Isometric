---
schema: sdlc/v1
type: verify
slug: full-codebase-audit-fixes
slice-slug: core-math
status: complete
stage-number: 6
created-at: "2026-07-07T13:58:52Z"
updated-at: "2026-07-07T13:58:52Z"
result: partial
metric-checks-run: 5
metric-checks-passed: 5
metric-acceptance-met: 9
metric-acceptance-total: 10
metric-acceptance-user-observable: 1
metric-acceptance-code-only: 9
metric-interactive-checks-run: 0
metric-interactive-checks-passed: 0
metric-issues-found: 0
metric-issues-found-initial: 0
metric-issues-found-final: 0
fix-rounds-run: 0
convergence: not-needed
verify-owned-fix-commit: null
regression-tests-added: 0
constraint-resolution-missing: []
interactive-verification: deferred
interactive-verification-defer-reason: >
  AC-A1b (KDoc CCW prose accuracy) and AC-A5 (normalize KDoc zero-vector prose) require
  human prose judgment — ladder rungs tried: (1) JVM-unit tests pass for the behavioral
  contract (rotateX/Y correct matrices proven by assertEquals in PointTest; normalize
  zero-vector proven by VectorTest); (2) dokka V2 build clean (no KDoc parse errors in
  Point.kt or Vector.kt); (3) source read-through confirms CCW/right-handed text on all
  three rotate functions and zero-vector text on normalize(). Residual: prose correctness
  is irreducibly a human judgment call — the text is present and correct by inspection
  but cannot be machine-asserted beyond parser hygiene. Constraint-resolution: po-accepted
  at plan time (plan §2.4: prose accuracy is human-judged at review stage; dokka build
  clean is the automated gate). This deferral is for a single AC that is prose-only and
  was explicitly pre-accepted in the plan's constraint-resolution — it is not a substantive
  code failure.
adapters-used: [jvm-unit]
bootstrap-failures: []
evidence-dir: ".ai/workflows/full-codebase-audit-fixes/verify-evidence/core-math/"
evidence-run-count: 1
security-scan-result: pass
metric-a11y-violations-new: 0
a11y-result: not-automatable
cross-slice-regressions-found: 0
metric-bundle-size-delta-pct: "skipped — stash non-empty"
ac-staleness-checked: true
ac-stale-count: 0
longitudinal-baseline-compared: "skipped — stash non-empty"
stability-check-flaky-count: 0
adversarial-tests-run: 0
adversarial-tests-failed: 0
failure-mode-probes-run: 0
cross-browser-delta: "none"
web-vitals-lcp-ms: null
web-vitals-cls: null
web-vitals-inp-ms: null
tags: [isometric-core, math, geometry, tests, kdoc]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-core-math.md
  plan: 04-plan-core-math.md
  implement: 05-implement-core-math.md
  review: 07-review-core-math.md
  adapters: skills/wf/reference/runtime-adapters.md
next-command: wf-review
next-invocation: "/wf review full-codebase-audit-fixes core-math"
---

# Verify: Core Math & Geometry

## The Verification

Nine audit findings in the oldest code in the library — and all nine closed. The JVM test
suite is the right tool for this slice because every AC is a pure-function assertion: rotation
matrices, distance calculations, polygon intersection, color clamping, hash distribution. No
emulator, no device, no interactive surface is needed or relevant here. The adapter choice is
JVM-unit throughout, and the evidence is direct: 109 tests across the 7 slice-affected test
classes, 0 failures.

The one honest note: AC-A1b (KDoc CCW prose) is marked partial because prose accuracy cannot
be machine-asserted beyond parser hygiene. The dokka V2 build is clean, the CCW/right-handed
text is present on all three rotate functions by source inspection, and the plan explicitly
pre-accepted this as a manual-review residual. It is not a code defect — the text is correct.
This yields `result: partial` with a recorded deferral, not `result: fail`.

The pre-existing `screenToWorld throws for near-degenerate angle` failure in
`IsometricEngineProjectionTest` appears in the full suite run but is out of scope: it predates
this slice's commit, touches no file this slice modified, and was documented in the implement
record as a known pre-existing failure to be addressed by the slice covering
`IsometricEngine` projector behavior.

## Verification Summary

All 9 code-only AC are fully met by JVM unit assertions. 1 user-observable AC (AC-A1b KDoc
prose) is deferred per plan-authored constraint-resolution. No issues were introduced by this
slice. The fix loop did not run (metric-issues-found-initial = 0).

## Automated Checks Run

- `./gradlew :isometric-core:test` (targeted 6 core-math classes): **pass** — 95 tests, 0 failures, 1 pre-existing skip (DepthSorterTest skipped test not related to this slice's assertions)
- `./gradlew :isometric-core:test --tests PathTest` (AC-A6 coverage): **pass** — 14 tests, 0 failures
- `./gradlew :isometric-core:build -x test`: **pass** — compiles cleanly, no build warnings beyond pre-existing Gradle 9.0 deprecation notice
- `./gradlew :isometric-core:apiCheck`: **pass** — binary compatibility validator confirms no unintended API surface change from the `hashCode` fix (implementation-only change; not in .api dump)
- `./gradlew :isometric-core:dokkaGeneratePublicationHtml`: **pass** — KDoc parses cleanly on core-math files; pre-existing cross-module link warnings in StackAxis.kt / TileGridConfig.kt are not introduced by this slice

**Full suite note:** Running the full `isometric-core:test` suite shows 232 tests, 1 failure (`screenToWorld throws for near-degenerate angle`). That failure is pre-existing, predates commit 36afbbd, and touches no file in this slice's file list.

**Security scan:** Diff inspected for secrets/API keys/passwords — none found. No `sdlc-debt:` markers in the diff. No new CVEs introduced (no new dependencies; `java.util.Objects` is JDK stdlib).

**Bundle size delta:** Skipped — stash list non-empty (`perf/broad-phase-sort` and `performance-investigation` stashes present). Absolute artifact size not a concern for a JVM-only math library patch.

## Interactive Verification Results

Automated only — all AC in this slice are `code-only` by the explicit `<!-- observable: false -->` annotations in `03-slice-core-math.md`, with the exception of AC-A1b (KDoc CCW prose), which carries `<!-- observable: true -->` and is deferred per plan-authored `constraint-resolution: po-accepted`. No interactive adapter was needed or bootstrapped. Sub-agent 3 not dispatched (stack platforms = [android, jvm-library]; no UI surface involved).

## Acceptance Criteria Status

| AC | criterion | kind | status | method | evidence |
|----|-----------|------|--------|--------|----------|
| AC-A1a | `Point(0,1,0).rotateX(ORIGIN,π/2)` = `(0,0,1)`; `Point(0,0,1).rotateY(ORIGIN,π/2)` = `(1,0,0)` (CCW, right-handed); existing rotateZ test passes. New PointTest cases MUST fail pre-fix. | code-only | met | automated (JUnit assertEquals in PointTest) | PointTest: tests=12 failures=0; `rotateX CCW around X axis right-handed convention` + `rotateY CCW around Y axis right-handed convention` present and passing |
| AC-A1b | KDoc on `rotateX`/`rotateY`/`rotateZ` states CCW/right-handed convention for positive angles. | user-observable | deferred (prose judgment) | deferred — dokka build clean + source inspection confirm text is present; human read-through at review | `interactive-verification: deferred`; CCW/right-handed text confirmed in Point.kt lines 150, 153, 172, 175, 194, 197 |
| AC-A2 | Segment `(0,0,0)→(0,0,2)` query `(0,0,1)` → `distanceToSegmentSquared` = 0.0; strengthened test fails pre-fix; degenerate segment returns distance-to-point. | code-only | met | automated (JUnit assertEquals in PointTest) | `distanceToSegmentSquared includes z component` test passes; exact AC-A2 case (vZ/wZ, midpoint = 0.0) present at PointTest line 127 |
| AC-A3 | Point strictly interior to large polygon → `isPointCloseToPoly` = true; all IntersectionUtilsTest + DepthSorterTest pass with internal callers on private edge-only helper. | code-only | met | automated (JUnit in IntersectionUtilsTest + DepthSorterTest) | IntersectionUtilsTest: tests=11 failures=0; `isPointCloseToPoly returns true for strictly interior point far from edges` present; DepthSorterTest: tests=24 failures=0 |
| AC-A4 | `IsoColor(10,10,80).lighten(-0.20,WHITE)` no channel zero-clamped; property over ∈[-1,1]: channels = hslToRgb of coerceIn(0,1); boundaries ±1.0, l=0, l=1 covered. | code-only | met | automated (JUnit property-style in IsoColorTest) | IsoColorTest: tests=16 failures=0; `lighten with negative percentage does not produce zero RGB channels`, `lighten boundary minus one`, `lighten boundary plus one` all present |
| AC-A5 | `normalize()` KDoc states zero-vector return; `Vector(0,0,0).normalize() == Vector(0,0,0)`. | code-only | met | automated (JUnit assertEquals in VectorTest) + source inspection | VectorTest: tests=6 failures=0; `normalize of zero vector returns zero vector` present; KDoc at Vector.kt:46 reads "Returns a zero vector if the magnitude is zero." |
| AC-A6 | Concave polygon whose first-triangle winding disagrees with shoelace → `cullPath` uses shoelace; PathTest CW/CCW/concave cases; Stairs regression; KDoc notes simple polygon contract. | code-only | met | automated (JUnit in PathTest) | PathTest: tests=14 failures=0; `L4 - concave polygon winding uses full shoelace not first 3 vertices` present (PathTest line 248) |
| AC-A7 | `TileCoordinate.hashCode` ≠ 0 for ORIGIN; distributes `(k, k*1_000_003)` patterns; equals/hashCode contract tests pass. | code-only | met | automated (JUnit in TileCoordinateTest) | TileCoordinateTest: tests=26 failures=0; `hashCode of ORIGIN is not zero` (confirms 961) + `hashCode distributes k vs k-times-1_000_003 patterns` present |
| AC-F1 | 6x6 TileGrid diagnostic test asserts expected face presence; fails if any tile top/side face missing. | code-only | met | automated (JUnit assertEquals in DepthSorterTest) | DepthSorterTest line 95: `assertEquals(48, sceneDefault.commands.size, ...)` + `assertEquals(36, topsDefault.size, ...)` + `assertTrue(missing.isEmpty(), ...)`; tests=24 failures=0 |
| AC-F2 | Tower tests assert `findFace(...) >= 0` before ordering assertions — no vacuous pass path. | code-only | met | automated (JUnit assertTrue in DepthSorterTest) | DepthSorterTest lines 259-261: `assertTrue(prism2Top >= 0, ...)`, `assertTrue(prism3Left >= 0, ...)`, `assertTrue(prism3Front >= 0, ...)` present before ordering block |

## Issues Found

None. `metric-issues-found-initial: 0`. No issues triaged; fix loop not needed.

## Augmentation Verification

Not applicable — no `02c-craft.md` and `augmentations:` list is empty in `00-index.md`.

## Security Scan

- **CVE scan:** No new dependencies introduced (only `java.util.Objects` from JDK stdlib, zero additional classpath entries). No tooling available for automated CVE scan; no new transitive dependencies to audit. Result: **skipped** (no new deps).
- **Secret detection:** Grep on diff for API key/secret/password/token/credential patterns in string literals — **none found**. Result: **pass**.
- **SAST:** No semgrep installed. Result: **skipped**.

## Accessibility Gate

Not applicable — this slice modifies only `isometric-core` (JVM library: no UI components, no Compose surfaces, no Android views). `a11y-result: not-automatable` (no UI surface to scan).

## Performance Gate

- **Bundle size delta:** Skipped — stash non-empty (recorded above).
- **Build time delta:** Not measured (build UP-TO-DATE on all compilation tasks).
- **Cold-start delta:** Not applicable (library, not service or CLI).

## Cross-Slice Regression

- **Sibling slices checked:** None — this is the first slice verified.
- **Regressions found:** 0

## Longitudinal Delta

- **Baseline capture:** Skipped — stash non-empty.
- **Interpretation:** No baseline comparison needed; all checks are JVM-only unit assertions with deterministic expected values. Visual delta is not applicable for a math library.

## Friction Notes

None. This slice has no user-facing surface; friction notes are not applicable.

## Free Exploration Notes

No interactive surface to explore. The open-ended exploration step was interpreted as: scan the diff for anything unexpected. Observations:

- The `isPointCloseToEdges` private helper is well-named and the disambiguation at call sites in `hasIntersection` / `hasInteriorIntersection` is explicit and readable.
- `Objects.hash(x, y)` import from `java.util.Objects` is standard JDK; no Kotlin stdlib equivalent ships a multi-field hash that avoids the `31*0+0=0` ORIGIN problem.
- Dokka V2 cross-module link warnings in `StackAxis.kt` / `TileGridConfig.kt` are pre-existing and not within this slice's scope.

## Adversarial Tests

Not applicable — this slice has no interactive surface. The adversarial test set (empty submission, extreme input, rapid repeat, mid-flow interruption, offline) applies to UI surfaces only.

| Test | Result | Finding |
|---|---|---|
| Empty submission | n-a | No form/action surface |
| Max-length input | n-a | No text field surface |
| Double-click / rapid repeat | n-a | No interactive surface |
| Mid-flow interruption | n-a | No flow surface |
| Offline / network failure | n-a | No network dependency |

## Failure Mode Probes

Not applicable — no user-observable AC with runtime behavior.

| Probe | Result | Finding |
|---|---|---|
| Slow response (Fast 3G) | n-a | No network dependency |
| Concurrent session | n-a | Pure functions; no state |
| Session expiry mid-flow | n-a | No session |

## Cross-Browser Delta

Not applicable — JVM library, no browser surface.

## Web Vitals

Not applicable — JVM library.

## Gaps / Unverified Areas

- **AC-A1b (KDoc prose):** Deferred to manual review. The text is present and correct by inspection; prose judgment is the residual. Cleared at review stage.
- **Pre-existing `screenToWorld throws for near-degenerate angle` failure:** Out of scope for this slice; addressed by the slice covering `IsometricEngine` projector behavior.

## Freshness Research

All AC are pure JVM math assertions with no external API or schema dependency. No freshness check needed beyond the plan's confirmed research (Objects.hash, coerceIn, CCW rotation convention — all confirmed in plan § Freshness Research). `ac-staleness-checked: true`, `ac-stale-count: 0`.

## Recommendation

The core-math slice is ready for review. All 9 code-only AC are met by passing JVM unit tests. The single user-observable AC (KDoc prose) is a pre-accepted manual-review residual with a deferral annotation and a clean dokka build. No substantive code failures remain. The fix loop was not needed.

## Recommended Next Stage

- **Option A (recommended):** `/wf review full-codebase-audit-fixes core-math` — all code-only AC met; fix loop not needed; ready for code review. KDoc prose deferral will be cleared during review read-through.
- **Option D:** `/wf handoff full-codebase-audit-fixes core-math` — if review is deemed unnecessary (trivial math fix already reviewed via the audit); only valid when `result: pass` (requires clearing the AC-A1b deferral first via a re-verify after review read-through, or accepting `partial` at handoff).
