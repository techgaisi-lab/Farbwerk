package at.techgaisi.farbwerk.ui.quickmenu

import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.adjust.AdjustmentKind
import at.techgaisi.farbwerk.input.LayerPick
import at.techgaisi.farbwerk.model.LayerOps
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.selection.ClipboardOps
import at.techgaisi.farbwerk.ui.actions.CanvasOps
import at.techgaisi.farbwerk.ui.shell.FloatingEyedropper
import org.json.JSONArray
import org.json.JSONObject

/** Gruppen der Aktionsliste („Aktion festlegen“). */
enum class QuickMenuGroup(val label: String) {
    LAYERS("Ebenen"), EDIT("Bearbeiten"), TOOLS("Werkzeuge & Panels"), VIEW("Hilfen & Ansicht"), COLOR("Pinsel & Farbe"), OTHER("Sonstiges")
}

/**
 * Aktionen, die einem QuickMenu-Knopf zugewiesen werden können (Anleitung §5).
 * [run] bekommt den Menümittelpunkt in Dokument-Koordinaten (für „Ebene wählen“), falls bekannt.
 */
enum class QuickMenuAction(val label: String, val group: QuickMenuGroup, val run: (EditorState, Float?, Float?) -> Unit) {
    NEW_LAYER("Neue Ebene", QuickMenuGroup.LAYERS, { s, _, _ -> LayerOps.addLayer(s) }),
    DUPLICATE_LAYER("Ebene duplizieren", QuickMenuGroup.LAYERS, { s, _, _ -> LayerOps.duplicateActiveLayer(s) }),
    DELETE_LAYER("Ebene löschen", QuickMenuGroup.LAYERS, { s, _, _ -> LayerOps.deleteActiveLayer(s) }),
    MERGE_DOWN("Nach unten zusammenfügen", QuickMenuGroup.LAYERS, { s, _, _ -> LayerOps.mergeDown(s) }),
    CLEAR_LAYER("Ebene leeren", QuickMenuGroup.LAYERS, { s, _, _ ->
        if (s.document?.activeLayer?.locked == true) s.flash("Ebene gesperrt") else { LayerOps.clearActiveLayer(s); s.flash("Ebene geleert") }
    }),
    FILL_LAYER("Ebene füllen", QuickMenuGroup.LAYERS, { s, _, _ -> LayerOps.fillLayer(s) }),
    ALPHA_LOCK("Alpha-Sperre", QuickMenuGroup.LAYERS, { s, _, _ ->
        LayerOps.toggleAlphaLock(s)
        s.flash(if (s.document?.activeLayer?.alphaLock == true) "Alpha-Sperre an" else "Alpha-Sperre aus")
    }),
    INVERT_LAYER("Ebene umkehren", QuickMenuGroup.LAYERS, { s, _, _ -> LayerOps.invert(s) }),
    SELECT_LAYER("Ebene wählen", QuickMenuGroup.LAYERS, { s, x, y ->
        if (x != null && y != null) LayerPick.pickTopmost(s, x, y)
    }),
    LAYER_ABOVE("Ebene darüber wählen", QuickMenuGroup.LAYERS, { s, _, _ -> LayerOps.selectLayerAbove(s) }),
    LAYER_BELOW("Ebene darunter wählen", QuickMenuGroup.LAYERS, { s, _, _ -> LayerOps.selectLayerBelow(s) }),
    SELECT_CONTENTS("Inhalt auswählen", QuickMenuGroup.LAYERS, { s, _, _ -> LayerOps.selectLayerContents(s) }),

    FLIP_H("Horizontal spiegeln", QuickMenuGroup.EDIT, { s, _, _ -> flipLayer(s, true) }),
    FLIP_V("Vertikal spiegeln", QuickMenuGroup.EDIT, { s, _, _ -> flipLayer(s, false) }),
    COPY("Kopieren", QuickMenuGroup.EDIT, { s, _, _ -> ClipboardOps.copy(s) }),
    CUT("Ausschneiden", QuickMenuGroup.EDIT, { s, _, _ -> ClipboardOps.cut(s) }),
    PASTE("Einfügen", QuickMenuGroup.EDIT, { s, _, _ -> ClipboardOps.paste(s) }),
    COPY_ALL("Alles kopieren", QuickMenuGroup.EDIT, { s, _, _ -> ClipboardOps.copyAll(s) }),
    CUT_PASTE("Ausschneiden & Einfügen", QuickMenuGroup.EDIT, { s, _, _ -> ClipboardOps.cutAndPaste(s) }),
    DUPLICATE("Duplizieren", QuickMenuGroup.EDIT, { s, _, _ -> ClipboardOps.duplicate(s) }),
    COPY_PASTE_MENU("Kopieren & Einfügen…", QuickMenuGroup.EDIT, { s, _, _ -> s.copyPasteMenuVisible = true }),
    UNDO("Rückgängig", QuickMenuGroup.EDIT, { s, _, _ -> s.undo.undo() }),
    REDO("Wiederholen", QuickMenuGroup.EDIT, { s, _, _ -> s.undo.redo() }),
    SELECT_ALL("Alles auswählen", QuickMenuGroup.EDIT, { s, _, _ -> ClipboardOps.selectAll(s) }),
    DESELECT("Auswahl aufheben", QuickMenuGroup.EDIT, { s, _, _ -> ClipboardOps.deselect(s) }),
    INVERT_SELECTION("Auswahl umkehren", QuickMenuGroup.EDIT, { s, _, _ -> ClipboardOps.invertSelection(s) }),

    PAINT("Malen", QuickMenuGroup.TOOLS, { s, _, _ -> s.selectTool(Tool.PAINT) }),
    SMUDGE("Wischen", QuickMenuGroup.TOOLS, { s, _, _ -> s.selectTool(Tool.SMUDGE) }),
    ERASE("Radieren", QuickMenuGroup.TOOLS, { s, _, _ -> s.selectTool(Tool.ERASE) }),
    SELECT("Auswahl", QuickMenuGroup.TOOLS, { s, _, _ -> s.selectTool(Tool.SELECT) }),
    TRANSFORM("Transformieren", QuickMenuGroup.TOOLS, { s, _, _ -> s.selectTool(Tool.TRANSFORM) }),
    EYEDROPPER("Pipette", QuickMenuGroup.TOOLS, { s, _, _ -> FloatingEyedropper.start(s) }),
    COLORS("Farben", QuickMenuGroup.TOOLS, { s, _, _ -> s.openPanel = Panel.COLORS }),
    LAYERS("Ebenen", QuickMenuGroup.TOOLS, { s, _, _ -> s.openPanel = Panel.LAYERS }),
    BRUSHES("Pinselbibliothek", QuickMenuGroup.TOOLS, { s, _, _ -> s.openPanel = Panel.BRUSHES }),
    ACTIONS("Aktionen", QuickMenuGroup.TOOLS, { s, _, _ -> s.openPanel = Panel.ACTIONS }),
    ADJUSTMENTS("Anpassungen", QuickMenuGroup.TOOLS, { s, _, _ -> s.openPanel = Panel.ADJUSTMENTS }),
    LIQUIFY("Verflüssigen", QuickMenuGroup.TOOLS, { s, _, _ -> s.adjustmentTool.open(AdjustmentKind.LIQUIFY) }),
    CLONE("Klonen", QuickMenuGroup.TOOLS, { s, _, _ -> s.adjustmentTool.open(AdjustmentKind.CLONE) }),
    HSB("Farbton, Sättigung, Helligkeit", QuickMenuGroup.TOOLS, { s, _, _ -> s.adjustmentTool.open(AdjustmentKind.HSB) }),
    BLUR("Gaußscher Weichzeichner", QuickMenuGroup.TOOLS, { s, _, _ -> s.adjustmentTool.open(AdjustmentKind.GAUSSIAN_BLUR) }),

    GUIDE_VISIBLE("Zeichenhilfe ein/aus", QuickMenuGroup.VIEW, { s, _, _ ->
        s.document?.guides?.let { g ->
            if (g.kind == at.techgaisi.farbwerk.model.GuideKind.NONE) s.flash("Keine Zeichenhilfe eingerichtet")
            else { g.visible = !g.visible; s.flash(if (g.visible) "Zeichenhilfe an" else "Zeichenhilfe aus"); s.invalidate() }
        }
    }),
    ASSISTED("Zeichenassistent", QuickMenuGroup.VIEW, { s, _, _ ->
        s.document?.activeLayer?.let { l ->
            l.assisted = !l.assisted
            s.flash(if (l.assisted) "Zeichenassistent an" else "Zeichenassistent aus"); s.invalidate()
        }
    }),
    QUICKSHAPE("QuickShape ein/aus", QuickMenuGroup.VIEW, { s, _, _ ->
        s.prefs.quickShapeEnabled = !s.prefs.quickShapeEnabled; s.prefs.save()
        s.flash(if (s.prefs.quickShapeEnabled) "QuickShape an" else "QuickShape aus")
    }),
    FIT("Einpassen", QuickMenuGroup.VIEW, { s, _, _ -> s.fitCanvasToScreen() }),
    ACTUAL_SIZE("Originalgröße", QuickMenuGroup.VIEW, { s, _, _ -> s.actualSize() }),
    FULLSCREEN("Vollbild", QuickMenuGroup.VIEW, { s, _, _ -> s.fullscreen = !s.fullscreen }),
    FLIP_CANVAS_H("Leinwand horizontal spiegeln", QuickMenuGroup.VIEW, { s, _, _ -> CanvasOps.flipCanvas(s, true) }),
    FLIP_CANVAS_V("Leinwand vertikal spiegeln", QuickMenuGroup.VIEW, { s, _, _ -> CanvasOps.flipCanvas(s, false) }),

    PREVIOUS_COLOR("Vorherige Farbe", QuickMenuGroup.COLOR, { s, _, _ ->
        s.setColorAndRemember(s.previousColor); s.flash("Farbe getauscht")
    }),
    PREVIOUS_BRUSH("Vorheriger Pinsel", QuickMenuGroup.COLOR, { s, _, _ -> previousBrush(s) }),
    SIZE_UP("Pinsel größer", QuickMenuGroup.COLOR, { s, _, _ -> stepSize(s, 0.05f) }),
    SIZE_DOWN("Pinsel kleiner", QuickMenuGroup.COLOR, { s, _, _ -> stepSize(s, -0.05f) }),

    NONE("Keine Aktion", QuickMenuGroup.OTHER, { _, _, _ -> });

    companion object {
        val DEFAULTS = listOf(NEW_LAYER, FLIP_H, COPY, MERGE_DOWN, CLEAR_LAYER, FLIP_V)
        fun byName(n: String?): QuickMenuAction? = entries.firstOrNull { it.name == n }
    }
}

/** Spiegelt die aktive Ebene (bzw. Auswahl) über eine kurze Transform-Sitzung – rückgängig machbar. */
private fun flipLayer(s: EditorState, horizontal: Boolean) {
    val l = s.document?.activeLayer ?: return
    if (l.locked) { s.flash("Ebene gesperrt"); return }
    val back = s.tool
    val wasTransform = back == Tool.TRANSFORM
    if (!wasTransform) s.selectTool(Tool.TRANSFORM)
    if (horizontal) s.transformTool.flipHorizontal() else s.transformTool.flipVertical()
    if (!wasTransform) s.selectTool(back)
    s.invalidate()
    s.flash(if (horizontal) "Horizontal gespiegelt" else "Vertikal gespiegelt")
}

/** Wechselt zum zuletzt benutzten anderen Pinsel des aktuellen Werkzeugs (aus „Zuletzt“). */
private fun previousBrush(s: EditorState) {
    val ts = s.currentToolSettings
    val prev = QuickMenuStore.lastBrush[s.brushTool]
    if (prev == null || prev == ts.brushId) { s.flash("Kein vorheriger Pinsel"); return }
    QuickMenuStore.lastBrush[s.brushTool] = ts.brushId
    ts.brushId = prev
    s.toolSettingsVersion++
    s.flash("Pinsel: ${s.brushLibrary.get(prev)?.name ?: ""}")
}

private fun stepSize(s: EditorState, d: Float) {
    val ts = s.currentToolSettings
    ts.size = (ts.size + d).coerceIn(0f, 1f)
    s.toolSettingsVersion++
    s.flash("Größe ${(ts.size * 100).toInt()} %")
}

/** Ein QuickMenu-Profil: Name und sechs Belegungen (im Uhrzeigersinn ab oben). */
data class QuickMenuProfile(val name: String, val actions: List<QuickMenuAction>)

/** Profile laden/speichern (JSON in [at.techgaisi.farbwerk.prefs.Prefs.quickMenuProfiles]). */
object QuickMenuStore {
    /** Zuletzt benutzter Pinsel je Werkzeug (für „Vorheriger Pinsel“), vom Editor beim Pinselwechsel gepflegt. */
    val lastBrush = HashMap<Tool, String>()

    fun profiles(s: EditorState): List<QuickMenuProfile> {
        val raw = s.prefs.quickMenuProfiles
        if (raw.isBlank()) return listOf(QuickMenuProfile("Standard", QuickMenuAction.DEFAULTS))
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val a = o.getJSONArray("a")
                QuickMenuProfile(
                    o.optString("n", "Profil ${i + 1}"),
                    (0 until 6).map { k -> QuickMenuAction.byName(a.optString(k)) ?: QuickMenuAction.NONE },
                )
            }.ifEmpty { listOf(QuickMenuProfile("Standard", QuickMenuAction.DEFAULTS)) }
        } catch (_: Throwable) {
            listOf(QuickMenuProfile("Standard", QuickMenuAction.DEFAULTS))
        }
    }

    fun save(s: EditorState, list: List<QuickMenuProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("n", p.name).put("a", JSONArray(p.actions.map { it.name })))
        }
        s.prefs.quickMenuProfiles = arr.toString()
        s.prefs.quickMenuProfile = s.prefs.quickMenuProfile.coerceIn(0, list.size - 1)
        s.prefs.save()
    }

    fun active(s: EditorState): QuickMenuProfile {
        val l = profiles(s)
        return l[s.prefs.quickMenuProfile.coerceIn(0, l.size - 1)]
    }

    fun assign(s: EditorState, slot: Int, action: QuickMenuAction) {
        val l = profiles(s).toMutableList()
        val i = s.prefs.quickMenuProfile.coerceIn(0, l.size - 1)
        val acts = l[i].actions.toMutableList(); acts[slot] = action
        l[i] = l[i].copy(actions = acts)
        save(s, l)
    }

    fun addProfile(s: EditorState) {
        val l = profiles(s).toMutableList()
        l.add(QuickMenuProfile("Profil ${l.size + 1}", QuickMenuAction.DEFAULTS))
        s.prefs.quickMenuProfile = l.size - 1
        save(s, l)
    }

    fun rename(s: EditorState, index: Int, name: String) {
        val l = profiles(s).toMutableList()
        if (index !in l.indices || name.isBlank()) return
        l[index] = l[index].copy(name = name.trim())
        save(s, l)
    }

    fun delete(s: EditorState, index: Int) {
        val l = profiles(s).toMutableList()
        if (l.size <= 1 || index !in l.indices) return
        l.removeAt(index)
        if (s.prefs.quickMenuProfile >= index) s.prefs.quickMenuProfile = (s.prefs.quickMenuProfile - 1).coerceAtLeast(0)
        save(s, l)
    }

    fun select(s: EditorState, index: Int) {
        s.prefs.quickMenuProfile = index
        s.prefs.save()
    }
}
