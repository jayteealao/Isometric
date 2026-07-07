---
schema: sdlc/v1
type: shape
slug: full-codebase-audit-fixes
status: complete
stage-number: 2
created-at: "2026-07-07T00:41:05Z"
updated-at: "2026-07-07T00:41:05Z"
revision-count: 1
docs-needed: true
docs-types: [reference, how-to]
tags: [audit-findings, isometric-core, isometric-compose, isometric-android-view, gestures, docs, tests]
refs:
  index: 00-index.md
  intake: 01-intake.md
  next: 03-slice.md
next-command: wf-slice
next-invocation: "/wf slice full-codebase-audit-fixes"
---

# Shape

## The Shape

Twenty product-owner decisions turned the audit's finding list into a spec with no open
contract questions. The pattern across every decision: **make the documented contract true**
rather than documenting the accident — `isPointCloseToPoly` gains the inside-test its KDoc
promises, `rotateX`/`rotateY` flip to match `rotateZ`'s counter-clockwise convention (and the
convention finally gets written down), GroupNode learns to actually apply `alpha`, and the
scene stops eating pointer events it never acts on. Two contracts go the other way because
the code's behavior is the deliberate one: `normalize()` keeps its zero-vector return
(KDoc fixes), and the DragEvent ABI note is verify-only.

The codebase scan shrank the risk picture considerably. Nothing in the repo — no test, no
sample, no built-in shape — depends on the current `rotateX`/`rotateY` direction (only
`Path`/`Shape` delegates call them, and only `rotateZ` has a test), so the scariest-sounding
break has near-zero internal blast radius; its real cost is the missing tests we now must
write. Snapshot churn localizes to the Octahedron/Knot goldens (plus doc screenshots), handled
in one deliberate re-record pass at sweep end. The subtlest hazard found: `isPointCloseToPoly`
has three internal callers that rely on today's edge-only semantics for boundary exclusion in
intersection tests — they move to a private edge-only helper so the public fix can't ripple
into depth-sorting.

The gesture fix got the most latitude: the PO authorized restructuring the two independent
`pointerInput` blocks into one coordinated handler, and the freshness research says that's
the right call — Compose 1.5.0's `detectTapGestures` already implements exactly the chosen
tap contract (onTap waits out the ~300ms double-tap window only when onDoubleTap is present).
The verification bar is CI parity everywhere plus the instrumented androidTest suite on an
emulator for the gesture changes, since pointer-input regressions are precisely what JVM
tests miss.

## Problem Statement

The 2026-07-06 audit confirmed 24 distinct defects and 4 judgment calls (inventoried as
A1–G4 in [01-intake.md](01-intake.md), full evidence in [audit-findings.json](audit-findings.json)):
silent wrong results in core math (opposite rotations, black faces, missed interior hits,
wrong 3D distances), a spurious-onClick gesture bug, tests that cannot fail, and drifted
docs/annotations. Library consumers hit these as unexplainable rendering and interaction
misbehavior; the maintainer cannot trust the test suite to catch regressions.

## Primary Actor / User

App developers consuming isometric-core / isometric-compose / isometric-android-view;
secondarily the maintainer (test-suite trustworthiness, honest API contracts).

## Desired Behavior

All 28 items resolved per the decision ledger below. Headline behavior changes:

1. **Tap contract (C1):** a node with only `onClick` fires instantly on tap. A node with
   both `onClick` and `onDoubleClick` fires `onClick` only after the double-tap window
   (~300ms, `ViewConfiguration.doubleTapTimeoutMillis`) expires without a second tap;
   a double-tap fires `onDoubleClick` exactly once and never `onClick`.
2. **Rotation convention (A1):** positive angles rotate counter-clockwise around all three
   axes (right-handed convention), documented in KDoc on `rotateX`/`rotateY`/`rotateZ`.
   BREAKING for external callers of rotateX/rotateY.
3. **Event consumption (C2):** the scene consumes pointer events only when a gesture
   handler, node-drag state, or camera actually processes them; an inert scene inside a
   scrollable lets the parent scroll.
4. **Honest geometry (B1/B2):** Octahedron is inscribed in the unit cube as documented
   (uniform proportions); `Knot(position)` is positioned at `position`. BREAKING (visual).
5. **Honest hit-testing (A3):** `isPointCloseToPoly` returns true for interior points per
   its KDoc; internal intersection/hit callers keep edge-only semantics via a private helper.
6. **Correct 3D math (A2/A4/A6):** segment distance is fully 3D; `lighten()` clamps
   lightness to [0,1] (no more black back-lit faces); `cullPath` uses the full shoelace sum
   (Stairs' concave silhouette culls correctly).
7. **Group alpha (G1):** `alpha` set on a Group multiplies down to descendant render output;
   the Group composable exposes the parameter (additive API).
8. **Tests that can fail (F1/F2/F3/F4):** diagnostic tests gain existence + outcome
   assertions; alpha test asserts the exact scaled value; autopan-suppression gets coverage.
9. **Docs/annotations true (A5/E1–E4, D2, G3):** KDoc, guides, reference tables, CHANGELOG
   (incl. per-break migration notes), and stability annotations match reality.

## Acceptance Criteria

Verification method tags: `automated` (JVM test/gradle gate), `interactive` (emulator/
instrumented or visual snapshot inspection), `manual` (human judgment).

### Zone A — core math
- **AC-A1a** `automated` — Given `Point(0,1,0)`, When `rotateX(ORIGIN, π/2)`, Then result is
  `(0,0,1)`; Given `Point(0,0,1)`, When `rotateY(ORIGIN, π/2)`, Then result is `(1,0,0)`
  (CCW, right-handed). New PointTest cases for both axes; rotateZ test still passes unchanged.
- **AC-A1b** `automated` — KDoc on all three rotate functions states the CCW/right-handed
  convention (checked in review; dokka builds clean).
- **AC-A2** `automated` — Given segment `(0,0,0)→(0,0,2)` and query `(0,0,1)`, When
  `distanceToSegmentSquared`, Then result is `0.0`. PointTest:97's existing "includes z"
  test strengthened to catch the 2D/3D mix (it currently passes against the broken code —
  it must be made to fail against the old implementation).
- **AC-A3** `automated` — Given a point strictly interior to a large polygon and far from all
  edges, When `isPointCloseToPoly(point, poly, radius)`, Then true. AND: all existing
  IntersectionUtilsTest + DepthSorterTest cases pass unchanged (internal callers pinned to
  the private edge-only helper).
- **AC-A4** `automated` — Given `IsoColor(10,10,80)` and `lighten(-0.20, WHITE)`, Then no RGB
  channel is 0-clamped from negative lightness; result is a darker blue, not black.
  Property: for any color and percentage in [-1,1], output channels equal
  hslToRgb(coerceIn(0,1) lightness).
- **AC-A5** `automated` — `normalize()` KDoc states zero-vector return; a VectorTest case
  pins `Vector(0,0,0).normalize() == Vector(0,0,0)`.
- **AC-A6** `automated` — Given a concave polygon whose first-triangle winding disagrees with
  its full shoelace winding, When `cullPath`, Then the shoelace verdict wins. Existing
  PathTest CW/CCW/concave cases updated/extended; a Stairs-derived regression case added.
- **AC-A7** `automated` — `TileCoordinate.hashCode` no longer returns 0 for ORIGIN and
  distributes `(k, k*1_000_003)` patterns; existing equals/hashCode contract tests pass.

### Zone B — shapes
- **AC-B1** `automated`+`interactive` — Octahedron vertices span the full unit cube on all
  axes (equatorial vertices at the cube faces); path count stays 8
  (IsometricEngineTest:298). Golden re-recorded and visually inspected.
- **AC-B2** `automated`+`interactive` — `Knot(Point.ORIGIN)` geometry is positioned at
  ORIGIN (bounding-box assertion); golden re-recorded and inspected.
- **AC-B3** `automated` — `Cylinder(radius=-1.0, …)` and invalid-vertex-count constructions
  throw with Cylinder's own message text (test asserts the message).

### Zone C — gestures
- **AC-C1** `automated`+`interactive` — Given a node with both onClick and onDoubleClick:
  single tap → exactly one onClick after the disambiguation window; double tap → exactly one
  onDoubleClick, zero onClick. Given a node with onClick only: tap → onClick with no added
  latency. JVM gesture tests (new DoubleTapDisambiguationTest) + existing DragANodeTest /
  DragEventClarityTest / NodeCallbacksInteractionTest pass; instrumented androidTest suite
  run on emulator (evidence: test report).
- **AC-C2** `automated` — Given a scene with no gesture handlers, no node-drag state, and no
  camera, When drag events arrive, Then no `PointerInputChange.consume()` is called (test
  asserts consumption flags / parent-scroll interop test).
- **AC-C3** `automated` — Given long-press fires, then a drag, then release, Then internal
  gesture state (isDragging, draggedNode, longPressFired) is fully reset; next gesture
  behaves as from idle.

### Zone D — view module & samples
- **AC-D1** `automated` — AndroidCanvasRenderer allocates zero Paint objects per draw call
  (single reused instance; allocation-free assertion or code-review verified + existing
  render tests pass).
- **AC-D2** `automated` — DragLifecycleSample KDoc/comments state DragEvent.x/y as absolute
  position and delta as per-event movement, matching GestureEvents.kt semantics.
- **AC-D3** `automated` — After `setSort`/`setCull`/`setBoundsCheck` on IsometricView, the
  next draw reflects the new option (cachedScene rebuilt; unit test with option toggle).

### Zone E — docs & annotations
- **AC-E1** `manual` — interactions.mdx onDoubleClick section describes the new delayed
  disambiguation contract (no false suppression claim); mirrors regenerated via sync-docs.js.
- **AC-E2** `manual` — scene-config.mdx parameter table includes nodeDragState.
- **AC-E3** `manual` — CHANGELOG Unreleased lists the three missing WS10 APIs under Features
  AND a Migration subsection with before/after for each breaking fix (A1, B1, B2, C1 timing).
- **AC-E4** `automated` — Stability annotations verified against actual mutability;
  `./gradlew apiCheck` passes (apiDump regenerated if the fix changed any dump).

### Zone F — tests
- **AC-F1** `automated` — the 6x6 TileGrid diagnostic test asserts expected face presence
  and fails if any tile top/side face is missing.
- **AC-F2** `automated` — tower tests assert `findFace(...) >= 0` before the ordering
  assertions (no vacuous pass path).
- **AC-F3** `automated` — alpha scaling test asserts the exact expected alpha value.
- **AC-F4** `automated` — a test pins that selecting a node suppresses camera autopan.

### Zone G — judgment calls
- **AC-G1** `automated`+`interactive` — alpha on Group multiplies into descendant output
  (unit test on GroupNode.renderTo + snapshot if a scene exercises it); Group composable
  exposes alpha (apiDump updated); IsometricNode.alpha KDoc + composables.mdx agree.
- **AC-G2** `automated` — IsometricView.onTouchEvent returns true for handled ACTION_UP.
- **AC-G3** `manual` — AdvancedSceneConfig KDoc documents that callback-only changes don't
  re-register (deliberate equals() exclusion) and what callers should do instead.
- **AC-G4** `manual` — CHANGELOG migration note verified to cover DragEvent.copy() descriptor
  change; amended only if insufficient.

### Sweep-level gates
- **AC-S1** `automated` — `./gradlew test apiCheck` green on the final state.
- **AC-S2** `interactive` — one deliberate `recordPaparazziDebug` pass at sweep end; every
  changed golden visually inspected and attributable to an intended fix (B1, B2, possibly G1);
  doc screenshots regenerated (DocScreenshotGenerator) for shape pages.
- **AC-S3** `automated` — instrumented androidTest suite green on emulator for the gesture
  changes.

## Non-Functional Requirements

- No new allocations per frame in either renderer (D1 removes some; nothing added).
- No added tap latency for onClick-only nodes (C1's delay applies only when onDoubleClick
  is registered).
- Public API changes are additive or accepted-breaking only; every dump change deliberate
  (apiDump diffs reviewed, not rubber-stamped).
- Commit hygiene on the shared branch: one logical fix (or coherent fix group) per commit,
  conventional-commit messages (commitlint gates PRs), snapshot re-record as its own commit.

## Edge Cases / Failure Modes

- **A2:** degenerate segment (v == w) → l2 == 0 path must still return distance to the point.
- **A3:** polygon with < 3 points; point exactly on an edge; radius 0.
- **A4:** percentage exactly ±1.0; colors at l=0 and l=1 boundaries.
- **A6:** self-intersecting polygon (shoelace sign is undefined-but-stable — document that
  simple polygons are the contract).
- **C1:** tap → long-press-timeout races the double-tap window; drag started within the
  double-tap window; rapid triple-tap (expect: doubleClick then pending single evaluation);
  onDoubleClick added/removed between recompositions (pointerInput keying).
- **C2:** camera present but disabled; handler removed mid-gesture.
- **G1:** nested Groups (alpha multiplies, not overwrites); alpha=0 group (children skipped
  or fully transparent — pick and document); Group alpha × child alpha composition.
- **B1:** Octahedron path count/winding must survive the geometry change (depth sorting of
  its 8 faces).
- **Snapshot pass:** cross-platform pixel drift (goldens recorded on Windows vs CI Linux) —
  Paparazzi 1.3.0 known issue; if verify fails on CI-only pixels, record on the CI-matching
  platform or set a minimal maxPercentDifference deliberately.

## Affected Areas

(From the integration-surface scan; file:line evidence in the scan report.)

- `isometric-core`: Point.kt, Vector.kt (KDoc), IsoColor.kt, IsometricProjection.kt,
  IntersectionUtils.kt (+ private helper), TileCoordinate.kt, shapes/{Octahedron,Knot,Cylinder}.kt.
  Callers all internal: Path/Shape delegates (rotate), hasIntersection/hasInteriorIntersection/
  HitTester (isPointCloseToPoly — pinned via private helper), IsometricProjection (normalize,
  lighten).
- `isometric-compose`: IsometricScene.kt pointerInput blocks (lines ~303–575: hand-rolled
  loop + detectTapGestures block), IsometricNode.kt (GroupNode.renderTo + alpha),
  IsometricComposables.kt (Group signature), RenderContext (alpha propagation channel),
  AdvancedSceneConfig.kt (KDoc/annotations).
- `isometric-android-view`: AndroidCanvasRenderer.kt (Paint reuse), IsometricView.kt
  (setters rebuild, ACTION_UP return).
- `app`: InteractionSamplesActivity.kt KDoc.
- Tests: PointTest (new rotate cases; strengthen A2 test), VectorTest, IsoColorTest,
  PathTest, TileCoordinateTest, IntersectionUtilsTest, DepthSorterTest (F1/F2),
  IsometricNodeRenderTest (F3), new DoubleTapDisambiguationTest, DragANodeTest (F4),
  IsometricCanvasSnapshotTest goldens (octahedron.png, knot.png, complex scenes),
  view-module tests (D3, G2).
- Docs: site/src/content/docs/guides/{interactions,gestures,shapes}.mdx,
  reference/{scene-config,composables}.mdx → mirrors via scripts/sync-docs.js;
  CHANGELOG.md; docs/assets/screenshots via DocScreenshotGenerator.
- API dumps: isometric-core.api, isometric-compose.api, isometric-android-view.api via
  `./gradlew apiDump`.

## Dependencies / Sequencing Notes

- **Rendering fixes before the snapshot pass:** B1, B2, (G1 if it affects any scene) land
  first; the single re-record (AC-S2) comes after — one golden change per file in history.
- **A3 requires the private helper extraction first**, then the public-function change, then
  the full IntersectionUtils/DepthSorter suites as the regression gate.
- **F1/F2 conversions should land before/with A6** — the DepthSorter scenarios are the
  natural regression net for the culling change.
- **C1 restructuring subsumes C2 and C3** — one coordinated gesture handler addresses
  consumption gating and state reset in the same structure; slice these together.
- **E-zone doc edits last** (they describe the fixed behavior), followed by sync-docs.js,
  then CHANGELOG, then apiDump/apiCheck as the final gate.
- Compose 1.5.0 pinned: `detectTapGestures`' disambiguation (waits out
  `doubleTapTimeoutMillis` when onDoubleTap present) is the primitive for C1. Do NOT use
  `forEachGesture` (known event-loss, issue 251260206); `awaitEachGesture` where hand-rolled.

## Questions Asked This Stage

20 questions in 5 rounds (core contracts, behavior dynamics, surfaces, failure modes,
boundaries). Full text and answers in [po-answers.md](po-answers.md).

## Answers Captured This Stage

Decision ledger: C1 delay-when-both + restructuring allowed; A1 flip + document convention;
A5 KDoc-wins; A3 code-matches-KDoc + private internal helper; C2 consume-only-when-acting;
A2 full-3D; F1/F2 convert-to-asserting; A6 full shoelace; snapshots once-at-end; G1
implement Group alpha; E3 full changelog + migrations; D1 single reused Paint; pre-1.0
minor versioning; E4 verify-then-annotate; CI parity + instrumented-for-gestures; A7+F4
included; B3 fix validation order; G4 verify-only.

## Out of Scope

- The 42 refuted audit findings (evidence retained in audit-findings.json).
- Toolchain upgrades surfaced by freshness research (Kotlin 1.9.22→2.x, AGP 8→9, Compose
  1.5→1.11, Paparazzi 1.3.0→1.3.5+): real debt, but a separate workflow — mixing an
  ecosystem upgrade into a correctness sweep would make regressions unattributable.
- Gesture-system feature work beyond the C1/C2/C3 fixes (no new gesture types).
- Wiring instrumented tests or sync-docs.js into CI (worth doing; separate change).
- Git LFS for snapshots (Paparazzi-recommended; separate decision).
- WebGPU roadmap work.

## Definition of Done

All 28 ACs pass or are explicitly waived in the artifact trail; `./gradlew test apiCheck`
green; instrumented gesture suite green on emulator; goldens re-recorded once with every
diff attributed; docs mirrors regenerated; CHANGELOG carries features + migration notes;
no unrelated changes mixed into the shared branch's commits.

## Verification Strategy

**Target verification environment.** Windows 11 host; JVM unit tests + Paparazzi run
locally via Gradle (CI parity: `./gradlew test apiCheck` — same gates as ci.yml). Android
emulator available via android-cli for the instrumented suite
(`./gradlew connectedDebugAndroidTest`, isometric-compose androidTest source set); logcat
via lazylogcat if gesture debugging is needed. No physical device required. Paparazzi
goldens recorded on Windows — watch the known cross-platform pixel-drift issue vs CI Linux.

**Observation model (headline outcomes).**
- Tap contract → observed by JVM gesture tests injecting pointer events and asserting
  callback counts/timing, and by the instrumented suite on emulator (test report artifact).
- Rotation/geometry/color math → observed by unit tests with exact expected values
  (constructive: each new test must FAIL against the pre-fix implementation — that is the
  proof the test observes the defect).
- Rendering changes (B1/B2/G1) → observed as Paparazzi golden diffs, each inspected and
  attributed; doc screenshots regenerated.
- Consumption behavior (C2) → observed by asserting PointerInputChange consumption flags
  in a JVM test (parent-interop).
- Docs → observed by human read-through of the rendered pages + markdownlint/lychee CI gates.

**Automated checks:** all Zone A/B/C/D/F ACs + E4 + G1/G2 (unit tests, `./gradlew test`,
`apiCheck`).
**Interactive verification:** platform Android — instrumented androidTest on emulator for
C1/C2/C3 (evidence: test report); Paparazzi golden inspection for B1/B2/G1 (evidence: git
diff of PNGs + HTML report).
**Human-in-the-loop:** E1–E3, G3, G4 doc accuracy; golden-diff attribution sign-off.

## Documentation Plan

- **Type:** reference — **Audience:** competent user — **Must cover:** scene-config.mdx
  nodeDragState row (E2); composables.mdx Group alpha parameter (G1); KDoc for rotation
  convention, normalize, isPointCloseToPoly, AdvancedSceneConfig callback semantics (G3).
  **Must NOT cover:** internal helper functions, workflow provenance. **Location:**
  site/src/content/docs/reference/ + KDoc in source.
- **Type:** how-to — **Audience:** competent user — **Must cover:** interactions.mdx
  corrected double-tap contract (E1); gestures.mdx consumption behavior (C2);
  shapes.mdx rotation direction note. **Must NOT cover:** implementation detail of the
  gesture loop. **Location:** site/src/content/docs/guides/.
- **CHANGELOG:** missing WS10 features + Migration subsection per breaking fix (E3, G4).
- No tutorial (no new capability for beginners); no explanation page (no architectural
  decision worth a standalone essay — the KDoc convention notes suffice); no README change.
- All .mdx edits followed by `node scripts/sync-docs.js` (never hand-edit docs/*.md).

## Freshness Research

- Source: TapGestureDetector.kt (androidx.compose.foundation 1.5.0) + Android Developers
  tap-and-press guide.
  Why it matters: C1's chosen contract must match what the platform primitive can deliver.
  Takeaway: detectTapGestures with both onTap and onDoubleTap already waits out
  `doubleTapTimeoutMillis` (~300ms, min 40ms) before firing onTap — the chosen contract is
  the platform default; no hand-rolled timing needed.
- Source: Android Developers pointer-input docs + issue 251260206.
  Why it matters: the fix restructures pointerInput blocks.
  Takeaway: stacked blocks each see all events (consumption flags coordinate); a preceding
  block consuming Release starves detectTapGestures (`awaitFirstDown(requireUnconsumed=true)`)
  — the exact mechanism behind C1. Avoid forEachGesture (event loss); use awaitEachGesture.
- Source: Kotlin/binary-compatibility-validator 0.17.0 docs + repo .api dumps inspection.
  Why it matters: E4/G1 may change dumps.
  Takeaway: the repo's dumps carry `$stable` synthetic fields, not the source annotations
  textually; whether an annotation swap changes dumps must be tested empirically with
  apiDump — budget for it in E4.
- Source: cashapp/paparazzi 1.3.0 changelog + issues #1465, #1716.
  Why it matters: AC-S2 mass re-record.
  Takeaway: record/verify via recordPaparazziDebug/verifyPaparazziDebug; known
  cross-platform pixel drift between record host and CI — verify on CI after the record
  commit before assuming green.
- Source: libs.versions.toml vs current stable versions (July 2026).
  Why it matters: scoping.
  Takeaway: Kotlin 1.9.22/AGP 8.2.2/Compose 1.5.0 are ~2.5 years behind; internally
  consistent, so the sweep proceeds on the pinned stack; upgrade is separate work
  (recorded Out of Scope).

## Recommended Next Stage

- **Option A (default):** `/wf slice full-codebase-audit-fixes` — 28 ACs across 7 zones with
  clear sequencing constraints (rendering-before-snapshots, helper-before-A3, C1 subsumes
  C2/C3) is textbook multi-slice work at Large appetite.
- **Option B:** `/wf plan full-codebase-audit-fixes` — not recommended: single-slice would
  make the shared-branch commit history and the snapshot gate unmanageable.
- **Option C:** `/wf intake full-codebase-audit-fixes` — not needed: no intake-level
  misunderstanding surfaced; all 20 discovery answers landed cleanly.
