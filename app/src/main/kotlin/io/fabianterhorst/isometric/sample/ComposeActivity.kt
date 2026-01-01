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
import io.fabianterhorst.isometric.compose.IsometricCanvas
import io.fabianterhorst.isometric.compose.rememberIsometricSceneState
import io.fabianterhorst.isometric.shapes.*
import kotlinx.coroutines.delay
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
    val sceneState = rememberIsometricSceneState()

    IsometricCanvas(state = sceneState) {
        add(Prism(Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
    }
}

@Composable
fun MultipleShapesSample() {
    val sceneState = rememberIsometricSceneState()

    IsometricCanvas(state = sceneState) {
        add(Prism(Point(0.0, 0.0, 0.0), 4.0, 4.0, 2.0), IsoColor(33.0, 150.0, 243.0))
        add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 2.0, 1.0), IsoColor(33.0, 150.0, 243.0))
        add(Prism(Point(1.0, -1.0, 0.0), 2.0, 1.0, 1.0), IsoColor(33.0, 150.0, 243.0))
    }
}

@Composable
fun ComplexSceneSample() {
    val sceneState = rememberIsometricSceneState()

    IsometricCanvas(state = sceneState) {
        add(Prism(Point(1.0, -1.0, 0.0), 4.0, 5.0, 2.0), IsoColor(33.0, 150.0, 243.0))
        add(Prism(Point(0.0, 0.0, 0.0), 1.0, 4.0, 1.0), IsoColor(33.0, 150.0, 243.0))
        add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 3.0, 1.0), IsoColor(33.0, 150.0, 243.0))
        add(Stairs(Point(-1.0, 0.0, 0.0), 10), IsoColor(33.0, 150.0, 243.0))
        add(
            Stairs(Point(0.0, 3.0, 1.0), 10).rotateZ(Point(0.5, 3.5, 1.0), -PI / 2),
            IsoColor(33.0, 150.0, 243.0)
        )
        add(Prism(Point(3.0, 0.0, 2.0), 2.0, 4.0, 1.0), IsoColor(33.0, 150.0, 243.0))
        add(Prism(Point(2.0, 1.0, 2.0), 1.0, 3.0, 1.0), IsoColor(33.0, 150.0, 243.0))
        add(
            Stairs(Point(2.0, 0.0, 2.0), 10).rotateZ(Point(2.5, 0.5, 0.0), -PI / 2),
            IsoColor(33.0, 150.0, 243.0)
        )
        add(Pyramid(Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), IsoColor(180.0, 180.0, 0.0))
        add(Pyramid(Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), IsoColor(180.0, 0.0, 180.0))
        add(Pyramid(Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), IsoColor(0.0, 180.0, 180.0))
        add(Pyramid(Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), IsoColor(40.0, 180.0, 40.0))
        add(Prism(Point(3.0, 2.0, 3.0), 1.0, 1.0, 0.2), IsoColor(50.0, 50.0, 50.0))
        add(
            Octahedron(Point(3.0, 2.0, 3.2)).rotateZ(Point(3.5, 2.5, 0.0), 0.0),
            IsoColor(0.0, 180.0, 180.0)
        )
    }
}

@Composable
fun AnimatedSample() {
    val sceneState = rememberIsometricSceneState()
    var angle by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16) // ~60fps
            angle += PI / 90
        }
    }

    LaunchedEffect(angle) {
        sceneState.clear()
        sceneState.add(Prism(Point(1.0, -1.0, 0.0), 4.0, 5.0, 2.0), IsoColor(33.0, 150.0, 243.0))
        sceneState.add(Prism(Point(0.0, 0.0, 0.0), 1.0, 4.0, 1.0), IsoColor(33.0, 150.0, 243.0))
        sceneState.add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 3.0, 1.0), IsoColor(33.0, 150.0, 243.0))
        sceneState.add(Stairs(Point(-1.0, 0.0, 0.0), 10), IsoColor(33.0, 150.0, 243.0))
        sceneState.add(
            Stairs(Point(0.0, 3.0, 1.0), 10).rotateZ(Point(0.5, 3.5, 1.0), -PI / 2),
            IsoColor(33.0, 150.0, 243.0)
        )
        sceneState.add(Prism(Point(3.0, 0.0, 2.0), 2.0, 4.0, 1.0), IsoColor(33.0, 150.0, 243.0))
        sceneState.add(Prism(Point(2.0, 1.0, 2.0), 1.0, 3.0, 1.0), IsoColor(33.0, 150.0, 243.0))
        sceneState.add(
            Stairs(Point(2.0, 0.0, 2.0), 10).rotateZ(Point(2.5, 0.5, 0.0), -PI / 2),
            IsoColor(33.0, 150.0, 243.0)
        )
        sceneState.add(Pyramid(Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), IsoColor(180.0, 180.0, 0.0))
        sceneState.add(Pyramid(Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), IsoColor(180.0, 0.0, 180.0))
        sceneState.add(Pyramid(Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), IsoColor(0.0, 180.0, 180.0))
        sceneState.add(Pyramid(Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), IsoColor(40.0, 180.0, 40.0))
        sceneState.add(Prism(Point(3.0, 2.0, 3.0), 1.0, 1.0, 0.2), IsoColor(50.0, 50.0, 50.0))
        sceneState.add(
            Octahedron(Point(3.0, 2.0, 3.2)).rotateZ(Point(3.5, 2.5, 0.0), angle),
            IsoColor(0.0, 180.0, 180.0)
        )
    }

    IsometricCanvas(state = sceneState)
}

@Composable
fun InteractiveSample() {
    val sceneState = rememberIsometricSceneState()
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

        IsometricCanvas(
            state = sceneState,
            modifier = Modifier.weight(1f),
            onItemClick = { item ->
                clickedItem = item.id
            }
        ) {
            add(Prism(Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
            add(Pyramid(Point(2.0, 0.0, 0.0)), IsoColor(255.0, 100.0, 0.0))
            add(Cylinder(Point(-2.0, 0.0, 0.0), 0.5, 20, 2.0), IsoColor(0.0, 200.0, 100.0))
        }
    }
}
