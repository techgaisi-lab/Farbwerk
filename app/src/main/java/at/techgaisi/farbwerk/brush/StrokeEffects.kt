package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Rect
import android.graphics.RectF
import java.lang.ref.SoftReference
import java.util.BitSet
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Rendering-Effekte, die auf den fertigen Strich-Puffer wirken (vor dem Einmischen in die Ebene):
 * - Nasse Kanten: Inneres heller/transparenter, Ränder dichter (wie getrocknete Aquarellfarbe)
 * - Verbrannte Kanten: Ränder dunkler, Mischmodus wählbar
 * - Alpha-Schwelle: als Farbfilter beim Einmischen ([thresholdFilter]) – wirkt live und beim Festschreiben
 *
 * Kanten werden über die Differenz Alpha − weichgezeichnetes Alpha bestimmt (O(n), unabhängig vom Radius).
 */
object StrokeEffects {

    fun needsPostPass(b: Brush): Boolean = b[BP.WET_EDGES] > 0.001f || b[BP.BURNT_EDGES] > 0.001f

    /**
     * Wendet nasse/verbrannte Kanten auf [buffer] im Bereich [area] an. [diameter] bestimmt die Kantenbreite.
     * Läuft beim Absetzen (einmal pro Strich).
     *
     * Mit [touched] (vom Strich berührte Kacheln des Rasters [tile]) wird nur um den Strich herum gerechnet
     * ([applyBlocks]), sonst über den ganzen Bereich: gleiche Gleitkomma-Operationen in gleicher Reihenfolge wie die
     * ursprüngliche Fassung, aber der senkrechte Weichzeichner-Durchgang läuft zeilenweise (statt spaltenweise mit
     * einem Cache-Fehlzugriff pro Pixel), Arbeitsspeicher wird wiederverwendet und große Bereiche werden in Streifen
     * auf mehrere Kerne verteilt ([BrushParallel]).
     */
    fun apply(
        buffer: Bitmap, area: RectF, b: Brush, diameter: Float,
        touched: BitSet? = null, tile: Int = 0,
    ) {
        val wet = b[BP.WET_EDGES]
        val burnt = b[BP.BURNT_EDGES]
        if (wet <= 0.001f && burnt <= 0.001f) return
        val rad = max(1, (diameter * 0.12f).toInt()).coerceAtMost(48)
        // Leerer Rand von mindestens der Reichweite beider Weichzeichner-Durchgänge: Die Kanten des Strichs werden
        // so unabhängig davon bestimmt, wie eng [area] den Strich umschließt.
        val m = 2 * rad + 2
        val r = Rect(floor(area.left).toInt() - m, floor(area.top).toInt() - m, ceil(area.right).toInt() + m, ceil(area.bottom).toInt() + m)
        if (!r.intersect(0, 0, buffer.width, buffer.height) || r.isEmpty) return
        val w = r.width(); val h = r.height()
        if (w.toLong() * h > 40_000_000L) return // Schutz vor riesigen Bereichen
        val mode = b.int(BP.BURNT_MODE)
        if (touched != null && tile > 0 && applyBlocks(buffer, r, rad, touched, tile, wet, burnt, mode)) return
        val n = w * h
        val ws = takeWork(n)
        try {
            val px = ws.px; val bl = ws.bl; val tmp = ws.tmp
            buffer.getPixels(px, 0, w, r.left, r.top, w, h)
            val parts = if (n >= PARALLEL_MIN_PIXELS) BrushParallel.THREADS else 1
            // Alpha 0..1, zweimal (waagrecht + senkrecht) weichzeichnen
            BrushParallel.ranges(parts, h) { y0, y1 ->
                for (i in y0 * w until y1 * w) bl[i] = (px[i] ushr 24) / 255f
                for (y in y0 until y1) blurRow(bl, y * w, w, rad, ws.line(w))
            }
            BrushParallel.ranges(parts, w) { x0, x1 -> blurColumns(bl, tmp, w, h, rad, x0, x1) }
            BrushParallel.ranges(parts, h) { y0, y1 -> for (y in y0 until y1) blurRow(tmp, y * w, w, rad, ws.line(w)) }
            BrushParallel.ranges(parts, w) { x0, x1 -> blurColumns(tmp, bl, w, h, rad, x0, x1) }
            BrushParallel.ranges(parts, h) { y0, y1 -> shade(px, bl, y0 * w, y1 * w, wet, burnt, mode) }
            buffer.setPixels(px, 0, w, r.left, r.top, w, h)
        } finally {
            giveWork(ws)
        }
    }

    /**
     * Nur dort rechnen, wo der Strich ist: Der Bereich wird in Blöcke geteilt; bearbeitet werden nur Blöcke mit vom
     * Strich berührten Kacheln ([touched], Raster [tile] im ganzen Puffer – außerhalb ist der Puffer leer), jeweils
     * mit einem Rand von der Reichweite des Weichzeichners. Bei dünnen oder langen, schrägen Strichen ein Bruchteil
     * des umschließenden Rechtecks. Der Weichzeichner rechnet hier mit exakten ganzzahligen Summen (statt
     * fortlaufender Gleitkomma-Summen) – das Ergebnis weicht davon nur in seltenen Rundungsfällen um eine Stufe ab.
     * false, wenn sich das nicht lohnt (dann rechnet [apply] über den ganzen Bereich).
     */
    private fun applyBlocks(
        buffer: Bitmap, r: Rect, rad: Int, touched: BitSet, tile: Int, wet: Float, burnt: Float, mode: Int,
    ): Boolean {
        val halo = 2 * rad
        val bs = max(128, ((4 * rad + 63) / 64) * 64)
        val ntx = (buffer.width + tile - 1) / tile
        val nbx = (buffer.width + bs - 1) / bs
        val need = BitSet()
        var k = touched.nextSetBit(0)
        while (k >= 0) {
            val x0 = (k % ntx) * tile; val y0 = (k / ntx) * tile
            val x1 = min(x0 + tile, r.right); val y1 = min(y0 + tile, r.bottom)
            if (x1 > r.left && y1 > r.top && x0 < r.right && y0 < r.bottom) {
                for (by in max(y0, r.top) / bs..(y1 - 1) / bs) for (bx in max(x0, r.left) / bs..(x1 - 1) / bs) need.set(by * nbx + bx)
            }
            k = touched.nextSetBit(k + 1)
        }
        val count = need.cardinality()
        if (count == 0) return true
        val side = (bs + 2 * halo).toLong()
        if (count * side * side * 10 >= r.width().toLong() * r.height() * 7) return false
        val ids = IntArray(count)
        var i = 0
        k = need.nextSetBit(0)
        while (k >= 0) { ids[i++] = k; k = need.nextSetBit(k + 1) }
        val out = arrayOfNulls<IntArray>(count)
        val cores = Array(count) { Rect() }
        val win = 2 * rad + 1
        val denom = 255.0 * win.toDouble() * win * win * win
        val parts = min(BrushParallel.THREADS, count)
        // 1. je Block: Alpha mit Rand lesen, weichzeichnen, Kern schattieren (liest nur unveränderte Pixel)
        BrushParallel.run(parts) { p ->
            val bw = BlockWork()
            var j = p
            while (j < count) {
                val id = ids[j]
                val core = cores[j]
                core.set((id % nbx) * bs, (id / nbx) * bs, (id % nbx) * bs + bs, (id / nbx) * bs + bs)
                core.intersect(r)
                out[j] = blurBlock(buffer, r, core, halo, rad, denom, bw, wet, burnt, mode)
                j += parts
            }
        }
        // 2. alle Kerne zurückschreiben (erst jetzt – die Ränder der Blöcke lesen die Nachbarkerne)
        for (j in 0 until count) {
            val c = cores[j]
            buffer.setPixels(out[j]!!, 0, c.width(), c.left, c.top, c.width(), c.height())
        }
        return true
    }

    /** Arbeitsspeicher eines Threads für [blurBlock]. */
    private class BlockWork {
        var a = IntArray(0); var s1 = IntArray(0); var s2 = IntArray(0); var s3 = IntArray(0)
        var col = LongArray(0)
        fun ensure(n: Int, w: Int) {
            if (a.size < n) { a = IntArray(n); s1 = IntArray(n); s2 = IntArray(n); s3 = IntArray(n) }
            if (col.size < w) col = LongArray(w)
        }
    }

    /**
     * Weichzeichnet das Alpha um [core] (mit Rand [halo], begrenzt auf [r]; an den Grenzen von [r] fortgesetzt wie
     * in [blurRow]/[blurColumns]) und schattiert den Kern. Liefert die neuen Pixel des Kerns.
     */
    private fun blurBlock(
        buffer: Bitmap, r: Rect, core: Rect, halo: Int, rad: Int, denom: Double, bw: BlockWork,
        wet: Float, burnt: Float, mode: Int,
    ): IntArray {
        val hx0 = max(r.left, core.left - halo); val hy0 = max(r.top, core.top - halo)
        val hx1 = min(r.right, core.right + halo); val hy1 = min(r.bottom, core.bottom + halo)
        val w = hx1 - hx0; val h = hy1 - hy0
        bw.ensure(w * h, w)
        val a = bw.a; val s1 = bw.s1; val s2 = bw.s2; val s3 = bw.s3
        buffer.getPixels(a, 0, w, hx0, hy0, w, h)
        val cw = core.width(); val ch = core.height()
        val cx = core.left - hx0; val cy = core.top - hy0
        // Farben des Kerns (vor dem Umwandeln in Alpha)
        val px = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(a, (cy + y) * w + cx, px, y * cw, cw)
        for (q in 0 until w * h) a[q] = a[q] ushr 24
        // waagrecht zweimal (alle Zeilen des Randbereichs)
        for (y in 0 until h) { boxRow(a, s1, y * w, w, rad); boxRow(s1, s2, y * w, w, rad) }
        // senkrecht (gleitende Spaltensummen, zeilenweise): erster Durchgang für die Zeilen Kern ± rad, zweiter
        // nur für den Kern
        val y0 = max(0, cy - rad); val y1 = min(h, cy + ch + rad)
        val x0 = cx; val x1 = cx + cw
        val col = bw.col
        for (x in x0 until x1) {
            var sum = 0L
            for (t in -rad..rad) sum += s2[(y0 + t).coerceIn(0, h - 1) * w + x]
            col[x - x0] = sum
        }
        for (y in y0 until y1) {
            val o = y * w
            val add = (y + rad + 1).coerceIn(0, h - 1) * w
            val sub = (y - rad).coerceIn(0, h - 1) * w
            for (x in x0 until x1) {
                val j = x - x0
                s3[o + x] = col[j].toInt()
                col[j] += (s2[add + x] - s2[sub + x]).toLong()
            }
        }
        for (x in x0 until x1) {
            var sum = 0L
            for (t in -rad..rad) sum += s3[(cy + t).coerceIn(0, h - 1) * w + x].toLong()
            col[x - x0] = sum
        }
        for (y in 0 until ch) {
            val yy = cy + y
            val o = y * cw
            val last = y == ch - 1
            val add = (yy + rad + 1).coerceIn(0, h - 1) * w
            val sub = (yy - rad).coerceIn(0, h - 1) * w
            for (x in 0 until cw) {
                val s4 = col[x]
                if (!last) col[x] = s4 + (s3[add + x0 + x] - s3[sub + x0 + x]).toLong()
                if ((px[o + x] ushr 24) == 0) continue
                shadePixel(px, o + x, (s4 / denom).toFloat(), wet, burnt, mode)
            }
        }
        return px
    }

    /** Waagrechte Fenstersumme (Breite 2·[r]+1, Ränder fortgesetzt) einer Zeile von [src] nach [dst]. */
    private fun boxRow(src: IntArray, dst: IntArray, o: Int, w: Int, r: Int) {
        var sum = 0
        for (k in -r..r) sum += src[o + k.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            dst[o + x] = sum
            sum += src[o + (x + r + 1).coerceIn(0, w - 1)] - src[o + (x - r).coerceIn(0, w - 1)]
        }
    }

    /** Pixel [from] until [to]: Kanten bestimmen und nasse/verbrannte Kanten auftragen. */
    private fun shade(px: IntArray, bl: FloatArray, from: Int, to: Int, wet: Float, burnt: Float, mode: Int) {
        for (i in from until to) {
            if ((px[i] ushr 24) == 0) continue
            shadePixel(px, i, bl[i], wet, burnt, mode)
        }
    }

    /** Ein Pixel (nicht leer): Kante aus Alpha − weichgezeichnetem Alpha [blurred], Effekte auftragen. */
    private fun shadePixel(px: IntArray, i: Int, blurred: Float, wet: Float, burnt: Float, mode: Int) {
        val c = px[i]
        val al = (c ushr 24) / 255f
        val edge = ((al - blurred) * 3f).coerceIn(0f, 1f)
        // nicht vormultiplizierte Farbe
        var rr = Color.red(c) / al / 255f; var gg = Color.green(c) / al / 255f; var bb = Color.blue(c) / al / 255f
        var na = al
        if (wet > 0f) {
            na = (al * (1f - 0.55f * wet) + wet * edge * al * 1.2f).coerceIn(0f, al)
        }
        if (burnt > 0f && edge > 0f) {
            val k = (burnt * edge).coerceIn(0f, 1f)
            rr += (burn(rr, mode) - rr) * k; gg += (burn(gg, mode) - gg) * k; bb += (burn(bb, mode) - bb) * k
        }
        val ai = (na * 255f + 0.5f).toInt().coerceIn(0, 255)
        px[i] = Color.argb(
            ai,
            (rr.coerceIn(0f, 1f) * ai + 0.5f).toInt(),
            (gg.coerceIn(0f, 1f) * ai + 0.5f).toInt(),
            (bb.coerceIn(0f, 1f) * ai + 0.5f).toInt(),
        )
    }

    /**
     * Waagrechter gleitender Mittelwert einer Zeile (Ränder fortgesetzt) – rechnet exakt wie
     * [BrushImages.boxBlur] mit `wrap = false`.
     */
    private fun blurRow(v: FloatArray, o: Int, w: Int, r: Int, line: FloatArray) {
        val win = 2 * r + 1
        var sum = 0f
        for (k in -r..r) sum += v[o + k.coerceIn(0, w - 1)]
        for (x in 0 until w) {
            line[x] = sum / win
            sum += v[o + (x + r + 1).coerceIn(0, w - 1)] - v[o + (x - r).coerceIn(0, w - 1)]
        }
        System.arraycopy(line, 0, v, o, w)
    }

    /**
     * Senkrechter gleitender Mittelwert der Spalten [x0] until [x1] von [src] nach [dst], zeilenweise (eine laufende
     * Summe je Spalte). Je Spalte dieselben Operationen in derselben Reihenfolge wie [BrushImages.boxBlur].
     */
    private fun blurColumns(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, x0: Int, x1: Int) {
        if (x0 >= x1) return
        val win = 2 * r + 1
        val sums = FloatArray(x1 - x0)
        for (k in -r..r) {
            val o = k.coerceIn(0, h - 1) * w
            for (x in x0 until x1) sums[x - x0] += src[o + x]
        }
        for (y in 0 until h) {
            val o = y * w
            val add = (y + r + 1).coerceIn(0, h - 1) * w
            val sub = (y - r).coerceIn(0, h - 1) * w
            for (x in x0 until x1) {
                val j = x - x0
                val s = sums[j]
                dst[o + x] = s / win
                sums[j] = s + (src[add + x] - src[sub + x])
            }
        }
    }

    // ------------------------------------------------------------ Arbeitsspeicher und Kerne

    /** Wiederverwendeter Arbeitsspeicher (wächst nur). */
    private class Work(n: Int) {
        val px = IntArray(n)
        val bl = FloatArray(n)
        val tmp = FloatArray(n)
        private val lines = ThreadLocal<FloatArray>()
        fun line(w: Int): FloatArray {
            val l = lines.get()
            if (l != null && l.size >= w) return l
            return FloatArray(w).also { lines.set(it) }
        }
    }

    /**
     * Zwischengespeicherter Arbeitsspeicher; leer, solange ihn ein Aufruf benutzt (thread-sicher ohne Sperre).
     * Weich referenziert: bei Speicherknappheit gibt ihn die Speicherbereinigung frei.
     */
    private val cached = AtomicReference<SoftReference<Work>?>(null)

    private fun takeWork(n: Int): Work {
        val w = cached.getAndSet(null)?.get()
        return if (w != null && w.px.size >= n) w else Work(n)
    }

    private fun giveWork(w: Work) {
        // sehr große Puffer nicht festhalten
        if (w.px.size <= MAX_CACHED_PIXELS) cached.set(SoftReference(w))
    }

    private const val PARALLEL_MIN_PIXELS = 250_000
    private const val MAX_CACHED_PIXELS = 6_000_000

    private fun burn(c: Float, mode: Int): Float = when (mode) {
        BurnMode.COLOR_BURN -> if (c <= 0.001f) 0f else (1f - (1f - c) / c).coerceIn(0f, 1f)
        BurnMode.LINEAR_BURN -> (2f * c - 1f).coerceIn(0f, 1f)
        BurnMode.DARKEN -> min(c, 0.35f)
        else -> c * c
    }

    /**
     * Farbfilter für die Alpha-Schwelle: jedes Pixel wird ganz deckend oder ganz transparent
     * (steile lineare Rampe auf dem Alphakanal, die durch Begrenzung zur Stufe wird).
     */
    fun thresholdFilter(t: Float): ColorMatrixColorFilter {
        val k = 255f
        val cm = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, k, -k * t.coerceIn(0.01f, 0.99f) * 255f + 0.5f,
        ))
        return ColorMatrixColorFilter(cm)
    }
}
