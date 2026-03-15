package io.fabianterhorst.isometric

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Represents a color with RGB and HSL representations
 * Renamed from Color to IsoColor to avoid conflicts with platform Color classes
 */
data class IsoColor(
    val r: Double,
    val g: Double,
    val b: Double,
    val a: Double = 255.0
) {
    constructor(r: Int, g: Int, b: Int, a: Int = 255) : this(
        r.toDouble(),
        g.toDouble(),
        b.toDouble(),
        a.toDouble()
    )

    private val hsl: Triple<Double, Double, Double> by lazy(LazyThreadSafetyMode.NONE) { computeHsl() }
    val h: Double get() = hsl.first
    val s: Double get() = hsl.second
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
     * Lighten the color by a percentage and blend with a light color
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
     * Convert to integer RGBA components (0-255)
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
        // Common colors
        val WHITE = IsoColor(255, 255, 255)
        val BLACK = IsoColor(0, 0, 0)
        val RED = IsoColor(255, 0, 0)
        val GREEN = IsoColor(0, 255, 0)
        val BLUE = IsoColor(0, 0, 255)
        val GRAY = IsoColor(158, 158, 158)
        val DARK_GRAY = IsoColor(97, 97, 97)
        val LIGHT_GRAY = IsoColor(224, 224, 224)
        val CYAN = IsoColor(0, 188, 212)
        val ORANGE = IsoColor(255, 152, 0)
        val PURPLE = IsoColor(156, 39, 176)
        val YELLOW = IsoColor(255, 235, 59)
        val BROWN = IsoColor(121, 85, 72)

        fun fromHex(hex: Long): IsoColor = when {
            hex < 0 -> fromPackedArgbInt(hex.toInt())
            hex <= 0xFFFFFFL -> fromPackedRgbInt(hex.toInt())
            hex <= 0xFFFFFFFFL -> fromPackedArgbInt(hex.toInt())
            else -> throw IllegalArgumentException("Hex color must fit in 32 bits, got $hex")
        }

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
