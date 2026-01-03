package io.fabianterhorst.isometric.benchmark

import android.view.Choreographer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FramePacer {
    private val choreographer = Choreographer.getInstance()

    suspend fun awaitNextFrame(onFrame: (frameTimeNanos: Long) -> Unit) {
        suspendCoroutine<Unit> { continuation ->
            choreographer.postFrameCallback { frameTimeNanos ->
                onFrame(frameTimeNanos)
                continuation.resume(Unit)
            }
        }
    }
}
