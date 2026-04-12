package io.github.jayteealao.isometric.webgpu.triangulation

import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.webgpu.pipeline.SceneDataLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class RenderCommandTriangulator {
    companion object {
        /** u32 slots per vertex: pos(2) + color(4) + uv(2) + textureIndex(1) = 9. */
        const val U32S_PER_VERTEX = 9
        const val BYTES_PER_VERTEX = U32S_PER_VERTEX * 4  // 36 bytes
    }

    data class PackedVertices(
        val buffer: ByteBuffer,
        val vertexCount: Int,
    )

    private var stagingBuffer: ByteBuffer? = null

    fun pack(scene: PreparedScene): PackedVertices {
        val vertexCount = countVertices(scene)
        val requiredBytes = vertexCount * BYTES_PER_VERTEX
        val buffer = ensureBuffer(requiredBytes)
        buffer.clear()

        for (command in scene.commands) {
            val pointCount = command.pointCount
            if (pointCount < 3) continue

            val x0 = toNdcX(command.pointX(0), scene.width)
            val y0 = toNdcY(command.pointY(0), scene.height)
            val r = (command.color.r / 255.0).toFloat()
            val g = (command.color.g / 255.0).toFloat()
            val b = (command.color.b / 255.0).toFloat()
            val a = (command.color.a / 255.0).toFloat()

            for (i in 1 until pointCount - 1) {
                writeVertex(buffer, x0, y0, r, g, b, a)
                writeVertex(
                    buffer,
                    toNdcX(command.pointX(i), scene.width),
                    toNdcY(command.pointY(i), scene.height),
                    r, g, b, a
                )
                writeVertex(
                    buffer,
                    toNdcX(command.pointX(i + 1), scene.width),
                    toNdcY(command.pointY(i + 1), scene.height),
                    r, g, b, a
                )
            }
        }

        buffer.flip()
        return PackedVertices(buffer = buffer, vertexCount = vertexCount)
    }

    private fun ensureBuffer(requiredBytes: Int): ByteBuffer {
        val existing = stagingBuffer
        if (existing != null && existing.capacity() >= requiredBytes) {
            return existing
        }

        val grownCapacity = maxOf(requiredBytes, (existing?.capacity() ?: BYTES_PER_VERTEX) * 2)
        return ByteBuffer.allocateDirect(grownCapacity)
            .order(ByteOrder.nativeOrder())
            .also { stagingBuffer = it }
    }

    private fun countVertices(scene: PreparedScene): Int {
        var vertexCount = 0
        for (command in scene.commands) {
            val pointCount = command.pointCount
            if (pointCount >= 3) {
                vertexCount += (pointCount - 2) * 3
            }
        }
        return vertexCount
    }

    private fun writeVertex(
        buffer: ByteBuffer,
        x: Float,
        y: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        u: Float = 0f,
        v: Float = 0f,
        textureIndex: Int = SceneDataLayout.NO_TEXTURE,
    ) {
        buffer.putFloat(x)
        buffer.putFloat(y)
        buffer.putFloat(r)
        buffer.putFloat(g)
        buffer.putFloat(b)
        buffer.putFloat(a)
        buffer.putFloat(u)
        buffer.putFloat(v)
        buffer.putInt(textureIndex)
    }

    private fun toNdcX(x: Double, width: Int): Float =
        ((x / width.toDouble()) * 2.0 - 1.0).toFloat()

    private fun toNdcY(y: Double, height: Int): Float =
        (1.0 - (y / height.toDouble()) * 2.0).toFloat()
}
