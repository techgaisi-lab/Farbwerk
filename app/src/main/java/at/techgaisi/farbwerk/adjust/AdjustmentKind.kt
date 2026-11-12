package at.techgaisi.farbwerk.adjust

import kotlin.math.roundToInt

/**
 * Ein Regler einer Anpassung.
 * @param group Regler wird nur angezeigt, wenn der Auswahl-Parameter [groupOf] den Wert [group] hat (-1 = immer).
 * @param choices Wenn gesetzt: diskreter Auswahl-Parameter (Wert = Index).
 * @param hidden Wird nicht als Regler angezeigt (z. B. per Ziehen auf der Leinwand gesetzter Winkel).
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
    val hidden: Boolean = false,
) {
    fun format(v: Float): String {
        if (choices != null) return choices.getOrElse(v.roundToInt()) { "" }
        val i = v.roundToInt()
        return if (signed && i > 0) "+$i $unit".trim() else "$i $unit".trim()
    }

    /** Wert normiert auf 0..1. */
    fun fraction(v: Float): Float = if (max > min) ((v - min) / (max - min)).coerceIn(0f, 1f) else 0f

    /** Normierten Wert 0..1 zurückrechnen. */
    fun fromFraction(f: Float): Float = min + (max - min) * f.coerceIn(0f, 1f)

    /** Prozentanzeige wie im Vorbild (Wischleiste): 0..100 %, vorzeichenbehaftete Regler −100..+100 %. */
    fun percentText(v: Float): String = if (signed) format(v) else "${(fraction(v) * 100f).roundToInt()} %"
}

/** Alle Anpassungen (Reihenfolge = Menü). */
enum class AdjustmentKind(
    val label: String,
    /** Index des Parameters, der durch waagerechtes Ziehen auf der Leinwand gesteuert wird (-1 = keiner). */
    val dragParam: Int = -1,
    /** Unterstützt den Stift-Modus (Anpassung mit dem Pinsel auftragen). */
    val pencil: Boolean = true,
    /** Menügruppe (für Trennlinien): 0 = Anpassungen, 1..3 = Filter. */
    val section: Int = 0,
    /** Erscheint im Anpassungsmenü (Umfärben wird über das QuickMenu geöffnet). */
    val inMenu: Boolean = true,
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
    CHROMATIC("Chromatische Aberration", dragParam = 1, section = 2),
    LIQUIFY("Verflüssigen", pencil = false, section = 3),
    CLONE("Klonen", pencil = false, section = 3),
    RECOLOR("Umfärben", pencil = false, section = 3, inMenu = false);

    val params: List<AdjParam> by lazy { paramsFor(this) }

    /** Kann einen verschiebbaren Mittelpunkt auf der Leinwand haben (siehe [AdjustmentTool.centerActive]). */
    val hasCenter: Boolean get() = this == PERSPECTIVE_BLUR || this == CHROMATIC || this == RECOLOR

    /** Malwerkzeuge ohne Vorschau-Berechnung (Pinselstriche statt Reglern). */
    val isBrushTool: Boolean get() = this == LIQUIFY || this == CLONE

    /** „Zum Anpassen wischen“: Stärke per waagerechtem Ziehen über die Leinwand. */
    val isSwipe: Boolean get() = dragParam >= 0

    companion object {
        val COLOR_BALANCE_RANGES = listOf("Schatten", "Mitteltöne", "Lichter")
        val LIQUIFY_MODES = listOf(
            "Schieben", "Wirbel rechts", "Wirbel links", "Zusammenziehen", "Ausdehnen", "Kristalle", "Kante", "Wiederherstellen",
        )
        val GLITCH_MODES = listOf("Artefakt", "Welle", "Signal", "Divergieren")
        val HALFTONE_MODES = listOf("Vollfarbe", "Siebdruck", "Zeitung")
        val CHROMATIC_MODES = listOf("Perspektive", "Verschieben")
        private val OFF_ON = listOf("Aus", "An")

        // ---- Indizes, die an mehreren Stellen gebraucht werden
        const val LIQUIFY_ADJUST = 5
        const val CHROMATIC_ANGLE = 6

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
            GAUSSIAN_BLUR -> listOf(AdjParam("Gaußscher Weichzeichner", 0f, 100f, 0f))
            MOTION_BLUR -> listOf(
                AdjParam("Bewegungsunschärfe", 0f, 100f, 0f),
                AdjParam("Winkel", 0f, 360f, 0f, unit = "°", hidden = true),
            )
            PERSPECTIVE_BLUR -> listOf(
                AdjParam("Art", 0f, 1f, 0f, choices = listOf("Positionsbezogen", "Richtungsbezogen")),
                AdjParam("Perspektivische Unschärfe", 0f, 100f, 0f),
                AdjParam("Winkel", 0f, 360f, 0f, unit = "°", group = 1, groupOf = 0),
            )
            NOISE -> listOf(
                AdjParam("Rauschen", 0f, 100f, 0f),
                AdjParam("Art", 0f, 2f, 0f, choices = listOf("Wolken", "Wogen", "Kämme")),
                AdjParam("Maßstab", 1f, 100f, 25f),
                AdjParam("Oktaven", 1f, 6f, 3f, unit = ""),
                AdjParam("Turbulenz", 0f, 100f, 0f),
                AdjParam("Kanäle", 0f, 1f, 0f, choices = listOf("Einfach", "Mehrfach")),
                AdjParam("Additiv", 0f, 1f, 0f, choices = OFF_ON),
            )
            SHARPEN -> listOf(AdjParam("Scharfzeichnen", 0f, 100f, 0f))
            BLOOM -> listOf(
                AdjParam("Bloom", 0f, 100f, 0f),
                AdjParam("Übergang", 0f, 100f, 60f),
                AdjParam("Größe", 0f, 100f, 30f),
                AdjParam("Brennen", 0f, 100f, 0f),
            )
            GLITCH -> listOf(
                AdjParam("Art", 0f, 3f, 0f, choices = GLITCH_MODES),
                AdjParam("Glitch", 0f, 100f, 0f),
                // Artefakt (2..4)
                AdjParam("Menge", 0f, 100f, 50f, group = 0, groupOf = 0),
                AdjParam("Blockgröße", 0f, 100f, 40f, group = 0, groupOf = 0),
                AdjParam("Zoom", 0f, 100f, 0f, group = 0, groupOf = 0),
                // Welle (5..7)
                AdjParam("Amplitude", 0f, 100f, 50f, group = 1, groupOf = 0),
                AdjParam("Frequenz", 0f, 100f, 40f, group = 1, groupOf = 0),
                AdjParam("Zoom", 0f, 100f, 0f, group = 1, groupOf = 0),
                // Signal (8..10)
                AdjParam("Menge", 0f, 100f, 50f, group = 2, groupOf = 0),
                AdjParam("Blockgröße", 0f, 100f, 40f, group = 2, groupOf = 0),
                AdjParam("Zoom", 0f, 100f, 0f, group = 2, groupOf = 0),
                // Divergieren (11..14)
                AdjParam("Rotverschiebung", -100f, 100f, 40f, signed = true, group = 3, groupOf = 0),
                AdjParam("Grünverschiebung", -100f, 100f, 0f, signed = true, group = 3, groupOf = 0),
                AdjParam("Blauverschiebung", -100f, 100f, -40f, signed = true, group = 3, groupOf = 0),
                AdjParam("Zoom", 0f, 100f, 0f, group = 3, groupOf = 0),
            )
            HALFTONE -> listOf(
                AdjParam("Art", 0f, 2f, 0f, choices = HALFTONE_MODES),
                AdjParam("Halbton", 0f, 100f, 0f),
            )
            CHROMATIC -> listOf(
                AdjParam("Art", 0f, 1f, 0f, choices = CHROMATIC_MODES),
                AdjParam("Chromatische Aberration", 0f, 100f, 0f),
                // Perspektive (2..3)
                AdjParam("Übergang", 0f, 100f, 50f, group = 0, groupOf = 0),
                AdjParam("Abfall", 0f, 100f, 30f, group = 0, groupOf = 0),
                // Verschieben (4..5, Winkel 6 per Ziehen)
                AdjParam("Unschärfe", 0f, 100f, 0f, group = 1, groupOf = 0),
                AdjParam("Transparenz", 0f, 100f, 0f, group = 1, groupOf = 0),
                AdjParam("Winkel", 0f, 360f, 0f, unit = "°", group = 1, groupOf = 0, hidden = true),
            )
            LIQUIFY -> listOf(
                AdjParam("Modus", 0f, (LIQUIFY_MODES.size - 1).toFloat(), 0f, choices = LIQUIFY_MODES),
                AdjParam("Größe", 1f, 100f, 25f),
                AdjParam("Druck", 1f, 100f, 50f),
                AdjParam("Verzerrung", 0f, 100f, 0f),
                AdjParam("Dynamik", 0f, 100f, 0f),
                AdjParam("Anpassen", 0f, 100f, 100f),
            )
            CLONE -> listOf(
                AdjParam("Größe", 1f, 100f, 25f),
                AdjParam("Stärke", 1f, 100f, 100f),
            )
            RECOLOR -> listOf(
                AdjParam("Füllen", 0f, 100f, 25f),
                AdjParam("Weichheit", 0f, 100f, 30f),
                AdjParam("Ausbreitung", 0f, 1f, 0f, choices = listOf("Zusammenhängend", "Global")),
            )
        }
    }
}
