package io.github.jayteealao.isometric.webgpu.pipeline

import android.util.Log
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUInstance
import androidx.webgpu.GPUPassTimestampWrites
import androidx.webgpu.GPUQueue
import androidx.webgpu.GPUQuerySet
import androidx.webgpu.GPUQuerySetDescriptor
import androidx.webgpu.GPURequestCallback
import androidx.webgpu.MapMode
import androidx.webgpu.QueryType
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages GPU timestamp queries for per-pass profiling of the WebGPU compute+render pipeline.
 *
 * Uses double-buffered resolve+readback buffers to avoid stalling the render loop:
 * frame N writes timestamps to buffer set A while reading back buffer set B (from frame N-1).
 * Timestamps are delivered one frame late via [readResults].
 *
 * Slot layout (5 pass pairs = 10 timestamp slots):
 * - 0,1 = M3 (transform-cull-light)
 * - 2,3 = M4a (pack-sort-keys)
 * - 4,5 = M4b (bitonic sort)
 * - 6,7 = M5 (triangulate-emit)
 * - 8,9 = render pass
 */
internal class GpuTimestampProfiler(
    private val device: GPUDevice,
    private val instance: GPUInstance,
    private val queue: GPUQueue,
) : AutoCloseable {

    private val querySet: GPUQuerySet = device.createQuerySet(
        GPUQuerySetDescriptor(type = QueryType.Timestamp, count = QUERY_COUNT)
    )

    // Double-buffered: two sets of resolve + readback buffers
    private val resolveBuffers = Array(2) {
        device.createBuffer(GPUBufferDescriptor(
            size = BUFFER_SIZE,
            usage = BufferUsage.QueryResolve or BufferUsage.CopySrc,
            label = "timestamp-resolve-$it",
        ))
    }
    private val readbackBuffers = Array(2) {
        device.createBuffer(GPUBufferDescriptor(
            size = BUFFER_SIZE,
            usage = BufferUsage.CopyDst or BufferUsage.MapRead,
            label = "timestamp-readback-$it",
        ))
    }

    /** Index of the buffer set currently being written to (0 or 1). */
    private var writeIndex = 0

    /** Whether the previous frame's readback buffer has data ready to read. */
    private var previousFrameReady = false

    /** Get [GPUPassTimestampWrites] for a given pass (by slot pair index 0–4). */
    fun timestampWritesFor(slotPairIndex: Int): GPUPassTimestampWrites {
        require(slotPairIndex in 0 until PASS_COUNT) {
            "slotPairIndex must be 0..${ PASS_COUNT - 1 }, got $slotPairIndex"
        }
        val beginIdx = slotPairIndex * 2
        val endIdx = beginIdx + 1
        return GPUPassTimestampWrites(
            querySet = querySet,
            beginningOfPassWriteIndex = beginIdx,
            endOfPassWriteIndex = endIdx,
        )
    }

    /**
     * Encode resolve + copy commands after all passes are done.
     * Call on the command encoder AFTER all compute/render passes have ended.
     */
    fun encodeResolveAndCopy(encoder: GPUCommandEncoder) {
        val resolve = resolveBuffers[writeIndex]
        val readback = readbackBuffers[writeIndex]
        encoder.resolveQuerySet(querySet, 0, QUERY_COUNT, resolve, 0L)
        encoder.copyBufferToBuffer(resolve, 0L, readback, 0L, BUFFER_SIZE)
    }

    /**
     * Swap buffer sets. Call after submitting the frame's command buffers.
     * The current write buffer becomes the next frame's read buffer.
     */
    fun swapBuffers() {
        previousFrameReady = true
        writeIndex = 1 - writeIndex
    }

    /**
     * Read back timestamp results from the previous frame's buffer.
     * Must be called on the GPU thread (inside [GpuContext.withGpu]).
     *
     * Uses raw [mapAsync] + explicit [GPUInstance.processEvents] pumping instead of
     * [mapAndAwait] to avoid deadlocking the single GPU thread. [mapAndAwait] suspends
     * waiting for the map callback, but the callback is delivered via [processEvents]
     * which runs on the same single GPU thread — causing starvation.
     *
     * @return [GpuTimestampResult] with per-pass durations in nanoseconds,
     *   or null if no previous frame data is available or readback fails.
     */
    fun readResults(): GpuTimestampResult? {
        if (!previousFrameReady) return null

        val readIndex = 1 - writeIndex  // previous frame's buffer
        val readback = readbackBuffers[readIndex]

        return try {
            // Step 1: Wait for GPU to finish the resolve+copy submitted last frame.
            // Uses raw onSubmittedWorkDone + processEvents pumping (not the suspend
            // version) to avoid deadlocking the single GPU thread.
            if (!awaitCallback("workDone") { executor, callback ->
                queue.onSubmittedWorkDone(executor, callback)
            }) return null

            // Step 2: Map the readback buffer for CPU read access.
            if (!awaitCallback("mapAsync") { executor, callback ->
                readback.mapAsync(MapMode.Read, 0L, BUFFER_SIZE, executor, callback)
            }) {
                try { readback.unmap() } catch (_: Throwable) {}
                return null
            }

            // Step 3: Read timestamps from the mapped buffer.
            val mapped = readback.getMappedRange(0L, BUFFER_SIZE)
            val byteBuffer = mapped.order(ByteOrder.LITTLE_ENDIAN)
            val timestamps = LongArray(QUERY_COUNT) { byteBuffer.getLong() }
            readback.unmap()

            GpuTimestampResult(
                transformCullLightNanos = timestamps[1] - timestamps[0],
                packSortKeysNanos = timestamps[3] - timestamps[2],
                bitonicSortNanos = timestamps[5] - timestamps[4],
                triangulateEmitNanos = timestamps[7] - timestamps[6],
                renderPassNanos = timestamps[9] - timestamps[8],
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Timestamp readback failed: ${e.message}")
            try { readback.unmap() } catch (_: Throwable) {}
            null
        }
    }

    /**
     * Issue a GPU async request and pump [processEvents] until the callback fires.
     * Returns true if the callback succeeded, false on error or timeout.
     */
    private inline fun awaitCallback(
        label: String,
        request: (Executor, GPURequestCallback<Unit>) -> Unit,
    ): Boolean {
        val done = AtomicBoolean(false)
        val error = AtomicBoolean(false)
        request(Executor(Runnable::run), object : GPURequestCallback<Unit> {
            override fun onResult(result: Unit) { done.set(true) }
            override fun onError(exception: Exception) {
                Log.w(TAG, "$label error: ${exception.message}")
                error.set(true)
                done.set(true)
            }
        })
        var iterations = 0
        while (!done.get() && iterations < MAX_POLL_ITERATIONS) {
            instance.processEvents()
            iterations++
        }
        if (!done.get() || error.get()) {
            Log.w(TAG, "$label failed: done=${done.get()} error=${error.get()} iters=$iterations")
            return false
        }
        return true
    }

    override fun close() {
        querySet.close()
        resolveBuffers.forEach { it.close() }
        readbackBuffers.forEach { it.close() }
    }

    companion object {
        private const val TAG = "GpuTimestampProfiler"
        /** 5 pass pairs: M3, M4a, M4b, M5, render */
        const val PASS_COUNT = 5
        const val QUERY_COUNT = PASS_COUNT * 2
        /** Each timestamp is a u64 (8 bytes) */
        private const val BUFFER_SIZE = QUERY_COUNT * 8L
        /** Max processEvents iterations before giving up on a map callback. */
        private const val MAX_POLL_ITERATIONS = 100
    }
}

/**
 * Per-frame GPU timestamp results with per-pass breakdown.
 * All durations are in nanoseconds (GPU clock domain).
 */
data class GpuTimestampResult(
    val transformCullLightNanos: Long,
    val packSortKeysNanos: Long,
    val bitonicSortNanos: Long,
    val triangulateEmitNanos: Long,
    val renderPassNanos: Long,
) {
    val totalComputeNanos: Long
        get() = transformCullLightNanos + packSortKeysNanos + bitonicSortNanos + triangulateEmitNanos
    val totalGpuNanos: Long
        get() = totalComputeNanos + renderPassNanos
}
