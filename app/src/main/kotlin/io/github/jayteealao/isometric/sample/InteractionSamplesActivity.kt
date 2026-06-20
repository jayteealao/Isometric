package io.github.jayteealao.isometric.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.jayteealao.isometric.HitOrder
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.compose.runtime.*
import io.github.jayteealao.isometric.screenToTile
import io.github.jayteealao.isometric.shapes.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * Activity demonstrating per-node interaction props added in WS10:
 * alpha, onClick, onLongClick, testTag, nodeId.
 */
class InteractionSamplesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    InteractionSamplesScreen()
                }
            }
        }
    }
}

@Composable
fun InteractionSamplesScreen() {
    var selectedSample by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = selectedSample,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = selectedSample == 0,
                onClick = { selectedSample = 0 },
                text = { Text("onClick") }
            )
            Tab(
                selected = selectedSample == 1,
                onClick = { selectedSample = 1 },
                text = { Text("Long Press") }
            )
            Tab(
                selected = selectedSample == 2,
                onClick = { selectedSample = 2 },
                text = { Text("Alpha") }
            )
            Tab(
                selected = selectedSample == 3,
                onClick = { selectedSample = 3 },
                text = { Text("Node ID") }
            )
            Tab(
                selected = selectedSample == 4,
                onClick = { selectedSample = 4 },
                text = { Text("Combined") }
            )
            Tab(
                selected = selectedSample == 5,
                onClick = { selectedSample = 5 },
                text = { Text("Drag Lifecycle") }
            )
            Tab(
                selected = selectedSample == 6,
                onClick = { selectedSample = 6 },
                text = { Text("Drag Node") }
            )
            Tab(
                selected = selectedSample == 7,
                onClick = { selectedSample = 7 },
                text = { Text("LP Config") }
            )
            Tab(
                selected = selectedSample == 8,
                onClick = { selectedSample = 8 },
                text = { Text("Double-tap") }
            )
            Tab(
                selected = selectedSample == 9,
                onClick = { selectedSample = 9 },
                text = { Text("Per-node") }
            )
            Tab(
                selected = selectedSample == 10,
                onClick = { selectedSample = 10 },
                text = { Text("Camera") }
            )
            Tab(
                selected = selectedSample == 11,
                onClick = { selectedSample = 11 },
                text = { Text("Pinch") }
            )
            Tab(
                selected = selectedSample == 12,
                onClick = { selectedSample = 12 },
                text = { Text("Hover") }
            )
            Tab(
                selected = selectedSample == 13,
                onClick = { selectedSample = 13 },
                text = { Text("Back-To-Front") }
            )
            Tab(
                selected = selectedSample == 14,
                onClick = { selectedSample = 14 },
                text = { Text("Hit Query") }
            )
            Tab(
                selected = selectedSample == 15,
                onClick = { selectedSample = 15 },
                text = { Text("Elevated Tile") }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedSample) {
                0 -> OnClickSample()
                1 -> LongPressSample()
                2 -> AlphaSample()
                3 -> NodeIdSample()
                4 -> CombinedSample()
                5 -> DragLifecycleSample()
                6 -> DragNodeSample()
                7 -> LongPressConfigSample()
                8 -> DoubleTapSample()
                9 -> PerNodeCallbackSample()
                10 -> CameraControlSample()
                11 -> PinchZoomRecipeSample()
                12 -> HoverRecipeSample()
                13 -> OccludedPickSample()
                14 -> ImperativeHitQuerySample()
                15 -> ElevatedTileSample()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 1: Per-node onClick
// ---------------------------------------------------------------------------

/**
 * Demonstrates per-node onClick callbacks. Tapping a shape highlights it
 * by changing its color. The scene-level onTap still fires alongside
 * the per-node handler — both are shown in the status bar.
 */
@Composable
fun OnClickSample() {
    var selectedShape by remember { mutableStateOf<String?>(null) }
    var lastSceneTap by remember { mutableStateOf("(none)") }

    val shapes = remember {
        listOf(
            "Red Box" to IsoColor.RED,
            "Green Box" to IsoColor.GREEN,
            "Blue Box" to IsoColor.BLUE,
            "Orange Box" to IsoColor.ORANGE,
            "Purple Box" to IsoColor.PURPLE,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Clicked shape: ${selectedShape ?: "(none)"}")
                Text(
                    "Scene onTap: $lastSceneTap",
                    style = MaterialTheme.typography.caption
                )
            }
        }

        IsometricScene(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = SceneConfig(
                gestures = GestureConfig(
                    onTap = { event ->
                        lastSceneTap = event.node?.nodeId ?: "background"
                    }
                )
            )
        ) {
            // Ground plane
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 10.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY
            )

            // Row of tappable shapes — each with its own onClick
            shapes.forEachIndexed { i, (name, color) ->
                val isSelected = selectedShape == name
                Shape(
                    geometry = Prism(
                        position = Point(i * 1.5, 0.0, 0.1),
                        width = 1.0, depth = 1.0,
                        height = if (isSelected) 2.0 else 1.0
                    ),
                    color = if (isSelected) IsoColor.YELLOW else color,
                    nodeId = name,
                    onClick = { selectedShape = if (selectedShape == name) null else name }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 2: Per-node onLongClick
// ---------------------------------------------------------------------------

/**
 * Long-press a shape to "lock" it (shown with reduced alpha). Tap to unlock.
 * Demonstrates the interplay between onClick and onLongClick on the same node.
 */
@Composable
fun LongPressSample() {
    val lockedShapes = remember { mutableStateMapOf<Int, Boolean>() }
    var statusText by remember { mutableStateOf("Long-press a shape to lock it") }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Text(statusText, modifier = Modifier.padding(12.dp))
        }

        IsometricScene(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Ground
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 8.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY
            )

            // Grid of shapes
            ForEach((0 until 9).toList(), key = { it }) { i ->
                val row = i / 3
                val col = i % 3
                val isLocked = lockedShapes[i] == true

                Shape(
                    geometry = Prism(
                        position = Point(col * 1.8, row * 1.8, 0.1),
                        width = 1.2, depth = 1.2, height = 1.0
                    ),
                    color = IsoColor(
                        (col + 1) * 80.0,
                        (row + 1) * 80.0,
                        150.0
                    ),
                    alpha = if (isLocked) 0.3f else 1f,
                    nodeId = "tile_$i",
                    onClick = {
                        if (isLocked) {
                            lockedShapes.remove(i)
                            statusText = "Unlocked tile $i"
                        } else {
                            statusText = "Tapped tile $i (long-press to lock)"
                        }
                    },
                    onLongClick = {
                        lockedShapes[i] = true
                        statusText = "Locked tile $i"
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 3: Alpha transparency
// ---------------------------------------------------------------------------

/**
 * Demonstrates alpha on different composable types: Shape, Path, Batch,
 * and animating alpha over time.
 */
@Composable
fun AlphaSample() {
    var alphaValue by remember { mutableStateOf(0.5f) }
    var animateAlpha by remember { mutableStateOf(false) }
    var wave by remember { mutableStateOf(0.0) }

    LaunchedEffect(animateAlpha) {
        while (animateAlpha) {
            withFrameNanos {
                wave += PI / 60
                alphaValue = ((sin(wave) + 1.0) / 2.0).toFloat().coerceIn(0.05f, 1f)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Alpha: ${"%.2f".format(alphaValue)}")
                Slider(
                    value = alphaValue,
                    onValueChange = {
                        alphaValue = it
                        animateAlpha = false
                    },
                    valueRange = 0.05f..1f,
                    enabled = !animateAlpha
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Animate")
                    Switch(
                        checked = animateAlpha,
                        onCheckedChange = { animateAlpha = it }
                    )
                }
            }
        }

        IsometricScene(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Opaque base for contrast
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 8.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.DARK_GRAY
            )

            // Shape with controllable alpha
            Shape(
                geometry = Prism(
                    position = Point(0.0, 0.0, 0.1),
                    width = 2.0, depth = 2.0, height = 2.0
                ),
                color = IsoColor.BLUE,
                alpha = alphaValue,
                testTag = "alpha-box"
            )

            // Fixed semi-transparent shape for comparison
            Shape(
                geometry = Cylinder(
                    position = Point(3.0, 0.0, 0.1),
                    radius = 0.8, height = 2.5, vertices = 20
                ),
                color = IsoColor.RED,
                alpha = 0.4f,
                testTag = "semi-transparent-cylinder"
            )

            // Fully opaque reference shape
            Shape(
                geometry = Pyramid(position = Point(0.0, 3.0, 0.1)),
                color = IsoColor.GREEN,
                alpha = 1f,
                testTag = "opaque-pyramid"
            )

            // Batch with alpha — all shapes in the batch share the same alpha
            Batch(
                shapes = listOf(
                    Prism(position = Point(3.5, 3.0, 0.1), width = 0.6, depth = 0.6, height = 0.8),
                    Prism(position = Point(4.3, 3.0, 0.1), width = 0.6, depth = 0.6, height = 1.2),
                    Prism(position = Point(5.1, 3.0, 0.1), width = 0.6, depth = 0.6, height = 1.6),
                ),
                color = IsoColor.CYAN,
                alpha = 0.6f,
                testTag = "alpha-batch"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 4: Stable nodeId and duplicate detection
// ---------------------------------------------------------------------------

/**
 * Demonstrates explicit nodeId for stable identity. Tapping a shape shows
 * its nodeId in the status bar, proving the caller-supplied ID is used
 * instead of the auto-generated one.
 */
@Composable
fun NodeIdSample() {
    var tappedId by remember { mutableStateOf<String?>(null) }
    var tappedTestTag by remember { mutableStateOf<String?>(null) }

    val buildings = remember {
        listOf(
            Building("hq", "Headquarters", IsoColor.BLUE, 3.0),
            Building("factory", "Factory", IsoColor.ORANGE, 2.0),
            Building("warehouse", "Warehouse", IsoColor.GREEN, 1.5),
            Building("tower", "Tower", IsoColor.PURPLE, 4.0),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Tapped nodeId: ${tappedId ?: "(none)"}")
                Text(
                    "testTag: ${tappedTestTag ?: "(none)"}",
                    style = MaterialTheme.typography.caption
                )
                Text(
                    "nodeId values are caller-supplied, not auto-generated",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        IsometricScene(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = SceneConfig(
                gestures = GestureConfig(
                    onTap = { event ->
                        tappedId = event.node?.nodeId
                        tappedTestTag = event.node?.testTag
                    }
                )
            )
        ) {
            // Ground
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 10.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY,
                nodeId = "ground",
                testTag = "ground-plane"
            )

            // Buildings with stable, human-readable nodeIds
            buildings.forEachIndexed { i, building ->
                val isSelected = tappedId == building.id
                Shape(
                    geometry = Prism(
                        position = Point(i * 2.0, 1.0, 0.1),
                        width = 1.5, depth = 1.5,
                        height = building.height
                    ),
                    color = if (isSelected) IsoColor.YELLOW else building.color,
                    nodeId = building.id,
                    testTag = "building-${building.id}",
                    onClick = {
                        tappedId = building.id
                        tappedTestTag = "building-${building.id}"
                    }
                )
            }
        }
    }
}

private data class Building(
    val id: String,
    val name: String,
    val color: IsoColor,
    val height: Double
)

// ---------------------------------------------------------------------------
// Sample 5: Combined — all WS10 features together
// ---------------------------------------------------------------------------

/**
 * A mini city builder demonstrating all WS10 features working together:
 * - onClick to select buildings
 * - onLongClick to demolish (remove) buildings
 * - alpha to ghost-preview placement
 * - nodeId for stable identity
 * - testTag for diagnostics
 */
@Composable
fun CombinedSample() {
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    val demolished = remember { mutableStateMapOf<Int, Boolean>() }
    var statusText by remember { mutableStateOf("Tap to select · Long-press to demolish") }

    val slots = remember {
        listOf(
            CitySlot(0, "Office", IsoColor.BLUE, 2.5, Point(0.0, 0.0, 0.1)),
            CitySlot(1, "Shop", IsoColor.ORANGE, 1.5, Point(2.0, 0.0, 0.1)),
            CitySlot(2, "Park", IsoColor.GREEN, 0.5, Point(4.0, 0.0, 0.1)),
            CitySlot(3, "Tower", IsoColor.PURPLE, 3.5, Point(0.0, 2.0, 0.1)),
            CitySlot(4, "House", IsoColor.CYAN, 1.0, Point(2.0, 2.0, 0.1)),
            CitySlot(5, "Garage", IsoColor.BROWN, 0.8, Point(4.0, 2.0, 0.1)),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(statusText)
                val selectedInfo = selectedSlot?.let { slots[it] }
                if (selectedInfo != null) {
                    Text(
                        "Selected: ${selectedInfo.name} (id=${selectedInfo.id})",
                        style = MaterialTheme.typography.caption
                    )
                }
                Text(
                    "Demolished: ${demolished.keys.sorted().joinToString { slots[it].name }}",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.error
                )
                if (demolished.isNotEmpty()) {
                    TextButton(onClick = {
                        demolished.clear()
                        statusText = "All buildings restored"
                    }) {
                        Text("Restore All")
                    }
                }
            }
        }

        IsometricScene(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Ground
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 8.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY,
                nodeId = "city-ground"
            )

            // Buildings
            slots.forEach { slot ->
                val isDemolished = demolished[slot.id] == true
                val isSelected = selectedSlot == slot.id

                If(!isDemolished) {
                    Shape(
                        geometry = Prism(
                            position = slot.position,
                            width = 1.5, depth = 1.5,
                            height = slot.height
                        ),
                        color = when {
                            isSelected -> IsoColor.YELLOW
                            else -> slot.color
                        },
                        alpha = when {
                            isSelected -> 0.8f
                            else -> 1f
                        },
                        nodeId = "building-${slot.id}",
                        testTag = "city-${slot.name.lowercase()}",
                        onClick = {
                            selectedSlot = if (selectedSlot == slot.id) null else slot.id
                            statusText = "Selected: ${slot.name}"
                        },
                        onLongClick = {
                            demolished[slot.id] = true
                            if (selectedSlot == slot.id) selectedSlot = null
                            statusText = "Demolished: ${slot.name}"
                        }
                    )
                }
            }
        }
    }
}

private data class CitySlot(
    val id: Int,
    val name: String,
    val color: IsoColor,
    val height: Double,
    val position: Point
)

// ---------------------------------------------------------------------------
// Sample 6: Drag lifecycle — onDragStart / onDrag / onDragEnd
// ---------------------------------------------------------------------------

/**
 * Demonstrates the full drag lifecycle with a non-default [GestureConfig.dragThreshold].
 * Dragging past 32px fires onDragStart once, then onDrag repeatedly, then onDragEnd once.
 *
 * The status card makes the [DragEvent] field contract visible: `x`/`y` are the absolute
 * pointer position (the drag-start position, captured in onDragStart), while `delta` is the
 * per-event movement that callers accumulate — the same value camera autopan sums. Reading
 * the absolute start from `x`/`y` and the running total from `delta` is the hero use case.
 */
@Composable
fun DragLifecycleSample() {
    var lastEvent by remember { mutableStateOf("(idle — drag the scene)") }
    var startX by remember { mutableStateOf(0.0) }
    var startY by remember { mutableStateOf(0.0) }
    var accumulatedDx by remember { mutableStateOf(0.0) }
    var accumulatedDy by remember { mutableStateOf(0.0) }
    var dragEvents by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Last event: $lastEvent")
                Text(
                    "Drag start (absolute x/y): " +
                        "(${"%.0f".format(startX)}, ${"%.0f".format(startY)})",
                    style = MaterialTheme.typography.caption
                )
                Text(
                    "Accumulated delta: " +
                        "(${"%.0f".format(accumulatedDx)}, ${"%.0f".format(accumulatedDy)}) " +
                        "over $dragEvents events",
                    style = MaterialTheme.typography.caption
                )
                Text(
                    "Threshold 32px · x/y are absolute, delta is per-event movement",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        IsometricScene(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = SceneConfig(
                gestures = GestureConfig(
                    dragThreshold = 32f,
                    onDragStart = { event ->
                        lastEvent = "DRAG_START"
                        startX = event.x
                        startY = event.y
                        accumulatedDx = 0.0
                        accumulatedDy = 0.0
                        dragEvents = 0
                    },
                    onDrag = { event ->
                        lastEvent = "DRAG"
                        // delta is non-null in onDrag; accumulate it to track total travel.
                        event.delta?.let { d ->
                            accumulatedDx += d.dx
                            accumulatedDy += d.dy
                            dragEvents++
                        }
                    },
                    onDragEnd = {
                        lastEvent = "DRAG_END"
                    }
                )
            )
        ) {
            // Floor slab + two contrasting prisms — mirrored by the
            // isometric-compose test fixture `DragLifecycleScene`.
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 8.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY
            )
            Shape(
                geometry = Prism(
                    position = Point(1.0, 1.0, 0.1),
                    width = 1.5, depth = 1.5, height = 1.5
                ),
                color = IsoColor.BLUE
            )
            Shape(
                geometry = Prism(
                    position = Point(4.0, 2.0, 0.1),
                    width = 1.5, depth = 1.5, height = 2.5
                ),
                color = IsoColor.ORANGE
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 7: Drag a node — the hero scenario
// ---------------------------------------------------------------------------

/**
 * The single-node drag hero: tap a prism to select it, then drag it to move only that
 * prism. Dragging empty space pans the camera; tapping empty space deselects.
 *
 * The whole interaction is wired by the library: handing a [rememberNodeDragState] to
 * [SceneConfig.nodeDragState] is all the call site needs — the scene selects the tapped
 * node, drags the selected node, clamps it to the configured [NodeDragBounds], and leaves
 * background drags to the camera. The sample only reads [NodeDragState.selectedNodeId] to
 * tint the selected prism.
 */
@Composable
fun DragNodeSample() {
    val dragState = rememberNodeDragState(
        bounds = NodeDragBounds(minX = -4.0, maxX = 4.0, minY = -4.0, maxY = 4.0)
    )
    val cameraState = remember { CameraState() }
    val selectedId = dragState.selectedNodeId

    // Stable id → base position; the selected prism is highlighted. Mirrored by the
    // isometric-compose test fixture `DragNodeScene`.
    val prisms = remember {
        listOf(
            "node-center" to Point(3.0, 2.0, 0.1),
            "node-n" to Point(3.0, 0.0, 0.1),
            "node-s" to Point(3.0, 4.0, 0.1),
            "node-w" to Point(1.0, 2.0, 0.1),
            "node-e" to Point(5.0, 2.0, 0.1),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Selected: ${selectedId ?: "(none — tap a prism)"}")
                Text(
                    "Tap a prism to select, then drag it to move only that prism. " +
                        "Drag empty space to pan; tap empty space to deselect.",
                    style = MaterialTheme.typography.caption
                )
                Text(
                    "Drag is clamped to ±4 engine units, so a node can't be lost off-scene.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        IsometricScene(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = SceneConfig(
                cameraState = cameraState,
                nodeDragState = dragState
            )
        ) {
            // Ground slab.
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 8.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY
            )
            // Five draggable prisms in a cross; the selected one is highlighted.
            // No `position` argument: the geometry carries each prism's home, leaving the
            // node-position transform free for the library's drag to mutate.
            prisms.forEach { (id, pos) ->
                Shape(
                    geometry = Prism(position = pos, width = 1.0, depth = 1.0, height = 1.0),
                    color = if (id == selectedId) IsoColor.YELLOW else IsoColor.BLUE,
                    nodeId = id
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 8: Configurable long-press timeout
// ---------------------------------------------------------------------------

/**
 * Demonstrates [GestureConfig.longPressTimeoutMs]. Pick a timeout (200 / 500 / 1000 ms),
 * then press and hold the prism: with 200ms it fires almost immediately, with 1000ms you
 * must hold noticeably longer before `onLongClick` fires. The default (500ms) matches the
 * platform long-press timeout.
 */
@Composable
fun LongPressConfigSample() {
    var timeoutMs by remember { mutableStateOf(500L) }
    var fireCount by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Pick a timeout, then press and hold the prism") }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(status)
                Text(
                    "Long-press timeout: ${timeoutMs}ms · fired $fireCount time(s)",
                    style = MaterialTheme.typography.caption
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(200L, 500L, 1000L).forEach { ms ->
                        Button(
                            onClick = {
                                timeoutMs = ms
                                fireCount = 0
                                status = "Timeout set to ${ms}ms — press and hold the prism"
                            },
                            enabled = timeoutMs != ms
                        ) {
                            Text("${ms}ms")
                        }
                    }
                }
            }
        }

        IsometricScene(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = SceneConfig(
                gestures = GestureConfig(longPressTimeoutMs = timeoutMs)
            )
        ) {
            // Ground
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 8.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY
            )
            // Hold target
            Shape(
                geometry = Prism(
                    position = Point(2.0, 2.0, 0.1),
                    width = 2.0, depth = 2.0, height = 1.5
                ),
                color = IsoColor.BLUE,
                nodeId = "lp-target",
                onLongClick = {
                    fireCount++
                    status = "onLongClick fired (timeout was ${timeoutMs}ms)"
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 9: Double-tap vs. single tap
// ---------------------------------------------------------------------------

/**
 * Demonstrates per-node `onDoubleClick`. Double-tap a prism to increment its double-tap
 * count; a single tap increments the single-tap count. The two counters make the
 * disambiguation between the two gestures visible.
 */
@Composable
fun DoubleTapSample() {
    var singleTaps by remember { mutableStateOf(0) }
    var doubleTaps by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Single taps: $singleTaps · Double taps: $doubleTaps")
                Text(
                    "Double-tap the prism to count a double tap; a single tap counts a single.",
                    style = MaterialTheme.typography.caption
                )
            }
        }

        IsometricScene(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Ground
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 8.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY
            )
            // Tap target with both callbacks
            Shape(
                geometry = Prism(
                    position = Point(2.0, 2.0, 0.1),
                    width = 2.0, depth = 2.0, height = 1.5
                ),
                color = IsoColor.ORANGE,
                nodeId = "dt-target",
                onClick = { singleTaps++ },
                onDoubleClick = { doubleTaps++ }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 10: Per-node callbacks across node types
// ---------------------------------------------------------------------------

/**
 * Demonstrates that `onClick` / `onLongClick` / `onDoubleClick` fire uniformly across the
 * hittable node types — a [Path], a [Batch], and a [CustomNode]. The status bar shows the
 * last callback that fired and which node type produced it.
 */
@Composable
fun PerNodeCallbackSample() {
    var lastFired by remember { mutableStateOf("Tap / long-press / double-tap a node") }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Last: $lastFired")
                Text(
                    "Path · Batch · CustomNode — each fires onClick / onLongClick / onDoubleClick.",
                    style = MaterialTheme.typography.caption
                )
            }
        }

        IsometricScene(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Ground
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 8.0, depth = 8.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY
            )

            // Path node — a closed quad tile.
            Path(
                path = io.github.jayteealao.isometric.Path(
                    listOf(
                        Point(0.0, 0.0, 0.4),
                        Point(2.0, 0.0, 0.4),
                        Point(2.0, 2.0, 0.4),
                        Point(0.0, 2.0, 0.4)
                    )
                ),
                color = IsoColor.GREEN,
                nodeId = "node-path",
                onClick = { lastFired = "onClick · Path" },
                onLongClick = { lastFired = "onLongClick · Path" },
                onDoubleClick = { lastFired = "onDoubleClick · Path" }
            )

            // Batch node — two prisms sharing one color.
            Batch(
                shapes = listOf(
                    Prism(position = Point(4.0, 0.0, 0.1), width = 1.0, depth = 1.0, height = 1.0),
                    Prism(position = Point(4.0, 1.5, 0.1), width = 1.0, depth = 1.0, height = 1.5)
                ),
                color = IsoColor.PURPLE,
                nodeId = "node-batch",
                onClick = { lastFired = "onClick · Batch" },
                onLongClick = { lastFired = "onLongClick · Batch" },
                onDoubleClick = { lastFired = "onDoubleClick · Batch" }
            )

            // CustomNode — a user-rendered quad (the escape hatch).
            CustomNode(
                nodeId = "node-custom",
                onClick = { lastFired = "onClick · CustomNode" },
                onLongClick = { lastFired = "onLongClick · CustomNode" },
                onDoubleClick = { lastFired = "onDoubleClick · CustomNode" },
                render = { context, nodeId ->
                    val quad = io.github.jayteealao.isometric.Path(
                        listOf(
                            Point(0.0, 4.0, 0.4),
                            Point(2.0, 4.0, 0.4),
                            Point(2.0, 6.0, 0.4),
                            Point(0.0, 6.0, 0.4)
                        )
                    )
                    listOf(
                        io.github.jayteealao.isometric.RenderCommand(
                            commandId = nodeId,
                            points = emptyList(),
                            color = IsoColor.CYAN,
                            originalPath = context.applyTransformsToPath(quad),
                            originalShape = null,
                            ownerNodeId = nodeId
                        )
                    )
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 11: Camera control — built-in pan / zoom / reset
// ---------------------------------------------------------------------------

/**
 * Demonstrates the built-in [CameraState] end-to-end. Handing a [CameraState] to
 * [SceneConfig.cameraState] enables drag-to-pan with no `onDrag` of your own: the scene's
 * default drag handler pans the camera. The buttons drive [CameraState.zoomBy] and
 * [CameraState.reset] programmatically, and the status card reflects the live `panX` / `panY` /
 * `zoom` — all three are Compose snapshot state, so the card recomposes as the camera moves.
 */
@Composable
fun CameraControlSample() {
    val cameraState = remember { CameraState() }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Drag to pan · zoom / reset with the buttons")
                Text(
                    "pan = (${"%.0f".format(cameraState.panX)}, " +
                        "${"%.0f".format(cameraState.panY)}) · " +
                        "zoom = ${"%.2f".format(cameraState.zoom)}",
                    style = MaterialTheme.typography.caption
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { cameraState.zoomBy(1.2) }) { Text("Zoom In") }
                    Button(onClick = { cameraState.zoomBy(1.0 / 1.2) }) { Text("Zoom Out") }
                    Button(onClick = { cameraState.reset() }) { Text("Reset") }
                }
            }
        }

        // Drag-to-pan fires automatically because cameraState is set and no onDrag is supplied.
        // Geometry mirrors the `CameraControlScene` test fixture.
        IsometricScene(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = SceneConfig(cameraState = cameraState)
        ) {
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 8.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY
            )
            Shape(
                // Seated on the slab top (z=0.1), not its base (z=0.0): a coplanar base
                // lets the slab's top face overpaint the prism walls, flattening it to a diamond.
                geometry = Prism(position = Point(0.0, 0.0, 0.1)),
                color = IsoColor(33.0, 150.0, 243.0)
            )
            Shape(
                geometry = Pyramid(position = Point(2.0, 0.0, 0.0)),
                color = IsoColor(255.0, 100.0, 0.0)
            )
            Shape(
                geometry = Cylinder(
                    position = Point(-2.0, 0.0, 0.0),
                    radius = 0.5, height = 2.0, vertices = 20
                ),
                color = IsoColor(0.0, 200.0, 100.0)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 12: Pinch-to-zoom recipe (no new API)
// ---------------------------------------------------------------------------

/**
 * A no-new-API **recipe**: pinch-to-zoom built from Compose primitives wired to
 * [CameraState.zoomBy]. The recipe is a `Modifier.pointerInput(Unit) { detectTransformGestures … }`
 * chained on the scene's modifier — deliberately a *separate* pointer-input node from the scene's
 * own tap/drag detector. Stacking `detectTransformGestures` in the same `pointerInput` lambda as
 * another detector would dead-code all but the first; keeping it separate lets the scale gesture
 * and the built-in drag-to-pan coexist. (`zoom` from `detectTransformGestures` is the per-gesture
 * scale ratio, always positive, so it's a valid [CameraState.zoomBy] factor.)
 */
@Composable
fun PinchZoomRecipeSample() {
    val cameraState = remember { CameraState() }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Pinch to zoom · zoom = ${"%.2f".format(cameraState.zoom)}")
                Text(
                    "Recipe: detectTransformGestures in its own pointerInput → cameraState.zoomBy",
                    style = MaterialTheme.typography.caption
                )
                Button(
                    onClick = { cameraState.reset() },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Reset") }
            }
        }

        // The pinch detector lives in a SEPARATE pointerInput from the scene's internal
        // tap/drag handler — this modifier is applied before the scene chains its own.
        // Geometry mirrors the `PinchZoomRecipeScene` test fixture.
        IsometricScene(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomFactor, _ ->
                        cameraState.zoomBy(zoomFactor.toDouble())
                    }
                },
            config = SceneConfig(cameraState = cameraState)
        ) {
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 6.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY
            )
            Shape(
                // Seated on the slab top (z=0.1), not its base (z=0.0): a coplanar base
                // lets the slab's top face overpaint the prism walls, flattening it to a diamond.
                geometry = Prism(position = Point(1.0, 1.0, 0.1)),
                color = IsoColor.BLUE
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 13: Hover recipe (mouse / stylus only)
// ---------------------------------------------------------------------------

/**
 * A no-new-API **recipe**: a hover affordance from [Modifier.hoverable] +
 * [collectIsHoveredAsState]. Moving a mouse or stylus over the scene tints the target prism and
 * flips the status card; the scene reads only `isHovered`.
 *
 * Hover fires **only** for mouse / stylus — a touchscreen finger never generates hover events, so
 * this tab shows nothing under touch input. That platform limitation (and how to verify it) is the
 * one manual-verification path in this sample set; the full accuracy note lives in the docs.
 */
@Composable
fun HoverRecipeSample() {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Hover state: ${if (isHovered) "ENTERED" else "EXITED"}")
                Text(
                    "Hover fires only for mouse / stylus — not for touchscreen input.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        // hoverable reports Enter/Exit for mouse/stylus pointers over the scene.
        // Geometry mirrors the `HoverRecipeScene` test fixture (the not-hovered baseline).
        IsometricScene(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .hoverable(interactionSource)
        ) {
            Shape(
                geometry = Prism(
                    position = Point(-1.0, -1.0, 0.0),
                    width = 6.0, depth = 6.0, height = 0.1
                ),
                color = IsoColor.LIGHT_GRAY
            )
            Shape(
                geometry = Prism(position = Point(1.0, 1.0, 0.1)),
                color = if (isHovered) IsoColor.YELLOW else IsoColor.ORANGE
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 14: Back-to-front hit ordering (findItemAt + HitOrder)
// ---------------------------------------------------------------------------

/**
 * Demonstrates the [HitOrder] escape hatch on overlapping geometry. Two prisms overlap on screen;
 * tapping the overlap resolves *two* hits from the same tap: the scene's default `onTap` gives the
 * near prism (`FRONT_TO_BACK`), and a follow-up [IsometricEngine.findItemAt] with `BACK_TO_FRONT`
 * reaches the occluded prism beneath it.
 *
 * The `BACK_TO_FRONT` order is not reachable through `onHitTestReady` (its delivered function is
 * always `FRONT_TO_BACK`), so the demo captures the [PreparedScene] via
 * [AdvancedSceneConfig.onPreparedSceneReady] and queries the supplied [AdvancedSceneConfig.engine]
 * directly. Geometry mirrors the `OccludedPickScene` test fixture.
 */
@Composable
fun OccludedPickSample() {
    val engine = remember { IsometricEngine() }
    var prepared by remember { mutableStateOf<PreparedScene?>(null) }
    var topHit by remember { mutableStateOf("(none)") }
    var bottomHit by remember { mutableStateOf("(none)") }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Tap where the two prisms overlap")
                Text(
                    "FRONT_TO_BACK (top): $topHit · BACK_TO_FRONT (bottom): $bottomHit",
                    style = MaterialTheme.typography.caption
                )
                Text(
                    "One tap, two orders: the default picks the near prism; BACK_TO_FRONT reaches " +
                        "the one occluded beneath it.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        IsometricScene(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = AdvancedSceneConfig(
                engine = engine,
                onPreparedSceneReady = { prepared = it },
                gestures = GestureConfig(
                    onTap = { event ->
                        topHit = event.node?.nodeId ?: "(miss)"
                        bottomHit = prepared?.let { scene ->
                            engine.findItemAt(scene, event.x, event.y, HitOrder.BACK_TO_FRONT, 8.0)
                                ?.ownerNodeId ?: "(miss)"
                        } ?: "(scene not ready)"
                    }
                )
            )
        ) {
            // Geometry mirrors the `OccludedPickScene` test fixture (front-tile / back-tile).
            Shape(
                geometry = Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 2.0),
                color = IsoColor.BLUE,
                nodeId = "front-tile"
            )
            Shape(
                geometry = Prism(Point(0.5, 0.5, 0.0), 1.0, 1.0, 1.0),
                color = IsoColor.RED,
                nodeId = "back-tile"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 15: Imperative hit query (onHitTestReady)
// ---------------------------------------------------------------------------

/**
 * Demonstrates [AdvancedSceneConfig.onHitTestReady]. The scene hands the caller a
 * `(x, y) -> IsometricNode?` function; this tab keeps it and lets you query any screen coordinate
 * from a text field + button — proving the hatch resolves hits *outside* any gesture.
 */
@Composable
fun ImperativeHitQuerySample() {
    var hitFn by remember { mutableStateOf<((Double, Double) -> IsometricNode?)?>(null) }
    var queryX by remember { mutableStateOf("400") }
    var queryY by remember { mutableStateOf("300") }
    var result by remember { mutableStateOf("(run a query)") }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Query a coordinate — no tap required · result: $result")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = queryX,
                        onValueChange = { queryX = it },
                        label = { Text("x") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = queryY,
                        onValueChange = { queryY = it },
                        label = { Text("y") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val x = queryX.toDoubleOrNull()
                            val y = queryY.toDoubleOrNull()
                            result = when {
                                x == null || y == null -> "enter numbers"
                                hitFn == null -> "(scene not ready)"
                                else -> hitFn!!.invoke(x, y)?.nodeId ?: "(miss)"
                            }
                        }
                    ) { Text("Query") }
                }
            }
        }

        IsometricScene(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = AdvancedSceneConfig(
                onHitTestReady = { fn -> hitFn = fn }
            )
        ) {
            Shape(
                geometry = Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1),
                color = IsoColor.LIGHT_GRAY,
                nodeId = "ground"
            )
            Shape(
                geometry = Prism(Point(1.0, 1.0, 0.1), 1.5, 1.5, 1.5),
                color = IsoColor.BLUE,
                nodeId = "tile-blue"
            )
            Shape(
                geometry = Prism(Point(4.0, 1.0, 0.1), 1.5, 1.5, 2.5),
                color = IsoColor.ORANGE,
                nodeId = "tile-orange"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sample 16: Elevated-tile coordinate mapping (screenToTile at elevation)
// ---------------------------------------------------------------------------

/**
 * Demonstrates [screenToTile] on an elevated tile. Tapping the raised orange tile maps the tap back
 * to its grid cell only when the inverse projection intersects the tile's surface z-plane
 * (`elevation = 2.0`); the same tap at `elevation = 0.0` lands on a different ground cell. The two
 * rows make the elevation parameter's effect visible side by side.
 *
 * Viewport dimensions come from [AdvancedSceneConfig.onFlagsReady]; the supplied
 * [AdvancedSceneConfig.engine] is the same instance `screenToTile` is called on, so the projection
 * matches the rendered scene. Geometry mirrors the `ElevatedTileScene` test fixture.
 */
@Composable
fun ElevatedTileSample() {
    val engine = remember { IsometricEngine() }
    var canvasW by remember { mutableStateOf(0) }
    var canvasH by remember { mutableStateOf(0) }
    var tappedNode by remember { mutableStateOf("(none)") }
    var tileAtSurface by remember { mutableStateOf("(tap the orange tile)") }
    var tileAtGround by remember { mutableStateOf("(tap the orange tile)") }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Tap the raised orange tile · node: $tappedNode")
                Text(
                    "screenToTile(elevation = 2.0): $tileAtSurface",
                    style = MaterialTheme.typography.caption
                )
                Text(
                    "screenToTile(elevation = 0.0): $tileAtGround — the ground-plane answer differs.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        IsometricScene(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = AdvancedSceneConfig(
                engine = engine,
                onFlagsReady = { flags ->
                    canvasW = flags.canvasWidth
                    canvasH = flags.canvasHeight
                },
                gestures = GestureConfig(
                    onTap = { event ->
                        tappedNode = event.node?.nodeId ?: "(background)"
                        if (canvasW > 0 && canvasH > 0) {
                            tileAtSurface = engine.screenToTile(
                                event.x, event.y, canvasW, canvasH, elevation = 2.0
                            ).toString()
                            tileAtGround = engine.screenToTile(
                                event.x, event.y, canvasW, canvasH, elevation = 0.0
                            ).toString()
                        }
                    }
                )
            )
        ) {
            // Geometry mirrors the `ElevatedTileScene` test fixture.
            Shape(
                geometry = Prism(Point(-1.0, -1.0, 0.0), 8.0, 8.0, 0.1),
                color = IsoColor.LIGHT_GRAY
            )
            Shape(
                geometry = Prism(Point(2.0, 2.0, 2.0), 1.0, 1.0, 0.5),
                color = IsoColor.ORANGE,
                nodeId = "elevated-tile"
            )
        }
    }
}
