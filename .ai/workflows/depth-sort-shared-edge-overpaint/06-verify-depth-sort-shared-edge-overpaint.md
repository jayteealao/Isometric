---
schema: sdlc/v1
type: verify
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 6
created-at: "2026-04-26T20:32:14Z"
updated-at: "2026-04-26T20:32:14Z"
result: partial
metric-checks-run: 4
metric-checks-passed: 4
metric-acceptance-met: 6
metric-acceptance-total: 8
metric-interactive-checks-run: 1
metric-interactive-checks-passed: 0
metric-issues-found: 1
evidence-dir: ".ai/workflows/depth-sort-shared-edge-overpaint/verify-evidence/"
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  review: 07-review.md
next-command: wf-review
next-invocation: "/wf-review depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Verify: depth-sort-shared-edge-overpaint

## Verification Summary

The fix in commit `3e811aa` is **verified for correctness at the unit and
integration level**: 183 of 183 isometric-core tests pass, all six AC-mapped
unit/integration tests pass with the predicted results, public signatures
of `Path.closerThan` and `Path.countCloserThan` are byte-identical to
`HEAD~1`, and `verifyPaparazzi` succeeds at the Gradle/JUnit level.

**Visual verification of the Paparazzi snapshot (AC-5) and pixel-stability
of pre-existing snapshots (AC-6 snapshot side) is UNVERIFIED in this
environment.** Paparazzi 1.3.0 + Layoutlib + Windows + JDK 17 produces
identical 6917-byte blank PNGs for every `IsometricCanvasSnapshotTest`
case, including pre-existing scenes (`pyramid`, `cylinder`, `sampleOne`,
etc.). This is environmental, not a slice regression — the same blank
output is produced for tests untouched by this fix. The new
`nodeIdSharedEdge.png` baseline therefore cannot be visually inspected
locally; the test infrastructure is wired correctly but the renderer
emits empty frames in this OS/JDK combination.

Result: **partial** — fix is correct; AC-5 visual inspection deferred to
Linux CI, where Paparazzi has historically produced real renders.

## Automated Checks Run

| Check | Command | Result |
| --- | --- | --- |
| Build / typecheck | `./gradlew :isometric-core:assemble :isometric-compose:assemble` | **PASS** — 50 tasks executed, debug+release AAR + core JAR built. One unrelated AGP `targetSdk` deprecation warning in `build-logic`. |
| Unit tests (core) | `./gradlew :isometric-core:test` | **PASS** — 183 tests across 16 classes, 0 failures, 0 errors, 0 skipped. |
| Snapshot record | `./gradlew :isometric-compose:recordPaparazzi` | **PASS at task level** (BUILD SUCCESSFUL, JUnit 16/16 green) but **FAIL at content level** (all 16 PNGs are 6917-byte blank renders — see Issues Found §1). |
| Snapshot verify | `./gradlew :isometric-compose:verifyPaparazzi` | **PASS** against the just-recorded baselines (tautological self-match; does not confirm visual correctness). |

All Gradle invocations required this Windows workaround stack: `./gradlew
--stop`, move stale `build-logic/build` aside, then run with
`-Dkotlin.incremental=false -Dkotlin.compiler.execution.strategy=in-process
--no-daemon --no-configuration-cache`. The kotlin-DSL precompiled-script-plugin
generator + IC daemon are unstable on Windows and corrupted their
`PersistentEnumerator` btree cache twice during this session. Linux CI is
unaffected by these issues.

### isometric-core test breakdown (183 tests, all green)

| Class | Tests | Slice-relevant cases |
| --- | --- | --- |
| `PathTest` | 10 | AC-1 *(closerThan returns nonzero for hq-right vs factory-top shared-edge case)*, AC-2 *(closerThan returns zero for genuinely coplanar non-overlapping faces)*, AC-3 *(four cases: X-adjacent diff heights, Y-adjacent diff heights, top vs vertical side equal heights, diagonally offset adjacency)*. |
| `DepthSorterTest` | 8 | AC-4 *(WS10 NodeIdSample four buildings render in correct front-to-back order)*, AC-7 *(closerThan is antisymmetric for representative non-coplanar pairs)*, plus six pre-existing regressions all green (coplanar adjacent prisms, coplanar tile grid + broad-phase parity, cycle fallback, kahn sparse parity, diagnostic face count). |
| `IntersectionUtilsTest` | 3 (NEW file) | hasIntersection: disjoint→false, overlapping→true, outer-contains-inner→true. |
| Other 13 classes | 162 | All pre-existing baselines green; no regression from any neighbour class. |

## Interactive Verification Results

### AC-5 — Paparazzi snapshot visual inspection (`nodeIdSharedEdge.png`)

- **Criterion**: snapshot shows no factory-top-over-hq-right artefact at
  shared corners; four buildings render with correct back-to-front
  occlusion.
- **Platform & tool**: Paparazzi 1.3.0 unit tests via Gradle on Windows 11
  + JDK 17 (`./gradlew :isometric-compose:recordPaparazzi`).
- **Steps performed**:
  1. Recorded all 16 `IsometricCanvasSnapshotTest` baselines via
     `recordPaparazzi`.
  2. Inspected the new `nodeIdSharedEdge.png` (saved as
     `verify-evidence/nodeIdSharedEdge-blank-windows.png`).
  3. Compared against pre-existing `pyramid.png`
     (`verify-evidence/pyramid-blank-windows.png`) as a control.
  4. Sampled byte sizes of all 16 PNGs (every one is exactly 6917 bytes).
- **Evidence**: `verify-evidence/nodeIdSharedEdge-blank-windows.png` (the
  new baseline) and `verify-evidence/pyramid-blank-windows.png` (a
  pre-existing baseline; control).
- **Observation**: Both PNGs are identical empty frames showing only a
  dark device background and the Android navigation bar (back / home /
  recents). No isometric scene content. All 16 baselines on disk share
  the same 6917-byte fingerprint.
- **Result**: **UNVERIFIED**. AC-5's pass criteria require comparing the
  rendered scene against expected occlusion behaviour, which is impossible
  when the renderer emits empty frames. Critically, the same emptiness
  affects scenes (`pyramid`, `cylinder`, `sampleOne`, `extrude`, `knot`,
  `octahedron`, `prism`, `path`, `grid`, `translate`, `scale`, `rotateZ`,
  `stairs`, `sampleTwo`, `sampleThree`) that this slice does not touch —
  proving the issue is environmental (Paparazzi/Layoutlib/Windows/JDK17),
  not a slice regression. Snapshot test source code is wired correctly
  (`paparazzi.snapshot { Box(...) { IsometricScene { WS10NodeIdScene() } } }`).

## Acceptance Criteria Status

| AC | Status | Method | Evidence |
| --- | --- | --- | --- |
| **AC-1** | **MET** | automated unit | `PathTest.closerThan returns nonzero for hq-right vs factory-top shared-edge case` — 0.001s, green. |
| **AC-2** | **MET** | automated unit | `PathTest.closerThan returns zero for genuinely coplanar non-overlapping faces` — green. |
| **AC-3** | **MET** | automated unit (parameterised) | Four `PathTest` cases: X-adjacent diff heights, Y-adjacent diff heights, top vs vertical side equal heights, diagonally offset adjacency — all green. The plan listed five sub-cases including "two prisms equal-height adjacent on Y"; the implementation merged similar configurations into four cases. Coverage equivalent — every adjacency the plan called out is exercised. |
| **AC-4** | **MET** | automated integration | `DepthSorterTest.WS10 NodeIdSample four buildings render in correct front-to-back order` — 0.002s, green. hq's right-side face command sits *after* factory's top-face command in the projected scene. |
| **AC-5** | **UNVERIFIED** | interactive (Paparazzi) | New `nodeIdSharedEdge` test wires correctly and JUnit reports it green. PNG output is blank; visual occlusion check impossible in this environment. Defer to Linux CI. |
| **AC-6** | **PARTIALLY MET** | automated regression | Unit-side: every pre-existing `DepthSorterTest`, `PathTest`, `IsometricEngineTest`, `TileCoordinateTest`, etc. case green (177 pre-existing tests, 0 fail). Snapshot-side: pixel-stability undeterminable because the local renderer emits blanks for every scene; no comparison baseline survives the environment quirk. |
| **AC-7** | **MET** | automated invariant | `DepthSorterTest.closerThan is antisymmetric for representative non-coplanar pairs` — green. Caveat: with the new permissive 0/1 return, antisymmetry currently holds by construction (at most one direction returns 1). The test guards against future continuous-valued regressions. |
| **AC-8** | **MET** | manual / source diff | `git show HEAD~1:.../Path.kt` and `HEAD:.../Path.kt` both expose `fun closerThan(pathA: Path, observer: Point): Int` and `private fun countCloserThan(pathA: Path, observer: Point): Int`. Signatures byte-identical; only KDoc grew (line numbers shifted from 99/106 to 99/117). |

**Met: 6/8. Partial: 1/8 (AC-6). Unverified: 1/8 (AC-5).**

## Issues Found

### Issue 1 — Paparazzi local renders are blank on Windows + JDK 17

- **Severity**: low (environment / verification gap, not a fix defect)
- **Scope**: every `IsometricCanvasSnapshotTest` snapshot, including
  scenes outside this slice. Pre-existing `pyramid.png` and
  `sampleThree.png` (which were on disk untracked from prior work) are
  also overwritten to blanks during this session's `recordPaparazzi`.
- **Symptom**: 16 baseline PNGs all exactly 6917 bytes; visual content is
  the device chrome and Android nav bar only — no Compose Canvas
  drawing.
- **Likely cause**: Paparazzi 1.3.0 + Layoutlib + JDK 17 + Windows
  combination. Compose Canvas drawing not making it into the captured
  bitmap. Gradle and JUnit both report success — failure is silent at
  the framework layer.
- **Implication for this slice**: AC-5 visual inspection cannot be
  performed locally. The test infrastructure code itself is correct
  (matches sibling tests' shape; `WS10NodeIdScene` factory composes the
  five-prism scene). Defer to Linux CI for actual baseline generation
  and pixel inspection.
- **Implication for the repo**: pre-existing tech debt of 11 missing
  Paparazzi baselines remains unresolved. Locally generated baselines
  would now be wrong (blank). Do **not** commit any of the
  `isometric-compose/src/test/snapshots/images/*.png` files produced in
  this session.
- **Recommended follow-up**: spawn a workflow to investigate the
  Paparazzi blank-render issue, ideally landing real baselines via
  Linux CI rather than developer machines.

## Gaps / Unverified Areas

- **AC-5** (interactive snapshot inspection) — see Issue 1 and AC-5 row
  above. Linux CI must close this gap.
- **AC-6 snapshot-side** — pixel-stability of pre-existing snapshots is
  undeterminable. Logic-side regression has been thoroughly covered by
  the 177 passing pre-existing core tests.
- **End-to-end Maestro verification on the Android emulator** (mentioned
  in `02-shape.md` § Verification Strategy as "optional but
  recommended") was not performed in this session. With unit/integration
  tests fully green and the build-logic Windows toolchain unstable,
  spending another build cycle on a manual flow would not change the
  verification outcome. Recommend running the
  `.ai/workflows/hotfix-long-press-node-id-sort/maestro-verify-nodeid.yaml`
  flow during `/wf-review` if a reviewer wants live-device visual
  confirmation.
- **Working-tree noise** — `build-logic/build.corrupt-*`,
  `build-logic/build.fail-*`, `build-logic/build.papa-*` and
  `build-logic/build.papa2-*` directories were created when applying
  the documented `mv build-logic/build aside` workaround for the IC-cache
  corruption. Each is `.gitignore`d via the standard `build/` glob (they
  match `build/**` patterns) but should be cleaned up by the developer
  before the next session to reclaim disk space. The
  `build-logic/build.gradle.kts` working-tree change (CC workaround)
  remains unstaged per plan — handoff will land it as a separate
  `chore(build-logic):` commit.

## Freshness Research

No new external research was needed at the verify stage — the fix is a
localised behavioural change to a single private method with no external
dependencies. The freshness searches at intake / shape / plan stages
remain authoritative (Newell's algorithm, Paparazzi changelog,
Shewchuk predicates, Kotlin TimSort docs, IsometricBlocks AABB minimax).

The Paparazzi 1.3.0 blank-render issue is documented in this report but
not pursued as a freshness lookup — debugging it is out of scope for
this verification stage and should be its own workflow.

## Recommendation

The fix is **correct and ready for review**. Six of the eight
acceptance criteria pass cleanly with hand-traceable evidence; one is
partial only on its snapshot-pixel side; one requires Linux CI to close
the visual-inspection loop. Reviewers can:

1. Read the diff and the diagnosis chain (intake → shape → plan →
   implement) to verify the algorithmic reasoning.
2. Trust the 183-test suite's coverage of AC-1 through AC-4, AC-7, and
   the regression-side of AC-6.
3. Wait for Linux CI's `recordPaparazzi` + `verifyPaparazzi` to close
   AC-5 and the snapshot-side of AC-6 — this happens automatically on
   PR open.

No code-level fixes were identified. The implementation matches the plan
modulo two documented deviations (`IsometricSceneScope` →
`IsometricScope`; intersection-utils third case → outer-contains-inner)
that were correctly noted in `05-implement-...md`.

## Recommended Next Stage

- **Option A:** `/wf-review depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — automated checks pass, six ACs are met, two are gated on Linux CI
  for visual confirmation. Reviewers can read the diff and trust CI's
  Paparazzi run for AC-5/AC-6 snapshot pixel inspection.

- **Option B:** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — **not recommended**. No fix issues found; the verify gap is
  environmental, not algorithmic. Re-implementing would not change AC-5
  status because the issue lives in Paparazzi/Layoutlib/Windows, not in
  the slice's code.

- **Option C:** `/wf-handoff depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — viable if the user is comfortable skipping formal review. The fix
  rides along on `feat/ws10-interaction-props`, which is a shared alpha
  prerelease branch with active sibling work (texture-material-shaders,
  webgpu-benchmark-timing). Skipping review on a branch this active is
  risky; recommend Option A.

- **Option D:** `/wf-plan depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — **not applicable**. The plan was sound and execution matched it.
