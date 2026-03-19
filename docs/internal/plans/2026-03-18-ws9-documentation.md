# WS9 Documentation Plan — 2026-03-18

Covers all documentation gaps introduced by WS9 (TileGrid, Stack, TileCoordinate, StackAxis,
TileGridConfig, TileCoordinateExtensions, TileGestureHub).

---

## Structural note — two file trees

The repo maintains two parallel doc trees:

| Tree | Path | Format | Purpose |
|---|---|---|---|
| Site source | `site/src/content/docs/` | `.mdx` (Starlight) | Canonical — what the deployed site renders |
| Markdown mirrors | `docs/` | `.md` | Local tooling references, GitHub-readable |

Every change has a counterpart in both trees. Admonition syntax differs:

| Tree | Caution block | Tip block |
|---|---|---|
| `.mdx` | `:::caution … :::` | `:::tip … :::` |
| `.md` | `> **Caution** …` | `> **Tip** …` |

Steps below target the `.mdx` file; the `.md` mirror receives the same content with admonition syntax substituted.

---

## Step 1 — Fix pre-existing bug: wrong package in `Path` import alias

**Files:**
- `site/src/content/docs/reference/composables.mdx` (in the `:::caution` block under `### Path (composable)`)
- `docs/reference/composables.md` (line 65)

**Current:**
```kotlin
import io.fabianterhorst.isometric.Path as IsoPath
```

**Fix:**
```kotlin
import io.github.jayteealao.isometric.Path as IsoPath
```

---

## Step 2 — Fix `TileGridConfig.kt` KDoc: document elevation exclusion from `equals`/`hashCode`

**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/TileGridConfig.kt`

The reason `elevation` is excluded from `equals`/`hashCode` (lambda identity instability across
recompositions) is documented only in an internal code comment. Users reading generated KDoc will
find two configs with different elevation functions comparing equal with no explanation.

**Location:** Class-level KDoc, after the `@param elevation` line.

**Current:**
```kotlin
 * @param elevation Optional per-tile elevation function. Returns the z-coordinate of the
 *   tile surface for the given [TileCoordinate]. When null, all tiles sit at
 *   [originOffset].z. Use for terrain height maps.
 */
```

**Replacement:**
```kotlin
 * @param elevation Optional per-tile elevation function. Returns the z-coordinate of the
 *   tile surface for the given [TileCoordinate]. When null, all tiles sit at
 *   [originOffset].z. Use for terrain height maps.
 *
 * **Note:** [elevation] is excluded from [equals] and [hashCode]. Two configs with the
 * same [tileSize] and [originOffset] compare equal regardless of their elevation functions.
 * This is intentional — lambda literals do not have stable identity across recompositions,
 * so including them would cause spurious inequality and unnecessary gesture-hub
 * re-registration on every recomposition.
 */
```

---

## Step 3 — Create `site/src/content/docs/guides/tile-grid.mdx` (new file)

**Estimated size:** ~190 lines.

### Frontmatter

```yaml
---
title: Tile Grid
description: Render and interact with an isometric tile grid using TileGrid
sidebar:
  order: 4
---
```

### Section outline

#### Intro paragraph (no heading)

Introduce `TileGrid` as the composable for a W×H isometric tile grid with built-in tap routing.
State what it does (renders tiles, converts taps to `TileCoordinate`) and what it does not do
(manage tile state or game logic).

#### `## Basic Usage`

Minimal 10×10 interactive tile selector. Emphasise that no `GestureConfig` is needed on
`IsometricScene` — tap routing activates automatically when `onTileClick` is provided. Note
that the content lambda's `Point.ORIGIN` is the tile origin.

```kotlin
@Composable
fun TileGridExample() {
    var selectedTile by remember { mutableStateOf<TileCoordinate?>(null) }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        TileGrid(
            width = 10,
            height = 10,
            onTileClick = { coord -> selectedTile = coord }
        ) { coord ->
            Shape(
                geometry = Prism(Point.ORIGIN),
                color = if (coord == selectedTile) IsoColor(33, 150, 243) else IsoColor(200, 200, 200)
            )
        }
    }
}
```

#### `## TileCoordinate`

Explain `TileCoordinate` as the discrete (x: Int, y: Int) cell identity, contrasting with
continuous `Point` (3D) and `Point2D` (2D screen). Cover:

- Constructor: `TileCoordinate(x: Int, y: Int)`
- `plus`/`minus` operators for neighbor lookup
- `isWithin(width, height)` — zero-based range check
- `toPoint(tileSize, elevation)` — cell origin as a world `Point`
- `ORIGIN` constant = `TileCoordinate(0, 0)`
- Negative coordinates are valid
- Safe as `Map`/`Set` key (`equals`/`hashCode` implemented)

```kotlin
val coord = TileCoordinate(3, 4)
val right  = coord + TileCoordinate(1, 0)    // TileCoordinate(4, 4)
val above  = coord - TileCoordinate(0, 1)    // TileCoordinate(3, 3)
val inBounds = coord.isWithin(width = 10, height = 10)  // true
```

#### `## TileGridConfig`

Full parameter table:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `tileSize` | `Double` | `1.0` | World units per tile side. Must be positive and finite. |
| `originOffset` | `Point` | `Point.ORIGIN` | World position of the grid's (0, 0) corner. |
| `elevation` | `((TileCoordinate) -> Double)?` | `null` | Per-tile z-coordinate. Null = flat at `originOffset.z`. |

Explain that the default `TileGridConfig()` is a unit-size flat grid at world origin — callers
only need a config when customising these values.

Terrain height-map example:
```kotlin
val heightMap = mapOf(
    TileCoordinate(2, 2) to 1.0,
    TileCoordinate(2, 3) to 1.0,
    TileCoordinate(3, 3) to 2.0
)

TileGrid(
    width = 6,
    height = 6,
    config = TileGridConfig(
        tileSize = 1.0,
        elevation = { coord -> heightMap[coord] ?: 0.0 }
    )
) { coord ->
    val h = heightMap[coord] ?: 0.0
    Shape(geometry = Prism(Point.ORIGIN, height = 1.0 + h), color = IsoColor(100, 160, 80))
}
```

Caution block (:::caution in .mdx / > **Caution** in .md):
> `onTileClick` uses a flat z = 0 plane for hit-testing. Elevated terrain requires the
> manual escape hatch — see Tap Accuracy with Elevation below.

#### `## Tap Accuracy with Elevation`

Explain the limitation: `onTileClick` always inverse-projects to the z = 0 plane. Show the
escape hatch using `GestureConfig.onTap` + `LocalIsometricEngine.current` + `screenToTile()`
with the surface elevation. Omit `onTileClick` in this pattern.

```kotlin
@Composable
fun ElevatedTileScene(heightMap: Map<TileCoordinate, Double>) {
    val engine = LocalIsometricEngine.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    IsometricScene(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it },
        config = SceneConfig(
            gestures = GestureConfig(
                onTap = { event ->
                    val coord = engine.screenToTile(
                        screenX = event.x,
                        screenY = event.y,
                        viewportWidth = viewportSize.width,
                        viewportHeight = viewportSize.height,
                        elevation = 0.0   // adjust to known surface height if needed
                    )
                    if (coord.isWithin(10, 10)) { /* handle */ }
                }
            )
        )
    ) {
        TileGrid(
            width = 10,
            height = 10,
            config = TileGridConfig(elevation = { heightMap[it] ?: 0.0 })
        ) { coord ->
            Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(100, 160, 80))
        }
    }
}
```

Note: `screenToTile` is defined in `TileCoordinateExtensions`. Obtain the engine via
`LocalIsometricEngine.current`. See [Engine reference — screenToTile](/reference/engine#tile-coordinate-helpers).

#### `## Positioning the Grid`

`originOffset` shifts where (0, 0) appears in world space. To center an N×N grid at world origin:

```kotlin
TileGrid(
    width = 10,
    height = 10,
    config = TileGridConfig(originOffset = Point(-5.0, -5.0, 0.0))
) { coord ->
    Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(200, 200, 200))
}
```

#### `## See Also`

- [Stack guide](/guides/stack)
- [Gestures guide — Tile Grid Tap Routing](/guides/gestures#tile-grid-tap-routing)
- [Composables reference — TileGrid](/reference/composables#tilegrid)
- [Engine reference — screenToTile](/reference/engine#tile-coordinate-helpers)
- [Coordinate System — Tile Coordinates](/getting-started/coordinate-system#tile-coordinates)

---

## Step 4 — Create `site/src/content/docs/guides/stack.mdx` (new file)

**Estimated size:** ~145 lines.

### Frontmatter

```yaml
---
title: Stack
description: Arrange shapes in a line along a world axis with the Stack composable
sidebar:
  order: 5
---
```

### Section outline

#### Intro paragraph (no heading)

`Stack` places `count` children at equal `gap` spacing along a chosen world axis. It replaces
the manual pattern of a `ForEach` with an index multiplied by a step size.

#### `## Basic Usage`

Five-floor building — the canonical hero scenario.

```kotlin
@Composable
fun BuildingScene() {
    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Stack(count = 5, axis = StackAxis.Z, gap = 1.0) { floor ->
            Shape(
                geometry = Prism(Point.ORIGIN),
                color = IsoColor(33, 150, floor * 40)
            )
        }
    }
}
```

Explain `index` (zero-based). Note the defaults: `axis = StackAxis.Z`, `gap = 1.0`.

#### `## StackAxis`

| Value | Screen direction | Unit point |
|-------|-----------------|------------|
| `StackAxis.Z` | Vertical (up) | `Point(0, 0, 1)` |
| `StackAxis.X` | Right-and-forward | `Point(1, 0, 0)` |
| `StackAxis.Y` | Left-and-forward | `Point(0, 1, 0)` |

Horizontal row example:
```kotlin
Stack(count = 8, axis = StackAxis.X, gap = 1.5) { _ ->
    Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(33, 150, 243))
}
```

#### `## Positioning a Stack`

`Stack` starts at world origin. Wrap in `Group` to move it:

```kotlin
Group(position = Point(2.0, 3.0, 0.0)) {
    Stack(count = 4, axis = StackAxis.Z, gap = 1.0) { floor ->
        Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(255, 160, 0))
    }
}
```

#### `## Negative Gap`

Reverses stacking direction. `gap = -1.0` with `StackAxis.Z` stacks downward from origin.
Useful for stalactites or hanging objects.

```kotlin
Stack(count = 3, axis = StackAxis.Z, gap = -1.0) { i ->
    Shape(geometry = Pyramid(Point.ORIGIN), color = IsoColor(150, 100, 200))
}
```

#### `## Nested Stacks`

Two `Stack` composables nest to form a 2D arrangement. For interactive tile grids with tap
routing, prefer `TileGrid`. Nested stacks suit fixed 2D layouts without tap handling.

```kotlin
// 3×3 arrangement of pillars
Stack(count = 3, axis = StackAxis.X, gap = 2.0) { _ ->
    Stack(count = 3, axis = StackAxis.Y, gap = 2.0) { _ ->
        Shape(geometry = Prism(Point.ORIGIN, height = 3.0), color = IsoColor(180, 180, 180))
    }
}
```

#### `## Stack inside TileGrid`

`Stack` composes naturally inside `TileGrid` content blocks, allowing towers of
varying height per tile:

```kotlin
TileGrid(width = 5, height = 5) { coord ->
    val floors = coord.x + coord.y + 1
    Stack(count = floors, axis = StackAxis.Z, gap = 1.0) { _ ->
        Shape(geometry = Prism(Point.ORIGIN, height = 0.8), color = IsoColor(0, 188, 212))
    }
}
```

#### `## Variable Spacing`

For non-uniform spacing (items of varying size stacked flush), use `ForEach` with a running
offset accumulator and explicit `Group` positioning. `Stack` only supports uniform gaps.

#### `## See Also`

- [Tile Grid guide](/guides/tile-grid) — uniform interactive tile grids with tap routing
- [Shapes guide](/guides/shapes) — built-in shape types
- [Composables reference — Stack](/reference/composables#stack)
- [Composables reference — Group](/reference/composables#group)

---

## Step 5 — Update `site/src/content/docs/reference/composables.mdx`: add TileGrid and Stack

**Location:** Append after the `### CustomNode` section (end of file).

### Content to append

````mdx

### TileGrid

`IsometricScope` extension composable. Renders a width × height isometric tile grid and routes
tap events to tile coordinates. No `GestureConfig` is required on the enclosing `IsometricScene`
when `onTileClick` is provided — gesture handling activates automatically.

| Param | Type | Default | Description |
|---|---|---|---|
| `width` | `Int` | — | Required. Number of tile columns. Must be ≥ 1. |
| `height` | `Int` | — | Required. Number of tile rows. Must be ≥ 1. |
| `config` | `TileGridConfig` | `TileGridConfig()` | Tile size, world origin, optional per-tile elevation. |
| `onTileClick` | `((TileCoordinate) -> Unit)?` | `null` | Called when the user taps a tile within the grid bounds. |
| `content` | `@Composable IsometricScope.(TileCoordinate) -> Unit` | — | Required. Rendered in each tile's local coordinate space. |

```kotlin
TileGrid(
    width = 10,
    height = 10,
    onTileClick = { coord -> selectedTile = coord }
) { coord ->
    Shape(
        geometry = Prism(Point.ORIGIN),
        color = if (coord == selectedTile) IsoColor(33, 150, 243) else IsoColor(200, 200, 200)
    )
}
```

:::caution
`onTileClick` assumes a flat z = 0 ground plane. Elevated terrain requires the escape hatch —
see [Tile Grid guide — Tap Accuracy with Elevation](/guides/tile-grid#tap-accuracy-with-elevation).
:::

### Stack

`IsometricScope` extension composable. Arranges `count` children at equal `gap` spacing along
a world axis.

| Param | Type | Default | Description |
|---|---|---|---|
| `count` | `Int` | — | Required. Number of children. Must be ≥ 1. |
| `axis` | `StackAxis` | `StackAxis.Z` | World axis along which children are arranged. |
| `gap` | `Double` | `1.0` | World-unit distance between consecutive child origins. Must be non-zero and finite. Negative values reverse direction. |
| `content` | `@Composable IsometricScope.(index: Int) -> Unit` | — | Required. Receives zero-based index. |

```kotlin
Stack(count = 5, axis = StackAxis.Z, gap = 1.0) { floor ->
    Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(33, 150, floor * 40))
}
```
````

---

## Step 6 — Update `site/src/content/docs/reference/engine.mdx`: add tile coordinate helpers

**Location:** Insert a new `## Tile Coordinate Helpers` section between `### findItemAt` and
`## projectionVersion`.

### Content to insert

````mdx

## Tile Coordinate Helpers

Extension functions that bridge continuous 3D world coordinates and the discrete tile grid system.

### screenToTile

Extension function on `IsometricEngine`. Converts a screen tap point to the `TileCoordinate`
of the tile cell that contains it. Chains `screenToWorld` with `Point.toTileCoordinate`.

```kotlin
fun IsometricEngine.screenToTile(
    screenX: Double,
    screenY: Double,
    viewportWidth: Int,
    viewportHeight: Int,
    tileSize: Double = 1.0,
    elevation: Double = 0.0,
    originOffset: Point = Point.ORIGIN
): TileCoordinate
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `screenX` | `Double` | — | Screen x-coordinate of the tap (pixels). |
| `screenY` | `Double` | — | Screen y-coordinate of the tap (pixels). |
| `viewportWidth` | `Int` | — | Scene viewport width (pixels). |
| `viewportHeight` | `Int` | — | Scene viewport height (pixels). |
| `tileSize` | `Double` | `1.0` | World units per tile side. Must match `TileGridConfig.tileSize`. |
| `elevation` | `Double` | `0.0` | Z-plane to intersect during inverse projection. Use the surface z of the tile layer. |
| `originOffset` | `Point` | `Point.ORIGIN` | World position of the grid's (0, 0) corner. Must match `TileGridConfig.originOffset`. |

For terrain where elevation varies per tile, use `screenToWorld` directly and call
`Point.toTileCoordinate` after determining the correct layer.

### Point.toTileCoordinate

Extension function on `Point`. Converts a continuous world point to the `TileCoordinate` of
the cell that contains it. Uses `floor()` division so negative coordinates map correctly.

```kotlin
fun Point.toTileCoordinate(
    tileSize: Double = 1.0,
    originOffset: Point = Point.ORIGIN
): TileCoordinate
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `tileSize` | `Double` | `1.0` | World units per tile side. Must be positive and finite. |
| `originOffset` | `Point` | `Point.ORIGIN` | World position of the grid's (0, 0) corner. |

The z-coordinate of the receiver `Point` is ignored — only x and y determine the tile.

```kotlin
Point(3.7, 5.2, 0.0).toTileCoordinate()        // TileCoordinate(3, 5)
Point(-0.3, 0.0, 0.0).toTileCoordinate()        // TileCoordinate(-1, 0)  — floor, not truncation
```
````

---

## Step 7 — Update `site/src/content/docs/getting-started/coordinate-system.mdx`: add tile coordinates

**Location:** Append `## Tile Coordinates` section at the end of the file (after
`## Coordinate Conventions`).

### Content to append

```mdx

## Tile Coordinates

For tile-grid scenarios the library provides a second coordinate type: `TileCoordinate`.
Where `Point` uses continuous `Double` values in 3D world space, `TileCoordinate` uses
discrete `Int` values for grid cells.

```
TileCoordinate(x: Int, y: Int)   — discrete column/row in the tile grid
Point(x, y, z: Double)           — continuous position in 3D world space
```

The relationship is controlled by `TileGridConfig.tileSize` (world units per tile side).
A tile at `TileCoordinate(3, 5)` with `tileSize = 1.0` occupies the world-space rectangle
`[3.0, 4.0) × [5.0, 6.0)` in the XY plane.

`TileCoordinate` axis directions follow the world axes:

- **x** — increases right-and-forward on screen (same as world X).
- **y** — increases left-and-forward on screen (same as world Y).

`TileCoordinate.ORIGIN` is the tile at (0, 0) — the grid's world origin corner.

For more on working with tile grids, see the [Tile Grid guide](/guides/tile-grid).
```

---

## Step 8 — Update `site/src/content/docs/guides/gestures.mdx`: add tile tap routing

**Location:** Append after `## Disabling Gestures` (end of file).

### Content to append

````mdx

## Tile Grid Tap Routing

`TileGrid` provides its own tap routing mechanism separate from `GestureConfig.onTap`. Passing
an `onTileClick` callback to `TileGrid` enables automatic screen-to-tile conversion — no
`GestureConfig` is needed on `IsometricScene`.

```kotlin
IsometricScene(modifier = Modifier.fillMaxSize()) {
    TileGrid(
        width = 10,
        height = 10,
        onTileClick = { coord ->
            // TileCoordinate — no coordinate math required
            selectedTile = coord
        }
    ) { coord ->
        Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(200, 200, 200))
    }
}
```

This differs from `GestureConfig.onTap` in two ways:

- **Delivers a `TileCoordinate`**, not raw screen coordinates or a hit-tested node.
- **Scoped to grid bounds.** Taps outside the grid's `width × height` area are silently ignored.

### Combining with Drag Gestures

Tile tap routing and `GestureConfig` drag callbacks coexist without conflict:

```kotlin
val camera = remember { CameraState() }

IsometricScene(
    modifier = Modifier.fillMaxSize(),
    config = SceneConfig(
        cameraState = camera,
        gestures = GestureConfig(
            onDrag = { event -> camera.pan(event.x / 50.0, -event.y / 50.0) }
        )
    )
) {
    TileGrid(
        width = 10,
        height = 10,
        onTileClick = { coord -> selectedTile = coord }
    ) { coord ->
        Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(200, 200, 200))
    }
}
```

:::caution
Do not use both `GestureConfig.onTap` and `TileGrid`'s `onTileClick` simultaneously — both
receive the same tap event, causing double-handling. Use one or the other.
:::

For elevated terrain where the default z = 0 assumption is incorrect, use `GestureConfig.onTap`
with `IsometricEngine.screenToTile()` directly and omit `onTileClick`. See
[Tile Grid — Tap Accuracy with Elevation](/guides/tile-grid#tap-accuracy-with-elevation).
````

---

## Step 9 — Update `README.md`

### Change 1 — Features list

After `- **Gesture handling** — tap and drag with spatial-indexed hit testing`, insert:

```markdown
- **Tile grid** — `TileGrid` composable for isometric tile maps with automatic tap-to-tile routing
- **Stack layout** — `Stack` composable for 1D arrangement along any world axis (X, Y, or Z)
```

### Change 2 — Documentation links

After `- [**Gestures**](…) — Tap and drag with spatial hit testing`, insert:

```markdown
- [**Tile Grid**](site/src/content/docs/guides/tile-grid.mdx) — Render and interact with isometric tile grids
- [**Stack**](site/src/content/docs/guides/stack.mdx) — Arrange shapes along a world axis
```

---

## Step 10 — Update `docs/contributing/testing.md` and `.mdx`: add WS9 test classes

**Location:** The test class table. Append new rows:

| Test class | Module | Covers |
|------------|--------|--------|
| `TileCoordinateTest` | `isometric-core` (unit) | Construction, `equals`/`hashCode`/`toString`, `plus`/`minus` operators, `isWithin`, `toPoint`, `ORIGIN` |
| `TileGridConfigTest` | `isometric-core` (unit) | Default values, `tileSize` validation (`isFinite` + `> 0`), elevation lambda storage, `equals`/`hashCode` (elevation excluded), `toString` |
| `TileCoordinateExtensionsTest` | `isometric-core` (unit) | `Point.toTileCoordinate` with positive/negative/boundary coords, floor-not-truncation, `screenToTile` round-trip for 10×10 grid |
| `StackAxisTest` | `isometric-core` (unit) | All three enum values, `unitPoint()` unit vectors, one-component-non-zero invariant |
| `TileGridTest` | `isometric-compose` (instrumented) | Per-tile content invocation, bounds enforcement, `onTileClick` wiring, elevation function inputs |
| `StackTest` | `isometric-compose` (instrumented) | Item count, axis directions, gap validation, negative gap, nested stacks |

Add a note below the table:

> Compose module instrumented tests (`TileGridTest`, `StackTest`) require an Android device or
> emulator. Run with `./gradlew :isometric-compose:connectedAndroidTest`.

---

## Step 11 — Register new guides in `site/astro.config.mjs`

**Location:** The `Guides` items array.

**Insert** after `{ label: 'Gestures', slug: 'guides/gestures' }`:

```js
{ label: 'Tile Grid', slug: 'guides/tile-grid' },
{ label: 'Stack', slug: 'guides/stack' },
```

Rationale: Tile Grid and Stack follow Gestures because `TileGrid`'s tap routing extends the
gesture system. Readers finishing the Gestures guide reach Tile Grid naturally.

---

## ~~Step 12~~ — REMOVED: `.md` mirrors are auto-generated

`scripts/sync-docs.js` generates all `docs/**/*.md` mirrors from the `.mdx` source.
The script was fixed as part of this workstream to rewrite absolute doc-site links
(`/guides/tile-grid/`) to relative `.md` paths (`../guides/tile-grid.md`) so they work
when browsing `docs/` on GitHub. Run after all `.mdx` edits are complete:

```bash
node scripts/sync-docs.js
```

---

## Implementation sequence

Execute steps in this order to avoid broken cross-links at any intermediate state:

1. **Step 2** — KDoc fix (no cross-links)
2. **Step 1** — package bug fix (self-contained)
3. **Step 3** — create `tile-grid.mdx` (other files link here)
4. **Step 4** — create `stack.mdx` (other files link here)
5. **Step 5** — composables reference additions
6. **Step 6** — engine reference additions
7. **Step 7** — coordinate-system addition
8. **Step 8** — gestures addition
9. **Step 11** — `astro.config.mjs` sidebar registration
10. **Step 9** — README updates
11. **Step 10** — contributing/testing additions
12. **Run** `node scripts/sync-docs.js` to regenerate all `.md` mirrors

---

## Scope summary

| Deliverable | Type | Est. lines |
|---|---|---|
| `site/src/content/docs/guides/tile-grid.mdx` | New file | ~190 |
| `site/src/content/docs/guides/stack.mdx` | New file | ~145 |
| `site/src/content/docs/reference/composables.mdx` | Append + bug fix | +55 |
| `site/src/content/docs/reference/engine.mdx` | Insert new section | +55 |
| `site/src/content/docs/getting-started/coordinate-system.mdx` | Append section | +20 |
| `docs/getting-started/coordinate-system.md` | Append section | +20 |
| `site/src/content/docs/guides/gestures.mdx` | Append section | +45 |
| `site/src/content/docs/guides/gestures.mdx` | Append section | +45 |
| `README.md` | 4 line additions | +4 |
| `site/src/content/docs/contributing/testing.mdx` | Table rows + note | +20 |
| `site/astro.config.mjs` | 2 sidebar entries | +2 |
| `isometric-core/.../TileGridConfig.kt` | KDoc addition | +8 |
| `scripts/sync-docs.js` | Link-rewriting fix (already committed) | — |
| **Total net new lines** | | **~544** (+mirrors auto-generated by script) |
