package io.github.jayteealao.isometric.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.*
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
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedSample) {
                0 -> OnClickSample()
                1 -> LongPressSample()
                2 -> AlphaSample()
                3 -> NodeIdSample()
                4 -> CombinedSample()
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
