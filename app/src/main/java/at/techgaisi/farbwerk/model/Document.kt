package at.techgaisi.farbwerk.model

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import java.util.UUID

/**
 * Mischmodi einer Ebene. [androidMode] wird vom Compositor auf android.graphics.BlendMode abgebildet.
 */
enum class LayerBlend(val label: String, val short: String) {
    NORMAL("Normal", "N"),
    MULTIPLY("Multiplizieren", "M"),
    DARKEN("Abdunkeln", "Ab"),
    COLOR_BURN("Farbig nachbelichten", "Fn"),
    LINEAR_BURN("Linear nachbelichten", "Ln"),
    SCREEN("Negativ multiplizieren", "Nm"),
    LIGHTEN("Aufhellen", "Au"),
    COLOR_DODGE("Farbig abwedeln", "Fa"),
    ADD("Hinzufügen", "Hz"),
    OVERLAY("Überlagern", "Ü"),
    SOFT_LIGHT("Weiches Licht", "Wl"),
    HARD_LIGHT("Hartes Licht", "Hl"),
    DIFFERENCE("Differenz", "D"),
    EXCLUSION("Ausschluss", "As"),
    HUE("Farbton", "Ft"),
    SATURATION("Sättigung", "S"),
    COLOR("Farbe", "F"),
    LUMINOSITY("Luminanz", "L"),
    // Erweiterte Modi (Compositor rendert sie per eigenem Shader/CPU-Pfad)
    SHADE("Schattieren", "Sc"),
    DARKER_COLOR("Dunklere Farbe", "DF"),
    LIGHTER_COLOR("Hellere Farbe", "HF"),
    VIVID_LIGHT("Strahlendes Licht", "Sl"),
    LINEAR_LIGHT("Lineares Licht", "LL"),
    PIN_LIGHT("Lichtpunkt", "Lp"),
    HARD_MIX("Harte Mischung", "HM"),
    SUBTRACT("Subtrahieren", "Su"),
    DIVIDE("Dividieren", "Di"),
}

/**
 * Eine Ebene. Pixel liegen in [bitmap] (ARGB_8888, Dokumentgröße, nicht vormultipliziert ist egal –
 * Android arbeitet intern premultiplied).
 */
class Layer(
    var name: String,
    var bitmap: Bitmap,
    val id: String = UUID.randomUUID().toString(),
) {
    var visible: Boolean = true
    var opacity: Float = 1f
    var blend: LayerBlend = LayerBlend.NORMAL
    var alphaLock: Boolean = false
    var clipping: Boolean = false
    var locked: Boolean = false
    var isReference: Boolean = false

    /** Optionale Ebenenmaske (ALPHA_8 oder ARGB_8888, weiß = sichtbar). */
    var mask: Bitmap? = null

    /** Zeichenassistent für diese Ebene aktiv (Striche rasten an der aktiven Zeichenhilfe ein). */
    var assisted: Boolean = false

    /** Private Ebene: erscheint nicht in Miniatur, Export und Zeitraffer. */
    var isPrivate: Boolean = false

    /** Vektor-Textdaten, wenn es eine (noch nicht gerasterte) Textebene ist. Typ gehört dem Text-Modul. */
    var text: Any? = null

    /** Gruppen: Ebenen mit gleicher groupId gehören zusammen (vom Ebenenmodul verwaltet). */
    var groupId: String? = null

    /**
     * Wenn gesetzt, zeichnet der Compositor diese Bitmap statt [bitmap] (z. B. Live-Vorschau
     * einer Anpassung).
     */
    var displayOverride: Bitmap? = null

    /**
     * Wenn gesetzt, ruft der Compositor diese Funktion auf, um den Ebeneninhalt zu zeichnen
     * (z. B. schwebender Inhalt beim Transformieren). Das Canvas ist bereits in Dokument-Koordinaten.
     */
    var drawOverride: ((Canvas) -> Unit)? = null

    val width get() = bitmap.width
    val height get() = bitmap.height
}

enum class SymmetryMode { VERTICAL, HORIZONTAL, QUADRANT, RADIAL }
enum class GuideKind { NONE, GRID_2D, ISOMETRIC, PERSPECTIVE, SYMMETRY }

/** Zeichenhilfen-Einstellungen (Aktionen → Leinwand → Zeichenhilfe). */
class GuideSettings {
    var kind: GuideKind = GuideKind.NONE
    var visible: Boolean = false
    var gridSize: Float = 100f
    var color: Int = 0xFF3FA9F5.toInt()
    var opacity: Float = 0.6f
    var symmetry: SymmetryMode = SymmetryMode.VERTICAL
    var symmetryCenterX: Float = Float.NaN   // NaN = Dokumentmitte
    var symmetryCenterY: Float = Float.NaN
    var rotationalSymmetry: Boolean = false
    var radialSegments: Int = 8
    /** Linienstärke der Hilfslinien in Bildschirm-dp. */
    var thickness: Float = 1f
    /** Verschiebung und Drehung des Rasters (Grad). */
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var rotation: Float = 0f
    /** Fluchtpunkte der Perspektiv-Hilfe (Dokument-Koordinaten), 1–3 Stück. */
    val vanishingPoints: MutableList<android.graphics.PointF> = mutableListOf()
    /** Zeichenassistent: Striche werden an der aktiven Hilfe ausgerichtet bzw. gespiegelt. */
    var assisted: Boolean = false
}

/**
 * Aktive Auswahl. [mask] ist eine ALPHA_8-Bitmap in Dokumentgröße (255 = ausgewählt).
 * [path] ist optional (bei Freihand/Rechteck/Ellipse) und wird für Live-Clipping und Ameisenlinie genutzt.
 */
class Selection(
    val mask: Bitmap,
    var path: Path? = null,
) {
    var feather: Float = 0f
}

class ArtDocument(
    var name: String,
    val width: Int,
    val height: Int,
    val id: String = UUID.randomUUID().toString(),
) {
    val layers: MutableList<Layer> = mutableListOf()   // Index 0 = unterste Ebene
    var activeIndex: Int = 0
    /** Zusätzlich (sekundär) ausgewählte Ebenen-IDs. */
    val secondarySelection: MutableSet<String> = mutableSetOf()
    var backgroundColor: Int = Color.WHITE
    var backgroundVisible: Boolean = true
    var dpi: Int = 300
    var selection: Selection? = null
    val guides = GuideSettings()
    var flipHorizontal: Boolean = false
    var flipVertical: Boolean = false
    var created: Long = System.currentTimeMillis()
    var modified: Long = System.currentTimeMillis()
    var dirty: Boolean = false
    /** Einstellungen des Animationsassistenten (null = aus). Typ gehört dem Animations-Modul. */
    var animation: Any? = null

    val activeLayer: Layer? get() = layers.getOrNull(activeIndex)

    fun newLayerBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    fun indexOf(layer: Layer) = layers.indexOfFirst { it.id == layer.id }

    companion object {
        fun create(name: String, width: Int, height: Int): ArtDocument {
            val d = ArtDocument(name, width, height)
            d.layers.add(Layer("Ebene 1", d.newLayerBitmap()))
            d.activeIndex = 0
            return d
        }
    }
}
