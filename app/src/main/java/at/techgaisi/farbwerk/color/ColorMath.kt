package at.techgaisi.farbwerk.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Farbumrechnungen (HSB/HSV ↔ ARGB, Hex) ohne Allokationen in heißen Pfaden.
 * Alle Farben dieses Moduls sind deckend (Alpha = 255).
 */
object ColorMath {

    /** HSB → ARGB. [h] in Grad (0..360), [s] und [v] in 0..1. */
    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val ss = s.coerceIn(0f, 1f)
        val vv = v.coerceIn(0f, 1f)
        val i = hh.toInt()
        val f = hh - i
        val p = vv * (1f - ss)
        val q = vv * (1f - ss * f)
        val t = vv * (1f - ss * (1f - f))
        val r: Float; val g: Float; val b: Float
        when (i) {
            0 -> { r = vv; g = t; b = p }
            1 -> { r = q; g = vv; b = p }
            2 -> { r = p; g = vv; b = t }
            3 -> { r = p; g = q; b = vv }
            4 -> { r = t; g = p; b = vv }
            else -> { r = vv; g = p; b = q }
        }
        return argb((r * 255f).roundToInt(), (g * 255f).roundToInt(), (b * 255f).roundToInt())
    }

    /** ARGB → HSB. Schreibt in [out] (Länge ≥ 3): Farbton (Grad), Sättigung, Helligkeit. */
    fun argbToHsv(argb: Int, out: FloatArray) {
        val r = red(argb) / 255f
        val g = green(argb) / 255f
        val b = blue(argb) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        out[0] = h
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun red(c: Int) = (c ushr 16) and 0xFF
    fun green(c: Int) = (c ushr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun alpha(c: Int) = (c ushr 24) and 0xFF

    fun opaque(c: Int) = c or 0xFF000000.toInt()

    /** "#RRGGBB" (Großbuchstaben, ohne Alpha). */
    fun toHex(c: Int): String = String.format("%06X", c and 0xFFFFFF)

    /** Akzeptiert "RGB", "RRGGBB", optional mit "#". Liefert null bei ungültiger Eingabe. */
    fun parseHex(text: String): Int? {
        val t = text.trim().removePrefix("#")
        val full = when (t.length) {
            3 -> buildString { t.forEach { append(it); append(it) } }
            6 -> t
            else -> return null
        }
        val v = full.toLongOrNull(16) ?: return null
        return opaque(v.toInt())
    }

    /** Relative Helligkeit (0..1) – z. B. für kontrastierende Ränder/Text. */
    fun luminance(c: Int): Float =
        (0.2126f * red(c) + 0.7152f * green(c) + 0.0722f * blue(c)) / 255f

    /** Farbtonabstand in Grad (0..180). */
    fun hueDistance(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}
