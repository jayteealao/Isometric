package io.github.jayteealao.isometric.webgpu

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

        // G4: Compute frame delay from the display refresh rate so the render loop paces
        // correctly on 90 Hz / 120 Hz devices instead of always targeting 60 fps.
        // Results: 60 Hz → 16 ms, 90 Hz → 11 ms, 120 Hz → 8 ms.
        // Uses WindowManager.defaultDisplay (deprecated API 30 but available since API 1)
        // as the fallback for minSdk 24; on API 30+ we use context.display directly.
        val context = LocalContext.current
        val frameDelayMs = remember(context) { context.displayFrameDelayMs() }

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
                        frameDelayMs = frameDelayMs,
                    )
                }
            }
        }
    }

    override fun toString(): String = "RenderBackend.WebGpu"
}

/**
 * Returns the target frame delay in milliseconds based on the display's refresh rate.
 *
 * Clamps to [8, 32] ms (125 Hz – 31.25 Hz) to guard against exotic reported rates.
 * Falls back to 16 ms if the refresh rate cannot be determined.
 */
@Suppress("DEPRECATION") // WindowManager.defaultDisplay deprecated at API 30; context.display added at API 30
private fun Context.displayFrameDelayMs(): Long {
    val refreshRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.refreshRate ?: 60f
    } else {
        (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.refreshRate ?: 60f
    }
    return (1_000f / refreshRate).toLong().coerceIn(8L, 32L)
}
