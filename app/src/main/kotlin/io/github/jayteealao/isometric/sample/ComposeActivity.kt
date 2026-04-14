package io.github.jayteealao.isometric.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jayteealao.isometric.*
import io.github.jayteealao.isometric.compose.runtime.GestureConfig
import io.github.jayteealao.isometric.compose.runtime.IsometricScene
import io.github.jayteealao.isometric.compose.runtime.SceneConfig
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.compose.runtime.TapEvent
import android.graphics.Bitmap
import io.github.jayteealao.isometric.shader.*
import io.github.jayteealao.isometric.shader.Shape as MaterialShape
import io.github.jayteealao.isometric.shader.render.ProvideTextureRendering
import io.github.jayteealao.isometric.shader.texturedBitmap
import io.github.jayteealao.isometric.shapes.*
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
            Tab(
                selected = selectedSample == 5,
                onClick = { selectedSample = 5 },
                text = { Text("Textured") }
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
                5 -> TexturedSample()
            }
        }
    }
}

@Composable
fun SimpleCubeSample() {
    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0)))
    }
}

@Composable
fun MultipleShapesSample() {
    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 4.0, depth = 4.0, height = 2.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(-1.0, 1.0, 0.0), width = 1.0, depth = 2.0, height = 1.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(1.0, -1.0, 0.0), width = 2.0, depth = 1.0, height = 1.0), material = IsoColor(33.0, 150.0, 243.0))
    }
}

@Composable
fun ComplexSceneSample() {
    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Shape(geometry = Prism(position = Point(1.0, -1.0, 0.0), width = 4.0, depth = 5.0, height = 2.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 1.0, depth = 4.0, height = 1.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(-1.0, 1.0, 0.0), width = 1.0, depth = 3.0, height = 1.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Stairs(position = Point(-1.0, 0.0, 0.0), stepCount = 10), material = IsoColor(33.0, 150.0, 243.0))
        Shape(
            geometry = Stairs(position = Point(0.0, 3.0, 1.0), stepCount = 10).rotateZ(Point(0.5, 3.5, 1.0), -PI / 2),
            material = IsoColor(33.0, 150.0, 243.0)
        )
        Shape(geometry = Prism(position = Point(3.0, 0.0, 2.0), width = 2.0, depth = 4.0, height = 1.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(2.0, 1.0, 2.0), width = 1.0, depth = 3.0, height = 1.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(
            geometry = Stairs(position = Point(2.0, 0.0, 2.0), stepCount = 10).rotateZ(Point(2.5, 0.5, 0.0), -PI / 2),
            material = IsoColor(33.0, 150.0, 243.0)
        )
        Shape(geometry = Pyramid(position = Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), material = IsoColor(180.0, 180.0, 0.0))
        Shape(geometry = Pyramid(position = Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), material = IsoColor(180.0, 0.0, 180.0))
        Shape(geometry = Pyramid(position = Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), material = IsoColor(0.0, 180.0, 180.0))
        Shape(geometry = Pyramid(position = Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), material = IsoColor(40.0, 180.0, 40.0))
        Shape(geometry = Prism(position = Point(3.0, 2.0, 3.0), width = 1.0, depth = 1.0, height = 0.2), material = IsoColor(50.0, 50.0, 50.0))
        Shape(
            geometry = Octahedron(position = Point(3.0, 2.0, 3.2)).rotateZ(Point(3.5, 2.5, 0.0), 0.0),
            material = IsoColor(0.0, 180.0, 180.0)
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
        Shape(geometry = Prism(position = Point(1.0, -1.0, 0.0), width = 4.0, depth = 5.0, height = 2.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 1.0, depth = 4.0, height = 1.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(-1.0, 1.0, 0.0), width = 1.0, depth = 3.0, height = 1.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Stairs(position = Point(-1.0, 0.0, 0.0), stepCount = 10), material = IsoColor(33.0, 150.0, 243.0))
        Shape(
            geometry = Stairs(position = Point(0.0, 3.0, 1.0), stepCount = 10).rotateZ(Point(0.5, 3.5, 1.0), -PI / 2),
            material = IsoColor(33.0, 150.0, 243.0)
        )
        Shape(geometry = Prism(position = Point(3.0, 0.0, 2.0), width = 2.0, depth = 4.0, height = 1.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(geometry = Prism(position = Point(2.0, 1.0, 2.0), width = 1.0, depth = 3.0, height = 1.0), material = IsoColor(33.0, 150.0, 243.0))
        Shape(
            geometry = Stairs(position = Point(2.0, 0.0, 2.0), stepCount = 10).rotateZ(Point(2.5, 0.5, 0.0), -PI / 2),
            material = IsoColor(33.0, 150.0, 243.0)
        )
        Shape(geometry = Pyramid(position = Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), material = IsoColor(180.0, 180.0, 0.0))
        Shape(geometry = Pyramid(position = Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), material = IsoColor(180.0, 0.0, 180.0))
        Shape(geometry = Pyramid(position = Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), material = IsoColor(0.0, 180.0, 180.0))
        Shape(geometry = Pyramid(position = Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), material = IsoColor(40.0, 180.0, 40.0))
        Shape(geometry = Prism(position = Point(3.0, 2.0, 3.0), width = 1.0, depth = 1.0, height = 0.2), material = IsoColor(50.0, 50.0, 50.0))
        Shape(
            geometry = Octahedron(position = Point(3.0, 2.0, 3.2)).rotateZ(Point(3.5, 2.5, 0.0), angle),
            material = IsoColor(0.0, 180.0, 180.0)
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
            Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0)), material = IsoColor(33.0, 150.0, 243.0))
            Shape(geometry = Pyramid(position = Point(2.0, 0.0, 0.0)), material = IsoColor(255.0, 100.0, 0.0))
            Shape(geometry = Cylinder(position = Point(-2.0, 0.0, 0.0), radius = 0.5, height = 2.0, vertices = 20), material = IsoColor(0.0, 200.0, 100.0))
        }
    }
}

@Composable
fun TexturedSample() {
    val checkerboard = remember {
        val size = 16
        val cellSize = 8
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val isMagenta = ((x / cellSize) + (y / cellSize)) % 2 == 0
                pixels[y * size + x] = if (isMagenta) 0xFFFF00FF.toInt() else 0xFF000000.toInt()
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    ProvideTextureRendering {
        IsometricScene(modifier = Modifier.fillMaxSize()) {
            // Textured prism (checkerboard)
            MaterialShape(
                geometry = Prism(position = Point(0.0, 0.0, 0.0)),
                material = texturedBitmap(checkerboard),
            )
            // Flat-color prism for comparison (backward compat)
            Shape(
                geometry = Prism(position = Point(2.0, 0.0, 0.0)),
                material = IsoColor(33.0, 150.0, 243.0),
            )
            // Another textured prism (cache reuse)
            MaterialShape(
                geometry = Prism(position = Point(4.0, 0.0, 0.0)),
                material = texturedBitmap(checkerboard),
            )
        }
    }
}
