package io.github.jayteealao.isometric.webgpu

import android.content.Context
import android.os.Build
import android.view.Surface
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

        // G4: Read display refresh rate to use as a Surface.setFrameRate hint.
        // Frame pacing is provided by PresentMode.Fifo in Dawn: surface.present()
        // (mapped to vkQueuePresentKHR) blocks the GPU thread at each vsync boundary —
        // no additional delay() is needed or correct. The setFrameRate hint tells
        // SurfaceFlinger to maintain the target Hz for this surface, preventing LTPO
        // power-saving from dropping the refresh rate (e.g. Galaxy Z Fold 6 inner panel
        // dropping from 120 Hz to 60 Hz when no touch events are detected).
        val context = LocalContext.current
        val displayRefreshHz = remember(context) { context.displayRefreshRateHz() }

        AndroidExternalSurface(modifier = modifier) {
            onSurface { surface, width, height ->
                // Signal preferred frame rate to the compositor. API 30+ only.
                // FRAME_RATE_COMPATIBILITY_DEFAULT: general rendering, not a fixed source.
                // CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS: avoid a visible display mode switch.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    @Suppress("NewApi")
                    surface.setFrameRate(
                        displayRefreshHz,
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    )
                }
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

/**
 * Returns the display's current refresh rate in Hz.
 *
 * Used as the argument to [Surface.setFrameRate] to signal the compositor to maintain
 * the target rate for this surface. Falls back to 60 Hz if the rate cannot be determined.
 *
 * Note: this is a snapshot at composition time. For LTPO devices where the system may
 * dynamically vary refresh rate, [DisplayManager.DisplayListener] could be used to
 * track changes — deferred to Phase 3 if profiling reveals it is needed.
 */
@Suppress("DEPRECATION") // WindowManager.defaultDisplay deprecated at API 30; context.display added at API 30
private fun Context.displayRefreshRateHz(): Float {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.refreshRate ?: 60f
    } else {
        (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.refreshRate ?: 60f
    }
}
