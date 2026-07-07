---
schema: sdlc/v1
type: po-answers
slug: full-codebase-audit-fixes
created-at: "2026-07-06T23:57:04Z"
updated-at: "2026-07-06T23:57:04Z"
---

# Product Owner Answers — cumulative log

## 2026-07-06 — Stage 1 (intake) — routing decision

**Q: How should the 29 confirmed + 4 judgment-call audit findings enter the lifecycle?**
(options: new fix workflow / attach to fresh-eyes-review-fixes / plain full intake)

**A: Plain full intake.** The PO chose the canonical 10-stage lifecycle over the compressed
fix mode and over attaching a slice to the review-complete `fresh-eyes-review-fixes` workflow.

## 2026-07-06 — Stage 1 (intake) — Batch A (structured)

**Q1 Branch strategy:** Shared — commit on `feat/ws10-interaction-props` alongside the pending
WS10 work; no separate PR. (Dedicated was recommended; PO chose shared.)

**Q2 Appetite:** Large — full sweep of all 29 confirmed findings plus the 4 judgment calls,
sliced for incremental delivery over multiple days.

**Q3 Review scope:** Slug-wide — one review artifact for the whole workflow against the
cumulative branch diff. (Per-slice was recommended; PO chose slug-wide.)

## 2026-07-07 — Stage 1 (intake) — Batch B (freeform)

PO answered a blanket **"yes"** to all four questions:

**Q1 Success criteria:** Accepted as proposed — every one of the 29 confirmed findings is either
fixed with a regression test (behavioral bugs) or explicitly waived with a recorded reason;
docs/CHANGELOG brought back in sync; API dumps regenerated where signatures change.

**Q2 Breaking-change posture:** Confirmed — direct breaking changes for all three
behavior-changing fixes (rotateX/rotateY direction flip, Octahedron geometry correction,
Knot offset removal). No deprecation cycles. Paparazzi snapshot churn accepted.

**Q3 Judgment calls:** All four split-verdict items are IN scope (GroupNode alpha
KDoc-vs-docs contradiction, IsometricView ACTION_UP return value, documenting that
callback-only AdvancedSceneConfig changes don't re-register, DragEvent ABI-break
changelog note verification).

**Q4 Stack confirmation:** Confirmed as detected — Android/JVM Kotlin library, Compose +
View wrapper, Gradle, JUnit + Paparazzi, lazylogcat, binary-compatibility-validator,
Maven publishing; session tooling android-cli, lazylogcat, prepare-pr, release, gh-stack.
Nothing missing or off-limits.

## 2026-07-07 — Stage 2 (shape) — discovery interview (20 questions, 5 rounds)

### Round 1 — core contracts
- **C1 tap contract:** Delay onClick only when the node also has onDoubleClick (~300ms window
  via platform disambiguation); instant onClick for onClick-only nodes.
- **A1 rotation:** Flip rotateX/rotateY to CCW-for-positive matching rotateZ; document the
  right-handed CCW convention in KDoc on all three. Breaking, accepted.
- **A5 normalize:** Keep zero-vector return; fix KDoc to state it. Non-breaking.
- **A3 isPointCloseToPoly:** Make code match KDoc — add the inside-test to the public function.

### Round 2 — behavior dynamics
- **C2 consumption:** Consume pointer events only when a handler/drag-state/camera actually
  acts; inert scenes let parents (scrollables) receive events.
- **A2 segment distance:** Fix to full 3D (3D dot product + z in closest-point reconstruction).
- **F1/F2 diagnostic tests:** Convert to asserting tests (keep scenarios, add existence +
  outcome assertions).
- **A6 cullPath:** Full shoelace formula over all vertices.

### Round 3 — surfaces
- **Snapshots:** One deliberate re-record pass at sweep end + visual inspection + single
  snapshot commit.
- **G1 Group alpha:** Implement alpha propagation through GroupNode's child render context.
- **E3 changelog:** Full — missing Features entries AND per-breaking-fix Migration subsection
  with before/after.
- **D1 Paint:** Single reused Paint instance mutated per command.

### Round 4 — failure modes
- **A3 internals:** Internal callers (hasIntersection, hasInteriorIntersection, HitTester)
  switch to a private edge-only helper; only the public function gains the inside-test.
- **Versioning:** Pre-1.0 posture — breaking fixes in next minor with detailed CHANGELOG
  migration entries; no major bump, no deprecation cycles.
- **E4 annotations:** Verify actual annotations at plan time, then annotate honestly
  (@Stable where mutability demands); apiDump if anything shifts.
- **Verify depth:** CI parity (./gradlew test + apiCheck) for all fixes; additionally run the
  existing instrumented androidTest suite on an emulator for the gesture changes (C1/C2/C3).

### Round 5 — boundaries (scope restraint)
- **A7 + F4 trim candidates:** Include both (cheap; rigor sweep).
- **B3 Cylinder:** Fix validation order so Cylinder's own require messages fire.
- **G4 DragEvent ABI:** Verify-only — confirm the existing CHANGELOG migration note suffices.
- **C1 latitude:** Restructuring the two pointerInput blocks into one unified gesture loop is
  ALLOWED if it is the cleaner correct fix; gesture tests + instrumented suite gate the risk.

## 2026-07-07T11:54:55Z — Stage 3 (slice) — slicing-strategy interview (4 questions, 1 round)

- **Granularity:** Zone-aligned (~5–7 slices). One slice per coherent zone; each independently
  verifiable, mapping to 1–3 clean commits on the shared branch.
- **First slice:** Core math first — foundation everything renders through; the F1/F2
  DepthSorter test conversions become the regression net for the A6 culling change.
- **Sweep gates:** Distribute + final gate slice — the instrumented emulator suite (AC-S3)
  runs inside the gesture slice's verify; a dedicated final slice owns the Paparazzi
  re-record (AC-S2), doc-screenshot regen, and closing test+apiCheck (AC-S1).
- **Docs slicing:** Hybrid — KDoc edits travel in the slice that fixes the code (same file,
  same commit); .mdx guides, reference tables, CHANGELOG + migrations, and the
  sync-docs.js run form a dedicated final docs slice.

## 2026-07-07T12:46:38Z — Stage 4 (plan) — cross-cutting discovery (5 questions, 2 rounds)

- **G1 alpha=0 semantics:** Skip children — early return in `GroupNode.renderTo` when
  `alpha == 0f`. Matches `isVisible=false` and Compose's `graphicsLayer(alpha=0f)` idiom;
  zero traversal cost; callers wanting present-but-invisible use `alpha = 0.001f`.
- **Long-press integration:** Keep the hand-rolled long-press coroutine honoring the
  configurable `GestureConfig.longPressTimeoutMs` (already-shipped public API); do NOT
  switch to `detectTapGestures(onLongPress)`'s fixed platform timeout.
- **Double-tap test harness:** JVM `DoubleTapDisambiguationTest` stays state-machine-only
  (matches the module's existing harness philosophy); real pointer-event routing is proven
  in the instrumented androidTest suite (the AC-S3 emulator gate). No Robolectric added
  to isometric-compose.
- **Paparazzi drift mitigation (pre-staged, executes only if CI verify fails on drift-only
  pixels post-record):** set `Paparazzi(maxPercentDifference = 0.5f)` via a shared constant
  in `IsometricCanvasSnapshotTest`, reason recorded here; NOT re-record-on-Linux.
- **Orphaned working-tree changes** (`GestureConfig.kt`, `GestureEvents.kt`,
  `IsometricRenderer.kt`, `DragEventClarityTest.kt` — binary-compat leftovers from the
  fresh-eyes-review-fixes workflow): **compose-contracts adopts them** — committed there
  as one coherent "land pre-existing contract fixes" step alongside the
  `AdvancedSceneConfig` secondary constructor it already claims.
