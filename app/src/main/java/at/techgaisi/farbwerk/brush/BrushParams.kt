package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/** Attribut-Gruppen des Pinsel-Studios (linke Liste, Reihenfolge wie im Vorbild). */
enum class BrushGroup(val label: String) {
    STROKE("Strichpfad"),
    STABILIZATION("Stabilisierung"),
    TAPER("Verjüngung"),
    SHAPE("Form"),
    GRAIN("Körnung"),
    RENDERING("Rendering"),
    WET("Nasse Farbe"),
    COLOR("Farbdynamik"),
    DYNAMICS("Dynamik"),
    PEN("Stift-Eigenschaften"),
    PROPERTIES("Eigenschaften"),
    PREVIEW("Vorschau"),
    INFO("Über diesen Pinsel"),
}

/** Darstellung/Einheit eines Parameters im Studio. */
enum class ParamKind { PERCENT, SIGNED_PERCENT, PIXELS, DEGREES, FACTOR, COUNT, TOGGLE, CHOICE, SHAPE, GRAIN }

/** Wie Dabs innerhalb eines Strichs gerendert werden (Rendering-Modus). */
object RenderMode {
    const val LIGHT_GLAZE = 0
    const val UNIFORM_GLAZE = 1
    const val INTENSE_GLAZE = 2
    const val HEAVY_GLAZE = 3
    const val UNIFORM_BLEND = 4
    const val INTENSE_BLEND = 5
    val labels = arrayOf(
        "Leichte Lasur", "Gleichmäßige Lasur", "Intensive Lasur",
        "Starke Lasur", "Gleichmäßiges Mischen", "Intensives Mischen",
    )
}

/** Ausrichtung der Pinselform (Eingabestil). */
object RotationMode {
    const val FIXED = 0
    const val FOLLOW = 1
    const val RANDOM = 2
    const val AZIMUTH = 3
    val labels = arrayOf("Fest", "Folgt Strich", "Zufällig", "Stiftausrichtung")
}

/** Mischmodus, mit dem der fertige Strich in die Ebene gemischt wird. */
object BrushBlend {
    val labels = arrayOf(
        "Normal", "Multiplizieren", "Negativ multiplizieren", "Überlagern",
        "Hinzufügen", "Abdunkeln", "Aufhellen", "Weiches Licht", "Farbig abwedeln",
        "Farbig nachbelichten", "Hartes Licht", "Differenz", "Farbton", "Sättigung", "Farbe", "Luminanz",
    )
    fun mode(index: Int): android.graphics.BlendMode? = when (index) {
        1 -> android.graphics.BlendMode.MULTIPLY
        2 -> android.graphics.BlendMode.SCREEN
        3 -> android.graphics.BlendMode.OVERLAY
        4 -> android.graphics.BlendMode.PLUS
        5 -> android.graphics.BlendMode.DARKEN
        6 -> android.graphics.BlendMode.LIGHTEN
        7 -> android.graphics.BlendMode.SOFT_LIGHT
        8 -> android.graphics.BlendMode.COLOR_DODGE
        9 -> android.graphics.BlendMode.COLOR_BURN
        10 -> android.graphics.BlendMode.HARD_LIGHT
        11 -> android.graphics.BlendMode.DIFFERENCE
        12 -> android.graphics.BlendMode.HUE
        13 -> android.graphics.BlendMode.SATURATION
        14 -> android.graphics.BlendMode.COLOR
        15 -> android.graphics.BlendMode.LUMINOSITY
        else -> null
    }
}

/** Mischmodus der verbrannten Kanten. */
object BurnMode {
    const val MULTIPLY = 0
    const val COLOR_BURN = 1
    const val LINEAR_BURN = 2
    const val DARKEN = 3
    val labels = arrayOf("Multiplizieren", "Farbig nachbelichten", "Linear nachbelichten", "Abdunkeln")
}

/**
 * Alle Pinsel-Parameter. Jeder Pinsel speichert die Werte in einem FloatArray (Index = ordinal), dadurch
 * sind Studio-UI, JSON-Speicherung und Engine generisch und neue Parameter kosten nur eine Zeile.
 * Gespeichert wird nach Namen – die Reihenfolge darf sich also ändern.
 *
 * @param section Zwischenüberschrift im Studio (beginnt bei diesem Parameter einen neuen Abschnitt)
 * @param dyn Parameter wirkt pro Tupfer und kann im Studio mit Druck/Neigung gekoppelt werden
 */
enum class BP(
    val group: BrushGroup,
    val label: String,
    val kind: ParamKind,
    val min: Float,
    val max: Float,
    val def: Float,
    val options: Array<String>? = null,
    val section: String? = null,
    val dyn: Boolean = false,
) {
    // ---- Strichpfad
    SPACING(BrushGroup.STROKE, "Abstand", ParamKind.PERCENT, 0.01f, 3f, 0.08f, section = "Stricheigenschaften", dyn = true),
    SPACING_JITTER(BrushGroup.STROKE, "Abstandsstreuung", ParamKind.PERCENT, 0f, 1f, 0f, dyn = true),
    JITTER(BrushGroup.STROKE, "Streuung seitlich", ParamKind.PERCENT, 0f, 3f, 0f, dyn = true),
    JITTER_LONG(BrushGroup.STROKE, "Streuung längs", ParamKind.PERCENT, 0f, 3f, 0f, dyn = true),
    FALLOFF(BrushGroup.STROKE, "Abklingen", ParamKind.PERCENT, 0f, 1f, 0f),

    // ---- Stabilisierung
    STREAMLINE(BrushGroup.STABILIZATION, "Stärke", ParamKind.PERCENT, 0f, 1f, 0.25f, section = "StreamLine"),
    STREAMLINE_PRESSURE(BrushGroup.STABILIZATION, "Druck", ParamKind.PERCENT, 0f, 1f, 0f),
    STABILIZE(BrushGroup.STABILIZATION, "Stärke", ParamKind.PERCENT, 0f, 1f, 0f, section = "Stabilisierung"),
    MOTION_FILTER(BrushGroup.STABILIZATION, "Stärke", ParamKind.PERCENT, 0f, 1f, 0f, section = "Bewegungsfilterung"),
    MOTION_FILTER_EXPR(BrushGroup.STABILIZATION, "Ausdruck", ParamKind.PERCENT, 0f, 1f, 0f),

    // ---- Verjüngung (Druck = Stift, Touch = Finger)
    TAPER_START(BrushGroup.TAPER, "Anfang", ParamKind.PERCENT, 0f, 1f, 0f, section = "Druck-Verjüngung"),
    TAPER_END(BrushGroup.TAPER, "Ende", ParamKind.PERCENT, 0f, 1f, 0f),
    TAPER_LINK(BrushGroup.TAPER, "Spitzengrößen verknüpfen", ParamKind.TOGGLE, 0f, 1f, 0f),
    TAPER_SIZE(BrushGroup.TAPER, "Größe", ParamKind.PERCENT, 0f, 1f, 0.9f),
    TAPER_OPACITY(BrushGroup.TAPER, "Deckkraft", ParamKind.PERCENT, 0f, 1f, 0f),
    TAPER_PRESSURE(BrushGroup.TAPER, "Druck", ParamKind.PERCENT, 0f, 1f, 0f),
    TAPER_TIP(BrushGroup.TAPER, "Spitze", ParamKind.PERCENT, 0f, 1f, 0.5f),
    TOUCH_TAPER_START(BrushGroup.TAPER, "Anfang", ParamKind.PERCENT, 0f, 1f, 0f, section = "Touch-Verjüngung"),
    TOUCH_TAPER_END(BrushGroup.TAPER, "Ende", ParamKind.PERCENT, 0f, 1f, 0f),
    TOUCH_TAPER_LINK(BrushGroup.TAPER, "Spitzengrößen verknüpfen", ParamKind.TOGGLE, 0f, 1f, 0f),
    TOUCH_TAPER_SIZE(BrushGroup.TAPER, "Größe", ParamKind.PERCENT, 0f, 1f, 0.9f),
    TOUCH_TAPER_OPACITY(BrushGroup.TAPER, "Deckkraft", ParamKind.PERCENT, 0f, 1f, 0f),
    TOUCH_TAPER_TIP(BrushGroup.TAPER, "Spitze", ParamKind.PERCENT, 0f, 1f, 0.5f),

    // ---- Form
    SHAPE_KIND(BrushGroup.SHAPE, "Formquelle", ParamKind.SHAPE, 0f, (ShapeKind.entries.size - 1).toFloat(), 0f, section = "Formquelle"),
    ROTATION_MODE(BrushGroup.SHAPE, "Eingabestil", ParamKind.CHOICE, 0f, 3f, 0f, RotationMode.labels, section = "Formverhalten"),
    ROTATION(BrushGroup.SHAPE, "Drehung", ParamKind.DEGREES, -180f, 180f, 0f, dyn = true),
    SCATTER(BrushGroup.SHAPE, "Streuung", ParamKind.PERCENT, 0f, 1f, 0f, dyn = true),
    SHAPE_RANDOMIZE(BrushGroup.SHAPE, "Zufällig (je Strich)", ParamKind.TOGGLE, 0f, 1f, 0f),
    COUNT(BrushGroup.SHAPE, "Anzahl", ParamKind.COUNT, 1f, 16f, 1f),
    COUNT_JITTER(BrushGroup.SHAPE, "Anzahl-Streuung", ParamKind.PERCENT, 0f, 1f, 0f),
    FLIP_X(BrushGroup.SHAPE, "Horizontal spiegeln", ParamKind.TOGGLE, 0f, 1f, 0f),
    FLIP_Y(BrushGroup.SHAPE, "Vertikal spiegeln", ParamKind.TOGGLE, 0f, 1f, 0f),
    ROUNDNESS(BrushGroup.SHAPE, "Rundheit", ParamKind.PERCENT, 0.05f, 1f, 1f, section = "Rundheit", dyn = true),
    ROUNDNESS_PRESSURE(BrushGroup.SHAPE, "Druck-Rundheit", ParamKind.PERCENT, 0f, 1f, 0f),
    TILT_ROUNDNESS(BrushGroup.SHAPE, "Neigungs-Rundheit", ParamKind.PERCENT, 0f, 1f, 0f),
    ROUNDNESS_JITTER(BrushGroup.SHAPE, "Rundheit-Streuung", ParamKind.PERCENT, 0f, 1f, 0f),
    SHAPE_FILTER(BrushGroup.SHAPE, "Formfilterung", ParamKind.CHOICE, 0f, 2f, 2f, arrayOf("Keine", "Klassisch", "Verbessert"), section = "Filterung"),

    // ---- Körnung
    GRAIN_KIND(BrushGroup.GRAIN, "Körnungsquelle", ParamKind.GRAIN, 0f, (GrainKind.entries.size - 1).toFloat(), 0f, section = "Körnungsquelle"),
    GRAIN_MODE(BrushGroup.GRAIN, "Verhalten", ParamKind.CHOICE, 0f, 1f, 0f, arrayOf("Texturiert", "Bewegt"), section = "Körnungsverhalten"),
    GRAIN_MOVEMENT(BrushGroup.GRAIN, "Bewegung", ParamKind.PERCENT, 0f, 1f, 1f),
    GRAIN_SCALE(BrushGroup.GRAIN, "Maßstab", ParamKind.FACTOR, 0.1f, 4f, 1f),
    GRAIN_ZOOM(BrushGroup.GRAIN, "Zoom (abgeschnitten → folgt Größe)", ParamKind.PERCENT, 0f, 1f, 0f),
    GRAIN_ROTATION(BrushGroup.GRAIN, "Drehung", ParamKind.SIGNED_PERCENT, -1f, 1f, 0f),
    GRAIN_DEPTH(BrushGroup.GRAIN, "Tiefe", ParamKind.PERCENT, 0f, 1f, 0.6f, dyn = true),
    GRAIN_DEPTH_MIN(BrushGroup.GRAIN, "Tiefe Minimum", ParamKind.PERCENT, 0f, 1f, 1f),
    GRAIN_DEPTH_JITTER(BrushGroup.GRAIN, "Tiefen-Streuung", ParamKind.PERCENT, 0f, 1f, 0f),
    GRAIN_OFFSET_JITTER(BrushGroup.GRAIN, "Versatz-Streuung", ParamKind.TOGGLE, 0f, 1f, 0f),
    GRAIN_INVERT(BrushGroup.GRAIN, "Invertieren", ParamKind.TOGGLE, 0f, 1f, 0f),
    GRAIN_BRIGHTNESS(BrushGroup.GRAIN, "Helligkeit", ParamKind.SIGNED_PERCENT, -1f, 1f, 0f, section = "Körnungsfarbe"),
    GRAIN_CONTRAST(BrushGroup.GRAIN, "Kontrast", ParamKind.SIGNED_PERCENT, -1f, 1f, 0f),
    GRAIN_FILTER(BrushGroup.GRAIN, "Körnungsfilterung", ParamKind.CHOICE, 0f, 2f, 2f, arrayOf("Keine", "Klassisch", "Verbessert"), section = "Filterung"),

    // ---- Rendering
    RENDER_MODE(BrushGroup.RENDERING, "Rendering-Modus", ParamKind.CHOICE, 0f, 5f, 0f, RenderMode.labels, section = "Rendering-Modus"),
    FLOW(BrushGroup.RENDERING, "Fluss", ParamKind.PERCENT, 0.01f, 1f, 1f, section = "Mischen", dyn = true),
    WET_EDGES(BrushGroup.RENDERING, "Nasse Kanten", ParamKind.PERCENT, 0f, 1f, 0f),
    BURNT_EDGES(BrushGroup.RENDERING, "Verbrannte Kanten", ParamKind.PERCENT, 0f, 1f, 0f),
    BURNT_MODE(BrushGroup.RENDERING, "Modus verbrannte Kanten", ParamKind.CHOICE, 0f, 3f, 0f, BurnMode.labels),
    BLEND(BrushGroup.RENDERING, "Mischmodus", ParamKind.CHOICE, 0f, (BrushBlend.labels.size - 1).toFloat(), 0f, BrushBlend.labels),
    LUMINANCE_BLEND(BrushGroup.RENDERING, "Luminanz-Mischen", ParamKind.TOGGLE, 0f, 1f, 0f),
    ALPHA_THRESHOLD(BrushGroup.RENDERING, "Alpha-Schwelle", ParamKind.TOGGLE, 0f, 1f, 0f),
    ALPHA_THRESHOLD_VALUE(BrushGroup.RENDERING, "Schwellenwert", ParamKind.PERCENT, 0.01f, 0.99f, 0.5f),

    // ---- Nasse Farbe
    DILUTION(BrushGroup.WET, "Verdünnung", ParamKind.PERCENT, 0f, 1f, 0f, dyn = true),
    CHARGE(BrushGroup.WET, "Ladung", ParamKind.PERCENT, 0f, 1f, 1f),
    ATTACK(BrushGroup.WET, "Anhaftung", ParamKind.PERCENT, 0f, 1f, 1f, dyn = true),
    PULL(BrushGroup.WET, "Ziehen", ParamKind.PERCENT, 0f, 1f, 0f),
    GRADE(BrushGroup.WET, "Körnigkeit", ParamKind.PERCENT, 0f, 1f, 0f),
    WET_BLUR(BrushGroup.WET, "Unschärfe", ParamKind.PERCENT, 0f, 1f, 0f, dyn = true),
    WET_BLUR_JITTER(BrushGroup.WET, "Unschärfe-Streuung", ParamKind.PERCENT, 0f, 1f, 0f),
    WETNESS_JITTER(BrushGroup.WET, "Nässe-Streuung", ParamKind.PERCENT, 0f, 1f, 0f),

    // ---- Farbdynamik
    HUE_JITTER(BrushGroup.COLOR, "Farbton", ParamKind.PERCENT, 0f, 1f, 0f, section = "Stempel-Farbstreuung", dyn = true),
    SAT_JITTER(BrushGroup.COLOR, "Sättigung", ParamKind.PERCENT, 0f, 1f, 0f, dyn = true),
    BRIGHT_JITTER(BrushGroup.COLOR, "Helligkeit", ParamKind.PERCENT, 0f, 1f, 0f, dyn = true),
    DARK_JITTER(BrushGroup.COLOR, "Dunkelheit", ParamKind.PERCENT, 0f, 1f, 0f, dyn = true),
    SECONDARY_JITTER(BrushGroup.COLOR, "Sekundärfarbe", ParamKind.PERCENT, 0f, 1f, 0f, dyn = true),
    STROKE_HUE_JITTER(BrushGroup.COLOR, "Farbton", ParamKind.PERCENT, 0f, 1f, 0f, section = "Strich-Farbstreuung"),
    STROKE_SAT_JITTER(BrushGroup.COLOR, "Sättigung", ParamKind.PERCENT, 0f, 1f, 0f),
    STROKE_BRIGHT_JITTER(BrushGroup.COLOR, "Helligkeit", ParamKind.PERCENT, 0f, 1f, 0f),
    STROKE_DARK_JITTER(BrushGroup.COLOR, "Dunkelheit", ParamKind.PERCENT, 0f, 1f, 0f),
    STROKE_SECONDARY_JITTER(BrushGroup.COLOR, "Sekundärfarbe", ParamKind.PERCENT, 0f, 1f, 0f),
    PRESSURE_HUE(BrushGroup.COLOR, "Farbton", ParamKind.PERCENT, 0f, 1f, 0f, section = "Farbdruck"),
    PRESSURE_SAT(BrushGroup.COLOR, "Sättigung", ParamKind.PERCENT, 0f, 1f, 0f),
    PRESSURE_BRIGHT(BrushGroup.COLOR, "Helligkeit", ParamKind.PERCENT, 0f, 1f, 0f),
    COLOR_PRESSURE(BrushGroup.COLOR, "Sekundärfarbe", ParamKind.PERCENT, 0f, 1f, 0f),
    TILT_HUE(BrushGroup.COLOR, "Farbton", ParamKind.PERCENT, 0f, 1f, 0f, section = "Farbneigung"),
    TILT_SAT(BrushGroup.COLOR, "Sättigung", ParamKind.PERCENT, 0f, 1f, 0f),
    TILT_BRIGHT(BrushGroup.COLOR, "Helligkeit", ParamKind.PERCENT, 0f, 1f, 0f),
    COLOR_TILT(BrushGroup.COLOR, "Sekundärfarbe", ParamKind.PERCENT, 0f, 1f, 0f),

    // ---- Dynamik
    SPEED_SIZE(BrushGroup.DYNAMICS, "Größe", ParamKind.SIGNED_PERCENT, -1f, 1f, 0f, section = "Tempo"),
    SPEED_OPACITY(BrushGroup.DYNAMICS, "Deckkraft", ParamKind.SIGNED_PERCENT, -1f, 1f, 0f),
    SPEED_SPACING(BrushGroup.DYNAMICS, "Abstand", ParamKind.SIGNED_PERCENT, -1f, 1f, 0f),
    SIZE_JITTER(BrushGroup.DYNAMICS, "Größe", ParamKind.PERCENT, 0f, 1f, 0f, section = "Streuung", dyn = true),
    OPACITY_JITTER(BrushGroup.DYNAMICS, "Deckkraft", ParamKind.PERCENT, 0f, 1f, 0f, dyn = true),

    // ---- Stift
    PRESSURE_SIZE(BrushGroup.PEN, "Größe", ParamKind.PERCENT, 0f, 1f, 0.7f, section = "Druck"),
    PRESSURE_OPACITY(BrushGroup.PEN, "Deckkraft", ParamKind.PERCENT, 0f, 1f, 0f),
    PRESSURE_FLOW(BrushGroup.PEN, "Fluss", ParamKind.PERCENT, 0f, 1f, 0f),
    PRESSURE_BLEED(BrushGroup.PEN, "Ausbluten", ParamKind.PERCENT, 0f, 1f, 0f),
    PRESSURE_SMOOTHING(BrushGroup.PEN, "Glättung", ParamKind.PERCENT, 0f, 1f, 0.1f),
    /** Neigung (ab Senkrechte), ab der die Neigungs-Dynamik einsetzt – darunter wirkt der Stift wie senkrecht. */
    TILT_ANGLE(BrushGroup.PEN, "Neigung wirkt ab", ParamKind.DEGREES, 0f, 80f, 0f, section = "Neigung"),
    TILT_OPACITY(BrushGroup.PEN, "Deckkraft", ParamKind.PERCENT, 0f, 1f, 0f),
    TILT_GRADATION(BrushGroup.PEN, "Abstufung", ParamKind.PERCENT, 0f, 1f, 0f),
    TILT_BLEED(BrushGroup.PEN, "Ausbluten", ParamKind.PERCENT, 0f, 1f, 0f),
    TILT_SIZE(BrushGroup.PEN, "Größe", ParamKind.PERCENT, 0f, 2f, 0f),
    FINGER_PRESSURE(BrushGroup.PEN, "Fingerdruck über Tempo simulieren", ParamKind.TOGGLE, 0f, 1f, 0f, section = "Finger"),
    CURSOR_OUTLINE(BrushGroup.PEN, "Cursor-Umriss", ParamKind.CHOICE, 0f, 2f, 1f, arrayOf("Keiner", "Kontrast", "Aktive Farbe"), section = "Schweben"),
    HOVER_PRESSURE(BrushGroup.PEN, "Geschätzter Schwebedruck", ParamKind.PERCENT, 0f, 1f, 0.5f),
    HOVER_FILL(BrushGroup.PEN, "Schwebefüllung", ParamKind.CHOICE, 0f, 2f, 1f, arrayOf("Keine", "Form", "Alles")),

    // ---- Eigenschaften
    STAMP_PREVIEW(BrushGroup.PROPERTIES, "Stempelvorschau verwenden", ParamKind.TOGGLE, 0f, 1f, 0f, section = "Pinseleigenschaften"),
    ORIENT_SCREEN(BrushGroup.PROPERTIES, "An Bildschirm ausrichten", ParamKind.TOGGLE, 0f, 1f, 0f),
    PREVIEW_SIZE(BrushGroup.PROPERTIES, "Vorschaugröße", ParamKind.PERCENT, 0f, 1f, 0.5f),
    SMUDGE_PICKUP(BrushGroup.PROPERTIES, "Wischen-Stärke", ParamKind.PERCENT, 0f, 1f, 0.5f),
    MAX_SIZE(BrushGroup.PROPERTIES, "Maximale Größe", ParamKind.PIXELS, 1f, 1000f, 120f, section = "Pinselverhalten"),
    MIN_SIZE(BrushGroup.PROPERTIES, "Minimale Größe", ParamKind.PIXELS, 0.5f, 100f, 1f),
    MAX_OPACITY(BrushGroup.PROPERTIES, "Maximale Deckkraft", ParamKind.PERCENT, 0f, 1f, 1f),
    MIN_OPACITY(BrushGroup.PROPERTIES, "Minimale Deckkraft", ParamKind.PERCENT, 0f, 1f, 0f),

    // ---- Vorschau (Bibliotheksvorschau)
    PREVIEW_PRESSURE_MIN(BrushGroup.PREVIEW, "Druck-Minimum", ParamKind.PERCENT, 0f, 1f, 0.25f, section = "Strichvorschau"),
    PREVIEW_PRESSURE_SCALE(BrushGroup.PREVIEW, "Druck-Maßstab", ParamKind.PERCENT, 0f, 1f, 1f),
    PREVIEW_WET(BrushGroup.PREVIEW, "Nasse Farbe in Vorschau", ParamKind.TOGGLE, 0f, 1f, 0f),
    PREVIEW_TILT(BrushGroup.PREVIEW, "Neigungswinkel", ParamKind.DEGREES, 0f, 90f, 0f),
    ;

    val isNumeric: Boolean
        get() = kind != ParamKind.TOGGLE && kind != ParamKind.CHOICE && kind != ParamKind.SHAPE && kind != ParamKind.GRAIN

    /** Wert als Text für das Studio. */
    fun format(v: Float): String = when (kind) {
        ParamKind.PERCENT -> "${(v * 100).roundToInt()} %"
        ParamKind.SIGNED_PERCENT -> (if (v > 0.004f) "+" else "") + "${(v * 100).roundToInt()} %"
        ParamKind.PIXELS -> if (v < 10f) "%.1f px".format(v) else "${v.roundToInt()} px"
        ParamKind.DEGREES -> "${v.roundToInt()}°"
        ParamKind.FACTOR -> "%.2f×".format(v)
        ParamKind.COUNT -> "${v.roundToInt()}"
        ParamKind.TOGGLE -> if (v >= 0.5f) "Ein" else "Aus"
        ParamKind.CHOICE -> options?.getOrNull(v.roundToInt()) ?: ""
        ParamKind.SHAPE -> ShapeKind.entries.getOrNull(v.roundToInt())?.label ?: ""
        ParamKind.GRAIN -> GrainKind.entries.getOrNull(v.roundToInt())?.label ?: ""
    }

    /** Anzeigewert ↔ gespeicherter Wert (für die Zahleneingabe): Prozent in %, sonst roh. */
    fun toDisplay(v: Float): Float = when (kind) {
        ParamKind.PERCENT, ParamKind.SIGNED_PERCENT -> v * 100f
        else -> v
    }
    fun fromDisplay(d: Float): Float = when (kind) {
        ParamKind.PERCENT, ParamKind.SIGNED_PERCENT -> d / 100f
        else -> d
    }.coerceIn(min, max)

    companion object {
        fun defaults(): FloatArray = FloatArray(entries.size) { entries[it].def }
        fun byName(n: String): BP? = entries.firstOrNull { it.name == n }
    }
}

/**
 * Ein Pinsel. [builtIn] = Teil der mitgelieferten Bibliothek (nicht löschbar, Änderungen erzeugen eine Kopie).
 * [rev] wird bei jeder Änderung erhöht (Vorschau-Cache).
 */
class Brush(
    val id: String,
    var name: String,
    var setId: String,
    val builtIn: Boolean,
    val v: FloatArray = BP.defaults(),
    /** Ursprungs-Pinsel bei Kopien (für "Zurücksetzen"). */
    var basedOn: String? = null,
    var created: Long = System.currentTimeMillis(),
) {
    var rev: Int = 0

    /**
     * Kopplung je Parameter an Druck ([couplings] `[2*i]`) und Neigung (`[2*i+1]`), jeweils −1..1.
     * Positiv: weniger Druck bzw. flacherer Stift verringert den Wert; negativ: vergrößert ihn.
     */
    val couplings: FloatArray = FloatArray(BP.entries.size * 2)

    /**
     * Druckdiagramm des Pinsels: Stützpunkte (x0,y0,x1,y1,…) sortiert nach x, inklusive der Endpunkte
     * bei x = 0 und x = 1. null = linear.
     */
    var pressureCurve: FloatArray? = null

    /** Eigene Form (ALPHA_8, 256×256, weiß = Farbe). Überschreibt [BP.SHAPE_KIND]. */
    var shapeImage: Bitmap? = null
    /** Eigene Körnung (ALPHA_8, 256×256, kachelbar). Überschreibt [BP.GRAIN_KIND]. */
    var grainImage: Bitmap? = null

    /** "Über diesen Pinsel": Autor und Unterschrift (Linienzüge in 0..1-Koordinaten). */
    var author: String = ""
    val signature: MutableList<FloatArray> = ArrayList()
    /** Rücksetzpunkt (JSON eines früheren Zustands, siehe [snapshot]). */
    var resetPoint: String? = null
    var resetPointTime: Long = 0L

    operator fun get(p: BP): Float = v[p.ordinal]
    operator fun set(p: BP, x: Float) { v[p.ordinal] = x.coerceIn(p.min, p.max) }
    fun int(p: BP): Int = v[p.ordinal].roundToInt()
    fun on(p: BP): Boolean = v[p.ordinal] >= 0.5f

    fun pressureCoupling(p: BP) = couplings[p.ordinal * 2]
    fun tiltCoupling(p: BP) = couplings[p.ordinal * 2 + 1]
    fun setCoupling(p: BP, pressure: Float, tilt: Float) {
        couplings[p.ordinal * 2] = pressure.coerceIn(-1f, 1f)
        couplings[p.ordinal * 2 + 1] = tilt.coerceIn(-1f, 1f)
    }
    val hasCouplings: Boolean get() = couplings.any { it != 0f }

    /** Wendet das Druckdiagramm an (0..1 → 0..1). */
    fun mapPressure(p: Float): Float {
        val c = pressureCurve ?: return p
        return Curves.eval(c, p)
    }

    /** Übernimmt alle Einstellungen (nicht ID/Name/Set/Info) von [o]. */
    fun copySettingsFrom(o: Brush) {
        o.v.copyInto(v)
        o.couplings.copyInto(couplings)
        pressureCurve = o.pressureCurve?.copyOf()
        shapeImage = o.shapeImage
        grainImage = o.grainImage
    }

    private fun copyExtrasTo(b: Brush): Brush {
        couplings.copyInto(b.couplings)
        b.pressureCurve = pressureCurve?.copyOf()
        b.shapeImage = shapeImage
        b.grainImage = grainImage
        b.author = author
        signature.forEach { b.signature.add(it.copyOf()) }
        b.resetPoint = resetPoint
        b.resetPointTime = resetPointTime
        return b
    }

    /** Kopie mit neuer ID (eigener Pinsel). */
    fun duplicate(newName: String = name, newSet: String = setId): Brush =
        copyExtrasTo(Brush(UUID.randomUUID().toString(), newName, newSet, false, v.copyOf(), basedOn ?: id))

    /** Exakte Kopie (gleiche ID) – für Arbeitskopien im Studio. */
    fun workingCopy(): Brush =
        copyExtrasTo(Brush(id, name, setId, builtIn, v.copyOf(), basedOn, created)).also { it.rev = rev }

    fun sameParams(o: Brush): Boolean =
        v.contentEquals(o.v) && name == o.name && couplings.contentEquals(o.couplings) &&
            (pressureCurve?.contentEquals(o.pressureCurve ?: FloatArray(0)) ?: (o.pressureCurve == null)) &&
            shapeImage === o.shapeImage && grainImage === o.grainImage && author == o.author &&
            signature.size == o.signature.size && signature.indices.all { signature[it].contentEquals(o.signature[it]) } &&
            resetPoint == o.resetPoint

    /** Einstellungen als JSON (für Rücksetzpunkte). Bilder werden nicht mitgespeichert. */
    fun snapshot(): String = JSONObject().also { o ->
        o.put("params", paramsJson())
        if (hasCouplings) o.put("couplings", couplingsJson())
        pressureCurve?.let { o.put("pressureCurve", floatsJson(it)) }
    }.toString()

    /** Stellt einen [snapshot] wieder her. */
    fun restoreSnapshot(s: String) {
        val o = JSONObject(s)
        BP.defaults().copyInto(v)
        couplings.fill(0f)
        readParams(o)
    }

    private fun paramsJson() = JSONObject().also { p -> for (e in BP.entries) p.put(e.name, v[e.ordinal].toDouble()) }
    private fun couplingsJson() = JSONObject().also { c ->
        for (e in BP.entries) {
            val a = couplings[e.ordinal * 2]; val t = couplings[e.ordinal * 2 + 1]
            if (a != 0f || t != 0f) c.put(e.name, JSONArray().put(a.toDouble()).put(t.toDouble()))
        }
    }

    internal fun readParams(o: JSONObject) {
        o.optJSONObject("params")?.let { p ->
            val it = p.keys()
            while (it.hasNext()) {
                val k = it.next()
                BP.byName(k)?.let { e -> this[e] = p.optDouble(k, e.def.toDouble()).toFloat() }
            }
            migrate(p)
        }
        o.optJSONObject("couplings")?.let { c ->
            val it = c.keys()
            while (it.hasNext()) {
                val k = it.next()
                val e = BP.byName(k) ?: continue
                val a = c.optJSONArray(k) ?: continue
                setCoupling(e, a.optDouble(0, 0.0).toFloat(), a.optDouble(1, 0.0).toFloat())
            }
        }
        pressureCurve = o.optJSONArray("pressureCurve")?.let { readFloats(it) }?.takeIf { it.size >= 4 }
    }

    /** Ältere Pinsel-Dateien: neue Parameter aus alten ableiten. */
    private fun migrate(p: JSONObject) {
        if (!p.has(BP.TOUCH_TAPER_START.name)) {
            this[BP.TOUCH_TAPER_START] = this[BP.TAPER_START]; this[BP.TOUCH_TAPER_END] = this[BP.TAPER_END]
            this[BP.TOUCH_TAPER_SIZE] = this[BP.TAPER_SIZE]; this[BP.TOUCH_TAPER_OPACITY] = this[BP.TAPER_OPACITY]
            this[BP.TOUCH_TAPER_TIP] = this[BP.TAPER_TIP]
            // früher: "Nur bei Fingereingabe" → Stift ohne Verjüngung
            if (p.optDouble("TAPER_TOUCH_ONLY", 0.0) >= 0.5) { this[BP.TAPER_START] = 0f; this[BP.TAPER_END] = 0f }
        }
        if (!p.has(BP.JITTER_LONG.name)) this[BP.JITTER_LONG] = this[BP.JITTER]
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("name", name); o.put("set", setId); o.put("created", created)
        basedOn?.let { o.put("basedOn", it) }
        o.put("params", paramsJson())
        if (hasCouplings) o.put("couplings", couplingsJson())
        pressureCurve?.let { o.put("pressureCurve", floatsJson(it)) }
        if (author.isNotEmpty()) o.put("author", author)
        if (signature.isNotEmpty()) o.put("signature", JSONArray().also { a -> signature.forEach { a.put(floatsJson(it)) } })
        resetPoint?.let { o.put("resetPoint", it); o.put("resetPointTime", resetPointTime) }
        return o
    }

    companion object {
        /** Liest einen (eigenen) Pinsel. Unbekannte Parameter werden ignoriert, fehlende bekommen Standardwerte. */
        fun fromJson(o: JSONObject, forceNewId: Boolean = false): Brush {
            val id = if (forceNewId) UUID.randomUUID().toString() else o.optString("id", UUID.randomUUID().toString())
            val b = Brush(
                id, o.optString("name", "Pinsel"), o.optString("set", BrushLibrary.SET_CUSTOM), false,
                basedOn = if (o.has("basedOn")) o.getString("basedOn") else null,
                created = o.optLong("created", System.currentTimeMillis()),
            )
            b.readParams(o)
            b.author = o.optString("author", "")
            o.optJSONArray("signature")?.let { a ->
                for (i in 0 until a.length()) a.optJSONArray(i)?.let { b.signature.add(readFloats(it)) }
            }
            if (o.has("resetPoint")) {
                b.resetPoint = o.optString("resetPoint")
                b.resetPointTime = o.optLong("resetPointTime", 0L)
            }
            return b
        }

        private fun floatsJson(f: FloatArray) = JSONArray().also { a -> f.forEach { a.put(it.toDouble()) } }
        private fun readFloats(a: JSONArray) = FloatArray(a.length()) { a.optDouble(it, 0.0).toFloat() }
    }
}

/** Stückweise glatte Kurven (Druckdiagramm). */
object Curves {
    /**
     * Wertet eine Kurve aus Stützpunkten (x,y-Paare, x aufsteigend, 0..1) aus. Monotone kubische
     * Interpolation (Fritsch-Carlson) → keine Überschwinger, glatt. Keine Allokation.
     */
    fun eval(c: FloatArray, x: Float): Float {
        val n = c.size / 2
        if (n < 2) return x
        val t = x.coerceIn(0f, 1f)
        if (t <= c[0]) return c[1].coerceIn(0f, 1f)
        if (t >= c[2 * (n - 1)]) return c[2 * (n - 1) + 1].coerceIn(0f, 1f)
        var i = 0
        while (i < n - 2 && t > c[2 * (i + 1)]) i++
        val x0 = c[2 * i]; val y0 = c[2 * i + 1]; val x1 = c[2 * i + 2]; val y1 = c[2 * i + 3]
        val h = (x1 - x0).coerceAtLeast(1e-5f)
        val d = (y1 - y0) / h
        val m0 = tangent(c, i, n); val m1 = tangent(c, i + 1, n)
        val s = (t - x0) / h
        val s2 = s * s; val s3 = s2 * s
        val y = (2 * s3 - 3 * s2 + 1) * y0 + (s3 - 2 * s2 + s) * h * m0 + (-2 * s3 + 3 * s2) * y1 + (s3 - s2) * h * m1
        // ohne Tangenten-Begrenzung kann es minimal überschwingen
        return if (d == 0f) y0.coerceIn(0f, 1f) else y.coerceIn(0f, 1f)
    }

    private fun slope(c: FloatArray, i: Int): Float {
        val h = (c[2 * i + 2] - c[2 * i]).coerceAtLeast(1e-5f)
        return (c[2 * i + 3] - c[2 * i + 1]) / h
    }

    private fun tangent(c: FloatArray, i: Int, n: Int): Float {
        if (i == 0) return slope(c, 0)
        if (i == n - 1) return slope(c, n - 2)
        val a = slope(c, i - 1); val b = slope(c, i)
        if (a * b <= 0f) return 0f
        return 2f / (1f / a + 1f / b)
    }

    /** Lineare Kurve (Standard). */
    fun linear() = floatArrayOf(0f, 0f, 1f, 1f)
}
