package at.techgaisi.farbwerk.color

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Erzeugt eine Palette aus einem Bild per Median-Cut-Quantisierung.
 * Das Bild wird zuvor auf ca. [MAX_SIDE] px verkleinert – das genügt für eine repräsentative Farbauswahl.
 * Nicht auf dem Hauptthread aufrufen.
 */
object PaletteExtractor {
    private const val MAX_SIDE = 160

    /** Lädt das Bild hinter [uri] und liefert bis zu [count] dominante Farben (hell → dunkel sortiert). */
    fun fromUri(context: Context, uri: Uri, count: Int = Palette.SLOTS): List<Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return emptyList()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return emptyList()
        return try { fromBitmap(bmp, count) } finally { bmp.recycle() }
    }

    fun fromBitmap(src: Bitmap, count: Int = Palette.SLOTS): List<Int> {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
        else src
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        if (bmp !== src) bmp.recycle()
        // nur (weitgehend) deckende Pixel berücksichtigen
        val colors = px.filter { (it ushr 24) >= 128 }.toIntArray()
        return medianCut(colors, count)
    }

    /** Klassischer Median-Cut: teilt immer die Box mit der größten Kanalspanne × Pixelanzahl. */
    fun medianCut(pixels: IntArray, count: Int): List<Int> {
        if (pixels.isEmpty()) return emptyList()
        val boxes = mutableListOf(Box(pixels, 0, pixels.size))
        while (boxes.size < count) {
            val box = boxes.filter { it.size > 1 && it.range > 0 }.maxByOrNull { it.range.toLong() * it.size } ?: break
            boxes.remove(box)
            boxes.addAll(box.split())
        }
        return boxes.map { it.average() }
            .distinct()
            .sortedByDescending { ColorMath.luminance(it) }
    }

    private class Box(val px: IntArray, val from: Int, val to: Int) {
        val size get() = to - from
        var channel = 0
        var range = 0

        init {
            var rMin = 255; var rMax = 0; var gMin = 255; var gMax = 0; var bMin = 255; var bMax = 0
            for (i in from until to) {
                val c = px[i]
                val r = (c ushr 16) and 0xFF; val g = (c ushr 8) and 0xFF; val b = c and 0xFF
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
            }
            val rr = rMax - rMin; val gr = gMax - gMin; val br = bMax - bMin
            if (rr >= gr && rr >= br) { channel = 16; range = rr }
            else if (gr >= br) { channel = 8; range = gr }
            else { channel = 0; range = br }
        }

        fun split(): List<Box> {
            val sh = channel
            // Teilbereich nach dem Kanal sortieren (Boxed-Sort über kleine Mengen ist hier unkritisch)
            val sorted = px.copyOfRange(from, to).sortedBy { (it ushr sh) and 0xFF }
            for (i in sorted.indices) px[from + i] = sorted[i]
            val mid = from + size / 2
            return listOf(Box(px, from, mid), Box(px, mid, to))
        }

        fun average(): Int {
            var r = 0L; var g = 0L; var b = 0L
            for (i in from until to) {
                val c = px[i]
                r += (c ushr 16) and 0xFF; g += (c ushr 8) and 0xFF; b += c and 0xFF
            }
            val n = size.coerceAtLeast(1)
            return ColorMath.argb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
    }
}
