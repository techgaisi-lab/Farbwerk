package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil

/**
 * Zeichnet einzelne Pinsel-Tupfer (Stempel) auf ein Canvas: Form (rotiert, gestaucht, gespiegelt),
 * Farbe/Deckkraft und optional Körnung. Alle Hilfsobjekte werden wiederverwendet – keine Allokationen pro Tupfer.
 */
class DabPainter {
    private val m = Matrix()
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ovalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val grainMatrix = Matrix()
    private val oval = RectF()
    private val src = Rect()
    private val dst = RectF()

    private var scratch: Bitmap? = null
    private var scratchCanvas: Canvas? = null

    private var shapeIndex = 0
    private var shape: Bitmap = BrushTextures.shape(0)
    private var grain: Bitmap? = null
    private var grainShader: BitmapShader? = null
    private var grainScale = 1f
    private var grainMoving = false

    /** Übernimmt Form und Körnung des Pinsels. Vor jedem Strich aufrufen. */
    fun setup(brush: Brush) {
        shapeIndex = brush.int(BP.SHAPE_KIND)
        shape = BrushTextures.shape(shapeIndex)
        val g = BrushTextures.grain(brush.int(BP.GRAIN_KIND), brush[BP.GRAIN_DEPTH], brush.on(BP.GRAIN_INVERT))
        if (g !== grain) {
            grain = g
            grainShader = g?.let { BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
            grainPaint.shader = grainShader
        }
        grainScale = brush[BP.GRAIN_SCALE]
        grainMoving = brush.int(BP.GRAIN_MODE) == 1
    }

    /**
     * Zeichnet einen Tupfer mit Mittelpunkt ([x],[y]).
     * @param angle Drehung in Radiant
     * @param flags Bit 0 = horizontal gespiegelt, Bit 1 = vertikal gespiegelt
     */
    fun draw(
        c: Canvas, x: Float, y: Float, diam: Float, aspect: Float, angle: Float,
        alpha: Float, color: Int, flags: Int,
    ) {
        if (alpha <= 0.002f || diam <= 0.05f) return
        val g = grainShader
        if (g == null) {
            drawShape(c, x, y, diam, aspect, angle, alpha, color, flags)
            return
        }
        val s = ceil(diam * 1.42f).toInt() + 4
        if (s > MAX_SCRATCH) {
            // Sehr große Tupfer: ohne Körnung (Speicher/Leistung)
            drawShape(c, x, y, diam, aspect, angle, alpha, color, flags)
            return
        }
        val sc = ensureScratch(s)
        val half = s / 2f
        sc.drawRect(0f, 0f, s.toFloat(), s.toFloat(), clearPaint)
        drawShape(sc, half, half, diam, aspect, angle, alpha, color, flags)
        // Körnung: Texturiert = fest im Dokument, Bewegt = fest am Tupfer
        grainMatrix.setScale(grainScale, grainScale)
        if (grainMoving) grainMatrix.postTranslate(half, half)
        else grainMatrix.postTranslate(-(x - half), -(y - half))
        g.setLocalMatrix(grainMatrix)
        sc.drawRect(0f, 0f, s.toFloat(), s.toFloat(), grainPaint)
        src.set(0, 0, s, s)
        dst.set(x - half, y - half, x + half, y + half)
        c.drawBitmap(scratch!!, src, dst, blitPaint)
    }

    private fun drawShape(
        c: Canvas, x: Float, y: Float, diam: Float, aspect: Float, angle: Float,
        alpha: Float, color: Int, flags: Int,
    ) {
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        if (a == 0) return
        val col = (color and 0x00FFFFFF) or (a shl 24)
        val deg = Math.toDegrees(angle.toDouble()).toFloat()
        if (shapeIndex == ShapeKind.HARD.ordinal) {
            // Harte runde Spitze analytisch → bei jeder Größe scharfe Kanten
            ovalPaint.color = col
            val r = diam / 2f
            c.save()
            c.translate(x, y)
            if (deg != 0f) c.rotate(deg)
            oval.set(-r, -r * aspect, r, r * aspect)
            c.drawOval(oval, ovalPaint)
            c.restore()
            return
        }
        val n = BrushTextures.SHAPE_SIZE.toFloat()
        val fx = if (flags and 1 != 0) -1f else 1f
        val fy = if (flags and 2 != 0) -1f else 1f
        m.setTranslate(-n / 2f, -n / 2f)
        m.postScale(diam / n * fx, diam * aspect / n * fy)
        if (deg != 0f) m.postRotate(deg)
        m.postTranslate(x, y)
        shapePaint.color = col
        c.drawBitmap(shape, m, shapePaint)
    }

    private fun ensureScratch(s: Int): Canvas {
        val cur = scratch
        if (cur == null || cur.width < s) {
            var size = 64
            while (size < s) size *= 2
            cur?.recycle()
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            scratch = b
            scratchCanvas = Canvas(b)
        }
        return scratchCanvas!!
    }

    /** Gibt den Zwischenspeicher frei. */
    fun release() {
        scratch?.recycle(); scratch = null; scratchCanvas = null
    }

    companion object {
        const val MAX_SCRATCH = 1024
    }
}
