package android.graphics

import org.jetbrains.skia.ClipMode
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.RRect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.VertexMode

/**
 * android.graphics.Canvas auf Basis von org.jetbrains.skia.Canvas.
 *
 * - `Canvas(bitmap)` zeichnet direkt in die Pixel der Bitmap (jede Zeichenoperation zählt deren Generation hoch).
 * - `Canvas(skiaCanvas)` hüllt eine fremde Skia-Leinwand ein (z. B. die von Compose beim Zeichnen einer View);
 *   getMatrix/setMatrix beziehen sich dann auf die Matrix zum Zeitpunkt des Einhüllens.
 * - Der Clip wird zusätzlich als Geräte-Rechteck mitgeführt (für [getClipBounds]); bei eingehüllten Leinwänden
 *   ist er zunächst unbegrenzt – mit [setDeviceClip] oder einem ersten clipRect einschränken.
 */
open class Canvas {
    /** Die zugrunde liegende Skia-Leinwand. */
    var skiaCanvas: org.jetbrains.skia.Canvas
        private set

    private var target: Bitmap? = null
    private var base: Matrix33? = null
    private var baseInv: Matrix? = null

    /** Clip-Stapel (Geräte-Koordinaten): Eintrag i gehört zum Save-Count i+1. */
    private val clipStack = ArrayList<RectF>()

    open var density: Int = Bitmap.DENSITY_NONE

    constructor() {
        skiaCanvas = EMPTY_CANVAS
        clipStack += RectF(0f, 0f, 0f, 0f)
    }

    constructor(bitmap: Bitmap) {
        require(!bitmap.isRecycled) { "Cannot draw to a recycled bitmap" }
        skiaCanvas = org.jetbrains.skia.Canvas(bitmap.skiaBitmap, SurfaceProps())
        target = bitmap
        density = bitmap.density
        clipStack += RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    }

    /** Hüllt eine vorhandene Skia-Leinwand ein (z. B. aus Compose `drawIntoCanvas { it.nativeCanvas }`). */
    constructor(skia: org.jetbrains.skia.Canvas) {
        skiaCanvas = skia
        val m = skia.localToDeviceAsMatrix33
        if (!Matrix.fromSkia(m).isIdentity()) {
            base = m
            baseInv = Matrix().also { Matrix.fromSkia(m).invert(it) }
        }
        clipStack += RectF(-UNBOUNDED, -UNBOUNDED, UNBOUNDED, UNBOUNDED)
    }

    /** Für Unterklassen (RecordingCanvas, PDF-Seite). */
    internal constructor(skia: org.jetbrains.skia.Canvas, width: Int, height: Int) {
        skiaCanvas = skia
        clipStack += RectF(0f, 0f, width.toFloat(), height.toFloat())
    }

    /** Setzt den bekannten sichtbaren Bereich (Geräte-Koordinaten) einer eingehüllten Leinwand. */
    fun setDeviceClip(left: Float, top: Float, right: Float, bottom: Float) {
        clipStack[clipStack.lastIndex] = RectF(left, top, right, bottom)
    }

    open fun setBitmap(bitmap: Bitmap?) {
        target = null
        clipStack.clear()
        if (bitmap == null) {
            skiaCanvas = EMPTY_CANVAS
            clipStack += RectF()
        } else {
            skiaCanvas = org.jetbrains.skia.Canvas(bitmap.skiaBitmap, SurfaceProps())
            target = bitmap
            clipStack += RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        }
    }

    open val isHardwareAccelerated: Boolean get() = false
    open val isOpaque: Boolean get() = target?.hasAlpha() == false
    open val width: Int get() = target?.width ?: 0
    open val height: Int get() = target?.height ?: 0
    open val maximumBitmapWidth: Int get() = 32766
    open val maximumBitmapHeight: Int get() = 32766

    private fun touched() { target?.touch() }

    // ===================== Zustand =====================

    open fun save(): Int {
        val n = skiaCanvas.save()
        pushClip()
        return n
    }

    @Suppress("UNUSED_PARAMETER")
    open fun save(saveFlags: Int): Int = save()

    open fun saveLayer(bounds: RectF?, paint: Paint?): Int {
        val n = if (bounds == null) {
            skiaCanvas.saveLayer(org.jetbrains.skia.Canvas.SaveLayerRec(null, paint?.toSkia()))
        } else skiaCanvas.saveLayer(bounds.toSk(), paint?.toSkia())
        pushClip()
        if (bounds != null) intersectDeviceClip(bounds.left, bounds.top, bounds.right, bounds.bottom)
        return n
    }

    @Suppress("UNUSED_PARAMETER")
    open fun saveLayer(bounds: RectF?, paint: Paint?, saveFlags: Int): Int = saveLayer(bounds, paint)

    open fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: Paint?): Int =
        saveLayer(RectF(left, top, right, bottom), paint)

    @Suppress("UNUSED_PARAMETER")
    open fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: Paint?, saveFlags: Int): Int =
        saveLayer(RectF(left, top, right, bottom), paint)

    open fun saveLayerAlpha(bounds: RectF?, alpha: Int): Int =
        saveLayer(bounds, Paint().apply { this.alpha = alpha.coerceIn(0, 255) })

    @Suppress("UNUSED_PARAMETER")
    open fun saveLayerAlpha(bounds: RectF?, alpha: Int, saveFlags: Int): Int = saveLayerAlpha(bounds, alpha)

    open fun saveLayerAlpha(left: Float, top: Float, right: Float, bottom: Float, alpha: Int): Int =
        saveLayerAlpha(RectF(left, top, right, bottom), alpha)

    open fun restore() {
        check(skiaCanvas.saveCount > 1) { "Underflow in restore - more restores than saves" }
        skiaCanvas.restore()
        touched()
        popClipTo(skiaCanvas.saveCount)
    }

    open fun restoreToCount(saveCount: Int) {
        require(saveCount >= 1) { "Underflow in restoreToCount - more restores than saves" }
        skiaCanvas.restoreToCount(saveCount)
        touched()
        popClipTo(skiaCanvas.saveCount)
    }

    open val saveCount: Int get() = skiaCanvas.saveCount

    private fun pushClip() { clipStack += RectF(clipStack.last()) }
    private fun popClipTo(count: Int) { while (clipStack.size > count && clipStack.size > 1) clipStack.removeAt(clipStack.lastIndex) }

    // ===================== Matrix =====================

    open fun translate(dx: Float, dy: Float) { if (dx != 0f || dy != 0f) skiaCanvas.translate(dx, dy) }
    open fun scale(sx: Float, sy: Float) { if (sx != 1f || sy != 1f) skiaCanvas.scale(sx, sy) }
    fun scale(sx: Float, sy: Float, px: Float, py: Float) {
        if (sx == 1f && sy == 1f) return
        translate(px, py); scale(sx, sy); translate(-px, -py)
    }
    open fun rotate(degrees: Float) { if (degrees != 0f) skiaCanvas.rotate(degrees) }
    fun rotate(degrees: Float, px: Float, py: Float) {
        if (degrees == 0f) return
        translate(px, py); rotate(degrees); translate(-px, -py)
    }
    open fun skew(sx: Float, sy: Float) { if (sx != 0f || sy != 0f) skiaCanvas.skew(sx, sy) }
    open fun concat(matrix: Matrix?) { if (matrix != null && !matrix.isIdentity()) skiaCanvas.concat(matrix.toSkia()) }

    open fun setMatrix(matrix: Matrix?) {
        val b = base
        when {
            b == null && matrix == null -> skiaCanvas.resetMatrix()
            b == null -> skiaCanvas.setMatrix(matrix!!.toSkia())
            matrix == null -> skiaCanvas.setMatrix(b)
            else -> skiaCanvas.setMatrix(b.makeConcat(matrix.toSkia()))
        }
    }

    @Deprecated("wie Android")
    open fun getMatrix(ctm: Matrix) {
        val total = Matrix.fromSkia(skiaCanvas.localToDeviceAsMatrix33)
        val bi = baseInv
        if (bi != null) { total.postConcat(bi) }
        ctm.set(total)
    }

    @Deprecated("wie Android")
    val matrix: Matrix get() = Matrix().also { @Suppress("DEPRECATION") getMatrix(it) }

    private fun deviceMatrix(): Matrix = Matrix.fromSkia(skiaCanvas.localToDeviceAsMatrix33)

    // ===================== Clip =====================

    private fun intersectDeviceClip(l: Float, t: Float, r: Float, b: Float) {
        val dev = RectF()
        deviceMatrix().mapRect(dev, RectF(l, t, r, b))
        val top = clipStack.last()
        if (!top.intersect(dev)) top.setEmpty()
    }

    private fun clipResult(): Boolean = !clipStack.last().isEmpty()

    open fun clipRect(rect: RectF): Boolean = clipRect(rect.left, rect.top, rect.right, rect.bottom)
    open fun clipRect(rect: Rect): Boolean =
        clipRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    open fun clipRect(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        clipRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    open fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        skiaCanvas.clipRect(org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom), ClipMode.INTERSECT, true)
        intersectDeviceClip(left, top, right, bottom)
        return clipResult()
    }

    open fun clipOutRect(rect: RectF): Boolean = clipOutRect(rect.left, rect.top, rect.right, rect.bottom)
    open fun clipOutRect(rect: Rect): Boolean =
        clipOutRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    open fun clipOutRect(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        clipOutRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    open fun clipOutRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
        skiaCanvas.clipRect(org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom), ClipMode.DIFFERENCE, true)
        return clipResult()
    }

    open fun clipPath(path: Path): Boolean {
        skiaCanvas.clipPath(path.skia, ClipMode.INTERSECT, true)
        if (!path.isInverseFillType) {
            val b = path.skia.bounds
            intersectDeviceClip(b.left, b.top, b.right, b.bottom)
        }
        return clipResult()
    }

    open fun clipOutPath(path: Path): Boolean {
        skiaCanvas.clipPath(path.skia, ClipMode.DIFFERENCE, true)
        return clipResult()
    }

    open fun getClipBounds(bounds: Rect?): Boolean {
        val dev = clipStack.last()
        if (dev.isEmpty()) { bounds?.setEmpty(); return false }
        if (bounds != null) {
            val inv = Matrix()
            if (!deviceMatrix().invert(inv)) { bounds.setEmpty(); return false }
            val local = RectF()
            inv.mapRect(local, dev)
            local.roundOut(bounds)
        }
        return true
    }

    val clipBounds: Rect get() = Rect().also { getClipBounds(it) }

    open fun quickReject(rect: RectF): Boolean {
        val dev = RectF(); deviceMatrix().mapRect(dev, rect)
        return !RectF.intersects(dev, clipStack.last())
    }
    open fun quickReject(left: Float, top: Float, right: Float, bottom: Float): Boolean = quickReject(RectF(left, top, right, bottom))
    open fun quickReject(path: Path): Boolean { val b = path.skia.bounds; return quickReject(b.left, b.top, b.right, b.bottom) }

    // ===================== Flächen =====================

    open fun drawColor(color: Int) {
        skiaCanvas.drawPaint(colorPaint(color, org.jetbrains.skia.BlendMode.SRC_OVER))
        touched()
    }

    open fun drawColor(color: Int, mode: PorterDuff.Mode) { skiaCanvas.drawPaint(colorPaint(color, mode.sk)); touched() }
    open fun drawColor(color: Int, mode: BlendMode) { skiaCanvas.drawPaint(colorPaint(color, mode.sk)); touched() }
    open fun drawARGB(a: Int, r: Int, g: Int, b: Int) = drawColor(Color.argb(a, r, g, b))
    open fun drawRGB(r: Int, g: Int, b: Int) = drawColor(Color.rgb(r, g, b))
    open fun drawPaint(paint: Paint) { skiaCanvas.drawPaint(paint.toSkia()); touched() }

    private fun colorPaint(color: Int, mode: org.jetbrains.skia.BlendMode): org.jetbrains.skia.Paint {
        val p = COLOR_PAINT.get()
        p.color = color
        p.blendMode = mode
        return p
    }

    // ===================== Bitmaps =====================

    private fun samplingFor(paint: Paint?): SamplingMode = sampling(paint?.isFilterBitmap == true)

    open fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
        val img = bitmap.image()
        val w = img.width.toFloat(); val h = img.height.toFloat()
        skiaCanvas.drawImageRect(
            img, org.jetbrains.skia.Rect.makeWH(w, h), org.jetbrains.skia.Rect.makeXYWH(left, top, w, h),
            samplingFor(paint), paint?.toSkia(), false,
        )
        touched()
    }

    open fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
        val img = bitmap.image()
        val s = src?.toSk() ?: org.jetbrains.skia.Rect.makeWH(img.width.toFloat(), img.height.toFloat())
        skiaCanvas.drawImageRect(img, s, dst.toSk(), samplingFor(paint), paint?.toSkia(), false)
        touched()
    }

    open fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) = drawBitmap(bitmap, src, RectF(dst), paint)

    open fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
        val n = skiaCanvas.save()
        skiaCanvas.concat(matrix.toSkia())
        val img = bitmap.image()
        val r = org.jetbrains.skia.Rect.makeWH(img.width.toFloat(), img.height.toFloat())
        skiaCanvas.drawImageRect(img, r, r, samplingFor(paint), paint?.toSkia(), false)
        skiaCanvas.restoreToCount(n)
        touched()
    }

    open fun drawBitmap(colors: IntArray, offset: Int, stride: Int, x: Float, y: Float, width: Int, height: Int, hasAlpha: Boolean, paint: Paint?) {
        if (width <= 0 || height <= 0) return
        val b = Bitmap.createBitmap(colors, offset, stride, width, height, Bitmap.Config.ARGB_8888)
        drawBitmap(b, x, y, paint)
        b.recycle()
    }

    /**
     * Gitterverzerrung wie Android: (meshWidth+1)·(meshHeight+1) Eckpunkte, Textur gleichmäßig über die Bitmap.
     */
    open fun drawBitmapMesh(
        bitmap: Bitmap, meshWidth: Int, meshHeight: Int, verts: FloatArray, vertOffset: Int,
        colors: IntArray?, colorOffset: Int, paint: Paint?,
    ) {
        if (meshWidth <= 0 || meshHeight <= 0) return
        val cols = meshWidth + 1
        val img = bitmap.image()
        val bw = img.width.toFloat(); val bh = img.height.toFloat()
        val triCount = meshWidth * meshHeight * 2
        val pos = FloatArray(triCount * 3 * 2)
        val tex = FloatArray(triCount * 3 * 2)
        val col = if (colors != null) IntArray(triCount * 3) else null
        var k = 0
        fun put(ix: Int, iy: Int) {
            val vi = iy * cols + ix
            pos[2 * k] = verts[vertOffset + 2 * vi]
            pos[2 * k + 1] = verts[vertOffset + 2 * vi + 1]
            tex[2 * k] = bw * ix / meshWidth
            tex[2 * k + 1] = bh * iy / meshHeight
            if (col != null) col[k] = colors!![colorOffset + vi]
            k++
        }
        for (y in 0 until meshHeight) for (x in 0 until meshWidth) {
            put(x, y); put(x + 1, y); put(x, y + 1)
            put(x + 1, y); put(x + 1, y + 1); put(x, y + 1)
        }
        val p = org.jetbrains.skia.Paint()
        try {
            val src = paint?.toSkia()
            if (src != null) {
                p.isAntiAlias = src.isAntiAlias
                p.alpha = src.alpha
                p.colorFilter = src.colorFilter
                p.blendMode = src.blendMode
                p.imageFilter = src.imageFilter
            }
            p.color = Color.BLACK
            p.alpha = paint?.alpha ?: 255
            p.shader = img.makeShader(
                org.jetbrains.skia.FilterTileMode.CLAMP, org.jetbrains.skia.FilterTileMode.CLAMP,
                samplingFor(paint), null,
            )
            skiaCanvas.drawVertices(VertexMode.TRIANGLES, pos, col, tex, null, org.jetbrains.skia.BlendMode.MODULATE, p)
        } finally {
            p.close()
        }
        touched()
    }

    // ===================== Formen =====================

    open fun drawPath(path: Path, paint: Paint) { skiaCanvas.drawPath(path.skia, paint.toSkia()); touched() }

    open fun drawRect(rect: RectF, paint: Paint) { skiaCanvas.drawRect(rect.toSk(), paint.toSkia()); touched() }
    open fun drawRect(r: Rect, paint: Paint) { skiaCanvas.drawRect(r.toSk(), paint.toSkia()); touched() }
    open fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        skiaCanvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(left, top, right, bottom), paint.toSkia()); touched()
    }

    open fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) =
        drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint)
    open fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, rx: Float, ry: Float, paint: Paint) {
        skiaCanvas.drawRRect(RRect.makeLTRB(left, top, right, bottom, rx, ry), paint.toSkia()); touched()
    }

    open fun drawDoubleRoundRect(outer: RectF, outerRx: Float, outerRy: Float, inner: RectF, innerRx: Float, innerRy: Float, paint: Paint) {
        skiaCanvas.drawDRRect(
            RRect.makeLTRB(outer.left, outer.top, outer.right, outer.bottom, outerRx, outerRy),
            RRect.makeLTRB(inner.left, inner.top, inner.right, inner.bottom, innerRx, innerRy), paint.toSkia(),
        )
        touched()
    }

    open fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
        skiaCanvas.drawCircle(cx, cy, radius, paint.toSkia()); touched()
    }

    open fun drawOval(oval: RectF, paint: Paint) { skiaCanvas.drawOval(oval.toSk(), paint.toSkia()); touched() }
    open fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
        drawOval(RectF(left, top, right, bottom), paint)

    open fun drawArc(oval: RectF, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) =
        drawArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter, paint)
    open fun drawArc(
        left: Float, top: Float, right: Float, bottom: Float, startAngle: Float, sweepAngle: Float,
        useCenter: Boolean, paint: Paint,
    ) {
        skiaCanvas.drawArc(left, top, right, bottom, startAngle, sweepAngle, useCenter, paint.toSkia()); touched()
    }

    open fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
        skiaCanvas.drawLine(startX, startY, stopX, stopY, paint.toSkia()); touched()
    }

    open fun drawLines(pts: FloatArray, offset: Int, count: Int, paint: Paint) {
        val n = (count / 4) * 4
        if (n <= 0) return
        skiaCanvas.drawLines(pts.copyOfRange(offset, offset + n), paint.toSkia()); touched()
    }
    open fun drawLines(pts: FloatArray, paint: Paint) = drawLines(pts, 0, pts.size, paint)

    open fun drawPoint(x: Float, y: Float, paint: Paint) { skiaCanvas.drawPoint(x, y, paint.toSkia()); touched() }
    open fun drawPoints(pts: FloatArray?, offset: Int, count: Int, paint: Paint) {
        if (pts == null) return
        val n = (count / 2) * 2
        if (n <= 0) return
        skiaCanvas.drawPoints(pts.copyOfRange(offset, offset + n), paint.toSkia()); touched()
    }
    open fun drawPoints(pts: FloatArray, paint: Paint) = drawPoints(pts, 0, pts.size, paint)

    // ===================== Text =====================

    open fun drawText(text: String, x: Float, y: Float, paint: Paint) {
        if (text.isEmpty()) return
        val sp = paint.toSkia()
        val ls = paint.letterSpacing * paint.textSize
        val width = paint.measureText(text)
        var cx = x - paint.alignOffset(width)
        if (ls == 0f) {
            skiaCanvas.drawTextLine(paint.textLine(text), cx, y, sp)
        } else {
            cx += ls / 2f
            var i = 0
            while (i < text.length) {
                val n = Character.charCount(text.codePointAt(i))
                val line = paint.textLine(text.substring(i, i + n))
                skiaCanvas.drawTextLine(line, cx, y, sp)
                cx += line.width + ls
                i += n
            }
        }
        if (paint.isUnderlineText || paint.isStrikeThruText) {
            val m = paint.skiaFont().metrics
            val thick = (m.underlineThickness ?: (paint.textSize / 18f)).coerceAtLeast(1f)
            val lp = org.jetbrains.skia.Paint().apply { color = paint.color; isAntiAlias = paint.isAntiAlias }
            val left = x - paint.alignOffset(width)
            if (paint.isUnderlineText) {
                val uy = y + (m.underlinePosition ?: (paint.textSize / 9f))
                skiaCanvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(left, uy, left + width, uy + thick), lp)
            }
            if (paint.isStrikeThruText) {
                val sy = y + (m.strikeoutPosition ?: (m.ascent / 3f))
                skiaCanvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(left, sy, left + width, sy + thick), lp)
            }
            lp.close()
        }
        touched()
    }

    open fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) =
        drawText(text.substring(start, end), x, y, paint)
    open fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) =
        drawText(text.subSequence(start, end).toString(), x, y, paint)
    open fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) =
        drawText(String(text, index, count), x, y, paint)

    @Suppress("UNUSED_PARAMETER")
    open fun drawTextRun(text: CharSequence, start: Int, end: Int, contextStart: Int, contextEnd: Int, x: Float, y: Float, isRtl: Boolean, paint: Paint) =
        drawText(text, start, end, x, y, paint)

    open fun drawTextOnPath(text: String, path: Path, hOffset: Float, vOffset: Float, paint: Paint) {
        val pm = PathMeasure(path, false)
        val pos = FloatArray(2); val tan = FloatArray(2)
        var d = hOffset
        var i = 0
        while (i < text.length) {
            val n = Character.charCount(text.codePointAt(i))
            val ch = text.substring(i, i + n)
            val w = paint.measureText(ch)
            if (pm.getPosTan(d + w / 2f, pos, tan)) {
                val n2 = skiaCanvas.save()
                skiaCanvas.translate(pos[0], pos[1])
                skiaCanvas.rotate(Math.toDegrees(kotlin.math.atan2(tan[1], tan[0]).toDouble()).toFloat())
                skiaCanvas.drawTextLine(paint.textLine(ch), -w / 2f, vOffset, paint.toSkia())
                skiaCanvas.restoreToCount(n2)
            }
            d += w
            i += n
        }
        touched()
    }

    // ===================== Bilder/Knoten =====================

    open fun drawPicture(picture: Picture) {
        picture.skia()?.let { skiaCanvas.drawPicture(it, null, null) }
        touched()
    }

    open fun drawPicture(picture: Picture, dst: RectF) {
        val p = picture.skia() ?: return
        val n = skiaCanvas.save()
        skiaCanvas.translate(dst.left, dst.top)
        if (picture.width > 0 && picture.height > 0) skiaCanvas.scale(dst.width() / picture.width, dst.height() / picture.height)
        skiaCanvas.clipRect(org.jetbrains.skia.Rect.makeWH(picture.width.toFloat(), picture.height.toFloat()))
        skiaCanvas.drawPicture(p, null, null)
        skiaCanvas.restoreToCount(n)
        touched()
    }

    open fun drawPicture(picture: Picture, dst: Rect) = drawPicture(picture, RectF(dst))

    /** Zeichnet einen [RenderNode] (Software-Pfad: Aufzeichnung + ggf. RenderEffect als ImageFilter). */
    open fun drawRenderNode(renderNode: RenderNode) {
        renderNode.drawInto(this)
        touched()
    }

    internal fun touchTarget() = touched()

    companion object {
        private const val UNBOUNDED = 1.0e9f
        private val EMPTY_CANVAS: org.jetbrains.skia.Canvas by lazy {
            val b = org.jetbrains.skia.Bitmap()
            b.allocN32Pixels(1, 1, false)
            org.jetbrains.skia.Canvas(b, SurfaceProps())
        }
        private val COLOR_PAINT = ThreadLocal.withInitial { org.jetbrains.skia.Paint() }

        @Suppress("unused")
        internal val IDENTITY33: Matrix33 = Matrix33.IDENTITY
    }
}
