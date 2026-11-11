package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Wisch-Werkzeug: Jeder Tupfer trägt die "geladene" Farbe (ein kleines Bild) an die neue Stelle und nimmt
 * dort wieder etwas von der Ebene auf. Alles rechnet mit vormultiplizierten Werten exakt per DST_OUT + ADD:
 *   ebene = ebene·(1 − a·maske) + geladen·maske·a
 *   geladen = geladen·(1 − k) + ebene·k
 * Für Symmetrie gibt es je Kopie einen eigenen Ladespeicher.
 */
class Smudger(private val painter: DabPainter) {
    private var size = 0
    private var carried = arrayOfNulls<Bitmap>(0)
    private var carriedCanvas = arrayOfNulls<Canvas>(0)
    private var loaded = BooleanArray(0)
    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private var tmp: Bitmap? = null
    private var tmpCanvas: Canvas? = null

    private val srcPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val dstIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val dstOut = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val add = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val atop = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
    private val srcRect = Rect()
    private val full = Rect()
    private val dst = RectF()

    /** Grundaufnahme 0..1 (Pinsel-Eigenschaft "Wischen: Farbaufnahme"). */
    var pickup = 0.5f
    var alphaLock = false

    /** Vor jedem Strich: Größe des Ladespeichers und Anzahl der Symmetrie-Kopien festlegen. */
    fun begin(diameter: Float, copies: Int) {
        val s = ceil(diameter * 1.05f).toInt().coerceIn(4, MAX)
        if (s != size || carried.size != copies) {
            release()
            size = s
            carried = arrayOfNulls(copies)
            carriedCanvas = arrayOfNulls(copies)
            for (i in 0 until copies) {
                val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                carried[i] = b; carriedCanvas[i] = Canvas(b)
            }
            mask = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { maskCanvas = Canvas(it) }
            tmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { tmpCanvas = Canvas(it) }
        }
        loaded = BooleanArray(copies)
        full.set(0, 0, size, size)
    }

    /**
     * Ein Wisch-Tupfer für Kopie [k] auf [layerCanvas] (Canvas der Ebenen-Bitmap [layerBitmap]).
     * @param strength 0..1 (aus Deckkraft, Druck usw.)
     * @param out erhält das veränderte Rechteck
     */
    fun dab(
        k: Int, layerCanvas: Canvas, layerBitmap: Bitmap,
        x: Float, y: Float, diam: Float, aspect: Float, angle: Float, strength: Float, flags: Int,
        out: RectF,
    ) {
        if (k >= carried.size) return
        val cb = carried[k] ?: return
        val cc = carriedCanvas[k] ?: return
        val half = diam / 2f
        srcRect.set(floor(x - half).toInt(), floor(y - half).toInt(), ceil(x + half).toInt(), ceil(y + half).toInt())
        if (srcRect.width() <= 0 || srcRect.height() <= 0) return
        dst.set(srcRect)
        if (!loaded[k]) {
            cc.drawColor(0, PorterDuff.Mode.CLEAR)
            cc.drawBitmap(layerBitmap, srcRect, full, srcPaint)
            loaded[k] = true
            return
        }
        val a = (strength.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        if (a <= 0) return
        val mc = maskCanvas ?: return
        val tc = tmpCanvas ?: return
        val mb = mask!!; val tb = tmp!!

        // Maske = Pinselform in Ladespeicher-Größe
        mc.drawColor(0, PorterDuff.Mode.CLEAR)
        painter.draw(mc, size / 2f, size / 2f, size.toFloat(), aspect, angle, 1f, Color.WHITE, flags)
        // geladen · Maske
        tc.drawBitmap(cb, 0f, 0f, srcPaint)
        tc.drawBitmap(mb, 0f, 0f, dstIn)

        if (alphaLock) {
            atop.alpha = a
            layerCanvas.drawBitmap(tb, full, dst, atop)
        } else {
            dstOut.alpha = a
            layerCanvas.drawBitmap(mb, full, dst, dstOut)
            add.alpha = a
            layerCanvas.drawBitmap(tb, full, dst, add)
        }

        // neue Farbe aufnehmen – je stärker der Wisch, desto länger hält die geladene Farbe
        val kf = (pickup * (0.75f - 0.68f * strength) + 0.03f).coerceIn(0.02f, 1f)
        cc.drawColor(Color.argb((kf * 255).toInt(), 0, 0, 0), PorterDuff.Mode.DST_OUT)
        add.alpha = (kf * 255).toInt()
        cc.drawBitmap(layerBitmap, srcRect, full, add)

        out.union(dst.left - 1, dst.top - 1, dst.right + 1, dst.bottom + 1)
    }

    fun release() {
        carried.forEach { it?.recycle() }
        carried = arrayOfNulls(0); carriedCanvas = arrayOfNulls(0)
        mask?.recycle(); mask = null; maskCanvas = null
        tmp?.recycle(); tmp = null; tmpCanvas = null
        size = 0
    }

    companion object {
        /** Größte Ladespeicher-Kante; größere Tupfer werden hochskaliert. */
        const val MAX = 320
    }
}
