# Diataxis Documentation Review — Isometric Docs Site

**Date:** 2026-04-10
**Reviewer:** Claude (automated Diataxis audit)
**Scope:** All 30 pages in `site/src/content/docs/`

---

## System-Level Assessment

**Start point:** Clear. Installation -> Quick Start -> Coordinate System is a well-sequenced onramp.

**Task route (how-to):** Scattered. How-to content is mixed into guide pages, example pages, and reference pages rather than living in a consistent place. A user looking for "how do I add tap handling?" must guess whether to look in Guides/Gestures, Examples/Interactive Scenes, or Reference/Composables.

**Lookup route (reference):** Good. The Reference section has four clean pages. But reference-grade parameter tables also appear in 5 of the 8 guide pages, diluting both.

**Conceptual route (explanation):** Strong. The three Concepts pages are well-bounded explanation content.

**Landing pages:** No section landing pages. The sidebar groups pages but never summarizes what each section offers.

---

## Per-Page Reviews

### Getting Started

#### Installation

| Dimension | Assessment |
|---|---|
| Current type | How-to |
| Ideal type | How-to |
| Audience | New user |
| What works | Clear composite-build instructions, requirements table, module descriptions |
| What is wrong | **States library is "not yet published to Maven Central"** — this is factually wrong since v1.1.0 was released. The "Future: Maven Central" section should be the primary path, and composite build should be the alternative. |
| Boundary violations | None |
| Severity | **Critical** — the primary installation path is wrong |
| Recommended fix | Flip the page: lead with Maven Central coordinates (`1.1.0`), demote composite build to a "Development / Unreleased" subsection |

#### Quick Start

| Dimension | Assessment |
|---|---|
| Current type | Tutorial |
| Ideal type | Tutorial |
| Audience | Beginner |
| What works | Progressive steps (one shape -> multiple -> groups -> interaction), each step builds on the last, good "Next Steps" links |
| What is wrong | Minor: Step 2 uses `IsoColor(33, 150, 243)` integer constructor but the note says "IsoColor takes RGB integer values" — `IsoColor` actually takes `Double`, not `Int`. The constructor auto-converts, but the comment is misleading. |
| Boundary violations | None — stays in its lane |
| Severity | Minor |
| Recommended fix | Change the note to say "IsoColor takes numeric values (0-255)" rather than "integer values" |

#### Coordinate System

| Dimension | Assessment |
|---|---|
| Current type | Explanation |
| Ideal type | Explanation |
| Audience | Beginner to intermediate |
| What works | Excellent explanation page. Covers projection math, origin, scale, depth formula, tile coordinates. Well-bounded — no procedures embedded. |
| What is wrong | Nothing significant |
| Boundary violations | None |
| Severity | N/A |
| Recommended fix | None |

---

### Guides

#### Shapes

| Dimension | Assessment |
|---|---|
| Current type | Mixed reference/how-to |
| Ideal type | Reference (shape catalog) + how-to (extrusion) |
| Audience | Competent user |
| What works | Shape catalog table, transform method coverage |
| What is wrong | Two distinct purposes on one page: API catalog (reference) and "how to extrude a custom shape" (how-to). The extrude section is a procedure, not a reference entry. |
| Boundary violations | How-to procedure embedded in reference content |
| Severity | Minor |
| Recommended fix | Reduce the extrude section to a one-line mention and link to Custom Shapes guide, which already covers this. Currently there is overlap between Shapes "Extruding 2D Paths" and Custom Shapes "Shape.extrude". |

#### Animation

| Dimension | Assessment |
|---|---|
| Current type | How-to |
| Ideal type | How-to |
| Audience | Competent user |
| What works | Two clean, copy-paste recipes. Performance section is brief and relevant. |
| What is wrong | Very thin — only two examples. The Animation Patterns example page has 6 more. There is no guidance on choosing between animation approaches or combining animations. |
| Boundary violations | The Performance section is explanation, but it is short enough to be useful context rather than a violation |
| Severity | Minor |
| Recommended fix | Add a sentence linking to Animation Patterns for more recipes. Consider moving the "Performance" subsection to a :::tip admonition to signal it is a sidebar, not core content. |

#### Gestures

| Dimension | Assessment |
|---|---|
| Current type | How-to with reference tables |
| Ideal type | How-to |
| Audience | Competent user |
| What works | Clear task coverage (tap, drag, tile routing), good GestureConfig example, the :::caution about onTap + onTileClick double-firing is excellent |
| What is wrong | The TapEvent/DragEvent parameter tables are reference content. They duplicate what is in Reference/Scene Config. |
| Boundary violations | Reference tables in a how-to page |
| Severity | Minor |
| Recommended fix | Keep the tables (they are useful inline) but add a cross-link: "See [Scene Config reference](/reference/scene-config) for the complete GestureConfig API." |

#### Theming & Colors

| Dimension | Assessment |
|---|---|
| Current type | Reference |
| Ideal type | Reference |
| Audience | Competent user |
| What works | Thorough IsoColor API coverage, CompositionLocals table, StrokeStyle variants, Compose interop |
| What is wrong | The "Lighting" section at the bottom is explanation, not reference. It discusses why light direction matters and what the default produces visually. |
| Boundary violations | Explanation section in reference page |
| Severity | Minor |
| Recommended fix | Move the Lighting explanation to a :::note or link to Coordinate System/Rendering Pipeline where lighting context fits better. Keep only the API surface (parameter name, type, default) in Theming. |

#### Camera & Viewport

| Dimension | Assessment |
|---|---|
| Current type | Mixed reference/how-to |
| Ideal type | How-to (with reference link) |
| Audience | Competent user |
| What works | Clean CameraState API coverage, two good working examples |
| What is wrong | The constructor and properties tables duplicate Reference/Scene Config content. The page tries to be both API reference and usage guide. |
| Boundary violations | Reference tables in a guide page |
| Severity | Minor |
| Recommended fix | Tolerable as-is. The page is short enough that the reference tables provide useful context. Add a note: "For the complete CameraState API, see [Scene Config reference](/reference/scene-config)." |

#### Custom Shapes

| Dimension | Assessment |
|---|---|
| Current type | How-to |
| Ideal type | How-to |
| Audience | Advanced user |
| What works | Three clear approaches ordered by complexity, good code examples, clear recommendation to prefer simpler approaches |
| What is wrong | Overlaps with Shapes guide "Extruding 2D Paths" section |
| Boundary violations | None |
| Severity | Minor |
| Recommended fix | In the Shapes guide, reduce the extrude section to a one-line mention + link to this page |

#### Stack

| Dimension | Assessment |
|---|---|
| Current type | How-to |
| Ideal type | How-to |
| Audience | Competent user |
| What works | Good progressive disclosure (basic -> axis -> gap -> nested -> TileGrid combo), clear recommendations |
| What is wrong | Nothing significant |
| Boundary violations | None |
| Severity | N/A |
| Recommended fix | None |

#### Tile Grid

| Dimension | Assessment |
|---|---|
| Current type | How-to with explanation |
| Ideal type | How-to |
| Audience | Competent user |
| What works | Good task-oriented structure, the Tap Accuracy with Elevation section is genuinely useful |
| What is wrong | The "Tap Accuracy with Elevation" section is heavy explanation (why flat-grid routing fails, how isometric projection causes misattribution). This is valuable content but it is explanation living inside a how-to. |
| Boundary violations | Explanation embedded in how-to |
| Severity | Minor |
| Recommended fix | Tolerable — the explanation is directly in service of the how-to task. If the section grows, consider extracting to a Concepts page. |

#### Performance

| Dimension | Assessment |
|---|---|
| Current type | Mixed explanation/reference |
| Ideal type | How-to (when to optimize) with reference tables |
| Audience | Intermediate to advanced |
| What works | Good overview of the three performance systems, clear "When to Optimize" decision table, anti-patterns section |
| What is wrong | The "Performance Model Overview" is explanation. The RenderOptions table is reference. The "When to Optimize" table is how-to guidance. Three types on one page. |
| Boundary violations | Explanation + reference + how-to on one page |
| Severity | Minor — the page is well-organized despite mixing types |
| Recommended fix | Tolerable because the page is coherent and not overlong. The RenderOptions table overlaps with Reference/Scene Config — add a cross-link. |

#### Compose Interop

| Dimension | Assessment |
|---|---|
| Current type | How-to |
| Ideal type | How-to |
| Audience | Competent user |
| What works | Covers the right tasks: layout, state, gestures, overlay, theming, lifecycle. Good quick-reference table at bottom. |
| What is wrong | The "Stabilizing Config to Prevent Rebuilds" section leans into explanation (why `remember` matters for engine instances). |
| Boundary violations | Minor explanation in how-to |
| Severity | Minor |
| Recommended fix | None needed — the explanation is in service of the task |

#### Advanced Config

| Dimension | Assessment |
|---|---|
| Current type | Reference with how-to examples |
| Ideal type | How-to (task-oriented) or reference (API-oriented) — currently both |
| Audience | Advanced user |
| What works | Lifecycle hooks table is excellent reference, each section has a working example |
| What is wrong | The page structure is feature-oriented (one H2 per API feature), which is reference-style. But the prose and examples are task-oriented ("Debug Overlay Example", "Engine Access"). Neither fish nor fowl. |
| Boundary violations | Mixed reference/how-to |
| Severity | Minor |
| Recommended fix | Reframe headings as tasks ("Drawing debug overlays", "Handling render errors", "Accessing the engine outside the scene") to commit to how-to. Move the lifecycle hooks table to Reference/Scene Config. |

---

### Concepts

#### Scene Graph

| Dimension | Assessment |
|---|---|
| Current type | Explanation |
| Ideal type | Explanation |
| Audience | Intermediate to advanced |
| What works | Excellent architecture explanation. Three-layer diagram, node type table, dirty tracking walkthrough, RenderContext |
| What is wrong | The "High-Level vs Low-Level" section includes procedural code (ComposeNode usage, standalone engine usage) that reads like how-to content |
| Boundary violations | How-to procedures in explanation |
| Severity | Minor |
| Recommended fix | Keep the code as illustration of what is possible, but remove the "direct node manipulation" ComposeNode example or link to Advanced Patterns where it belongs |

#### Depth Sorting

| Dimension | Assessment |
|---|---|
| Current type | Explanation |
| Ideal type | Explanation |
| Audience | Intermediate |
| What works | Clear pipeline explanation, good ASCII diagrams, honest about limitations, tuning guidance |
| What is wrong | The "Disabling Depth Sort" and "Tuning broadPhaseCellSize" sections cross into how-to territory |
| Boundary violations | How-to procedures in explanation |
| Severity | Minor |
| Recommended fix | Tolerable — the tuning guidance is brief and directly serves understanding. Link to Performance guide for the full how-to context. |

#### Rendering Pipeline

| Dimension | Assessment |
|---|---|
| Current type | Explanation |
| Ideal type | Explanation |
| Audience | Intermediate to advanced |
| What works | End-to-end flow diagram is excellent, cache invalidation table is clear, PreparedScene coverage enables advanced usage |
| What is wrong | The "Path Caching" and "Native Canvas Path" sections are thinly veiled how-to/reference content (when to enable each flag). They duplicate Performance guide content. |
| Boundary violations | Reference/how-to in explanation |
| Severity | Minor |
| Recommended fix | Reduce Path Caching and Native Canvas to brief mentions with links to Performance guide |

---

### Reference

#### Composables

| Dimension | Assessment |
|---|---|
| Current type | Reference |
| Ideal type | Reference |
| Audience | All levels |
| What works | Clean parameter tables for every composable, code examples are minimal and illustrative |
| What is wrong | The `Path` name collision note is a how-to concern, not reference. Minor. |
| Boundary violations | None significant |
| Severity | N/A |
| Recommended fix | None |

#### Scene Config

| Dimension | Assessment |
|---|---|
| Current type | Reference |
| Ideal type | Reference |
| Audience | All levels |
| What works | Clean tables for SceneConfig, RenderOptions, AdvancedSceneConfig, StrokeStyle |
| What is wrong | The "When to use AdvancedSceneConfig" section at the bottom is recommendation/opinion. |
| Boundary violations | Opinion in reference |
| Severity | Minor |
| Recommended fix | Move the "When to use" guidance to Advanced Config guide. Keep reference tables clean. |

#### CompositionLocals

| Dimension | Assessment |
|---|---|
| Current type | Reference with explanation |
| Ideal type | Reference |
| Audience | Intermediate |
| What works | Reference table, override examples, per-subtree theming example |
| What is wrong | "Why staticCompositionLocalOf?" is pure explanation (and good explanation). It does not belong in reference. |
| Boundary violations | Explanation in reference |
| Severity | Minor |
| Recommended fix | Move the "Why staticCompositionLocalOf?" section to Scene Graph or Rendering Pipeline concept pages, or collapse to a one-sentence note with a link |

#### Engine & Projector

| Dimension | Assessment |
|---|---|
| Current type | Reference |
| Ideal type | Reference |
| Audience | Intermediate to advanced |
| What works | Complete API surface, parameter tables, tile coordinate helpers, SceneProjector interface |
| What is wrong | "Direct Usage Outside Compose" is a how-to procedure. The section is useful but crosses the boundary. |
| Boundary violations | How-to in reference |
| Severity | Minor |
| Recommended fix | Keep a brief note about standalone usage, link to Advanced Patterns where the full recipe lives |

---

### Examples

#### Basic Scenes / Animation Patterns / Interactive Scenes / Advanced Patterns

| Dimension | Assessment |
|---|---|
| Current type | How-to (cookbook recipes) |
| Ideal type | How-to (cookbook recipes) |
| Audience | All levels |
| What works | All four are well-structured as self-contained, copy-paste recipes. Good progressive complexity across the section. |
| What is wrong | Advanced Patterns overlaps with Advanced Config guide (lifecycle hooks, engine access). |
| Boundary violations | Duplication across sections |
| Severity | Minor |
| Recommended fix | In Advanced Config, link to Advanced Patterns for the worked examples rather than duplicating them |

---

### FAQ

| Dimension | Assessment |
|---|---|
| Current type | Troubleshooting how-to |
| Ideal type | FAQ / troubleshooting |
| Audience | All levels |
| What works | Concise, problem-oriented entries |
| What is wrong | **"Is this published to Maven Central?" says "Not yet"** — factually wrong since v1.1.0. The import paths reference `io.fabianterhorst.isometric` — the old package name before the rewrite. |
| Boundary violations | None |
| Severity | **Critical** — two factual errors that will confuse users |
| Recommended fix | Update Maven Central answer to "Yes — see Installation." Fix import paths to `io.github.jayteealao.isometric`. |

---

### Migration

#### View -> Compose

| Dimension | Assessment |
|---|---|
| Current type | How-to |
| Ideal type | How-to |
| Audience | User migrating from legacy API |
| What works | Side-by-side before/after code for every aspect, comprehensive key differences table |
| What is wrong | Uses old package name `io.fabianterhorst.isometric` in the "Before" examples — this is correct (it was the old name) but should have a note explaining the package rename |
| Boundary violations | None |
| Severity | Minor |
| Recommended fix | Add a note at the top: "The View API used the `io.fabianterhorst.isometric` package. The Compose API uses `io.github.jayteealao.isometric`." |

---

### Contributing

#### Development Setup / Testing / Documentation Guide

| Dimension | Assessment |
|---|---|
| Current type | How-to |
| Ideal type | How-to |
| Audience | Contributor |
| What works | All three are well-structured, task-oriented, and practical |
| What is wrong | Testing page references old package `io.fabianterhorst.isometric` for test locations — this is factually correct (test files are there) but the note about "WS9 and later test files use the updated package" is good. |
| Boundary violations | None |
| Severity | N/A |
| Recommended fix | None significant |

---

## System-Level Findings

### Missing doc types

1. **No installation page for the published library** — Installation still describes composite builds as the primary path
2. **No changelog or release notes page** — `CHANGELOG.md` exists but is not linked from the site
3. **No "Concepts: How IsometricScene Works"** explanation — the Compose interop guide covers the practical "how" but there is no explanation of why `IsometricScene` uses a separate composition, custom applier, and sub-composition pattern

### Duplicates and overlaps

| Content | Appears in |
|---|---|
| RenderOptions parameter table | Performance guide, Scene Config reference |
| Shape.extrude procedure | Shapes guide, Custom Shapes guide |
| CameraState API | Camera guide, Scene Config reference |
| Lifecycle hooks examples | Advanced Config guide, Advanced Patterns examples |
| StrokeStyle variants | Theming guide, Scene Config reference |
| Engine standalone usage | Engine reference, Advanced Patterns examples |
| `staticCompositionLocalOf` explanation | CompositionLocals reference, (implicitly) Scene Graph concept |

### Proposed information architecture

The current architecture is sound. No major reorganization needed. The main issue is boundary discipline within pages, not the structure itself.

---

## Priority Fixes

### Fix 1: Factual errors in Installation and FAQ (Critical)

**Installation page:** Rewrite to lead with Maven Central coordinates for v1.1.0. Demote composite build to a "Development / Unreleased" subsection.

**FAQ:** Update "Is this published to Maven Central?" to "Yes." Fix import paths from `io.fabianterhorst.isometric` to `io.github.jayteealao.isometric`.

### Fix 2: Deduplicate reference tables (Major)

Pick one home for each parameter table. The Reference section should be the canonical source. Guide pages should show a brief example and link to Reference for the full table. Specific actions:

- Remove RenderOptions table from Performance guide, link to Scene Config reference
- Remove CameraState constructor/properties tables from Camera guide, link to Scene Config reference
- Remove StrokeStyle variants table from Theming guide, link to Scene Config reference
- Remove the "When to use AdvancedSceneConfig" opinion from Scene Config reference, keep it in Advanced Config guide

### Fix 3: Reduce explanation leakage in reference pages (Minor)

- Move "Why staticCompositionLocalOf?" from CompositionLocals reference to Scene Graph concept page
- Move "Direct Usage Outside Compose" from Engine reference to a brief note + link to Advanced Patterns
- Move Lighting explanation from Theming reference to Rendering Pipeline concept page

---

## Severity Summary

| Severity | Count | Pages |
|---|---|---|
| Critical | 2 | Installation, FAQ |
| Major | 0 | — |
| Minor | 18 | Most guide and reference pages (boundary discipline, duplication) |
| Clean | 10 | Coordinate System, Stack, Composables ref, Basic/Animation/Interactive examples, Contributing (3), Quick Start |

---

## Overall Verdict

The docs site is **well-organized and mostly well-written**. The Diataxis quadrants are represented: tutorials (Quick Start), how-to guides (Guides + Examples), reference (Reference section), and explanation (Concepts). The two critical issues are factual staleness (Installation and FAQ not updated after Maven Central publication). The minor issues are consistent boundary violations where reference tables leak into guide pages and explanation leaks into reference pages. These are common and do not severely harm usability — they are refinement work, not structural problems.
