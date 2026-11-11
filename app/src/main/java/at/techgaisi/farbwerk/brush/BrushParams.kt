package at.techgaisi.farbwerk.brush

import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/** Attribut-Gruppen des Pinsel-Studios (linke Liste). */
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
    INFO("Info"),
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

/** Ausrichtung der Pinselform. */
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
        else -> null
    }
}

/**
 * Alle Pinsel-Parameter. Jeder Pinsel speichert die Werte in einem FloatArray (Index = ordinal), dadurch
 * sind Studio-UI, JSON-Speicherung und Engine generisch und neue Parameter kosten nur eine Zeile.
 */
enum class BP(
    val group: BrushGroup,
    val label: String,
    val kind: ParamKind,
    val min: Float,
    val max: Float,
    val def: Float,
    val options: Array<String>? = null,
) {
    // ---- Strichpfad
    SPACING(BrushGroup.STROKE, "Abstand", ParamKind.PERCENT, 0.01f, 3f, 0.08f),
    JITTER(BrushGroup.STROKE, "Streuung", ParamKind.PERCENT, 0f, 3f, 0f),
    FALLOFF(BrushGroup.STROKE, "Abklingen", ParamKind.PERCENT, 0f, 1f, 0f),

    // ---- Stabilisierung
    STREAMLINE(BrushGroup.STABILIZATION, "StreamLine", ParamKind.PERCENT, 0f, 1f, 0.25f),
    STABILIZE(BrushGroup.STABILIZATION, "Stabilisierung", ParamKind.PERCENT, 0f, 1f, 0f),
    MOTION_FILTER(BrushGroup.STABILIZATION, "Bewegungsfilterung", ParamKind.PERCENT, 0f, 1f, 0f),

    // ---- Verjüngung
    TAPER_START(BrushGroup.TAPER, "Länge am Anfang", ParamKind.PERCENT, 0f, 1f, 0f),
    TAPER_END(BrushGroup.TAPER, "Länge am Ende", ParamKind.PERCENT, 0f, 1f, 0f),
    TAPER_SIZE(BrushGroup.TAPER, "Größe", ParamKind.PERCENT, 0f, 1f, 0.9f),
    TAPER_OPACITY(BrushGroup.TAPER, "Deckkraft", ParamKind.PERCENT, 0f, 1f, 0f),
    TAPER_TIP(BrushGroup.TAPER, "Spitze", ParamKind.PERCENT, 0f, 1f, 0.5f),
    TAPER_TOUCH_ONLY(BrushGroup.TAPER, "Nur bei Fingereingabe", ParamKind.TOGGLE, 0f, 1f, 0f),

    // ---- Form
    SHAPE_KIND(BrushGroup.SHAPE, "Formquelle", ParamKind.SHAPE, 0f, (ShapeKind.entries.size - 1).toFloat(), 0f),
    ROUNDNESS(BrushGroup.SHAPE, "Rundheit", ParamKind.PERCENT, 0.05f, 1f, 1f),
    ROTATION(BrushGroup.SHAPE, "Drehung", ParamKind.DEGREES, -180f, 180f, 0f),
    ROTATION_MODE(BrushGroup.SHAPE, "Ausrichtung", ParamKind.CHOICE, 0f, 3f, 0f, RotationMode.labels),
    SCATTER(BrushGroup.SHAPE, "Drehstreuung", ParamKind.PERCENT, 0f, 1f, 0f),
    COUNT(BrushGroup.SHAPE, "Anzahl", ParamKind.COUNT, 1f, 16f, 1f),
    COUNT_JITTER(BrushGroup.SHAPE, "Anzahl-Streuung", ParamKind.PERCENT, 0f, 1f, 0f),
    FLIP_X(BrushGroup.SHAPE, "Zufällig horizontal spiegeln", ParamKind.TOGGLE, 0f, 1f, 0f),
    FLIP_Y(BrushGroup.SHAPE, "Zufällig vertikal spiegeln", ParamKind.TOGGLE, 0f, 1f, 0f),
    TILT_ROUNDNESS(BrushGroup.SHAPE, "Neigung → Rundheit", ParamKind.PERCENT, 0f, 1f, 0f),

    // ---- Körnung
    GRAIN_KIND(BrushGroup.GRAIN, "Körnungsquelle", ParamKind.GRAIN, 0f, (GrainKind.entries.size - 1).toFloat(), 0f),
    GRAIN_MODE(BrushGroup.GRAIN, "Verhalten", ParamKind.CHOICE, 0f, 1f, 0f, arrayOf("Texturiert", "Bewegt")),
    GRAIN_SCALE(BrushGroup.GRAIN, "Maßstab", ParamKind.FACTOR, 0.1f, 4f, 1f),
    GRAIN_DEPTH(BrushGroup.GRAIN, "Tiefe", ParamKind.PERCENT, 0f, 1f, 0.6f),
    GRAIN_INVERT(BrushGroup.GRAIN, "Invertieren", ParamKind.TOGGLE, 0f, 1f, 0f),

    // ---- Rendering
    RENDER_MODE(BrushGroup.RENDERING, "Rendering-Modus", ParamKind.CHOICE, 0f, 5f, 0f, RenderMode.labels),
    FLOW(BrushGroup.RENDERING, "Fluss", ParamKind.PERCENT, 0.01f, 1f, 1f),
    BLEND(BrushGroup.RENDERING, "Mischmodus", ParamKind.CHOICE, 0f, (BrushBlend.labels.size - 1).toFloat(), 0f, BrushBlend.labels),

    // ---- Nasse Farbe
    DILUTION(BrushGroup.WET, "Verdünnung", ParamKind.PERCENT, 0f, 1f, 0f),
    CHARGE(BrushGroup.WET, "Ladung", ParamKind.PERCENT, 0f, 1f, 1f),
    PULL(BrushGroup.WET, "Ziehen", ParamKind.PERCENT, 0f, 1f, 0f),
    WETNESS_JITTER(BrushGroup.WET, "Nässe-Streuung", ParamKind.PERCENT, 0f, 1f, 0f),

    // ---- Farbdynamik
    HUE_JITTER(BrushGroup.COLOR, "Farbton-Streuung (Tupfer)", ParamKind.PERCENT, 0f, 1f, 0f),
    SAT_JITTER(BrushGroup.COLOR, "Sättigungs-Streuung (Tupfer)", ParamKind.PERCENT, 0f, 1f, 0f),
    BRIGHT_JITTER(BrushGroup.COLOR, "Helligkeits-Streuung (Tupfer)", ParamKind.PERCENT, 0f, 1f, 0f),
    STROKE_HUE_JITTER(BrushGroup.COLOR, "Farbton-Streuung (Strich)", ParamKind.PERCENT, 0f, 1f, 0f),
    STROKE_BRIGHT_JITTER(BrushGroup.COLOR, "Helligkeits-Streuung (Strich)", ParamKind.PERCENT, 0f, 1f, 0f),
    COLOR_PRESSURE(BrushGroup.COLOR, "Druck → Sekundärfarbe", ParamKind.PERCENT, 0f, 1f, 0f),
    COLOR_TILT(BrushGroup.COLOR, "Neigung → Sekundärfarbe", ParamKind.PERCENT, 0f, 1f, 0f),

    // ---- Dynamik
    SPEED_SIZE(BrushGroup.DYNAMICS, "Tempo → Größe", ParamKind.SIGNED_PERCENT, -1f, 1f, 0f),
    SPEED_OPACITY(BrushGroup.DYNAMICS, "Tempo → Deckkraft", ParamKind.PERCENT, 0f, 1f, 0f),
    SIZE_JITTER(BrushGroup.DYNAMICS, "Größen-Streuung", ParamKind.PERCENT, 0f, 1f, 0f),
    OPACITY_JITTER(BrushGroup.DYNAMICS, "Deckkraft-Streuung", ParamKind.PERCENT, 0f, 1f, 0f),

    // ---- Stift
    PRESSURE_SIZE(BrushGroup.PEN, "Druck → Größe", ParamKind.PERCENT, 0f, 1f, 0.7f),
    PRESSURE_OPACITY(BrushGroup.PEN, "Druck → Deckkraft", ParamKind.PERCENT, 0f, 1f, 0f),
    PRESSURE_FLOW(BrushGroup.PEN, "Druck → Fluss", ParamKind.PERCENT, 0f, 1f, 0f),
    PRESSURE_SMOOTHING(BrushGroup.PEN, "Druckglättung", ParamKind.PERCENT, 0f, 1f, 0.1f),
    TILT_SIZE(BrushGroup.PEN, "Neigung → Größe", ParamKind.PERCENT, 0f, 2f, 0f),
    TILT_OPACITY(BrushGroup.PEN, "Neigung → Deckkraft", ParamKind.PERCENT, 0f, 1f, 0f),
    FINGER_PRESSURE(BrushGroup.PEN, "Fingerdruck über Tempo simulieren", ParamKind.TOGGLE, 0f, 1f, 0f),

    // ---- Eigenschaften
    MAX_SIZE(BrushGroup.PROPERTIES, "Maximale Größe", ParamKind.PIXELS, 1f, 1000f, 120f),
    MIN_SIZE(BrushGroup.PROPERTIES, "Minimale Größe", ParamKind.PIXELS, 0.5f, 100f, 1f),
    MAX_OPACITY(BrushGroup.PROPERTIES, "Maximale Deckkraft", ParamKind.PERCENT, 0f, 1f, 1f),
    MIN_OPACITY(BrushGroup.PROPERTIES, "Minimale Deckkraft", ParamKind.PERCENT, 0f, 1f, 0f),
    PREVIEW_SIZE(BrushGroup.PROPERTIES, "Vorschaugröße", ParamKind.PERCENT, 0f, 1f, 0.5f),
    SMUDGE_PICKUP(BrushGroup.PROPERTIES, "Wischen: Farbaufnahme", ParamKind.PERCENT, 0f, 1f, 0.5f),
    ;

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

    operator fun get(p: BP): Float = v[p.ordinal]
    operator fun set(p: BP, x: Float) { v[p.ordinal] = x.coerceIn(p.min, p.max) }
    fun int(p: BP): Int = v[p.ordinal].roundToInt()
    fun on(p: BP): Boolean = v[p.ordinal] >= 0.5f

    /** Kopie mit neuer ID (eigener Pinsel). */
    fun duplicate(newName: String = name, newSet: String = setId): Brush =
        Brush(UUID.randomUUID().toString(), newName, newSet, false, v.copyOf(), basedOn ?: id)

    /** Exakte Kopie (gleiche ID) – für Arbeitskopien im Studio. */
    fun workingCopy(): Brush = Brush(id, name, setId, builtIn, v.copyOf(), basedOn, created).also { it.rev = rev }

    fun sameParams(o: Brush): Boolean = v.contentEquals(o.v) && name == o.name

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("name", name); o.put("set", setId); o.put("created", created)
        basedOn?.let { o.put("basedOn", it) }
        val p = JSONObject()
        for (e in BP.entries) p.put(e.name, v[e.ordinal].toDouble())
        o.put("params", p)
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
            o.optJSONObject("params")?.let { p ->
                val it = p.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    BP.byName(k)?.let { e -> b[e] = p.optDouble(k, e.def.toDouble()).toFloat() }
                }
            }
            return b
        }
    }
}
