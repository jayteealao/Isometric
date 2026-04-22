---
schema: sdlc/v1
type: routing
slug: texture-material-shaders
updated-at: "2026-04-21T17:31:15Z"
current-stage: review
stage-number: 7
status: complete
stage-verdict: ship-with-caveats
selected-slice: uv-generation-knot
branch-strategy: dedicated
branch: "feat/texture"
on-correct-branch: true
is-blocked: false
open-questions: []
remaining-slices: ["webgpu-ngon-faces"]
review-fix-status: all-deferred
next-command: wf-handoff
next-invocation: "/wf-handoff texture-material-shaders"
refs:
  index: 00-index.md
  current-stage-file: 07-review-uv-generation-knot.md
---

# Next

The `uv-generation-knot` slice has cleared review with verdict **ship-with-caveats** and all 32 findings deferred. Workflow is at stage 7 (review) complete; default routing advances to stage 8 (handoff) for PR consolidation on the dedicated `feat/texture` branch (PR #8 already open).

One slice in `03-slice.md` is still unplanned: `webgpu-ngon-faces` (status: `defined`). It is the natural home for the deferred I-2 finding from the stairs verify (non-convex triangulation in `RenderCommandTriangulator`) and the deferred `:isometric-benchmark` compile-fix concern. Implementing it before handoff would consolidate the workflow into a single comprehensive PR; alternatively, it can be split into a follow-up workflow.

## All Options

- **Option A (default, recommended):** `/wf-handoff texture-material-shaders` — Slice review is complete with verdict ship-with-caveats and all findings triaged as deferred. PR #8 is already open on `feat/texture`. Handoff aggregates all 15 verified slices into the PR description and prepares for review/merge. The 32 deferred findings persist in `07-review-uv-generation-knot.md` for re-triage via `/wf-review texture-material-shaders uv-generation-knot triage` later.

- **Option B:** `/wf-extend texture-material-shaders from-review` — Open one or more follow-on slices for the deferred review findings. Highest-payoff is **Group A (sourcePrisms redesign)** which addresses 5 findings (U-01, U-05, U-13, U-14, U-31) in ~30 min via a single `companion val CANONICAL_PRISMS`. Other groups (B: quadBboxUvs hot-path, C: forKnotFace cache, D: test hardening, E: doc polish) are also documented in the review file's Recommendations section.

- **Option C:** `/wf-plan texture-material-shaders webgpu-ngon-faces` — Plan the remaining defined slice. This is the natural home for the stairs-verify I-2 (non-convex triangulation in `RenderCommandTriangulator.kt:75`) and the pre-existing `:isometric-benchmark/BenchmarkScreen.kt:165` compile failure. Doing this before handoff produces a single comprehensive PR that completes the texture-material-shaders scope end-to-end.

- **Option D:** `/wf-implement texture-material-shaders uv-generation-knot` — Re-open the knot slice and address some/all of the 5 HIGH findings now rather than deferring. Not recommended given your explicit defer-all decision and the consistent sibling-slice precedent of folding fixes into downstream slices.

- **Option E:** `/wf-amend texture-material-shaders from-review` — **Not applicable.** Review findings critique implementation choices, not slice spec/ACs. AC1-AC5 all met at verify pass 1; the spec was correct.

- **Option F:** `/wf-ship texture-material-shaders` — Skip handoff and proceed directly to publish. Acceptable only if PR #8 is already merge-ready (CI green, reviewers approved). Handoff (Option A) is the safer default to ensure PR description is updated and the merge gate is verified.

## If Blocked
None. No open questions; correct branch (`feat/texture`); no awaiting-input stages.

## Branch Check
Currently on `feat/texture` (matches workflow `branch`). No switch required.
