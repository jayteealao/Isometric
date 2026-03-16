package io.fabianterhorst.isometric.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.fabianterhorst.isometric.*
import io.fabianterhorst.isometric.compose.runtime.GestureConfig
import io.fabianterhorst.isometric.compose.runtime.IsometricScene
import io.fabianterhorst.isometric.compose.runtime.SceneConfig
import io.fabianterhorst.isometric.compose.runtime.Shape
import io.fabianterhorst.isometric.compose.runtime.TapEvent
import io.fabianterhorst.isometric.shapes.*
import kotlin.math.PI

class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    IsometricSamplesScreen()
                }
            }
        }
    }
}

@Composable
fun IsometricSamplesScreen() {
    var selectedSample by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sample selector
        ScrollableTabRow(
            selectedTabIndex = selectedSample,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = selectedSample == 0,
                onClick = { selectedSample = 0 },
                text = { Text("Simple Cube") }
            )
            Tab(
                selected = selectedSample == 1,
                onClick = { selectedSample = 1 },
                text = { Text("Multiple Shapes") }
            )
            Tab(
                selected = selectedSample == 2,
                onClick = { selectedSample = 2 },
                text = { Text("Complex Scene") }
            )
            Tab(
                selected = selectedSample == 3,
                onClick = { selectedSample = 3 },
                text = { Text("Animated") }
            )
            Tab(
                selected = selectedSample == 4,
                onClick = { selectedSample = 4 },
                text = { Text("Interactive") }
            )
        }

        // Sample content
        Box(modifier = Modifier.weight(1f)) {
            when (selectedSample) {
                0 -> SimpleCubeSample()
                1 -> MultipleShapesSample()
                2 -> ComplexSceneSample()
                3 -> AnimatedSample()
                4 -> InteractiveSample()
            }
        }
    }
}

@Composable
fun SimpleCubeSample() {
    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0)), color = IsoColor(33.0, 150.0, 243.0))
    }
}

@Composable
fun MultipleShapesSample() {
    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 4.0, depth = 4.0, height = 2.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(-1.0, 1.0, 0.0), width = 1.0, depth = 2.0, height = 1.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(1.0, -1.0, 0.0), width = 2.0, depth = 1.0, height = 1.0), color = IsoColor(33.0, 150.0, 243.0))
    }
}

@Composable
fun ComplexSceneSample() {
    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Shape(geometry = Prism(position = Point(1.0, -1.0, 0.0), width = 4.0, depth = 5.0, height = 2.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 1.0, depth = 4.0, height = 1.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(-1.0, 1.0, 0.0), width = 1.0, depth = 3.0, height = 1.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Stairs(position = Point(-1.0, 0.0, 0.0), stepCount = 10), color = IsoColor(33.0, 150.0, 243.0))
        Shape(
            geometry = Stairs(position = Point(0.0, 3.0, 1.0), stepCount = 10).rotateZ(Point(0.5, 3.5, 1.0), -PI / 2),
            color = IsoColor(33.0, 150.0, 243.0)
        )
        Shape(geometry = Prism(position = Point(3.0, 0.0, 2.0), width = 2.0, depth = 4.0, height = 1.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(2.0, 1.0, 2.0), width = 1.0, depth = 3.0, height = 1.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(
            geometry = Stairs(position = Point(2.0, 0.0, 2.0), stepCount = 10).rotateZ(Point(2.5, 0.5, 0.0), -PI / 2),
            color = IsoColor(33.0, 150.0, 243.0)
        )
        Shape(geometry = Pyramid(position = Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), color = IsoColor(180.0, 180.0, 0.0))
        Shape(geometry = Pyramid(position = Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), color = IsoColor(180.0, 0.0, 180.0))
        Shape(geometry = Pyramid(position = Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), color = IsoColor(0.0, 180.0, 180.0))
        Shape(geometry = Pyramid(position = Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), color = IsoColor(40.0, 180.0, 40.0))
        Shape(geometry = Prism(position = Point(3.0, 2.0, 3.0), width = 1.0, depth = 1.0, height = 0.2), color = IsoColor(50.0, 50.0, 50.0))
        Shape(
            geometry = Octahedron(position = Point(3.0, 2.0, 3.2)).rotateZ(Point(3.5, 2.5, 0.0), 0.0),
            color = IsoColor(0.0, 180.0, 180.0)
        )
    }
}

@Composable
fun AnimatedSample() {
    var angle by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                angle += PI / 90
            }
        }
    }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Shape(geometry = Prism(position = Point(1.0, -1.0, 0.0), width = 4.0, depth = 5.0, height = 2.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 1.0, depth = 4.0, height = 1.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(-1.0, 1.0, 0.0), width = 1.0, depth = 3.0, height = 1.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Stairs(position = Point(-1.0, 0.0, 0.0), stepCount = 10), color = IsoColor(33.0, 150.0, 243.0))
        Shape(
            geometry = Stairs(position = Point(0.0, 3.0, 1.0), stepCount = 10).rotateZ(Point(0.5, 3.5, 1.0), -PI / 2),
            color = IsoColor(33.0, 150.0, 243.0)
        )
        Shape(geometry = Prism(position = Point(3.0, 0.0, 2.0), width = 2.0, depth = 4.0, height = 1.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(2.0, 1.0, 2.0), width = 1.0, depth = 3.0, height = 1.0), color = IsoColor(33.0, 150.0, 243.0))
        Shape(
            geometry = Stairs(position = Point(2.0, 0.0, 2.0), stepCount = 10).rotateZ(Point(2.5, 0.5, 0.0), -PI / 2),
            color = IsoColor(33.0, 150.0, 243.0)
        )
        Shape(geometry = Pyramid(position = Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), color = IsoColor(180.0, 180.0, 0.0))
        Shape(geometry = Pyramid(position = Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), color = IsoColor(180.0, 0.0, 180.0))
        Shape(geometry = Pyramid(position = Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), color = IsoColor(0.0, 180.0, 180.0))
        Shape(geometry = Pyramid(position = Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), color = IsoColor(40.0, 180.0, 40.0))
        Shape(geometry = Prism(position = Point(3.0, 2.0, 3.0), width = 1.0, depth = 1.0, height = 0.2), color = IsoColor(50.0, 50.0, 50.0))
        Shape(
            geometry = Octahedron(position = Point(3.0, 2.0, 3.2)).rotateZ(Point(3.5, 2.5, 0.0), angle),
            color = IsoColor(0.0, 180.0, 180.0)
        )
    }
}

@Composable
fun InteractiveSample() {
    var clickedItem by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (clickedItem != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                backgroundColor = MaterialTheme.colors.primary
            ) {
                Text(
                    "Clicked: $clickedItem",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colors.onPrimary
                )
            }
        }

        IsometricScene(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            config = SceneConfig(
                gestures = GestureConfig(
                    onTap = { event: TapEvent ->
                        clickedItem = event.node?.nodeId
                    }
                )
            )
        ) {
            Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0)), color = IsoColor(33.0, 150.0, 243.0))
            Shape(geometry = Pyramid(position = Point(2.0, 0.0, 0.0)), color = IsoColor(255.0, 100.0, 0.0))
            Shape(geometry = Cylinder(position = Point(-2.0, 0.0, 0.0), radius = 0.5, height = 2.0, vertices = 20), color = IsoColor(0.0, 200.0, 100.0))
        }
    }
}
