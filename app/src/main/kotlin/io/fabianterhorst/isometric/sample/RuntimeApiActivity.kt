package io.fabianterhorst.isometric.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.compose.runtime.*
import io.fabianterhorst.isometric.shapes.*
import kotlinx.coroutines.delay
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
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedSample) {
                0 -> SimpleSample()
                1 -> HierarchySample()
                2 -> AnimationSample()
                3 -> InteractiveSample()
                4 -> ConditionalSample()
                5 -> PerformanceSample()
            }
        }
    }
}

/**
 * Sample 1: Simple shapes using the new API
 */
@Composable
fun SimpleSample() {
    IsometricScene {
        Shape(
            shape = Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 2.0),
            color = IsoColor(33.0, 150.0, 243.0)
        )

        Shape(
            shape = Pyramid(Point(3.0, 0.0, 0.0)),
            color = IsoColor(255.0, 100.0, 0.0)
        )

        Shape(
            shape = Cylinder(Point(-3.0, 0.0, 0.0), 0.5, 20, 2.0),
            color = IsoColor(0.0, 200.0, 100.0)
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
        while (true) {
            delay(16)
            groupRotation += PI / 180
        }
    }

    IsometricScene {
        // Static base
        Shape(
            shape = Prism(Point(0.0, 0.0, 0.0), 4.0, 4.0, 0.5),
            color = IsoColor(100.0, 100.0, 100.0)
        )

        // Rotating group
        Group(
            position = Point(0.0, 0.0, 0.5),
            rotation = groupRotation,
            rotationOrigin = Point(0.0, 0.0, 0.5)
        ) {
            // These all rotate together
            Shape(
                shape = Prism(Point(-1.5, 0.0, 0.0), 1.0, 1.0, 2.0),
                color = IsoColor(255.0, 0.0, 0.0)
            )

            Shape(
                shape = Prism(Point(1.5, 0.0, 0.0), 1.0, 1.0, 2.0),
                color = IsoColor(0.0, 255.0, 0.0)
            )

            Shape(
                shape = Prism(Point(0.0, -1.5, 0.0), 1.0, 1.0, 2.0),
                color = IsoColor(0.0, 0.0, 255.0)
            )

            Shape(
                shape = Prism(Point(0.0, 1.5, 0.0), 1.0, 1.0, 2.0),
                color = IsoColor(255.0, 255.0, 0.0)
            )

            // Nested group with additional rotation
            Group(
                position = Point(0.0, 0.0, 2.0),
                rotation = -groupRotation * 2,
                rotationOrigin = Point(0.0, 0.0, 2.0)
            ) {
                Shape(
                    shape = Octahedron(Point(0.0, 0.0, 0.0)),
                    color = IsoColor(255.0, 0.0, 255.0)
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
        while (true) {
            delay(16)
            angle += PI / 90
            wave += PI / 60
        }
    }

    IsometricScene {
        // Static scene (never recomposes)
        Shape(
            shape = Prism(Point(1.0, -1.0, 0.0), 4.0, 5.0, 2.0),
            color = IsoColor(33.0, 150.0, 243.0)
        )

        Shape(
            shape = Prism(Point(0.0, 0.0, 0.0), 1.0, 4.0, 1.0),
            color = IsoColor(33.0, 150.0, 243.0)
        )

        Shape(
            shape = Stairs(Point(-1.0, 0.0, 0.0), 10),
            color = IsoColor(33.0, 150.0, 243.0)
        )

        // Animated group (only this recomposes!)
        Group(
            position = Point(3.0, 2.0, 3.0),
            rotation = angle,
            rotationOrigin = Point(3.5, 2.5, 3.0)
        ) {
            Shape(
                shape = Octahedron(Point(0.0, 0.0, 0.0)),
                color = IsoColor(0.0, 180.0, 180.0)
            )
        }

        // Animated tower
        Group(position = Point(-3.0, 0.0, 0.0)) {
            ForEach((0..5).toList()) { i ->
                Shape(
                    shape = Prism(
                        Point(0.0, 0.0, i.toDouble()),
                        1.0,
                        1.0,
                        1.0
                    ),
                    color = IsoColor(
                        255.0 * sin(wave + i * PI / 3),
                        150.0,
                        255.0 * cos(wave + i * PI / 3)
                    )
                )
            }
        }
    }
}

/**
 * Sample 4: Interactive with gesture handling
 */
@Composable
fun InteractiveSample() {
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
            modifier = Modifier.weight(1f),
            enableGestures = true,
            onTap = { x, y, node ->
                tappedNode = node?.let { "Node ${it.nodeId}" } ?: "Background"
            },
            onDrag = { deltaX, deltaY ->
                dragOffset = Point(
                    dragOffset.x + deltaX / 50.0,
                    dragOffset.y - deltaY / 50.0,
                    dragOffset.z
                )
            }
        ) {
            Group(position = dragOffset) {
                Shape(
                    shape = Prism(Point(0.0, 0.0, 0.0)),
                    color = IsoColor(33.0, 150.0, 243.0)
                )

                Shape(
                    shape = Pyramid(Point(2.0, 0.0, 0.0)),
                    color = IsoColor(255.0, 100.0, 0.0)
                )

                Shape(
                    shape = Cylinder(Point(-2.0, 0.0, 0.0), 0.5, 20, 2.0),
                    color = IsoColor(0.0, 200.0, 100.0)
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

        IsometricScene(modifier = Modifier.weight(1f)) {
            // Base always visible
            Shape(
                shape = Prism(Point(0.0, 0.0, 0.0), 8.0, 8.0, 0.2),
                color = IsoColor(150.0, 150.0, 150.0)
            )

            // Conditional pyramids
            If(showPyramids) {
                ForEach((0 until count).toList()) { i ->
                    Shape(
                        shape = Pyramid(Point(-3.0 + i.toDouble(), 2.0, 0.2)),
                        color = IsoColor(255.0, 100.0 + i * 15.0, 0.0)
                    )
                }
            }

            // Conditional cylinders
            If(showCylinders) {
                ForEach((0 until count).toList()) { i ->
                    Shape(
                        shape = Cylinder(Point(-3.0 + i.toDouble(), -2.0, 0.2), 0.4, 20, 1.5),
                        color = IsoColor(0.0, 150.0 + i * 10.0, 255.0)
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
        while (animationEnabled) {
            delay(16)
            wave += PI / 30
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

        IsometricScene(modifier = Modifier.weight(1f)) {
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
                        shape = Prism(
                            Point(
                                (x - gridSize / 2).toDouble() * 1.2,
                                (y - gridSize / 2).toDouble() * 1.2,
                                0.0
                            ),
                            1.0,
                            1.0,
                            height
                        ),
                        color = IsoColor(
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
