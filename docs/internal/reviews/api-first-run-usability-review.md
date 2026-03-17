# API Usability Review

> **Date**: 2026-03-13
> **Scope**: Isometric Compose Runtime API (`IsometricScene`, composables, core types)
> **Verdict**: Weak — the composable DSL is well-layered, but the entry point and core types have significant usability gaps

---

## 1. Executive Summary

The library's DSL structure is sound — scoped composables inside `IsometricScene`, declarative shape placement, Compose-native lifecycle. But the execution has problems at ten levels:

1. **Entry point**: `IsometricScene` dumps 21 parameters (including 4 benchmark-only) into a single flat signature with no layering. Beginners see `spatialIndexCellSize` and `forceRebuild` next to `modifier` and `content`. Two additional benchmark-only types (`RuntimeFlagSnapshot`, `RenderBenchmarkHooks`) are public in the same packages as core API.
2. **Core types**: `IsoColor` forces a parallel color system with no Compose bridge discoverable at the call site. Shape constructors accept invalid inputs silently. Two `IsometricScope` types with the same name cause import ambiguity.
3. **Progressive disclosure**: No structural separation exists between beginner, intermediate, and advanced concerns. Advanced users are partially underserved — no access to `IsometricEngine`/`IsometricRenderer`, custom nodes are possible via the public `IsometricNode`/`IsometricApplier` types but undocumented, no programmatic camera.
4. **Naming**: The same concept uses different names across the API (`origin` vs `position`, `i/j/k` vs `x/y/z`, `dx/dy/dz` for both dimensions and displacement). Boolean flags use four prefix patterns (`enable*`, `draw*`, `use*`, `force*`). Implementation details leak into public names (`enablePathCaching`, `spatialIndexCellSize`, `forceRebuild`).
5. **Readability**: The most common call site — `Shape(shape = Prism(Point(0.0, 0.0, 0.0), 4.0, 5.0, 2.0))` — is tautological (`shape = ...` inside `Shape`), has three unlabeled positional doubles, and requires three nesting levels. Generic method names (`prepare()`, `invalidate()`) hide intent. Boolean pairs like `reverseSort = true, useRadius = true` are opaque.
6. **Invalid state prevention**: Nearly every constructor accepts degenerate inputs silently — zero/negative dimensions, NaN coordinates, empty path lists, contradictory flag combinations. The old `IsometricCanvas` API has a critical bug where shapes accumulate on every recomposition. `useNativeCanvas = true` on non-Android crashes at render time instead of construction time. Hit-test IDs use hash codes that can collide.
7. **Modularity**: `IsometricEngine` (491 lines) is a god object owning scene state, projection, lighting, culling, depth sorting, and hit testing with a convex hull algorithm. `IsometricRenderer` (670 lines) mixes two rendering backends (Compose + Android native), cache management, spatial indexing, hit-test resolution, and benchmark hooks. Path/color conversion logic is duplicated in three locations. No interfaces exist — the renderer cannot be tested without a real engine. The `:lib` legacy module coexists with no boundary.
8. **Escape hatches**: The compose layer acts as an opaque wall around every useful internal. The projection matrix, `PreparedScene`, `DrawScope`, `RenderContext`, and spatial index are all locked inside `IsometricScene` with no access. There is no world-to-screen coordinate conversion. No render pipeline hooks exist beyond benchmark timing callbacks. Custom `IsometricNode` subclasses are technically possible but require raw `ComposeNode` calls with no DSL support. The only way to access engine output is to drop out of the compose layer entirely and use `IsometricEngine` imperatively — there is no gradual "drop-down" path.
9. **Language fit**: Several patterns fight Kotlin and Compose idioms. All `CompositionLocal` providers use `compositionLocalOf` (recomposition-tracking) when they should be `staticCompositionLocalOf` (values change rarely). `SideEffect` is used for listener wiring that needs `DisposableEffect`. Math types (`Point`, `Vector`) lack operator overloads — the canonical Kotlin use case. Shape/Path subclasses exist only for constructor convenience (no added behavior), fighting Kotlin's factory-function idiom. `Shape.orderedPaths()` hand-rolls a bubble sort. Extension functions inside `object` bodies require `with()` dispatch. Java collection constructors (`HashMap()`, `ArrayList()`) appear instead of Kotlin factories.
10. **Evolvability**: The API has multiple binary-compatibility traps that will break compiled consumers on any addition. All core types (`Point`, `Vector`, `RenderContext`, `RenderCommand`) are `data class` — adding a field changes `copy()`, `componentN()`, `equals`/`hashCode`, and the synthetic constructor. Gesture callbacks are fixed-arity lambdas (`(Double, Double) -> Unit`) that cannot be extended without breaking every call site. Shape/Path transform methods return `Shape`/`Path` base types, losing subtype information and requiring casts. `IsometricNode.children` is a public `MutableList` that bypasses the Compose Applier's consistency guarantees. The depth formula hardcodes a 30° projection angle, silently producing wrong results for any other value.

The composable layer (`Group`, `If`, `ForEach`, named transforms) is the bright spot: clean hierarchy, self-documenting intent, consistent naming across all four composables.

---

## 2. Hero Scenario

**"I want to render a 3D isometric box on screen in my Compose app."**

A game developer, creative coder, or UI experimenter wants to see an isometric shape — just to verify the library works and understand the coordinate system. Everything else (animation, groups, hit testing, physics) comes later.

### Minimal working code today

```kotlin
IsometricScene {
    Shape(
        shape = Prism(Point(0.0, 0.0, 0.0), 1.0, 1.0, 1.0),
        color = IsoColor(33.0, 150.0, 243.0)
    )
}
```

**Concepts required**: `IsometricScene`, `Shape` (composable), `Prism`, `Point`, `IsoColor` — 5 types before seeing a single pixel.

### Ideal beginner code (after proposed changes)

```kotlin
IsometricScene(modifier = Modifier.fillMaxSize()) {
    Shape(shape = Prism())  // blue cube at origin — all defaults
}
```

**Concepts required**: 3 — `IsometricScene`, `Shape`, `Prism`.

---

## 3. Findings

Every finding includes a severity, evidence, and a proposed fix. Findings are grouped by theme rather than listed by discovery order.

### 3.1 Entry Point & Progressive Disclosure

#### F1. `IsometricScene` has 21 parameters with no layering — HIGH

The primary overload exposes beginner params (`modifier`, `content`), intermediate params (`onTap`, `lightDirection`, `colorPalette`), renderer tuning (`enablePathCaching`, `spatialIndexCellSize`), and benchmark infrastructure (`forceRebuild`, `frameVersion`, `onHitTestReady`, `onFlagsReady`) in a single flat signature. The simplified 5-param overload hard-codes `enableGestures = false`, stripping all gesture support rather than just hiding advanced knobs.

Four of the 21 parameters exist exclusively for the benchmark harness and are never used in production:

| Parameter | Purpose |
|-----------|---------|
| `forceRebuild` | Disable caching per-frame for cache-miss benchmarks |
| `frameVersion` | Force Canvas invalidation for static-scene benchmarks |
| `onHitTestReady` | Expose hit-test function to benchmark runner |
| `onFlagsReady` | Report runtime flag state to benchmark validation |

Two additional benchmark-only types live in the public API surface: `RuntimeFlagSnapshot` (data class at `IsometricScene.kt:30-38`, used only by `onFlagsReady`) and `RenderBenchmarkHooks` (interface at `IsometricRenderer.kt:27`, consumed via `LocalBenchmarkHooks` CompositionLocal). Neither is used in production, but both appear as importable public types in the same packages as core API.

**Evidence**: `IsometricScene.kt:75-97` (full 21-param signature), `IsometricScene.kt:324-339` (simplified overload disables gestures), `IsometricScene.kt:30-38` (`RuntimeFlagSnapshot`), `IsometricRenderer.kt:27` (`RenderBenchmarkHooks`), `CompositionLocals.kt:71` (`LocalBenchmarkHooks`)

**Fix**: Restructure into a 2-overload API with config objects for progressive disclosure. The beginner overload exposes only `modifier`, `defaultColor`, and `content`. The full overload adds `SceneConfig` (visual styling), `AdvancedSceneConfig` (renderer tuning, engine access, benchmark hooks), and gesture callbacks. See [section 4.1](#41-2-overload-isometricscene-redesign) for the full redesign.

#### F2. No access to `IsometricEngine` or `IsometricRenderer` — HIGH

Both are created inside `IsometricScene` via `remember {}` (lines 100-108) and never exposed. An advanced user who needs world→screen coordinate conversion, spatial-index inspection, custom tree traversal, or external animation integration has no escape hatch.

**Evidence**: `IsometricScene.kt:100-108` (local variables, never exposed)

**Fix**: Add `onEngineReady` and `onRendererReady` callbacks in `AdvancedSceneConfig`:

```kotlin
data class AdvancedSceneConfig(
    // ...existing renderer tuning...
    val onEngineReady: ((IsometricEngine) -> Unit)? = null,
    val onRendererReady: ((IsometricRenderer) -> Unit)? = null,
)
```

Wire them in `IsometricScene` via `SideEffect`:

```kotlin
SideEffect {
    advancedConfig.onEngineReady?.invoke(engine)
    advancedConfig.onRendererReady?.invoke(renderer)
}
```

#### F3. Custom `IsometricNode` extension is possible but undiscoverable — MEDIUM

`IsometricApplier` is public, `IsometricNode` is an abstract class explicitly documented as "Open for extension to support custom node types via low-level ComposeNode primitives" (`IsometricNode.kt:12`), and all concrete node types (`ShapeNode`, `GroupNode`, `PathNode`, `BatchNode`) are public. An advanced user *can* technically write `ComposeNode<MyNode, IsometricApplier>(...)` — the escape hatch exists at the type level.

However, no documentation, example, or convenience composable demonstrates this pattern. A user looking at the provided composables (`Shape`, `Group`, `Path`, `Batch`) sees only hard-coded node types and has no indication that custom nodes are supported. The `IsometricScopeImpl` singleton is `internal`, so wiring child content into a custom node requires knowing the undocumented incantation `IsometricScopeImpl.content()`.

**Evidence**: `IsometricNode.kt:12-14` (KDoc says "Open for extension"), `IsometricApplier.kt:11` (public class), `IsometricScope.kt:19` (`IsometricScopeImpl` is internal)

**Fix**: Add a `CustomNode` composable that surfaces the existing escape hatch as a first-class API and documents the pattern:

```kotlin
@IsometricComposable
@Composable
fun <T : IsometricNode> IsometricScope.CustomNode(
    factory: () -> T,
    update: (T) -> Unit,
    content: (@Composable IsometricScope.() -> Unit)? = null
) {
    if (content != null) {
        ComposeNode<T, IsometricApplier>(
            factory = factory,
            update = { update(this) },
            content = { IsometricScopeImpl.content() }
        )
    } else {
        ComposeNode<T, IsometricApplier>(
            factory = factory,
            update = { update(this) }
        )
    }
}
```

This lowers the barrier from "read the library source to discover that `IsometricApplier` is public and `IsometricNode` is extensible" to "find `CustomNode` in autocomplete."

> **See also**: F58 (full `CustomNode` composable design), F61 (`LocalIsometricEngine` for custom composables).

#### F4. No per-shape render options — LOW

`RenderOptions` is scene-level. An advanced user cannot disable depth sorting for a known-good pre-sorted batch while keeping it for the rest of the scene.

**Evidence**: `IsometricScene.kt:77` (single `renderOptions` for entire scene)

**Fix**: Add an optional `renderOptions: RenderOptions?` param to `Group` and `Batch` composables. When non-null, it overrides the scene-level options for that subtree. When null (default), inherits from the parent. This can be deferred to a future release.

#### F5. Gesture system is all-or-nothing — LOW

`enableGestures: Boolean` is a single toggle. No way to enable tap without drag, customize the 8px drag threshold, or add pinch-to-zoom. Four separate lambdas (`onTap`, `onDragStart`, `onDrag`, `onDragEnd`) are the only gesture surface.

**Evidence**: `IsometricScene.kt:216-277` (hard-coded 8px threshold, single-pointer only)

**Fix**: Replace the four lambdas and boolean with a nullable structured event callback. Gestures are disabled when null:

```kotlin
sealed class GestureEvent {
    data class Tap(val x: Double, val y: Double, val node: IsometricNode?) : GestureEvent()
    data class DragStart(val x: Double, val y: Double) : GestureEvent()
    data class Drag(val deltaX: Double, val deltaY: Double) : GestureEvent()
    object DragEnd : GestureEvent()
}

// In IsometricScene L2 overload:
onGesture: ((GestureEvent) -> Unit)? = null  // null = gestures disabled
```

For advanced gesture configuration (thresholds, multi-touch), a `GestureConfig` class can be added later without breaking the API.

> **See also**: F72 (fixed-arity lambda callbacks cannot be extended), F22 (gestures enabled by default), F41 (contradictory gesture flag combinations).

### 3.2 Core Type Construction

#### F6. `IsoColor` has no discoverable Compose `Color` interop — HIGH

Every Compose developer has colors as `Color.Blue`, `Color(0xFF2196F3)`, or `MaterialTheme.colorScheme.primary`. The library forces them into a parallel system with `Double` channel values 0-255.

A `Color.toIsoColor()` extension **already exists** but is buried as a member extension inside `ComposeRenderer`, requiring the undiscoverable import `import ...ComposeRenderer.toIsoColor`. No beginner will find this.

**Evidence**: `ComposeRenderer.kt:66` (member extension, not top-level)

**Fix**: Promote to a top-level public extension in a new file:

```kotlin
// New file: isometric-compose/.../ColorExtensions.kt
package io.fabianterhorst.isometric.compose

fun Color.toIsoColor(): IsoColor = IsoColor(
    r = (red * 255).toDouble(),
    g = (green * 255).toDouble(),
    b = (blue * 255).toDouble(),
    a = (alpha * 255).toDouble()
)

fun IsoColor.toComposeColor(): Color = Color(
    red = (r / 255).toFloat(),
    green = (g / 255).toFloat(),
    blue = (b / 255).toFloat(),
    alpha = (a / 255).toFloat()
)
```

#### F7. `IsoColor` constructor requires `Double`, lacks hex/Int entry points — HIGH

- No hex constructor (`IsoColor.fromHex(0x2196F3)`)
- No `Int` constructor (`IsoColor(33, 150, 243)`) — must use `Double`
- Only 5 named constants (`RED`, `GREEN`, `BLUE`, `WHITE`, `BLACK`)
- No common colors: `GRAY`, `ORANGE`, `PURPLE`, `CYAN`, `YELLOW`
- No input validation — `IsoColor(-10.0, 300.0, 0.0)` silently produces broken HSL values

**Evidence**: `IsoColor.kt` — no `Int` constructor, no `fromHex()`, no `require()` guards

**Fix**: Add an `Int` constructor overload, `fromHex()` factory, additional named constants, and channel validation:

```kotlin
data class IsoColor(val r: Double, val g: Double, val b: Double, val a: Double = 255.0) {
    init {
        require(r in 0.0..255.0) { "r must be in 0..255, got $r" }
        require(g in 0.0..255.0) { "g must be in 0..255, got $g" }
        require(b in 0.0..255.0) { "b must be in 0..255, got $b" }
        require(a in 0.0..255.0) { "a must be in 0..255, got $a" }
    }

    constructor(r: Int, g: Int, b: Int, a: Int = 255)
        : this(r.toDouble(), g.toDouble(), b.toDouble(), a.toDouble())

    companion object {
        // Existing
        val WHITE = IsoColor(255.0, 255.0, 255.0)
        val BLACK = IsoColor(0.0, 0.0, 0.0)
        val RED = IsoColor(255.0, 0.0, 0.0)
        val GREEN = IsoColor(0.0, 255.0, 0.0)
        val BLUE = IsoColor(0.0, 0.0, 255.0)

        // New
        val GRAY = IsoColor(158, 158, 158)
        val DARK_GRAY = IsoColor(97, 97, 97)
        val LIGHT_GRAY = IsoColor(224, 224, 224)
        val CYAN = IsoColor(0, 188, 212)
        val ORANGE = IsoColor(255, 152, 0)
        val PURPLE = IsoColor(156, 39, 176)
        val YELLOW = IsoColor(255, 235, 59)
        val BROWN = IsoColor(121, 85, 72)

        fun fromHex(hex: Long): IsoColor {
            val hasAlpha = hex > 0xFFFFFFL
            val a = if (hasAlpha) ((hex shr 24) and 0xFF).toDouble() else 255.0
            val r = ((hex shr 16) and 0xFF).toDouble()
            val g = ((hex shr 8) and 0xFF).toDouble()
            val b = (hex and 0xFF).toDouble()
            return IsoColor(r, g, b, a)
        }
    }
}
```

#### F8. Legacy `Color` class still importable — LOW

The deprecated `Color` class at `io.fabianterhorst.isometric.Color` is still importable. Its `lighten()` method returns `Color` (not `IsoColor`), so IDE-suggested migration hits a compile error if the user calls `.lighten()`.

**Evidence**: `Color.kt` (deprecated but still public, returns `Color` from operations)

**Fix**: Delete `Color.kt` entirely. Add `lighten()`/`darken()` to `IsoColor` if not already present, so all functionality is available on the current type.


#### F9. Zero input validation on shape constructors — HIGH

Bad values silently produce broken or invisible geometry:

| Input | Result |
|-------|--------|
| `Prism(origin, dx = -1.0)` | Inside-out box, invisible after backface culling |
| `Cylinder(origin, vertices = 0)` | Empty path, likely `IndexOutOfBoundsException` at render |
| `Cylinder(origin, radius = -1.0)` | Inverted circle, broken geometry |
| `Stairs(origin, stepCount = 0)` | Degenerate 2-point path, nothing visible |
| `Stairs(origin, stepCount = -1)` | Division by negative, NaN-adjacent coordinates |
| `Shape.extrude(path, height = 0.0)` | Collapsed faces on same plane |

**Evidence**: `Prism.kt`, `Cylinder.kt`, `Stairs.kt`, `Circle.kt` — no `require()` guards

**Fix**: Add `require()` checks to all shape constructors:

```kotlin
class Prism(origin: Point = Point.ORIGIN, dx: Double = 1.0, dy: Double = 1.0, dz: Double = 1.0) {
    init {
        require(dx > 0.0) { "Prism dx must be positive, got $dx" }
        require(dy > 0.0) { "Prism dy must be positive, got $dy" }
        require(dz > 0.0) { "Prism dz must be positive, got $dz" }
    }
}

class Cylinder(origin: Point = Point.ORIGIN, radius: Double = 1.0, vertices: Int = 20, height: Double = 1.0) {
    init {
        require(radius > 0.0) { "Cylinder radius must be positive, got $radius" }
        require(vertices >= 3) { "Cylinder needs at least 3 vertices, got $vertices" }
        require(height > 0.0) { "Cylinder height must be positive, got $height" }
    }
}

class Stairs(origin: Point = Point.ORIGIN, stepCount: Int) {
    init {
        require(stepCount >= 1) { "Stairs needs at least 1 step, got $stepCount" }
    }
}

class Pyramid(origin: Point = Point.ORIGIN, dx: Double = 1.0, dy: Double = 1.0, dz: Double = 1.0) {
    init {
        require(dx > 0.0) { "Pyramid dx must be positive, got $dx" }
        require(dy > 0.0) { "Pyramid dy must be positive, got $dy" }
        require(dz > 0.0) { "Pyramid dz must be positive, got $dz" }
    }
}
```

#### F10. Shape constructors require explicit `Point` origin — MEDIUM

Every shape requires `origin: Point` with no default. `Point.ORIGIN` exists but constructors don't default to it, so `Prism()` doesn't compile. All examples repeat `Point(0.0, 0.0, 0.0)`.

**Evidence**: `Prism.kt`, `Pyramid.kt`, `Cylinder.kt`, `Stairs.kt`, `Octahedron.kt` — no default for `origin`

**Fix**: Default `origin = Point.ORIGIN` on all shape constructors:

```kotlin
class Prism(origin: Point = Point.ORIGIN, dx: Double = 1.0, dy: Double = 1.0, dz: Double = 1.0)
class Pyramid(origin: Point = Point.ORIGIN, dx: Double = 1.0, dy: Double = 1.0, dz: Double = 1.0)
class Cylinder(origin: Point = Point.ORIGIN, radius: Double = 1.0, vertices: Int = 20, height: Double = 1.0)
class Octahedron(origin: Point = Point.ORIGIN)
class Stairs(origin: Point = Point.ORIGIN, stepCount: Int)
```

#### F11. `Knot` has a documented depth-sort bug — MEDIUM

`Knot.kt` line 9: "Note: needs depth sorting fix as per original TODO". The shape uses magic offsets and ignores `origin` for internal positioning. It's a ported JavaScript demo artifact, not a production primitive. The README lists it alongside `Prism`/`Cylinder` as an available shape.

**Evidence**: `Knot.kt:9` (KDoc documents its own bug)

**Fix**: Mark as experimental so users opt-in knowingly:

```kotlin
@RequiresOptIn(message = "Knot has known depth-sorting issues and is provided as a demo shape.")
annotation class ExperimentalIsometricShape

@ExperimentalIsometricShape
class Knot(origin: Point = Point.ORIGIN) : Shape(createPaths(origin))
```

### 3.3 Naming, Imports & Consistency

#### F12. Two incompatible `IsometricScope` types in the same artifact — CRITICAL

Two interfaces named `IsometricScope` exist in the same module:

- `io.fabianterhorst.isometric.compose.IsometricScope` — the old `IsometricCanvas` scope with `add()`/`clear()` methods
- `io.fabianterhorst.isometric.compose.runtime.IsometricScope` — the new runtime scope (marker interface for composable extensions)

If a user imports both packages, the compiler reports an ambiguous reference. The runtime composables (`Group`, `Batch`, `ForEach`, `If`) are extension functions on the runtime scope — they are invisible inside `IsometricCanvas`. A user who starts with the old API and tries to use `Group {}` gets no autocomplete, no error — just silence.

**Evidence**: `IsometricCanvas.kt:102`, `IsometricScope.kt:10`

**Fix**: Rename the old-API scope directly:

```kotlin
// IsometricCanvas.kt — rename to disambiguate
interface IsometricCanvasScope {
    fun add(shape: Shape, color: IsoColor)
    fun add(path: Path, color: IsoColor)
    fun clear()
}
```

Update all call sites from `IsometricScope` to `IsometricCanvasScope`.

#### F13. `IsometricCanvas` content lambda is not `@Composable` — CRITICAL

`IsometricCanvas` declares `content: IsometricScope.() -> Unit` (not `@Composable`). The lambda is called directly at `scope.content()` (line 43). If a beginner writes `remember {}` or `LaunchedEffect {}` inside the block, they get a cryptic runtime error. Nothing in the KDoc warns about this. The README presents both APIs with equal weight.

**Evidence**: `IsometricCanvas.kt:39` (`content: IsometricScope.() -> Unit`), line 43 (`scope.content()`)

**Fix**: Remove `IsometricCanvas` entirely. The runtime `IsometricScene` API is the only entry point. Delete `IsometricCanvas.kt` and update any samples or tests that reference it.

#### F14. `Shape` composable name clashes with `Shape` class — MEDIUM

`Shape(shape = Prism(...))` — the function and the parameter type share the name `Shape`. Import collisions between `io.fabianterhorst.isometric.Shape` and the composable are possible. Since both are used in the same call site, the user must import both, and IDE auto-import may pick the wrong one.

**Evidence**: `IsometricComposables.kt:33` (composable `Shape`), `io.fabianterhorst.isometric.Shape` (core class)

**Fix**: This is a known trade-off from Compose convention (e.g., `Text` composable vs `Text` type). No rename needed, but the composable is an extension function on `IsometricScope`, which helps disambiguation. Consider adding a KDoc note:

```kotlin
/**
 * Add a 3D shape to the isometric scene.
 *
 * Note: this is a composable function, not the [io.fabianterhorst.isometric.Shape] class.
 * Import both: `import ...Shape` (class) and `import ...runtime.Shape` (composable).
 */
```


#### F26. `origin` (shape constructors) vs `position` (composables) — same concept, different names — HIGH

Every shape class (`Prism`, `Pyramid`, `Cylinder`, `Stairs`, `Knot`, `Octahedron`, `Circle`) names its placement parameter `origin`. Every runtime composable (`Shape`, `Group`, `Path`, `Batch`) names the equivalent concept `position`. A user constructing `Prism(origin = Point(1, 0, 0))` and placing it with `Shape(position = Point(2, 0, 0))` must mentally map two words to one concept.

Additionally, `rotationOrigin` and `scaleOrigin` in composables reuse the word "origin" to mean "pivot point" — a third meaning. The word "origin" now means:
1. Shape placement point (shape constructors)
2. Transform pivot point (composable params)
3. The world zero point (`Point.ORIGIN`)

**Evidence**: `shapes/Prism.kt`, `shapes/Pyramid.kt`, etc. (`origin: Point`), `IsometricComposables.kt` (`position: Point`, `rotationOrigin: Point?`, `scaleOrigin: Point?`)

**Fix**: Rename shape constructors' `origin` → `position` to match the composable layer. Rename `rotationOrigin`/`scaleOrigin` → `rotationPivot`/`scalePivot` to disambiguate from both:

```kotlin
// Shape constructors:
class Prism(val position: Point = Point.ORIGIN, val dx: Double, ...)

// Composables:
fun Shape(shape: Shape, position: Point, rotationPivot: Point? = null, scalePivot: Point? = null, ...)
```

#### F27. `Vector` uses `i`/`j`/`k` while `Point` uses `x`/`y`/`z` — same coordinate axes, different names — HIGH

`Vector(i, j, k)` uses math basis-vector notation. `Point(x, y, z)` uses standard axis names. A user computing `Vector.fromTwoPoints(p1, p2)` gets a `Vector` where `p1.x` maps to `result.i` — the same coordinate axis has two names depending on the type. Every cross-type operation requires a mental name-swap.

**Evidence**: `Vector.kt` (`val i`, `val j`, `val k`), `Point.kt` (`val x`, `val y`, `val z`)

**Fix**: Rename `Vector` components to `x`, `y`, `z`:

```kotlin
data class Vector(val x: Double, val y: Double, val z: Double)
```

The math notation `i`/`j`/`k` is standard in physics/linear algebra but not in graphics APIs. Every mainstream graphics library (OpenGL, Unity, Three.js, Compose `Offset`) uses `x`/`y`/`z`.

> **See also**: F65 (operator overloads on `Point`/`Vector`), F52 (`transformColor()` duplicates `Vector` math) — the rename enables these follow-on improvements.

#### F28. `dx`/`dy`/`dz` means "dimensions" in shapes but "displacement" in transforms — MEDIUM

`Prism(origin, dx, dy, dz)` uses `dx`/`dy`/`dz` to mean width/depth/height (extent dimensions). `Point.translate(dx, dy, dz)` uses the same names to mean offset/displacement. The same abbreviation has two unrelated meanings across the API.

**Evidence**: `shapes/Prism.kt` (`dx`, `dy`, `dz` = dimensions), `Point.kt:translate(dx, dy, dz)` = displacement

**Fix**: Rename shape dimension parameters to domain-appropriate names:

```kotlin
class Prism(val position: Point = Point.ORIGIN, val width: Double, val depth: Double, val height: Double)
class Pyramid(val position: Point = Point.ORIGIN, val width: Double, val depth: Double, val height: Double)
```

Keep `dx`/`dy`/`dz` in `translate()` where "delta" is the correct meaning.

#### F29. Boolean flag prefixes are inconsistent — four patterns for the same concept — MEDIUM

Boolean parameters use four different prefix conventions with no consistent rule:

| Pattern | Examples |
|---------|----------|
| `enable*` | `enableDepthSorting`, `enablePathCaching`, `enableSpatialIndex`, `enableGestures` |
| `draw*` | `drawStroke` |
| `use*` | `useNativeCanvas` |
| `force*` | `forceRebuild` |
| bare | `visible` |

A user cannot predict the name of a boolean flag without looking it up. `drawStroke` should be `enableStroke`; `useNativeCanvas` should be `enableNativeCanvas`; `forceRebuild` is benchmark-only (addressed by F1's `AdvancedSceneConfig`).

**Evidence**: `RenderOptions.kt` (4× `enable*`), `IsometricScene.kt:78-88` (mixed `enable*`, `draw*`, `use*`, `force*`), `IsometricComposables.kt` (`visible` — bare)

**Fix**: Standardize on `enable*` for all public on/off toggles. Keep `visible` as-is on composables (Compose convention for visibility). Keep `is*` for properties (Kotlin convention):

```kotlin
// IsometricScene / SceneConfig:
enableStroke: Boolean = true        // was: drawStroke
// AdvancedSceneConfig:
enableNativeCanvas: Boolean = false // was: useNativeCanvas
```

#### F30. `IsoColor` uses `Iso` abbreviation — nothing else in the library does — MEDIUM

Every type uses the full word `Isometric` (`IsometricEngine`, `IsometricScene`, `IsometricNode`, `IsometricScope`, `IsometricRenderer`, `IsometricApplier`). Only `IsoColor` abbreviates to `Iso`. The name was chosen to avoid clashing with the legacy `Color` class.

**Evidence**: `IsoColor.kt` (KDoc: "Renamed from Color to IsoColor to avoid conflicts")

**Fix**: Once the legacy `Color.kt` is deleted (F8), the namespace conflict is gone. Rename `IsoColor` → `IsometricColor` for consistency. Alternatively, keep `IsoColor` and accept the inconsistency — the short name is more ergonomic for a type used in every shape call. Either choice is defensible; what matters is a conscious decision.

#### F31. `options` parameter in `engine.prepare()` vs `renderOptions` everywhere else — LOW

`IsometricEngine.prepare()` names the parameter `options: RenderOptions`. Every other reference in the codebase — `IsometricScene`, `RenderContext`, `CompositionLocals` — uses `renderOptions`. The inconsistency means `engine.prepare(options = renderOptions)` reads oddly.

**Evidence**: `IsometricEngine.kt:prepare()` (`options`), `IsometricScene.kt:77` (`renderOptions`), `RenderContext.kt` (`renderOptions`)

**Fix**: Rename the parameter in `engine.prepare()` to `renderOptions`.

#### F32. `viewportWidth`/`viewportHeight` vs bare `width`/`height` — same concept, two patterns — LOW

`PreparedScene` uses `viewportWidth`/`viewportHeight`. `RenderContext`, `IsometricRenderer`, `IsometricScene`, and `engine.prepare()` all use bare `width`/`height` for the same pixel dimensions.

**Evidence**: `PreparedScene.kt` (`viewportWidth`, `viewportHeight`), `RenderContext.kt` (`width`, `height`)

**Fix**: Standardize on bare `width`/`height` in `PreparedScene` to match every other usage. The "viewport" qualifier adds nothing — these types are already in a rendering context where `width`/`height` unambiguously refers to the viewport.

#### F33. `id` vs `nodeId` vs `ownerNodeId` — inconsistent ID naming — LOW

- `RenderCommand.id` — a command identifier
- `IsometricNode.nodeId` — a node identifier
- `RenderCommand.ownerNodeId` — the node that produced the command

The ID naming is ad-hoc. `RenderCommand.id` should be `commandId` for the same reason that `IsometricNode` uses `nodeId` — both qualify the type of ID.

**Evidence**: `RenderCommand.kt` (`id`, `ownerNodeId`), `IsometricNode.kt` (`nodeId`)

**Fix**: Rename `RenderCommand.id` → `commandId`. Keep `nodeId` and `ownerNodeId` as-is.

### 3.4 Configuration & Documentation

#### F15. `RenderOptions.Quality` is misleading — MEDIUM

`Quality` disables backface culling and bounds checking. A beginner choosing "Quality" for best rendering gets **slower performance and more z-fighting**. `Default` is actually the best preset for most cases.

**Evidence**: `RenderOptions.kt:43-48`

**Fix**: Rename to describe behavior, not subjective quality:

```kotlin
companion object {
    val Default = RenderOptions()

    val NoCulling = RenderOptions(
        enableDepthSorting = true,
        enableBackfaceCulling = false,
        enableBoundsChecking = false
    )

    val NoDepthSort = RenderOptions(
        enableDepthSorting = false,
        enableBackfaceCulling = true,
        enableBoundsChecking = true
    )

}
```

#### F16. Coordinate system undocumented — HIGH

A beginner cannot determine which direction +X, +Y, +Z go on screen. The only description is inside `IsometricEngine.translatePoint()` (a `private` method). The README shows a screenshot with a vague caption ("The blue grid is the xy-plane") but no axis labels.

**Evidence**: `IsometricEngine.kt:202-203` (private method, only coordinate system description)

**Fix**: Add a coordinate system section to `RUNTIME_API.md` and to the `IsometricScene` KDoc:

```
Coordinate System:
  +X → screen lower-right (isometric east)
  +Y → screen lower-left (isometric south)
  +Z → screen up (vertical axis)
  Origin → center of the canvas
```

Include an ASCII diagram in the `IsometricScene` KDoc so it appears in IDE documentation popups.

#### F17. Animation samples use `delay(16)` instead of `withFrameNanos` — LOW

Every animation example uses `delay(16)` (a non-frame-locked timer that drifts). No example or doc mentions `withFrameNanos`, which is the correct Compose animation primitive. A beginner copying the sample will get jitter on real devices.

**Fix**: Update all animation examples to use `withFrameNanos`:

```kotlin
// Before (drifts, not frame-locked):
LaunchedEffect(Unit) {
    while (true) {
        delay(16)
        angle += 0.02
    }
}

// After (frame-locked, correct):
LaunchedEffect(Unit) {
    var lastFrameTime = 0L
    while (true) {
        withFrameNanos { frameTime ->
            val dt = if (lastFrameTime == 0L) 0f else (frameTime - lastFrameTime) / 1_000_000_000f
            lastFrameTime = frameTime
            angle += dt * 1.2f  // radians per second
        }
    }
}
```

#### F18. `color` param always specified in examples despite having a default — LOW

`Shape(shape, color)` defaults `color` to `LocalDefaultColor.current` (blue). But every example passes `color =` explicitly, hiding this convenience from beginners.

**Fix**: Update at least the first/simplest example in documentation to omit `color`, showing that `Shape(shape = Prism())` renders a visible blue shape with zero configuration.

#### F19. No programmatic camera/viewport control — LOW

The `IsometricEngine` controls the projection (scale, translation, rotation). Inside `IsometricScene`, there's no way to set or animate the camera. The only viewport interaction is through gestures.

**Fix**: Expose camera control via the `onEngineReady` escape hatch (see F2). For a more integrated solution, add a `CameraState` parameter:

```kotlin
class CameraState(
    initialScale: Double = 1.0,
    initialTranslation: Point = Point.ORIGIN
) {
    var scale by mutableStateOf(initialScale)
    var translation by mutableStateOf(initialTranslation)
}

// In IsometricScene:
val cameraState: CameraState = remember { CameraState() }
```

This can be deferred — `onEngineReady` is sufficient as a first step.

### 3.5 Readability in Real Code

**Readability verdict**: The composable DSL layer (`Group`, `If`, `ForEach`, named transforms) reads well. The shape construction layer and the `IsometricScene` entry point read poorly — tautological parameter names, opaque positional arguments, and generic verbs force readers to leave the code and open docs.

#### F34. `Shape(shape = ...)` and `Path(path = ...)` — tautological parameter names — HIGH

The most common call site in the entire runtime API reads:

```kotlin
Shape(
    shape = Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 2.0),
    color = IsoColor(33.0, 150.0, 243.0)
)
```

"Shape, shape equals…" is visual redundancy on every single usage. The same pattern exists for `Path(path = myPath, ...)`. This is distinct from F14 (type name collision) — the issue here is the parameter name `shape` inside a function called `Shape`, not the import ambiguity.

**Evidence**: `IsometricComposables.kt:33` (`fun IsometricScope.Shape(shape: Shape, ...)`), `IsometricComposables.kt:105` (`fun IsometricScope.Path(path: Path, ...)`)

**Fix**: Rename the parameter to `geometry` in both composables:

```kotlin
@Composable
fun IsometricScope.Shape(
    geometry: Shape,  // was: shape
    color: IsoColor = LocalDefaultColor.current,
    ...
)

@Composable
fun IsometricScope.Path(
    geometry: Path,  // was: path
    color: IsoColor = LocalDefaultColor.current,
    ...
)
```

Call sites now read `Shape(geometry = Prism(...))` — the function names the concept ("add a shape") and the parameter names the role ("the geometry to render"). Positional usage `Shape(Prism(...))` is unaffected since position doesn't change.

#### F35. Positional shape constructors — three unlabeled doubles — HIGH

Sample code throughout the codebase uses positional arguments for shape constructors:

```kotlin
add(Prism(Point(0.0, 0.0, 0.0), 4.0, 5.0, 2.0), IsoColor(33.0, 150.0, 243.0))
add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 2.0, 1.0), IsoColor(33.0, 150.0, 243.0))
add(Prism(Point(1.0, -1.0, 0.0), 2.0, 1.0, 1.0), IsoColor(33.0, 150.0, 243.0))
```

A reader sees three lines with different dimension triples but cannot determine which is width, depth, or height without checking the constructor signature. The parameters are named `dx`, `dy`, `dz` — which themselves are unclear (see F28). With six shapes in a scene, the reader is doing mental axis mapping on 18 values.

`IsoColor(33.0, 150.0, 243.0)` has the same problem: three `Double`s without any channel indicator. The fact that colors use 0–255 range but `Double` type adds confusion — a reader cannot tell if the range is 0–1 or 0–255.

**Evidence**: `ComposeActivity.kt:97-102` (MultipleShapesSample), `ComposeActivity.kt:104-133` (ComplexSceneSample), `RuntimeApiActivity.kt:97-114` (SimpleSample)

**Fix**: This is primarily a documentation/sample quality problem — F28 renames the parameters to `width`/`depth`/`height`, which makes named arguments self-documenting:

```kotlin
// After F28 rename — named arguments now read clearly:
Shape(geometry = Prism(width = 4.0, depth = 5.0, height = 2.0))
```

Update all samples and README code to use named arguments for shape constructors. Named arguments cost nothing at runtime and make every code review and diff self-documenting.

#### F36. `Cylinder` parameter order — rendering param between geometric params — MEDIUM

`Cylinder(origin, radius, vertices, height)` places the rendering-quality parameter `vertices` between two geometric parameters `radius` and `height`. A reader sees:

```kotlin
Cylinder(Point(-2.0, 0.0, 0.0), 0.5, 20, 2.0)
```

The `20` is a tessellation count (how many sides the circle approximation has), not a geometric dimension. It sits between `0.5` (radius) and `2.0` (height), breaking the reader's mental model of "geometric properties grouped together."

**Evidence**: `shapes/Cylinder.kt` constructor (`origin: Point, radius: Double, vertices: Int, height: Double`)

**Fix**: Reorder to `Cylinder(position, radius, height, vertices)` — geometric params first, rendering quality last:

```kotlin
class Cylinder(
    val position: Point = Point.ORIGIN,
    val radius: Double = 1.0,
    val height: Double = 1.0,
    val vertices: Int = 20   // rendering quality — last
)
```

The `vertices` default of 20 means most callers never specify it, and those who do are already in "advanced tuning" mode.

#### F37. `reverseSort` and `useRadius` in `findItemAt` — opaque booleans — MEDIUM

The full call site:

```kotlin
engine.findItemAt(
    preparedScene = scene,
    x = offset.x.toDouble(),
    y = offset.y.toDouble(),
    reverseSort = true,
    useRadius = true,
    radius = 8.0
)
```

`reverseSort = true` communicates nothing about intent. It means "search front-to-back so the frontmost shape wins" — but the word "reverse" tells the reader nothing about *what* is reversed or *why*. `useRadius = true` paired with a separate `radius = 8.0` is redundant — the boolean gates a feature that the radius value already controls.

**Evidence**: `IsometricEngine.kt:findItemAt()`, used in `IsometricRenderer.kt:hitTest()` and `IsometricCanvas.kt:handleTap()`

**Fix**: Replace the boolean pair with intent-revealing parameters:

```kotlin
fun findItemAt(
    preparedScene: PreparedScene,
    x: Double,
    y: Double,
    hitOrder: HitOrder = HitOrder.FRONT_TO_BACK,  // was: reverseSort
    touchRadius: Double = 0.0                       // was: useRadius + radius
): RenderCommand?

enum class HitOrder { FRONT_TO_BACK, BACK_TO_FRONT }
```

`touchRadius = 0.0` means exact point test; any positive value means fuzzy match. The boolean is eliminated. `hitOrder = HitOrder.FRONT_TO_BACK` reads as clear intent.

#### F38. Generic method names hide intent — `prepare()`, `invalidate()`, `clearDirty()` — MEDIUM

Three methods use verbs so generic that a reader cannot infer what they do:

| Current | What it does | Problem |
|---------|-------------|---------|
| `engine.prepare(...)` | Projects 3D shapes to 2D, depth-sorts, returns draw commands | "Prepare" says nothing about projection or sorting |
| `renderer.invalidate()` | Clears all internal caches (PreparedScene, path maps, spatial grid) | In Android/Compose, `invalidate()` means "schedule redraw" — this does the opposite (destroys cached work) |
| `node.clearDirty()` | Resets the dirty flag after rendering | `markClean()` is the natural antonym of `markDirty()` |

**Evidence**: `IsometricEngine.kt:prepare()`, `IsometricRenderer.kt:invalidate()`, `IsometricNode.kt:clearDirty()`

**Fix**:

```kotlin
// IsometricEngine:
fun projectScene(width: Int, height: Int, renderOptions: RenderOptions, lightDirection: Vector): PreparedScene

// IsometricRenderer:
fun clearCache()  // was: invalidate()

// IsometricNode:
fun markClean()   // was: clearDirty()
```

`projectScene` communicates the operation. `clearCache` is unambiguous. `markClean` is the natural inverse of `markDirty`.

#### F39. Nested construction depth — three levels typical, five in complex samples — LOW

A typical runtime API call requires three nesting levels:

```kotlin
Shape(                                            // level 1: composable
    shape = Prism(                                // level 2: shape constructor
        Point(0.0, 0.0, 0.0), 2.0, 2.0, 2.0     // level 3: point constructor
    ),
    color = IsoColor(33.0, 150.0, 243.0)          // level 2: color constructor
)
```

Complex samples reach five levels with chained transforms:

```kotlin
add(                                                          // level 1
    Pyramid(Point(2.0, 3.0, 3.0))                            // level 2+3
        .scale(Point(2.0, 4.0, 3.0), 0.5),                   // level 3 (second Point)
    IsoColor(180.0, 180.0, 0.0)                               // level 2
)
```

Multiple `Point` instances in one expression — one for position, one for scale origin — forces readers to track which point serves which role. The transform chaining (`.scale(Point(...), 0.5).rotateZ(Point(...), angle)`) mixes two unrelated pivot points into a single fluent chain.

**Evidence**: `ComposeActivity.kt:104-133` (ComplexSceneSample), `RuntimeApiActivity.kt:97-114` (SimpleSample)

**Fix**: The nesting is inherent to the domain (shapes have positions, positions have coordinates). The fix is to make each level more readable through the other naming fixes:

```kotlin
// After F26 (position default), F28 (width/depth/height), F34 (geometry param):
Shape(geometry = Prism(width = 2.0, depth = 2.0, height = 2.0))  // Point.ORIGIN is default

// After F37 (named transform pivots):
val pyramid = Pyramid(position = Point(2.0, 3.0, 3.0))
    .scale(pivot = Point(2.0, 4.0, 3.0), factor = 0.5)
Shape(geometry = pyramid, color = IsoColor.YELLOW)
```

Extracting chained transforms into a local variable reduces single-expression complexity.

### 3.6 Safety, Defaults & Validation

#### F20. `.fillMaxSize()` hardcoded in Canvas modifier — caller sizing silently overridden — HIGH

`IsometricScene` chains `.fillMaxSize()` after the user's `modifier`:

```kotlin
Canvas(
    modifier = modifier
        .fillMaxSize()  // <-- hardcoded, no opt-out
        .then(...)
)
```

In Compose, modifiers chain outer-to-inner (left-to-right). If the caller passes `Modifier.height(100.dp)`, the inner `.fillMaxSize()` overrides it by filling all remaining space. There is no escape hatch — the caller cannot prevent this. A user expecting `IsometricScene(modifier = Modifier.size(200.dp))` to produce a 200×200 canvas gets surprising behavior.

**Evidence**: `IsometricScene.kt:212-215`

**Fix**: Remove `.fillMaxSize()` from the internal chain. Let the caller control sizing entirely through their `modifier`. If no modifier is provided, the default `Modifier` already means "take the parent's constraints," which is correct for most layouts. Document that the caller should apply `.fillMaxSize()` if they want the scene to fill available space:

```kotlin
// Internal — just pass through the user's modifier
Canvas(
    modifier = modifier
        .then(if (enableGestures) gestureModifier else Modifier)
) { ... }
```

**Suggested KDoc wording**:
> The scene takes the size determined by [modifier]. Apply `Modifier.fillMaxSize()` to fill the parent, or `Modifier.size(...)` for a fixed-size scene.

#### F21. Canvas size initialized to 800×600 — stale first-frame render — MEDIUM

`canvasWidth` and `canvasHeight` are initialized to arbitrary defaults:

```kotlin
var canvasWidth by remember { mutableStateOf(800) }
var canvasHeight by remember { mutableStateOf(600) }
```

These are updated inside the Canvas draw lambda, but the `renderContext` is built outside it via `remember(canvasWidth, canvasHeight, ...)`. On frame 0:
1. Composition runs with `width=800, height=600`, building a stale `renderContext`
2. Draw lambda runs, updates `canvasWidth`/`canvasHeight` to real values
3. State update triggers recomposition, rebuilding `renderContext` correctly
4. Frame 1 draws correctly

The practical impact is a one-frame visual glitch: shapes are offset because `IsometricEngine.prepare()` centers using `width/2.0` and `height*0.9`. The `onHitTestReady` callback also fires with stale 800×600 dimensions before the real size is known.

**Evidence**: `IsometricScene.kt:131-132` (initial values), `IsometricScene.kt:294-295` (updated in draw), `IsometricScene.kt:135-142` (renderContext built from stale values)

**Fix**: Initialize to 0×0 (which is invalid for rendering) and skip the first render until real dimensions arrive:

```kotlin
var canvasWidth by remember { mutableStateOf(0) }
var canvasHeight by remember { mutableStateOf(0) }

// In Canvas draw lambda:
canvasWidth = size.width.toInt()
canvasHeight = size.height.toInt()
if (canvasWidth == 0 || canvasHeight == 0) return@Canvas  // skip until measured
```

This eliminates the first-frame glitch at the cost of a blank frame, which is the standard Compose pattern (e.g., `LazyColumn` also skips drawing until measured).

#### F22. Gestures enabled by default — hidden hit-test work on every tap — MEDIUM

`enableGestures` defaults to `true`, and `onTap` defaults to a no-op lambda `{ _, _, _ -> }`. This means:

1. The `pointerInput` coroutine is installed and running on every scene by default
2. On every `Release` event, a full hit test runs (`renderer.hitTest(...)`) even when the result is discarded by the no-op callback
3. The hit test walks the spatial index or performs a linear scan — non-trivial work for large scenes

The combination of "gestures on by default" + "no-op callbacks" means every scene silently pays the cost of gesture processing and hit testing without the user asking for it.

**Evidence**: `IsometricScene.kt:83` (`enableGestures: Boolean = true`), `IsometricScene.kt:92` (`onTap: ... = { _, _, _ -> }`), `IsometricScene.kt:260-268` (hit test runs unconditionally on Release)

**Fix**: Two options:

**(a) Default `enableGestures = false`** — gestures are opt-in. The simplified overload already does this, but the primary overload doesn't. This is the safest default: no hidden work.

**(b) Guard hit test on callback presence** — check whether the tap callback is the default no-op before running the hit test. This requires either a nullable `onTap` (null = no callback, skip hit test) or a sentinel check. The proposed redesign's `onTap: ((...) -> Unit)? = null` (nullable, null = disabled) already achieves this naturally.

**Suggested KDoc wording**:
> When [onTap] is non-null, a hit test runs on every tap to identify the touched node. For scenes with many shapes, this has non-trivial cost. Pass `null` to disable tap handling entirely.

#### F23. No error handling in render path — uncaught exceptions crash the frame — MEDIUM

Zero `try`/`catch` exists in the production rendering code. An exception thrown by `DrawScope.drawPath()`, `Shape.rotateZ()`, or any transform operation propagates uncaught into Compose's draw pass, crashing the application. `renderNative()` throws `NoClassDefFoundError` on non-Android platforms without catching it.

The only validation in the entire render pipeline is two `require()` calls:
- `IsometricRenderer` constructor: `require(spatialIndexCellSize > 0.0)`
- `RenderOptions`: `require(broadPhaseCellSize > 0.0)`

All other inputs (NaN coordinates, degenerate paths, empty shapes) pass through unchecked and may produce undefined behavior at draw time.

**Evidence**: `IsometricRenderer.kt` (no `try`/`catch` in `render()` or `renderNative()`), `IsometricApplier.kt:52,70,90` (three `error()` calls for programming-contract violations)

**Fix**: Wrap the draw loop in a minimal safety net:

```kotlin
// In IsometricRenderer.render():
commands.forEachIndexed { index, cached ->
    try {
        drawScope.drawPath(cached.path, cached.fill)
        if (drawStroke) drawScope.drawPath(cached.path, cached.stroke)
    } catch (e: Exception) {
        // Log once per session, skip the broken command
        // Prevents a single malformed shape from crashing the entire scene
    }
}
```

This should be a development/debug aid, not a production suppression pattern. In release builds, the validation from F9 (shape constructor `require()` guards) should prevent malformed geometry from reaching the renderer in the first place.

#### F24. `enableBroadPhaseSort` off by default, off in all presets, but not marked experimental — LOW

`RenderOptions.enableBroadPhaseSort` defaults to `false` and is set to `false` in every preset (`Default`, `Performance`, `Quality`) and in the benchmark `ALL_ON` flag set. The broad-phase sorting code may produce different depth ordering from the baseline O(n²) path due to a pair-order reversal in `IsometricEngine.buildBroadPhaseCandidatePairs()`.

Despite this, the parameter appears as a normal boolean with no KDoc warning that it's experimental or that results may differ from the baseline.

**Evidence**: `RenderOptions.kt:14` (defaults `false`), `RenderOptions.kt:33,43` (both presets set `false`), `BenchmarkFlags.kt:32` (`ALL_ON` sets `false`)

**Fix**: Add KDoc and optionally an opt-in annotation:

```kotlin
/**
 * Enable broad-phase spatial sorting for depth ordering.
 *
 * **Experimental**: This optimization reduces depth-sort comparisons for sparse scenes
 * but may produce different depth ordering from the baseline algorithm for overlapping
 * shapes. Default: `false`. Enable only after visual verification with your scene.
 */
val enableBroadPhaseSort: Boolean = false,
```

#### F25. Renderer caches have no explicit cleanup lifecycle — LOW

`IsometricRenderer` holds 5 `HashMap`s, a `SpatialGrid`, a cached `PreparedScene`, and a cached paths list. There is an `invalidate()` method that clears them, but it is only called internally when `forceRebuild = true`. The renderer has no `Closeable`/`close()`/`dispose()` method.

Currently this is safe because the renderer is `remember`-scoped inside `IsometricScene` and will be GC'd when the composable leaves the tree. However, the proposed F2 fix (`onRendererReady` callback) would give external code a reference to the renderer. If that reference outlives the composition, the caches become a memory leak with no way to release them.

**Evidence**: `IsometricRenderer` class (no `Closeable` implementation, no public `dispose()`), `IsometricScene.kt:101-108` (renderer scoped to `remember`)

**Fix**: Implement `Closeable` on `IsometricRenderer` and call `close()` in `onDispose`:

```kotlin
class IsometricRenderer(...) : Closeable {
    override fun close() {
        invalidate()
        // Clear all maps and spatial index
    }
}

// In IsometricScene:
DisposableEffect(renderer) {
    onDispose { renderer.close() }
}
```

Document that references obtained via `onRendererReady` are invalidated when the `IsometricScene` leaves the tree.


#### F40. `IsometricEngine` constructor accepts degenerate values — silent visual corruption — HIGH

The engine constructor performs zero validation on any parameter:

```kotlin
IsometricEngine(scale = 0.0)              // all geometry collapses to a single point
IsometricEngine(scale = -70.0)            // shapes render mirrored and upside-down
IsometricEngine(angle = Double.NaN)       // transformation matrix is all NaN — invisible scene
IsometricEngine(angle = 0.0)             // projection degenerates to a line
IsometricEngine(colorDifference = -0.5)   // lighten() darkens, producing negative lightness
IsometricEngine(lightDirection = Vector(0.0, 0.0, 0.0))  // zero-magnitude → flat lighting, no shading
```

Every one of these compiles, constructs, and renders without any error. The user sees nothing or visual garbage with no diagnostic.

**Evidence**: `IsometricEngine.kt` constructor — no `require()` guards; `Vector.normalize()` returns zero vector for zero magnitude

**Fix**:

```kotlin
class IsometricEngine(
    val angle: Double = PI / 6,
    val scale: Double = 70.0,
    val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION,
    val colorDifference: Double = 0.20,
    val lightColor: IsoColor = IsoColor.WHITE
) {
    init {
        require(angle.isFinite()) { "angle must be finite" }
        require(scale.isFinite() && scale > 0.0) { "scale must be positive and finite" }
        require(colorDifference.isFinite() && colorDifference >= 0.0) { "colorDifference must be non-negative and finite" }
        require(lightDirection.magnitude() > 0.0) { "lightDirection must be non-zero" }
    }
}
```

#### F41. Contradictory flag combinations silently accepted — dead callbacks and meaningless options — HIGH

Several parameter combinations are representable but semantically contradictory:

| Combination | What happens |
|-------------|-------------|
| `enableGestures = false` + `onTap = { ... }` | Tap callback silently never called — dead code |
| `enableBroadPhaseSort = true` + `enableDepthSorting = false` | Broad-phase sort runs inside the depth-sorting branch — silently ignored |
| `enableStroke = true` + `strokeWidth = 0f` | Compose draws a hairline stroke (1px) — probably not intended |
| `enableStroke = true` + `strokeWidth = -1f` | Platform-undefined behavior — no crash, no stroke |
| `enableStroke = false` + `strokeWidth = 5f` | Stroke width is allocated but never used |

None of these are caught at construction or composition time. The user's intent is contradicted silently.

**Evidence**: `IsometricScene.kt:216-280` (gestures disabled → `pointerInput` replaced with `Modifier`), `IsometricEngine.kt:sortPaths` (`enableBroadPhaseSort` only checked inside `if (enableDepthSorting)`), `IsometricRenderer.kt` (strokeWidth passed directly to `Stroke()`)

**Fix**: For gestures, the proposed redesign (F1) uses nullable `onTap: ((...) -> Unit)? = null` — null means disabled, non-null means enabled. No separate boolean needed. For `enableBroadPhaseSort`:

```kotlin
// In RenderOptions.init:
require(!enableBroadPhaseSort || enableDepthSorting) {
    "enableBroadPhaseSort requires enableDepthSorting = true"
}
```

For stroke, replace the boolean+float pair with a sealed type:

```kotlin
sealed class StrokeStyle {
    object None : StrokeStyle()
    data class Solid(val width: Float) : StrokeStyle() {
        init { require(width > 0f) { "stroke width must be positive" } }
    }
}
// In SceneConfig:
val stroke: StrokeStyle = StrokeStyle.Solid(1f)
```

This makes `StrokeStyle.None` the only way to disable strokes, and `Solid` enforces a valid width. The contradictory state `enabled + invalid width` becomes unrepresentable.

#### F42. `useNativeCanvas = true` crashes at render time on non-Android — should fail at construction — HIGH

```kotlin
IsometricScene(useNativeCanvas = true)  // on JVM Desktop or iOS
```

The `renderNative()` path instantiates `android.graphics.Paint` (via lazy delegates) and accesses `canvas.nativeCanvas` as `android.graphics.Canvas`. On non-Android platforms, this throws `NoClassDefFoundError` on the first render frame — not at composition time, not at construction time, but deep inside the draw lambda.

**Evidence**: `IsometricRenderer.kt:renderNative()` — accesses Android-only types with no platform guard

**Fix**: Validate at construction time:

```kotlin
// In IsometricScene, before creating the renderer:
if (useNativeCanvas) {
    check(Build.VERSION.SDK_INT > 0) { "useNativeCanvas requires Android" }
}
```

Or use Kotlin Multiplatform `expect`/`actual` to gate the feature. The key principle: fail at the earliest possible point — construction, not rendering.

#### F43. `nodeId` and command ID use hash codes — collision-prone hit testing — MEDIUM

`IsometricNode.nodeId` is computed as:

```kotlin
val nodeId: String = "node_${System.identityHashCode(this)}"
```

`System.identityHashCode` is not unique — two objects can share the same hash under GC pressure. If two nodes collide, `nodeIdMap` maps one ID to one node, silently masking the other. Hit tests on the masked node return the wrong node.

Similarly, `ShapeNode.render()` computes command IDs as `"${nodeId}_${path.hashCode()}"`. If a shape contains two paths with the same `hashCode()` (hash collision), both commands get the same ID. The `commandIdMap` keeps only the last one — the first path is invisible to hit testing.

**Evidence**: `IsometricNode.kt` (`nodeId`), `IsometricNode.kt:ShapeNode.render()` (command ID)

**Fix**: Use an atomic counter for both:

```kotlin
companion object {
    private val nextId = AtomicLong(0)
}
val nodeId: String = "node_${nextId.getAndIncrement()}"

// In ShapeNode.render():
id = "${nodeId}_${index}"  // index within the shape's paths list
```

Atomic counters guarantee uniqueness within a JVM process. No collision possible.

#### F44. `IsometricCanvas` old API accumulates shapes on every recomposition — unbounded memory growth — CRITICAL

```kotlin
IsometricCanvas(state = sceneState) {
    add(Prism(Point.ORIGIN), IsoColor.BLUE)  // called on EVERY recomposition
}
```

The `content` lambda is called directly (`scope.content()`) on every recomposition without clearing the engine first. Each call to `add()` appends to `engine.items`. After N recompositions, the engine holds N copies of every shape. `add()` also calls `version++` on the state, which triggers recomposition — creating a feedback loop that only stabilizes when Compose's recomposition limit is hit.

**Evidence**: `IsometricCanvas.kt:63` (`scope.content()` called every recomposition), `IsometricSceneState.kt:add()` (appends to engine + increments version)

**Fix**: Since F13 removes `IsometricCanvas` entirely, this bug is eliminated. Removing `IsometricCanvas` also resolves F12 (scope name collision) and F8 (legacy `Color` can be deleted alongside). If the old API is retained for any reason, `scope.content()` must be preceded by `state.clear()`:

```kotlin
state.clear()
scope.content()
```

#### F45. Node `rotation` and `scale` accept NaN, Infinity, and zero — degenerate transforms — MEDIUM

```kotlin
Shape(geometry = prism, rotation = Double.POSITIVE_INFINITY)  // cos(∞) = NaN → all coordinates NaN
Shape(geometry = prism, scale = 0.0)                          // all points collapse to scale origin
Shape(geometry = prism, scale = -1.0)                         // winding order inverts → backface culling wrong
```

`rotation` is passed to `cos()`/`sin()` in `Point.rotateZ()`. `cos(Infinity)` returns `NaN` on the JVM, silently poisoning every coordinate. `scale = 0.0` collapses geometry to a point. `scale < 0` flips winding order, causing backface culling to show the wrong faces.

**Evidence**: `IsometricNode.kt:ShapeNode.render()` (applies rotation/scale via `Point.rotateZ`/`Point.scale`), `RenderContext.kt:applyTransformsToShape/Point`

**Fix**: Validate in the composable `update` block or in node setters:

```kotlin
// In IsometricComposables.kt Shape/Group/Path/Batch update blocks:
set(rotation) {
    require(rotation.isFinite()) { "rotation must be finite" }
    this.rotation = it
}
set(scale) {
    require(scale.isFinite() && scale > 0.0) { "scale must be positive and finite" }
    this.scale = it
}
```

#### F46. Core types `Shape` and `Path` accept empty or degenerate construction — silent invisible output — MEDIUM

```kotlin
Shape(emptyList<Path>())     // zero paths → renders nothing
Shape()                       // vararg with zero args → same
Path(emptyList<Point>())     // zero points → invisible, no lighting, no culling
Path(Point.ORIGIN)           // one point → degenerate, no polygon semantics
```

All of these compile, construct, and render without any error or warning. The user sees nothing and has no diagnostic. The downstream code is extensively guarded (`points.size < 3` checks everywhere), so these never crash — but they silently produce invisible output.

**Evidence**: `Shape.kt` (no `require` on `paths`), `Path.kt` (no `require` on `points`), throughout renderer code (guards return early for `< 3` points)

**Fix**:

```kotlin
// In Path:
init { require(points.size >= 3) { "Path requires at least 3 points" } }

// In Shape:
init { require(paths.isNotEmpty()) { "Shape requires at least one path" } }
```

This makes it impossible to construct degenerate geometry. Users who genuinely need a 1-or-2-point path (a line or point marker) would need a separate `Line` type, which is a cleaner domain model anyway.

> **See also**: F9 (shape constructor validation), F40 (engine constructor validation), F45 (transform validation) — these four findings compose into a single validation sweep across all constructors (WS1).

#### F47. `Infinity` passes `> 0.0` guards on cell sizes — optimization degenerates to O(N²) — LOW

```kotlin
RenderOptions(broadPhaseCellSize = Double.POSITIVE_INFINITY)  // passes require(> 0.0)
IsometricRenderer(engine, spatialIndexCellSize = Double.POSITIVE_INFINITY)  // passes require(> 0.0)
```

Both `require(x > 0.0)` checks pass for `Infinity`. With infinite cell size, `floor(coordinate / Infinity) = 0.0` for any finite coordinate — every item hashes to cell `(0, 0)`. The spatial optimization degenerates: broad-phase generates all N×(N-1)/2 pairs, spatial index puts all shapes in one bucket. Performance is worse than without the optimization (same work plus HashMap overhead).

**Evidence**: `RenderOptions.kt:require(broadPhaseCellSize > 0.0)`, `IsometricRenderer.kt:require(spatialIndexCellSize > 0.0)`

**Fix**: Tighten guards to require finite values:

```kotlin
require(broadPhaseCellSize.isFinite() && broadPhaseCellSize > 0.0)
require(spatialIndexCellSize.isFinite() && spatialIndexCellSize > 0.0)
```

### 3.7 Modularity & Composition

#### F48. `IsometricEngine` is a god object — 491 lines, 6+ unrelated responsibilities — HIGH

`IsometricEngine` simultaneously owns:

| Concern | Evidence |
|---------|----------|
| Mutable scene state (item accumulation) | `items = mutableListOf<SceneItem>()`, `add()`, `clear()`, `nextId` |
| 3D→2D projection | `translatePoint()`, `transformation` matrix math |
| Per-face lighting (HSL pipeline) | `transformColor()` — 40 lines of inline normal/dot-product/HSL math |
| Back-face culling | `cullPath()` |
| Viewport bounds checking | `itemInDrawingBounds()` |
| Topological depth sorting (two algorithm branches) | `sortPaths()` — 83 lines with embedded broad-phase and baseline paths |
| Hit testing with convex hull | `findItemAt()`, `buildConvexHull()` |

The `transformColor()` method manually computes a cross product and normalization instead of calling the existing `Vector.crossProduct()` and `Vector.normalize()` utilities — the engine duplicates its own domain types' functionality. `buildConvexHull()` is a standalone geometry algorithm embedded in the engine solely because `findItemAt()` needs it.

A consumer who only needs projection (e.g., a coordinate overlay) must instantiate the entire engine with its depth-sort, lighting, and hit-test infrastructure.

**Evidence**: `IsometricEngine.kt` (491 lines), `IsometricEngine.kt:transformColor()` (manual cross product duplicating `Vector.crossProduct()`), `IsometricEngine.kt:sortPaths()` (83 lines, two algorithm branches), `IsometricEngine.kt:buildConvexHull()` (standalone geometry algorithm)

**Fix**: Extract responsibilities into focused collaborators:

```kotlin
// Scene state — accumulation + ID management
class SceneGraph {
    fun add(shape: Shape, color: IsoColor): Int
    fun add(path: Path, color: IsoColor): Int
    fun clear()
    val items: List<SceneItem>
}

// Projection — pure function, no state
object IsometricProjection {
    fun project(items: List<SceneItem>, config: ProjectionConfig): List<TransformedItem>
}

// Depth sorting — independent algorithm
object DepthSorter {
    fun sort(items: List<TransformedItem>, options: RenderOptions): List<RenderCommand>
}

// Hit testing — independent concern
object HitTester {
    fun findItemAt(scene: PreparedScene, x: Double, y: Double, ...): RenderCommand?
}

// IsometricEngine becomes a thin facade:
class IsometricEngine(...) {
    private val sceneGraph = SceneGraph()
    fun add(...) = sceneGraph.add(...)
    fun projectScene(...) = /* delegates to projection + sorting */
    fun findItemAt(...) = HitTester.findItemAt(...)
}
```

The facade preserves the current single-class ergonomics while making each concern independently testable and reusable.

> **See also**: F53 (no testable interface), F56 (projection API locked inside engine), F52 (`transformColor()` duplicates `Vector` math) — decomposition directly enables these fixes.

#### F49. `IsometricRenderer` mixes two rendering backends in one class — 670 lines — HIGH

`IsometricRenderer` contains:

| Concern | Evidence |
|---------|----------|
| PreparedScene caching | `cachedPreparedScene`, `rebuildCache()`, `needsUpdate()` |
| Compose path object caching | `cachedPaths`, `buildPathCache()` |
| Compose `DrawScope.render()` | 60+ lines of Compose drawing |
| Android-native `DrawScope.renderNative()` | 50+ lines of `android.graphics.*` drawing |
| Spatial grid for hit testing | `SpatialGrid` private inner class (46 lines) |
| Command-to-node resolution | `commandIdMap`, `commandOrderMap`, `commandToNodeMap`, `buildCommandMaps()` |
| Node tree traversal | `buildNodeIdMap()` with recursive `visit()` |
| Benchmark hooks | `benchmarkHooks`, `forceRebuild` |
| Color/path conversion extensions | `toComposePath()`, `toNativePath()`, `toComposeColor()`, `toAndroidColor()` |
| Android-specific paint objects | `fillPaint`, `strokePaint` (`android.graphics.Paint` lazy delegates) |

The Android-native rendering path (`renderNative()`, `toNativePath()`, `toAndroidColor()`, `fillPaint`, `strokePaint`) imports `android.graphics.*` at the top of the file. This compiles into the shared class and makes the module non-multiplatform — any non-Android target gets `NoClassDefFoundError` at class load time, not just when calling `renderNative()`.

The `SpatialGrid` inner class is a complete data structure that cannot be tested independently because it's private.

**Evidence**: `IsometricRenderer.kt` (670 lines), `IsometricRenderer.kt` top-level imports (`android.graphics.Paint`, `android.graphics.Color`, `android.graphics.Path`), `IsometricRenderer.kt:SpatialGrid` (private inner class)

**Fix**: Split into focused types:

```kotlin
// Cache management — owns invalidation logic
class SceneCache(private val engine: IsometricEngine) {
    fun getOrRebuild(rootNode: GroupNode, context: RenderContext): PreparedScene
    fun invalidate()
}

// Cross-platform renderer — Compose DrawScope only
class ComposeSceneRenderer {
    fun DrawScope.render(scene: PreparedScene, config: RenderConfig)
}

// Android-native renderer — separate source set (androidMain)
class NativeSceneRenderer {
    fun DrawScope.render(scene: PreparedScene, config: RenderConfig)
}

// Spatial grid — independent, testable
class SpatialGrid(cellSize: Double) {
    fun insert(command: RenderCommand, bounds: Rect)
    fun query(x: Double, y: Double, radius: Double): List<RenderCommand>
}

// Hit testing — uses spatial grid
class HitTestResolver(private val grid: SpatialGrid) {
    fun hitTest(x: Double, y: Double, ...): IsometricNode?
}
```

The Android-native renderer moves to an `androidMain` source set, eliminating the platform coupling. The `SpatialGrid` becomes testable. The cache, renderer, and hit tester each own one concern.

#### F50. `toComposePath()` conversion duplicated in three locations — MEDIUM

The same `Point2D` → Compose `Path` conversion logic appears in three places:

1. `IsometricRenderer.kt` — `private fun RenderCommand.toComposePath()`
2. `IsometricRenderer.kt` — `private fun RenderCommand.toNativePath()` (for `android.graphics.Path`)
3. `ComposeRenderer.kt` — `private fun RenderCommand.toComposePath()`

The `IsoColor` → Compose `Color` conversion is duplicated in two places:

1. `IsometricRenderer.kt` — `private fun IsoColor.toComposeColor()`
2. `ComposeRenderer.kt` — `fun IsoColor.toComposeColor()`

Similarly, bounding-box computation (min/max scan over `Point2D` lists) is duplicated between `IsometricEngine.kt` (`TransformedItem.getBounds()`) and `IsometricRenderer.kt` (`RenderCommand.getBounds()`).

**Evidence**: `IsometricRenderer.kt` (three private conversion extensions), `ComposeRenderer.kt` (duplicate public conversions), `IsometricEngine.kt:TransformedItem.getBounds()` vs `IsometricRenderer.kt:RenderCommand.getBounds()`

**Fix**: Extract shared conversions to extension functions in a single location:

```kotlin
// In isometric-compose, a shared conversions file:
fun RenderCommand.toComposePath(): androidx.compose.ui.graphics.Path { ... }
fun IsoColor.toComposeColor(): Color { ... }
fun List<Point2D>.bounds(): Rect { ... }
```

Remove all private duplicates and `ComposeRenderer.toComposeColor()`. The Android-native conversions (`toNativePath()`, `toAndroidColor()`) move to the Android-specific renderer (see F49).

#### F51. `IsoColor` eagerly computes HSL on every construction — penalizes hot render path — MEDIUM

`IsoColor.init {}` runs the full RGB-to-HSL conversion (branch-heavy, division-heavy) eagerly:

```kotlin
class IsoColor(val r: Double, val g: Double, val b: Double, val a: Double = 255.0) {
    val h: Double
    val s: Double
    val l: Double
    init {
        // 20+ lines of RGB→HSL computation
    }
}
```

HSL values are only consumed by `lighten()`, which is only called from `IsometricEngine.transformColor()` during lighting. Every other use of `IsoColor` — construction, passing as parameters, conversion to Compose `Color` — pays the HSL cost for nothing.

In a scene with 300 shapes × 6 faces × per-face lighting, `IsoColor` is constructed thousands of times per frame. The RGB→HSL computation runs on every one, but only `lighten()` needs the result.

**Evidence**: `IsoColor.kt:init {}` (eager HSL computation), `IsoColor.kt:lighten()` (only consumer of `h`/`s`/`l`), `IsometricEngine.kt:transformColor()` (only caller of `lighten()`)

**Fix**: Compute HSL lazily:

```kotlin
class IsoColor(val r: Double, val g: Double, val b: Double, val a: Double = 255.0) {
    // HSL computed on first access, not on construction
    private val hsl: Triple<Double, Double, Double> by lazy { rgbToHsl(r, g, b) }
    val h: Double get() = hsl.first
    val s: Double get() = hsl.second
    val l: Double get() = hsl.third
}
```

Colors that are never lightened (most of them — only lit faces call `lighten()`) skip the computation entirely. `lazy` is thread-safe by default in Kotlin.

#### F52. `IsometricEngine.transformColor()` duplicates `Vector` math — manual cross product and normalize — LOW

`transformColor()` computes the face normal manually with raw component variables:

```kotlin
val i3 = j * k2 - j2 * k
val j3 = -1 * (i * k2 - i2 * k)
val k3 = i * j2 - i2 * j
```

This is `Vector.crossProduct()`. The subsequent normalization logic also duplicates `Vector.normalize()`. The engine does not call its own domain types' utilities.

**Evidence**: `IsometricEngine.kt:transformColor()` — manual cross product and normalization, `Vector.kt:crossProduct()` and `Vector.normalize()` — existing utilities

**Fix**: Replace inline math with `Vector` operations:

```kotlin
// Before (manual):
val i3 = j * k2 - j2 * k
val j3 = -1 * (i * k2 - i2 * k)
val k3 = i * j2 - i2 * j
// ... manual normalization ...

// After:
val edge1 = Vector(points[1].x - points[0].x, ...)
val edge2 = Vector(points[2].x - points[0].x, ...)
val normal = edge1.crossProduct(edge2).normalize()
val intensity = normal.dotProduct(lightDirection)
```

This also makes the lighting math readable to someone unfamiliar with cross products — `edge1.crossProduct(edge2).normalize()` communicates the geometry better than six component variables.

#### F53. No interface for `IsometricEngine` — renderer cannot be tested with a fake engine — MEDIUM

`IsometricRenderer` takes a concrete `IsometricEngine` in its constructor. There is no interface or abstract class defining the engine's contract. A test that wants to verify renderer behavior (cache invalidation, spatial grid queries, hit-test resolution) must construct a real engine with real shapes, run real projection, and produce a real `PreparedScene`.

Similarly, `IsometricRenderer.hitTest()` and `rebuildCache()` require a `GroupNode` specifically — not an abstract `IsometricNode` — so tests must always construct a full `GroupNode` tree.

**Evidence**: `IsometricRenderer.kt` constructor (takes concrete `IsometricEngine`), `IsometricRenderer.kt:hitTest()` and `rebuildCache()` (typed to `GroupNode`)

**Fix**: Extract a minimal engine interface:

```kotlin
interface SceneProjector {
    fun projectScene(
        width: Int, height: Int,
        renderOptions: RenderOptions,
        lightDirection: Vector
    ): PreparedScene

    fun findItemAt(
        preparedScene: PreparedScene,
        x: Double, y: Double,
        hitOrder: HitOrder,
        touchRadius: Double
    ): RenderCommand?
}
```

`IsometricEngine` implements `SceneProjector`. `IsometricRenderer` depends on `SceneProjector`, not the concrete engine. Tests can provide a fake that returns canned `PreparedScene` data without running the full projection/sorting pipeline.

#### F54. `:lib` module coexists with no boundary — legacy API accessible alongside new API — LOW

`settings.gradle` includes `:lib`, which contains `IsometricCompose.kt` — a composable that uses the pre-refactor `Isometric()` and `Color()` classes directly, bypassing `IsometricEngine` entirely. This module coexists with `:isometric-compose` in the same project. A user browsing the project sees two composable entry points with no indication of which is current.

The `Color` class in `:isometric-core` duplicates `IsoColor`'s full RGB-to-HSL pipeline (the legacy class that was supposed to be replaced).

**Evidence**: `settings.gradle` (`include ':lib'`), `lib/src/main/java/.../IsometricCompose.kt` (uses old API), `Color.kt` (duplicates `IsoColor.kt` HSL logic)

**Fix**: Remove the `:lib` module from `settings.gradle` and delete the legacy `Color` class. If the legacy API must remain accessible for any reason, move it to a separate `:isometric-legacy` module with clear documentation indicating it is superseded.

#### F55. `isometric-compose` uses `api(project(":isometric-core"))` — re-exports entire core surface — LOW

The `isometric-compose` module declares `api(project(":isometric-core"))` in its build script, which re-exports every public type from `isometric-core` to consumers. Any consumer of `isometric-compose` gets the full core API (`IsometricEngine`, `IntersectionUtils`, `PreparedScene`, etc.) whether they need it or not.

This means a beginner who only wants `IsometricScene` and `Shape` also sees `IsometricEngine.add()`, `IntersectionUtils.isIntersecting()`, `PreparedScene.commands`, and other internal-grade types in autocomplete.

**Evidence**: `isometric-compose/build.gradle.kts` (`api(project(":isometric-core"))`)

**Fix**: Change to `implementation(project(":isometric-core"))` and explicitly re-export only the types that consumers need:

```kotlin
// In isometric-compose's public API surface — re-export domain types:
// Shape, Path, Point, Vector, IsoColor, Prism, Cylinder, Pyramid, etc.
// Do NOT re-export: IsometricEngine, PreparedScene, RenderCommand, IntersectionUtils
```

Types needed by advanced users (via `onEngineReady`) are already accessed through the callback — they don't need to be in the default import surface. This reduces autocomplete noise for beginners while keeping advanced access available.

### 3.8 Escape Hatches & Advanced Control

#### F56. No world-to-screen or screen-to-world coordinate conversion — HIGH

The isometric projection function (`translatePoint()`) is `private` inside `IsometricEngine`. The projection matrix (`transformation`) is also `private`. Users cannot convert a 3D world `Point` to a 2D screen position, or vice versa.

This blocks common advanced use cases:

| Use case | Why it fails |
|----------|-------------|
| Place a Compose UI element at a 3D world position | Cannot compute the screen offset |
| Draw a line from world point A to B in screen space | Cannot project either endpoint |
| Implement custom camera controls | Cannot read or apply the projection matrix |
| Build a coordinate readout overlay | Cannot map cursor position to world coordinates |
| Snap-to-grid in world space | Cannot invert the projection |

The only way to get screen-space coordinates is to add a shape to the engine, call `projectScene()`, and read `RenderCommand.points` — a full pipeline invocation for a single point conversion.

**Evidence**: `IsometricEngine.kt:translatePoint()` (private), `IsometricEngine.kt:transformation` (private), no inverse projection exists anywhere

**Fix**: Add public projection methods to `IsometricEngine`:

```kotlin
// Forward projection — 3D world to 2D screen
fun worldToScreen(point: Point): Point2D {
    return translatePoint(point, originX = 0.0, originY = 0.0)
}

// Inverse projection — 2D screen to 3D world (at a given Z plane)
fun screenToWorld(screenX: Double, screenY: Double, atZ: Double = 0.0): Point {
    // Invert the isometric projection matrix at the specified Z plane
    // Returns the world-space point that projects to (screenX, screenY) at height atZ
}
```

`screenToWorld` requires specifying a Z plane because the 2D→3D mapping is ambiguous — a single screen point maps to a line in 3D space. Constraining to a Z plane (typically `z = 0.0` for ground-level) makes it unique.

The `AdvancedSceneConfig.onEngineReady` callback (F2) provides engine access, so these methods are reachable without polluting the beginner path.

#### F57. No render pipeline hooks — cannot draw overlays or intercept commands — HIGH

The only hook interface is `RenderBenchmarkHooks`, which fires timing-only callbacks (`onPrepareStart`, `onPrepareEnd`, `onDrawStart`, `onDrawEnd`, `onCacheHit`, `onCacheMiss`). None of these receive the `PreparedScene`, `DrawScope`, or any rendering context. There is no way to:

- Draw additional elements in the same Canvas pass (selection highlights, grid lines, debug bounds)
- Intercept individual command rendering (custom shading, wireframe mode, per-face effects)
- Inject a pre-render or post-render pass

The `DrawScope` is captured inside the Canvas lambda in `IsometricScene.kt` and never surfaced. An advanced user who wants to draw a selection rectangle around a tapped shape, or overlay a coordinate grid, must use a separate `Canvas` composable stacked on top — which introduces z-ordering complexity and double-buffering overhead.

**Evidence**: `IsometricRenderer.kt:RenderBenchmarkHooks` (timing only, no rendering context), `IsometricScene.kt:Canvas` lambda (DrawScope never exposed), `IsometricRenderer.kt:render()` / `renderNative()` (no pre/post hooks)

**Fix**: Add render lifecycle hooks to `AdvancedSceneConfig`:

```kotlin
data class AdvancedSceneConfig(
    // ... existing fields ...

    // Render pipeline hooks
    val onBeforeDraw: ((scene: PreparedScene, drawScope: DrawScope) -> Unit)? = null,
    val onAfterDraw: ((scene: PreparedScene, drawScope: DrawScope) -> Unit)? = null,
    val onPreparedSceneReady: ((PreparedScene) -> Unit)? = null,
)
```

Wire in `IsometricScene`'s Canvas lambda:

```kotlin
Canvas(modifier = ...) {
    val scene = renderer.rebuildIfNeeded(rootNode, context)
    advancedConfig.onPreparedSceneReady?.invoke(scene)
    advancedConfig.onBeforeDraw?.invoke(scene, this)
    renderer.render(scene, this)
    advancedConfig.onAfterDraw?.invoke(scene, this)
}
```

`onAfterDraw` enables overlays drawn on top of the scene in the same canvas pass. `onPreparedSceneReady` enables SVG export, external coordinate queries, and analytics without needing `DrawScope`. All three are null by default — zero cost for beginners.

#### F58. No `CustomNode` composable — custom nodes require raw `ComposeNode` calls — HIGH

`IsometricNode` is abstract and not sealed — users can subclass it and override `render(context: RenderContext): List<RenderCommand>`. But there is no DSL composable to wire a custom node into the scene tree. Users must write:

```kotlin
// What a user must do today — raw Compose runtime API:
ComposeNode<MyCustomNode, IsometricApplier>(
    factory = { MyCustomNode() },
    update = {
        set(position) { this.position = it }
        // ... manually set every property
    }
)
```

This requires knowing the applier class name (`IsometricApplier`), the Compose runtime `ComposeNode` API, and how `Updater` works. It is not documented, not discoverable, and fragile.

Additionally, custom nodes that render screen-space content (text labels, selection highlights) cannot work because `RenderCommand.points` is populated by the engine's `translatePoint()` during `projectScene()`. There is no way to provide pre-projected 2D coordinates that bypass the engine.

**Evidence**: `IsometricComposables.kt` (only `Shape`, `Path`, `Batch`, `Group` composables — no `CustomNode`), `IsometricNode.kt` (abstract, not sealed — subclassing is allowed), `IsometricApplier.kt` (accepts any `IsometricNode` — wiring is possible but undocumented)

**Fix**: Add a `CustomNode` composable to the DSL:

```kotlin
@IsometricComposable
@Composable
fun <N : IsometricNode> IsometricScope.CustomNode(
    factory: () -> N,
    position: Point = Point.ORIGIN,
    rotation: Double = 0.0,
    scale: Double = 1.0,
    update: @DisallowComposableCalls Updater<N>.() -> Unit = {}
) {
    ComposeNode<N, IsometricApplier>(
        factory = factory,
        update = {
            set(position) { this.position = it }
            set(rotation) { this.rotation = it }
            set(scale) { this.scale = it }
            update()
        }
    )
}
```

This wraps the Compose runtime boilerplate, provides the standard transform parameters, and lets advanced users focus on their node's `render()` implementation. The `update` lambda allows setting custom properties beyond the standard set.

#### F59. `PreparedScene` inaccessible from compose layer — only available via imperative engine — MEDIUM

`IsometricRenderer.currentPreparedScene` is `internal` — inaccessible from application code. The only way to get a `PreparedScene` is to construct an `IsometricEngine` imperatively, add shapes manually, and call `projectScene()` — completely bypassing the compose DSL.

This blocks use cases that need the frame's projected output:

| Use case | Why it fails |
|----------|-------------|
| Export current frame to SVG | Cannot read the commands list |
| Compute visible bounds for camera fitting | Cannot read projected extents |
| Overlay projected coordinates | Cannot access screen-space points |
| Analytics (shape count, visible count) | Cannot inspect the scene |

F57's `onPreparedSceneReady` callback addresses this directly. An alternative that does not require pipeline hooks:

**Evidence**: `IsometricRenderer.kt:currentPreparedScene` (`internal val`), `IsometricScene.kt` (no callback to surface the scene)

**Fix**: Already addressed by F57's `onPreparedSceneReady` callback. Alternatively, expose via `AdvancedSceneConfig.onEngineReady`:

```kotlin
// User code:
var engine: IsometricEngine? = null
IsometricScene(
    advancedConfig = AdvancedSceneConfig(
        onEngineReady = { engine = it }
    )
) { ... }

// Later — user can call engine.projectScene() directly
val scene = engine?.projectScene(width, height, options, lightDirection)
```

This already works with the F2 proposal. The gap is that calling `projectScene()` repeats work the renderer already did. F57's `onPreparedSceneReady` is the zero-cost solution.

#### F60. Engine constructor params are private and immutable — no runtime camera control — MEDIUM

`IsometricEngine`'s `angle` and `scale` are `private val` constructor parameters:

```kotlin
class IsometricEngine(
    private val angle: Double = PI / 6,
    private val scale: Double = 70.0,
    ...
)
```

Users cannot read back the projection angle, cannot change it after construction, and cannot animate it. A "zoom" or "rotate view" interaction requires constructing a new engine every frame — discarding all internal state.

**Evidence**: `IsometricEngine.kt` constructor — `private val angle`, `private val scale`, `private val colorDifference`

**Fix**: Make configuration readable and optionally mutable:

```kotlin
class IsometricEngine(
    angle: Double = PI / 6,
    scale: Double = 70.0,
    colorDifference: Double = 0.20,
    lightDirection: Vector = DEFAULT_LIGHT_DIRECTION,
    lightColor: IsoColor = IsoColor.WHITE
) {
    var angle: Double = angle
        set(value) {
            require(value.isFinite()) { "angle must be finite" }
            field = value
            rebuildTransformation()  // recompute projection matrix
        }

    var scale: Double = scale
        set(value) {
            require(value.isFinite() && value > 0.0) { "scale must be positive and finite" }
            field = value
            rebuildTransformation()
        }

    // Read-only access to projection state:
    val projectionAngle: Double get() = angle
    val projectionScale: Double get() = scale
}
```

This enables animated camera zoom/rotation via `onEngineReady`:

```kotlin
advancedConfig = AdvancedSceneConfig(
    onEngineReady = { engine ->
        // Animate zoom:
        engine.scale = lerp(startScale, endScale, progress)
    }
)
```

#### F61. No `CompositionLocal` for render context or engine — custom composables cannot do coordinate math — MEDIUM

Custom composables written by advanced users (either via `CustomNode` or as wrappers around existing composables) cannot access the accumulated transform context or engine. The following `CompositionLocal` providers are absent:

| Missing local | What it would enable |
|--------------|---------------------|
| `LocalRenderContext` | Custom composables reading accumulated position/rotation/scale to do coordinate math |
| `LocalIsometricEngine` | Custom composables calling `worldToScreen()` for UI overlay placement |
| `LocalPreparedScene` | Custom composables reading the current frame's projected output |

Currently, CompositionLocals exist for visual config (`LocalDefaultColor`, `LocalLightDirection`, `LocalRenderOptions`, `LocalStrokeWidth`, `LocalDrawStroke`, `LocalColorPalette`) and benchmark hooks (`LocalBenchmarkHooks`), but not for the objects that enable advanced computation.

**Evidence**: `CompositionLocals.kt` (6 visual + 1 benchmark local, no engine/context/scene locals)

**Fix**: Add engine and context locals, provided inside `IsometricScene`:

```kotlin
// In CompositionLocals.kt:
val LocalIsometricEngine = staticCompositionLocalOf<IsometricEngine> {
    error("No IsometricEngine provided — must be used inside IsometricScene")
}

// In IsometricScene, inside the composition:
CompositionLocalProvider(
    LocalIsometricEngine provides engine,
    // ...existing providers...
) {
    content()
}
```

Advanced users access the engine from any custom composable:

```kotlin
@IsometricComposable
@Composable
fun IsometricScope.WorldLabel(text: String, worldPosition: Point) {
    val engine = LocalIsometricEngine.current
    val screenPos = engine.worldToScreen(worldPosition)
    // Position a text overlay at screenPos
}
```

`staticCompositionLocalOf` is correct here — the engine instance does not change during the composition's lifetime, so re-reads do not trigger recomposition.

#### F62. `Point2D` has no operations — projected coordinates are inert data — LOW

`Point2D` is the type of all projected screen-space coordinates in `RenderCommand.points`. It is a pure data holder:

```kotlin
data class Point2D(val x: Double, val y: Double)
```

No distance function, no midpoint, no transform operations. An advanced user who receives `RenderCommand.points` and wants to compute the centroid of a face, the bounding box, or the distance between two projected points must write all geometry operations from scratch.

**Evidence**: `Point2D.kt` (two fields, no methods), `RenderCommand.points: List<Point2D>` (user receives these but cannot operate on them)

**Fix**: Add basic 2D operations:

```kotlin
data class Point2D(val x: Double, val y: Double) {
    fun distanceTo(other: Point2D): Double =
        sqrt((x - other.x).pow(2) + (y - other.y).pow(2))

    fun midpoint(other: Point2D): Point2D =
        Point2D((x + other.x) / 2.0, (y + other.y) / 2.0)

    operator fun plus(other: Point2D): Point2D = Point2D(x + other.x, y + other.y)
    operator fun minus(other: Point2D): Point2D = Point2D(x - other.x, y - other.y)
    operator fun times(scalar: Double): Point2D = Point2D(x * scalar, y * scalar)
}
```

These are standard 2D point operations that enable working with projected output without external geometry code.

### 3.9 Language Fit

#### F63. All `CompositionLocal` providers use `compositionLocalOf` — should be `staticCompositionLocalOf` — CRITICAL

All six visual-config CompositionLocals use `compositionLocalOf`, which creates fine-grained recomposition tracking:

```kotlin
val LocalDefaultColor = compositionLocalOf { IsoColor(33.0, 150.0, 243.0) }
val LocalLightDirection = compositionLocalOf { ... }
val LocalRenderOptions = compositionLocalOf { RenderOptions.Default }
val LocalStrokeWidth = compositionLocalOf { 1f }
val LocalDrawStroke = compositionLocalOf { true }
val LocalColorPalette = compositionLocalOf { ColorPalette() }
```

Every `Shape`, `Path`, and `Batch` composable reads these locals, creating per-composable subscriptions. When any value changes, Compose must invalidate each subscriber individually. But these values are set once at the `IsometricScene` root and rarely (if ever) change — light direction, render options, and stroke width are scene-level configuration, not per-frame state.

`staticCompositionLocalOf` is the correct choice: it invalidates the entire subtree on change (which is what the scene needs anyway — all shapes must re-render with the new settings) but avoids per-composable subscription bookkeeping. The file already uses `staticCompositionLocalOf` for `LocalBenchmarkHooks` with the comment "set once per benchmark run" — the same reasoning applies to all the others.

**Evidence**: `CompositionLocals.kt:13-42` (six `compositionLocalOf` calls), `CompositionLocals.kt:71` (correctly uses `staticCompositionLocalOf` for `LocalBenchmarkHooks`)

**Fix**: Change all six to `staticCompositionLocalOf`:

```kotlin
val LocalDefaultColor = staticCompositionLocalOf { IsoColor(33.0, 150.0, 243.0) }
val LocalLightDirection = staticCompositionLocalOf { IsometricEngine.DEFAULT_LIGHT_DIRECTION.normalize() }
val LocalRenderOptions = staticCompositionLocalOf { RenderOptions.Default }
val LocalStrokeWidth = staticCompositionLocalOf { 1f }
val LocalDrawStroke = staticCompositionLocalOf { true }
val LocalColorPalette = staticCompositionLocalOf { ColorPalette() }
```

#### F64. `SideEffect` used for listener wiring that needs cleanup — should be `DisposableEffect` — HIGH

`IsometricScene` uses `SideEffect` to wire up the `rootNode.onDirty` callback:

```kotlin
SideEffect {
    rootNode.onDirty = { sceneVersion++ }
}
```

`SideEffect` fires after every successful recomposition with no cleanup. This means:
- The `onDirty` lambda is redundantly reassigned on every recomposition
- When the composable leaves the composition, `onDirty` is never cleared — the callback holds a reference to `sceneVersion` (a `MutableState`) after the scene is disposed
- There are four separate `SideEffect` blocks in `IsometricScene` (lines 117-170) that should be merged

The correct Compose idiom for event-listener wiring is `DisposableEffect`:

```kotlin
DisposableEffect(rootNode) {
    rootNode.onDirty = { sceneVersion++ }
    onDispose { rootNode.onDirty = null }
}
```

For the remaining side effects (benchmark hooks, flags), a single merged `SideEffect` is appropriate since those don't need cleanup:

```kotlin
SideEffect {
    renderer.benchmarkHooks = currentBenchmarkHooks
    renderer.forceRebuild = forceRebuild
    onHitTestReady?.invoke { x, y -> renderer.hitTest(...) }
    onFlagsReady?.invoke(RuntimeFlagSnapshot(...))
}
```

**Evidence**: `IsometricScene.kt:117-119` (`SideEffect` for listener wiring), `IsometricScene.kt:117-170` (four separate `SideEffect` blocks)

#### F65. `Point` and `Vector` lack operator overloads — the canonical Kotlin use case — HIGH

Math types are the textbook case for Kotlin operator overloads. Neither `Point` nor `Vector` defines any. All arithmetic is verbose method calls:

```kotlin
// Current — manual component math (used throughout IsometricEngine, shapes, RenderContext):
Point(origin.x + dx, origin.y + dy, origin.z + dz)
Vector(j * k2 - j2 * k, -1 * (i * k2 - i2 * k), i * j2 - i2 * j)

// Also: Vector uses companion static methods instead of instance methods:
Vector.crossProduct(v1, v2)   // Java-ism
Vector.dotProduct(v1, v2)     // Java-ism
```

Kotlin's operator conventions and infix functions would make this natural:

```kotlin
// Point operators:
operator fun Point.plus(v: Vector): Point = Point(x + v.x, y + v.y, z + v.z)
operator fun Point.minus(other: Point): Vector = Vector(x - other.x, y - other.y, z - other.z)
operator fun Point.times(scale: Double): Point = Point(x * scale, y * scale, z * scale)

// Vector operators:
operator fun Vector.plus(other: Vector): Vector = Vector(x + other.x, y + other.y, z + other.z)
operator fun Vector.minus(other: Vector): Vector = Vector(x - other.x, y - other.y, z - other.z)
operator fun Vector.times(scalar: Double): Vector = Vector(x * scalar, y * scalar, z * scalar)
operator fun Vector.unaryMinus(): Vector = Vector(-x, -y, -z)
infix fun Vector.cross(other: Vector): Vector = ...
infix fun Vector.dot(other: Vector): Double = ...
```

This transforms the lighting math in `transformColor()` from:

```kotlin
val i3 = j * k2 - j2 * k                    // manual cross product
val j3 = -1 * (i * k2 - i2 * k)
val k3 = i * j2 - i2 * j
```

to:

```kotlin
val normal = edge1 cross edge2               // idiomatic Kotlin
val intensity = normal.normalize() dot lightDirection
```

**Evidence**: `Point.kt` (no operators), `Vector.kt:24-36` (`crossProduct`/`dotProduct` as companion functions), `IsometricEngine.kt:transformColor()` (manual component math)

**Fix**: Add operator overloads to `Point` and `Vector`. Move `crossProduct`/`dotProduct` from companion static methods to instance `infix` functions.

#### F66. Shape subclasses exist only for constructor convenience — should be factory functions — MEDIUM

`Prism`, `Pyramid`, `Cylinder`, `Stairs`, `Octahedron`, and `Knot` all extend `Shape` with the same pattern:

```kotlin
class Prism(origin: Point, dx: Double, dy: Double, dz: Double) : Shape(createPaths(origin, dx, dy, dz)) {
    companion object {
        private fun createPaths(origin: Point, dx: Double, dy: Double, dz: Double): List<Path> { ... }
    }
}
```

No subclass adds behavior, overrides methods, or defines new properties. They exist purely as constructor factories. The `open class` hierarchy allows arbitrary subclassing (users could write `class MyShape : Shape(...)`) but provides no contract for what that means — there's nothing to override.

In Kotlin, this pattern is served by companion factory functions or top-level functions:

```kotlin
// Factory functions on Shape companion — no subclassing needed:
fun Shape.Companion.prism(
    position: Point = Point.ORIGIN,
    width: Double = 1.0, depth: Double = 1.0, height: Double = 1.0
): Shape = Shape(createPrismPaths(position, width, depth, height))

fun Shape.Companion.cylinder(
    position: Point = Point.ORIGIN,
    radius: Double = 1.0, height: Double = 1.0, vertices: Int = 20
): Shape = Shape(createCylinderPaths(position, radius, height, vertices))
```

Alternatively, if the named types are desirable for `is` checks or exhaustive matching, use a sealed hierarchy:

```kotlin
sealed class Shape(val paths: List<Path>) {
    class Prism(...) : Shape(...)
    class Pyramid(...) : Shape(...)
    class Cylinder(...) : Shape(...)
}
```

The current `open class` design gives the worst of both worlds: types that look like they form a hierarchy but don't seal it, and no behavior polymorphism.

**Evidence**: `shapes/Prism.kt`, `shapes/Pyramid.kt`, `shapes/Cylinder.kt`, `shapes/Stairs.kt`, `shapes/Octahedron.kt`, `shapes/Knot.kt` — all extend `Shape` with zero behavior additions

#### F67. `Shape.orderedPaths()` hand-rolls a bubble sort — should use stdlib `sortedBy` — MEDIUM

`Shape.orderedPaths()` implements a 26-line manual bubble sort:

```kotlin
fun orderedPaths(): List<Path> {
    val sortedPaths = paths.toMutableList()
    val depthList = MutableList(sortedPaths.size) { sortedPaths[it].depth() }
    var swapped = true
    var j = 0
    while (swapped) {
        swapped = false
        j++
        for (i in 0 until sortedPaths.size - j) {
            if (depthList[i] < depthList[i + 1]) {
                // manual swap on both lists...
            }
        }
    }
    return sortedPaths
}
```

This is O(n²) worst-case, maintains two parallel mutable lists, and is 26 lines of code that Kotlin's stdlib replaces with one:

```kotlin
fun orderedPaths(): List<Path> = paths.sortedByDescending { it.depth() }
```

`sortedByDescending` uses TimSort (O(n log n)), is well-tested, and communicates intent in a single expression. The parallel `depthList` pattern — pre-computing `depth()` and then sorting by the pre-computed values — can be preserved if `depth()` is expensive:

```kotlin
fun orderedPaths(): List<Path> = paths.sortedByDescending { it.depth() }
// or if depth() is expensive and called multiple times:
fun orderedPaths(): List<Path> = paths
    .map { it to it.depth() }
    .sortedByDescending { it.second }
    .map { it.first }
```

**Evidence**: `Shape.kt:58-84` (manual bubble sort)

#### F68. Extension functions defined inside `object` / class bodies — require `with()` dispatch — MEDIUM

`ComposeRenderer` defines extension functions inside an `object` body:

```kotlin
object ComposeRenderer {
    fun DrawScope.renderIsometric(...) { ... }
    fun IsoColor.toComposeColor(): Color { ... }
    fun Color.toIsoColor(): IsoColor { ... }
}
```

Extensions defined inside a class/object have two receivers: the dispatch receiver (the enclosing type) and the extension receiver. This means they can only be called from within `ComposeRenderer`'s scope:

```kotlin
// Call site in IsometricCanvas.kt — requires with():
with(ComposeRenderer) {
    renderIsometric(preparedScene, renderOptions, ...)
}
```

This defeats the purpose of extension functions. `renderIsometric` should read as `drawScope.renderIsometric(...)` from anywhere. The same pattern appears in `IsometricRenderer` where `DrawScope.render()` is defined as a member extension.

In Kotlin, extensions on framework types should be top-level:

```kotlin
// Top-level — callable from any DrawScope without with():
fun DrawScope.renderIsometric(scene: PreparedScene, ...) { ... }
fun IsoColor.toComposeColor(): Color { ... }
fun Color.toIsoColor(): IsoColor { ... }
```

**Evidence**: `ComposeRenderer.kt:16-71` (three extensions inside `object`), `IsometricCanvas.kt:69-71` (`with(ComposeRenderer) { ... }` call site), `IsometricRenderer.kt:render()` (member extension on `DrawScope`)

#### F69. Java collection constructors instead of Kotlin factory functions — LOW

Several files use Java-style explicit collection constructors:

```kotlin
// IsometricEngine.kt:
val grid = HashMap<Long, MutableList<Int>>()     // line 379
val pairs = ArrayList<Pair<Int, Int>>()          // line 397

// IntersectionUtils.kt:
private fun min(a: Double, b: Double) = if (a < b) a else b  // line 150
private fun max(a: Double, b: Double) = if (a > b) a else b  // line 151
```

Kotlin conventions:

```kotlin
val grid = mutableMapOf<Long, MutableList<Int>>()
val pairs = mutableListOf<Pair<Int, Int>>()
// And: use kotlin.math.min / kotlin.math.max instead of reimplementing
```

Similarly, no shape constructor uses `buildList { }` — all follow the pattern:

```kotlin
val paths = mutableListOf<Path>()
paths.add(face1)
paths.add(face2)
return paths

// Idiomatic:
return buildList {
    add(face1)
    add(face2)
}
```

`IntersectionUtils.kt` also uses non-standard variable naming (`AminX`, `AmaxX`) instead of Kotlin's camelCase convention (`aMinX`, `aMaxX`).

**Evidence**: `IsometricEngine.kt:379,397`, `IntersectionUtils.kt:150-151` (reimplemented `min`/`max`), `IntersectionUtils.kt:54-75` (uppercase locals), all shape files (`mutableListOf` + `add` instead of `buildList`)

#### F70. No `@JvmOverloads` or `@JvmField` on public constructors and constants — Java interop gap — LOW

`IsometricEngine` has five constructor parameters with Kotlin defaults. Java callers cannot use default parameter values — they must specify all five. `@JvmOverloads` generates the necessary overload chain for Java:

```kotlin
class IsometricEngine @JvmOverloads constructor(
    val angle: Double = PI / 6,
    val scale: Double = 70.0,
    ...
)
```

Similarly, companion object constants (`IsoColor.WHITE`, `IsoColor.BLUE`, `IsometricEngine.DEFAULT_LIGHT_DIRECTION`) are accessed from Java as `IsoColor.Companion.getWHITE()` unless annotated with `@JvmField`:

```kotlin
companion object {
    @JvmField val WHITE = IsoColor(255.0, 255.0, 255.0)
    @JvmField val DEFAULT_LIGHT_DIRECTION = Vector(2.0, -1.0, 3.0)
}
```

This affects `IsometricEngine`, `RenderOptions`, `RenderContext`, `ColorPalette`, and `IsoColor` — all public types with defaulted constructors or companion constants.

**Evidence**: `IsometricEngine.kt` (constructor, no `@JvmOverloads`), `IsoColor.kt:121-127` (companion constants, partial `@JvmField`), `IsometricEngine.kt:22` (`DEFAULT_LIGHT_DIRECTION`, no `@JvmField`)

### 3.10 Evolvability & Compatibility

#### F71. Public `data class` types will break consumers on any field addition — HIGH

Five public `data class` types form the API surface and will certainly need new fields:

| Type | Current fields | Inevitable additions | Blast radius |
|------|---------------|---------------------|-------------|
| `RenderOptions` | 5 booleans + cell size | anti-aliasing, shadow, wireframe, LOD, transparency sort | Every consumer using `copy()` or destructuring |
| `PreparedScene` | commands, width, height | version, renderOptions, lightDirection, boundingBox | Renderer hand-off — all renderers break |
| `RenderCommand` | 6 fields | strokeColor, opacity, userData, renderLayer | Hit-test callbacks, custom renderers |
| `RenderContext` | 4 public + 5 private | clippingRect, frameNumber, selectedNodeId | Every `render()` override |
| `ColorPalette` | 6 color roles | onPrimary, warning, info, onSurface | Theming consumers |

For each type, adding a field:
- Changes the generated `copy()` signature (binary break for compiled consumers)
- Changes `componentN()` functions (breaks destructuring: `val (a, b, c) = options`)
- Changes `equals()`/`hashCode()` (breaks caches, sets, maps keyed on these types)

`RenderContext` is especially fragile: it has `private` constructor fields (`accumulatedPosition`, `accumulatedRotation`, `accumulatedScale`) that are included in the generated `copy()`. A caller writing `context.copy(width = newWidth)` inadvertently resets accumulated transforms because unspecified private params take their defaults.

**Evidence**: `RenderOptions.kt` (data class, 5 fields), `PreparedScene.kt` (data class, 3 fields), `RenderCommand.kt` (data class, 6 fields), `RenderContext.kt` (data class, 4 public + 5 private fields), `CompositionLocals.kt:ColorPalette` (data class, 6 fields)

**Fix**: Convert public API types that will grow from `data class` to regular `class`:

```kotlin
// Before (binary-fragile):
data class RenderOptions(val enableDepthSorting: Boolean = true, ...)

// After (evolvable):
class RenderOptions(
    val enableDepthSorting: Boolean = true,
    val enableBackfaceCulling: Boolean = true,
    // ... future fields added here with defaults — no binary break
) {
    // Explicit copy with named params — signature stable
    fun copy(
        enableDepthSorting: Boolean = this.enableDepthSorting,
        enableBackfaceCulling: Boolean = this.enableBackfaceCulling,
        // ...
    ): RenderOptions = RenderOptions(enableDepthSorting, enableBackfaceCulling, ...)

    override fun equals(other: Any?): Boolean { ... }
    override fun hashCode(): Int { ... }
}
```

This preserves `copy()` ergonomics but makes the signature explicit and controlled. No `componentN()` functions are generated, so destructuring is not possible (which is correct — consumers should not positionally destructure config objects). New fields can be added with defaults without breaking existing compiled consumers.

Alternatively, wrap growing field sets in config objects (as F1 does for `IsometricScene` params) so the outer type stays fixed while the inner config absorbs additions.

> **See also**: F75 (`RenderContext.copy()` leaks private transform state — a direct consequence of `data class`), F7 (`IsoColor` is also `data class` but rarely extended).

#### F72. Gesture callbacks are fixed-arity lambdas — cannot extend without breaking — HIGH

```kotlin
onTap: (x: Double, y: Double, node: IsometricNode?) -> Unit = { _, _, _ -> }
onDragStart: (x: Double, y: Double) -> Unit = { _, _ -> }
onDrag: (deltaX: Double, deltaY: Double) -> Unit = { _, _ -> }
onDragEnd: () -> Unit = {}
```

Lambda types in Kotlin are structural — `(Double, Double, IsometricNode?) -> Unit` is not a named type. Future requirements that are certain to arrive:

| Callback | Future need | Why it breaks |
|----------|------------|---------------|
| `onTap` | Pointer ID (multi-touch), pressure, timestamp | Must become 4+ params or change type |
| `onDrag` | Velocity, pointer count, pinch scale | Must become 4+ params |
| `onDragEnd` | Final position, velocity vector | Must gain params |
| `onTap` | Return `HitTestResult` instead of `IsometricNode?` | Lambda type changes entirely |

Every change to these signatures breaks all existing callers.

**Evidence**: `IsometricScene.kt:75-97` (gesture lambda types in composable signature)

**Fix**: Define event types now, even with minimal fields:

```kotlin
data class TapEvent(
    val x: Double,
    val y: Double,
    val node: IsometricNode? = null,
    // future: val timestamp: Long = 0L, val pointerId: Int = 0
)
data class DragEvent(
    val deltaX: Double,
    val deltaY: Double,
    // future: val velocity: Point2D? = null
)

// Callback signatures become stable:
onTap: ((TapEvent) -> Unit)? = null,
onDrag: ((DragEvent) -> Unit)? = null,
```

New fields on `TapEvent`/`DragEvent` are additive (with defaults). The callback signature `(TapEvent) -> Unit` never changes. This is the same pattern F1 already proposes for `DragEvent` but `TapEvent` needs the same treatment.

#### F73. Shape/Path transform methods return base type — subtype information lost — HIGH

```kotlin
open class Shape(val paths: List<Path>) {
    fun translate(dx: Double, dy: Double, dz: Double): Shape = Shape(paths.map { ... })
    fun rotateX(origin: Point, angle: Double): Shape = Shape(paths.map { ... })
    fun scale(origin: Point, factor: Double): Shape = Shape(paths.map { ... })
}
```

Every transform method returns `Shape`, not the subclass. After a transform, subtype information is permanently lost:

```kotlin
val prism: Prism = Prism(Point.ORIGIN, 2.0, 3.0, 1.0)
val moved: Shape = prism.translate(1.0, 0.0, 0.0)  // Prism → Shape — type lost
// moved is Prism? No — it's a new Shape(paths). Cannot cast back.
```

This means:
- Consumers cannot chain transforms and keep the specific shape type
- `when (shape) { is Prism -> ... }` checks fail after any transform
- Any future per-subtype metadata (e.g., `Prism.width`, `Cylinder.radius`) is inaccessible after a transform

The same issue affects `Path.translate()`, `Path.rotateX/Y/Z()`, and `Path.scale()` — all return `Path`, so `Circle.translate(...)` returns `Path`, not `Circle`.

**Evidence**: `Shape.kt:translate/rotateX/rotateY/rotateZ/scale` (all return `Shape`), `Path.kt:translate/rotateX/rotateY/rotateZ/scale` (all return `Path`), `shapes/Prism.kt` etc. (subclasses inherit these methods unchanged)

**Fix**: If factory functions replace subclasses (F66), this is resolved — there is only `Shape`, no subtypes to lose. If the subclass hierarchy is kept, override transforms in each subclass to return the specific type:

```kotlin
class Prism(...) : Shape(...) {
    override fun translate(dx: Double, dy: Double, dz: Double): Prism =
        Prism(position.translate(dx, dy, dz), width, depth, height)
}
```

#### F74. `IsometricNode.children` is a public `MutableList` — bypasses Applier consistency — HIGH

```kotlin
abstract val children: MutableList<IsometricNode>
```

All four node types expose `children` as a public `MutableList<IsometricNode>`. External code can call `node.children.add(child)`, `node.children.removeAt(0)`, or `node.children.clear()` directly — bypassing the `IsometricApplier` which is responsible for:
- Setting `child.parent = this`
- Calling `updateChildrenSnapshot()` for thread-safe snapshot reads
- Triggering dirty propagation via `markDirty()`
- Coalescing batch mutations

Direct mutation silently corrupts the tree: orphaned children (no parent), stale snapshots, and missed dirty flags.

Additionally, leaf nodes (`ShapeNode`, `PathNode`, `BatchNode`) all carry an empty `children` list that is never populated — dead weight that consumers can accidentally populate.

**Evidence**: `IsometricNode.kt` (`abstract val children: MutableList<IsometricNode>`), `IsometricApplier.kt:insertBottomUp/remove/move` (the only correct mutation path)

**Fix**: Expose children as read-only; make mutation internal:

```kotlin
abstract class IsometricNode {
    internal abstract val mutableChildren: MutableList<IsometricNode>
    val children: List<IsometricNode> get() = mutableChildren
}

// Leaf nodes override with emptyList() — no allocation:
class ShapeNode(...) : IsometricNode() {
    override val mutableChildren: MutableList<IsometricNode> get() =
        error("ShapeNode does not support children")
    override val children: List<IsometricNode> get() = emptyList()
}
```

#### F75. `RenderContext` data class `copy()` leaks private accumulated transform state — MEDIUM

`RenderContext` is a `data class` with private fields in the primary constructor:

```kotlin
@Immutable
data class RenderContext(
    val width: Int,
    val height: Int,
    val renderOptions: RenderOptions,
    val lightDirection: Vector = ...,
    private val accumulatedPosition: Point = Point.ORIGIN,
    private val accumulatedRotation: Double = 0.0,
    private val accumulatedScale: Double = 1.0,
    private val rotationOrigin: Point? = null,
    private val scaleOrigin: Point? = null
)
```

The generated `copy()` includes all fields — including the private ones. A caller writing:

```kotlin
val newContext = context.copy(width = newWidth)
```

gets a new `RenderContext` with the accumulated transforms from the old context — which is correct. But a caller writing:

```kotlin
val newContext = context.copy(width = newWidth, accumulatedScale = 2.0)  // ERROR: private
```

gets a compile error because `accumulatedScale` is private. This is the correct behavior *today*, but if `accumulatedScale` is ever made `internal` or `public` (for the `onRenderContext` escape hatch proposed in F61), callers can corrupt the transform state through `copy()`.

More critically, `data class` generates `component1()..component9()` for all nine fields. Java code or reflection-based serialization that calls `component5()` will get `accumulatedPosition` — a private implementation detail — because `componentN()` functions ignore visibility.

**Evidence**: `RenderContext.kt:16-25` (data class with private constructor params)

**Fix**: Convert `RenderContext` from `data class` to a regular `class`:

```kotlin
@Immutable
class RenderContext private constructor(
    val width: Int,
    val height: Int,
    val renderOptions: RenderOptions,
    val lightDirection: Vector,
    private val accumulatedPosition: Point,
    private val accumulatedRotation: Double,
    private val accumulatedScale: Double,
    private val rotationOrigin: Point?,
    private val scaleOrigin: Point?
) {
    constructor(width: Int, height: Int, renderOptions: RenderOptions, lightDirection: Vector)
        : this(width, height, renderOptions, lightDirection, Point.ORIGIN, 0.0, 1.0, null, null)

    fun withTransform(...): RenderContext = RenderContext(...)  // already exists
}
```

No `copy()`, no `componentN()`, no leaked private state. The `withTransform()` factory method (already present) is the only way to create derived contexts.

#### F76. `Point.depth()` and `Path.depth()` hardcode formula for 30° angle — wrong for configurable angles — MEDIUM

```kotlin
// Point.kt:
fun depth(): Double = x + y - 2 * z
```

The depth formula `x + y - 2z` produces correct depth ordering only at the standard isometric angle (`π/6`). But `IsometricEngine` accepts a configurable `angle` constructor parameter. When `angle ≠ π/6`, the projection matrix changes but the depth formula does not — faces will be sorted in the wrong order, causing visual glitches (shapes rendered behind others that should be in front).

`depth()` is public on both `Point` and `Path`. Consumers using `path.depth()` or `point.depth()` for custom sorting will get wrong results for non-standard angles. The formula is also used by `Path.closerThan()`, which is part of the depth-sorting pipeline.

**Evidence**: `Point.kt:depth()` (hardcoded `x + y - 2 * z`), `Path.kt:depth()` (delegates to `Point.depth()`), `Path.kt:closerThan()` (uses `depth()`), `IsometricEngine.kt` constructor (`angle: Double = PI / 6` — configurable)

**Fix**: Either make `depth()` take the projection angle as a parameter:

```kotlin
fun depth(angle: Double = PI / 6): Double {
    val cosA = cos(angle)
    val sinA = sin(angle)
    return x * cosA + y * sinA - 2 * z
}
```

Or make `depth()` internal and compute depth inside the engine where the angle is known, removing the public method that encodes a specific projection assumption.

#### F77. `node.render()` returns `List<RenderCommand>` — per-node allocation every frame — MEDIUM

```kotlin
abstract fun render(context: RenderContext): List<RenderCommand>
```

Every node creates and returns a new `List<RenderCommand>` on every render call. For a scene with 300 shapes, each with 6 faces, this allocates 300 lists (plus the parent `GroupNode` that `flatMap`s them) per frame — significant GC pressure in a hot render path.

The return type also prevents future evolution: switching to `Sequence<RenderCommand>` (lazy, zero-allocation) or an accumulator pattern (`renderTo(output: MutableList<RenderCommand>)`) would change the signature and break all custom node implementations.

**Evidence**: `IsometricNode.kt:render()` (abstract, returns `List<RenderCommand>`), `IsometricNode.kt:GroupNode.render()` (calls `flatMap` which allocates additional intermediate lists)

**Fix**: Change to an accumulator pattern that avoids per-node allocation:

```kotlin
abstract fun renderTo(output: MutableList<RenderCommand>, context: RenderContext)

// GroupNode:
override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
    for (child in childrenSnapshot) {
        if (child.isVisible) child.renderTo(output, childContext)
    }
}

// ShapeNode:
override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
    for (path in transformedShape.paths) {
        output.add(RenderCommand(...))
    }
}
```

The caller pre-allocates a single list and all nodes append to it. Zero intermediate list allocation. The signature is also more stable — adding parameters to `RenderContext` handles future enrichment without touching `renderTo`.

#### F78. `RenderBenchmarkHooks` interface has no default implementations — adding methods breaks implementors — LOW

```kotlin
interface RenderBenchmarkHooks {
    fun onPrepareStart()
    fun onPrepareEnd()
    fun onDrawStart()
    fun onDrawEnd()
    fun onCacheHit()
    fun onCacheMiss()
}
```

Every method is abstract — any implementor must override all six. Adding a seventh method (e.g., `onSpatialIndexBuild()`, `onPathCacheHit()`) breaks every existing implementation at compile time.

The benchmark tests already implement this interface (`CountingHooks` in test code). Any addition forces updating all implementations even if they don't care about the new hook.

**Evidence**: `IsometricRenderer.kt:27-34` (`RenderBenchmarkHooks` — six abstract methods, no defaults)

**Fix**: Add default no-op implementations:

```kotlin
interface RenderBenchmarkHooks {
    fun onPrepareStart() {}
    fun onPrepareEnd() {}
    fun onDrawStart() {}
    fun onDrawEnd() {}
    fun onCacheHit() {}
    fun onCacheMiss() {}
    // Future hooks can be added without breaking existing implementors
}
```

Default implementations cost nothing and make the interface additively extensible. Existing implementations that override all six methods continue to work unchanged.

---

## 4. Default Behavior Audit

### 4.1 Defaults Summary Table

| Parameter / Behavior | Default | Safe? | Notes |
|---------------------|---------|-------|-------|
| `modifier` | `Modifier` | **Good** | Caller controls sizing |
| `.fillMaxSize()` (internal) | Always applied | **Dangerous** | Overrides caller sizing silently (F20) |
| `renderOptions` | `Default` (all optimizations on) | **Good** | Correct for production |
| `strokeWidth` | `1f` | **Good** | Visible, sensible |
| `drawStroke` | `true` | **Good** | Shapes visible with outlines |
| `lightDirection` | `DEFAULT_LIGHT_DIRECTION.normalize()` | **Good** | Consistent shading |
| `defaultColor` | Blue `IsoColor(33, 150, 243)` | **Good** | Visible, distinctive |
| `colorPalette` | `ColorPalette()` (6 presets) | **Good** | Reasonable Material-ish defaults |
| `enableGestures` | `true` | **Surprising** | Hidden hit-test work on every tap (F22) |
| `enablePathCaching` | `true` | **Good** | 30-40% GC reduction, correct to enable |
| `enableSpatialIndex` | `true` | **Good** | 7-25x faster hit tests, correct to enable |
| `spatialIndexCellSize` | `100.0` | **Good** | Reasonable grid granularity |
| `useNativeCanvas` | `false` | **Good** | Multiplatform by default, Android opt-in |
| `forceRebuild` | `false` | **Good** | Benchmark-only, off by default |
| `frameVersion` | `0L` | **Good** | Benchmark-only, inert by default |
| `onHitTestReady` | `null` | **Good** | No overhead when unused |
| `onFlagsReady` | `null` | **Good** | No overhead when unused |
| `onTap` | `{ _, _, _ -> }` (no-op) | **Surprising** | Non-null no-op still triggers hit test (F22) |
| `onDragStart/onDrag/onDragEnd` | No-op lambdas | **Good** | Inert, low overhead |
| Canvas size init | `800×600` | **Weak** | Stale first-frame render (F21) |
| `IsoColor` channel validation | None | **Dangerous** | Silent broken HSL (F7) |
| Shape constructor validation | None | **Dangerous** | Silent broken geometry (F9) |
| `enableBroadPhaseSort` | `false` | **Good** | Experimental, off by default |
| `enableBackfaceCulling` | `true` | **Good** | Correct for production |
| `enableBoundsChecking` | `true` | **Good** | Correct for production |
| `enableDepthSorting` | `true` | **Good** | Correct for production |
| Render error handling | None | **Weak** | Uncaught exceptions crash (F23) |
| Renderer cleanup | GC only | **Weak** | No explicit lifecycle (F25) |
| Composition cleanup | `dispose()` in `onDispose` | **Good** | Correctly disposed |
| Threading model | Main-thread assumed | **Undocumented** | No `@MainThread` or runtime assertion |

### 4.2 Good Defaults

The library gets most performance-sensitive defaults right:

- **All rendering optimizations on by default** (`enablePathCaching`, `enableSpatialIndex`, `enableBackfaceCulling`, `enableBoundsChecking`, `enableDepthSorting`). The "pit of success" — the default path is the fast path.
- **Benchmark infrastructure inert by default** (`forceRebuild = false`, `frameVersion = 0L`, `onHitTestReady = null`, `onFlagsReady = null`). Zero overhead when unused.
- **`useNativeCanvas = false`** — multiplatform rendering by default, Android-specific optimization is opt-in. Correct for a Compose Multiplatform library.
- **Composition properly disposed** — the `DisposableEffect` correctly calls `composition.dispose()`.
- **`rememberUpdatedState` correctly used** for all gesture callbacks — the `pointerInput(Unit)` coroutine reads fresh callback references without restarting.

### 4.3 Dangerous or Surprising Defaults

| Default | Problem | Risk | Fix |
|---------|---------|------|-----|
| `.fillMaxSize()` hardcoded | Overrides caller's explicit sizing | User cannot make a fixed-size scene | Remove from internal chain (F20) |
| `enableGestures = true` | Installs pointer handler + runs hit tests on every tap with no-op callbacks | Hidden CPU work, pointer event consumption | Default to `false` or make nullable (F22) |
| No `IsoColor` validation | `IsoColor(-10, 300, 0)` silently produces broken HSL | Incorrect rendering, hard to debug | Add `require()` guards (F7) |
| No shape validation | `Prism(dx = -1.0)` produces invisible geometry | Shape disappears with no error | Add `require()` guards (F9) |
| Canvas init 800×600 | First frame renders with wrong dimensions | One-frame visual glitch | Init to 0×0, skip until measured (F21) |
| No render error handling | Malformed geometry crashes the draw pass | App crash from a single bad shape | Wrap draw loop in try/catch (F23) |

### 4.4 Behaviors That Should Require Explicit Opt-In

| Behavior | Current | Should Be |
|----------|---------|-----------|
| Gesture processing | On by default (`enableGestures = true`) | Off by default, or null-callback means disabled |
| Native canvas rendering | Off by default (correct) | Keep as opt-in |
| Broad-phase sort | Off by default (correct) | Keep as opt-in, add `@ExperimentalIsometricApi` |
| Force rebuild | Off by default (correct) | Keep as opt-in |
| `Knot` shape | Available without warning | Require `@ExperimentalIsometricShape` opt-in (F11) |

### 4.5 Suggested Documentation for Key Defaults

**`IsometricScene` KDoc header** — add a "Defaults" section:

```
## Defaults

By default, `IsometricScene` enables all rendering optimizations (path caching,
spatial indexing, depth sorting, backface culling). These produce correct,
performant output for most scenes and should not be changed unless profiling
indicates a specific bottleneck.

Gesture handling is disabled in the minimal overload. In the full overload,
gestures are enabled when `onTap` or `onDrag` is non-null. When enabled,
a hit test runs on every tap to identify the touched node — for scenes with
many shapes, consider whether tap handling is needed.

The scene takes the size determined by [modifier]. Apply `Modifier.fillMaxSize()`
to fill the parent, or `Modifier.size(...)` for a fixed-size scene.
```

**`RenderOptions` KDoc** — add preset guidance:

```
## Presets

- [Default]: All optimizations enabled. Correct for production.
- [NoCulling]: Disables backface culling and bounds checking. Shows all faces
  including back faces. Slower, may cause z-fighting. Use for debugging or
  when rendering transparent geometry.
- [NoDepthSort]: Disables intersection-based depth sorting. Faster, but shapes
  may render in wrong order when overlapping. Use when shapes are known not to
  overlap (e.g., a flat grid).
```

**`IsoColor` KDoc** — add channel range documentation:

```
## Channel Ranges

All channels are in the range `0.0..255.0`. Values outside this range will
throw [IllegalArgumentException]. Use [fromHex] for hex color codes, the
[Int constructor][IsoColor(Int, Int, Int)] for integer values, or
[Color.toIsoColor()][toIsoColor] to convert from Compose colors.
```

---

## 5. Proposed API Redesign

### 5.1 2-Overload `IsometricScene` Redesign

The 3 conceptual layers (beginner / intermediate / advanced) are expressed as **2 function overloads**, not 3, to avoid Kotlin overload ambiguity. A 3-overload design where L2 and L3 differ only by an optional `advancedConfig` parameter would be ambiguous — a call like `IsometricScene(config = SceneConfig(), onTap = { ... }) { }` matches both L2 and L3 because `advancedConfig` has a default.

Instead: one minimal overload (L1) and one full overload (L2+L3 combined). The layering comes from the config objects — beginners ignore them, intermediate users pass `SceneConfig`, advanced users additionally pass `AdvancedSceneConfig`.

#### Overload 1 — Beginner (3 params)

```kotlin
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    defaultColor: IsoColor = IsoColor(33, 150, 243),
    content: @Composable IsometricScope.() -> Unit
)
```

Enough to render shapes. Sensible defaults for everything. No gesture handling, no advanced config. Delegates to overload 2 internally.

#### Overload 2 — Intermediate + Advanced (config objects + gestures)

```kotlin
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    config: SceneConfig = SceneConfig(),
    advancedConfig: AdvancedSceneConfig = AdvancedSceneConfig(),
    onTap: ((x: Double, y: Double, node: IsometricNode?) -> Unit)? = null,
    onDrag: ((DragEvent) -> Unit)? = null,
    content: @Composable IsometricScope.() -> Unit
)
```

This single overload serves both intermediate and advanced users. The progressive disclosure comes from the config objects:

- **Intermediate** users pass `config = SceneConfig(...)` and gesture lambdas. They never touch `advancedConfig`.
- **Advanced** users additionally pass `advancedConfig = AdvancedSceneConfig(...)` for renderer tuning and escape hatches.

The two overloads are unambiguous because overload 1 takes `IsoColor` at position 2 while overload 2 takes `SceneConfig` — different types, no resolution conflict.

#### Supporting types

```kotlin
@Immutable
data class SceneConfig(
    val defaultColor: IsoColor = IsoColor(33, 150, 243),
    val renderOptions: RenderOptions = RenderOptions.Default,
    val lightDirection: LightDirection = LightDirection.Default,
    val strokeWidth: Float = 1f,
    val enableStroke: Boolean = true,
    val colorPalette: ColorPalette = ColorPalette()
)

class LightDirection private constructor(val vector: Vector) {
    companion object {
        val Default = LightDirection(DEFAULT_LIGHT_DIRECTION.normalize())
        val TopLeft = LightDirection(Vector(2.0, -1.0, 3.0).normalize())
        val FrontRight = LightDirection(Vector(-1.0, 2.0, 3.0).normalize())

        fun custom(vector: Vector) = LightDirection(vector.normalize())
    }
}

sealed class DragEvent {
    data class Start(val x: Double, val y: Double) : DragEvent()
    data class Move(val deltaX: Double, val deltaY: Double) : DragEvent()
    object End : DragEvent()
}

data class AdvancedSceneConfig(
    // Renderer tuning
    val enablePathCaching: Boolean = true,
    val enableSpatialIndex: Boolean = true,
    val spatialIndexCellSize: Double = 100.0,
    val enableNativeCanvas: Boolean = false,

    // Engine/renderer escape hatches
    val onEngineReady: ((IsometricEngine) -> Unit)? = null,
    val onRendererReady: ((IsometricRenderer) -> Unit)? = null,

    // Benchmark infrastructure
    val forceRebuild: Boolean = false,
    val frameVersion: Long = 0L,
    val onHitTestReady: ((hitTest: (x: Double, y: Double) -> IsometricNode?) -> Unit)? = null,
    val onFlagsReady: ((RuntimeFlagSnapshot) -> Unit)? = null
)
```

Gestures are opt-in (null = disabled). Visual config is grouped in `SceneConfig`. Renderer tuning and benchmark hooks are buried in `AdvancedSceneConfig`. Light direction has discoverable presets via a class with named companion constants instead of a raw `Vector`.

### 5.2 Before / After

**Beginner** — overload 1, zero config:
```kotlin
IsometricScene(modifier = Modifier.fillMaxSize()) {
    Shape(shape = Prism())
}
```

**Intermediate** — overload 2, `SceneConfig` only (ignores `advancedConfig`):
```kotlin
IsometricScene(
    modifier = Modifier.fillMaxSize(),
    config = SceneConfig(
        defaultColor = Color.Blue.toIsoColor(),
        lightDirection = LightDirection.TopLeft
    ),
    onTap = { x, y, node -> handleTap(node) }
) {
    Shape(shape = Prism(), position = Point(1.0, 0.0, 0.0))
}
```

**Advanced** — overload 2, both config objects:
```kotlin
IsometricScene(
    modifier = Modifier.fillMaxSize(),
    config = SceneConfig(defaultColor = IsoColor.GRAY),
    advancedConfig = AdvancedSceneConfig(
        spatialIndexCellSize = 50.0,
        useNativeCanvas = true,
        onEngineReady = { engine -> cameraController.attach(engine) }
    ),
    onTap = { x, y, node -> handleTap(node) }
) {
    ForEach(buildings, key = { it.id }) { building ->
        Group(position = building.position) {
            Shape(shape = building.shape, color = building.color)
        }
    }
}
```

---

## 6. Change Summary

| # | Finding | Change | Effort | Impact | Breaking? |
|---|---------|--------|--------|--------|-----------|
| 1 | F6 | Promote `Color.toIsoColor()` to top-level extension | Trivial | HIGH | No |
| 2 | F7 | `IsoColor` Int ctor + `fromHex()` + constants + validation | Low | HIGH | No (unless passing invalid values) |
| 3 | F10 | Default `origin = Point.ORIGIN` on all shapes | Trivial | MEDIUM | No |
| 4 | F9 | `require()` guards on shape constructors | Low | HIGH | No (unless passing invalid values) |
| 5 | F12 | Rename old `IsometricScope` → `IsometricCanvasScope` | Low | HIGH | Yes (old API users) |
| 6 | F13 | Remove `IsometricCanvas` entirely | Low | HIGH | Yes |
| 7 | F1 | 2-overload `IsometricScene` with `SceneConfig`/`AdvancedSceneConfig` | Medium | HIGH | Yes (moved params) |
| 8 | F15 | Rename `RenderOptions.Quality` → `NoCulling` | Trivial | MEDIUM | Yes |
| 9 | F11 | Mark `Knot` as `@ExperimentalIsometricShape` | Trivial | LOW | Yes (opt-in required) |
| 10 | F16 | Document coordinate system | Low | HIGH | No |
| 11 | F2 | Add `onEngineReady`/`onRendererReady` escape hatches | Low | MEDIUM | No |
| 12 | F17 | Fix animation examples to use `withFrameNanos` | Trivial | LOW | No |
| 13 | F18 | Update examples to omit default `color` | Trivial | LOW | No |
| 14 | F20 | Remove hardcoded `.fillMaxSize()` from internal modifier chain | Trivial | HIGH | Yes (scenes no longer auto-fill) |
| 15 | F21 | Init canvas size to 0×0, skip render until measured | Trivial | MEDIUM | No |
| 16 | F22 | Default `enableGestures = false` or guard hit test on null callback | Trivial | MEDIUM | Yes (gestures no longer auto-enabled) |
| 17 | F23 | Wrap render draw loop in try/catch per command | Low | MEDIUM | No |
| 18 | F24 | Add `@ExperimentalIsometricApi` to `enableBroadPhaseSort` | Trivial | LOW | Yes (opt-in required) |
| 19 | F25 | Add `Closeable` / `dispose()` to `IsometricRenderer` | Low | MEDIUM | No |
| 20 | F26 | Rename shape `origin` → `position`; rename `rotationOrigin`/`scaleOrigin` → `rotationPivot`/`scalePivot` | Low | HIGH | Yes |
| 21 | F27 | Rename `Vector(i, j, k)` → `Vector(x, y, z)` | Low | HIGH | Yes |
| 22 | F28 | Rename shape `dx`/`dy`/`dz` → `width`/`depth`/`height` | Low | HIGH | Yes |
| 23 | F29 | Standardize boolean flags: `drawStroke` → `enableStroke`, `useNativeCanvas` → `enableNativeCanvas` | Trivial | MEDIUM | Yes |
| 24 | F30 | Rename `IsoColor` → `IsometricColor` (or keep — conscious decision) | Low | MEDIUM | Yes |
| 25 | F31 | Rename `engine.prepare(options:)` → `renderOptions` | Trivial | LOW | Yes |
| 26 | F32 | Rename `PreparedScene.viewportWidth`/`viewportHeight` → `width`/`height` | Trivial | LOW | Yes |
| 27 | F33 | Rename `RenderCommand.id` → `commandId` | Trivial | LOW | Yes |
| 28 | F34 | Rename `Shape(shape:)` → `Shape(geometry:)`, `Path(path:)` → `Path(geometry:)` | Trivial | HIGH | Yes |
| 29 | F35 | Update all samples to use named arguments for shape constructors | Low | HIGH | No |
| 30 | F36 | Reorder `Cylinder` params: `(position, radius, height, vertices)` | Trivial | MEDIUM | Yes |
| 31 | F37 | Replace `reverseSort`/`useRadius` booleans with `HitOrder` enum + `touchRadius: Double` | Low | MEDIUM | Yes |
| 32 | F38 | Rename `prepare()` → `projectScene()`, `invalidate()` → `clearCache()`, `clearDirty()` → `markClean()` | Trivial | MEDIUM | Yes |
| 33 | F39 | Reduce nesting in samples via local variables and default positions | Low | LOW | No |
| 34 | F40 | Add `require()` guards to `IsometricEngine` constructor (`scale`, `angle`, `colorDifference`, `lightDirection`) | Trivial | HIGH | No (unless passing invalid values) |
| 35 | F41 | Enforce flag consistency: `require(!broadPhaseSort \|\| depthSorting)`, replace `drawStroke`+`strokeWidth` with `StrokeStyle` sealed class | Low | HIGH | Yes |
| 36 | F42 | Fail at construction time for `useNativeCanvas = true` on non-Android | Trivial | HIGH | No |
| 37 | F43 | Replace `identityHashCode`/`path.hashCode()` IDs with atomic counters | Trivial | MEDIUM | No |
| 38 | F44 | Remove `IsometricCanvas` entirely (eliminates recomposition accumulation bug) | Low | CRITICAL | Yes |
| 39 | F45 | Add `require(isFinite())` guards to node `rotation`/`scale` setters | Trivial | MEDIUM | No (unless passing invalid values) |
| 40 | F46 | Add `require(paths.isNotEmpty())` to `Shape`, `require(points.size >= 3)` to `Path` | Trivial | MEDIUM | No (unless constructing degenerate geometry) |
| 41 | F47 | Tighten cell size guards to `require(isFinite() && > 0.0)` | Trivial | LOW | No |
| 42 | F48 | Extract `IsometricEngine` responsibilities into `SceneGraph`, `IsometricProjection`, `DepthSorter`, `HitTester` behind a facade | High | HIGH | Yes (internal restructure) |
| 43 | F49 | Split `IsometricRenderer` into `SceneCache`, `ComposeSceneRenderer`, `NativeSceneRenderer`, `SpatialGrid`, `HitTestResolver` | High | HIGH | Yes (internal restructure) |
| 44 | F50 | Deduplicate `toComposePath()` (3 copies) and `toComposeColor()` (2 copies) into shared extensions | Low | MEDIUM | No |
| 45 | F51 | Make `IsoColor` HSL computation lazy instead of eager | Trivial | MEDIUM | No |
| 46 | F52 | Replace manual cross product / normalize in `transformColor()` with `Vector` utilities | Trivial | LOW | No |
| 47 | F53 | Extract `SceneProjector` interface from `IsometricEngine` for testability | Low | MEDIUM | No |
| 48 | F54 | Remove `:lib` legacy module and legacy `Color` class | Trivial | LOW | Yes |
| 49 | F55 | Change `api(project(":isometric-core"))` → `implementation` + explicit re-exports | Low | MEDIUM | Yes |
| 50 | F56 | Add `worldToScreen(Point): Point2D` and `screenToWorld(x, y, atZ): Point` to `IsometricEngine` | Low | HIGH | No |
| 51 | F57 | Add `onBeforeDraw`, `onAfterDraw`, `onPreparedSceneReady` render hooks to `AdvancedSceneConfig` | Low | HIGH | No |
| 52 | F58 | Add `CustomNode` composable to DSL for user-defined `IsometricNode` subclasses | Low | HIGH | No |
| 53 | F59 | Surface `PreparedScene` via `onPreparedSceneReady` callback (see F57) | Low | MEDIUM | No |
| 54 | F60 | Make `IsometricEngine` `angle`/`scale` readable and mutable with setter validation | Low | HIGH | Yes (fields become `var`) |
| 55 | F61 | Add `LocalIsometricEngine` CompositionLocal for engine access in custom composables | Trivial | MEDIUM | No |
| 56 | F62 | Add basic 2D operations to `Point2D` (`distanceTo`, `midpoint`, arithmetic) | Trivial | LOW | No |
| 57 | F63 | Change all `compositionLocalOf` → `staticCompositionLocalOf` for scene-level config | Trivial | HIGH | No |
| 58 | F64 | Replace `SideEffect` listener wiring with `DisposableEffect`; merge remaining `SideEffect` blocks | Trivial | HIGH | No |
| 59 | F65 | Add `+`, `-`, `*` operators to `Point`; add `+`, `-`, `*`, `cross`, `dot` operators to `Vector` | Low | HIGH | No |
| 60 | F66 | Replace shape subclass hierarchy with companion factory functions or sealed class | Medium | MEDIUM | Yes |
| 61 | F67 | Replace hand-rolled bubble sort in `orderedPaths()` with `sortedByDescending` | Trivial | MEDIUM | No |
| 62 | F68 | Move extension functions from `object`/class bodies to top-level | Low | MEDIUM | Yes |
| 63 | F69 | Replace `HashMap()`/`ArrayList()` with `mutableMapOf()`/`mutableListOf()`; use `buildList`; use stdlib `min`/`max` | Low | LOW | No |
| 64 | F70 | Add `@JvmOverloads` to public constructors and `@JvmField` to companion constants | Trivial | LOW | No |
| 65 | F71 | Replace `data class` with regular class + explicit `equals`/`hashCode`/`toString` on `Point`, `Vector`, `RenderContext`, `RenderCommand` | Medium | HIGH | Yes |
| 66 | F72 | Replace gesture lambdas with event types (`TapEvent`, `DragEvent`) via single-param callbacks | Low | HIGH | Yes |
| 67 | F73 | Add self-returning generic transforms (`<T : Shape> T.translate(): T`) or use `@SelfType` | Medium | HIGH | Yes |
| 68 | F74 | Make `IsometricNode.children` `internal` or expose as read-only `List`; mutations go through `Applier` | Trivial | HIGH | Yes |
| 69 | F75 | Replace `RenderContext` `data class` with builder or regular class — block `copy()` from leaking transform state | Low | MEDIUM | Yes |
| 70 | F76 | Parameterize depth formula on engine `angle` instead of hardcoding 30° | Low | MEDIUM | No |
| 71 | F77 | Change `node.render()` to accumulator pattern `renderTo(output: MutableList<RenderCommand>)` to avoid per-node allocation | Low | MEDIUM | Yes |
| 72 | F78 | Add default implementations (no-op) to `RenderBenchmarkHooks` interface methods | Trivial | LOW | No |

Changes 1-4, 10-13, 15, 17, 19, 29, 33-37, 39-41, 44-47, 50-53, 55-59, 61, 63-64, 70, 72 are purely additive or validation-only. Changes 5-9, 14, 16, 18, 20-28, 30-32, 35, 38, 42-43, 48-49, 54, 60, 62, 65-69, 71 are breaking — apply directly.

---

## 7. Progressive Disclosure Scorecard

| Criterion | Current | After Redesign |
|-----------|---------|---------------|
| **Clear beginner path** | 3/10 — simplified overload strips gestures; primary has 21 params | 9/10 — minimal overload with smart defaults |
| **Config grouping** | 2/10 — `RenderOptions` groups 5 booleans; 16 others are flat | 8/10 — `SceneConfig` + `AdvancedSceneConfig` |
| **Benchmark isolation** | 1/10 — 4 benchmark params in primary signature + 2 benchmark types in public surface | 9/10 — buried in `AdvancedSceneConfig` |
| **Advanced escape hatches** | 3/10 — no engine/renderer access; no projection API; no render hooks; custom nodes require raw `ComposeNode` | 8/10 — `onEngineReady`/`onRendererReady`, `worldToScreen()`/`screenToWorld()`, render hooks, `CustomNode` composable, `LocalIsometricEngine` |
| **Composable layer** | 8/10 — smart defaults, clean progressive complexity | 8/10 — unchanged (already good) |
| **Overall** | **3/10** | **8/10** |

---

## 8. Naming Consistency Scorecard

| Criterion | Current | After Fixes |
|-----------|---------|-------------|
| **Same concept → same name** | 3/10 — `origin`/`position`/`rotationOrigin` for placement; `i,j,k`/`x,y,z` for axes; `dx,dy,dz` means both dimensions and displacement | 9/10 — `position` everywhere, `x,y,z` everywhere, `width`/`depth`/`height` for dimensions |
| **Different concepts → different names** | 5/10 — `origin` means 3 things; `context` means 2 things; `Path` means 3 things | 8/10 — `position` vs `rotationPivot` vs `Point.ORIGIN`; `renderContext` consistent |
| **Boolean flag consistency** | 4/10 — four prefix patterns (`enable*`, `draw*`, `use*`, `force*`) plus bare | 9/10 — `enable*` for all toggles |
| **Abbreviation consistency** | 5/10 — `Iso` vs `Isometric`; `i,j,k` vs `x,y,z` | 8/10 — consistent `Isometric` prefix (or conscious `IsoColor` exception) |
| **Implementation leakage** | 3/10 — `forceRebuild`, `enablePathCaching`, `spatialIndexCellSize`, `frameVersion` all expose internals | 8/10 — buried in `AdvancedSceneConfig` with intent-oriented grouping |
| **Composable layer consistency** | 9/10 — `Shape`, `Group`, `Path`, `Batch` use identical parameter names | 9/10 — unchanged (already good); `rotationPivot`/`scalePivot` clarify pivot semantics |
| **Overall** | **4/10** | **8/10** |

---

## 9. Domain Terms Glossary

Standardize these terms across the entire API surface:

| Concept | Standard Term | Replaces |
|---------|--------------|----------|
| Where a shape/node is in 3D space | `position` | `origin` (shape ctors) |
| The world zero point | `Point.ORIGIN` | (keep as-is) |
| Transform pivot point | `rotationPivot`, `scalePivot` | `rotationOrigin`, `scaleOrigin` |
| 3D coordinate components | `x`, `y`, `z` | `i`, `j`, `k` (Vector) |
| Shape extent along X axis | `width` | `dx` (Prism, Pyramid) |
| Shape extent along Y axis | `depth` | `dy` (Prism, Pyramid) |
| Shape extent along Z axis | `height` | `dz` (Prism, Pyramid) |
| Translation offset | `dx`, `dy`, `dz` | (keep — correct for delta) |
| Enable/disable toggle | `enable*` | `draw*`, `use*`, `force*` |
| Rendering configuration | `renderOptions` | `options` (engine.prepare) |
| Viewport pixel dimensions | `width`, `height` | `viewportWidth`, `viewportHeight` |
| Single draw instruction | `commandId` | `id` (RenderCommand) |
| Node identifier | `nodeId` | (keep as-is) |
| Tap gesture | `onTap` | `onItemClick` (old API) |
| Shape/Path data parameter | `geometry` | `shape` (in Shape composable), `path` (in Path composable) |
| Hit test ordering | `HitOrder.FRONT_TO_BACK` | `reverseSort: Boolean` |
| Fuzzy hit radius | `touchRadius: Double` | `useRadius: Boolean` + `radius: Double` |
| Project 3D → 2D | `projectScene()` | `prepare()` |
| Clear renderer caches | `clearCache()` | `invalidate()` |
| Reset dirty flag | `markClean()` | `clearDirty()` |
| 3D → 2D point projection | `worldToScreen()` | (absent — private `translatePoint()`) |
| 2D → 3D point unprojection | `screenToWorld()` | (absent) |
| Pre-render callback | `onBeforeDraw` | (absent) |
| Post-render callback | `onAfterDraw` | (absent) |
| Frame output callback | `onPreparedSceneReady` | (absent) |
| User-defined node composable | `CustomNode` | raw `ComposeNode<N, IsometricApplier>` |
| Vector cross product | `v1 cross v2` (infix) | `Vector.crossProduct(v1, v2)` (companion static) |
| Vector dot product | `v1 dot v2` (infix) | `Vector.dotProduct(v1, v2)` (companion static) |
| Point arithmetic | `point + vector`, `p1 - p2` (operators) | `Point(p.x + v.x, ...)` (manual) |
| Tap gesture event | `TapEvent` (data class with coordinates + metadata) | `(Double, Double) -> Unit` (fixed-arity lambda) |
| Drag gesture event | `DragEvent` (data class with delta + metadata) | `(Double, Double) -> Unit` (fixed-arity lambda) |
| Accumulator render | `renderTo(output: MutableList<RenderCommand>)` | `render(): List<RenderCommand>` (allocating) |

---

## 10. Readability Scorecard

| Criterion | Current | After Fixes |
|-----------|---------|-------------|
| **Composable DSL** | 8/10 — `Group { Shape(...) }` hierarchy reads naturally; `If`/`ForEach` are clear | 9/10 — `Shape(geometry=...)` eliminates tautology |
| **Shape construction** | 3/10 — positional `Prism(Point(...), 4.0, 5.0, 2.0)` is opaque; `dx/dy/dz` unclear | 8/10 — `Prism(width=4.0, depth=5.0, height=2.0)` with default position |
| **Color system** | 3/10 — `IsoColor(33.0, 150.0, 243.0)` unlabeled doubles in wrong type | 8/10 — `IsoColor.fromHex(0x2196F3)`, named constants, Int ctor |
| **Hit testing** | 2/10 — `reverseSort=true, useRadius=true` opaque booleans | 8/10 — `HitOrder.FRONT_TO_BACK`, `touchRadius=8.0` |
| **Scene entry point** | 3/10 — 21 params mixing benchmark/production; `forceRebuild` leaks internals | 8/10 — `SceneConfig` + `AdvancedSceneConfig` separation |
| **Method names** | 5/10 — `prepare()`, `invalidate()`, `clearDirty()` are generic or misleading | 8/10 — `projectScene()`, `clearCache()`, `markClean()` |
| **Nesting depth** | 5/10 — three levels typical; transform chains add unlabeled Point instances | 7/10 — default positions + named params reduce nesting |
| **Overall** | **4/10** | **8/10** |

### Calls that read well (preserve these patterns)

```kotlin
// Group hierarchy — clear intent, self-documenting transforms
Group(position = Point(2.0, 2.0, 0.5), rotation = angle) {
    Shape(...)
    Shape(...)
}

// Conditional rendering — reads like English
If(showPyramids) {
    ForEach(items, key = { it.id }) { item ->
        Shape(...)
    }
}

// Named presets — intent without implementation details
renderOptions = RenderOptions.Default

// Named constants — zero ambiguity
color = IsoColor.BLUE
position = Point.ORIGIN
```

### Calls that read poorly (before vs after)

**Before** — three unlabeled doubles, tautological param, nested Points:
```kotlin
IsometricScene {
    Shape(
        shape = Prism(Point(0.0, 0.0, 0.0), 4.0, 5.0, 2.0),
        color = IsoColor(33.0, 150.0, 243.0)
    )
    Shape(
        shape = Cylinder(Point(-2.0, 0.0, 0.0), 0.5, 20, 2.0),
        color = IsoColor(0.0, 200.0, 100.0)
    )
    Shape(
        shape = Pyramid(Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5),
        color = IsoColor(180.0, 180.0, 0.0)
    )
}
```

**After** — named params, domain language, no tautology:
```kotlin
IsometricScene(modifier = Modifier.fillMaxSize()) {
    Shape(
        geometry = Prism(width = 4.0, depth = 5.0, height = 2.0),
        color = IsoColor.BLUE
    )
    Shape(
        geometry = Cylinder(radius = 0.5, height = 2.0),
        color = IsoColor.fromHex(0x00C864)
    )

    val pyramid = Pyramid(position = Point(2.0, 3.0, 3.0))
        .scale(pivot = Point(2.0, 4.0, 3.0), factor = 0.5)
    Shape(geometry = pyramid, color = IsoColor(180, 180, 0))
}
```

---

## 11. Invalid State Prevention Scorecard

| Criterion | Current | After Fixes |
|-----------|---------|-------------|
| **Shape/Path construction** | 1/10 — zero dimensions, empty paths, degenerate points all accepted silently | 9/10 — `require()` guards on all constructors (F9, F46) |
| **Engine configuration** | 1/10 — zero scale, NaN angle, zero light direction all accepted | 9/10 — `require()` guards in engine constructor (F40) |
| **Color channels** | 1/10 — unbounded `Double`, NaN, negative all accepted | 9/10 — range + finiteness validation (F7) |
| **Flag consistency** | 2/10 — contradictory combinations silently accepted; dead callbacks | 8/10 — `require()` for mutually exclusive flags; sealed types eliminate pairs (F41) |
| **Platform safety** | 1/10 — `useNativeCanvas` crashes at render time on non-Android | 8/10 — construction-time platform check (F42) |
| **ID uniqueness** | 3/10 — `identityHashCode`/`path.hashCode()` can collide | 9/10 — atomic counters (F43) |
| **Transform params** | 2/10 — NaN rotation, zero/negative scale accepted | 8/10 — finiteness + range guards in composable setters (F45) |
| **Cell size guards** | 5/10 — `> 0.0` passes `Infinity` | 9/10 — `isFinite() && > 0.0` (F47) |
| **Old canvas API** | 0/10 — unbounded shape accumulation on recomposition | 10/10 — removed entirely (F13/F44) |
| **Overall** | **2/10** | **9/10** |

---

## 12. Modularity & Composition Scorecard

| Criterion | Current | After Fixes |
|-----------|---------|-------------|
| **Single responsibility** | 2/10 — `IsometricEngine` owns 6+ concerns (state, projection, lighting, culling, sorting, hit testing); `IsometricRenderer` owns 10+ (two backends, caching, spatial grid, hit resolution, conversions, benchmarks) | 8/10 — facade delegates to focused collaborators (F48, F49) |
| **Code duplication** | 3/10 — `toComposePath()` triplicated, `toComposeColor()` duplicated, bounding box duplicated, `Vector` math duplicated in lighting | 9/10 — shared extensions, `Vector` utilities used throughout (F50, F52) |
| **Testability** | 4/10 — engine/renderer testable but only with real instances; no interfaces; `SpatialGrid` private and untestable; renderer requires Android for native path | 8/10 — `SceneProjector` interface enables fakes; `SpatialGrid` public and testable; native renderer in separate source set (F49, F53) |
| **Platform separation** | 3/10 — `android.graphics.*` imports in shared renderer class; compiles but crashes on non-Android at class-load time | 9/10 — native renderer in `androidMain` source set; cross-platform code has zero Android imports (F49) |
| **Module boundaries** | 5/10 — core/compose split is clean, but `api()` re-exports entire core; `:lib` legacy module with no boundary | 8/10 — `implementation()` + explicit re-exports; legacy module removed (F54, F55) |
| **Performance coupling** | 4/10 — `IsoColor` HSL computation penalizes all constructions; lighting duplicates `Vector` math | 8/10 — lazy HSL; lighting uses `Vector` utilities (F51, F52) |
| **Hidden global state** | 9/10 — no mutable singletons or thread-local state; all state is instance-level | 9/10 — unchanged (already good) |
| **Overall** | **4/10** | **8/10** |

---

## 13. Escape Hatch & Advanced Control Scorecard

| Criterion | Current | After Fixes |
|-----------|---------|-------------|
| **World↔screen projection** | 0/10 — `translatePoint()` is private; no inverse projection exists | 9/10 — public `worldToScreen()` and `screenToWorld()` on engine (F56) |
| **Render pipeline hooks** | 1/10 — `RenderBenchmarkHooks` fires timing-only events; no `DrawScope` or `PreparedScene` access | 8/10 — `onBeforeDraw`/`onAfterDraw`/`onPreparedSceneReady` in `AdvancedSceneConfig` (F57) |
| **Custom node support** | 3/10 — `IsometricNode` is subclassable but wiring requires raw `ComposeNode` calls, undocumented | 8/10 — `CustomNode` composable wraps boilerplate, documented path (F58) |
| **Scene output access** | 2/10 — `PreparedScene` accessible via imperative engine only; `internal` from compose layer | 8/10 — `onPreparedSceneReady` callback from compose layer (F57/F59) |
| **Runtime camera control** | 1/10 — `angle`/`scale` are `private val`; changing requires new engine | 8/10 — mutable with setter validation (F60) |
| **CompositionLocal ambients** | 5/10 — visual config locals present; no engine, context, or scene locals | 8/10 — `LocalIsometricEngine` enables coordinate math in custom composables (F61) |
| **Core layer openness** | 7/10 — `Shape`/`Path` are `open class`; `PreparedScene`/`RenderCommand` are public data classes; `IntersectionUtils` is fully public; engine `add()`/`projectScene()` are public | 8/10 — `worldToScreen()`/`screenToWorld()` complete the public API (F56) |
| **2D geometry utilities** | 2/10 — `Point2D` is a bare `x`/`y` holder with no operations | 7/10 — basic arithmetic, `distanceTo`, `midpoint` (F62) |
| **Beginner path isolation** | 4/10 — benchmark hooks, `forceRebuild`, `frameVersion` in main signature | 9/10 — all advanced/escape hatch APIs in `AdvancedSceneConfig`, invisible to beginners (F1, F57) |
| **Overall** | **3/10** | **8/10** |

---

## 14. Language Fit Scorecard (Kotlin / Compose)

| Criterion | Current | After Fixes |
|-----------|---------|-------------|
| **CompositionLocal types** | 2/10 — all six visual locals use `compositionLocalOf` (per-subscriber tracking) when values change rarely; only `LocalBenchmarkHooks` correctly uses `static` | 9/10 — all `staticCompositionLocalOf` (F63) |
| **Side effect correctness** | 3/10 — `SideEffect` used for listener wiring (no cleanup); four separate blocks; state mutated in Canvas draw phase | 9/10 — `DisposableEffect` for listeners; merged side effects (F64) |
| **Operator overloads** | 1/10 — math types (`Point`, `Vector`) have zero operators; `crossProduct`/`dotProduct` are companion statics (Java-ism) | 9/10 — `+`, `-`, `*` on Point; `+`, `-`, `*`, `cross`, `dot` on Vector (F65) |
| **Type hierarchy idiom** | 3/10 — `open class Shape` subclassed 6 times purely for constructor convenience; no added behavior, not sealed | 8/10 — sealed hierarchy or companion factories (F66) |
| **Collection idioms** | 4/10 — `HashMap()`/`ArrayList()` constructors; reimplemented `min`/`max`; no `buildList`; hand-rolled bubble sort | 9/10 — Kotlin factories, stdlib sort, `buildList` (F67, F69) |
| **Extension function patterns** | 4/10 — extensions inside `object`/class bodies require `with()` dispatch; conversion functions duplicated as private extensions | 8/10 — top-level extensions, shared utilities (F68, F50) |
| **Java interop** | 4/10 — no `@JvmOverloads` on public constructors; missing `@JvmField` on companion constants; Java-style listener interfaces | 8/10 — proper annotations, `fun interface` or lambda params (F70) |
| **Compose conventions** | 6/10 — `@Stable`/`@Immutable` used on some types; `ReusableComposeNode` chosen correctly; `rememberUpdatedState` correct for gestures | 8/10 — correct effect types, static locals, no draw-phase state mutation (F63, F64) |
| **Overall** | **3/10** | **9/10** |

---

## 15. Evolvability & Compatibility Scorecard

| Criterion | Current | After Fixes |
|-----------|---------|-------------|
| **Binary-safe types** | 2/10 — all core types (`Point`, `Vector`, `RenderContext`, `RenderCommand`) are `data class` — adding any field breaks compiled consumers via changed `copy()`, `componentN()`, `equals`/`hashCode`, and synthetic constructor | 8/10 — regular classes with explicit structural methods; fields can be added without binary breakage (F71) |
| **Callback extensibility** | 2/10 — gesture callbacks are fixed-arity lambdas (`(Double, Double) -> Unit`); adding metadata (pressure, pointerId, timestamp) requires breaking every call site | 9/10 — event types (`TapEvent`, `DragEvent`) with single-param callbacks; new fields addable without breaking (F72) |
| **Transform type preservation** | 2/10 — `Prism.translate()` returns `Shape`; `Path.translate()` returns `Path` base; chained transforms lose subtype info, requiring unsafe casts | 8/10 — self-returning generics preserve concrete type through chains (F73) |
| **Encapsulation** | 3/10 — `IsometricNode.children` is a public `MutableList`; `RenderContext.copy()` leaks accumulated transform state | 8/10 — children `internal` or read-only; `RenderContext` blocks `copy()` (F74, F75) |
| **Formula portability** | 3/10 — `Point.depth()`/`Path.depth()` hardcode `cos(30°)` and `sin(30°)` — wrong for any other engine angle | 9/10 — depth formula parameterized on engine angle (F76) |
| **Allocation pressure** | 4/10 — `node.render()` returns `List<RenderCommand>` — allocates per node per frame; garbage pressure scales with scene size | 8/10 — accumulator pattern `renderTo(output)` reuses a single list (F77) |
| **Interface extensibility** | 4/10 — `RenderBenchmarkHooks` methods have no defaults — adding a method breaks all implementors | 9/10 — default no-op implementations on all interface methods (F78) |
| **Overall** | **3/10** | **8/10** |

---

## 16. Implementation Workstreams

The 78 findings compose into nine cohesive workstreams (WS1a, WS1b, WS2–WS8), ordered by dependency (earlier workstreams unblock later ones). Each workstream groups findings that touch the same files, share a single theme, or whose fixes compound — implementing them together avoids redundant passes over the same code. Two findings (F30, F66) are resolved/redundant and do not appear in any workstream.

### WS1a. Core Type Hardening — validation, defaults, and guards

Sweep every core type constructor across both modules: add `require()` guards, add defaults, fix color construction. Pure safety/correctness work — no renames, no structural changes.

| Finding | Change |
|---------|--------|
| F9 | `require()` guards on shape constructors |
| F10 | Default `origin = Point.ORIGIN` on all shapes |
| F6 | Promote `Color.toIsoColor()` to top-level extension; add `IsoColor.toComposeColor()` |
| F7 | `IsoColor` validation, `Int` constructor, `fromHex()`, named constants |
| F51 | Lazy HSL computation in `IsoColor` instead of eager on every construction |
| F40 | `require()` guards on `IsometricEngine` constructor |
| F46 | `require(paths.isNotEmpty())`, `require(points.size >= 3)` |
| F45 | Finiteness guards on node `rotation`/`scale` |
| F47 | `isFinite() && > 0.0` on cell sizes |
| F67 | Precalculate `depth()` on `Path` construction; replace hand-rolled bubble sort in `orderedPaths()` with `sortedByDescending` using cached depth |

> **F67↔F76 cross-WS note.** F67 caches depth on `Path` construction using the current hardcoded formula (`x + y - 2z`). F76 (WS6) later parameterizes the depth formula on the engine's projection angle. Since `Path` is immutable and transforms return new instances (each with freshly cached depth), the cache is always valid for the hardcoded formula. When F76 lands, it must either (a) make `Path.depth()` accept an angle parameter (breaking the cache), or (b) only parameterize the *scene-level* depth comparison in `closerThan()` and leave the intra-shape face ordering with the approximate hardcoded formula (acceptable — `orderedPaths()` only needs approximate back-to-front within a single shape). Approach (b) is recommended.

**Files**: `Point.kt`, `IsoColor.kt`, `Shape.kt`, `Path.kt`, `Prism.kt`, `Pyramid.kt`, `Cylinder.kt`, `Stairs.kt`, `Octahedron.kt`, `Knot.kt`, `IsometricEngine.kt` (F40), `IsometricNode.kt` (F45), `RenderOptions.kt` (F47 finiteness), `IsometricRenderer.kt` (F47 finiteness), new `ColorExtensions.kt`
**Effort**: Low — each change is mechanical with clear before/after

### WS1b. Core Type Modernization — naming, operators, and type hierarchy

Rename parameters, add operators, modernize the type hierarchy, and harden public API surface. These are breaking changes that touch many files and require design decisions.

| Finding | Change |
|---------|--------|
| F26 | Rename shape `origin` → `position` |
| F27 | Rename `Vector(i, j, k)` → `Vector(x, y, z)` |
| F28 | Rename shape `dx/dy/dz` → `width/depth/height` |
| F36 | Reorder `Cylinder` params |
| F65 | `+`, `-`, `*` operators on `Point` and `Vector` |
| F71 | Replace `data class` with regular class on public types |
| F75 | Block `RenderContext.copy()` from leaking transform state |
| F73 | Self-returning generic transforms on shape subclasses |
| F37 | `reverseSort`/`useRadius` → `HitOrder` enum + `touchRadius: Double` |

> **F66 is redundant.** Shape subclasses (`Prism`, `Pyramid`, `Cylinder`, etc.) are intentionally kept as concrete subclasses of an open `Shape` class. Users must be able to define their own shape types via subclassing. Neither factory functions nor a sealed hierarchy is appropriate. F73 (self-returning generic transforms) is the correct fix for subtype loss on transform methods.

> **F73 prerequisite: store constructor params as `val` properties.** Currently shape subclasses pass their constructor parameters to the parent `Shape(createPaths(...))` and discard them — e.g., `Prism(origin, dx, dy, dz)` stores nothing. Self-returning transforms need to reconstruct the shape from its dimensions (`Prism(position.translate(dx, dy, dz), width, depth, height)`), which requires the subclass to retain `val position`, `val width`, `val depth`, `val height`. This storage change is part of F73's implementation, applied during the F26/F28 renames (same parameters, same files).
>
> **F73 scope: `translate()` only for self-returning subtype.** Reconstruction from stored params works for `translate()` (just shift position), but NOT for `rotateX/Y/Z` or `scale` — a rotated `Prism` cannot be expressed as a `Prism` with different stored params, because axis-aligned faces are no longer axis-aligned after rotation. Design: subclasses override `translate()` to return `Self` (Kotlin supports covariant return types); `rotateX/Y/Z` and `scale` remain returning `Shape`. This covers the dominant use case (positioning shapes) without requiring complex lazy-transform machinery.

> **F71 and F75 are merged here** (previously F75 was in WS4). Both convert the same public types from `data class` to regular class — `RenderContext`, `RenderOptions`, `PreparedScene`, `RenderCommand`, `ColorPalette`. Doing `data class` removal (F71) and `copy()` blocking (F75) in the same pass avoids two edits to the same files.

**Files**: `Point.kt`, `Vector.kt`, `Shape.kt`, `Path.kt`, `Prism.kt`, `Pyramid.kt`, `Cylinder.kt`, `Stairs.kt`, `Octahedron.kt`, `Knot.kt`, `RenderContext.kt`, `RenderCommand.kt`, `RenderOptions.kt`, `PreparedScene.kt`, `CompositionLocals.kt`, `IsometricEngine.kt`, `IsometricRenderer.kt` (F37 caller of `findItemAt`)
**Effort**: Medium — high file count, each rename is mechanical but blast radius is wide
**Depends on**: WS1a (validation guards should be in place before restructuring)

### WS2. Entry Point & Configuration Redesign

Restructure `IsometricScene` into a layered 2-overload API and extract configuration into typed objects. This is the highest-impact single workstream.

| Finding | Change |
|---------|--------|
| F1 | 2-overload `IsometricScene` with `SceneConfig`/`AdvancedSceneConfig` |
| F2 | `onEngineReady`/`onRendererReady` escape hatches |
| F5, F72 | Gesture event types (`TapEvent`, `DragEvent`) replace four fixed-arity lambdas — extensible and nullable (single design) |
| F20 | Remove hardcoded `.fillMaxSize()` |
| F21 | Init canvas to 0×0, skip until measured |
| F22 | Default `enableGestures = false` or guard on callback presence |
| F41 | `require()` for contradictory flags; `StrokeStyle` sealed type |

> **F5 and F72 are a single design decision**, consolidated here. F5 proposed a single `onGesture: ((GestureEvent) -> Unit)?` with a sealed `GestureEvent`; F72 proposed separate `onTap: ((TapEvent) -> Unit)?` / `onDragStart: ((DragEvent) -> Unit)?` callbacks with event data classes. The final design should choose one approach — both restructure the same four gesture lambdas.

> **Cross-cutting: use `DisposableEffect` from the start.** WS7's F64 requires converting `SideEffect` → `DisposableEffect` for listener wiring. When restructuring `IsometricScene`, wire all new callbacks (`onEngineReady`, `onRendererReady`, etc.) with `DisposableEffect` and cleanup in `onDispose` from day one, rather than using `SideEffect` and converting later. This applies to the existing 4 `SideEffect` blocks as well — restructure them as part of this work.

> **Cross-cutting: new types should avoid `data class`.** WS1b's F71 establishes the principle that public types should not be `data class` (auto-generated `copy()` and `componentN()` break on field additions). Apply this to all new types created here: `SceneConfig`, `AdvancedSceneConfig` should be regular classes. `StrokeStyle` is a sealed type (fine). Gesture event types (`TapEvent`, `DragEvent`) may use `data class` since they are consumed values, not extended — but the decision should be conscious.

> **F41 partially overlaps with F29 (WS3).** F41 replaces `drawStroke` + `strokeWidth` with a sealed `StrokeStyle`, eliminating `drawStroke` entirely. After WS2, F29 in WS3 no longer needs to rename `drawStroke → enableStroke` — only `useNativeCanvas → enableNativeCanvas` remains.

**Files**: `IsometricScene.kt`, `IsometricComposables.kt`, `CompositionLocals.kt` (F41 removes `LocalDrawStroke`/`LocalStrokeWidth`, adds `LocalStrokeStyle`), new `SceneConfig.kt`, new `GestureEvent.kt`
**Effort**: Medium — single-file focus (`IsometricScene.kt`) plus new types
**Depends on**: WS1b (naming/validation changes apply to params referenced here)

### WS3. Naming & Readability Cleanup

One pass to standardize naming conventions, eliminate tautology, and update all samples.

| Finding | Change |
|---------|--------|
| F29 | Standardize boolean flags on `enable*` prefix |
| F31 | `engine.prepare(options:)` → `renderOptions` |
| F32 | `PreparedScene.viewportWidth/Height` → `width/height` |
| F33 | `RenderCommand.id` → `commandId` |
| F34 | `Shape(shape:)` → `Shape(geometry:)` |
| F38 | `prepare()` → `projectScene()`, `invalidate()` → `clearCache()`, `clearDirty()` → `markClean()` |
| F15 | `RenderOptions.Quality` → `NoCulling` |
| F35 | Update all samples to named arguments |
| F39 | Reduce nesting via local variables |

> **F30 is resolved: keep `IsoColor`.** The short name is more ergonomic for a type used in every shape call, and the naming conflict with the legacy `Color` class is eliminated by F8 (delete `Color.kt`). The `Iso` prefix is a conscious, documented exception to the `Isometric*` convention.

> **F29 scope reduced by WS2.** WS2's F41 eliminates `drawStroke` (replaced by `StrokeStyle`) and F5/F22 may eliminate `enableGestures` (nullable callbacks replace the boolean gate). After WS2, F29 only applies to `useNativeCanvas → enableNativeCanvas`. The `force*` prefix (`forceRebuild`) moves to `AdvancedSceneConfig` (F1) and is benchmark-only, so `enable*` standardization does not apply.

**Files**: `IsometricEngine.kt`, `IsometricRenderer.kt`, `IsometricNode.kt`, `RenderOptions.kt`, `PreparedScene.kt`, `RenderCommand.kt`, `IsometricComposables.kt`, all sample/activity files
**Effort**: Low — each rename is trivial; volume is the challenge
**Depends on**: WS1b (renames in WS1b — F26, F27, F28 — should land first so WS3 renames don't collide), WS2 (F41 eliminates `drawStroke` before F29 attempts to rename it)

### WS4. Safety Net & Lifecycle

Error handling, cleanup, platform safety, and legacy removal.

| Finding | Change |
|---------|--------|
| F23 | try/catch per draw command in render loop |
| F24 | Mark `enableBroadPhaseSort` as experimental |
| F25 | `Closeable`/`dispose()` on `IsometricRenderer` |
| F42 | Fail at construction time for `useNativeCanvas` on non-Android |
| F43 | Atomic counter IDs replacing hash codes |
| F13, F44, F12 | Remove `IsometricCanvas` entirely — eliminates accumulation bug (F44) and scope name collision (F12) in one deletion. Migrate `ComposeActivity.kt` and `MainActivity.kt` sample usages to `IsometricScene` |
| F8 | Delete legacy `Color.kt` |
| F11 | `@ExperimentalIsometricShape` on `Knot` |
| F74 | `IsometricNode.children` → `internal` or read-only |

> **F75 moved to WS1b** — merged with F71 (`data class` removal) since both edit the same types (`RenderContext`, `RenderOptions`, etc.) and F75 is a natural side effect of F71.

**Files**: `IsometricRenderer.kt`, `IsometricCanvas.kt` (delete — includes old `IsometricScope`), `IsometricCanvasSnapshotTest.kt` (delete or migrate), `Color.kt` (delete), `IsometricNode.kt`, `Knot.kt`, `RenderOptions.kt` (F24 `@ExperimentalIsometricApi`), `ComposeActivity.kt`, `MainActivity.kt`
**Effort**: Low — mostly deletions and guard additions
**Depends on**: WS3 (use post-rename method names when migrating samples; `prepare()` is now `projectScene()`, etc.)

### WS5. Architecture & Modularity

Decompose god objects, deduplicate utilities, and clean module boundaries. This is the largest-effort workstream.

| Finding | Change |
|---------|--------|
| F48 | Extract `IsometricEngine` → `SceneGraph`, `IsometricProjection`, `DepthSorter`, `HitTester` behind facade |
| F49 | Split `IsometricRenderer` → `SceneCache`, `ComposeSceneRenderer`, `NativeSceneRenderer`, `SpatialGrid`, `HitTestResolver` |
| F50 | Deduplicate `toComposePath()` (3 copies) and `toComposeColor()` (2 copies) |
| F52 | Replace manual cross product in `transformColor()` with `Vector` utilities |
| F53 | Extract `SceneProjector` interface for testability |
| F54 | Remove `:lib` legacy module |
| F55 | `api(project(":isometric-core"))` → `implementation` + explicit re-exports |
| F68 | Move extension functions from `object`/class bodies to top-level |
| F69 | Replace Java collection constructors with Kotlin factories |
| F77 | Accumulator pattern `renderTo(output)` instead of per-node allocation |

> **F67 moved to WS1a** — it's a one-line sort fix + depth precalculation on `Path`, not an architectural concern. Should not be blocked behind WS3/WS5.

> **F77 changes the `render()` abstract method signature on `IsometricNode`**, which breaks all node subclasses (`GroupNode`, `ShapeNode`, `PathNode`, `BatchNode`) and any user-created custom nodes. This must land before WS6 ships `CustomNode` (F58), or early adopters' custom nodes will break on the next release.

**Files**: `IsometricEngine.kt` (major refactor), `IsometricRenderer.kt` (major refactor), `Shape.kt`, `ComposeRenderer.kt`, `build.gradle.kts`, new extracted classes
**Effort**: High — two large refactors; facade pattern preserves API compatibility
**Depends on**: WS3 (method renames apply to the same code being split)

### WS6. Escape Hatches & Extensibility

Add the "drop-down" layer between the DSL and raw internals. Split into access hatches (exposing existing internals) and new capabilities (adding features).

**WS6a — Access hatches** (expose existing internals cleanly):

| Finding | Change |
|---------|--------|
| F3, F58 | `CustomNode` composable for user-defined nodes (single implementation) |
| F56 | `worldToScreen()`/`screenToWorld()` on engine |
| F60 | Make engine `angle`/`scale` mutable with validation |
| F61 | `LocalIsometricEngine` CompositionLocal |
| F62 | `Point2D` arithmetic operations |
| F76 | Parameterize depth formula on engine angle |
| F78 | Default no-op implementations on `RenderBenchmarkHooks` |

**WS6b — Advanced capabilities** (new features, higher complexity):

| Finding | Change |
|---------|--------|
| F57 | `onBeforeDraw`/`onAfterDraw`/`onPreparedSceneReady` render hooks |
| F59 | Surface `PreparedScene` via `onPreparedSceneReady` |
| F4 | Per-subtree `renderOptions` override (architectural — requires engine to check options per-command during projection) |
| F19 | `CameraState` for programmatic viewport control (optional — deferrable) |

> **F77 (WS5) must land before F58 ships.** `CustomNode` exposes the `IsometricNode` abstract class to users. If F77 later changes `render()` to `renderTo(output)`, early adopters' custom nodes break immediately.

**Files**: `IsometricEngine.kt`, `IsometricScene.kt`, `IsometricComposables.kt`, `CompositionLocals.kt`, `Point2D.kt`, `Point.kt`, `Path.kt`, `RenderBenchmarkHooks` (F78 — currently in `IsometricRenderer.kt`, post-WS5 location may differ), new `CustomNode.kt`
**Effort**: Medium — mostly additive (no breaking restructure)
**Depends on**: WS5 (engine decomposition exposes the internal seams these hooks need; F77 must land before F58)

### WS7. Compose Runtime Correctness

Fix Compose effect types and CompositionLocal providers. Small scope, high impact on runtime behavior.

| Finding | Change |
|---------|--------|
| F63 | All `compositionLocalOf` → `staticCompositionLocalOf` |
| F64 | `SideEffect` → `DisposableEffect` for listener wiring; merge remaining blocks |
| F70 | `@JvmOverloads`/`@JvmField` on public constructors and constants |

> **F37 moved to WS1b** — it's an API naming/design change (`reverseSort`/`useRadius` → `HitOrder` enum + `touchRadius: Double`), not a Compose runtime correctness issue. It touches `IsometricEngine.findItemAt()` in the core module.

> **F72 consolidated into WS2** — it restructures the gesture callback surface alongside F5. Both proposals target the same four lambda parameters on `IsometricScene`.

> **F64 scope note.** If WS2 already converted `SideEffect` → `DisposableEffect` as recommended in the WS2 cross-cutting note, F64 is largely complete. Verify all listener wiring uses `DisposableEffect` with cleanup, and merge any remaining `SideEffect` blocks where possible.

**Files**: `CompositionLocals.kt`, `IsometricScene.kt`, public constructors across core module
**Effort**: Low — mechanical changes with high correctness payoff
**Depends on**: WS2 (F64 edits `IsometricScene.kt` restructured by WS2)

### WS8. Documentation & Examples

Update all samples and add missing documentation. Must run after renames (WS1b, WS3) and deletions (WS4) are complete — writing samples against old names or deleted APIs is wasted work.

| Finding | Change |
|---------|--------|
| F16 | Document coordinate system in KDoc and RUNTIME_API.md |
| F17 | Fix animation samples: `delay(16)` → `withFrameNanos` |
| F18 | Omit default `color` in first example |
| F14 | KDoc note on `Shape` composable vs `Shape` class disambiguation |

**Files**: `RUNTIME_API.md`, `ComposeActivity.kt`, `RuntimeApiActivity.kt`, KDoc on `IsometricScene.kt`
**Effort**: Trivial — no code changes, only docs and samples
**Depends on**: WS1b (F26/F28 renames change sample parameter names), WS3 (F34/F35 renames change sample code), WS4 (F13 deletes `IsometricCanvas` referenced in samples)

### Dependency graph

```
WS1a ──→ WS1b ──→ WS2 ──┬──→ WS3 ──┬──→ WS4
                         │          │
                         │          └──→ WS5 ──→ WS6a ──→ WS6b
                         │                       ↑
                         │              (F77 before F58)
                         │
                         └──→ WS7

                    WS8 (after WS1b, WS3, WS4)
```

All edges in the graph correspond to stated `Depends on` declarations:

| WS | Depends on |
|----|-----------|
| WS1a | — |
| WS1b | WS1a |
| WS2 | WS1b |
| WS3 | WS1b, WS2 |
| WS4 | WS3 |
| WS5 | WS3 |
| WS6 | WS5 |
| WS7 | WS2 |
| WS8 | WS1b, WS3, WS4 |

### Findings disposition

| Finding | Disposition |
|---------|------------|
| F30 | **Resolved: keep `IsoColor`** — conscious exception to `Isometric*` convention, short name is more ergonomic |
| F66 | **Redundant** — shape subclasses are intentional; users must be able to define own shapes via subclassing. F73 fixes subtype loss |
| F72 | **Consolidated into WS2** with F5 — single design decision for gesture callbacks |
| F75 | **Merged into WS1b** with F71 — both edit same `data class` → regular class conversions |
| F37 | **Moved from WS7 to WS1b** — API naming change, not Compose runtime correctness |
| F67 | **Moved from WS5 to WS1a** — one-line sort fix + depth precalculation, not architectural |

### Execution order (sequential)

1. **WS1a** — Validation guards, depth precalculation, color construction fixes
2. **WS1b** — Renames, operators, `data class` removal, type hierarchy hardening
3. **WS2** — `IsometricScene` restructure, config objects, gesture event design
4. **WS3** — Remaining naming cleanup, readability pass
5. **WS4** — Legacy deletion (`IsometricCanvas`, `Color.kt`), safety, lifecycle
6. **WS5** — Architecture decomposition (engine + renderer), `renderTo()` signature change
7. **WS6** — Escape hatches (6a: `CustomNode`, `LocalIsometricEngine`, coordinate APIs), then advanced capabilities (6b: render hooks, camera)
8. **WS7** — Compose runtime correctness (`staticCompositionLocalOf`, `DisposableEffect`, `@JvmOverloads`)
9. **WS8** — Documentation and examples (all renames and deletions are final)
