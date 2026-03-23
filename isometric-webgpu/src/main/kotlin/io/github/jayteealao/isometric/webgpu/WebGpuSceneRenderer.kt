package io.github.jayteealao.isometric.webgpu

import android.util.Log
import android.view.Surface
import androidx.compose.runtime.State
import androidx.webgpu.BackendType
import androidx.webgpu.CompositeAlphaMode
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUColor
import androidx.webgpu.GPURequestAdapterOptions
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPUSurface
import androidx.webgpu.GPUSurfaceConfiguration
import androidx.webgpu.GPUSurfaceDescriptor
import androidx.webgpu.GPUSurfaceSourceAndroidNativeWindow
import androidx.webgpu.LoadOp
import androidx.webgpu.PresentMode
import androidx.webgpu.PowerPreference
import androidx.webgpu.StoreOp
import androidx.webgpu.SurfaceGetCurrentTextureStatus
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import androidx.webgpu.helper.Util.windowFromSurface
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.webgpu.pipeline.GpuRenderPipeline
import io.github.jayteealao.isometric.webgpu.pipeline.GpuVertexBuffer
import io.github.jayteealao.isometric.webgpu.triangulation.RenderCommandTriangulator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking

internal class WebGpuSceneRenderer : AutoCloseable {
    companion object {
        private const val TAG = "WebGpuSceneRenderer"
    }

    private val triangulator = RenderCommandTriangulator()

    private var gpuContext: GpuContext? = null
    // false when the context was borrowed from WebGpuComputeBackend — we must not destroy it.
    private var ownsContext: Boolean = true
    private var gpuSurface: GPUSurface? = null
    private var renderPipeline: GpuRenderPipeline? = null
    private var vertexBuffer: GpuVertexBuffer? = null
    private var uploadedVertexBuffer: GPUBuffer? = null
    private var surfaceFormat: Int = TextureFormat.Undefined
    private var presentMode: Int = PresentMode.Fifo
    private var alphaMode: Int = CompositeAlphaMode.Auto
    private var currentWidth: Int = 1
    private var currentHeight: Int = 1
    private var lastScene: PreparedScene? = null
    private var currentVertexCount: Int = 0

    suspend fun renderLoop(
        androidSurface: Surface,
        surfaceWidth: Int,
        surfaceHeight: Int,
        preparedScene: State<PreparedScene?>,
        renderContextWidth: State<Int>,
        renderContextHeight: State<Int>,
        frameDelayMs: Long = 16L,
    ) {
        try {
            ensureInitialized(
                androidSurface = androidSurface,
                width = surfaceWidth,
                height = surfaceHeight,
            )

            while (currentCoroutineContext().isActive) {
                // F3: Reconfigure the surface if the viewport dimensions changed since
                // the last configure call. This handles window/fold resize without
                // waiting for AndroidExternalSurface to destroy and recreate.
                val ctxWidth = renderContextWidth.value
                val ctxHeight = renderContextHeight.value
                if (ctxWidth > 0 && ctxHeight > 0 &&
                    (ctxWidth != currentWidth || ctxHeight != currentHeight)) {
                    val ctx = gpuContext
                    if (ctx != null) {
                        ctx.withGpu {
                            gpuSurface?.unconfigure()
                            configureSurface(ctxWidth, ctxHeight)
                        }
                        Log.d(TAG, "Surface reconfigured to ${currentWidth}x${currentHeight}")
                    }
                }

                val scene = preparedScene.value
                if (scene !== lastScene) {
                    uploadScene(scene)
                    lastScene = scene
                }

                drawFrame(androidSurface)
                delay(frameDelayMs)
            }
        } finally {
            cleanup()
        }
    }

    private suspend fun ensureInitialized(
        androidSurface: Surface,
        width: Int,
        height: Int,
    ) {
        if (gpuContext != null && gpuSurface != null && renderPipeline != null && vertexBuffer != null) return

        // F2: Use getContextIfReady() (opportunistic, no-trigger) instead of
        // awaitContextForSharing() to avoid forcing compute backend GPU init when the user
        // has CPU compute selected. Only reuse a context that already exists.
        val sharedContext = (WebGpuComputeBackendHolder.instance as? WebGpuComputeBackend)
            ?.getContextIfReady()

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
            var createdSurface: GPUSurface? = null
            context = GpuContext.create { instance ->
                createdSurface = instance.createSurface(
                    GPUSurfaceDescriptor(
                        label = "IsometricSurface",
                        surfaceSourceAndroidNativeWindow =
                            GPUSurfaceSourceAndroidNativeWindow(windowFromSurface(androidSurface))
                    )
                )
                GPURequestAdapterOptions(
                    powerPreference = PowerPreference.HighPerformance,
                    backendType = BackendType.Vulkan,
                    compatibleSurface = createdSurface,
                )
            }
            ownsContext = true
            gpuSurface = checkNotNull(createdSurface)
        }

        gpuContext = context

        context.withGpu {
            configureSurface(width, height)
            renderPipeline = GpuRenderPipeline(context.device, surfaceFormat)
            vertexBuffer = GpuVertexBuffer(context.device, context.queue)
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
            val capabilities = surface.getCapabilities(ctx.adapter)

            require(capabilities.formats.isNotEmpty()) { "No supported surface formats returned" }
            require((capabilities.usages and TextureUsage.RenderAttachment) != 0) {
                "Surface does not support RenderAttachment usage"
            }

            surfaceFormat = when {
                capabilities.formats.contains(TextureFormat.BGRA8Unorm) -> TextureFormat.BGRA8Unorm
                capabilities.formats.contains(TextureFormat.RGBA8Unorm) -> TextureFormat.RGBA8Unorm
                else -> capabilities.formats.first()
            }
            presentMode = if (capabilities.presentModes.contains(PresentMode.Fifo)) {
                PresentMode.Fifo
            } else {
                capabilities.presentModes.firstOrNull() ?: PresentMode.Fifo
            }
            alphaMode = if (capabilities.alphaModes.contains(CompositeAlphaMode.Auto)) {
                CompositeAlphaMode.Auto
            } else {
                capabilities.alphaModes.firstOrNull() ?: CompositeAlphaMode.Auto
            }
        }

        currentWidth = width.coerceAtLeast(1)
        currentHeight = height.coerceAtLeast(1)

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
    }

    private suspend fun uploadScene(scene: PreparedScene?) {
        currentVertexCount = 0
        uploadedVertexBuffer = null

        if (scene == null) return

        val packed = triangulator.pack(scene)
        currentVertexCount = packed.vertexCount
        Log.d(TAG, "Uploading scene: commands=${scene.commands.size} vertices=$currentVertexCount scene=${scene.width}x${scene.height}")
        if (currentVertexCount == 0) return

        val ctx = checkNotNull(gpuContext)
        ctx.withGpu {
            uploadedVertexBuffer = vertexBuffer!!.upload(packed.buffer)
        }
    }

    private suspend fun drawFrame(androidSurface: Surface) {
        val ctx = checkNotNull(gpuContext)
        val surface = checkNotNull(gpuSurface)
        val pipeline = checkNotNull(renderPipeline)

        ctx.withGpu {
            ctx.checkHealthy()

            val surfaceTexture = surface.getCurrentTexture()
            when (surfaceTexture.status) {
                SurfaceGetCurrentTextureStatus.SuccessOptimal,
                SurfaceGetCurrentTextureStatus.SuccessSuboptimal -> {
                    val textureView = surfaceTexture.texture.createView()
                    val encoder = ctx.device.createCommandEncoder()
                    val pass = encoder.beginRenderPass(
                        GPURenderPassDescriptor(
                            colorAttachments = arrayOf(
                                GPURenderPassColorAttachment(
                                    clearValue = GPUColor(0.0, 0.0, 0.0, 0.0),
                                    view = textureView,
                                    loadOp = LoadOp.Clear,
                                    storeOp = StoreOp.Store,
                                )
                            )
                        )
                    )

                    pass.setPipeline(pipeline.pipeline)
                    val gpuVertexBuffer = uploadedVertexBuffer
                    if (currentVertexCount > 0 && gpuVertexBuffer != null) {
                        pass.setVertexBuffer(0, gpuVertexBuffer)
                        pass.draw(currentVertexCount)
                    }
                    pass.end()
                    pass.close()

                    val commandBuffer = encoder.finish()
                    ctx.queue.submit(arrayOf(commandBuffer))
                    commandBuffer.close()
                    encoder.close()
                    surface.present()

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

    private suspend fun reconfigureSurface() {
        val surface = gpuSurface ?: return
        surface.unconfigure()
        configureSurface(currentWidth, currentHeight)
    }

    private suspend fun recreateSurface(androidSurface: Surface) {
        val ctx = checkNotNull(gpuContext)
        gpuSurface?.unconfigure()
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
                    gpuSurface?.unconfigure()
                    renderPipeline?.close()
                    vertexBuffer?.close()
                    gpuSurface?.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "cleanup: GPU teardown skipped — context already destroyed", e)
            }
            // Only destroy if we own the context. When shared with WebGpuComputeBackend,
            // the compute backend owns the lifecycle and will destroy it.
            if (ownsContext) {
                ctx.destroy()
            }
        }
        renderPipeline = null
        vertexBuffer = null
        uploadedVertexBuffer = null
        gpuSurface = null
        gpuContext = null
        ownsContext = true
        lastScene = null
        currentVertexCount = 0
    }

    override fun close() {
        cleanup()
    }
}
