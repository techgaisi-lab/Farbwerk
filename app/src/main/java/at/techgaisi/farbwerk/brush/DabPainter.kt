package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Zeichnet einzelne Pinsel-Tupfer (Stempel) auf ein Canvas: Form (rotiert, gestaucht, gespiegelt, ggf. unscharf),
 * Farbe/Deckkraft und optional Körnung (texturiert oder bewegt, mit Maßstab/Zoom/Drehung/Tiefe).
 * Alle Hilfsobjekte werden wiederverwendet – keine Allokationen pro Tupfer.
 *
 * Zusatzinformationen pro Tupfer stecken in den `flags` (siehe [encodeFlags]), damit die Tupfer-Schnittstelle
 * [DabConsumer] unverändert bleibt.
 *
 * Vereinigung ([union]): Deckende, harte runde Tupfer (analytisch, ohne Körnung/Unschärfe) werden gesammelt und
 * als EIN Pfad gefüllt (Füllregel „non-zero“ → Vereinigung der Formen). Ohne das addieren sich die
 * kantenglättenden Halbtöne von Dutzenden überlappender Tupfer zu fast deckenden Pixeln – die Kante wird
 * treppig („pixelig“). Gesammelte Tupfer werden mit [flush] gezeichnet (auch automatisch, sobald ein anderer
 * Tupfer, eine andere Farbe oder ein anderes Ziel kommt).
 *
 * Große Tupfer werden in angemeldete Ziele ([setBandTarget]) streifenweise auf mehreren Kernen gezeichnet
 * ([BrushParallel]); jeder Streifen hat eigene Zeichenfläche und Werkzeuge, das Ergebnis ist pixelgleich.
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

    /** Deckende harte Tupfer als Vereinigung zeichnen (siehe Klassenbeschreibung). Danach [flush] aufrufen. */
    var union = false
        set(v) { if (!v) flush(); field = v }
    /** Pinsel kann vereinigt gezeichnet werden (harte analytische Spitze ohne Körnung). Nach [setup] gültig. */
    var unionCapable = false
        private set
    private var unionCanvas: Canvas? = null
    private var unionColor = 0
    private var unionCount = 0
    private val unionPaths = ArrayList<Path>()
    private val unionTmp = Path()
    private val unionMatrix = Matrix()

    private var baseShape: Bitmap = BrushTextures.shape(0)
    private var analyticHard = false
    private var grainShader: BitmapShader? = null
    private var grainMask: Bitmap? = null
    private var grainScale = 1f
    private var detail = 1f
    private var grainMoving = false
    private var grainMovement = 1f
    private var grainZoom = 0f
    private var grainRotation = 0f
    private var baseDepth = 0f
    private var offX = 0f
    private var offY = 0f

    /**
     * Übernimmt Form und Körnung des Pinsels. Vor jedem Strich aufrufen. [detailScale] = Feinberechnung des
     * Dokuments: die im Dokument feste Körnung wird mitskaliert, damit sie physisch gleich groß bleibt.
     */
    fun setup(brush: Brush, detailScale: Float = 1f) {
        detail = detailScale.coerceAtLeast(0.01f)
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
        grainScale = brush[BP.GRAIN_SCALE] * detail
        grainMoving = brush.int(BP.GRAIN_MODE) == 1
        grainMovement = brush[BP.GRAIN_MOVEMENT]
        grainZoom = brush[BP.GRAIN_ZOOM]
        grainRotation = brush[BP.GRAIN_ROTATION]
        baseDepth = brush[BP.GRAIN_DEPTH]
        unionCapable = analyticHard && (grainShader == null || baseDepth <= 0.004f) && brush[BP.WET_BLUR] <= 0f
        if (brush.on(BP.GRAIN_OFFSET_JITTER)) {
            offX = (Math.random() * BrushImages.SIZE).toFloat(); offY = (Math.random() * BrushImages.SIZE).toFloat()
        } else { offX = 0f; offY = 0f }
    }

    /**
     * Ungefährer Rechenaufwand (bearbeitete Pixel) eines Tupfers mit Durchmesser [diam] nach [setup]: analytische
     * harte Spitzen ohne Körnung sind billig, Bildformen kosten ihre Fläche, Körnung etwa das Dreifache.
     */
    fun pixelCost(diam: Float): Float {
        val grain = grainShader != null && baseDepth > 0.004f && ceil(diam * 1.42f).toInt() + 4 <= MAX_SCRATCH
        if (analyticHard && !grain) return 0.1f * diam * diam
        return diam * diam * (if (grain) 3f else 1f)
    }

    /**
     * Zeichnet einen Tupfer mit Mittelpunkt ([x],[y]).
     * @param angle Drehung in Radiant
     * @param flags siehe [encodeFlags]
     * @param m optionale zusätzliche Abbildung (Symmetrie-Kopie); [slot] trennt die Vereinigungspfade je Kopie
     */
    fun draw(
        c: Canvas, x: Float, y: Float, diam: Float, aspect: Float, angle: Float,
        alpha: Float, color: Int, flags: Int, m: Matrix? = null, slot: Int = 0,
    ) {
        if (alpha <= 0.002f || diam <= 0.05f) return
        val g = grainShader
        val depth = if (flags and FLAG_DEPTH != 0) ((flags shr 8) and 0xFF) / 255f else baseDepth
        val noGrain = g == null || depth <= 0.004f
        if (union && noGrain && analyticHard && ((flags shr 16) and 0x7) == 0 && (alpha * 255f + 0.5f).toInt() >= 255) {
            appendUnion(c, x, y, diam, aspect, angle, color, m, slot)
            return
        }
        flush()
        if (m != null) {
            // mit zusätzlicher Abbildung nie streifenweise (die Streifen-Zeichenflächen kennen sie nicht)
            c.save(); c.concat(m)
            mapped = true
            try { drawSingle(c, x, y, diam, aspect, angle, alpha, color, flags, noGrain, depth) } finally { mapped = false }
            c.restore()
        } else drawSingle(c, x, y, diam, aspect, angle, alpha, color, flags, noGrain, depth)
    }

    /** Zeichnet alle gesammelten Vereinigungs-Tupfer (siehe [union]). */
    fun flush() {
        val c = unionCanvas ?: return
        ovalPaint.color = unionColor or (0xFF shl 24)
        for (p in unionPaths) if (!p.isEmpty) { c.drawPath(p, ovalPaint); p.rewind() }
        unionCanvas = null
        unionCount = 0
    }

    private fun appendUnion(
        c: Canvas, x: Float, y: Float, diam: Float, aspect: Float, angle: Float, color: Int, m: Matrix?, slot: Int,
    ) {
        val rgb = color and 0x00FFFFFF
        if (unionCanvas !== c || rgb != unionColor || unionCount >= MAX_UNION_DABS) flush()
        unionCanvas = c
        unionColor = rgb
        while (unionPaths.size <= slot) unionPaths.add(Path())
        val path = unionPaths[slot]
        val r = diam / 2f
        if (aspect >= 0.999f && m == null) {
            path.addCircle(x, y, r, Path.Direction.CW)
        } else {
            unionTmp.rewind()
            unionTmp.addOval(-r, -r * aspect, r, r * aspect, Path.Direction.CW)
            unionMatrix.setRotate(Math.toDegrees(angle.toDouble()).toFloat())
            unionMatrix.postTranslate(x, y)
            if (m != null) unionMatrix.postConcat(m)
            path.addPath(unionTmp, unionMatrix)
        }
        unionCount++
    }

    private fun drawSingle(
        c: Canvas, x: Float, y: Float, diam: Float, aspect: Float, angle: Float,
        alpha: Float, color: Int, flags: Int, noGrain: Boolean, depth: Float,
    ) {
        val g = grainShader
        val t = bandTargetFor(c)
        // Kantenlänge der (für jede Drehung ausreichenden) Stempelfläche – bestimmt, ob überhaupt Körnung
        // angewandt wird, und die Lage des Tupfers im Zwischenspeicher (Mitte s/2)
        val s = ceil(diam * 1.42f).toInt() + 4
        if (noGrain || g == null || s > MAX_SCRATCH) {
            // ohne Körnung (sehr große Tupfer: ohne Körnung wegen Speicher/Leistung)
            if (!prepareShape(x, y, diam, aspect, angle, alpha, color, flags)) return
            if (t != null && !prepOval) {
                val cs = abs(cos(angle)); val sn = abs(sin(angle))
                val ex = 0.5f * diam * (cs + aspect * sn) + 2f
                val ey = 0.5f * diam * (sn + aspect * cs) + 2f
                if (bandRows(t, x - ex, y - ey, x + ex, y + ey)) {
                    syncBandTools(false)
                    drawBanded(t) { bc, k -> k.render(bc, prepBitmap!!) }
                    return
                }
            }
            renderShape(c)
            return
        }
        // Nur das umschließende Rechteck der (gedrehten, gestauchten) Form bearbeiten – bei ungedrehten runden
        // Tupfern halb so viele Pixel wie das für jede Drehung reichende Quadrat. Die Kanten haben dieselbe
        // Parität wie s: Die Mitte verschiebt sich gegenüber s/2 nur um ganze Pixel, der Tupfer wird also mit
        // derselben Subpixel-Lage gerastert und gleich (gefiltert) übertragen – das Ergebnis bleibt pixelgleich.
        val sw = fitParity(diam * (abs(cos(angle)) + aspect * abs(sin(angle))), s)
        val sh = fitParity(diam * (abs(sin(angle)) + aspect * abs(cos(angle))), s)
        val sc = ensureScratch(max(sw, sh))
        val hx = sw / 2f
        val hy = sh / 2f
        val hasShape = prepareShape(hx, hy, diam, aspect, angle, alpha, color, flags)
        // Körnung: Texturiert = fest im Dokument, Bewegt = (je nach "Bewegung") am Tupfer
        if (grainMoving) {
            val sEff = (grainScale * (1f + grainZoom * (diam / detail / 128f - 1f))).coerceAtLeast(0.03f)
            val mv = grainMovement
            grainMatrix.setScale(sEff, sEff)
            grainMatrix.postTranslate(hx * mv + (hx - x) * (1f - mv) + offX, hy * mv + (hy - y) * (1f - mv) + offY)
            if (grainRotation != 0f) {
                grainMatrix.postRotate(Math.toDegrees(angle.toDouble()).toFloat() * grainRotation, hx, hy)
            }
        } else {
            grainMatrix.setScale(grainScale, grainScale)
            grainMatrix.postTranslate(-(x - hx) + offX, -(y - hy) + offY)
        }
        g.setLocalMatrix(grainMatrix)
        grainPaint.alpha = (depth.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        src.set(0, 0, sw, sh)
        dst.set(x - hx, y - hy, x + hx, y + hy)
        // Geleert (und gekörnt) wird ein Pixel mehr als übertragen: Die Filterung beim Übertragen liest am rechten
        // und unteren Rand ein Pixel jenseits des Quellrechtecks – dort darf nichts von früheren Tupfern stehen
        // (früher konnten dort Reste größerer Tupfer als feiner Schleier neben dem Strich durchscheinen).
        val cw = minOf(sw + 2, scratch!!.width); val ch = minOf(sh + 2, scratch!!.height)
        val fw = cw.toFloat(); val fh = ch.toFloat()
        if (c.isHardwareAccelerated) {
            // Rasterize textured dabs on the GPU. Form/grain coordinates and depth match the CPU path.
            c.save(); c.translate(x - hx, y - hy)
            val layer = c.saveLayer(0f, 0f, fw, fh, null)
            try {
                if (hasShape) renderShape(c)
                c.drawRect(0f, 0f, fw, fh, grainPaint)
            } finally { c.restoreToCount(layer); c.restore() }
            return
        }
        if (t != null && sw.toLong() * sh >= BAND_MIN_AREA && bandRows(t, dst.left - 1f, dst.top - 1f, dst.right + 1f, dst.bottom + 1f)) {
            // 1. Form + Körnung im Zwischenspeicher, streifenweise parallel (jede Zeile hängt nur von sich ab)
            syncBandTools(true)
            val parts = bandParts
            val sb = scratchBands
            val tools = bandTools
            val shape = prepBitmap
            BrushParallel.run(parts) { p ->
                val bc = sb[p]!!
                val k = tools[p]
                bc.save()
                bc.clipRect(0, ch * p / parts, cw, ch * (p + 1) / parts)
                bc.drawRect(0f, 0f, fw, fh, k.clear)
                if (hasShape) k.render(bc, shape)
                bc.drawRect(0f, 0f, fw, fh, k.grain)
                bc.restore()
            }
            // 2. gefiltert ins Ziel übertragen (liest Nachbarzeilen → erst nach Schritt 1)
            val sc2 = scratch!!
            drawBanded(t) { bc, k -> bc.drawBitmap(sc2, k.src, k.dst, k.blit) }
            return
        }
        sc.drawRect(0f, 0f, fw, fh, clearPaint)
        if (hasShape) renderShape(sc)
        sc.drawRect(0f, 0f, fw, fh, grainPaint)
        c.drawBitmap(scratch!!, src, dst, blitPaint)
    }

    /**
     * Kantenlänge (≤ [s]) für eine Ausdehnung [extent] plus Rand für Kantenglättung/Filterung, mit derselben
     * Parität wie [s] (siehe [drawSingle]).
     */
    private fun fitParity(extent: Float, s: Int): Int {
        var n = ceil(extent).toInt() + 6
        if ((s - n) and 1 != 0) n++
        return n.coerceAtMost(s)
    }

    // Vorbereitete Form (siehe [prepareShape]); beim streifenweisen Zeichnen von allen Threads nur gelesen
    private var prepOval = false
    private var prepBitmap: Bitmap? = null
    private var prepX = 0f
    private var prepY = 0f
    private var prepDeg = 0f

    /**
     * Bereitet die Form eines Tupfers mit Mittelpunkt ([x],[y]) vor (Farbe, Matrix bzw. Ellipse); [renderShape]
     * zeichnet sie. false, wenn nichts zu zeichnen ist.
     */
    private fun prepareShape(
        x: Float, y: Float, diam: Float, aspect: Float, angle: Float,
        alpha: Float, color: Int, flags: Int,
    ): Boolean {
        val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        if (a == 0) return false
        val col = (color and 0x00FFFFFF) or (a shl 24)
        val deg = Math.toDegrees(angle.toDouble()).toFloat()
        val blur = (flags shr 16) and 0x7
        if (analyticHard && blur == 0) {
            // Harte runde Spitze analytisch → bei jeder Größe scharfe Kanten
            ovalPaint.color = col
            val r = diam / 2f
            oval.set(-r, -r * aspect, r, r * aspect)
            prepOval = true
            prepX = x; prepY = y; prepDeg = deg
            return true
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
        prepOval = false
        prepBitmap = shape
        return true
    }

    /** Zeichnet die mit [prepareShape] vorbereitete Form (verändert nur [c]). */
    private fun renderShape(c: Canvas) {
        if (prepOval) {
            c.save()
            c.translate(prepX, prepY)
            if (prepDeg != 0f) c.rotate(prepDeg)
            c.drawOval(oval, ovalPaint)
            c.restore()
        } else {
            c.drawBitmap(prepBitmap ?: return, m, shapePaint)
        }
    }

    // ------------------------------------------------------------ streifenweise parallel zeichnen

    /**
     * Zeichenziel, in das große Tupfer streifenweise auf mehreren Kernen gezeichnet werden dürfen: [canvas] zeichnet
     * in [bitmap], verschoben um ([dx],[dy]) und begrenzt auf [clip] (Bitmap-Pixel) – sonst nichts (keine
     * weitere Transformation, kein weiterer Clip). Jeder Thread zeichnet mit einer eigenen Zeichenfläche nur
     * seine Zeilen; das Ergebnis ist pixelgleich mit dem Zeichnen in einem Durchgang.
     */
    private class BandTarget {
        var canvas: Canvas? = null
        var bitmap: Bitmap? = null
        var dx = 0
        var dy = 0
        val clip = Rect()
        var bands = arrayOfNulls<Canvas>(0)
    }

    private val targets = arrayOf(BandTarget(), BandTarget())
    private var scratchBands = arrayOfNulls<Canvas>(0)
    /** Von [bandRows] bestimmt: Zeilenbereich (Bitmap-Pixel) und Anzahl der Streifen. */
    private val bandRect = Rect()
    private var bandParts = 1

    /**
     * Meldet [c] als Ziel für streifenweises Zeichnen an ([slot] 0 oder 1; null meldet ab). [c] muss in [bmp]
     * zeichnen, nur um ([dx],[dy]) verschoben und auf [clip] begrenzt (null = ganze Bitmap).
     */
    fun setBandTarget(slot: Int, c: Canvas?, bmp: Bitmap?, dx: Int = 0, dy: Int = 0, clip: Rect? = null) {
        val t = targets[slot]
        if (c == null || bmp == null || BrushParallel.THREADS <= 1) { t.canvas = null; return }
        if (t.bitmap !== bmp || t.bands.size != BrushParallel.THREADS) {
            t.bitmap = bmp
            // Zeichenflächen wiederverwenden (sie halten sonst die Pixel der alten Bitmap bis zur nächsten GC fest)
            if (t.bands.size == BrushParallel.THREADS) for (bc in t.bands) bc!!.setBitmap(bmp)
            else t.bands = Array(BrushParallel.THREADS) { Canvas(bmp) }
        }
        t.canvas = c
        t.dx = dx; t.dy = dy
        if (clip != null) t.clip.set(clip) else t.clip.set(0, 0, bmp.width, bmp.height)
        if (!t.clip.intersect(0, 0, bmp.width, bmp.height)) t.clip.setEmpty()
    }

    /** Gerade wird mit einer Symmetrie-Abbildung gezeichnet (siehe [draw]). */
    private var mapped = false

    private fun bandTargetFor(c: Canvas): BandTarget? {
        if (BrushParallel.THREADS <= 1 || mapped) return null
        for (t in targets) if (t.canvas === c && t.bitmap?.isRecycled == false) return t
        return null
    }

    /**
     * Bestimmt für einen Tupfer mit dem Rechteck (Zielkoordinaten) den Zeilenbereich in der Bitmap und, ob sich das
     * Aufteilen lohnt.
     */
    private fun bandRows(t: BandTarget, l: Float, tp: Float, r: Float, b: Float): Boolean {
        bandRect.set(
            kotlin.math.floor(l).toInt() + t.dx, kotlin.math.floor(tp).toInt() + t.dy,
            ceil(r).toInt() + t.dx, ceil(b).toInt() + t.dy,
        )
        if (!bandRect.intersect(t.clip)) return false
        val area = bandRect.width().toLong() * bandRect.height()
        if (area < BAND_MIN_AREA) return false
        bandParts = minOf(BrushParallel.THREADS.toLong(), area / BAND_PART_AREA, bandRect.height() / 16L).toInt()
        return bandParts > 1
    }

    /** Zeichnet mit [fn] streifenweise in [t] (Zeilen aus [bandRows]); [fn] erhält die Werkzeuge des Streifens. */
    private inline fun drawBanded(t: BandTarget, crossinline fn: (Canvas, BandTools) -> Unit) {
        val parts = bandParts
        val top = bandRect.top; val h = bandRect.height()
        val l = bandRect.left; val r = bandRect.right
        val dx = t.dx.toFloat(); val dy = t.dy.toFloat()
        val bands = t.bands
        val tools = bandTools
        BrushParallel.run(parts) { p ->
            val bc = bands[p]!!
            bc.save()
            bc.clipRect(l, top + h * p / parts, r, top + h * (p + 1) / parts)
            bc.translate(dx, dy)
            fn(bc, tools[p])
            bc.restore()
        }
    }

    /**
     * Eigene Zeichenwerkzeuge je Streifen: Paint/Matrix/Shader werden nie von mehreren Threads gleichzeitig benutzt
     * (sie können beim Zeichnen interne Zwischenstände anlegen). Vor jedem parallelen Zeichnen mit dem
     * Zustand des Malers abgeglichen ([syncBandTools]).
     */
    private class BandTools {
        val shape = Paint()
        val oval = Paint()
        val grain = Paint()
        val blit = Paint()
        val clear = Paint()
        val m = Matrix()
        val gm = Matrix()
        val ovalRect = RectF()
        val src = Rect()
        val dst = RectF()
        var x = 0f; var y = 0f; var deg = 0f; var isOval = false
        var grainShader: BitmapShader? = null
        var grainOf: Bitmap? = null

        fun render(c: Canvas, shape: Bitmap?) {
            if (isOval) {
                c.save()
                c.translate(x, y)
                if (deg != 0f) c.rotate(deg)
                c.drawOval(ovalRect, oval)
                c.restore()
            } else if (shape != null) {
                c.drawBitmap(shape, m, this.shape)
            }
        }
    }

    private val bandTools by lazy(LazyThreadSafetyMode.NONE) { Array(BrushParallel.THREADS) { BandTools() } }

    /** Überträgt den vorbereiteten Tupfer (Form, Farbe, ggf. Körnung und Übertragung) in die Streifen-Werkzeuge. */
    private fun syncBandTools(withGrain: Boolean) {
        val gMask = grainMask
        for (k in bandTools) {
            k.isOval = prepOval
            k.x = prepX; k.y = prepY; k.deg = prepDeg
            if (prepOval) { k.oval.set(ovalPaint); k.ovalRect.set(oval) }
            else { k.shape.set(shapePaint); k.m.set(m) }
            if (withGrain && gMask != null) {
                k.clear.set(clearPaint)
                k.blit.set(blitPaint)
                k.src.set(src); k.dst.set(dst)
                if (k.grainOf !== gMask) {
                    k.grainShader = BitmapShader(gMask, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                    k.grainOf = gMask
                }
                val sh = k.grainShader!!
                k.grain.set(grainPaint)
                k.gm.set(grainMatrix)
                sh.setLocalMatrix(k.gm)
                k.grain.shader = sh
            }
        }
    }

    private fun ensureScratch(s: Int): Canvas {
        val cur = scratch
        if (cur == null || cur.width < s) {
            var size = 64
            while (size < s) size *= 2
            scratchCanvas?.setBitmap(null)
            for (bc in scratchBands) bc?.setBitmap(null)
            cur?.recycle()
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            scratch = b
            scratchCanvas = Canvas(b)
            scratchBands = if (BrushParallel.THREADS > 1) Array(BrushParallel.THREADS) { Canvas(b) } else arrayOfNulls(0)
        }
        return scratchCanvas!!
    }

    /** Gibt den Zwischenspeicher frei. */
    fun release() {
        flush()
        scratchCanvas?.setBitmap(null)
        for (bc in scratchBands) bc?.setBitmap(null)
        scratch?.recycle(); scratch = null; scratchCanvas = null
        scratchBands = arrayOfNulls(0)
        for (t in targets) {
            for (bc in t.bands) bc?.setBitmap(null)
            t.canvas = null; t.bitmap = null; t.bands = arrayOfNulls(0)
        }
    }

    companion object {
        const val MAX_SCRATCH = 1024
        /** Ab dieser Fläche (Pixel) wird ein Tupfer streifenweise parallel gezeichnet. */
        private const val BAND_MIN_AREA = 90_000L
        /** Mindestfläche je Streifen. */
        private const val BAND_PART_AREA = 45_000L
        /** Höchstzahl gesammelter Vereinigungs-Tupfer, bevor gezeichnet wird (Pfadgröße begrenzen). */
        private const val MAX_UNION_DABS = 4000
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
