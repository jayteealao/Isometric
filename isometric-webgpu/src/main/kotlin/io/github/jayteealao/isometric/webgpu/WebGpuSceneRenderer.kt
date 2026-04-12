package io.github.jayteealao.isometric.webgpu

import android.util.Log
import android.view.Surface
import androidx.compose.runtime.State
import androidx.webgpu.CompositeAlphaMode
import androidx.webgpu.GPUColor
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPUSurface
import androidx.webgpu.GPUSurfaceConfiguration
import androidx.webgpu.GPUSurfaceDescriptor
import androidx.webgpu.GPUSurfaceSourceAndroidNativeWindow
import androidx.webgpu.LoadOp
import androidx.webgpu.PresentMode
import androidx.webgpu.StoreOp
import androidx.webgpu.SurfaceGetCurrentTextureStatus
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import androidx.webgpu.helper.Util.windowFromSurface
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.webgpu.pipeline.GpuFullPipeline
import io.github.jayteealao.isometric.webgpu.pipeline.GpuRenderPipeline
import io.github.jayteealao.isometric.webgpu.pipeline.GpuTimestampProfiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

internal class WebGpuSceneRenderer : AutoCloseable {
    companion object {
        private const val TAG = "WebGpuSceneRenderer"

        /**
         * Diagnostic flag: when true, skip the M3→M5 compute dispatch entirely and
         * just submit a plain clear render pass. Flip to `true` to determine whether
         * `VK_ERROR_DEVICE_LOST` is caused by our compute shaders (TDR) or by an
         * external factor (surface/swapchain/driver). Set back to `false` for
         * production use.
         */
        private const val DEBUG_SKIP_COMPUTE = false

        /**
         * Diagnostic flag: when true, submit each compute stage (M3, M4a, every sort stage,
         * M5) as a separate [GPUQueue.submit] call instead of batching them into one command
         * buffer. This forces a Vulkan timeline semaphore between every pair of consecutive
         * compute stages, working around the Adreno bug where compute→compute barriers within
         * a single VkCommandBuffer are not honoured.
         *
         * If enabling this flag fixes the `VK_ERROR_DEVICE_LOST` TDR, the root cause is
         * the Adreno compute-barrier bug. See WEBGPU_CRASH_INVESTIGATION.md § Bug 2.
         */
        private const val DEBUG_INDIVIDUAL_COMPUTE_SUBMITS = false

        /**
         * Diagnostic flag: when true, log per-step `System.nanoTime()` breakdown inside
         * `drawFrame()` to identify where the ~14.5ms draw floor originates.
         *
         * Expected output per frame:
         * ```
         * drawFrame: acquire=Xns compute=Yns render=Zns resolve=Wns present=Vns total=Tns
         * ```
         *
         * `const val = false` → compiler eliminates all timing code at compile time.
         */
        private const val DEBUG_DRAW_TIMING = false

    }

    private var gpuContext: GpuContext? = null
    // false when the context was borrowed from WebGpuComputeBackend — we must not destroy it.
    private var ownsContext: Boolean = true
    private var gpuSurface: GPUSurface? = null
    private var renderPipeline: GpuRenderPipeline? = null
    private var fullPipeline: GpuFullPipeline? = null
    private var surfaceFormat: Int = TextureFormat.Undefined
    private var presentMode: Int = PresentMode.Fifo
    private var alphaMode: Int = CompositeAlphaMode.Auto
    private var currentWidth: Int = 1
    private var currentHeight: Int = 1
    // H1: Track whether surface.configure() has completed successfully. cleanup() must not
    // call unconfigure() on a surface that was never configured — doing so passes a
    // native handle that Dawn never initialised, which causes a Scudo double-free (SIGABRT).
    private var surfaceConfigured: Boolean = false
    private var useVsync: Boolean = false
    private var lastScene: PreparedScene? = null
    // Track last uploaded viewport so we re-upload uniforms and bind groups when the
    // surface is reconfigured while the scene remains unchanged.
    private var lastUploadWidth: Int = 0
    private var lastUploadHeight: Int = 0

    suspend fun renderLoop(
        androidSurface: Surface,
        surfaceWidth: Int,
        surfaceHeight: Int,
        preparedScene: AtomicReference<PreparedScene?>,
        renderContextWidth: State<Int>,
        renderContextHeight: State<Int>,
        frameCallback: WebGpuFrameCallback = object : WebGpuFrameCallback {},
        enableGpuTimestamps: Boolean = false,
        vsync: Boolean = false,
    ) {
        useVsync = vsync
        var profiler: GpuTimestampProfiler? = null
        var profilerFailures = 0
        try {
            ensureInitialized(
                androidSurface = androidSurface,
                width = surfaceWidth,
                height = surfaceHeight,
                enableGpuTimestamps = enableGpuTimestamps,
            )

            // Create GPU timestamp profiler if supported and requested
            val ctx = gpuContext
            if (enableGpuTimestamps && ctx != null && ctx.supportsTimestamps) {
                profiler = ctx.withGpu { GpuTimestampProfiler(ctx) }
                Log.i(TAG, "GPU timestamp profiler enabled")
            } else if (enableGpuTimestamps) {
                Log.i(TAG, "GPU timestamps requested but not supported by device")
            }

            while (currentCoroutineContext().isActive) {
                // F3: Reconfigure the surface if the viewport dimensions changed since
                // the last configure call. This handles window/fold resize without
                // waiting for AndroidExternalSurface to destroy and recreate.
                // Reconfigure failures (e.g. getCapabilities on a degraded surface)
                // break the loop instead of escaping to the Compose scope and crashing.
                val ctxWidth = renderContextWidth.value
                val ctxHeight = renderContextHeight.value
                if (ctxWidth > 0 && ctxHeight > 0 &&
                    (ctxWidth != currentWidth || ctxHeight != currentHeight)) {
                    val ctx = gpuContext
                    if (ctx != null) {
                        try {
                            ctx.withGpu {
                                reconfigureSurface(ctxWidth, ctxHeight)
                            }
                            Log.d(TAG, "Surface reconfigured to ${currentWidth}x${currentHeight}")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "renderLoop: surface reconfigure failed, stopping: ${e.message}")
                            break
                        }
                    }
                }

                // G3: uploadScene (queue.writeBuffer) and drawFrame (surface.present,
                // checkHealthy) both propagate unrecoverable GPU errors as exceptions.
                // Catch them together so cleanup() still runs and the render coroutine
                // exits gracefully rather than crashing the Compose coroutine scope.
                // CancellationException is always re-thrown — it means the caller
                // cancelled the render loop intentionally (Activity exit, recompose).
                try {
                    // Read back previous frame's GPU timestamps (one frame late).
                    // readResults() uses raw mapAsync + processEvents pumping internally
                    // (not mapAndAwait) to avoid deadlocking the single GPU thread.
                    // Must run on GPU thread since processEvents is thread-confined.
                    val p = profiler
                    if (p != null) {
                        val result = try {
                            gpuContext?.withGpu { p.readResults() }
                        } catch (e: Exception) {
                            Log.w(TAG, "Timestamp readback exception: ${e.message}")
                            null
                        }
                        if (result != null) {
                            profilerFailures = 0
                            frameCallback.onGpuTimestamps(
                                computeNanos = result.totalComputeNanos,
                                renderNanos = result.renderPassNanos,
                            )
                        } else if (++profilerFailures >= 5) {
                            // Disable profiler after 5 consecutive failures to prevent
                            // JNI global reference accumulation from callback objects
                            // that Dawn never releases on failed readbacks.
                            Log.w(TAG, "Disabling GPU timestamp profiler after $profilerFailures consecutive failures")
                            try { gpuContext?.withGpu { p.close() } } catch (_: Exception) {}
                            profiler = null
                        }
                    }

                    val scene = preparedScene.get()
                    // Re-upload when scene changes OR when viewport was reconfigured since
                    // the last upload (uniforms and emit bind group include viewport dims).
                    val viewportChanged = currentWidth != lastUploadWidth ||
                        currentHeight != lastUploadHeight
                    if (scene !== lastScene || viewportChanged) {
                        frameCallback.onUploadStart()
                        uploadScene(scene)
                        frameCallback.onUploadEnd()
                        lastScene = scene
                    }
                    frameCallback.onDrawFrameStart()
                    drawFrame(androidSurface, profiler, frameCallback)
                    frameCallback.onDrawFrameEnd()

                    // Swap profiler buffers so next frame reads this frame's timestamps
                    profiler?.swapBuffers()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "renderLoop: GPU error, stopping render loop: ${e.message}")
                    break
                }
                // G2: Check cancellation after each frame. withContext(gpuDispatcher) inside
                // drawFrame already yields the calling-dispatcher thread for the duration of
                // the GPU work, so there is no need to re-queue via yield(). ensureActive()
                // throws CancellationException immediately if the scope has been cancelled
                // without the overhead of a full scheduler round-trip.
                currentCoroutineContext().ensureActive()
            }
        } finally {
            // R-02: Close profiler on GPU thread to avoid Dawn JNI handle race
            // with processEvents(). Guard with try-catch for the case where the
            // context is already destroyed.
            profiler?.let { p ->
                try { gpuContext?.withGpu { p.close() } } catch (_: Exception) {}
            }
            cleanup()
        }
    }

    private suspend fun ensureInitialized(
        androidSurface: Surface,
        width: Int,
        height: Int,
        enableGpuTimestamps: Boolean = false,
    ) {
        if (gpuContext != null && gpuSurface != null && renderPipeline != null && fullPipeline != null) return

        // F2: Use getContextIfReady() (opportunistic, no-trigger) instead of
        // awaitContextForSharing() to avoid forcing compute backend GPU init when the user
        // has CPU compute selected. Only reuse a context that already exists.
        val sharedContext = WebGpuProviderImpl.computeBackend.getContextIfReady()

        val context: GpuContext
        if (sharedContext != null) {
            context = sharedContext
            ownsContext = false
            val surface = context.withGpu {
                context.instance.createSurface(
                    GPUSurfaceDescriptor(
                        label = "IsometricSurface",
                        surfaceSourceAndroidNativeWindow =
                            GPUSurfaceSourceAndroidNativeWindow(windowFromSurface(androidSurface))
                    )
                )
            }
            gpuSurface = surface
            Log.d(TAG, "Reusing compute GpuContext for render surface")
        } else {
            // G1: Use createForSurface() to guarantee the selected adapter is compatible
            // with the render surface. The surface is created inside the factory so the
            // GPUInstance is available when GPUSurface is constructed.
            val (newContext, surface) = GpuContext.createForSurface(enableTimestamps = enableGpuTimestamps) { instance ->
                instance.createSurface(
                    GPUSurfaceDescriptor(
                        label = "IsometricSurface",
                        surfaceSourceAndroidNativeWindow =
                            GPUSurfaceSourceAndroidNativeWindow(windowFromSurface(androidSurface))
                    )
                )
            }
            context = newContext
            ownsContext = true
            gpuSurface = surface
        }

        gpuContext = context

        context.withGpu {
            configureSurface(width, height)
            val gp = GpuFullPipeline(context)
            gp.ensurePipelines()
            fullPipeline = gp
            renderPipeline = GpuRenderPipeline(
                context.device,
                surfaceFormat,
                gp.textureBinder.bindGroupLayout,
            )
        }

        Log.d(TAG, "Initialized surface ${currentWidth}x${currentHeight} format=$surfaceFormat owned=$ownsContext")
    }

    // G2: Capabilities (format, presentMode, alphaMode) are stable for the lifetime of a
    // GPUSurface — they don't change between resizes. Only query getCapabilities() on the
    // first configure; on subsequent resizes just reconfigure with the cached values.
    // surfaceFormat == TextureFormat.Undefined signals "not yet queried".
    // recreateSurface() resets surfaceFormat to Undefined to force a re-query for the new surface.
    private suspend fun configureSurface(width: Int, height: Int) {
        val ctx = checkNotNull(gpuContext)
        val surface = checkNotNull(gpuSurface)

        if (surfaceFormat == TextureFormat.Undefined) {
            // getCapabilities() can fail when the adapter was requested without
            // compatibleSurface (e.g. when reusing the compute-only GpuContext via
            // getContextIfReady()). Fall back to universally-supported safe defaults so
            // the surface can still be configured for rendering.
            try {
                val capabilities = surface.getCapabilities(ctx.adapter)

                require(capabilities.formats.isNotEmpty()) { "No supported surface formats returned" }
                require((capabilities.usages and TextureUsage.RenderAttachment) != 0) {
                    "Surface does not support RenderAttachment usage"
                }

                Log.d(TAG, "Available presentModes: ${capabilities.presentModes.toList()}")

                surfaceFormat = when {
                    capabilities.formats.contains(TextureFormat.BGRA8Unorm) -> TextureFormat.BGRA8Unorm
                    capabilities.formats.contains(TextureFormat.RGBA8Unorm) -> TextureFormat.RGBA8Unorm
                    else -> capabilities.formats.first()
                }
                presentMode = when {
                    !useVsync && capabilities.presentModes.contains(PresentMode.Mailbox) ->
                        PresentMode.Mailbox
                    capabilities.presentModes.contains(PresentMode.Fifo) ->
                        PresentMode.Fifo
                    else ->
                        capabilities.presentModes.firstOrNull() ?: PresentMode.Fifo
                }
                Log.d(TAG, "Using presentMode=$presentMode (vsync=$useVsync)")
                alphaMode = if (capabilities.alphaModes.contains(CompositeAlphaMode.Auto)) {
                    CompositeAlphaMode.Auto
                } else {
                    capabilities.alphaModes.firstOrNull() ?: CompositeAlphaMode.Auto
                }
            } catch (e: Exception) {
                // Shared-context case: the adapter has no surface compatibility contract.
                // BGRA8Unorm + Fifo + Auto are universally supported on Android Vulkan.
                Log.w(TAG, "getCapabilities failed — using safe defaults (${e.message})")
                surfaceFormat = TextureFormat.BGRA8Unorm
                presentMode = PresentMode.Fifo
                alphaMode = CompositeAlphaMode.Auto
            }
        }

        currentWidth = width.coerceAtLeast(1)
        currentHeight = height.coerceAtLeast(1)

        // H1: Reset before configure so any exception leaves surfaceConfigured = false,
        // preventing cleanup() from calling unconfigure() on a half-initialised surface.
        surfaceConfigured = false
        surface.configure(
            GPUSurfaceConfiguration(
                device = ctx.device,
                width = currentWidth,
                height = currentHeight,
                format = surfaceFormat,
                usage = TextureUsage.RenderAttachment,
                alphaMode = alphaMode,
                presentMode = presentMode,
            )
        )
        surfaceConfigured = true
    }

    private suspend fun uploadScene(scene: PreparedScene?) {
        // Snapshot dimensions before the withGpu suspension point so that the upload
        // call sees consistent width/height values.
        val w = currentWidth
        val h = currentHeight

        val ctx = checkNotNull(gpuContext)
        ctx.withGpu {
            val gp = fullPipeline!!
            if (scene == null || scene.commands.isEmpty()) {
                gp.clearScene()
            } else {
                gp.upload(scene, w, h)
            }
            // R-08: Set lastUpload* inside withGpu so they are only updated after the
            // GPU upload succeeds. If withGpu throws, the stale values ensure the next
            // frame re-uploads correctly.
            lastUploadWidth  = w
            lastUploadHeight = h
        }
    }

    private suspend fun drawFrame(
        androidSurface: Surface,
        profiler: GpuTimestampProfiler? = null,
        frameCallback: WebGpuFrameCallback? = null,
    ) {
        val ctx = checkNotNull(gpuContext)
        val surface = checkNotNull(gpuSurface)
        val pipeline = checkNotNull(renderPipeline)

        ctx.withGpu {
            // G3: checkHealthy() rethrows any stored device-lost or uncaptured-error
            // failure. Let it propagate — renderLoop() catches and breaks.
            ctx.checkHealthy()

            val acquireStart = System.nanoTime()

            val surfaceTexture = surface.getCurrentTexture()
            val postAcquire = System.nanoTime()
            val acquireNanos = postAcquire - acquireStart

            when (surfaceTexture.status) {
                SurfaceGetCurrentTextureStatus.SuccessOptimal,
                SurfaceGetCurrentTextureStatus.SuccessSuboptimal -> {
                    val textureView = surfaceTexture.texture.createView()
                    frameCallback?.onAcquireEnd(acquireNanos)

                    val gp = fullPipeline
                    val shouldDraw = !DEBUG_SKIP_COMPUTE && gp != null && gp.hasScene
                    if (DEBUG_SKIP_COMPUTE) {
                        Log.d(TAG, "DEBUG_SKIP_COMPUTE=true — bypassing compute dispatch")
                    }

                    // A1: Submit compute (M3→M5) and render in SEPARATE command buffers.
                    //
                    // On Adreno, using a compute-written buffer as a vertex/indirect buffer
                    // within the same VkCommandBuffer causes VK_ERROR_DEVICE_LOST (GPU TDR).
                    // This is a confirmed Adreno driver bug where Dawn's implicit barriers
                    // for Storage→Vertex and Storage→Indirect usage transitions are not
                    // honoured when compute and render passes share one command stream.
                    //
                    // A2 (DEBUG_INDIVIDUAL_COMPUTE_SUBMITS): also submit each compute stage
                    // in its own command buffer. This additionally works around the Adreno
                    // bug where compute→compute barriers within a single VkCommandBuffer are
                    // broken (each sort stage needs to see the previous stage's writes).
                    if (shouldDraw) {
                        if (DEBUG_INDIVIDUAL_COMPUTE_SUBMITS) {
                            gp!!.dispatchIndividualSubmits()
                        } else {
                            val computeEncoder = ctx.device.createCommandEncoder()
                            gp!!.dispatch(computeEncoder, profiler)
                            val computeCmdBuf = computeEncoder.finish()
                            ctx.queue.submit(arrayOf(computeCmdBuf))
                            computeCmdBuf.close()
                            computeEncoder.close()
                        }
                    }
                    val t2 = if (DEBUG_DRAW_TIMING) System.nanoTime() else 0L

                    val renderEncoder = ctx.device.createCommandEncoder()
                    val pass = renderEncoder.beginRenderPass(
                        GPURenderPassDescriptor(
                            colorAttachments = arrayOf(
                                GPURenderPassColorAttachment(
                                    clearValue = GPUColor(0.0, 0.0, 0.0, 0.0),
                                    view = textureView,
                                    loadOp = LoadOp.Clear,
                                    storeOp = StoreOp.Store,
                                )
                            ),
                            timestampWrites = profiler?.timestampWritesFor(4),
                        )
                    )

                    pass.setPipeline(pipeline.pipeline)
                    if (shouldDraw) {
                        // Bind texture + sampler at @group(0) for the fragment shader.
                        pass.setBindGroup(0, gp!!.textureBindGroup)
                        // Vertex buffer written by M5a; vertex count written by M5b.
                        // drawIndirect reads the vertex count from indirectArgsBuffer without
                        // any CPU readback — the count stays entirely on the GPU.
                        pass.setVertexBuffer(0, gp.vertexBuffer)
                        pass.drawIndirect(gp.indirectArgsBuffer, 0L)
                    }
                    pass.end()
                    pass.close()

                    val renderCmdBuf = renderEncoder.finish()
                    ctx.queue.submit(arrayOf(renderCmdBuf))
                    renderCmdBuf.close()
                    renderEncoder.close()
                    val t3 = if (DEBUG_DRAW_TIMING) System.nanoTime() else 0L

                    // Resolve timestamp queries → readback buffer (after compute+render)
                    if (profiler != null) {
                        val resolveEncoder = ctx.device.createCommandEncoder()
                        profiler.encodeResolveAndCopy(resolveEncoder)
                        val resolveCmdBuf = resolveEncoder.finish()
                        ctx.queue.submit(arrayOf(resolveCmdBuf))
                        resolveCmdBuf.close()
                        resolveEncoder.close()
                    }
                    val t4 = if (DEBUG_DRAW_TIMING) System.nanoTime() else 0L

                    // G3: surface.present() maps to vkQueuePresentKHR. On Activity teardown
                    // the Vulkan surface is invalidated before the render loop is cancelled;
                    // present() then fires the device-lost callback synchronously and throws
                    // IllegalStateException. Let it propagate — renderLoop() catches and breaks.
                    surface.present()
                    val t5 = if (DEBUG_DRAW_TIMING) System.nanoTime() else 0L

                    if (DEBUG_DRAW_TIMING) {
                        Log.d(TAG, "drawFrame: acquire=${acquireNanos}ns compute=${t2-postAcquire}ns " +
                            "render=${t3-t2}ns resolve=${t4-t3}ns present=${t5-t4}ns " +
                            "total=${t5-acquireStart}ns")
                    }

                    textureView.close()
                    surfaceTexture.texture.close()

                    if (surfaceTexture.status == SurfaceGetCurrentTextureStatus.SuccessSuboptimal) {
                        reconfigureSurface()
                    }
                }
                SurfaceGetCurrentTextureStatus.Outdated -> reconfigureSurface()
                SurfaceGetCurrentTextureStatus.Lost -> recreateSurface(androidSurface)
                SurfaceGetCurrentTextureStatus.Timeout -> Unit
                SurfaceGetCurrentTextureStatus.Error -> {
                    Log.e(TAG, "surface.getCurrentTexture() returned Error")
                    throw IllegalStateException("Surface getCurrentTexture returned Error")
                }
            }
        }
    }

    // H1: width/height default to the cached values so callers that don't need a resize
    // (e.g. Outdated/SuccessSuboptimal from drawFrame) can use the no-arg form, while the
    // renderLoop resize path passes the new dimensions without duplicating the guard logic.
    private suspend fun reconfigureSurface(width: Int = currentWidth, height: Int = currentHeight) {
        val surface = gpuSurface ?: return
        // H1: Only unconfigure if we successfully configured before.
        if (surfaceConfigured) surface.unconfigure()
        configureSurface(width, height)
    }

    private suspend fun recreateSurface(androidSurface: Surface) {
        val ctx = checkNotNull(gpuContext)
        // H1: Only unconfigure if we successfully configured before.
        if (surfaceConfigured) gpuSurface?.unconfigure()
        gpuSurface?.close()
        gpuSurface = ctx.instance.createSurface(
            GPUSurfaceDescriptor(
                label = "IsometricSurface",
                surfaceSourceAndroidNativeWindow =
                    GPUSurfaceSourceAndroidNativeWindow(windowFromSurface(androidSurface))
            )
        )
        // G2: reset so configureSurface() re-queries capabilities for the new surface object.
        surfaceFormat = TextureFormat.Undefined
        // Let exceptions propagate — if the ANativeWindow is already gone (Activity teardown,
        // device reset) configureSurface() will throw. The exception propagates through
        // drawFrame() to renderLoop()'s inner catch, which breaks the loop cleanly instead
        // of spinning recreateSurface() every frame while the window is permanently gone.
        configureSurface(currentWidth, currentHeight)
    }

    // F1: All Dawn API calls (unconfigure, close on surfaces/pipelines/buffers) must run on
    // the dedicated GPU thread. cleanup() is called from the finally block of renderLoop(),
    // which runs on Dispatchers.Default. We dispatch the Dawn teardown work to the GPU thread
    // via runBlocking(gpuDispatcher) — the same pattern used by GpuContext.destroy().
    //
    // G1: Guarded with try/catch — if the shared context was already destroyed externally
    // (e.g. WebGpuComputeBackend device-lost → invalidateContext() → ctx.destroy() shuts
    // down the dispatcher), runBlocking throws RejectedExecutionException. The GPU device
    // is already gone in that case; log and proceed to null out all fields.
    private fun cleanup() {
        val ctx = gpuContext
        if (ctx != null) {
            try {
                runBlocking(ctx.gpuDispatcher) {
                    // H1: Do NOT call gpuSurface?.unconfigure() here. cleanup() is called
                    // during Activity teardown, at which point the ANativeWindow / VkSurface
                    // backing the GPUSurface may already be destroyed by SurfaceFlinger.
                    // Calling unconfigure() on a surface whose Vulkan swap chain is gone causes
                    // a Scudo double-free (SIGABRT) inside libwebgpu_c_bundled.so.
                    // unconfigure() is only needed before *reconfiguring* the same surface
                    // (see reconfigureSurface / recreateSurface — both called while the window
                    // is still alive). On teardown, close() alone releases the Dawn handle via
                    // wgpuSurfaceRelease, which correctly decrements the refcount and frees
                    // native resources without touching the already-destroyed VkSurface.
                    renderPipeline?.close()
                    fullPipeline?.close()
                    gpuSurface?.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "cleanup: GPU teardown skipped — context already destroyed", e)
                // Best-effort JNI handle release even when the GPU thread is gone.
                // Each close() is guarded individually — one failure must not block the others.
                try { gpuSurface?.close() } catch (_: Exception) {}
                try { renderPipeline?.close() } catch (_: Exception) {}
                try { fullPipeline?.close() } catch (_: Exception) {}
            }
            // Only destroy if we own the context. When shared with WebGpuComputeBackend,
            // the compute backend owns the lifecycle and will destroy it.
            if (ownsContext) {
                ctx.destroy()
            }
        }
        renderPipeline    = null
        fullPipeline      = null
        gpuSurface        = null
        gpuContext        = null
        ownsContext       = true
        surfaceConfigured = false
        lastScene         = null
        lastUploadWidth   = 0
        lastUploadHeight  = 0
    }

    override fun close() {
        cleanup()
    }
}
