package io.github.jayteealao.isometric.webgpu.pipeline

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.TextureSource
import io.github.jayteealao.isometric.shapes.PyramidFace
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

    // ── Per-face `resolveEffectiveColor` coverage (BL-2 from review) ──────────
    //
    // Before this pair of tests existed, `SceneDataPacker.resolveEffectiveColor` had
    // zero unit coverage — the only PerFace-related dispatch in the whole packer was
    // untested. A regression that returned `cmd.baseColor` for all PerFace cases
    // (reintroducing the I-03 "flat-gray pyramid" bug on WebGPU) would pass CI.

    @Test
    fun `packInto writes per-face IsoColor for PerFace Pyramid with IsoColor slot`() {
        // A pyramid lateral face carrying IsoColor.RED. Expected: the packed vec4 at
        // offset 96 is red — NOT the PerFace default gray, NOT the command's baseColor.
        val path = trianglePath()
        val red = IsoColor(255.0, 0.0, 0.0, 255.0)
        val grayBase = IsoColor(128.0, 128.0, 128.0, 255.0)
        val material = IsometricMaterial.PerFace.Pyramid(
            laterals = mapOf(PyramidFace.Lateral(0) to red),
            default = grayBase,
        )
        val command = RenderCommand(
            commandId = "pyramidPerFaceIsoColor",
            points = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0),
            color = grayBase,
            originalPath = path,
            originalShape = null,
            baseColor = grayBase,
            material = material,
            faceType = PyramidFace.Lateral(0),
            faceVertexCount = 3,
        )

        val packed = packSingle(command)

        packed.position(96)
        assertEquals(1.0f, packed.getFloat(), "R is 255/255 = 1.0 (red, not default gray)")
        assertEquals(0.0f, packed.getFloat(), "G is 0/255 = 0.0")
        assertEquals(0.0f, packed.getFloat(), "B is 0/255 = 0.0")
        assertEquals(1.0f, packed.getFloat(), "A is 255/255 = 1.0")
    }

    @Test
    fun `packInto writes Textured tint for PerFace Pyramid with Textured slot`() {
        // A pyramid lateral face carrying Textured(tint=BLUE). The fragment shader
        // multiplies `sample * vertex.color`, so the per-face tint must arrive in the
        // packed vertex-color slot or textured faces render uniformly darkened with
        // the PerFace default.
        val path = trianglePath()
        val blueTint = IsoColor(0.0, 0.0, 255.0, 255.0)
        val grayDefault = IsoColor(128.0, 128.0, 128.0, 255.0)
        val material = IsometricMaterial.PerFace.Pyramid(
            laterals = mapOf(
                PyramidFace.Lateral(0) to IsometricMaterial.Textured(
                    source = TextureSource.Resource(1),
                    tint = blueTint,
                ),
            ),
            default = grayDefault,
        )
        val command = RenderCommand(
            commandId = "pyramidPerFaceTextured",
            points = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0),
            color = grayDefault,
            originalPath = path,
            originalShape = null,
            baseColor = grayDefault,
            material = material,
            faceType = PyramidFace.Lateral(0),
            faceVertexCount = 3,
        )

        val packed = packSingle(command)

        packed.position(96)
        assertEquals(0.0f, packed.getFloat(), "R is tint.r / 255 = 0.0 (blue tint, not default gray)")
        assertEquals(0.0f, packed.getFloat(), "G is tint.g / 255 = 0.0")
        assertEquals(1.0f, packed.getFloat(), "B is tint.b / 255 = 1.0")
        assertEquals(1.0f, packed.getFloat(), "A is tint.a / 255 = 1.0")
    }

    private fun trianglePath(): Path = Path(
        listOf(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            Point(1.0, 1.0, 0.0),
        ),
    )

    private fun packSingle(command: RenderCommand): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(SceneDataLayout.FACE_DATA_BYTES)
            .order(ByteOrder.nativeOrder())
        SceneDataPacker.packInto(listOf(command), buffer)
        return buffer
    }
}
