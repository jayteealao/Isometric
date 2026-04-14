package io.github.jayteealao.isometric.shader.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.compose.runtime.MaterialDrawHook
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.TextureSource
import io.github.jayteealao.isometric.shader.TextureTransform

/**
 * [MaterialDrawHook] implementation that draws textured faces using `BitmapShader` +
 * affine matrix mapping.
 *
 * For each [RenderCommand] with an [IsometricMaterial.Textured] material, this hook:
 * 1. Resolves the texture from cache or loads it (falling back to checkerboard on failure)
 * 2. Creates (or retrieves cached) a [BitmapShader] with the correct [Shader.TileMode]
 *    based on the material's [TextureTransform]
 * 3. Computes a 3-point affine matrix mapping UV texture space to screen space,
 *    compositing the [TextureTransform] into the matrix
 * 4. Draws the face path with a `BitmapShader`-configured `Paint`
 *
 * [IsometricMaterial.PerFace] commands resolve their sub-material per face;
 * non-textured sub-materials (e.g. [IsoColor]) delegate to the flat-color path.
 *
 * All mutable state (`Paint`, `Matrix`) is held as fields and reused — zero per-frame
 * allocation in the draw loop.
 */
internal class TexturedCanvasDrawHook(
    private val cache: TextureCache,
    private val loader: TextureLoader,
    private val onTextureLoadError: ((TextureSource) -> Unit)? = null,
) : MaterialDrawHook {

    private val texturedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val affineMatrix = Matrix()
    private val checkerboard: Bitmap by lazy { createCheckerboardBitmap() }

    /**
     * Per-[Shader.TileMode] BitmapShader cache. Keyed by `(TextureSource, tileU)`.
     * Using tileU as the representative tile mode is valid for the common case where
     * tileU == tileV (uniform tiling). Asymmetric tiling is a documented future enhancement.
     */
    private val shaderCache = LinkedHashMap<Pair<TextureSource, Shader.TileMode>, BitmapShader>()

    override fun draw(
        nativeCanvas: android.graphics.Canvas,
        command: RenderCommand,
        nativePath: android.graphics.Path,
    ): Boolean {
        val material = command.material as? IsometricMaterial ?: return false

        return when (material) {
            is IsometricMaterial.Textured -> drawTextured(nativeCanvas, command, nativePath, material)
            is IsometricMaterial.PerFace -> {
                val face = command.faceType
                val sub = if (face != null) material.resolve(face) else material.default
                when (sub) {
                    is IsometricMaterial.Textured -> drawTextured(nativeCanvas, command, nativePath, sub)
                    // IsoColor or other MaterialData — delegate to the flat-color draw path
                    else -> false
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

        val cached = resolveToCache(material.source)
        val texW = cached.bitmap.width
        val texH = cached.bitmap.height

        val tileU = if (material.transform.scaleU != 1f) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
        val tileV = if (material.transform.scaleV != 1f) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
        val shaderKey = material.source to tileU
        val shader = shaderCache.getOrPut(shaderKey) {
            BitmapShader(cached.bitmap, tileU, tileV)
        }

        computeAffineMatrix(uvCoords, command.points, texW, texH, material.transform, affineMatrix)
        shader.setLocalMatrix(affineMatrix)

        texturedPaint.shader = shader
        texturedPaint.colorFilter = material.tint.toColorFilterOrNull()
        try {
            nativeCanvas.drawPath(nativePath, texturedPaint)
        } finally {
            texturedPaint.shader = null
            texturedPaint.colorFilter = null
        }

        return true
    }

    private fun resolveToCache(source: TextureSource): CachedTexture {
        cache.get(source)?.let { return it }
        val bitmap = loader.load(source)
        return if (bitmap != null) {
            cache.put(source, bitmap)
        } else {
            onTextureLoadError?.invoke(source)
            // Cache the checkerboard under the failed source key to avoid repeated load attempts.
            cache.put(source, checkerboard)
        }
    }
}

/**
 * Computes the affine [Matrix] that maps texture UV space to screen space for a single
 * isometric face, with an optional [TextureTransform] applied in UV space.
 *
 * Uses [Matrix.setPolyToPoly] with 3 control points — sufficient for the affine transform
 * that parallelogram faces require.
 *
 * **Matrix math:** `BitmapShader.setLocalMatrix(M)` causes the shader to sample
 * `bitmap[M⁻¹ * screenPos]`. After `setPolyToPoly(src, dst)`, M maps UV-pixel-space to
 * screen-space. Pre-concatenating `T⁻¹` gives `M * T⁻¹`, whose inverse is `T * M⁻¹`.
 * The shader then samples `T(M⁻¹ * screenPos) = T(UV)` — the UV transform is applied
 * in UV space as intended.
 *
 * @param uvCoords Per-vertex UV pairs `[u0,v0, u1,v1, u2,v2, ...]` from [RenderCommand.uvCoords].
 *   At least 6 values (3 UV pairs) must be present.
 * @param screenPoints Screen-space 2D vertices `[x0,y0, x1,y1, ...]` from [RenderCommand.points]
 *   as `DoubleArray`. Converted to Float inline.
 * @param texWidth Width of the source bitmap in pixels.
 * @param texHeight Height of the source bitmap in pixels.
 * @param transform UV transform to composite into the matrix.
 * @param outMatrix The matrix to write into (reused across calls — no allocation).
 */
internal fun computeAffineMatrix(
    uvCoords: FloatArray,
    screenPoints: DoubleArray,
    texWidth: Int,
    texHeight: Int,
    transform: TextureTransform,
    outMatrix: Matrix,
) {
    if (screenPoints.size < 6) {
        outMatrix.reset()
        return
    }
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

    if (transform != TextureTransform.IDENTITY) {
        // Build T in texture-pixel space: scale (around center) → rotate (around center) → translate
        val cx = texWidth / 2f
        val cy = texHeight / 2f
        val t = Matrix()
        t.setScale(transform.scaleU, transform.scaleV, cx, cy)
        if (transform.rotationDegrees != 0f) {
            t.postRotate(transform.rotationDegrees, cx, cy)
        }
        if (transform.offsetU != 0f || transform.offsetV != 0f) {
            t.postTranslate(transform.offsetU * texWidth, transform.offsetV * texHeight)
        }
        // Pre-concat T^-1: M_final = M_poly * T^-1
        val tInv = Matrix()
        if (t.invert(tInv)) {
            outMatrix.preConcat(tInv)
        }
        // If inversion fails (degenerate transform), the identity transform is used instead.
        // TextureTransform.init ensures scaleU/scaleV are non-zero, so this should not occur.
    }
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
    if (r >= 255.0 && g >= 255.0 && b >= 255.0) return null
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
