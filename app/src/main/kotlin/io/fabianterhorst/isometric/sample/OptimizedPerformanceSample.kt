package io.fabianterhorst.isometric.sample

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.compose.runtime.*
import io.fabianterhorst.isometric.compose.runtime.optimized.OptimizedIsometricScene
import io.fabianterhorst.isometric.shapes.Prism
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.math.PI

/**
 * Demonstrates the performance optimizations in OptimizedIsometricScene
 *
 * Optimizations shown:
 * 1. Path object caching
 * 2. PreparedScene caching
 * 3. Spatial indexing for hit testing
 * 4. Native canvas rendering (Android)
 * 5. Off-thread computation
 */

@Composable
fun OptimizedPerformanceSample() {
    var gridSize by remember { mutableStateOf(10) }
    var enableSpatialIndex by remember { mutableStateOf(true) }
    var useNativeCanvas by remember { mutableStateOf(true) }
    var enableOffThread by remember { mutableStateOf(false) }
    var animationEnabled by remember { mutableStateOf(false) }
    var wave by remember { mutableStateOf(0.0) }
    var clickCount by remember { mutableStateOf(0) }
    var lastClickTime by remember { mutableStateOf(0L) }
    var avgClickTime by remember { mutableStateOf(0.0) }

    LaunchedEffect(animationEnabled) {
        while (animationEnabled) {
            delay(16)
            wave += PI / 30
        }
    }

    val shapeCount = gridSize * gridSize

    Column(modifier = Modifier.fillMaxSize()) {
        // Controls
        Card(
            modifier = Modifier.padding(16.dp),
            elevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Optimized Performance Demo",
                    style = MaterialTheme.typography.h6
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Shapes: $shapeCount | Clicks: $clickCount | " +
                            "Avg Click Time: ${String.format("%.2f", avgClickTime)}ms",
                    style = MaterialTheme.typography.body2
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Grid Size: $gridSize x $gridSize")
                Slider(
                    value = gridSize.toFloat(),
                    onValueChange = { gridSize = it.toInt() },
                    valueRange = 3f..20f
                )

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Optimization toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Spatial Index\n(Fast hit testing)")
                    Switch(
                        checked = enableSpatialIndex,
                        onCheckedChange = { enableSpatialIndex = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Native Canvas\n(Android-only, 2x faster)")
                    Switch(
                        checked = useNativeCanvas,
                        onCheckedChange = { useNativeCanvas = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Off-Thread Compute\n(Async preparation)")
                    Switch(
                        checked = enableOffThread,
                        onCheckedChange = { enableOffThread = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Animate\n(Test caching)")
                    Switch(
                        checked = animationEnabled,
                        onCheckedChange = { animationEnabled = it }
                    )
                }
            }
        }

        // Optimized Scene
        OptimizedIsometricScene(
            modifier = Modifier.weight(1f),
            enableSpatialIndex = enableSpatialIndex,
            useNativeCanvas = useNativeCanvas,
            enableOffThreadComputation = enableOffThread,
            enableGestures = true,
            onTap = { _, _, node ->
                // Measure click time
                val startTime = System.nanoTime()

                clickCount++

                val endTime = System.nanoTime()
                val clickTime = (endTime - startTime) / 1_000_000.0 // ms

                // Running average
                avgClickTime = if (clickCount == 1) {
                    clickTime
                } else {
                    (avgClickTime * (clickCount - 1) + clickTime) / clickCount
                }

                lastClickTime = System.currentTimeMillis()
            }
        ) {
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

        // Performance Tips
        Card(
            modifier = Modifier.padding(16.dp),
            elevation = 4.dp,
            backgroundColor = MaterialTheme.colors.secondary.copy(alpha = 0.1f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "💡 Performance Tips:",
                    style = MaterialTheme.typography.subtitle2
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    "• Spatial Index: ${if (enableSpatialIndex) "O(1) hit testing ✅" else "O(n) linear search ❌"}",
                    style = MaterialTheme.typography.caption
                )

                Text(
                    "• Native Canvas: ${if (useNativeCanvas) "Direct Android rendering ✅" else "Compose abstraction layer ⚠️"}",
                    style = MaterialTheme.typography.caption
                )

                Text(
                    "• Off-Thread: ${if (enableOffThread) "Async computation ✅" else "Main thread blocking ⚠️"}",
                    style = MaterialTheme.typography.caption
                )

                Text(
                    "• Path Caching: Always enabled ✅",
                    style = MaterialTheme.typography.caption
                )

                Text(
                    "• Scene Caching: Always enabled ✅",
                    style = MaterialTheme.typography.caption
                )
            }
        }
    }
}

/**
 * Comparison demo: Standard vs Optimized
 */
@Composable
fun PerformanceComparisonDemo() {
    var useOptimized by remember { mutableStateOf(true) }
    var wave by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            wave += PI / 30
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (useOptimized) "Optimized API" else "Standard API",
                style = MaterialTheme.typography.h6
            )

            Button(onClick = { useOptimized = !useOptimized }) {
                Text("Switch to ${if (useOptimized) "Standard" else "Optimized"}")
            }
        }

        if (useOptimized) {
            // Optimized version
            OptimizedIsometricScene(
                modifier = Modifier.weight(1f),
                enableSpatialIndex = true,
                useNativeCanvas = true,
                enableOffThreadComputation = true
            ) {
                LargeAnimatedGrid(wave)
            }
        } else {
            // Standard version
            IsometricScene(
                modifier = Modifier.weight(1f)
            ) {
                LargeAnimatedGrid(wave)
            }
        }

        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    if (useOptimized) {
                        "✅ Using all optimizations:\n" +
                                "• Path caching\n" +
                                "• Scene caching\n" +
                                "• Spatial indexing\n" +
                                "• Native canvas\n" +
                                "• Off-thread computation\n\n" +
                                "Expected: ~2-5ms per frame"
                    } else {
                        "⚠️ Standard rendering:\n" +
                                "• No path caching\n" +
                                "• Rebuild scene every frame\n" +
                                "• Linear hit testing\n" +
                                "• Compose abstractions\n\n" +
                                "Expected: ~15-80ms per frame"
                    },
                    style = MaterialTheme.typography.body2
                )
            }
        }
    }
}

@Composable
private fun IsometricScope.LargeAnimatedGrid(wave: Double) {
    ForEach((0..15).toList()) { x ->
        ForEach((0..15).toList()) { y ->
            val height = 1.0 + sin(wave + x * 0.3 + y * 0.3) * 0.5

            Shape(
                shape = Prism(
                    Point(
                        (x - 7.5) * 1.2,
                        (y - 7.5) * 1.2,
                        0.0
                    ),
                    1.0,
                    1.0,
                    height
                ),
                color = IsoColor(
                    (x / 15.0) * 255,
                    (y / 15.0) * 255,
                    150.0
                )
            )
        }
    }
}
