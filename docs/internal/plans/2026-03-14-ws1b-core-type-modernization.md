# WS1b: Core Type Modernization — Naming, Operators, and Type Hierarchy

> **Workstream**: 1b of 9
> **Phase**: 1 (after WS1a)
> **Scope**: Rename parameters, add operators, modernize the type hierarchy, harden public API surface
> **Findings**: F26, F27, F28, F36, F37, F65, F71, F73, F75
> **Depends on**: WS1a (validation guards should be in place before restructuring)
> **Review ref**: `docs/reviews/api-first-run-usability-review.md` §16 WS1b

---

## Execution Order

The 9 findings decompose into 5 ordered steps. Each step is self-contained and produces a compilable codebase.

1. **Step 1**: `data class` → regular class conversion + block `copy()` leak (F71, F75)
2. **Step 2**: Parameter renaming (F26, F27, F28, F36)
3. **Step 3**: Operator overloads on Point and Vector (F65)
4. **Step 4**: Self-returning transforms via covariant overrides (F73)
5. **Step 5**: HitOrder enum + touchRadius (F37)

Step 1 must land first — downstream steps assume regular classes. Steps 2–5 are parallelizable after step 1. Step 4 depends on step 2 having completed the parameter renames (subclass `val` properties use new names).

**Efficiency note — combine WS1a validation and WS1b renaming into a single pass per shape file**: WS1a Step 2 and WS1b Step 2 both touch `Prism.kt`, `Pyramid.kt`, `Cylinder.kt`, `Stairs.kt`. Rather than writing `require(dx > 0.0)` in WS1a and immediately renaming `dx → width` in WS1b, apply validation and renaming together per file. Write `require(width > 0.0) { "Prism width must be positive, got $width" }` directly. The steps are documented separately for conceptual clarity, but should be implemented as a single pass per shape file.

> **F71 and F75 are merged here** (previously F75 was in WS4). Both convert the same public types from `data class` to regular class — `RenderContext`, `RenderOptions`, `PreparedScene`, `RenderCommand`, `ColorPalette`. Doing `data class` removal (F71) and `copy()` blocking (F75) in the same pass avoids two edits to the same files.

---

## Step 1: `data class` → Regular Class Conversion + Block `copy()` Leak (F71, F75)

### Rationale

Adding a field to a `data class` changes the bytecode-generated `copy()`, `componentN()`, `equals()`, `hashCode()`, and the synthetic constructor. Any compiled consumer calling `copy()` or destructuring will get `NoSuchMethodError` at runtime — a silent binary break with no compile-time warning.

Additionally, `RenderContext` as a `data class` exposes `copy()` which leaks private accumulated transform state — any caller can `context.copy(accumulatedScale = 999.0)` and corrupt the transform hierarchy (F75). Converting to regular class and providing a public-field-only `copy()` blocks this leak.

### Best Practice

Kotlin library authors (kotlinx.serialization, Ktor, Compose itself) use regular classes with explicit `equals`/`hashCode`/`toString` for public API types that will grow. An explicit `copy()`-style builder method provides the same ergonomics without the binary contract.

### Files and Changes

#### 1a. `Point.kt` — `data class Point` → `class Point`

**Current** (line 10): `data class Point(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0)`

**After**:
```kotlin
class Point(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {

    override fun equals(other: Any?): Boolean =
        other is Point && x == other.x && y == other.y && z == other.z

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        return result
    }

    override fun toString(): String = "Point(x=$x, y=$y, z=$z)"

    // ... existing methods unchanged ...
}
```

**Destructuring impact**: No code in the codebase uses `val (x, y, z) = point` — verified via exploration. Safe to remove `componentN()`.

**`copy()` impact**: `Point` is constructed directly everywhere (`Point(x, y, z)`), never via `copy()`. No `copy()` replacement needed.

#### 1b. `Vector.kt` — `data class Vector` → `class Vector`

**Current** (line 8): `data class Vector(val i: Double, val j: Double, val k: Double)`

**After**: Same pattern as Point. Note: parameter names change in Step 2 (`i/j/k` → `x/y/z`), but the conversion happens first so we don't duplicate work. Use the old names here; Step 2 renames them.

```kotlin
class Vector(val i: Double, val j: Double, val k: Double) {
    override fun equals(other: Any?): Boolean =
        other is Vector && i == other.i && j == other.j && k == other.k
    override fun hashCode(): Int { /* standard 31-based */ }
    override fun toString(): String = "Vector(i=$i, j=$j, k=$k)"
}
```

#### 1c. `RenderCommand.kt` — `data class RenderCommand` → `class RenderCommand`

**Current** (line 13):
```kotlin
data class RenderCommand(
    val id: String,
    val points: List<Point2D>,
    val color: IsoColor,
    val originalPath: Path,
    val originalShape: Shape?,
    val ownerNodeId: String? = null
)
```

**After**: Regular class with explicit `equals`/`hashCode`/`toString`. This type is likely to gain fields (strokeColor, opacity, renderLayer per review F71 analysis).

#### 1d. `RenderContext.kt` — `data class RenderContext` → `class RenderContext` + public-field-only `copy()` (F71 + F75 merged)

**Current** (lines 16–29): `@Immutable data class RenderContext(val width: Int, val height: Int, val renderOptions: RenderOptions, val lightDirection: Vector = ..., private val accumulatedPosition: Point = ..., private val accumulatedRotation: Double = 0.0, private val accumulatedScale: Double = 1.0, private val rotationOrigin: Point? = null, private val scaleOrigin: Point? = null)`

**Critical**: The `private val` constructor parameters are still accessible in `data class copy()` calls. The `data class copy()` exposes all 9 constructor parameters — including the 5 `private` accumulated transform fields. Any caller can write:
```kotlin
val corrupted = context.copy(accumulatedScale = 0.0) // compiles!
```

Converting to regular class eliminates this leak. The explicit `copy()` method exposes only the 4 public fields, blocking the private transform state from leaking.

**After**:
```kotlin
@Immutable
class RenderContext(
    val width: Int,
    val height: Int,
    val renderOptions: RenderOptions,
    val lightDirection: Vector = IsometricEngine.DEFAULT_LIGHT_DIRECTION.normalize(),
    private val accumulatedPosition: Point = Point(0.0, 0.0, 0.0),
    private val accumulatedRotation: Double = 0.0,
    private val accumulatedScale: Double = 1.0,
    private val rotationOrigin: Point? = null,
    private val scaleOrigin: Point? = null
) {
    /**
     * Creates a copy with the given public fields overridden.
     * Private accumulated transform state is reset to defaults —
     * the correct behavior for callers that want a fresh context
     * with different options.
     */
    fun copy(
        width: Int = this.width,
        height: Int = this.height,
        renderOptions: RenderOptions = this.renderOptions,
        lightDirection: Vector = this.lightDirection
    ): RenderContext = RenderContext(width, height, renderOptions, lightDirection)

    // withTransform uses direct constructor instead of copy()
    fun withTransform(
        position: Point = Point(0.0, 0.0, 0.0),
        rotation: Double = 0.0,
        scale: Double = 1.0,
        rotationOrigin: Point? = null,
        scaleOrigin: Point? = null
    ): RenderContext {
        // ... existing transform accumulation logic ...
        // Replace copy() call with direct constructor:
        return RenderContext(
            width, height, renderOptions, lightDirection,
            newPosition, newRotation, newScale,
            rotationOrigin ?: this.rotationOrigin,
            scaleOrigin ?: this.scaleOrigin
        )
    }

    override fun equals(other: Any?): Boolean =
        other is RenderContext &&
        width == other.width && height == other.height &&
        renderOptions == other.renderOptions &&
        lightDirection == other.lightDirection &&
        accumulatedPosition == other.accumulatedPosition &&
        accumulatedRotation == other.accumulatedRotation &&
        accumulatedScale == other.accumulatedScale &&
        rotationOrigin == other.rotationOrigin &&
        scaleOrigin == other.scaleOrigin

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + renderOptions.hashCode()
        result = 31 * result + lightDirection.hashCode()
        result = 31 * result + accumulatedPosition.hashCode()
        result = 31 * result + accumulatedRotation.hashCode()
        result = 31 * result + accumulatedScale.hashCode()
        result = 31 * result + (rotationOrigin?.hashCode() ?: 0)
        result = 31 * result + (scaleOrigin?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RenderContext(width=$width, height=$height, renderOptions=$renderOptions, lightDirection=$lightDirection)"
}
```

**Visibility stays `private`**: `withTransform()` is a member method on `RenderContext` itself, so it can already access `private` constructor parameters directly — no visibility change needed. The `data class` removal alone is sufficient to block external `copy()` leaking these fields. If a future workstream (e.g., WS5 decomposition) moves transform logic into a sibling class in the same module, upgrade to `internal` at that point.

**Keep transform state private**: The cohesion point between F71 and F75 is not "remove every copy-like API"; it is "remove the compiler-generated `copy()` that exposes private constructor state." The explicit 4-field `copy()` is still useful for tests and for benign public-field tweaks like `renderOptions = ...`.

**Test breakage**: `RenderContext.copy()` is called in test code at 5 locations in `IsometricRendererTest.kt` (lines 132, 161, 481, 586, 611) — e.g., `context.copy(renderOptions = RenderOptions.Quality)`. The explicit `copy()` above covers this with identical signature for the public fields. Tests that were copying private fields were relying on an implementation detail — those break intentionally.

#### 1e. `IsoColor.kt` — KEEP as `data class`

`IsoColor` has body-declared `h`/`s`/`l` vals that are already excluded from `equals`/`hashCode`/`copy()`. The `data class` only tracks `r`/`g`/`b`/`a` — a fixed, complete set that is unlikely to grow. Keeping it as `data class` is safe.

#### 1f. `PreparedScene.kt` — `data class PreparedScene` → `class PreparedScene`

Will grow (version, renderOptions, lightDirection, boundingBox per review analysis). Convert.

#### 1g. `RenderOptions.kt` — `data class RenderOptions` → `class RenderOptions`

Will grow (anti-aliasing, shadow, wireframe, LOD). Convert. Provide explicit `copy()` method for ergonomic preset derivation:

```kotlin
class RenderOptions(
    val enableDepthSorting: Boolean = true,
    val enableBackfaceCulling: Boolean = true,
    val enableBoundsChecking: Boolean = true,
    val enableBroadPhaseSort: Boolean = false,
    val broadPhaseCellSize: Double = DEFAULT_BROAD_PHASE_CELL_SIZE
) {
    fun copy(
        enableDepthSorting: Boolean = this.enableDepthSorting,
        enableBackfaceCulling: Boolean = this.enableBackfaceCulling,
        enableBoundsChecking: Boolean = this.enableBoundsChecking,
        enableBroadPhaseSort: Boolean = this.enableBroadPhaseSort,
        broadPhaseCellSize: Double = this.broadPhaseCellSize
    ): RenderOptions = RenderOptions(enableDepthSorting, enableBackfaceCulling, enableBoundsChecking, enableBroadPhaseSort, broadPhaseCellSize)

    override fun equals(other: Any?): Boolean = /* all 5 fields */
    override fun hashCode(): Int = /* all 5 fields */
    override fun toString(): String = /* all 5 fields */
}
```

#### 1h. `ColorPalette` in `CompositionLocals.kt` — `data class ColorPalette` → `class ColorPalette`

Will grow (onPrimary, warning, info, onSurface per review analysis). Convert. Same explicit `copy()` pattern.

### Verification

After step 1, run full test suite. Key checks:
- No `componentN()` calls exist in test code (verified by exploration — none found)
- `RenderOptions.copy()` in production (`IsometricView.kt` lines 38, 47, 55) and tests — covered by explicit `copy()` method with identical signature
- `RenderContext.copy()` in tests (`IsometricRendererTest.kt` lines 132, 161, 481, 586, 611) — now uses public-field-only `copy()` method; verify tests pass with reset transform state
- No `.copy()` calls found on `Point`, `Vector`, `RenderCommand`, or `PreparedScene` — safe to omit explicit `copy()`
- `equals`/`hashCode` behavior unchanged for existing fields
- Confirm `RenderContext` has no synthetic `copy()` exposing transform internals, no `componentN()`, and no destructuring support

---

## Step 2: Parameter Renaming (F26, F27, F28, F36)

### Best Practice

Direct breaking rename — no deprecation cycles per user preference (memory: `feedback_no_deprecation_cycles.md`).

### Changes

#### 2a. Shape `origin` → `position` (F26)

**Files**: `Prism.kt`, `Pyramid.kt`, `Cylinder.kt`, `Stairs.kt`, `Octahedron.kt`, `Knot.kt`

In each file, rename the constructor parameter from `origin` to `position`. Also rename the internal `createPaths(origin)` companion function parameter.

**Call site updates**: Search all files for `origin =` in shape constructor calls. Expected locations:
- Sample/activity files
- Test files
- `Knot.kt` internal composition (uses `Prism(Point.ORIGIN, 5.0, 1.0, 1.0)` positionally — positional callers are unaffected by parameter rename but must update for reorder if any)

**Do NOT rename**: `Point.ORIGIN` (it's the world zero point, not a shape placement), `rotationOrigin`/`scaleOrigin` in composables (those become `rotationPivot`/`scalePivot` — but that's in WS2/WS3, not WS1b; leave for now to avoid double-rename confusion).

#### 2b. Vector `i/j/k` → `x/y/z` (F27)

**File**: `Vector.kt`
**Current**: `class Vector(val i: Double, val j: Double, val k: Double)`
**After**: `class Vector(val x: Double, val y: Double, val z: Double)`

**Impact**: Every reference to `.i`, `.j`, `.k` on a Vector must change. Key locations:
- `Vector.kt` itself: `magnitude()`, `normalize()`, `crossProduct()`, `dotProduct()`, `fromTwoPoints()`
- `IsometricEngine.kt`: `transformColor()` uses `lightDirection.i/j/k` and cross product components
- `IsometricEngine.kt`: `DEFAULT_LIGHT_DIRECTION` is `Vector(2.0, -1.0, 3.0)` (defined in `IsometricEngine.Companion`, imported by `IsometricScene.kt` and `RenderContext.kt`)
- `RenderContext.kt`: `lightDirection` usage

Use IDE "Rename Symbol" or find-and-replace with careful scoping to Vector accesses only.

**Watch out**: `Vector.crossProduct()` has local variables named `val i`, `val j`, `val k` that shadow the current field names. After renaming the fields to `x/y/z`, the locals no longer shadow anything — but they become confusing since `i/j/k` no longer corresponds to any fields. Rename the locals to `cx/cy/cz` (or `rx/ry/rz` for "result") to avoid confusion.

#### 2c. Shape dimensions `dx/dy/dz` → `width/depth/height` (F28)

**Files**: `Prism.kt`, `Pyramid.kt`

**Current** (Prism): `class Prism(origin: Point, dx: Double = 1.0, dy: Double = 1.0, dz: Double = 1.0)`
**After**: `class Prism(position: Point = Point.ORIGIN, width: Double = 1.0, depth: Double = 1.0, height: Double = 1.0)`

Note: This combines with F26 (origin → position) and F10 (default position from WS1a). Apply all three simultaneously per file.

**Internal path computation** in `createPaths()` references `dx`/`dy`/`dz` — update those too.

#### 2d. Cylinder parameter reorder (F36)

**Current**: `Cylinder(origin: Point, radius: Double = 1.0, vertices: Int = 20, height: Double = 1.0)`
**After**: `Cylinder(position: Point = Point.ORIGIN, radius: Double = 1.0, height: Double = 1.0, vertices: Int = 20)`

**Breaking**: Positional callers of `Cylinder(origin, radius, vertices, height)` break. All call sites must update to named arguments or new positional order.

---

## Step 3: Operator Overloads on Point and Vector (F65)

**Point.kt** — add operators:
```kotlin
operator fun plus(other: Point): Point = Point(x + other.x, y + other.y, z + other.z)
operator fun plus(v: Vector): Point = Point(x + v.x, y + v.y, z + v.z)  // uses v.x after F27 rename
operator fun minus(other: Point): Vector = Vector(x - other.x, y - other.y, z - other.z)
operator fun minus(v: Vector): Point = Point(x - v.x, y - v.y, z - v.z)
operator fun times(scalar: Double): Point = Point(x * scalar, y * scalar, z * scalar)
operator fun unaryMinus(): Point = Point(-x, -y, -z)
```

**Vector.kt** — add operators + infix:
```kotlin
operator fun plus(other: Vector): Vector = Vector(x + other.x, y + other.y, z + other.z)
operator fun minus(other: Vector): Vector = Vector(x - other.x, y - other.y, z - other.z)
operator fun times(scalar: Double): Vector = Vector(x * scalar, y * scalar, z * scalar)
operator fun unaryMinus(): Vector = Vector(-x, -y, -z)
infix fun cross(other: Vector): Vector = Vector(
    y * other.z - z * other.y,
    z * other.x - x * other.z,
    x * other.y - y * other.x
)
infix fun dot(other: Vector): Double = x * other.x + y * other.y + z * other.z
```

**Companion statics**: Keep `Vector.crossProduct()` and `Vector.dotProduct()` for now (WS3 may address removal). The infix operators are the idiomatic path.

---

## Step 4: Self-Returning Transforms via Covariant Overrides (F73)

### Problem

`Prism(...).translate(1.0, 0.0, 0.0)` returns `Shape`, not `Prism`. The subtype is lost through the transform chain, requiring unsafe casts.

### Prerequisite — Make `Shape.translate()` `open`

Currently `Shape.translate()` is **non-open** (Shape.kt line 14). It must be changed to `open` to enable subclass overrides. Only `translate()` needs this change — see below for why rotation/scale are excluded.

> **F66 is redundant.** Shape subclasses (`Prism`, `Pyramid`, `Cylinder`, etc.) are intentionally kept as concrete subclasses of an open `Shape` class. Users must be able to define their own shape types via subclassing. Neither factory functions nor a sealed hierarchy is appropriate. F73 (self-returning generic transforms) is the correct fix for subtype loss on transform methods.

### Which Transforms Can Preserve Type?

> **F73 scope: `translate()` only for self-returning subtype.** Reconstruction from stored params works for `translate()` (just shift position), but NOT for `rotateX/Y/Z` or `scale` — a rotated `Prism` cannot be expressed as a `Prism` with different stored params, because axis-aligned faces are no longer axis-aligned after rotation. Design: subclasses override `translate()` to return `Self` (Kotlin supports covariant return types); `rotateX/Y/Z` and `scale` remain returning `Shape`. This covers the dominant use case (positioning shapes) without requiring complex lazy-transform machinery.

**Only `translate()`** can safely return the concrete subtype. Translation shifts the position without changing the shape's geometry — so a translated `Prism` is still fully describable as `Prism(newPosition, width, depth, height)`.

**Rotation and scale CANNOT preserve type.** A `Prism(width=2, depth=1, height=3)` is defined by axis-aligned parameters. After `rotateX(origin, PI/4)`, the prism is physically rotated in space — its faces are no longer axis-aligned, and it cannot be reconstructed from `(position, width, depth, height)`. An override like `Prism(position.rotateX(origin, angle), width, depth, height)` would only rotate the position point and then generate a brand-new axis-aligned prism at that position — **producing wrong geometry**.

Therefore: `rotateX/Y/Z` and `scale` remain inherited from the base `Shape` class. They return `Shape`, losing the concrete subtype but producing correct geometry. This is the standard trade-off in parametric shape libraries.

### Prerequisite — Promote Constructor Params to `val` Properties

> **F73 prerequisite: store constructor params as `val` properties.** Currently shape subclasses pass their constructor parameters to the parent `Shape(createPaths(...))` and discard them — e.g., `Prism(origin, dx, dy, dz)` stores nothing. Self-returning transforms need to reconstruct the shape from its dimensions (`Prism(position.translate(dx, dy, dz), width, depth, height)`), which requires the subclass to retain `val position`, `val width`, `val depth`, `val height`. This storage change is part of F73's implementation, applied during the F26/F28 renames (same parameters, same files).

**Memory trade-off**: Promoting params to `val` adds storage per shape instance (e.g., Prism gains `position: Point` + 3 `Double` fields = ~32 bytes). For scenes with thousands of shapes, this is measurable but modest — and it's the standard trade-off for type-preserving transforms. Without stored params, covariant overrides are impossible.

### Best Practice for Kotlin — Covariant Return Types

Kotlin allows overrides to narrow the return type. Each subclass overrides `translate()` with its concrete return type — no generics, no casts:

```kotlin
// Base class — only translate() is open
open class Shape(val paths: List<Path>) {
    open fun translate(dx: Double, dy: Double, dz: Double): Shape =
        Shape(paths.map { it.translate(dx, dy, dz) })

    // Rotation/scale stay non-open — they transform paths directly,
    // which is always correct regardless of subtype.
    fun rotateX(origin: Point, angle: Double): Shape =
        Shape(paths.map { it.rotateX(origin, angle) })
    fun rotateY(origin: Point, angle: Double): Shape = ...
    fun rotateZ(origin: Point, angle: Double): Shape = ...
    fun scale(origin: Point, dx: Double, dy: Double, dz: Double): Shape = ...
}

// Each built-in subclass — overrides translate() only
class Prism(
    val position: Point = Point.ORIGIN,
    val width: Double = 1.0,
    val depth: Double = 1.0,
    val height: Double = 1.0
) : Shape(createPrismPaths(position, width, depth, height)) {

    override fun translate(dx: Double, dy: Double, dz: Double): Prism =
        Prism(position.translate(dx, dy, dz), width, depth, height)
}
```

Usage:
```kotlin
val p: Prism = Prism().translate(1.0, 0.0, 0.0)  // returns Prism — type preserved
val s: Shape = p.rotateZ(Point.ORIGIN, PI / 4)    // returns Shape — type lost, geometry correct
```

**Why not generics**: A generic `fun <T : Shape> translate(...): T` is unsound — the caller must provide the type parameter explicitly (`prism.translate<Prism>(...)`), and nothing prevents `prism.translate<Cylinder>(...)` which compiles but throws `ClassCastException`. Covariant overrides are type-safe, require zero casts, and Kotlin infers the return type automatically.

**User-defined subclasses**: Users who subclass `Shape` can override `translate()` the same way. If they don't override, they get the base `Shape` return — a safe, expected fallback. Rotation/scale always produce correct geometry via the base class.

**Path/Circle note**: `Path` has one subclass — `Circle` in `io.fabianterhorst.isometric.paths`. `Circle.translate()` returns `Path` (loses `Circle` type), which is correct: a translated circle is geometrically just a set of translated points; the `origin`/`radius`/`vertices` defining parameters don't survive transforms. No covariant override needed.

### Verification

- `Prism().translate(1.0, 0.0, 0.0)` infers return type `Prism` (not `Shape`)
- `Prism().rotateZ(Point.ORIGIN, PI / 4)` returns `Shape` — no override, base class behavior
- User subclass without `translate()` override: `class Wall(...) : Shape(...)` — `Wall().translate(...)` returns `Shape`, not `Wall`. Safe fallback.
- All existing tests pass unchanged

---

## Step 5: HitOrder Enum + touchRadius (F37)

> **Note**: F37 moved from WS7 to WS1b — it's an API naming/design change, not a Compose runtime correctness issue.

### Rationale

`findItemAt(x, y, reverseSort = true, useRadius = false, radius = 8.0)` has two problems:

1. **Boolean params are opaque at call sites**: `findItemAt(x, y, true, false, 10.0)` — what does `true` mean? What does `false` mean? Readers must consult the signature to understand intent.

2. **`useRadius` is redundant**: A non-zero `radius` value inherently implies radius-based hit testing. The boolean `useRadius` exists only because `radius` has a non-zero default (8.0) that would activate even when radius testing is unwanted. The fix is to default `radius` to `0.0` and use the value itself as the activation signal.

3. **`reverseSort` encodes domain meaning in implementation terms**: "reverse sort" describes *how* (implementation), not *what* (intent). The caller wants front-to-back or back-to-front hit ordering — a domain concept that deserves a named type.

### Best Practice

Replace boolean parameters with enum types when the boolean encodes a domain concept. Replace redundant boolean+value pairs by using sentinel defaults (0.0 means "disabled"). Kotlin's named arguments make enum parameters self-documenting at call sites: `findItemAt(x, y, order = HitOrder.FRONT_TO_BACK, touchRadius = 12.0)`.

### Files and Changes

#### 5a. New enum: `HitOrder.kt`

**New file**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/HitOrder.kt`

```kotlin
package io.fabianterhorst.isometric

/**
 * Controls the search order for hit testing.
 *
 * @property FRONT_TO_BACK Search from the frontmost (topmost rendered) item backward.
 *   Use for click/tap handling where the user expects to hit the visually topmost shape.
 * @property BACK_TO_FRONT Search from the backmost (bottom rendered) item forward.
 *   Use for ray-casting or depth queries where the first occluded shape is desired.
 */
enum class HitOrder {
    FRONT_TO_BACK,
    BACK_TO_FRONT
}
```

#### 5b. `IsometricEngine.kt` — `findItemAt` signature change

**Current** (line 156):
```kotlin
fun findItemAt(
    preparedScene: PreparedScene,
    x: Double,
    y: Double,
    reverseSort: Boolean = true,
    useRadius: Boolean = false,
    radius: Double = 8.0
): RenderCommand? {
    val commandsList = if (reverseSort) {
        preparedScene.commands.reversed()
    } else {
        preparedScene.commands
    }

    for (command in commandsList) {
        val hull = buildConvexHull(command.points)

        val isInside = if (useRadius) {
            IntersectionUtils.isPointCloseToPoly(
                hull.map { Point(it.x, it.y, 0.0) },
                x, y, radius
            ) || IntersectionUtils.isPointInPoly(
                hull.map { Point(it.x, it.y, 0.0) },
                x, y
            )
        } else {
            IntersectionUtils.isPointInPoly(
                hull.map { Point(it.x, it.y, 0.0) },
                x, y
            )
        }

        if (isInside) {
            return command
        }
    }
    return null
}
```

**After**:
```kotlin
fun findItemAt(
    preparedScene: PreparedScene,
    x: Double,
    y: Double,
    order: HitOrder = HitOrder.FRONT_TO_BACK,
    touchRadius: Double = 0.0
): RenderCommand? {
    val commandsList = when (order) {
        HitOrder.FRONT_TO_BACK -> preparedScene.commands.reversed()
        HitOrder.BACK_TO_FRONT -> preparedScene.commands
    }

    for (command in commandsList) {
        val hull = buildConvexHull(command.points)
        val hullPoints = hull.map { Point(it.x, it.y, 0.0) }

        val isInside = if (touchRadius > 0.0) {
            IntersectionUtils.isPointCloseToPoly(hullPoints, x, y, touchRadius)
                || IntersectionUtils.isPointInPoly(hullPoints, x, y)
        } else {
            IntersectionUtils.isPointInPoly(hullPoints, x, y)
        }

        if (isInside) {
            return command
        }
    }
    return null
}
```

**Key changes**:
- `reverseSort: Boolean = true` → `order: HitOrder = HitOrder.FRONT_TO_BACK` (same default behavior)
- `useRadius: Boolean = false, radius: Double = 8.0` → `touchRadius: Double = 0.0` (default 0.0 = no radius, equivalent to old `useRadius = false`)
- `hullPoints` extracted to avoid duplicate `.map {}` call in the radius branch

#### 5c. Call site updates

All surviving call sites must be updated. No deprecation — direct breaking change. If WS4 has already deleted `IsometricCanvas.kt`, skip that call site rather than reviving legacy code just to rename it.

**`IsometricRenderer.kt`** (lines 293, 309) — 2 call sites:
```kotlin
// Before
engine.findItemAt(
    preparedScene = filteredScene,
    x = x, y = y,
    reverseSort = true,
    useRadius = true,
    radius = HIT_TEST_RADIUS_PX
)

// After
engine.findItemAt(
    preparedScene = filteredScene,
    x = x, y = y,
    order = HitOrder.FRONT_TO_BACK,
    touchRadius = HIT_TEST_RADIUS_PX
)
```

**`IsometricCanvas.kt`** (line 88) — 1 call site:
```kotlin
// Before
state.engine.findItemAt(
    preparedScene = tempScene,
    x = offset.x.toDouble(),
    y = offset.y.toDouble(),
    reverseSort = true,
    useRadius = true,
    radius = 8.0
)

// After
state.engine.findItemAt(
    preparedScene = tempScene,
    x = offset.x.toDouble(),
    y = offset.y.toDouble(),
    order = HitOrder.FRONT_TO_BACK,
    touchRadius = 8.0
)
```

Only apply this edit if `IsometricCanvas.kt` still exists locally; otherwise the WS4 deletion already removed it from scope.

**`IsometricView.kt`** (line 157) — 1 call site:
```kotlin
// Before
engine.findItemAt(
    preparedScene = scene,
    x = event.x.toDouble(),
    y = event.y.toDouble(),
    reverseSort = reverseSortForLookup,
    useRadius = touchRadiusLookup,
    radius = touchRadius
)

// After
engine.findItemAt(
    preparedScene = scene,
    x = event.x.toDouble(),
    y = event.y.toDouble(),
    order = if (reverseSortForLookup) HitOrder.FRONT_TO_BACK else HitOrder.BACK_TO_FRONT,
    touchRadius = if (touchRadiusLookup) touchRadius else 0.0
)
```

Note: `IsometricView.kt` uses runtime booleans `reverseSortForLookup` and `touchRadiusLookup`. These should be traced to their declaration and refactored to use `HitOrder` and `Double` directly. If they are public properties of `IsometricView`, they become:
- `reverseSortForLookup: Boolean` → `hitOrder: HitOrder = HitOrder.FRONT_TO_BACK`
- `touchRadiusLookup: Boolean` + `touchRadius: Double` → `touchRadius: Double = 0.0`

**`IsometricEngineTest.kt`** (lines 62, 66) — 2 call sites:
```kotlin
// Before
engine.findItemAt(scene, avgX, avgY, reverseSort = true)

// After
engine.findItemAt(scene, avgX, avgY, order = HitOrder.FRONT_TO_BACK)
```

### Verification

- All existing hit-test tests pass with updated call sites.
- Add test: `findItemAt` with `order = HitOrder.BACK_TO_FRONT` returns the backmost item when shapes overlap.
- Add test: `findItemAt` with `touchRadius = 0.0` does not match points near edges (same as old `useRadius = false`).
- Add test: `findItemAt` with `touchRadius = 10.0` matches points within 10px of edges (same as old `useRadius = true, radius = 10.0`).

---

## Cross-Workstream Dependencies

| This step | Produces | Consumed by |
|-----------|----------|-------------|
| Step 1 (data class conversion) | Regular `RenderContext` with public-field-only `copy()` | WS4 F75 — **resolved**: F75 is now merged into this step; no separate WS4 coordination needed |
| Step 1 (data class conversion) | Regular `RenderOptions` with explicit `copy()` | WS3 F15 (rename `Quality` preset) — the preset constants stay the same, just the class changes |
| Step 1 (data class conversion) | Regular `RenderCommand` | WS3 F33 (rename `id` → `commandId`) — field rename on a regular class is simpler |
| Step 2 (Vector rename) | `Vector(x, y, z)` | WS5 F52 (`transformColor()` uses `lightDirection.i/j/k` → `.x/.y/.z`) |
| Step 2 (param renames) | `position`, `width/depth/height` | WS1a validation — combined single-pass implementation |
| Step 3 (operators) | `v1 cross v2`, `v1 dot v2` | WS5 F52 (replace manual cross product in `transformColor()` with infix operator) |
| Step 4 (open Shape, covariant translate) | Extensible `Shape` base with overridable `translate()` | WS6 F58 (`CustomNode` can accept any `Shape` subclass including user-defined); user subclasses can override `translate()` |
| Step 4 (covariant transforms) | Subclass `val` properties (position, width, etc.) | WS6 F73 (user subclasses can override transforms with same pattern) |
| Step 5 (HitOrder enum) | `HitOrder.FRONT_TO_BACK` / `BACK_TO_FRONT` | WS2 F32 (gesture callback redesign) — the hit-test API is called from gesture handlers. WS2 may wrap `findItemAt` differently, but the `HitOrder` enum and `touchRadius` parameter survive any wrapping. |
| Step 5 (HitOrder enum) | Removed `reverseSort`/`useRadius` booleans | WS3 F15 (boolean flag renaming) — F37's booleans are deleted entirely, reducing WS3's scope |

---

## File Change Summary

| File | Steps | Changes |
|------|-------|---------|
| `Point.kt` | 1, 3 | data→class (no copy() needed — unused), operators |
| `Vector.kt` | 1, 2, 3 | data→class (no copy() needed — unused), i/j/k→x/y/z, operators + infix cross/dot |
| `IsoColor.kt` | 1 | KEEP as data class — no change in this step |
| `Shape.kt` | 4 | Make `translate()` `open`, covariant base return |
| `Prism.kt` | 2, 4 | origin→position, dx/dy/dz→width/depth/height, promote params to `val`, covariant `translate()` override |
| `Pyramid.kt` | 2, 4 | origin→position, dx/dy/dz→width/depth/height, promote params to `val`, covariant `translate()` override |
| `Cylinder.kt` | 2, 4 | origin→position, reorder params (height before vertices), promote params to `val`, covariant `translate()` override |
| `Stairs.kt` | 2 | origin→position |
| `Octahedron.kt` | 2 | origin→position |
| `Knot.kt` | 2 | origin→position |
| `RenderContext.kt` | 1 | data→class, keep `private` visibility, explicit public-field-only `copy()`, `withTransform()` uses direct constructor (F71+F75 merged) |
| `RenderCommand.kt` | 1 | data→class (no copy() needed — unused) |
| `PreparedScene.kt` | 1 | data→class (no copy() needed — unused) |
| `RenderOptions.kt` | 1 | data→class, explicit `copy()` |
| `CompositionLocals.kt` | 1 | ColorPalette data→class |
| `HitOrder.kt` | 5 | **new file** — `enum class HitOrder { FRONT_TO_BACK, BACK_TO_FRONT }` |
| `IsometricEngine.kt` | 2*, 5 | Vector field refs update (`.i/.j/.k` → `.x/.y/.z`), `findItemAt` signature: remove `reverseSort`/`useRadius`/`radius`, add `order`/`touchRadius` |
| `IsometricRenderer.kt` | 5 | 2 `findItemAt` call sites updated to `HitOrder`/`touchRadius` |
| `IsometricCanvas.kt` | 5 | 1 `findItemAt` call site updated (if file still exists before WS4 deletion) |
| `IsometricView.kt` | 5 | 1 `findItemAt` call site updated; `reverseSortForLookup`/`touchRadiusLookup` properties replaced with `hitOrder`/`touchRadius` |
| `IsometricEngineTest.kt` | 5 | 2 `findItemAt` call sites updated |
| `IsometricRendererTest.kt` | 1 | update `RenderContext.copy()` calls (5 locations) — now uses public-field-only `copy()` |

---

## Review-Doc Callouts (verbatim from `api-first-run-usability-review.md` §16 WS1b)

> **F66 is redundant.** Shape subclasses (`Prism`, `Pyramid`, `Cylinder`, etc.) are intentionally kept as concrete subclasses of an open `Shape` class. Users must be able to define their own shape types via subclassing. Neither factory functions nor a sealed hierarchy is appropriate. F73 (self-returning generic transforms) is the correct fix for subtype loss on transform methods.

> **F73 prerequisite: store constructor params as `val` properties.** Currently shape subclasses pass their constructor parameters to the parent `Shape(createPaths(...))` and discard them — e.g., `Prism(origin, dx, dy, dz)` stores nothing. Self-returning transforms need to reconstruct the shape from its dimensions (`Prism(position.translate(dx, dy, dz), width, depth, height)`), which requires the subclass to retain `val position`, `val width`, `val depth`, `val height`. This storage change is part of F73's implementation, applied during the F26/F28 renames (same parameters, same files).

> **F73 scope: `translate()` only for self-returning subtype.** Reconstruction from stored params works for `translate()` (just shift position), but NOT for `rotateX/Y/Z` or `scale` — a rotated `Prism` cannot be expressed as a `Prism` with different stored params, because axis-aligned faces are no longer axis-aligned after rotation. Design: subclasses override `translate()` to return `Self` (Kotlin supports covariant return types); `rotateX/Y/Z` and `scale` remain returning `Shape`. This covers the dominant use case (positioning shapes) without requiring complex lazy-transform machinery.

> **F71 and F75 are merged here** (previously F75 was in WS4). Both convert the same public types from `data class` to regular class — `RenderContext`, `RenderOptions`, `PreparedScene`, `RenderCommand`, `ColorPalette`. Doing `data class` removal (F71) and `copy()` blocking (F75) in the same pass avoids two edits to the same files.
