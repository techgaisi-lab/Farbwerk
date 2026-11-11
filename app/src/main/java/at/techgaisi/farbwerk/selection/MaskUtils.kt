package at.techgaisi.farbwerk.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Hilfsfunktionen für Auswahlmasken (ALPHA_8, Dokumentgröße, 255 = ausgewählt).
 * Alle Funktionen liefern neue Bitmaps und verändern ihre Eingaben nicht (Auswahlen sind damit
 * unveränderlich und können gefahrlos im Verlauf referenziert werden).
 */
object MaskUtils {

    fun create(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)

    /** Vollständig ausgewählte Maske. */
    fun full(w: Int, h: Int): Bitmap = create(w, h).apply { eraseColor(Color.BLACK) }

    /** Liest die Maske als dicht gepacktes Byte-Array (w*h, ohne Zeilen-Padding). */
    fun readBytes(mask: Bitmap): ByteArray {
        val w = mask.width
        val h = mask.height
        val rb = mask.rowBytes
        val buf = ByteBuffer.allocate(rb * h)
        mask.copyPixelsToBuffer(buf)
        val raw = buf.array()
        if (rb == w) return raw
        val out = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(raw, y * rb, out, y * w, w)
        return out
    }

    /** Schreibt ein dicht gepacktes Byte-Array (w*h) in eine ALPHA_8-Maske. */
    fun writeBytes(mask: Bitmap, bytes: ByteArray) {
        val w = mask.width
        val h = mask.height
        val rb = mask.rowBytes
        if (rb == w) {
            mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, w * h))
            return
        }
        val raw = ByteArray(rb * h)
        for (y in 0 until h) System.arraycopy(bytes, y * w, raw, y * rb, w)
        mask.copyPixelsFromBuffer(ByteBuffer.wrap(raw))
    }

    fun fromBytes(w: Int, h: Int, bytes: ByteArray): Bitmap = create(w, h).also { writeBytes(it, bytes) }

    /** Rastert einen Pfad (Dokument-Koordinaten) in eine neue Maske. */
    fun fromPath(w: Int, h: Int, path: Path): Bitmap {
        val m = create(w, h)
        Canvas(m).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        return m
    }

    /** Vereinigt ([subtract] = false) bzw. entfernt [shape] aus [base]. */
    fun combine(base: Bitmap, shape: Bitmap, subtract: Boolean): Bitmap {
        val out = base.copy(Bitmap.Config.ALPHA_8, true)
        val p = Paint()
        if (subtract) p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        Canvas(out).drawBitmap(shape, 0f, 0f, p)
        return out
    }

    fun invert(mask: Bitmap): Bitmap {
        val b = readBytes(mask)
        for (i in b.indices) b[i] = (255 - (b[i].toInt() and 0xFF)).toByte()
        return fromBytes(mask.width, mask.height, b)
    }

    /** Weiche Kante: Annäherung an einen Gauß-Weichzeichner durch drei Box-Durchgänge. */
    fun feather(mask: Bitmap, radius: Float): Bitmap {
        val r = (radius / 1.7f).toInt()
        if (r < 1) return mask.copy(Bitmap.Config.ALPHA_8, true)
        val w = mask.width
        val h = mask.height
        val bytes = readBytes(mask)
        val a = IntArray(w * h) { bytes[it].toInt() and 0xFF }
        val tmp = IntArray(w * h)
        repeat(3) {
            boxH(a, tmp, w, h, r)
            boxV(tmp, a, w, h, r)
        }
        for (i in bytes.indices) bytes[i] = a[i].coerceIn(0, 255).toByte()
        return fromBytes(w, h, bytes)
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (k in -r..r) sum += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = sum / div
                sum += src[row + min(w - 1, x + r + 1)] - src[row + max(0, x - r)]
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sum = 0
            for (k in -r..r) sum += src[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / div
                sum += src[min(h - 1, y + r + 1) * w + x] - src[max(0, y - r) * w + x]
            }
        }
    }

    /** Begrenzungsrechteck aller Maskenpixel > 0 oder null, wenn die Maske leer ist. */
    fun bounds(mask: Bitmap): Rect? = bounds(readBytes(mask), mask.width, mask.height)

    fun bounds(bytes: ByteArray, w: Int, h: Int): Rect? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            var first = -1
            var last = -1
            for (x in 0 until w) if (bytes[row + x].toInt() != 0) { first = x; break }
            if (first < 0) continue
            for (x in w - 1 downTo first) if (bytes[row + x].toInt() != 0) { last = x; break }
            if (first < l) l = first
            if (last > r) r = last
            if (y < t) t = y
            b = y
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }

    /** Begrenzungsrechteck aller nicht transparenten Pixel einer ARGB-Bitmap. */
    fun alphaBounds(bmp: Bitmap): Rect? {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var first = -1
            for (x in 0 until w) if (row[x] ushr 24 != 0) { first = x; break }
            if (first < 0) continue
            var last = first
            for (x in w - 1 downTo first) if (row[x] ushr 24 != 0) { last = x; break }
            if (first < l) l = first
            if (last > r) r = last
            if (y < t) t = y
            b = y
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }

    /** Kopiert immer (auch bei vollem Ausschnitt) einen Bereich in eine neue, veränderbare Bitmap. */
    fun crop(src: Bitmap, r: Rect): Bitmap {
        val out = Bitmap.createBitmap(r.width(), r.height(), src.config ?: Bitmap.Config.ARGB_8888)
        val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        Canvas(out).drawBitmap(src, -r.left.toFloat(), -r.top.toFloat(), p)
        return out
    }

    /**
     * Wandelt die Maskenkontur in einen Pfad um (für die Ameisenlinie). Bei großen Masken wird auf
     * einem gröberen Raster gearbeitet; Kanten werden zu geschlossenen Polygonzügen verkettet und
     * kollineare Punkte zusammengefasst.
     */
    fun contour(mask: Bitmap, maxGrid: Int = 1024): Path {
        val w = mask.width
        val h = mask.height
        val bytes = readBytes(mask)
        val step = max(1, ceil(max(w, h) / maxGrid.toFloat()).toInt())
        val gw = (w + step - 1) / step
        val gh = (h + step - 1) / step
        val inside = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            val sy = min(h - 1, gy * step + step / 2)
            for (gx in 0 until gw) {
                val sx = min(w - 1, gx * step + step / 2)
                inside[gy * gw + gx] = (bytes[sy * w + sx].toInt() and 0xFF) >= 128
            }
        }
        val vw = gw + 1
        val nv = vw * (gh + 1)
        val out1 = IntArray(nv) { -1 }
        val out2 = IntArray(nv) { -1 }
        fun addEdge(a: Int, b: Int) { if (out1[a] < 0) out1[a] = b else out2[a] = b }
        fun ins(x: Int, y: Int) = x in 0 until gw && y in 0 until gh && inside[y * gw + x]
        for (y in 0 until gh) for (x in 0 until gw) {
            if (!inside[y * gw + x]) continue
            val tl = y * vw + x
            val tr = tl + 1
            val bl = tl + vw
            val br = bl + 1
            if (!ins(x, y - 1)) addEdge(tl, tr)
            if (!ins(x + 1, y)) addEdge(tr, br)
            if (!ins(x, y + 1)) addEdge(br, bl)
            if (!ins(x - 1, y)) addEdge(bl, tl)
        }
        fun take(v: Int): Int {
            val n = out1[v]
            if (n >= 0) { out1[v] = out2[v]; out2[v] = -1; return n }
            return -1
        }
        val path = Path()
        fun px(v: Int) = min(w, (v % vw) * step).toFloat()
        fun py(v: Int) = min(h, (v / vw) * step).toFloat()
        for (start in 0 until nv) {
            while (out1[start] >= 0) {
                path.moveTo(px(start), py(start))
                var cur = start
                var prevDir = -1
                while (true) {
                    val next = take(cur)
                    if (next < 0) break
                    val dir = when (next - cur) { 1 -> 0; -1 -> 1; vw -> 2; else -> 3 }
                    if (prevDir >= 0 && dir != prevDir) path.lineTo(px(cur), py(cur))
                    prevDir = dir
                    cur = next
                }
                path.close()
            }
        }
        return path
    }

    /**
     * Flutfüllung (Scanline) auf ARGB-Pixeln. [tolerance] 0..1 (0 = nur exakt gleiche Farbe).
     * Liefert eine Maske als Byte-Array (w*h) oder null bei ungültigem Startpunkt.
     */
    fun floodFill(px: IntArray, w: Int, h: Int, sx: Int, sy: Int, tolerance: Float): ByteArray? {
        if (sx !in 0 until w || sy !in 0 until h) return null
        val out = ByteArray(w * h)
        val seed = norm(px[sy * w + sx])
        val sa = seed ushr 24; val sr = (seed shr 16) and 0xFF; val sg = (seed shr 8) and 0xFF; val sb = seed and 0xFF
        val limit = (tolerance * tolerance * 4f * 255f * 255f).toInt()
        fun match(i: Int): Boolean {
            if (out[i].toInt() != 0) return false
            val c = norm(px[i])
            val da = (c ushr 24) - sa
            val dr = ((c shr 16) and 0xFF) - sr
            val dg = ((c shr 8) and 0xFF) - sg
            val db = (c and 0xFF) - sb
            return da * da + dr * dr + dg * dg + db * db <= limit
        }
        var stack = IntArray(1024)
        var sp = 0
        fun push(i: Int) {
            if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
            stack[sp++] = i
        }
        push(sy * w + sx)
        while (sp > 0) {
            val i = stack[--sp]
            if (!match(i)) continue
            val y = i / w
            val row = y * w
            var l = i - row
            var r = l
            while (l > 0 && match(row + l - 1)) l--
            while (r < w - 1 && match(row + r + 1)) r++
            for (x in l..r) out[row + x] = -1
            for (ny in intArrayOf(y - 1, y + 1)) {
                if (ny < 0 || ny >= h) continue
                val nrow = ny * w
                var x = l
                while (x <= r) {
                    if (match(nrow + x)) {
                        push(nrow + x)
                        while (x <= r && match(nrow + x)) x++
                    } else x++
                }
            }
        }
        return out
    }

    /** Transparente Pixel vergleichen sich unabhängig von ihren (bedeutungslosen) RGB-Werten. */
    private fun norm(c: Int): Int = if (c ushr 24 == 0) 0 else c
}
