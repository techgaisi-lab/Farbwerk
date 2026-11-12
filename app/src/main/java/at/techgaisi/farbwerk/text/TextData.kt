package at.techgaisi.farbwerk.text

import android.graphics.Matrix
import org.json.JSONArray
import org.json.JSONObject

/** Absatzausrichtung einer Textebene. */
enum class TextAlign(val label: String) { LEFT("Links"), CENTER("Mitte"), RIGHT("Rechts"), JUSTIFY("Blocksatz") }

/**
 * Vektor-Textdaten einer Textebene (liegt in [at.techgaisi.farbwerk.model.Layer.text]).
 * Die Pixel der Ebene werden jederzeit aus diesen Daten neu gesetzt ([TextRenderer.render]); solange
 * [Layer.text] gesetzt ist, bleibt der Text bearbeitbar. „Rastern“ entfernt die Daten.
 *
 * Geometrie: Der Textrahmen beginnt bei ([x], [y]) im Rahmenraum; [matrix] bildet Rahmenraum auf
 * Dokument-Koordinaten ab (Identität, außer nach Leinwand-Drehung/-Skalierung).
 */
class TextData {
    var text: String = "Text"
    /** Schriftschlüssel, siehe [FontRegistry]. */
    var fontKey: String = FontRegistry.DEFAULT_KEY
    var size: Float = 96f
    var bold: Boolean = false
    var italic: Boolean = false
    var color: Int = 0xFF000000.toInt()
    /** Laufweite in Tausendstel em (−200…1000). */
    var tracking: Float = 0f
    /** Unterschneidung (Kerning-Tabellen der Schrift) verwenden. */
    var kerning: Boolean = true
    /** Zeilenabstand als Vielfaches (0,5…3). */
    var lineSpacing: Float = 1f
    /** Grundlinienversatz in Pixeln (positiv = nach oben). */
    var baseline: Float = 0f
    var opacity: Float = 1f
    var align: TextAlign = TextAlign.LEFT
    var underline: Boolean = false
    var outline: Boolean = false
    var vertical: Boolean = false
    var uppercase: Boolean = false
    /** Rahmenbreite für den Umbruch; 0 = automatisch (kein Umbruch). */
    var boxWidth: Float = 0f
    var x: Float = 0f
    var y: Float = 0f
    /** Rahmenraum → Dokument (9 Werte, android.graphics.Matrix). */
    val matrix: FloatArray = FloatArray(9).also { Matrix().getValues(it) }
    /** Ebenenname folgt dem Text, bis der Nutzer umbenennt. */
    var autoName: Boolean = true
    /** Bitmap-Generation nach dem letzten Setzen (erkennt fremde Pixeländerungen; nicht gespeichert). */
    @Transient var renderedGeneration: Int = -1

    fun matrix(out: Matrix = Matrix()): Matrix = out.also { it.setValues(matrix) }
    fun setMatrix(m: Matrix) = m.getValues(matrix)

    fun copy(): TextData = TextData().also { c ->
        c.text = text; c.fontKey = fontKey; c.size = size; c.bold = bold; c.italic = italic; c.color = color
        c.tracking = tracking; c.kerning = kerning; c.lineSpacing = lineSpacing; c.baseline = baseline
        c.opacity = opacity; c.align = align; c.underline = underline; c.outline = outline; c.vertical = vertical
        c.uppercase = uppercase; c.boxWidth = boxWidth; c.x = x; c.y = y; matrix.copyInto(c.matrix)
        c.autoName = autoName; c.renderedGeneration = renderedGeneration
    }

    /** Vorgeschlagener Ebenenname (erste Zeile, max. 30 Zeichen). */
    fun displayName(): String = text.lineSequence().firstOrNull()?.trim()?.take(30)?.ifBlank { null } ?: "Text"

    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text); put("font", fontKey); put("size", size.toDouble()); put("bold", bold); put("italic", italic)
        put("color", color); put("tracking", tracking.toDouble()); put("kerning", kerning)
        put("lineSpacing", lineSpacing.toDouble()); put("baseline", baseline.toDouble()); put("opacity", opacity.toDouble())
        put("align", align.name); put("underline", underline); put("outline", outline); put("vertical", vertical)
        put("uppercase", uppercase); put("boxWidth", boxWidth.toDouble()); put("x", x.toDouble()); put("y", y.toDouble())
        put("matrix", JSONArray().apply { matrix.forEach { put(it.toDouble()) } })
        put("autoName", autoName)
    }

    companion object {
        fun fromJson(o: JSONObject?): TextData? {
            if (o == null) return null
            return TextData().apply {
                text = o.optString("text", "Text")
                fontKey = o.optString("font", FontRegistry.DEFAULT_KEY)
                size = o.optDouble("size", 96.0).toFloat().coerceIn(1f, 4000f)
                bold = o.optBoolean("bold"); italic = o.optBoolean("italic")
                color = o.optInt("color", color)
                tracking = o.optDouble("tracking", 0.0).toFloat()
                kerning = o.optBoolean("kerning", true)
                lineSpacing = o.optDouble("lineSpacing", 1.0).toFloat().coerceIn(0.3f, 4f)
                baseline = o.optDouble("baseline", 0.0).toFloat()
                opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f)
                align = TextAlign.entries.firstOrNull { it.name == o.optString("align") } ?: TextAlign.LEFT
                underline = o.optBoolean("underline"); outline = o.optBoolean("outline")
                vertical = o.optBoolean("vertical"); uppercase = o.optBoolean("uppercase")
                boxWidth = o.optDouble("boxWidth", 0.0).toFloat().coerceAtLeast(0f)
                x = o.optDouble("x", 0.0).toFloat(); y = o.optDouble("y", 0.0).toFloat()
                o.optJSONArray("matrix")?.let { a -> if (a.length() == 9) for (i in 0 until 9) matrix[i] = a.optDouble(i).toFloat() }
                autoName = o.optBoolean("autoName", true)
            }
        }
    }
}
