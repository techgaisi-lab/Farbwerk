package at.techgaisi.farbwerk

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.adjust.AdjustmentTool
import at.techgaisi.farbwerk.brush.BrushEngine
import at.techgaisi.farbwerk.brush.BrushLibrary
import at.techgaisi.farbwerk.canvas.CanvasView
import at.techgaisi.farbwerk.color.Eyedropper
import at.techgaisi.farbwerk.io.ProjectStore
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.model.ToolInputHandler
import at.techgaisi.farbwerk.model.ToolSettings
import at.techgaisi.farbwerk.prefs.Prefs
import at.techgaisi.farbwerk.selection.SelectionTool
import at.techgaisi.farbwerk.transform.TransformTool
import at.techgaisi.farbwerk.undo.PixelEdit
import at.techgaisi.farbwerk.undo.UndoManager

/**
 * Zentraler App-Zustand. Alle Module bekommen diese Instanz und lesen/schreiben hier.
 * Compose-relevante Felder sind State; nach Pixel-/Dokumentänderungen [invalidate] aufrufen.
 */
class EditorState(val context: Context) {
    val prefs = Prefs(context)
    val undo = UndoManager(this)
    val store = ProjectStore(context)

    // ---------------- Dokument ----------------
    var document by mutableStateOf<ArtDocument?>(null)
        private set

    /** Wird bei jeder sichtbaren Änderung erhöht – Compose-Panels lesen das, um neu zu zeichnen. */
    var revision by mutableIntStateOf(0)
        private set

    // ---------------- UI ----------------
    var tool by mutableStateOf(Tool.PAINT)
    var openPanel by mutableStateOf(Panel.NONE)
    var fullscreen by mutableStateOf(false)
    var showGallery by mutableStateOf(true)
    var copyPasteMenuVisible by mutableStateOf(false)
    /** Kurzer Hinweistext oben in der Mitte (z. B. "Rückgängig: Pinselstrich"). */
    var flashMessage by mutableStateOf<String?>(null)
    /** Optionale Schaltfläche im Hinweis (z. B. "Form bearbeiten", "Nachfüllen fortsetzen"). */
    var flashActionLabel by mutableStateOf<String?>(null)
    var flashAction: (() -> Unit)? = null
        private set

    /**
     * Dauerhafte Statusleiste oben (z. B. "Gaußscher Weichzeichner 42 %", "Deckkraft 80 %",
     * "ColorDrop-Schwellenwert 47 %"). null = ausgeblendet. Wer sie setzt, entfernt sie auch wieder.
     */
    var statusBar by mutableStateOf<StatusBar?>(null)

    /** Letzter Punkt, an dem gemalt/berührt wurde (Dokument-Koordinaten) – z. B. für die schwebende Pipette. */
    var lastPaintPoint: android.graphics.PointF? = null

    /**
     * Wenn gesetzt, steuert die Seitenleiste diese Quelle statt Pinselgröße/-deckkraft
     * (z. B. Klonen, Verflüssigen, Anpassung im Stift-Modus). Die Seitenleiste ist dann auch
     * außerhalb von Malen/Wischen/Radieren sichtbar.
     */
    var sidebarSource by mutableStateOf<SidebarSource?>(null)

    /**
     * Wenn gesetzt, gehen Ein-Finger-/Stift-Eingaben auf der Leinwand an diesen Handler statt an das
     * aktive Werkzeug (z. B. Ebenendeckkraft per Ziehen nach 2-Finger-Tippen auf eine Ebene).
     * Der Handler setzt das Feld selbst wieder auf null, wenn er fertig ist.
     */
    var canvasOverride by mutableStateOf<at.techgaisi.farbwerk.model.ToolInputHandler?>(null)

    /** QuickMenu sichtbar (Position in Fenster-Koordinaten). */
    var quickMenuAt by mutableStateOf<android.graphics.PointF?>(null)
    /** Modifizierer-Taste (Quadrat in der Seitenleiste) gedrückt bzw. Pipette scharf geschaltet. */
    var eyedropperArmed by mutableStateOf(false)
    /** Leertaste gehalten (Tastatur): Ziehen verschiebt die Leinwand. */
    var spaceHeld by mutableStateOf(false)

    // ---------------- Farbe ----------------
    /** Aktuelle Farbe (ARGB). */
    var color by mutableIntStateOf(0xFF1E88E5.toInt())
    /** Vorherige Farbe (für "Farbe tauschen" und Verlauf). */
    var previousColor by mutableIntStateOf(0xFF000000.toInt())
    /** Sekundärfarbe (für Farbdynamik der Pinsel, wie im Vorbild). */
    var secondaryColor by mutableIntStateOf(0xFFFFFFFF.toInt())
    val recentColors = mutableStateListOf<Int>()

    fun setColorAndRemember(argb: Int) {
        if (argb == color) return
        previousColor = color
        color = argb
        recentColors.remove(argb)
        recentColors.add(0, argb)
        while (recentColors.size > 10) recentColors.removeAt(recentColors.lastIndex)
    }

    // ---------------- Werkzeuge ----------------
    val brushLibrary = BrushLibrary(context)
    init {
        // Pinselvorschauen beim Start im Hintergrund vorladen (Festplatten-Cache, sonst rendern)
        brushLibrary.warmUp()
        try { at.techgaisi.farbwerk.io.CanvasQuality.screenLongSide(context) } catch (_: Throwable) {}
    }
    val toolSettings: Map<Tool, ToolSettings> = mapOf(
        Tool.PAINT to ToolSettings(brushLibrary.defaultBrushId(Tool.PAINT), 0.2f, 1f),
        Tool.SMUDGE to ToolSettings(brushLibrary.defaultBrushId(Tool.SMUDGE), 0.3f, 0.8f),
        Tool.ERASE to ToolSettings(brushLibrary.defaultBrushId(Tool.ERASE), 0.3f, 1f),
    )
    /** Werkzeug, dessen Größe/Deckkraft die Seitenleiste gerade anzeigt. */
    val brushTool: Tool get() = if (tool == Tool.SMUDGE || tool == Tool.ERASE) tool else Tool.PAINT
    val currentToolSettings: ToolSettings get() = toolSettings.getValue(brushTool)
    /** Erhöhen, wenn sich Größe/Deckkraft/Pinsel geändert hat (Seitenleiste liest das). */
    var toolSettingsVersion by mutableIntStateOf(0)

    val brushEngine = BrushEngine(this)
    val selectionTool = SelectionTool(this)
    val transformTool = TransformTool(this)
    val adjustmentTool = AdjustmentTool(this)
    val eyedropper = Eyedropper(this)

    /** Liefert den Handler für das aktive Werkzeug. */
    fun activeHandler(): ToolInputHandler? = when (tool) {
        Tool.PAINT, Tool.SMUDGE, Tool.ERASE -> brushEngine
        Tool.SELECT -> selectionTool
        Tool.TRANSFORM -> transformTool
        Tool.ADJUST -> adjustmentTool
    }

    /**
     * Werkzeug wechseln. Wechsel aus Auswahl/Transform/Anpassen schließt diese Modi sauber ab.
     */
    fun selectTool(t: Tool) {
        if (tool == t) {
            // Bereits im Transformieren, aber ohne Sitzung (z. B. nach "Bild/Text einfügen", das die
            // laufende Transformation abgeschlossen hat): neue Sitzung auf der aktiven Ebene starten.
            if (t == Tool.TRANSFORM && !transformTool.active) {
                transformTool.begin()
                invalidate()
            }
            return
        }
        // Ein laufender Strich gehört zum alten Werkzeug – sonst überschreiben sich z. B. die
        // drawOverrides von Pinsel und Transformieren gegenseitig.
        canvasView?.abortStroke()
        val old = tool
        when (old) {
            Tool.TRANSFORM -> transformTool.commit()
            Tool.ADJUST -> adjustmentTool.apply()
            Tool.SELECT -> selectionTool.onDeactivate()
            else -> {}
        }
        tool = t
        when (t) {
            Tool.TRANSFORM -> transformTool.begin()
            Tool.SELECT -> selectionTool.onActivate()
            else -> {}
        }
        toolSettingsVersion++
        invalidate()
    }

    // ---------------- Leinwand ----------------
    internal var canvasView: CanvasView? = null

    /**
     * Zähler der Leinwand-Überlagerung (Schwebevorschau des Stifts). Wird nur beim Zeichnen gelesen – eine
     * Änderung zeichnet allein die Überlagerung neu, ohne Neukomposition und ohne die Leinwand neu zu zeichnen.
     */
    internal val canvasOverlayTick = mutableIntStateOf(0)

    /** Läuft gerade ein Strich (Werkzeug-Berührung) auf der Leinwand? Kein Compose-State (ändert sich ohne Neukomposition). */
    var strokeActive = false
        internal set
    /** Zeitpunkt ([android.os.SystemClock.uptimeMillis]) des letzten Strichendes. */
    var lastStrokeEndAt = 0L
        internal set

    fun invalidate() {
        revision++
        canvasView?.postInvalidateOnAnimation()
    }

    fun markDirty() {
        document?.let { it.dirty = true; it.modified = System.currentTimeMillis() }
    }

    fun beginPixelEdit(layer: at.techgaisi.farbwerk.model.Layer): PixelEdit {
        val copy = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return PixelEdit(this, layer, copy)
    }

    /**
     * Wie [beginPixelEdit], aber ohne Kopie der ganzen Ebene: Der Aufrufer sichert Bereiche mit
     * [PixelEdit.protect]/[PixelEdit.protectExact], bevor er sie verändert (Pinsel: kein Kopieren beim Aufsetzen).
     */
    fun beginLazyPixelEdit(layer: at.techgaisi.farbwerk.model.Layer): PixelEdit = PixelEdit(this, layer, null)

    /**
     * Rechnet eine Position in Fenster-Koordinaten (z. B. aus einem Compose-Drag, positionInWindow)
     * in Dokument-Koordinaten um. null, wenn keine Leinwand vorhanden ist.
     */
    fun windowToDoc(windowX: Float, windowY: Float): android.graphics.PointF? =
        canvasView?.windowToDoc(windowX, windowY)

    fun fitCanvasToScreen() = canvasView?.fitToScreen(animate = true)
    fun zoomBy(factor: Float) = canvasView?.zoomBy(factor)
    fun actualSize() = canvasView?.actualSize()

    // ---------------- Dokument öffnen/schließen ----------------
    fun openDocument(doc: ArtDocument) {
        // Sitzungen des bisherigen Dokuments (z. B. bei "Leinwandgröße ändern") verwerfen,
        // bevor das neue Dokument gesetzt wird – sie verweisen noch auf alte Ebenen.
        canvasView?.abortStroke()
        brushEngine.release()
        resetTransientModes()
        when (tool) {
            Tool.TRANSFORM -> transformTool.cancel()
            Tool.ADJUST -> adjustmentTool.cancel()
            Tool.SELECT -> selectionTool.onDeactivate()
            else -> {}
        }
        eyedropperArmed = false
        copyPasteMenuVisible = false
        document = doc
        undo.clear()
        showGallery = false
        openPanel = Panel.NONE
        tool = Tool.PAINT
        // Existiert die Leinwand noch nicht (erstes Öffnen aus der Galerie), passt sie sich in
        // onSizeChanged selbst ein.
        canvasView?.fitToScreen(animate = false)
        invalidate()
    }

    /**
     * Speichert und kehrt zur Galerie zurück. Verlauf wird (wie im Original) gelöscht.
     *
     * Die Galerie erscheint sofort: Auf dem Main-Thread werden nur offene Werkzeug-Sitzungen abgeschlossen und
     * die Metadaten erfasst. Das geschlossene Dokument wird danach nicht mehr verändert und direkt im Hintergrund
     * gespeichert (Miniatur zuerst, dann nur geänderte Kacheln) – ohne Kopien der Ebenen.
     */
    fun closeToGallery() {
        canvasView?.abortStroke()
        at.techgaisi.farbwerk.ui.text.TextEditing.session?.done()
        resetTransientModes()
        when (tool) {
            Tool.TRANSFORM -> transformTool.commit()
            Tool.ADJUST -> adjustmentTool.apply()
            Tool.SELECT -> selectionTool.onDeactivate()
            else -> {}
        }
        tool = Tool.PAINT
        eyedropperArmed = false
        copyPasteMenuVisible = false
        val d = document
        // Laufendes „Form bearbeiten“ übernehmen und den Pinsel lösen, bevor das Dokument übergeben wird –
        // danach greift nichts mehr auf seine Ebenen zu.
        try { brushEngine.commitPendingShapeEdit() } catch (t: Throwable) { android.util.Log.e("Farbwerk", "Form übernehmen", t) }
        brushEngine.release()
        if (d != null) {
            try {
                store.save(d, detached = true)
            } catch (t: Throwable) {
                // Nicht schließen, sonst wäre das Werk verloren – im Editor bleiben
                android.util.Log.e("Farbwerk", "Speichern fehlgeschlagen", t)
                d.dirty = true
                flash("Speichern fehlgeschlagen")
                return
            }
        }
        document = null
        undo.clear()
        openPanel = Panel.NONE
        showGallery = true
    }

    /**
     * Beendet modale Zustände, die an das aktuelle Dokument gebunden sind (Leinwand-Override, Status-/Seitenleiste,
     * QuickMenu, Zuschneiden/Zeichenhilfe-Editor/Text), damit beim Dokument-/Galeriewechsel nichts hängen bleibt.
     */
    private fun resetTransientModes() {
        at.techgaisi.farbwerk.ui.actions.ActionModes.crop?.cancel()
        at.techgaisi.farbwerk.ui.actions.ActionModes.guideEditor?.cancel()
        at.techgaisi.farbwerk.ui.text.TextEditing.session = null
        canvasOverride = null
        statusBar = null
        sidebarSource = null
        quickMenuAt = null
    }

    /** Speichert das geöffnete Werk im Hintergrund (Main-Thread-Anteil: Metadaten + Miniatur). */
    fun saveNow() {
        val d = document ?: return
        // Laufendes „Form bearbeiten“ übernehmen – die Ebene enthält sonst den Zustand ohne Form
        brushEngine.commitPendingShapeEdit()
        try {
            store.save(d)
        } catch (t: Throwable) {
            // Speichern darf die App nie beenden (z. B. Speicher voll / zu wenig RAM)
            android.util.Log.e("Farbwerk", "Speichern fehlgeschlagen", t)
            d.dirty = true
            flash("Speichern fehlgeschlagen")
        }
    }

    // ---------------- Hinweise ----------------
    private val handler = Handler(Looper.getMainLooper())
    private val clearFlash = Runnable { flashMessage = null; flashActionLabel = null; flashAction = null }
    fun flash(msg: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        flashMessage = msg
        flashActionLabel = actionLabel
        flashAction = onAction
        handler.removeCallbacks(clearFlash)
        handler.postDelayed(clearFlash, if (actionLabel != null) 3500 else 1500)
    }

    /** Hinweis-Schaltfläche wurde angetippt. */
    fun triggerFlashAction() {
        val a = flashAction
        flashMessage = null; flashActionLabel = null; flashAction = null
        a?.invoke()
    }
}

/** Inhalt der dauerhaften Statusleiste. [percent] 0..1 oder null. */
data class StatusBar(
    val text: String,
    val percent: Float? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * Alternative Quelle für die Seitenleiste. Werte 0..1. [label] erscheint in der Vorschaublase.
 */
interface SidebarSource {
    val topLabel: String get() = "Größe"
    val bottomLabel: String get() = "Deckkraft"
    var top: Float
    var bottom: Float
}
