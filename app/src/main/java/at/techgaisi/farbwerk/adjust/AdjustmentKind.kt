package at.techgaisi.farbwerk.adjust

import kotlin.math.roundToInt

/**
 * Ein Regler einer Anpassung.
 * @param group Regler wird nur angezeigt, wenn der Auswahl-Parameter [groupOf] den Wert [group] hat (-1 = immer).
 * @param choices Wenn gesetzt: diskreter Auswahl-Parameter (Wert = Index).
 */
class AdjParam(
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "%",
    val signed: Boolean = false,
    val choices: List<String>? = null,
    val group: Int = -1,
    val groupOf: Int = -1,
) {
    fun format(v: Float): String {
        if (choices != null) return choices.getOrElse(v.roundToInt()) { "" }
        val i = v.roundToInt()
        return if (signed && i > 0) "+$i $unit".trim() else "$i $unit".trim()
    }
}

/** Alle Anpassungen (Reihenfolge = Menü). */
enum class AdjustmentKind(
    val label: String,
    /** Index des Parameters, der durch horizontales Ziehen auf der Leinwand gesteuert wird (-1 = keiner). */
    val dragParam: Int = -1,
    /** Unterstützt den Stift-Modus (Anpassung mit dem Pinsel auftragen). */
    val pencil: Boolean = true,
    /** Menügruppe (für Trennlinien). */
    val section: Int = 0,
) {
    HSB("Farbton, Sättigung, Helligkeit", section = 0),
    COLOR_BALANCE("Farbbalance", section = 0),
    CURVES("Kurven", section = 0),
    GRADIENT_MAP("Verlaufsumsetzung", section = 0),
    GAUSSIAN_BLUR("Gaußscher Weichzeichner", dragParam = 0, section = 1),
    MOTION_BLUR("Bewegungsunschärfe", dragParam = 0, section = 1),
    PERSPECTIVE_BLUR("Perspektivische Unschärfe", dragParam = 1, section = 1),
    NOISE("Rauschen", dragParam = 0, section = 2),
    SHARPEN("Scharfzeichnen", dragParam = 0, section = 2),
    BLOOM("Bloom", dragParam = 0, section = 2),
    GLITCH("Glitch", dragParam = 1, section = 2),
    HALFTONE("Halbton", dragParam = 1, section = 2),
    CHROMATIC("Chromatische Aberration", dragParam = 0, section = 2),
    LIQUIFY("Verflüssigen", pencil = false, section = 3),
    CLONE("Klonen", pencil = false, section = 3);

    val params: List<AdjParam> by lazy { paramsFor(this) }

    /** Benötigt einen verschiebbaren Mittelpunkt auf der Leinwand. */
    val hasCenter: Boolean get() = this == PERSPECTIVE_BLUR || this == CHROMATIC

    val isBrushTool: Boolean get() = this == LIQUIFY || this == CLONE

    companion object {
        val COLOR_BALANCE_RANGES = listOf("Schatten", "Mitteltöne", "Lichter")
        val LIQUIFY_MODES = listOf(
            "Schieben", "Zwirbeln rechts", "Zwirbeln links", "Zusammenziehen", "Aufblähen", "Kristalle", "Kante", "Rekonstruieren",
        )

        private fun paramsFor(k: AdjustmentKind): List<AdjParam> = when (k) {
            HSB -> listOf(
                AdjParam("Farbton", -100f, 100f, 0f, signed = true),
                AdjParam("Sättigung", -100f, 100f, 0f, signed = true),
                AdjParam("Helligkeit", -100f, 100f, 0f, signed = true),
            )
            COLOR_BALANCE -> buildList {
                add(AdjParam("Tonbereich", 0f, 2f, 1f, choices = COLOR_BALANCE_RANGES))
                for (g in 0..2) {
                    add(AdjParam("Cyan – Rot", -100f, 100f, 0f, signed = true, group = g, groupOf = 0))
                    add(AdjParam("Magenta – Grün", -100f, 100f, 0f, signed = true, group = g, groupOf = 0))
                    add(AdjParam("Gelb – Blau", -100f, 100f, 0f, signed = true, group = g, groupOf = 0))
                }
            }
            CURVES -> emptyList()
            GRADIENT_MAP -> listOf(AdjParam("Stärke", 0f, 100f, 100f))
            GAUSSIAN_BLUR -> listOf(AdjParam("Weichzeichner", 0f, 100f, 0f))
            MOTION_BLUR -> listOf(
                AdjParam("Bewegungsunschärfe", 0f, 100f, 0f),
                AdjParam("Winkel", 0f, 360f, 0f, unit = "°"),
            )
            PERSPECTIVE_BLUR -> listOf(
                AdjParam("Art", 0f, 1f, 0f, choices = listOf("Positionsbezogen", "Richtungsbezogen")),
                AdjParam("Unschärfe", 0f, 100f, 0f),
                AdjParam("Winkel", 0f, 360f, 0f, unit = "°", group = 1, groupOf = 0),
            )
            NOISE -> listOf(
                AdjParam("Rauschen", 0f, 100f, 0f),
                AdjParam("Art", 0f, 2f, 0f, choices = listOf("Wolken", "Wogen", "Kämme")),
                AdjParam("Größe", 1f, 100f, 25f),
                AdjParam("Oktaven", 1f, 6f, 3f, unit = ""),
                AdjParam("Turbulenz", 0f, 100f, 0f),
            )
            SHARPEN -> listOf(AdjParam("Scharfzeichnen", 0f, 100f, 0f))
            BLOOM -> listOf(
                AdjParam("Bloom", 0f, 100f, 0f),
                AdjParam("Übergang", 0f, 100f, 60f),
                AdjParam("Größe", 0f, 100f, 30f),
                AdjParam("Brennen", 0f, 100f, 0f),
            )
            GLITCH -> listOf(
                AdjParam("Art", 0f, 3f, 0f, choices = listOf("Artefakt", "Welle", "Signal", "Divergieren")),
                AdjParam("Glitch", 0f, 100f, 0f),
                AdjParam("Größe", 0f, 100f, 40f),
                AdjParam("Variation", 0f, 100f, 0f),
            )
            HALFTONE -> listOf(
                AdjParam("Art", 0f, 1f, 0f, choices = listOf("Vollfarbe", "Zeitung")),
                AdjParam("Halbton", 0f, 100f, 0f),
            )
            CHROMATIC -> listOf(
                AdjParam("Verschiebung", 0f, 100f, 0f),
                AdjParam("Übergang", 0f, 100f, 50f),
            )
            LIQUIFY -> listOf(
                AdjParam("Modus", 0f, (LIQUIFY_MODES.size - 1).toFloat(), 0f, choices = LIQUIFY_MODES),
                AdjParam("Größe", 1f, 100f, 25f),
                AdjParam("Druck", 1f, 100f, 50f),
                AdjParam("Verzerrung", 0f, 100f, 0f),
                AdjParam("Dynamik", 0f, 100f, 0f),
            )
            CLONE -> listOf(
                AdjParam("Größe", 1f, 100f, 20f),
                AdjParam("Deckkraft", 1f, 100f, 100f),
                AdjParam("Härte", 0f, 100f, 50f),
            )
        }
    }
}

/** Voreingestellte Verläufe für die Verlaufsumsetzung (Farbstopps ARGB, gleichmäßig verteilt). */
object GradientPresets {
    class Preset(val name: String, val colors: IntArray)

    /** Index 0 wird zur Laufzeit aus der aktuellen Farbe gebildet. */
    fun all(current: Int): List<Preset> = listOf(
        Preset("Aktuelle Farbe", intArrayOf(0xFF000000.toInt(), current, 0xFFFFFFFF.toInt())),
        Preset("Noir", intArrayOf(0xFF050505.toInt(), 0xFF4A4A4A.toInt(), 0xFFF2F2F2.toInt())),
        Preset("Sonnenuntergang", intArrayOf(0xFF1B0A3A.toInt(), 0xFFB0255E.toInt(), 0xFFF58A3C.toInt(), 0xFFFFE3A0.toInt())),
        Preset("Ozean", intArrayOf(0xFF021428.toInt(), 0xFF0B5C8C.toInt(), 0xFF38B3C9.toInt(), 0xFFE2FAF7.toInt())),
        Preset("Vintage", intArrayOf(0xFF2B1D14.toInt(), 0xFF8A6A48.toInt(), 0xFFE7D3A8.toInt())),
        Preset("Neon", intArrayOf(0xFF12002B.toInt(), 0xFFFF2E9A.toInt(), 0xFF2EF2FF.toInt())),
        Preset("Wärmebild", intArrayOf(0xFF000020.toInt(), 0xFF6A00A8.toInt(), 0xFFE0303A.toInt(), 0xFFFFB000.toInt(), 0xFFFFFFC8.toInt())),
        Preset("Wald", intArrayOf(0xFF0E1A0E.toInt(), 0xFF2F5D2A.toInt(), 0xFF9CC46B.toInt(), 0xFFF1F5D6.toInt())),
    )

    /** 256-Einträge-LUT (ARGB) aus Farbstopps. */
    fun lut(colors: IntArray): IntArray {
        val out = IntArray(256)
        val n = colors.size - 1
        for (i in 0 until 256) {
            val t = i / 255f * n
            val k = t.toInt().coerceAtMost(n - 1)
            val f = t - k
            val a = colors[k]
            val b = colors[k + 1]
            fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).roundToInt()) and 0xFF
            out[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return out
    }
}
