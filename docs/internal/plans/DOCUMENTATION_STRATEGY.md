# Isometric Library — Documentation Strategy Plan

> Produced 2026-03-16.
> Audience: library maintainer(s), future contributors, documentation implementers.

---

## 1. Executive Summary

**What the library is:** Isometric is a Kotlin isometric drawing library for Android that provides a Compose Runtime–level API (`IsometricScene`) for building interactive, hierarchically-transformable 3D isometric scenes with high performance, plus a legacy Android View API.

**Recommended documentation stack:**

| Layer | Choice | Rationale |
|-------|--------|-----------|
| Docs authoring format | MDX in-repo (`docs/`) | Supports components, Astro-native, diffable in PRs |
| Docs framework | **Astro + Starlight** | Turnkey search (Pagefind), sidebar, dark mode, MDX, splash pages for landing; avoids building those from scratch |
| API reference | **Dokka 2.x HTML** served at `/api/` | Most stable Dokka output format; embedded as static assets in Starlight site |
| Deployment | **GitHub Pages** via GitHub Actions | Free, fits open-source, co-located with the repo |
| Source of truth | **In-repo `docs/` folder** consumed directly by Astro during build | Single PR updates both code and docs; no sync fragility |

**Biggest documentation problems today:**

1. **`IsometricCanvas` doesn't exist** — the README's "High-Level API" references a composable that has no source implementation. This misleads every reader.
2. **Installation coordinates are wrong** — README shows `io.fabianterhorst:isometric-compose:0.1.0`; the library isn't published at all yet, and the intended group is `io.github.jayteealao`.
3. **No getting-started path** — the README is simultaneously a marketing page, tutorial, reference, and changelog. A new user cannot follow a clear "install → first scene → next steps" flow.
4. **External image links are broken** — all screenshots point to the upstream fork's `lib/screenshots/` which may not exist in this repo.
5. **Internal-only docs are mixed with user docs** — `docs/plans/`, `docs/reviews/`, `docs/research/` are development artifacts, not user documentation.
6. **No CI, no publishing, no CHANGELOG** — the project cannot be consumed as a dependency yet.

**Highest-value changes (in order):**

1. Remove `IsometricCanvas` references or clearly label it as unimplemented.
2. Rewrite the README as a focused entry point: pitch → install → quickstart → link to docs.
3. Create a "Getting Started" tutorial built around `IsometricScene`.
4. Set up Astro + Starlight site consuming `docs/` content.
5. Add Dokka API reference generation.

---

## 2. Codebase Understanding

### What the library does

Isometric provides an isometric (2.5D) rendering engine in pure Kotlin and a Jetpack Compose integration that lets Android developers declaratively build interactive isometric scenes. The core engine handles:

- Isometric projection (configurable angle/scale)
- Depth sorting with broad-phase optimization
- Back-face culling
- Hit testing with spatial indexing
- Lighting/shading based on face normals

The Compose integration adds:

- A custom Compose Runtime `Applier` and node tree (`IsometricScene`, `Shape`, `Group`, `Path`, `Batch`, `If`, `ForEach`)
- Hierarchical transform accumulation (`RenderContext`)
- Dirty tracking and prepared-scene caching
- Gesture handling (tap, drag)
- Camera pan/zoom via `CameraState`
- Native Android Canvas rendering path (~2× faster)
- Escape hatches (`CustomNode`, `AdvancedSceneConfig`, `ComposeNode` primitives)

### Target audiences

| Audience | Profile |
|----------|---------|
| **Primary** | Android developers using Jetpack Compose who want isometric visualizations (data viz, game UIs, architectural mockups, creative apps) |
| **Secondary** | Android developers on the legacy View system migrating to Compose |
| **Tertiary** | Kotlin/JVM developers interested in the platform-agnostic core engine |
| **Future** | Compose Multiplatform developers (Desktop, iOS) — the core is ready, the Compose module is Android-only |

### API shape and mental model

Users need to understand:

1. **Coordinate system** — x = right-down, y = left-down, z = up. Not screen coordinates.
2. **Geometry hierarchy** — `Point` → `Path` (face polygon) → `Shape` (multi-face solid). All immutable, all transforms return new instances.
3. **Scene composition** — `IsometricScene { Shape(...) / Group(...) { ... } }`. Groups accumulate transforms.
4. **Colors** — `IsoColor` (not Compose `Color`). Automatic face shading from light direction.
5. **Configuration** — `SceneConfig` for standard use, `AdvancedSceneConfig` for power users.
6. **Two rendering backends** — Compose path (cross-platform) vs native canvas (Android-only, faster).

### Complexity hotspots and likely pain points

| Area | Issue |
|------|-------|
| `IsoColor` vs `Color` | Users will instinctively use `androidx.compose.ui.graphics.Color`; they must learn `IsoColor` or use `.toIsoColor()` |
| Coordinate system | Isometric coordinates are unintuitive; where is (0,0,0) on screen? |
| `Path` name collision | `io.fabianterhorst.isometric.Path` vs `androidx.compose.ui.graphics.Path` — snapshot tests already show the import alias pattern |
| Depth sorting artifacts | Known issues with `Knot` and complex overlapping geometry |
| Missing `IsometricCanvas` | README documents it; source code doesn't have it |
| Package names | Still `io.fabianterhorst`; will change before publication |
| No publication | Cannot `implementation(...)` this library yet |

### What must be documented first

1. How to install (once published)
2. How to render a first scene with `IsometricScene`
3. The coordinate system (with a visual diagram)
4. Available shapes and their parameters
5. How to animate
6. How to handle user interaction

---

## 3. Documentation Goals

| Goal | Metric |
|------|--------|
| **Fast first success** | A developer can render their first isometric scene within 5 minutes of reading the quickstart |
| **Clear mental model** | The coordinate system, geometry hierarchy, and scene composition model are explained with diagrams before any advanced feature |
| **Discoverable task guides** | Common tasks (animation, gestures, custom shapes, theming) each have a dedicated guide findable via search and sidebar |
| **Trustworthy reference** | Every public API class/function has KDoc; Dokka-generated reference is accessible but doesn't overwhelm the human docs |
| **Contributor friendliness** | A contributor can set up the project and run tests within 10 minutes; the contribution process is documented |
| **Version-aware maintenance** | Docs are tagged to releases; breaking changes have migration guides |
| **SEO/discoverability** | The docs site ranks for "kotlin isometric library", "android isometric compose", "isometric drawing android" |
| **Beautiful docs UX** | The landing page showcases the library visually (isometric renders); the docs feel professional and trustworthy |

---

## 4. Audience Segmentation

### First-time evaluators
**Need:** "What is this? Is it worth my time?"
**Content:** Landing page hero, feature overview, visual demos, comparison with alternatives (if any), trust signals (test count, license, architecture quality).

### First-time users
**Need:** "How do I install this and make something work?"
**Content:** Installation guide, quickstart tutorial, coordinate system explanation, first interactive example.

### Returning users
**Need:** "How do I do X?" (animation, gestures, custom shapes, theming)
**Content:** Task-oriented how-to guides, searchable by topic, code-heavy.

### Advanced users
**Need:** "How do I push this further?" (custom nodes, native canvas, benchmark hooks, escape hatches)
**Content:** Advanced guides (primitive levels, `AdvancedSceneConfig`, `CustomNode`), architecture explanation, performance optimization guide.

### Contributors
**Need:** "How do I build, test, and submit changes?"
**Content:** CONTRIBUTING.md, architecture overview, testing guide, docs contribution guide.

### Maintainers
**Need:** "How do I release, version, and maintain docs?"
**Content:** Publishing guide (already exists), release workflow, docs maintenance checklist.

### Users migrating from View API
**Need:** "How do I move from `IsometricView` to `IsometricScene`?"
**Content:** Migration guide (already exists at `docs/MIGRATION.md`).

### Java interop users
**Need:** "Can I use this from Java?"
**Content:** Note in the installation/quickstart guide about `@JvmOverloads`, `@JvmField`, and `@JvmStatic` support. The core module's Java-friendly constructors should be documented.

---

## 5. Documentation Gap Analysis

### What exists

| Document | Location | Quality | Issues |
|----------|----------|---------|--------|
| README.md | Root | Medium | Too long; mixes tutorial/reference/marketing; references nonexistent `IsometricCanvas`; wrong Maven coordinates; broken image links to upstream repo |
| RUNTIME_API.md | `docs/` | Good | Comprehensive but mixes concepts, reference, and tutorial content |
| PERFORMANCE_OPTIMIZATIONS.md | `docs/` | Good | Detailed with benchmarks; audience is advanced users |
| PRIMITIVE_LEVELS.md | `docs/` | Good | Clear high-level vs low-level comparison |
| OPTIMIZATION_SUMMARY.md | `docs/` | Good | Useful quick-reference table |
| MIGRATION.md | `docs/` | Good | View → Compose migration; well-structured |
| api-design-guideline.md | `docs/` | Internal only | Not user-facing; should stay in `docs/internal/` |
| PUBLISHING_GUIDE.md | `docs/guides/` | Internal only | Maintainer-only content |
| 19 plan docs | `docs/plans/` | Internal only | Development artifacts |
| 4 research docs | `docs/research/` | Internal only | Development artifacts |
| 18 review docs | `docs/reviews/` | Internal only | Development artifacts |
| Sample app | `app/` module | Medium | Good usage examples but not documented for readers |
| KDoc comments | Source | Variable | Some public APIs have good KDoc; many have minimal or none |
| Tests | Various | Excellent | Reveal usage patterns, edge cases, and API contracts — a gold mine for documentation |

### What is missing

| Gap | Priority | Notes |
|-----|----------|-------|
| **Getting Started tutorial** | Critical | No guided first-success path exists |
| **Coordinate system explanation with diagram** | Critical | The isometric coordinate system is non-obvious |
| **Shape catalog with visuals** | High | Shapes exist but there's no visual reference page showing each with its parameters |
| **Installation guide (accurate)** | Blocked | Requires publication first; placeholder with JitPack or local-build instructions needed |
| **Animation guide** | High | Animation is a primary use case; scattered across README and RUNTIME_API.md |
| **Gesture/interaction guide** | High | Tap, drag, camera pan/zoom — each deserves focused coverage |
| **Color and theming guide** | Medium | `IsoColor`, `ColorPalette`, `CompositionLocal`s, lighting, shading |
| **Custom shapes guide** | Medium | `Shape.extrude`, `Path`, `CustomNode` |
| **CHANGELOG.md** | High | No changelog exists |
| **CONTRIBUTING.md** | High | No contributor guide exists |
| **Architecture/concepts explanation** | Medium | How the engine, projection, and node tree work together |
| **FAQ/Troubleshooting** | Medium | Common issues: `Path` name collision, coordinate confusion, `IsoColor` vs `Color` |
| **Dokka API reference** | Medium | No generated API docs exist |

### What is misleading

- `IsometricCanvas` API documented in README but not implemented
- Maven coordinates `io.fabianterhorst:isometric-compose:0.1.0` — not published
- Screenshot links point to upstream repo (may 404)
- "Available Shapes" section links to Java source files in the upstream repo, not this fork's Kotlin source

### What is redundant

- "Available Shapes" listed twice in the README (lines ~375 and ~436)
- Animation examples shown for both "High-Level API" (which doesn't exist) and Runtime API
- Performance comparison tables duplicated between README and RUNTIME_API.md

### What should move from README into docs

- Architecture details → Concepts page
- Runtime API features table → Runtime API guide
- All examples beyond quickstart → Guides and Examples pages
- Performance comparison → Performance guide
- `IsometricCanvas` animation/interaction examples → Remove (API doesn't exist) or move to a future "Simple API" guide when implemented

### What should move from tests into docs

- Import alias pattern for `Path` (`import ... as IsoPath`) → FAQ/Troubleshooting
- `worldToScreen`/`screenToWorld` usage → Coordinate system guide
- `AdvancedSceneConfig` usage patterns → Advanced configuration guide
- `CameraState` pan/zoom patterns → Gestures guide
- `CustomNode` render function → Custom shapes guide

---

## 6. Documentation Taxonomy

Following the [Diátaxis framework](https://diataxis.fr/):

| Type | Purpose | User state | Examples in this library |
|------|---------|------------|--------------------------|
| **Tutorial** | Learning-oriented; guided steps to first success | Studying, following along | "Build your first isometric scene" |
| **How-to Guide** | Task-oriented; solve a specific problem | Working, knows basics | "How to animate shapes", "How to handle gestures" |
| **Explanation** | Understanding-oriented; clarify concepts | Studying, wants depth | "How the coordinate system works", "How depth sorting works" |
| **Reference** | Information-oriented; precise, complete | Working, looking something up | Dokka API docs, shape parameter tables, config option tables |

Additionally:

| Type | Purpose |
|------|---------|
| **README** | Entry point and elevator pitch; links to everything else |
| **Examples/Cookbook** | Standalone, copy-paste recipes for common patterns |
| **FAQ/Troubleshooting** | Common problems and solutions |
| **Migration** | Version-to-version upgrade guides |
| **Contribution** | How to build, test, and contribute |
| **Changelog** | What changed in each release |

**Critical rule:** Each page belongs to exactly one type. Do not mix a tutorial with reference tables or an explanation with how-to steps. Cross-link instead.

---

## 7. README Strategy

The README should be **short, focused, and link outward**. It is a landing page, not a manual.

### Proposed README outline

```markdown
# Isometric

One-line tagline: Declarative isometric rendering for Jetpack Compose.

[badges: build status, Maven Central version, min SDK, Kotlin version, license]

[Hero image: a polished isometric scene render from the library]

## What is Isometric?

2-3 sentences. What it does, who it's for, what makes it different.
Link to the docs site for the full story.

## Features

Bullet list (6-8 items). Each is one line.
- Declarative scene graph with Shape, Group, Path composables
- Hierarchical transforms with automatic accumulation
- Built-in animation support (vsync-aligned, per-node recomposition)
- Tap and drag gesture handling with spatial hit testing
- 6 built-in shapes: Prism, Pyramid, Cylinder, Octahedron, Stairs, Knot
- Custom shape support via Path extrusion and CustomNode
- Configurable lighting, shading, and stroke styles
- Camera pan/zoom with CameraState

## Quick Start

### Installation

Gradle Kotlin DSL snippet (with correct coordinates once published).
Link to full installation guide for Groovy DSL, version catalog, etc.

### Your first scene

Minimal IsometricScene example (5-7 lines of Kotlin).
Brief explanation of what each line does.
Link to the Getting Started tutorial.

## Documentation

Bulleted list of links:
- Getting Started
- Guides (animation, gestures, custom shapes, theming)
- API Reference
- Examples
- Migration Guide
- FAQ

## Requirements

Table: min SDK 24, Kotlin 1.9+, Compose 1.5+, Java 11+ target.

## License

Apache 2.0 — one line + link.

## Credits

Original library by Fabian Terhorst. Fork maintained by [maintainer].
```

### What should NOT be in the README

- Architecture diagrams (→ Concepts page)
- Performance benchmark tables (→ Performance guide)
- Multiple full code examples (→ Examples page)
- API comparison tables (→ docs)
- Java/View API examples (→ Migration guide)
- Shape screenshots (→ Shape catalog page)

---

## 8. GitHub Docs Source-of-Truth Strategy

### Folder structure

```
docs/
├── getting-started/
│   ├── installation.mdx
│   ├── quickstart.mdx
│   └── coordinate-system.mdx
├── guides/
│   ├── animation.mdx
│   ├── gestures.mdx
│   ├── shapes.mdx
│   ├── custom-shapes.mdx
│   ├── theming.mdx
│   ├── camera.mdx
│   ├── performance.mdx
│   └── advanced-config.mdx
├── concepts/
│   ├── architecture.mdx
│   ├── scene-graph.mdx
│   ├── depth-sorting.mdx
│   └── rendering-pipeline.mdx
├── reference/
│   ├── shapes-catalog.mdx
│   ├── scene-config.mdx
│   ├── composables.mdx
│   └── composition-locals.mdx
├── examples/
│   ├── basic-scenes.mdx
│   ├── animation-patterns.mdx
│   ├── interactive-scenes.mdx
│   └── advanced-patterns.mdx
├── migration/
│   ├── view-to-compose.mdx
│   └── changelog.mdx
├── contributing/
│   ├── setup.mdx
│   ├── testing.mdx
│   └── docs-guide.mdx
├── faq.mdx
└── internal/                    ← NOT published to site
    ├── plans/                   ← existing planning docs
    ├── research/                ← existing research docs
    ├── reviews/                 ← existing review docs
    ├── api-design-guideline.md
    └── PUBLISHING_GUIDE.md
```

### Naming conventions

- Directories: lowercase, kebab-case
- Files: lowercase, kebab-case, `.mdx` extension
- One topic per file; prefer more small files over fewer large ones

### Frontmatter conventions

Every `.mdx` file in `docs/` (except `internal/`) must have:

```yaml
---
title: "Page Title"                    # Required
description: "One-line description"    # Required (SEO)
sidebar:
  order: 1                             # Required (navigation order)
  badge:
    text: "New"                        # Optional
    variant: "tip"
---
```

### Format choice: MDX

**Why MDX over plain Markdown:**
- Supports Starlight built-in components (`<Tabs>`, `<Card>`, `<Aside>`, `<Steps>`)
- Supports custom components (interactive code demos, shape previews)
- Starlight has first-class MDX support
- Still readable as plain Markdown in GitHub

**Why not Markdoc:** Less ecosystem support; MDX is the Starlight default.

### Organization principles

- `getting-started/` = tutorials (learning-oriented, ordered)
- `guides/` = how-to guides (task-oriented, can be read in any order)
- `concepts/` = explanations (understanding-oriented, no code-heavy steps)
- `reference/` = curated reference (information-oriented, tables and signatures)
- `examples/` = standalone recipes (copy-paste patterns)
- `migration/` = version transition content
- `contributing/` = contributor onboarding
- `internal/` = maintainer-only docs (excluded from site build via `.gitignore` or Starlight config)

---

## 9. Astro Website Architecture

### Option comparison

| Criterion | Pure Astro | Astro + Starlight | Recommendation |
|-----------|-----------|-------------------|----------------|
| Search | Build from scratch | Pagefind (zero-config) | Starlight |
| Sidebar nav | Build from scratch | Auto-generated or manual | Starlight |
| Dark/light mode | Build from scratch | Built-in | Starlight |
| MDX support | Manual setup | Built-in | Starlight |
| Custom landing page | Full control | Splash template + custom pages | Starlight (sufficient) |
| Component library | None | Cards, Tabs, Asides, Steps, etc. | Starlight |
| Accessibility | Manual | Built-in ARIA, keyboard nav | Starlight |
| Time to first deploy | Days | Hours | Starlight |
| Flexibility | Unlimited | Override any component | Starlight (sufficient) |

### Recommendation: **Astro + Starlight**

Starlight provides 90% of what this project needs out of the box. The 10% that requires customization (landing page, API reference embedding, code block enhancements) is achievable via Starlight's component override system and custom Astro pages.

### Content pipeline

```
docs/*.mdx (in-repo)
    ↓ Astro reads from filesystem
    ↓ Starlight docsLoader() processes frontmatter
    ↓ Content Collections validate schema
    ↓ Starlight renders pages with sidebar, search, nav
    ↓ Static HTML output

Dokka HTML (generated by Gradle task)
    ↓ Copied to site/public/api/
    ↓ Served as static assets at /api/
```

### How GitHub docs files are consumed

The Astro site lives in a `site/` directory at the repo root. Starlight's content directory is configured to read directly from `docs/` (excluding `docs/internal/`):

```ts
// site/astro.config.mjs
import starlight from '@astrojs/starlight';

export default defineConfig({
  integrations: [
    starlight({
      title: 'Isometric',
      social: { github: 'https://github.com/jayteealao/Isometric' },
      sidebar: [
        { label: 'Getting Started', autogenerate: { directory: 'getting-started' } },
        { label: 'Guides', autogenerate: { directory: 'guides' } },
        { label: 'Concepts', autogenerate: { directory: 'concepts' } },
        { label: 'Reference', autogenerate: { directory: 'reference' } },
        { label: 'Examples', autogenerate: { directory: 'examples' } },
        { label: 'Migration', autogenerate: { directory: 'migration' } },
        { label: 'Contributing', autogenerate: { directory: 'contributing' } },
        { label: 'FAQ', link: '/faq/' },
      ],
      customCss: ['./src/styles/custom.css'],
    }),
  ],
});
```

Content collection config:

```ts
// site/src/content.config.ts
import { defineCollection } from 'astro:content';
import { docsLoader } from '@astrojs/starlight/loaders';
import { docsSchema } from '@astrojs/starlight/schema';

export const collections = {
  docs: defineCollection({
    loader: docsLoader({ base: '../docs' }), // Read from repo root docs/
    schema: docsSchema(),
  }),
};
```

### Routing

Starlight generates routes from the filesystem structure:
- `docs/getting-started/quickstart.mdx` → `/getting-started/quickstart/`
- `docs/guides/animation.mdx` → `/guides/animation/`
- `docs/faq.mdx` → `/faq/`

### Custom pages

- **Landing page** (`site/src/pages/index.astro`) — fully custom Astro page, not a Starlight doc page. Links into `/getting-started/quickstart/` as the primary CTA.
- **API reference** — Dokka HTML at `/api/` served from `site/public/api/`.

### Search strategy

**Pagefind** (Starlight default):
- Zero config, zero cost, fully static
- Indexes all docs pages at build time
- Exclude internal/generated pages via `pagefind: false` frontmatter
- Sufficient for a library of this size; no need for Algolia

### Syntax highlighting

Starlight uses **Shiki** with built-in support for Kotlin syntax highlighting. Code blocks in MDX render with proper Kotlin highlighting automatically.

Enhanced code block features via Starlight's [Expressive Code](https://expressive-code.com/):
- Line highlighting (`{2-4}`)
- Diff markers (`// [!code ++]`)
- File name tabs
- Copy button (built-in)
- Word wrapping

### SEO strategy

- Every page has `title` and `description` frontmatter → `<title>` and `<meta name="description">`
- Starlight generates `sitemap.xml` automatically
- OpenGraph and Twitter Card meta tags via Starlight config
- Canonical URLs configured in `astro.config.mjs`
- Clean URL structure (no `.html` suffixes)

### Deployment strategy

**GitHub Pages via GitHub Actions:**

```yaml
# .github/workflows/docs.yml
name: Deploy docs
on:
  push:
    branches: [master]
    paths: ['docs/**', 'site/**']

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 22 }
      - run: cd site && npm ci && npm run build
      - uses: actions/upload-pages-artifact@v3
        with: { path: site/dist }

  deploy:
    needs: build
    runs-on: ubuntu-latest
    permissions:
      pages: write
      id-token: write
    environment:
      name: github-pages
    steps:
      - uses: actions/deploy-pages@v4
```

---

## 10. Information Architecture and Sitemap

### Proposed navigation tree

```
Landing Page (/)
│
├── Getting Started
│   ├── Installation (/getting-started/installation/)
│   ├── Quick Start (/getting-started/quickstart/)
│   └── Coordinate System (/getting-started/coordinate-system/)
│
├── Guides
│   ├── Animation (/guides/animation/)
│   ├── Gestures & Interaction (/guides/gestures/)
│   ├── Built-in Shapes (/guides/shapes/)
│   ├── Custom Shapes (/guides/custom-shapes/)
│   ├── Theming & Colors (/guides/theming/)
│   ├── Camera & Viewport (/guides/camera/)
│   ├── Performance (/guides/performance/)
│   └── Advanced Configuration (/guides/advanced-config/)
│
├── Concepts
│   ├── Architecture Overview (/concepts/architecture/)
│   ├── Scene Graph & Nodes (/concepts/scene-graph/)
│   ├── Depth Sorting (/concepts/depth-sorting/)
│   └── Rendering Pipeline (/concepts/rendering-pipeline/)
│
├── Reference
│   ├── Shape Catalog (/reference/shapes-catalog/)
│   ├── SceneConfig Reference (/reference/scene-config/)
│   ├── Composables Reference (/reference/composables/)
│   └── CompositionLocals (/reference/composition-locals/)
│
├── Examples
│   ├── Basic Scenes (/examples/basic-scenes/)
│   ├── Animation Patterns (/examples/animation-patterns/)
│   ├── Interactive Scenes (/examples/interactive-scenes/)
│   └── Advanced Patterns (/examples/advanced-patterns/)
│
├── FAQ (/faq/)
│
├── Migration
│   ├── View → Compose (/migration/view-to-compose/)
│   └── Changelog (/migration/changelog/)
│
├── Contributing
│   ├── Development Setup (/contributing/setup/)
│   ├── Testing Guide (/contributing/testing/)
│   └── Documentation Guide (/contributing/docs-guide/)
│
└── API Reference (/api/)  ← Dokka HTML (external link in sidebar)
```

### User journey flows

**New user:** Landing → Installation → Quick Start → (branch to Guides or Examples)

**Evaluator:** Landing → Features section → Quick Start code → (convinced? → Installation)

**Returning user:** Search or sidebar → specific Guide or Reference page

**Advanced user:** Performance guide → Advanced Config → Primitive Levels → API Reference

**Contributor:** Contributing → Setup → Testing → (submit PR)

---

## 11. Landing Page Plan

### Hero section

- **Headline:** "Isometric" (large)
- **Tagline:** "Declarative isometric rendering for Jetpack Compose"
- **Sub-tagline:** "Build interactive 3D isometric scenes with a familiar Compose API"
- **CTA buttons:**
  - Primary: "Get Started" → `/getting-started/quickstart/`
  - Secondary: "View on GitHub" → repo URL
- **Hero visual:** An animated or static render of a complex isometric scene (use Paparazzi snapshot output or a purpose-built demo render). Consider using the "complex scene" from `sampleThree` in the snapshot tests.

### Feature blocks (3-column grid)

1. **Declarative API** — "Build scenes with `Shape`, `Group`, and `Path` composables. Transforms accumulate automatically through the hierarchy." + code snippet
2. **High Performance** — "7-20× faster animations. Per-node dirty tracking, prepared-scene caching, and spatial indexing." + benchmark numbers
3. **Interactive** — "Built-in tap and drag gestures with spatial hit testing. Camera pan and zoom with `CameraState`." + code snippet

### Additional sections

4. **Shape showcase** — Visual grid of all 6 built-in shapes with names and mini code snippets
5. **Code example** — A real, copy-pasteable `IsometricScene` block showing animation and interaction (pulled from the quickstart)
6. **Architecture diagram** — The three-layer architecture diagram (Composable → Node Tree → Rendering)

### Design direction

- **Visual tone:** Technical but approachable. Clean, modern. Isometric visual elements as decorative accents.
- **Dark/light mode:** Starlight handles this; ensure the hero visual works in both modes (use semi-transparent backgrounds or provide light/dark variants).
- **Responsive:** Hero stacks vertically on mobile; feature grid becomes single-column.
- **No social proof section initially** — the project isn't published yet. Add GitHub stars badge and "used by" section once adoption begins.

### Connection to docs

The landing page is a custom Astro page (`site/src/pages/index.astro`). It imports Starlight's header/footer components for visual consistency, then links directly into the docs via the CTA button. The docs site takes over navigation from that point.

---

## 12. API Reference Strategy

### KDoc quality expectations

Every public declaration must have:

| Element | Minimum KDoc |
|---------|-------------|
| Class/Interface/Object | Summary paragraph + constructor parameters |
| Public function | Summary + all `@param` + `@return` + `@throws` |
| Public property | Summary |
| Extension function | Summary + `@receiver` description |
| Sealed subclass | Summary clarifying when to use this variant |
| `@ExperimentalIsometricApi` member | `@sample` demonstrating usage |

### Tooling: Dokka 2.x

```kotlin
// Root build.gradle.kts
plugins {
    id("org.jetbrains.dokka") version "2.1.0"
}
```

Generate multi-module HTML reference:

```bash
./gradlew dokkaGeneratePublicationHtml
```

Output: `build/dokka/html/` → copied to `site/public/api/` during site build.

### Where generated reference lives

- **Source:** Generated at build time by Dokka into `build/dokka/html/`
- **In site:** Copied into `site/public/api/` (served as static assets)
- **URL:** `https://[site-url]/api/`
- **Navigation:** Sidebar includes an external link to `/api/`

### Keeping reference separate from human docs

The Dokka HTML output lives at `/api/` and is **not** integrated into Starlight's content collection. This prevents:
- Generated reference from polluting the sidebar
- Dokka's own navigation from conflicting with Starlight's
- Broken cross-links between generated and authored content

Human-written reference pages in `docs/reference/` provide curated, opinionated summaries of key API surfaces (composables, config classes, shapes). These link *to* the Dokka reference for full details.

### Tradeoffs

| Approach | Pros | Cons |
|----------|------|------|
| Dokka HTML at `/api/` | Stable output; visually consistent Dokka UI; no custom work | Different visual style from Starlight; separate search index |
| Dokka GFM → Starlight pages | Fully integrated into site; single search | GFM format is Alpha quality; needs frontmatter post-processing; fragile |
| No Dokka, only curated reference | Perfect editorial control | Doesn't scale; misses internal details |

**Recommendation:** Dokka HTML at `/api/` (stable, proven) + curated reference pages in Starlight for the most important API surfaces.

---

## 13. Content Plan (Prioritized Backlog)

### Phase 1 — Critical (before first release)

| # | Page | Purpose | Audience | Priority |
|---|------|---------|----------|----------|
| 1 | `getting-started/quickstart.mdx` | First-success tutorial: install → scene → run | New users | P0 |
| 2 | `getting-started/installation.mdx` | Gradle KTS/Groovy/Version Catalog setup | New users | P0 |
| 3 | `getting-started/coordinate-system.mdx` | Visual explanation of isometric coordinates | All users | P0 |
| 4 | README.md rewrite | Focused entry point per §7 | Everyone | P0 |
| 5 | `guides/shapes.mdx` | Shape catalog with visuals and parameters | New/returning | P0 |
| 6 | `guides/animation.mdx` | How to animate with `withFrameNanos` | Returning | P0 |
| 7 | `faq.mdx` | Path name collision, IsoColor, coordinates | All | P1 |
| 8 | CHANGELOG.md | Initial changelog | All | P1 |
| 9 | CONTRIBUTING.md | Build, test, contribute | Contributors | P1 |

### Phase 2 — Important (first month)

| # | Page | Purpose | Audience | Priority |
|---|------|---------|----------|----------|
| 10 | `guides/gestures.mdx` | Tap, drag, `GestureConfig` | Returning | P1 |
| 11 | `guides/theming.mdx` | IsoColor, ColorPalette, CompositionLocals, lighting | Returning | P1 |
| 12 | `guides/camera.mdx` | CameraState, pan, zoom | Returning | P1 |
| 13 | `guides/custom-shapes.mdx` | Path, Shape.extrude, CustomNode | Advanced | P2 |
| 14 | `concepts/architecture.mdx` | Three-layer architecture, engine overview | Advanced | P2 |
| 15 | `reference/composables.mdx` | Shape, Group, Path, Batch, If, ForEach signatures and params | Returning | P2 |
| 16 | `reference/scene-config.mdx` | SceneConfig and AdvancedSceneConfig fields | Returning | P2 |
| 17 | `examples/basic-scenes.mdx` | 5-6 copy-paste recipes | New/returning | P2 |

### Phase 3 — Nice to have (quarter 1)

| # | Page | Purpose | Audience | Priority |
|---|------|---------|----------|----------|
| 18 | `guides/performance.mdx` | Optimization guide with benchmarks | Advanced | P2 |
| 19 | `guides/advanced-config.mdx` | AdvancedSceneConfig, native canvas, escape hatches | Advanced | P2 |
| 20 | `concepts/scene-graph.mdx` | Node types, dirty tracking, applier | Advanced | P3 |
| 21 | `concepts/depth-sorting.mdx` | Depth formula, broad-phase, known issues | Advanced | P3 |
| 22 | `concepts/rendering-pipeline.mdx` | Engine → PreparedScene → Canvas | Advanced | P3 |
| 23 | `reference/shapes-catalog.mdx` | All shapes with constructor params, edge cases | Returning | P2 |
| 24 | `reference/composition-locals.mdx` | All CompositionLocals with defaults | Advanced | P3 |
| 25 | `examples/animation-patterns.mdx` | Wave, rotation, color cycling | Returning | P3 |
| 26 | `examples/interactive-scenes.mdx` | Drag-to-move, tap-to-select | Returning | P3 |
| 27 | `examples/advanced-patterns.mdx` | ComposeNode, CustomNode, Batch | Advanced | P3 |
| 28 | `migration/view-to-compose.mdx` | Revised from existing MIGRATION.md | Migrating users | P2 |
| 29 | `contributing/setup.mdx` | Clone, build, run sample app | Contributors | P2 |
| 30 | `contributing/testing.mdx` | Unit tests, Paparazzi snapshots, Maestro | Contributors | P3 |
| 31 | `contributing/docs-guide.mdx` | How to write and preview docs | Contributors | P3 |

---

## 14. Example and Tutorial Strategy

### Example types

| Type | Location | Purpose |
|------|----------|---------|
| **Quickstart snippet** | README, quickstart page | Minimal 5-line working scene |
| **Guided tutorial snippets** | Getting Started pages | Build up step by step with explanations between each snippet |
| **Task-driven examples** | Guide pages | Show how to achieve a specific goal (e.g., "animate a group") |
| **Copy-paste recipes** | Examples pages | Complete, standalone, ready to drop into a project |
| **Edge case examples** | FAQ, reference pages | Show gotchas and their solutions |
| **Anti-patterns** | Guides (sparingly) | "Don't do this" with explanation of why and the correct alternative |

### Keeping examples in sync

**Strategy: `@sample` + compilable sample module**

1. **KDoc `@sample` tags** — For API reference examples. The `@sample` body is compiled during the build; if it breaks, the build fails. Use this for all Dokka-rendered examples.

2. **Sample module (`app/`)** — Already contains working examples. Reference these from docs by file path. Add a CI step that builds the sample app to catch breakage.

3. **Docs code blocks** — For MDX content, code blocks are not automatically compiled. Mitigation: keep docs examples small and simple (under 15 lines); derive them from patterns already tested in the snapshot tests or sample app.

4. **Never duplicate** — If an example exists in the sample app, the docs page should reference it or extract the relevant portion, not maintain a separate copy.

### Copy-paste friendliness

- Every code block must include all necessary imports (or state "assuming standard imports" with a link to the import list)
- Use the `// highlight-next-line` directive for the key line
- Include the `@Composable` annotation on every example function
- Show `IsometricScene { }` as the outermost scope in every example (don't assume the reader knows the entry point)

---

## 15. Contributor and Maintainer Documentation

### How to write docs

Create `contributing/docs-guide.mdx` covering:

- File naming: lowercase, kebab-case, `.mdx`
- Frontmatter: required fields (`title`, `description`, `sidebar.order`)
- Content type: which Diátaxis type is this page? Write accordingly.
- Code examples: keep under 15 lines; include imports or link to import list
- Images: place in `site/src/assets/`; reference via relative path
- Preview: `cd site && npm run dev`

### Style guide (concise)

- Use second person ("you") not first person ("we")
- Use present tense ("returns" not "will return")
- Use active voice
- Lead with the most common case; cover edge cases after
- One idea per paragraph
- Code over prose when code is clearer

### Review checklist for docs PRs

- [ ] Frontmatter has `title` and `description`
- [ ] Page belongs to exactly one Diátaxis type
- [ ] Code examples compile (or are derived from compiled source)
- [ ] No broken links
- [ ] No reference to `IsometricCanvas` (until implemented)
- [ ] Screenshots/visuals have alt text

### Definition of "done" for features requiring docs

A feature PR that changes public API is not mergeable until:
1. KDoc is added/updated on all affected public declarations
2. If the feature is user-facing, a docs page or docs update is included (or a follow-up issue is filed and linked)

---

## 16. CI/CD and Automation Plan

### Phase 1 (with first docs PR)

| Tool | Purpose | Config |
|------|---------|--------|
| **markdownlint** | Lint Markdown/MDX files | `.markdownlint.json` in repo root; run via GitHub Actions |
| **Astro build check** | Validate site builds | `cd site && npm run build` in CI |
| **Dokka build** | Validate KDoc + generate API reference | `./gradlew dokkaGeneratePublicationHtml` in CI |

### Phase 2 (within first month)

| Tool | Purpose |
|------|---------|
| **lychee** or **linkinator** | Dead link checking across docs and README |
| **Docs preview deploys** | Deploy Astro site preview on docs-related PRs (GitHub Actions + Netlify preview or Vercel preview) |

### Phase 3 (mature)

| Tool | Purpose |
|------|---------|
| **Vale** | Prose linting (style, terminology consistency) |
| **git-cliff** | Automated changelog generation from conventional commits |
| **Sample app build** | CI builds the `:app` module to catch example breakage |
| **Paparazzi snapshot update detection** | Flag when snapshots change (potential docs visual update needed) |

### Workflow sketch

```yaml
# .github/workflows/ci.yml
name: CI
on: [push, pull_request]
jobs:
  library:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 17, distribution: temurin }
      - run: ./gradlew build
      - run: ./gradlew dokkaGeneratePublicationHtml

  docs:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 22 }
      - run: cd site && npm ci && npm run build
      - run: npx markdownlint-cli2 "docs/**/*.mdx"
```

---

## 17. Versioning and Release Documentation

### Current state

No releases, no changelog, no published artifacts.

### Recommended approach

| Concern | Strategy |
|---------|----------|
| **Latest docs** | `master` branch docs deployed to the main site URL |
| **Older versions** | Not needed until v2.0+; when needed, use the `starlight-versions` community plugin |
| **Changelog** | `CHANGELOG.md` at repo root; generated via `git-cliff` from conventional commits |
| **Migration guides** | One page per major version transition in `docs/migration/` |
| **Breaking changes** | Listed prominently in changelog; dedicated migration guide if > 3 breaking changes |
| **Compatibility matrix** | Table in installation guide: library version × Kotlin version × Compose BOM × min SDK |
| **Pre-release docs** | Feature branches with docs changes get preview deploys; merged docs only publish on `master` |

### Per the user's preference

The user [prefers direct breaking changes over deprecation migration paths](../memory/feedback_no_deprecation_cycles.md). Documentation should reflect this: migration guides describe the before/after directly, without deprecation-period warnings.

---

## 18. Accessibility and Usability Standards

| Standard | Implementation |
|----------|---------------|
| **Heading structure** | H1 = page title (auto from frontmatter); H2-H4 in content; never skip levels |
| **Reading flow** | Summary → core content → edge cases → links to related pages |
| **Code sample legibility** | Syntax highlighting (Shiki/Kotlin); light and dark mode support; copy button |
| **Mobile nav** | Starlight's responsive sidebar collapses to hamburger menu; tested |
| **Keyboard navigation** | Starlight provides keyboard-accessible sidebar, search, and page nav |
| **Contrast** | Starlight's default themes meet WCAG AA; custom CSS must preserve this |
| **Accessible callouts** | Use Starlight's `<Aside>` component (renders as `<aside>` with `role="note"`) |
| **Search/discoverability** | Pagefind indexes all page content; every page has descriptive `title` and `description` |
| **Scannability** | Use bullet lists, tables, and code blocks liberally; limit prose paragraphs to 3-4 sentences |
| **Page-level consistency** | Every guide page follows: intro → prerequisites → steps → result → next steps |

---

## 19. Risks and Tradeoffs

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| **Stale docs** | High | High | CI checks; "docs impact" label on PRs; definition of done includes docs |
| **`IsometricCanvas` confusion** | Certain | High | Remove all references now; add back only when implemented |
| **Dokka HTML visual mismatch** | Medium | Low | Accept the different style; link to `/api/` rather than embedding. Users of Dokka-style docs are accustomed to this pattern (Ktor, Arrow) |
| **Overly large README** | Already happening | Medium | Ruthlessly cut to §7 outline; link out to docs |
| **Duplicated content (README ↔ docs ↔ site)** | Medium | Medium | README contains only pitch + quickstart + links. Docs are the single source of truth. Site reads from docs directly. |
| **GitHub-to-site sync fragility** | Low | Medium | No sync needed — Astro reads from filesystem during build. Same repo, same commit. |
| **Broken external images** | Certain | Medium | Replace upstream screenshot URLs with locally committed images or Paparazzi outputs |
| **Too much reference, not enough guidance** | Medium | High | Content plan (§13) prioritizes tutorials and guides before reference pages |
| **Package rename disruption** | Certain (planned) | Medium | Do the rename before writing docs with final import paths; use a placeholder in docs until rename is complete |
| **MDX complexity for contributors** | Low | Low | Keep MDX component usage minimal; most pages are just Markdown with code blocks |

---

## 20. Recommended Final Architecture

| Decision | Choice |
|----------|--------|
| **Docs framework** | Astro 5.x + Starlight 0.37+ |
| **Docs authoring format** | MDX (`.mdx`) |
| **Docs source location** | `docs/` at repo root (same repo as library) |
| **Site source location** | `site/` at repo root |
| **Content pipeline** | Starlight `docsLoader()` reads from `../docs/` relative to site |
| **API reference** | Dokka 2.x HTML → `site/public/api/` |
| **Landing page** | Custom Astro page at `site/src/pages/index.astro` |
| **Search** | Pagefind (Starlight default) |
| **Deployment** | GitHub Pages via GitHub Actions on `master` push |
| **Preview deploys** | Netlify or Vercel preview on PR (optional, phase 2) |
| **Changelog** | `git-cliff` + conventional commits |
| **Docs linting** | markdownlint + lychee (link checker) |
| **KDoc standard** | All public APIs documented; Dokka build in CI catches gaps |
| **Versioning** | Single-version docs until v2.0; `starlight-versions` plugin when needed |

### Workflow for keeping GitHub docs and Astro site aligned

There is no sync. The Astro site reads `docs/` directly from the filesystem during `npm run build`. A PR that changes docs content automatically changes the site. The CI builds the site on every PR to catch build failures. Merging to `master` triggers a deploy.

---

## 21. Phased Implementation Roadmap

### Phase 0: Discovery and Audit (this document)
- **Objective:** Understand codebase, research best practices, produce this plan
- **Deliverable:** This document
- **Status:** Complete

### Phase 1: README Rewrite + Cleanup
- **Objectives:**
  - Remove `IsometricCanvas` references (or clearly label as unimplemented)
  - Fix Maven coordinates (use placeholder or JitPack instructions)
  - Replace broken upstream image links with local images
  - Restructure README per §7 (pitch → install → quickstart → links)
  - Remove duplicated "Available Shapes" section
  - Add badges (build status, license, Kotlin version)
- **Deliverables:** Revised `README.md`, local screenshots committed to repo
- **Dependencies:** None
- **Risks:** Deciding on Maven coordinates before publication
- **Success criteria:** README fits on 2 screens; every link works; no `IsometricCanvas` reference

### Phase 2: Docs Content Foundation
- **Objectives:**
  - Create `docs/` structure per §8
  - Move internal docs to `docs/internal/`
  - Write P0 pages: quickstart, installation, coordinate system, shapes guide, animation guide
  - Write FAQ
  - Create CHANGELOG.md and CONTRIBUTING.md
- **Deliverables:** 9 docs pages (§13 Phase 1 backlog)
- **Dependencies:** Phase 1 (README provides accurate links)
- **Risks:** Writing installation docs before publication is finalized
- **Success criteria:** A new user can follow the quickstart and render a scene; all P0 pages reviewed and merged

### Phase 3: Astro Site Foundation
- **Objectives:**
  - Initialize `site/` directory with Astro + Starlight
  - Configure content collection to read from `docs/`
  - Configure sidebar navigation per §10
  - Set up GitHub Actions deploy to GitHub Pages
  - Verify search, dark mode, mobile nav work
- **Deliverables:** Deployed docs site at `https://jayteealao.github.io/Isometric/` (or custom domain)
- **Dependencies:** Phase 2 (content must exist to test the site)
- **Risks:** Starlight `docsLoader` base path configuration; GitHub Pages path prefix
- **Success criteria:** Site deploys on `master` push; all docs pages render correctly; search works

### Phase 4: Landing Page Polish
- **Objectives:**
  - Create custom landing page at `site/src/pages/index.astro`
  - Design hero section with isometric visual
  - Add feature blocks, code example, shape showcase
  - Ensure dark/light mode, responsive behavior
- **Deliverables:** Polished landing page
- **Dependencies:** Phase 3 (site infrastructure)
- **Risks:** Design quality — may need iteration
- **Success criteria:** Landing page feels professional; CTA leads to quickstart; renders well on mobile

### Phase 5: API Reference Integration
- **Objectives:**
  - Add Dokka 2.x plugin to Gradle build
  - Audit and improve KDoc on all public APIs
  - Generate Dokka HTML and integrate into site at `/api/`
  - Add `@sample` tags where appropriate
  - Add Dokka build to CI
- **Deliverables:** Generated API reference at `/api/`; improved KDoc across all modules
- **Dependencies:** Phase 3 (site must exist to host Dokka output)
- **Risks:** Dokka 2.x compatibility with Kotlin 1.9.10 and AGP 8.2.2
- **Success criteria:** Every public class/function has KDoc; Dokka builds without warnings; `/api/` is navigable

### Phase 6: Automation and Maintenance
- **Objectives:**
  - Add markdownlint to CI
  - Add dead link checker (lychee) to CI
  - Add PR preview deploys
  - Add conventional commit enforcement (commitlint)
  - Set up git-cliff for changelog generation
  - Write docs contribution guide
  - Add "docs impact" PR label and review checklist
- **Deliverables:** CI workflow additions; docs contribution guide
- **Dependencies:** Phase 3 (CI infrastructure)
- **Risks:** Over-engineering CI; slowing down contributor PRs
- **Success criteria:** Every PR touching `docs/` or public API triggers docs build; dead links are caught before merge

---

## 22. File/Folder Proposal

```
Isometric/
├── README.md                          # Focused entry point (§7)
├── CHANGELOG.md                       # Generated via git-cliff
├── CONTRIBUTING.md                    # Contributor onboarding
├── LICENSE                            # Apache 2.0
├── NOTICE                             # Attribution (original + fork)
│
├── isometric-core/                    # Library: pure Kotlin/JVM
├── isometric-compose/                 # Library: Compose
├── isometric-android-view/            # Library: Android View
├── app/                               # Sample application
├── isometric-benchmark/               # Benchmark application
│
├── docs/                              # Documentation source of truth
│   ├── getting-started/
│   │   ├── installation.mdx
│   │   ├── quickstart.mdx
│   │   └── coordinate-system.mdx
│   ├── guides/
│   │   ├── animation.mdx
│   │   ├── gestures.mdx
│   │   ├── shapes.mdx
│   │   ├── custom-shapes.mdx
│   │   ├── theming.mdx
│   │   ├── camera.mdx
│   │   ├── performance.mdx
│   │   └── advanced-config.mdx
│   ├── concepts/
│   │   ├── architecture.mdx
│   │   ├── scene-graph.mdx
│   │   ├── depth-sorting.mdx
│   │   └── rendering-pipeline.mdx
│   ├── reference/
│   │   ├── shapes-catalog.mdx
│   │   ├── scene-config.mdx
│   │   ├── composables.mdx
│   │   └── composition-locals.mdx
│   ├── examples/
│   │   ├── basic-scenes.mdx
│   │   ├── animation-patterns.mdx
│   │   ├── interactive-scenes.mdx
│   │   └── advanced-patterns.mdx
│   ├── migration/
│   │   ├── view-to-compose.mdx        # Revised from existing MIGRATION.md
│   │   └── changelog.mdx              # Links to CHANGELOG.md or embeds
│   ├── contributing/
│   │   ├── setup.mdx
│   │   ├── testing.mdx
│   │   └── docs-guide.mdx
│   ├── faq.mdx
│   │
│   └── internal/                      # NOT published to site
│       ├── plans/                     # Existing planning docs
│       ├── research/                  # Existing research docs
│       ├── reviews/                   # Existing review docs
│       ├── api-design-guideline.md
│       └── PUBLISHING_GUIDE.md
│
├── site/                              # Astro + Starlight docs website
│   ├── astro.config.mjs
│   ├── package.json
│   ├── tsconfig.json
│   ├── src/
│   │   ├── content.config.ts          # Content collection: reads from ../docs/
│   │   ├── pages/
│   │   │   └── index.astro            # Custom landing page
│   │   ├── styles/
│   │   │   └── custom.css             # Theme overrides
│   │   ├── components/
│   │   │   └── ShapeShowcase.astro    # Custom component for shape gallery
│   │   └── assets/
│   │       ├── hero.png               # Landing page hero image
│   │       └── shapes/                # Shape preview images
│   │           ├── prism.png
│   │           ├── pyramid.png
│   │           ├── cylinder.png
│   │           ├── octahedron.png
│   │           ├── stairs.png
│   │           └── knot.png
│   └── public/
│       ├── api/                       # Dokka HTML output (generated, gitignored)
│       └── favicon.svg
│
├── .github/
│   └── workflows/
│       ├── ci.yml                     # Build library + docs on every PR
│       ├── docs.yml                   # Deploy docs site on master push
│       └── release.yml                # Publish to Maven Central (future)
│
├── .markdownlint.json                 # markdownlint config
├── build.gradle                       # Root Gradle build
├── settings.gradle                    # Module includes
├── gradle.properties
└── gradle/
    └── libs.versions.toml             # Version catalog (future)
```

---

## 23. Acceptance Criteria

### Quantitative

| Criterion | Target |
|-----------|--------|
| Time from README to first rendered scene | < 5 minutes (assuming published artifact) |
| Public API KDoc coverage | 100% of public classes, functions, and properties |
| Docs pages covering top use cases | ≥ 8 guides covering: install, first scene, shapes, animation, gestures, theming, camera, custom shapes |
| Broken links in docs | 0 (enforced by CI) |
| Mobile usability | All docs pages readable without horizontal scroll |
| Search effectiveness | Searching "animation" returns the animation guide as top result |
| Site build time | < 30 seconds |

### Qualitative

- A developer who has never seen Isometric can evaluate whether it fits their needs from the landing page alone
- The coordinate system is explained before any example that uses specific coordinates
- No page mixes tutorial steps with reference tables
- Every code example is copy-pasteable (with stated prerequisites)
- The README links to docs; docs don't repeat README content
- No reference to `IsometricCanvas` exists unless it is implemented

---

## 24. Open Questions and Decisions to Make

| # | Question | Options | Recommendation |
|---|----------|---------|----------------|
| 1 | **When will the library be published?** | Before docs site; after docs site; simultaneously | Publish first (even to JitPack); then docs have real installation instructions |
| 2 | **Final Maven coordinates?** | `io.github.jayteealao:isometric-*` per PUBLISHING_GUIDE | Confirm before writing final installation docs |
| 3 | **Is `IsometricCanvas` planned?** | Implement it; remove all references; mark as "coming soon" | Remove references now; add back when implemented |
| 4 | **Custom domain for docs?** | `isometric.dev`, GitHub Pages subdomain, etc. | Start with `jayteealao.github.io/Isometric`; add custom domain later |
| 5 | **Should the View API be documented prominently?** | Yes (equal); yes (secondary); minimal mention | Minimal — the View API is legacy; docs should focus on Compose |
| 6 | **Dokka compatibility with current Gradle/Kotlin versions** | Kotlin 1.9.10 + AGP 8.2.2 + Dokka 2.1.0 — does this combination work? | Test before committing to Phase 5 |
| 7 | **Should `docs/internal/` stay in the same repo?** | Yes (convenient); separate repo; `.gitignore`'d | Keep in repo but exclude from site build |
| 8 | **Compose Multiplatform support timeline** | Near-term; long-term; never | Impacts whether docs should say "Android" or be platform-neutral |
| 9 | **Screenshots: Paparazzi outputs or purpose-built renders?** | Paparazzi (automated, always current); hand-crafted (prettier) | Paparazzi for shape catalog (automated); hand-crafted for landing page hero |
| 10 | **Starlight `docsLoader` base path** | Does Starlight support reading from a sibling directory (`../docs/`)? | Test during Phase 3; fallback is symlinking or copying during build |

---

## Sources and References

### Astro / Starlight
- [Starlight Getting Started](https://starlight.astro.build/getting-started/) — v0.37.6
- [Starlight Customization Guide](https://starlight.astro.build/guides/customization/)
- [Starlight Components](https://starlight.astro.build/guides/components/)
- [Starlight Overriding Components](https://starlight.astro.build/guides/overriding-components/)
- [Starlight Frontmatter Reference](https://starlight.astro.build/reference/frontmatter/)
- [Starlight Site Search](https://starlight.astro.build/guides/site-search/)
- [Starlight Pages Guide](https://starlight.astro.build/guides/pages/)
- [Starlight Versioning Discussion #957](https://github.com/withastro/starlight/discussions/957)
- [starlight-versions plugin](https://starlight-versions.vercel.app/getting-started/)

### Kotlin Documentation
- [KDoc Reference](https://kotlinlang.org/docs/kotlin-doc.html)
- [Dokka 2.x](https://github.com/Kotlin/dokka) — v2.1.0
- [Kotlin API Guidelines: Informative Documentation](https://kotlinlang.org/docs/api-guidelines-informative-documentation.html)
- [Kotlin API Guidelines: Build for Multiplatform](https://kotlinlang.org/docs/api-guidelines-build-for-multiplatform.html)
- [AndroidX KDoc Guidelines](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/docs/kdoc_guidelines.md)

### Documentation Best Practices
- [Diátaxis Framework](https://diataxis.fr/)
- [Make a README](https://www.makeareadme.com/)
- [The Good Docs Project — README Template](https://www.thegooddocsproject.dev/template/readme)
- [Write the Docs — Docs as Code](https://www.writethedocs.org/guide/docs-as-code/)
- [Keep a Changelog](https://keepachangelog.com/)

### Deployment
- [Astro Deployment Guide](https://docs.astro.build/en/guides/deploy/)
- [GitHub Pages + Astro](https://docs.astro.build/en/guides/deploy/github/)

---

## Recommended Next Action

**Do this first:**

1. **Remove `IsometricCanvas` from the README.** Every section referencing `IsometricCanvas` or the "High-Level API" either needs to be removed or clearly marked as unimplemented. This is the single most misleading element in the current docs and fixing it takes 15 minutes.

2. **Commit local screenshots.** Copy the Paparazzi snapshot outputs (from `isometric-compose/src/test/snapshots/`) into a `docs/assets/screenshots/` directory and update the README image links to point to them. This fixes the broken upstream image links immediately.

3. **Rewrite the README** per §7. Cut it from 487 lines to ~80 lines. Pitch → install → quickstart → docs links → license.

These three actions can be done in a single PR and immediately improve the first-contact experience for anyone who finds this repository.
