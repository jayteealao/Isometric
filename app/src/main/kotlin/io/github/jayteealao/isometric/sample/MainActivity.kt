package io.github.jayteealao.isometric.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SampleChooser(
                    onSelect = { startActivity(it) }
                )
            }
        }
    }

    @Composable
    private fun SampleChooser(onSelect: (Intent) -> Unit) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("Isometric Samples") })
        }) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SampleCard(
                    title = "View API",
                    description = "Legacy IsometricView with XML layout: classic scene rendering",
                    onClick = { onSelect(Intent(this@MainActivity, ViewSampleActivity::class.java)) }
                )
                SampleCard(
                    title = "Compose Scene API",
                    description = "IsometricScene samples: shapes, animation, and interaction",
                    onClick = { onSelect(Intent(this@MainActivity, ComposeActivity::class.java)) }
                )
                SampleCard(
                    title = "Runtime API",
                    description = "Declarative ComposeNode samples: hierarchy, gestures, conditional rendering, performance",
                    onClick = { onSelect(Intent(this@MainActivity, RuntimeApiActivity::class.java)) }
                )
                SampleCard(
                    title = "WebGPU",
                    description = "RenderMode.WebGpu and RenderMode.Canvas(Compute.WebGpu) samples",
                    onClick = { onSelect(Intent(this@MainActivity, WebGpuSampleActivity::class.java)) }
                )
                SampleCard(
                    title = "Interaction API",
                    description = "Per-node interaction: onClick, onLongClick, alpha transparency, stable nodeId, testTag",
                    onClick = { onSelect(Intent(this@MainActivity, InteractionSamplesActivity::class.java)) }
                )
                SampleCard(
                    title = "Textured Materials",
                    description = "Per-face BitmapShader (grass top, dirt sides) in Canvas and WebGPU modes",
                    onClick = { onSelect(Intent(this@MainActivity, TexturedDemoActivity::class.java)) }
                )
            }
        }
    }
}

@Composable
private fun SampleCard(title: String, description: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.body2)
        }
    }
}
