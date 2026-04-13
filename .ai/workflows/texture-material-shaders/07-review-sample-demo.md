---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: sample-demo
status: complete
stage-number: 7
created-at: "2026-04-13T18:07:17Z"
updated-at: "2026-04-13T18:07:17Z"
verdict: ship-with-caveats
commands-run: [correctness, security, code-simplification, performance, architecture]
metric-commands-run: 5
metric-findings-total: 13
metric-findings-raw: 30
metric-findings-blocker: 0
metric-findings-high: 2
metric-findings-med: 5
metric-findings-low: 4
metric-findings-nit: 1
tags: [sample, demo, texture, per-face, review]
refs:
  index: 00-index.md
  slice-def: 03-slice-sample-demo.md
  implement: 05-implement-sample-demo.md
  verify: 06-verify-sample-demo.md
  sub-reviews: [07-review-sample-demo-correctness.md, 07-review-sample-demo-security.md, 07-review-sample-demo-code-simplification.md, 07-review-sample-demo-performance.md, 07-review-sample-demo-architecture.md]
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders sample-demo reviews"
---

# Review: sample-demo

## Verdict

**Ship with caveats**

No blockers. Two HIGH findings: SD-2 (tint loss from IsoColor.WHITE) will cause visible rendering bugs for any caller using `texturedBitmap(bmp, tint=RED)`, and SD-3 (buffer index ordering invariant) is a latent desync risk. Both are fixable in a single implement pass. The 4 MED and 5 LOW/NIT findings are doc/cleanup improvements. All triaged for fix.

## Domain Coverage

| Domain | Command | Status |
|--------|---------|--------|
| Logic & invariants | `correctness` | Issues (2 HIGH, 3 MED, 3 LOW) |
| Vulnerabilities | `security` | Issues (1 MED, 3 LOW, 3 INFO) |
| Simplification | `code-simplification` | Issues (1 MED, 2 LOW, 2 NIT) |
| Performance | `performance` | Issues (2 MED, 5 LOW) |
| Architecture | `architecture` | Issues (2 HIGH, 3 MED, 2 LOW, 1 NIT) |

## All Findings (Deduplicated)

| ID | Sev | Conf | Source | File:Line | Issue |
|----|-----|------|--------|-----------|-------|
| SD-1 | HIGH | High | Arch | SceneProjector.kt:42 | faceType on public interface — Prism-specific leak |
| SD-2 | HIGH | High | Arch | IsometricMaterialComposables.kt:58-62 | IsoColor.WHITE drops Textured.tint |
| SD-3 | HIGH | Med | Cor | GpuTextureManager.kt:264-280 | UV buffer index ordering assumption — latent desync |
| SD-4 | MED | High | Simp | TriangulateEmitShader.kt:28-32 | Stale KDoc says UVs "emitted as constants" |
| SD-5 | MED | Med | Arch | GpuTriangulateEmitPipeline.kt | 6/8 storage buffer slots used |
| SD-6 | MED | Med | Arch | GpuTextureManager.kt | uvCoordsBuf wrong responsibility (geometry, not atlas) |
| SD-7 | MED | High | Sec | AndroidManifest.xml:30-32 | exported="true" but only launched internally |
| SD-8 | MED | Med | Perf | GpuTextureManager.kt | UV coords uploaded every frame, no dirty-check |
| SD-9 | LOW | Med | Sec+Cor | GrowableGpuStagingBuffer.kt:41-46 | Int overflow in ensureCapacity |
| SD-10 | LOW | High | Simp+Arch | TexturedDemoActivity.kt | TogglePill duplicated in same module |
| SD-11 | LOW | Med | Perf+Cor | WebGpuSceneRenderer.kt:445 | White clear color hardcoded — breaks dark mode |
| SD-12 | LOW | Low | Cor | GpuTextureManager.kt:210 | uploadTexIndexBuffer sends 2x data |
| SD-13 | NIT | Low | Simp | TexturedDemoActivity.kt | Wildcard imports + inline FQ qualifier |

**Total:** BLOCKER: 0 | HIGH: 2 | MED: 5 | LOW: 4 | NIT: 1
*(After dedup: 13 findings merged from 30 raw findings across 5 commands)*

## Triage Decisions

| ID | Sev | Source | Decision | Notes |
|----|-----|--------|----------|-------|
| SD-1 | HIGH | Arch | dismiss | Intentional design — faceType needed on interface; add KDoc only |
| SD-2 | HIGH | Arch | fix | Use material.tint for Textured, LocalDefaultColor for PerFace |
| SD-3 | HIGH | Cor | fix | Add documenting comment + require() guard |
| SD-4 | MED | Simp | fix | Delete stale KDoc section |
| SD-5 | MED | Arch | fix | Document binding count + limit |
| SD-6 | MED | Arch | fix | Extract uvCoordsBuf from GpuTextureManager |
| SD-7 | MED | Sec | fix | Set exported="false" |
| SD-8 | MED | Perf | fix | Add scene-version dirty check |
| SD-9 | LOW | Sec | fix | Long arithmetic in ensureCapacity |
| SD-10 | LOW | Simp | fix | Extract TogglePill to shared file |
| SD-11 | LOW | Perf | fix | Make clear color configurable via SceneConfig |
| SD-12 | LOW | Cor | fix | Apply rewind/limit pattern to uploadTexIndexBuffer |
| SD-13 | NIT | Simp | fix | Replace wildcard imports, add IsometricMaterial import |

## Recommendations

### Must Fix (HIGH triaged "fix")
- **SD-2**: Use `material.tint` for `Textured`, `LocalDefaultColor.current` for `PerFace` — ~5 min
- **SD-3**: Add invariant comment + `require(faceCount <= scene.commands.size)` — ~5 min

### Should Fix (MED triaged "fix")
- **SD-4**: Delete stale KDoc section in TriangulateEmitShader — ~2 min
- **SD-5**: Add comment documenting 6/8 binding limit — ~2 min
- **SD-6**: Extract uvCoordsBuf to separate class — ~15 min
- **SD-7**: Set `exported="false"` in manifest — ~1 min
- **SD-8**: Add dirty-check for per-frame buffer uploads — ~10 min

### Fix (LOW/NIT)
- **SD-9**: Long arithmetic in GrowableGpuStagingBuffer — ~5 min
- **SD-10**: Extract TogglePill to SampleUiComponents.kt — ~5 min
- **SD-11**: Make clear color configurable via SceneConfig — ~15 min
- **SD-12**: Fix uploadTexIndexBuffer over-send — ~2 min
- **SD-13**: Wildcard imports cleanup — ~2 min

### Dismissed
- **SD-1**: faceType on SceneProjector.add() — intentional, add KDoc docs only

## Recommended Next Stage
- **Option A (default):** `/wf-implement texture-material-shaders sample-demo reviews` — fix all 12 triaged findings
- **Option B:** `/compact` then Option A — clear review context (recommended)
- **Option C:** `/wf-handoff texture-material-shaders sample-demo` — ship with caveats, fix later
