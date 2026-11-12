package at.techgaisi.farbwerk.animation

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Schlanker GIF89a-Encoder für animierte GIFs: je Bild eigene Farbtabelle (Median-Cut über ein 15-Bit-Histogramm,
 * bis 255 Farben + 1 transparenter Index), LZW-Kompression, Endlosschleife optional.
 */
class GifEncoder(file: File, private val width: Int, private val height: Int, loop: Boolean) : Closeable {
    private val out: OutputStream = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val pixels = IntArray(width * height)
    private val indices = ByteArray(width * height)
    private val hist = IntArray(32768)
    private val lut = IntArray(32768)

    init {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width); short(height)
        out.write(0x70) // keine globale Farbtabelle, Farbauflösung 8 Bit
        out.write(0); out.write(0)
        if (loop) {
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf(3, 1, 0, 0, 0))
        }
    }

    private fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    /** Fügt ein Bild hinzu. [delayMs] Anzeigedauer; [transparent] = Alpha < 128 wird durchsichtig. */
    fun addFrame(bmp: Bitmap, delayMs: Int, transparent: Boolean) {
        require(bmp.width == width && bmp.height == height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        val palette = quantize(transparent)
        val transIndex = if (transparent) 255 else -1
        for (i in pixels.indices) {
            val c = pixels[i]
            indices[i] = if (transparent && (c ushr 24) < 128) 255.toByte()
            else lut[((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)].toByte()
        }
        // Grafiksteuerung
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4))
        val disposal = if (transparent) 2 else 1
        out.write((disposal shl 2) or (if (transIndex >= 0) 1 else 0))
        short((delayMs / 10).coerceIn(2, 65535))
        out.write(if (transIndex >= 0) transIndex else 0)
        out.write(0)
        // Bildbeschreibung mit lokaler Farbtabelle (256 Einträge)
        out.write(0x2C); short(0); short(0); short(width); short(height)
        out.write(0x87)
        for (i in 0 until 256) {
            val c = palette.getOrElse(i) { 0 }
            out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF)
        }
        lzw(indices, 8)
    }

    /** Median-Cut: liefert bis zu 255 (bzw. 256) Farben und füllt [lut] (15-Bit-Farbe → Index). */
    private fun quantize(transparent: Boolean): IntArray {
        hist.fill(0)
        for (c in pixels) {
            if (transparent && (c ushr 24) < 128) continue
            hist[((c shr 9) and 0x7C00) or ((c shr 6) and 0x3E0) or ((c shr 3) and 0x1F)]++
        }
        val colors = ArrayList<Int>()
        for (i in hist.indices) if (hist[i] > 0) colors += i
        val maxColors = if (transparent) 255 else 256
        val boxes = ArrayList<IntArray>()
        if (colors.isNotEmpty()) boxes += colors.toIntArray()
        while (boxes.size < maxColors) {
            // Box mit größter Spannweite teilen
            var bi = -1; var bestRange = 0; var bestCh = 0
            for ((k, b) in boxes.withIndex()) {
                if (b.size < 2) continue
                for (ch in 0..2) {
                    val sh = ch * 5
                    var mn = 31; var mx = 0
                    for (c in b) { val v = (c shr sh) and 31; if (v < mn) mn = v; if (v > mx) mx = v }
                    if (mx - mn > bestRange) { bestRange = mx - mn; bi = k; bestCh = ch }
                }
            }
            if (bi < 0) break
            val b = boxes[bi]
            val sh = bestCh * 5
            val sorted = b.sortedBy { (it shr sh) and 31 }
            // Am gewichteten Median teilen
            val total = sorted.sumOf { hist[it].toLong() }
            var acc = 0L; var cut = 1
            for ((i, c) in sorted.withIndex()) { acc += hist[c]; if (acc * 2 >= total) { cut = (i + 1).coerceIn(1, sorted.size - 1); break } }
            boxes[bi] = sorted.subList(0, cut).toIntArray()
            boxes += sorted.subList(cut, sorted.size).toIntArray()
        }
        val pal = IntArray(boxes.size)
        for ((k, b) in boxes.withIndex()) {
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            for (c in b) {
                val w = hist[c].toLong()
                r += ((c shr 10) and 31) * w; g += ((c shr 5) and 31) * w; bl += (c and 31) * w; n += w
                lut[c] = k
            }
            n = n.coerceAtLeast(1)
            val rr = (r * 255 / (31 * n)).toInt(); val gg = (g * 255 / (31 * n)).toInt(); val bb = (bl * 255 / (31 * n)).toInt()
            pal[k] = (rr shl 16) or (gg shl 8) or bb
        }
        return pal
    }

    // ------------------------------------------------------------------ LZW

    private val block = ByteArray(255)
    private var blockLen = 0
    private var bitBuf = 0
    private var bitCount = 0

    private fun lzw(data: ByteArray, minCodeSize: Int) {
        out.write(minCodeSize)
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        var next = eoi + 1
        val table = HashMap<Int, Int>(8192)
        blockLen = 0; bitBuf = 0; bitCount = 0
        emit(clear, codeSize)
        var prefix = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = table[key]
            if (found != null) { prefix = found; continue }
            emit(prefix, codeSize)
            if (next < 4096) {
                table[key] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                emit(clear, codeSize)
                table.clear(); codeSize = minCodeSize + 1; next = eoi + 1
            }
            prefix = k
        }
        emit(prefix, codeSize)
        if (next >= (1 shl codeSize) && codeSize < 12) codeSize++
        emit(eoi, codeSize)
        if (bitCount > 0) byteOut(bitBuf and 0xFF)
        flushBlock()
        out.write(0)
    }

    private fun emit(code: Int, size: Int) {
        bitBuf = bitBuf or (code shl bitCount)
        bitCount += size
        while (bitCount >= 8) { byteOut(bitBuf and 0xFF); bitBuf = bitBuf ushr 8; bitCount -= 8 }
    }

    private fun byteOut(b: Int) {
        block[blockLen++] = b.toByte()
        if (blockLen == 255) flushBlock()
    }

    private fun flushBlock() {
        if (blockLen == 0) return
        out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0
    }

    override fun close() {
        out.write(0x3B)
        out.close()
    }
}
