package at.techgaisi.farbwerk.ui.actions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.undo.LambdaAction
import kotlin.math.ceil
import kotlin.math.max

/** Leinwand-Operationen des Aktionen-Panels (Größe ändern, Spiegeln, Text). */
object CanvasOps {

    /** Beendet laufende Transformationen/Anpassungen, bevor die ganze Leinwand verändert wird. */
    private fun finishModes(state: EditorState) {
        if (state.tool == Tool.TRANSFORM || state.tool == Tool.ADJUST) state.selectTool(Tool.PAINT)
    }

    /**
     * Neue Leinwandgröße. [anchorX]/[anchorY] ∈ {0,1,2} (links/Mitte/rechts bzw. oben/Mitte/unten).
     * [scaleContent] = Inhalt mitskalieren (Bildgröße) statt zuschneiden/erweitern (Leinwandgröße).
     * Erzeugt ein neues Dokument mit gleicher ID; der Verlauf wird dabei geleert.
     */
    fun resizeCanvas(state: EditorState, newW: Int, newH: Int, anchorX: Int, anchorY: Int, scaleContent: Boolean) {
        val old = state.document ?: return
        finishModes(state)
        if (newW == old.width && newH == old.height) return
        val dx = if (scaleContent) 0f else (newW - old.width) * anchorX / 2f
        val dy = if (scaleContent) 0f else (newH - old.height) * anchorY / 2f
        val sx = newW / old.width.toFloat(); val sy = newH / old.height.toFloat()
        val m = Matrix().apply { if (scaleContent) setScale(sx, sy) else setTranslate(dx.toInt().toFloat(), dy.toInt().toFloat()) }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }

        val doc = ArtDocument(old.name, newW, newH, old.id)
        doc.dpi = old.dpi
        doc.backgroundColor = old.backgroundColor
        doc.backgroundVisible = old.backgroundVisible
        doc.flipHorizontal = old.flipHorizontal
        doc.flipVertical = old.flipVertical
        doc.created = old.created
        doc.modified = System.currentTimeMillis()
        copyGuides(old, doc, if (scaleContent) null else m, if (scaleContent) sx else 1f, if (scaleContent) sy else 1f)
        for (l in old.layers) {
            val nb = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
            Canvas(nb).drawBitmap(l.bitmap, m, paint)
            val nl = Layer(l.name, nb, l.id)
            nl.visible = l.visible; nl.opacity = l.opacity; nl.blend = l.blend
            nl.alphaLock = l.alphaLock; nl.clipping = l.clipping; nl.locked = l.locked
            nl.isReference = l.isReference; nl.groupId = l.groupId
            l.mask?.let { mask ->
                val cfg = if (mask.config == Bitmap.Config.ALPHA_8) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888
                val nm = Bitmap.createBitmap(newW, newH, cfg)
                val c = Canvas(nm)
                c.drawColor(Color.WHITE)   // neuer Bereich sichtbar
                c.drawBitmap(mask, m, paint)
                nl.mask = nm
            }
            doc.layers.add(nl)
        }
        // Gruppen-Metadaten übernehmen (gleiche IDs)
        old.layers.mapNotNull { it.groupId }.distinct().forEach { gid ->
            at.techgaisi.farbwerk.render.LayerGroups.find(old, gid)?.let { at.techgaisi.farbwerk.render.LayerGroups.put(doc, it) }
        }
        doc.activeIndex = old.activeIndex.coerceIn(0, doc.layers.lastIndex)
        doc.secondarySelection.addAll(old.secondarySelection)
        doc.dirty = true
        state.openDocument(doc)
        state.flash("Leinwand: $newW × $newH px")
    }

    private fun copyGuides(old: ArtDocument, doc: ArtDocument, translate: Matrix?, sx: Float, sy: Float) {
        val a = old.guides; val b = doc.guides
        b.kind = a.kind; b.visible = a.visible; b.gridSize = a.gridSize; b.color = a.color; b.opacity = a.opacity
        b.symmetry = a.symmetry; b.rotationalSymmetry = a.rotationalSymmetry; b.radialSegments = a.radialSegments
        b.assisted = a.assisted
        if (!a.symmetryCenterX.isNaN()) {
            val v = FloatArray(9); translate?.getValues(v)
            b.symmetryCenterX = a.symmetryCenterX * sx + (if (translate != null) v[Matrix.MTRANS_X] else 0f)
            b.symmetryCenterY = a.symmetryCenterY * sy + (if (translate != null) v[Matrix.MTRANS_Y] else 0f)
        }
    }

    /**
     * Spiegelt alle Ebenen (inkl. Masken und Auswahl) tatsächlich – wie "Leinwand spiegeln".
     * Rückgängig = erneut spiegeln (kein zusätzlicher Speicher).
     */
    fun flipCanvas(state: EditorState, horizontal: Boolean) {
        val doc = state.document ?: return
        finishModes(state)
        applyFlip(doc, horizontal)
        val label = if (horizontal) "Leinwand horizontal spiegeln" else "Leinwand vertikal spiegeln"
        state.undo.push(LambdaAction(label, onUndo = { applyFlip(doc, horizontal) }, onRedo = { applyFlip(doc, horizontal) }))
        state.invalidate()
        state.flash(label)
    }

    private fun applyFlip(doc: ArtDocument, horizontal: Boolean) {
        val m = Matrix().apply {
            if (horizontal) setScale(-1f, 1f, doc.width / 2f, doc.height / 2f)
            else setScale(1f, -1f, doc.width / 2f, doc.height / 2f)
        }
        val src = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        var tmpArgb: Bitmap? = null
        fun flipBitmap(b: Bitmap) {
            val t: Bitmap
            if (b.config == Bitmap.Config.ARGB_8888) {
                val cur = tmpArgb
                t = if (cur != null && cur.width == b.width && cur.height == b.height) cur
                else Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { tmpArgb?.recycle(); tmpArgb = it }
                Canvas(t).drawBitmap(b, 0f, 0f, src)
            } else {
                t = b.copy(b.config ?: Bitmap.Config.ALPHA_8, false)
            }
            Canvas(b).drawBitmap(t, m, src)
            if (t !== tmpArgb) t.recycle()
        }
        for (l in doc.layers) {
            flipBitmap(l.bitmap)
            l.mask?.let { if (it.isMutable) flipBitmap(it) }
        }
        doc.selection?.let { sel ->
            if (sel.mask.isMutable) flipBitmap(sel.mask)
            sel.path?.transform(m)
        }
        tmpArgb?.recycle()
        val g = doc.guides
        if (horizontal && !g.symmetryCenterX.isNaN()) g.symmetryCenterX = doc.width - g.symmetryCenterX
        if (!horizontal && !g.symmetryCenterY.isNaN()) g.symmetryCenterY = doc.height - g.symmetryCenterY
    }

    /**
     * Geschätzte maximale Ebenenanzahl für eine Leinwandgröße – gleiche Formel wie
     * LayerOps.maxLayers (30 % des Gerätespeichers), aber ohne offenes Dokument nutzbar.
     */
    fun maxLayers(context: android.content.Context, w: Int, h: Int): Int {
        val perLayer = w.toLong() * h * 4
        if (perLayer <= 0) return 0
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val total = am?.let { android.app.ActivityManager.MemoryInfo().also(it::getMemoryInfo).totalMem } ?: (2L shl 30)
        return ((total * 0.3) / perLayer).toInt().coerceIn(2, 999)
    }

    // ------------------------------------------------------------------ Text

    enum class FontChoice(val label: String, val typeface: Typeface) {
        SANS("Sans", Typeface.SANS_SERIF),
        SERIF("Serif", Typeface.SERIF),
        MONO("Mono", Typeface.MONOSPACE),
        CONDENSED("Schmal", Typeface.create("sans-serif-condensed", Typeface.NORMAL)),
        CURSIVE("Schreibschrift", Typeface.create("cursive", Typeface.NORMAL)),
    }

    /** Rendert mehrzeiligen Text als Bitmap (passend zugeschnitten, mit Rand für Antialiasing). */
    fun renderText(text: String, font: FontChoice, sizePx: Float, color: Int, bold: Boolean, italic: Boolean,
                   align: Layout.Alignment, maxWidth: Int): Bitmap {
        val style = when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            textSize = sizePx
            typeface = Typeface.create(font.typeface, style)
        }
        val natural = text.split('\n').maxOf { ceil(tp.measureText(it)).toInt() }
        val w = natural.coerceIn(1, max(1, maxWidth))
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, w)
            .setAlignment(align).setIncludePad(true).build()
        val pad = (sizePx * 0.15f).toInt() + 2
        val bmp = Bitmap.createBitmap(w + pad * 2, layout.height + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(pad.toFloat(), pad.toFloat())
        layout.draw(c)
        return bmp
    }

    /** Text als neue Ebene in der Bildmitte (Undo-fähig), danach Transformieren zum Platzieren. */
    fun addTextLayer(state: EditorState, text: String, font: FontChoice, sizePx: Float, color: Int,
                     bold: Boolean, italic: Boolean, align: Layout.Alignment) {
        val doc = state.document ?: return
        if (text.isBlank()) return
        val img = renderText(text, font, sizePx, color, bold, italic, align, doc.width)
        val layerBmp = doc.newLayerBitmap()
        Canvas(layerBmp).drawBitmap(img, (doc.width - img.width) / 2f, (doc.height - img.height) / 2f, null)
        img.recycle()
        val name = text.lineSequence().first().take(30).ifBlank { "Text" }
        if (at.techgaisi.farbwerk.io.Importers.addLayer(state, Layer(name, layerBmp), "Text hinzufügen")) {
            state.selectTool(Tool.TRANSFORM)
        }
    }
}
