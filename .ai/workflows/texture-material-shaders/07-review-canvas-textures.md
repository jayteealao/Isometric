---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: canvas-textures
status: complete
stage-number: 7
created-at: "2026-04-12T15:00:38Z"
updated-at: "2026-04-12T15:00:38Z"
verdict: ship-with-caveats
commands-run: [correctness, security, code-simplification, architecture]
metric-commands-run: 4
metric-findings-total: 20
metric-findings-raw: 33
metric-findings-blocker: 0
metric-findings-high: 4
metric-findings-med: 9
metric-findings-low: 4
metric-findings-nit: 3
tags: [canvas, texture, rendering]
refs:
  index: 00-index.md
  slice-def: 03-slice-canvas-textures.md
  implement: 05-implement-canvas-textures.md
  verify: 06-verify-canvas-textures.md
  sub-reviews: [07-review-canvas-textures-correctness.md, 07-review-canvas-textures-security.md, 07-review-canvas-textures-code-simplification.md, 07-review-canvas-textures-architecture.md]
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders reviews"
---

# Review: Canvas Textured Rendering

## Verdict

**Ship with caveats**

No blockers. 4 HIGH findings triaged for fixing: paint state leak (CT-CR-3), OOM swallowing
(CT-SEC-1), per-frame native path allocation (CT-CS-1), and SceneItem FloatArray equals
(CT-ARCH-3). All are straightforward fixes. 5 MED findings also triaged for fixing including
screenPoints guard, redundant when arms, BitmapSource cache key, failed texture caching, and
white-tint threshold. Remaining MEDs deferred to later slices. The MaterialDrawHook architecture
is sound and dependency direction is clean.

## Domain Coverage

| Domain | Command | Status |
|--------|---------|--------|
| Correctness | `correctness` | Issues (3 HIGH, 4 MED, 1 LOW, 2 NIT) |
| Security | `security` | Issues (1 HIGH, 2 MED, 2 LOW, 2 INFO) |
| Code Simplification | `code-simplification` | Issues (1 HIGH, 2 MED, 2 LOW) |
| Architecture | `architecture` | Issues (1 HIGH, 3 MED, 1 LOW, 2 INFO) |

## All Findings (Deduplicated)

| ID | Sev | Source | File:Line | Issue |
|----|-----|--------|-----------|-------|
| CT-CR-3 | HIGH | correctness+security | TexturedCanvasDrawHook.kt:72-82 | Paint state leak — no try/finally around drawPath |
| CT-SEC-1 | HIGH | security | TextureLoader.kt:30-35 | runCatching swallows OOM (Throwable not Exception) |
| CT-CS-1 | HIGH | code-simp+correctness+arch | IsometricRenderer.kt:473-478 | Per-frame native path allocation in DrawScope hook path |
| CT-ARCH-3 | HIGH | architecture | SceneGraph.kt:8-16 | SceneItem data class with FloatArray — broken equals |
| CT-CR-6 | MED | correctness | TexturedCanvasDrawHook.kt:109 | No guard on screenPoints.size < 6 |
| CT-CR-7 | MED | correctness | TextureCache.kt:44 | BitmapSource cache key uses Bitmap reference identity |
| CT-CR-4 | MED | correctness | TexturedCanvasDrawHook.kt:88-90 | Failed texture loads permanently cached as checkerboard |
| CT-CS-3 | MED | code-simp | IsometricRenderer.kt:483-490 | Redundant when arms in materialHandled stroke branch |
| CT-ARCH-9 | MED | architecture+security | TexturedCanvasDrawHook.kt:64-65 | Textured material + null uvCoords silently falls back |
| CT-SEC-2 | MED | security | TextureLoader.kt | No bitmap size limit — large textures can OOM |
| CT-SEC-3 | MED | security | TextureCache.kt | Cache bounded by count not memory bytes |
| CT-ARCH-7 | MED | architecture | SceneProjector.kt:32-40 | Interface default params not binary-safe for external impls |
| CT-CR-1 | MED | correctness | TexturedCanvasDrawHook.kt | uvTransform field is dead code in render path |
| CT-CR-8 | LOW | correctness+code-simp | TexturedCanvasDrawHook.kt:152 | White-tint threshold >= 254 should be >= 255 |
| CT-SEC-4 | LOW | security | TextureCache.kt | Cached BitmapSource bitmap can be recycled externally |
| CT-SEC-6 | LOW | security | ProvideTextureRendering.kt | maxCacheSize has no upper bound guard |
| CT-ARCH-6 | LOW | architecture | ProvideTextureRendering.kt | Wrong-nesting pitfall — no runtime warning |
| CT-CR-9 | NIT | correctness | IsometricMaterialComposables.kt:69 | UvCoordProvider lambda ignores shape parameter |
| CT-CR-10 | NIT | correctness | ProvideTextureRendering.kt:36 | remember keyed on Activity context, not applicationContext |
| CT-SEC-8 | NIT | security | TextureSource.kt | Asset path backslash validation mismatch |

**Total:** BLOCKER: 0 | HIGH: 4 | MED: 9 | LOW: 4 | NIT: 3
*(After dedup: 20 findings merged from 33 raw findings across 4 commands)*

## Triage Decisions

| ID | Sev | Source | Decision | Notes |
|----|-----|--------|----------|-------|
| CT-CR-3 | HIGH | correctness | **fix** | try/finally for paint state |
| CT-SEC-1 | HIGH | security | **fix** | try/catch(Exception), re-throw Error |
| CT-CS-1 | HIGH | code-simp | **fix** | native path pool or reuse |
| CT-ARCH-3 | HIGH | architecture | **fix** | override equals or remove data class |
| CT-CR-6 | MED | correctness | **fix** | add screenPoints.size guard |
| CT-CR-7 | MED | correctness | **fix** | document BitmapSource identity semantics |
| CT-CR-4 | MED | correctness | **fix** | don't cache checkerboard under original key |
| CT-CS-3 | MED | code-simp | **fix** | merge identical when arms |
| CT-CR-8 | LOW | correctness | **fix** | user requested: threshold >= 255 |
| CT-ARCH-9 | MED | architecture | defer | per-face slice will add face-level resolution |
| CT-SEC-2 | MED | security | defer | async preloading scope |
| CT-SEC-3 | MED | security | defer | byte-budget cache is future enhancement |
| CT-ARCH-7 | MED | architecture | defer | interface not yet published as stable |
| CT-CR-1 | MED | correctness | defer | uvTransform used when UV generation extended |
| CT-SEC-4 | LOW | security | untriaged | — |
| CT-SEC-6 | LOW | security | untriaged | — |
| CT-ARCH-6 | LOW | architecture | untriaged | — |
| CT-CR-9 | NIT | correctness | untriaged | — |
| CT-CR-10 | NIT | correctness | untriaged | — |
| CT-SEC-8 | NIT | security | untriaged | — |

## Recommendations

### Must Fix (9 findings)
1. **CT-CR-3** — try/finally for paint state in TexturedCanvasDrawHook.drawTextured (~2 min)
2. **CT-SEC-1** — replace runCatching with try/catch(Exception) in TextureLoader (~2 min)
3. **CT-CS-1** — native path pool in IsometricRenderer.renderPreparedScene (~15 min)
4. **CT-ARCH-3** — remove data class from SceneItem or override equals/hashCode (~5 min)
5. **CT-CR-6** — add screenPoints.size < 6 guard in computeAffineMatrix (~1 min)
6. **CT-CR-7** — document BitmapSource identity; consider warn on non-remembered bitmap (~5 min)
7. **CT-CR-4** — cache checkerboard under a sentinel key, not the failed source key (~5 min)
8. **CT-CS-3** — merge redundant Stroke/FillAndStroke arms (~1 min)
9. **CT-CR-8** — threshold >= 255.0 instead of >= 254.0 (~1 min)

### Deferred (5 findings)
- CT-ARCH-9, CT-SEC-2, CT-SEC-3, CT-ARCH-7, CT-CR-1

### Consider (LOW/NIT — 6 findings, untriaged)
- CT-SEC-4, CT-SEC-6, CT-ARCH-6, CT-CR-9, CT-CR-10, CT-SEC-8

## Fix Status

| ID | Severity | Status | Notes |
|----|----------|--------|-------|
| CT-CR-3 | HIGH | Fixed | try/finally around drawPath for paint state cleanup |
| CT-SEC-1 | HIGH | Fixed | Replaced runCatching with try/catch(Exception) |
| CT-CS-1 | HIGH | Fixed | Added nativePathPool + fillNativePath, merged redundant when arms (CT-CS-3) |
| CT-ARCH-3 | HIGH | Fixed | Removed `data` keyword from SceneItem |
| CT-CR-6 | MED | Fixed | Added screenPoints.size < 6 guard in computeAffineMatrix |
| CT-CR-7 | MED | Fixed | Documented BitmapSource identity semantics in TextureCache KDoc |
| CT-CR-4 | MED | Fixed | Checkerboard no longer cached under source key; uses dedicated checkerboardCached field |
| CT-CS-3 | MED | Fixed | Merged Stroke/FillAndStroke when arms (done as part of CT-CS-1) |
| CT-CR-8 | LOW | Fixed | Threshold changed from >= 254.0 to >= 255.0; test updated |

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders canvas-textures` — re-verify after fixes
- **Option B:** `/compact` then Option A
- **Option C:** `/wf-next` — route to next slice
