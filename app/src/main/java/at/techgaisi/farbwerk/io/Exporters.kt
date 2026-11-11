package at.techgaisi.farbwerk.io

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.render.Compositor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

/** Exportformate (Aktionen → Teilen). */
enum class ExportFormat(val label: String, val ext: String, val mime: String, val description: String) {
    PROJECT("Farbwerk", "farbwerk", "application/octet-stream", "Projekt mit allen Ebenen"),
    PSD("PSD", "psd", "image/vnd.adobe.photoshop", "Photoshop mit Ebenen"),
    PDF("PDF", "pdf", "application/pdf", "Dokument"),
    JPEG("JPEG", "jpg", "image/jpeg", "Bild ohne Transparenz"),
    PNG("PNG", "png", "image/png", "Bild mit Transparenz"),
    TIFF("TIFF", "tif", "image/tiff", "Unkomprimiert mit Alpha"),
    LAYERS_PNG("PNG-Dateien", "zip", "application/zip", "Jede Ebene als PNG"),
}

/**
 * Export in alle Formate. Die Schritte, die den Dokumentzustand lesen (Zusammenfügen über den
 * Compositor), laufen auf dem Main-Thread; Kodierung und Schreiben im Hintergrund.
 * Dateien landen in cacheDir/exports/ und werden über den FileProvider geteilt.
 */
object Exporters {

    fun exportDir(context: Context): File = File(context.cacheDir, "exports").apply {
        mkdirs()
        // alte Exporte (> 1 Tag) aufräumen
        val limit = System.currentTimeMillis() - 24L * 3600 * 1000
        listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
    }

    /** Dateiname ohne problematische Zeichen. */
    fun safeName(name: String): String =
        name.trim().replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80).ifBlank { "Farbwerk" }

    /** Exportiert [doc] im Format [format]. Muss aus einer Coroutine auf dem Main-Thread kommen. */
    suspend fun export(context: Context, doc: ArtDocument, format: ExportFormat): File {
        val dir = exportDir(context)
        val out = File(dir, safeName(doc.name) + "." + format.ext)
        when (format) {
            ExportFormat.PROJECT -> {
                // Kopien auf Main, Kodieren im Hintergrund
                val json = ProjectFormat.toJson(doc)
                val copies = doc.layers.map {
                    ProjectFormat.LayerData(it.bitmap.copy(Bitmap.Config.ARGB_8888, false),
                        it.mask?.let { m -> m.copy(m.config ?: Bitmap.Config.ARGB_8888, false) })
                }
                val thumb = thumbnail(doc)
                withContext(Dispatchers.IO) {
                    try { ProjectFormat.write(out, json, copies, thumb) }
                    finally { copies.forEach { it.bitmap.recycle(); it.mask?.recycle() } }
                }
            }
            ExportFormat.PNG -> {
                val flat = Compositor.flatten(doc, includeBackground = true)
                withContext(Dispatchers.IO) { writeBitmap(flat, out, Bitmap.CompressFormat.PNG, 100); flat.recycle() }
            }
            ExportFormat.JPEG -> {
                val flat = opaque(doc)
                withContext(Dispatchers.IO) { writeBitmap(flat, out, Bitmap.CompressFormat.JPEG, 95); flat.recycle() }
            }
            ExportFormat.TIFF -> {
                val flat = Compositor.flatten(doc, includeBackground = true)
                withContext(Dispatchers.IO) { writeTiff(flat, doc.dpi, out); flat.recycle() }
            }
            ExportFormat.PDF -> {
                val flat = opaque(doc)
                withContext(Dispatchers.IO) { writePdf(flat, doc.dpi, out); flat.recycle() }
            }
            ExportFormat.PSD -> {
                val composite = Compositor.flatten(doc, includeBackground = true)
                // Während des Exports blockiert ein Dialog die Bearbeitung → Ebenen direkt lesen
                withContext(Dispatchers.IO) {
                    try { PsdWriter.write(doc, composite, out, dir) } finally { composite.recycle() }
                }
            }
            ExportFormat.LAYERS_PNG -> withContext(Dispatchers.IO) { writeLayersZip(doc, out) }
        }
        return out
    }

    private fun thumbnail(doc: ArtDocument): Bitmap? = try {
        val s = min(1f, 512f / maxOf(doc.width, doc.height))
        val b = Bitmap.createBitmap(maxOf(1, (doc.width * s).toInt()), maxOf(1, (doc.height * s).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.scale(b.width / doc.width.toFloat(), b.height / doc.height.toFloat())
        Compositor.draw(c, doc); b
    } catch (_: Throwable) { null }

    /** Zusammengefügtes Bild ohne Transparenz (für JPEG/PDF). */
    private fun opaque(doc: ArtDocument): Bitmap {
        val flat = Compositor.flatten(doc, includeBackground = true)
        if (doc.backgroundVisible) return flat
        val out = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.WHITE); c.drawBitmap(flat, 0f, 0f, null)
        flat.recycle()
        return out
    }

    private fun writeBitmap(b: Bitmap, out: File, fmt: Bitmap.CompressFormat, q: Int) {
        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { b.compress(fmt, q, it) }
    }

    /** Jede Ebene als einzelne PNG-Datei in einem ZIP (von unten nach oben nummeriert). */
    private fun writeLayersZip(doc: ArtDocument, out: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 16)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            val used = HashSet<String>()
            doc.layers.forEachIndexed { i, l ->
                var n = "%02d %s".format(i + 1, safeName(l.name))
                while (!used.add(n)) n += "_"
                zip.putNextEntry(ZipEntry("$n.png"))
                val buf = java.io.ByteArrayOutputStream()
                l.bitmap.compress(Bitmap.CompressFormat.PNG, 100, buf)
                buf.writeTo(zip)
                zip.closeEntry()
            }
        }
    }

    /** PDF-Seite in Druckgröße (Pixel / DPI), Bild in voller Auflösung eingebettet. */
    private fun writePdf(b: Bitmap, dpi: Int, out: File) {
        val pdf = PdfDocument()
        try {
            var pw = b.width * 72f / dpi.coerceAtLeast(1)
            var ph = b.height * 72f / dpi.coerceAtLeast(1)
            // PDF-Grenze: 14 400 pt (200 Zoll)
            val s = min(1f, 14400f / maxOf(pw, ph))
            pw *= s; ph *= s
            val info = PdfDocument.PageInfo.Builder(maxOf(1, pw.toInt()), maxOf(1, ph.toInt()), 1).create()
            val page = pdf.startPage(info)
            val m = Matrix().apply { setScale(info.pageWidth / b.width.toFloat(), info.pageHeight / b.height.toFloat()) }
            page.canvas.drawBitmap(b, m, Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page)
            FileOutputStream(out).use { pdf.writeTo(it) }
        } finally { pdf.close() }
    }

    /** Baseline-TIFF, unkomprimiert, RGBA 8 Bit, nicht vormultipliziertes Alpha, Little Endian. */
    private fun writeTiff(b: Bitmap, dpi: Int, out: File) {
        val w = b.width; val h = b.height
        val dataLen = w.toLong() * h * 4
        val tags = 14
        val ifdOffset = 8L
        val ifdSize = 2 + tags * 12 + 4
        val bpsOffset = ifdOffset + ifdSize
        val xresOffset = bpsOffset + 8
        val yresOffset = xresOffset + 8
        val dataOffset = yresOffset + 8
        DataOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 16)).use { o ->
            fun s16(v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
            fun s32(v: Long) { s16((v and 0xFFFF).toInt()); s16(((v shr 16) and 0xFFFF).toInt()) }
            fun tag(id: Int, type: Int, count: Long, value: Long) {
                s16(id); s16(type); s32(count)
                if (type == 3 && count == 1L) { s16(value.toInt()); s16(0) } else s32(value)
            }
            o.write('I'.code); o.write('I'.code); s16(42); s32(ifdOffset)
            s16(tags)
            tag(256, 4, 1, w.toLong())          // Breite
            tag(257, 4, 1, h.toLong())          // Höhe
            tag(258, 3, 4, bpsOffset)           // Bits pro Sample
            tag(259, 3, 1, 1)                   // keine Kompression
            tag(262, 3, 1, 2)                   // RGB
            tag(273, 4, 1, dataOffset)          // Streifen-Offset
            tag(277, 3, 1, 4)                   // Samples pro Pixel
            tag(278, 4, 1, h.toLong())          // Zeilen pro Streifen
            tag(279, 4, 1, dataLen)             // Streifenlänge
            tag(282, 5, 1, xresOffset)          // X-Auflösung
            tag(283, 5, 1, yresOffset)          // Y-Auflösung
            tag(284, 3, 1, 1)                   // chunky
            tag(296, 3, 1, 2)                   // Zoll
            tag(338, 3, 1, 2)                   // Extra-Sample: nicht vormultipliziertes Alpha
            s32(0)
            repeat(4) { s16(8) }
            s32(dpi.toLong()); s32(1)
            s32(dpi.toLong()); s32(1)
            val row = IntArray(w)
            val bytes = ByteArray(w * 4)
            for (y in 0 until h) {
                b.getPixels(row, 0, w, 0, y, w, 1)
                var k = 0
                for (p in row) {
                    bytes[k++] = (p shr 16).toByte(); bytes[k++] = (p shr 8).toByte()
                    bytes[k++] = p.toByte(); bytes[k++] = (p ushr 24).toByte()
                }
                o.write(bytes)
            }
        }
    }

    // ------------------------------------------------------------------ Teilen

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Öffnet das System-Teilen-Menü für [file]. */
    fun share(context: Context, file: File, mime: String) {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Teilen: ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }

    /** Teilt mehrere Dateien (z. B. mehrere Projekte aus der Galerie). */
    fun shareMultiple(context: Context, files: List<File>, mime: String) {
        if (files.isEmpty()) return
        if (files.size == 1) return share(context, files[0], mime)
        val uris = ArrayList(files.map { uriFor(context, it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(files[0].name, uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Export + Teilen in einem Schritt. */
    suspend fun exportAndShare(context: Context, doc: ArtDocument, format: ExportFormat) {
        val f = export(context, doc, format)
        share(context, f, format.mime)
    }
}
