package io.github.jayteealao.isometric.webgpu

import android.content.Context
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.compose.runtime.LocalWebGpuFrameCallback
import io.github.jayteealao.isometric.compose.runtime.LocalWebGpuVsync
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import io.github.jayteealao.isometric.compose.runtime.StrokeStyle
import io.github.jayteealao.isometric.compose.runtime.render.RenderBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

internal class WebGpuRenderBackend : RenderBackend {
    @Composable
    override fun Surface(
        preparedScene: State<PreparedScene?>,
        renderContext: RenderContext,
        modifier: Modifier,
        strokeStyle: StrokeStyle,
    ) {
        val renderer = remember { WebGpuSceneRenderer() }
        val frameCallback = LocalWebGpuFrameCallback.current
        val vsync = LocalWebGpuVsync.current

        // R-03: Bridge Compose State → AtomicReference for thread-safe off-thread reads.
        // The render loop runs on Dispatchers.Default and cannot safely read Compose
        // snapshot state. snapshotFlow observes changes on the main thread and publishes
        // them to the AtomicReference that the render loop reads with .get().
        val sceneRef = remember { AtomicReference<PreparedScene?>(null) }
        LaunchedEffect(preparedScene) {
            snapshotFlow { preparedScene.value }
                .collect { sceneRef.set(it) }
        }

        // F3: Track renderContext dimensions as State so the renderLoop can observe changes
        // and reconfigure the GPU surface on window/fold resize without waiting for
        // AndroidExternalSurface to destroy and recreate the surface.
        val contextWidth = remember { mutableIntStateOf(renderContext.width) }
        val contextHeight = remember { mutableIntStateOf(renderContext.height) }
        contextWidth.intValue = renderContext.width
        contextHeight.intValue = renderContext.height

        // G4: Read display refresh rate to use as a Surface.setFrameRate hint.
        // When vsync=true, frame pacing is provided by PresentMode.Fifo in Dawn:
        // getCurrentTexture() blocks on the swapchain semaphore at each vsync boundary.
        // When vsync=false (default), PresentMode.Mailbox presents immediately.
        // The setFrameRate hint tells SurfaceFlinger to maintain the target Hz for this
        // surface, preventing LTPO power-saving from dropping the refresh rate.
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
                    val typedCallback = frameCallback as? WebGpuFrameCallback
                        ?: object : WebGpuFrameCallback {}
                    renderer.renderLoop(
                        androidSurface = surface,
                        surfaceWidth = width,
                        surfaceHeight = height,
                        preparedScene = sceneRef,
                        renderContextWidth = contextWidth,
                        renderContextHeight = contextHeight,
                        frameCallback = typedCallback,
                        // Enable GPU timestamps only when a real WebGpuFrameCallback is provided
                        enableGpuTimestamps = frameCallback is WebGpuFrameCallback,
                        vsync = vsync,
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
