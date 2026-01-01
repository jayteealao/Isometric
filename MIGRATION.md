# Migration Guide: Android View → Jetpack Compose

This guide helps you migrate from the View-based `IsometricView` API to the new Compose `IsometricCanvas` API.

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
    val sceneState = rememberIsometricSceneState()

    IsometricCanvas(
        state = sceneState,
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        add(
            Prism(Point(0.0, 0.0, 0.0)),
            IsoColor(33.0, 150.0, 243.0)
        )
    }
}
```

## Key Differences

### 1. Imports

| View API | Compose API |
|----------|-------------|
| `import io.fabianterhorst.isometric.IsometricView` | `import io.fabianterhorst.isometric.compose.IsometricCanvas` |
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
val sceneState = rememberIsometricSceneState()

// In composable content block:
IsometricCanvas(state = sceneState) {
    add(shape, color)
}

// Or imperatively:
sceneState.add(shape, color)
sceneState.clear()
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
IsometricCanvas(
    state = sceneState,
    onItemClick = { renderCommand ->
        // Handle click
    }
) {
    // Scene content
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
IsometricCanvas(
    state = sceneState,
    renderOptions = RenderOptions(
        enableDepthSorting = true,
        enableBackfaceCulling = true,
        enableBoundsChecking = true
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
    val sceneState = rememberIsometricSceneState()

    IsometricCanvas(state = sceneState) {
        add(Prism(Point.ORIGIN), IsoColor(33.0, 150.0, 243.0))
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
    val sceneState = rememberIsometricSceneState()
    var count by remember { mutableIntStateOf(1) }

    LaunchedEffect(count) {
        sceneState.clear()
        repeat(count) { i ->
            sceneState.add(
                Prism(Point(i.toDouble(), 0.0, 0.0)),
                IsoColor(33.0, 150.0, 243.0)
            )
        }
    }

    IsometricCanvas(state = sceneState)
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
    val sceneState = rememberIsometricSceneState()
    var rotation by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            rotation += 0.02
        }
    }

    LaunchedEffect(rotation) {
        sceneState.clear()
        val cube = Prism(Point.ORIGIN, 3.0, 3.0, 1.0)
        sceneState.add(
            cube.rotateZ(Point(1.5, 1.5, 0.0), rotation),
            IsoColor(50.0, 60.0, 160.0)
        )
    }

    IsometricCanvas(state = sceneState)
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

**Solution:** Ensure you're using `IsometricCanvas` with `onItemClick` parameter:

```kotlin
IsometricCanvas(
    state = sceneState,
    onItemClick = { renderCommand ->
        // Handle click
    }
) {
    // Scene content
}
```

## Performance Tips

1. **Use RenderOptions.Performance** for large scenes:
   ```kotlin
   IsometricCanvas(
       renderOptions = RenderOptions.Performance
   )
   ```

2. **Avoid recreating scenes unnecessarily:**
   ```kotlin
   // Good: Only recreates when count changes
   LaunchedEffect(count) {
       sceneState.clear()
       // Build scene
   }

   // Bad: Recreates every recomposition
   sceneState.clear()
   // Build scene
   ```

3. **Use `remember` for expensive computations:**
   ```kotlin
   val complexShape = remember {
       Prism(Point.ORIGIN, 10.0, 10.0, 10.0)
           .rotateZ(Point(5.0, 5.0, 0.0), PI / 4)
   }
   ```

## Questions?

- Check the investigation report: `COMPOSE_PORT_INVESTIGATION.md`
- Review the README: `README_COMPOSE.md`
- Open an issue on GitHub

## Summary

**Key Changes:**
- ✅ `Color` → `IsoColor`
- ✅ `IsometricView` (XML) → `IsometricCanvas` (Composable)
- ✅ `setClickListener()` → `onItemClick` parameter
- ✅ Imperative API → Declarative API (but imperative still available via `sceneState`)
- ✅ Int coordinates → Double coordinates
- ✅ Java constructors → Kotlin constructors

**Benefits:**
- ✅ Proper Compose state management
- ✅ Automatic recomposition
- ✅ Better performance with caching
- ✅ Modern Kotlin API
- ✅ Touch handling built-in
- ✅ Future-proof architecture
