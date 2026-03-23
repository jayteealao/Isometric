package io.github.jayteealao.isometric.webgpu

import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import io.github.jayteealao.isometric.compose.runtime.StrokeStyle
import io.github.jayteealao.isometric.compose.runtime.render.RenderBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class WebGpuRenderBackend : RenderBackend {
    @Composable
    override fun Surface(
        preparedScene: State<PreparedScene?>,
        renderContext: RenderContext,
        modifier: Modifier,
        strokeStyle: StrokeStyle,
    ) {
        val renderer = remember { WebGpuSceneRenderer() }
        val currentPreparedScene by rememberUpdatedState(preparedScene)

        AndroidExternalSurface(modifier = modifier) {
            onSurface { surface, width, height ->
                withContext(Dispatchers.Default) {
                    renderer.renderLoop(
                        androidSurface = surface,
                        surfaceWidth = width,
                        surfaceHeight = height,
                        preparedScene = currentPreparedScene,
                    )
                }
            }
        }
    }

    override fun toString(): String = "RenderBackend.WebGpu"
}
