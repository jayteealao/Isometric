---
schema: sdlc/v1
type: review
slug: full-codebase-audit-fixes
review-scope: slug-wide
slice-slug: ""
status: complete
stage-number: 7
created-at: "2026-07-07T19:11:28Z"
updated-at: "2026-07-07T19:15:34Z"
verdict: ship-with-caveats
commands-run: [correctness, security, code-simplification, testing, maintainability, reliability, docs, release]
metric-commands-run: 8
metric-findings-total: 3
metric-findings-raw: 8
metric-findings-blocker: 0
metric-findings-pre-existing: 0
metric-findings-high: 1
metric-findings-med: 0
metric-findings-low: 2
metric-findings-nit: 0
metric-findings-resolved: 0
metric-findings-total-ever: 8
runs:
  - at: "2026-07-07T19:11:28Z"
    dimensions: [correctness, security, code-simplification, testing, maintainability, reliability, docs, release]
    verdict: ship-with-caveats
    fix-commit: "c214af8"
tags: []
refs:
  index: 00-index.md
  shape: 02-shape.md
  slice-index: 03-slice.md
  implements:
    - 05-implement-core-math.md
    - 05-implement-gesture-coordination.md
    - 05-implement-compose-contracts.md
    - 05-implement-view-module.md
    - 05-implement-shape-geometry.md
    - 05-implement-docs-and-changelog.md
    - 05-implement-snapshot-sweep-gate.md
  verifies:
    - 06-verify-core-math.md
    - 06-verify-gesture-coordination.md
    - 06-verify-compose-contracts.md
    - 06-verify-view-module.md
    - 06-verify-shape-geometry.md
    - 06-verify-docs-and-changelog.md
    - 06-verify-snapshot-sweep-gate.md
  sub-reviews:
    - 07-review-correctness.md
    - 07-review-security.md
    - 07-review-code-simplification.md
    - 07-review-testing.md
    - 07-review-maintainability.md
    - 07-review-reliability.md
    - 07-review-docs.md
    - 07-review-release.md
next-command: wf-handoff
next-invocation: "/wf handoff full-codebase-audit-fixes"
---

# Review

## The Review

The branch carries 248 changed files and ~23,600 net new lines across 7 delivery slices: core math
correctness (rotation matrices, 3D segment distance, polygon hit-test interior, depth formula, lightness
clamp, shoelace winding, TileCoordinate hash), a gesture coordination rewrite (double-tap disambiguation,
consumption gating, drag-state reset), shape geometry repairs (Octahedron/Knot positioning, Cylinder
validation), a renderer allocation fix (Paint/Path reuse in AndroidCanvasRenderer), Group alpha
propagation through RenderContext, and the snapshot re-record gate. All 247 JVM tests pass; apiCheck is
clean; Paparazzi verify is green locally.

The correctness fixes are well-substantiated. The rotation matrix inversion (rotateX/rotateY now use
the standard CCW right-handed convention) is the most visible breaking change, and both the math and
the KDoc match the intended contract. The depth formula change (`x + y - z/sin(α)`) reduces to the
legacy `x + y - 2z` at the 30° default, so the snapshot churn at default angle is zero as expected.
The `isPointCloseToPoly` interior-test addition is correctly isolated from internal callers via a private
`isPointCloseToEdges` helper.

One HIGH finding was found and fixed during this review: per-node `onClick` was delayed by the
double-tap disambiguation window even for nodes that had no `onDoubleClick` registered — an
unnecessary ~300ms latency on every tap where disambiguation cannot possibly matter. The fix
(commit c214af8) makes the delay conditional on `onDoubleClick` presence and corrects the
CHANGELOG migration note that had stated the delay applied universally.

One remaining HIGH finding (CR-2, projectionVersion polling busy-loop) is deferred as an
architectural concern that requires a Flow/StateFlow change to IsometricEngine. It is a performance
issue on long-lived scenes but not a correctness problem and does not block shipping the correctness
sweep. Two LOW findings (dead code helper, vacuous drag test) and one NIT (magic constant) are
deferred for a follow-up pass.

## Verdict

**Ship with caveats**

All 28 acceptance criteria from the shape spec are met (verified or plan-pre-accepted). No
BLOCKER or correctness-class OPEN finding remains. The one HIGH finding (projectionVersion polling)
is a CPU efficiency issue that affects long-lived interactive scenes but produces no wrong output.
Deferred items are recorded. CI Paparazzi verification pending push (plan-pre-authorized deferral).

## Domain Coverage

| Domain | Command | Status |
|--------|---------|--------|
| Correctness | correctness | Issues — CR-1 fixed; CR-3 fixed; CR-2 deferred HIGH |
| Security | security | Clean |
| Simplification | code-simplification | Deferred LOW (CS-1 dead code; CS-2 magic constant) |
| Testing | testing | Deferred LOW (TST-1 vacuous drag test) |
| Maintainability | maintainability | MAIN-1 NIT fixed (workflow vocab in source comments) |
| Reliability | reliability | Deferred HIGH (CR-2 polling loop) |
| Docs | docs | Deferred LOW (DOCS-1 missing NodeDragState guide) |
| Release | release | Clean — CHANGELOG correct, API dumps verified |

## All Findings

ALL findings ever recorded for this scope — open AND closed.

| ID | Sev | Conf | Status | Pre | Surfaced | Source | File:Line | Issue |
|----|-----|------|--------|-----|----------|--------|-----------|-------|
| CR-1 | HIGH | High | fixed | false | 2026-07-07 | correctness | IsometricScene.kt:609 | onClick delayed even when onDoubleClick absent |
| CR-3 | MED | High | fixed | false | 2026-07-07 | correctness | CHANGELOG.md:102 | Migration note incorrectly stated delay applies to onClick-only nodes |
| CR-2 | HIGH | Med | deferred | false | 2026-07-07 | reliability | IsometricScene.kt:148 | projectionVersion polling busy-loop: 16ms tick runs indefinitely on idle scenes |
| CS-1 | LOW | Med | deferred | false | 2026-07-07 | code-simplification | IsometricNode.kt:190 | applyAlpha() helper defined but never called (dead code) |
| MAIN-1 | NIT | Low | fixed | false | 2026-07-07 | maintainability | IsometricView.kt:29 | Workflow vocabulary in source code comments |
| TST-1 | LOW | Med | deferred | false | 2026-07-07 | testing | DoubleTapDisambiguationTest.kt:170 | Drag-within-window test has no assertion exercising the actual drag path |
| CS-2 | NIT | Low | deferred | false | 2026-07-07 | code-simplification | IsometricScene.kt:148 | Magic constant 16L should be a named constant |
| DOCS-1 | LOW | Low | deferred | false | 2026-07-07 | docs | CHANGELOG.md:22 | NodeDragState/rememberNodeDragState feature lacks an end-to-end how-to guide |

**Open:** BLOCKER: 0 | HIGH: 1 (deferred) | MED: 0 | LOW: 2 (deferred) | NIT: 1 (deferred)
**Pre-existing:** 0
**Closed:** fixed: 3 | dismissed: 0
**Ledger size (ever):** 8
*(This run: 8 net-new, 0 re-confirmed, 0 resolved; fix loop patched 3 of 3 selected; 4 open deferred)*

## Findings (Detailed)

### CR-1: onClick delayed even when onDoubleClick absent [HIGH — FIXED]

**Location:** `isometric-compose/src/main/kotlin/.../IsometricScene.kt:609`
**Source:** correctness

**Evidence (before fix):**
```kotlin
pendingTapJob = longPressScope.launch {
    delay(doubleTapWindowMs)   // unconditional ~300ms wait
    capturedHitNode?.onClick?.invoke()
```

**Issue:** Per-node `onClick` was always delayed by the double-tap window (~300ms) regardless
of whether `onDoubleClick` was registered on the hit node. For nodes with only `onClick`,
no second tap could ever arrive to override the outcome, so the delay was pure latency with
no functional benefit.

**Fix applied (c214af8):** Check `capturedHitNode?.onDoubleClick` before launching the delayed
job. If null, invoke `onClick` immediately; if non-null, delay as before.

**Severity:** HIGH | **Confidence:** High | **Pre-existing:** false
**Status:** fixed | **Surfaced:** 2026-07-07T19:11:28Z | **Fixed:** 2026-07-07T19:15:34Z

---

### CR-3: CHANGELOG migration note incorrect about onClick-only delay [MED — FIXED]

**Location:** `CHANGELOG.md:102`
**Source:** correctness

**Evidence (before fix):**
```
Nodes with only `onClick` (no `onDoubleClick`) are also subject to the delay — the scene
cannot determine at first-tap time whether a second tap is arriving.
```

**Issue:** This statement was factually inaccurate after the CR-1 fix. It also contradicted
the shape spec, which explicitly states onClick-only nodes should fire with no added latency.

**Fix applied (c214af8):** Updated the migration note to state that onClick-only nodes fire
immediately; the delay only applies when `onDoubleClick` is also registered.

**Severity:** MED | **Confidence:** High | **Pre-existing:** false
**Status:** fixed | **Surfaced:** 2026-07-07T19:11:28Z | **Fixed:** 2026-07-07T19:15:34Z

---

### CR-2: projectionVersion polling busy-loop [HIGH — DEFERRED]

**Location:** `isometric-compose/src/main/kotlin/.../IsometricScene.kt:148–157`
**Source:** reliability

**Evidence:**
```kotlin
LaunchedEffect(engine) {
    var lastVersion = engine.projectionVersion
    while (true) {
        delay(16L)  // ~60 checks/second, runs forever on idle scenes
        val current = engine.projectionVersion
        if (current != lastVersion) { sceneVersion++ }
    }
}
```

**Issue:** The projection-version bridge polls every 16ms regardless of whether the engine
angle/scale changes. On a long-lived scene with a static engine configuration, this loop
runs indefinitely at ~60 polls/second, burning CPU for no redraws. This is architecturally
similar to a JavaScript `setInterval` that runs when the tab is hidden.

**Suggested fix:** Expose a `Flow<Int>` or `StateFlow<Int>` from `IsometricEngine` for
`projectionVersion`, or use `snapshotFlow { engine.projectionVersion }` if the field becomes
Compose-observable. This transforms the polling into a reactive subscription with zero idle
cost.

**Severity:** HIGH | **Confidence:** Med | **Pre-existing:** false
**Status:** deferred | **Surfaced:** 2026-07-07T19:11:28Z

---

### CS-1: applyAlpha() helper defined but never called [LOW — DEFERRED]

**Location:** `isometric-compose/src/main/kotlin/.../IsometricNode.kt:190`
**Source:** code-simplification

**Evidence:**
```kotlin
protected fun applyAlpha(color: IsoColor): IsoColor =
    if (alpha < 1f) color.withAlpha(alpha) else color
```

**Issue:** All three leaf nodes (ShapeNode, PathNode, BatchNode) apply alpha inline via
`color.withAlpha(effectiveAlpha)`. The helper is dead code. It would be useful if callers
migrated to it, but currently it creates misleading dual-path expectations.

**Severity:** LOW | **Confidence:** Med | **Pre-existing:** false
**Status:** deferred | **Surfaced:** 2026-07-07T19:11:28Z

---

### TST-1: Drag-within-window test has vacuous assertion [LOW — DEFERRED]

**Location:** `isometric-compose/src/test/.../DoubleTapDisambiguationTest.kt:170`
**Source:** testing

**Evidence:**
```kotlin
@Test
fun `drag within double-tap window does not fire either callback`() {
    var singleTaps = 0
    var doubleTaps = 0
    // ... no callbacks fire, no assertion that exercises the drag path
    assertThat(singleTaps).isEqualTo(0)
    assertThat(doubleTaps).isEqualTo(0)
}
```

**Issue:** The test asserts two counters start at zero, which is trivially true before any
code runs. No actual gesture code is exercised; the test cannot fail even if the drag path
accidentally dispatched callbacks.

**Severity:** LOW | **Confidence:** Med | **Pre-existing:** false
**Status:** deferred | **Surfaced:** 2026-07-07T19:11:28Z

---

### MAIN-1: Workflow vocabulary in source code comments [NIT — FIXED]

**Location:** `isometric-android-view/src/main/kotlin/.../IsometricView.kt:29,46`
**Source:** maintainability

**Issue:** Two inline comments contained process-internal vocabulary ("sdlc-debt:", "upgrade
path:") that is intended for internal planning artifacts, not published library source.

**Fix applied (c214af8):** Replaced with product-language equivalents.

**Severity:** NIT | **Confidence:** Low | **Pre-existing:** false
**Status:** fixed | **Surfaced:** 2026-07-07T19:11:28Z | **Fixed:** 2026-07-07T19:15:34Z

---

### CS-2: Magic constant 16L lacks documentation [NIT — DEFERRED]

**Location:** `isometric-compose/src/main/kotlin/.../IsometricScene.kt:148`
**Source:** code-simplification

**Issue:** `delay(16L)` approximates one 60fps frame boundary but has no named constant
to document this intent. A reader cannot distinguish it from an arbitrary sleep.

**Severity:** NIT | **Confidence:** Low | **Pre-existing:** false
**Status:** deferred | **Surfaced:** 2026-07-07T19:11:28Z

---

### DOCS-1: NodeDragState feature lacks an end-to-end how-to guide [LOW — DEFERRED]

**Location:** `CHANGELOG.md:22`
**Source:** docs

**Issue:** The new `NodeDragState` / `rememberNodeDragState` / `NodeDragBounds` / `SceneConfig.nodeDragState`
feature is documented in the reference page (`scene-config.mdx`) but has no corresponding how-to
guide walking through the tap-to-select-then-drag interaction pattern end-to-end. Users will discover
the feature by scanning the API reference but have no example of the complete workflow.

**Severity:** LOW | **Confidence:** Low | **Pre-existing:** false
**Status:** deferred | **Surfaced:** 2026-07-07T19:11:28Z

---

## Pre-existing Debt

No pre-existing findings. All findings surfaced on code introduced by this branch.

## Triage Decisions

| ID | Sev | Source | Decision | Notes |
|----|-----|--------|----------|-------|
| CR-1 | HIGH | correctness | fix | Unnecessary ~300ms latency on onClick-only nodes — fixed in c214af8 |
| CR-3 | MED | correctness | fix | CHANGELOG migration note factually incorrect — fixed in c214af8 |
| MAIN-1 | NIT | maintainability | fix | Workflow vocabulary in source comments — fixed in c214af8 |
| CR-2 | HIGH | reliability | defer | Architectural change (Flow/StateFlow) needed; no wrong output; performance debt |
| CS-1 | LOW | code-simplification | defer | Dead helper; harmless but noise — requires callers to migrate |
| TST-1 | LOW | testing | defer | Vacuous drag test; a proper instrumented test is the right fix |
| CS-2 | NIT | code-simplification | defer | Named constant; low risk, follow-up cleanup |
| DOCS-1 | LOW | docs | defer | New feature guide; separate documentation PR |

## Fix Status

| ID | Sev | Source | Status | Fixed-at | Commit | Notes |
|----|-----|--------|--------|----------|--------|-------|
| CR-1 | HIGH | correctness | fixed | 2026-07-07T19:15:34Z | c214af8 | onClick-only nodes now fire immediately |
| CR-3 | MED | correctness | fixed | 2026-07-07T19:15:34Z | c214af8 | CHANGELOG migration note corrected |
| MAIN-1 | NIT | maintainability | fixed | 2026-07-07T19:15:34Z | c214af8 | Workflow vocab removed from IsometricView comments |

## Recommendations

### Must Fix (triaged "fix")

All 3 "fix" decisions were successfully applied in commit c214af8. No remaining must-fix items.

### Should Fix (MED triaged "fix")

None — CR-3 was fixed.

### Deferred (triaged "defer")

1. **CR-2** (HIGH) — projectionVersion polling busy-loop. Route: add a Flow/StateFlow to
   IsometricEngine or use snapshotFlow for reactive subscription. Estimated effort: M (requires
   IsometricEngine API change + LaunchedEffect update).
2. **CS-1** (LOW) — dead `applyAlpha()` helper on IsometricNode. Route: either migrate all
   three leaf node inline usages to the helper, or remove the helper.
3. **TST-1** (LOW) — vacuous drag-within-window test. Route: replace with a test that exercises
   the drag branch in the gesture state machine (or convert to an instrumented test).
4. **CS-2** (NIT) — magic constant 16L. Route: `private const val FRAME_POLL_INTERVAL_MS = 16L`.
5. **DOCS-1** (LOW) — missing NodeDragState how-to guide. Route: new docs PR with the
   tap-to-select-then-drag walkthrough in `guides/drag-and-camera.mdx`.

### Dismissed

None.

### Consider (LOW/NIT — not triaged)

Deferred items CS-2 and DOCS-1 are low-effort and could be addressed in the handoff or a
follow-up cleanup PR.

## Verify Notes from Prior Stages

Friction notes from verify artifacts forwarded per reference requirement:

- **snapshot-sweep-gate (06-verify):** CI drift gate (AC-S2a) — CI ubuntu-latest Paparazzi
  verification has not run; branch is ahead of origin. Clear by pushing and observing the
  build. If drift-only pixels cause CI failure: apply `maxPercentDifference = 0.5f` per
  pre-resolved PO decision.
- **snapshot-sweep-gate (06-verify):** Doc screenshot pixel inspection (AC-S2b) deferred per
  po-accepted constraint-resolution. Geometry correctness proven constructively by
  OctahedronGeometryTest 6/6 and KnotGeometryTest 5/5. This review's read-through confirms
  the shape description in interactions.mdx, scene-config.mdx, and composables.mdx is accurate.
- **docs-and-changelog (06-verify):** Rendered-page visual format deferred; prose accuracy
  confirmed by this review's source read-through (all five mdx files match landed code).

## Recommended Next Stage

- **Option A (recommended):** `/wf handoff full-codebase-audit-fixes` — verdict is
  `ship-with-caveats` with zero OPEN BLOCKER findings, all 7 slices complete, and the
  only open HIGH (CR-2 polling) is a performance concern not a correctness defect. PR
  the branch.
- **Option B:** `/wf review full-codebase-audit-fixes` (accumulating re-run) — if the
  CI drift gate surfaces unexpected Paparazzi failures after push, re-invoke to merge
  fresh findings.
- **Option F:** `/wf intake full-codebase-audit-fixes from-review` — if CR-2's polling
  fix or the NodeDragState how-to guide are in-scope for this PR, extend rather than
  defer. The signal for CR-2 is missing new capability (Flow on IsometricEngine) not
  broken code.
