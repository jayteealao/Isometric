package io.github.jayteealao.isometric

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Represents a color with RGB and HSL representations.
 *
 * Renamed from `Color` to `IsoColor` to avoid conflicts with platform Color classes
 * (e.g., `android.graphics.Color`, `androidx.compose.ui.graphics.Color`).
 *
 * All RGBA channel values are in the range 0&ndash;255. The derived HSL properties are
 * lazily computed: [h] is a normalized hue in 0&ndash;1 (not 0&ndash;360), [s] is
 * saturation in 0&ndash;1, and [l] is lightness in 0&ndash;1.
 *
 * @param r Red channel (0&ndash;255)
 * @param g Green channel (0&ndash;255)
 * @param b Blue channel (0&ndash;255)
 * @param a Alpha channel (0&ndash;255, default 255 = fully opaque)
 */
data class IsoColor @JvmOverloads constructor(
    val r: Double,
    val g: Double,
    val b: Double,
    val a: Double = 255.0
) : MaterialData {
    @JvmOverloads
    constructor(r: Int, g: Int, b: Int, a: Int = 255) : this(
        r.toDouble(),
        g.toDouble(),
        b.toDouble(),
        a.toDouble()
    )

    override fun baseColor(): IsoColor = this

    private val hsl: Triple<Double, Double, Double> by lazy(LazyThreadSafetyMode.NONE) { computeHsl() }

    /** Hue component in 0&ndash;1 (normalized; multiply by 360 for degrees). */
    val h: Double get() = hsl.first

    /** Saturation component in 0&ndash;1. */
    val s: Double get() = hsl.second

    /** Lightness component in 0&ndash;1. */
    val l: Double get() = hsl.third

    init {
        require(r in 0.0..255.0) { "r must be in 0..255, got $r" }
        require(g in 0.0..255.0) { "g must be in 0..255, got $g" }
        require(b in 0.0..255.0) { "b must be in 0..255, got $b" }
        require(a in 0.0..255.0) { "a must be in 0..255, got $a" }
    }

    private fun computeHsl(): Triple<Double, Double, Double> {
        val rNorm = r / 255.0
        val gNorm = g / 255.0
        val bNorm = b / 255.0

        val max = max(rNorm, max(gNorm, bNorm))
        val min = min(rNorm, min(gNorm, bNorm))

        var hue = 0.0
        var saturation: Double
        val lightness = (max + min) / 2.0

        if (max == min) {
            hue = 0.0  // achromatic
            saturation = 0.0
        } else {
            val d = max - min
            saturation = if (lightness > 0.5) d / (2.0 - max - min) else d / (max + min)

            when (max) {
                rNorm -> hue = (gNorm - bNorm) / d + (if (gNorm < bNorm) 6.0 else 0.0)
                gNorm -> hue = (bNorm - rNorm) / d + 2.0
                bNorm -> hue = (rNorm - gNorm) / d + 4.0
            }
            hue /= 6.0
        }

        return Triple(hue, saturation, lightness)
    }

    /**
     * Produces a lighter variant of this color by blending with [lightColor] and
     * then increasing the HSL lightness by [percentage].
     *
     * Used internally by the renderer to simulate directional lighting on faces.
     *
     * @param percentage Amount to add to the lightness component (0&ndash;1 range,
     *   clamped to a maximum lightness of 1.0).
     * @param lightColor The color to blend with before adjusting lightness.
     * @return A new [IsoColor] with the adjusted lightness, preserving the original alpha.
     */
    fun lighten(percentage: Double, lightColor: IsoColor): IsoColor {
        val newColor = IsoColor(
            (lightColor.r / 255.0) * r,
            (lightColor.g / 255.0) * g,
            (lightColor.b / 255.0) * b,
            a
        )

        val newLightness = min(newColor.l + percentage, 1.0)

        return newColor.withLightness(newLightness)
    }

    /**
     * Create a new color with a different lightness value
     */
    private fun withLightness(newLightness: Double): IsoColor {
        val (rNew, gNew, bNew) = hslToRgb(h, s, newLightness)
        return IsoColor(
            rNew.coerceIn(0.0, 255.0),
            gNew.coerceIn(0.0, 255.0),
            bNew.coerceIn(0.0, 255.0),
            a
        )
    }

    private fun hslToRgb(h: Double, s: Double, l: Double): Triple<Double, Double, Double> {
        val r: Double
        val g: Double
        val b: Double

        if (s == 0.0) {
            r = l  // achromatic
            g = l
            b = l
        } else {
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2.0 * l - q
            r = hue2rgb(p, q, h + 1.0 / 3.0)
            g = hue2rgb(p, q, h)
            b = hue2rgb(p, q, h - 1.0 / 3.0)
        }

        return Triple(r * 255.0, g * 255.0, b * 255.0)
    }

    private fun hue2rgb(p: Double, q: Double, t: Double): Double {
        var tNorm = t
        if (tNorm < 0) tNorm += 1
        if (tNorm > 1) tNorm -= 1
        if (tNorm < 1.0 / 6.0) return p + (q - p) * 6.0 * tNorm
        if (tNorm < 1.0 / 2.0) return q
        if (tNorm < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - tNorm) * 6.0
        return p
    }

    /**
     * Returns a copy of this color with the alpha channel scaled by [alpha].
     *
     * The [alpha] factor is multiplied against the existing alpha value, so
     * `IsoColor(255, 0, 0, 200).withAlpha(0.5f)` produces alpha ≈ 100.
     *
     * @param alpha Opacity multiplier in 0&ndash;1 range.
     * @throws IllegalArgumentException if [alpha] is outside 0&ndash;1.
     */
    fun withAlpha(alpha: Float): IsoColor {
        require(alpha in 0f..1f) { "alpha must be in 0..1, got $alpha" }
        return copy(a = (a * alpha).coerceIn(0.0, 255.0))
    }

    /**
     * Converts this color to an [IntArray] of four integer RGBA components,
     * each rounded to the nearest whole number in the 0&ndash;255 range.
     *
     * @return `intArrayOf(r, g, b, a)` with values in 0&ndash;255.
     */
    fun toRGBA(): IntArray {
        return intArrayOf(
            round(r).toInt(),
            round(g).toInt(),
            round(b).toInt(),
            round(a).toInt()
        )
    }

    companion object {
        /** Opaque white (255, 255, 255). */
        @JvmField val WHITE = IsoColor(255, 255, 255)
        /** Opaque black (0, 0, 0). */
        @JvmField val BLACK = IsoColor(0, 0, 0)
        /** Opaque red (255, 0, 0). */
        @JvmField val RED = IsoColor(255, 0, 0)
        /** Opaque green (0, 255, 0). */
        @JvmField val GREEN = IsoColor(0, 255, 0)
        /** Opaque blue (0, 0, 255). */
        @JvmField val BLUE = IsoColor(0, 0, 255)
        /** Opaque gray (158, 158, 158). */
        @JvmField val GRAY = IsoColor(158, 158, 158)
        /** Opaque dark gray (97, 97, 97). */
        @JvmField val DARK_GRAY = IsoColor(97, 97, 97)
        /** Opaque light gray (224, 224, 224). */
        @JvmField val LIGHT_GRAY = IsoColor(224, 224, 224)
        /** Opaque cyan (0, 188, 212). */
        @JvmField val CYAN = IsoColor(0, 188, 212)
        /** Opaque orange (255, 152, 0). */
        @JvmField val ORANGE = IsoColor(255, 152, 0)
        /** Opaque purple (156, 39, 176). */
        @JvmField val PURPLE = IsoColor(156, 39, 176)
        /** Opaque yellow (255, 235, 59). */
        @JvmField val YELLOW = IsoColor(255, 235, 59)
        /** Opaque brown (121, 85, 72). */
        @JvmField val BROWN = IsoColor(121, 85, 72)

        /**
         * Creates an [IsoColor] from a packed hex color value.
         *
         * Accepts 24-bit RGB values (e.g., `0xFF8800`) or 32-bit ARGB values
         * (e.g., `0xFFFF8800`). Negative values are treated as signed ARGB integers.
         *
         * @param hex Packed color value fitting within 32 bits
         */
        fun fromHex(hex: Long): IsoColor = when {
            hex < 0 -> fromPackedArgbInt(hex.toInt())
            hex <= 0xFFFFFFL -> fromPackedRgbInt(hex.toInt())
            hex <= 0xFFFFFFFFL -> fromPackedArgbInt(hex.toInt())
            else -> throw IllegalArgumentException("Hex color must fit in 32 bits, got $hex")
        }

        /**
         * Creates an [IsoColor] from a hex color string.
         *
         * Accepts 6-digit (`RRGGBB`) or 8-digit (`AARRGGBB`) hex strings,
         * with optional `#`, `0x`, or `0X` prefix.
         *
         * @param hex Hex color string (e.g., `"#FF8800"`, `"0xFFFF8800"`)
         */
        fun fromHex(hex: String): IsoColor {
            val normalized = hex
                .removePrefix("#")
                .removePrefix("0x")
                .removePrefix("0X")

            require(normalized.length == 6 || normalized.length == 8) {
                "Hex color must be 6 (RRGGBB) or 8 (AARRGGBB) digits, got '$hex'"
            }

            val value = normalized.toLong(16)
            return if (normalized.length == 6) {
                fromPackedRgbInt(value.toInt())
            } else {
                fromPackedArgbInt(value.toInt())
            }
        }

        /**
         * Creates an [IsoColor] from individual ARGB component values (each 0&ndash;255).
         *
         * @param a Alpha channel (0&ndash;255)
         * @param r Red channel (0&ndash;255)
         * @param g Green channel (0&ndash;255)
         * @param b Blue channel (0&ndash;255)
         */
        fun fromArgb(a: Int, r: Int, g: Int, b: Int): IsoColor = IsoColor(r, g, b, a)

        private fun fromPackedRgbInt(rgb: Int): IsoColor = IsoColor(
            (rgb ushr 16) and 0xFF,
            (rgb ushr 8) and 0xFF,
            rgb and 0xFF
        )

        private fun fromPackedArgbInt(argb: Int): IsoColor = IsoColor(
            (argb ushr 16) and 0xFF,
            (argb ushr 8) and 0xFF,
            argb and 0xFF,
            (argb ushr 24) and 0xFF
        )
    }
}
