package at.techgaisi.farbwerk.selection

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Persistente Einstellungen des Auswahl- und Transform-Moduls.
 *
 * Liegt in derselben SharedPreferences-Datei wie die App-Einstellungen ("farbwerk_prefs"), aber unter
 * eigenen Schlüsseln. So kann der Einstellungen-Tab (WP1) die Werte direkt binden, z. B.
 * `Slider(SelectionPrefs.maskOpacity, { SelectionPrefs.updateMaskOpacity(it) })`.
 */
object SelectionPrefs {
    private const val FILE = "farbwerk_prefs"
    private const val KEY_MASK_OPACITY = "selectionMaskOpacity"
    private const val KEY_MAGNETIC = "tfMagnetic"
    private const val KEY_SNAPPING = "tfSnapping"
    private const val KEY_SNAP_DISTANCE = "tfSnapDistance"
    private const val KEY_SNAP_SPEED = "tfSnapSpeed"

    private var sp: SharedPreferences? = null

    /** Deckkraft der diagonalen Streifen außerhalb der Auswahl („Sichtbarkeit der Auswahlmaske“), 0..1. */
    var maskOpacity by mutableFloatStateOf(0.35f)
        private set

    /** Transformieren: Magnetisch. */
    var magnetic by mutableStateOf(false)
        private set
    /** Transformieren: Einrasten an Objekten und Leinwand. */
    var snapping by mutableStateOf(false)
        private set
    /** Einrast-Abstand in dp (Bildschirm). */
    var snapDistance by mutableFloatStateOf(14f)
        private set
    /** Geschwindigkeit 0..1: bis zu welcher Zieh-Geschwindigkeit Hilfslinien greifen (1 = immer). */
    var snapSpeed by mutableFloatStateOf(0.6f)
        private set

    /** Einmalig mit einem Context laden (mehrfacher Aufruf ist harmlos). */
    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        maskOpacity = p.getFloat(KEY_MASK_OPACITY, 0.35f).coerceIn(0f, 1f)
        magnetic = p.getBoolean(KEY_MAGNETIC, false)
        snapping = p.getBoolean(KEY_SNAPPING, false)
        snapDistance = p.getFloat(KEY_SNAP_DISTANCE, 14f).coerceIn(1f, 50f)
        snapSpeed = p.getFloat(KEY_SNAP_SPEED, 0.6f).coerceIn(0f, 1f)
    }

    fun updateMaskOpacity(v: Float) {
        maskOpacity = v.coerceIn(0f, 1f)
        sp?.edit()?.putFloat(KEY_MASK_OPACITY, maskOpacity)?.apply()
    }

    fun updateMagnetic(v: Boolean) {
        magnetic = v
        sp?.edit()?.putBoolean(KEY_MAGNETIC, v)?.apply()
    }

    fun updateSnapping(v: Boolean) {
        snapping = v
        sp?.edit()?.putBoolean(KEY_SNAPPING, v)?.apply()
    }

    fun updateSnapDistance(v: Float) {
        snapDistance = v.coerceIn(1f, 50f)
        sp?.edit()?.putFloat(KEY_SNAP_DISTANCE, snapDistance)?.apply()
    }

    fun updateSnapSpeed(v: Float) {
        snapSpeed = v.coerceIn(0f, 1f)
        sp?.edit()?.putFloat(KEY_SNAP_SPEED, snapSpeed)?.apply()
    }
}
