package io.github.jayteealao.isometric.shader

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.MaterialData
import io.github.jayteealao.isometric.shapes.PrismFace

/**
 * Describes how a face should be painted.
 *
 * Implements [MaterialData] so that [io.github.jayteealao.isometric.RenderCommand]
 * can carry a material reference without depending on Android types.
 *
 * ## Progressive disclosure (guideline Section 2)
 *
 * - **Simple:** `Shape(Prism(origin), IsoColor.BLUE)` — zero overhead, flat color via core
 * - **Configurable:** `texturedResource(R.drawable.brick)` — bitmap texture
 * - **Advanced:** `perFace { top = texturedResource(...) }` — per-face control
 *
 * ## Sealed interface (guideline Section 6)
 *
 * Subtypes are `Textured` and `PerFace` only — flat-color rendering uses [IsoColor] directly.
 * **Evolution note:** Adding a new subtype in a future version is a breaking change
 * for consumers using exhaustive `when` expressions. Use an `else` branch in `when`
 * if forward compatibility is needed.
 */
sealed interface IsometricMaterial : MaterialData {

    /**
     * Renders the face with a [TextureSource] bitmap, optionally tinted and transformed.
     *
     * Requires [io.github.jayteealao.isometric.shader.render.ProvideTextureRendering] in the
     * composition or textures will not be rendered.
     *
     * @property source Where to load the bitmap from
     * @property tint Multiplicative color tint applied over the texture (WHITE = no tint)
     * @property transform Affine UV transform (scale, offset, rotation). Defaults to identity.
     */
    data class Textured(
        val source: TextureSource,
        val tint: IsoColor = IsoColor.WHITE,
        val transform: TextureTransform = TextureTransform.IDENTITY,
    ) : IsometricMaterial {
        override fun baseColor(): IsoColor = tint
    }

    /**
     * Assigns different materials to different faces of a Prism shape.
     *
     * Faces not covered by [faceMap] fall back to [default].
     *
     * Use [perFace] DSL to construct instances. For advanced callers needing direct
     * map construction, use [PerFace.of].
     *
     * @property faceMap Map from [PrismFace] role to material for that face
     * @property default Material used for faces not present in [faceMap].
     *   Defaults to mid-gray ([PerFace.Companion.UNASSIGNED_FACE_DEFAULT]) so unassigned faces are visible.
     */
    class PerFace private constructor(
        val faceMap: Map<PrismFace, MaterialData>,
        val default: MaterialData = UNASSIGNED_FACE_DEFAULT,
    ) : IsometricMaterial {
        init {
            require(faceMap.values.none { it is PerFace }) {
                "PerFace materials cannot be nested — each face must be IsoColor or Textured"
            }
            require(default !is PerFace) {
                "PerFace default cannot itself be PerFace"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PerFace) return false
            return faceMap == other.faceMap && default == other.default
        }

        override fun hashCode(): Int {
            var result = faceMap.hashCode()
            result = 31 * result + default.hashCode()
            return result
        }

        override fun baseColor(): IsoColor = default.baseColor()

        override fun toString(): String = "PerFace(faceMap=$faceMap, default=$default)"

        companion object {
            /** Mid-gray fallback for unassigned [PerFace] faces (visible, not transparent). */
            internal val UNASSIGNED_FACE_DEFAULT: IsoColor = IsoColor(128, 128, 128, 255)

            /**
             * Creates a [PerFace] material that maps different materials to each face of a prism.
             *
             * This factory function exists because [PerFace] uses an `internal constructor` to satisfy
             * Kotlin's `explicitApi()` requirement: the class itself must be `public` (so it can appear
             * in public function signatures), but direct construction is locked down so callers cannot
             * bypass the [default]-must-not-be-[PerFace] invariant enforced here.
             *
             * Prefer the [perFace] DSL for typical usage.
             *
             * @param faceMap Map from [PrismFace] to material for that face
             * @param default The material to use for faces not explicitly assigned. Must not itself be
             *   a [PerFace] instance.
             */
            fun of(
                faceMap: Map<PrismFace, MaterialData>,
                default: MaterialData = UNASSIGNED_FACE_DEFAULT,
            ) = PerFace(faceMap, default)
        }
    }
}

// -- DSL builders -------------------------------------------------------------

/**
 * Creates an [IsometricMaterial.Textured] material from a drawable resource.
 *
 * Requires [io.github.jayteealao.isometric.shader.render.ProvideTextureRendering] in the
 * composition or textures will not be rendered.
 *
 * ```kotlin
 * Shape(Prism(origin), material = texturedResource(R.drawable.brick))
 * Shape(Prism(origin), material = texturedResource(R.drawable.brick, tint = IsoColor.RED))
 * Shape(Prism(origin), material = texturedResource(R.drawable.brick,
 *     transform = TextureTransform.tiling(2f, 2f)))
 * ```
 */
fun texturedResource(
    @DrawableRes resId: Int,
    tint: IsoColor = IsoColor.WHITE,
    transform: TextureTransform = TextureTransform.IDENTITY,
): IsometricMaterial.Textured =
    IsometricMaterial.Textured(source = TextureSource.Resource(resId), tint = tint, transform = transform)

/**
 * Creates an [IsometricMaterial.Textured] material from an asset path.
 *
 * Requires [io.github.jayteealao.isometric.shader.render.ProvideTextureRendering] in the
 * composition or textures will not be rendered.
 */
fun texturedAsset(
    path: String,
    tint: IsoColor = IsoColor.WHITE,
    transform: TextureTransform = TextureTransform.IDENTITY,
): IsometricMaterial.Textured =
    IsometricMaterial.Textured(source = TextureSource.Asset(path), tint = tint, transform = transform)

/**
 * Creates an [IsometricMaterial.Textured] material from a [Bitmap].
 *
 * Requires [io.github.jayteealao.isometric.shader.render.ProvideTextureRendering] in the
 * composition or textures will not be rendered.
 */
fun texturedBitmap(
    bitmap: Bitmap,
    tint: IsoColor = IsoColor.WHITE,
    transform: TextureTransform = TextureTransform.IDENTITY,
): IsometricMaterial.Textured =
    IsometricMaterial.Textured(source = TextureSource.Bitmap(bitmap), tint = tint, transform = transform)

/**
 * Creates an [IsometricMaterial.PerFace] material via a builder with named face properties.
 *
 * ```kotlin
 * // Simple: grass top, dirt sides
 * Shape(Prism(origin), material = perFace {
 *     top = texturedResource(R.drawable.grass)
 *     sides = texturedResource(R.drawable.dirt)
 * })
 *
 * // Fine-grained: different materials per side
 * Shape(Prism(origin), material = perFace {
 *     top = texturedResource(R.drawable.grass)
 *     front = texturedResource(R.drawable.dirt_shadow)
 *     left = texturedResource(R.drawable.dirt_light)
 *     default = IsoColor.GRAY
 * })
 * ```
 */
fun perFace(
    block: PerFaceMaterialScope.() -> Unit,
): IsometricMaterial.PerFace =
    PerFaceMaterialScope().apply(block).build()

// -- Builder classes ----------------------------------------------------------

/** DSL marker that prevents nesting [PerFaceMaterialScope] calls inadvertently. */
@DslMarker
annotation class IsometricMaterialDsl

@IsometricMaterialDsl
class PerFaceMaterialScope internal constructor() {
    var top: MaterialData? = null
    var bottom: MaterialData? = null
    var front: MaterialData? = null
    var back: MaterialData? = null
    var left: MaterialData? = null
    var right: MaterialData? = null
    var default: MaterialData = IsometricMaterial.PerFace.UNASSIGNED_FACE_DEFAULT

    /**
     * Convenience: sets [front], [back], [left], [right] to the same material.
     * Write-only — later individual assignments override.
     */
    var sides: MaterialData?
        @Deprecated("sides is write-only", level = DeprecationLevel.ERROR)
        get() = error("sides is write-only")
        set(value) { front = value; back = value; left = value; right = value }

    internal fun build(): IsometricMaterial.PerFace {
        val map = buildMap {
            top?.let { put(PrismFace.TOP, it) }
            bottom?.let { put(PrismFace.BOTTOM, it) }
            front?.let { put(PrismFace.FRONT, it) }
            back?.let { put(PrismFace.BACK, it) }
            left?.let { put(PrismFace.LEFT, it) }
            right?.let { put(PrismFace.RIGHT, it) }
        }
        return IsometricMaterial.PerFace.of(faceMap = map, default = default)
    }
}
