package android.graphics.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.SurfaceProps
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * android.graphics.pdf.PdfDocument. Skiko enthält kein PDF-Backend; die Seiten werden daher aufgezeichnet und
 * beim Abschließen in der Auflösung der feinsten darauf gezeichneten Bitmap gerastert (verlustfrei,
 * Flate-komprimiert, Transparenz als SMask) und als Bild-Seite in eine PDF-1.4-Datei geschrieben.
 */
class PdfDocument {

    class PageInfo private constructor(
        val pageWidth: Int,
        val pageHeight: Int,
        val pageNumber: Int,
        val contentRect: Rect,
    ) {
        class Builder(private val pageWidth: Int, private val pageHeight: Int, private val pageNumber: Int) {
            private var content: Rect? = null

            init {
                require(pageWidth > 0) { "page width must be positive" }
                require(pageHeight > 0) { "page width must be positive" }
                require(pageNumber >= 0) { "pageNumber must be non negative" }
            }

            fun setContentRect(contentRect: Rect?): Builder { content = contentRect; return this }
            fun create(): PageInfo = PageInfo(pageWidth, pageHeight, pageNumber, content ?: Rect(0, 0, pageWidth, pageHeight))
        }
    }

    class Page internal constructor(val info: PageInfo, internal val recorder: PictureRecorder, internal val pageCanvas: PdfPageCanvas) {
        val canvas: Canvas get() = pageCanvas
    }

    /** Seiten-Leinwand: merkt sich die feinste Bitmap-Auflösung (Bitmap-Pixel je PDF-Punkt). */
    internal class PdfPageCanvas(skia: org.jetbrains.skia.Canvas, w: Int, h: Int) : Canvas(skia, w, h) {
        var bitmapDensity = 1f

        private fun note(bmpW: Float, dstW: Float, bmpH: Float, dstH: Float) {
            val m = Matrix()
            @Suppress("DEPRECATION") getMatrix(m)
            val scale = sqrt(kotlin.math.abs(m.mapRadius(1f) * m.mapRadius(1f)))
            if (dstW > 0f && dstH > 0f && scale > 0f) {
                val d = maxOf(bmpW / (dstW * scale), bmpH / (dstH * scale))
                if (d.isFinite()) bitmapDensity = maxOf(bitmapDensity, d)
            }
        }

        override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
            note(1f, 1f, 1f, 1f); super.drawBitmap(bitmap, left, top, paint)
        }

        override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
            val sw = (src?.width() ?: bitmap.width).toFloat(); val sh = (src?.height() ?: bitmap.height).toFloat()
            note(sw, dst.width(), sh, dst.height()); super.drawBitmap(bitmap, src, dst, paint)
        }

        override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
            val r = matrix.mapRadius(1f)
            if (r > 0f) note(1f, r, 1f, r)
            super.drawBitmap(bitmap, matrix, paint)
        }
    }

    private class FinishedPage(val info: PageInfo, val pxW: Int, val pxH: Int, val rgb: ByteArray, val alpha: ByteArray?)

    private val finished = ArrayList<FinishedPage>()
    private var current: Page? = null
    private var closed = false

    val pages: List<PageInfo> get() = finished.map { it.info }

    fun startPage(pageInfo: PageInfo): Page {
        check(!closed) { "document is closed!" }
        check(current == null) { "current page not finished!" }
        val rec = PictureRecorder()
        val c = rec.beginRecording(org.jetbrains.skia.Rect.makeWH(pageInfo.pageWidth.toFloat(), pageInfo.pageHeight.toFloat()))
        val cr = pageInfo.contentRect
        val pc = PdfPageCanvas(c, pageInfo.pageWidth, pageInfo.pageHeight)
        if (cr.left != 0 || cr.top != 0 || cr.right != pageInfo.pageWidth || cr.bottom != pageInfo.pageHeight) {
            pc.translate(cr.left.toFloat(), cr.top.toFloat())
            pc.clipRect(0f, 0f, cr.width().toFloat(), cr.height().toFloat())
        }
        return Page(pageInfo, rec, pc).also { current = it }
    }

    fun finishPage(page: Page) {
        check(!closed) { "document is closed!" }
        require(page === current) { "invalid page" }
        current = null
        val pic = page.recorder.finishRecordingAsPicture()
        val info = page.info
        // Auflösung: feinste Bitmap, höchstens 16× bzw. ~120 MP je Seite
        var s = page.pageCanvas.bitmapDensity.coerceIn(1f, 16f)
        val maxPx = 120_000_000.0
        val px = info.pageWidth.toDouble() * info.pageHeight * s * s
        if (px > maxPx) s *= sqrt(maxPx / px).toFloat()
        val w = maxOf(1, ceil(info.pageWidth * s - 0.01f).toInt())
        val h = maxOf(1, ceil(info.pageHeight * s - 0.01f).toInt())
        val bmp = org.jetbrains.skia.Bitmap()
        try {
            bmp.allocPixels(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
            bmp.erase(0)
            val c = org.jetbrains.skia.Canvas(bmp, SurfaceProps())
            c.scale(w / info.pageWidth.toFloat(), h / info.pageHeight.toFloat())
            c.drawPicture(pic, null, null)
            c.close()
            val px32 = bmp.readPixels(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), w * 4, 0, 0)
                ?: ByteArray(w * h * 4)
            val rgb = ByteArray(w * h * 3)
            val a = ByteArray(w * h)
            var opaque = true
            for (i in 0 until w * h) {
                rgb[3 * i] = px32[4 * i]; rgb[3 * i + 1] = px32[4 * i + 1]; rgb[3 * i + 2] = px32[4 * i + 2]
                val av = px32[4 * i + 3]
                a[i] = av
                if (av.toInt() != -1) opaque = false
            }
            finished += FinishedPage(info, w, h, deflate(rgb), if (opaque) null else deflate(a))
        } finally {
            bmp.close(); pic.close()
        }
    }

    private fun deflate(b: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(maxOf(1024, b.size / 4))
        DeflaterOutputStream(bos, Deflater(6), 1 shl 16).use { it.write(b) }
        return bos.toByteArray()
    }

    fun writeTo(out: OutputStream) {
        check(!closed) { "document is closed!" }
        check(current == null) { "current page not finished!" }
        val w = PdfWriter(out)
        val n = finished.size
        // Objektnummern: 1 Katalog, 2 Seitenbaum, dann je Seite: Seite, Inhalt, Bild, (SMask)
        val ids = ArrayList<IntArray>()
        var next = 3
        for (p in finished) {
            val arr = IntArray(4)
            arr[0] = next++; arr[1] = next++; arr[2] = next++
            arr[3] = if (p.alpha != null) next++ else 0
            ids += arr
        }
        w.header()
        w.obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        w.obj(2, "<< /Type /Pages /Count $n /Kids [${ids.joinToString(" ") { "${it[0]} 0 R" }}] >>")
        for ((i, p) in finished.withIndex()) {
            val id = ids[i]
            val pw = p.info.pageWidth; val ph = p.info.pageHeight
            w.obj(id[0], "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] " +
                "/Resources << /XObject << /Im0 ${id[2]} 0 R >> >> /Contents ${id[1]} 0 R >>")
            val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q\n".toByteArray(Charsets.US_ASCII)
            w.stream(id[1], "<< /Length ${content.size} >>", content)
            val smask = if (p.alpha != null) " /SMask ${id[3]} 0 R" else ""
            w.stream(id[2], "<< /Type /XObject /Subtype /Image /Width ${p.pxW} /Height ${p.pxH} /ColorSpace /DeviceRGB " +
                "/BitsPerComponent 8 /Filter /FlateDecode /Interpolate true$smask /Length ${p.rgb.size} >>", p.rgb)
            if (p.alpha != null) {
                w.stream(id[3], "<< /Type /XObject /Subtype /Image /Width ${p.pxW} /Height ${p.pxH} /ColorSpace /DeviceGray " +
                    "/BitsPerComponent 8 /Filter /FlateDecode /Length ${p.alpha.size} >>", p.alpha)
            }
        }
        w.finish(next - 1, 1)
        out.flush()
    }

    fun close() {
        closed = true
        finished.clear()
        current = null
    }

    /** Minimaler PDF-Schreiber mit Querverweistabelle. */
    private class PdfWriter(private val out: OutputStream) {
        private var pos = 0L
        private val offsets = HashMap<Int, Long>()

        private fun write(b: ByteArray) { out.write(b); pos += b.size }
        private fun write(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

        fun header() {
            write("%PDF-1.4\n")
            write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        }

        fun obj(id: Int, body: String) {
            offsets[id] = pos
            write("$id 0 obj\n$body\nendobj\n")
        }

        fun stream(id: Int, dict: String, data: ByteArray) {
            offsets[id] = pos
            write("$id 0 obj\n$dict\nstream\n")
            write(data)
            write("\nendstream\nendobj\n")
        }

        fun finish(maxId: Int, root: Int) {
            val xref = pos
            val sb = StringBuilder()
            sb.append("xref\n0 ${maxId + 1}\n0000000000 65535 f \n")
            for (i in 1..maxId) sb.append(String.format("%010d 00000 n \n", offsets[i] ?: 0L))
            sb.append("trailer\n<< /Size ${maxId + 1} /Root $root 0 R >>\nstartxref\n$xref\n%%EOF\n")
            write(sb.toString())
        }
    }
}
