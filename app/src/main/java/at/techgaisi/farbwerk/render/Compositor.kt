package at.techgaisi.farbwerk.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderNode
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.LayerBlend
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Setzt die Ebenen eines Dokuments zusammen.
 *
 * Reihenfolge: Hintergrundfarbe (falls sichtbar), dann Ebenen von unten (Index 0) nach oben.
 * Je Ebene werden Deckkraft, Mischmodus, Ebenenmaske, Schnittmaske und Gruppen-Sichtbarkeit/-Deckkraft berücksichtigt.
 *
 * Performance:
 *  - Paints, Farbfilter und Hilfsobjekte werden je Thread einmal angelegt und wiederverwendet.
 *  - Eine Ebene ohne Maske/Schnittmaske/drawOverride wird direkt mit drawBitmap (Alpha + BlendMode im Paint)
 *    gezeichnet, ohne Offscreen-Ebene. saveLayer nur, wenn wirklich nötig.
 *
 * Schnittmasken: Die Clipping-Ebene wird in eine eigene Offscreen-Ebene gezeichnet, dort mit dem Alpha der
 * Basis-Ebene (inkl. deren Maske) per DST_IN beschnitten und anschließend mit ihrem eigenen Mischmodus über
 * alles darunter gelegt. Dadurch wirkt der Mischmodus der Clipping-Ebene – wie erwartet – auf das gesamte
 * Bild darunter, ist aber nur dort sichtbar, wo die Basis Pixel hat.
 *
 * Eigene Mischmodi ([BlendMath.isCustom]: Linear nachbelichten, Schattieren, Dunklere/Hellere Farbe,
 * Strahlendes/Lineares Licht, Lichtpunkt, Harte Mischung, Subtrahieren, Dividieren) brauchen das Bild
 * darunter als Eingabe. Sie werden exakt berechnet:
 *  - **Bildschirm, ab Android 13:** alles unter der Ebene wird in einen RenderNode aufgezeichnet; darauf liegt
 *    ein AGSL-RuntimeShader ([BlendShader]) als RenderEffect, der die Ebene (inkl. Maske/Schnittmaske)
 *    verrechnet. Mehrere solche Ebenen werden verschachtelt. Alles läuft auf der GPU, ohne Rücklesen.
 *  - **Bildschirm bis Android 12, Exporte, Zusammenfügen, Pipette:** Software-Zusammensetzung in eine
 *    Bitmap mit Pixelschleife ([BlendMath.blendInto]); auf dem Bildschirm nur der sichtbare Ausschnitt.
 * Ebenen oberhalb der obersten eigenen Ebene werden immer nativ darübergezeichnet.
 */
object Compositor {

    /** Wiederverwendbare Zeichenobjekte (je Thread, da flatten auch im Hintergrund laufen kann). */
    private class Ctx {
        /** Direktes Zeichnen einer Ebene (Alpha + BlendMode). */
        val direct = Paint()
        /** Paint beim Zurückschreiben einer Offscreen-Ebene (Alpha, BlendMode). */
        val layer = Paint()
        /** Inhalt innerhalb einer Offscreen-Ebene. */
        val plain = Paint()
        /** Beschneiden mit Alpha (Masken als ALPHA_8 und Schnittmasken-Basis). */
        val dstIn = Paint().apply { blendMode = BlendMode.DST_IN }
        /** Offscreen-Ebene für eine Schnittmasken-Basis mit Maske/drawOverride. */
        val dstInLayer = Paint().apply { blendMode = BlendMode.DST_IN }
        /** Beschneiden mit der Helligkeit einer ARGB-Maske (weiß = sichtbar). */
        val dstInLuma = Paint().apply { blendMode = BlendMode.DST_IN; colorFilter = LUMA_TO_ALPHA }
        /** Hält einen sonst leeren RenderNode „nicht leer“, ohne etwas zu verändern. */
        val keepAlive = Paint().apply { blendMode = BlendMode.DST }
        val matrix = Matrix()
        val values = FloatArray(9)
        val clip = Rect()
        val pixel: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val pixelCanvas = Canvas(pixel)
        /** Arbeitsbitmaps der Software-Zusammensetzung (Ergebnis-Ausschnitt / Ebeneninhalt). */
        val region = Scratch()
        val content = Scratch()
    }

    /**
     * Wiederverwendbare Arbeitsbitmap. Wächst nur; kleinere Größen werden per [Bitmap.reconfigure]
     * ohne Neuallokation bedient.
     */
    private class Scratch {
        private var bmp: Bitmap? = null
        fun obtain(w: Int, h: Int): Bitmap {
            val cur = bmp
            if (cur != null && !cur.isRecycled) {
                if (cur.width == w && cur.height == h) return cur
                if (cur.allocationByteCount >= w * h * 4) {
                    cur.reconfigure(w, h, Bitmap.Config.ARGB_8888)
                    return cur
                }
                // Auf dem UI-Thread kann der RenderThread die alte Bitmap noch zeichnen → nicht recyceln (GC)
                if (Looper.myLooper() != Looper.getMainLooper()) cur.recycle()
            }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp = it }
        }
        fun release() { bmp?.recycle(); bmp = null }
        /** Verweis aufgeben, ohne zu recyceln (Bitmap evtl. noch in einer Anzeigeliste). */
        fun drop() { bmp = null }
    }

    private val LUMA_TO_ALPHA = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0f, 0f, 0f, 0f, 0f,
        0.2126f, 0.7152f, 0.0722f, 0f, 0f,
    )))

    /** Invertiert RGB (Alpha bleibt). Wird auch von [at.techgaisi.farbwerk.model.LayerOps] benutzt. */
    val INVERT_FILTER = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )))

    private val ctx = ThreadLocal.withInitial { Ctx() }

    /** Ab diesem Zoomfaktor werden Pixel scharf (ohne Interpolation) dargestellt. */
    private const val PIXEL_ZOOM = 2f

    /** Größte Dokumentkante für den GPU-Pfad (Texturgrenze gängiger GPUs). */
    private const val GPU_MAX_SIZE = 8192

    /**
     * Abbildung der Ebenen-Mischmodi auf Android. Für die eigenen Modi ([BlendMath.isCustom]) liefert die
     * Funktion nur die nächstliegende native Entsprechung – der Compositor zeichnet sie selbst exakt.
     */
    fun blendModeOf(b: LayerBlend): BlendMode = when (b) {
        LayerBlend.NORMAL -> BlendMode.SRC_OVER
        LayerBlend.MULTIPLY -> BlendMode.MULTIPLY
        LayerBlend.DARKEN -> BlendMode.DARKEN
        LayerBlend.COLOR_BURN -> BlendMode.COLOR_BURN
        LayerBlend.SCREEN -> BlendMode.SCREEN
        LayerBlend.LIGHTEN -> BlendMode.LIGHTEN
        LayerBlend.COLOR_DODGE -> BlendMode.COLOR_DODGE
        LayerBlend.ADD -> BlendMode.PLUS
        LayerBlend.OVERLAY -> BlendMode.OVERLAY
        LayerBlend.SOFT_LIGHT -> BlendMode.SOFT_LIGHT
        LayerBlend.HARD_LIGHT -> BlendMode.HARD_LIGHT
        LayerBlend.DIFFERENCE -> BlendMode.DIFFERENCE
        LayerBlend.EXCLUSION -> BlendMode.EXCLUSION
        LayerBlend.HUE -> BlendMode.HUE
        LayerBlend.SATURATION -> BlendMode.SATURATION
        LayerBlend.COLOR -> BlendMode.COLOR
        LayerBlend.LUMINOSITY -> BlendMode.LUMINOSITY
        // Nur Näherungen (werden nicht benutzt – siehe BlendMath)
        LayerBlend.LINEAR_BURN, LayerBlend.SHADE -> BlendMode.MULTIPLY
        LayerBlend.DARKER_COLOR -> BlendMode.DARKEN
        LayerBlend.LIGHTER_COLOR -> BlendMode.LIGHTEN
        LayerBlend.VIVID_LIGHT, LayerBlend.LINEAR_LIGHT, LayerBlend.PIN_LIGHT, LayerBlend.HARD_MIX -> BlendMode.HARD_LIGHT
        LayerBlend.SUBTRACT -> BlendMode.DIFFERENCE
        LayerBlend.DIVIDE -> BlendMode.COLOR_DODGE
    }

    /** Zeichnet das ganze Dokument (inkl. Hintergrund, falls sichtbar) in Dokument-Koordinaten. */
    fun draw(canvas: Canvas, doc: ArtDocument) {
        val c = ctx.get()!!
        val filter = filterFor(canvas, c)
        val layers = doc.layers
        val top = topCustomIndex(doc, layers, forceVisible = false)
        if (top < 0) {
            render(canvas, doc, doc.backgroundVisible, filter, c)
            return
        }
        val main = Looper.myLooper() == Looper.getMainLooper()
        if (Build.VERSION.SDK_INT >= 33 && main && !gpuFailed && canvas.isHardwareAccelerated &&
            maxOf(doc.width, doc.height) <= GPU_MAX_SIZE
        ) {
            try {
                gpu().draw(canvas, doc, filter, c)
                return
            } catch (t: Throwable) {
                // z. B. AGSL lässt sich auf dem Gerät nicht übersetzen → dauerhaft Software-Pfad
                android.util.Log.e("Farbwerk", "GPU-Mischmodus fehlgeschlagen, Software-Pfad", t)
                gpuFailed = true
                gpuInstance = null
            }
        }
        // Software: nur der sichtbare Ausschnitt (Dokument-Koordinaten) wird berechnet
        val clip = c.clip
        if (!canvas.getClipBounds(clip)) return
        if (!clip.intersect(0, 0, doc.width, doc.height) || clip.isEmpty) return
        try {
            val out = c.region.obtain(clip.width(), clip.height())
            composeSoftware(out, clip.left, clip.top, doc, layers, top + 1, doc.backgroundVisible, false, c)
            val p = c.direct
            p.alpha = 255; p.blendMode = BlendMode.SRC_OVER; p.isFilterBitmap = filter
            canvas.drawBitmap(out, clip.left.toFloat(), clip.top.toFloat(), p)
            drawRange(canvas, doc, layers, top + 1, layers.size, filter, false, c)
        } finally {
            if (!main) { c.region.release(); c.content.release() }
        }
    }

    /** Liefert das zusammengefügte Bild in Dokumentgröße. */
    fun flatten(doc: ArtDocument, includeBackground: Boolean = true): Bitmap {
        val b = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        val c = ctx.get()!!
        val bg = includeBackground && doc.backgroundVisible
        val top = topCustomIndex(doc, doc.layers, forceVisible = false)
        if (top < 0) {
            render(Canvas(b), doc, bg, false, c)
            return b
        }
        try {
            composeSoftware(b, 0, 0, doc, doc.layers, top + 1, bg, false, c)
            drawRange(Canvas(b), doc, doc.layers, top + 1, doc.layers.size, false, false, c)
        } finally {
            releaseIfBackground(c)
        }
        return b
    }

    /**
     * Farbe des zusammengesetzten Bildes an einer Dokumentposition. Rendert nur einen einzigen Pixel
     * (1×1-Bitmap mit Verschiebung) – Skia verwirft alles außerhalb des Clips sehr früh.
     */
    fun samplePixel(doc: ArtDocument, x: Float, y: Float): Int {
        val c = ctx.get()!!
        val ix = floor(x).toInt().coerceIn(0, doc.width - 1)
        val iy = floor(y).toInt().coerceIn(0, doc.height - 1)
        val top = topCustomIndex(doc, doc.layers, forceVisible = false)
        if (top >= 0) {
            composeSoftware(c.pixel, ix, iy, doc, doc.layers, top + 1, doc.backgroundVisible, false, c)
            val pc = c.pixelCanvas
            val save = pc.save()
            pc.translate(-ix.toFloat(), -iy.toFloat())
            drawRange(pc, doc, doc.layers, top + 1, doc.layers.size, false, false, c)
            pc.restoreToCount(save)
            return c.pixel.getPixel(0, 0)
        }
        c.pixel.eraseColor(Color.TRANSPARENT)
        val pc = c.pixelCanvas
        val save = pc.save()
        pc.translate(-ix.toFloat(), -iy.toFloat())
        render(pc, doc, doc.backgroundVisible, false, c)
        pc.restoreToCount(save)
        return c.pixel.getPixel(0, 0)
    }

    /**
     * Zeichnet nur die angegebenen Ebenen (in Listenreihenfolge, unten zuerst) ohne Hintergrund.
     * Wird zum Zusammenfügen benutzt. [forceVisible]: auch ausgeblendete Ebenen/Gruppen zeichnen.
     */
    fun renderLayers(canvas: Canvas, doc: ArtDocument, layers: List<Layer>, forceVisible: Boolean) {
        val c = ctx.get()!!
        val top = topCustomIndex(doc, layers, forceVisible)
        if (top < 0) {
            drawRange(canvas, doc, layers, 0, layers.size, false, forceVisible, c)
            return
        }
        val tmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        try {
            composeSoftware(tmp, 0, 0, doc, layers, top + 1, false, forceVisible, c)
            val p = c.direct
            p.alpha = 255; p.blendMode = BlendMode.SRC_OVER; p.isFilterBitmap = false
            canvas.drawBitmap(tmp, 0f, 0f, p)
            drawRange(canvas, doc, layers, top + 1, layers.size, false, forceVisible, c)
        } finally {
            tmp.recycle()
            releaseIfBackground(c)
        }
    }

    /** True, wenn mindestens eine sichtbare Ebene einen eigenen (nicht nativen) Mischmodus benutzt. */
    fun usesCustomBlend(doc: ArtDocument): Boolean = topCustomIndex(doc, doc.layers, false) >= 0

    // ------------------------------------------------------------------------------------------

    private fun releaseIfBackground(c: Ctx) {
        if (Looper.myLooper() != Looper.getMainLooper()) { c.region.release(); c.content.release() }
    }

    private fun render(canvas: Canvas, doc: ArtDocument, background: Boolean, filter: Boolean, c: Ctx) {
        if (background) canvas.drawColor(doc.backgroundColor)
        drawRange(canvas, doc, doc.layers, 0, doc.layers.size, filter, forceVisible = false, c = c)
    }

    /** Interpolation nur, solange nicht stark hineingezoomt ist (dann sollen Pixel scharf erscheinen). */
    @Suppress("DEPRECATION")
    private fun filterFor(canvas: Canvas, c: Ctx): Boolean {
        canvas.getMatrix(c.matrix)
        c.matrix.getValues(c.values)
        val scale = hypot(c.values[Matrix.MSCALE_X], c.values[Matrix.MSKEW_Y])
        return scale < PIXEL_ZOOM
    }

    /** Sichtbarkeit einer Ebene inkl. Gruppe. */
    private fun visible(doc: ArtDocument, l: Layer, forceVisible: Boolean) =
        forceVisible || (l.visible && LayerGroups.isVisible(doc, l.groupId))

    /**
     * Index der obersten tatsächlich gezeichneten Ebene mit eigenem Mischmodus, sonst −1.
     * Berücksichtigt Sichtbarkeit, Deckkraft und unsichtbare Schnittmasken-Basen.
     */
    private fun topCustomIndex(doc: ArtDocument, layers: List<Layer>, forceVisible: Boolean): Int {
        var result = -1
        var baseVisible = false
        var hasBase = false
        for (i in layers.indices) {
            val l = layers[i]
            val vis = visible(doc, l, forceVisible)
            if (!l.clipping) { hasBase = true; baseVisible = vis }
            if (!vis || !BlendMath.isCustom(l.blend)) continue
            if (l.clipping && hasBase && !baseVisible) continue
            if (l.opacity * LayerGroups.opacity(doc, l.groupId) <= 0f) continue
            result = i
        }
        return result
    }

    /** Schnittmasken-Basis unterhalb von [index] (nächste Ebene ohne clipping), sonst null. */
    private fun baseBelow(layers: List<Layer>, index: Int): Layer? {
        var j = index - 1
        while (j >= 0) {
            if (!layers[j].clipping) return layers[j]
            j--
        }
        return null
    }

    /**
     * Zeichnet die Ebenen [from] until [to] nativ. Schnittmasken-Basen unterhalb von [from] werden
     * korrekt berücksichtigt. Eigene Mischmodi innerhalb des Bereichs werden hier nicht erwartet
     * (Aufrufer sorgen dafür); falls doch, wird die native Näherung benutzt.
     */
    private fun drawRange(
        canvas: Canvas, doc: ArtDocument, layers: List<Layer>, from: Int, to: Int,
        filter: Boolean, forceVisible: Boolean, c: Ctx,
    ) {
        if (from >= to) return
        var base: Layer? = baseBelow(layers, from)
        var baseVisible = base?.let { visible(doc, it, forceVisible) } ?: false
        for (i in from until to) {
            val l = layers[i]
            val vis = visible(doc, l, forceVisible)
            if (!l.clipping) { base = l; baseVisible = vis }
            if (!vis) continue
            val groupAlpha = LayerGroups.opacity(doc, l.groupId)
            val b = base
            if (l.clipping && b != null && b !== l) {
                if (!baseVisible) continue
                drawLayer(canvas, l, b, groupAlpha, filter, c)
            } else {
                drawLayer(canvas, l, null, groupAlpha, filter, c)
            }
        }
    }

    private fun drawLayer(canvas: Canvas, l: Layer, clipBase: Layer?, groupAlpha: Float, filter: Boolean, c: Ctx) {
        val alpha = (l.opacity * groupAlpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        if (alpha == 0) return
        val content = l.displayOverride ?: l.bitmap
        val override = l.drawOverride
        val mode = blendModeOf(l.blend)

        // Schneller Weg: ein einziger drawBitmap
        if (override == null && l.mask == null && clipBase == null) {
            val p = c.direct
            p.alpha = alpha
            p.blendMode = mode
            p.isFilterBitmap = filter
            canvas.drawBitmap(content, 0f, 0f, p)
            return
        }

        val lp = c.layer
        lp.alpha = alpha
        lp.blendMode = mode
        val sc = canvas.saveLayer(null, lp)
        drawContentInLayer(canvas, l, clipBase, filter, c)
        canvas.restoreToCount(sc)
    }

    /** Inhalt + Maske + Schnittmaske in die aktuelle (Offscreen-)Ebene. */
    private fun drawContentInLayer(canvas: Canvas, l: Layer, clipBase: Layer?, filter: Boolean, c: Ctx) {
        val override = l.drawOverride
        if (override != null) {
            override(canvas)
        } else {
            c.plain.isFilterBitmap = filter
            canvas.drawBitmap(l.displayOverride ?: l.bitmap, 0f, 0f, c.plain)
        }
        l.mask?.let { applyMask(canvas, it, filter, c) }
        if (clipBase != null) applyClipBase(canvas, clipBase, filter, c)
    }

    /** Multipliziert den Inhalt der aktuellen Offscreen-Ebene mit der Maske. */
    private fun applyMask(canvas: Canvas, mask: Bitmap, filter: Boolean, c: Ctx) {
        val p = if (mask.config == Bitmap.Config.ALPHA_8) c.dstIn else c.dstInLuma
        p.isFilterBitmap = filter
        canvas.drawBitmap(mask, 0f, 0f, p)
    }

    /** Beschneidet den Inhalt der aktuellen Offscreen-Ebene auf das sichtbare Alpha der Basis. */
    private fun applyClipBase(canvas: Canvas, base: Layer, filter: Boolean, c: Ctx) {
        val content = base.displayOverride ?: base.bitmap
        val override = base.drawOverride
        if (override == null && base.mask == null) {
            c.dstIn.isFilterBitmap = filter
            canvas.drawBitmap(content, 0f, 0f, c.dstIn)
            return
        }
        val sc = canvas.saveLayer(null, c.dstInLayer)
        if (override != null) override(canvas) else {
            c.plain.isFilterBitmap = filter
            canvas.drawBitmap(content, 0f, 0f, c.plain)
        }
        base.mask?.let { applyMask(canvas, it, filter, c) }
        canvas.restoreToCount(sc)
    }

    // ------------------------------------------------------------------------------------------
    // Software-Zusammensetzung (exakt, CPU)
    // ------------------------------------------------------------------------------------------

    /**
     * Setzt Hintergrund und die Ebenen 0 until [count] in [out] zusammen. [out] bildet den Dokument-
     * ausschnitt ab ([ox], [oy]) mit der Größe von [out] im Maßstab 1:1 ab. Native Modi werden per Canvas
     * gezeichnet, eigene Modi per [BlendMath.blendInto].
     */
    private fun composeSoftware(
        out: Bitmap, ox: Int, oy: Int, doc: ArtDocument, layers: List<Layer>, count: Int,
        background: Boolean, forceVisible: Boolean, c: Ctx,
    ) {
        out.eraseColor(Color.TRANSPARENT)
        val cv = Canvas(out)
        cv.translate(-ox.toFloat(), -oy.toFloat())
        if (background) cv.drawColor(doc.backgroundColor)
        var base: Layer? = null
        var baseVisible = false
        for (i in 0 until count.coerceAtMost(layers.size)) {
            val l = layers[i]
            val vis = visible(doc, l, forceVisible)
            if (!l.clipping) { base = l; baseVisible = vis }
            if (!vis) continue
            val groupAlpha = LayerGroups.opacity(doc, l.groupId)
            val b = base
            val clipBase = if (l.clipping && b != null && b !== l) b else null
            if (clipBase != null && !baseVisible) continue
            if (!BlendMath.isCustom(l.blend)) {
                drawLayer(cv, l, clipBase, groupAlpha, false, c)
                continue
            }
            val alpha = l.opacity * groupAlpha
            if (alpha <= 0f) continue
            // Ebeneninhalt (mit Maske/Schnittmaske, volle Deckkraft) in eine Arbeitsbitmap gleicher Größe
            val tmp = c.content.obtain(out.width, out.height)
            tmp.eraseColor(Color.TRANSPARENT)
            val tc = Canvas(tmp)
            tc.translate(-ox.toFloat(), -oy.toFloat())
            if (l.drawOverride == null && l.mask == null && clipBase == null) {
                c.plain.isFilterBitmap = false
                tc.drawBitmap(l.displayOverride ?: l.bitmap, 0f, 0f, c.plain)
            } else {
                val sc = tc.saveLayer(null, null)
                drawContentInLayer(tc, l, clipBase, false, c)
                tc.restoreToCount(sc)
            }
            BlendMath.blendInto(out, tmp, l.blend, alpha)
        }
    }

    // ------------------------------------------------------------------------------------------
    // GPU-Zusammensetzung (Android 13+, nur Hauptthread)
    // ------------------------------------------------------------------------------------------

    private var gpuInstance: Any? = null
    @Volatile private var gpuFailed = false

    @RequiresApi(33)
    private fun gpu(): Gpu = (gpuInstance as? Gpu) ?: Gpu().also { gpuInstance = it }

    /**
     * Verschachtelte RenderNodes: Node i enthält Node i−1 (bzw. den Hintergrund) und die nativen Ebenen bis
     * zur i-ten eigenen Ebene; sein RenderEffect mischt diese Ebene darauf. Je Stufe eigene Shader und
     * Arbeitsbitmaps, weil die RenderEffects bis zum Zeichnen des Bildes darauf verweisen.
     */
    @RequiresApi(33)
    private class GpuSlot {
        val node = RenderNode("blend")
        val shader = BlendShader()
        val src = Scratch()
        val clip = Scratch()
    }

    @RequiresApi(33)
    private class Gpu {
        private val slots = ArrayList<GpuSlot>()

        private fun slot(i: Int): GpuSlot {
            while (slots.size <= i) slots += GpuSlot()
            return slots[i]
        }

        fun draw(canvas: Canvas, doc: ArtDocument, filter: Boolean, c: Ctx) {
            val layers = doc.layers
            val w = doc.width
            val h = doc.height
            var prev: RenderNode? = null
            var start = 0
            var used = 0
            var base: Layer? = null
            var baseVisible = false
            for (i in layers.indices) {
                val l = layers[i]
                val vis = visible(doc, l, false)
                if (!l.clipping) { base = l; baseVisible = vis }
                if (!vis || !BlendMath.isCustom(l.blend)) continue
                val b = base
                val clipBase = if (l.clipping && b != null && b !== l) b else null
                if (clipBase != null && !baseVisible) continue
                val alpha = l.opacity * LayerGroups.opacity(doc, l.groupId)
                if (alpha <= 0f) continue

                val s = slot(used++)
                val n = s.node
                n.setPosition(0, 0, w, h)
                val rc = n.beginRecording(w, h)
                try {
                    rc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), c.keepAlive)
                    if (prev == null) {
                        if (doc.backgroundVisible) rc.drawColor(doc.backgroundColor)
                    } else rc.drawRenderNode(prev)
                    drawRange(rc, doc, layers, start, i, filter, false, c)
                } finally {
                    n.endRecording()
                }

                // Ebeneninhalt: direkt die Bitmap, mit drawOverride (laufender Strich, Transformieren) vorbereitet
                val override = l.drawOverride
                val srcBmp = if (override == null) l.displayOverride ?: l.bitmap else {
                    val t = s.src.obtain(w, h)
                    t.eraseColor(Color.TRANSPARENT)
                    override(Canvas(t))
                    t
                }
                val clipBmp = clipBase?.let { cb ->
                    if (cb.drawOverride == null && cb.mask == null) cb.displayOverride ?: cb.bitmap else {
                        val t = s.clip.obtain(w, h)
                        t.eraseColor(Color.TRANSPARENT)
                        val tc = Canvas(t)
                        val sc = tc.saveLayer(null, null)
                        drawContentInLayer(tc, cb, null, false, c)
                        tc.restoreToCount(sc)
                        t
                    }
                }
                val mask = l.mask
                n.setRenderEffect(s.shader.effect(
                    srcBmp, mask, mask?.config == Bitmap.Config.ALPHA_8, clipBmp, l.blend, alpha, filter,
                ))
                prev = n
                start = i + 1
            }
            val last = prev
            if (last == null) {
                // Sollte nicht vorkommen (Aufrufer prüft) – normal zeichnen
                render(canvas, doc, doc.backgroundVisible, filter, c)
                return
            }
            canvas.drawRenderNode(last)
            drawRange(canvas, doc, layers, start, layers.size, filter, false, c)
            // Nicht mehr benötigte Stufen freigeben (Speicher der Arbeitsbitmaps)
            for (k in used until slots.size) { slots[k].src.drop(); slots[k].clip.drop() }
            while (slots.size > used + 2) slots.removeAt(slots.lastIndex).node.discardDisplayList()
        }
    }
}
