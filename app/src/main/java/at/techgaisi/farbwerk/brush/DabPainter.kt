package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
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
 * Zeichnet einzelne Pinsel-Tupfer (Stempel) auf ein Canvas: Form (rotiert, gestaucht, gespiegelt, ggf. unscharf),
 * Farbe/Deckkraft und optional Körnung (texturiert oder bewegt, mit Maßstab/Zoom/Drehung/Tiefe).
 * Alle Hilfsobjekte werden wiederverwendet – keine Allokationen pro Tupfer.
 *
 * Zusatzinformationen pro Tupfer stecken in den `flags` (siehe [encodeFlags]), damit die Tupfer-Schnittstelle
 * [DabConsumer] unverändert bleibt.
 */
class DabPainter {
    private val m = Matrix()
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ovalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_OUT }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val grainMatrix = Matrix()
    private val oval = RectF()
    private val src = Rect()
    private val dst = RectF()

    private var scratch: Bitmap? = null
    private var scratchCanvas: Canvas? = null

    private var baseShape: Bitmap = BrushTextures.shape(0)
    private var analyticHard = false
    private var grainShader: BitmapShader? = null
    private var grainMask: Bitmap? = null
    private var grainScale = 1f
    private var grainMoving = false
    private var grainMovement = 1f
    private var grainZoom = 0f
    private var grainRotation = 0f
    private var baseDepth = 0f
    private var offX = 0f
    private var offY = 0f

    /** Übernimmt Form und Körnung des Pinsels. Vor jedem Strich aufrufen. */
    fun setup(brush: Brush) {
        val idx = brush.int(BP.SHAPE_KIND)
        val img = brush.shapeImage
        baseShape = img ?: BrushTextures.shape(idx)
        analyticHard = img == null && idx == ShapeKind.HARD.ordinal
        when (brush.int(BP.SHAPE_FILTER)) {
            0 -> { shapePaint.isAntiAlias = false; shapePaint.isFilterBitmap = false; ovalPaint.isAntiAlias = false }
            1 -> { shapePaint.isAntiAlias = false; shapePaint.isFilterBitmap = true; ovalPaint.isAntiAlias = true }
            else -> { shapePaint.isAntiAlias = true; shapePaint.isFilterBitmap = true; ovalPaint.isAntiAlias = true }
        }
        val g = BrushImages.grainMask(
            brush.int(BP.GRAIN_KIND), brush.grainImage, brush.on(BP.GRAIN_INVERT),
            brush[BP.GRAIN_BRIGHTNESS], brush[BP.GRAIN_CONTRAST],
        )
        if (g !== grainMask) {
            grainMask = g
            grainShader = g?.let { BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
            grainPaint.shader = grainShader
        }
        grainPaint.isFilterBitmap = brush.int(BP.GRAIN_FILTER) != 0
        grainScale = brush[BP.GRAIN_SCALE]
        grainMoving = brush.int(BP.GRAIN_MODE) == 1
        grainMovement = brush[BP.GRAIN_MOVEMENT]
        grainZoom = brush[BP.GRAIN_ZOOM]
        grainRotation = brush[BP.GRAIN_ROTATION]
        baseDepth = brush[BP.GRAIN_DEPTH]
        if (brush.on(BP.GRAIN_OFFSET_JITTER)) {
            offX = (Math.random() * BrushImages.SIZE).toFloat(); offY = (Math.random() * BrushImages.SIZE).toFloat()
        } else { offX = 0f; offY = 0f }
    }

    /**
     * Zeichnet einen Tupfer mit Mittelpunkt ([x],[y]).
     * @param angle Drehung in Radiant
     * @param flags siehe [encodeFlags]
     */
    fun draw(
        c: Canvas, x: Float, y: Float, diam: Float, aspect: Float, angle: Float,
        alpha: Float, color: Int, flags: Int,
    ) {
        if (alpha <= 0.002f || diam <= 0.05f) return
        val g = grainShader
        val depth = if (flags and FLAG_DEPTH != 0) ((flags shr 8) and 0xFF) / 255f else baseDepth
        if (g == null || depth <= 0.004f) {
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
        // Körnung: Texturiert = fest im Dokument, Bewegt = (je nach "Bewegung") am Tupfer
        if (grainMoving) {
            val sEff = (grainScale * (1f + grainZoom * (diam / 128f - 1f))).coerceAtLeast(0.03f)
            val mv = grainMovement
            grainMatrix.setScale(sEff, sEff)
            grainMatrix.postTranslate(half * mv + (half - x) * (1f - mv) + offX, half * mv + (half - y) * (1f - mv) + offY)
            if (grainRotation != 0f) {
                grainMatrix.postRotate(Math.toDegrees(angle.toDouble()).toFloat() * grainRotation, half, half)
            }
        } else {
            grainMatrix.setScale(grainScale, grainScale)
            grainMatrix.postTranslate(-(x - half) + offX, -(y - half) + offY)
        }
        g.setLocalMatrix(grainMatrix)
        grainPaint.alpha = (depth.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
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
        val blur = (flags shr 16) and 0x7
        if (analyticHard && blur == 0) {
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
        val shape = if (blur == 0) baseShape else BrushImages.blurred(baseShape, blur.coerceAtMost(4))
        val n = shape.width.toFloat()
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
        private const val FLAG_DEPTH = 0x80

        /**
         * Tupfer-Flags: Bit 0/1 = horizontal/vertikal gespiegelt, Bit 7 + Bits 8–15 = eigene Körnungstiefe
         * (0..255), Bits 16–18 = Unschärfestufe 0..4 der Form.
         */
        fun encodeFlags(flipX: Boolean, flipY: Boolean, depth: Float, blurLevel: Int): Int {
            var f = 0
            if (flipX) f = f or 1
            if (flipY) f = f or 2
            if (depth >= 0f) f = f or FLAG_DEPTH or (((depth.coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF) shl 8)
            f = f or ((blurLevel.coerceIn(0, 4)) shl 16)
            return f
        }
    }
}
