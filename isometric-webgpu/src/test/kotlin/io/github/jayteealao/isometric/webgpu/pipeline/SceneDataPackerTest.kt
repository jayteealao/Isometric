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
    fun `packInto writes baseColor not color at byte offset 96`() {
        // Regression guard for the cmd.color → cmd.baseColor fix.
        // If a future change accidentally reverts to cmd.color, the packed bytes would
        // contain the lit color (0, 255, 0) instead of the raw material color (255, 0, 0),
        // and the GPU shader would apply lighting a second time (double-lighting).
        val path = Path(
            listOf(
                Point(0.0, 0.0, 0.0),
                Point(1.0, 0.0, 0.0),
                Point(1.0, 1.0, 0.0),
            )
        )
        val baseColor = IsoColor(255.0, 0.0, 0.0, 200.0)   // red — raw material color
        val litColor  = IsoColor(0.0, 255.0, 0.0, 255.0)   // green — lighting already applied

        val command = RenderCommand(
            commandId = "baseColorTest",
            points = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0),
            color = litColor,
            originalPath = path,
            originalShape = null,
            baseColor = baseColor,
        )

        val buffer = ByteBuffer
            .allocateDirect(SceneDataLayout.FACE_DATA_BYTES)
            .order(ByteOrder.nativeOrder())

        SceneDataPacker.packInto(listOf(command), buffer)

        // baseColor is written at byte offset 96 (vec4<f32>, 4 × 4 bytes = 16 bytes).
        buffer.position(96)
        val packedR = buffer.getFloat()
        val packedG = buffer.getFloat()
        val packedB = buffer.getFloat()
        val packedA = buffer.getFloat()

        assertEquals(baseColor.r.toFloat() / 255f, packedR,
            "packed R should match baseColor.r, not color.r")
        assertEquals(baseColor.g.toFloat() / 255f, packedG,
            "packed G should match baseColor.g, not color.g")
        assertEquals(baseColor.b.toFloat() / 255f, packedB,
            "packed B should match baseColor.b, not color.b")
        assertEquals(baseColor.a.toFloat() / 255f, packedA,
            "packed A should match baseColor.a, not color.a")
    }

    @Test
    fun `face data byte size matches six vertex layout`() {
        assertEquals(144, SceneDataLayout.FACE_DATA_BYTES)
    }
}
