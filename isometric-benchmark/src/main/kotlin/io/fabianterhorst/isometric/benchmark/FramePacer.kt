package io.fabianterhorst.isometric.benchmark

import android.view.Choreographer
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Provides frame-synchronized timing using Android's Choreographer.
 *
 * Must be constructed on a thread with a Looper (typically the main thread).
 * All operations execute on the thread where the Choreographer instance was created.
 *
 * @param choreographer The Choreographer instance to use (defaults to current thread's instance)
 */
class FramePacer(
    private val choreographer: Choreographer = Choreographer.getInstance()
) {
    /**
     * Suspends until the next frame callback, then invokes [onFrame] with the frame timestamp.
     *
     * Supports coroutine cancellation - if cancelled, the frame callback is removed.
     * If [onFrame] throws an exception, the coroutine still resumes normally (exception is suppressed).
     *
     * @param onFrame Callback invoked with frame time in nanoseconds
     * @throws IllegalStateException if Choreographer not available on current thread (during construction)
     */
    suspend fun awaitNextFrame(onFrame: (frameTimeNanos: Long) -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val callback = Choreographer.FrameCallback { frameTimeNanos ->
                try {
                    onFrame(frameTimeNanos)
                } finally {
                    continuation.resume(Unit)
                }
            }
            choreographer.postFrameCallback(callback)
            continuation.invokeOnCancellation {
                choreographer.removeFrameCallback(callback)
            }
        }
    }
}
