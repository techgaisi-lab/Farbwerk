package at.techgaisi.farbwerk.adjust

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Abbruch-Signal für Hintergrundberechnungen (wird pro Zeile geprüft). */
class CancelToken {
    @Volatile var cancelled = false
    fun check() { if (cancelled) throw CancellationException("Anpassung überholt") }
}

/**
 * Eingabe für eine Filterberechnung.
 * @param scale Vorschau-Maßstab (1 = volle Auflösung); Radien werden in voller Auflösung definiert und skaliert.
 * @param fullMax längere Seite des Dokuments in voller Auflösung
 * @param cx, cy Mittelpunkt (normiert 0..1) für positionsbezogene Effekte
 */
class FilterInput(
    val kind: AdjustmentKind,
    val v: FloatArray,
    val scale: Float,
    val fullMax: Int,
    val cx: Float,
    val cy: Float,
    val curveLuts: Array<IntArray>?,
    val gradientLut: IntArray?,
    val token: CancelToken,
)

/**
 * Pixelverarbeitung aller Regler-Anpassungen auf IntArrays (nicht vormultipliziertes ARGB wie von
 * Bitmap.getPixels). Weichzeichner arbeiten intern vormultipliziert (keine dunklen Säume an
 * transparenten Kanten) mit Box-Filtern in O(1) pro Pixel unabhängig vom Radius.
 */
object Filters {

    /** Liefert true, wenn die Parameter keine Veränderung bewirken. */
    fun isNeutral(kind: AdjustmentKind, v: FloatArray, curves: Curves?): Boolean = when (kind) {
        AdjustmentKind.HSB -> v[0] == 0f && v[1] == 0f && v[2] == 0f
        AdjustmentKind.COLOR_BALANCE -> (1 until v.size).all { v[it] == 0f }
        AdjustmentKind.CURVES -> curves?.isIdentity ?: true
        AdjustmentKind.GRADIENT_MAP -> v[0] <= 0f
        AdjustmentKind.GAUSSIAN_BLUR, AdjustmentKind.MOTION_BLUR, AdjustmentKind.NOISE,
        AdjustmentKind.SHARPEN, AdjustmentKind.BLOOM, AdjustmentKind.CHROMATIC -> v[0] <= 0f
        AdjustmentKind.PERSPECTIVE_BLUR, AdjustmentKind.GLITCH, AdjustmentKind.HALFTONE -> v[1] <= 0f
        AdjustmentKind.LIQUIFY, AdjustmentKind.CLONE -> false
    }

    fun process(src: IntArray, w: Int, h: Int, inp: FilterInput): IntArray {
        val v = inp.v
        return when (inp.kind) {
            AdjustmentKind.HSB -> hsb(src, v[0], v[1], v[2], inp.token)
            AdjustmentKind.COLOR_BALANCE -> colorBalance(src, v, inp.token)
            AdjustmentKind.CURVES -> curves(src, inp.curveLuts)
            AdjustmentKind.GRADIENT_MAP -> gradientMap(src, inp.gradientLut, v[0] / 100f)
            AdjustmentKind.GAUSSIAN_BLUR -> {
                val a = v[0] / 100f
                val sigma = a * a * inp.fullMax * 0.04f * inp.scale
                if (sigma < 0.3f) src.copyOf() else unpremul(gaussian(premul(src), w, h, sigma, inp.token))
            }
            AdjustmentKind.MOTION_BLUR -> {
                val a = v[0] / 100f
                val r = (a * a * inp.fullMax * 0.08f * inp.scale).roundToInt()
                if (r < 1) src.copyOf() else {
                    val ang = Math.toRadians(v[1].toDouble()).toFloat()
                    val p = premul(src)
                    var o = directionalBox(p, w, h, ang, r, inp.token)
                    o = directionalBox(o, w, h, ang, max(1, r / 3), inp.token)
                    unpremul(o)
                }
            }
            AdjustmentKind.PERSPECTIVE_BLUR -> perspectiveBlur(src, w, h, inp)
            AdjustmentKind.NOISE -> noise(src, w, h, inp)
            AdjustmentKind.SHARPEN -> sharpen(src, w, h, inp)
            AdjustmentKind.BLOOM -> bloom(src, w, h, inp)
            AdjustmentKind.GLITCH -> glitch(src, w, h, inp)
            AdjustmentKind.HALFTONE -> halftone(src, w, h, inp)
            AdjustmentKind.CHROMATIC -> chromatic(src, w, h, inp)
            AdjustmentKind.LIQUIFY, AdjustmentKind.CLONE -> src.copyOf()
        }
    }

    // ================================================================= Farbanpassungen

    private fun hsb(src: IntArray, hue: Float, sat: Float, bri: Float, token: CancelToken): IntArray {
        val out = IntArray(src.size)
        val th = Math.toRadians(hue * 1.8)
        val c = cos(th).toFloat()
        val s = sin(th).toFloat()
        // Farbton-Drehung (luminanzerhaltend)
        val h = floatArrayOf(
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f,
        )
        val sv = 1f + sat / 100f
        val st = floatArrayOf(
            0.213f + 0.787f * sv, 0.715f - 0.715f * sv, 0.072f - 0.072f * sv,
            0.213f - 0.213f * sv, 0.715f + 0.285f * sv, 0.072f - 0.072f * sv,
            0.213f - 0.213f * sv, 0.715f - 0.715f * sv, 0.072f + 0.928f * sv,
        )
        val m = FloatArray(9)
        for (r in 0 until 3) for (k in 0 until 3) {
            m[r * 3 + k] = st[r * 3] * h[k] + st[r * 3 + 1] * h[3 + k] + st[r * 3 + 2] * h[6 + k]
        }
        val b = bri / 100f
        val lut = IntArray(256) { i ->
            (if (b >= 0f) i + (255 - i) * b else i * (1f + b)).roundToInt().coerceIn(0, 255)
        }
        for (i in src.indices) {
            if (i and 0xFFFF == 0) token.check()
            val p = src[i]
            val a = p ushr 24
            if (a == 0) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
            val nr = lut[(m[0] * r + m[1] * g + m[2] * bl).toInt().coerceIn(0, 255)]
            val ng = lut[(m[3] * r + m[4] * g + m[5] * bl).toInt().coerceIn(0, 255)]
            val nb = lut[(m[6] * r + m[7] * g + m[8] * bl).toInt().coerceIn(0, 255)]
            out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return out
    }

    private fun colorBalance(src: IntArray, v: FloatArray, token: CancelToken): IntArray {
        val out = IntArray(src.size)
        val k = 0.4f * 255f / 100f
        // v[1..3] Schatten, v[4..6] Mitteltöne, v[7..9] Lichter
        val ws = FloatArray(256); val wm = FloatArray(256); val wh = FloatArray(256)
        for (i in 0 until 256) {
            val l = i / 255f
            ws[i] = 1f - smooth(0f, 0.55f, l)
            wh[i] = smooth(0.45f, 1f, l)
            wm[i] = (1f - abs(l - 0.5f) * 2f).coerceAtLeast(0f)
        }
        for (i in src.indices) {
            if (i and 0xFFFF == 0) token.check()
            val p = src[i]
            val a = p ushr 24
            if (a == 0) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val l = ((r * 77 + g * 150 + b * 29) shr 8).coerceIn(0, 255)
            val s = ws[l]; val m = wm[l]; val hh = wh[l]
            val dr = (v[1] * s + v[4] * m + v[7] * hh) * k
            val dg = (v[2] * s + v[5] * m + v[8] * hh) * k
            val db = (v[3] * s + v[6] * m + v[9] * hh) * k
            val nr = (r + dr).toInt().coerceIn(0, 255)
            val ng = (g + dg).toInt().coerceIn(0, 255)
            val nb = (b + db).toInt().coerceIn(0, 255)
            out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return out
    }

    private fun curves(src: IntArray, luts: Array<IntArray>?): IntArray {
        if (luts == null) return src.copyOf()
        val lr = luts[0]; val lg = luts[1]; val lb = luts[2]
        val out = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            val a = p ushr 24
            if (a == 0) continue
            out[i] = (a shl 24) or (lr[(p shr 16) and 0xFF] shl 16) or (lg[(p shr 8) and 0xFF] shl 8) or lb[p and 0xFF]
        }
        return out
    }

    private fun gradientMap(src: IntArray, lut: IntArray?, strength: Float): IntArray {
        if (lut == null) return src.copyOf()
        val out = IntArray(src.size)
        val s = strength.coerceIn(0f, 1f)
        for (i in src.indices) {
            val p = src[i]
            val a = p ushr 24
            if (a == 0) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val l = ((r * 77 + g * 150 + b * 29) shr 8).coerceIn(0, 255)
            val c = lut[l]
            val nr = (r + (((c shr 16) and 0xFF) - r) * s).toInt()
            val ng = (g + (((c shr 8) and 0xFF) - g) * s).toInt()
            val nb = (b + ((c and 0xFF) - b) * s).toInt()
            out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return out
    }

    // ================================================================= Weichzeichner

    fun premul(src: IntArray): IntArray {
        val out = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            val a = p ushr 24
            if (a == 0) continue
            if (a == 255) { out[i] = p; continue }
            val r = ((p shr 16) and 0xFF) * a / 255
            val g = ((p shr 8) and 0xFF) * a / 255
            val b = (p and 0xFF) * a / 255
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    fun unpremul(src: IntArray): IntArray {
        for (i in src.indices) {
            val p = src[i]
            val a = p ushr 24
            if (a == 0) { src[i] = 0; continue }
            if (a == 255) continue
            val r = min(255, ((p shr 16) and 0xFF) * 255 / a)
            val g = min(255, ((p shr 8) and 0xFF) * 255 / a)
            val b = min(255, (p and 0xFF) * 255 / a)
            src[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return src
    }

    /** Gauß-Annäherung mit drei Box-Durchgängen (vormultipliziert). */
    fun gaussian(pm: IntArray, w: Int, h: Int, sigma: Float, token: CancelToken): IntArray {
        val a = pm.copyOf()
        val b = IntArray(pm.size)
        for (size in boxesForGauss(sigma, 3)) {
            val r = (size - 1) / 2
            if (r < 1) continue
            boxH(a, b, w, h, r, token)
            boxV(b, a, w, h, r, token)
        }
        return a
    }

    private fun boxesForGauss(sigma: Float, n: Int): IntArray {
        val wIdeal = sqrt(12f * sigma * sigma / n + 1f)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12f * sigma * sigma - n * wl * wl - 4f * n * wl - 3f * n) / (-4f * wl - 4f)
        val m = mIdeal.roundToInt()
        return IntArray(n) { if (it < m) wl else wu }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, token: CancelToken) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            if (y and 31 == 0) token.check()
            val row = y * w
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val p = src[row + k.coerceIn(0, w - 1)]
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pi = src[row + min(w - 1, x + r + 1)]
                val po = src[row + max(0, x - r)]
                sa += (pi ushr 24) - (po ushr 24)
                sr += ((pi shr 16) and 0xFF) - ((po shr 16) and 0xFF)
                sg += ((pi shr 8) and 0xFF) - ((po shr 8) and 0xFF)
                sb += (pi and 0xFF) - (po and 0xFF)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, token: CancelToken) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            if (x and 31 == 0) token.check()
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val p = src[k.coerceIn(0, h - 1) * w + x]
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pi = src[min(h - 1, y + r + 1) * w + x]
                val po = src[max(0, y - r) * w + x]
                sa += (pi ushr 24) - (po ushr 24)
                sr += ((pi shr 16) and 0xFF) - ((po shr 16) and 0xFF)
                sg += ((pi shr 8) and 0xFF) - ((po shr 8) and 0xFF)
                sb += (pi and 0xFF) - (po and 0xFF)
            }
        }
    }

    /**
     * Box-Weichzeichner entlang einer beliebigen Richtung: Das Bild wird in parallele, digitale
     * Linien zerlegt (jedes Pixel gehört genau einer Linie); auf jeder Linie läuft eine gleitende Summe.
     */
    fun directionalBox(src: IntArray, w: Int, h: Int, angle: Float, r: Int, token: CancelToken): IntArray {
        val dst = IntArray(src.size)
        val cs = cos(angle)
        val sn = sin(angle)
        val xMajor = abs(cs) >= abs(sn)
        val len = if (xMajor) w else h
        val other = if (xMajor) h else w
        val slope = if (xMajor) sn / cs else cs / sn
        val idx = IntArray(len)
        val la = IntArray(len); val lr = IntArray(len); val lg = IntArray(len); val lb = IntArray(len)
        val offEnd = ((len - 1) * slope).roundToInt()
        val minOff = min(0, offEnd)
        val maxOff = max(0, offEnd)
        val div = 2 * r + 1
        for (o0 in -maxOff until other - minOff) {
            if (o0 and 31 == 0) token.check()
            var n = 0
            for (t in 0 until len) {
                val o = o0 + (t * slope).roundToInt()
                if (o < 0 || o >= other) continue
                val i = if (xMajor) o * w + t else t * w + o
                idx[n] = i
                val p = src[i]
                la[n] = p ushr 24; lr[n] = (p shr 16) and 0xFF; lg[n] = (p shr 8) and 0xFF; lb[n] = p and 0xFF
                n++
            }
            if (n == 0) continue
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val j = k.coerceIn(0, n - 1)
                sa += la[j]; sr += lr[j]; sg += lg[j]; sb += lb[j]
            }
            for (k in 0 until n) {
                dst[idx[k]] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val ji = min(n - 1, k + r + 1)
                val jo = max(0, k - r)
                sa += la[ji] - la[jo]; sr += lr[ji] - lr[jo]; sg += lg[ji] - lg[jo]; sb += lb[ji] - lb[jo]
            }
        }
        return dst
    }

    private fun perspectiveBlur(src: IntArray, w: Int, h: Int, inp: FilterInput): IntArray {
        val amount = inp.v[1] / 100f
        if (amount <= 0f) return src.copyOf()
        val pm = premul(src)
        val cx = inp.cx * w
        val cy = inp.cy * h
        val maxD = hypot(max(cx, w - cx), max(cy, h - cy)).coerceAtLeast(1f)
        if (inp.v[0] < 0.5f) {
            // Positionsbezogen: Zoom-Unschärfe vom Mittelpunkt weg
            val out = IntArray(pm.size)
            val samples = 20
            val len = amount * 0.35f
            for (y in 0 until h) {
                if (y and 15 == 0) inp.token.check()
                for (x in 0 until w) {
                    val dx = cx - x
                    val dy = cy - y
                    var sa = 0; var sr = 0; var sg = 0; var sb = 0
                    for (k in 0 until samples) {
                        val t = k / (samples - 1f) * len
                        val sx = (x + dx * t).toInt().coerceIn(0, w - 1)
                        val sy = (y + dy * t).toInt().coerceIn(0, h - 1)
                        val p = pm[sy * w + sx]
                        sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
                    }
                    out[y * w + x] = ((sa / samples) shl 24) or ((sr / samples) shl 16) or ((sg / samples) shl 8) or (sb / samples)
                }
            }
            return unpremul(out)
        }
        // Richtungsbezogen: Bewegungsunschärfe, die mit dem Abstand zum Mittelpunkt zunimmt
        val r = (amount * amount * inp.fullMax * 0.08f * inp.scale).roundToInt().coerceAtLeast(1)
        val ang = Math.toRadians(inp.v[2].toDouble()).toFloat()
        var blurred = directionalBox(pm, w, h, ang, r, inp.token)
        blurred = directionalBox(blurred, w, h, ang, max(1, r / 3), inp.token)
        val out = IntArray(pm.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val f = smooth(0.1f, 0.9f, hypot(x - cx, y - cy) / maxD)
                out[i] = lerpPacked(pm[i], blurred[i], f)
            }
        }
        return unpremul(out)
    }

    // ================================================================= Effekte

    private fun noise(src: IntArray, w: Int, h: Int, inp: FilterInput): IntArray {
        val amount = inp.v[0] / 100f
        val type = inp.v[1].roundToInt()
        val sizePx = 2f + inp.v[2] / 100f * inp.fullMax * 0.12f
        val octaves = inp.v[3].roundToInt().coerceIn(1, 6)
        val turb = inp.v[4] / 100f
        val out = IntArray(src.size)
        val inv = 1f / (sizePx * inp.scale)
        val k = amount * 255f
        for (y in 0 until h) {
            if (y and 15 == 0) inp.token.check()
            for (x in 0 until w) {
                val i = y * w + x
                val p = src[i]
                val a = p ushr 24
                if (a == 0) continue
                var n = fbm(x * inv, y * inv, octaves, turb)
                n = when (type) {
                    1 -> abs(2f * n - 1f)
                    2 -> 1f - abs(2f * n - 1f)
                    else -> n
                }
                val d = ((n - 0.5f) * 2f * k).toInt()
                val r = (((p shr 16) and 0xFF) + d).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + d).coerceIn(0, 255)
                val b = ((p and 0xFF) + d).coerceIn(0, 255)
                out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun fbm(x: Float, y: Float, octaves: Int, turb: Float): Float {
        var sum = 0f
        var amp = 0.5f
        var norm = 0f
        var fx = x
        var fy = y
        for (o in 0 until octaves) {
            var n = valueNoise(fx, fy, o * 131)
            if (turb > 0f) n = n * (1f - turb) + abs(2f * n - 1f) * turb
            sum += n * amp
            norm += amp
            amp *= 0.5f
            fx *= 2.03f
            fy *= 2.03f
        }
        return sum / norm
    }

    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val u = fx * fx * (3f - 2f * fx)
        val v = fy * fy * (3f - 2f * fy)
        val a = hash(ix, iy, seed)
        val b = hash(ix + 1, iy, seed)
        val c = hash(ix, iy + 1, seed)
        val d = hash(ix + 1, iy + 1, seed)
        return (a + (b - a) * u) + ((c + (d - c) * u) - (a + (b - a) * u)) * v
    }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var hh = x * 374761393 + y * 668265263 + seed * 2147483
        hh = (hh xor (hh ushr 13)) * 1274126177
        hh = hh xor (hh ushr 16)
        return (hh and 0xFFFFFF) / 16777215f
    }

    private fun sharpen(src: IntArray, w: Int, h: Int, inp: FilterInput): IntArray {
        val k = inp.v[0] / 100f * 2.5f
        val pm = premul(src)
        val sigma = max(0.6f, 1.6f * inp.scale)
        val bl = gaussian(pm, w, h, sigma, inp.token)
        val out = IntArray(pm.size)
        for (i in pm.indices) {
            val p = pm[i]
            val q = bl[i]
            val a = p ushr 24
            if (a == 0) continue
            val r = (((p shr 16) and 0xFF) + k * (((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))).toInt().coerceIn(0, a)
            val g = (((p shr 8) and 0xFF) + k * (((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))).toInt().coerceIn(0, a)
            val b = ((p and 0xFF) + k * ((p and 0xFF) - (q and 0xFF))).toInt().coerceIn(0, a)
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return unpremul(out)
    }

    private fun bloom(src: IntArray, w: Int, h: Int, inp: FilterInput): IntArray {
        val intensity = inp.v[0] / 100f * 2f
        val thr = 1f - inp.v[1] / 100f * 0.95f
        val sigma = (0.004f + inp.v[2] / 100f * 0.05f) * inp.fullMax * inp.scale
        val burn = inp.v[3] / 100f
        // Helle Bereiche extrahieren (vormultipliziert)
        val bright = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]
            val a = p ushr 24
            if (a == 0) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val l = (r * 77 + g * 150 + b * 29) / 65280f
            val wgt = smooth(thr - 0.08f, thr + 0.08f, l) * a / 255f
            if (wgt <= 0f) continue
            val aa = (255 * wgt).toInt()
            bright[i] = (aa shl 24) or ((r * wgt).toInt() shl 16) or ((g * wgt).toInt() shl 8) or (b * wgt).toInt()
        }
        val glow = gaussian(bright, w, h, max(0.5f, sigma), inp.token)
        val pm = premul(src)
        val out = IntArray(src.size)
        val gamma = 1f - burn * 0.6f
        for (i in pm.indices) {
            val p = pm[i]
            val q = glow[i]
            fun ch(s: Int): Int {
                val c = ((p shr s) and 0xFF) / 255f
                var gch = ((q shr s) and 0xFF) / 255f
                if (burn > 0f && gch > 0f) gch = gch.pow(gamma)
                gch = (gch * intensity).coerceAtMost(1f)
                return ((c + gch - c * gch) * 255f).toInt().coerceIn(0, 255)
            }
            val a = ch(24)
            if (a == 0) continue
            out[i] = (a shl 24) or (min(a, ch(16)) shl 16) or (min(a, ch(8)) shl 8) or min(a, ch(0))
        }
        return unpremul(out)
    }

    private fun glitch(src: IntArray, w: Int, h: Int, inp: FilterInput): IntArray {
        val type = inp.v[0].roundToInt()
        val amount = inp.v[1] / 100f
        val size = 0.05f + inp.v[2] / 100f
        val rnd = Random(1234L + inp.v[3].roundToInt() * 7919L)
        val sc = inp.fullMax * inp.scale
        val out = src.copyOf()
        when (type) {
            0 -> { // Artefakt: verschobene Blöcke mit Kanaltausch
                val blocks = (amount * 80).toInt() + 1
                val bs = max(2f, size * 0.12f * sc)
                for (n in 0 until blocks) {
                    inp.token.check()
                    val bw = (bs * (0.5f + rnd.nextFloat() * 2f)).toInt().coerceIn(1, w)
                    val bh = (bs * (0.2f + rnd.nextFloat() * 0.8f)).toInt().coerceIn(1, h)
                    val x0 = rnd.nextInt(max(1, w - bw + 1))
                    val y0 = rnd.nextInt(max(1, h - bh + 1))
                    val ox = ((rnd.nextFloat() - 0.5f) * 2f * bs * 2f * amount).toInt()
                    val oy = ((rnd.nextFloat() - 0.5f) * bs * 0.5f * amount).toInt()
                    val swap = rnd.nextInt(3)
                    for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) {
                        val sx = (x + ox).coerceIn(0, w - 1)
                        val sy = (y + oy).coerceIn(0, h - 1)
                        var p = src[sy * w + sx]
                        if (swap == 1) p = (p and 0xFF000000.toInt()) or ((p and 0xFF) shl 16) or (p and 0xFF00) or ((p shr 16) and 0xFF)
                        else if (swap == 2) p = (p and 0xFF000000.toInt()) or ((p and 0xFF00) shl 8) or ((p shr 8) and 0xFF00) or (p and 0xFF)
                        out[y * w + x] = p
                    }
                }
            }
            1 -> { // Welle
                val amp = amount * 0.04f * sc
                val period = max(4f, (0.02f + size * 0.25f) * sc)
                val phase = rnd.nextFloat() * 6.283f
                for (y in 0 until h) {
                    if (y and 31 == 0) inp.token.check()
                    val shift = (sin(y * 6.283f / period + phase) * amp).roundToInt()
                    for (x in 0 until w) out[y * w + x] = src[y * w + (x - shift).coerceIn(0, w - 1)]
                }
            }
            2 -> { // Signal: Störstreifen + RGB-Versatz
                val split = (amount * 0.012f * sc).roundToInt()
                var y = 0
                while (y < h) {
                    inp.token.check()
                    val band = max(1, (rnd.nextFloat() * size * 0.05f * sc).toInt())
                    val shift = if (rnd.nextFloat() < amount * 0.6f) ((rnd.nextFloat() - 0.5f) * amount * 0.15f * sc).toInt() else 0
                    for (yy in y until min(h, y + band)) {
                        val row = yy * w
                        for (x in 0 until w) {
                            val base = (x - shift).coerceIn(0, w - 1)
                            val pr = src[row + (base - split).coerceIn(0, w - 1)]
                            val pg = src[row + base]
                            val pb = src[row + (base + split).coerceIn(0, w - 1)]
                            val a = max(pg ushr 24, max(pr ushr 24, pb ushr 24))
                            out[row + x] = (a shl 24) or (pr and 0xFF0000) or (pg and 0xFF00) or (pb and 0xFF)
                        }
                    }
                    y += band
                }
            }
            else -> { // Divergieren: Farbkanäle auseinanderziehen
                val off = (amount * 0.025f * sc).roundToInt()
                val offY = (amount * size * 0.01f * sc).roundToInt()
                for (y in 0 until h) {
                    if (y and 31 == 0) inp.token.check()
                    for (x in 0 until w) {
                        val pr = src[(y - offY).coerceIn(0, h - 1) * w + (x - off).coerceIn(0, w - 1)]
                        val pg = src[y * w + x]
                        val pb = src[(y + offY).coerceIn(0, h - 1) * w + (x + off).coerceIn(0, w - 1)]
                        val a = max(pg ushr 24, max(pr ushr 24, pb ushr 24))
                        out[y * w + x] = (a shl 24) or (pr and 0xFF0000) or (pg and 0xFF00) or (pb and 0xFF)
                    }
                }
            }
        }
        return out
    }

    private fun halftone(src: IntArray, w: Int, h: Int, inp: FilterInput): IntArray {
        val amount = inp.v[1] / 100f
        val cell = max(2f, (0.003f + amount * 0.035f) * inp.fullMax * inp.scale)
        val color = inp.v[0] < 0.5f
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cols = (w / cell).toInt() + 2
        val rows = (h / cell).toInt() + 2
        // Durchschnittsfarbe je Zelle
        val avgR = FloatArray(cols * rows); val avgG = FloatArray(cols * rows); val avgB = FloatArray(cols * rows)
        val cnt = FloatArray(cols * rows)
        for (y in 0 until h) {
            val cy = (y / cell).toInt()
            for (x in 0 until w) {
                val p = src[y * w + x]
                val a = (p ushr 24) / 255f
                if (a == 0f) continue
                val ci = cy * cols + (x / cell).toInt()
                avgR[ci] += ((p shr 16) and 0xFF) * a; avgG[ci] += ((p shr 8) and 0xFF) * a; avgB[ci] += (p and 0xFF) * a
                cnt[ci] += a
            }
        }
        inp.token.check()
        if (color) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        val inks = if (color) intArrayOf(0xFF00AEEF.toInt(), 0xFFEC008C.toInt(), 0xFFFFF200.toInt()) else intArrayOf(Color.BLACK)
        val offsets = floatArrayOf(0f, 0.33f, 0.66f)
        for ((k, ink) in inks.withIndex()) {
            paint.color = ink
            val o = offsets[k] * cell
            for (cy in 0 until rows) for (cx in 0 until cols) {
                val ci = cy * cols + cx
                if (cnt[ci] <= 0f) continue
                val r = avgR[ci] / cnt[ci] / 255f; val g = avgG[ci] / cnt[ci] / 255f; val b = avgB[ci] / cnt[ci] / 255f
                val ink01 = if (!color) 1f - (0.299f * r + 0.587f * g + 0.114f * b) else when (k) { 0 -> 1f - r; 1 -> 1f - g; else -> 1f - b }
                if (ink01 <= 0.01f) continue
                val rad = cell * 0.72f * sqrt(ink01)
                c.drawCircle(cx * cell + cell / 2f + o, cy * cell + cell / 2f + o, rad, paint)
            }
        }
        val out = IntArray(w * h)
        bmp.getPixels(out, 0, w, 0, 0, w, h)
        bmp.recycle()
        for (i in out.indices) {
            val a = src[i] ushr 24
            out[i] = if (a == 0) 0 else (a shl 24) or (out[i] and 0xFFFFFF)
        }
        return out
    }

    private fun chromatic(src: IntArray, w: Int, h: Int, inp: FilterInput): IntArray {
        val k = inp.v[0] / 100f * 0.05f
        val start = inp.v[1] / 100f * 0.8f
        val cx = inp.cx * w
        val cy = inp.cy * h
        val maxD = hypot(max(cx, w - cx), max(cy, h - cy)).coerceAtLeast(1f)
        val out = IntArray(src.size)
        for (y in 0 until h) {
            if (y and 31 == 0) inp.token.check()
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                val f = ((hypot(dx, dy) / maxD - start) / (1f - start + 1e-4f)).coerceIn(0f, 1f)
                val s = k * f
                val pr = sample(src, w, h, cx + dx * (1f - s), cy + dy * (1f - s))
                val pg = src[y * w + x]
                val pb = sample(src, w, h, cx + dx * (1f + s), cy + dy * (1f + s))
                val a = max(pg ushr 24, max(pr ushr 24, pb ushr 24))
                if (a == 0) continue
                out[y * w + x] = (a shl 24) or (pr and 0xFF0000) or (pg and 0xFF00) or (pb and 0xFF)
            }
        }
        return out
    }

    /** Bilineares Abtasten (nicht vormultipliziert, für Farbversatz ausreichend). */
    private fun sample(src: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val fx = (x - 0.5f).coerceIn(0f, w - 1f)
        val fy = (y - 0.5f).coerceIn(0f, h - 1f)
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val tx = fx - x0; val ty = fy - y0
        val top = lerpPacked(src[y0 * w + x0], src[y0 * w + x1], tx)
        val bot = lerpPacked(src[y1 * w + x0], src[y1 * w + x1], tx)
        return lerpPacked(top, bot, ty)
    }

    // ================================================================= Hilfen

    fun lerpPacked(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
