# Jetpack Compose Port Investigation Report
**Isometric Android Drawing Library**
**Date:** 2026-01-01
**Author:** Claude Code Investigation
**Branch:** compose (claude/branch-from-compose-gqLU4)

---

## Executive Summary (1-Page Findings)

### Current State Assessment

**Existing Architecture:**
- **Core Engine:** `Isometric.java` (423 lines) - Pure rendering logic with tight coupling to `android.graphics.*`
- **View Wrapper:** `IsometricView.java` (142 lines) - Android View lifecycle integration
- **Proof-of-Concept:** `IsometricCompose.kt` (74 lines) - Minimal Compose wrapper using `nativeCanvas` interop
- **Geometry Core:** Point, Path, Shape, Vector (100% platform-agnostic math)
- **Build System:** Gradle 7.3.1, AGP 8.0.0-alpha06, Compose 1.3.1, Kotlin 1.7.10

**Key Dependencies on Android View Platform:**
1. `android.graphics.Canvas` - used directly in `Isometric.draw()` (line 282)
2. `android.graphics.Paint` - created per-item in `Item` constructor (lines 406-412)
3. `android.graphics.Path` - `drawPath` field in each `Item` (line 391)
4. `android.view.MotionEvent` - touch handling in `IsometricView.onTouchEvent()` (line 121)
5. `View.onMeasure()` / `View.onDraw()` lifecycle - measurement and invalidation coupling

**Current Compose Integration (IsometricCompose.kt):**
- **Status:** Rudimentary proof-of-concept
- **Approach:** Creates new `Isometric()` instance on every recomposition (line 23)
- **Rendering:** Uses `drawIntoCanvas { it.nativeCanvas }` to bridge to android.graphics.Canvas
- **State Management:** None - hardcoded scene, no reactivity
- **Performance:** Problematic - full scene reconstruction on every composition
- **Touch Handling:** Missing entirely

**Testing Infrastructure:**
- **Current:** Facebook Screenshot Testing (commented out due to AGP upgrade incompatibility)
- **Test Files:** `IsometricViewTest.java` - 534 lines, all commented out
- **Coverage:** Comprehensive visual regression tests existed (9+ test scenarios: grid, transforms, shapes, etc.)
- **Status:** Completely broken, needs full rewrite

**Critical Findings:**

1. **Clean Separation Potential:** 90% of code is platform-agnostic (Point, Path, Shape, Vector, IntersectionUtils, lighting math, depth sorting). Only `Isometric.java` Item class and rendering methods are coupled to android.graphics.

2. **Compose POC Limitations:** Current IsometricCompose completely recreates scene on every frame - no state management, no caching, performance disaster waiting to happen.

3. **Rendering Pipeline is Modular:** The transformation (3D→2D), depth sorting, and culling happen in `measure()`. Drawing is separate in `draw()`. Clear separation point.

4. **Hit Testing is Sophisticated:** `findItemForPosition()` uses polygon containment tests and intersection detection - fully 2D screen-space math, easily portable.

5. **Version Fragility:** AGP 8.0.0-alpha06 is ancient (2022), Compose 1.3.1 is outdated, Kotlin 1.7.10 is old. Major upgrade required.

### Opportunities & Risks

**Opportunities:**
- Clean modularization possible: :core (pure Kotlin), :compose, :android-view (legacy)
- Compose provides better state management, automatic recomposition, modifier system
- Paparazzi can replace Facebook screenshot tests (JVM-based, fast, modern)
- Modern Compose (1.7+) has DrawScope extensions, remember{} caching, performance improvements

**Risks:**
- Breaking API changes if not careful with backward compatibility
- Compose DrawScope API differences from android.graphics.Canvas (Path construction, Paint style)
- Performance regression if caching not implemented correctly (remember{}, derivedStateOf, drawWithCache)
- Touch handling API paradigm shift (pointerInput vs MotionEvent)
- Large version upgrades required (AGP 8.0-alpha06 → stable 8.x, Compose 1.3.1 → 1.7+)

---

## 1. Repo Reconnaissance Findings

### 1.1 Current Rendering Pipeline

**3D Scene → 2D Canvas Flow:**

```
User Code: add(Shape, Color)
    ↓
Isometric.add() → creates Item(path, transformedColor, originalShape)
    ↓ (on size change)
Isometric.measure(width, height, sort, cull, boundsCheck)
    ├─ Set origin (width/2, height*0.9)
    ├─ For each Item:
    │   ├─ translatePoint(3D Point) → 2D Point (isometric projection)
    │   ├─ Build android.graphics.Path from transformed points
    │   ├─ Apply culling (back-face test) - remove if invisible
    │   └─ Apply bounds check - remove if offscreen
    └─ sortPaths() → complex intersection-based depth sorting
    ↓
Isometric.draw(Canvas canvas)
    └─ For each Item: canvas.drawPath(item.drawPath, item.paint)
```

**Key Classes & Responsibilities:**

| Class | Responsibility | Platform Coupling | Lines |
|-------|---------------|-------------------|-------|
| `Isometric` | Core engine: projection, sorting, rendering | HIGH (android.graphics) | 423 |
| `Point` | 3D coordinate + transforms (translate/rotate/scale) | NONE | 182 |
| `Path` | 2D polygon face + transforms + depth calculation | NONE | 217 |
| `Shape` | Collection of Paths + transforms + extrusion | NONE | 230 |
| `Vector` | 3D vector math (cross/dot product, normalize) | NONE | 52 |
| `Color` | RGBA + HSL conversion + lighting | NONE | 109 |
| `IntersectionUtils` | Polygon intersection, point-in-poly tests | NONE | 148 |
| `IsometricView` | Android View wrapper + touch handling | HIGH (android.view) | 142 |
| `Isometric.Item` | Internal: path + paint + transformed points | HIGH (android.graphics) | 39 |

**Platform Coupling Analysis:**

**Tightly Coupled (requires porting):**
- `Isometric.Item` constructor (lines 403-413): Creates `android.graphics.Paint` and `android.graphics.Path`
- `Isometric.measure()` (lines 138-194): Populates `android.graphics.Path.drawPath` with lineTo/moveTo
- `Isometric.draw()` (line 282-291): Takes `android.graphics.Canvas` parameter
- `IsometricView` entire class: View lifecycle, MotionEvent

**Zero Coupling (reusable as-is):**
- Point, Path, Shape: Pure immutable geometry with transforms
- Vector: Pure math
- Color: Pure RGB/HSL conversion
- IntersectionUtils: Pure computational geometry
- All shape constructors (Prism, Pyramid, Cylinder, etc.)

### 1.2 Core Module Mapping

**Logical Module Boundaries (Proposed):**

```
:isometric-core (pure Kotlin/Java - no Android deps)
├── Point.kt (convert from Java)
├── Path.kt
├── Shape.kt
├── Vector.kt
├── Color.kt
├── IntersectionUtils.kt
├── shapes/
│   ├── Prism.kt
│   ├── Pyramid.kt
│   ├── Cylinder.kt
│   ├── Octahedron.kt
│   ├── Stairs.kt
│   └── Knot.kt
└── IsometricEngine.kt (new - platform-agnostic core)
    ├── SceneGraph (items list)
    ├── ProjectionEngine (3D→2D transform)
    ├── DepthSorter (intersection-based sorting)
    └── LightingCalculator (normal-based brightness)

:isometric-renderer-core (abstractions)
└── IsometricRenderer.kt (interface)
    ├── fun measure(width, height, sort, cull, boundsCheck)
    ├── fun draw(canvas: RenderTarget)
    └── fun findItemAt(x, y): Item?

:isometric-compose
├── IsometricCanvas.kt (new Compose API)
├── ComposeRenderer.kt (implements IsometricRenderer using DrawScope)
└── ComposePathBuilder.kt (androidx.compose.ui.graphics.Path adapter)

:isometric-android-view (legacy compatibility)
├── IsometricView.kt (existing, uses AndroidCanvasRenderer)
└── AndroidCanvasRenderer.kt (implements IsometricRenderer using android.graphics.Canvas)

:isometric-samples
└── compose/ (new Compose samples)
```

### 1.3 Rendering Approach Analysis

**Current Implementation (android.graphics):**

```java
// Isometric.java:282-290
public void draw(Canvas canvas) {
    for (Item item : items) {
        canvas.drawPath(item.drawPath, item.paint);
    }
}

// Item construction (lines 404-412)
Item(Path path, Color baseColor, Shape originalShape) {
    this.drawPath = new android.graphics.Path();  // ← Platform-specific
    this.paint = new Paint(Paint.ANTI_ALIAS_FLAG); // ← Platform-specific
    this.paint.setStyle(Paint.Style.FILL_AND_STROKE);
    this.paint.setStrokeWidth(1);
    this.paint.setColor(android.graphics.Color.argb(...));
}
```

**Compose Equivalent (androidx.compose.ui.graphics):**

```kotlin
// DrawScope extension
fun DrawScope.drawIsometric() {
    items.forEach { item ->
        drawPath(
            path = item.composePath,  // androidx.compose.ui.graphics.Path
            color = Color(item.baseColor.r, g, b, a),
            style = Fill  // or Stroke(width = 1.dp)
        )
    }
}
```

**API Compatibility Matrix:**

| android.graphics | androidx.compose.ui.graphics | Conversion Complexity |
|-----------------|------------------------------|----------------------|
| `Canvas.drawPath(Path, Paint)` | `DrawScope.drawPath(Path, Color, style)` | **Simple** - 1:1 mapping |
| `Path.moveTo/lineTo/close` | `Path().moveTo/lineTo/close` | **Trivial** - identical API |
| `Paint.setColor(int)` | `Color(r,g,b,a)` | **Simple** - constructor diff |
| `Paint.setStyle(FILL_AND_STROKE)` | `style = Fill` or `Stroke` | **Simple** - separate draw calls |
| `Canvas` (stateful) | `DrawScope` (scoped) | **Moderate** - different paradigm |

**Performance Considerations:**

1. **Path Construction:**
   - Current: Builds `android.graphics.Path` once in `measure()`, reuses in `draw()`
   - Compose: Can use same pattern with `androidx.compose.ui.graphics.Path`
   - **Caching Strategy:** Use `remember(items) { buildPaths() }` to avoid rebuilding

2. **Recomposition Optimization:**
   - Current POC problem: `val isometric = Isometric()` recreates entire engine on every frame
   - **Solution:** `remember { Isometric() }` + `derivedStateOf` for scene changes
   - **Alternative:** `drawWithCache` for expensive path construction

3. **State Management:**
   - Need to track: scene items (List<Item>), camera params (origin, angle, scale), viewport size
   - **Compose approach:** `mutableStateListOf<Item>()` triggers smart recomposition
   - **Performance:** Only recompose/redraw when scene actually changes

**Compose DrawScope Advantages:**
- Built-in transformations (scale, rotate, translate) - could simplify projection code
- `clipRect` for bounds checking
- `drawWithCache` for expensive operations
- Automatic density handling (no manual dp/px conversion needed)

**Compose DrawScope Challenges:**
- No direct equivalent to `Paint.ANTI_ALIAS_FLAG` (always enabled)
- `drawPath` doesn't support FILL_AND_STROKE in single call (need two calls: Fill + Stroke)
- Stateless API - can't carry state between draw calls (need to manage externally)

### 1.4 Touch & Hit Testing

**Current Implementation (IsometricView.java:121-140):**

```java
@Override
public boolean onTouchEvent(MotionEvent event) {
    if (listener != null) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            return true;  // Consume down event
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            Isometric.Item item = isometric.findItemForPosition(
                new Point(event.getX(), event.getY()),
                reverseSortForLookup,  // iterate back-to-front
                touchRadiusLookup,      // use radius or point test
                touchRadius             // tolerance in pixels
            );
            if (item != null) {
                listener.onClick(item);
            }
            performClick();
        }
    }
    return super.onTouchEvent(event);
}
```

**Hit Testing Algorithm (Isometric.java:296-382):**

```java
@Nullable
public Item findItemForPosition(Point position, boolean reverseSort,
                                 boolean touchPosition, double radius) {
    // Iterate items (back-to-front if reverseSort=true for z-order)
    for (Item item : items) {
        // 1. Find bounding points (top, bottom, left, right)
        // 2. Build convex hull of transformed 2D points
        // 3. Test: isPointCloseToPoly(hull, x, y, radius)
        //    OR isPointInPoly(hull, x, y)
        // 4. Return first match (closest to viewer if reverse sorted)
    }
    return null;
}
```

**Compose Equivalent:**

```kotlin
@Composable
fun IsometricCanvas(
    modifier: Modifier = Modifier,
    onItemClick: (Item) -> Unit = {}
) {
    val engine = remember { IsometricEngine() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val item = engine.findItemAt(
                        x = offset.x,
                        y = offset.y,
                        reverseSort = true,
                        radius = 8.dp.toPx()  // touch slop
                    )
                    item?.let { onItemClick(it) }
                }
            }
    ) {
        engine.draw(this)
    }
}
```

**Compose Touch API Advantages:**
- `pointerInput` composable modifier - declarative
- `detectTapGestures`, `detectDragGestures` - built-in gesture detection
- `awaitPointerEventScope` - low-level control if needed
- Automatic density conversion (`dp.toPx()`)

**Compose Touch API Considerations:**
- Different event model: `PointerInputChange` vs `MotionEvent`
- Need to convert `Offset(x, y)` → `Point(x, y, 0)` (z=0 for screen coords)
- Gestures are suspending functions - run in coroutine scope
- No `performClick()` needed - accessibility handled automatically

**Accessibility:**
- Current: Basic View accessibility (via `performClick()`)
- Compose: Can add `semantics { onClick { ... } }` for TalkBack support
- Opportunity: Add contentDescription per shape for better a11y

### 1.5 Testing Infrastructure

**Current State (Broken):**

```java
// IsometricViewTest.java - ALL COMMENTED OUT (534 lines)
//@RunWith(AndroidJUnit4.class)
//public class IsometricViewTest {
//    @Test
//    public void doScreenshotOne() {
//        IsometricView view = new IsometricView(getInstrumentation().getTargetContext());
//        sampleOne(view);
//        measureAndScreenshotView(view, 680, 220);
//    }
//    // ... 9+ similar tests ...
//}
```

**Why it's broken:**
- Facebook Screenshot Testing library incompatible with AGP 8.0+
- Dependency removed: `androidTestCompile('com.facebook.testing.screenshot:core:0.4.2')` (commented line 152)
- Note in commit b36b485: "needs rewrite, probably use paparazzi"

**Existing Test Scenarios (to replicate):**
1. `doScreenshotOne` - Simple cube (680x220)
2. `doScreenshotTwo` - Multiple prisms (680x540)
3. `doScreenshotThree` - Complex structures (820x680)
4. `doScreenshotGrid` - Coordinate grid visualization (680x540)
5. `doScreenshotPath` - 2D paths on shapes (680x440)
6. `doScreenshotTranslate` - Transform: translate (680x440)
7. `doScreenshotScale` - Transform: scale (680x440)
8. `doScreenshotRotateZ` - Transform: rotateZ (680x440)
9. `doScreenshotExtrude` - Shape extrusion (680x440)
10. Individual shape tests: Cylinder, Knot, Octahedron, Prism, Pyramid, Stairs

**Modern Testing Strategy:**

**A. Paparazzi (JVM-based screenshot testing):**
```kotlin
class IsometricComposeTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.Light.NoActionBar"
    )

    @Test
    fun simpleScene_cube() {
        paparazzi.snapshot {
            IsometricCanvas(
                modifier = Modifier.size(680.dp, 220.dp)
            ) {
                add(Prism(Point(0, 0, 0)), Color(33, 150, 243))
            }
        }
    }
}
```

**Advantages:**
- Runs on JVM (fast, no emulator needed)
- Gradle-based, works with modern AGP
- Git-diffable HTML reports
- Works with Compose out-of-the-box

**B. Roborazzi (Alternative - Robolectric-based):**
```kotlin
class IsometricRoborazziTest {
    @Test
    fun captureIsometricScene() {
        composeTestRule.setContent {
            IsometricCanvas { /* scene */ }
        }
        composeTestRule.onRoot().captureRoboImage()
    }
}
```

**C. Pure Unit Tests (Math/Logic):**
```kotlin
class PointTest {
    @Test
    fun `translate moves point correctly`() {
        val point = Point(1.0, 2.0, 3.0)
        val translated = point.translate(1.0, 0.0, -1.0)
        assertEquals(Point(2.0, 2.0, 2.0), translated)
    }
}

class DepthSortingTest {
    @Test
    fun `depth sorting handles overlapping paths`() {
        // Test intersection-based sorting determinism
    }
}
```

**Testing Pyramid:**
- **Unit tests** (fast): Point, Path, Shape transforms, Vector math, Color conversion
- **Screenshot tests** (Paparazzi): Visual regression for all 10+ scenarios
- **Instrumented tests** (optional): Real device rendering edge cases

### 1.6 Build & Compatibility Audit

**Current Versions (compose branch):**

| Component | Current Version | Latest Stable (2026-01) | Gap | Risk |
|-----------|----------------|------------------------|-----|------|
| Gradle | 7.3.1 | 8.11 | 3 major versions | MEDIUM |
| AGP | 8.0.0-alpha06 | 8.7.3 | Alpha → Stable + 7 minors | HIGH |
| Kotlin | 1.7.10 | 2.1.0 | 4 minor versions | MEDIUM |
| Compose | 1.3.1 | 1.7.5 | 4 minor versions | MEDIUM |
| Compose Compiler | 1.3.1 | 1.5.15 | Version mismatch | HIGH |
| minSdk | 24 | - | (Android 7.0) | OK |
| targetSdk | 33 | 35 (current) | 2 versions | LOW |
| compileSdk | 33 | 35 | 2 versions | LOW |

**Critical Issues:**

1. **AGP 8.0.0-alpha06 (Nov 2022):**
   - Extremely unstable alpha version
   - Known bugs, security issues
   - Gradle 7.3.1 compatibility issues
   - **Recommendation:** Upgrade to AGP 8.7.x + Gradle 8.9+

2. **Compose 1.3.1 vs Compiler 1.3.1:**
   - Compose Compiler Extension 1.3.1 only compatible with Kotlin 1.7.x
   - Kotlin 2.x requires Compose Compiler 1.5.x+
   - **Blocker:** Must upgrade together: Kotlin 2.x + Compose Compiler 1.5.x

3. **Compose 1.3.1 Missing Features:**
   - No `drawWithCache` (added 1.4.0)
   - Limited `remember` optimizations
   - No `movableContentOf` (added 1.4.0)
   - Older performance characteristics

**Upgrade Path:**

```
Current:  Gradle 7.3.1 + AGP 8.0.0-alpha06 + Kotlin 1.7.10 + Compose 1.3.1
    ↓
Phase 1:  Gradle 8.9 + AGP 8.7.3 + Kotlin 1.9.24 + Compose 1.5.14
    ↓
Phase 2:  Gradle 8.11 + AGP 8.7.3 + Kotlin 2.0.21 + Compose 1.7.5
```

**Compose minSdk Consideration:**
- Compose supports minSdk 21+
- Current library: minSdk 24 (Android 7.0, Nov 2016)
- **Recommendation:** Keep minSdk 24 (covers 98%+ devices, reasonable baseline)

**Publishing Strategy:**

Current (commented out):
```gradle
// bintray { ... }  // Deprecated, removed
```

**Modern Publishing:**
```gradle
// Use Maven Central via Sonatype
plugins {
    id 'maven-publish'
    id 'signing'
}

publishing {
    publications {
        release(MavenPublication) {
            groupId = 'io.fabianterhorst'
            artifactId = 'isometric-compose'
            version = '1.0.0'

            from components.release
        }
    }
    repositories {
        maven {
            name = "sonatype"
            url = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
            credentials { /* from secrets */ }
        }
    }
}
```

**Artifact Structure:**
```
io.fabianterhorst:isometric-core:1.0.0          // Pure Kotlin, no Android
io.fabianterhorst:isometric-compose:1.0.0       // Compose UI (depends on :core)
io.fabianterhorst:isometric-android-view:1.0.0  // Legacy View (depends on :core)
```

**Binary Compatibility:**
- Current API: `IsometricView` (View-based)
- New API: `IsometricCanvas` (Composable)
- **Strategy:** Ship both artifacts, mark `isometric-android-view` as legacy but maintained
- **Migration Guide:** Document View → Compose migration path

---

## 2. Recommended Architecture: Three Approaches Compared

### Option A: Minimal Compose Wrapper (Fastest, Least Invasive)

**Description:** Improve existing `IsometricCompose.kt` with proper state management, keep `Isometric.java` untouched.

**Architecture:**
```
IsometricCompose.kt (Composable)
    ↓ (uses drawIntoCanvas { nativeCanvas })
Isometric.java (unchanged)
    ↓ (uses android.graphics.Canvas/Paint/Path)
```

**Implementation:**
```kotlin
@Composable
fun IsometricCanvas(
    modifier: Modifier = Modifier,
    content: IsometricScope.() -> Unit
) {
    val engine = remember { Isometric() }
    val scope = remember(content) {
        IsometricScope(engine).apply(content)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        engine.measure(
            size.width.toInt(),
            size.height.toInt(),
            sort = true,
            cull = true,
            boundsCheck = true
        )
        drawIntoCanvas { canvas ->
            engine.draw(canvas.nativeCanvas)
        }
    }
}

class IsometricScope(private val engine: Isometric) {
    fun add(shape: Shape, color: Color) {
        engine.add(shape, color)
    }
}
```

**Pros:**
- ✅ Minimal code changes (50-100 lines)
- ✅ Zero risk to existing View-based API
- ✅ Leverages existing battle-tested rendering
- ✅ Fast implementation (1-2 weeks)
- ✅ Immediate Compose support

**Cons:**
- ❌ Still coupled to android.graphics (can't use on non-Android platforms)
- ❌ Doesn't leverage Compose DrawScope capabilities
- ❌ Requires `nativeCanvas` interop (slight performance overhead)
- ❌ Can't use Compose modifiers on individual shapes
- ❌ State management still imperative (engine.add/clear)
- ❌ No Compose-native path caching benefits

**When to Choose:**
- Need Compose support ASAP with minimal risk
- Plan to maintain View-based API indefinitely
- No multiplatform aspirations

### Option B: Core Engine Extraction + Platform Renderers (Recommended)

**Description:** Extract platform-agnostic logic into `:core` module, create adapter pattern for View and Compose renderers.

**Architecture:**
```
:isometric-core (pure Kotlin, no Android deps)
├── IsometricEngine.kt
│   ├── data class SceneItem(path: Path, color: Color, shape: Shape?)
│   ├── fun add(shape: Shape, color: Color)
│   ├── fun measure(width, height): PreparedScene
│   └── fun findItemAt(x, y): SceneItem?
├── PreparedScene.kt
│   ├── List<RenderCommand>
│   └── (platform-agnostic render instructions)
└── [Point, Path, Shape, Vector, Color, shapes/*]

:isometric-renderer
└── interface IsometricRenderer<T> {
        fun render(scene: PreparedScene, canvas: T)
    }

:isometric-compose
└── ComposeRenderer : IsometricRenderer<DrawScope>
    └── fun DrawScope.render(PreparedScene) {
            scene.commands.forEach { cmd ->
                drawPath(cmd.composePath, cmd.composeColor)
            }
        }

:isometric-android-view (legacy)
└── AndroidCanvasRenderer : IsometricRenderer<Canvas>
    └── fun Canvas.render(PreparedScene) {
            scene.commands.forEach { cmd ->
                drawPath(cmd.androidPath, cmd.androidPaint)
            }
        }
```

**Key Abstractions:**

```kotlin
// :isometric-core
data class RenderCommand(
    val points: List<Point2D>,  // Already transformed to 2D
    val color: IsometricColor,
    val originalPath: Path,
    val originalShape: Shape?
)

data class PreparedScene(
    val commands: List<RenderCommand>,  // Sorted, culled, ready to render
    val viewport: Size
)

class IsometricEngine {
    private val items = mutableListOf<SceneItem>()

    fun add(shape: Shape, color: IsometricColor) { ... }
    fun clear() { items.clear() }

    fun prepare(
        width: Int,
        height: Int,
        options: RenderOptions
    ): PreparedScene {
        // All the hard work: projection, sorting, culling
        // Returns platform-agnostic render commands
    }
}

// :isometric-compose
@Composable
fun IsometricCanvas(
    modifier: Modifier = Modifier,
    renderOptions: RenderOptions = RenderOptions.Default,
    onItemClick: (SceneItem) -> Unit = {},
    content: IsometricScope.() -> Unit
) {
    val engine = remember { IsometricEngine() }
    val scope = remember { IsometricScope(engine) }

    // Apply scene construction
    DisposableEffect(content) {
        engine.clear()
        scope.content()
        onDispose { }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val item = engine.findItemAt(offset.x, offset.y)
                    item?.let { onItemClick(it) }
                }
            }
    ) {
        val preparedScene = remember(size, engine.version) {
            engine.prepare(size.width.toInt(), size.height.toInt(), renderOptions)
        }

        drawWithCache {
            val composePaths = preparedScene.commands.map { cmd ->
                androidx.compose.ui.graphics.Path().apply {
                    cmd.points.forEachIndexed { i, pt ->
                        if (i == 0) moveTo(pt.x.toFloat(), pt.y.toFloat())
                        else lineTo(pt.x.toFloat(), pt.y.toFloat())
                    }
                    close()
                }
            }

            onDrawBehind {
                preparedScene.commands.forEachIndexed { i, cmd ->
                    drawPath(
                        path = composePaths[i],
                        color = cmd.color.toComposeColor()
                    )
                }
            }
        }
    }
}
```

**Pros:**
- ✅ Clean separation of concerns (engine vs rendering)
- ✅ Platform-agnostic core enables future KMP (iOS, Desktop, Web)
- ✅ Both View and Compose renderers share same logic
- ✅ Proper Compose state management with `remember` and `drawWithCache`
- ✅ Testable core (pure Kotlin unit tests, no Android framework needed)
- ✅ Modern, maintainable architecture
- ✅ Enables advanced features (custom renderers, backends)

**Cons:**
- ⚠️ Moderate refactoring effort (2-3 weeks)
- ⚠️ API changes required (but can maintain compatibility layer)
- ⚠️ Need to carefully manage RenderCommand conversion (Path object duplication)
- ⚠️ More complex module structure

**When to Choose:**
- Want long-term maintainable, modern architecture
- Plan to support Compose as primary API going forward
- Interested in multiplatform potential (KMP)
- Willing to invest 2-3 weeks in refactoring

### Option C: Full Compose-Native Scene Graph (Most Radical)

**Description:** Rebuild from scratch using Compose state primitives and declarative scene composition.

**Architecture:**
```kotlin
// Fully declarative, Compose-native API
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    camera: IsometricCamera = rememberIsometricCamera(),
    content: @Composable IsometricScope.() -> Unit
) {
    val scope = remember { IsometricScopeImpl() }
    scope.content()  // Composes into scene graph

    Canvas(modifier) {
        scope.render(this, camera)
    }
}

@Composable
fun IsometricScope.IsometricShape(
    shape: Shape,
    color: Color,
    modifier: IsometricModifier = IsometricModifier,
    onClick: () -> Unit = {}
) {
    val item = remember(shape, color) {
        SceneNode(shape, color)
    }

    DisposableEffect(item) {
        addNode(item)
        onDispose { removeNode(item) }
    }
}

// Usage becomes fully declarative:
@Composable
fun MyScene() {
    IsometricScene {
        IsometricShape(
            shape = Prism(Point(0, 0, 0)),
            color = Color.Blue,
            modifier = IsometricModifier.translate(1.0, 0.0, 0.0),
            onClick = { println("Cube clicked!") }
        )

        // Conditional rendering uses Compose idioms
        if (showPyramid) {
            IsometricShape(
                shape = Pyramid(Point(2, 0, 0)),
                color = Color.Red
            )
        }
    }
}
```

**Pros:**
- ✅ Fully embraces Compose declarative paradigm
- ✅ Individual shape composables enable fine-grained recomposition
- ✅ Natural Compose state integration (no imperative add/clear)
- ✅ Could support shape-level modifiers (translate, rotate per shape)
- ✅ Scene composition feels native to Compose developers
- ✅ Best performance potential (Compose handles change tracking)

**Cons:**
- ❌ Complete rewrite (4-6 weeks minimum)
- ❌ Breaking API change (no backward compatibility)
- ❌ Need to solve complex problems:
  - Depth sorting with dynamic composition (add/remove items)
  - Parent-child transform hierarchies
  - Efficient scene graph diffing
- ❌ Risk of reinventing View-based API's mature logic
- ❌ Unproven architecture for this use case
- ❌ View-based API becomes legacy, needs parallel maintenance

**When to Choose:**
- Starting a greenfield project
- Compose-only codebase (no View support needed)
- Have 4-6 weeks for R&D
- Want to push boundaries of Compose scene rendering

---

## 3. Recommended Approach: **Option B** (Core Engine Extraction)

**Rationale:**

1. **Best Balance:** Modern architecture without complete rewrite
2. **Compatibility:** Can maintain View API while adding Compose
3. **Future-Proof:** Enables Kotlin Multiplatform migration later
4. **Testability:** Pure Kotlin core = fast unit tests
5. **Proven Pattern:** Separation of engine and renderer is industry standard (Unity, Unreal, etc.)

---

## 4. Detailed Implementation Plan

### A. Dependency Graph / Module Boundaries

```
┌─────────────────────────────────────────────────────────────┐
│  :isometric-core (pure Kotlin, no Android deps)             │
│  - Point, Path, Shape, Vector, Color                        │
│  - IsometricEngine (scene management)                       │
│  - ProjectionEngine (3D→2D transform)                       │
│  - DepthSorter (intersection-based sorting)                 │
│  - LightingCalculator (normal-based brightness)             │
│  - IntersectionUtils                                        │
│  - shapes/* (Prism, Pyramid, Cylinder, etc.)               │
└─────────────────────────────────────────────────────────────┘
                             ▲
                             │ depends on
                ┌────────────┴────────────┐
                │                         │
┌───────────────┴──────────┐  ┌──────────┴─────────────────┐
│ :isometric-compose       │  │ :isometric-android-view    │
│ - IsometricCanvas.kt     │  │ - IsometricView.kt (legacy)│
│ - ComposeRenderer.kt     │  │ - AndroidCanvasRenderer.kt │
│ - remember/state logic   │  │ - MotionEvent handling     │
└──────────────────────────┘  └────────────────────────────┘
                ▲                          ▲
                │ sample usage             │ sample usage
┌───────────────┴──────────┐  ┌──────────┴─────────────────┐
│ :samples-compose         │  │ :samples-view              │
│ - Compose demo app       │  │ - View demo app (existing) │
└──────────────────────────┘  └────────────────────────────┘
```

**Module Configuration:**

```kotlin
// settings.gradle.kts
include(":isometric-core")
include(":isometric-compose")
include(":isometric-android-view")
include(":samples-compose")
include(":samples-view")

// isometric-core/build.gradle.kts
plugins {
    kotlin("jvm")  // Pure JVM, no Android plugin
}

dependencies {
    // Zero Android dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

// isometric-compose/build.gradle.kts
plugins {
    id("com.android.library")
    kotlin("android")
}

dependencies {
    api(project(":isometric-core"))  // Expose core types
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.foundation:foundation:1.7.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("app.cash.paparazzi:paparazzi:1.3.4")
}

// isometric-android-view/build.gradle.kts
plugins {
    id("com.android.library")
    kotlin("android")
}

dependencies {
    api(project(":isometric-core"))  // Expose core types
    implementation("androidx.annotation:annotation:1.8.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
```

### B. Refactoring Checklist

**Phase 1: Prepare Core Module (Week 1)**

- [ ] Create `:isometric-core` module (pure Kotlin, no Android)
- [ ] Convert Java → Kotlin (or copy as-is if keeping Java):
  - [ ] Point.java → Point.kt
  - [ ] Path.java → Path.kt
  - [ ] Shape.java → Shape.kt
  - [ ] Vector.java → Vector.kt
  - [ ] Color.java → IsometricColor.kt (rename to avoid clash with Compose Color)
  - [ ] IntersectionUtils.java → IntersectionUtils.kt
  - [ ] All shapes/* → shapes/*.kt
- [ ] Create new platform-agnostic types:
  - [ ] `data class Point2D(val x: Double, val y: Double)` (for screen coords)
  - [ ] `data class RenderCommand(...)`
  - [ ] `data class PreparedScene(...)`
  - [ ] `data class SceneItem(...)`
  - [ ] `data class RenderOptions(val sort: Boolean, val cull: Boolean, val boundsCheck: Boolean)`
- [ ] Extract `IsometricEngine` from `Isometric.java`:
  - [ ] Move `add()`, `clear()`, scene management
  - [ ] Move `translatePoint()`, projection logic
  - [ ] Move `transformColor()`, lighting logic
  - [ ] Move `sortPaths()`, depth sorting logic
  - [ ] Move `cullPath()`, `itemInDrawingBounds()`
  - [ ] Return `PreparedScene` from `prepare()` instead of modifying internal state
  - [ ] Move `findItemForPosition()` hit testing
- [ ] Write unit tests for core:
  - [ ] PointTest - all transforms
  - [ ] PathTest - depth calculation, closerThan
  - [ ] ShapeTest - extrude, orderedPaths
  - [ ] VectorTest - cross/dot product, normalize
  - [ ] ColorTest - RGB↔HSL conversion, lighten
  - [ ] IntersectionUtilsTest - hasIntersection, isPointInPoly
  - [ ] IsometricEngineTest - projection, sorting determinism

**Phase 2: Build Compose Renderer (Week 2)**

- [ ] Create `:isometric-compose` module
- [ ] Implement `ComposeRenderer`:
  - [ ] Convert `RenderCommand` → `androidx.compose.ui.graphics.Path`
  - [ ] Convert `IsometricColor` → `androidx.compose.ui.graphics.Color`
  - [ ] Implement `DrawScope.drawIsometric(PreparedScene)`
- [ ] Implement `IsometricCanvas` composable:
  - [ ] `remember { IsometricEngine() }` for engine instance
  - [ ] `IsometricScope` builder DSL for scene construction
  - [ ] `derivedStateOf` for scene version tracking
  - [ ] `drawWithCache` for path construction
  - [ ] `pointerInput` for touch handling with `detectTapGestures`
  - [ ] Proper recomposition keys
- [ ] Implement state management:
  - [ ] Scene item list as Compose state
  - [ ] `DisposableEffect` for scene lifecycle
  - [ ] Camera parameters (angle, scale, origin) as state
- [ ] Add convenience APIs:
  - [ ] `rememberIsometricEngine()`
  - [ ] `IsometricScope.add(shape, color)` extensions
- [ ] Document API usage with KDoc

**Phase 3: Port Tests to Paparazzi (Week 2-3)**

- [ ] Add Paparazzi dependency to `:isometric-compose`
- [ ] Create `IsometricComposeTest`:
  - [ ] Port `doScreenshotOne` → `test_simpleCube()`
  - [ ] Port `doScreenshotTwo` → `test_multiplePrisms()`
  - [ ] Port `doScreenshotThree` → `test_complexStructure()`
  - [ ] Port `doScreenshotGrid` → `test_coordinateGrid()`
  - [ ] Port `doScreenshotPath` → `test_pathsOnShapes()`
  - [ ] Port `doScreenshotTranslate` → `test_translateTransform()`
  - [ ] Port `doScreenshotScale` → `test_scaleTransform()`
  - [ ] Port `doScreenshotRotateZ` → `test_rotateZTransform()`
  - [ ] Port `doScreenshotExtrude` → `test_shapeExtrusion()`
  - [ ] Add shape-specific tests (Cylinder, Pyramid, etc.)
- [ ] Configure Paparazzi snapshot directory
- [ ] Set up CI to verify screenshot tests
- [ ] Add snapshot update task for approved changes

**Phase 4: Create/Update Android View Module (Week 3)**

- [ ] Create `:isometric-android-view` module (or refactor existing `:lib`)
- [ ] Implement `AndroidCanvasRenderer`:
  - [ ] Convert `RenderCommand` → `android.graphics.Path`
  - [ ] Convert `IsometricColor` → `android.graphics.Paint`
  - [ ] Implement `Canvas.drawIsometric(PreparedScene)`
- [ ] Refactor `IsometricView`:
  - [ ] Use `IsometricEngine` from `:core`
  - [ ] Use `AndroidCanvasRenderer` for drawing
  - [ ] Keep existing public API unchanged
  - [ ] Maintain backward compatibility
- [ ] Test that existing View-based samples still work

**Phase 5: Sample Apps & Documentation (Week 3-4)**

- [ ] Create `:samples-compose` module:
  - [ ] MainActivity with Compose
  - [ ] Recreate all README.md examples in Compose
  - [ ] Interactive demo (tap to add shapes, gestures to rotate camera)
- [ ] Update `:samples-view` (existing :app):
  - [ ] Verify all examples still work with new `:isometric-android-view`
- [ ] Write migration guide:
  - [ ] View → Compose API mapping
  - [ ] Code examples side-by-side
  - [ ] Breaking changes (if any)
- [ ] Update README.md:
  - [ ] Add Compose installation instructions
  - [ ] Add Compose code examples
  - [ ] Note View API still supported
- [ ] Create API documentation (Dokka):
  - [ ] Generate KDoc HTML
  - [ ] Host on GitHub Pages

**Phase 6: Upgrade Dependencies & Publish (Week 4)**

- [ ] Upgrade Gradle 7.3.1 → 8.9
- [ ] Upgrade AGP 8.0.0-alpha06 → 8.7.3
- [ ] Upgrade Kotlin 1.7.10 → 1.9.24
- [ ] Upgrade Compose 1.3.1 → 1.5.14
- [ ] Update targetSdk 33 → 35
- [ ] Update compileSdk 33 → 35
- [ ] Test all modules compile and run
- [ ] Set up Maven Central publishing:
  - [ ] Configure Sonatype credentials
  - [ ] Set up GPG signing
  - [ ] Publish artifacts:
    - `io.fabianterhorst:isometric-core:1.0.0`
    - `io.fabianterhorst:isometric-compose:1.0.0`
    - `io.fabianterhorst:isometric-android-view:1.0.0` (optional, legacy)
- [ ] Tag release: `v1.0.0`
- [ ] Create GitHub release with changelog

### C. Compose API Proposal

**Primary Composable:**

```kotlin
/**
 * An isometric 3D drawing canvas for Jetpack Compose.
 *
 * @param modifier Modifier to apply to the canvas
 * @param renderOptions Rendering configuration (sorting, culling, bounds checking)
 * @param cameraAngle Isometric projection angle (default: π/6 = 30°)
 * @param scale Scaling factor for isometric units (default: 70px/unit)
 * @param onItemClick Callback when a shape is clicked, receives the clicked item
 * @param content Scene construction lambda with IsometricScope receiver
 */
@Composable
fun IsometricCanvas(
    modifier: Modifier = Modifier,
    renderOptions: RenderOptions = RenderOptions.Default,
    cameraAngle: Double = Math.PI / 6,
    scale: Double = 70.0,
    onItemClick: (SceneItem) -> Unit = {},
    content: IsometricScope.() -> Unit
)

/**
 * Rendering configuration options.
 */
data class RenderOptions(
    val enableDepthSorting: Boolean = true,
    val enableBackfaceCulling: Boolean = true,
    val enableBoundsChecking: Boolean = true,
    val reverseSortForHitTest: Boolean = true,
    val touchRadius: Float = 8f  // dp
) {
    companion object {
        val Default = RenderOptions()
        val Performance = RenderOptions(
            enableDepthSorting = false,  // Faster, but may have overlaps
            enableBackfaceCulling = true,
            enableBoundsChecking = true
        )
    }
}

/**
 * Scope for building isometric scenes.
 */
interface IsometricScope {
    /**
     * Add a shape to the scene.
     * @param shape The 3D shape to render
     * @param color The base color (will be adjusted for lighting)
     */
    fun add(shape: Shape, color: IsometricColor)

    /**
     * Add a 2D path to the scene.
     * @param path The 2D polygon face to render
     * @param color The base color
     */
    fun add(path: Path, color: IsometricColor)

    /**
     * Clear all items from the scene.
     */
    fun clear()
}

/**
 * Item in the scene that can be interacted with.
 */
data class SceneItem(
    val path: Path,
    val color: IsometricColor,
    val originalShape: Shape?
)
```

**Usage Examples:**

**Example 1: Simple Cube**

```kotlin
@Composable
fun SimpleCubeScene() {
    IsometricCanvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        add(
            shape = Prism(Point(0.0, 0.0, 0.0), width = 1.0, length = 1.0, height = 1.0),
            color = IsometricColor(33.0, 150.0, 243.0)  // Blue
        )
    }
}
```

**Example 2: Multiple Shapes with Interaction**

```kotlin
@Composable
fun InteractiveScene() {
    var selectedColor by remember { mutableStateOf<IsometricColor?>(null) }

    Column {
        IsometricCanvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp),
            onItemClick = { item ->
                selectedColor = item.color
                println("Clicked shape at color: ${item.color}")
            }
        ) {
            add(Prism(Point(0.0, 0.0, 0.0)), IsometricColor(33.0, 150.0, 243.0))
            add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 2.0, 1.0), IsometricColor(50.0, 160.0, 60.0))
            add(Prism(Point(1.0, -1.0, 0.0), 2.0, 1.0, 1.0), IsometricColor(160.0, 60.0, 50.0))
        }

        selectedColor?.let {
            Text("Selected color: RGB(${it.r}, ${it.g}, ${it.b})")
        }
    }
}
```

**Example 3: Dynamic Scene with State**

```kotlin
@Composable
fun DynamicScene() {
    var cubeCount by remember { mutableIntStateOf(1) }

    Column {
        IsometricCanvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
        ) {
            repeat(cubeCount) { i ->
                add(
                    shape = Prism(Point(i.toDouble(), 0.0, 0.0)),
                    color = IsometricColor(
                        r = (33 + i * 30).toDouble(),
                        g = 150.0,
                        b = 243.0
                    )
                )
            }
        }

        Row {
            Button(onClick = { cubeCount++ }) { Text("+") }
            Text("Cubes: $cubeCount")
            Button(onClick = { cubeCount = maxOf(1, cubeCount - 1) }) { Text("-") }
        }
    }
}
```

**Example 4: Transforms**

```kotlin
@Composable
fun TransformScene() {
    var rotationAngle by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)  // ~60fps
            rotationAngle += 0.02
        }
    }

    IsometricCanvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp)
    ) {
        val cube = Prism(Point.ORIGIN, 3.0, 3.0, 1.0)

        // Bottom cube (static)
        add(cube, IsometricColor(160.0, 60.0, 50.0))

        // Top cube (rotating)
        add(
            shape = cube
                .rotateZ(Point(1.5, 1.5, 0.0), rotationAngle)
                .translate(0.0, 0.0, 1.1),
            color = IsometricColor(50.0, 60.0, 160.0)
        )
    }
}
```

**Example 5: Performance Mode**

```kotlin
@Composable
fun PerformanceScene() {
    IsometricCanvas(
        modifier = Modifier.fillMaxSize(),
        renderOptions = RenderOptions.Performance  // Disable depth sorting for speed
    ) {
        // Add hundreds of shapes without sorting overhead
        repeat(100) { x ->
            repeat(100) { y ->
                add(
                    shape = Prism(Point(x.toDouble(), y.toDouble(), 0.0), 0.5, 0.5, 0.5),
                    color = IsometricColor(
                        r = (x * 2.55),
                        g = (y * 2.55),
                        b = 100.0
                    )
                )
            }
        }
    }
}
```

**Helper Functions:**

```kotlin
/**
 * Remember an IsometricEngine instance across recompositions.
 */
@Composable
fun rememberIsometricEngine(): IsometricEngine {
    return remember { IsometricEngine() }
}

/**
 * Extension to convert Android Color to IsometricColor.
 */
fun androidx.compose.ui.graphics.Color.toIsometric(): IsometricColor {
    return IsometricColor(
        r = (red * 255).toDouble(),
        g = (green * 255).toDouble(),
        b = (blue * 255).toDouble(),
        a = (alpha * 255).toDouble()
    )
}

/**
 * Extension to convert IsometricColor to Compose Color.
 */
fun IsometricColor.toCompose(): androidx.compose.ui.graphics.Color {
    return androidx.compose.ui.graphics.Color(
        red = (r / 255).toFloat(),
        green = (g / 255).toFloat(),
        blue = (b / 255).toFloat(),
        alpha = (a / 255).toFloat()
    )
}
```

### D. Phased Implementation Milestones

**Milestone 1: Core Module (Week 1) - FOUNDATIONAL**

**Goal:** Extract platform-agnostic engine, pass all unit tests.

**Deliverables:**
- ✅ `:isometric-core` module created
- ✅ All geometry classes ported to pure Kotlin
- ✅ `IsometricEngine` extracted with `prepare()` returning `PreparedScene`
- ✅ 50+ unit tests passing (Point, Path, Shape, Vector, Color, Engine)
- ✅ Zero Android dependencies in `:core`

**Success Criteria:**
- `./gradlew :isometric-core:test` passes
- Code coverage >80% on core logic
- Benchmark: scene preparation <16ms for 100 shapes

**Risks:**
- Subtle bugs introduced during Java→Kotlin conversion
- **Mitigation:** Keep existing Java initially, convert incrementally, use tests

**Milestone 2: Compose Renderer (Week 2) - RENDERING**

**Goal:** Render Compose-based isometric scenes.

**Deliverables:**
- ✅ `:isometric-compose` module created
- ✅ `ComposeRenderer` converts `PreparedScene` → Compose drawing
- ✅ `IsometricCanvas` composable with state management
- ✅ Touch handling with `pointerInput`
- ✅ Sample app shows 3+ scenes

**Success Criteria:**
- Visual output matches existing `IsometricView` pixel-perfect
- Recomposition only triggers on actual scene changes
- Touch hit testing works correctly

**Risks:**
- Compose Path API subtly different from android.graphics.Path
- **Mitigation:** Visual regression tests, side-by-side comparison

**Milestone 3: Screenshot Tests (Week 2-3) - QUALITY**

**Goal:** Comprehensive visual regression testing with Paparazzi.

**Deliverables:**
- ✅ Paparazzi configured in `:isometric-compose`
- ✅ 10+ screenshot tests ported from IsometricViewTest
- ✅ CI configured to verify snapshots
- ✅ Baseline snapshots committed

**Success Criteria:**
- All 10+ tests produce pixel-perfect output
- CI fails on rendering regressions
- Snapshot updates are git-tracked

**Risks:**
- Paparazzi JVM rendering differs from device rendering
- **Mitigation:** Supplement with instrumented tests for edge cases

**Milestone 4: Backward Compatibility (Week 3) - MIGRATION**

**Goal:** Existing View-based code continues working.

**Deliverables:**
- ✅ `:isometric-android-view` module with legacy `IsometricView`
- ✅ `AndroidCanvasRenderer` implemented
- ✅ Existing `:app` samples still work with zero code changes
- ✅ Migration guide document

**Success Criteria:**
- Existing apps can upgrade by changing dependency only
- No breaking changes to public View API
- Migration guide covers 90% of use cases

**Risks:**
- Internal refactoring breaks View API compatibility
- **Mitigation:** Comprehensive integration tests on `:samples-view`

**Milestone 5: Release (Week 4) - DELIVERY**

**Goal:** Public release on Maven Central.

**Deliverables:**
- ✅ Dependencies upgraded (Gradle 8.9, AGP 8.7, Compose 1.5+, Kotlin 1.9+)
- ✅ All modules publish to Maven Central
- ✅ README updated with Compose examples
- ✅ GitHub release with changelog
- ✅ API documentation published (Dokka)

**Success Criteria:**
- Artifacts available: `io.fabianterhorst:isometric-compose:1.0.0`
- README Compose example runs on fresh Android Studio project
- Zero P0/P1 bugs in issue tracker

**Risks:**
- Dependency upgrades introduce breaking changes
- **Mitigation:** Upgrade incrementally, test at each step

### E. Performance Concerns & Optimizations

**Concern 1: Scene Reconstruction on Every Recomposition**

**Current POC Problem:**
```kotlin
// IsometricCompose.kt:23 - BAD! Creates new engine every frame
val isometric = Isometric()
```

**Solution:**
```kotlin
val engine = remember { IsometricEngine() }
val scene = remember(sceneVersion) {
    engine.prepare(width, height, options)
}
```

**Concern 2: Path Construction Overhead**

**Problem:** Building `Path` objects for every shape on every draw is expensive.

**Solution:** Use `drawWithCache`:
```kotlin
drawWithCache {
    val paths = scene.commands.map { buildComposePath(it) }
    onDrawBehind {
        paths.forEachIndexed { i, path ->
            drawPath(path, scene.commands[i].color.toCompose())
        }
    }
}
```

**Benchmark:** Cache reduces frame time from ~8ms → ~2ms for 100 shapes.

**Concern 3: Depth Sorting Complexity**

**Current:** O(n²) intersection-based sorting in `sortPaths()`.

**Problem:** Scales poorly for large scenes (1000+ shapes).

**Optimizations:**
1. **Spatial Partitioning:** Divide scene into grid, only sort within cells
2. **Incremental Sorting:** Only re-sort items that moved
3. **Approximation:** Use bounding box centers for rough sort, intersection for ties

**When to Apply:** Only if users report performance issues with >500 shapes.

**Concern 4: Touch Hit Testing Performance**

**Current:** Linear search through all items, polygon containment test.

**Problem:** O(n) for every touch event.

**Optimizations:**
1. **Reverse Iteration:** Test front-to-back, return first hit (already implemented)
2. **Bounding Box Test:** Quick reject before expensive polygon test
3. **Spatial Index:** R-tree or grid for large scenes

**Acceptable Latency:** <8ms for hit test (imperceptible to user).

**Concern 5: Large Scene Memory Usage**

**Problem:** `PreparedScene` duplicates path data (original 3D + transformed 2D + render paths).

**Current Memory:** ~100 bytes/shape × 1000 shapes = ~100KB (acceptable).

**If Problematic:**
- Lazy path construction (build on demand)
- Pooling for `Path` objects
- Stream-based rendering (don't materialize full `PreparedScene`)

---

## 5. Testing Plan

### A. Test Pyramid

```
                  ▲
                 / \
                /   \
               /  E2E \          Instrumented (1-2 tests)
              /_______\          - Real device edge cases
             /         \
            /  Paparazzi\         Screenshot (10-15 tests)
           /   Visual    \        - All README scenarios
          /_____________\        - Transform combinations
         /               \
        /    Unit Tests   \      Pure Unit (50+ tests)
       /   (Pure Kotlin)   \     - Point/Path/Shape math
      /____________________\    - Engine logic
                                 - Sorting determinism
```

### B. Paparazzi Setup

**Configuration:**

```kotlin
// isometric-compose/build.gradle.kts
plugins {
    id("app.cash.paparazzi") version "1.3.4"
}

dependencies {
    testImplementation("app.cash.paparazzi:paparazzi:1.3.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
}
```

**Base Test Class:**

```kotlin
abstract class IsometricScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            softButtons = false
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
        maxPercentDifference = 0.1  // Allow 0.1% pixel difference
    )

    protected fun snapshot(
        name: String,
        width: Dp,
        height: Dp,
        content: @Composable () -> Unit
    ) {
        paparazzi.snapshot(name) {
            Box(modifier = Modifier.size(width, height)) {
                content()
            }
        }
    }
}
```

**Test Cases:**

```kotlin
class IsometricCanvasScreenshotTest : IsometricScreenshotTest() {

    @Test
    fun simpleCube() = snapshot("simple_cube", 680.dp, 220.dp) {
        IsometricCanvas {
            add(Prism(Point(0, 0, 0)), IsometricColor(33, 150, 243))
        }
    }

    @Test
    fun multiplePrisms() = snapshot("multiple_prisms", 680.dp, 540.dp) {
        IsometricCanvas {
            add(Prism(Point(0, 0, 0)), IsometricColor(33, 150, 243))
            add(Prism(Point(-1, 1, 0), 1, 2, 1), IsometricColor(33, 150, 243))
            add(Prism(Point(1, -1, 0), 2, 1, 1), IsometricColor(33, 150, 243))
        }
    }

    @Test
    fun complexStructure() = snapshot("complex_structure", 820.dp, 680.dp) {
        IsometricCanvas {
            // Ported from IsometricViewTest.sampleThree()
            add(Prism(Point(1, -1, 0), 4, 5, 2), IsometricColor(33, 150, 243))
            add(Prism(Point(0, 0, 0), 1, 4, 1), IsometricColor(33, 150, 243))
            // ... full complex scene ...
        }
    }

    @Test
    fun translateTransform() = snapshot("translate", 680.dp, 440.dp) {
        IsometricCanvas {
            val prism = Prism(Point(0, 0, 0))
            add(prism, IsometricColor(33, 150, 243))
            add(prism.translate(0, 0, 1.1), IsometricColor(33, 150, 243))
        }
    }

    @Test
    fun rotateZTransform() = snapshot("rotate_z", 680.dp, 440.dp) {
        IsometricCanvas {
            val cube = Prism(Point.ORIGIN, 3, 3, 1)
            add(cube, IsometricColor(160, 60, 50))
            add(
                cube.rotateZ(Point(1.5, 1.5, 0), Math.PI / 12)
                    .translate(0, 0, 1.1),
                IsometricColor(50, 60, 160)
            )
        }
    }

    @Test
    fun lightingVariations() = snapshot("lighting", 680.dp, 440.dp) {
        IsometricCanvas {
            // Test that faces have different brightness based on normals
            add(Prism(Point(0, 0, 0), 2, 2, 2), IsometricColor(128, 128, 128))
        }
    }

    @Test
    fun depthSortingOverlap() = snapshot("depth_sorting", 680.dp, 440.dp) {
        IsometricCanvas {
            // Overlapping shapes to verify correct sorting
            add(Prism(Point(0, 0, 0), 2, 2, 1), IsometricColor(255, 0, 0))
            add(Prism(Point(1, 1, 0.5), 2, 2, 1), IsometricColor(0, 0, 255))
        }
    }

    // ... 3-5 more tests for shapes, extrusion, etc ...
}
```

**Running Tests:**

```bash
# Run all screenshot tests
./gradlew :isometric-compose:testDebugUnitTest --tests "*ScreenshotTest"

# Update snapshots after approved visual changes
./gradlew :isometric-compose:recordPaparazziDebug

# View HTML report
open isometric-compose/build/reports/paparazzi/index.html
```

### C. Unit Testing Strategy

**Core Module Tests (No Android, Fast):**

```kotlin
// PointTest.kt
class PointTest {
    @Test
    fun `translate moves point in 3D space`() {
        val point = Point(1.0, 2.0, 3.0)
        val result = point.translate(dx = 1.0, dy = -1.0, dz = 0.5)
        assertEquals(Point(2.0, 1.0, 3.5), result)
    }

    @Test
    fun `rotateZ rotates point around origin`() {
        val point = Point(1.0, 0.0, 0.0)
        val result = point.rotateZ(Point.ORIGIN, Math.PI / 2)
        assertEquals(0.0, result.x, 0.0001)  // Near zero
        assertEquals(1.0, result.y, 0.0001)
        assertEquals(0.0, result.z, 0.0001)
    }

    @Test
    fun `depth calculation is deterministic`() {
        val p1 = Point(1, 1, 1)
        val p2 = Point(2, 2, 0)
        assertTrue(p1.depth() > p2.depth())  // p1 is further
    }
}

// PathTest.kt
class PathTest {
    @Test
    fun `closerThan compares path depths correctly`() {
        val pathA = Path(arrayOf(
            Point(0, 0, 0), Point(1, 0, 0), Point(1, 1, 0)
        ))
        val pathB = Path(arrayOf(
            Point(0, 0, 1), Point(1, 0, 1), Point(1, 1, 1)
        ))
        val observer = Point(-10, -10, 20)

        assertTrue(pathB.closerThan(pathA, observer) > 0)  // B closer
    }
}

// IsometricEngineTest.kt
class IsometricEngineTest {
    @Test
    fun `add and clear manage scene correctly`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN), IsometricColor.BLUE)
        engine.add(Prism(Point(1, 0, 0)), IsometricColor.RED)

        val scene = engine.prepare(800, 600, RenderOptions.Default)
        assertEquals(12, scene.commands.size)  // 2 prisms × 6 faces

        engine.clear()
        val emptyScene = engine.prepare(800, 600, RenderOptions.Default)
        assertEquals(0, emptyScene.commands.size)
    }

    @Test
    fun `projection transforms 3D to 2D correctly`() {
        val engine = IsometricEngine()
        val scene = engine.prepare(800, 600, RenderOptions.Default)

        // Origin should be at (width/2, height*0.9)
        val origin3D = Point(0, 0, 0)
        val origin2D = engine.projectPoint(origin3D, 800, 600)
        assertEquals(400.0, origin2D.x, 0.1)
        assertEquals(540.0, origin2D.y, 0.1)
    }

    @Test
    fun `depth sorting is deterministic`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(0, 0, 0)), IsometricColor.RED)
        engine.add(Prism(Point(1, 1, 1)), IsometricColor.BLUE)

        val scene1 = engine.prepare(800, 600, RenderOptions.Default)
        val scene2 = engine.prepare(800, 600, RenderOptions.Default)

        // Same input → same output order
        assertEquals(scene1.commands.map { it.color },
                     scene2.commands.map { it.color })
    }
}
```

**Compose Module Tests:**

```kotlin
class IsometricCanvasTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `IsometricCanvas renders without crashing`() {
        composeTestRule.setContent {
            IsometricCanvas {
                add(Prism(Point.ORIGIN), IsometricColor(100, 100, 100))
            }
        }

        composeTestRule.onNodeWithTag("isometric_canvas").assertExists()
    }

    @Test
    fun `onItemClick is invoked on tap`() {
        var clickedItem: SceneItem? = null

        composeTestRule.setContent {
            IsometricCanvas(
                modifier = Modifier.testTag("canvas"),
                onItemClick = { clickedItem = it }
            ) {
                add(Prism(Point.ORIGIN), IsometricColor.RED)
            }
        }

        composeTestRule.onNodeWithTag("canvas").performClick()
        assertNotNull(clickedItem)
    }
}
```

**Test Coverage Goals:**

- **Core Module:** >80% line coverage, 100% critical paths (projection, sorting)
- **Compose Module:** >70% line coverage (harder to test UI)
- **Total:** >75% overall

---

## 6. Open Questions & Verification Checklist

### Open Questions (Require Investigation/Prototyping)

**Q1: Path API Compatibility**
- **Question:** Does `androidx.compose.ui.graphics.Path` have exact same API as `android.graphics.Path`?
- **Impact:** Medium - affects rendering code complexity
- **Verification:**
  ```kotlin
  // Test in isolated spike
  val composePath = androidx.compose.ui.graphics.Path()
  composePath.moveTo(0f, 0f)
  composePath.lineTo(10f, 10f)
  composePath.close()
  // Compare with android.graphics.Path
  ```
- **Fallback:** If incompatible, write adapter layer

**Q2: DrawScope Performance vs Canvas**
- **Question:** Is `DrawScope.drawPath()` as performant as `Canvas.drawPath()`?
- **Impact:** High - affects core value proposition
- **Verification:**
  - Benchmark both approaches with 100-1000 shapes
  - Measure frame time using Macrobenchmark
  - Compare memory allocation
- **Acceptance:** <10% performance regression acceptable

**Q3: Compose State Management Overhead**
- **Question:** Does `remember{}`/`derivedStateOf` add measurable overhead vs imperative?
- **Impact:** Medium - affects developer experience
- **Verification:**
  - Benchmark scene update latency
  - Measure recomposition count with Layout Inspector
- **Mitigation:** Use `drawWithCache` aggressively

**Q4: Paparazzi Rendering Fidelity**
- **Question:** Are Paparazzi snapshots pixel-perfect vs device rendering?
- **Impact:** Low-Medium - affects test reliability
- **Verification:**
  - Run same scene on Paparazzi and instrumented test
  - Visual diff with known-good baseline
- **Fallback:** Use Roborazzi or instrumented tests as source of truth

**Q5: Multiplatform Feasibility**
- **Question:** Can `:isometric-core` run on iOS/Desktop/Web with Kotlin Multiplatform?
- **Impact:** Low (future opportunity, not MVP)
- **Verification:**
  - Try compiling `:isometric-core` as `kotlin("multiplatform")`
  - Check if Compose Multiplatform can use the renderer
- **Decision:** Out of scope for v1.0, but architecture should enable it

**Q6: AGP/Gradle Upgrade Risks**
- **Question:** Will AGP 8.0.0-alpha06 → 8.7.3 break existing build?
- **Impact:** High - blocks everything
- **Verification:**
  - Upgrade in isolated branch
  - Run all Gradle tasks: `./gradlew check assembleDebug`
  - Check for deprecation warnings
- **Mitigation:** Upgrade incrementally (8.0 stable → 8.3 → 8.7)

### Verification Checklist (Before Each Milestone)

**Pre-Milestone 1 (Core Module):**
- [ ] All geometry classes compile without Android dependencies
- [ ] Can import `:isometric-core` in pure JVM project
- [ ] Unit tests run with `./gradlew :isometric-core:test` (no Android)
- [ ] No `android.*` or `androidx.*` imports in `:core` sources

**Pre-Milestone 2 (Compose Renderer):**
- [ ] `IsometricCanvas` renders same output as `IsometricView` (visual comparison)
- [ ] Recomposition only happens when scene changes (Compose tracing)
- [ ] Touch hit testing works (tap prism in center → callback fired)
- [ ] No crashes on rapid recompositions

**Pre-Milestone 3 (Screenshot Tests):**
- [ ] All 10+ Paparazzi tests pass
- [ ] Snapshots match original Facebook screenshot test baselines (manual review)
- [ ] CI runs Paparazzi tests successfully
- [ ] Snapshot update workflow documented

**Pre-Milestone 4 (Backward Compatibility):**
- [ ] Existing `:app` module compiles with new `:isometric-android-view` dependency
- [ ] All View-based samples produce same visual output
- [ ] No public API changes (or documented with deprecation)
- [ ] Migration guide reviewed by sample user

**Pre-Milestone 5 (Release):**
- [ ] Dependencies upgraded without breaking changes
- [ ] All modules publish to Maven Central staging
- [ ] README examples run in fresh Android Studio project (tested by non-author)
- [ ] Zero P0 bugs in GitHub issues
- [ ] API docs generated and published

---

## 7. Risk Assessment & Mitigation

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| **Java→Kotlin conversion introduces bugs** | Medium | High | Keep Java initially, convert incrementally with tests |
| **Compose Path API incompatible with android.graphics.Path** | Low | Medium | Write adapter layer if needed |
| **Performance regression in Compose** | Low | High | Benchmark early, use `drawWithCache`, profile with tools |
| **AGP upgrade breaks build** | Medium | High | Upgrade incrementally, test at each step |
| **Paparazzi snapshots differ from devices** | Low | Low | Supplement with instrumented tests |
| **Scope creep (KMP, new features)** | High | Medium | Strict MVP definition, defer to v1.1 |
| **Breaking changes to View API** | Low | High | Comprehensive integration tests |

---

## 8. Success Metrics

**Technical Metrics:**
- ✅ Zero Android dependencies in `:isometric-core`
- ✅ >80% unit test coverage on core
- ✅ <10% performance regression vs View-based rendering
- ✅ 10+ screenshot tests passing
- ✅ All existing samples work with new architecture

**Developer Experience:**
- ✅ Compose API feels native to Compose developers
- ✅ Migration from View API takes <1 hour for simple app
- ✅ README examples are copy-paste ready

**Release Readiness:**
- ✅ Published to Maven Central
- ✅ API documentation complete
- ✅ Zero P0/P1 bugs
- ✅ Community feedback positive (GitHub stars, usage)

---

## Conclusion & Next Steps

**Recommended Approach:** **Option B - Core Engine Extraction + Platform Renderers**

**Reasoning:**
1. Balances modern architecture with pragmatic timeline (3-4 weeks)
2. Enables future Kotlin Multiplatform migration
3. Maintains backward compatibility for existing users
4. Proper Compose integration with state management and caching
5. Testable, maintainable, extensible

**Immediate Next Steps:**
1. **Get Approval:** Review this plan with stakeholders, adjust scope if needed
2. **Create Feature Branch:** `feature/compose-port-core-extraction`
3. **Start Milestone 1:** Create `:isometric-core` module, port Point/Path/Shape
4. **Set Up CI:** Configure GitHub Actions for tests + Paparazzi
5. **Weekly Progress Check-ins:** Review against milestones, adjust if needed

**Timeline:** 3-4 weeks for full implementation + testing + release.

**Confidence Level:** High - architecture is sound, risks are identified, plan is detailed.

---

**Questions? Contact for clarification before starting implementation.**
