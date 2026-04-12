package io.github.jayteealao.isometric.shader.render

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.compose.runtime.MaterialDrawHook
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.TextureSource

/**
 * [MaterialDrawHook] implementation that draws textured faces using `BitmapShader` +
 * affine matrix mapping.
 *
 * For each [RenderCommand] with an [IsometricMaterial.Textured] material, this hook:
 * 1. Resolves the texture from cache or loads it (falling back to checkerboard on failure)
 * 2. Computes a 3-point affine matrix mapping UV texture space to screen space
 * 3. Draws the face path with a `BitmapShader`-configured `Paint`
 *
 * [IsometricMaterial.FlatColor] commands return `false` (delegate to flat-color path).
 * [IsometricMaterial.PerFace] commands resolve the default sub-material.
 *
 * All mutable state (`Paint`, `Matrix`) is held as fields and reused — zero per-frame
 * allocation in the draw loop.
 */
internal class TexturedCanvasDrawHook(
    private val cache: TextureCache,
    private val loader: TextureLoader,
) : MaterialDrawHook {

    private val texturedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val affineMatrix = Matrix()
    private val checkerboard: Bitmap by lazy { createCheckerboardBitmap() }

    override fun draw(
        nativeCanvas: android.graphics.Canvas,
        command: RenderCommand,
        nativePath: android.graphics.Path,
    ): Boolean {
        val material = command.material as? IsometricMaterial ?: return false

        return when (material) {
            is IsometricMaterial.FlatColor -> false
            is IsometricMaterial.Textured -> drawTextured(nativeCanvas, command, nativePath, material)
            is IsometricMaterial.PerFace -> {
                val sub = material.default
                if (sub is IsometricMaterial.Textured) {
                    drawTextured(nativeCanvas, command, nativePath, sub)
                } else {
                    false
                }
            }
        }
    }

    private fun drawTextured(
        nativeCanvas: android.graphics.Canvas,
        command: RenderCommand,
        nativePath: android.graphics.Path,
        material: IsometricMaterial.Textured,
    ): Boolean {
        val uvCoords = command.uvCoords
        if (uvCoords == null || uvCoords.size < 6) return false

        val cached = resolveTexture(material.source)
        val texW = cached.bitmap.width
        val texH = cached.bitmap.height

        computeAffineMatrix(uvCoords, command.points, texW, texH, affineMatrix)
        cached.shader.setLocalMatrix(affineMatrix)

        texturedPaint.shader = cached.shader
        texturedPaint.colorFilter = material.tint.toColorFilterOrNull()

        nativeCanvas.drawPath(nativePath, texturedPaint)

        texturedPaint.shader = null
        texturedPaint.colorFilter = null

        return true
    }

    private fun resolveTexture(source: TextureSource): CachedTexture {
        return cache.get(source) ?: run {
            val bitmap = loader.load(source) ?: checkerboard
            cache.put(source, bitmap)
        }
    }
}

/**
 * Computes the affine [Matrix] that maps texture UV space to screen space for a single
 * isometric face.
 *
 * Uses [Matrix.setPolyToPoly] with 3 control points — sufficient for the affine transform
 * that parallelogram faces require. The 4-point variant would compute a projective transform
 * which is numerically less stable and unnecessary for isometric geometry.
 *
 * @param uvCoords Per-vertex UV pairs `[u0,v0, u1,v1, u2,v2, ...]` from [RenderCommand.uvCoords].
 *   At least 6 values (3 UV pairs) must be present.
 * @param screenPoints Screen-space 2D vertices `[x0,y0, x1,y1, ...]` from [RenderCommand.points]
 *   as `DoubleArray`. Converted to Float inline.
 * @param texWidth Width of the source bitmap in pixels.
 * @param texHeight Height of the source bitmap in pixels.
 * @param outMatrix The matrix to write into (reused across calls — no allocation).
 */
internal fun computeAffineMatrix(
    uvCoords: FloatArray,
    screenPoints: DoubleArray,
    texWidth: Int,
    texHeight: Int,
    outMatrix: Matrix,
) {
    val src = floatArrayOf(
        uvCoords[0] * texWidth, uvCoords[1] * texHeight,
        uvCoords[2] * texWidth, uvCoords[3] * texHeight,
        uvCoords[4] * texWidth, uvCoords[5] * texHeight,
    )
    val dst = floatArrayOf(
        screenPoints[0].toFloat(), screenPoints[1].toFloat(),
        screenPoints[2].toFloat(), screenPoints[3].toFloat(),
        screenPoints[4].toFloat(), screenPoints[5].toFloat(),
    )
    outMatrix.setPolyToPoly(src, 0, dst, 0, 3)
}

/**
 * Generates the canonical 16x16 magenta/black missing-texture indicator.
 *
 * Cell size: 8x8 pixels. Colors: magenta (`#FF00FF`) and black (`#000000`).
 * This is the industry-standard "missing texture" pattern (Source Engine, Unity, Minecraft).
 *
 * Called once (lazily) per [TexturedCanvasDrawHook] instance; the result is reused indefinitely.
 */
internal fun createCheckerboardBitmap(): Bitmap {
    val size = 16
    val cellSize = 8
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val isMagenta = ((x / cellSize) + (y / cellSize)) % 2 == 0
            pixels[y * size + x] = if (isMagenta) 0xFFFF00FF.toInt() else 0xFF000000.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * Returns a multiplicative color filter for the given tint, or `null` for white (no-op).
 *
 * Returning `null` for white avoids a GPU state change on the common case where no
 * tint is applied.
 */
internal fun IsoColor.toColorFilterOrNull(): PorterDuffColorFilter? {
    if (r >= 254.0 && g >= 254.0 && b >= 254.0) return null
    return PorterDuffColorFilter(
        android.graphics.Color.argb(
            255,
            r.toInt().coerceIn(0, 255),
            g.toInt().coerceIn(0, 255),
            b.toInt().coerceIn(0, 255),
        ),
        PorterDuff.Mode.MULTIPLY,
    )
}
