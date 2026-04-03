package io.github.jayteealao.isometric.webgpu.pipeline

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class SceneDataPackerTest {

    @Test
    fun `packInto preserves six vertices and stores vertexCount in final slot`() {
        val path = Path(
            listOf(
                Point(0.0, 0.0, 0.0),
                Point(1.0, 0.0, 0.0),
                Point(1.0, 1.0, 0.0),
                Point(0.0, 1.0, 0.0),
                Point(-1.0, 1.0, 0.0),
                Point(-1.0, 0.0, 0.0),
            )
        )
        val command = RenderCommand(
            commandId = "hex",
            points = doubleArrayOf(
                0.0, 0.0,
                1.0, 0.0,
                1.0, 1.0,
                0.0, 1.0,
                -1.0, 1.0,
                -1.0, 0.0,
            ),
            color = IsoColor(255.0, 128.0, 64.0, 255.0),
            originalPath = path,
            originalShape = null,
        )

        val buffer = ByteBuffer
            .allocateDirect(SceneDataLayout.FACE_DATA_BYTES)
            .order(ByteOrder.nativeOrder())

        SceneDataPacker.packInto(listOf(command), buffer)

        assertEquals(SceneDataLayout.FACE_DATA_BYTES, buffer.limit())

        // v5.xyz starts at byte offset 80.
        buffer.position(80)
        assertEquals(-1.0f, buffer.getFloat())
        assertEquals(0.0f, buffer.getFloat())
        assertEquals(0.0f, buffer.getFloat())

        // vertexCount is packed into v5's padding slot at byte offset 92.
        assertEquals(6, buffer.getInt())
    }

    @Test
    fun `face data byte size matches six vertex layout`() {
        assertEquals(144, SceneDataLayout.FACE_DATA_BYTES)
    }
}
