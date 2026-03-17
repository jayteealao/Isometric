# WS8: Documentation & Examples — Detailed Implementation Plan

> **Workstream**: 8 of 8
> **Depends on**: WS1b (F26/F28 renames change sample parameter names), WS3 (F34/F35 renames change sample code), WS4 (F13 deletes `IsometricCanvas` referenced in samples)
> **Scope**: Coordinate system documentation, animation sample fixes, first-example simplification, name disambiguation KDoc
> **Findings**: F16, F17, F18, F14
> **Review ref**: `docs/reviews/api-first-run-usability-review.md` §3.4, §3.5

---

## Execution Order

The 4 findings decompose into 4 ordered steps. Each step is self-contained and produces a compilable codebase.

1. **Step 1**: Coordinate system documentation — KDoc + RUNTIME_API.md section (F16)
2. **Step 2**: Animation sample fixes — `delay(16)` → `withFrameNanos` (F17)
3. **Step 3**: First-example simplification — omit explicit `color` (F18)
4. **Step 4**: Shape composable vs Shape class disambiguation KDoc (F14)

Steps 1–4 are parallelizable (no interdependencies within WS8). Ordering is by risk: documentation-only changes first, then behavioral changes (animation), then cosmetic changes (examples), then KDoc additions.

**Publication baseline**: before WS8 lands, convert any remaining legacy `IsometricCanvas` snippets to the runtime API and use the settled post-WS3 names (`geometry`, `IsoColor`, `projectScene()`, etc.). WS8 should document the API users will actually see after the earlier workstreams merge.

---

## Step 1: Coordinate System Documentation (F16)

### Rationale

There is zero documentation of the isometric coordinate system anywhere in the codebase. Users need to understand: which direction each axis points on screen, how `Point.depth()` works for painter's algorithm sorting, and how `IsometricEngine.translatePoint()` projects 3D points to 2D screen coordinates. The projection math is private with no explanation. A user reading the API for the first time has no way to predict where `Point(1.0, 0.0, 0.0)` will appear on screen.

### Best Practice

Library coordinate systems must be documented in three places: (1) KDoc on the core engine class (appears in IDE hover), (2) KDoc on the `Point` class (appears when constructing points), and (3) a prose section in the API documentation (appears when reading docs). An ASCII diagram is the standard way to show axis directions in text-based documentation.

### Files and Changes

#### 1a. `IsometricEngine.kt` — Add coordinate system KDoc to class

**Current** (line 9):
```kotlin
/**
 * Core isometric rendering engine.
 * Platform-agnostic - outputs PreparedScene that can be rendered by any platform.
 */
class IsometricEngine(
```

**After**:
```kotlin
/**
 * Core isometric rendering engine.
 * Platform-agnostic - outputs PreparedScene that can be rendered by any platform.
 *
 * ## Coordinate System
 *
 * The engine uses a standard isometric projection with configurable [angle] (default 30 degrees)
 * and [scale] (default 70 pixels per unit).
 *
 * ```
 *          z (up)
 *          |
 *          |
 *         / \
 *        /   \
 *       y     x
 *  (left-down) (right-down)
 * ```
 *
 * - **x-axis**: points right-and-down on screen
 * - **y-axis**: points left-and-down on screen
 * - **z-axis**: points straight up on screen
 *
 * ### Projection formulas
 *
 * The 3D-to-2D projection is:
 * ```
 * screenX = originX + x * scale * cos(angle) + y * scale * cos(PI - angle)
 * screenY = originY - x * scale * sin(angle) - y * scale * sin(PI - angle) - z * scale
 * ```
 *
 * At the default angle of PI/6 (30 degrees):
 * - `cos(30°) ≈ 0.866`, `sin(30°) = 0.5`
 * - `cos(150°) ≈ -0.866`, `sin(150°) = 0.5`
 *
 * ### Depth sorting
 *
 * Faces are sorted back-to-front using [Point.depth]: `x + y - 2 * z`.
 * Higher depth values are farther from the viewer and drawn first.
 */
class IsometricEngine(
```

#### 1b. `Point.kt` — Expand `depth()` KDoc

**Current** (line 124–126):
```kotlin
/**
 * The depth of a point in the isometric plane
 * z is weighted slightly to accommodate |_ arrangements
 */
fun depth(): Double {
    return x + y - 2 * z
}
```

**After**:
```kotlin
/**
 * The depth of a point in the isometric projection, used for painter's algorithm sorting.
 *
 * Formula: `x + y - 2 * z`
 *
 * - Points with higher depth are farther from the viewer and should be drawn first.
 * - The z-axis is weighted by 2 because each unit of z moves a point "closer" to the viewer
 *   more than one unit of x or y (z is perpendicular to the screen, while x and y are
 *   projected at 30-degree angles).
 *
 * @return the depth value for sorting; higher = farther from viewer
 */
fun depth(): Double {
    return x + y - 2 * z
}
```

#### 1c. `Point.kt` — Add class-level coordinate system KDoc

**Current**: The `Point` class has a minimal KDoc or none.

**After**: Add to the class-level KDoc:
```kotlin
/**
 * A point in 3D isometric space.
 *
 * The coordinate system is:
 * - [x]: right-and-down on screen (increases toward bottom-right)
 * - [y]: left-and-down on screen (increases toward bottom-left)
 * - [z]: straight up on screen (increases upward)
 *
 * @see depth for painter's algorithm sorting
 */
data class Point(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
```

#### 1d. `RUNTIME_API.md` — Add coordinate system section

Insert a new section after "Key Concepts" (after line ~14) and before "API Reference":

```markdown
## Coordinate System

The isometric engine uses a standard isometric projection. Understanding the axes is
essential for positioning shapes correctly.

### Axis directions

```
         z (up)
         |
         |
        / \
       /   \
      y     x
 (left-down) (right-down)
```

| Axis | Screen direction | Example |
|------|-----------------|---------|
| **x** | Right-and-down (toward bottom-right) | `Point(1.0, 0.0, 0.0)` moves a shape to the bottom-right |
| **y** | Left-and-down (toward bottom-left) | `Point(0.0, 1.0, 0.0)` moves a shape to the bottom-left |
| **z** | Straight up | `Point(0.0, 0.0, 1.0)` moves a shape upward |

### Projection math

The 3D-to-2D projection uses a configurable angle (default 30 degrees) and scale
(default 70 pixels per unit):

```
screenX = originX + x * scale * cos(angle) + y * scale * cos(PI - angle)
screenY = originY - x * scale * sin(angle) - y * scale * sin(PI - angle) - z * scale
```

### Depth sorting

Faces are drawn back-to-front using the depth formula `x + y - 2 * z`. Higher values
are farther from the viewer. The z-axis is weighted by 2 because vertical movement
has a stronger effect on perceived depth than diagonal x/y movement.
```

### Verification

After step 1, verify:
- KDoc renders correctly in IDE hover (IntelliJ "Quick Documentation" on `IsometricEngine`, `Point`, `Point.depth()`)
- RUNTIME_API.md renders correctly in GitHub Markdown preview
- ASCII diagrams are visually correct (x right-down, y left-down, z up)

---

## Step 2: Animation Sample Fixes — `delay(16)` → `withFrameNanos` (F17)

### Rationale

Every animation example in the codebase uses `delay(16)` — a coroutine suspension that approximates 60fps but does not synchronize with the display refresh rate. On devices with 90Hz or 120Hz displays, `delay(16)` runs too slowly. On devices under load, it drifts because `delay(16)` waits *at least* 16ms, not exactly 16ms. The result is jitter, wasted battery (busy-waiting between frames), and animations that run at different speeds on different devices.

`withFrameNanos` is the Compose-idiomatic animation primitive. It suspends until the next frame callback from the choreographer, guaranteeing vsync alignment. It also provides the frame timestamp in nanoseconds, enabling time-based animation that runs at the same visual speed regardless of frame rate.

### Best Practice

Compose documentation explicitly recommends `withFrameNanos` for custom animation loops. The Compose animation system (`animate*AsState`, `Animatable`) is built on top of `withFrameNanos` internally. Using it directly is the correct approach for open-ended continuous animations.

### Pattern

**Before** (9 occurrences in source, 6 in docs):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(16)
        angle += PI / 90
    }
}
```

**After** (time-based animation):
```kotlin
LaunchedEffect(Unit) {
    var previousNanos = 0L
    while (true) {
        withFrameNanos { frameNanos ->
            if (previousNanos != 0L) {
                val deltaNanos = frameNanos - previousNanos
                val deltaSeconds = deltaNanos / 1_000_000_000.0
                angle += (PI / 90) * deltaSeconds * 60  // 60 = original assumed fps
            }
            previousNanos = frameNanos
        }
    }
}
```

**Simplified alternative** (angle-per-frame, preserving existing visual speed — recommended for samples where time-based precision is not the teaching goal):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos { _ ->
            angle += PI / 90
        }
    }
}
```

**Recommendation**: Use the simplified alternative for all sample code. The samples exist to demonstrate the isometric library, not to teach time-based animation. The key fix is replacing `delay(16)` with `withFrameNanos` so animations sync with the display. A comment can note that production code should use the frame timestamp for time-based animation.

### Files and Changes

#### 2a. `ComposeActivity.kt` — 1 occurrence (line 142)

**Current** (line 140–144):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(16) // ~60fps
        angle += PI / 90
    }
}
```

**After**:
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos { // vsync-aligned frame callback
            angle += PI / 90
        }
    }
}
```

**Import change**: Remove `import kotlinx.coroutines.delay`, add `import androidx.compose.ui.platform.withFrameNanos` (or `import androidx.compose.runtime.withFrameNanos` depending on Compose version — verify which package exports it in the project's Compose BOM).

#### 2b. `RuntimeApiActivity.kt` — 3 occurrences (lines 125, 188, 388)

**Occurrence 1** (line 123–127, `HierarchySample`):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(16)
        groupRotation += PI / 180
    }
}
```
→
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos {
            groupRotation += PI / 180
        }
    }
}
```

**Occurrence 2** (line 186–191, `AnimationSample`):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(16)
        angle += PI / 90
        wave += PI / 60
    }
}
```
→
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos {
            angle += PI / 90
            wave += PI / 60
        }
    }
}
```

**Occurrence 3** (line 386–390, `PerformanceSample`):
```kotlin
LaunchedEffect(animationEnabled) {
    while (animationEnabled) {
        delay(16)
        wave += PI / 30
    }
}
```
→
```kotlin
LaunchedEffect(animationEnabled) {
    while (animationEnabled) {
        withFrameNanos {
            wave += PI / 30
        }
    }
}
```

**Import change**: Remove `import kotlinx.coroutines.delay`, add `withFrameNanos` import.

#### 2c. `OptimizedPerformanceSample.kt` — 2 occurrences (lines 40, 223)

**Occurrence 1** (line 38–42, `OptimizedPerformanceSample`):
```kotlin
LaunchedEffect(animationEnabled) {
    while (animationEnabled) {
        delay(16)
        wave += PI / 30
    }
}
```
→
```kotlin
LaunchedEffect(animationEnabled) {
    while (animationEnabled) {
        withFrameNanos {
            wave += PI / 30
        }
    }
}
```

**Occurrence 2** (line 221–225, `PerformanceComparisonDemo`):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(16)
        wave += PI / 30
    }
}
```
→
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos {
            wave += PI / 30
        }
    }
}
```

**Import change**: Remove `import kotlinx.coroutines.delay`, add `withFrameNanos` import.

#### 2d. `PrimitiveLevelsExample.kt` — 3 occurrences (lines 73, 94, 248)

**Occurrence 1** (line 71–75, `AnimatedShapeHighLevel`):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(16)
        rotation += PI / 90
    }
}
```
→
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos {
            rotation += PI / 90
        }
    }
}
```

**Occurrence 2** (line 92–96, `AnimatedShapeLowLevel`):
Same pattern → same fix.

**Occurrence 3** (line 246–250):
Same pattern → same fix.

**Import change**: Remove `import kotlinx.coroutines.delay`, add `withFrameNanos` import.

#### 2e. `README.md` — 2 occurrences (lines 260, 341)

**Occurrence 1** (line 258–262, "Animation" section):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(16) // ~60fps
        angle += PI / 90
    }
}
```
→
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos { // vsync-aligned
            angle += PI / 90
        }
    }
}
```

**Occurrence 2** (line 339–343, "Hierarchical Transforms" section):
Same pattern → same fix.

#### 2f. `RUNTIME_API.md` — 2 occurrences (lines 336, 449)

**Occurrence 1** (line 334–338, "Example 2: Hierarchical Transforms"):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(16)
        angle += PI / 180
    }
}
```
→
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos {
            angle += PI / 180
        }
    }
}
```

**Occurrence 2** (line 447–451, "Example 5: Performance Grid"):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(16)
        wave += PI / 30
    }
}
```
→
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos {
            wave += PI / 30
        }
    }
}
```

#### 2g. `MIGRATION.md` — 1 occurrence (line 287)

**Current** (line 285–289):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(16)
        rotation += 0.02
    }
}
```
→
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos {
            rotation += 0.02
        }
    }
}
```

#### 2h. `PRIMITIVE_LEVELS.md` — 1 occurrence (line 129)

**Current** (line 127–131):
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        delay(16)
        scale = 1.0 + sin(System.currentTimeMillis() / 500.0) * 0.2
    }
}
```
→
```kotlin
LaunchedEffect(Unit) {
    while (true) {
        withFrameNanos { frameNanos ->
            scale = 1.0 + sin(frameNanos / 500_000_000.0) * 0.2
        }
    }
}
```

Note: This occurrence uses `System.currentTimeMillis()` which should also be replaced with the `frameNanos` parameter for consistency.

### Verification

After step 2:
- All sample activities compile without `import kotlinx.coroutines.delay` (unless delay is used elsewhere in the file for non-animation purposes — check before removing)
- Run each sample on device: animations should be smooth and vsync-aligned
- No `delay(16)` remains anywhere in the codebase (verify with `grep -r "delay(16)"`)

---

## Step 3: First-Example Simplification — Omit Default Color (F18)

### Rationale

The first example a user sees should be the absolute minimum code to get a shape on screen. A few first-run examples still over-specify color and, in some places, still reflect the legacy canvas API. The surviving runtime `Shape()` composable already has `color = LocalDefaultColor.current`, so the first example can omit it entirely. By doing that on the real runtime API surface, we show the smallest practical call and teach users that color is optional.

### Best Practice

Developer documentation should follow a "progressive disclosure" pattern: the first example shows the minimal API surface, subsequent examples introduce optional parameters one at a time. Showing all parameters in the first example overwhelms beginners and obscures the required vs optional distinction.

### Files and Changes

#### 3a. `RUNTIME_API.md` — Example 1

**Current**:
```kotlin
IsometricScene {
    Shape(
        geometry = Prism(position = Point(0.0, 0.0, 0.0)),
        color = IsoColor(33.0, 150.0, 243.0)
    )
}
```

**After**:
```kotlin
IsometricScene {
    Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0)))
}
```

Then add a short follow-up note: "The shape uses the scene default color. Pass `color = IsoColor(...)` when you want an explicit override."

#### 3b. `RuntimeApiActivity.kt` — `BasicShapesSample()` first shape

**Current**:
```kotlin
Shape(
    geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 2.0, depth = 2.0, height = 2.0),
    color = IsoColor(33.0, 150.0, 243.0)
)
```

**After** (first shape only — keep color on remaining shapes to show the parameter):
```kotlin
Shape(
    // Uses default color from LocalDefaultColor
    geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 2.0, depth = 2.0, height = 2.0)
)
```

#### 3c. `README.md` — First runtime/Compose example

**Current**:
```kotlin
IsometricScene {
    Shape(
        geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 1.0, depth = 1.0, height = 1.0),
        color = IsoColor(33.0, 150.0, 243.0)
    )
}
```

**After**:
```kotlin
IsometricScene {
    Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 1.0, depth = 1.0, height = 1.0))
}
```

#### 3d. Legacy-snippet cleanup guard

If any README or sample text still shows `IsometricCanvas`, convert it to the runtime API first as part of WS4's migration, then apply the same "omit the first explicit color" rule. WS8 should not preserve or further document the deleted surface.

### Scope Limitation

Only the **first** example in each file should omit color. Subsequent examples should continue to show explicit colors to teach the `IsoColor` API. The goal is progressive disclosure, not removal of all color parameters.

### Verification

After step 3:
- First example in each file compiles without explicit color
- Default color renders visibly (not transparent/invisible) — verify by running the first runtime sample and `BasicShapesSample`
- Subsequent examples still show explicit colors

---

## Step 4: Shape Composable vs Shape Class Disambiguation KDoc (F14)

### Rationale

The `Shape()` composable function in `IsometricComposables.kt` shares its name with the `Shape` class in `isometric-core`. When a user imports both packages:

```kotlin
import io.fabianterhorst.isometric.Shape          // the class
import io.fabianterhorst.isometric.compose.runtime // includes the Shape() composable
```

The compiler reports: `Overload resolution ambiguity` or requires qualified names. This is a first-run usability hazard because both imports are needed in any real isometric app. KDoc on the composable should explain the distinction and suggest the import alias pattern.

### Best Practice

When a library has name collisions across modules, the canonical fix is either renaming or documenting the disambiguation. Since `Shape` is the domain-correct name for both the composable and the class, KDoc documentation with `@see` cross-references and import alias guidance is the appropriate fix.

### Files and Changes

#### 4a. `IsometricComposables.kt` — Expand KDoc on `Shape()` composable (line 19–30)

**Current**:
```kotlin
/**
 * Add a 3D shape to the isometric scene
 *
 * @param shape The 3D shape to render
 * @param color The color of the shape (defaults to LocalDefaultColor)
 * @param position Local position offset
 * @param rotation Local rotation around Z axis
 * @param scale Local scale factor
 * @param rotationOrigin Origin point for rotation
 * @param scaleOrigin Origin point for scaling
 * @param visible Whether the shape is visible
 */
@IsometricComposable
@Composable
fun IsometricScope.Shape(
```

**After**:
```kotlin
/**
 * Add a 3D shape to the isometric scene.
 *
 * **Name disambiguation**: This composable function shares its name with
 * [io.fabianterhorst.isometric.Shape], the core geometry class. The [geometry] parameter
 * accepts an instance of that class. If you have both imported, use an import alias:
 *
 * ```kotlin
 * import io.fabianterhorst.isometric.Shape as IsoShape
 * // Then use IsoShape(...) for the class, Shape(...) for the composable
 * ```
 *
 * Or, since the composable is an extension on [IsometricScope], it is only callable
 * inside an [IsometricScene] block — the compiler will resolve it correctly if the
 * core [Shape][io.fabianterhorst.isometric.Shape] class is constructed outside the scene block:
 *
 * ```kotlin
 * val cube = Shape(Prism(Point(0.0, 0.0, 0.0)).paths)  // core Shape class
 * IsometricScene {
 *     Shape(geometry = cube)  // composable — resolved by IsometricScope receiver
 * }
 * ```
 *
 * @param geometry The 3D shape geometry to render (an [io.fabianterhorst.isometric.Shape] instance)
 * @param color The color of the shape (defaults to [LocalDefaultColor])
 * @param position Local position offset
 * @param rotation Local rotation around Z axis (radians)
 * @param scale Local scale factor
 * @param rotationOrigin Origin point for rotation
 * @param scaleOrigin Origin point for scaling
 * @param visible Whether the shape is visible
 * @see io.fabianterhorst.isometric.Shape
 * @see IsometricScene
 */
@IsometricComposable
@Composable
fun IsometricScope.Shape(
```

#### 4b. `IsometricComposables.kt` — Add `@see` to other composables for discoverability

Add `@see Shape` cross-references to `Group`, `Path`, `Batch` KDoc so users browsing any composable can discover the full set:

**Example** (Group, line 58):
```kotlin
/**
 * Create a group that applies transforms to all its children.
 *
 * ...existing params...
 * @see Shape
 * @see Path
 * @see Batch
 */
```

**Example** (Path, line 96):
```kotlin
/**
 * Add a raw 2D path to the isometric scene.
 *
 * **Name disambiguation**: This composable shares its name with
 * [io.fabianterhorst.isometric.Path], the core path class. The same import alias
 * pattern described in [Shape] applies here.
 *
 * ...existing params...
 * @see io.fabianterhorst.isometric.Path
 * @see Shape
 */
```

### Verification

After step 4:
- KDoc renders in IDE hover with the disambiguation note, code examples, and `@see` links
- Links resolve correctly to the core `Shape` and `Path` classes
- No compile errors from KDoc code examples (verify with `./gradlew dokkaHtml` if Dokka is configured)

---

## Cross-Workstream Dependencies

| This step | Produces | Consumed by | Coordination note |
|-----------|----------|-------------|-------------------|
| Step 1 (coord docs) | KDoc on `IsometricEngine`, `Point` | WS1 Step 3 (param renames) | If WS1 renames `origin` → `position`, update the KDoc examples accordingly. KDoc references `Point.depth()` which is stable. |
| Step 2 (animation fix) | Updated sample code | WS1 Step 3 (param renames `origin` → `position`, `dx/dy/dz` → `width/depth/height`) | Sample files will be edited by both WS1 and WS8. **If WS8 lands first**: WS1 must update sample files again for param renames. **If WS1 lands first**: WS8 must use new param names in sample code. Either order works — the changes touch different lines (animation loop vs shape construction). |
| Step 2 (animation fix) | Updated sample code | WS3 (method renames `prepare` → `projectScene`) | Same coordination as WS1: different lines, either order works. |
| Step 3 (first-example) | Simplified first examples | WS1 F10 (default `position = Point.ORIGIN`) | After WS1 F10 lands, the first example can be further simplified to `Prism()` (zero-arg). WS8 should use `Prism(Point(0.0, 0.0, 0.0))` for now since the default does not exist yet. |
| Step 4 (disambiguation) | KDoc on `Shape` composable | — | Shape remains an open class (F66 is redundant — subclasses are intentional for user extensibility). The KDoc addresses the name collision between the composable and the class, not the class hierarchy. |

---

## File Change Summary

| File | Steps | Changes |
|------|-------|---------|
| `isometric-core/src/main/kotlin/.../IsometricEngine.kt` | 1 | Add coordinate system KDoc to class |
| `isometric-core/src/main/kotlin/.../Point.kt` | 1 | Add class-level coordinate KDoc, expand `depth()` KDoc |
| `docs/RUNTIME_API.md` | 1, 2, 3 | Add coordinate system section, fix 2 `delay(16)`, simplify first example |
| `app/.../sample/ComposeActivity.kt` | 2 | Fix 1 `delay(16)` |
| `app/.../sample/RuntimeApiActivity.kt` | 2, 3 | Fix 3 `delay(16)`, simplify first shape in `BasicShapesSample` |
| `app/.../sample/OptimizedPerformanceSample.kt` | 2 | Fix 2 `delay(16)` |
| `app/.../sample/PrimitiveLevelsExample.kt` | 2 | Fix 3 `delay(16)` |
| `README.md` | 2, 3 | Fix 2 `delay(16)`, simplify first runtime example |
| `docs/MIGRATION.md` | 2 | Fix 1 `delay(16)` |
| `docs/PRIMITIVE_LEVELS.md` | 2 | Fix 1 `delay(16)` |
| `isometric-compose/.../runtime/IsometricComposables.kt` | 4 | Expand `Shape` KDoc with disambiguation, add `@see` cross-refs to `Group`, `Path`, `Batch` |
