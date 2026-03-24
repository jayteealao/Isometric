package io.github.jayteealao.isometric.webgpu.pipeline

import android.util.Log
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUQueue
import io.github.jayteealao.isometric.RenderCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages the GPU-side `FaceData` storage buffer for Phase 3 scene upload.
 *
 * On each dirty frame the CPU packs [RenderCommand] data into the [SceneDataPacker]
 * byte format and uploads it via [GPUQueue.writeBuffer]. This class owns both the
 * [GPUBuffer] and a reusable CPU-side [ByteBuffer], handling geometric capacity growth
 * on both sides so that allocations are as rare as possible.
 *
 * ## Buffer usage flags
 *
 * The GPU buffer is created with `Storage | CopyDst`:
 * - `Storage` — bound as `var<storage, read>` in the Transform+Cull+Light compute shader
 * - `CopyDst` — allows [GPUQueue.writeBuffer] uploads from the CPU
 *
 * ## Geometric growth
 *
 * Capacity is always a multiple of [INITIAL_CAPACITY_FACES] and grows by 2× when the
 * face count exceeds current capacity. This bounds the number of allocations to
 * O(log N) over the lifetime of the buffer, for both the GPU and CPU buffers.
 *
 * ## Lifetime
 *
 * Create once per [WebGpuSceneRenderer] session. Call [close] when the renderer tears
 * down to release the GPU buffer. [upload] may be called from the GPU thread only
 * (inside a `ctx.withGpu` block).
 */
internal class GpuSceneDataBuffer(
    private val device: GPUDevice,
    private val queue: GPUQueue,
) : AutoCloseable {

    companion object {
        private const val TAG = "GpuSceneDataBuffer"
        private const val INITIAL_CAPACITY_FACES = 64
    }

    private var gpuBuffer: GPUBuffer? = null
    private var capacityFaces: Int = 0

    /** Reusable CPU-side pack buffer; grown geometrically alongside the GPU buffer. */
    private var cpuBuffer: ByteBuffer? = null
    private var cpuCapacityFaces: Int = 0

    /** Number of faces in the most recent [upload] call, or 0 if never uploaded. */
    var faceCount: Int = 0
        private set

    /**
     * The underlying [GPUBuffer], available after the first successful [upload].
     *
     * Bind this as `@binding(0) var<storage, read> scene: array<FaceData>` in the
     * Transform+Cull+Light compute shader.
     *
     * This reference is stable across [upload] calls as long as the face count stays
     * below [capacityFaces]. If the buffer grows, the reference changes — callers that
     * cache this value must re-query it after each [upload].
     */
    val buffer: GPUBuffer? get() = gpuBuffer

    /**
     * Pack [commands] and upload the result to the GPU scene-data buffer.
     *
     * If the face count exceeds the current buffer capacity both the GPU buffer and the
     * CPU pack buffer are grown to at least 2× the required capacity.
     *
     * Must be called from the GPU thread (inside `ctx.withGpu { ... }`).
     *
     * @param commands Render commands for this frame; may be empty.
     */
    fun upload(commands: List<RenderCommand>) {
        faceCount = commands.size
        if (faceCount == 0) return

        ensureCapacity(faceCount)

        // Pack into the reusable CPU buffer, then DMA to the GPU buffer.
        val packed = checkNotNull(cpuBuffer)
        SceneDataPacker.packInto(commands, packed)
        queue.writeBuffer(checkNotNull(gpuBuffer), 0L, packed)
    }

    private fun ensureCapacity(requiredFaces: Int) {
        val needsGrow = requiredFaces > capacityFaces || gpuBuffer == null
        if (!needsGrow) return

        val newCapacity = maxOf(requiredFaces, maxOf(INITIAL_CAPACITY_FACES, capacityFaces * 2))

        // Grow GPU buffer.
        gpuBuffer?.destroy()
        gpuBuffer?.close()
        capacityFaces = newCapacity
        val byteSize = capacityFaces.toLong() * SceneDataLayout.FACE_DATA_BYTES
        gpuBuffer = device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Storage or BufferUsage.CopyDst,
                size = byteSize,
            )
        ).also {
            it.setLabel("IsometricSceneDataBuffer")
            Log.d(TAG, "Allocated scene data buffer: capacity=$capacityFaces faces ($byteSize bytes)")
        }

        // Grow CPU pack buffer to match.
        cpuCapacityFaces = newCapacity
        cpuBuffer = ByteBuffer
            .allocateDirect(cpuCapacityFaces * SceneDataLayout.FACE_DATA_BYTES)
            .order(ByteOrder.nativeOrder())
    }

    override fun close() {
        gpuBuffer?.destroy()
        gpuBuffer?.close()
        gpuBuffer = null
        capacityFaces = 0
        cpuBuffer = null
        cpuCapacityFaces = 0
        faceCount = 0
    }
}
