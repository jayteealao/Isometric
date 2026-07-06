---
schema: sdlc/v1
type: slice
slug: fresh-eyes-review-fixes
slice-slug: docs-housekeeping
status: complete
stage-number: 3
revision-count: 1
created-at: "2026-07-06T09:57:36Z"
updated-at: "2026-07-06T09:57:36Z"
complexity: s
depends-on: [depth-correctness, interaction-api-honesty, view-module]
tags: [docs, kdoc, site-mdx, housekeeping]
findings: [L1, L2, L9, L10]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-depth-correctness.md, 03-slice-interaction-api-honesty.md, 03-slice-view-module.md]
  plan: 04-plan-docs-housekeeping.md
  implement: 05-implement-docs-housekeeping.md
---

# Slice: Docs & Housekeeping

## The Slice

This slice makes the written word match the shipped code — which is why the product owner deliberately parked *all* documentation here, at the end, accepting that stale KDoc briefly coexists with fixed code. The alternative (docs inside each code slice) would have meant writing the depth-formula explanation twice: once speculatively, once again after the H3 gate settled the final wording. Depth-dependent prose is the whole reason this slice trails everything.

The content splits three ways. First, the findings that *are* docs bugs: the coordinate-system diagram claims +x goes "right-and-down" while the actual math sends both axes up the screen (L1), and `Vector.normalize()`'s KDoc promises a throw that never happens (L2). Second, the accumulated doc debt from the three code slices: the final depth formula and the observer fix in the depth-sorting explanation, the new `NoDepthSorting` = insertion-order contract, the honest config-mutability story, the `projectionVersion` requirement for custom projectors, re-verified drag guide examples (with the unchanged `onDrag` payload stated explicitly), the solid Pyramid, working TileGrid clicks under custom projectors, the L6 rotation-origin semantics, and the View module's stroke setter and redraw contract — all classified in the shape's Diátaxis documentation plan, all authored in `site/src/content/docs/**/*.mdx` and synced via `scripts/sync-docs.js`, never hand-edited in `docs/`. Third, the pure hygiene: delete the ghost `isometric-shader/`/`isometric-webgpu/` dirs and the leftover `webgpu-source.tar.gz` (L9), and move the sample app off its hardcoded Compose 1.5.0 onto the version catalog (L10).

The risk here is small but real: this is the slice where workflow vocabulary could leak into public docs, and where a sync-script misstep (hand-editing `docs/*.md`) would silently diverge the mirrors. Both have hard project rules; the verify stage checks them.

## Goal

Every public doc, KDoc, and diagram matches the shipped math and semantics; the repo carries no ghost modules, no leftover tarballs, and no version-catalog bypasses.

## Why This Slice Exists

Doc wording for the depth formula, `NoDepthSorting`, L6 semantics, and the L8 API cannot be finalized until those slices land — the PO chose one trailing docs slice over speculative per-slice docs. L9/L10 ride along as the shape's "docs & housekeeping" blast-radius group; they are order-independent and could commit any time, but grouping keeps the ledger simple.

## Scope

- **In:**
  - L1 — corrected screen-axis directions in `Point.kt`/`IsometricEngine.kt` KDoc diagrams and `getting-started/coordinate-system.mdx`.
  - L2 — `Vector.normalize()` KDoc documents the zero-vector return.
  - All KDoc corrections deferred from the code slices: `distanceToSegmentSquared` (M4), `RenderContext.withTransform` rotation-origin semantics (L6), engine depth KDoc (H3), the H1 drag KDoc that still says "linear v1 approximation".
  - Site docs per the shape's Documentation Plan: `concepts/depth-sorting.mdx`, `getting-started/coordinate-system.mdx`, `reference/engine.mdx`, `reference/scene-config.mdx`, View module docs, `guides/drag-and-camera.mdx`, `guides/interactions.mdx`, `guides/shapes.mdx`, `guides/hit-testing-escape-hatches.mdx` — authored in `site/*.mdx`, synced via `scripts/sync-docs.js`.
  - L9 — delete `isometric-shader/`, `isometric-webgpu/`, `webgpu-source.tar.gz`.
  - L10 — `app/build.gradle.kts` Compose version from the catalog; app builds and launches.
- **Out (handled by other slices / stages):**
  - Code-contract artifacts that travel with code (`@Immutable`→`@Stable` annotation changes, api dumps) — those land in their code slices; this slice only writes prose.
  - README changes and changelog/release notes — shape says not required here; ship stage owns release notes.

## Acceptance Criteria

- AC-24 — Given the shipped math and semantics from the three code slices, when the site docs (`site/*.mdx`, synced via `scripts/sync-docs.js` — never hand-edit `docs/*.md`) and the `IsometricEngine`/`Point` KDoc are read, then they match: corrected screen-axis directions, the final depth formula, the `NoDepthSorting` insertion-order contract, the honest config contract, the projector version requirement, correct drag-guide units, the solid Pyramid, and the decided rotation-origin semantics.
  <!-- observable: false — content-correctness review against the landed code plus an automated check that docs/*.md is exactly the sync output; no runtime observation -->
- AC-25 — Given `Vector.normalize()` with a zero-magnitude vector, when the KDoc is read, then it documents the zero-vector return (and no longer claims a throw).
  <!-- observable: false — KDoc content check; the behavior itself is already assertion-covered in core tests -->
- AC-26 — Given the repo root, when the housekeeping commit lands, then `isometric-shader/`, `isometric-webgpu/`, and `webgpu-source.tar.gz` are gone and the build still configures (`settings.gradle` never referenced them).
  <!-- observable: false — file-absence check + a Gradle configuration run; fully automated -->
- AC-27 — Given `app/build.gradle.kts`, when the Compose dependency comes from the version catalog, then the sample app builds and launches.
  <!-- observable: true — "launches" is a user-visible outcome on a device surface; the build half is automated, the launch half needs a live boot -->
  verify: { method: gradle assemble + android-cli install/launch + lazylogcat startup-crash check, env: Android AVD on the Windows host — boot via android-cli (same session as the slice's smoke passes), fixture: sample app main activity, rung: android-3 (build gate covers everything below it) }

## Dependencies on Other Slices

- `depth-correctness` — final depth formula and `NoDepthSorting` wording (AC-11 gate outcome feeds AC-24).
- `interaction-api-honesty` — L6 decided semantics, M3 honest-contract wording, re-verified drag guide examples.
- `view-module` — stroke-setter API and setter/redraw contract to document.
- L9/L10 have no dependencies and may commit early within this slice if tree hygiene demands it.

## Risks

- **Workflow-vocabulary leak** — public docs and commit messages must stay in product language; this slice writes the most prose, so it carries the most leak surface. The external-output boundary check runs before every commit.
- **Sync discipline** — hand-editing `docs/*.md` instead of `site/*.mdx` + `scripts/sync-docs.js` silently forks the mirrors; the AC-24 automated check (docs == sync output) is the tripwire.
- **Stale-KDoc window** — until this slice lands, fixed code (e.g. the drag path) carries outdated KDoc; accepted by the PO at slicing, but the window should stay short — this slice is small and should follow the code slices immediately.
