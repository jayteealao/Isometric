# Migration Guide: Android View → Jetpack Compose

> **This document has been superseded by the [documentation site](https://jayteealao.github.io/Isometric/migration/view-to-compose/).** This file is retained for reference only.

This guide helps you migrate from the View-based `IsometricView` API to the Compose `IsometricScene` API.

> **Note:** An earlier Compose API called `IsometricCanvas` was removed and superseded by `IsometricScene`. All examples below use `IsometricScene`. If you are migrating from `IsometricCanvas`, see the [Runtime API documentation](RUNTIME_API.md) for the current API.

## Module Migration

### Old (View-based)

```gradle
dependencies {
    implementation 'io.fabianterhorst:Isometric:0.0.9'
}
```

### New (Compose)

```gradle
dependencies {
    // For Compose apps
    implementation 'io.fabianterhorst:isometric-compose:1.0.0'

    // OR for View apps (backward compatible)
    implementation 'io.fabianterhorst:isometric-android-view:1.0.0'
}
```

## Basic Usage Migration

### Before (View API)

```java
// In your layout XML
<io.fabianterhorst.isometric.IsometricView
    android:id="@+id/isometricView"
    android:layout_width="match_parent"
    android:layout_height="300dp" />

// In your Activity/Fragment
IsometricView isometricView = findViewById(R.id.isometricView);
isometricView.add(
    new Prism(new Point(0, 0, 0)),
    new Color(33, 150, 243)
);
```

### After (Compose API)

```kotlin
@Composable
fun MyScreen() {
    IsometricScene(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        Shape(
            geometry = Prism(position = Point(0.0, 0.0, 0.0)),
            color = IsoColor(33, 150, 243)
        )
    }
}
```

## Key Differences

### 1. Imports

| View API | Compose API |
|----------|-------------|
| `import io.fabianterhorst.isometric.IsometricView` | `import io.fabianterhorst.isometric.compose.runtime.IsometricScene` |
| `import io.fabianterhorst.isometric.Color` | `import io.fabianterhorst.isometric.IsoColor` |
| `import io.fabianterhorst.isometric.shapes.*` | `import io.fabianterhorst.isometric.shapes.*` (unchanged) |

### 2. Color Class

**Important:** `Color` has been renamed to `IsoColor` to avoid conflicts with Compose's `Color` class.

```kotlin
// Old
new Color(33, 150, 243)

// New
IsoColor(33.0, 150.0, 243.0)
```

### 3. Point/Shape Construction

**View API used Java constructors:**
```java
new Point(0, 0, 0)
new Prism(new Point(0, 0, 0), 1, 1, 1)
```

**Compose API uses Kotlin:**
```kotlin
Point(0.0, 0.0, 0.0)
Prism(Point(0.0, 0.0, 0.0), 1.0, 1.0, 1.0)
```

### 4. State Management

**View API (imperative):**
```java
isometricView.add(shape, color);
isometricView.clear();
```

**Compose API (declarative):**
```kotlin
IsometricScene {
    Shape(geometry = shape, color = color)
}
```

### 5. Click Handling

**View API:**
```java
isometricView.setClickListener(new IsometricView.OnItemClickListener() {
    @Override
    public void onClick(@NonNull Isometric.Item item) {
        // Handle click
    }
});
```

**Compose API:**
```kotlin
IsometricScene(
    config = SceneConfig(
        gestures = GestureConfig(
            onTap = { event ->
                println("Tapped node: ${event.node?.nodeId}")
            }
        )
    )
) {
    Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0)), color = IsoColor(33, 150, 243))
}
```

### 6. Configuration

**View API:**
```java
isometricView.setSort(true);
isometricView.setCull(true);
isometricView.setBoundsCheck(true);
isometricView.setReverseSortForLookup(true);
isometricView.setTouchRadius(8.0);
```

**Compose API:**
```kotlin
IsometricScene(
    config = SceneConfig(
        renderOptions = RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = true,
            enableBoundsChecking = true
        )
    )
) {
    // Scene content
}
```

## Common Migration Patterns

### Pattern 1: Static Scene

**Before:**
```java
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        IsometricView view = findViewById(R.id.isometricView);
        view.add(new Prism(Point.ORIGIN), new Color(33, 150, 243));
    }
}
```

**After:**
```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyStaticScene()
        }
    }
}

@Composable
fun MyStaticScene() {
    IsometricScene {
        Shape(
            geometry = Prism(position = Point.ORIGIN),
            color = IsoColor(33, 150, 243)
        )
    }
}
```

### Pattern 2: Dynamic Scene Updates

**Before:**
```java
private IsometricView isometricView;

private void updateScene(int count) {
    isometricView.clear();
    for (int i = 0; i < count; i++) {
        isometricView.add(
            new Prism(new Point(i, 0, 0)),
            new Color(33, 150, 243)
        );
    }
}
```

**After:**
```kotlin
@Composable
fun DynamicScene() {
    var count by remember { mutableIntStateOf(1) }

    IsometricScene {
        ForEach((0 until count).toList()) { i ->
            Shape(
                geometry = Prism(position = Point(i.toDouble(), 0.0, 0.0)),
                color = IsoColor(33, 150, 243)
            )
        }
    }
}
```

### Pattern 3: Animation

**Before:**
```java
private double rotation = 0;

private void startAnimation() {
    new Handler().postDelayed(new Runnable() {
        @Override
        public void run() {
            rotation += 0.02;
            updateScene();
            new Handler().postDelayed(this, 16);
        }
    }, 16);
}

private void updateScene() {
    isometricView.clear();
    Shape cube = new Prism(Point.ORIGIN, 3, 3, 1);
    isometricView.add(
        cube.rotateZ(new Point(1.5, 1.5, 0), rotation),
        new Color(50, 60, 160)
    );
}
```

**After:**
```kotlin
@Composable
fun AnimatedScene() {
    var rotation by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                rotation += 0.02
            }
        }
    }

    IsometricScene {
        Group(
            rotation = rotation,
            rotationOrigin = Point(1.5, 1.5, 0.0)
        ) {
            Shape(
                geometry = Prism(position = Point.ORIGIN, width = 3.0, depth = 3.0, height = 1.0),
                color = IsoColor(50, 60, 160)
            )
        }
    }
}
```

## Type Conversion Helpers

### Old Color → New IsoColor

```kotlin
fun Color.toIsoColor(): IsoColor {
    return IsoColor(this.r, this.g, this.b, this.a)
}
```

### Compose Color ↔ IsoColor

```kotlin
import io.fabianterhorst.isometric.compose.ComposeRenderer.toIsoColor
import io.fabianterhorst.isometric.compose.ComposeRenderer.toComposeColor

val composeColor = androidx.compose.ui.graphics.Color.Blue
val isoColor = composeColor.toIsoColor()
```

## Backward Compatibility

If you're not ready to migrate to Compose, you can use the new `:isometric-android-view` module which provides the same View-based API but uses the refactored engine:

```gradle
dependencies {
    implementation 'io.fabianterhorst:isometric-android-view:1.0.0'
}
```

The View API remains unchanged:

```java
import io.fabianterhorst.isometric.view.IsometricView;

IsometricView view = findViewById(R.id.isometricView);
view.add(new Prism(Point.ORIGIN), new IsoColor(33, 150, 243));
```

**Note:** You'll need to use `IsoColor` instead of `Color`, but everything else remains the same.

## Troubleshooting

### Issue: "Unresolved reference: Color"

**Solution:** Use `IsoColor` instead of `Color`:

```kotlin
// Wrong
add(Prism(Point.ORIGIN), Color(33, 150, 243))

// Correct
add(Prism(Point.ORIGIN), IsoColor(33.0, 150.0, 243.0))
```

### Issue: "Type mismatch: inferred type is Int but Double was expected"

**Solution:** Use Double literals (add `.0`):

```kotlin
// Wrong
Point(0, 0, 0)

// Correct
Point(0.0, 0.0, 0.0)
```

### Issue: Scene not updating when state changes

**Solution:** Use `LaunchedEffect` to rebuild the scene when dependencies change:

```kotlin
val sceneState = rememberIsometricSceneState()
var myState by remember { mutableStateOf(someValue) }

LaunchedEffect(myState) {
    sceneState.clear()
    // Rebuild scene based on myState
}
```

### Issue: Click detection not working

**Solution:** Ensure you're using `IsometricScene` with `GestureConfig`:

```kotlin
IsometricScene(
    config = SceneConfig(
        gestures = GestureConfig(
            onTap = { event ->
                println("Tapped: ${event.node?.nodeId}")
            }
        )
    )
) {
    // Scene content
}
```

## Performance Tips

1. **Use RenderOptions.NoDepthSorting** for large scenes where draw order does not matter:
   ```kotlin
   IsometricScene(
       config = SceneConfig(renderOptions = RenderOptions.NoDepthSorting)
   ) {
       // Scene content
   }
   ```

2. **Leverage per-node dirty tracking:**
   ```kotlin
   // Good: Only the animated group recomposes
   IsometricScene {
       Shape(geometry = staticShape, color = color1)  // Never recomposes
       Group(rotation = angle) {                       // Only this recomposes
           Shape(geometry = animatedShape, color = color2)
       }
   }
   ```

3. **Use `remember` for expensive computations:**
   ```kotlin
   val complexShape = remember {
       Prism(Point.ORIGIN, 10.0, 10.0, 10.0)
           .rotateZ(Point(5.0, 5.0, 0.0), PI / 4)
   }
   ```

## Questions?

- See the [Runtime API documentation](RUNTIME_API.md) for the current API reference
- See the [Performance Optimizations guide](PERFORMANCE_OPTIMIZATIONS.md) for tuning
- Open an issue on GitHub

## Summary

**Key Changes:**
- ✅ `Color` → `IsoColor`
- ✅ `IsometricView` (XML) → `IsometricScene` (Composable)
- ✅ `setClickListener()` → `GestureConfig(onTap = ...)`
- ✅ Imperative API → Declarative API with scene graph
- ✅ Int coordinates → Double coordinates
- ✅ Java constructors → Kotlin constructors

**Benefits:**
- ✅ Proper Compose state management
- ✅ Automatic recomposition
- ✅ Better performance with caching
- ✅ Modern Kotlin API
- ✅ Touch handling built-in
- ✅ Future-proof architecture
