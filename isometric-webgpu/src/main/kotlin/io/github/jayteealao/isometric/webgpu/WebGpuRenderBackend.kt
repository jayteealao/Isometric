package io.github.jayteealao.isometric.webgpu

import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

        // F3: Track renderContext dimensions as State so the renderLoop can observe changes
        // and reconfigure the GPU surface on window/fold resize without waiting for
        // AndroidExternalSurface to destroy and recreate the surface.
        val contextWidth = remember { mutableIntStateOf(renderContext.width) }
        val contextHeight = remember { mutableIntStateOf(renderContext.height) }
        contextWidth.intValue = renderContext.width
        contextHeight.intValue = renderContext.height

        AndroidExternalSurface(modifier = modifier) {
            onSurface { surface, width, height ->
                withContext(Dispatchers.Default) {
                    renderer.renderLoop(
                        androidSurface = surface,
                        surfaceWidth = width,
                        surfaceHeight = height,
                        preparedScene = currentPreparedScene,
                        renderContextWidth = contextWidth,
                        renderContextHeight = contextHeight,
                    )
                }
            }
        }
    }

    override fun toString(): String = "RenderBackend.WebGpu"
}
