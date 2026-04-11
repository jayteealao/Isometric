package io.github.jayteealao.isometric.webgpu

import android.util.Log
import androidx.webgpu.BackendType
import androidx.webgpu.GPU
import androidx.webgpu.GPUAdapter
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUDeviceDescriptor
import androidx.webgpu.GPUInstance
import androidx.webgpu.GPUQueue
import androidx.webgpu.GPURequestAdapterOptions
import androidx.webgpu.GPUSurface
import androidx.webgpu.PowerPreference
import androidx.webgpu.DeviceLostCallback
import androidx.webgpu.FeatureName
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
    /** Whether the device was created with `FeatureName.TimestampQuery` enabled. */
    val supportsTimestamps: Boolean = false,
) {
    companion object {
        private const val TAG = "GpuContext"
        private const val POLLING_DELAY_MS = 100L
        private const val GPU_THREAD_NAME = "WebGPU-Thread"
        private const val MIN_COMPUTE_INVOCATIONS = 256

        /**
         * Initialize the GPU context for compute-only use (Phase 1 path).
         *
         * Loads the native library, creates an instance, requests an adapter with Vulkan
         * backend and high-performance preference, and requests a device. All Dawn API
         * calls are confined to a dedicated single thread to avoid Vulkan driver races.
         *
         * Prefer [createForSurface] when a render surface exists — it passes
         * `compatibleSurface` to the adapter request to guarantee the selected adapter
         * can present to the surface.
         *
         * If adapter or device creation fails, the dedicated thread is shut down before
         * the exception propagates — no thread leak on failure.
         *
         * @throws Exception if adapter or device creation fails (e.g. no Vulkan support)
         */
        suspend fun create(
            requestAdapterOptionsFactory: ((GPUInstance) -> GPURequestAdapterOptions)? = null,
            enableTimestamps: Boolean = false,
        ): GpuContext {
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
            val (instance, adapter, device, timestampsAvailable) = try {
                withContext(gpuDispatcher) {
                    // Capture the thread reference (runs on the GPU thread).
                    if (gpuThreadRef == null) gpuThreadRef = Thread.currentThread()

                    val instance = GPU.createInstance()

                    val adapterOptions = requestAdapterOptionsFactory?.invoke(instance)
                        ?: GPURequestAdapterOptions(
                            powerPreference = PowerPreference.HighPerformance,
                            backendType = BackendType.Vulkan
                        )

                    val adapter = instance.requestAdapter(adapterOptions)

                    val timestampsOk = enableTimestamps && adapter.hasFeature(FeatureName.TimestampQuery)
                    Log.i(TAG, "create: TimestampQuery feature: supported=${adapter.hasFeature(FeatureName.TimestampQuery)}, requested=$enableTimestamps, enabled=$timestampsOk")
                    val device = adapter.requestDevice(buildDeviceDescriptor(lastFailure, timestampsOk))

                    assertComputeLimits(device)
                    ContextInitResult(instance, adapter, device, timestampsOk)
                }
            } catch (t: Throwable) {
                gpuDispatcher.close()
                throw t
            }

            val isClosing = AtomicBoolean(false)
            val pollingJob = startPollingJob(gpuDispatcher, instance, isClosing, lastFailure)
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
                supportsTimestamps = timestampsAvailable,
            )
        }

        /**
         * Initialize a GPU context compatible with the given render surface.
         *
         * Prefer this factory over [create] when a render surface exists. Passing
         * `compatibleSurface` to the adapter request guarantees the selected adapter can
         * present to the surface — without it, the adapter may be selected on performance
         * preference alone and may not support surface presentation on all devices.
         *
         * The [GPUSurface] is created inside this factory by [surfaceFactory], which
         * receives the newly created [GPUInstance] and runs on the dedicated GPU thread.
         * Both the [GpuContext] and the [GPUSurface] are returned as a pair; the caller
         * owns both lifetimes.
         *
         * If the adapter request with `compatibleSurface` fails (e.g. on an emulator
         * that does not expose a compatible Vulkan adapter), the factory falls back to a
         * plain `requestAdapter()` call without the surface hint and logs a warning.
         *
         * @param surfaceFactory Creates the [GPUSurface] from the [GPUInstance]. Called
         *   on the dedicated GPU thread inside this factory.
         * @throws Exception if adapter or device creation fails after both attempts.
         */
        suspend fun createForSurface(
            enableTimestamps: Boolean = false,
            surfaceFactory: (GPUInstance) -> GPUSurface,
        ): Pair<GpuContext, GPUSurface> {
            initLibrary()

            var gpuThreadRef: Thread? = null
            val gpuDispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, GPU_THREAD_NAME).apply {
                    isDaemon = true
                    gpuThreadRef = this
                }
            }.asCoroutineDispatcher()

            val lastFailure = AtomicReference<Throwable?>(null)

            val (instance, adapter, device, surface, timestampsAvailable) = try {
                withContext(gpuDispatcher) {
                    if (gpuThreadRef == null) gpuThreadRef = Thread.currentThread()

                    val instance = GPU.createInstance()
                    val surface = surfaceFactory(instance)

                    // G1: Request an adapter compatible with the surface. This ensures the
                    // selected adapter supports surface presentation on this device.
                    // Fall back to a plain request if the surface-compatible request fails
                    // (e.g. on emulators that report no compatible adapter).
                    val adapter = try {
                        instance.requestAdapter(
                            GPURequestAdapterOptions(
                                powerPreference = PowerPreference.HighPerformance,
                                backendType = BackendType.Vulkan,
                                compatibleSurface = surface,
                            )
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "createForSurface: adapter request with compatibleSurface failed, retrying without surface hint", t)
                        instance.requestAdapter(
                            GPURequestAdapterOptions(
                                powerPreference = PowerPreference.HighPerformance,
                                backendType = BackendType.Vulkan,
                            )
                        )
                    }

                    val timestampsOk = enableTimestamps && adapter.hasFeature(FeatureName.TimestampQuery)
                    Log.i(TAG, "createForSurface: TimestampQuery feature: supported=${adapter.hasFeature(FeatureName.TimestampQuery)}, requested=$enableTimestamps, enabled=$timestampsOk")
                    val device = adapter.requestDevice(buildDeviceDescriptor(lastFailure, timestampsOk))

                    assertComputeLimits(device)
                    SurfaceInitResult(instance, adapter, device, surface, timestampsOk)
                }
            } catch (t: Throwable) {
                gpuDispatcher.close()
                throw t
            }

            val isClosing = AtomicBoolean(false)
            val pollingJob = startPollingJob(gpuDispatcher, instance, isClosing, lastFailure)

            val context = GpuContext(
                instance = instance,
                adapter = adapter,
                device = device,
                queue = device.queue,
                gpuDispatcher = gpuDispatcher,
                gpuThread = gpuThreadRef ?: Thread.currentThread(),
                pollingJob = pollingJob,
                isClosing = isClosing,
                lastFailure = lastFailure,
                supportsTimestamps = timestampsAvailable,
            )
            return context to surface
        }

        /**
         * Assert that the device meets the minimum compute workgroup invocation limit.
         *
         * The WebGPU spec mandates a minimum of 256 for `maxComputeInvocationsPerWorkgroup`
         * on all conformant implementations. This check is performed on every context —
         * regardless of whether it is used for compute or rendering — to surface
         * non-conformant drivers at context-creation time rather than silently dispatching
         * incorrect workgroup sizes later.
         *
         * Risk R2 from the WS13 plan.
         */
        private fun assertComputeLimits(device: GPUDevice) {
            val limits = device.getLimits()
            require(limits.maxComputeInvocationsPerWorkgroup >= MIN_COMPUTE_INVOCATIONS) {
                "Device does not meet the WebGPU minimum maxComputeInvocationsPerWorkgroup " +
                    "(required >= $MIN_COMPUTE_INVOCATIONS, device reports ${limits.maxComputeInvocationsPerWorkgroup})"
            }
        }

        /**
         * Build a [GPUDeviceDescriptor] with device-lost and uncaptured-error callbacks
         * that record the first failure into [lastFailure].
         *
         * Extracted to avoid duplicating the callback setup across [create] and
         * [createForSurface].
         */
        private fun buildDeviceDescriptor(
            lastFailure: AtomicReference<Throwable?>,
            enableTimestamps: Boolean = false,
        ): GPUDeviceDescriptor = GPUDeviceDescriptor(
            deviceLostCallbackExecutor = Executor(Runnable::run),
            uncapturedErrorCallbackExecutor = Executor(Runnable::run),
            requiredFeatures = if (enableTimestamps) intArrayOf(FeatureName.TimestampQuery) else intArrayOf(),
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

        /**
         * Start the [GPUInstance.processEvents] polling loop on the given [gpuDispatcher].
         *
         * Dawn requires periodic polling for async callbacks (mapAndAwait, device-lost,
         * etc.) to fire. The loop runs on the same GPU thread as all other Dawn API calls
         * to avoid data races, and terminates when [isClosing] is set or the job is
         * cancelled via [destroy].
         *
         * Extracted to avoid duplicating the polling setup across [create] and
         * [createForSurface].
         */
        private fun startPollingJob(
            gpuDispatcher: ExecutorCoroutineDispatcher,
            instance: GPUInstance,
            isClosing: AtomicBoolean,
            lastFailure: AtomicReference<Throwable?>,
        ): Job = kotlinx.coroutines.CoroutineScope(gpuDispatcher).launch {
            // Dawn requires processEvents() polling for async callbacks to fire.
            // Run on the same GPU thread to avoid data races with GPU API calls.
            while (isActive && !isClosing.get()) {
                try {
                    instance.processEvents()
                } catch (t: Throwable) {
                    lastFailure.compareAndSet(null, t)
                }
                delay(POLLING_DELAY_MS)
            }
        }

        /** Typed result of the [create] init block, used for destructuring. */
        private data class ContextInitResult(
            val instance: GPUInstance,
            val adapter: GPUAdapter,
            val device: GPUDevice,
            val timestampsAvailable: Boolean = false,
        )

        /** Typed result of the [createForSurface] init block, used for destructuring. */
        private data class SurfaceInitResult(
            val instance: GPUInstance,
            val adapter: GPUAdapter,
            val device: GPUDevice,
            val surface: GPUSurface,
            val timestampsAvailable: Boolean = false,
        )
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
     * Assert that the caller is running on the dedicated GPU thread.
     *
     * Throws [IllegalStateException] if called from any other thread. Call this at the
     * entry of every GPU operation (upload, dispatch, etc.) to catch thread-confinement
     * violations at the point of misuse rather than as a silent data race in the driver.
     */
    fun assertGpuThread() {
        check(Thread.currentThread() === gpuThread) {
            "GPU operation called from wrong thread '${Thread.currentThread().name}'; " +
                "must run on the dedicated GPU thread '${gpuThread.name}'. " +
                "Use ctx.withGpu { ... } to dispatch work to the GPU thread."
        }
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
            // G4: destroy() invalidates the device immediately (all further GPU work
            // fails with device-lost errors). close() decrements the JNI wrapper
            // refcount and releases the underlying Dawn handle. Both calls are needed:
            // destroy() for immediate GPU-side cleanup, close() for JNI handle release.
            // The vendored helper layer has close() commented out (upstream b/428866400),
            // but that TODO is about the helper layer's lifecycle management — calling
            // close() here is still correct and required to avoid JNI handle leaks.
            // Reverse-construction order: device → adapter → instance.
            device.destroy()
            device.close()
            adapter.close()
            instance.close()
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
