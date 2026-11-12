package at.techgaisi.farbwerk.model

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.render.Compositor
import at.techgaisi.farbwerk.render.LayerGroup
import at.techgaisi.farbwerk.render.LayerGroups
import at.techgaisi.farbwerk.undo.LambdaAction

/**
 * Alle Ebenen-Operationen. Jede Änderung erzeugt einen Undo-Eintrag und ruft danach [EditorState.invalidate] auf.
 *
 * Strukturelle Änderungen (Hinzufügen, Löschen, Umsortieren, Zusammenfügen, Gruppieren) sichern die
 * Ebenenliste samt aktiver Ebene, Sekundärauswahl und Gruppenzugehörigkeit vor und nach der Änderung.
 * Pixeländerungen laufen über [EditorState.beginPixelEdit] (respektiert eine aktive Auswahl).
 */
object LayerOps {

    // =========================================================================================
    // Hilfen
    // =========================================================================================

    /** Abbild der Ebenenstruktur für Undo/Redo. */
    private class Structure(doc: ArtDocument) {
        val layers: List<Layer> = doc.layers.toList()
        val groupIds: List<String?> = layers.map { it.groupId }
        val clipping: List<Boolean> = layers.map { it.clipping }
        val active: Int = doc.activeIndex
        val secondary: Set<String> = doc.secondarySelection.toSet()

        fun restore(doc: ArtDocument) {
            doc.layers.clear()
            doc.layers.addAll(layers)
            layers.forEachIndexed { i, l -> l.groupId = groupIds[i]; l.clipping = clipping[i] }
            doc.activeIndex = active.coerceIn(0, (layers.size - 1).coerceAtLeast(0))
            doc.secondarySelection.clear()
            doc.secondarySelection.addAll(secondary)
        }
    }

    /**
     * Führt eine strukturelle Änderung mit Undo aus. [block] liefert false, wenn nichts geändert wurde.
     */
    private inline fun structural(
        state: EditorState, label: String, bytes: Long = 0L, block: (ArtDocument) -> Boolean,
    ): Boolean {
        val doc = state.document ?: return false
        val before = Structure(doc)
        if (!block(doc)) return false
        val after = Structure(doc)
        state.undo.push(LambdaAction(label, { before.restore(doc) }, { after.restore(doc) }, bytes))
        state.invalidate()
        return true
    }

    /** Eigenschaftsänderung einer Ebene mit Undo (setter wird mit altem bzw. neuem Wert aufgerufen). */
    private fun <T> property(state: EditorState, label: String, old: T, new: T, set: (T) -> Unit) {
        if (old == new) return
        set(new)
        state.undo.push(LambdaAction(label, { set(old) }, { set(new) }))
        state.invalidate()
    }

    private fun layerBytes(l: Layer): Long =
        l.bitmap.allocationByteCount.toLong() + (l.mask?.allocationByteCount ?: 0)

    private fun checkUnlocked(state: EditorState, l: Layer): Boolean {
        if (l.locked) { state.flash("Ebene gesperrt"); return false }
        return true
    }

    private val SRC_PAINT = Paint().apply { blendMode = BlendMode.SRC }

    /** Ebene anhand ihrer ID. */
    fun layerById(doc: ArtDocument, id: String): Layer? = doc.layers.firstOrNull { it.id == id }

    /** Nächster freier Name "Ebene N". */
    fun nextLayerName(doc: ArtDocument): String {
        val rx = Regex("^Ebene (\\d+)$")
        val max = doc.layers.maxOfOrNull { rx.find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0 } ?: 0
        return "Ebene ${maxOf(max, doc.layers.size) + 1}"
    }

    /**
     * Maximale Ebenenzahl abhängig von Dokumentgröße und Gerätespeicher.
     * Bitmaps liegen im nativen Speicher; budgetiert werden ~30 % des Gerätespeichers
     * (Undo-Kopien und Arbeitspuffer brauchen den Rest).
     */
    fun maxLayers(state: EditorState): Int {
        val doc = state.document ?: return 1
        val am = state.context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val total = am?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo).totalMem } ?: (2L shl 30)
        val perLayer = doc.width.toLong() * doc.height * 4L
        return ((total * 0.3) / perLayer.coerceAtLeast(1)).toInt().coerceIn(2, 999)
    }

    private fun canAdd(state: EditorState, count: Int = 1): Boolean {
        val doc = state.document ?: return false
        if (doc.layers.size + count > maxLayers(state)) {
            state.flash("Maximale Ebenenzahl erreicht (${maxLayers(state)})")
            return false
        }
        return true
    }

    /** Alle ausgewählten Ebenen (aktiv + sekundär), von unten nach oben. */
    fun selectedLayers(doc: ArtDocument): List<Layer> {
        val active = doc.activeLayer
        return doc.layers.filter { it === active || it.id in doc.secondarySelection }
    }

    // =========================================================================================
    // Auswahl der aktiven Ebene (kein Undo – reine Navigation)
    // =========================================================================================

    /** Macht die Ebene am Index aktiv und hebt die Sekundärauswahl auf. */
    fun setActive(state: EditorState, index: Int) {
        val doc = state.document ?: return
        if (index !in doc.layers.indices) return
        doc.activeIndex = index
        doc.secondarySelection.clear()
        state.invalidate()
    }

    /** Sekundäre Auswahl einer Ebene umschalten (nach rechts wischen). */
    fun toggleSecondary(state: EditorState, layer: Layer) {
        val doc = state.document ?: return
        if (layer === doc.activeLayer) return
        if (!doc.secondarySelection.remove(layer.id)) doc.secondarySelection.add(layer.id)
        state.invalidate()
    }

    /** Wählt alle Ebenen einer Gruppe aus (oberste wird aktiv, die übrigen sekundär). */
    fun selectGroup(state: EditorState, groupId: String) {
        val doc = state.document ?: return
        val members = doc.layers.filter { it.groupId == groupId }
        val top = members.lastOrNull() ?: return
        doc.activeIndex = doc.indexOf(top)
        doc.secondarySelection.clear()
        members.forEach { if (it !== top) doc.secondarySelection.add(it.id) }
        state.invalidate()
    }

    fun selectLayerAbove(state: EditorState) {
        val doc = state.document ?: return
        if (doc.activeIndex < doc.layers.lastIndex) setActive(state, doc.activeIndex + 1)
    }

    fun selectLayerBelow(state: EditorState) {
        val doc = state.document ?: return
        if (doc.activeIndex > 0) setActive(state, doc.activeIndex - 1)
    }

    // =========================================================================================
    // Hinzufügen / Löschen / Duplizieren
    // =========================================================================================

    /** Neue leere Ebene über der aktiven Ebene (in derselben Gruppe). */
    fun addLayer(state: EditorState) {
        if (!canAdd(state)) return
        val doc = state.document ?: return
        val l = Layer(nextLayerName(doc), doc.newLayerBitmap())
        structural(state, "Neue Ebene", layerBytes(l)) {
            val active = doc.activeLayer
            val index = if (doc.layers.isEmpty()) 0 else doc.activeIndex + 1
            l.groupId = active?.groupId
            doc.layers.add(index, l)
            doc.activeIndex = index
            doc.secondarySelection.clear()
            true
        }
    }

    /** Löscht die aktive Ebene bzw. alle ausgewählten Ebenen (mindestens eine bleibt erhalten). */
    fun deleteActiveLayer(state: EditorState) {
        val doc = state.document ?: return
        val targets = selectedLayers(doc)
        if (targets.isEmpty()) return
        deleteLayers(state, targets.map { it.id })
    }

    /** Löscht die Ebenen mit den angegebenen IDs. */
    fun deleteLayers(state: EditorState, ids: Collection<String>) {
        val doc = state.document ?: return
        val targets = doc.layers.filter { it.id in ids }
        if (targets.isEmpty()) return
        if (targets.any { it.locked }) { state.flash("Ebene gesperrt"); return }
        if (targets.size >= doc.layers.size) { state.flash("Die letzte Ebene kann nicht gelöscht werden"); return }
        val label = if (targets.size > 1) "Ebenen löschen" else "Ebene löschen"
        structural(state, label, targets.sumOf { layerBytes(it) }) {
            val activeId = doc.activeLayer?.id
            val lowest = targets.minOf { doc.indexOf(it) }
            doc.layers.removeAll { it.id in ids }
            doc.secondarySelection.removeAll(ids.toSet())
            val keep = doc.layers.indexOfFirst { it.id == activeId }
            doc.activeIndex = if (keep >= 0) keep else (lowest - 1).coerceIn(0, doc.layers.lastIndex)
            fixClipping(doc)
            true
        }
    }

    /** Dupliziert die aktive Ebene (bzw. alle ausgewählten) direkt darüber. */
    fun duplicateActiveLayer(state: EditorState) {
        val doc = state.document ?: return
        val targets = selectedLayers(doc)
        if (targets.isEmpty() || !canAdd(state, targets.size)) return
        val copies = targets.map { it to copyLayer(it) }
        structural(state, "Ebene duplizieren", copies.sumOf { layerBytes(it.second) }) {
            val activeId = doc.activeLayer?.id
            var newActive: Layer? = null
            for ((orig, copy) in copies.asReversed()) {
                doc.layers.add(doc.indexOf(orig) + 1, copy)
                if (orig.id == activeId) newActive = copy
            }
            doc.secondarySelection.clear()
            copies.forEach { (_, c) -> if (c !== newActive) doc.secondarySelection.add(c.id) }
            doc.activeIndex = newActive?.let { doc.indexOf(it) } ?: doc.activeIndex
            true
        }
    }

    /** Erstellt eine unabhängige Kopie einer Ebene (Pixel, Maske, Eigenschaften). */
    fun copyLayer(src: Layer): Layer {
        val l = Layer(src.name, src.bitmap.copy(Bitmap.Config.ARGB_8888, true))
        l.visible = src.visible
        l.opacity = src.opacity
        l.blend = src.blend
        l.alphaLock = src.alphaLock
        l.clipping = src.clipping
        l.groupId = src.groupId
        l.mask = src.mask?.let { it.copy(it.config ?: Bitmap.Config.ALPHA_8, true) }
        l.assisted = src.assisted
        l.isPrivate = src.isPrivate
        l.isReference = false
        l.text = src.text
        return l
    }

    // =========================================================================================
    // Zusammenfügen
    // =========================================================================================

    /** Rendert Ebenen (mit Mischmodi, Masken, Schnittmasken) in eine neue Bitmap. */
    private fun renderMerged(doc: ArtDocument, layers: List<Layer>): Bitmap {
        val b = doc.newLayerBitmap()
        Compositor.renderLayers(Canvas(b), doc, layers, forceVisible = true)
        return b
    }

    /** Fügt die aktive Ebene mit der darunterliegenden zusammen. */
    fun mergeDown(state: EditorState) {
        val doc = state.document ?: return
        val i = doc.activeIndex
        if (i <= 0 || i > doc.layers.lastIndex) { state.flash("Keine Ebene darunter"); return }
        mergeLayers(state, listOf(doc.layers[i - 1].id, doc.layers[i].id), "Nach unten zusammenfügen")
    }

    /**
     * Fügt mehrere Ebenen zu einer zusammen (Zusammenkneifen-Geste). Das Ergebnis liegt an der Position der
     * obersten beteiligten Ebene, trägt den Namen der untersten und ist Normal/100 %.
     */
    fun mergeLayers(
        state: EditorState, ids: Collection<String>, label: String = "Ebenen zusammenfügen",
        resultName: String? = null, keepGroup: Boolean = true,
    ) {
        val doc = state.document ?: return
        val targets = doc.layers.filter { it.id in ids }
        if (targets.size < 2) return
        if (targets.any { it.locked }) { state.flash("Ebene gesperrt"); return }
        val bottom = targets.first()
        val merged = Layer(resultName ?: bottom.name, renderMerged(doc, targets))
        merged.groupId = if (keepGroup) bottom.groupId else null
        structural(state, label, targets.sumOf { layerBytes(it) } + layerBytes(merged)) {
            val topIndex = targets.maxOf { doc.indexOf(it) }
            doc.layers.add(topIndex + 1, merged)
            doc.layers.removeAll { it.id in ids }
            doc.secondarySelection.clear()
            doc.activeIndex = doc.indexOf(merged)
            fixClipping(doc)
            true
        }
    }

    /** Reduziert alle sichtbaren Ebenen auf eine einzige (ohne Hintergrund). Ausgeblendete werden verworfen. */
    fun flattenAll(state: EditorState) {
        val doc = state.document ?: return
        if (doc.layers.size < 2 && doc.layers.firstOrNull()?.let { it.opacity == 1f && it.blend == LayerBlend.NORMAL && it.mask == null } != false) return
        if (doc.layers.any { it.locked }) { state.flash("Gesperrte Ebenen vorhanden"); return }
        val b = doc.newLayerBitmap()
        Compositor.renderLayers(Canvas(b), doc, doc.layers, forceVisible = false)
        val flat = Layer(doc.layers.first().name, b)
        structural(state, "Reduzieren", doc.layers.sumOf { layerBytes(it) } + layerBytes(flat)) {
            doc.layers.clear()
            doc.layers.add(flat)
            doc.activeIndex = 0
            doc.secondarySelection.clear()
            true
        }
    }

    // =========================================================================================
    // Pixel-Operationen
    // =========================================================================================

    /** Löscht den Inhalt der aktiven Ebene (bzw. nur innerhalb der Auswahl). */
    fun clearActiveLayer(state: EditorState) {
        val l = state.document?.activeLayer ?: return
        if (!checkUnlocked(state, l)) return
        // Wird die Maske bearbeitet, setzt "Löschen" sie auf Weiß (alles sichtbar) zurück
        val target = paintTarget(state) ?: return
        val e = state.beginPixelEdit(target)
        if (target !== l) target.bitmap.eraseColor(Color.WHITE) else target.bitmap.eraseColor(Color.TRANSPARENT)
        // PixelEdit beschneidet auf eine aktive Auswahl → Schrubben/Löschen wirkt nur innerhalb der Auswahl
        e.commit(null, if (target !== l) "Maske löschen" else "Ebene löschen")
    }

    /** Füllt die Ebene mit der aktuellen Farbe (Alpha-Sperre und Auswahl werden respektiert). */
    fun fillLayer(state: EditorState, layer: Layer? = state.document?.activeLayer) {
        val l = layer ?: return
        if (!checkUnlocked(state, l)) return
        if (l === state.document?.activeLayer && editingMaskLayer(state) === l) {
            val m = paintTarget(state) ?: return
            val e = state.beginPixelEdit(m)
            Canvas(m.bitmap).drawColor(paintColor(state, state.color), BlendMode.SRC)
            e.commit(null, "Maske füllen")
            return
        }
        val e = state.beginPixelEdit(l)
        val c = Canvas(l.bitmap)
        if (l.alphaLock) c.drawColor(state.color, BlendMode.SRC_ATOP) else c.drawColor(state.color, BlendMode.SRC)
        e.commit(null, "Ebene füllen")
    }

    /** Kehrt die Farben der Ebene um (Alpha bleibt). */
    fun invert(state: EditorState, layer: Layer? = state.document?.activeLayer) {
        val l = layer ?: return
        if (!checkUnlocked(state, l)) return
        if (l === state.document?.activeLayer && editingMaskLayer(state) === l) { invertMask(state, l); return }
        val e = state.beginPixelEdit(l)
        val p = Paint().apply { blendMode = BlendMode.SRC; colorFilter = Compositor.INVERT_FILTER }
        Canvas(l.bitmap).drawBitmap(e.original, 0f, 0f, p)
        e.commit(null, "Umkehren")
    }

    // =========================================================================================
    // Eigenschaften
    // =========================================================================================

    fun toggleAlphaLock(state: EditorState) {
        state.document?.activeLayer?.let { toggleAlphaLock(state, it) }
    }

    fun toggleAlphaLock(state: EditorState, l: Layer) {
        property(state, if (l.alphaLock) "Alpha-Sperre aus" else "Alpha-Sperre", l.alphaLock, !l.alphaLock) { l.alphaLock = it }
        state.flash(if (l.alphaLock) "Alpha-Sperre an" else "Alpha-Sperre aus")
    }

    fun setBlend(state: EditorState, l: Layer, blend: LayerBlend) =
        property(state, "Mischmodus", l.blend, blend) { l.blend = it }

    /** Deckkraft sofort mit Undo setzen. */
    fun setOpacity(state: EditorState, l: Layer, value: Float) =
        property(state, "Deckkraft", l.opacity, value.coerceIn(0f, 1f)) { l.opacity = it }

    /** Deckkraft während des Ziehens setzen (ohne Undo). Danach [commitOpacity] aufrufen. */
    fun setOpacityLive(state: EditorState, l: Layer, value: Float) {
        l.opacity = value.coerceIn(0f, 1f)
        state.invalidate()
    }

    /** Schließt das Ziehen des Deckkraftreglers ab und legt einen Undo-Eintrag an. */
    fun commitOpacity(state: EditorState, l: Layer, startValue: Float) {
        val end = l.opacity
        if (end == startValue) return
        state.undo.push(LambdaAction("Deckkraft", { l.opacity = startValue }, { l.opacity = end }))
        state.invalidate()
    }

    fun toggleVisible(state: EditorState, l: Layer) =
        property(state, if (l.visible) "Ebene ausblenden" else "Ebene einblenden", l.visible, !l.visible) { l.visible = it }

    /** Schnittmaske umschalten (nicht für die unterste Ebene). */
    fun toggleClipping(state: EditorState, l: Layer) {
        val doc = state.document ?: return
        if (!l.clipping && doc.indexOf(l) <= 0) { state.flash("Keine Ebene darunter"); return }
        property(state, "Schnittmaske", l.clipping, !l.clipping) { l.clipping = it }
    }

    fun setLocked(state: EditorState, l: Layer, locked: Boolean) {
        property(state, if (locked) "Ebene sperren" else "Ebene entsperren", l.locked, locked) { l.locked = it }
        state.flash(if (locked) "Ebene gesperrt" else "Ebene entsperrt")
    }

    fun toggleLocked(state: EditorState, l: Layer) = setLocked(state, l, !l.locked)

    /** Referenzebene umschalten (es gibt höchstens eine). */
    fun setReference(state: EditorState, l: Layer) {
        val doc = state.document ?: return
        val before = doc.layers.associateWith { it.isReference }
        val enable = !l.isReference
        doc.layers.forEach { it.isReference = false }
        l.isReference = enable
        val after = doc.layers.associateWith { it.isReference }
        state.undo.push(LambdaAction("Referenz",
            { before.forEach { (k, v) -> k.isReference = v } },
            { after.forEach { (k, v) -> k.isReference = v } }))
        state.flash(if (enable) "Referenzebene" else "Referenz aufgehoben")
        state.invalidate()
    }

    fun rename(state: EditorState, l: Layer, name: String) {
        val n = name.trim().ifEmpty { return }
        property(state, "Umbenennen", l.name, n) { l.name = it }
    }

    // ---------------- Masken ----------------

    /** Fügt eine (vollständig weiße = sichtbare) Ebenenmaske hinzu. */
    fun addMask(state: EditorState, layer: Layer? = state.document?.activeLayer) {
        val l = layer ?: return
        val doc = state.document ?: return
        if (l.mask != null) { state.flash("Ebene hat bereits eine Maske"); return }
        // Graustufen-Maske als ARGB (deckend, Weiß = sichtbar): direkt mit Pinseln bemalbar
        val m = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        m.eraseColor(Color.WHITE)
        property(state, "Maske", null, m) { l.mask = it }
        if (l === doc.activeLayer) setMaskEditing(state, l, true)
    }

    fun removeMask(state: EditorState, layer: Layer? = state.document?.activeLayer) {
        val l = layer ?: return
        val m = l.mask ?: return
        property(state, "Maske entfernen", m, null) { l.mask = it }
        state.document?.let { maskEditing.remove(it) }
    }

    /** Wendet die Maske auf die Pixel an und entfernt sie. */
    fun applyMask(state: EditorState, layer: Layer? = state.document?.activeLayer) {
        val l = layer ?: return
        val m = l.mask ?: return
        if (!checkUnlocked(state, l)) return
        val before = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val c = Canvas(l.bitmap)
        val p = Paint().apply {
            blendMode = BlendMode.DST_IN
            if (m.config != Bitmap.Config.ALPHA_8) colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix(floatArrayOf(
                    0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                    0.2126f, 0.7152f, 0.0722f, 0f, 0f)))
        }
        c.drawBitmap(m, 0f, 0f, p)
        val after = l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        l.mask = null
        state.document?.let { maskEditing.remove(it) }
        state.undo.push(LambdaAction("Maske anwenden", {
            Canvas(l.bitmap).drawBitmap(before, 0f, 0f, SRC_PAINT); l.mask = m
        }, {
            Canvas(l.bitmap).drawBitmap(after, 0f, 0f, SRC_PAINT); l.mask = null
        }, before.allocationByteCount * 2L))
        state.invalidate()
    }

    // ---------------- Auswahl ----------------

    /** Erstellt eine Auswahl aus dem Alpha der Ebene. */
    fun selectLayerContents(state: EditorState, layer: Layer? = state.document?.activeLayer) {
        val doc = state.document ?: return
        val l = layer ?: return
        var alpha = l.bitmap.extractAlpha()
        if (alpha.config != Bitmap.Config.ALPHA_8 || !alpha.isMutable) {
            alpha = alpha.copy(Bitmap.Config.ALPHA_8, true)
        }
        l.mask?.let { m ->
            val p = Paint().apply { blendMode = BlendMode.DST_IN }
            Canvas(alpha).drawBitmap(if (m.config == Bitmap.Config.ALPHA_8) m else m.extractAlpha(), 0f, 0f, p)
        }
        val prev = doc.selection
        val sel = Selection(alpha)
        doc.selection = sel
        state.undo.push(LambdaAction("Auswählen", { doc.selection = prev }, { doc.selection = sel }, alpha.allocationByteCount.toLong()))
        state.flash("Ebeneninhalt ausgewählt")
        state.invalidate()
    }

    // =========================================================================================
    // Hintergrund
    // =========================================================================================

    fun setBackgroundColor(state: EditorState, argb: Int) {
        val doc = state.document ?: return
        property(state, "Hintergrundfarbe", doc.backgroundColor, argb) { doc.backgroundColor = it }
    }

    fun toggleBackgroundVisible(state: EditorState) {
        val doc = state.document ?: return
        property(state, "Hintergrund", doc.backgroundVisible, !doc.backgroundVisible) { doc.backgroundVisible = it }
    }

    // =========================================================================================
    // Umsortieren
    // =========================================================================================

    /** Verschiebt eine Ebene von Index [from] nach Index [to] (Dokumentindizes, 0 = unten). */
    fun moveLayer(state: EditorState, from: Int, to: Int) {
        val doc = state.document ?: return
        if (from !in doc.layers.indices || from == to) return
        val target = to.coerceIn(0, doc.layers.lastIndex)
        structural(state, "Ebene verschieben") {
            val activeId = doc.activeLayer?.id
            val l = doc.layers.removeAt(from)
            doc.layers.add(target, l)
            doc.activeIndex = doc.layers.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
            fixClipping(doc)
            true
        }
    }

    /**
     * Verschiebt mehrere Ebenen (Reihenfolge bleibt erhalten) relativ zu [anchorId]:
     * [below] = true → direkt unter den Anker, sonst direkt darüber. [anchorId] = null → ganz nach oben
     * (bzw. ganz nach unten bei [below]). Die verschobenen Ebenen erhalten die Gruppe [groupId].
     */
    fun moveLayers(state: EditorState, ids: List<String>, anchorId: String?, below: Boolean, groupId: String?) {
        val doc = state.document ?: return
        if (anchorId != null && anchorId in ids) return
        val moving = doc.layers.filter { it.id in ids }
        if (moving.isEmpty()) return
        structural(state, "Ebene verschieben") {
            val before = doc.layers.toList()
            val oldGroups = moving.map { it.groupId }
            val activeId = doc.activeLayer?.id
            doc.layers.removeAll { it.id in ids }
            val insert = if (anchorId == null) {
                if (below) 0 else doc.layers.size
            } else {
                val ai = doc.layers.indexOfFirst { it.id == anchorId }
                if (ai < 0) doc.layers.size else if (below) ai else ai + 1
            }
            doc.layers.addAll(insert, moving)
            moving.forEach { it.groupId = groupId }
            doc.activeIndex = doc.layers.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
            fixClipping(doc)
            before != doc.layers || oldGroups != moving.map { it.groupId }
        }
    }

    // =========================================================================================
    // Gruppen
    // =========================================================================================

    /** Fasst aktive und sekundär ausgewählte Ebenen zu einer neuen Gruppe zusammen. */
    fun groupSelected(state: EditorState) {
        val doc = state.document ?: return
        groupLayers(state, selectedLayers(doc).map { it.id })
    }

    /**
     * Fasst Ebenen zu einer neuen Gruppe zusammen. Die Ebenen werden an der Position der obersten
     * zusammengeschoben (Reihenfolge bleibt erhalten).
     */
    fun groupLayers(state: EditorState, ids: Collection<String>): LayerGroup? {
        val doc = state.document ?: return null
        val targets = doc.layers.filter { it.id in ids }
        if (targets.isEmpty()) return null
        val group = LayerGroups.create(doc)
        structural(state, "Gruppieren") {
            val activeId = doc.activeLayer?.id
            val top = targets.last()
            val rest = targets.dropLast(1)
            doc.layers.removeAll { it in rest }
            val ti = doc.indexOf(top)
            doc.layers.addAll(ti, rest)
            targets.forEach { it.groupId = group.id }
            doc.activeIndex = doc.layers.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
            doc.secondarySelection.clear()
            fixClipping(doc)
            true
        }
        return group
    }

    /** "Nach unten kombinieren": aktive Ebene mit der darunter in eine Gruppe. */
    fun combineDown(state: EditorState) {
        val doc = state.document ?: return
        val i = doc.activeIndex
        if (i <= 0) { state.flash("Keine Ebene darunter"); return }
        val below = doc.layers[i - 1]
        val active = doc.layers[i]
        if (below.groupId != null) {
            // Darunterliegende Ebene ist bereits in einer Gruppe → aktive Ebene kommt dazu
            moveLayers(state, listOf(active.id), below.id, below = false, groupId = below.groupId)
        } else {
            groupLayers(state, listOf(below.id, active.id))
        }
    }

    /**
     * Ebenen auf eine andere Ebene ziehen: liegt [target] in einer Gruppe, kommen die Ebenen über [target] in
     * diese Gruppe; sonst entsteht eine neue Gruppe aus [target] und den Ebenen.
     */
    fun dropOnto(state: EditorState, ids: List<String>, target: Layer) {
        val doc = state.document ?: return
        if (target.id in ids) return
        val gid = target.groupId
        if (gid != null) {
            moveLayers(state, ids, target.id, below = false, groupId = gid)
        } else {
            val group = LayerGroups.create(doc)
            structural(state, "Gruppieren") {
                val activeId = doc.activeLayer?.id
                val moving = doc.layers.filter { it.id in ids }
                doc.layers.removeAll { it.id in ids }
                doc.layers.addAll(doc.indexOf(target) + 1, moving)
                (moving + target).forEach { it.groupId = group.id }
                doc.activeIndex = doc.layers.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
                fixClipping(doc)
                true
            }
        }
    }

    /** Hebt eine Gruppierung auf (Ebenen bleiben erhalten). */
    fun ungroup(state: EditorState, groupId: String) {
        structural(state, "Gruppierung aufheben") { doc ->
            var changed = false
            doc.layers.forEach { if (it.groupId == groupId) { it.groupId = null; changed = true } }
            changed
        }
    }

    /** Löscht eine Gruppe samt Ebenen. */
    fun deleteGroup(state: EditorState, groupId: String) {
        val doc = state.document ?: return
        deleteLayers(state, doc.layers.filter { it.groupId == groupId }.map { it.id })
    }

    /** Fügt alle Ebenen einer Gruppe zu einer Ebene zusammen (Gruppe wird aufgelöst). */
    fun mergeGroup(state: EditorState, groupId: String) {
        val doc = state.document ?: return
        val ids = doc.layers.filter { it.groupId == groupId }.map { it.id }
        val name = LayerGroups.find(doc, groupId)?.name
        if (ids.size < 2) { ungroup(state, groupId); return }
        mergeLayers(state, ids, "Gruppe zusammenfügen", resultName = name, keepGroup = false)
    }

    fun toggleGroupVisible(state: EditorState, groupId: String) {
        val doc = state.document ?: return
        val g = LayerGroups.get(doc, groupId)
        property(state, if (g.visible) "Gruppe ausblenden" else "Gruppe einblenden", g.visible, !g.visible) { g.visible = it }
    }

    /** Ein-/Ausklappen (nur Ansicht, kein Undo). */
    fun toggleGroupCollapsed(state: EditorState, groupId: String) {
        val doc = state.document ?: return
        val g = LayerGroups.get(doc, groupId)
        g.collapsed = !g.collapsed
        state.invalidate()
    }

    fun renameGroup(state: EditorState, groupId: String, name: String) {
        val doc = state.document ?: return
        val n = name.trim().ifEmpty { return }
        val g = LayerGroups.get(doc, groupId)
        property(state, "Gruppe umbenennen", g.name, n) { g.name = it }
    }

    fun setGroupOpacityLive(state: EditorState, groupId: String, value: Float) {
        val doc = state.document ?: return
        LayerGroups.get(doc, groupId).opacity = value.coerceIn(0f, 1f)
        state.invalidate()
    }

    fun commitGroupOpacity(state: EditorState, groupId: String, startValue: Float) {
        val doc = state.document ?: return
        val g = LayerGroups.get(doc, groupId)
        val end = g.opacity
        if (end == startValue) return
        state.undo.push(LambdaAction("Gruppendeckkraft", { g.opacity = startValue }, { g.opacity = end }))
        state.invalidate()
    }

    // =========================================================================================
    // Mehrfachauswahl (Ebenenmenü über den Titel)
    // =========================================================================================

    /** Markiert alle Ebenen (die aktive bleibt primär). */
    fun selectAll(state: EditorState) {
        val doc = state.document ?: return
        val active = doc.activeLayer
        doc.secondarySelection.clear()
        doc.layers.forEach { if (it !== active) doc.secondarySelection.add(it.id) }
        state.invalidate()
    }

    /** Hebt die Sekundärauswahl auf (nur die Primärebene bleibt markiert). */
    fun deselectAll(state: EditorState) {
        val doc = state.document ?: return
        if (doc.secondarySelection.isEmpty()) return
        doc.secondarySelection.clear()
        state.invalidate()
    }

    /** Fügt alle markierten Ebenen zu einer zusammen. */
    fun mergeSelected(state: EditorState) {
        val doc = state.document ?: return
        val ids = selectedLayers(doc).map { it.id }
        if (ids.size < 2) { state.flash("Mindestens zwei Ebenen markieren"); return }
        mergeLayers(state, ids)
    }

    /**
     * Schreibt die Ebenen als PNG-Dateien (nummeriert ab der untersten) in den Exportordner und liefert
     * die Dateien. Läuft blockierend – im Hintergrund aufrufen (z. B. Dispatchers.IO).
     */
    fun exportLayersPng(context: Context, doc: ArtDocument, layers: List<Layer>): List<java.io.File> {
        val dir = at.techgaisi.farbwerk.io.Exporters.exportDir(context)
        val base = at.techgaisi.farbwerk.io.Exporters.safeName(doc.name)
        val ordered = layers.sortedBy { doc.indexOf(it) }
        return ordered.mapIndexed { i, l ->
            val f = java.io.File(dir, "${base}_${i + 1}_${at.techgaisi.farbwerk.io.Exporters.safeName(l.name)}.png")
            // Maske einrechnen, damit das geteilte Bild dem sichtbaren Ergebnis entspricht
            val bmp = if (l.mask != null) renderMerged(doc, listOf(copyForExport(l))) else l.bitmap
            java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (bmp !== l.bitmap) bmp.recycle()
            f
        }
    }

    /** Kopie für den Export: Normal, volle Deckkraft, sichtbar, ohne Schnittmaske. */
    private fun copyForExport(src: Layer): Layer {
        val l = Layer(src.name, src.bitmap)
        l.mask = src.mask
        return l
    }

    // =========================================================================================
    // Solo-Sichtbarkeit (Häkchen halten)
    // =========================================================================================

    private class Solo(val layerId: String, val visibility: Map<String, Boolean>)

    private val solo = java.util.WeakHashMap<ArtDocument, Solo>()

    /** ID der Ebene im Solo-Modus oder null. */
    fun soloLayerId(doc: ArtDocument): String? = solo[doc]?.layerId

    /**
     * Solo-Sichtbarkeit umschalten: nur [layer] ist sichtbar. Erneut halten beendet Solo und stellt die
     * vorherige Sichtbarkeit wieder her (zuvor ausgeblendete Ebenen bleiben ausgeblendet). Halten auf eine
     * andere Ebene wechselt die Solo-Ebene. Reiner Ansichtsmodus (kein Undo-Eintrag).
     */
    fun toggleSolo(state: EditorState, layer: Layer) {
        val doc = state.document ?: return
        val cur = solo.remove(doc)
        if (cur != null) {
            doc.layers.forEach { l -> cur.visibility[l.id]?.let { l.visible = it } }
            if (cur.layerId == layer.id) {
                state.flash("Solo-Sichtbarkeit aus")
                state.invalidate()
                return
            }
        }
        solo[doc] = Solo(layer.id, doc.layers.associate { it.id to it.visible })
        doc.layers.forEach { it.visible = it === layer }
        state.flash("Solo-Sichtbarkeit: ${layer.name}")
        state.invalidate()
    }

    // =========================================================================================
    // Zeichenassistent / Zusätze
    // =========================================================================================

    /** Zeichenassistent für diese Ebene an/aus. */
    fun toggleAssisted(state: EditorState, l: Layer) {
        property(state, "Zeichenassistent", l.assisted, !l.assisted) { l.assisted = it }
        state.flash(if (l.assisted) "Zeichenassistent an" else "Zeichenassistent aus")
    }

    /** Sichtbare Ebenen mit Inhalt an einer Dokumentposition, oberste zuerst (für „Ebene wählen“). */
    fun layersAt(doc: ArtDocument, x: Float, y: Float): List<Layer> {
        val ix = x.toInt()
        val iy = y.toInt()
        if (ix !in 0 until doc.width || iy !in 0 until doc.height) return emptyList()
        return doc.layers.asReversed().filter { l ->
            l.visible && LayerGroups.isVisible(doc, l.groupId) && Color.alpha(l.bitmap.getPixel(ix, iy)) > 8
        }
    }

    // =========================================================================================
    // Maske als eigene Zeile: Bearbeitungsziel
    // =========================================================================================

    /** Dokument → ID der Ebene, deren Maske gerade bearbeitet wird (Maskenzeile ist primär). */
    private val maskEditing = java.util.WeakHashMap<ArtDocument, String>()

    /** Stellvertreter-Ebenen je Masken-Bitmap (für Pinsel und PixelEdit). */
    private val maskProxies = java.util.WeakHashMap<Bitmap, Layer>()

    /** Ebene, deren Maske gerade bearbeitet wird – nur gültig, solange sie aktiv ist und eine Maske hat. */
    fun editingMaskLayer(state: EditorState): Layer? {
        val doc = state.document ?: return null
        val id = maskEditing[doc] ?: return null
        val a = doc.activeLayer ?: return null
        return if (a.id == id && a.mask != null) a else null
    }

    /** True, wenn die Maskenzeile der aktiven Ebene primär ist (Malen wirkt auf die Maske). */
    fun isEditingMask(state: EditorState): Boolean = editingMaskLayer(state) != null

    fun isEditingMask(doc: ArtDocument, layer: Layer): Boolean =
        maskEditing[doc] == layer.id && layer.mask != null && doc.activeLayer === layer

    /**
     * Maske der Ebene als Bearbeitungsziel wählen (Tippen auf die Maskenzeile) bzw. zurück zur Ebene.
     * Die Ebene wird dabei aktiv; Maske und Ebene bleiben gekoppelt.
     */
    fun setMaskEditing(state: EditorState, layer: Layer, on: Boolean) {
        val doc = state.document ?: return
        if (on) {
            if (layer.mask == null) return
            ensureArgbMask(layer)
            val idx = doc.indexOf(layer)
            if (idx < 0) return
            if (doc.activeIndex != idx) { doc.activeIndex = idx; doc.secondarySelection.clear() }
            maskEditing[doc] = layer.id
        } else {
            maskEditing.remove(doc)
        }
        state.invalidate()
    }

    /**
     * Ziel für Malwerkzeuge, Füllen, Filter: die aktive Ebene oder – wenn die Maskenzeile primär ist – eine
     * Stellvertreter-Ebene, deren [Layer.bitmap] die Masken-Bitmap selbst ist (ARGB-Graustufen, Weiß =
     * sichtbar). `state.beginPixelEdit(target)` und Undo funktionieren damit unverändert.
     * Die Stellvertreter-Ebene steht NICHT in `doc.layers`.
     */
    fun paintTarget(state: EditorState): Layer? {
        val doc = state.document ?: return null
        val l = editingMaskLayer(state) ?: return doc.activeLayer
        return maskLayerFor(l)
    }

    /** Malfarbe für das aktuelle Ziel: bei Masken wird der Farbton ignoriert (nur Helligkeit zählt). */
    fun paintColor(state: EditorState, argb: Int): Int {
        if (!isEditingMask(state)) return argb
        val g = (0.2126f * Color.red(argb) + 0.7152f * Color.green(argb) + 0.0722f * Color.blue(argb) + 0.5f)
            .toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(argb), g, g, g)
    }

    private fun maskLayerFor(l: Layer): Layer {
        val m = ensureArgbMask(l)
        val proxy = synchronized(maskProxies) { maskProxies.getOrPut(m) { Layer(l.name + " · Maske", m) } }
        proxy.locked = l.locked
        proxy.alphaLock = false
        proxy.visible = true
        return proxy
    }

    /**
     * Wandelt eine ALPHA_8-Maske (z. B. aus PSD-Import) in eine deckende ARGB-Graustufenmaske um.
     * Optisch identisch, daher ohne Undo-Eintrag.
     */
    private fun ensureArgbMask(l: Layer): Bitmap {
        val m = l.mask ?: throw IllegalStateException("Ebene ohne Maske")
        if (m.config == Bitmap.Config.ARGB_8888 && m.isMutable) return m
        val argb = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        argb.eraseColor(Color.BLACK)
        if (m.config == Bitmap.Config.ALPHA_8) {
            Canvas(argb).drawBitmap(m, 0f, 0f, Paint().apply { color = Color.WHITE })
        } else {
            Canvas(argb).drawBitmap(m, 0f, 0f, null)
        }
        l.mask = argb
        return argb
    }

    /** Kehrt die Maske um (Schwarz ↔ Weiß), mit Undo; respektiert eine aktive Auswahl. */
    fun invertMask(state: EditorState, layer: Layer) {
        if (layer.mask == null) return
        if (!checkUnlocked(state, layer)) return
        val proxy = maskLayerFor(layer)
        val e = state.beginPixelEdit(proxy)
        val p = Paint().apply { blendMode = BlendMode.SRC; colorFilter = Compositor.INVERT_FILTER }
        Canvas(proxy.bitmap).drawBitmap(e.original, 0f, 0f, p)
        e.commit(null, "Maske umkehren")
    }

    // =========================================================================================
    // Interne Konsistenz
    // =========================================================================================

    /** Die unterste Ebene kann keine Schnittmaske sein. */
    private fun fixClipping(doc: ArtDocument) {
        doc.layers.firstOrNull()?.let { if (it.clipping) it.clipping = false }
    }

    /** Ausschnitt der ganzen Ebene (Hilfsfunktion für Aufrufer). */
    fun fullRect(l: Layer) = Rect(0, 0, l.width, l.height)
}
