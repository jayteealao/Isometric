package io.github.jayteealao.isometric.webgpu

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
import androidx.webgpu.UncapturedErrorCallback
import androidx.webgpu.helper.initLibrary
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
 * - [GPUInstance.processEvents] must be polled periodically (~100ms) for async callbacks
 *   to fire. Without this, `mapAndAwait` and other async operations hang.
 *
 * ## Thread safety
 *
 * All Dawn API calls (device, queue, instance, processEvents) are confined to a single
 * dedicated thread via [gpuDispatcher]. Callers must use [withGpu] to run GPU work on this
 * thread. The [processEvents] polling also runs on this same thread, eliminating the
 * data-race between the main-thread poller and coroutine-dispatched GPU commands that
 * caused SIGSEGV crashes in the Vulkan driver.
 *
 * [create] is safe to call from any coroutine context. [destroy] is idempotent and safe
 * to call from any thread.
 */
class GpuContext private constructor(
    val instance: GPUInstance,
    val adapter: GPUAdapter,
    val device: GPUDevice,
    val queue: GPUQueue,
    val gpuDispatcher: ExecutorCoroutineDispatcher,
    private val gpuThread: Thread,
    private val pollingJob: Job,
    private val isClosing: AtomicBoolean,
    private val lastFailure: AtomicReference<Throwable?>,
) {
    companion object {
        private const val POLLING_DELAY_MS = 100L
        private const val GPU_THREAD_NAME = "WebGPU-Thread"

        /**
         * Initialize the GPU context.
         *
         * Loads the native library, creates an instance, requests an adapter with Vulkan
         * backend and high-performance preference, and requests a device. All Dawn API
         * calls are confined to a dedicated single thread to avoid Vulkan driver races.
         *
         * If adapter or device creation fails, the dedicated thread is shut down before
         * the exception propagates — no thread leak on failure.
         *
         * @throws Exception if adapter or device creation fails (e.g. no Vulkan support)
         */
        suspend fun create(): GpuContext {
            // Must be called before any androidx.webgpu call.
            initLibrary()

            var gpuThreadRef: Thread? = null
            val gpuDispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, GPU_THREAD_NAME).apply {
                    isDaemon = true
                    gpuThreadRef = this
                }
            }.asCoroutineDispatcher()

            val lastFailure = AtomicReference<Throwable?>(null)

            // Run all Dawn initialization on the dedicated GPU thread.
            // On failure, shut down the dispatcher to avoid leaking the thread.
            val (instance, adapter, device) = try {
                withContext(gpuDispatcher) {
                    // Capture the thread reference (runs on the GPU thread).
                    if (gpuThreadRef == null) gpuThreadRef = Thread.currentThread()

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
                            deviceLostCallback = DeviceLostCallback { _, reason, message ->
                                lastFailure.compareAndSet(
                                    null,
                                    IllegalStateException("WebGPU device lost: reason=$reason, message=$message")
                                )
                            },
                            uncapturedErrorCallback = UncapturedErrorCallback { _, type, message ->
                                lastFailure.compareAndSet(
                                    null,
                                    IllegalStateException("WebGPU uncaptured error: type=$type, message=$message")
                                )
                            },
                        )
                    )

                    Triple(instance, adapter, device)
                }
            } catch (t: Throwable) {
                gpuDispatcher.close()
                throw t
            }

            // Dawn requires processEvents() polling for async callbacks to fire.
            // Run on the same GPU thread to avoid data races with GPU API calls.
            val isClosing = AtomicBoolean(false)
            val pollingJob = kotlinx.coroutines.CoroutineScope(gpuDispatcher).launch {
                while (isActive && !isClosing.get()) {
                    try {
                        instance.processEvents()
                    } catch (t: Throwable) {
                        lastFailure.compareAndSet(null, t)
                    }
                    delay(POLLING_DELAY_MS)
                }
            }

            return GpuContext(
                instance = instance,
                adapter = adapter,
                device = device,
                queue = device.queue,
                gpuDispatcher = gpuDispatcher,
                gpuThread = gpuThreadRef ?: Thread.currentThread(),
                pollingJob = pollingJob,
                isClosing = isClosing,
                lastFailure = lastFailure,
            )
        }
    }

    /**
     * Run a block on the dedicated GPU thread. All Dawn API calls
     * (buffer creation, command encoding, queue submit, etc.) must
     * go through this to avoid data races with processEvents polling.
     */
    suspend fun <T> withGpu(block: suspend () -> T): T =
        withContext(gpuDispatcher) { block() }

    fun checkHealthy() {
        lastFailure.get()?.let { throw it }
    }

    /**
     * Release all GPU resources and shut down the dedicated GPU thread.
     * Idempotent — safe to call multiple times.
     *
     * Safe to call from any thread, including the GPU thread itself
     * (e.g. from a device-lost callback).
     */
    fun destroy() {
        if (isClosing.getAndSet(true)) return

        pollingJob.cancel()

        val cleanup = {
            device.destroy()
            instance.close()
            adapter.close()
        }

        if (Thread.currentThread() === gpuThread) {
            // Already on the GPU thread — run inline to avoid deadlock.
            cleanup()
        } else {
            // Run cleanup on the GPU thread to avoid racing with in-flight work.
            runBlocking(gpuDispatcher) { cleanup() }
        }

        // Shut down the backing ExecutorService and terminate the thread.
        gpuDispatcher.close()
    }
}
