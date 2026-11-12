package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import java.nio.ByteBuffer
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bildverarbeitung für Pinselformen und -körnungen: eigene Bilder (Foto/Datei/Einfügen) in 256×256-Graustufen
 * (ALPHA_8) umwandeln, kachelbar machen, unscharfe Formvarianten und vorbereitete Körnungsmasken erzeugen.
 * Thread-sicher (Vorschauen entstehen im Hintergrund).
 */
object BrushImages {
    const val SIZE = 256

    // ------------------------------------------------------------ Grundfunktionen

    /** Liest ein ALPHA_8-Bild in ein FloatArray 0..1. */
    fun toFloats(b: Bitmap): FloatArray {
        val n = b.width * b.height
        val out = FloatArray(n)
        if (b.config == Bitmap.Config.ALPHA_8) {
            val buf = ByteBuffer.allocate(b.rowBytes * b.height)
            b.copyPixelsToBuffer(buf)
            val arr = buf.array()
            for (y in 0 until b.height) for (x in 0 until b.width) {
                out[y * b.width + x] = (arr[y * b.rowBytes + x].toInt() and 0xFF) / 255f
            }
        } else {
            val px = IntArray(n)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            for (i in 0 until n) out[i] = Color.alpha(px[i]) / 255f
        }
        return out
    }

    /** Erzeugt ein ALPHA_8-Bild aus Werten 0..1. */
    fun fromFloats(a: FloatArray, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val buf = ByteBuffer.allocate(b.rowBytes * h)
        val arr = buf.array()
        for (y in 0 until h) for (x in 0 until w) {
            arr[y * b.rowBytes + x] = (a[y * w + x].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
        b.copyPixelsFromBuffer(buf)
        return b
    }

    /**
     * Wandelt ein beliebiges Bild in eine Form/Körnung um: auf [SIZE]² eingepasst (Form: zentriert mit Rand;
     * Körnung: formatfüllend), Luminanz als Wert. Weiß = Farbe. Transparente Bereiche zählen als schwarz.
     * Sind die Ränder überwiegend hell (dunkles Motiv auf weißem Papier), wird für Formen automatisch invertiert.
     */
    fun importImage(src: Bitmap, forGrain: Boolean): Bitmap {
        val argb = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(argb)
        c.drawColor(Color.BLACK)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val sw = src.width.toFloat(); val sh = src.height.toFloat()
        val scale = if (forGrain) max(SIZE / sw, SIZE / sh) else min(SIZE / sw, SIZE / sh) * 0.94f
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((SIZE - sw * scale) / 2f, (SIZE - sh * scale) / 2f)
        }
        c.drawBitmap(src, m, p)
        val px = IntArray(SIZE * SIZE)
        argb.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        argb.recycle()
        val v = FloatArray(px.size)
        for (i in px.indices) {
            val q = px[i]
            v[i] = (0.2126f * Color.red(q) + 0.7152f * Color.green(q) + 0.0722f * Color.blue(q)) / 255f
        }
        if (!forGrain) {
            // Randhelligkeit prüfen
            var edge = 0f; var cnt = 0
            for (k in 0 until SIZE) {
                edge += v[k] + v[(SIZE - 1) * SIZE + k] + v[k * SIZE] + v[k * SIZE + SIZE - 1]; cnt += 4
            }
            if (edge / cnt > 0.5f) for (i in v.indices) v[i] = 1f - v[i]
        }
        return fromFloats(v, SIZE, SIZE)
    }

    /** Invertiert ein ALPHA_8-Bild. */
    fun invert(b: Bitmap): Bitmap {
        val v = toFloats(b)
        for (i in v.indices) v[i] = 1f - v[i]
        return fromFloats(v, b.width, b.height)
    }

    /**
     * Körnungs-Editor „Automatisch wiederholen“: skaliert ([scale] 0.25..4, kachelt dabei), dreht in 90°-Schritten
     * und überblendet die Ränder ([overlap] 0..0.5, [hardness] 0..1 Maskenhärte) mit der gegenüberliegenden
     * Seite, damit die Kachel nahtlos wird. [mirror] überblendet zusätzlich mit der gespiegelten Kachel.
     */
    fun autoRepeat(b: Bitmap, scale: Float, rotate90: Int, overlap: Float, hardness: Float, mirror: Boolean): Bitmap {
        val n = b.width
        val src = toFloats(b)
        val out = FloatArray(n * n)
        val s = scale.coerceIn(0.25f, 4f)
        val r = ((rotate90 % 4) + 4) % 4
        for (y in 0 until n) for (x in 0 until n) {
            var u = x; var w = y
            when (r) { 1 -> { u = y; w = n - 1 - x }; 2 -> { u = n - 1 - x; w = n - 1 - y }; 3 -> { u = n - 1 - y; w = x } }
            val sx = (((u / s).toInt() % n) + n) % n
            val sy = (((w / s).toInt() % n) + n) % n
            out[y * n + x] = src[sy * n + sx]
        }
        var res = out
        val ov = (overlap.coerceIn(0f, 0.5f) * n).roundToInt()
        if (ov > 0) {
            val tmp = out.copyOf()
            fun weight(d: Int): Float {
                // d = Abstand zum Rand; 0 → 0.5 Mischung mit der Gegenseite, ov → 0
                // Härte: 0 = weicher, langer Übergang, 1 = schneller Abfall zur Mitte hin
                val t = (d / ov.toFloat()).coerceIn(0f, 1f)
                val k = 1f + hardness.coerceIn(0f, 1f) * 4f
                return 0.5f * Math.pow((1f - t).toDouble(), k.toDouble()).toFloat()
            }
            for (y in 0 until n) for (x in 0 until n) {
                var v = tmp[y * n + x]
                val dx = min(x, n - 1 - x)
                if (dx < ov) {
                    val wgt = weight(dx)
                    v = v * (1 - wgt) + tmp[y * n + (n - 1 - x)] * wgt
                }
                val dy = min(y, n - 1 - y)
                if (dy < ov) {
                    val wgt = weight(dy)
                    v = v * (1 - wgt) + tmp[(n - 1 - y) * n + x] * wgt
                }
                out[y * n + x] = v
            }
            res = out
        }
        if (mirror) {
            val m = res.copyOf()
            for (y in 0 until n) for (x in 0 until n) res[y * n + x] = (m[y * n + x] + m[y * n + (n - 1 - x)]) * 0.5f
        }
        return fromFloats(res, n, n)
    }

    // ------------------------------------------------------------ Varianten (gecacht)

    private val blurCache = WeakHashMap<Bitmap, Array<Bitmap?>>()

    /** Unscharfe Variante einer Form (Stufe 1..4); Stufe 0 = Original. */
    @Synchronized
    fun blurred(shape: Bitmap, level: Int): Bitmap {
        if (level <= 0) return shape
        val arr = blurCache.getOrPut(shape) { arrayOfNulls(5) }
        arr[level]?.let { return it }
        val n = shape.width
        val v = toFloats(shape)
        val r = level * 6
        repeat(2) { boxBlur(v, n, n, r) }
        val b = fromFloats(v, n, n)
        arr[level] = b
        return b
    }

    private val grainCache = HashMap<String, Bitmap>()

    /**
     * Körnungsmaske für [DabPainter]: Alpha = 1 − g', wobei g' die mit Helligkeit/Kontrast/Invertierung
     * bearbeitete Körnung ist (weiß = Farbe bleibt). Wird mit DST_OUT und Deckkraft = Tiefe gezeichnet:
     * Ergebnis = Tupfer · (1 − Tiefe · (1 − g')).
     */
    @Synchronized
    fun grainMask(kind: Int, custom: Bitmap?, invert: Boolean, brightness: Float, contrast: Float): Bitmap? {
        if (custom == null && (kind <= 0 || kind >= GrainKind.entries.size)) return null
        val bq = (brightness * 50).roundToInt(); val cq = (contrast * 50).roundToInt()
        val key = "${custom?.let { System.identityHashCode(it) } ?: "k$kind"}|$invert|$bq|$cq"
        grainCache[key]?.let { return it }
        val base = toFloats(custom ?: BrushTextures.grain(kind, 1f, false) ?: return null)
        val bright = bq / 50f; val con = cq / 50f
        val k = if (con >= 0f) 1f + con * 4f else 1f + con
        for (i in base.indices) {
            var g = if (invert) 1f - base[i] else base[i]
            g = ((g - 0.5f) * k + 0.5f + bright * 0.5f).coerceIn(0f, 1f)
            base[i] = 1f - g
        }
        val n = custom?.width ?: BrushTextures.GRAIN_SIZE
        val b = fromFloats(base, n, n)
        if (grainCache.size > 32) grainCache.clear()
        grainCache[key] = b
        return b
    }

    /** Weiße ARGB-Vorschau eines ALPHA_8-Bildes. */
    fun thumbnail(src: Bitmap, size: Int = 96): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        Canvas(out).drawBitmap(src, null, Rect(0, 0, size, size), p)
        return out
    }

    /** Graustufen-Vorschau (weiß auf schwarz) eines ALPHA_8-Bildes – für die Editoren. */
    fun grayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(src, 0f, 0f, Paint().apply { color = Color.WHITE })
        return out
    }

    // ------------------------------------------------------------ Weichzeichner

    /** Gleitender Mittelwert (horizontal + vertikal), O(n) unabhängig vom Radius, kachelnd. */
    fun boxBlur(v: FloatArray, w: Int, h: Int, r: Int, wrap: Boolean = true) {
        if (r <= 0) return
        val line = FloatArray(max(w, h))
        val win = 2 * r + 1
        for (y in 0 until h) {
            var sum = 0f
            for (k in -r..r) sum += v[y * w + idx(k, w, wrap)]
            for (x in 0 until w) {
                line[x] = sum / win
                sum += v[y * w + idx(x + r + 1, w, wrap)] - v[y * w + idx(x - r, w, wrap)]
            }
            System.arraycopy(line, 0, v, y * w, w)
        }
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += v[idx(k, h, wrap) * w + x]
            for (y in 0 until h) {
                line[y] = sum / win
                sum += v[idx(y + r + 1, h, wrap) * w + x] - v[idx(y - r, h, wrap) * w + x]
            }
            for (y in 0 until h) v[y * w + x] = line[y]
        }
    }

    private fun idx(i: Int, n: Int, wrap: Boolean): Int =
        if (wrap) ((i % n) + n) % n else i.coerceIn(0, n - 1)
}
