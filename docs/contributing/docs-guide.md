---
title: Documentation Guide
description: How to write and preview documentation
sidebar:
  order: 3
---

## Site Architecture

The docs site is built with:

- **[Astro](https://astro.build/)** -- static site generator
- **[Starlight](https://starlight.astro.build/)** -- documentation theme for Astro
- **MDX** -- Markdown with JSX components for interactive examples
- **[Pagefind](https://pagefind.app/)** -- client-side search, auto-indexed at build time

All documentation source files live under `site/src/content/docs/`. The sidebar structure and site metadata are configured in `site/astro.config.mjs`.

## Running Locally

```bash
cd site
npm install
npm run dev
```

This starts a dev server at `http://localhost:4321/Isometric/` with hot-reload. Changes to `.mdx` files are reflected immediately.

> **Note**
>
The **API Reference** sidebar link (`/api/`) requires the Dokka output to be generated first. In CI this happens automatically, but locally you need to run it once:

```bash
# from the repo root
./gradlew :dokkaGeneratePublicationHtml
cp -r build/dokka/html site/public/api
```

`site/public/api/` is gitignored — re-run these commands whenever the Kotlin source changes and you need an up-to-date API reference locally.

To build the production site:

```bash
npm run build
```

The output goes to `site/dist/`.

## Creating a New Page

### 1. Create the MDX File

Add a new `.mdx` file in the appropriate subdirectory:

```
site/src/content/docs/
  getting-started/    # onboarding and setup
  guides/             # conceptual how-to guides
  reference/          # API reference pages
  examples/           # copy-paste recipes
  contributing/       # contributor docs
  migration/          # migration guides
```

### 2. Add Frontmatter

Every page must start with YAML frontmatter:

```yaml
---
title: Page Title
description: One-line summary for search engines and link previews
sidebar:
  order: 3
---
```

The `sidebar.order` controls the position within its section. Lower numbers appear first.

### 3. Register in the Sidebar

Open `site/astro.config.mjs` and add your page to the appropriate section:

```js
{
  label: 'Guides',
  items: [
    { label: 'Shapes', slug: 'guides/shapes' },
    { label: 'Your New Page', slug: 'guides/your-new-page' }, // add here
  ],
},
```

The `slug` matches the file path relative to `site/src/content/docs/`, without the `.mdx` extension.

## Writing Content with MDX

### Starlight Components

Starlight provides several built-in components. Import them at the top of your MDX file:

```mdx
```

<Tabs>
<TabItem label="Tabs">
```mdx
<Tabs>
<TabItem label="Kotlin">
\`\`\`kotlin
val color = IsoColor(33, 150, 243)
\`\`\`
</TabItem>
<TabItem label="Java">
\`\`\`java
IsoColor color = new IsoColor(33, 150, 243);
\`\`\`
</TabItem>
</Tabs>
```
</TabItem>
<TabItem label="Admonitions">
```mdx
> **Note**
>
Informational note.

> **Tip**
>
Helpful suggestion.

> **Caution**
>
Proceed with care.

> **Danger**
>
Critical warning.

```
</TabItem>
</Tabs>

### Code Blocks

Use triple-backtick fenced code blocks with language identifiers:

````mdx
```kotlin
@Composable
fun MyScene() {
    IsometricScene {
        Shape(geometry = Prism(Point.ORIGIN))
    }
}
```
````

For inline code, use single backticks: `` `IsoColor` ``.

### Tables

Use standard Markdown tables for API references:

```mdx
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `angle` | `Double` | `PI / 6` | Projection angle |
| `scale` | `Double` | `70.0` | Pixels per unit |
```

### Links

Link to other docs pages using root-relative paths:

```mdx
See the [Composables Reference](../reference/composables.md) for details.
```

## Screenshot Workflow

Documentation screenshots are generated from code, not captured manually:

1. Add a rendering method to `DocScreenshotGenerator` in the core test source set.
2. Run the generator:
   ```bash
   ./gradlew :isometric-core:test --tests "*.DocScreenshotGenerator"
   ```
3. Copy output to the site:
   ```bash
   cp docs/assets/screenshots/*.png site/public/screenshots/
   ```
4. Reference in MDX:
   ```mdx
   ![Scene description](../screenshots/my-scene.png.md)
   ```

## KDoc Conventions

All public symbols in `isometric-core` and `isometric-compose` should have KDoc documentation:

- **Classes**: Class-level doc explaining purpose and usage.
- **Parameters**: `@param` tag for every constructor/function parameter.
- **Cross-references**: `@see` tags linking related types (e.g., `@see Shape` on `Path`).
- **Code examples**: Use triple-backtick blocks inside KDoc for inline examples.

Example:

```kotlin
/**
 * A rectangular prism (box) shape composed of six quad faces.
 *
 * @param position The minimum-corner origin point
 * @param width Extent along the x-axis (must be positive)
 * @param depth Extent along the y-axis (must be positive)
 * @param height Extent along the z-axis (must be positive)
 * @see Shape
 * @see Pyramid
 */
class Prism(...)
```

## CI Checks

Every PR runs three automated checks defined in `.github/workflows/ci.yml`:

### Markdown lint

All `.md` and `.mdx` files are linted with [markdownlint](https://github.com/DavidAnson/markdownlint). Rules are configured in `.markdownlint.json`. To run locally:

```bash
npx markdownlint-cli2 "**/*.md" "site/src/content/docs/**/*.mdx"
```

Common failures and fixes:

| Rule | Failure | Fix |
|------|---------|-----|
| MD022 | Heading not surrounded by blank lines | Add a blank line before and after every heading |
| MD031 | Code fence not surrounded by blank lines | Add a blank line before and after every ` ``` ` block |
| MD032 | List not surrounded by blank lines | Add a blank line before the first list item |

### External link check

[lychee](https://lychee.cli.rs/) checks all HTTP/HTTPS links in source files for 404s. Internal root-relative links (`/guides/...`) are excluded — they're validated at build time by Astro.

### Commit message lint

PRs enforce [Conventional Commits](https://www.conventionalcommits.org/) via [commitlint](https://commitlint.js.org/). Every commit on a PR branch must start with a type prefix:

```
feat: add cylinder extrusion support
fix: correct depth sorting for overlapping prisms
docs: update quickstart with camera example
refactor: extract hit-test resolver into separate class
test: add snapshot test for Stairs geometry
build: upgrade Dokka to 2.2.0
ci: cache Gradle wrapper in docs workflow
chore: update copyright year
```

The allowed types are: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `revert`.

## Changelog

The `CHANGELOG.md` is generated from commit history using [git-cliff](https://git-cliff.org/). The configuration is in `cliff.toml`. To preview what the next release changelog entry would look like:

```bash
git-cliff --unreleased
```

Do not edit `CHANGELOG.md` manually — it is regenerated on release.

## Style Guide for Code Examples

1. **Self-contained.** Every code block should be copy-pasteable. Include all necessary imports conceptually (the reader should know what to import) but avoid long import lists in the example itself.
2. **Minimal.** Show the concept being demonstrated and nothing else. Omit unrelated parameters that use defaults.
3. **Realistic.** Use concrete color values (`IsoColor(33, 150, 243)`) rather than abstract placeholders. Use reasonable dimensions.
4. **Consistent naming.** Use `Point.ORIGIN` not `Point(0.0, 0.0, 0.0)` when the origin is meant. Use `Prism(Point.ORIGIN)` for a default unit cube.
5. **Annotate composables.** Always include `@Composable` on function signatures.
6. **Prefer named arguments.** Use `Prism(position = Point(1.0, 0.0, 0.0), width = 2.0)` over positional arguments for clarity.
