package io.github.jayteealao.isometric.webgpu.pipeline

import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import io.github.jayteealao.isometric.webgpu.GpuContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encapsulates a growable GPU storage buffer paired with a CPU-side staging [ByteBuffer].
 *
 * Both the GPU buffer and the CPU buffer use x2 over-allocation to amortise per-frame
 * reallocations when entry counts grow gradually.
 *
 * @param ctx    The [GpuContext] used to create and destroy the GPU buffer.
 * @param label  Debug label applied to the underlying [GPUBuffer].
 * @param usage  [BufferUsage] flags for the GPU buffer (default: `Storage | CopyDst`).
 */
internal class GrowableGpuStagingBuffer(
    private val ctx: GpuContext,
    private val label: String,
    private val usage: Int = BufferUsage.Storage or BufferUsage.CopyDst,
) {
    var gpuBuffer: GPUBuffer? = null; private set
    var cpuBuffer: ByteBuffer? = null; private set
    var capacity: Int = 0; private set

    /**
     * Ensure both the GPU buffer and the CPU staging buffer can hold at least
     * [entryCount] entries of [entryBytes] bytes each.
     *
     * If the current GPU capacity is smaller than [entryCount], the existing GPU
     * buffer is closed and a new one is created with capacity `entryCount * 2`.
     * If the CPU buffer is null or too small for `entryCount * entryBytes` bytes,
     * a new direct [ByteBuffer] is allocated with size `entryCount * entryBytes * 2`.
     */
    fun ensureCapacity(entryCount: Int, entryBytes: Int) {
        if (entryCount > capacity) {
            gpuBuffer?.close()
            val newCapacity = entryCount.toLong() * 2L
            gpuBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = usage,
                    size = newCapacity * entryBytes.toLong(),
                )
            ).also { it.setLabel(label) }
            capacity = newCapacity.toInt()
        }
        val requiredBytes = entryCount.toLong() * entryBytes.toLong()
        val cpu = cpuBuffer
        if (cpu == null || cpu.capacity().toLong() < requiredBytes) {
            cpuBuffer = ByteBuffer.allocateDirect((requiredBytes * 2L).toInt()).order(ByteOrder.nativeOrder())
        }
    }

    /**
     * Close the underlying GPU buffer and reset capacity to zero.
     * The CPU buffer is left for GC; [ByteBuffer.allocateDirect] memory is freed by the JVM.
     */
    fun close() {
        gpuBuffer?.close()
        gpuBuffer = null
        capacity = 0
    }
}
