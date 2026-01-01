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
    val h: Double
    val s: Double
    val l: Double

    init {
        // Calculate HSL from RGB
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

        this.h = hue
        this.s = saturation
        this.l = lightness
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
        return IsoColor(rNew, gNew, bNew, a)
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
        val WHITE = IsoColor(255.0, 255.0, 255.0)
        val BLACK = IsoColor(0.0, 0.0, 0.0)
        val RED = IsoColor(255.0, 0.0, 0.0)
        val GREEN = IsoColor(0.0, 255.0, 0.0)
        val BLUE = IsoColor(0.0, 0.0, 255.0)
    }
}
