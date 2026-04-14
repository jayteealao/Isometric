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
import kotlin.math.cos

/**
 * Activity demonstrating the new runtime-level API with ComposeNode and custom Applier
 */
class RuntimeApiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    RuntimeApiSamplesScreen()
                }
            }
        }
    }
}

@Composable
fun RuntimeApiSamplesScreen() {
    var selectedSample by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = selectedSample,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = selectedSample == 0,
                onClick = { selectedSample = 0 },
                text = { Text("Simple") }
            )
            Tab(
                selected = selectedSample == 1,
                onClick = { selectedSample = 1 },
                text = { Text("Hierarchy") }
            )
            Tab(
                selected = selectedSample == 2,
                onClick = { selectedSample = 2 },
                text = { Text("Animation") }
            )
            Tab(
                selected = selectedSample == 3,
                onClick = { selectedSample = 3 },
                text = { Text("Interactive") }
            )
            Tab(
                selected = selectedSample == 4,
                onClick = { selectedSample = 4 },
                text = { Text("Conditional") }
            )
            Tab(
                selected = selectedSample == 5,
                onClick = { selectedSample = 5 },
                text = { Text("Performance") }
            )
            Tab(
                selected = selectedSample == 6,
                onClick = { selectedSample = 6 },
                text = { Text("Stack") }
            )
            Tab(
                selected = selectedSample == 7,
                onClick = { selectedSample = 7 },
                text = { Text("Tile Grid") }
            )
            Tab(
                selected = selectedSample == 8,
                onClick = { selectedSample = 8 },
                text = { Text("Opt. Perf") }
            )
            Tab(
                selected = selectedSample == 9,
                onClick = { selectedSample = 9 },
                text = { Text("Perf Cmp") }
            )
            Tab(
                selected = selectedSample == 10,
                onClick = { selectedSample = 10 },
                text = { Text("Low Level") }
            )
            Tab(
                selected = selectedSample == 11,
                onClick = { selectedSample = 11 },
                text = { Text("Grid Stack") }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedSample) {
                0 -> SimpleSample()
                1 -> HierarchySample()
                2 -> AnimationSample()
                3 -> RuntimeInteractiveSample()
                4 -> ConditionalSample()
                5 -> PerformanceSample()
                6 -> StackExample()
                7 -> TileGridExample()
                8 -> OptimizedPerformanceSample()
                9 -> PerformanceComparisonDemo()
                10 -> MixedLevelExample()
                11 -> GridStackExample()
            }
        }
    }
}

/**
 * Sample 1: Simple shapes using the new API
 */
@Composable
fun SimpleSample() {
    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Shape(
            geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 2.0, depth = 2.0, height = 2.0)
        )

        Shape(
            geometry = Pyramid(position = Point(3.0, 0.0, 0.0)),
            material = IsoColor(255.0, 100.0, 0.0)
        )

        Shape(
            geometry = Cylinder(position = Point(-3.0, 0.0, 0.0), radius = 0.5, height = 2.0, vertices = 20),
            material = IsoColor(0.0, 200.0, 100.0)
        )
    }
}

/**
 * Sample 2: Hierarchical transforms with groups
 */
@Composable
fun HierarchySample() {
    var groupRotation by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameNanos != 0L) {
                    val deltaSeconds = (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
                    groupRotation += deltaSeconds * PI
                }
                lastFrameNanos = frameTimeNanos
            }
        }
    }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        // Static base
        Shape(
            geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 4.0, depth = 4.0, height = 0.5),
            material = IsoColor(100.0, 100.0, 100.0)
        )

        // Rotating group — centered on the base
        Group(
            position = Point(2.0, 2.0, 0.5),
            rotation = groupRotation,
            rotationOrigin = Point(2.0, 2.0, 0.5)
        ) {
            // These all rotate together (local coords relative to group center)
            Shape(
                geometry = Prism(position = Point(-1.5, 0.0, 0.0), width = 1.0, depth = 1.0, height = 2.0),
                material = IsoColor(255.0, 0.0, 0.0)
            )

            Shape(
                geometry = Prism(position = Point(1.5, 0.0, 0.0), width = 1.0, depth = 1.0, height = 2.0),
                material = IsoColor(0.0, 255.0, 0.0)
            )

            Shape(
                geometry = Prism(position = Point(0.0, -1.5, 0.0), width = 1.0, depth = 1.0, height = 2.0),
                material = IsoColor(0.0, 0.0, 255.0)
            )

            Shape(
                geometry = Prism(position = Point(0.0, 1.5, 0.0), width = 1.0, depth = 1.0, height = 2.0),
                material = IsoColor(255.0, 255.0, 0.0)
            )

            // Nested group with additional counter-rotation
            Group(
                position = Point(0.0, 0.0, 2.0),
                rotation = -groupRotation * 2
            ) {
                Shape(
                    geometry = Octahedron(position = Point(0.0, 0.0, 0.0)),
                    material = IsoColor(255.0, 0.0, 255.0)
                )
            }
        }
    }
}

/**
 * Sample 3: Efficient animation - only animated node recomposes
 */
@Composable
fun AnimationSample() {
    var angle by remember { mutableStateOf(0.0) }
    var wave by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameNanos != 0L) {
                    val deltaSeconds = (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
                    angle += deltaSeconds * (2 * PI / 3)
                    wave += deltaSeconds * PI
                }
                lastFrameNanos = frameTimeNanos
            }
        }
    }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        // Static scene (never recomposes)
        Shape(
            geometry = Prism(position = Point(1.0, -1.0, 0.0), width = 4.0, depth = 5.0, height = 2.0),
            material = IsoColor(33.0, 150.0, 243.0)
        )

        Shape(
            geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 1.0, depth = 4.0, height = 1.0),
            material = IsoColor(33.0, 150.0, 243.0)
        )

        Shape(
            geometry = Stairs(position = Point(-1.0, 0.0, 0.0), stepCount = 10),
            material = IsoColor(33.0, 150.0, 243.0)
        )

        // Animated group (only this recomposes!)
        Group(
            position = Point(3.0, 2.0, 3.0),
            rotation = angle,
            rotationOrigin = Point(3.5, 2.5, 3.0)
        ) {
            Shape(
                geometry = Octahedron(position = Point(0.0, 0.0, 0.0)),
                material = IsoColor(0.0, 180.0, 180.0)
            )
        }

        // Animated tower
        Group(position = Point(-3.0, 0.0, 0.0)) {
            ForEach((0..5).toList()) { i ->
                Shape(
                    geometry = Prism(
                        position = Point(0.0, 0.0, i.toDouble()),
                        width = 1.0,
                        depth = 1.0,
                        height = 1.0
                    ),
                    material = IsoColor(
                        ((sin(wave + i * PI / 3) + 1.0) * 0.5) * 255.0,
                        150.0,
                        ((cos(wave + i * PI / 3) + 1.0) * 0.5) * 255.0
                    )
                )
            }
        }
    }
}

/**
 * Sample 4: Interactive with gesture handling (runtime API version)
 */
@Composable
fun RuntimeInteractiveSample() {
    var tappedNode by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Point(0.0, 0.0, 0.0)) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (tappedNode != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                backgroundColor = MaterialTheme.colors.primary
            ) {
                Text(
                    "Tapped: $tappedNode",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colors.onPrimary
                )
            }
        }

        IsometricScene(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = SceneConfig(
                gestures = GestureConfig(
                    onTap = { event ->
                        tappedNode = event.node?.let { "Node ${it.nodeId}" } ?: "Background"
                    },
                    onDrag = { event ->
                        dragOffset = Point(
                            dragOffset.x + event.x / 50.0,
                            dragOffset.y - event.y / 50.0,
                            dragOffset.z
                        )
                    }
                )
            )
        ) {
            Group(position = dragOffset) {
                Shape(
                    geometry = Prism(position = Point(0.0, 0.0, 0.0)),
                    material = IsoColor(33.0, 150.0, 243.0)
                )

                Shape(
                    geometry = Pyramid(position = Point(2.0, 0.0, 0.0)),
                    material = IsoColor(255.0, 100.0, 0.0)
                )

                Shape(
                    geometry = Cylinder(position = Point(-2.0, 0.0, 0.0), radius = 0.5, height = 2.0, vertices = 20),
                    material = IsoColor(0.0, 200.0, 100.0)
                )
            }
        }
    }
}

/**
 * Sample 5: Conditional rendering
 */
@Composable
fun ConditionalSample() {
    var showPyramids by remember { mutableStateOf(true) }
    var showCylinders by remember { mutableStateOf(true) }
    var count by remember { mutableStateOf(3) }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Show Pyramids")
                    Switch(
                        checked = showPyramids,
                        onCheckedChange = { showPyramids = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Show Cylinders")
                    Switch(
                        checked = showCylinders,
                        onCheckedChange = { showCylinders = it }
                    )
                }

                Text("Count: $count")
                Slider(
                    value = count.toFloat(),
                    onValueChange = { count = it.toInt() },
                    valueRange = 1f..10f
                )
            }
        }

        IsometricScene(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Base always visible
            Shape(
                geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 8.0, depth = 8.0, height = 0.2),
                material = IsoColor(150.0, 150.0, 150.0)
            )

            // Conditional pyramids — positioned on the base
            If(showPyramids) {
                ForEach((0 until count).toList()) { i ->
                    Shape(
                        geometry = Pyramid(position = Point(1.0 + i * 1.5, 2.0, 0.2)),
                        material = IsoColor(255.0, 100.0 + i * 15.0, 0.0)
                    )
                }
            }

            // Conditional cylinders — positioned on the base
            If(showCylinders) {
                ForEach((0 until count).toList()) { i ->
                    Shape(
                        geometry = Cylinder(position = Point(1.0 + i * 1.5, 5.0, 0.2), radius = 0.4, height = 1.5, vertices = 20),
                        material = IsoColor(0.0, 150.0 + i * 10.0, 255.0)
                    )
                }
            }
        }
    }
}

/**
 * Sample 6: Performance with many shapes
 */
@Composable
fun PerformanceSample() {
    var gridSize by remember { mutableStateOf(5) }
    var animationEnabled by remember { mutableStateOf(false) }
    var wave by remember { mutableStateOf(0.0) }

    LaunchedEffect(animationEnabled) {
        var lastFrameNanos = 0L
        while (animationEnabled) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameNanos != 0L) {
                    val deltaSeconds = (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
                    wave += deltaSeconds * 2 * PI
                }
                lastFrameNanos = frameTimeNanos
            }
        }
    }

    val shapeCount = gridSize * gridSize

    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Grid Size: $gridSize x $gridSize ($shapeCount shapes)")
                Slider(
                    value = gridSize.toFloat(),
                    onValueChange = { gridSize = it.toInt() },
                    valueRange = 3f..15f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Animate")
                    Switch(
                        checked = animationEnabled,
                        onCheckedChange = { animationEnabled = it }
                    )
                }
            }
        }

        IsometricScene(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ForEach(
                items = (0 until gridSize).toList(),
                key = { it }
            ) { x ->
                ForEach(
                    items = (0 until gridSize).toList(),
                    key = { it }
                ) { y ->
                    val height = if (animationEnabled) {
                        1.0 + sin(wave + x * 0.5 + y * 0.5) * 0.5
                    } else {
                        1.0
                    }

                    Shape(
                        geometry = Prism(
                            position = Point(
                                (x - gridSize / 2).toDouble() * 1.2,
                                (y - gridSize / 2).toDouble() * 1.2,
                                0.0
                            ),
                            width = 1.0,
                            depth = 1.0,
                            height = height
                        ),
                        material = IsoColor(
                            (x.toDouble() / gridSize) * 255,
                            (y.toDouble() / gridSize) * 255,
                            150.0
                        )
                    )
                }
            }
        }
    }
}

