---
schema: sdlc/v1
type: intake
slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 1
created-at: "2026-04-26T17:52:49Z"
updated-at: "2026-04-26T17:52:49Z"
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
refs:
  index: 00-index.md
  next: 02-shape.md
next-command: wf-shape
next-invocation: "/wf-shape depth-sort-shared-edge-overpaint"
---

# Intake — depth-sort shared-edge overpaint

## Restated Request

In the WS10 sample app's "Node ID" tab (`InteractionSamplesActivity.NodeIdSample`), the Factory building's top face is painted **on top of** (after) the HQ building's right side face, even though HQ is the closer building to the viewer in isometric world space and should occlude Factory at their shared screen-space corner.

The two faces share a 3D edge (HQ's right side at world `x=1.5` is right next to Factory's left side at world `x=2.0`; Factory's top at `z=2.1` intersects the vertical span of HQ's right side which extends from `z=0.1` to `z=3.1`). Their projected 2D polygons therefore meet — and may overlap — at the corner where Factory's top-left projects into HQ's right-edge area.

The painter's-algorithm pipeline (`DepthSorter` → `Path.closerThan` plane-side test → topological sort) currently produces the wrong final order for this face pair, and Factory-top wins.

## Intended Outcome

A correct, robust painter sort for any pair of prism faces that share a 3D edge, restoring visually-correct occlusion in the WS10 NodeIdSample (and any future scene with adjacent same-y prisms of differing heights).

## Primary User / Actor

- **Direct beneficiary:** developers using `IsometricScene` in apps that render adjacent buildings/blocks of mixed heights — exactly the kind of scene the WS10 alpha demo is meant to showcase.
- **Indirect beneficiary:** anyone running the samples on the published 1.2.0-alpha.01 prerelease. The bug undermines the WS10 Interaction-API demo even though the interaction code itself works fine post-`97416ba`.
- **Library-level beneficiary:** `Path.closerThan` and `DepthSorter` users elsewhere — the root cause is in shared geometry code, not sample-app code.

## Known Constraints

- **No breaking API changes.** If a fix requires changing the public signature or semantics of `Path.closerThan`, `Path.depth`, `IntersectionUtils.hasIntersection`, `DepthSorter`, or any documented part of `isometric-core`, it must be raised with the PO before implementation.
- **No regression in any other sample.** View API, Compose Scene API, Runtime API, and the four sibling Interaction API tabs (onClick, Long Press, Alpha, Combined) must render byte-or-eye-identically post-fix. Tile-grid scenes are explicit guards: the `a685620` "pre-sort by depth" fix that prevents side-faces from over-painting top-faces in tile grids must remain effective.
- **Branch:** Shared. Commits land on the current branch `feat/ws10-interaction-props`; no separate PR. The fix rides along with the WS10 alpha branch.
- **Appetite:** Medium (~1–2 days), inclusive of regression test fixtures.

## Assumptions

- The bug is in `Path.closerThan` and/or `IntersectionUtils.hasIntersection`, **not** in `Path.depth` (the precomputed pre-sort key). The hotfix `hotfix-long-press-node-id-sort` already proved that changing `Path.depth` to drop the `z` term does not fix this symptom — meaning the topo-sort step downstream is the real culprit.
- The bug exists on `master` as well as on the current feature branch. `Path.closerThan` and `Point.depth()` date to the v1.0.0 release commit and have not been touched since (per the hotfix sub-agent investigation). Only the *exposure* of the bug changed when commit `a685620` made the pre-sort by depth the primary tiebreaker for non-overlapping faces.
- The sample-side data for `NodeIdSample` is not the cause — buildings at `Point(i*2.0, 1.0, 0.1)` with widths 1.5 and heights `[3.0, 2.0, 1.5, 4.0]` is a reasonable, realistic scene. The fix lives in the library, not the sample.

## Product Owner Questions Asked

- Q1 (acceptance criteria): "WS10 NodeIdSample alone, all height permutations, all shared-edge pairs, or property-test over random arrangements?"
- Q2 (don't-regress scenarios): "Tile grids? Batches? Other samples?"
- Q3 (test coverage): "Unit, snapshot, or both?"
- Q4 (constraints): "Public API, sort algorithm, broad-phase, behavior changes?"
- (Structured) Branch strategy and Appetite via AskUserQuestion.

## Product Owner Answers

See `po-answers.md` for the full log. Summary:

- **Acceptance criteria:** **(a) AND (c)** — both the WS10 NodeIdSample renders without overpaint, and any pair of prisms sharing a 3D edge always sorts correctly. The fix must generalise beyond this one scene.
- **Don't regress:** **All other samples** must remain visually unchanged (View API, Compose Scene API, Runtime API, sibling Interaction tabs). Tile-grid sort behavior must remain correct.
- **Test coverage:** **Both** — a unit test on the underlying predicate (with hand-computed expected output for the shared-edge case) and a Paparazzi/screenshot regression test of NodeIdSample (or a minimal hand-built repro scene).
- **Constraints:** **No breaking changes**. If breaking is necessary, **PO discussion required first**.
- **Branch:** Shared on `feat/ws10-interaction-props`.
- **Appetite:** Medium (~1–2 days).

## Unknowns / Open Questions

- **Which predicate is the actual root cause** — `Path.closerThan` returning 0 (ambiguous), `Path.closerThan` returning the wrong sign, or `IntersectionUtils.hasIntersection` failing to detect the 2D overlap? Diagnosis via instrumented run is the first SHAPE-stage activity.
- **Which Newell-style fallback is appropriate** — Z-minimax → X-minimax → Y-minimax (classical Newell), polygon splitting (more robust but invasive), or a simpler tweak (e.g., adjust the epsilon in `Path.closerThan`)? To be decided in SHAPE.
- **Performance budget**: the broad-phase spatial bucketing is O(k); any added edge-case logic should preserve that. To be confirmed in SHAPE.
- **Test scaffolding**: do we have an existing Paparazzi setup for the sample app, or do we need to build a minimal scene factory? (Sibling workflow `texture-material-shaders` mentions Paparazzi gates — likely yes.)

## Dependencies / External Factors

- Already-merged hotfix `97416ba` (long-press fix) is independent — this workflow does not block on it nor it on this.
- Sibling branch `feat/texture` carries the texture-material-shaders work which includes its own painter changes. If this fix is merged into `feat/ws10-interaction-props` and `feat/texture` is later merged into the same eventual integration branch, conflicts must be reconciled. Coordinate with whichever branch ships first.
- No external library dependency. All work is in `isometric-core` (likely) and possibly `isometric-compose` (if a snapshot test goes there).

## Risks if Misunderstood

- **Risk: applying a fix that breaks tile-grid sorting.** The `a685620` commit explicitly notes that pre-sort by depth was needed to make side-faces not over-paint top-faces in tile grids. A naïve "ignore depth ties" fix could regress that. Mitigation: SHAPE must include a tile-grid regression test in the test plan.
- **Risk: changing `Path.depth` instead of `Path.closerThan`.** The hotfix already tried this path and found it doesn't address this symptom. SHAPE must instrument first to confirm the actual broken predicate before proposing a fix.
- **Risk: introducing a stealth breaking change.** Even non-breaking-on-the-face changes to `closerThan`'s tie-handling can ripple into downstream sort order in ways that change snapshot tests in other samples. Mitigation: PO has explicitly required no regressions in any sample → snapshot diff is the gate.
- **Risk: scope creep into a full painter redesign.** Newell's full algorithm with polygon splitting is well past the medium appetite. SHAPE must propose the smallest viable change and explicitly call out anything bigger as deferred.

## Success Criteria

1. **Visual:** the WS10 NodeIdSample's reference screenshot — re-shot with all four buildings (`hq` h=3.0, `factory` h=2.0, `warehouse` h=1.5, `tower` h=4.0) at standard positions — shows no face from a farther building painted over a face from a closer building. Specifically, Factory's top face must not bleed over HQ's right side at their shared corner.
2. **General:** a unit test asserts that `Path.closerThan` (or whichever predicate is fixed) returns the correct sign for any pair of prism faces that share a 3D edge — including the four canonical adjacency pairs (right-side ↔ left-side, top ↔ side, side ↔ top with different heights). Hand-computed expected output, no use of the implementation under test to derive expectations.
3. **No regression:** Paparazzi/snapshot diffs across all existing sample reference screenshots are clean. Tile-grid sort behavior is verified by a dedicated regression test (existing or new).
4. **No public API change** — confirmed by inspecting the diff against the documented surface in `docs/internal/api-design-guideline.md`. If a breaking change is unavoidable, the SHAPE stage must produce a written PO request before implementation.
5. **Builds and CI gates pass** including any pre-existing Robolectric/snapshot suites in `isometric-core` and `isometric-compose`.

## Out of Scope for Now

- Refactoring the painter into a Z-buffer/per-pixel pipeline. Long-term direction worth considering but explicitly out of scope.
- Adopting Shewchuk's robust geometric predicates wholesale. Out of scope unless the SHAPE stage finds it's the only viable way to meet the acceptance criteria — in which case PO discussion is mandatory per Q4.
- Implementing the BSP-tree resolution path. Out of scope for the same reason.
- Changing `Point.depth()` or the projection angle handling. Already ruled out by the hotfix investigation.
- Texture-material-shader work on `feat/texture`. Separate workflow; integration coordinated at merge time.
- Performance optimization beyond preserving the existing O(k) broad-phase. Profiling and tuning are not goals of this fix.

## Freshness Research

- **Source:** [Painter's algorithm — Wikipedia](https://en.wikipedia.org/wiki/Painter%27s_algorithm)
  **Why it matters:** Confirms that "shared edge" / "cyclic overlap" / "piercing polygons" is a textbook failure mode of centroid-depth sorting, not an implementation accident in this codebase.
  **Takeaway:** The bug class is well-understood; classical solutions exist; we're not inventing geometry.

- **Source:** [Newell's algorithm — Wikipedia](https://en.wikipedia.org/wiki/Newell%27s_algorithm)
  **Why it matters:** Defines the canonical sequence of overlap tests (Z minimax → X minimax → Y minimax → polygon split) used to disambiguate face order when centroid depth is insufficient. The current `Path.closerThan` resembles only the *plane-side* test, which is one ingredient of Newell — the 1972 algorithm's robustness comes from the *combination* of tests with explicit fallbacks.
  **Takeaway:** A surgical fix likely either (a) adds Z/X/Y minimax fallbacks before falling back to pre-sort, or (b) tightens `closerThan` to handle shared-edge cases (vertices at exactly distance 0 from the other plane) without returning 0.

- **Source:** [4.3.1 Depth Buffering — OpenGL.org](https://www.opengl.org/archives/resources/code/samples/advanced/advanced97/notes/node19.html)
  **Why it matters:** Background on why most modern 3D engines abandoned painter's-algorithm sorting in favour of Z-buffering. This is *not* a path we're taking (out of scope), but it explains why the painter's-algorithm robustness literature stopped progressing in the early 2000s — most known fixes are the ones from 1972–1990s.
  **Takeaway:** Don't expect a contemporary best-practice. The fix space is mature and small.

## Recommended Next Stage

- **Option A (default):** `/wf-shape depth-sort-shared-edge-overpaint` — multiple viable fix designs (sharper `closerThan` epsilon vs. Newell-style minimax fallbacks vs. polygon splitting) with different blast radii. SHAPE should diagnose first (instrumented run on the NodeIdSample geometry to confirm whether `closerThan` returns 0 or the wrong sign), then propose the smallest design that satisfies the acceptance criteria, and surface any unavoidable breaking change for PO discussion before PLAN.
- **Option B:** `/wf-plan depth-sort-shared-edge-overpaint` — only justified if SHAPE turns out to be a single-predicate change with no design choices. Likely premature given the open question about which predicate is wrong.
- **Option C:** Blocked — not applicable; all required intake answers are in.
