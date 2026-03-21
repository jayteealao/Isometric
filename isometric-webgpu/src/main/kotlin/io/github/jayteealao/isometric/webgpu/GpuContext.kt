package io.github.jayteealao.isometric.webgpu

import android.os.Handler
import android.os.Looper
import androidx.webgpu.BackendType
import androidx.webgpu.GPU
import androidx.webgpu.GPUAdapter
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUDeviceDescriptor
import androidx.webgpu.GPUInstance
import androidx.webgpu.GPUQueue
import androidx.webgpu.GPURequestAdapterOptions
import androidx.webgpu.PowerPreference
import androidx.webgpu.DeviceLostCallback
import androidx.webgpu.DeviceLostException
import androidx.webgpu.UncapturedErrorCallback
import androidx.webgpu.WebGpuRuntimeException
import androidx.webgpu.helper.initLibrary
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the GPU device lifecycle for the `isometric-webgpu` module.
 *
 * Encapsulates [GPUInstance], [GPUAdapter], [GPUDevice], and [GPUQueue] into a single
 * lifecycle-managed object. Call [create] to initialize (suspend — adapter and device
 * requests are async), and [destroy] to release all GPU resources.
 *
 * ## Key API constraints (from vendored source)
 *
 * - Entry point is [GPU.createInstance], NOT `GPUInstance()` constructor.
 * - [initLibrary] must be called before any `androidx.webgpu` call (loads `webgpu_c_bundled`).
 * - [GPUInstance.requestAdapter] and [GPUAdapter.requestDevice] are suspend functions that
 *   throw on failure — never return null.
 * - A [GPUAdapter] can only produce one [GPUDevice]. After `requestDevice()`, the adapter
 *   cannot produce another — design [GpuContext] as single-use.
 * - [GPUInstance.processEvents] must be polled periodically (~100ms) on the main thread
 *   for async callbacks to fire. Without this, `mapAndAwait` and other async operations hang.
 *
 * ## Thread safety
 *
 * [create] is safe to call from any coroutine context. The [processEvents] polling handler
 * runs on the main looper. [destroy] is idempotent and safe to call from any thread.
 */
class GpuContext private constructor(
    val instance: GPUInstance,
    val adapter: GPUAdapter,
    val device: GPUDevice,
    val queue: GPUQueue,
    private val eventHandler: Handler,
    private val isClosing: AtomicBoolean,
) {
    companion object {
        private const val POLLING_DELAY_MS = 100L

        /**
         * Initialize the GPU context.
         *
         * Loads the native library, creates an instance, requests an adapter with Vulkan
         * backend and high-performance preference, and requests a device.
         *
         * @throws Exception if adapter or device creation fails (e.g. no Vulkan support)
         */
        suspend fun create(): GpuContext {
            // Must be called before any androidx.webgpu call.
            initLibrary()

            val instance = GPU.createInstance()

            val adapter = instance.requestAdapter(
                GPURequestAdapterOptions(
                    powerPreference = PowerPreference.HighPerformance,
                    backendType = BackendType.Vulkan
                )
            )

            val device = adapter.requestDevice(
                GPUDeviceDescriptor(
                    deviceLostCallbackExecutor = Executor(Runnable::run),
                    uncapturedErrorCallbackExecutor = Executor(Runnable::run),
                    deviceLostCallback = DeviceLostCallback { device, reason, message ->
                        throw DeviceLostException(
                            /* device = */ device,
                            /* reason = */ reason,
                            /* message = */ message
                        )
                    },
                    uncapturedErrorCallback = UncapturedErrorCallback { _, type, message ->
                        throw WebGpuRuntimeException.create(type, message)
                    },
                )
            )

            // Dawn requires processEvents() polling for async callbacks to fire.
            val isClosing = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            fun scheduleProcess() {
                handler.postDelayed({
                    if (!isClosing.get()) {
                        instance.processEvents()
                        scheduleProcess()
                    }
                }, POLLING_DELAY_MS)
            }
            scheduleProcess()

            return GpuContext(
                instance = instance,
                adapter = adapter,
                device = device,
                queue = device.queue,
                eventHandler = handler,
                isClosing = isClosing,
            )
        }
    }

    /**
     * Release all GPU resources. Idempotent — safe to call multiple times.
     */
    fun destroy() {
        if (isClosing.getAndSet(true)) return

        eventHandler.removeCallbacksAndMessages(null)
        device.destroy()
        instance.close()
        adapter.close()
    }
}
