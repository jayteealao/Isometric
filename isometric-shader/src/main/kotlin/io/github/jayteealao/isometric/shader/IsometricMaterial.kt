package io.github.jayteealao.isometric.shader

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.MaterialData

/**
 * Describes how a face should be painted.
 *
 * Implements [MaterialData] so that [io.github.jayteealao.isometric.RenderCommand]
 * can carry a material reference without depending on Android types.
 *
 * ## Progressive disclosure (guideline Section 2)
 *
 * - **Simple:** `flatColor(IsoColor.BLUE)` — zero overhead, identical to existing color path
 * - **Configurable:** `textured(R.drawable.brick) { uvScale(2f, 2f) }` — bitmap texture
 * - **Advanced:** `perFace(default = flatColor(IsoColor.GRAY)) { face(0, textured(...)) }` — per-face control
 *
 * ## Sealed interface (guideline Section 6)
 *
 * Subtypes are exhaustive — renderers `when`-match without an else branch.
 * **Evolution note:** Adding a new subtype in a future version is a breaking change
 * for consumers using exhaustive `when` expressions. Use an `else` branch in `when`
 * if forward compatibility is needed.
 */
sealed interface IsometricMaterial : MaterialData {

    /**
     * Renders the face with a solid [IsoColor]. Zero texture overhead.
     * This is the default when no material is specified (backward compatible path).
     */
    data class FlatColor(val color: IsoColor) : IsometricMaterial

    /**
     * Renders the face with a [TextureSource] bitmap, optionally tinted and transformed.
     *
     * @property source Where to load the bitmap from
     * @property tint Multiplicative color tint applied over the texture (WHITE = no tint)
     * @property uvTransform Affine UV transform (scale, offset, rotation)
     */
    data class Textured(
        val source: TextureSource,
        val tint: IsoColor = IsoColor.WHITE,
        val uvTransform: UvTransform = UvTransform.IDENTITY,
    ) : IsometricMaterial

    /**
     * Assigns different materials to different faces of a shape.
     *
     * Faces not covered by this map fall back to [default].
     *
     * @property faceMap Map from face index (0-based, matching shape paths order) to material
     * @property default Material used for faces not present in [faceMap]
     */
    data class PerFace(
        val faceMap: Map<Int, IsometricMaterial>,
        val default: IsometricMaterial,
    ) : IsometricMaterial
}

// -- DSL builders -------------------------------------------------------------

/**
 * Creates a [IsometricMaterial.FlatColor] material.
 *
 * ```kotlin
 * Shape(Prism(origin), material = flatColor(IsoColor.BLUE))
 * ```
 */
fun flatColor(color: IsoColor): IsometricMaterial.FlatColor =
    IsometricMaterial.FlatColor(color)

/**
 * Creates a [IsometricMaterial.Textured] material from a drawable resource.
 *
 * ```kotlin
 * Shape(Prism(origin), material = textured(R.drawable.brick) {
 *     tint = IsoColor.WHITE
 *     uvScale(2f, 2f)
 * })
 * ```
 */
fun textured(
    @DrawableRes resId: Int,
    block: TexturedBuilder.() -> Unit = {},
): IsometricMaterial.Textured =
    TexturedBuilder(TextureSource.Resource(resId)).apply(block).build()

/**
 * Creates a [IsometricMaterial.Textured] material from an asset path.
 */
fun texturedAsset(
    path: String,
    block: TexturedBuilder.() -> Unit = {},
): IsometricMaterial.Textured =
    TexturedBuilder(TextureSource.Asset(path)).apply(block).build()

/**
 * Creates a [IsometricMaterial.Textured] material from a [Bitmap].
 */
fun texturedBitmap(
    bitmap: Bitmap,
    block: TexturedBuilder.() -> Unit = {},
): IsometricMaterial.Textured =
    TexturedBuilder(TextureSource.BitmapSource(bitmap)).apply(block).build()

/**
 * Creates a [IsometricMaterial.PerFace] material via a builder.
 *
 * ```kotlin
 * Shape(Prism(origin), material = perFace(default = flatColor(IsoColor.GRAY)) {
 *     face(0, textured(R.drawable.grass))   // top face
 *     face(1, textured(R.drawable.dirt))    // front face
 * })
 * ```
 */
fun perFace(
    default: IsometricMaterial = IsometricMaterial.FlatColor(IsoColor.GRAY),
    block: PerFaceBuilder.() -> Unit,
): IsometricMaterial.PerFace =
    PerFaceBuilder(default).apply(block).build()

// -- Builder classes ----------------------------------------------------------

@IsometricMaterialDsl
class TexturedBuilder internal constructor(private val source: TextureSource) {
    var tint: IsoColor = IsoColor.WHITE
    private var uvTransform: UvTransform = UvTransform.IDENTITY

    /** Sets the UV scale. Values > 1 tile the texture; values < 1 show a sub-region. */
    fun uvScale(scaleU: Float, scaleV: Float) {
        uvTransform = uvTransform.copy(scaleU = scaleU, scaleV = scaleV)
    }

    /** Sets the UV offset in texture coordinate space. */
    fun uvOffset(offsetU: Float, offsetV: Float) {
        uvTransform = uvTransform.copy(offsetU = offsetU, offsetV = offsetV)
    }

    /** Sets the UV rotation in degrees. */
    fun uvRotate(degrees: Float) {
        uvTransform = uvTransform.copy(rotationDegrees = degrees)
    }

    internal fun build(): IsometricMaterial.Textured =
        IsometricMaterial.Textured(source = source, tint = tint, uvTransform = uvTransform)
}

@IsometricMaterialDsl
class PerFaceBuilder internal constructor(private val default: IsometricMaterial) {
    private val faceMap = mutableMapOf<Int, IsometricMaterial>()

    /** Assigns [material] to the face at [index] (0-based, matching shape.paths order). */
    fun face(index: Int, material: IsometricMaterial) {
        require(index >= 0) { "Face index must be non-negative, got $index" }
        faceMap[index] = material
    }

    internal fun build(): IsometricMaterial.PerFace =
        IsometricMaterial.PerFace(faceMap = faceMap.toMap(), default = default)
}

/** DSL marker preventing scope leakage between nested builders (guideline Section 10). */
@DslMarker
annotation class IsometricMaterialDsl
