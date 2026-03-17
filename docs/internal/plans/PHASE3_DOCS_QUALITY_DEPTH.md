# Phase 3 — Documentation Quality, Depth & Automation

> Depends on: Phase 2 (complete)
> Goal: Fix known quality issues in existing pages, add the 14 planned depth pages,
> finish KDoc coverage, migrate remaining loose docs, and mature the CI pipeline.

---

## Overview

Phase 2 shipped a browsable site with 15 content pages. Phase 3 addresses everything
that was deferred: correctness fixes in existing pages, advanced/conceptual content,
contributor docs, full KDoc coverage, content migration from loose Markdown, and
CI automation.

Phase 3 has five workstreams:

| # | Workstream | Scope | Dependencies |
|---|---|---|---|
| WS1 | Fix existing page quality issues | 8 targeted fixes across 5 existing pages | None |
| WS2 | New content pages (14 pages) | Guides, concepts, examples, contributing, reference | WS1 (correctness fixes first) |
| WS3 | KDoc completion (Tier 3 + secondary gaps) | ~12 source files | None |
| WS4 | Content migration & cleanup | Migrate 4 loose docs, update README links | WS2 (pages must exist first) |
| WS5 | CI & automation maturity | Sitemap, link checker, Vale, changelog | WS2 (all pages must exist) |

---

## WS1 — Fix Existing Page Quality Issues

These are bugs and gaps in Phase 2 pages that should be fixed before writing new content,
because new pages will cross-link to them.

### Fix 1.1: `gestures.mdx` — broken drag example (CRITICAL)

**Problem:** The `onDrag` example references `event.deltaX` and `event.deltaY`, but the
actual `DragEvent` data class only has `x` and `y` fields. The example will not compile.

**Also:** The `DragEvent` properties table is missing entirely — only `TapEvent` has a table.

**Action:**
- Verify the actual `DragEvent` fields in `GestureEvents.kt`
- Fix the example code to use the correct field names
- Add a `DragEvent` property table matching the `TapEvent` table format
- Verify `TapEvent.x`/`TapEvent.y` types — the table says `Float` but the source may
  declare `Double`; align the table with the source

### Fix 1.2: `gestures.mdx` — `TapEvent` type mismatch

**Problem:** The `TapEvent` table says `x` and `y` are `Float`, but the source declares
them as `Double`. Verify and correct the table.

### Fix 1.3: `custom-shapes.mdx` — `CustomNode` example is a non-functional stub (CRITICAL)

**Problem:** The example returns `emptyList()` — a reader cannot write a working
`CustomNode` from this. The render lambda signature shown is also outdated
(`context, nodeId` params vs actual `SceneProjector.(RenderContext)` receiver pattern).

**Action:**
- Replace the stub with a real working example that constructs a `RenderCommand`
- Show the `RenderContext` methods: `applyTransformsToPath()`, `applyTransformsToShape()`
- Reference the `IsometricComposables.kt` KDoc example as the source of truth

### Fix 1.4: `quickstart.mdx` — unlinked guide references

**Problem:** The "Next Steps" section has `**Shapes Guide**` and `**Animation Guide**`
as bold text with no hyperlinks.

**Action:**
- Link Shapes Guide to `/guides/shapes/`
- Link Animation Guide to `/guides/animation/`

### Fix 1.5: `theming.mdx` — incomplete `IsoColor` coverage

**Problems:**
- Named constants list says "and more" — should list all 13 constants
- `fromArgb(a, r, g, b)` factory method is missing entirely
- `lighten()` method is not mentioned (public API, used by renderer)
- `LocalRenderOptions` is missing from the CompositionLocals table
- `LocalIsometricEngine` is missing from the CompositionLocals table

**Action:**
- List all 13 named color constants explicitly
- Add `fromArgb` to the constructors section
- Add a `lighten()` subsection with a practical shade-variation example
- Add `LocalRenderOptions` and `LocalIsometricEngine` to the CompositionLocals table

### Fix 1.6: `composables.mdx` — thin entries for `Batch`, `If`, `ForEach`, `Path`

**Problems:**
- `Batch` — no code example, no "when to use" guidance vs `ForEach`
- `If` — no code example
- `ForEach` — no key function example
- `Path` — says "same params as Shape" instead of showing the full table

**Action:**
- Add a `Batch` code example showing rendering a list of shapes efficiently
- Add an `If` toggle example (show/hide a shape based on state)
- Add a `ForEach` example with a `key` function for stable recomposition
- Expand `Path` to its own parameter table (same columns as Shape)

### Fix 1.7: `camera.mdx` — missing constructor params and animation example

**Problems:**
- `CameraState()` constructor params (`panX`, `panY`, `zoom`) are undocumented
- No programmatic camera animation example (smooth pan/zoom)
- No pinch-to-zoom example (mentioned in Phase 2 plan but not written)

**Action:**
- Document the `CameraState(panX, panY, zoom)` constructor with defaults
- Add an animated camera example using `LaunchedEffect` + `animate*AsState`
- Add a pinch-to-zoom gesture example

### Fix 1.8: `scene-config.mdx` — missing `AdvancedSceneConfig` callbacks + no example

**Problems:**
- `onFlagsReady` and `onRenderError` callbacks are absent from the reference table
- No usage example for `AdvancedSceneConfig` — users don't know when to choose it

**Action:**
- Add the two missing callbacks to the table
- Add a "When to use AdvancedSceneConfig" section with a practical example
  (e.g., debug overlay using `onBeforeDraw`/`onAfterDraw`, error logging with `onRenderError`)

---

## WS2 — New Content Pages (14 pages)

Organized by section. Priority reflects user impact and dependency order.

### Guides (2 new pages)

#### 2.1: `guides/performance.mdx` — P0

**Source:** `PERFORMANCE_OPTIMIZATIONS.md`, `OPTIMIZATION_SUMMARY.md`, sample app
`OptimizedPerformanceSample.kt`

**Content:**
- Performance model overview:
  - Per-node dirty tracking — only changed subtrees recompose
  - PreparedScene caching — full scene projection is cached when tree is clean
  - Spatial indexing — O(1) hit testing regardless of scene size
- `RenderOptions` tuning guide:
  | Flag | Default | When to change | Impact |
  |---|---|---|---|
  | `enableDepthSorting` | true | Flat scenes, overlays | Skips topological sort |
  | `enableBackfaceCulling` | true | Transparent shapes | Renders all faces |
  | `enableBoundsChecking` | true | Full-viewport scenes | Skips bounds test |
  | `enableBroadPhaseSort` | true | Very few shapes (<10) | Removes grid overhead |
  | `broadPhaseCellSize` | 100.0 | Dense or sparse scenes | Tune bucket granularity |
- `useNativeCanvas` — what it does, when to enable, Android-only caveat
- `enablePathCaching` — cache projected Compose `Path` objects between frames
- `enableSpatialIndex` / `spatialIndexCellSize` — hit-test grid tuning
- Benchmark results table from `PERFORMANCE_OPTIMIZATIONS.md`:
  | Scene | Without caching | With caching | Speedup |
  (migrate the existing frame-time data)
- `AdvancedSceneConfig` performance knobs:
  - `forceRebuild` — bypass cache for debugging
  - `frameVersion` — external cache invalidation signal
- Anti-patterns: `delay(16)` loops, recreating geometry every frame, key-less `ForEach`
- **Note:** Off-thread computation is NOT a public API — omit or label as roadmap

#### 2.2: `guides/advanced-config.mdx` — P1

**Source:** `AdvancedSceneConfig.kt`, `RUNTIME_API.md` advanced topics,
`PrimitiveLevelsExample.kt`

**Content:**
- When to use `AdvancedSceneConfig` vs `SceneConfig` — decision tree
- Lifecycle hooks overview table:
  | Hook | Signature | Fires when | Use case |
  |---|---|---|---|
  | `onEngineReady` | `(SceneProjector) -> Unit` | Engine created | Store ref for `worldToScreen` |
  | `onRendererReady` | `(IsometricRenderer) -> Unit` | Renderer created | Access cache controls |
  | `onHitTestReady` | `(Float, Float, RenderCommand?) -> Unit` | After each hit-test | External hit-test UI |
  | `onPreparedSceneReady` | `(PreparedScene) -> Unit` | After projection | Export/serialize scene |
  | `onFlagsReady` | `(RuntimeFlagSnapshot) -> Unit` | After config applied | Validation/logging |
  | `onRenderError` | `(String, Throwable) -> Unit` | Render failure | Error reporting |
  | `onBeforeDraw` | `DrawScope.() -> Unit` | Before scene paint | Custom background |
  | `onAfterDraw` | `DrawScope.() -> Unit` | After scene paint | Debug overlay |
- Worked example: debug overlay using `onAfterDraw` to draw bounding boxes
- Worked example: error logging with `onRenderError`
- `engine: SceneProjector` — injecting a custom or mock engine
- `forceRebuild` and `frameVersion` — manual cache control

### Concepts (3 new pages)

#### 2.3: `concepts/scene-graph.mdx` — P1

**Source:** `RUNTIME_API.md` architecture section, `PRIMITIVE_LEVELS.md`,
`IsometricNode.kt`, `IsometricApplier.kt`

**Content:**
- Three-layer architecture diagram:
  ```
  ┌─────────────────────────────┐
  │  Composable API             │  Shape(), Group(), Path(), ...
  │  (IsometricComposables.kt)  │
  ├─────────────────────────────┤
  │  Node Tree                  │  IsometricNode hierarchy
  │  (IsometricApplier)         │  Dirty tracking, tree diffing
  ├─────────────────────────────┤
  │  Rendering Engine           │  IsometricEngine → PreparedScene
  │  (IsometricRenderer)        │  → Canvas draw commands
  └─────────────────────────────┘
  ```
- Node types:
  | Node | Composable | Role |
  |---|---|---|
  | `ShapeNode` | `Shape()` | Single geometry + color |
  | `GroupNode` | `Group()` | Transform container, content slot |
  | `PathNode` | `Path()` | 2D polygon face |
  | `BatchNode` | `Batch()` | Multiple shapes, shared transforms |
  | `CustomRenderNode` | `CustomNode()` | Escape hatch, raw render commands |
- Dirty tracking: `markDirty()` propagation → `sceneVersion` bump → Canvas invalidation
- `IsometricApplier`: Compose runtime's `Applier<IsometricNode>` — insert/remove/move
- Why selective recomposition works: only the `Shape` whose state changed recomposes;
  the rest of the tree is skipped
- High-level vs low-level API comparison from `PRIMITIVE_LEVELS.md`:
  - High-level: `Shape()` composable → node created by runtime
  - Low-level: `IsometricEngine.add()` → manual management
  - When to use each (99% use high-level; low-level for non-Compose targets or custom renderers)

#### 2.4: `concepts/depth-sorting.mdx` — P2

**Source:** `DepthSorter.kt` (internal), `RenderOptions.kt`, coordinate-system.mdx depth
section, `PERFORMANCE_OPTIMIZATIONS.md` depth sorting section

**Content:**
- Depth formula recap: `depth = x + y - 2z`
- Why isometric needs explicit sorting (no z-buffer like perspective 3D)
- The sorting pipeline:
  1. Compute per-face depth from mean vertex position
  2. Broad-phase: bucket faces into spatial grid cells (`broadPhaseCellSize`)
  3. Narrow-phase: test AABB intersection between candidate pairs
  4. Topological sort: build DAG from "in front of" relationships, topological order
- Known limitations:
  - Intersecting faces (faces that pass through each other) have no correct sort order
  - The `Knot` shape self-intersects → depth artifacts expected
  - Very large shapes spanning many grid cells degrade broad-phase efficiency
- Tuning: `broadPhaseCellSize` trade-off (smaller = fewer candidates but more cells)
- `enableBroadPhaseSort = false`: falls back to O(n²) pairwise test (correct but slow)
- `enableDepthSorting = false`: render in insertion order (useful for flat 2D overlays)

#### 2.5: `concepts/rendering-pipeline.mdx` — P2

**Source:** `IsometricEngine.kt`, `IsometricRenderer.kt`, `SceneCache.kt` (internal)

**Content:**
- End-to-end pipeline diagram:
  ```
  Compose recomposition
       ↓
  Node tree dirty?  ──no──→  Cache hit (skip projection)
       ↓ yes
  Traverse tree → engine.add(shape, color)
       ↓
  engine.projectScene(width, height)
       ↓
  PreparedScene (List<RenderCommand>)
       ↓
  DrawScope.drawPath() for each command   ← or NativeSceneRenderer on Android
  ```
- Cache invalidation — three independent axes:
  1. Dirty tree flag (content changed)
  2. `projectionVersion` (engine angle/scale changed)
  3. Viewport dimensions (width/height changed)
- `RenderCommand` anatomy: `commandId`, `points: List<Point2D>`, `color`, `originalPath`,
  `originalShape`, `ownerNodeId`
- Path caching: pre-converts `RenderCommand.points` to `androidx.compose.ui.graphics.Path`
  objects between frames → avoids per-frame allocation
- Native canvas path: `NativeSceneRenderer` bypasses Compose `DrawScope`, uses
  `android.graphics.Canvas` directly with reused `Paint` objects — ~2x faster on Android,
  Android-only (silently no-ops on other platforms)

### Reference (2 new pages)

#### 2.6: `reference/composition-locals.mdx` — P1

**Source:** `CompositionLocals.kt`

**Content:**
- Full reference table for every public CompositionLocal:
  | Local | Type | Default | Purpose |
  |---|---|---|---|
  | `LocalDefaultColor` | `IsoColor` | Material Blue (33,150,243) | Default shape color |
  | `LocalLightDirection` | `Vector` | `(2,-1,3).normalize()` | Face shading direction |
  | `LocalRenderOptions` | `RenderOptions` | `RenderOptions.Default` | Depth sort, culling, bounds |
  | `LocalStrokeStyle` | `StrokeStyle` | `FillAndStroke()` | Edge rendering |
  | `LocalColorPalette` | `ColorPalette` | Built-in palette | Semantic theme colors |
  | `LocalIsometricEngine` | `IsometricEngine` | Error if not in scene | Engine access for `worldToScreen` |
- Override pattern with `CompositionLocalProvider` — worked example
- Per-subtree theming example: dark palette in one Group, light in another
- `LocalIsometricEngine` usage: coordinate conversion inside a scene
  ```kotlin
  val engine = LocalIsometricEngine.current
  val screenPoint = engine.worldToScreen(worldPoint, viewportWidth, viewportHeight)
  ```
- Why `staticCompositionLocalOf` is used (values set once per scene, avoids recomp tracking)

#### 2.7: `reference/engine.mdx` — P2

**Source:** `IsometricEngine.kt`, `SceneProjector.kt`, `PrimitiveLevelsExample.kt`

**Content:**
- `IsometricEngine` constructor reference:
  | Param | Type | Default | Description |
  |---|---|---|---|
  | `angle` | `Double` | `PI/6` | Isometric projection angle |
  | `scale` | `Double` | `70.0` | Pixels per world unit |
  | `colorDifference` | `Double` | `0.20` | Face shading contrast |
  | `lightColor` | `IsoColor` | `IsoColor.WHITE` | Light tint color |
- Key methods:
  - `add(shape: Shape, color: IsoColor)` — add geometry to the scene buffer
  - `add(path: Path, color: IsoColor, ...)` — add a single face with metadata
  - `clear()` — reset the scene buffer
  - `projectScene(width, height, renderOptions, lightDirection): PreparedScene`
  - `findItemAt(preparedScene, x, y, order, touchRadius): RenderCommand?`
  - `worldToScreen(point, viewportWidth, viewportHeight): Point2D`
  - `screenToWorld(screenPoint, viewportWidth, viewportHeight, z): Point`
- `SceneProjector` interface — the abstraction contract that `IsometricEngine` implements
  - Purpose: dependency injection for testing, alternative projection implementations
  - All `AdvancedSceneConfig.engine` and `CustomNode.render` use this interface
- Direct usage pattern (non-Compose):
  ```kotlin
  val engine = IsometricEngine(scale = 100.0)
  engine.add(Prism(Point.ORIGIN), IsoColor.BLUE)
  val scene = engine.projectScene(800, 600, RenderOptions.Default, lightDir)
  // scene.commands contains List<RenderCommand> — render with any backend
  ```
- `projectionVersion` — increments when `angle` or `scale` changes, used for cache
  invalidation

### Examples (3 new pages)

#### 2.8: `examples/animation-patterns.mdx` — P1

**Source:** `ComposeActivity.kt` `AnimatedSample`, `RuntimeApiActivity.kt` `AnimationSample`

**Content:**
- Recipe 1: Spinning shape (basic `withFrameNanos` rotation)
- Recipe 2: Wave grid (ForEach + phase offsets) — expand from animation.mdx
- Recipe 3: Color cycling (rotate hue using `IsoColor.h` + HSL reconstruction)
- Recipe 4: Pulsing scale (sin-wave scale factor)
- Recipe 5: Orbiting shapes (multiple shapes rotating around a center point)
- Recipe 6: Staggered entrance animation (shapes appearing one by one with delay offsets)

Each recipe: full copy-paste code, brief explanation, which concepts it demonstrates.

#### 2.9: `examples/interactive-scenes.mdx` — P1

**Source:** `ComposeActivity.kt` `InteractiveSample`, `RuntimeApiActivity.kt`
`RuntimeInteractiveSample`

**Content:**
- Recipe 1: Tap to highlight (shape changes color on tap, resets on background tap)
- Recipe 2: Drag to pan with camera (CameraState + GestureConfig.onDrag)
- Recipe 3: Pinch to zoom (transformable modifier + CameraState.zoomBy)
- Recipe 4: Tap to add shapes (dynamic scene building)
- Recipe 5: Shape picker UI (Compose Row of buttons that select the active geometry type)

#### 2.10: `examples/advanced-patterns.mdx` — P2

**Source:** `PrimitiveLevelsExample.kt`, `IsometricComposables.kt` KDoc

**Content:**
- Pattern 1: Custom composable extension (`Tower` pattern — `IsometricScope` extension
  that generates multiple shapes)
- Pattern 2: Working `CustomNode` with real `RenderCommand` construction
- Pattern 3: `Batch` for bulk rendering (100+ shapes with shared transforms)
- Pattern 4: Per-subtree render options (disable depth sort for a flat overlay group)
- Pattern 5: `onBeforeDraw` / `onAfterDraw` for custom background or debug overlay
- Pattern 6: Direct `IsometricEngine` usage outside Compose

### Contributing (3 new pages)

#### 2.11: `contributing/setup.mdx` — P1

**Source:** `CONTRIBUTING.md`

**Content:**
- Prerequisites: JDK 17, Android SDK 34, Android Studio / IntelliJ
- Clone and build:
  ```bash
  git clone https://github.com/jayteealao/Isometric.git
  cd Isometric
  ./gradlew assembleDebug
  ```
- Run the sample app: `./gradlew :app:installDebug`
- Project structure overview:
  | Module | Purpose |
  |---|---|
  | `isometric-core` | Pure Kotlin/JVM engine |
  | `isometric-compose` | Jetpack Compose integration |
  | `isometric-android-view` | Legacy View support |
  | `isometric-benchmark` | Performance benchmarks |
  | `app` | Sample/demo app |
  | `site` | Documentation site (Astro + Starlight) |
- IDE setup notes (import as Gradle project, sync, etc.)

#### 2.12: `contributing/testing.mdx` — P2

**Source:** `CONTRIBUTING.md` testing section, test source files

**Content:**
- Unit tests: `./gradlew :isometric-core:test`
  - `IsoColorTest`, `PointTest`, `VectorTest`, `PathTest`, `ShapeTest`,
    `IsometricEngineTest`, `IsometricEngineProjectionTest`, `Point2DTest`
- Screenshot generation: `./gradlew :isometric-core:test --tests "*.DocScreenshotGenerator"`
  - Output: `docs/assets/screenshots/` → copied to `site/public/screenshots/`
- Paparazzi snapshot tests (if configured)
- Maestro UI tests (if configured)
- Writing new tests: naming conventions, test file location, assertion patterns

#### 2.13: `contributing/docs-guide.mdx` — P2

**Source:** New content

**Content:**
- Docs site architecture: Astro + Starlight + MDX + Pagefind
- Running locally: `cd site && npm install && npm run dev`
- Creating a new page:
  1. Create `.mdx` file in the right directory
  2. Add YAML frontmatter (`title`, `description`, `sidebar.order`)
  3. Add to `astro.config.mjs` sidebar if using explicit slugs
  4. Write content using MDX (Starlight components: `Tabs`, `TabItem`, `Card`, etc.)
- Screenshot workflow: generate with `DocScreenshotGenerator`, copy to `site/public/screenshots/`
- KDoc conventions: all public symbols need class doc + `@param` tags
- Style guide: code examples should be self-contained and copy-pasteable

### Migration (1 page update)

#### 2.14: `migration/view-to-compose.mdx` — update (P2)

**Action:** Enrich the existing page with content from `docs/MIGRATION.md` that isn't
already present:
- Three migration patterns (gradual, parallel, full rewrite)
- Type conversion table (`Color` → `IsoColor`, `setClickListener` → `GestureConfig`)
- Troubleshooting section (common migration errors)
- Performance comparison tips (View invalidation vs Compose recomposition)

---

## WS3 — KDoc Completion

### Tier 3 (from Phase 2 plan — still outstanding)

| File | What needs KDoc | Est. lines |
|---|---|---|
| `IsometricScene.kt` | `@param` tags on both composable overloads (SceneConfig + AdvancedSceneConfig) | ~12 |
| `Circle.kt` | `@param origin`, `@param radius`, `@param vertices` with validation note (vertices ≥ 3) | ~10 |
| `If` composable | Expand to `@param condition`, explain dirty-tracking on flip | ~8 |
| `ForEach` composable | `@param items`, `@param key`, stable key requirement for efficient recomposition | ~10 |

### Secondary gaps (discovered during Phase 3 audit)

| File | What needs KDoc | Est. lines |
|---|---|---|
| `Vector.kt` | Class doc expansion, `magnitude()`, `normalize()`, `cross`, `dot` operators | ~20 |
| `IntersectionUtils.kt` | `@param`/`@return` on all 3 public methods | ~15 |
| `HitOrder.kt` | KDoc on `FRONT_TO_BACK` and `BACK_TO_FRONT` enum values | ~6 |
| `ColorExtensions.kt` | KDoc on `Color.toIsoColor()` and `IsoColor.toComposeColor()` | ~8 |
| `Point.kt` | `@param`/`@return` on operator overloads and secondary `scale()` overloads | ~20 |
| `IsometricView.kt` | Method-level KDoc for `setSort`, `setCull`, `setBoundsCheck`, `setClickListener`, `add`, `clear` | ~25 |

### Verification

After all KDoc is added, run `./gradlew dokkaGeneratePublicationHtml` and verify the
output has no "missing documentation" warnings for public symbols.

---

## WS4 — Content Migration & Cleanup

### Step 4.1: Mark migrated docs as deprecated

Once Phase 3 pages exist, add a deprecation notice at the top of each migrated file:

| File | Content migrated to | Action |
|---|---|---|
| `docs/RUNTIME_API.md` | Multiple site pages (quickstart, composables, animation, gestures, coordinate-system, faq, scene-graph, advanced-patterns) | Add notice: "This document has been superseded by the docs site." |
| `docs/MIGRATION.md` | `migration/view-to-compose.mdx` | Add notice |
| `docs/PERFORMANCE_OPTIMIZATIONS.md` | `guides/performance.mdx` + `reference/scene-config.mdx` | Add notice |
| `docs/OPTIMIZATION_SUMMARY.md` | `guides/performance.mdx` | Add notice |
| `docs/PRIMITIVE_LEVELS.md` | `concepts/scene-graph.mdx` | Add notice |

### Step 4.2: Update README.md links

Replace docs-file links with site URLs:

| Current link | New link |
|---|---|
| `docs/PERFORMANCE_OPTIMIZATIONS.md` | Site `/guides/performance/` |
| `docs/PRIMITIVE_LEVELS.md` | Site `/concepts/scene-graph/` |
| `docs/OPTIMIZATION_SUMMARY.md` | Site `/guides/performance/` |
| `docs/RUNTIME_API.md` | Site `/getting-started/quickstart/` |
| `docs/MIGRATION.md` | Site `/migration/view-to-compose/` |

Add a prominent "📖 Documentation" link near the top of the README pointing to the
site root.

### Step 4.3: Update `astro.config.mjs` sidebar

Add entries for all new sections:

```ts
sidebar: [
  { label: 'Getting Started', items: [ ... ] },       // existing (3)
  { label: 'Guides', items: [
    // existing (6)
    { label: 'Performance', slug: 'guides/performance' },
    { label: 'Advanced Config', slug: 'guides/advanced-config' },
  ]},
  { label: 'Concepts', items: [
    { label: 'Scene Graph', slug: 'concepts/scene-graph' },
    { label: 'Depth Sorting', slug: 'concepts/depth-sorting' },
    { label: 'Rendering Pipeline', slug: 'concepts/rendering-pipeline' },
  ]},
  { label: 'Reference', items: [
    // existing (2)
    { label: 'CompositionLocals', slug: 'reference/composition-locals' },
    { label: 'Engine & Projector', slug: 'reference/engine' },
  ]},
  { label: 'Examples', items: [
    // existing (1)
    { label: 'Animation Patterns', slug: 'examples/animation-patterns' },
    { label: 'Interactive Scenes', slug: 'examples/interactive-scenes' },
    { label: 'Advanced Patterns', slug: 'examples/advanced-patterns' },
  ]},
  { label: 'FAQ', slug: 'faq' },
  { label: 'Migration', items: [ ... ] },              // existing (1)
  { label: 'Contributing', items: [
    { label: 'Setup', slug: 'contributing/setup' },
    { label: 'Testing', slug: 'contributing/testing' },
    { label: 'Docs Guide', slug: 'contributing/docs-guide' },
  ]},
  { label: 'API Reference', link: '/api/', attrs: { target: '_blank' } },
]
```

### Step 4.4: Code example consistency pass

Standardize across all pages:
- Use named `position` param consistently: `Prism(position = Point.ORIGIN, ...)`
- Use `IsoColor(r, g, b)` Int constructor as the default in examples
- Add imports where non-obvious (especially `Path` alias, `IsoColor` vs `Color`)

---

## WS5 — CI & Automation Maturity

### Step 5.1: Internal link checker

Add a post-build link validation step to `.github/workflows/docs.yml`:

```yaml
- name: Check internal links
  working-directory: site
  run: npx astro check 2>&1 | tee /tmp/astro-check.log
```

Or use a dedicated tool like `lychee` for broken link detection in the built HTML:

```yaml
- name: Check links
  uses: lycheeverse/lychee-action@v2
  with:
    args: --no-progress site/dist/
    fail: true
```

### Step 5.2: Prose linting with Vale (optional, low-priority)

```yaml
- name: Lint prose
  uses: errata-ai/vale-action@v2
  with:
    files: site/src/content/docs/
    config: site/.vale.ini
```

Create `site/.vale.ini` with Microsoft or Google style base, customized for technical
docs (allow passive voice in API descriptions, etc.).

### Step 5.3: Automated changelog with git-cliff (optional)

Add `cliff.toml` at project root. Generate changelog from conventional commits:

```yaml
- name: Generate changelog
  run: |
    cargo install git-cliff
    git-cliff -o CHANGELOG.md
```

Integrate into the docs site as `migration/changelog.mdx` or a top-level page.

### Step 5.4: Screenshot regeneration CI step (optional)

Add a workflow step that regenerates screenshots from `DocScreenshotGenerator` and
validates they haven't drifted from the committed PNGs:

```yaml
- name: Verify screenshots
  run: |
    ./gradlew :isometric-core:test --tests "*.DocScreenshotGenerator"
    git diff --exit-code docs/assets/screenshots/
```

---

## Execution Order

```
Week 1:  WS1 (all 8 fixes) + WS3 Tier 3 (4 files)
Week 2:  WS2 pages 2.1-2.2 (guides: performance, advanced-config) + WS3 secondary (6 files)
Week 3:  WS2 pages 2.3-2.5 (concepts: scene-graph, depth-sorting, rendering-pipeline)
Week 4:  WS2 pages 2.6-2.7 (reference: composition-locals, engine)
Week 5:  WS2 pages 2.8-2.10 (examples: animation, interactive, advanced)
Week 6:  WS2 pages 2.11-2.14 (contributing + migration update) + WS4 (migration/cleanup)
Week 7:  WS5 (CI maturity) + full-site review + polish
```

WS1 must be first — existing pages are cross-linked from new pages.
WS3 (KDoc) is independent and can overlap with any week.
WS5 runs last because all pages must exist for link checking to be meaningful.

---

## Verification Checklist

- [ ] All 8 WS1 fixes applied and verified (compile-test code examples mentally)
- [ ] All 14 new MDX pages render with correct formatting
- [ ] `npm run build` — zero warnings, zero broken links
- [ ] Sidebar shows all new sections (Concepts, Contributing, expanded Examples/Reference)
- [ ] Pagefind indexes all ~29 pages
- [ ] Cross-links between pages work (e.g., performance guide → scene-config reference)
- [ ] All 13 `IsoColor` named constants listed in theming.mdx
- [ ] `DragEvent` table matches source types exactly
- [ ] `CustomNode` example compiles (verified against actual API signature)
- [ ] `AdvancedSceneConfig` reference table has all callbacks (including `onFlagsReady`, `onRenderError`)
- [ ] KDoc: `./gradlew dokkaGeneratePublicationHtml` succeeds with no public-symbol gaps
- [ ] README links point to site URLs (not loose Markdown files)
- [ ] Deprecated docs have notices
- [ ] CI link checker passes
- [ ] Code examples use consistent style across all pages

---

## Files Modified/Created Summary

### New files (~20)
- `site/src/content/docs/guides/performance.mdx`
- `site/src/content/docs/guides/advanced-config.mdx`
- `site/src/content/docs/concepts/scene-graph.mdx`
- `site/src/content/docs/concepts/depth-sorting.mdx`
- `site/src/content/docs/concepts/rendering-pipeline.mdx`
- `site/src/content/docs/reference/composition-locals.mdx`
- `site/src/content/docs/reference/engine.mdx`
- `site/src/content/docs/examples/animation-patterns.mdx`
- `site/src/content/docs/examples/interactive-scenes.mdx`
- `site/src/content/docs/examples/advanced-patterns.mdx`
- `site/src/content/docs/contributing/setup.mdx`
- `site/src/content/docs/contributing/testing.mdx`
- `site/src/content/docs/contributing/docs-guide.mdx`
- `site/.vale.ini` (optional)

### Modified files (~25)
- `site/src/content/docs/guides/gestures.mdx` (Fix 1.1, 1.2)
- `site/src/content/docs/guides/custom-shapes.mdx` (Fix 1.3)
- `site/src/content/docs/getting-started/quickstart.mdx` (Fix 1.4)
- `site/src/content/docs/guides/theming.mdx` (Fix 1.5)
- `site/src/content/docs/reference/composables.mdx` (Fix 1.6)
- `site/src/content/docs/guides/camera.mdx` (Fix 1.7)
- `site/src/content/docs/reference/scene-config.mdx` (Fix 1.8)
- `site/src/content/docs/migration/view-to-compose.mdx` (2.14 enrichment)
- `site/astro.config.mjs` (sidebar update)
- `README.md` (link updates)
- `docs/RUNTIME_API.md` (deprecation notice)
- `docs/MIGRATION.md` (deprecation notice)
- `docs/PERFORMANCE_OPTIMIZATIONS.md` (deprecation notice)
- `docs/OPTIMIZATION_SUMMARY.md` (deprecation notice)
- `docs/PRIMITIVE_LEVELS.md` (deprecation notice)
- `.github/workflows/docs.yml` (link checker step)
- ~10 Kotlin source files for KDoc (WS3)
