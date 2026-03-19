# Phase 2 — Documentation Site & Content

> Depends on: Phase 1 (complete)
> Goal: Ship a browsable docs site with getting-started tutorial, guides, reference, and API docs.

---

## Overview

Phase 2 has four workstreams that can partially overlap:

| # | Workstream | Estimated Scope | Dependencies |
|---|---|---|---|
| WS1 | Astro + Starlight site scaffold | New `site/` directory, config, CI | None |
| WS2 | Content pages (MDX) | 12 pages, mostly migrated from existing docs | WS1 (for preview) |
| WS3 | KDoc improvements | ~15 files need KDoc added/improved | None |
| WS4 | Dokka integration | Gradle plugin + site embedding | WS1, WS3 |

---

## WS1 — Astro + Starlight Site Scaffold

### Step 1.1: Initialize the site

```
site/
├── astro.config.mjs
├── package.json
├── tsconfig.json
├── src/
│   ├── content.config.ts
│   └── styles/
│       └── custom.css
└── public/
    └── favicon.svg
```

- Use `npm create astro@latest -- --template starlight`
- Configure content source to read from `../docs/` (repo root `docs/` folder)
- Exclude `docs/internal/` from the content collection
- Configure sidebar per the navigation tree in DOCUMENTATION_STRATEGY.md §10

### Step 1.2: Sidebar configuration

```ts
sidebar: [
  { label: 'Getting Started', autogenerate: { directory: 'getting-started' } },
  { label: 'Guides', autogenerate: { directory: 'guides' } },
  { label: 'Concepts', autogenerate: { directory: 'concepts' } },
  { label: 'Reference', autogenerate: { directory: 'reference' } },
  { label: 'Examples', autogenerate: { directory: 'examples' } },
  { label: 'FAQ', link: '/faq/' },
  { label: 'Migration', autogenerate: { directory: 'migration' } },
  { label: 'Contributing', autogenerate: { directory: 'contributing' } },
  { label: 'API Reference', link: '/api/', attrs: { target: '_blank' } },
]
```

### Step 1.3: MDX folder structure

Create the directory tree under `docs/`:

```
docs/
├── getting-started/
│   ├── installation.mdx
│   ├── quickstart.mdx
│   └── coordinate-system.mdx
├── guides/
│   ├── shapes.mdx
│   ├── animation.mdx
│   ├── gestures.mdx
│   ├── theming.mdx
│   ├── camera.mdx
│   └── custom-shapes.mdx
├── concepts/
│   └── architecture.mdx
├── reference/
│   ├── composables.mdx
│   └── scene-config.mdx
├── examples/
│   └── basic-scenes.mdx
├── migration/
│   └── view-to-compose.mdx
├── faq.mdx
└── assets/screenshots/  (existing)
```

### Step 1.4: GitHub Actions deployment

Create `.github/workflows/docs.yml`:
- Trigger on push to `master` when `docs/**` or `site/**` change
- Build: `cd site && npm ci && npm run build`
- Deploy to GitHub Pages

### Step 1.5: Verification

- `cd site && npm run dev` — local preview works
- All existing screenshots render at `/assets/screenshots/`
- Sidebar navigation shows all sections
- Search (Pagefind) indexes content

---

## WS2 — Content Pages (MDX)

Each page has a source (existing doc to migrate from, or new content).

### Priority 1 — Getting Started (P0, before first release)

#### 2.1: `getting-started/installation.mdx`
**Source:** README.md §Quick Start + new content
**Content:**
- Gradle Kotlin DSL (primary)
- Gradle Groovy DSL
- Version Catalog setup
- Composite build workaround (current, pre-publish)
- Minimum versions table (minSdk 24, Kotlin 1.9+, Compose 1.5+, JVM 11)
- Module dependency explanations:
  - `isometric-compose` — full Compose integration (most users)
  - `isometric-core` — pure Kotlin/JVM, no Android dependency
  - `isometric-android-view` — legacy View support

#### 2.2: `getting-started/quickstart.mdx`
**Source:** README.md §Your First Scene + RUNTIME_API.md examples
**Content:**
- Step-by-step tutorial: add dependency → create composable → add IsometricScene → add Shape → run
- Progressive examples:
  1. Single blue cube (5 lines)
  2. Add a second shape with different color
  3. Add a Group with transforms
  4. Add tap interaction
- Each step shows the code AND a screenshot
- Link to coordinate-system.mdx for "why does it look like this"

#### 2.3: `getting-started/coordinate-system.mdx`
**Source:** RUNTIME_API.md §Coordinate System (lines ~35-65)
**Content:**
- Isometric coordinate axes diagram (x, y, z)
- Projection formula: `screenX = originX + x·scale·cos(angle) + y·scale·cos(π-angle)`
- Origin placement explanation (center-bottom of viewport)
- Depth sorting formula: `depth = x + y - 2z`
- Why z goes "up" on screen
- Visual: annotated screenshot with axis overlays (can reuse grid.png)

### Priority 1 — Guides (P0-P1)

#### 2.4: `guides/shapes.mdx`
**Source:** New content + shape screenshots from Phase 1
**Content:**
- Shape catalog with visual for each:
  | Shape | Screenshot | Constructor | Key params |
  |---|---|---|---|
  | Prism | shape-prism.png | `Prism(position, width, depth, height)` | All default 1.0 |
  | Pyramid | shape-pyramid.png | `Pyramid(position, width, depth, height)` | All default 1.0 |
  | Cylinder | shape-cylinder.png | `Cylinder(position, radius, height, vertices)` | vertices controls smoothness |
  | Octahedron | shape-octahedron.png | `Octahedron(position)` | Fixed unit size |
  | Stairs | shape-stairs.png | `Stairs(position, stepCount)` | stepCount required |
  | Knot | shape-knot.png | `Knot(position)` | Experimental, depth-sort issues |
- Transform operations with screenshots:
  - `translate(dx, dy, dz)` — translate.png
  - `scale(origin, dx, dy, dz)` — scale.png
  - `rotateZ(origin, angle)` — rotate-z.png
- Custom shapes via `Shape.extrude(path, height)` — extrude-before.png / extrude-after.png
- Link to custom-shapes.mdx for advanced usage

#### 2.5: `guides/animation.mdx`
**Source:** RUNTIME_API.md §Animation examples + sample app `AnimatedSample`
**Content:**
- `withFrameNanos` for vsync-aligned animation
- Basic rotation animation (single shape spinning)
- Wave animation (ForEach with phase offsets)
- Color cycling
- Performance: only changed nodes recompose
- Code example from `RuntimeApiActivity.AnimatedSample`

#### 2.6: `guides/gestures.mdx`
**Source:** RUNTIME_API.md §Interactive example + GestureConfig API
**Content:**
- `GestureConfig` — onTap, onDrag, onDragStart, onDragEnd
- `TapEvent` — x, y, node (hit-test result)
- `DragEvent` — x, y
- dragThreshold explanation
- `GestureConfig.Disabled` (default)
- Example: tap to identify shapes (from `InteractiveSample`)
- Example: drag to rotate scene
- How hit testing works (spatial index, front-to-back order)

#### 2.7: `guides/theming.mdx`
**Source:** CompositionLocals.kt + new content
**Content:**
- `IsoColor` — RGB constructor, Int constructor, alpha
- `IsoColor.fromHex()` — Long and String overloads
- Named color constants (WHITE, BLACK, RED, etc.)
- HSL properties (h, s, l) and `lighten()`
- `ColorPalette` — primary, secondary, accent, background, surface, error
- `LocalDefaultColor`, `LocalLightDirection`, `LocalStrokeStyle`, `LocalColorPalette`
- `StrokeStyle` sealed class — FillOnly, Stroke, FillAndStroke
- Compose interop: `Color.toIsoColor()` / `IsoColor.toComposeColor()`
- Example: themed scene with custom palette

#### 2.8: `guides/camera.mdx`
**Source:** CameraState.kt + new content
**Content:**
- `CameraState(panX, panY, zoom)` — snapshot-state-backed
- `pan(deltaX, deltaY)`, `zoomBy(factor)`, `reset()`
- Passing to `SceneConfig(cameraState = ...)`
- Example: pinch-to-zoom, drag-to-pan
- Example: programmatic camera animation
- Coordinate system interaction (pan is screen-space, not world-space)

### Priority 1 — Reference (P1-P2)

#### 2.9: `reference/composables.mdx`
**Source:** IsometricComposables.kt KDoc + RUNTIME_API.md §API Reference
**Content:**
- Curated reference table for each composable:
  - `IsometricScene` — entry point, two overloads (SceneConfig vs AdvancedSceneConfig)
  - `Shape` — geometry + color + transforms, name disambiguation note
  - `Group` — hierarchical transforms, content slot
  - `Path` — 2D polygon face in 3D space
  - `Batch` — efficient multi-shape rendering
  - `If` — conditional rendering
  - `ForEach` — list rendering with optional keys
  - `CustomNode` — escape hatch with render lambda
- Full parameter tables with types, defaults, descriptions
- Link to Dokka for implementation details

#### 2.10: `reference/scene-config.mdx`
**Source:** SceneConfig.kt + AdvancedSceneConfig.kt + PERFORMANCE_OPTIMIZATIONS.md
**Content:**
- `SceneConfig` parameter table:
  | Param | Type | Default | Description |
  |---|---|---|---|
  | renderOptions | RenderOptions | Default | Depth sorting, culling, bounds checking |
  | lightDirection | Vector | (2,-1,3).normalize() | Directional light for face shading |
  | defaultColor | IsoColor | Material Blue | Default shape color |
  | colorPalette | ColorPalette | default palette | Theme colors |
  | strokeStyle | StrokeStyle | FillAndStroke | Edge rendering |
  | gestures | GestureConfig | Disabled | Tap/drag handlers |
  | useNativeCanvas | Boolean | false | Android native canvas path |
  | cameraState | CameraState? | null | Pan/zoom state |
- `AdvancedSceneConfig` additional params (engine, caching, hooks)
- `RenderOptions` presets (Default, NoDepthSorting, NoCulling)
- Usage examples: basic, performance-tuned, interactive

### Priority 1 — Other (P1)

#### 2.11: `faq.mdx`
**Source:** RUNTIME_API.md §Troubleshooting + new content
**Content:**
- **"Unresolved reference: Path"** — name collision with `kotlin.io.path.Path`. Fix: `import io.fabianterhorst.isometric.Path as IsoPath`
- **"Color is ambiguous"** — Compose `Color` vs `IsoColor`. Use `IsoColor` or import alias.
- **"Shapes render in wrong order"** — depth sorting formula, `RenderOptions.enableDepthSorting`
- **"Scene is blank"** — check IsometricScene is in a Compose context, check shape positions
- **"How do I use this with the old View API?"** — link to migration guide
- **"Is this published to Maven Central?"** — not yet, use composite build

#### 2.12: `examples/basic-scenes.mdx`
**Source:** Sample app (ComposeActivity, RuntimeApiActivity) + new content
**Content:**
- 5-6 standalone, copy-paste recipes:
  1. Single cube with custom color
  2. Multiple shapes scene (from `multipleShapes` screenshot)
  3. Stacked/translated cubes
  4. Grid with axis lines (from grid screenshot)
  5. Complex building scene (from complex-scene screenshot)
  6. Animated spinning octahedron

### Content to migrate (not new pages, but content moves)

| Existing file | Content migrates to | Action on original |
|---|---|---|
| `docs/RUNTIME_API.md` | quickstart, composables ref, animation guide, gestures guide, coordinate-system, faq | Keep as-is until site launches, then add deprecation notice pointing to site |
| `docs/MIGRATION.md` | `migration/view-to-compose.mdx` | Keep as-is until site launches |
| `docs/PERFORMANCE_OPTIMIZATIONS.md` | `reference/scene-config.mdx` + future `guides/performance.mdx` (Phase 3) | Keep as-is |
| `docs/PRIMITIVE_LEVELS.md` | Future `concepts/scene-graph.mdx` (Phase 3) | Keep as-is |
| `docs/OPTIMIZATION_SUMMARY.md` | Absorbed into scene-config ref + future performance guide | Keep as-is |

---

## WS3 — KDoc Improvements

Priority order based on user-facing impact and Dokka readiness.

### Tier 1 — Critical (must-have before Dokka is useful)

| File | What needs KDoc | Est. lines |
|---|---|---|
| `SceneConfig.kt` | Class doc + all 8 constructor params | ~30 |
| `AdvancedSceneConfig.kt` | Class doc + all params especially hook callbacks | ~50 |
| `GestureConfig.kt` | Class doc + all params + `enabled` + `Disabled` | ~25 |
| `StrokeStyle.kt` | Sealed class doc + all 3 variants + DefaultStrokeColor | ~20 |
| `GestureEvents.kt` | `TapEvent` (especially `node` field) + `DragEvent` | ~10 |

### Tier 2 — Important (significantly improves Dokka output)

| File | What needs KDoc | Est. lines |
|---|---|---|
| `IsoColor.kt` | Value ranges, HSL semantics, factory methods, color constants | ~40 |
| `IsometricEngine.kt` | `add()`, `clear()`, `findItemAt()`, `projectScene()` params | ~30 |
| `Shape.kt` | Class doc expansion, `extrude()` params | ~20 |
| `Path.kt` | Class doc (winding, face concept), variadic constructor | ~15 |
| `CompositionLocals.kt` | `LocalStrokeStyle`, `ColorPalette` class + properties | ~20 |

### Tier 3 — Nice to have (polish)

| File | What needs KDoc | Est. lines |
|---|---|---|
| `IsometricScene.kt` | `@param` tags on both overloads | ~15 |
| Shape subclasses (Prism, Pyramid, Cylinder, Stairs, Octahedron) | Constructor param docs | ~5 each, ~25 total |
| `Circle.kt` | Constructor params, `vertices` quality note | ~10 |
| `Point.kt` | Operator overloads, secondary `scale()` overloads | ~15 |
| `If`/`ForEach` composables | Expand thin docs | ~10 |

### Already good (no work needed)

- `IsometricComposables.kt` — Shape, Group, Path, Batch, CustomNode (all have full KDoc)
- `CameraState.kt` — good coverage
- `RenderOptions.kt` — good coverage with `@property` tags
- `Point.kt` — mostly good, minor gaps
- `Knot.kt` — best shape doc (warns about depth-sort issues)

---

## WS4 — Dokka Integration

### Step 4.1: Add Dokka plugin

```kotlin
// Root build.gradle.kts
plugins {
    id("org.jetbrains.dokka") version "2.1.0"
}
```

### Step 4.2: Configure multi-module generation

```bash
./gradlew dokkaGeneratePublicationHtml
```

Output: `build/dokka/html/`

### Step 4.3: Embed in site

- Copy `build/dokka/html/` → `site/public/api/` during site build
- Add sidebar link: `{ label: 'API Reference', link: '/api/' }`
- Dokka runs as a separate Gradle task, not part of the Astro build

### Step 4.4: CI integration

Add to `.github/workflows/docs.yml`:
```yaml
- run: ./gradlew dokkaGeneratePublicationHtml
- run: cp -r build/dokka/html site/public/api
```

---

## Execution Order

```
Week 1:  WS1 (site scaffold) + WS3 Tier 1 (critical KDoc)
Week 2:  WS2 pages 2.1-2.3 (getting-started) + WS3 Tier 2
Week 3:  WS2 pages 2.4-2.8 (guides) + WS4 (Dokka)
Week 4:  WS2 pages 2.9-2.12 (reference, faq, examples) + polish + deploy
```

KDoc work (WS3) is independent and can be done in parallel with everything else.
Site scaffold (WS1) should be first so content pages can be previewed locally.

---

## Verification Checklist

- [ ] `cd site && npm run dev` — site builds and serves locally
- [ ] All 12 MDX pages render with correct formatting
- [ ] Screenshots display in shapes guide and examples
- [ ] Sidebar navigation matches the planned structure
- [ ] Search finds content across all pages
- [ ] Dokka HTML serves at `/api/` with all public classes
- [ ] GitHub Actions deploys to Pages on push to master
- [ ] No broken internal links between pages
- [ ] Mobile responsive (Starlight handles this, but verify)
- [ ] Dark mode renders screenshots correctly (white backgrounds)

---

## Files Modified/Created Summary

### New files (~35)
- `site/` directory (scaffold ~8 files)
- `docs/getting-started/` (3 MDX files)
- `docs/guides/` (5 MDX files)
- `docs/concepts/` (1 MDX file)
- `docs/reference/` (2 MDX files)
- `docs/examples/` (1 MDX file)
- `docs/migration/` (1 MDX file — content migrated from MIGRATION.md)
- `docs/faq.mdx`
- `.github/workflows/docs.yml`

### Modified files (~15)
- Root `build.gradle.kts` (Dokka plugin)
- ~15 Kotlin source files for KDoc improvements (WS3)

### Unchanged
- All existing `docs/*.md` files remain as-is (deprecated once site launches)
- `docs/internal/` untouched
- `README.md` — add link to docs site once deployed
