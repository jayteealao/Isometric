---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: material-types
status: complete
stage-number: 7
created-at: "2026-04-12T08:33:37Z"
updated-at: "2026-04-12T08:33:37Z"
verdict: ship-with-caveats
commands-run: [correctness, security, code-simplification, architecture]
metric-commands-run: 4
metric-findings-total: 14
metric-findings-raw: 14
metric-findings-blocker: 0
metric-findings-high: 1
metric-findings-med: 5
metric-findings-low: 5
metric-findings-nit: 0
tags: [texture, material, module]
refs:
  index: 00-index.md
  slice-def: 03-slice-material-types.md
  implement: 05-implement-material-types.md
  verify: 06-verify-material-types.md
  sub-reviews: [07-review-correctness.md, 07-review-security.md, 07-review-code-simplification.md, 07-review-architecture.md]
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders reviews"
---

# Review

## Verdict

**Ship with caveats**

One HIGH finding (SEC-1: path traversal in TextureSource.Asset) and two MED findings
(CS-1: unnecessary TexturedBuilder, SEC-2: missing runtime resource ID validation) are
triaged for fixing. No blockers. The core type system and dependency graph are sound.
Three fixes should be applied before proceeding to the next slice.

## Domain Coverage

| Domain | Command | Status |
|--------|---------|--------|
| Logic & invariants | `correctness` | Issues (1 MED, 1 LOW) |
| Vulnerabilities | `security` | Issues (1 HIGH, 1 MED, 1 LOW) |
| Complexity | `code-simplification` | Issues (2 MED, 2 LOW) |
| Module structure | `architecture` | Clean (4 notes) |

## All Findings (Deduplicated)

| ID | Sev | Conf | Source | File:Line | Issue |
|----|-----|------|--------|-----------|-------|
| SEC-1 | HIGH | High | security | TextureSource.kt:30 | Path traversal in Asset — no `..` or `/` guard |
| CS-1 | MED | Med | code-simplification | IsometricMaterial.kt:115 | TexturedBuilder unnecessary — named params suffice |
| SEC-2 | MED | Med | security | TextureSource.kt:22 | @DrawableRes is lint-only, missing require(resId > 0) |
| CR-1 | MED | High | correctness | RenderCommand.kt:56 | uvCoords nullable contentEquals — correct but fragile |
| CS-4 | MED | Med | code-simplification | UvCoord.kt:30 | UvTransform premature — no consumer yet |
| CR-2 | LOW | Med | correctness | IsometricMaterial.kt:57 | PerFace.faceMap uses Int keys — deferred to per-face slice |
| SEC-3 | LOW | Med | security | TextureSource.kt:42 | BitmapSource recycle check is construction-time only |
| CS-3 | LOW | Low | code-simplification | IsometricMaterial.kt:160 | @IsometricMaterialDsl never triggers — no nested builders |
| CS-5 | LOW | Low | code-simplification | IsometricMaterialComposables.kt | Shape/Path composable duplication |
| ARCH-1 | LOW | Med | architecture | IsometricMaterial.kt:57 | PerFace allows recursive nesting |
| ARCH-2 | LOW | Low | architecture | build.gradle.kts:36 | Redundant api(":isometric-core") in shader |
| ARCH-3 | LOW | Med | architecture | IsometricNode.kt:300 | BatchNode missing material slot |
| API-1 | MED | High | api-review | IsometricMaterial.kt:56 | PerFace allows recursive nesting — nonsense state (§6) |
| API-2 | MED | High | api-review | IsometricNode.kt:218-221 | ShapeNode.color + material are implicitly exclusive — undocumented contract (§6) |

**Total:** BLOCKER: 0 | HIGH: 1 | MED: 6 | LOW: 7 | NIT: 0

## Triage Decisions

| ID | Sev | Source | Decision | Notes |
|----|-----|--------|----------|-------|
| SEC-1 | HIGH | security | **fix** | Add path validation guards |
| CS-1 | MED | code-simplification | **fix** | Replace TexturedBuilder with named params |
| SEC-2 | MED | security | **fix** | Add require(resId > 0) |
| API-1 | MED | api-review | **fix** | Add init guard rejecting nested PerFace |
| API-2 | MED | api-review | **fix** | Document color/material contract on ShapeNode |
| CS-4 | MED | code-simplification | defer | UvTransform is part of planned API surface |
| CR-1 | MED | correctness | untriaged | — |
| CR-2 | LOW | correctness | untriaged | — |
| SEC-3 | LOW | security | untriaged | — |
| CS-3 | LOW | code-simplification | untriaged | — |
| CS-5 | LOW | code-simplification | untriaged | — |
| ARCH-1 | LOW | architecture | untriaged | — |
| ARCH-2 | LOW | architecture | untriaged | — |
| ARCH-3 | LOW | architecture | untriaged | — |

## Recommendations

### Must Fix (triaged "fix")
1. **SEC-1** — Add `require` guards in `TextureSource.Asset` init rejecting `..` components, null bytes, and leading `/`. ~5 min.
2. **CS-1** — Replace `TexturedBuilder` class with named parameters on `textured()`/`texturedAsset()`/`texturedBitmap()` factory functions. ~15 min.
3. **SEC-2** — Add `require(resId > 0)` in `TextureSource.Resource` init block. ~2 min.
4. **API-1** — Add `init { require(faceMap.values.none { it is PerFace }) }` to reject nested PerFace. ~2 min.
5. **API-2** — Document the color/material contract on `ShapeNode`: color is the base/tint, material controls painting method. Add KDoc to `ShapeNode.color` and `ShapeNode.material` explaining their relationship. ~5 min.

### Deferred
- **CS-4** — UvTransform stays. Part of the planned API surface for future slices.

### Consider (LOW — not triaged)
- CR-1, CR-2, SEC-3, CS-3, CS-5, ARCH-1, ARCH-2, ARCH-3 — 8 minor items. Address as convenient.

## Fix Status

| ID | Severity | Status | Notes |
|----|----------|--------|-------|
| SEC-1 | HIGH | Fixed | Added path traversal guards: reject `..`, leading `/`, null bytes |
| CS-1 | MED | Fixed | Replaced TexturedBuilder with named params on factory functions. Removed @IsometricMaterialDsl (CS-3 freebie) |
| SEC-2 | MED | Fixed | Added `require(resId > 0)` in Resource init block |
| API-1 | MED | Fixed | Added init guard rejecting nested PerFace in faceMap and default |
| API-2 | MED | Fixed | Added KDoc to ShapeNode/PathNode documenting color/material contract |

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders material-types` — re-verify after fixes
- **Option B:** `/wf-implement texture-material-shaders uv-generation` — proceed to next slice
- **Option C:** `/wf-handoff texture-material-shaders material-types` — all fixes applied, ship
