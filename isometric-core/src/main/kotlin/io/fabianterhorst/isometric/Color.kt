package io.fabianterhorst.isometric

import kotlin.math.max
import kotlin.math.min

/**
 * Legacy Color class for backward compatibility.
 *
 * @deprecated Use [IsoColor] instead. This class is maintained for backward compatibility only.
 */
@Deprecated(
    message = "Use IsoColor instead for better Compose compatibility",
    replaceWith = ReplaceWith("IsoColor(r, g, b, a)", "io.fabianterhorst.isometric.IsoColor")
)
class Color @JvmOverloads constructor(
    val r: Double,
    val g: Double,
    val b: Double,
    val a: Double = 255.0
) {
    val h: Double
    val s: Double
    val l: Double

    init {
        val hsl = loadHSL(r, g, b)
        h = hsl[0]
        s = hsl[1]
        l = hsl[2]
    }

    private fun loadHSL(r: Double, g: Double, b: Double): DoubleArray {
        val rNorm = r / 255.0
        val gNorm = g / 255.0
        val bNorm = b / 255.0

        val maxVal = max(rNorm, max(gNorm, bNorm))
        val minVal = min(rNorm, min(gNorm, bNorm))

        var h = 0.0
        val l = (maxVal + minVal) / 2.0
        val s: Double

        if (maxVal == minVal) {
            h = 0.0
            s = 0.0  // achromatic
        } else {
            val d = maxVal - minVal
            s = if (l > 0.5) d / (2.0 - maxVal - minVal) else d / (maxVal + minVal)

            h = when (maxVal) {
                rNorm -> (gNorm - bNorm) / d + (if (gNorm < bNorm) 6.0 else 0.0)
                gNorm -> (bNorm - rNorm) / d + 2.0
                bNorm -> (rNorm - gNorm) / d + 4.0
                else -> 0.0
            }
            h /= 6.0
        }

        return doubleArrayOf(h, s, l)
    }

    fun lighten(percentage: Double, lightColor: Color): Color {
        val newColor = Color(
            (lightColor.r / 255.0) * r,
            (lightColor.g / 255.0) * g,
            (lightColor.b / 255.0) * b,
            a
        )

        val newL = min(newColor.l + percentage, 1.0)
        val rgb = loadRGB(newColor.h, newColor.s, newL)

        return Color(rgb[0], rgb[1], rgb[2], a)
    }

    private fun loadRGB(h: Double, s: Double, l: Double): DoubleArray {
        val r: Double
        val g: Double
        val b: Double

        if (s == 0.0) {
            r = l
            g = l
            b = l  // achromatic
        } else {
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2.0 * l - q
            r = hue2rgb(p, q, h + 1.0 / 3.0)
            g = hue2rgb(p, q, h)
            b = hue2rgb(p, q, h - 1.0 / 3.0)
        }

        return doubleArrayOf(r * 255.0, g * 255.0, b * 255.0)
    }

    private fun hue2rgb(p: Double, q: Double, tIn: Double): Double {
        var t = tIn
        if (t < 0) t += 1.0
        if (t > 1) t -= 1.0
        if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t
        if (t < 1.0 / 2.0) return q
        if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0
        return p
    }

    /**
     * Convert to IsoColor
     */
    fun toIsoColor(): IsoColor = IsoColor(r, g, b, a)
}
