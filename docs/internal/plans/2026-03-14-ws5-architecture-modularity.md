# WS5: Architecture & Modularity — Detailed Implementation Plan

> **Workstream**: 5 of 8
> **Phase**: 3 (after WS3)
> **Scope**: Decompose god objects, deduplicate conversions, Kotlin idiom cleanup, module hygiene
> **Findings**: F48, F49, F50, F52, F53, F54, F55, F68, F69, F77
> **Depends on**: WS3 (method/type renames in same code being split), WS1b (Vector operators, open `Shape` base)
> **Review ref**: `docs/reviews/api-first-run-usability-review.md` §3.7, §3.9

---

## Execution Order

The 10 findings decompose into 8 ordered steps. Each step is self-contained and produces a compilable codebase.

1. **Step 1**: Quick wins — Java collections, member extensions (F69, F68)
2. **Step 2**: Deduplicate conversions (F50)
3. **Step 3**: Accumulator render pattern (F77)
4. **Step 4**: Replace manual cross product with infix operators (F52)
5. **Step 5**: Extract `SceneProjector` interface (F53)
6. **Step 6**: Decompose `IsometricEngine` (F48)
7. **Step 7**: Decompose `IsometricRenderer` (F49)
8. **Step 8**: Module cleanup — remove `:lib`, tighten `api` to `implementation` (F54, F55)

Steps 1-4 are independent quick wins that can be parallelized after WS3 completes. Steps 5-7 are sequential (interface extraction enables decomposition, engine decomposition informs renderer decomposition). Step 8 runs last since module boundaries depend on the final public API surface.

**Naming baseline**: WS5 starts after WS3, so implementation should use the settled public names: `IsoColor`, `projectScene()`, `clearCache()`, `markClean()`, `commandId`, `PreparedScene.width/height`, and `HitOrder`/`touchRadius`. If any illustrative snippet below still shows a pre-WS3 identifier, translate it mechanically before coding.

---

## Step 1: Quick Wins — Java Collections, Member Extensions (F69, F68)

> **F67 moved to WS1a** — it's a one-line sort fix + depth precalculation on `Path`, not an architectural concern. Should not be blocked behind WS3/WS5.

### 1a. Replace Java collection constructors with Kotlin factories (F69)

#### Rationale

`HashMap()`, `ArrayList()`, `HashSet()` are Java-isms. Kotlin provides `hashMapOf()`, `mutableListOf()`, `hashSetOf()` (or `linkedSetOf()`) that produce the same backing types with idiomatic naming.

#### Files and Changes

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`

**Line 379** — `buildBroadPhaseCandidatePairs()`:
```kotlin
// Before:
val grid = HashMap<Long, MutableList<Int>>()
// After:
val grid = hashMapOf<Long, MutableList<Int>>()
```

**Line 395**:
```kotlin
// Before:
val seen = HashSet<Long>()
// After:
val seen = hashSetOf<Long>()
```

**Line 396**:
```kotlin
// Before:
val pairs = ArrayList<Pair<Int, Int>>()
// After:
val pairs = mutableListOf<Pair<Int, Int>>()
```

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRenderer.kt`

**Lines 448-450** — `buildCommandMaps()`:
```kotlin
// Before:
val cmdMap = HashMap<String, RenderCommand>(scene.commands.size)
val orderMap = HashMap<String, Int>(scene.commands.size)
val cmdToNode = HashMap<String, IsometricNode>(scene.commands.size)

// After:
val cmdMap = HashMap<String, RenderCommand>(scene.commands.size)
val orderMap = HashMap<String, Int>(scene.commands.size)
val cmdToNode = HashMap<String, IsometricNode>(scene.commands.size)
```

**Note**: The `IsometricRenderer` calls use `HashMap(initialCapacity)` which is a performance optimization for pre-sized maps. Kotlin's `hashMapOf()` does not accept a capacity argument. Keep the `HashMap(capacity)` constructor here — the Java-ism is justified by the performance intent. Only replace zero-arg Java constructors.

### 1b. Move member extensions to top-level (F68)

#### Rationale

Extensions defined inside an `object` body require `with(ComposeRenderer) { ... }` dispatch, defeating the purpose of extension functions. Top-level extensions are callable from any scope.

#### Files and Changes

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/ComposeRenderer.kt`

**Current** (lines 16-74): `object ComposeRenderer` contains three member extensions:
- `DrawScope.renderIsometric()` (line 21)
- `IsoColor.toComposeColor()` (line 54)
- `Color.toIsoColor()` (line 66)

**After**: Delete the `object ComposeRenderer` wrapper entirely. Make all three functions top-level in the same file:

```kotlin
package io.fabianterhorst.isometric.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderCommand

/**
 * Render a prepared scene using Compose DrawScope
 */
fun DrawScope.renderIsometric(scene: PreparedScene, strokeWidth: Float = 1f, drawStroke: Boolean = true) {
    for (command in scene.commands) {
        val path = command.toComposePath()
        val color = command.color.toComposeColor()
        drawPath(path, color, style = Fill)
        if (drawStroke) {
            drawPath(path, Color.Black.copy(alpha = 0.1f), style = Stroke(width = strokeWidth))
        }
    }
}

/**
 * Convert IsoColor to Compose Color
 */
fun IsoColor.toComposeColor(): Color {
    return Color(
        red = (r.toFloat() / 255f).coerceIn(0f, 1f),
        green = (g.toFloat() / 255f).coerceIn(0f, 1f),
        blue = (b.toFloat() / 255f).coerceIn(0f, 1f),
        alpha = (a.toFloat() / 255f).coerceIn(0f, 1f)
    )
}

/**
 * Convert Compose Color to IsoColor
 */
fun Color.toIsoColor(): IsoColor {
    return IsoColor(
        r = (red * 255).toDouble(),
        g = (green * 255).toDouble(),
        b = (blue * 255).toDouble(),
        a = (alpha * 255).toDouble()
    )
}

private fun RenderCommand.toComposePath(): Path {
    return Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        for (i in 1 until points.size) {
            lineTo(points[i].x.toFloat(), points[i].y.toFloat())
        }
        close()
    }
}
```

**Call site updates**: Any `with(ComposeRenderer) { ... }` blocks become direct calls. Search for `ComposeRenderer` usage across the codebase. Expected locations:
- Any still-unmigrated legacy wrapper that uses `with(ComposeRenderer) { renderIsometric(...) }` before WS4 deletes it
- Any test files referencing `ComposeRenderer`

**Breaking change**: `ComposeRenderer.toComposeColor()` and `ComposeRenderer.toIsoColor()` — callers using `with(ComposeRenderer)` dispatch break. Direct rename, no deprecation.

### Verification

After step 1, run full test suite. Key checks:
- `orderedPaths()` produces the same sort order (descending depth)
- No `with(ComposeRenderer)` calls remain
- Collection behavior unchanged (HashMap/HashSet semantics are identical)

---

## Step 2: Deduplicate Conversions (F50)

### Rationale

`toComposePath()` appears in three locations and `toComposeColor()` appears in two locations. Bounding-box computation is also duplicated between `IsometricEngine` and `IsometricRenderer`. A single canonical location eliminates divergence risk and reduces the file sizes of the two god objects before decomposition.

### Files and Changes

#### 2a. Create shared conversions file

**New file**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/RenderExtensions.kt`

```kotlin
package io.fabianterhorst.isometric.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.RenderCommand

/**
 * Convert RenderCommand to Compose Path
 */
fun RenderCommand.toComposePath(): Path {
    return Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        for (i in 1 until points.size) {
            lineTo(points[i].x.toFloat(), points[i].y.toFloat())
        }
        close()
    }
}

/**
 * Convert IsoColor to Compose Color
 */
fun IsoColor.toComposeColor(): Color {
    return Color(
        red = (r.toFloat() / 255f).coerceIn(0f, 1f),
        green = (g.toFloat() / 255f).coerceIn(0f, 1f),
        blue = (b.toFloat() / 255f).coerceIn(0f, 1f),
        alpha = (a.toFloat() / 255f).coerceIn(0f, 1f)
    )
}
```

**Note**: WS1 creates a `ColorExtensions.kt` file with `Color.toIsoColor()` and `IsoColor.toComposeColor()`. Coordinate: if WS1 runs first, that file already exists — merge `toComposePath()` into it or keep them as two focused files (`ColorExtensions.kt` for color bridge, `RenderExtensions.kt` for path conversion). If WS5 runs first, create `RenderExtensions.kt` with both path and color conversions; WS1 later moves color-only functions to `ColorExtensions.kt`.

#### 2b. Remove duplicates from `IsometricRenderer.kt`

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRenderer.kt`

Delete these private functions (lines 585-656):
- `private fun RenderCommand.toComposePath()` (lines 585-595)
- `private fun IsoColor.toComposeColor()` (lines 649-656)

Add import at top of file:
```kotlin
import io.fabianterhorst.isometric.compose.toComposePath
import io.fabianterhorst.isometric.compose.toComposeColor
```

**Keep** the Android-specific conversions in `IsometricRenderer.kt` for now:
- `private fun RenderCommand.toNativePath()` (lines 601-611) — Android-only, moves to `androidMain` in Step 7
- `private fun IsoColor.toAndroidColor()` (lines 662-669) — Android-only, moves to `androidMain` in Step 7

#### 2c. Remove duplicates from `ComposeRenderer.kt`

After Step 1b moved the member extensions to top-level, the `toComposePath()` and `toComposeColor()` in that file are already the canonical versions. In Step 2, either:
- Move them to `RenderExtensions.kt` and import from there, or
- Keep them in `ComposeRenderer.kt` if that file becomes the canonical location

**Recommendation**: Move to `RenderExtensions.kt` for a single source of truth. `ComposeRenderer.kt` keeps only `renderIsometric()` (which imports the shared extensions).

### Verification

- All usages of `toComposePath()` and `toComposeColor()` resolve to the shared file
- Search for `private fun.*toComposePath` and `private fun.*toComposeColor` — should find zero results
- Rendered output unchanged (pixel-identical)

---

## Step 3: Accumulator Render Pattern (F77)

### Rationale

Every node's `render()` allocates and returns a new `List<RenderCommand>`. `GroupNode.render()` uses `flatMap` which creates additional intermediate lists. For a scene with 300 shapes, each with 6 faces, this allocates 300+ lists per frame. The accumulator pattern `renderTo(output: MutableList<RenderCommand>)` reuses a single pre-allocated list.

### Files and Changes

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricNode.kt`

#### 3a. Replace `render()` with `renderTo()` in `IsometricNode`

**Current** (line 101):
```kotlin
abstract fun render(context: RenderContext): List<RenderCommand>
```

**After**:
```kotlin
abstract fun renderTo(output: MutableList<RenderCommand>, context: RenderContext)
```

#### 3b. Update `GroupNode.renderTo()`

**Current** (lines 111-127):
```kotlin
override fun render(context: RenderContext): List<RenderCommand> {
    if (!isVisible) return emptyList()

    val childContext = context.withTransform(
        position = position,
        rotation = rotation,
        scale = scale,
        rotationOrigin = rotationOrigin,
        scaleOrigin = scaleOrigin
    )

    return childrenSnapshot.flatMap { child ->
        child.render(childContext)
    }
}
```

**After**:
```kotlin
override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
    if (!isVisible) return

    val childContext = context.withTransform(
        position = position,
        rotation = rotation,
        scale = scale,
        rotationOrigin = rotationOrigin,
        scaleOrigin = scaleOrigin
    )

    for (child in childrenSnapshot) {
        child.renderTo(output, childContext)
    }
}
```

The `flatMap` becomes a simple `for` loop — zero intermediate list allocations, zero iterator allocations.

#### 3c. Update `ShapeNode.renderTo()`

**Current** (lines 140-170):
```kotlin
override fun render(context: RenderContext): List<RenderCommand> {
    if (!isVisible) return emptyList()

    var transformedShape = context.applyTransformsToShape(shape)
    transformedShape = transformedShape.translate(position.x, position.y, position.z)
    // ... rotation, scale ...

    return transformedShape.paths.map { path ->
        RenderCommand(
            commandId = "${nodeId}_${path.hashCode()}",
            points = emptyList(),
            color = color,
            originalPath = path,
            originalShape = transformedShape,
            ownerNodeId = nodeId
        )
    }
}
```

**After**:
```kotlin
override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
    if (!isVisible) return

    var transformedShape = context.applyTransformsToShape(shape)
    transformedShape = transformedShape.translate(position.x, position.y, position.z)

    if (rotation != 0.0) {
        val origin = rotationOrigin ?: position
        transformedShape = transformedShape.rotateZ(origin, rotation)
    }

    if (scale != 1.0) {
        val origin = scaleOrigin ?: position
        transformedShape = transformedShape.scale(origin, scale)
    }

    for (path in transformedShape.paths) {
        output.add(
            RenderCommand(
                commandId = "${nodeId}_${path.hashCode()}",
                points = emptyList(),
                color = color,
                originalPath = path,
                originalShape = transformedShape,
                ownerNodeId = nodeId
            )
        )
    }
}
```

#### 3d. Update `PathNode.renderTo()`

**Current** (lines 183-217): Returns `listOf(RenderCommand(...))`.

**After**:
```kotlin
override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
    if (!isVisible) return

    var transformedPath = path
    transformedPath = context.applyTransformsToPath(transformedPath)

    if (position.x != 0.0 || position.y != 0.0 || position.z != 0.0) {
        transformedPath = transformedPath.translate(position.x, position.y, position.z)
    }

    if (rotation != 0.0) {
        val origin = rotationOrigin ?: position
        transformedPath = transformedPath.rotateZ(origin, rotation)
    }

    if (scale != 1.0) {
        val origin = scaleOrigin ?: position
        transformedPath = transformedPath.scale(origin, scale)
    }

    output.add(
        RenderCommand(
            commandId = nodeId,
            points = emptyList(),
            color = color,
            originalPath = transformedPath,
            originalShape = null,
            ownerNodeId = nodeId
        )
    )
}
```

#### 3e. Update `BatchNode.renderTo()`

**Current** (lines 231-259): Uses `flatMapIndexed` + `map`.

**After**:
```kotlin
override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
    if (!isVisible) return

    shapes.forEachIndexed { index, shape ->
        var transformedShape = context.applyTransformsToShape(shape)
        transformedShape = transformedShape.translate(position.x, position.y, position.z)

        if (rotation != 0.0) {
            val origin = rotationOrigin ?: position
            transformedShape = transformedShape.rotateZ(origin, rotation)
        }

        if (scale != 1.0) {
            val origin = scaleOrigin ?: position
            transformedShape = transformedShape.scale(origin, scale)
        }

        for (path in transformedShape.paths) {
            output.add(
                RenderCommand(
                    commandId = "${nodeId}_${index}_${path.hashCode()}",
                    points = emptyList(),
                    color = color,
                    originalPath = path,
                    originalShape = transformedShape,
                    ownerNodeId = nodeId
                )
            )
        }
    }
}
```

#### 3f. Update caller in `IsometricRenderer.rebuildCache()`

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRenderer.kt`

**Current** (line 380):
```kotlin
val commands = rootNode.render(context)
```

**After**:
```kotlin
val commands = mutableListOf<RenderCommand>()
rootNode.renderTo(commands, context)
```

### Verification

- All `render(context)` calls replaced with `renderTo(output, context)`
- No `flatMap`, `map`, or `listOf` allocations in render paths
- Render output identical (same commands in same order)
- Existing tests that call `render()` must update to `renderTo()`

---

## Step 4: Replace Manual Cross Product with Infix Operators (F52)

### Rationale

`IsometricEngine.transformColor()` manually computes a cross product and normalization using raw component variables (lines 217-247). WS1 provides `infix fun Vector.cross(other: Vector)` and `infix fun Vector.dot(other: Vector)`. The engine should use its own domain types' utilities.

### Precondition

WS1 Step 5 must complete first — it adds the `cross` and `dot` infix operators to `Vector.kt` and renames `i/j/k` to `x/y/z`.

### Files and Changes

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`

**Current** (lines 217-247):
```kotlin
private fun transformColor(path: Path, color: IsoColor, lightDirection: Vector): IsoColor {
    if (path.points.size < 3) return color

    val p1 = path.points[1]
    val p2 = path.points[0]
    val i = p2.x - p1.x
    val j = p2.y - p1.y
    val k = p2.z - p1.z

    val p3 = path.points[2]
    val p4 = path.points[1]
    val i2 = p4.x - p3.x
    val j2 = p4.y - p3.y
    val k2 = p4.z - p3.z

    // Cross product to get normal
    val i3 = j * k2 - j2 * k
    val j3 = -1 * (i * k2 - i2 * k)
    val k3 = i * j2 - i2 * j

    // Normalize
    val magnitude = sqrt(i3 * i3 + j3 * j3 + k3 * k3)
    val normalI = if (magnitude == 0.0) 0.0 else i3 / magnitude
    val normalJ = if (magnitude == 0.0) 0.0 else j3 / magnitude
    val normalK = if (magnitude == 0.0) 0.0 else k3 / magnitude

    // Dot product with light angle
    val brightness = normalI * lightDirection.i + normalJ * lightDirection.j + normalK * lightDirection.k

    return color.lighten(brightness * colorDifference, lightColor)
}
```

**After** (using WS1's Vector operators — `x/y/z` field names and `cross`/`dot` infix):
```kotlin
private fun transformColor(path: Path, color: IsoColor, lightDirection: Vector): IsoColor {
    if (path.points.size < 3) return color

    val edge1 = Vector.fromTwoPoints(path.points[1], path.points[0])
    val edge2 = Vector.fromTwoPoints(path.points[2], path.points[1])

    val normal = (edge1 cross edge2).normalize()
    val brightness = normal dot lightDirection

    return color.lighten(brightness * colorDifference, lightColor)
}
```

This reduces 30 lines of component math to 5 lines of readable geometry. The `cross` and `dot` infix operators communicate the mathematical intent directly. The `sqrt` import may become unused — remove it if no other usage exists in the file.

### Verification

- Lighting output identical for all face orientations
- Normal vector computation mathematically equivalent (verified by the existing `Vector.crossProduct()` tests from WS1)
- `sqrt` import cleanup if unused

---

## Step 5: Extract `SceneProjector` Interface (F53)

### Rationale

`IsometricRenderer` takes a concrete `IsometricEngine` in its constructor. Tests must construct a real engine with real shapes and run real projection. An interface allows test doubles that return canned `PreparedScene` data.

### Files and Changes

#### 5a. Define the interface

**New file**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/SceneProjector.kt`

```kotlin
package io.fabianterhorst.isometric

/**
 * Abstraction over the isometric projection pipeline.
 * Allows the renderer to be tested with fake projectors.
 */
interface SceneProjector {
    /**
     * Add a shape to the scene graph.
     */
    fun add(shape: Shape, color: IsoColor)

    /**
     * Add a path to the scene graph with optional metadata.
     */
    fun add(
        path: Path,
        color: IsoColor,
        originalShape: Shape? = null,
        commandId: String? = null,
        ownerNodeId: String? = null
    )

    /**
     * Clear all items from the scene graph.
     */
    fun clear()

    /**
     * Project the scene to 2D and produce sorted render commands.
     */
    fun projectScene(
        width: Int,
        height: Int,
        renderOptions: RenderOptions = RenderOptions.Default,
        lightDirection: Vector
    ): PreparedScene

    /**
     * Find the frontmost item at a screen position (hit testing).
     */
    fun findItemAt(
        preparedScene: PreparedScene,
        x: Double,
        y: Double,
        order: HitOrder = HitOrder.FRONT_TO_BACK,
        touchRadius: Double = 0.0
    ): RenderCommand?
}
```

#### 5b. Implement the interface on `IsometricEngine`

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`

**Current** (line 13):
```kotlin
class IsometricEngine(
```

**After**:
```kotlin
class IsometricEngine(
    private val angle: Double = PI / 6,
    private val scale: Double = 70.0,
    private val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION,
    private val colorDifference: Double = 0.20,
    private val lightColor: IsoColor = IsoColor.WHITE
) : SceneProjector {
```

After WS3, the public engine surface already matches this contract semantically — `add()`, `clear()`, `projectScene()`, and `findItemAt()`. Add `override` keywords and align parameter names where needed.

#### 5c. Update `IsometricRenderer` constructor

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRenderer.kt`

**Current** (line 55):
```kotlin
class IsometricRenderer(
    private val engine: IsometricEngine,
```

**After**:
```kotlin
class IsometricRenderer(
    private val engine: SceneProjector,
```

All usages of `engine` in `IsometricRenderer` call `engine.clear()`, `engine.add()`, `engine.projectScene()`, and `engine.findItemAt()` — all present on the interface. No architectural call-site changes needed.

### Verification

- Existing code continues to pass `IsometricEngine` instances (which now implement `SceneProjector`)
- Tests can provide a fake `SceneProjector` that returns canned `PreparedScene` data
- Binary compatibility unchanged — the interface adds a supertype, doesn't remove one

---

## Step 6: Decompose `IsometricEngine` (F48)

### Rationale

`IsometricEngine` (491 lines) owns 7 unrelated responsibility groups: scene state, projection, lighting, culling, bounds checking, depth sorting (with broad-phase), and hit testing. Decomposing into focused collaborators behind a facade preserves the single-class ergonomics while making each concern independently testable.

### Files and Changes

#### 6a. Extract `SceneGraph`

**New file**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/SceneGraph.kt`

Extracts: `items`, `nextId`, `add()`, `clear()`, `SceneItem`

```kotlin
package io.fabianterhorst.isometric

/**
 * Mutable collection of scene items (paths with colors and metadata).
 * Accumulates items via [add] and provides them for projection via [items].
 */
internal class SceneGraph {
    internal data class SceneItem(
        val path: Path,
        val baseColor: IsoColor,
        val originalShape: Shape?,
        val id: String,
        val ownerNodeId: String? = null
    )

    private val _items = mutableListOf<SceneItem>()
    private var nextId = 0

    val items: List<SceneItem> get() = _items

    fun add(shape: Shape, color: IsoColor) {
        val paths = shape.orderedPaths()
        for (path in paths) {
            add(path, color, shape)
        }
    }

    fun add(
        path: Path,
        color: IsoColor,
        originalShape: Shape? = null,
        id: String? = null,
        ownerNodeId: String? = null
    ) {
        _items.add(SceneItem(path, color, originalShape, id ?: "item_${nextId++}", ownerNodeId))
    }

    fun clear() {
        _items.clear()
        nextId = 0
    }
}
```

#### 6b. Extract `IsometricProjection`

**New file**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricProjection.kt`

Extracts: `transformation` matrix, `translatePoint()`, `transformColor()`, `cullPath()`, `itemInDrawingBounds()`

```kotlin
package io.fabianterhorst.isometric

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure projection logic: 3D points to 2D screen coordinates,
 * lighting, back-face culling, and bounds checking.
 */
internal class IsometricProjection(
    angle: Double,
    private val scale: Double,
    lightDirection: Vector,
    private val colorDifference: Double,
    private val lightColor: IsoColor
) {
    private val transformation: Array<DoubleArray> = arrayOf(
        doubleArrayOf(scale * cos(angle), scale * sin(angle)),
        doubleArrayOf(scale * cos(PI - angle), scale * sin(PI - angle))
    )

    private val normalizedLightDirection: Vector = lightDirection.normalize()

    fun translatePoint(point: Point, originX: Double, originY: Double): Point2D {
        return Point2D(
            originX + point.x * transformation[0][0] + point.y * transformation[1][0],
            originY - point.x * transformation[0][1] - point.y * transformation[1][1] - (point.z * scale)
        )
    }

    fun transformColor(path: Path, color: IsoColor, lightDirection: Vector): IsoColor {
        if (path.points.size < 3) return color

        val edge1 = Vector.fromTwoPoints(path.points[1], path.points[0])
        val edge2 = Vector.fromTwoPoints(path.points[2], path.points[1])

        val normal = (edge1 cross edge2).normalize()
        val brightness = normal dot lightDirection

        return color.lighten(brightness * colorDifference, lightColor)
    }

    fun cullPath(transformedPoints: List<Point2D>): Boolean {
        if (transformedPoints.size < 3) return false

        val a = transformedPoints[0].x * transformedPoints[1].y
        val b = transformedPoints[1].x * transformedPoints[2].y
        val c = transformedPoints[2].x * transformedPoints[0].y

        val d = transformedPoints[1].x * transformedPoints[0].y
        val e = transformedPoints[2].x * transformedPoints[1].y
        val f = transformedPoints[0].x * transformedPoints[2].y

        val z = a + b + c - d - e - f
        return z > 0
    }

    fun itemInDrawingBounds(transformedPoints: List<Point2D>, width: Int, height: Int): Boolean {
        for (point in transformedPoints) {
            if (point.x >= 0 && point.x <= width && point.y >= 0 && point.y <= height) {
                return true
            }
        }
        return false
    }
}
```

**Note**: `transformColor()` uses `cross` and `dot` infix operators from Step 4. If Step 4 has not been applied yet (ordering flexibility), use the raw component math from the original code instead.

#### 6c. Extract `DepthSorter`

**New file**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/DepthSorter.kt`

Extracts: `sortPaths()`, `buildBroadPhaseCandidatePairs()`, `cellKey()`, `pairKey()`, `TransformedItem.getBounds()`, `ItemBounds`

```kotlin
package io.fabianterhorst.isometric

import kotlin.math.floor

/**
 * Intersection-based depth sorting with optional broad-phase acceleration.
 */
internal object DepthSorter {

    internal data class TransformedItem(
        val item: SceneGraph.SceneItem,
        val transformedPoints: List<Point2D>,
        val litColor: IsoColor
    )

    fun sort(items: List<TransformedItem>, options: RenderOptions): List<TransformedItem> {
        // ... existing sortPaths() logic moved here unchanged ...
    }

    // ... buildBroadPhaseCandidatePairs(), cellKey(), pairKey(), getBounds(), ItemBounds ...
}
```

The `TransformedItem` type moves here since it is consumed by the sorter. The engine's `projectScene()` method creates `TransformedItem` instances and passes them to `DepthSorter.sort()`.

#### 6d. Extract `HitTester`

**New file**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/HitTester.kt`

Extracts: `findItemAt()`, `buildConvexHull()`

```kotlin
package io.fabianterhorst.isometric

/**
 * Hit testing: find the frontmost render command at a screen coordinate.
 */
internal object HitTester {

    fun findItemAt(
        preparedScene: PreparedScene,
        x: Double,
        y: Double,
        order: HitOrder = HitOrder.FRONT_TO_BACK,
        touchRadius: Double = 0.0
    ): RenderCommand? {
        // ... existing findItemAt() logic moved here unchanged ...
    }

    private fun buildConvexHull(points: List<Point2D>): List<Point2D> {
        // ... existing buildConvexHull() logic moved here unchanged ...
    }
}
```

#### 6e. `IsometricEngine` becomes a thin facade

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`

**After** (reduced from 491 lines to ~80 lines):

```kotlin
package io.fabianterhorst.isometric

import kotlin.math.PI

class IsometricEngine(
    private val angle: Double = PI / 6,
    private val scale: Double = 70.0,
    private val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION,
    private val colorDifference: Double = 0.20,
    private val lightColor: IsoColor = IsoColor.WHITE
) : SceneProjector {

    companion object {
        val DEFAULT_LIGHT_DIRECTION = Vector(2.0, -1.0, 3.0)
    }

    private val sceneGraph = SceneGraph()
    private val projection = IsometricProjection(angle, scale, lightDirection, colorDifference, lightColor)

    override fun add(shape: Shape, color: IsoColor) = sceneGraph.add(shape, color)

    override fun add(
        path: Path, color: IsoColor, originalShape: Shape?,
        commandId: String?, ownerNodeId: String?
    ) = sceneGraph.add(path, color, originalShape, commandId, ownerNodeId)

    override fun clear() = sceneGraph.clear()

    override fun projectScene(
        width: Int, height: Int,
        renderOptions: RenderOptions, lightDirection: Vector
    ): PreparedScene {
        val normalizedLight = lightDirection.normalize()
        val originX = width / 2.0
        val originY = height * 0.9

        val transformedItems = sceneGraph.items.mapNotNull { item ->
            val transformedPoints = item.path.points.map { point ->
                projection.translatePoint(point, originX, originY)
            }

            if (renderOptions.enableBackfaceCulling && projection.cullPath(transformedPoints)) {
                return@mapNotNull null
            }

            if (renderOptions.enableBoundsChecking && !projection.itemInDrawingBounds(transformedPoints, width, height)) {
                return@mapNotNull null
            }

            val litColor = projection.transformColor(item.path, item.baseColor, normalizedLight)
            DepthSorter.TransformedItem(item, transformedPoints, litColor)
        }

        val sortedItems = if (renderOptions.enableDepthSorting) {
            DepthSorter.sort(transformedItems, renderOptions)
        } else {
            transformedItems
        }

        val commands = sortedItems.map { transformedItem ->
            RenderCommand(
                commandId = transformedItem.item.id,
                points = transformedItem.transformedPoints,
                color = transformedItem.litColor,
                originalPath = transformedItem.item.path,
                originalShape = transformedItem.item.originalShape,
                ownerNodeId = transformedItem.item.ownerNodeId
            )
        }

        return PreparedScene(commands, width, height)
    }

    override fun findItemAt(
        preparedScene: PreparedScene, x: Double, y: Double,
        order: HitOrder, touchRadius: Double
    ): RenderCommand? = HitTester.findItemAt(preparedScene, x, y, order, touchRadius)
}
```

### Visibility

All extracted types are `internal` — they are implementation details of the `isometric-core` module. The public API surface is unchanged: `IsometricEngine` (now implementing `SceneProjector`) remains the only public entry point.

### Verification

- All existing tests pass without modification (facade API is identical)
- Each extracted type can be unit-tested independently
- `IsometricEngine.kt` reduced from 491 lines to ~80 lines
- No public API changes

---

## Step 7: Decompose `IsometricRenderer` (F49)

### Rationale

`IsometricRenderer` (670 lines) mixes PreparedScene caching, Compose path caching, two rendering backends (Compose + Android native), spatial indexing, command-to-node resolution, and benchmark hooks. Splitting into focused types enables independent testing and prepares for KMP (Kotlin Multiplatform) by isolating Android-specific code.

### Files and Changes

#### 7a. Extract `SpatialGrid` to top-level internal class

**New file**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/SpatialGrid.kt`

Move the private inner class (lines 534-579) to a top-level `internal` class:

```kotlin
package io.fabianterhorst.isometric.compose.runtime

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 2D spatial grid for O(k) hit-test candidate lookup.
 * Cells are axis-aligned squares of [cellSize] pixels.
 */
internal class SpatialGrid(
    private val width: Double,
    private val height: Double,
    private val cellSize: Double
) {
    private val cols = (width / cellSize).toInt() + 1
    private val rows = (height / cellSize).toInt() + 1
    private val grid = Array(rows) { Array(cols) { mutableListOf<String>() } }

    fun insert(id: String, bounds: ShapeBounds) {
        val minCol = max(0, floor(bounds.minX / cellSize).toInt())
        val maxCol = min(cols - 1, floor(bounds.maxX / cellSize).toInt())
        val minRow = max(0, floor(bounds.minY / cellSize).toInt())
        val maxRow = min(rows - 1, floor(bounds.maxY / cellSize).toInt())

        if (minCol > maxCol || minRow > maxRow) return

        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                grid[row][col].add(id)
            }
        }
    }

    fun query(x: Double, y: Double, radius: Double = 0.0): List<String> {
        val minCol = max(0, floor((x - radius) / cellSize).toInt())
        val maxCol = min(cols - 1, floor((x + radius) / cellSize).toInt())
        val minRow = max(0, floor((y - radius) / cellSize).toInt())
        val maxRow = min(rows - 1, floor((y + radius) / cellSize).toInt())

        if (maxRow < 0 || maxCol < 0 || minRow >= rows || minCol >= cols) {
            return emptyList()
        }

        val ids = LinkedHashSet<String>()
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                ids.addAll(grid[row][col])
            }
        }

        return ids.toList()
    }
}
```

Also extract `ShapeBounds` and `RenderCommand.getBounds()` to the same file (or a separate `ShapeBounds.kt`):

```kotlin
internal data class ShapeBounds(
    val minX: Double, val minY: Double,
    val maxX: Double, val maxY: Double
)

internal fun RenderCommand.getBounds(): ShapeBounds? {
    if (points.isEmpty()) return null
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    points.forEach { point ->
        minX = min(minX, point.x)
        minY = min(minY, point.y)
        maxX = max(maxX, point.x)
        maxY = max(maxY, point.y)
    }
    if (minX.isNaN() || minY.isNaN() || maxX.isNaN() || maxY.isNaN()) return null
    return ShapeBounds(minX, minY, maxX, maxY)
}
```

#### 7b. Extract `SceneCache`

**New file**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/SceneCache.kt`

Extracts: `cachedPreparedScene`, `cachedPaths`, `cacheValid`, `cachedWidth`, `cachedHeight`, `cachedPrepareInputs`, `PrepareInputs`, `CachedPath`, `needsUpdate()`, `invalidate()`, `rebuildCache()`, `buildPathCache()`, `buildCommandMaps()`, `buildSpatialIndex()`, `buildNodeIdMap()`

```kotlin
package io.fabianterhorst.isometric.compose.runtime

import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderCommand
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.SceneProjector
import io.fabianterhorst.isometric.Vector

/**
 * Manages the PreparedScene cache, path cache, spatial index, and command maps.
 * Encapsulates all cache invalidation logic.
 */
internal class SceneCache(
    private val engine: SceneProjector,
    private val enablePathCaching: Boolean,
    private val enableSpatialIndex: Boolean,
    private val spatialIndexCellSize: Double
) {
    // ... PrepareInputs, CachedPath data classes ...
    // ... all cache fields ...
    // ... needsUpdate(), invalidate(), rebuildCache() ...
    // ... buildPathCache(), buildCommandMaps(), buildSpatialIndex(), buildNodeIdMap() ...

    val currentPreparedScene: PreparedScene? get() = cachedPreparedScene
    val cachedPathsList: List<CachedPath>? get() = cachedPaths

    // Hit-test support accessors
    val commandIdMap: Map<String, RenderCommand> get() = _commandIdMap
    val commandOrderMap: Map<String, Int> get() = _commandOrderMap
    val commandToNodeMap: Map<String, IsometricNode> get() = _commandToNodeMap
}
```

This class absorbs approximately 250 lines from `IsometricRenderer`.

#### 7c. Extract `HitTestResolver`

**New file**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/HitTestResolver.kt`

Extracts: `hitTest()` and `findNodeByCommandId()`

```kotlin
package io.fabianterhorst.isometric.compose.runtime

import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.SceneProjector

/**
 * Resolves screen coordinates to IsometricNode instances,
 * using spatial indexing for O(k) performance when available.
 */
internal class HitTestResolver(
    private val engine: SceneProjector,
    private val cache: SceneCache
) {
    fun hitTest(
        rootNode: GroupNode,
        x: Double, y: Double,
        context: RenderContext,
        width: Int, height: Int
    ): IsometricNode? {
        // ... existing hitTest() logic, delegating to cache for maps ...
    }
}
```

#### 7d. Isolate Android-native rendering

**Existing file**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRenderer.kt`

Move `renderNative()`, `toNativePath()`, `toAndroidColor()`, `fillPaint`, `strokePaint` to a separate file:

**New file**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/NativeSceneRenderer.kt`

```kotlin
package io.fabianterhorst.isometric.compose.runtime

import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import io.fabianterhorst.isometric.RenderCommand

/**
 * Android-native rendering backend using android.graphics.Canvas directly.
 * Provides ~2x faster rendering compared to Compose DrawScope on Android.
 *
 * TODO(KMP): Move to androidMain source set.
 */
internal class NativeSceneRenderer {
    private val fillPaint by lazy {
        Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    }
    private val strokePaint by lazy {
        Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }
    }

    fun DrawScope.renderNative(
        scene: io.fabianterhorst.isometric.PreparedScene,
        strokeWidth: Float = 1f,
        drawStroke: Boolean = true
    ) {
        drawIntoCanvas { canvas ->
            scene.commands.forEach { command ->
                val nativePath = command.toNativePath()
                fillPaint.color = command.color.toAndroidColor()
                canvas.nativeCanvas.drawPath(nativePath, fillPaint)
                if (drawStroke) {
                    strokePaint.strokeWidth = strokeWidth
                    strokePaint.color = android.graphics.Color.argb(25, 0, 0, 0)
                    canvas.nativeCanvas.drawPath(nativePath, strokePaint)
                }
            }
        }
    }

    // ... toNativePath(), toAndroidColor() moved here ...
}
```

#### 7e. Slim down `IsometricRenderer`

After extraction, `IsometricRenderer` becomes a thin coordinator:

```kotlin
class IsometricRenderer(
    private val engine: SceneProjector,
    private val enablePathCaching: Boolean = true,
    private val enableSpatialIndex: Boolean = true,
    private val spatialIndexCellSize: Double = DEFAULT_SPATIAL_INDEX_CELL_SIZE
) {
    private val cache = SceneCache(engine, enablePathCaching, enableSpatialIndex, spatialIndexCellSize)
    private val nativeRenderer = NativeSceneRenderer()
    private val hitTestResolver = HitTestResolver(engine, cache)

    var benchmarkHooks: RenderBenchmarkHooks? = null
    var forceRebuild: Boolean = false

    fun DrawScope.render(rootNode: GroupNode, context: RenderContext, ...) {
        // Delegates to cache.ensurePreparedScene() + draws from cache.cachedPaths
    }

    fun DrawScope.renderNative(rootNode: GroupNode, context: RenderContext, ...) {
        // Delegates to cache + nativeRenderer
    }

    fun hitTest(...): IsometricNode? = hitTestResolver.hitTest(...)

    fun clearCache() = cache.invalidate()
}
```

**Target**: `IsometricRenderer.kt` reduced from 670 lines to ~100 lines.

### Verification

- All existing tests pass without modification (public API is identical)
- `SpatialGrid` can be unit-tested independently
- `SceneCache` can be tested with a fake `SceneProjector`
- Android-specific code isolated in one file (prepares for KMP extraction)

---

## Step 8: Module Cleanup (F54, F55)

### 8a. Remove `:lib` legacy module (F54)

#### Rationale

`settings.gradle` includes `:lib`, which contains a legacy Java implementation of the isometric library — `Isometric.java`, `Color.java`, `Shape.java`, etc. These duplicate `isometric-core`'s Kotlin types. The `Color.java` class duplicates `IsoColor.kt`'s full RGB-to-HSL pipeline. A user browsing the project sees two entry points with no indication of which is current.

#### Files and Changes

**File**: `settings.gradle`

**Current** (line 18):
```groovy
include ':lib'
```

**After**: Remove the line.

**Directory**: `lib/` — Delete the entire directory.

**Impact check**: Search for `:lib` references in:
- `app/build.gradle.kts` — may have `implementation(project(":lib"))`. If found, remove.
- Any CI configuration or scripts referencing `:lib`

The `:lib` module contains only Java source files (`Isometric.java`, `Color.java`, `Shape.java`, etc.) and old screenshot tests. None of these are referenced by `isometric-core`, `isometric-compose`, or `app`. The screenshots can be preserved in a git archive tag if needed.

### 8b. Tighten `api` to `implementation` (F55)

#### Rationale

`isometric-compose/build.gradle.kts` line 49 declares `api(project(":isometric-core"))`, re-exporting every public type from core to compose consumers. A beginner who only wants `IsometricScene` also sees `IsometricEngine.add()`, `IntersectionUtils.isIntersecting()`, and `PreparedScene.commands` in autocomplete.

#### Files and Changes

**File**: `isometric-compose/build.gradle.kts`

**Current** (line 49):
```kotlin
api(project(":isometric-core"))
```

**After**:
```kotlin
implementation(project(":isometric-core"))
```

**Breaking**: Any consumer that transitively accesses core types through the compose module must add an explicit `implementation(project(":isometric-core"))` dependency. In this project, that means:

- `app/build.gradle.kts` — likely already has its own dependency on `isometric-core`, or needs one added

**Re-exports**: The compose module's public API (composables, `IsometricScene`) uses core types in its signatures — `Shape`, `Path`, `Point`, `IsoColor`, `Vector`, `Prism`, `Cylinder`, `Pyramid`, etc. These types must remain visible to consumers. Two approaches:

**Option A** — Use `api` selectively (not possible with Gradle project dependencies — `api`/`implementation` is all-or-nothing per dependency).

**Option B** — Keep `api(project(":isometric-core"))` but rely on the open public `Shape` hierarchy plus `internal` visibility from Steps 5-7 to hide implementation types. The decomposed types (`SceneGraph`, `IsometricProjection`, `DepthSorter`, `HitTester`) are all `internal`, so they won't appear in autocomplete even with `api`.

**Recommendation**: If the decomposition in Steps 6-7 successfully makes all non-API types `internal`, then `api(project(":isometric-core"))` is acceptable — the only visible types are the ones consumers actually need (`Shape`, `Point`, `IsoColor`, etc.). Change to `implementation` only if a clear boundary can be drawn between "consumer types" and "engine internals". In practice, `Shape` appears in composable signatures, so the consumer needs it.

**Pragmatic fix**: Keep `api(project(":isometric-core"))` for now. The `internal` visibility on decomposed types (Steps 6-7) achieves the same goal — reducing autocomplete noise — without breaking transitive consumers. Revisit when the KMP migration creates explicit `api` surface declarations via module metadata.

### Verification

- `:lib` module removed from settings and builds successfully without it
- No remaining references to `:lib` in build files or scripts
- `app` module compiles and runs (transitive dependency resolved)
- Autocomplete in consumer code no longer shows `internal` types from decomposition

---

## Cross-Workstream Dependencies

| This step | Produces | Consumed by |
|-----------|----------|-------------|
| Step 1b (top-level extensions) | `IsoColor.toComposeColor()` as top-level | WS1 F6 (`ColorExtensions.kt` — coordinate: same extension, one canonical location) |
| Step 2 (shared conversions) | Single `toComposePath()`, `toComposeColor()` | All rendering code |
| Step 3 (accumulator pattern) | `renderTo(output, context)` | WS6 F58 (custom nodes must implement `renderTo()` instead of `render()`) |
| Step 4 (infix operators) | Readable `transformColor()` | Requires WS1 Step 5 (`cross`/`dot` infix on `Vector`) |
| Step 5 (SceneProjector) | Testable renderer | WS6 F61 (escape hatches may expose `SceneProjector` type) |
| Step 6 (engine decomposition) | `SceneGraph`, `IsometricProjection`, `DepthSorter`, `HitTester` | WS6 (escape hatches for projection access via `IsometricProjection`) |
| Step 7 (renderer decomposition) | `SceneCache`, `SpatialGrid`, `HitTestResolver` | WS4 F23 (try/catch in renderer — WS5 preserves error boundaries during decomposition) |
| Step 8 (module cleanup) | Clean module graph | All workstreams benefit from reduced confusion |

### Dependencies FROM other workstreams

| Dependency | Source | Why needed |
|-----------|--------|-----------|
| Vector `x/y/z` field names | WS1 Step 3 | Step 4 and Step 6b use `Vector.x/y/z` (not `i/j/k`) |
| `cross`/`dot` infix operators | WS1 Step 5 | Step 4 uses `edge1 cross edge2` and `normal dot lightDirection` |
| Open `Shape` base + final built-ins | WS1 Step 4 | Step 6a's `SceneGraph.add(shape)` calls `shape.orderedPaths()` and keeps user-defined subclasses viable |
| Renamed methods (`projectScene`, `clearCache`, `markClean`) | WS3 | Steps 5-7 use method names from WS3. If WS3 hasn't run, use original names (`prepare`, `invalidate`, `clearDirty`) |
| `try/catch`, `Closeable` patterns | WS4 | Steps 6-7 must preserve any `try/catch` or `Closeable` that WS4 added. If WS4 hasn't run, no action needed. |

---

## File Change Summary

| File | Steps | Changes |
|------|-------|---------|
| `IsometricEngine.kt` | 1a, 4, 5, 6 | Java collections, infix operators, implement `SceneProjector`, decompose to facade |
| `ComposeRenderer.kt` | 1b, 2 | Remove `object` wrapper, move extensions to top-level, deduplicate |
| `IsometricRenderer.kt` | 1a, 2, 3, 5, 7 | Java collections, remove duplicate conversions, update to `renderTo()`, depend on `SceneProjector`, decompose |
| `IsometricNode.kt` | 3 | `render()` to `renderTo()` for all 4 node types |
| `SceneProjector.kt` | 5 | **new file** — engine interface |
| `SceneGraph.kt` | 6 | **new file** — scene state accumulation |
| `IsometricProjection.kt` | 6 | **new file** — 3D to 2D projection + lighting |
| `DepthSorter.kt` | 6 | **new file** — topological depth sorting |
| `HitTester.kt` | 6 | **new file** — hit testing with convex hull |
| `RenderExtensions.kt` | 2 | **new file** — shared `toComposePath()`, `toComposeColor()` |
| `SpatialGrid.kt` | 7 | **new file** — extracted from `IsometricRenderer` inner class |
| `SceneCache.kt` | 7 | **new file** — cache management extracted from renderer |
| `HitTestResolver.kt` | 7 | **new file** — hit-test resolution extracted from renderer |
| `NativeSceneRenderer.kt` | 7 | **new file** — Android-native rendering isolated for KMP |
| `settings.gradle` | 8 | Remove `:lib` include |
| `isometric-compose/build.gradle.kts` | 8 | Evaluate `api` to `implementation` (may keep `api`) |
| `lib/` (directory) | 8 | **delete** — entire legacy module |
