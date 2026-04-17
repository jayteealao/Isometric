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
import io.github.jayteealao.isometric.shader.resolveForFace
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
    private val flatPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val affineMatrix = Matrix()
    private val transformMatrix = Matrix()
    private val transformMatrixInv = Matrix()
    private val matrixSrc = FloatArray(6)
    private val matrixDst = FloatArray(6)
    private val checkerboard: Bitmap by lazy { createCheckerboardBitmap() }

    /**
     * Per-[Shader.TileMode] BitmapShader cache. Keyed by `(Bitmap, tileU, tileV)`.
     * Using the [Bitmap] instance (rather than [TextureSource]) as the first key component
     * ensures a cache miss whenever [TextureCache] evicts and reloads a texture — the new
     * [Bitmap] instance will not match the old key, so a fresh [BitmapShader] is created
     * from the new bitmap instead of returning a shader backed by a recycled/evicted bitmap.
     * Both tile modes are included in the key so that asymmetric tiling (tileU != tileV)
     * never collides with a uniformly-tiled shader.
     */
    private val shaderCache = object : LinkedHashMap<Triple<Bitmap, Shader.TileMode, Shader.TileMode>, BitmapShader>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Triple<Bitmap, Shader.TileMode, Shader.TileMode>, BitmapShader>): Boolean {
            return size > cache.maxSize * 2
        }
    }

    /**
     * Cached tint color from the last [colorFilterFor] call.
     * Initialized to WHITE so the first non-white tint always triggers a cache miss.
     */
    private var cachedTintColor: IsoColor = IsoColor.WHITE

    /**
     * Cached [PorterDuffColorFilter] matching [cachedTintColor].
     * `null` when [cachedTintColor] is white (no-op filter).
     */
    private var cachedColorFilter: PorterDuffColorFilter? = null

    /**
     * Returns a [PorterDuffColorFilter] for [tint], reusing the previously created filter
     * when the tint color has not changed between frames.
     *
     * White is the common case and always short-circuits before checking the cache.
     */
    private fun colorFilterFor(tint: IsoColor): PorterDuffColorFilter? {
        // White tint is a no-op; skip the cache entirely.
        if (isWhite(tint)) return null
        if (tint == cachedTintColor) return cachedColorFilter
        val filter = tint.toColorFilterOrNull()
        cachedTintColor = tint
        cachedColorFilter = filter
        return filter
    }

    override fun draw(
        nativeCanvas: android.graphics.Canvas,
        command: RenderCommand,
        nativePath: android.graphics.Path,
    ): Boolean {
        val material = command.material as? IsometricMaterial ?: return false

        return when (material) {
            is IsometricMaterial.Textured -> drawTextured(nativeCanvas, command, nativePath, material)
            is IsometricMaterial.PerFace -> {
                when (val sub = material.resolveForFace(command.faceType)) {
                    is IsometricMaterial.Textured -> drawTextured(nativeCanvas, command, nativePath, sub)
                    is IsoColor -> drawFlatColor(nativeCanvas, nativePath, sub)
                    // Any other non-Textured MaterialData — delegate to the default
                    // flat-color path. The default path paints command.color (which equals
                    // material.baseColor() — the PerFace.default's baseColor), so this only
                    // renders the fallback for per-face materials that aren't Textured or IsoColor.
                    else -> false
                }
            }
        }
    }

    /**
     * Draws a per-face resolved [IsoColor] directly. Needed because the default
     * flat-color path paints [RenderCommand.color] which, for a [IsometricMaterial.PerFace]
     * material, is the [IsometricMaterial.PerFace.default]'s baseColor — not the per-face
     * resolved value. Without this arm, `pyramidPerFace { lateral(0, RED); ... }` would
     * render every lateral as the default gray.
     */
    private fun drawFlatColor(
        nativeCanvas: android.graphics.Canvas,
        nativePath: android.graphics.Path,
        color: IsoColor,
    ): Boolean {
        // Mirror NativeSceneRenderer.toAndroidColor: Android's Color.argb expects
        // alpha-red-green-blue channel order; IsoColor stores each as a [0..255] Double.
        flatPaint.color = android.graphics.Color.argb(
            color.a.toInt().coerceIn(0, 255),
            color.r.toInt().coerceIn(0, 255),
            color.g.toInt().coerceIn(0, 255),
            color.b.toInt().coerceIn(0, 255),
        )
        nativeCanvas.drawPath(nativePath, flatPaint)
        return true
    }

    private fun drawTextured(
        nativeCanvas: android.graphics.Canvas,
        command: RenderCommand,
        nativePath: android.graphics.Path,
        material: IsometricMaterial.Textured,
    ): Boolean {
        val uvCoords = command.uvCoords
        // Floor at 6 because computeAffineMatrix always reads 3 UV pairs (indices 0..5).
        // Require 2*faceVertexCount to match GpuUvCoordsBuffer's invariant so pyramid /
        // octahedron commands that ship exact-size UV arrays don't pass with a truncated buffer.
        val minUvSize = maxOf(6, 2 * command.faceVertexCount)
        if (uvCoords == null || uvCoords.size < minUvSize) return false

        val cached = resolveToCache(material.source)
        val texW = cached.bitmap.width
        val texH = cached.bitmap.height

        val tileMode = if (material.transform != TextureTransform.IDENTITY) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
        val tileU = tileMode
        val tileV = tileMode
        val shaderKey = Triple(cached.bitmap, tileU, tileV)
        val shader = shaderCache.getOrPut(shaderKey) {
            BitmapShader(cached.bitmap, tileU, tileV)
        }

        computeAffineMatrix(uvCoords, command.points, texW, texH, material.transform, affineMatrix, transformMatrix, transformMatrixInv, matrixSrc, matrixDst)
        shader.setLocalMatrix(affineMatrix)

        texturedPaint.shader = shader
        texturedPaint.colorFilter = colorFilterFor(material.tint)
        try {
            nativeCanvas.drawPath(nativePath, texturedPaint)
        } finally {
            texturedPaint.shader = null
            texturedPaint.colorFilter = null
        }

        return true
    }

    internal fun resolveToCache(source: TextureSource): CachedTexture {
        cache.get(source)?.let { return it }
        val bitmap = loader.load(source)
        return if (bitmap != null) {
            cache.put(source, bitmap)
        } else {
            onTextureLoadError?.invoke(source)
            // Do NOT insert into cache on failure — the next frame will retry the load.
            // Return the checkerboard directly for this frame only so the UI doesn't crash.
            CachedTexture(checkerboard)
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
 * @param workMatrix Scratch matrix for the UV transform T (pre-allocated by caller — no allocation).
 * @param workMatrixInv Scratch matrix for T⁻¹ (pre-allocated by caller — no allocation).
 * @param workSrc Scratch [FloatArray] of length ≥ 6 for UV source points (pre-allocated by caller — no allocation).
 * @param workDst Scratch [FloatArray] of length ≥ 6 for screen destination points (pre-allocated by caller — no allocation).
 */
internal fun computeAffineMatrix(
    uvCoords: FloatArray,
    screenPoints: DoubleArray,
    texWidth: Int,
    texHeight: Int,
    transform: TextureTransform,
    outMatrix: Matrix,
    workMatrix: Matrix = Matrix(),
    workMatrixInv: Matrix = Matrix(),
    workSrc: FloatArray = FloatArray(6),
    workDst: FloatArray = FloatArray(6),
) {
    if (screenPoints.size < 6) {
        outMatrix.reset()
        return
    }
    workSrc[0] = uvCoords[0] * texWidth;  workSrc[1] = uvCoords[1] * texHeight
    workSrc[2] = uvCoords[2] * texWidth;  workSrc[3] = uvCoords[3] * texHeight
    workSrc[4] = uvCoords[4] * texWidth;  workSrc[5] = uvCoords[5] * texHeight
    workDst[0] = screenPoints[0].toFloat(); workDst[1] = screenPoints[1].toFloat()
    workDst[2] = screenPoints[2].toFloat(); workDst[3] = screenPoints[3].toFloat()
    workDst[4] = screenPoints[4].toFloat(); workDst[5] = screenPoints[5].toFloat()
    outMatrix.setPolyToPoly(workSrc, 0, workDst, 0, 3)

    if (transform != TextureTransform.IDENTITY) {
        // Build T in texture-pixel space: scale (around center) → rotate (around center) → translate
        val cx = texWidth / 2f
        val cy = texHeight / 2f
        workMatrix.reset()
        workMatrix.setScale(transform.scaleU, transform.scaleV, cx, cy)
        if (transform.rotationDegrees != 0f) {
            workMatrix.postRotate(transform.rotationDegrees, cx, cy)
        }
        if (transform.offsetU != 0f || transform.offsetV != 0f) {
            workMatrix.postTranslate(transform.offsetU * texWidth, transform.offsetV * texHeight)
        }
        // Pre-concat T^-1: M_final = M_poly * T^-1
        workMatrixInv.reset()
        if (workMatrix.invert(workMatrixInv)) {
            outMatrix.preConcat(workMatrixInv)
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
 * Returns `true` if [color] is white (all RGB components ≥ 255.0), meaning it is a no-op tint.
 *
 * Centralising this predicate here ensures that if [IsoColor.WHITE] is ever redefined
 * (e.g. using 1.0f float components), only this one place needs to change.
 */
private fun isWhite(color: IsoColor): Boolean =
    color.r >= 255.0 && color.g >= 255.0 && color.b >= 255.0

/**
 * Returns a multiplicative color filter for the given tint, or `null` for white (no-op).
 *
 * Returning `null` for white avoids a GPU state change on the common case where no
 * tint is applied.
 */
internal fun IsoColor.toColorFilterOrNull(): PorterDuffColorFilter? {
    if (isWhite(this)) return null
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
