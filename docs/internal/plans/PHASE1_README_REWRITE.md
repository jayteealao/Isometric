# Phase 1: README Rewrite & Cleanup — Implementation Plan

> Prerequisite: [DOCUMENTATION_STRATEGY.md](DOCUMENTATION_STRATEGY.md) (Phase 0 — complete)
> Target: Single PR on `feat/api-changes` branch

---

## Objective

Transform the README from a 487-line mixed-purpose document into a focused ~80-line entry point, fix all broken/misleading content, and prepare the docs folder structure for Phase 2.

---

## Scope

### In scope
1. Remove or relabel all `IsometricCanvas` references in user-facing docs
2. Generate and commit Paparazzi snapshot images for the README
3. Replace all broken upstream image links with local images
4. Rewrite README per the §7 strategy (pitch → install → quickstart → links)
5. Remove duplicated content (double "Available Shapes" section, etc.)
6. Reorganize `docs/` to separate internal docs from user-facing docs
7. Create placeholder `CHANGELOG.md` and `CONTRIBUTING.md`

### Out of scope
- Writing new docs pages (that's Phase 2)
- Setting up the Astro site (that's Phase 3)
- Dokka integration (that's Phase 5)
- CI/CD setup (that's Phase 6)
- Changing Maven coordinates / publishing (separate effort)

---

## Pre-Implementation Decisions Required

Before starting, the maintainer must decide:

| # | Decision | Options | Needed for |
|---|----------|---------|------------|
| 1 | **Is `IsometricCanvas` planned for future implementation?** | (a) No — remove all references<br>(b) Yes — label as "Coming soon" | Step 1 |
| 2 | **Installation instructions before publication** | (a) JitPack instructions<br>(b) "Not yet published" note with local build instructions<br>(c) Placeholder coordinates with "coming soon" note | Step 5 |
| 3 | **Keep upstream credit style** | (a) Keep Fabian Terhorst as primary with "forked by" note<br>(b) Present as independent project with attribution | Step 5 |

**Recommended answers:** 1(a), 2(b), 3(a)

---

## Implementation Steps

### Step 1: Generate and Commit Screenshot Images

**Why:** All 9 README images link to `https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/...` — an external repo that may change or disappear. We need local images.

**How:**

1.1. Run the Paparazzi snapshot tests to generate golden images:

```bash
cd Isometric
./gradlew :isometric-compose:recordPaparazziDebug
```

This generates PNG files in `isometric-compose/src/test/snapshots/images/` — one per `@Test` method in `IsometricCanvasSnapshotTest.kt`.

1.2. The test file contains 16 snapshot tests that cover all the shapes and operations shown in the README:

| Test method | README section it covers |
|-------------|--------------------------|
| `sampleOne` | "Drawing a simple cube" |
| `sampleTwo` | "Drawing multiple Shapes" |
| `sampleThree` | "Supports complex structures" |
| `grid` | "The grid" |
| `path` | "Drawing multiple Paths" |
| `translate` | "Translate" |
| `scale` | "Scale" |
| `rotateZ` | "RotateZ" |
| `extrude` | "Shapes from Paths" |
| `cylinder` | Available Shapes — Cylinder |
| `knot` | Available Shapes — Knot |
| `octahedron` | Available Shapes — Octahedron |
| `prism` | Available Shapes — Prism |
| `pyramid` | Available Shapes — Pyramid |
| `stairs` | Available Shapes — Stairs |

1.3. Create a `docs/assets/screenshots/` directory.

1.4. Copy the generated snapshots into `docs/assets/screenshots/` with descriptive names:

```
docs/assets/screenshots/
├── simple-cube.png          ← from sampleOne
├── multiple-shapes.png      ← from sampleTwo
├── complex-scene.png        ← from sampleThree
├── grid.png                 ← from grid
├── path.png                 ← from path
├── translate.png            ← from translate
├── scale.png                ← from scale
├── rotate-z.png             ← from rotateZ
├── extrude.png              ← from extrude
├── shape-cylinder.png       ← from cylinder
├── shape-knot.png           ← from knot
├── shape-octahedron.png     ← from octahedron
├── shape-prism.png          ← from prism
├── shape-pyramid.png        ← from pyramid
└── shape-stairs.png         ← from stairs
```

1.5. Git-add the screenshots. Do NOT add the `build/` directory outputs.

**Risk:** If the Paparazzi tests fail to run (e.g., JDK version issue with Paparazzi 1.3.0), alternative: capture screenshots from the sample app running on an emulator, or keep the upstream links temporarily with a `<!-- TODO: replace with local images -->` comment.

**Verification:** Open each PNG locally and confirm it renders a visible isometric shape.

---

### Step 2: Remove/Relabel `IsometricCanvas` References in User-Facing Docs

**Why:** `IsometricCanvas` is documented as a working API but the source file was deleted. Every reference misleads users.

**Files to modify (user-facing only):**

| File | Action | Details |
|------|--------|---------|
| `README.md` | **Remove** | Lines 14-15 (two API levels mentioning IsometricCanvas), 74-88 (High-Level API section), 179-195 (Compose drawing example using IsometricCanvas), 199-209 (multiple shapes example using IsometricCanvas), 331-371 (Animation and Interactive high-level API sections) |
| `docs/RUNTIME_API.md` | **Edit** | Lines 567, 597, 620 — remove "Simple API" comparisons or relabel as "legacy/removed" |
| `docs/MIGRATION.md` | **Edit** | This file references IsometricCanvas extensively as a migration target. Update intro to clarify IsometricCanvas was superseded by IsometricScene. Keep migration steps but update the target API name. |

**Files to leave alone (internal docs):**

All files in `docs/plans/`, `docs/reviews/`, `docs/research/` — these are historical development records. Do not modify them; they'll be moved to `docs/internal/` in Step 3.

**The snapshot test file** `IsometricCanvasSnapshotTest.kt` — rename the class to `IsometricSceneSnapshotTest` is optional and out of scope (code change, not docs change). Note: the test file already uses `IsometricScene` internally, only the class name is legacy.

**Detailed edits for README.md:**

2.1. **Line 13-16** — Architecture section. Change from:
```markdown
- **:isometric-compose** - Jetpack Compose UI components with **two API levels**:
  - **High-level API** (`IsometricCanvas`) - Simple and easy to use
  - **Runtime-level API** (`IsometricScene`) - Advanced features with ComposeNode and custom Applier
```
To:
```markdown
- **:isometric-compose** - Jetpack Compose UI components (`IsometricScene`)
```

2.2. **Lines 74-88** — Remove the entire "Compose - High-Level API (Simple scenes)" section.

2.3. **Lines 109-171** — "Runtime API Features" section. Remove the comparison table (it compares against a non-existent API). Keep the feature list but present it as the library's features, not a comparison.

2.4. **Lines 179-195** — "Drawing a simple cube" example uses `IsometricCanvas`. Replace the Compose example with `IsometricScene` equivalent.

2.5. **Lines 199-209** — "Drawing multiple Shapes" Compose example uses `IsometricCanvas`. Replace with `IsometricScene` equivalent.

2.6. **Lines 331-371** — "Animation (High-Level API)" and "Interactive Scenes (High-Level API)" sections. Remove entirely.

2.7. **Lines 113-133** — Feature comparison tables and performance comparison tables reference "Old API" / "High-Level API". Remove the comparison framing; present performance numbers as absolute values.

**Detailed edits for RUNTIME_API.md:**

2.8. Search for "Simple API", "high-level", "IsometricCanvas" and either remove comparisons or change to "the legacy API (removed)".

**Detailed edits for MIGRATION.md:**

2.9. Update the document title/intro to clarify the migration target is `IsometricScene`, not `IsometricCanvas`. The migration content (View → Compose patterns) is still valid; only the API name references need updating.

---

### Step 3: Reorganize `docs/` Folder Structure

**Why:** Internal planning/review docs are mixed with user-facing docs. Users browsing `docs/` on GitHub see 41+ files with no clear hierarchy.

**Actions:**

3.1. Create `docs/internal/` directory.

3.2. Move internal-only content:

```bash
# Planning docs
mv docs/plans/ docs/internal/plans/
mv docs/research/ docs/internal/research/
mv docs/reviews/ docs/internal/reviews/

# Internal guides
mv docs/api-design-guideline.md docs/internal/
mv docs/guides/PUBLISHING_GUIDE.md docs/internal/
```

3.3. The following files stay in `docs/` root (user-facing):

```
docs/
├── RUNTIME_API.md              ← stays (will become guides/runtime-api.mdx in Phase 2)
├── PERFORMANCE_OPTIMIZATIONS.md ← stays (will become guides/performance.mdx in Phase 2)
├── PRIMITIVE_LEVELS.md          ← stays (will become guides/advanced-config.mdx in Phase 2)
├── OPTIMIZATION_SUMMARY.md      ← stays (will become reference/ in Phase 2)
├── MIGRATION.md                 ← stays (will become migration/ in Phase 2)
├── assets/
│   └── screenshots/             ← created in Step 1
└── internal/                    ← moved here in this step
    ├── plans/
    ├── research/
    ├── reviews/
    ├── api-design-guideline.md
    └── PUBLISHING_GUIDE.md
```

3.4. Remove `docs/guides/` directory (it only contained `PUBLISHING_GUIDE.md` which was moved).

**Risk:** Any hardcoded links in internal docs that reference `../plans/` or sibling docs will break. This is acceptable — internal docs are for the maintainer, not rendered on a site. Fix links opportunistically.

**Verification:** Confirm no user-facing doc references a path inside `docs/internal/`.

---

### Step 4: Fix Shape Links

**Why:** The "Available Shapes" section links to Java source files in the upstream repo (`https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/...`). These don't exist in this fork.

**Action:**

4.1. Replace each upstream shape link with a link to the corresponding Kotlin source file in this repo:

| Shape | Old link | New link |
|-------|----------|----------|
| Cylinder | `.../lib/src/main/java/.../Cylinder.java` | `isometric-core/src/main/kotlin/.../shapes/Cylinder.kt` |
| Knot | `.../lib/src/main/java/.../Knot.java` | `isometric-core/src/main/kotlin/.../shapes/Knot.kt` |
| Octahedron | `.../lib/src/main/java/.../Octahedron.java` | `isometric-core/src/main/kotlin/.../shapes/Octahedron.kt` |
| Prism | `.../lib/src/main/java/.../Prism.java` | `isometric-core/src/main/kotlin/.../shapes/Prism.kt` |
| Pyramid | `.../lib/src/main/java/.../Pyramid.java` | `isometric-core/src/main/kotlin/.../shapes/Pyramid.kt` |
| Stairs | `.../lib/src/main/java/.../Stairs.java` | `isometric-core/src/main/kotlin/.../shapes/Stairs.kt` |

4.2. Verify the Kotlin source paths actually exist in this repo before linking.

**Note:** In the rewritten README (Step 5), the shape catalog will be simplified to a bullet list linking to a future "Shape Catalog" docs page. These per-shape links become less prominent but should still be correct.

---

### Step 5: Rewrite the README

**Why:** The current README is 487 lines mixing marketing, tutorial, reference, API comparison, and shape gallery. A new user cannot find the entry point.

**Target:** ~80-100 lines. Focused on: what → why → install → quickstart → links → credits → license.

**Proposed structure:**

```markdown
# Isometric

Declarative isometric rendering for Jetpack Compose.

[badges]

[single hero image — the complex scene snapshot]

## What is Isometric?

[2-3 sentences: what it does, who it's for]

## Features

[bullet list, 6-8 items, one line each — no code, no tables]

## Quick Start

### Installation

[Gradle Kotlin DSL snippet with correct/placeholder coordinates]
[One-line note: "For Groovy DSL and version catalog setup, see the Installation Guide."]

### Your First Scene

[Minimal IsometricScene code block — 7 lines max]
[1-2 sentences explaining what it does]
[Link: "See the Getting Started guide for next steps."]

## Documentation

[Bulleted links to docs pages — these will point to docs/*.md until the
 Astro site exists in Phase 3, then updated to site URLs]
- Getting Started (→ docs/RUNTIME_API.md for now)
- Performance Guide (→ docs/PERFORMANCE_OPTIMIZATIONS.md)
- Primitive Levels (→ docs/PRIMITIVE_LEVELS.md)
- Migration Guide (→ docs/MIGRATION.md)

## Requirements

[Short table: min SDK, Kotlin version, Compose version, JVM target]

## Available Shapes

[Single line listing: Prism, Pyramid, Cylinder, Octahedron, Stairs, Knot]
[6 small inline images in a row, or a single composite image]
[Link: "See the Shape Catalog for details and parameters."]

## Credits

[Attribution to original author + contributors]

## License

[One line: Apache 2.0 + link to LICENSE file]
```

**Detailed content for each section:**

5.1. **Title + tagline:**
```markdown
# Isometric

Declarative isometric rendering for Jetpack Compose.
```

5.2. **Badges** (using shields.io):
```markdown
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple.svg)](https://kotlinlang.org)
[![API](https://img.shields.io/badge/API-24%2B-green.svg)](https://developer.android.com/about/versions/nougat)
```
Do NOT add a Maven Central badge until published. Do NOT add a build status badge until CI exists.

5.3. **Hero image:**
```markdown
![Isometric scene with prisms, stairs, pyramids, and octahedron](docs/assets/screenshots/complex-scene.png)
```

5.4. **What is Isometric?**
```markdown
Isometric is a Kotlin library for rendering interactive isometric (2.5D) scenes
in Jetpack Compose. Build scenes declaratively with `Shape`, `Group`, and `Path`
composables. Transforms accumulate through the hierarchy, animations recompose
only changed nodes, and built-in gesture handling supports tap and drag
interactions with spatial hit testing.
```

5.5. **Features:**
```markdown
- Declarative scene graph — `Shape`, `Group`, `Path`, `Batch`, `If`, `ForEach` composables
- Hierarchical transforms — position, rotation, and scale accumulate through groups
- Per-node dirty tracking — only changed subtrees re-render
- Built-in animation — vsync-aligned via `withFrameNanos`
- Gesture handling — tap and drag with spatial-indexed hit testing
- Camera control — pan and zoom with `CameraState`
- 6 built-in shapes — Prism, Pyramid, Cylinder, Octahedron, Stairs, Knot
- Custom shapes — extrude paths or implement `CustomNode` for full control
```

5.6. **Installation:**
```markdown
> **Note:** This library is not yet published to Maven Central.
> To use it now, clone the repository and include the modules as local dependencies.

```kotlin
// settings.gradle.kts
includeBuild("path/to/Isometric") {
    dependencySubstitution {
        substitute(module("io.github.jayteealao:isometric-core")).using(project(":isometric-core"))
        substitute(module("io.github.jayteealao:isometric-compose")).using(project(":isometric-compose"))
    }
}
```

Or, once published:

```kotlin
dependencies {
    implementation("io.github.jayteealao:isometric-compose:<version>")
}
```
```

**Note:** Adjust the coordinates and `includeBuild` instructions based on Decision #2. The above shows option (b) — local build instructions with future placeholder.

5.7. **Quick Start code:**
```kotlin
@Composable
fun MyIsometricScene() {
    IsometricScene {
        Shape(
            geometry = Prism(position = Point(0.0, 0.0, 0.0)),
            color = IsoColor(33, 150, 243)
        )
    }
}
```

5.8. **Requirements table:**

| Requirement | Version |
|-------------|---------|
| Android min SDK | 24 |
| Kotlin | 1.9+ |
| Jetpack Compose | 1.5+ |
| JVM target | 11 |

5.9. **Available Shapes** — simplified to a visual strip:
```markdown
| Prism | Pyramid | Cylinder | Octahedron | Stairs | Knot |
|:-----:|:-------:|:--------:|:----------:|:------:|:----:|
| ![](docs/assets/screenshots/shape-prism.png) | ![](docs/assets/screenshots/shape-pyramid.png) | ... |
```

Or if images are too large for a table, use a simple list with one hero image:
```markdown
Prism · Pyramid · Cylinder · Octahedron · Stairs · Knot

See the [Shape Catalog](docs/RUNTIME_API.md#shapes) for parameters and examples.
```

5.10. **Credits:**
```markdown
Originally created by [Fabian Terhorst](https://github.com/FabianTerhorst).
Rewritten in Kotlin with Compose Runtime API by [jayteealao](https://github.com/jayteealao).
```

5.11. **License:**
```markdown
[Apache License 2.0](LICENSE)
```

---

### Step 6: Create Placeholder CHANGELOG.md

**Why:** No changelog exists. Users and contributors need to know what changed.

**Content:**

```markdown
# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Jetpack Compose Runtime API (`IsometricScene`) with custom `Applier` and node tree
- Hierarchical transforms via `Group` composable
- Per-node dirty tracking and prepared-scene caching
- Spatial-indexed hit testing
- Tap and drag gesture handling (`GestureConfig`)
- Camera pan/zoom (`CameraState`)
- Native Android Canvas rendering path
- `CustomNode` escape hatch for custom geometry
- `Batch` composable for efficient multi-shape rendering
- `If` and `ForEach` conditional rendering composables
- `AdvancedSceneConfig` for power-user configuration
- Benchmark harness with frame-level metrics
- Paparazzi snapshot tests
- Maestro E2E test flows

### Changed
- Migrated all source from Java to Kotlin
- Modularized into `isometric-core`, `isometric-compose`, `isometric-android-view`
- `Color` renamed to `IsoColor` to avoid Compose namespace collision
- Core module is now pure Kotlin/JVM (no Android dependency)
- `Point`, `Vector`, `Path`, `Shape` support Kotlin operator overloads

### Removed
- `IsometricCanvas` high-level API (superseded by `IsometricScene`)
```

---

### Step 7: Create Placeholder CONTRIBUTING.md

**Why:** Contributors need to know how to build, test, and submit changes.

**Content:**

```markdown
# Contributing to Isometric

Thank you for your interest in contributing!

## Development Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/jayteealao/Isometric.git
   cd Isometric
   ```

2. Open in Android Studio (Hedgehog or newer recommended).

3. Sync Gradle and ensure the project builds:
   ```bash
   ./gradlew build
   ```

## Running Tests

### Unit tests
```bash
./gradlew :isometric-core:test
./gradlew :isometric-compose:testDebugUnitTest
```

### Paparazzi snapshot tests
```bash
./gradlew :isometric-compose:recordPaparazziDebug   # Generate/update golden images
./gradlew :isometric-compose:verifyPaparazziDebug    # Verify against golden images
```

### Sample app
```bash
./gradlew :app:installDebug
```

## Submitting Changes

1. Create a branch from `master` (or the current development branch).
2. Make your changes.
3. Ensure all tests pass.
4. Submit a pull request with a clear description of what changed and why.

## Code Style

- Follow standard Kotlin conventions.
- Add KDoc to all new public API.
- Keep commits focused — one logical change per commit.

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE).
```

---

### Step 8: Clean Up Dead Artifacts

**Why:** The repo contains dead files that confuse contributors.

**Actions:**

8.1. **`lib/` module** — contains only `build/` output, no source code. This is a dead legacy artifact from the upstream repo.
- Delete the `lib/build/` directory contents (these are stale build outputs).
- Note: `lib/` itself may need to stay if `settings.gradle` doesn't include it (it doesn't — confirmed). If the directory is empty after removing `build/`, delete it entirely.

8.2. **`upload.sh`** — contains `./gradlew clean :lib:bintrayUpload`. Bintray is defunct. Delete this file.

8.3. **`local.properties`** — should be in `.gitignore` (it typically contains local SDK paths). Check if it's tracked; if so, add to `.gitignore` and remove from tracking.

**Verification:** Run `./gradlew projects` to confirm no build references to `lib/`.

---

## Execution Order

The steps have dependencies. Execute in this order:

```
Step 1: Generate screenshots
    ↓ (screenshots needed for Step 5)
Step 2: Remove IsometricCanvas references
    ↓ (clean content needed for Step 5)
Step 3: Reorganize docs/ folder
    ↓ (correct paths needed for Step 5 links)
Step 4: Fix shape links
    ↓ (correct links needed for Step 5)
Step 5: Rewrite README
    ↓ (README done)
Step 6: Create CHANGELOG.md        ← independent, can parallel with 5
Step 7: Create CONTRIBUTING.md     ← independent, can parallel with 5
Step 8: Clean up dead artifacts    ← independent, can parallel with 5
```

Steps 6, 7, 8 are independent of each other and of Step 5. They can be done in parallel or in any order.

---

## Files Changed (Summary)

| Action | File | Notes |
|--------|------|-------|
| **Create** | `docs/assets/screenshots/*.png` (15 files) | Paparazzi outputs |
| **Create** | `CHANGELOG.md` | Initial changelog |
| **Create** | `CONTRIBUTING.md` | Contributor guide |
| **Create** | `docs/internal/` directory | For internal docs |
| **Rewrite** | `README.md` | 487 lines → ~90 lines |
| **Edit** | `docs/RUNTIME_API.md` | Remove IsometricCanvas comparisons |
| **Edit** | `docs/MIGRATION.md` | Update API name references |
| **Move** | `docs/plans/` → `docs/internal/plans/` | 19 files |
| **Move** | `docs/research/` → `docs/internal/research/` | 4 files |
| **Move** | `docs/reviews/` → `docs/internal/reviews/` | 18 files |
| **Move** | `docs/api-design-guideline.md` → `docs/internal/` | 1 file |
| **Move** | `docs/guides/PUBLISHING_GUIDE.md` → `docs/internal/` | 1 file |
| **Delete** | `docs/guides/` | Empty after move |
| **Delete** | `upload.sh` | Dead Bintray script |
| **Delete** | `lib/` (or `lib/build/`) | Dead legacy module |
| **Edit** | `.gitignore` | Add `local.properties` if not already present |

**Total:** ~15 creates, 3 edits, ~43 moves, 3 deletes, 1 rewrite

---

## Verification Checklist

After completing all steps, verify:

- [ ] `README.md` renders correctly on GitHub (images load, links work, badges display)
- [ ] No user-facing document references `IsometricCanvas` as a working API
- [ ] All image links in README point to local `docs/assets/screenshots/` files
- [ ] All images render correctly (not broken)
- [ ] No links point to upstream repo (`FabianTerhorst/Isometric`) except in Credits section
- [ ] `docs/` root contains only user-facing docs + `assets/` + `internal/`
- [ ] `docs/internal/` contains all planning/review/research docs
- [ ] `CHANGELOG.md` exists at repo root
- [ ] `CONTRIBUTING.md` exists at repo root
- [ ] `upload.sh` is deleted
- [ ] `lib/` directory is deleted or empty
- [ ] `./gradlew build` still passes (no broken module references)
- [ ] README is under 100 lines
- [ ] README quickstart example uses `IsometricScene` (not `IsometricCanvas`)
- [ ] README installation section accurately reflects current state (not published)

---

## Commit Strategy

**Option A (recommended): Single commit**
```
docs: rewrite README, remove IsometricCanvas refs, reorganize docs structure

- Rewrite README as focused entry point (~90 lines)
- Remove all references to unimplemented IsometricCanvas API
- Move internal planning/review/research docs to docs/internal/
- Add Paparazzi screenshots as local images
- Fix broken upstream image and source links
- Add CHANGELOG.md and CONTRIBUTING.md
- Remove dead artifacts (upload.sh, lib/)
```

**Option B: Multiple commits** (if the maintainer prefers granular history)

1. `chore: generate and commit Paparazzi screenshot images`
2. `docs: remove IsometricCanvas references from user-facing docs`
3. `docs: reorganize docs/ to separate internal from user-facing content`
4. `docs: rewrite README as focused entry point`
5. `docs: add CHANGELOG.md and CONTRIBUTING.md`
6. `chore: remove dead artifacts (upload.sh, lib/)`

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Paparazzi tests fail to run | Medium | Blocks Step 1 | Use JDK 17; if still failing, temporarily keep upstream image links and file issue |
| `docs/internal/` move breaks internal doc cross-references | High | Low (internal only) | Accept broken internal links; fix opportunistically |
| Users on forks/PRs have stale README cached | Low | Low | Normal Git behavior; no action needed |
| README is too short — users want more content inline | Low | Medium | The links section is the escape valve; users click through to detailed docs |
| Maven coordinates placeholder confuses users | Medium | Medium | Use clear "Not yet published" callout box with local-build workaround |
| Paparazzi snapshot images are too large for Git | Low | Low | PNGs from Paparazzi are typically 50-200KB each; ~2MB total is fine |

---

## Success Criteria

| Criterion | Measurement |
|-----------|-------------|
| README is focused | Under 100 lines (currently 487) |
| No misleading API references | Zero mentions of `IsometricCanvas` as a working API in user-facing docs |
| All images load | Zero broken image links in README when viewed on GitHub |
| Clear next step | README contains exactly one quickstart example and links to detailed docs |
| Internal docs separated | `docs/` root has ≤ 10 files/directories visible |
| Dead artifacts removed | `upload.sh` and `lib/` no longer exist |
| Build still works | `./gradlew build` passes |
