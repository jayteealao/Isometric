# WS4: Safety Net & Lifecycle — Detailed Implementation Plan

> **Workstream**: 4 of 8
> **Phase**: 3 (after WS3)
> **Scope**: Error handling, resource cleanup, ID safety, encapsulation, experimental annotations, and legacy deletion
> **Findings**: F8, F11, F13/F44/F12, F23, F24, F25, F42, F43, F74
> **Depends on**: WS3 (use post-rename method names when migrating samples; `prepare()` is now `projectScene()`, etc.)
> **Review ref**: `docs/reviews/api-first-run-usability-review.md` §3.4, §3.7, §3.8

---

## Execution Order

The 9 findings decompose into 7 ordered steps. Each step is self-contained and produces a compilable codebase.

1. **Step 1**: Delete legacy files (F8, F13/F44/F12)
2. **Step 2**: Encapsulation fix (F74)
3. **Step 3**: Atomic ID generation (F43)
4. **Step 4**: Error handling in render loop (F23)
5. **Step 5**: Lifecycle / resource cleanup (F25)
6. **Step 6**: Experimental annotations (F24, F11)
7. **Step 7**: Platform safety (F42)

Steps 1–3 must be sequential (Step 2 touches `IsometricNode` children visibility which Step 3 also modifies; Step 1 removes files that could otherwise create merge noise). Steps 4–5 are sequential (Step 5 builds on the error-handling boundaries from Step 4). Steps 6–7 are independent of each other and can be done after Step 3.

---

## Step 1: Delete Legacy Files (F8, F13/F44/F12)

### Rationale

Legacy wrappers that are already superseded create confusion, inflate API surface, and risk accidental usage. `Color.kt` is already `@Deprecated` with a direct replacement (`IsoColor`). `IsometricCanvas.kt` is a broken legacy API — its `content` lambda is NOT `@Composable`, it references `rememberIsometricSceneState()` which does not exist, and its `IsometricScope` collides with the runtime-level `IsometricScope`. Both files should be deleted outright — no deprecation cycle per user preference.

### Best Practice

Delete immediately. No `@Deprecated` annotations, no aliases, no migration paths. Consumers who depend on these types get a compile error pointing them to the replacement.

### Files and Changes

#### 1a. `Color.kt` — DELETE (F8)

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/Color.kt` (112 lines)

**Current state**: Already `@Deprecated` with `replaceWith = ReplaceWith("IsoColor(r, g, b, a)")`. Has `@JvmOverloads` — the only file in the codebase with it. Contains duplicated HSL logic that already exists in `IsoColor.kt`.

**Action**: Delete the file entirely.

**Call site impact**: Search for `import io.fabianterhorst.isometric.Color` across the codebase. Any remaining usages must switch to `IsoColor`. Expected locations:
- Possibly `lib/src/main/java/io/fabianterhorst/isometric/IsometricCompose.kt` (legacy module)
- Test files referencing the old `Color` type

**Also delete**: `isometric-core/bin/main/io/fabianterhorst/isometric/Color.kt` (stale build output copy).

#### 1b. `IsometricCanvas.kt` — DELETE (F13/F44/F12)

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/IsometricCanvas.kt` (138 lines)

**Current state**:
- `content: IsometricScope.() -> Unit` — NOT `@Composable`. This means shapes added inside the lambda are evaluated eagerly during composition with no Compose lifecycle (no `remember`, no `LaunchedEffect`, no recomposition).
- References `rememberIsometricSceneState()` in KDoc `@param` (line 23) — this function does not exist. The actual state holder is `IsometricSceneState` constructed manually.
- `IsometricScope` interface (line 102) collides with `io.fabianterhorst.isometric.compose.runtime.IsometricScope` from the runtime API.
- `IsometricScopeImpl` (line 122) is a private wrapper that delegates to `IsometricSceneState` — no unique behavior.
- The entire composable bypasses the runtime node tree, rendering directly via `ComposeRenderer` with no dirty tracking, no hit-test spatial indexing, and no path caching.

**Action**: Delete the file entirely.

**Call site impact**: Search for `import io.fabianterhorst.isometric.compose.IsometricCanvas` and `IsometricCanvas(` across the codebase. Consumers must migrate to `IsometricScene` from the runtime API. Expected locations:
- `IsometricCanvasSnapshotTest.kt` — either delete or convert to use `IsometricScene`
- Sample activities that may use the legacy API

**Also delete**: Any snapshot test baselines that only test the legacy canvas path.

### Verification

After step 1, run full test suite. Key checks:
- No unresolved `Color` imports (the `io.fabianterhorst.isometric.Color` type no longer exists)
- No unresolved `IsometricCanvas` imports
- No `IsometricScope` ambiguity errors (only the runtime version remains)
- Snapshot tests updated or removed

---

## Step 2: Encapsulation Fix (F74)

### Rationale

Exposing `children: MutableList<IsometricNode>` as abstract+public allows any consumer to mutate the tree directly, bypassing dirty tracking and snapshot synchronization.

> **F75 moved to WS1b** — merged with F71 (`data class` removal) since both edit the same types (`RenderContext`, `RenderOptions`, etc.) and F75 is a natural side effect of F71.

### Best Practice

Use the narrowest possible visibility. Mutable internal state should be `internal` or `protected`, never `public`.

### Files and Changes

#### 2a. `IsometricNode.kt` — `children` visibility (F74)

**Current** (line 23):
```kotlin
abstract val children: MutableList<IsometricNode>
```

**After**:
```kotlin
@PublishedApi
internal abstract val children: MutableList<IsometricNode>
```

**Why `internal` not `protected`**: The `IsometricApplier` (separate file, same module) needs direct access to `children` for `insertAt()`, `remove()`, `move()`. External consumers should use `childrenSnapshot` (read-only `List<IsometricNode>`) for traversal.

**Why `@PublishedApi`**: If any `inline` functions in the module access `children`, `@PublishedApi` is required. If none do, the annotation can be omitted — verify during implementation.

**Impact on subclasses**: `GroupNode`, `ShapeNode`, `PathNode`, `BatchNode` all override `children` in the same module. Change their visibility to `internal` as well:

```kotlin
// GroupNode (line 109)
internal override val children = mutableListOf<IsometricNode>()

// ShapeNode (line 138)
internal override val children = mutableListOf<IsometricNode>()

// PathNode (line 181)
internal override val children = mutableListOf<IsometricNode>()

// BatchNode (line 229)
internal override val children = mutableListOf<IsometricNode>()
```

**WS6 impact**: `CustomNode` (F58 from WS6) will need `children` access. Since `CustomNode` will be in the same module, `internal` visibility is sufficient. If WS6 places `CustomNode` in a different module, escalate to `protected` with a read-only public accessor.

### Verification

After step 2:
- Confirm `children` is inaccessible from outside the module (create a scratch file in `app/` that tries `node.children` — should fail to compile)
- All existing tests pass with updated access patterns

---

## Step 3: Atomic ID Generation (F43)

### Rationale

`IsometricNode.nodeId` currently uses `System.identityHashCode(this).toString()` (line 66 of `IsometricNode.kt`). `identityHashCode` is NOT unique — the JVM specification explicitly allows hash collisions, and they are observed in practice with GC compaction. Two nodes with the same `nodeId` will collide in `nodeIdMap`, `commandIdMap`, and `commandToNodeMap`, causing hit-test misrouting and render command confusion.

### Best Practice

Use `java.util.concurrent.atomic.AtomicLong` for monotonically increasing IDs. This is lock-free, thread-safe, and guaranteed collision-free for the lifetime of the process.

### Files and Changes

#### 3a. `IsometricNode.kt` — Atomic counter (F43)

**Current** (line 66):
```kotlin
val nodeId: String = "node_${System.identityHashCode(this)}"
```

**After**:
```kotlin
companion object {
    private val nextId = java.util.concurrent.atomic.AtomicLong(0)
}

val nodeId: String = "node_${nextId.getAndIncrement()}"
```

**Impact on `RenderCommand.id`**: Node subclasses construct `RenderCommand` with IDs derived from `nodeId`:
- `ShapeNode` (line 162): `"${nodeId}_${path.hashCode()}"` — still unique if `nodeId` is unique
- `PathNode` (line 209): `nodeId` directly
- `BatchNode` (line 250): `"${nodeId}_${index}_${path.hashCode()}"` — still unique

No changes needed in these derivations — they all prefix with `nodeId` which is now unique.

**WS1 dependency**: WS1 Step 1c converts `RenderCommand` from `data class`. The `id` field is read-only and its *generation* (here) is independent of its *storage* (RenderCommand). No conflict.

### Verification

After step 3:
- Unit test: create 10,000 nodes, verify all `nodeId` values are distinct
- Unit test: create nodes on two threads concurrently, verify no duplicates
- Existing hit-test tests still pass

---

## Step 4: Error Handling in Render Loop (F23)

### Rationale

`IsometricRenderer.kt` (670 lines) has ZERO `try/catch` blocks. A single malformed path (NaN coordinates, empty point list) in any `RenderCommand` crashes the entire frame via an uncaught exception in `drawPath()`, `moveTo()`, or `lineTo()`. This is catastrophic for a library — one bad shape from user code kills the entire scene.

### Best Practice

Wrap individual draw commands in `try/catch`, not the entire render loop. Log and skip malformed commands rather than crashing. Use a callback or logging interface so consumers can observe errors without the library swallowing them silently.

### Files and Changes

#### 4a. `IsometricRenderer.kt` — Per-command error handling

**Add error callback to class**:
```kotlin
class IsometricRenderer(
    private val engine: IsometricEngine,
    private val enablePathCaching: Boolean = true,
    private val enableSpatialIndex: Boolean = true,
    private val spatialIndexCellSize: Double = DEFAULT_SPATIAL_INDEX_CELL_SIZE
) {
    /**
     * Optional callback invoked when a render command fails to draw.
     * Receives the command ID and the exception. The command is skipped.
     * Null in production (errors are silently skipped).
     */
    var onRenderError: ((commandId: String, error: Throwable) -> Unit)? = null
```

**Wrap the fast path** (lines 182-189 of `render()`):

**Current**:
```kotlin
for (i in paths.indices) {
    val cached = paths[i]
    drawPath(cached.path, cached.fillColor, style = Fill)

    if (drawStroke) {
        drawPath(cached.path, cached.strokeColor, style = Stroke(width = strokeWidth))
    }
}
```

**After**:
```kotlin
for (i in paths.indices) {
    val cached = paths[i]
    try {
        drawPath(cached.path, cached.fillColor, style = Fill)

        if (drawStroke) {
            drawPath(cached.path, cached.strokeColor, style = Stroke(width = strokeWidth))
        }
    } catch (e: Exception) {
        onRenderError?.invoke(cached.commandId, e)
    }
}
```

**Wrap the fallback path** (`renderPreparedScene()`, lines 491-506):

**Current**:
```kotlin
scene.commands.forEach { command ->
    val path = command.toComposePath()
    val color = command.color.toComposeColor()
    drawPath(path, color, style = Fill)
    if (drawStroke) {
        drawPath(path, Color.Black.copy(alpha = 0.1f), style = Stroke(width = strokeWidth))
    }
}
```

**After**:
```kotlin
scene.commands.forEach { command ->
    try {
        val path = command.toComposePath()
        val color = command.color.toComposeColor()
        drawPath(path, color, style = Fill)
        if (drawStroke) {
            drawPath(path, Color.Black.copy(alpha = 0.1f), style = Stroke(width = strokeWidth))
        }
    } catch (e: Exception) {
        onRenderError?.invoke(command.id, e)
    }
}
```

**Wrap the native path** (`renderNative()`, lines 230-243):

**Current**:
```kotlin
cachedPreparedScene?.commands?.forEach { command ->
    val nativePath = command.toNativePath()
    fillPaint.color = command.color.toAndroidColor()
    canvas.nativeCanvas.drawPath(nativePath, fillPaint)
    if (drawStroke) {
        strokePaint.strokeWidth = strokeWidth
        strokePaint.color = android.graphics.Color.argb(25, 0, 0, 0)
        canvas.nativeCanvas.drawPath(nativePath, strokePaint)
    }
}
```

**After**:
```kotlin
cachedPreparedScene?.commands?.forEach { command ->
    try {
        val nativePath = command.toNativePath()
        fillPaint.color = command.color.toAndroidColor()
        canvas.nativeCanvas.drawPath(nativePath, fillPaint)
        if (drawStroke) {
            strokePaint.strokeWidth = strokeWidth
            strokePaint.color = android.graphics.Color.argb(25, 0, 0, 0)
            canvas.nativeCanvas.drawPath(nativePath, strokePaint)
        }
    } catch (e: Exception) {
        onRenderError?.invoke(command.id, e)
    }
}
```

**Also wrap `rebuildCache()`** (lines 376-424) — the `rootNode.render(context)` call can throw if a custom node's `render()` implementation has bugs:

**Current** (line 380):
```kotlin
val commands = rootNode.render(context)
```

**After** — wrap the entire cache rebuild in a try/catch that preserves the last valid cache on failure:
```kotlin
try {
    val commands = rootNode.render(context)
    // ... rest of rebuild ...
} catch (e: Exception) {
    onRenderError?.invoke("rebuild", e)
    // Leave previous cache intact — render last good frame
    return
}
```

### Verification

After step 4:
- Unit test: inject a node whose `render()` throws — verify the renderer continues drawing remaining commands
- Unit test: verify `onRenderError` callback receives the correct command ID and exception
- Unit test: verify that a failed `rebuildCache()` preserves the previous valid scene
- Performance: verify try/catch adds zero overhead on the happy path (JIT eliminates try/catch with no throw)

---

## Step 5: Lifecycle / Resource Cleanup (F25)

### Rationale

`IsometricRenderer` holds a triple-layer cache (`cachedPreparedScene`, `cachedPaths`, `spatialIndex`) plus multiple `HashMap` lookup structures (`commandIdMap`, `commandOrderMap`, `commandToNodeMap`, `nodeIdMap`), lazy `Paint` objects, and references to the `IsometricEngine`. None of these are released when the renderer is no longer needed. The `SpatialGrid` inner class allocates a 2D `Array` of mutable lists. Without a cleanup mechanism, abandoned renderers leak until GC collects them — which may not happen promptly if references are held in composition state.

### Best Practice

Implement `java.io.Closeable` so renderers can be used in `use {}` blocks and Compose `DisposableEffect` `onDispose` callbacks. The `close()` method should be idempotent and safe to call from any thread.

### Files and Changes

#### 5a. `IsometricRenderer.kt` — Implement `Closeable` (F25)

**Current** (line 55):
```kotlin
class IsometricRenderer(
    private val engine: IsometricEngine,
    ...
) {
```

**After**:
```kotlin
class IsometricRenderer(
    private val engine: IsometricEngine,
    private val enablePathCaching: Boolean = true,
    private val enableSpatialIndex: Boolean = true,
    private val spatialIndexCellSize: Double = DEFAULT_SPATIAL_INDEX_CELL_SIZE
) : java.io.Closeable {
```

**Add `close()` method**:
```kotlin
/**
 * Release all cached resources. Idempotent — safe to call multiple times.
 * After close(), the renderer must not be used for rendering.
 */
override fun close() {
    clearCache()
    benchmarkHooks = null
    onRenderError = null
}
```

The existing `clearCache()` already nulls out all cache fields. `close()` extends that by also clearing the callback references.

> **Post-WS3 name**: `invalidate()` becomes `clearCache()` after WS3 Step 3 (F38). The code above assumes WS3 has landed (WS4 is Phase 3, after WS3).

#### 5b. `IsometricScene.kt` — Dispose renderer on removal

**Current** (lines 183-199): The `DisposableEffect` disposes the `Composition` but not the renderer.

**After** — add renderer cleanup:
```kotlin
DisposableEffect(composition, renderer) {
    composition.setContent {
        // ... existing content ...
    }
    onDispose {
        composition.dispose()
        renderer.close()
    }
}
```

**Note**: The `renderer` is added to the `DisposableEffect` key so that if the renderer is recreated (due to `enablePathCaching`/`enableSpatialIndex`/`spatialIndexCellSize` changing), the old one is closed before the new one is used.

### Verification

After step 5:
- Unit test: call `close()` twice — no exception
- Unit test: verify all cache fields are null after `close()`
- Integration test: verify `DisposableEffect` calls `close()` when the composable leaves the tree

---

## Step 6: Experimental Annotations (F24, F11)

### Rationale

`enableBroadPhaseSort` is a new optimization flag that is not yet battle-tested. `Knot` has a known depth-sort bug documented in its own KDoc. Both should require explicit opt-in so consumers acknowledge the instability.

### Best Practice

Use Kotlin's `@RequiresOptIn` to create opt-in annotations. This generates a compiler warning (or error, depending on level) when the annotated API is used without the corresponding `@OptIn`.

### Files and Changes

#### 6a. Create opt-in annotations

**New file**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/ExperimentalIsometricApi.kt`

```kotlin
package io.fabianterhorst.isometric

/**
 * Marks APIs that are experimental and may change or be removed
 * in future releases. Use at your own risk.
 */
@RequiresOptIn(
    message = "This API is experimental and may change without notice.",
    level = RequiresOptIn.Level.WARNING
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER
)
annotation class ExperimentalIsometricApi
```

#### 6b. `RenderOptions.kt` — Annotate `enableBroadPhaseSort` (F24)

**Current** (line 14):
```kotlin
val enableBroadPhaseSort: Boolean = false,
```

**After**:
```kotlin
@property:ExperimentalIsometricApi
val enableBroadPhaseSort: Boolean = false,
```

**Also annotate** `broadPhaseCellSize` (line 15) since it only matters when broad-phase sort is enabled:
```kotlin
@property:ExperimentalIsometricApi
val broadPhaseCellSize: Double = DEFAULT_BROAD_PHASE_CELL_SIZE
```

**Call site impact**: Any code that reads `renderOptions.enableBroadPhaseSort` or constructs `RenderOptions(enableBroadPhaseSort = true)` will get a compiler warning. Callers must add `@OptIn(ExperimentalIsometricApi::class)`.

Expected locations requiring `@OptIn`:
- `IsometricScene.kt` (reads `renderOptions.enableBroadPhaseSort` at line 163)
- `IsometricRenderer.kt` (if it reads broad-phase fields)
- `IsometricEngine.kt` (if it reads broad-phase fields during `prepare()`)
- Benchmark code
- Sample activities that enable broad-phase sort

#### 6c. `Knot.kt` — Annotate class (F11)

**Current** (line 11):
```kotlin
class Knot(origin: Point) : Shape(createPaths(origin)) {
```

**After**:
```kotlin
@ExperimentalIsometricApi
class Knot(origin: Point) : Shape(createPaths(origin)) {
```

**Update KDoc** to reference the annotation:
```kotlin
/**
 * A knot shape composed of interlocking prisms and custom faces.
 *
 * **Experimental**: This shape has a known depth-sorting issue where
 * overlapping internal faces may render in incorrect order. Use with
 * caution in scenes that require precise depth accuracy.
 */
@ExperimentalIsometricApi
class Knot(origin: Point) : Shape(createPaths(origin)) {
```

**Call site impact**: Any code constructing `Knot(...)` will get a compiler warning. Expected locations:
- Sample activities
- Tests (add `@OptIn` to test classes)

### Verification

After step 6:
- Verify that constructing `RenderOptions(enableBroadPhaseSort = true)` without `@OptIn` produces a compiler warning
- Verify that constructing `Knot(Point.ORIGIN)` without `@OptIn` produces a compiler warning
- All existing code compiles with appropriate `@OptIn` annotations added

---

## Step 7: Platform Safety (F42)

### Rationale

`IsometricScene` accepts `useNativeCanvas: Boolean = false`. When set to `true` on a non-Android platform (desktop JVM, future KMP targets), the call to `renderNative()` triggers `NoClassDefFoundError` for `android.graphics.Canvas` at runtime — with no compile-time warning. This is a silent no-op that becomes a runtime crash.

### Best Practice

Fail at construction time with a clear error message rather than at render time with a confusing `NoClassDefFoundError`. Check platform availability eagerly.

### Files and Changes

#### 7a. `IsometricScene.kt` — Construction-time platform check (F42)

**Add platform detection** at the top of the `IsometricScene` composable (after `remember` blocks, before rendering):

```kotlin
// Validate useNativeCanvas is only used on Android
if (useNativeCanvas) {
    remember {
        try {
            Class.forName("android.graphics.Canvas")
        } catch (_: ClassNotFoundException) {
            throw IllegalStateException(
                "useNativeCanvas=true requires Android. " +
                "The android.graphics.Canvas class is not available on this platform. " +
                "Use the default Compose rendering path instead."
            )
        }
        true // remembered value — check runs once
    }
}
```

**Why `remember {}`**: The check runs once per composition entry, not every recomposition. The `Class.forName` reflection call is cached by the classloader after the first invocation.

**Why not a `require()` at the top level**: The `IsometricScene` function is `@Composable`, so `require()` would throw on every recomposition if the condition changes. `remember` ensures it runs once.

**Alternative — compile-time safety via `expect/actual`**: When the project moves to KMP (Kotlin Multiplatform), `useNativeCanvas` should be removed from the common API and only exposed in `androidMain`. This runtime check is a bridge until KMP migration happens.

#### 7b. `IsometricRenderer.kt` — Document platform restriction

**Update `renderNative()` KDoc** (already partially documented at line 206):

**Current**:
```kotlin
/**
 * @throws NoClassDefFoundError on non-Android platforms
 */
```

**After**:
```kotlin
/**
 * @throws IllegalStateException if called on a non-Android platform
 *   (should be caught at the IsometricScene level before reaching this method)
 * @throws NoClassDefFoundError if android.graphics.Canvas is unavailable
 *   (fallback — should not occur if IsometricScene validates first)
 */
```

### Verification

After step 7:
- Unit test (on JVM): `IsometricScene(useNativeCanvas = true)` throws `IllegalStateException` with a clear message
- Unit test: `IsometricScene(useNativeCanvas = false)` works normally
- Android instrumented test: `IsometricScene(useNativeCanvas = true)` renders correctly

---

## Cross-Workstream Dependencies

| This step | Produces | Consumed by |
|-----------|----------|-------------|
| Step 1a (delete `Color.kt`) | Clean import surface | All workstreams — no accidental `Color` usage |
| Step 1b (delete `IsometricCanvas.kt`) | No scope name collision | WS6 F58 (`CustomNode`) — can safely use `IsometricScope` without ambiguity |
| Step 2 (`children` → `internal`) | Encapsulated tree mutation | WS6 F58 (`CustomNode`) — must be in same module to access `children` |
| *(F75 moved to WS1b)* | *(No transform leak)* | *WS1b handles F75 alongside F71 (`data class` removal)* |
| Step 3 (atomic `nodeId`) | Collision-free IDs | WS1 Step 1c (RenderCommand data→class) — `ownerNodeId` references are stable |
| Step 4 (try/catch in render) | Error-resilient renderer | WS5 F52 (renderer decomposition) — preserve try/catch boundaries during extraction |
| Step 5 (`Closeable` on renderer) | Resource lifecycle | WS5 F52 — preserve `close()` contract during decomposition |
| Step 6a (`@ExperimentalIsometricApi` annotation) | Opt-in mechanism | WS2 F41 (contradictory flags) — may annotate new validation as experimental |
| Step 6b (broad-phase `@Experimental`) | Opt-in on `enableBroadPhaseSort` | WS5 — engine decomposition must propagate `@OptIn` |
| Step 7 (platform check) | Fail-fast for `useNativeCanvas` | WS5 F52 (KMP migration) — replaced by `expect/actual` split |

---

## File Change Summary

| File | Steps | Changes |
|------|-------|---------|
| `Color.kt` | 1 | **DELETE** — legacy wrapper, already @Deprecated |
| `IsometricCanvas.kt` | 1 | **DELETE** — broken legacy API, non-@Composable lambda, missing references |
| `IsometricNode.kt` | 2, 3 | `children` → `internal` (F74), atomic `nodeId` via `AtomicLong` (F43) |
| `IsometricRenderer.kt` | 4, 5 | Per-command try/catch in 3 render paths + rebuildCache, implement `Closeable`, `onRenderError` callback |
| `IsometricScene.kt` | 5, 7 | `renderer.close()` in `onDispose`, platform check for `useNativeCanvas` |
| `RenderOptions.kt` | 6 | `@ExperimentalIsometricApi` on `enableBroadPhaseSort` and `broadPhaseCellSize` |
| `Knot.kt` | 6 | `@ExperimentalIsometricApi` on class, updated KDoc |
| `ExperimentalIsometricApi.kt` | 6 | **NEW FILE** — `@RequiresOptIn` annotation definition |
| `IsometricCanvasSnapshotTest.kt` | 1 | **DELETE or update** — tests legacy `IsometricCanvas` |
| `IsometricRendererTest.kt` | 4, 5 | Add error-handling and lifecycle tests |
