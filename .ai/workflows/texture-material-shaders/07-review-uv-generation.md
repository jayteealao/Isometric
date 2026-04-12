---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: uv-generation
status: complete
stage-number: 7
created-at: "2026-04-12T09:37:28Z"
updated-at: "2026-04-12T09:37:28Z"
verdict: ship
commands-run: [correctness, security, code-simplification, api-review]
metric-commands-run: 4
metric-findings-total: 4
metric-findings-raw: 8
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 1
metric-findings-low: 2
metric-findings-nit: 1
tags: [uv, geometry]
refs:
  index: 00-index.md
  slice-def: 03-slice-uv-generation.md
  implement: 05-implement-uv-generation.md
  verify: 06-verify-uv-generation.md
  sub-reviews: [07-review-uv-correctness.md, 07-review-uv-simplification.md]
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders canvas-textures"
---

# Review: UV Generation

## Verdict

**Ship**

No blockers, no HIGH findings. One MED finding (unsafe `as Prism` cast in uvProvider lambda)
that both the correctness and simplification reviewers flagged independently. The fix is
trivial — close over the captured Prism reference instead of re-casting. Three LOW/NIT
items are minor improvements. All UV math is correct — verified by tracing every vertex
through every formula against `Prism.createPaths()`.

## Domain Coverage

| Domain | Command | Status |
|--------|---------|--------|
| Logic & math | `correctness` | Clean (1 MED recommendation) |
| Vulnerabilities | `security` | Clean |
| Complexity | `code-simplification` | Clean (1 MED, 1 LOW) |
| API design | `api-review` | Clean (1 NIT, 1 LOW) |

## All Findings (Deduplicated)

| ID | Sev | Conf | Source | File:Line | Issue |
|----|-----|------|--------|-----------|-------|
| UV-1 | MED | High | correctness + simplification | IsometricMaterialComposables.kt:62 | `shape as Prism` cast in uvProvider lambda — unsafe if shape mutated |
| UV-2 | LOW | Low | simplification | UvGenerator.kt:39 | `(0..5)` hardcoded — should be `PrismFace.entries.indices` |
| UV-3 | LOW | Low | correctness | UvGeneratorTest.kt | Translated-prism test only covers FRONT face |
| UV-4 | NIT | Low | api-review | IsometricNode.kt:240 | `uvProvider` lambda type not self-documenting — consider named fun interface |

**Total:** BLOCKER: 0 | HIGH: 0 | MED: 1 | LOW: 2 | NIT: 1
*(After dedup: 4 findings merged from 8 raw findings across 4 commands)*

## Findings (Detailed)

### UV-1: Unsafe `as Prism` cast in uvProvider lambda [MED]
**Location:** `isometric-shader/.../IsometricMaterialComposables.kt:62`
**Source:** correctness (UVC-2) + simplification (UVCS-6)
**Evidence:**
```kotlin
{ shape, faceIndex -> UvGenerator.forPrismFace(shape as Prism, faceIndex) }
```
**Issue:** The lambda receives `ShapeNode.shape` at render time. If shape is mutated to a
non-Prism between recompositions, the cast throws `ClassCastException`. Both reviewers
independently flagged this.
**Fix:** Close over the captured `geometry` reference (already known to be `Prism`) instead
of re-casting the lambda argument:
```kotlin
val prism = geometry as Prism
{ _, faceIndex -> UvGenerator.forPrismFace(prism, faceIndex) }
```
This also correctly uses model-space dimensions for UV normalization (not transformed dims).
**Severity:** MED | **Confidence:** High

## Triage Decisions

| ID | Sev | Source | Decision | Notes |
|----|-----|--------|----------|-------|
| UV-1 | MED | correctness+simplification | untriaged | — |
| UV-2 | LOW | simplification | untriaged | — |
| UV-3 | LOW | correctness | untriaged | — |
| UV-4 | NIT | api-review | untriaged | — |

## Recommendations

### Consider (LOW/NIT — not triaged)
- UV-1: Fix the cast — trivial one-line change
- UV-2: Replace `(0..5)` with `PrismFace.entries.indices`
- UV-3: Add translated-prism tests for more faces
- UV-4: Named fun interface for uvProvider (optional)

## Recommended Next Stage
- **Option A (default):** `/wf-implement texture-material-shaders canvas-textures` — proceed to next slice. UV-1 can be fixed as part of the canvas-textures implementation.
- **Option B:** `/wf-implement texture-material-shaders reviews` — fix UV-1 first
- **Option C:** `/wf-handoff texture-material-shaders uv-generation` — ship as-is
