# WS9: TileGrid — Isometric Tile Grid System

> **Workstream**: 9 of 9
> **Phase**: 4 (after WS6 — requires `screenToWorld()` public API from WS6 Step 4)
> **Scope**: Add `TileCoordinate` core type, `Point.toTileCoordinate()` and `IsometricEngine.screenToTile()` coordinate math, `TileGridConfig` configuration type, `TileGestureHub` gesture routing infrastructure, `IsometricScene` hub wiring, the `TileGrid` composable, and the `StackAxis` enum + `Stack` composable for 1D layout along any world axis
> **Depends on**: WS6 Step 4 (`screenToWorld()` public API), WS2 (`GestureConfig`, `TapEvent`, `SceneConfig`/`AdvancedSceneConfig`), WS1b (regular class pattern for core types)
> **Coordinate with**: WS2 Step 5 (`IsometricScene` tap dispatch — this workstream adds a second dispatch target in the `PointerEventType.Release` block)
> **Review ref**: `docs/internal/api-design-guideline.md` §1 Hero Scenario, §2 Progressive Disclosure, §4 Name for User Intent, §8 Composition Over God Objects

---

## Motivation

The library provides the full mathematical foundation for isometric tile games: projection, inverse projection, depth sorting, and polygon hit testing. But it leaves users to wire these pieces together manually whenever they want a discrete tile grid. The typical pattern — `ForEach` over a coordinate range, `screenToWorld()` in an `onTap` callback, manual floor and bounds-check — is correct but repetitive and requires understanding internal coordinate math that most users do not need to think about.

`TileGrid` encapsulates this pattern into a first-class composable. It is not a game engine. It does not manage tile state, pathfinding, or rendering strategy. It does one thing: render a grid of tiles in isometric space and tell the caller which tile was tapped.

The three questions that motivated this workstream map directly to its three layers:

- **Orthographic projection** → already in the library; `TileGrid` builds on top of it
- **Tiling** → `TileGrid` composable + `TileCoordinate` type provide the missing grid abstraction
- **Coordinate mapping for selection** → `IsometricEngine.screenToTile()` + `TileGestureHub` close the loop from tap to tile identity

---

## Execution Order

The 7 steps decompose into a clear dependency chain. Each step is self-contained and produces a compilable codebase.

1. **Step 1**: `TileCoordinate` — new core type (`isometric-core`)
2. **Step 2**: `Point.toTileCoordinate()` and `IsometricEngine.screenToTile()` — coordinate math (`isometric-core`)
3. **Step 3**: `TileGridConfig` — configuration type (`isometric-core`)
4. **Step 4**: `TileGestureHub` and `LocalTileGestureHub` — internal gesture routing infrastructure (`isometric-compose`)
5. **Step 5**: `IsometricScene` wiring — hub provision, reactive gesture enablement, tap dispatch to hub
6. **Step 6**: `TileGrid` composable — the public API (`isometric-compose`)
7. **Step 7**: `StackAxis` enum + `Stack` composable — 1D layout along any world axis (`isometric-core` + `isometric-compose`)
8. **Step 8**: Tests and examples

Steps 1–3 are all in `isometric-core` and have no Compose dependency. Steps 1 and 3 are parallelizable (Step 3 uses `TileCoordinate` from Step 1; it does not depend on Step 2). Step 2 depends on Step 1. Step 4 depends on Steps 2 and 3 (the hub uses `TileGridConfig` and calls `engine.screenToTile()`). Step 5 depends on Step 4. Step 6 depends on Steps 3, 4, and 5. **Step 7 (`Stack`) has no dependency on Steps 1–6** — it depends only on `ForEach` and `Group`, which predate WS9. Step 7 can be implemented in parallel with Steps 3–6 once Steps 1–2 are merged. Step 8 depends on Steps 6 and 7.

Parallelization summary:
- Steps 1 and 3 can start immediately in parallel
- Step 2 can start after Step 1
- Step 4 can start after Steps 2 and 3
- Steps 5 and 6 are sequential after Step 4
- **Step 7 (`Stack`) can proceed in parallel with Steps 3–6** once Step 1 (`TileCoordinate`) is merged — `StackAxis` is independent
- Step 8 follows Steps 6 and 7

---

## Step 1: `TileCoordinate` — New Core Type

### Rationale

The library uses `Point(x: Double, y: Double, z: Double)` for continuous 3D world coordinates and `Point2D(x: Double, y: Double)` for 2D screen coordinates. Neither type is suitable for discrete tile grid positions. Tile coordinates are:

- Integer-valued — tiles occupy whole-unit cells
- 2D — there is no z-axis concept at the tile identity level (elevation is a property of a tile, not of its identity)
- Bounded — tile (3, 5) is a different thing from world position (3.0, 5.0, 0.0)

Reusing `Point` with an implicit "only use integer parts" convention would violate guideline §6 (Make Invalid States Hard to Express). `TileCoordinate(x: Int, y: Int)` makes the type system enforce discreteness.

The type follows the regular-class pattern established in WS1b for all core types. `data class` is not used because: (a) the pattern is established, and (b) `TileCoordinate` may grow fields in the future (e.g., a `layer: Int` for multi-level grids) where a `copy()` generated from the current fields would break callers.

### Best Practice

Regular class with explicit `equals`/`hashCode`/`toString`. Operators `+` and `-` for coordinate arithmetic. A `companion object` with `@JvmField ORIGIN`. No `@JvmOverloads` — the constructor has two required parameters with no defaults, so there are no overloads to generate. Validation is deliberately absent: negative coordinates are valid for grids that do not start at origin, and extremely large values are valid for large worlds. The `isWithin()` method provides caller-side bounds checking without baking assumptions about valid ranges into the type.

### Files and Changes

#### 1a. New file: `TileCoordinate.kt`

**Location**: `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/TileCoordinate.kt`

```kotlin
package io.github.jayteealao.isometric

/**
 * Discrete 2D tile grid coordinate.
 *
 * Identifies a single cell in an isometric tile grid by its integer column
 * and row. Distinct from [Point] (continuous 3D world space) and [Point2D]
 * (continuous 2D screen space).
 *
 * Negative coordinates are valid — grids are not required to start at (0, 0).
 *
 * @param x Column index (increases right-and-down on screen)
 * @param y Row index (increases left-and-down on screen)
 */
class TileCoordinate(
    val x: Int,
    val y: Int
) {
    /** Returns the coordinate offset by [other]. */
    operator fun plus(other: TileCoordinate): TileCoordinate = TileCoordinate(x + other.x, y + other.y)

    /** Returns the coordinate offset by the negation of [other]. */
    operator fun minus(other: TileCoordinate): TileCoordinate = TileCoordinate(x - other.x, y - other.y)

    /**
     * Returns true if this coordinate falls within a grid of [width] columns
     * and [height] rows anchored at (0, 0).
     *
     * Callers using a non-zero [TileGridConfig.originOffset] are responsible
     * for shifting the check if needed — `isWithin` always tests against the
     * zero-based range `[0, width)` × `[0, height)`.
     */
    fun isWithin(width: Int, height: Int): Boolean = x in 0 until width && y in 0 until height

    /**
     * Converts this tile coordinate to a continuous world [Point] at the
     * grid's (0,0) corner of this cell.
     *
     * @param tileSize World units per tile side (must match the value used in [TileGridConfig])
     * @param elevation World z-coordinate of the tile surface
     */
    fun toPoint(tileSize: Double = 1.0, elevation: Double = 0.0): Point =
        Point(x * tileSize, y * tileSize, elevation)

    override fun equals(other: Any?): Boolean =
        other is TileCoordinate && x == other.x && y == other.y

    override fun hashCode(): Int = 31 * x + y

    override fun toString(): String = "TileCoordinate($x, $y)"

    companion object {
        /** The tile at grid position (0, 0). */
        @JvmField
        val ORIGIN = TileCoordinate(0, 0)
    }
}
```

**Note on `hashCode()`**: The formula `31 * x + y` is fast and collision-free for small grids (x values up to ~70 million before overflow wraps). For very large grids this is acceptable — tile grids in isometric games are rarely larger than 256×256.

### Verification

- `TileCoordinate(3, 5) == TileCoordinate(3, 5)` is `true`
- `TileCoordinate(3, 5) == TileCoordinate(3, 6)` is `false`
- `TileCoordinate(3, 5) + TileCoordinate(1, 0) == TileCoordinate(4, 5)` is `true`
- `TileCoordinate(3, 5).isWithin(10, 10)` is `true`
- `TileCoordinate(10, 5).isWithin(10, 10)` is `false` (out of bounds on x)
- `TileCoordinate(-1, 0).isWithin(10, 10)` is `false`
- `TileCoordinate(3, 5).toPoint(tileSize = 2.0)` == `Point(6.0, 10.0, 0.0)`
- `TileCoordinate.ORIGIN == TileCoordinate(0, 0)` is `true`
- `TileCoordinate` is usable as a `HashMap` key (hashCode/equals contract holds)

---

## Step 2: `Point.toTileCoordinate()` and `IsometricEngine.screenToTile()` — Coordinate Math

### Rationale

Two coordinate conversions are needed:

1. **World → tile**: Given a continuous world `Point`, which tile cell contains it? This is `floor(world.x / tileSize)` and `floor(world.y / tileSize)`. It is a pure geometric operation with no dependency on the rendering pipeline, so it belongs as an extension on `Point`.

2. **Screen → tile**: Given a screen tap position, which tile was tapped? This chains the existing `screenToWorld()` (WS6 Step 4) with the new `toTileCoordinate()`. It belongs on `IsometricEngine` alongside `screenToWorld()` — they are both coordinate space transformations using the engine's projection parameters.

Decomposing into two functions instead of one monolithic `screenToTile()` follows guideline §9 (Escape Hatches): advanced users who have already called `screenToWorld()` for another purpose should be able to call `toTileCoordinate()` directly without repeating the inverse projection.

### Best Practice

Both functions use `floor()` (not `roundToInt()`, not integer truncation). Floor is the only correct operation for "which tile cell contains this point": points in the range [1.0, 2.0) all belong to tile 1, not tile 2. Truncation mishandles negative coordinates (`-0.3` truncates to `0`, but belongs to tile `-1`).

The `require()` on `tileSize` is placed on `toTileCoordinate()` only. `screenToTile()` delegates to it, so the validation fires either way without duplication.

### Files and Changes

#### 2a. New file: `TileCoordinateExtensions.kt`

**Location**: `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/TileCoordinateExtensions.kt`

```kotlin
package io.github.jayteealao.isometric

import kotlin.math.floor

/**
 * Converts a continuous world [Point] to the [TileCoordinate] of the tile
 * cell that contains it.
 *
 * Uses floor division so that points within [tileSize] of a grid line map
 * to the correct cell, including negative coordinates.
 *
 * @param tileSize World units per tile side (default 1.0, must be positive)
 * @param originOffset World position of the grid's (0,0) corner (default [Point.ORIGIN])
 */
fun Point.toTileCoordinate(
    tileSize: Double = 1.0,
    originOffset: Point = Point.ORIGIN
): TileCoordinate {
    require(tileSize > 0.0) { "tileSize must be positive, got $tileSize" }
    return TileCoordinate(
        x = floor((x - originOffset.x) / tileSize).toInt(),
        y = floor((y - originOffset.y) / tileSize).toInt()
    )
}

/**
 * Returns the [TileCoordinate] of the tile at the given screen position on
 * the specified z-plane.
 *
 * Chains [screenToWorld] with [toTileCoordinate]. The [elevation] parameter
 * specifies which horizontal z-plane to intersect during inverse projection —
 * for flat tile grids this should be 0.0 (the default). For elevated terrain,
 * pass the surface height of the expected tile layer.
 *
 * For complex terrain where elevation varies per tile, use [screenToWorld]
 * directly and call [toTileCoordinate] with the appropriate elevation after
 * determining the layer through other means.
 *
 * @param screenX Screen x-coordinate of the tap (px)
 * @param screenY Screen y-coordinate of the tap (px)
 * @param viewportWidth Width of the scene viewport (px)
 * @param viewportHeight Height of the scene viewport (px)
 * @param tileSize World units per tile side (default 1.0)
 * @param elevation Z-plane to intersect during inverse projection (default 0.0)
 * @param originOffset World position of the grid's (0,0) corner (default [Point.ORIGIN])
 */
fun IsometricEngine.screenToTile(
    screenX: Double,
    screenY: Double,
    viewportWidth: Int,
    viewportHeight: Int,
    tileSize: Double = 1.0,
    elevation: Double = 0.0,
    originOffset: Point = Point.ORIGIN
): TileCoordinate {
    val world = screenToWorld(
        screenPoint = Point2D(screenX, screenY),
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        z = elevation
    )
    return world.toTileCoordinate(tileSize = tileSize, originOffset = originOffset)
}
```

**Why an extension file rather than methods on the classes**: `IsometricEngine.screenToTile()` is an extension rather than a method so that `IsometricEngine` itself does not need to know about `TileCoordinate`. Both live in `isometric-core`, but the extension file keeps the coordinate domain concern separate from the projection engine. If a future `isometric-core` refactor splits these into submodules, the extension can move without touching the engine class.

### Verification

- `Point(3.7, 5.2, 0.0).toTileCoordinate()` == `TileCoordinate(3, 5)`
- `Point(3.0, 5.0, 0.0).toTileCoordinate()` == `TileCoordinate(3, 5)` (on the boundary — belongs to tile (3,5))
- `Point(-0.3, 0.0, 0.0).toTileCoordinate()` == `TileCoordinate(-1, 0)` (floor handles negative correctly)
- `Point(4.0, 6.0, 0.0).toTileCoordinate(tileSize = 2.0)` == `TileCoordinate(2, 3)`
- `Point(3.7, 5.2, 0.0).toTileCoordinate(originOffset = Point(1.0, 1.0, 0.0))` == `TileCoordinate(2, 4)`
- `Point.ORIGIN.toTileCoordinate(tileSize = 0.0)` throws `IllegalArgumentException`
- Full round-trip test: for a 10×10 grid at default engine parameters, tap at each tile's projected screen center, verify `screenToTile` returns that tile's `TileCoordinate`

---

## Step 3: `TileGridConfig` — Configuration Type

### Rationale

`TileGrid` requires three pieces of configuration beyond the grid dimensions: tile size (world units per cell), the grid's world-space origin offset, and an optional per-tile elevation function for terrain. These belong in a dedicated config type rather than as individual parameters on the composable, for three reasons:

1. **Guideline §2 (Progressive Disclosure)**: All three have safe defaults. The simple path is `TileGrid(width = 10, height = 10, onTileClick = { ... }) { ... }` — no config needed. `TileGridConfig` is the Layer 2 (configurable) option.
2. **Guideline §11 (Evolution)**: Future additions — per-tile visibility, hover radius, gap spacing — add to `TileGridConfig` without touching the composable's parameter list.
3. **Module placement**: `TileGridConfig` depends only on `Point` and `TileCoordinate`, both in `isometric-core`. Placing it in core means a future `isometric-android-view` module can reuse the same config type without a Compose dependency.

### Best Practice

`TileGridConfig` is a regular class (not data class) following the WS1b pattern. The `elevation` function is nullable rather than defaulting to `{ 0.0 }` — the null case allows the renderer to skip a per-tile function call for flat grids, which is the common case.

`elevation` is excluded from `equals`/`hashCode` for the same reason gesture callbacks are excluded from `GestureConfig.equals` in WS2: function instances are not stable across recompositions when expressed as lambda literals, so including them causes spurious inequality and unnecessary recomposition cascades. Users who need `equals` to reflect elevation changes should wrap their config in `remember` with appropriate keys.

### Files and Changes

#### 3a. New file: `TileGridConfig.kt`

**Location**: `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/TileGridConfig.kt`

```kotlin
package io.github.jayteealao.isometric

/**
 * Configuration for a [TileGrid] composable.
 *
 * All fields have safe defaults: a 1-unit tile size, grid origin at world
 * origin, and a flat ground plane (no per-tile elevation). Pass a
 * [TileGridConfig] only when customizing these values.
 *
 * @param tileSize World units per tile side. Must be positive and finite. Default 1.0.
 * @param originOffset World position of the grid's (0, 0) corner. Default [Point.ORIGIN].
 * @param elevation Optional per-tile elevation function. Returns the z-coordinate of the
 *   tile surface for the given [TileCoordinate]. When null, all tiles sit at
 *   [originOffset].z. Use for terrain height maps.
 */
class TileGridConfig(
    val tileSize: Double = 1.0,
    val originOffset: Point = Point.ORIGIN,
    val elevation: ((TileCoordinate) -> Double)? = null
) {
    init {
        require(tileSize > 0.0) { "tileSize must be positive, got $tileSize" }
        require(tileSize.isFinite()) { "tileSize must be finite, got $tileSize" }
    }

    // elevation is a function — excluded from equals/hashCode (function identity
    // is unstable across recompositions when expressed as a lambda literal).
    override fun equals(other: Any?): Boolean =
        other is TileGridConfig &&
            tileSize == other.tileSize &&
            originOffset == other.originOffset

    override fun hashCode(): Int {
        var result = tileSize.hashCode()
        result = 31 * result + originOffset.hashCode()
        return result
    }

    override fun toString(): String =
        "TileGridConfig(tileSize=$tileSize, originOffset=$originOffset, " +
            "elevation=${if (elevation != null) "<function>" else "null"})"
}
```

### Verification

- `TileGridConfig()` compiles and has `tileSize = 1.0`, `originOffset = Point.ORIGIN`, `elevation = null`
- `TileGridConfig(tileSize = 2.0)` compiles
- `TileGridConfig(tileSize = 0.0)` throws `IllegalArgumentException`
- `TileGridConfig(tileSize = Double.POSITIVE_INFINITY)` throws `IllegalArgumentException` (finite check fires before positive check on infinity)
- `TileGridConfig(tileSize = 1.0) == TileGridConfig(tileSize = 1.0)` is `true`
- Two configs with different `elevation` lambdas are `equal` (function excluded from equals — by design)

---

## Step 4: `TileGestureHub` and `LocalTileGestureHub` — Gesture Routing Infrastructure

### Rationale

`TileGrid` is a composable *inside* `IsometricScope`, downstream of `IsometricScene`. Tap events fire at the `IsometricScene` level (where the `pointerInput` modifier lives). `TileGrid` needs to receive those events and convert them to tile coordinates.

CompositionLocals flow downward only — a child composable cannot write a value that a parent reads. The correct Compose pattern for "child affects parent behavior" is: **the parent provides a mutable object via CompositionLocal; children mutate it; the parent observes the object's state reactively**.

`TileGestureHub` is that mutable object. `IsometricScene` creates and provides it. `TileGrid` registers its handler into it via `DisposableEffect`. `IsometricScene` dispatches taps to it.

The registration list uses `mutableStateListOf` so that `IsometricScene` can observe `hub.hasHandlers` reactively. When a `TileGrid` registers its first handler, `hasHandlers` flips to `true`, triggering recomposition of `IsometricScene`, which then installs the `pointerInput` modifier — even if the user did not configure `GestureConfig` on the scene.

### Best Practice

`TileGestureHub` is `internal` to the `isometric-compose` module — it is not part of the public API. `LocalTileGestureHub` uses `staticCompositionLocalOf` (consistent with WS7's ruling: configuration that changes rarely, or not at all after initial provision, should use `staticCompositionLocalOf` to avoid the overhead of dynamic slot tracking). The hub instance itself is stable (created once, `remember`ed in `IsometricScene`) — only its contents change.

Both `TileGestureHub` and `LocalTileGestureHub` are placed in the `compose.runtime` package alongside the other internal infrastructure (`HitTestResolver`, `SpatialGrid`, `IsometricApplier`). There is no `compose.internal` subpackage in this module.

### Files and Changes

#### 4a. New file: `TileGestureHub.kt`

**Location**: `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/TileGestureHub.kt`

```kotlin
package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.TileCoordinate
import io.github.jayteealao.isometric.TileGridConfig
import io.github.jayteealao.isometric.screenToTile

/**
 * Internal registration record for a single TileGrid's tap handler.
 *
 * @param config Grid configuration (tile size, origin offset)
 * @param gridWidth Width in tiles — used for bounds-checking after coordinate conversion
 * @param gridHeight Height in tiles — used for bounds-checking after coordinate conversion
 * @param onTileClick Callback invoked when the tap maps to a tile within bounds
 */
internal class TileGestureRegistration(
    val config: TileGridConfig,
    val gridWidth: Int,
    val gridHeight: Int,
    val onTileClick: (TileCoordinate) -> Unit
)

/**
 * Collects tap handlers from TileGrid composables and routes scene-level
 * tap events to the correct handler with coordinate conversion.
 *
 * Created by IsometricScene and provided via [LocalTileGestureHub].
 * TileGrid registers into it using DisposableEffect.
 */
internal class TileGestureHub {

    private val registrations = mutableStateListOf<TileGestureRegistration>()

    /**
     * True when at least one TileGrid has registered a tap handler.
     * Backed by snapshot state — readable as a Compose state observable.
     */
    val hasHandlers: Boolean
        get() = registrations.isNotEmpty()

    /**
     * Registers a tile tap handler. Returns an unregister function intended
     * for use in a [DisposableEffect.onDispose] block.
     */
    fun register(registration: TileGestureRegistration): () -> Unit {
        registrations.add(registration)
        return { registrations.remove(registration) }
    }

    /**
     * Converts [tapX]/[tapY] screen coordinates to a tile coordinate for each
     * registered handler and invokes the handler if the tile falls within the
     * grid's declared bounds.
     *
     * Called by IsometricScene during tap event processing, after the
     * user-facing [GestureConfig.onTap] callback has already fired.
     *
     * @param tapX Screen x-coordinate of the tap (px)
     * @param tapY Screen y-coordinate of the tap (px)
     * @param viewportWidth Scene canvas width at the time of tap (px)
     * @param viewportHeight Scene canvas height at the time of tap (px)
     * @param engine The scene's projection engine (used for inverse projection)
     */
    fun dispatch(
        tapX: Double,
        tapY: Double,
        viewportWidth: Int,
        viewportHeight: Int,
        engine: IsometricEngine
    ) {
        for (reg in registrations) {
            val tile = engine.screenToTile(
                screenX = tapX,
                screenY = tapY,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                tileSize = reg.config.tileSize,
                elevation = 0.0,
                originOffset = reg.config.originOffset
            )
            if (tile.isWithin(reg.gridWidth, reg.gridHeight)) {
                reg.onTileClick(tile)
            }
        }
    }
}

/**
 * Provides the [TileGestureHub] created by the nearest enclosing IsometricScene.
 * Null when accessed outside an IsometricScene (e.g., in previews or tests without
 * a scene host).
 */
internal val LocalTileGestureHub = staticCompositionLocalOf<TileGestureHub?> { null }
```

**Why `elevation = 0.0` is hardcoded in `dispatch()`**: The hub dispatches against the flat ground plane. This correctly handles the common case. For elevated terrain, users should use the Layer 3 escape hatch: configure `GestureConfig.onTap` on the scene and call `engine.screenToTile(elevation = height)` directly using `LocalIsometricEngine.current`. The known limitation is documented on the `TileGrid` composable in Step 6.

**Why the hub dispatches to all registrations per tap**: Multiple `TileGrid` composables can coexist in a single scene (a base terrain grid and a decoration grid at a different elevation, for example). Each registration bounds-checks independently. If bounds do not overlap, each handler fires only for taps in its own region.

### Verification

- `TileGestureHub().hasHandlers` is `false` initially
- After `hub.register(reg)`, `hub.hasHandlers` is `true` — and this change is observable by Compose snapshot observers (backed by `mutableStateListOf`)
- Calling the returned unregister function causes `hub.hasHandlers` to return `false`
- `hub.dispatch(...)` calls the registered callback only when the resolved tile is within `gridWidth`/`gridHeight`
- Taps outside grid bounds do not invoke the callback
- Multiple registrations all receive the same dispatch call independently

---

## Step 5: `IsometricScene` Wiring — Hub Provision, Reactive Gesture Enablement, Tap Dispatch

### Rationale

`IsometricScene` must do three new things to support `TileGrid.onTileClick`:

1. **Create and provide the hub** via `LocalTileGestureHub` — so `TileGrid` composables in the `content` lambda can find and register with it.
2. **Reactively enable gestures** when the hub has handlers — so `onTileClick` works without requiring the caller to configure `GestureConfig` on the scene.
3. **Dispatch taps to the hub** after firing the user-facing `GestureConfig.onTap` — so tile coordinate conversion happens on confirmed taps.

This is purely additive. The existing tap path (`GestureConfig.onTap`) is unchanged.

### Best Practice

`tileGestureHub` is `remember`ed without keys — its identity is stable for the lifetime of the composition. Only its contents change reactively. The reactive `hasHandlers` property (backed by `mutableStateListOf`) causes `IsometricScene` to recompose when a `TileGrid` registers or unregisters, updating the `gesturesActive` flag and therefore the `pointerInput` modifier installation.

### Files and Changes

#### 5a. `IsometricScene.kt` — Create and remember the hub

**Location**: `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricScene.kt`

In the advanced `IsometricScene` overload body, add alongside the existing `remember` blocks:

```kotlin
val tileGestureHub = remember { TileGestureHub() }
```

#### 5b. `IsometricScene.kt` — Provide hub via `composition.setContent`

The `CompositionLocalProvider` in `IsometricScene` is not a simple outer block — it lives inside a `DisposableEffect(composition)` that calls `composition.setContent { ... }`. The local additions go inside the `buildList { ... }` that constructs the provider arguments:

**Current** (inside `DisposableEffect(composition)` → `composition.setContent { buildList { ... } }`):
```kotlin
val providers = buildList {
    add(LocalDefaultColor provides currentDefaultColor)
    add(LocalLightDirection provides currentLightDirection)
    add(LocalRenderOptions provides currentRenderOptions)
    add(LocalStrokeStyle provides currentStrokeStyle)
    add(LocalColorPalette provides currentColorPalette)
    if (currentIsometricEngine != null) {
        add(LocalIsometricEngine provides currentIsometricEngine!!)
    }
}
```

**After** — add the hub to the list:
```kotlin
val providers = buildList {
    add(LocalDefaultColor provides currentDefaultColor)
    add(LocalLightDirection provides currentLightDirection)
    add(LocalRenderOptions provides currentRenderOptions)
    add(LocalStrokeStyle provides currentStrokeStyle)
    add(LocalColorPalette provides currentColorPalette)
    if (currentIsometricEngine != null) {
        add(LocalIsometricEngine provides currentIsometricEngine!!)
    }
    add(LocalTileGestureHub provides tileGestureHub) // ← new
}
```

**Note on `rememberUpdatedState` for the hub**: The hub is stable (`remember`ed, not updated from config) so it does not need `rememberUpdatedState`. The `LocalTileGestureHub provides tileGestureHub` entry uses the hub instance directly.

#### 5c. `IsometricScene.kt` — Reactive gesture enablement

The existing code derives gesture enablement from:

```kotlin
val gesturesActive = config.gestures.enabled || config.cameraState != null
```

Extend this to also activate when the hub has registered tile handlers:

```kotlin
val gesturesActive = config.gestures.enabled || config.cameraState != null || tileGestureHub.hasHandlers
```

**Key change**: the `pointerInput` modifier currently uses `Unit` as its key — `Modifier.pointerInput(Unit)`. With `Unit` as the key, the pointer input coroutine is launched once and never restarted. This means changing `gesturesActive` from `false` to `true` (because a `TileGrid` registered) does not install the modifier — the condition check is outside the `pointerInput` block, not inside it.

The `pointerInput` key must change to `gesturesActive`:

**Current**:
```kotlin
if (gesturesActive) {
    Modifier.pointerInput(Unit) { ... }
} else {
    Modifier
}
```

**After**:
```kotlin
if (gesturesActive) {
    Modifier.pointerInput(gesturesActive) { ... }
} else {
    Modifier
}
```

When `gesturesActive` flips from `false` to `true` (because a `TileGrid` registered its first handler), Compose re-evaluates the modifier chain and installs a fresh `pointerInput` coroutine. When it flips back to `false`, the modifier is removed and the coroutine cancelled. This is the correct Compose pattern for dynamically enabled gesture modifiers.

#### 5d. `IsometricScene.kt` — Dispatch taps to the hub

In the `PointerEventType.Release` block, after the existing `currentGestures.onTap?.invoke(tapEvent)` call:

```kotlin
PointerEventType.Release -> {
    val position = event.changes.first().position

    if (!isDragging) {
        val hitNode = hitTestResolver.findItemAt(...)
        val tapEvent = TapEvent(
            x = position.x.toDouble(),
            y = position.y.toDouble(),
            node = hitNode
        )

        // Existing: user-facing scene tap callback
        currentGestures.onTap?.invoke(tapEvent)

        // New: route to any registered TileGrid tap handlers
        val isometricEngine = currentIsometricEngine
        if (tileGestureHub.hasHandlers && isometricEngine != null) {
            tileGestureHub.dispatch(
                tapX = position.x.toDouble(),
                tapY = position.y.toDouble(),
                viewportWidth = currentCanvasWidth,
                viewportHeight = currentCanvasHeight,
                engine = isometricEngine
            )
        }
    }

    isDragging = false
    dragStartPos = null
}
```

**`currentCanvasWidth` and `currentCanvasHeight`** are already declared via `rememberUpdatedState` in the existing codebase — no new declarations needed:
```kotlin
// Already present in IsometricScene:
val currentCanvasWidth by rememberUpdatedState(canvasWidth)
val currentCanvasHeight by rememberUpdatedState(canvasHeight)
```

**`currentIsometricEngine`** is also already declared:
```kotlin
// Already present in IsometricScene:
val currentIsometricEngine by rememberUpdatedState(engine as? IsometricEngine)
```

The null guard `isometricEngine != null` is required because `engine` is typed as `SceneProjector` (the interface), and `screenToTile` is defined on `IsometricEngine` (the concrete class). The null cast protects against a future custom `SceneProjector` that is not an `IsometricEngine`.

**Ordering**: `currentGestures.onTap` fires first, then `tileGestureHub.dispatch`. This gives user-level scene tap handlers first visibility of every tap before tile routing occurs.

### Verification

- `IsometricScene { TileGrid(10, 10, onTileClick = { ... }) { ... } }` — no `GestureConfig` on scene — taps are processed correctly
- `IsometricScene(config = SceneConfig(gestures = GestureConfig(onTap = { ... }))) { TileGrid(...) { ... } }` — both `onTap` and `onTileClick` fire; `onTap` fires first
- Scene with no `TileGrid` and `GestureConfig.Disabled` — no `pointerInput` installed (no regression)
- Tap outside all grid bounds — `onTileClick` is not invoked
- `TileGrid` leaving the composition — `DisposableEffect` removes the registration; `hasHandlers` drops to `false`; `gesturesActive` recomputes; `pointerInput` is uninstalled if `config.gestures` and `cameraState` are also inactive
- `CameraState` active + `TileGrid` present — `gesturesActive` is `true` from both; key is `true`; no conflict

---

## Step 6: `TileGrid` Composable — The Public API

### Rationale

The public surface is a single composable: `TileGrid`. It is an extension on `IsometricScope`, consistent with `Shape`, `Group`, `ForEach`, `Batch`, and `Path`. It does three things:

1. **Renders tiles**: Uses the existing `ForEach` + `Group` composables to place a grid of tile positions. Each tile is a `Group` at its world coordinate, so the user's `content` lambda renders relative to that tile's origin without specifying positions manually.
2. **Registers tap routing**: Uses `DisposableEffect` to register a `TileGestureRegistration` with the `TileGestureHub` when `onTileClick` is non-null.
3. **Validates inputs early**: `require()` on `width`/`height` at entry — guideline §6.

### Hero Scenario (Layer 1 — Simple Path)

```kotlin
IsometricScene(modifier = Modifier.fillMaxSize()) {
    TileGrid(
        width = 10,
        height = 10,
        onTileClick = { coord -> selectedTile = coord }
    ) { coord ->
        Shape(
            geometry = Prism(),
            color = if (coord == selectedTile) IsoColor.BLUE else IsoColor.GRAY
        )
    }
}
```

Five lines. No config objects. No knowledge of projection math. This is the hero scenario per guideline §1.

### Configurable Path (Layer 2 — Common Variations)

```kotlin
IsometricScene(modifier = Modifier.fillMaxSize()) {
    TileGrid(
        width = 20,
        height = 20,
        config = TileGridConfig(
            tileSize = 1.0,
            originOffset = Point(-10.0, -10.0, 0.0),
            elevation = { coord -> heightMap[coord] ?: 0.0 }
        ),
        onTileClick = { coord -> handleTileClick(coord) }
    ) { coord ->
        val height = heightMap[coord] ?: 0.0
        Shape(geometry = Prism(height = height.coerceAtLeast(0.1)), color = terrainColor(height))
    }
}
```

### Escape Hatch (Layer 3 — Advanced Control)

Users needing custom tap-to-tile logic (multi-layer elevation, tap correction not handled by the built-in routing) can use `LocalIsometricEngine` and `screenToTile()` directly in `GestureConfig.onTap`. `LocalIsometricEngine` is already provided by `IsometricScene` — no additional setup needed:

```kotlin
val engine = LocalIsometricEngine.current

IsometricScene(
    config = SceneConfig(
        gestures = GestureConfig(
            onTap = { event ->
                val tile = engine.screenToTile(
                    screenX = event.x,
                    screenY = event.y,
                    viewportWidth = canvasWidth,
                    viewportHeight = canvasHeight,
                    elevation = layerElevation
                )
                if (tile.isWithin(gridWidth, gridHeight)) handleTileClick(tile)
            }
        )
    )
) {
    TileGrid(width = 20, height = 20) { coord -> /* content */ }
}
```

### Best Practice

**`@IsometricComposable`**: `TileGrid` must carry the `@IsometricComposable` annotation, identical to `Shape`, `Group`, `ForEach`, and `Path`. This annotation is a `@ComposableTarget` that restricts the composable to the `IsometricApplier` tree — without it, calling `TileGrid` outside an `IsometricScene` would compile silently but fail at runtime.

**`DisposableEffect` key discipline**: The `onTileClick` lambda must **not** be included as a `DisposableEffect` key. Lambda instances are not stable across recompositions when written as lambda literals — including the lambda as a key would re-run the `DisposableEffect` (and therefore unregister and re-register) on every recompose. Instead, use `rememberUpdatedState(onTileClick)` to get a stable `State` wrapper, and have the registration capture a lambda that reads the state value at dispatch time. This is the same pattern used throughout `IsometricScene` for gesture callbacks.

**`ForEach` key**: Use `key = { it }` — the `TileCoordinate` itself is the natural stable key since it has correct `equals`/`hashCode`. This avoids string formatting overhead on every recomposition.

### Files and Changes

#### 6a. New file: `TileGrid.kt`

**Location**: `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/TileGrid.kt`

```kotlin
package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.TileCoordinate
import io.github.jayteealao.isometric.TileGridConfig

/**
 * Renders an isometric tile grid and routes tap events to tile coordinates.
 *
 * Tiles are arranged in a [width] × [height] grid. Each tile is rendered by
 * the [content] lambda, which receives the [TileCoordinate] of the tile being
 * drawn. Content is rendered in the coordinate space of the tile — a [Shape]
 * with the default position renders at the tile's world origin without any
 * additional positioning.
 *
 * When [onTileClick] is provided, taps on the scene are automatically converted
 * from screen coordinates to tile coordinates. Only taps within the grid bounds
 * invoke the callback. No [GestureConfig] on the enclosing [IsometricScene] is
 * required — gesture handling is enabled automatically.
 *
 * **Elevation and tap accuracy**: [onTileClick] assumes a flat ground plane
 * (z = 0) for hit-testing. For elevated terrain, tap routing may miss tiles
 * whose visual surface is above z = 0. Use the escape hatch: configure
 * [GestureConfig.onTap] on the scene and call [IsometricEngine.screenToTile]
 * with the appropriate elevation using [LocalIsometricEngine].
 *
 * @param width Number of tile columns (must be ≥ 1)
 * @param height Number of tile rows (must be ≥ 1)
 * @param config Grid configuration: tile size, world origin, optional elevation function.
 *   Defaults to a 1-unit flat grid at world origin.
 * @param onTileClick Called when the user taps a tile within this grid's bounds.
 *   Null by default (no tap handling).
 * @param content Composable lambda for each tile. Receives the tile's [TileCoordinate].
 *   Rendered in the tile's local coordinate space — the tile origin is at (0, 0, 0)
 *   relative to the group.
 */
@IsometricComposable
@Composable
fun IsometricScope.TileGrid(
    width: Int,
    height: Int,
    config: TileGridConfig = TileGridConfig(),
    onTileClick: ((TileCoordinate) -> Unit)? = null,
    content: @Composable IsometricScope.(TileCoordinate) -> Unit
) {
    require(width >= 1) { "TileGrid width must be at least 1, got $width" }
    require(height >= 1) { "TileGrid height must be at least 1, got $height" }

    // Build the full coordinate list once per grid dimension change.
    // Config changes do not affect the coordinate list — only tile positions.
    val coordinates = remember(width, height) {
        (0 until width).flatMap { x -> (0 until height).map { y -> TileCoordinate(x, y) } }
    }

    // Render each tile as a Group positioned at its world coordinate.
    // Content inside the Group uses the tile origin as its local (0,0,0).
    ForEach(items = coordinates, key = { it }) { coord ->
        val elevation = config.elevation?.invoke(coord) ?: config.originOffset.z
        val tilePosition = Point(
            x = config.originOffset.x + coord.x * config.tileSize,
            y = config.originOffset.y + coord.y * config.tileSize,
            z = elevation
        )
        Group(position = tilePosition) {
            content(coord)
        }
    }

    // Register a tap handler with the hub for automatic screen → tile routing.
    // rememberUpdatedState ensures the registration always calls the latest
    // onTileClick without needing to re-register on every recomposition.
    val hub = LocalTileGestureHub.current
    val currentOnTileClick by rememberUpdatedState(onTileClick)

    if (hub != null && onTileClick != null) {
        DisposableEffect(hub, config, width, height) {
            val registration = TileGestureRegistration(
                config = config,
                gridWidth = width,
                gridHeight = height,
                onTileClick = { coord -> currentOnTileClick?.invoke(coord) }
            )
            val unregister = hub.register(registration)
            onDispose { unregister() }
        }
    }
}
```

**Why `DisposableEffect(hub, config, width, height)` — not including `onTileClick`**:

The registration must be replaced when `hub`, `config`, `width`, or `height` change:
- `hub`: if the scene remounts, a new hub is provided; the old registration is in the previous hub and must be replaced
- `config`: the `TileGestureRegistration` stores `config` for coordinate math; a new config means a new registration
- `width`/`height`: the registration stores these for bounds-checking

`onTileClick` is intentionally excluded because lambda instances are not stable across recompositions. Including it would cause `DisposableEffect` to re-run on every recomposition, unregistering and re-registering the handler redundantly. `rememberUpdatedState(onTileClick)` + the captured closure `{ coord -> currentOnTileClick?.invoke(coord) }` ensures the latest callback is always called at dispatch time without triggering re-registration.

**Why the outer `if (hub != null && onTileClick != null)` guard**:

- `hub != null`: protects against use outside an `IsometricScene` (previews, tests without a scene host)
- `onTileClick != null`: avoids installing a `DisposableEffect` when no tap handling is needed

When `onTileClick` changes from non-null to null (or back), Compose enters and exits the `if` block, which causes `onDispose` to fire (unregistering the handler) and the `DisposableEffect` to re-run (re-registering it) respectively. Conditional composable blocks in Compose handle this correctly.

**Tile position math**: Each tile's `Group` position is:
```
x = originOffset.x + coord.x × tileSize
y = originOffset.y + coord.y × tileSize
z = elevation(coord)  (or originOffset.z if no elevation function)
```
Tile (0,0) is at `originOffset` exactly. Subsequent tiles are spaced `tileSize` apart. Content inside each `Group` uses the tile position as its local (0,0,0).

### Verification

- `TileGrid(10, 10) { }` renders 100 tiles without `onTileClick`
- `TileGrid(0, 10) { }` throws `IllegalArgumentException` at composition time
- `TileGrid(10, 10, onTileClick = { ... }) { }` — scene enables gesture handling; taps route to callback
- Tapping tile (3, 5) invokes `onTileClick(TileCoordinate(3, 5))`
- Tapping outside the 10×10 grid does not invoke `onTileClick`
- `TileGrid` leaving the composition — unregisters from the hub; `hasHandlers` drops to false; no further tap callbacks
- `config.elevation = { coord -> coord.x * 0.5 }` — tiles render at increasing z; layout is correct
- `config = TileGridConfig(tileSize = 2.0)` — tiles are spaced 2 world units apart; tap routing uses the same `tileSize`
- Changing `onTileClick` from one non-null lambda to another — `rememberUpdatedState` ensures the new lambda is called without re-registration

---

## Step 7: `StackAxis` and `Stack` Composable

### Rationale

`TileGrid` eliminates the repetitive `ForEach` + position-math pattern for 2D tile grids. The same pattern appears in 1D: users who want to place multiple objects along a single axis — floors of a building, a row of columns, a line of platforms — currently write:

```kotlin
ForEach(items = (0 until floors).toList(), key = { it }) { floor ->
    Group(position = Point(0.0, 0.0, floor.toDouble() * floorHeight)) {
        Shape(geometry = Prism())
    }
}
```

This is correct but reveals internals the user should not need to think about: the `ForEach` key, an explicit `Group`, and the arithmetic to compute each Z offset. `Stack` encapsulates the pattern into a single composable:

```kotlin
Stack(count = floors, axis = StackAxis.Z, gap = floorHeight) { floor ->
    Shape(geometry = Prism())
}
```

`StackAxis` is the complementary enum that names the three world axes for the `axis` parameter, using intent-revealing names (`Z` for vertical, `X` and `Y` for the two isometric horizontal directions) rather than requiring callers to construct `Point` unit vectors directly.

Neither `StackAxis` nor `Stack` depend on `TileGrid` — they are independent additions that fit naturally alongside it as layout composables for `IsometricScope`. Both should be implemented in this workstream because they are small, follow identical patterns, and share the same motivation.

### Best Practice

**`StackAxis` in `isometric-core`**: The enum has no Compose dependency. It belongs in `isometric-core` alongside `TileCoordinate`, `TileGridConfig`, and the other domain types, so that a future `isometric-android-view` module can reuse it without a Compose dependency.

**`unitPoint()` on `StackAxis`**: The method returns the unit world `Point` for each axis. It is public — advanced users who want to compute their own offsets (e.g., diagonal stacking by combining two axes) can call it without reading internal `when` expressions. It also keeps the `Stack` composable body clean: position math reduces to a single `Point` multiply rather than a `when` expression per child.

**`Stack` in `isometric-compose`**: Follows the same `@IsometricComposable @Composable fun IsometricScope.FunctionName(...)` pattern as `Shape`, `Group`, `Path`, `Batch`, `TileGrid`, and `ForEach`. The `@IsometricComposable` annotation is required — without it, calling `Stack` outside an `IsometricScene` would compile silently and fail at runtime.

**Index-based lambda, not arbitrary content**: Like `TileGrid`, `Stack` uses `content: @Composable IsometricScope.(index: Int) -> Unit`. This is the only compositional model that allows assigning a distinct position to each child — Compose does not support enumerating children of a content block before they compose.

**No `position` parameter on `Stack`**: Users who need to position the entire stack in the scene wrap it in a `Group`. Adding a `position` parameter to `Stack` would duplicate `Group`'s responsibility and introduce an ambiguity ("does position refer to the first item's origin or the stack's center?"). All other layout composables (`TileGrid`, `ForEach`) follow this same "no baked-in position, use Group" convention.

**`gap` allows negative values**: A negative `gap` reverses the stacking direction — `axis = StackAxis.Z, gap = -1.0` stacks downward from world origin. This is intentional and documented: the sign encodes direction. The only prohibited values are zero (collapses all items to the same position, always a user error) and non-finite (NaN/Infinity produce invalid world coordinates).

**`gap` validation order — `isFinite` before `!= 0.0`**: `Double.NaN != 0.0` evaluates to `true` in Kotlin (NaN comparisons always return `false`). Checking `isFinite` first ensures NaN fails with the correct "must be finite" message rather than silently passing the zero check and producing corrupt positions.

**`count` minimum is 1**: A count of zero renders nothing and is almost certainly a user error. The `require()` guard fires at composition time with an actionable message — consistent with `TileGrid`'s `width >= 1` / `height >= 1` guards and the `scale > 0.0` guard on `Shape`.

**`remember(count)` for the indices list**: The `(0 until count).toList()` allocation is hoisted into `remember(count)` so that the list is not reallocated on every recomposition. Without `remember`, `ForEach` sees a new list reference on every recompose and treats all items as changed, causing spurious re-composition of all children.

**`val unit = axis.unitPoint()` hoisted outside `ForEach`**: `unitPoint()` is a constant for a given `StackAxis`. Hoisting the call outside the `ForEach` block avoids calling it once per child per recomposition.

### Files and Changes

#### 7a. New file: `StackAxis.kt`

**Location**: `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/StackAxis.kt`

```kotlin
package io.github.jayteealao.isometric

/**
 * The world axis along which a [Stack] composable arranges its children.
 *
 * In isometric view:
 * - [Z] is vertical (up/down on screen) — the most common stacking direction.
 * - [X] runs right-and-forward on screen.
 * - [Y] runs left-and-forward on screen.
 *
 * Pass to [Stack] via the `axis` parameter. Defaults to [Z].
 */
enum class StackAxis {
    /** Stack along the world X axis (right-and-forward in isometric view). */
    X,

    /** Stack along the world Y axis (left-and-forward in isometric view). */
    Y,

    /** Stack along the world Z axis (vertical — the default stacking direction). */
    Z;

    /**
     * Returns the unit [Point] for this axis.
     *
     * Each component is 0.0 except the component for this axis, which is 1.0.
     * Multiply by child index and [Stack.gap] to compute the child's position offset.
     *
     * ```kotlin
     * // Manual offset computation using unitPoint:
     * val unit = StackAxis.Z.unitPoint()  // Point(0.0, 0.0, 1.0)
     * val childPosition = Point(unit.x * index * gap, unit.y * index * gap, unit.z * index * gap)
     * ```
     *
     * Also useful for diagonal stacking — add two axis unit points and use the
     * result as a direction vector into a nested [Group] position.
     */
    fun unitPoint(): Point = when (this) {
        X -> Point(1.0, 0.0, 0.0)
        Y -> Point(0.0, 1.0, 0.0)
        Z -> Point(0.0, 0.0, 1.0)
    }
}
```

**Why `enum` not `sealed class`**: `StackAxis` is a fixed, exhaustive set of three mutually-exclusive values with no associated data. An `enum` communicates exhaustiveness at the call site, produces concise `when` expressions without `else`, and requires no companion object or subclass boilerplate. A `sealed class` would add noise without benefit.

**Why `unitPoint()` is `public` not `internal`**: The method is useful to callers who want to compute stack bounds (e.g., total extent = `count * gap` along the axis direction) or to drive custom animations aligned with the stack axis. Making it `internal` would force users to duplicate the `when` expression themselves.

#### 7b. New file: `Stack.kt`

**Location**: `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/Stack.kt`

```kotlin
package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.StackAxis

/**
 * Renders [count] children arranged along [axis] at equal [gap] spacing.
 *
 * Each child is placed inside a [Group] whose position is `axis × index × gap`
 * from world origin. Content renders relative to that Group's local origin —
 * a [Shape] at the default position appears at the item's slot without any
 * additional offset.
 *
 * ```kotlin
 * // Five-floor building — each floor is 1 world unit above the previous
 * Stack(count = 5, axis = StackAxis.Z, gap = 1.0) { floor ->
 *     Shape(geometry = Prism(), color = floorColors[floor])
 * }
 *
 * // Row of eight columns spaced 1.5 world units apart along X
 * Stack(count = 8, axis = StackAxis.X, gap = 1.5) { _ ->
 *     Shape(geometry = Cylinder())
 * }
 *
 * // To position the whole stack, wrap in a Group:
 * Group(position = Point(2.0, 3.0, 0.0)) {
 *     Stack(count = 4, axis = StackAxis.Z, gap = 1.0) { floor -> … }
 * }
 * ```
 *
 * **Negative gap**: Reverses the stacking direction.
 * `axis = StackAxis.Z, gap = -1.0` stacks downward from world origin.
 *
 * **Nested stacks**: Two `Stack` composables can be nested to form a 2D
 * arrangement. For uniform tile grids, prefer [TileGrid] — it provides
 * tap routing and is optimised for the common case.
 *
 * @param count Number of children to render. Must be ≥ 1.
 * @param axis The world axis along which children are arranged.
 *   Defaults to [StackAxis.Z] (vertical — the most common stacking direction).
 * @param gap World-unit distance between consecutive child origins.
 *   Must be non-zero and finite. Negative values reverse the stacking direction.
 * @param content Composable block called once per child, receiving the
 *   child's zero-based [index]. Rendered in the child's local coordinate
 *   space — position (0,0,0) is the child's Group origin.
 * @see StackAxis
 * @see Group
 * @see TileGrid
 */
@IsometricComposable
@Composable
fun IsometricScope.Stack(
    count: Int,
    axis: StackAxis = StackAxis.Z,
    gap: Double = 1.0,
    content: @Composable IsometricScope.(index: Int) -> Unit
) {
    require(count >= 1) { "Stack count must be at least 1, got $count" }
    require(gap.isFinite()) { "Stack gap must be finite, got $gap" }
    require(gap != 0.0) { "Stack gap must be non-zero, got $gap" }

    // Stable list allocation — avoids reallocating on every recomposition.
    val indices = remember(count) { (0 until count).toList() }

    // Hoist unitPoint() outside ForEach — constant for a given axis.
    val unit = axis.unitPoint()

    ForEach(items = indices, key = { it }) { index ->
        val position = Point(
            x = unit.x * index * gap,
            y = unit.y * index * gap,
            z = unit.z * index * gap
        )
        Group(position = position) {
            content(index)
        }
    }
}
```

**Why position math uses component-wise multiply rather than a `Point.times(scalar)` operator**: `Point` does not currently define a scalar multiplication operator. Using component-wise arithmetic is explicit and dependency-free. If a `Point.times(Double)` operator is added in a future workstream, `Stack` can be simplified to `val position = unit * index * gap` without changing behavior.

**Why `DisposableEffect` is not needed**: Unlike `TileGrid`, `Stack` does not register with any hub or external system. It is purely a rendering layout that composes `Group` nodes — all cleanup is handled automatically by the Compose runtime when the `Stack` leaves the composition.

**Why there is no `onItemClick` parameter**: Tap handling in the isometric scene is spatial, not index-based — users who need tap-per-slot should use `GestureConfig.onTap` with hit testing against `ownerNodeId`, or wrap each slot in a `Group` with a custom `CustomNode` that assigns a known `ownerNodeId`. Adding `onItemClick` to `Stack` would require the same `TileGestureHub` registration infrastructure as `TileGrid`, which is not warranted for the 1D case where positional overlap between slots is common (e.g., a tower of prisms all occupying a similar screen region). Document this as a known limitation.

### Hero Scenario (Layer 1 — Simple Path)

```kotlin
IsometricScene(modifier = Modifier.fillMaxSize()) {
    Stack(count = 5, axis = StackAxis.Z, gap = 1.0) { floor ->
        Shape(geometry = Prism(), color = floorColors[floor])
    }
}
```

Three parameters. No `ForEach`, no `Group`, no position arithmetic. This is the hero scenario per guideline §1.

### Configurable Variations (Layer 2)

```kotlin
// Horizontal row along X
Stack(count = 8, axis = StackAxis.X, gap = 1.5) { _ ->
    Shape(geometry = Pyramid())
}

// Row along Y with per-item color
Stack(count = 6, axis = StackAxis.Y, gap = 2.0) { col ->
    Shape(geometry = Prism(), color = columnColors[col])
}

// Reverse stacking — downward from origin
Stack(count = 3, axis = StackAxis.Z, gap = -1.0) { layer ->
    Shape(geometry = Prism())
}

// Positioned via enclosing Group
Group(position = Point(5.0, 0.0, 0.0)) {
    Stack(count = 4, axis = StackAxis.Z, gap = 1.0) { floor ->
        Shape(geometry = Prism())
    }
}

// Nested stacks — 2D arrangement by composing two Stacks
// (prefer TileGrid for uniform interactive tile grids)
Stack(count = 4, axis = StackAxis.X, gap = 2.0) { col ->
    Stack(count = 3, axis = StackAxis.Y, gap = 2.0) { row ->
        Shape(geometry = Prism(), color = gridColors[col][row])
    }
}
```

### Escape Hatch (Layer 3 — Advanced Control)

Users who need per-item tap handling, non-uniform gaps, or non-axis-aligned directions should use `ForEach` with explicit `Group` positioning:

```kotlin
// Non-uniform gap (items spaced by their individual heights)
var zOffset = 0.0
ForEach(items = floors, key = { it.id }) { floor ->
    Group(position = Point(0.0, 0.0, zOffset)) {
        Shape(geometry = Prism(height = floor.height))
    }
    zOffset += floor.height
}
```

This is the intentional Layer 3 escape hatch — `Stack` is not trying to model variable spacing. Guideline §9 applies: always leave a clear path out.

### Verification

- `Stack(count = 1, axis = StackAxis.Z, gap = 1.0)` — renders one child at `Point(0, 0, 0)` (index 0 × gap = 0)
- `Stack(count = 3, axis = StackAxis.Z, gap = 1.0)` — children at z = 0.0, 1.0, 2.0
- `Stack(count = 3, axis = StackAxis.X, gap = 2.0)` — children at x = 0.0, 2.0, 4.0
- `Stack(count = 3, axis = StackAxis.Y, gap = 1.5)` — children at y = 0.0, 1.5, 3.0
- `Stack(count = 3, axis = StackAxis.Z, gap = -1.0)` — children at z = 0.0, -1.0, -2.0
- `Stack(count = 0, ...)` — throws `IllegalArgumentException("Stack count must be at least 1, got 0")` at composition time
- `Stack(count = 3, gap = 0.0)` — throws `IllegalArgumentException("Stack gap must be non-zero, got 0.0")`
- `Stack(count = 3, gap = Double.NaN)` — throws `IllegalArgumentException("Stack gap must be finite, got NaN")` (isFinite check fires before zero check)
- `Stack(count = 3, gap = Double.POSITIVE_INFINITY)` — throws `IllegalArgumentException("Stack gap must be finite, got Infinity")`
- `remember(count)` stability: changing only `axis` or `gap` on recomposition does not reallocate the indices list
- Content index 0 always receives position (0, 0, 0) on the axis — no offset at the base
- `StackAxis.X.unitPoint() == Point(1.0, 0.0, 0.0)`, `Y == Point(0.0, 1.0, 0.0)`, `Z == Point(0.0, 0.0, 1.0)`
- Nested `Stack(axis = X) { Stack(axis = Y) { } }` — outer and inner offsets are independent; no axis interference
- `Stack` inside a `TileGrid` content block — positions are relative to the tile's Group origin, so a `Stack(count = 3, axis = StackAxis.Z)` inside a tile produces a tower at that tile's world position

---

## Step 8: Tests and Examples

### Test Coverage

#### 8a. `TileCoordinateTest.kt` — unit tests in `isometric-core`

**Location**: `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/TileCoordinateTest.kt`

Required test cases:
- Construction and property access
- `equals`/`hashCode` contract (reflexive, symmetric, consistent)
- `toString` format
- `+` and `-` operators
- `isWithin`: in bounds, on exact boundary, out of bounds on x, out of bounds on y, negative coordinates
- `toPoint`: default tileSize, custom tileSize, with elevation

#### 8b. `TileCoordinateExtensionsTest.kt` — unit tests in `isometric-core`

**Location**: `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/TileCoordinateExtensionsTest.kt`

Required test cases:
- `Point.toTileCoordinate()`: positive coordinates, exact grid boundary, negative coordinates, non-unit tileSize, non-zero originOffset, `tileSize = 0.0` throws
- `IsometricEngine.screenToTile()`: round-trip accuracy for a 10×10 grid at default engine parameters — project each tile's world origin to screen with `worldToScreen`, then call `screenToTile`, verify the correct `TileCoordinate` is returned

#### 8c. `TileGridConfigTest.kt` — unit tests in `isometric-core`

**Location**: `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/TileGridConfigTest.kt`

Required test cases:
- Construction: defaults, custom `tileSize`, custom `originOffset`, with elevation function
- Validation: `tileSize = 0.0`, `tileSize = -1.0`, `tileSize = Double.POSITIVE_INFINITY`
- `equals`: two configs with same value fields are equal; elevation lambda excluded

#### 8d. `TileGridTest.kt` — Compose UI tests in `isometric-compose`

**Location**: `isometric-compose/src/androidTest/kotlin/io/github/jayteealao/isometric/compose/TileGridTest.kt`

Note: Compose UI tests require an Android device or emulator — they belong in `androidTest`, not `test`.

Required test cases:
- `width = 0` throws at composition time
- `onTileClick` fires for an in-bounds tap
- `onTileClick` does not fire for an out-of-bounds tap
- `TileGrid` removed from composition — subsequent taps do not invoke callback
- Two `TileGrid` composables with non-overlapping bounds — tap routes to the correct grid only
- `GestureConfig.Disabled` on the scene + `onTileClick` on `TileGrid` — taps are processed correctly

#### 8e. `StackAxisTest.kt` — unit tests in `isometric-core`

**Location**: `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/StackAxisTest.kt`

Required test cases:
- All three enum values exist: `StackAxis.X`, `StackAxis.Y`, `StackAxis.Z`
- `StackAxis.X.unitPoint() == Point(1.0, 0.0, 0.0)`
- `StackAxis.Y.unitPoint() == Point(0.0, 1.0, 0.0)`
- `StackAxis.Z.unitPoint() == Point(0.0, 0.0, 1.0)`
- `StackAxis.values()` returns exactly three entries (exhaustiveness guard)
- `StackAxis.valueOf("X") == StackAxis.X` (enum valueOf contract)

#### 8f. `StackTest.kt` — Compose UI tests in `isometric-compose`

**Location**: `isometric-compose/src/androidTest/kotlin/io/github/jayteealao/isometric/compose/StackTest.kt`

Note: Compose UI tests require an Android device or emulator — they belong in `androidTest`, not `test`.

Required test cases:
- `count = 0` throws `IllegalArgumentException` at composition time
- `count = -1` throws `IllegalArgumentException` at composition time
- `gap = 0.0` throws `IllegalArgumentException` at composition time
- `gap = Double.NaN` throws `IllegalArgumentException` ("must be finite") before zero check fires
- `gap = Double.POSITIVE_INFINITY` throws `IllegalArgumentException` ("must be finite")
- `Stack(count = 1)` renders exactly one child at position `(0, 0, 0)`
- `Stack(count = 3, axis = StackAxis.Z, gap = 1.0)` — children receive indices 0, 1, 2 in order; verify via rendered `color` or composable tracking
- `Stack(count = 3, axis = StackAxis.X, gap = 2.0)` — correct x offsets: 0.0, 2.0, 4.0
- `Stack(count = 3, axis = StackAxis.Z, gap = -1.0)` — correct z offsets: 0.0, -1.0, -2.0 (negative gap renders downward)
- `count` change during recomposition — children are added/removed correctly without full tree rebuild (verified via `remember` stability)
- `gap` change during recomposition — positions update; no unnecessary recomposition of unchanged children
- `Stack` inside `TileGrid` content block — positions accumulate correctly with the tile's `Group` transform
- `Stack` inside `Group(position = Point(1.0, 2.0, 0.0))` — offsets are local to the Group; world positions include the Group's offset

### Examples

#### 8g. New sample: `TileGridExample.kt`

**Location**: `app/src/main/kotlin/io/github/jayteealao/isometric/TileGridExample.kt`

A simple interactive tile selector: a 10×10 grid where the selected tile is highlighted. Serves as both a dogfood test (guideline §12) and a reference sample for documentation.

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
                geometry = Prism(),
                color = if (coord == selectedTile) IsoColor.BLUE else IsoColor.GRAY
            )
        }
    }
}
```

Verify on device and in Compose Preview before closing the workstream (guideline §12: dogfood the API).

#### 8h. New sample: `StackExample.kt`

**Location**: `app/src/main/kotlin/io/github/jayteealao/isometric/StackExample.kt`

Demonstrates all three `StackAxis` values side by side in a single scene. Serves as the dogfood test for `Stack` and a visual reference for documentation.

```kotlin
@Composable
fun StackExample() {
    val floorColors = listOf(
        IsoColor(33, 150, 243),   // blue — ground
        IsoColor(76, 175, 80),    // green — mid
        IsoColor(255, 193, 7),    // amber — top
        IsoColor(244, 67, 54)     // red — roof
    )

    IsometricScene(modifier = Modifier.fillMaxSize()) {

        // Vertical tower — 4 floors stacked along Z
        Group(position = Point(0.0, 0.0, 0.0)) {
            Stack(count = 4, axis = StackAxis.Z, gap = 1.0) { floor ->
                Shape(geometry = Prism(), color = floorColors[floor])
            }
        }

        // Horizontal row — 6 prisms along X, spaced 1.5 units
        Group(position = Point(0.0, 4.0, 0.0)) {
            Stack(count = 6, axis = StackAxis.X, gap = 1.5) { _ ->
                Shape(geometry = Prism(), color = IsoColor(120, 144, 156))
            }
        }

        // Depth row — 5 pyramids along Y, spaced 1.5 units
        Group(position = Point(4.0, 0.0, 0.0)) {
            Stack(count = 5, axis = StackAxis.Y, gap = 1.5) { _ ->
                Shape(geometry = Pyramid(), color = IsoColor(156, 39, 176))
            }
        }

        // Nested stacks — Stack inside TileGrid content block
        // Each tile of a 3×3 grid has a small tower of height proportional to x+y
        TileGrid(width = 3, height = 3, config = TileGridConfig(originOffset = Point(-6.0, 0.0, 0.0))) { coord ->
            val towerHeight = coord.x + coord.y + 1
            Stack(count = towerHeight, axis = StackAxis.Z, gap = 0.5) { _ ->
                Shape(geometry = Prism(height = 0.4), color = IsoColor(0, 188, 212))
            }
        }
    }
}
```

Four sub-scenes in one sample:
1. Vertical tower (`StackAxis.Z`) — the hero scenario
2. Horizontal row (`StackAxis.X`) — demonstrates X-axis stacking
3. Depth row (`StackAxis.Y`) — demonstrates Y-axis stacking
4. `Stack` inside `TileGrid` — demonstrates composability of the two layout primitives

Verify on device: all four arrangements render without overlap, positions are correct, and the nested stack heights match the expected `x + y + 1` formula per tile.

---

## Cross-Workstream Dependencies

| This step | Produces | Consumed by / Depends on |
|-----------|----------|--------------------------|
| Step 1 (`TileCoordinate`) | New core type | Steps 2, 3, 4, 6 — all depend on this type |
| Step 2 (`screenToTile`) | Extension functions on `Point` and `IsometricEngine` | Step 4 (`TileGestureHub.dispatch` calls `engine.screenToTile`); depends on WS6 Step 4 (`screenToWorld()` public API) |
| Step 3 (`TileGridConfig`) | Configuration type | Steps 4 (registration stores config), 6 (`TileGrid` parameter) |
| Step 4 (`TileGestureHub`) | Internal routing infrastructure | Step 5 (scene creates and provides it), Step 6 (`TileGrid` registers into it) |
| Step 5 (scene wiring) | Hub provision + reactive tap dispatch | Step 6 depends on hub being provided; coordinates with WS2 Step 5 (`IsometricScene` tap block — both modify `PointerEventType.Release`) |
| Step 6 (`TileGrid`) | Public composable API | Step 8 (tests and examples) |
| Step 7 (`StackAxis`, `Stack`) | Layout composables for 1D arrangement | Step 8 (tests and examples); no dependency on Steps 1–6 — `Stack` can be implemented in parallel with `TileGrid` once `ForEach` and `Group` are confirmed stable |
| WS6 Step 4 | `screenToWorld()` public API | Required by Step 2; if not yet merged, Step 2 must call the internal projection math directly as a temporary measure |
| `LocalIsometricEngine` | Already exists in `CompositionLocals.kt` and is already provided by `IsometricScene` | Used in the Layer 3 escape hatch path. No new provision work needed — it is already wired. |
| WS2 Step 3 | `GestureConfig.enabled` and the `gesturesActive` local in `IsometricScene` | Step 5 extends `gesturesActive` to include `tileGestureHub.hasHandlers` — must coordinate to avoid merge conflict on that line |

---

## File Change Summary

| File | Step | Changes |
|------|------|---------|
| `TileCoordinate.kt` | 1 | **new file** — regular class, operators, `isWithin()`, `toPoint()`, `equals`/`hashCode`/`toString`, `ORIGIN` constant |
| `TileCoordinateExtensions.kt` | 2 | **new file** — `Point.toTileCoordinate()` extension, `IsometricEngine.screenToTile()` extension |
| `TileGridConfig.kt` | 3 | **new file** — regular class, `tileSize`/`originOffset`/`elevation`, validation, `equals`/`hashCode`/`toString` |
| `TileGestureHub.kt` | 4 | **new file** — internal `TileGestureRegistration`, `TileGestureHub`, `LocalTileGestureHub` |
| `IsometricScene.kt` | 5 | Add `remember { TileGestureHub() }`; add `LocalTileGestureHub provides tileGestureHub` to `buildList` inside `composition.setContent`; extend `gesturesActive` condition with `\|\| tileGestureHub.hasHandlers`; change `pointerInput` key from `Unit` to `gesturesActive`; add `tileGestureHub.dispatch(...)` in `PointerEventType.Release` block |
| `TileGrid.kt` | 6 | **new file** — `@IsometricComposable @Composable` extension on `IsometricScope`: `require()` guards, `ForEach` + `Group` rendering loop, `rememberUpdatedState(onTileClick)`, `DisposableEffect` registration |
| `StackAxis.kt` | 7 | **new file** — `enum class StackAxis { X, Y, Z }` with `unitPoint(): Point` method; in `isometric-core` |
| `Stack.kt` | 7 | **new file** — `@IsometricComposable @Composable` extension on `IsometricScope`: `require()` guards, `remember(count)` indices list, `ForEach` + `Group` rendering loop with axis-computed position; in `isometric-compose` |
| `TileCoordinateTest.kt` | 8 | **new file** — unit tests for `TileCoordinate` |
| `TileCoordinateExtensionsTest.kt` | 8 | **new file** — unit tests for `toTileCoordinate()` and `screenToTile()` |
| `TileGridConfigTest.kt` | 8 | **new file** — unit tests for `TileGridConfig` |
| `TileGridTest.kt` | 8 | **new file** — Compose UI tests for `TileGrid` in `androidTest` |
| `StackAxisTest.kt` | 8 | **new file** — unit tests for `StackAxis.unitPoint()` and enum contract |
| `StackTest.kt` | 8 | **new file** — Compose UI tests for `Stack` in `androidTest`: validation guards, axis offsets, negative gap, nesting |
| `TileGridExample.kt` | 8 | **new file** — interactive tile selector sample (10×10 grid, tap to highlight) |
| `StackExample.kt` | 8 | **new file** — multi-axis demonstration: Z tower, X row, Y row, Stack nested inside TileGrid |

---

## Notes

**`TileCoordinate` is not a `data class`**: Consistent with WS1b's ruling on `Point`, `Vector`, `PreparedScene`, and `RenderCommand`. Binary stability, controlled copy semantics, and safe future field addition all apply here.

**`screenToTile` uses `elevation = 0.0` always in `TileGestureHub.dispatch`**: This is a known, documented limitation for elevated terrain. Correct behavior for variable-height terrain requires knowing which layer was tapped before projecting — a chicken-and-egg problem that requires a different interaction model. The flat-grid assumption handles the vast majority of use cases. The Layer 3 escape hatch (`LocalIsometricEngine` + `screenToTile` in `GestureConfig.onTap`) is the documented solution.

**`TileGrid` does not accept a `key` parameter**: Unlike `ForEach`, the key for tiles is always the `TileCoordinate` itself — there is no meaningful alternative. Exposing a `key` parameter would add noise without benefit (guideline §2: avoid advanced knobs leaking into beginner workflows).

**Multi-grid scenes**: Multiple `TileGrid` composables in a single scene are fully supported. The `TileGestureHub` dispatches each tap to all registrations independently, and each bounds-checks against its own grid dimensions. Grids with overlapping world footprints will both fire for taps in the overlapping region — callers who need priority ordering should use the Layer 3 escape hatch.

**`onTileHover`**: Not included in this workstream. Hover is a pointer-device concept not naturally modeled on touch screens. A future workstream can add `onTileHover: ((TileCoordinate?) -> Unit)?` to `TileGrid` and extend `TileGestureHub` to handle `PointerEventType.Move` events — it fits the architecture without structural changes.

**`LocalIsometricEngine` does not need to be added**: It already exists in `CompositionLocals.kt` and is already provided by `IsometricScene`. Earlier drafts of this plan incorrectly listed it as a WS6 future dependency. The Layer 3 escape hatch in Step 6 uses it as-is.

**`Stack` has no tap routing**: Unlike `TileGrid`, `Stack` does not route tap events to individual slots. Tap routing in a 1D stack is not well-defined — slots occupy a narrow on-screen band and overlap heavily in the isometric projection (e.g., prisms in a Z-axis tower project to the same X range on screen). Users who need per-slot interaction should use `GestureConfig.onTap` with hit testing against `RenderCommand.ownerNodeId` via `CustomNode`, or model the scene as a `TileGrid` with a single-column layout.

**`Stack` does not accept variable gap**: All slots are equally spaced. Users who need non-uniform spacing (e.g., items of varying height stacked flush) should use `ForEach` with a running `zOffset` accumulator — this is the documented Layer 3 escape hatch in Step 7.

**`StackAxis` is independent of `TileCoordinate` and `TileGrid`**: The enum and the `Stack` composable have no compile-time dependency on Steps 1–6. `Stack` depends only on `ForEach` and `Group`, both of which predate WS9. This means Step 7 can be implemented in parallel with Steps 3–6 once Steps 1–2 (core types and extensions) are merged.

**`Stack` and `TileGrid` compose naturally**: A `Stack` inside a `TileGrid` content block produces a tower at each tile position, because `Stack`'s Groups accumulate on top of the tile's `Group` transform. The `StackExample.kt` sample demonstrates this explicitly and serves as an integration test for the interaction between the two composables.

**`onStackItemHover`**: Not included. Same rationale as `onTileHover` — hover is not a natural touch-screen concept. A future workstream can add it alongside `TileGrid` hover support without structural changes to `Stack`.

**`Stack` vs. `Group` with `ForEach`**: `Stack` is not strictly necessary — every `Stack` can be expressed with `ForEach` + `Group`. Its value is documentation of intent. `Stack(count = 5, axis = StackAxis.Z, gap = 1.0)` communicates "this is a vertical arrangement of 5 equal-height slots" immediately; the `ForEach`/`Group` equivalent requires reading the position math to infer the same intent. This is the same trade-off as `TileGrid` vs. `ForEach` + `Group`.
