package android.graphics

import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Nachbildung von android.graphics.Color (Int-Farben im Format 0xAARRGGBB, nicht vormultipliziert).
 * Die HSV-Umrechnung entspricht SkRGBToHSV/SkHSVToColor (wie Android).
 */
class Color private constructor(private val r: Float, private val g: Float, private val b: Float, private val a: Float) {

    fun red(): Float = r
    fun green(): Float = g
    fun blue(): Float = b
    fun alpha(): Float = a
    fun toArgb(): Int = argb(a, r, g, b)
    fun luminance(): Float = luminance(toArgb())

    override fun equals(other: Any?): Boolean = other is Color && other.toArgb() == toArgb()
    override fun hashCode(): Int = toArgb()
    override fun toString(): String = "Color($r, $g, $b, $a, sRGB IEC61966-2.1)"

    companion object {
        const val BLACK: Int = -0x1000000
        const val DKGRAY: Int = -0xbbbbbc
        const val GRAY: Int = -0x777778
        const val LTGRAY: Int = -0x333334
        const val WHITE: Int = -0x1
        const val RED: Int = -0x10000
        const val GREEN: Int = -0xff0100
        const val BLUE: Int = -0xffff01
        const val YELLOW: Int = -0x100
        const val CYAN: Int = -0xff0001
        const val MAGENTA: Int = -0xff01
        const val TRANSPARENT: Int = 0

        @JvmStatic fun alpha(color: Int): Int = color ushr 24
        @JvmStatic fun red(color: Int): Int = (color shr 16) and 0xFF
        @JvmStatic fun green(color: Int): Int = (color shr 8) and 0xFF
        @JvmStatic fun blue(color: Int): Int = color and 0xFF

        @JvmStatic fun rgb(red: Int, green: Int, blue: Int): Int =
            -0x1000000 or (red shl 16) or (green shl 8) or blue

        @JvmStatic fun rgb(red: Float, green: Float, blue: Float): Int =
            -0x1000000 or ((red * 255f + 0.5f).toInt() shl 16) or ((green * 255f + 0.5f).toInt() shl 8) or (blue * 255f + 0.5f).toInt()

        @JvmStatic fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue

        @JvmStatic fun argb(alpha: Float, red: Float, green: Float, blue: Float): Int =
            ((alpha * 255f + 0.5f).toInt() shl 24) or ((red * 255f + 0.5f).toInt() shl 16) or
                ((green * 255f + 0.5f).toInt() shl 8) or (blue * 255f + 0.5f).toInt()

        @JvmStatic fun valueOf(color: Int): Color =
            Color(red(color) / 255f, green(color) / 255f, blue(color) / 255f, alpha(color) / 255f)

        @JvmStatic fun valueOf(r: Float, g: Float, b: Float): Color = Color(r, g, b, 1f)
        @JvmStatic fun valueOf(r: Float, g: Float, b: Float, a: Float): Color = Color(r, g, b, a)

        @JvmStatic fun isSrgb(color: Long): Boolean = true

        private fun eotf(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

        /** Relative Luminanz (linear sRGB, BT.709-Gewichte) wie android.graphics.Color.luminance. */
        @JvmStatic fun luminance(color: Int): Float {
            val r = eotf(red(color) / 255.0)
            val g = eotf(green(color) / 255.0)
            val b = eotf(blue(color) / 255.0)
            return (0.2126 * r + 0.7152 * g + 0.0722 * b).toFloat().coerceIn(0f, 1f)
        }

        private val NAMES: Map<String, Int> = mapOf(
            "black" to BLACK, "darkgray" to DKGRAY, "gray" to GRAY, "lightgray" to LTGRAY, "white" to WHITE,
            "red" to RED, "green" to GREEN, "blue" to BLUE, "yellow" to YELLOW, "cyan" to CYAN, "magenta" to MAGENTA,
            "aqua" to -0xff0001, "fuchsia" to -0xff01, "darkgrey" to DKGRAY, "grey" to GRAY, "lightgrey" to LTGRAY,
            "lime" to -0xff0100, "maroon" to -0x800000, "navy" to -0xffff80, "olive" to -0x7f8000, "purple" to -0x7fff80,
            "silver" to -0x3f3f40, "teal" to -0xff7f80, "transparent" to TRANSPARENT,
        )

        @JvmStatic fun parseColor(colorString: String): Int {
            if (colorString.startsWith("#")) {
                var color = colorString.substring(1).toLong(16)
                when (colorString.length) {
                    7 -> color = color or 0x00000000ff000000L
                    9 -> {}
                    else -> throw IllegalArgumentException("Unknown color")
                }
                return color.toInt()
            }
            return NAMES[colorString.lowercase()] ?: throw IllegalArgumentException("Unknown color")
        }

        @JvmStatic fun RGBToHSV(red: Int, green: Int, blue: Int, hsv: FloatArray) {
            val r = red and 0xFF; val g = green and 0xFF; val b = blue and 0xFF
            val min = minOf(r, g, b)
            val max = maxOf(r, g, b)
            val delta = max - min
            val v = max / 255f
            if (delta == 0) {
                hsv[0] = 0f; hsv[1] = 0f; hsv[2] = v
                return
            }
            val s = delta.toFloat() / max
            var h = when (max) {
                r -> (g - b).toFloat() / delta
                g -> 2f + (b - r).toFloat() / delta
                else -> 4f + (r - g).toFloat() / delta
            }
            h *= 60f
            if (h < 0f) h += 360f
            hsv[0] = h; hsv[1] = s; hsv[2] = v
        }

        @JvmStatic fun colorToHSV(color: Int, hsv: FloatArray) = RGBToHSV(red(color), green(color), blue(color), hsv)

        @JvmStatic fun HSVToColor(hsv: FloatArray): Int = HSVToColor(0xFF, hsv)

        @JvmStatic fun HSVToColor(alpha: Int, hsv: FloatArray): Int {
            val s = hsv[1].coerceIn(0f, 1f)
            val v = hsv[2].coerceIn(0f, 1f)
            val vByte = floor(v * 255f + 0.5f).toInt()
            if (s <= 1f / 4096f) return argb(alpha, vByte, vByte, vByte)
            val hx = if (hsv[0] < 0f || hsv[0] >= 360f) 0f else hsv[0] / 60f
            val w = floor(hx)
            val f = hx - w
            val p = floor((1f - s) * v * 255f + 0.5f).toInt()
            val q = floor((1f - s * f) * v * 255f + 0.5f).toInt()
            val t = floor((1f - s * (1f - f)) * v * 255f + 0.5f).toInt()
            val (r, g, b) = when (w.toInt()) {
                0 -> Triple(vByte, t, p)
                1 -> Triple(q, vByte, p)
                2 -> Triple(p, vByte, t)
                3 -> Triple(p, q, vByte)
                4 -> Triple(t, p, vByte)
                else -> Triple(vByte, p, q)
            }
            return argb(alpha, r, g, b)
        }

        /** Hilfen für die Kompatibilitätsschicht. */
        internal fun premultiply(c: Int): Int {
            val a = c ushr 24
            if (a == 255) return c
            if (a == 0) return 0
            val r = mul255((c shr 16) and 0xFF, a)
            val g = mul255((c shr 8) and 0xFF, a)
            val b = mul255(c and 0xFF, a)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        internal fun unpremultiply(c: Int): Int {
            val a = c ushr 24
            if (a == 255) return c
            if (a == 0) return 0
            val scale = 255f / a
            val r = (((c shr 16) and 0xFF) * scale + 0.5f).toInt().coerceAtMost(255)
            val g = (((c shr 8) and 0xFF) * scale + 0.5f).toInt().coerceAtMost(255)
            val b = ((c and 0xFF) * scale + 0.5f).toInt().coerceAtMost(255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** (x·a + 127) / 255 mit exakter Rundung wie SkMulDiv255Round. */
        internal fun mul255(x: Int, a: Int): Int {
            val prod = x * a + 128
            return (prod + (prod shr 8)) shr 8
        }

        @Suppress("unused")
        internal fun roundF(v: Float): Int = v.roundToInt()
    }
}
