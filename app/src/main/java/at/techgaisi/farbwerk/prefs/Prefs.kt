package at.techgaisi.farbwerk.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-Einstellungen (Aktionen → Einstellungen). Werte sind Compose-State und werden in
 * SharedPreferences gespeichert, sobald [save] aufgerufen wird.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("farbwerk_prefs", Context.MODE_PRIVATE)

    var lightInterface by mutableStateOf(sp.getBoolean("light", false))
    /** false = Seitenleiste links (Standard), true = rechts ("Rechtshändige Benutzeroberfläche") */
    var sidebarRight by mutableStateOf(sp.getBoolean("sidebarRight", false))
    var brushCursor by mutableStateOf(sp.getBoolean("brushCursor", true))
    var dynamicBrushScaling by mutableStateOf(sp.getBoolean("dynScale", true))
    var projectCanvas by mutableStateOf(sp.getBoolean("projectCanvas", false))
    /** Finger dürfen malen. Aus = nur Stift malt, Finger nur Gesten. */
    var fingerPainting by mutableStateOf(sp.getBoolean("fingerPaint", true))
    /** Druckkurve: 4 Werte (x1,y1,x2,y2) einer kubischen Bézier von (0,0) nach (1,1). */
    var pressureCurve by mutableStateOf(
        sp.getString("pressureCurve", "0.25,0.25,0.75,0.75")!!.split(",").map { it.toFloat() }
    )
    /**
     * Druckglättung 0..1 (Zeitkonstante bis 80 ms, siehe StrokeRenderer). Standard [DEFAULT_PRESSURE_SMOOTHING];
     * ältere, niedrigere gespeicherte Werte werden einmalig angehoben (S-Pen-Rauschen → gezackte Strichkanten).
     */
    var pressureSmoothing by mutableFloatStateOf(migratedPressureSmoothing())
    var motionFilteringAmount by mutableFloatStateOf(sp.getFloat("motionFilter", 0f))
    var holdDelayMs by mutableIntStateOf(sp.getInt("holdDelay", 450))
    var quickShapeDelayMs by mutableIntStateOf(sp.getInt("qsDelay", 650))
    var rapidUndoDelayMs by mutableIntStateOf(sp.getInt("rapidUndoDelay", 650))
    var gestureTapUndo by mutableStateOf(sp.getBoolean("gTapUndo", true))
    var gestureScrubClear by mutableStateOf(sp.getBoolean("gScrub", true))
    var gestureCopyPaste by mutableStateOf(sp.getBoolean("gCopyPaste", true))
    var gestureFullscreen by mutableStateOf(sp.getBoolean("gFullscreen", true))
    var gestureHoldEyedropper by mutableStateOf(sp.getBoolean("gHoldEyedropper", true))
    var quickShapeEnabled by mutableStateOf(sp.getBoolean("quickShape", true))
    var undoLimit by mutableIntStateOf(sp.getInt("undoLimit", 250))

    // ---------------- S Pen / Stift ----------------
    /** Seitentaste gedrückt + Berühren (Wert = StylusButtonAction.id, Standard 1 = Pipette). */
    var stylusButtonAction by mutableIntStateOf(sp.getInt("penButton", 1))
    /** Seitentaste klicken, während der Stift schwebt (StylusButtonAction.id, Standard 2 = Radierer umschalten). */
    var stylusButtonHoverAction by mutableIntStateOf(sp.getInt("penButtonHover", 2))
    /** Sobald ein Stift benutzt wurde, malen Finger nicht mehr (nur noch Gesten). */
    var stylusOnlyAfterPen by mutableStateOf(sp.getBoolean("penOnlyAuto", true))
    /** Handballenerkennung: 0 = aus, 1 = normal, 2 = stark. */
    var palmRejection by mutableIntStateOf(sp.getInt("palmRejection", 1))
    /** Pinselumriss anzeigen, während der Stift über dem Display schwebt. */
    var hoverPreview by mutableStateOf(sp.getBoolean("hoverPreview", true))
    /** Bewegungsvorhersage (vorhergesagte Punkte als Vorschau zeichnen). */
    var motionPrediction by mutableStateOf(sp.getBoolean("motionPrediction", true))

    // ---------------- Oberfläche (WP1) ----------------
    /** Seitenleiste Größe & Deckkraft anzeigen. */
    var sidebarVisible by mutableStateOf(sp.getBoolean("sidebarVisible", true))
    /** Vertikale Lage der Seitenleiste: −1 = ganz oben, 0 = mittig, 1 = ganz unten. */
    var sidebarOffset by mutableFloatStateOf(sp.getFloat("sidebarOffset", 0f))
    /** Zwei-Finger-Geste dreht die Leinwand mit (Gestensteuerung → Allgemein). */
    var rotateWithPinch by mutableStateOf(sp.getBoolean("rotateWithPinch", true))
    /** Oberfläche beim Malen automatisch ausblenden. */
    var autoFullscreen by mutableStateOf(sp.getBoolean("autoFullscreen", false))
    /** Verzögerung, nach der die Oberfläche nach dem Malen zurückkehrt (ms). */
    var autoFullscreenDelayMs by mutableIntStateOf(sp.getInt("autoFullscreenDelay", 900))
    /** Leertaste kurz tippen = QuickMenu (halten + ziehen verschiebt weiterhin die Leinwand). */
    var spaceQuickMenu by mutableStateOf(sp.getBoolean("spaceQuickMenu", true))
    /**
     * Gestensteuerung: Zuordnung Funktion → Auslöser, kodiert als "FUNKTION:AUSLÖSER,AUSLÖSER;…"
     * (Auswertung in input/GestureMap). Leer = Standardbelegung.
     */
    var gestureTriggers by mutableStateOf(sp.getString("gestureTriggers", "") ?: "")
    /** Verzögerung „Berühren & halten“ für QuickMenu/Ebene wählen (ms). */
    var holdMenuDelayMs by mutableIntStateOf(sp.getInt("holdMenuDelay", 450))
    /** QuickMenu-Profile als JSON (Auswertung in ui/quickmenu). Leer = Standardprofil. */
    var quickMenuProfiles by mutableStateOf(sp.getString("qmProfiles", "") ?: "")
    /** Index des aktiven QuickMenu-Profils. */
    var quickMenuProfile by mutableIntStateOf(sp.getInt("qmProfile", 0))
    /** Pinselcursor-Sichtbarkeit: 0 = beim Schweben, 1 = beim Malen, 2 = beides. */
    var cursorVisibility by mutableIntStateOf(sp.getInt("cursorVisibility", 2))
    /** Pinselcursor-Umriss: 0 = hoher Kontrast, 1 = aktive Farbe, 2 = pro Pinsel. */
    var cursorStyle by mutableIntStateOf(sp.getInt("cursorStyle", 0))
    /** Deckkraft der Auswahl-Schraffur außerhalb der Auswahl (0..1). */
    var selectionMaskOpacity by mutableFloatStateOf(sp.getFloat("selMaskOpacity", 0.35f))
    /** Vereinfachtes Rückgängig im Transformieren: alle Schritte einer Sitzung = ein Schritt. */
    var simplifiedUndo by mutableStateOf(sp.getBoolean("simplifiedUndo", false))
    /** Version der Größen-/Deckkraftmarken (Compose liest das, um Marken neu zu zeichnen). */
    var marksVersion by mutableIntStateOf(0)
        private set

    /**
     * Größen- bzw. Deckkraftmarken der Seitenleiste je Pinsel und Werkzeug (max. 4).
     * [key] z. B. "PAINT/<pinsel-id>", [opacity] wählt die Deckkraftmarken.
     */
    fun sliderMarks(key: String, opacity: Boolean): List<Float> {
        val raw = sp.getString(marksKey(key, opacity), null) ?: return emptyList()
        return raw.split(",").mapNotNull { it.toFloatOrNull() }.filter { it in 0f..1f }.take(MAX_MARKS)
    }

    fun setSliderMarks(key: String, opacity: Boolean, marks: List<Float>) {
        val k = marksKey(key, opacity)
        val list = marks.distinct().sorted().take(MAX_MARKS)
        sp.edit().apply { if (list.isEmpty()) remove(k) else putString(k, list.joinToString(",")) }.apply()
        marksVersion++
    }

    private fun marksKey(key: String, opacity: Boolean) = "marks_" + (if (opacity) "o_" else "s_") + key

    fun save() {
        sp.edit()
            .putBoolean("light", lightInterface)
            .putBoolean("sidebarRight", sidebarRight)
            .putBoolean("brushCursor", brushCursor)
            .putBoolean("dynScale", dynamicBrushScaling)
            .putBoolean("projectCanvas", projectCanvas)
            .putBoolean("fingerPaint", fingerPainting)
            .putString("pressureCurve", pressureCurve.joinToString(","))
            .putFloat("pressureSmoothing", pressureSmoothing)
            .putFloat("motionFilter", motionFilteringAmount)
            .putInt("holdDelay", holdDelayMs)
            .putInt("qsDelay", quickShapeDelayMs)
            .putInt("rapidUndoDelay", rapidUndoDelayMs)
            .putBoolean("gTapUndo", gestureTapUndo)
            .putBoolean("gScrub", gestureScrubClear)
            .putBoolean("gCopyPaste", gestureCopyPaste)
            .putBoolean("gFullscreen", gestureFullscreen)
            .putBoolean("gHoldEyedropper", gestureHoldEyedropper)
            .putBoolean("quickShape", quickShapeEnabled)
            .putInt("undoLimit", undoLimit)
            .putInt("penButton", stylusButtonAction)
            .putInt("penButtonHover", stylusButtonHoverAction)
            .putBoolean("penOnlyAuto", stylusOnlyAfterPen)
            .putInt("palmRejection", palmRejection)
            .putBoolean("hoverPreview", hoverPreview)
            .putBoolean("motionPrediction", motionPrediction)
            .putBoolean("sidebarVisible", sidebarVisible)
            .putFloat("sidebarOffset", sidebarOffset)
            .putBoolean("rotateWithPinch", rotateWithPinch)
            .putBoolean("autoFullscreen", autoFullscreen)
            .putInt("autoFullscreenDelay", autoFullscreenDelayMs)
            .putBoolean("spaceQuickMenu", spaceQuickMenu)
            .putString("gestureTriggers", gestureTriggers)
            .putInt("holdMenuDelay", holdMenuDelayMs)
            .putString("qmProfiles", quickMenuProfiles)
            .putInt("qmProfile", quickMenuProfile)
            .putInt("cursorVisibility", cursorVisibility)
            .putInt("cursorStyle", cursorStyle)
            .putFloat("selMaskOpacity", selectionMaskOpacity)
            .putBoolean("simplifiedUndo", simplifiedUndo)
            .apply()
    }

    /** Wendet die Druckkurve auf einen Rohdruck 0..1 an. */
    fun applyPressureCurve(raw: Float): Float {
        val p = raw.coerceIn(0f, 1f)
        val c = pressureCurve
        if (c.size < 4) return p
        // Bézier nach t auflösen (x(t) = p) per Bisektion
        var lo = 0f; var hi = 1f; var t = p
        repeat(18) {
            t = (lo + hi) / 2f
            val x = bez(t, c[0], c[2])
            if (x < p) lo = t else hi = t
        }
        return bez(t, c[1], c[3]).coerceIn(0f, 1f)
    }

    /** Einmalige Migration: gespeicherte Druckglättung unter dem neuen Standard auf den Standard anheben. */
    private fun migratedPressureSmoothing(): Float {
        val stored = sp.getFloat("pressureSmoothing", DEFAULT_PRESSURE_SMOOTHING)
        if (sp.getInt("pressureSmoothingVersion", 0) >= 2) return stored
        val v = if (stored < DEFAULT_PRESSURE_SMOOTHING) DEFAULT_PRESSURE_SMOOTHING else stored
        sp.edit().putFloat("pressureSmoothing", v).putInt("pressureSmoothingVersion", 2).apply()
        return v
    }

    companion object {
        /** Höchstzahl der Größen- bzw. Deckkraftmarken je Pinsel und Werkzeug. */
        const val MAX_MARKS = 4
        /** Standard der Druckglättung (≈ 28 ms Zeitkonstante): glättet S-Pen-Rauschen, ohne den Druckaufbau zu bremsen. */
        const val DEFAULT_PRESSURE_SMOOTHING = 0.35f
    }

    private fun bez(t: Float, p1: Float, p2: Float): Float {
        val u = 1 - t
        return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t
    }
}
