package at.techgaisi.farbwerk.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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
 * "Linear nachbelichten" gibt es in android.graphics.BlendMode nicht. Bei deckendem Untergrund (sichtbare
 * Hintergrundfarbe) wird es exakt nachgebildet:  ergebnis = 1 − ((1 − unten) + α·(1 − oben))
 * (Untergrund invertieren → invertierte Ebene addieren → zurück invertieren). Bei transparentem Untergrund
 * wird als nächste Näherung "Multiplizieren" verwendet.
 */
object Compositor {

    /** Wiederverwendbare Zeichenobjekte (je Thread, da flatten auch im Hintergrund laufen kann). */
    private class Ctx {
        /** Direktes Zeichnen einer Ebene (Alpha + BlendMode). */
        val direct = Paint()
        /** Paint beim Zurückschreiben einer Offscreen-Ebene (Alpha, BlendMode, ggf. Farbfilter). */
        val layer = Paint()
        /** Inhalt innerhalb einer Offscreen-Ebene. */
        val plain = Paint()
        /** Beschneiden mit Alpha (Masken als ALPHA_8 und Schnittmasken-Basis). */
        val dstIn = Paint().apply { blendMode = BlendMode.DST_IN }
        /** Offscreen-Ebene für eine Schnittmasken-Basis mit Maske/drawOverride. */
        val dstInLayer = Paint().apply { blendMode = BlendMode.DST_IN }
        /** Beschneiden mit der Helligkeit einer ARGB-Maske (weiß = sichtbar). */
        val dstInLuma = Paint().apply { blendMode = BlendMode.DST_IN; colorFilter = LUMA_TO_ALPHA }
        val matrix = Matrix()
        val values = FloatArray(9)
        val pixel: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val pixelCanvas = Canvas(pixel)
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

    /** Abbildung der Ebenen-Mischmodi auf Android. [LayerBlend.LINEAR_BURN] wird gesondert behandelt. */
    fun blendModeOf(b: LayerBlend): BlendMode = when (b) {
        LayerBlend.NORMAL -> BlendMode.SRC_OVER
        LayerBlend.MULTIPLY -> BlendMode.MULTIPLY
        LayerBlend.DARKEN -> BlendMode.DARKEN
        LayerBlend.COLOR_BURN -> BlendMode.COLOR_BURN
        LayerBlend.LINEAR_BURN -> BlendMode.MULTIPLY
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
    }

    /** Zeichnet das ganze Dokument (inkl. Hintergrund, falls sichtbar) in Dokument-Koordinaten. */
    fun draw(canvas: Canvas, doc: ArtDocument) {
        val c = ctx.get()!!
        render(canvas, doc, doc.backgroundVisible, filterFor(canvas, c), c)
    }

    /** Liefert das zusammengefügte Bild in Dokumentgröße. */
    fun flatten(doc: ArtDocument, includeBackground: Boolean = true): Bitmap {
        val b = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        render(Canvas(b), doc, includeBackground && doc.backgroundVisible, false, ctx.get()!!)
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
        drawLayers(canvas, doc, layers, bgOpaque = false, filter = false, forceVisible = forceVisible, c = ctx.get()!!)
    }

    // ------------------------------------------------------------------------------------------

    private fun render(canvas: Canvas, doc: ArtDocument, background: Boolean, filter: Boolean, c: Ctx) {
        if (background) canvas.drawColor(doc.backgroundColor)
        val opaque = background && Color.alpha(doc.backgroundColor) == 255
        drawLayers(canvas, doc, doc.layers, opaque, filter, forceVisible = false, c = c)
    }

    /** Interpolation nur, solange nicht stark hineingezoomt ist (dann sollen Pixel scharf erscheinen). */
    @Suppress("DEPRECATION")
    private fun filterFor(canvas: Canvas, c: Ctx): Boolean {
        canvas.getMatrix(c.matrix)
        c.matrix.getValues(c.values)
        val scale = hypot(c.values[Matrix.MSCALE_X], c.values[Matrix.MSKEW_Y])
        return scale < PIXEL_ZOOM
    }

    private fun drawLayers(
        canvas: Canvas, doc: ArtDocument, layers: List<Layer>,
        bgOpaque: Boolean, filter: Boolean, forceVisible: Boolean, c: Ctx,
    ) {
        var base: Layer? = null
        var baseVisible = false
        for (i in layers.indices) {
            val l = layers[i]
            val vis = forceVisible || (l.visible && LayerGroups.isVisible(doc, l.groupId))
            if (!l.clipping) { base = l; baseVisible = vis }
            if (!vis) continue
            val groupAlpha = LayerGroups.opacity(doc, l.groupId)
            if (l.clipping && base != null) {
                // Basis unsichtbar → Clipping-Ebene ebenfalls unsichtbar
                if (!baseVisible) continue
                drawLayer(canvas, l, base, groupAlpha, bgOpaque, filter, c)
            } else {
                drawLayer(canvas, l, null, groupAlpha, bgOpaque, filter, c)
            }
        }
    }

    private fun drawLayer(
        canvas: Canvas, l: Layer, clipBase: Layer?, groupAlpha: Float,
        bgOpaque: Boolean, filter: Boolean, c: Ctx,
    ) {
        val alpha = (l.opacity * groupAlpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        if (alpha == 0) return
        val content = l.displayOverride ?: l.bitmap
        val override = l.drawOverride
        val exactBurn = l.blend == LayerBlend.LINEAR_BURN && bgOpaque
        val mode = if (exactBurn) BlendMode.PLUS else blendModeOf(l.blend)

        // Schneller Weg: ein einziger drawBitmap
        if (override == null && l.mask == null && clipBase == null && !exactBurn) {
            val p = c.direct
            p.alpha = alpha
            p.blendMode = mode
            p.isFilterBitmap = filter
            canvas.drawBitmap(content, 0f, 0f, p)
            return
        }

        if (exactBurn) canvas.drawColor(Color.WHITE, BlendMode.DIFFERENCE)
        val lp = c.layer
        lp.alpha = alpha
        lp.blendMode = mode
        lp.colorFilter = if (exactBurn) INVERT_FILTER else null
        val sc = canvas.saveLayer(null, lp)
        lp.colorFilter = null
        if (override != null) {
            override(canvas)
        } else {
            c.plain.isFilterBitmap = filter
            canvas.drawBitmap(content, 0f, 0f, c.plain)
        }
        l.mask?.let { applyMask(canvas, it, filter, c) }
        if (clipBase != null) applyClipBase(canvas, clipBase, filter, c)
        canvas.restoreToCount(sc)
        if (exactBurn) canvas.drawColor(Color.WHITE, BlendMode.DIFFERENCE)
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
}
