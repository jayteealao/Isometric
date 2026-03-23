package io.github.jayteealao.isometric.webgpu.pipeline

import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUQueue
import java.nio.ByteBuffer

internal class GpuVertexBuffer(
    private val device: GPUDevice,
    private val queue: GPUQueue,
) : AutoCloseable {
    private var buffer: GPUBuffer? = null
    private var capacityBytes: Int = 0

    fun upload(data: ByteBuffer): GPUBuffer {
        val requiredBytes = data.remaining()
        ensureCapacity(requiredBytes)
        val gpuBuffer = checkNotNull(buffer)
        queue.writeBuffer(gpuBuffer, 0L, data)
        return gpuBuffer
    }

    private fun ensureCapacity(requiredBytes: Int) {
        if (capacityBytes >= requiredBytes && buffer != null) return

        buffer?.destroy()
        buffer?.close()

        capacityBytes = maxOf(requiredBytes, maxOf(1024, capacityBytes * 2))
        buffer = device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Vertex or BufferUsage.CopyDst,
                size = capacityBytes.toLong(),
            )
        ).also {
            it.setLabel("IsometricVertexBuffer")
        }
    }

    override fun close() {
        buffer?.destroy()
        buffer?.close()
        buffer = null
        capacityBytes = 0
    }
}
