package at.techgaisi.farbwerk.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Schnelles internes Ebenenformat ".fwl" für die Projektdatei (nicht für Exporte – die bleiben PNG/PSD).
 *
 * Die Ebene wird in Kacheln ([TILE]×[TILE]) zerlegt. Leere Kacheln (alle Bytes 0) kosten nichts; alle anderen
 * werden mit einem Sub-Filter (wie PNG) und Deflate Stufe 1 komprimiert – parallel auf mehreren Kernen.
 * Jede Kachel trägt zwei Prüfsummen (CRC32 + Adler32 der Rohdaten). Beim nächsten Speichern werden unveränderte
 * Kacheln (gleiche Prüfsummen) unverändert aus der alten Datei übernommen; nur geänderte Kacheln werden neu
 * komprimiert. Damit ist keine Änderungsverfolgung der Bitmaps nötig – die Erkennung beruht nur auf dem Inhalt.
 *
 * Pixel werden als Rohspeicher (vormultipliziert, wie [Bitmap.copyPixelsToBuffer]) gespeichert – der Rundlauf
 * ist bitgenau. Gelesen wird in Streifen zu [TILE] Zeilen; es wird nie eine Kopie der ganzen Ebene angelegt.
 *
 * ```
 * int  MAGIC 'FWL1'   int version   int width   int height   int bpp (1|4)   int tile   int filter
 * int  tileCount      tileCount × (int crc, int adler, int length)   (length 0 = leere Kachel)
 * byte[] komprimierte Kacheln hintereinander (rohes Deflate ohne zlib-Kopf)
 * ```
 * Alle Zahlen Big Endian.
 */
internal object LayerCodec {
    private const val MAGIC = 0x46574C31 // "FWL1"
    private const val VERSION = 1
    const val TILE = 256
    private const val FILTER_SUB = 1
    private const val LEVEL = 1
    private const val HEADER_INTS = 8

    private val threadCount = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
    private val counter = AtomicInteger()
    private val pool: ExecutorService = Executors.newFixedThreadPool(threadCount) { r ->
        Thread(r, "farbwerk-codec-${counter.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    /** Arbeitsobjekte je Thread (Kachel-/Streifenpuffer, Prüfsummen, Deflater/Inflater). */
    private class Work {
        val tile = ByteArray(TILE * TILE * 4)
        val out = ByteArray(TILE * TILE * 4 + 1024)
        val crc = CRC32()
        val adler = Adler32()
        val deflater = Deflater(LEVEL, true)
        val inflater = Inflater(true)
        /** Pixel eines Streifens (TILE Zeilen); weich gehalten, damit der Speicher bei Bedarf frei wird. */
        private var band: java.lang.ref.SoftReference<ByteArray>? = null
        fun band(bytes: Int): ByteArray {
            band?.get()?.let { if (it.size >= bytes) return it }
            return ByteArray(bytes).also { band = java.lang.ref.SoftReference(it) }
        }
    }
    private val work = ThreadLocal.withInitial { Work() }
    /** Kachelinhalt: Prüfsummen und komprimierte Daten (Ausschnitt aus [data]). */
    private class TileOut(val crc: Int, val adler: Int, val data: ByteArray?, val off: Int, val len: Int)

    /** Eine gelesene (alte) Ebenendatei, für die Übernahme unveränderter Kacheln. */
    class Parsed(val blob: ByteArray) {
        val width: Int; val height: Int; val bpp: Int; val tile: Int; val filter: Int
        val crc: IntArray; val adler: IntArray; val len: IntArray; val off: IntArray
        init {
            val b = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN)
            if (blob.size < HEADER_INTS * 4 || b.getInt(0) != MAGIC) throw IOException("Keine FWL-Ebene")
            if (b.getInt(4) != VERSION) throw IOException("Unbekannte FWL-Version")
            width = b.getInt(8); height = b.getInt(12); bpp = b.getInt(16); tile = b.getInt(20); filter = b.getInt(24)
            val n = b.getInt(28)
            if (width <= 0 || height <= 0 || (bpp != 1 && bpp != 4) || tile <= 0 || tile > 4096 ||
                n != tilesX(width, tile) * tilesY(height, tile)
            ) throw IOException("Ungültige FWL-Ebene")
            crc = IntArray(n); adler = IntArray(n); len = IntArray(n); off = IntArray(n)
            var p = HEADER_INTS * 4
            var data = p + n * 12
            for (i in 0 until n) {
                crc[i] = b.getInt(p); adler[i] = b.getInt(p + 4); len[i] = b.getInt(p + 8); p += 12
                off[i] = data; data += len[i]
                if (len[i] < 0 || data > blob.size) throw IOException("FWL-Ebene beschädigt")
            }
        }
        fun compatible(w: Int, h: Int, bpp: Int) = w == width && h == height && bpp == this.bpp && tile == TILE && filter == FILTER_SUB
    }

    private fun tilesX(w: Int, t: Int) = (w + t - 1) / t
    private fun tilesY(h: Int, t: Int) = (h + t - 1) / t

    private fun bppOf(b: Bitmap) = if (b.config == Bitmap.Config.ALPHA_8) 1 else 4

    /**
     * Kodiert [bitmap] parallel in Streifen zu [TILE] Zeilen (je Streifen eine kleine Kopie, nie die ganze Ebene
     * auf einmal). [old] = bisherige Fassung derselben Ebene: Kacheln mit gleichen Prüfsummen werden übernommen.
     * [stats] zählt neu kodierte / übernommene Kacheln.
     */
    fun encode(bitmap: Bitmap, old: Parsed?, stats: IntArray? = null): ByteArray {
        val w = bitmap.width; val h = bitmap.height; val bpp = bppOf(bitmap)
        val reuse = old?.takeIf { it.compatible(w, h, bpp) }
        val tx = tilesX(w, TILE); val ty = tilesY(h, TILE)
        val tiles = arrayOfNulls<TileOut>(tx * ty)
        val tasks = (0 until ty).map { row ->
            Callable {
                val wk = work.get()
                val y0 = row * TILE
                val th = minOf(TILE, h - y0)
                // Streifen kopieren (bitgenau, vormultipliziert) und als Rohbytes lesen
                val strip = if (th == h) bitmap else Bitmap.createBitmap(bitmap, 0, y0, w, th)
                val stride = strip.rowBytes
                val px = wk.band(stride * th)
                try {
                    strip.copyPixelsToBuffer(ByteBuffer.wrap(px, 0, stride * th))
                } finally {
                    if (strip !== bitmap) strip.recycle()
                }
                var fresh = 0; var reused = 0
                for (col in 0 until tx) {
                    val x0 = col * TILE
                    val tw = minOf(TILE, w - x0)
                    val rowLen = tw * bpp
                    val n = rowLen * th
                    val t = wk.tile
                    for (r in 0 until th) System.arraycopy(px, r * stride + x0 * bpp, t, r * rowLen, rowLen)
                    val idx = row * tx + col
                    if (isZero(t, n)) { tiles[idx] = TileOut(0, 0, null, 0, 0); continue }
                    wk.crc.reset(); wk.crc.update(t, 0, n)
                    wk.adler.reset(); wk.adler.update(t, 0, n)
                    val c = wk.crc.value.toInt(); val a = wk.adler.value.toInt()
                    if (reuse != null && reuse.len[idx] > 0 && reuse.crc[idx] == c && reuse.adler[idx] == a) {
                        tiles[idx] = TileOut(c, a, reuse.blob, reuse.off[idx], reuse.len[idx]); reused++
                        continue
                    }
                    subFilter(t, rowLen, th, bpp)
                    tiles[idx] = TileOut(c, a, deflate(wk, t, n), 0, -1); fresh++
                }
                stats?.let { synchronized(it) { it[0] += fresh; it[1] += reused } }
            }
        }
        runAll(tasks)

        var total = HEADER_INTS * 4 + tiles.size * 12
        for (t in tiles) total += t!!.let { if (it.len >= 0) it.len else it.data!!.size }
        val bos = ByteArrayOutputStream(total)
        val out = DataOutputStream(bos)
        out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(w); out.writeInt(h)
        out.writeInt(bpp); out.writeInt(TILE); out.writeInt(FILTER_SUB); out.writeInt(tiles.size)
        for (t in tiles) {
            t!!
            out.writeInt(t.crc); out.writeInt(t.adler); out.writeInt(if (t.len >= 0) t.len else t.data!!.size)
        }
        for (t in tiles) {
            t!!
            when {
                t.data == null -> {}
                t.len >= 0 -> out.write(t.data, t.off, t.len)
                else -> out.write(t.data)
            }
        }
        out.flush()
        return bos.toByteArray()
    }

    /**
     * Dekodiert eine Ebene in eine neue, veränderbare Bitmap ([Bitmap.Config.ARGB_8888] bzw. ALPHA_8 bei
     * 1 Byte/Pixel). Streifen werden parallel entpackt und bitgenau (SRC) in die Ebene übertragen.
     */
    fun decode(blob: ByteArray): Bitmap {
        val p = Parsed(blob)
        val config = if (p.bpp == 1) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888
        val bmp = Bitmap.createBitmap(p.width, p.height, config)
        val canvas = Canvas(bmp)
        val copy = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val tx = tilesX(p.width, p.tile); val ty = tilesY(p.height, p.tile)
        val tasks = (0 until ty).map { row ->
            Callable {
                val y0 = row * p.tile
                val th = minOf(p.tile, p.height - y0)
                val first = row * tx
                if ((0 until tx).all { p.len[first + it] == 0 }) return@Callable // leerer Streifen
                val wk = work.get()
                val strip = Bitmap.createBitmap(p.width, th, config)
                try {
                    val stride = strip.rowBytes
                    val px = wk.band(stride * th)
                    java.util.Arrays.fill(px, 0, stride * th, 0)
                    val tmp = if (p.tile == TILE) wk.tile else ByteArray(p.tile * p.tile * p.bpp)
                    for (col in 0 until tx) {
                        val idx = first + col
                        if (p.len[idx] == 0) continue
                        val x0 = col * p.tile
                        val tw = minOf(p.tile, p.width - x0)
                        val rowLen = tw * p.bpp
                        val n = rowLen * th
                        inflate(wk.inflater, blob, p.off[idx], p.len[idx], tmp, n)
                        if (p.filter == FILTER_SUB) subUnfilter(tmp, rowLen, th, p.bpp)
                        for (r in 0 until th) System.arraycopy(tmp, r * rowLen, px, r * stride + x0 * p.bpp, rowLen)
                    }
                    strip.copyPixelsFromBuffer(ByteBuffer.wrap(px, 0, stride * th))
                    synchronized(canvas) { canvas.drawBitmap(strip, 0f, y0.toFloat(), copy) }
                } finally {
                    strip.recycle()
                }
            }
        }
        runAll(tasks)
        return bmp
    }

    // ------------------------------------------------------------------ Hilfen

    private fun runAll(tasks: List<Callable<Unit>>) {
        // Der aufrufende Thread arbeitet mit (sonst wartet er nur); die übrigen Aufgaben übernimmt der Pool.
        val futures = tasks.drop(1).map { pool.submit(it) }
        var error: Throwable? = null
        try { tasks.firstOrNull()?.call() } catch (t: Throwable) { error = t }
        for (f in futures) {
            try { f.get() } catch (e: java.util.concurrent.ExecutionException) { if (error == null) error = e.cause ?: e }
        }
        error?.let { if (it is IOException) throw it else throw IOException("Ebene konnte nicht verarbeitet werden", it) }
    }

    private fun isZero(a: ByteArray, n: Int): Boolean {
        for (i in 0 until n) if (a[i].toInt() != 0) return false
        return true
    }

    /** PNG-Sub-Filter je Zeile (in place, von hinten nach vorne). */
    private fun subFilter(a: ByteArray, rowLen: Int, rows: Int, bpp: Int) {
        for (r in 0 until rows) {
            val base = r * rowLen
            var i = base + rowLen - 1
            val stop = base + bpp
            while (i >= stop) { a[i] = (a[i] - a[i - bpp]).toByte(); i-- }
        }
    }

    private fun subUnfilter(a: ByteArray, rowLen: Int, rows: Int, bpp: Int) {
        for (r in 0 until rows) {
            val base = r * rowLen
            var i = base + bpp
            val end = base + rowLen
            while (i < end) { a[i] = (a[i] + a[i - bpp]).toByte(); i++ }
        }
    }

    private fun deflate(wk: Work, src: ByteArray, n: Int): ByteArray {
        val d = wk.deflater
        d.reset(); d.setInput(src, 0, n); d.finish()
        var out = wk.out
        var len = 0
        while (!d.finished()) {
            if (len == out.size) out = out.copyOf(out.size * 2)
            len += d.deflate(out, len, out.size - len)
        }
        return out.copyOf(len)
    }

    private fun inflate(inf: Inflater, src: ByteArray, off: Int, len: Int, dst: ByteArray, n: Int) {
        inf.reset(); inf.setInput(src, off, len)
        var got = 0
        try {
            while (got < n) {
                val k = inf.inflate(dst, got, n - got)
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                got += k
            }
        } catch (e: DataFormatException) { throw IOException("FWL-Kachel beschädigt", e) }
        if (got != n) throw IOException("FWL-Kachel unvollständig")
    }

}
