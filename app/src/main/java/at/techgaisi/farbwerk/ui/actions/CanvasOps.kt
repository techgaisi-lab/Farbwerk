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

    /** Für Overlays aus diesem Paket: laufende Modi beenden. */
    fun finishModesPublic(state: EditorState) = finishModes(state)

    /**
     * Neue Leinwandgröße. [anchorX]/[anchorY] ∈ {0,1,2} (links/Mitte/rechts bzw. oben/Mitte/unten).
     * [scaleContent] = Inhalt mitskalieren (Bildgröße) statt zuschneiden/erweitern (Leinwandgröße).
     * Erzeugt ein neues Dokument mit gleicher ID; der Verlauf wird dabei geleert.
     */
    fun resizeCanvas(state: EditorState, newW: Int, newH: Int, anchorX: Int, anchorY: Int, scaleContent: Boolean) {
        val old = state.document ?: return
        if (newW == old.width && newH == old.height) return
        val dx = if (scaleContent) 0f else (newW - old.width) * anchorX / 2f
        val dy = if (scaleContent) 0f else (newH - old.height) * anchorY / 2f
        val sx = newW / old.width.toFloat(); val sy = newH / old.height.toFloat()
        val m = Matrix().apply { if (scaleContent) setScale(sx, sy) else setTranslate(dx.toInt().toFloat(), dy.toInt().toFloat()) }
        transformCanvas(state, m, newW, newH, old.dpi, "Leinwand")
    }

    /**
     * Allgemeines Zuschneiden/Skalieren/Drehen der Leinwand: jede Ebene (und Maske) wird mit [m]
     * (Dokument → neues Dokument) in eine neue Bitmap [newW]×[newH] gezeichnet. Zeichenhilfen, Textebenen,
     * Gruppen und Animationsdaten werden mitgeführt. Der Verlauf wird geleert (neues Dokument, gleiche ID).
     */
    fun transformCanvas(state: EditorState, m: Matrix, newW: Int, newH: Int, dpi: Int, label: String) {
        val old = state.document ?: return
        finishModes(state)
        if (newW <= 0 || newH <= 0) return
        if (newW == old.width && newH == old.height && m.isIdentity) {
            if (dpi != old.dpi) { old.dpi = dpi; state.markDirty(); state.invalidate() }
            return
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
        val doc = ArtDocument(old.name, newW, newH, old.id)
        doc.dpi = dpi
        doc.backgroundColor = old.backgroundColor
        doc.backgroundVisible = old.backgroundVisible
        doc.flipHorizontal = old.flipHorizontal
        doc.flipVertical = old.flipVertical
        doc.created = old.created
        doc.modified = System.currentTimeMillis()
        doc.animation = old.animation
        copyGuides(old, doc, m)
        try {
            buildResizedLayers(old, doc, newW, newH, m, paint)
        } catch (oom: OutOfMemoryError) {
            // Alte und neue Ebenen gleichzeitig passen nicht in den Speicher → Dokument unverändert lassen
            doc.layers.forEach { it.bitmap.recycle(); it.mask?.recycle() }
            state.flash("Zu wenig Speicher für diese Leinwandgröße")
            return
        }
        // Gruppen-Metadaten übernehmen (gleiche IDs)
        old.layers.mapNotNull { it.groupId }.distinct().forEach { gid ->
            at.techgaisi.farbwerk.render.LayerGroups.find(old, gid)?.let { at.techgaisi.farbwerk.render.LayerGroups.put(doc, it) }
        }
        doc.activeIndex = old.activeIndex.coerceIn(0, doc.layers.lastIndex)
        doc.secondarySelection.addAll(old.secondarySelection)
        doc.dirty = true
        state.openDocument(doc)
        state.flash("$label: $newW × $newH px")
    }

    private fun buildResizedLayers(old: ArtDocument, doc: ArtDocument, newW: Int, newH: Int, m: Matrix, paint: Paint) {
        for (l in old.layers) {
            val nb = Bitmap.createBitmap(newW, newH, Bitmap.Config.ARGB_8888)
            val nl = Layer(l.name, nb, l.id)
            val td = l.text as? at.techgaisi.farbwerk.text.TextData
            if (td != null) {
                // Text bleibt Vektor: Matrix erweitern und neu setzen
                val t = td.copy()
                val tm = t.matrix(); tm.postConcat(m); t.setMatrix(tm)
                nl.text = t
                at.techgaisi.farbwerk.text.TextRenderer.render(t, nb)
            } else {
                Canvas(nb).drawBitmap(l.bitmap, m, paint)
            }
            nl.visible = l.visible; nl.opacity = l.opacity; nl.blend = l.blend
            nl.alphaLock = l.alphaLock; nl.clipping = l.clipping; nl.locked = l.locked
            nl.isReference = l.isReference; nl.groupId = l.groupId
            nl.assisted = l.assisted; nl.isPrivate = l.isPrivate
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
    }

    private fun copyGuides(old: ArtDocument, doc: ArtDocument, m: Matrix) {
        val a = old.guides; val b = doc.guides
        b.kind = a.kind; b.visible = a.visible; b.color = a.color; b.opacity = a.opacity
        b.symmetry = a.symmetry; b.rotationalSymmetry = a.rotationalSymmetry; b.radialSegments = a.radialSegments
        b.assisted = a.assisted; b.thickness = a.thickness
        val v = FloatArray(9); m.getValues(v)
        val scale = kotlin.math.hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
        b.gridSize = (a.gridSize * scale).coerceAtLeast(2f)
        b.rotation = a.rotation + Math.toDegrees(kotlin.math.atan2(v[Matrix.MSKEW_Y].toDouble(), v[Matrix.MSCALE_X].toDouble())).toFloat()
        val p = FloatArray(2)
        fun map(x: Float, y: Float): FloatArray { p[0] = x; p[1] = y; m.mapPoints(p); return p }
        // Rasterursprung mitführen
        val o = map(old.width / 2f + a.offsetX, old.height / 2f + a.offsetY)
        b.offsetX = o[0] - doc.width / 2f; b.offsetY = o[1] - doc.height / 2f
        if (!a.symmetryCenterX.isNaN()) {
            val c = map(a.symmetryCenterX, a.symmetryCenterY); b.symmetryCenterX = c[0]; b.symmetryCenterY = c[1]
        }
        a.vanishingPoints.forEach { vp -> val q = map(vp.x, vp.y); b.vanishingPoints.add(android.graphics.PointF(q[0], q[1])) }
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
        if (horizontal) { g.offsetX = -g.offsetX; g.rotation = 180f - g.rotation } else { g.offsetY = -g.offsetY; g.rotation = -g.rotation }
        g.vanishingPoints.forEach { if (horizontal) it.x = doc.width - it.x else it.y = doc.height - it.y }
        // Textebenen bleiben Vektor: Spiegelung in die Textmatrix übernehmen
        for (l in doc.layers) (l.text as? at.techgaisi.farbwerk.text.TextData)?.let { t ->
            val tm = t.matrix(); tm.postConcat(m); t.setMatrix(tm); t.renderedGeneration = l.bitmap.generationId
        }
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
