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
    var pressureSmoothing by mutableFloatStateOf(sp.getFloat("pressureSmoothing", 0.1f))
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

    private fun bez(t: Float, p1: Float, p2: Float): Float {
        val u = 1 - t
        return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t
    }
}
