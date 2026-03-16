# WS1a: Core Type Hardening — Validation, Defaults, and Guards

> **Workstream**: 1a of 9
> **Phase**: 1 (first — no dependencies)
> **Scope**: Sweep every core type constructor across both modules: add `require()` guards, add defaults, fix color construction, precalculate depth
> **Findings**: F6, F7, F9, F10, F40, F45, F46, F47, F51, F67
> **Review ref**: `docs/reviews/api-first-run-usability-review.md` §16 WS1a

---

## Execution Order

The 10 findings decompose into 4 ordered steps. Each step is self-contained and produces a compilable codebase.

1. **Step 1**: Constructor validation (F7, F9, F40, F45, F46, F47)
2. **Step 2**: Default values and delete redundant secondary constructors (F10)
3. **Step 3**: Color interop extension + lazy HSL (F6, F51)
4. **Step 4**: Depth precalculation + sort fix (F67)

Steps 1–2 must be sequential (Step 2 adds defaults to the same constructors that Step 1 validates). Steps 3–4 are independent of each other and can be parallelized after Step 2.

---

## Step 1: Constructor Validation (F7, F9, F40, F45, F46, F47)

### Best Practice

Use `require()` in `init {}` blocks for precondition validation. `require()` throws `IllegalArgumentException` with a descriptive message — the Kotlin standard for argument validation. Place guards at the earliest possible construction point.

### Files and Changes

#### 1a. `IsoColor.kt` — Channel range validation (F7)

Add to existing `init {}` block (before HSL computation, line 21):
```kotlin
init {
    require(r in 0.0..255.0) { "r must be in 0..255, got $r" }
    require(g in 0.0..255.0) { "g must be in 0..255, got $g" }
    require(b in 0.0..255.0) { "b must be in 0..255, got $b" }
    require(a in 0.0..255.0) { "a must be in 0..255, got $a" }
    // ... existing HSL computation ...
}
```

**Internal construction boundary**: The `lighten()` method calls `withLightness()` → `hslToRgb()`, which can produce floating-point values slightly outside 0–255 (e.g., `255.00000000001`) due to intermediate arithmetic. This would fail the new `require()` guards. Fix: clamp in `withLightness()` before constructing:
```kotlin
private fun withLightness(newL: Double): IsoColor {
    val (rNew, gNew, bNew) = hslToRgb(h, s, newL)
    return IsoColor(
        rNew.coerceIn(0.0, 255.0),
        gNew.coerceIn(0.0, 255.0),
        bNew.coerceIn(0.0, 255.0),
        a
    )
}
```
This keeps `require()` strict at the public API boundary while handling internal floating-point imprecision at the single internal construction site.

Also add `Int` constructor overload and `fromHex()` factory:
```kotlin
constructor(r: Int, g: Int, b: Int, a: Int = 255)
    : this(r.toDouble(), g.toDouble(), b.toDouble(), a.toDouble())

companion object {
    // ... existing WHITE, BLACK, RED, GREEN, BLUE ...
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

    /** Explicit ARGB factory — use when alpha=0 is needed (fromHex can't distinguish 0x00RRGGBB from 0xRRGGBB). */
    fun fromArgb(a: Int, r: Int, g: Int, b: Int): IsoColor =
        IsoColor(r, g, b, a)
}
```

#### 1b. Shape subclass constructors — Positive dimension guards (F9)

Each subclass gets an `init {}` block. Current state: zero guards in any subclass.

```kotlin
// Prism.kt
init {
    require(dx > 0.0) { "Prism width must be positive, got $dx" }
    require(dy > 0.0) { "Prism depth must be positive, got $dy" }
    require(dz > 0.0) { "Prism height must be positive, got $dz" }
}

// Pyramid.kt — same pattern

// Cylinder.kt
init {
    require(radius > 0.0) { "Cylinder radius must be positive, got $radius" }
    require(vertices >= 3) { "Cylinder needs at least 3 vertices, got $vertices" }
    require(height > 0.0) { "Cylinder height must be positive, got $height" }
}

// Stairs.kt
init {
    require(stepCount >= 1) { "Stairs needs at least 1 step, got $stepCount" }
}
```

**Note**: `Octahedron` and `Knot` take only `origin: Point` — no dimension params to validate.

**`Circle.kt`** (Path subclass in `io.fabianterhorst.isometric.paths`): Also needs validation — `Circle(origin, radius, vertices)` currently accepts degenerate inputs like `vertices = 1`. Without its own guard, a `Circle(origin, 1.0, vertices = 2)` would fail at the proposed `Path` init guard with the confusing message "Path requires at least 3 points" rather than a Circle-specific message. Add:
```kotlin
// Circle.kt
init {
    require(radius > 0.0) { "Circle radius must be positive, got $radius" }
    require(vertices >= 3) { "Circle needs at least 3 vertices, got $vertices" }
}
```

#### 1c. `IsometricEngine` constructor — (F40)

**Current**: `IsometricEngine(private val angle: Double = PI / 6, private val scale: Double = 70.0, private val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION, private val colorDifference: Double = 0.20, private val lightColor: IsoColor = IsoColor.WHITE)`

Add `init {}`:
```kotlin
init {
    require(angle.isFinite()) { "angle must be finite, got $angle" }
    require(scale.isFinite() && scale > 0.0) { "scale must be positive and finite, got $scale" }
    require(colorDifference.isFinite() && colorDifference >= 0.0) { "colorDifference must be non-negative and finite, got $colorDifference" }
    require(lightDirection.magnitude() > 0.0) { "lightDirection must be non-zero" }
}
```

#### 1d. `Shape.kt` and `Path.kt` — Degenerate construction guards (F46)

```kotlin
// Path.kt init {}
init {
    require(points.size >= 3) { "Path requires at least 3 points, got ${points.size}" }
}

// Shape.kt init {}
init {
    require(paths.isNotEmpty()) { "Shape requires at least one path" }
}
```

#### 1e. Node transform validation (F45)

In `IsometricComposables.kt`, within the `update` blocks. The current pattern is `set(rotation) { this.rotation = it; markDirty() }` — add validation **before** the assignment, preserving the existing `markDirty()` call:
```kotlin
set(rotation) {
    require(it.isFinite()) { "rotation must be finite, got $it" }
    this.rotation = it
    markDirty()
}
set(scale) {
    require(it.isFinite() && it > 0.0) { "scale must be positive and finite, got $it" }
    this.scale = it
    markDirty()
}
```

Apply to all 4 composables that have these update blocks: `Shape` (line 49), `Group` (line 84), `Path` (line 126), `Batch` (line 165). Also update the `PrimitiveLevelsExample.kt` sample's custom `set(rotation)` blocks to include validation.

#### 1f. Cell size guards — tighten to finite (F47)

**Current** (`RenderOptions.kt` line 18): `require(broadPhaseCellSize > 0.0)`
**After**: `require(broadPhaseCellSize.isFinite() && broadPhaseCellSize > 0.0) { "broadPhaseCellSize must be positive and finite, got $broadPhaseCellSize" }`

Same pattern for `IsometricRenderer` constructor's `spatialIndexCellSize` guard.

### Test Updates

Existing tests that construct shapes/colors with boundary values may need updating. Search for test files that pass zero/negative dimensions and update to valid values. Add new test cases that verify `IllegalArgumentException` is thrown for invalid inputs.

### Verification

After Step 1, run full test suite. Key checks:
- All existing tests pass (no accidental construction with invalid values)
- New validation tests confirm `IllegalArgumentException` for each guard
- `IsoColor.lighten()` / `IsoColor.darken()` still work correctly after `withLightness()` clamping
- `Circle(origin, 1.0, vertices = 2)` throws with Circle-specific message, not Path's generic message

---

## Step 2: Default Values and Delete Redundant Secondary Constructors (F10)

### Default `position = Point.ORIGIN` on all shapes

**Current state**: Only `Prism`, `Pyramid`, `Cylinder` have defaults for dimension params, but none have defaults for `origin`/`position`.

**After**: Every shape constructor gets `origin: Point = Point.ORIGIN` (note: the actual rename from `origin` to `position` happens in WS1b, not here — WS1a does not do renames):
```kotlin
class Prism(origin: Point = Point.ORIGIN, dx: Double = 1.0, dy: Double = 1.0, dz: Double = 1.0)
class Pyramid(origin: Point = Point.ORIGIN, dx: Double = 1.0, dy: Double = 1.0, dz: Double = 1.0)
class Cylinder(origin: Point = Point.ORIGIN, radius: Double = 1.0, vertices: Int = 20, height: Double = 1.0)
class Stairs(origin: Point = Point.ORIGIN, stepCount: Int)
class Octahedron(origin: Point = Point.ORIGIN)
class Knot(origin: Point = Point.ORIGIN)
```

This enables `Prism()` as a zero-arg call producing a unit cube at origin — the ideal beginner experience.

### Delete redundant secondary constructors

After adding `origin: Point = Point.ORIGIN` with all dimension defaults, the following secondary constructors become redundant. Delete them:
- `Prism(origin: Point) : this(origin, 1.0, 1.0, 1.0)` (Prism.kt line 17)
- `Pyramid(origin: Point) : this(origin, 1.0, 1.0, 1.0)` (Pyramid.kt line 18)
- `Cylinder(origin: Point, vertices: Int, height: Double) : this(origin, 1.0, vertices, height)` (Cylinder.kt line 17)

These were workarounds for the missing default on `origin`. With the default in place, they are dead code that adds maintenance burden and confuses IntelliJ's completion popup with duplicate entries.

### Verification

After Step 2, run full test suite. Key checks:
- `Prism()` compiles and produces a unit cube at origin
- `Pyramid()` compiles and produces a unit pyramid at origin
- `Cylinder()` compiles and produces a unit cylinder at origin
- All existing call sites that used the deleted secondary constructors have been updated to use primary constructor with named arguments
- No call site relied on the specific parameter order of the deleted `Cylinder(origin, vertices, height)` secondary constructor

---

## Step 3: Color Interop Extension + Lazy HSL (F6, F51)

### 3a. Color interop extension (F6)

**New file**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/ColorExtensions.kt`

```kotlin
package io.fabianterhorst.isometric.compose

import androidx.compose.ui.graphics.Color
import io.fabianterhorst.isometric.IsoColor

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

**Also**: Remove **both** member extensions from inside `ComposeRenderer` object body:
- `fun Color.toIsoColor(): IsoColor` (line 66) — replaced by top-level version above
- `fun IsoColor.toComposeColor(): Color` (line 54) — replaced by top-level version above

These are member extensions requiring `with(ComposeRenderer) { ... }` dispatch (F68 from WS5). The new top-level extensions are the canonical versions. Note: `ComposeRenderer` also has a private `fun RenderCommand.toComposePath()` member extension (line 39) — leave that for WS5 F50 (dedup) since it involves `Path` conversion logic beyond simple color bridging.

**`coerceIn` removal**: The existing `ComposeRenderer.toComposeColor()` uses `.coerceIn(0f, 1f)` as defensive clamping. After Step 1a adds `require(r in 0.0..255.0)` validation, values are guaranteed in range, making `coerceIn` unnecessary. The new top-level extension omits it intentionally — validation at construction is the correct boundary.

### 3b. Lazy HSL computation (F51)

**Current** (`IsoColor.kt` lines 21–52): HSL is computed eagerly in `init {}` on every construction.

**After**: Use `lazy` delegate:
```kotlin
val h: Double by lazy { computeHSL().first }
val s: Double by lazy { computeHSL().second }
val l: Double by lazy { computeHSL().third }

private fun computeHSL(): Triple<Double, Double, Double> {
    // ... existing HSL computation moved here ...
}
```

**Alternative** (more efficient — single computation):
```kotlin
private val hsl: Triple<Double, Double, Double> by lazy(LazyThreadSafetyMode.NONE) { computeHSL() }
val h: Double get() = hsl.first
val s: Double get() = hsl.second
val l: Double get() = hsl.third
```

This avoids three separate lazy delegates. Use `LazyThreadSafetyMode.NONE` because `IsoColor` instances are not shared across threads during rendering — the default `SYNCHRONIZED` mode adds unnecessary synchronization overhead (volatile read + double-checked lock on every access). HSL is only needed by `lighten()`, which is called during the render phase's lighting computation — not on every `IsoColor` construction.

### Verification

After Step 3, run full test suite. Key checks:
- `Color.toIsoColor()` is callable without `with(ComposeRenderer) { ... }` wrapper
- `IsoColor.toComposeColor()` is callable without `with(ComposeRenderer) { ... }` wrapper
- Existing `ComposeRenderer.renderIsometric()` still works (it calls `toComposePath()` and `toComposeColor()` internally — `toComposeColor()` is now resolved from the top-level extension)
- `IsoColor(255.0, 0.0, 0.0).h` triggers HSL computation lazily on first access
- `IsoColor(255.0, 0.0, 0.0).lighten()` still produces correct results (accesses `h`/`s`/`l` which triggers lazy init → computes HSL → calls `withLightness()`)
- No perf regression — HSL is no longer computed on every `IsoColor` construction (only on first `h`/`s`/`l` access)

---

## Step 4: Depth Precalculation + Sort Fix (F67)

### Rationale

`Shape.orderedPaths()` implements a 26-line manual bubble sort (O(n^2) worst-case) maintaining two parallel mutable lists. Kotlin's `sortedByDescending` uses TimSort (O(n log n)), is well-tested, and communicates intent in a single expression.

Additionally, `Path.depth()` is called by the sort comparator on every comparison. By precalculating depth once at `Path` construction time, we eliminate redundant computation during sorting entirely.

### Files and Changes

#### 4a. `Path.kt` — Precalculate depth as a `val` property

**Current** (lines 64–67):
```kotlin
fun depth(): Double {
    if (points.isEmpty()) return 0.0
    return points.sumOf { it.depth() } / points.size
}
```

**After**: Replace the `depth()` method with a `val` property computed once at construction:
```kotlin
/**
 * Average depth of all points in this path, precalculated at construction time.
 * Uses the standard isometric depth formula (x + y - 2z) averaged across all points.
 */
val depth: Double = points.sumOf { it.depth() } / points.size
```

Note: The `points.isEmpty()` guard from the original `fun depth()` is no longer needed because Step 1d adds `require(points.size >= 3)` in the `Path` init block — `points` is guaranteed non-empty at construction.

**Source compatibility**: Callers of `path.depth()` (with parentheses) will get a compile error since `depth` is now a property, not a function. The call sites are:
- `Shape.orderedPaths()` — updated in Step 4b below
- `Path.closerThan()` — update `this.depth()` → `this.depth` and `other.depth()` → `other.depth`
- Any test code referencing `path.depth()` — update to `path.depth`

Keep the old `fun depth()` temporarily as a deprecated shim if needed for migration, or update all call sites directly (preferred — WS1a is a breaking-change workstream).

#### 4b. `Shape.kt` — Replace bubble sort with `sortedByDescending` using cached depth

**Current** (lines 57–84):
```kotlin
fun orderedPaths(): List<Path> {
    // Bubble sort by depth (back to front)
    val sortedPaths = paths.toMutableList()
    val depths = sortedPaths.map { it.depth() }.toMutableList()

    var swapped = true
    var j = 0
    while (swapped) {
        swapped = false
        j++
        for (i in 0 until sortedPaths.size - j) {
            if (depths[i] < depths[i + 1]) {
                // Swap paths
                val tmpPath = sortedPaths[i]
                sortedPaths[i] = sortedPaths[i + 1]
                sortedPaths[i + 1] = tmpPath

                // Swap depths
                val tmpDepth = depths[i]
                depths[i] = depths[i + 1]
                depths[i + 1] = tmpDepth

                swapped = true
            }
        }
    }
    return sortedPaths
}
```

**After**:
```kotlin
fun orderedPaths(): List<Path> = paths.sortedByDescending { it.depth }
```

This is a one-liner that uses TimSort (O(n log n)) and accesses the precalculated `depth` property — no per-comparison computation. The `sortedByDescending` lambda reads the cached `val depth` from each `Path` instance, making the sort purely comparison-based with zero allocation beyond the sorted list itself.

### F67↔F76 cross-WS note

F67 caches depth on `Path` construction using the current hardcoded formula (`x + y - 2z`). F76 (WS6) later parameterizes the depth formula on the engine's projection angle. Since `Path` is immutable and transforms return new instances (each with freshly cached depth), the cache is always valid for the hardcoded formula. When F76 lands, it must either (a) make `Path.depth` accept an angle parameter (breaking the cache), or (b) only parameterize the *scene-level* depth comparison in `closerThan()` and leave the intra-shape face ordering with the approximate hardcoded formula (acceptable — `orderedPaths()` only needs approximate back-to-front within a single shape). Approach (b) is recommended.

### Verification

After Step 4, run full test suite. Key checks:
- `Path` instances have their `depth` property set at construction time
- `Shape.orderedPaths()` produces the same ordering as the old bubble sort (verified by comparing output on existing test shapes)
- `Path.closerThan()` still works correctly with `this.depth` instead of `this.depth()`
- No `depth()` method calls remain in the codebase (all converted to property access)
- Performance improvement: `orderedPaths()` is O(n log n) instead of O(n^2), with zero redundant depth computation

---

## Cross-Workstream Dependencies

| This step | Produces | Consumed by |
|-----------|----------|-------------|
| Step 1 (validation) | `require()` guards on all constructors | WS2 F41 (contradictory flag validation) — WS2 adds cross-field validation; WS1a adds per-field validation |
| Step 1a (IsoColor validation) | Channel range guarantees | Step 3a (color extension can omit `coerceIn` — values guaranteed in range) |
| Step 2 (defaults) | `origin = Point.ORIGIN` defaults | WS1b (rename `origin` → `position` on top of the defaults) |
| Step 3a (color extension) | Top-level `Color.toIsoColor()` / `IsoColor.toComposeColor()` | WS5 F68 (member extension cleanup — color bridge already done) |
| Step 3b (lazy HSL) | Lazy HSL on `IsoColor` | No downstream dependency — pure perf win |
| Step 4 (depth cache) | `Path.depth` as precalculated `val` | WS5 F67 (bubble sort replacement — already done here); WS6 F76 (depth formula parameterization — see cross-WS note above) |

---

## File Change Summary

| File | Steps | Changes |
|------|-------|---------|
| `IsoColor.kt` | 1a, 3b | validation, `Int` ctor, `fromHex()`, `fromArgb()`, named constants, lazy HSL |
| `Shape.kt` | 1d, 4b | `require(paths.isNotEmpty())`, replace bubble sort with `sortedByDescending` |
| `Path.kt` | 1d, 4a | `require(points.size >= 3)`, precalculate `depth` as `val` property |
| `Circle.kt` | 1b | `require(radius > 0, vertices >= 3)` |
| `Prism.kt` | 1b, 2 | dimension guards, default `origin = Point.ORIGIN`, delete redundant secondary ctor |
| `Pyramid.kt` | 1b, 2 | dimension guards, default `origin = Point.ORIGIN`, delete redundant secondary ctor |
| `Cylinder.kt` | 1b, 2 | dimension guards, default `origin = Point.ORIGIN`, delete redundant secondary ctor |
| `Stairs.kt` | 1b, 2 | `require(stepCount >= 1)`, default `origin = Point.ORIGIN` |
| `Octahedron.kt` | 2 | default `origin = Point.ORIGIN` |
| `Knot.kt` | 2 | default `origin = Point.ORIGIN` |
| `IsometricEngine.kt` | 1c | constructor guards (angle, scale, colorDifference, lightDirection) |
| `IsometricComposables.kt` | 1e | rotation/scale guards in update blocks (4 composables) |
| `RenderOptions.kt` | 1f | tighten cell size guard to `isFinite() && > 0.0` |
| `IsometricRenderer.kt` | 1f | tighten `spatialIndexCellSize` guard to `isFinite() && > 0.0` |
| `ColorExtensions.kt` | 3a | **new file** — top-level `Color.toIsoColor()` / `IsoColor.toComposeColor()` bridge |
| `ComposeRenderer.kt` | 3a | remove member extensions `toIsoColor()` and `toComposeColor()` |
| `PrimitiveLevelsExample.kt` | 1e | rotation/scale guards in custom `set(rotation)` blocks |

---

## Notes

Step 1 does not add validation to Point coordinates (NaN/Infinity) — coordinates are intentionally unbounded for transform math. Only construction-time params (dimensions, angles, color channels) are validated.
