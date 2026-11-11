package at.techgaisi.farbwerk.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import at.techgaisi.farbwerk.model.ArtDocument

/** STUB – wird vom Ebenen-Modul ersetzt. */
object Compositor {
    /** Zeichnet das ganze Dokument (inkl. Hintergrund, falls sichtbar) in Dokument-Koordinaten. */
    fun draw(canvas: Canvas, doc: ArtDocument) {
        if (doc.backgroundVisible) canvas.drawColor(doc.backgroundColor)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (l in doc.layers) if (l.visible) {
            p.alpha = (l.opacity * 255).toInt()
            l.drawOverride?.invoke(canvas) ?: canvas.drawBitmap(l.displayOverride ?: l.bitmap, 0f, 0f, p)
        }
    }

    /** Liefert das zusammengefügte Bild in Dokumentgröße. */
    fun flatten(doc: ArtDocument, includeBackground: Boolean = true): Bitmap {
        val b = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val vis = doc.backgroundVisible
        if (!includeBackground) doc.backgroundVisible = false
        draw(Canvas(b), doc)
        doc.backgroundVisible = vis
        return b
    }

    /** Farbe des zusammengesetzten Bildes an einer Dokumentposition. */
    fun samplePixel(doc: ArtDocument, x: Float, y: Float): Int {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.translate(-x, -y); draw(c, doc)
        return b.getPixel(0, 0)
    }
}
