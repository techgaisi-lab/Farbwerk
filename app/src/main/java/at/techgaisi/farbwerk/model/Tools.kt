package at.techgaisi.farbwerk.model

enum class Tool { PAINT, SMUDGE, ERASE, SELECT, TRANSFORM, ADJUST }

enum class Panel { NONE, BRUSHES, LAYERS, COLORS, ACTIONS, ADJUSTMENTS }

/** Eingabepunkt in Dokument-Koordinaten. */
data class InputPoint(
    val x: Float,
    val y: Float,
    /** 0..1 (Finger = 1 bzw. simuliert) */
    val pressure: Float,
    /** Neigung in Radiant, 0 = senkrecht */
    val tilt: Float,
    /** Ausrichtung in Radiant */
    val orientation: Float,
    val time: Long,
    val isStylus: Boolean,
)

/**
 * Schnittstelle für alle Werkzeuge, die Eingaben auf der Leinwand verarbeiten.
 * Die CanvasView ruft diese Methoden auf; Koordinaten sind Dokument-Koordinaten.
 */
interface ToolInputHandler {
    fun onStrokeStart(p: InputPoint)
    fun onStrokeMove(points: List<InputPoint>)
    fun onStrokeEnd(p: InputPoint)
    /** Strich wurde abgebrochen (z. B. weil eine Mehrfinger-Geste begonnen hat). Alles rückgängig machen. */
    fun onStrokeCancel()
    /** Stift/Finger wurde nach einer Bewegung still gehalten (QuickShape). */
    fun onHoldStill(p: InputPoint) {}
    /**
     * Overlay zeichnen (Canvas ist in View-Koordinaten). [docToView] bildet Dokument- auf View-Koordinaten ab.
     */
    fun drawOverlay(canvas: android.graphics.Canvas, docToView: android.graphics.Matrix) {}
}

/** Einstellungen je Werkzeug (Malen/Wischen/Radieren haben je eigene Größe, Deckkraft, Pinsel). */
class ToolSettings(
    var brushId: String,
    /** 0..1, wird vom Pinsel auf seinen Min/Max-Bereich abgebildet */
    var size: Float = 0.25f,
    /** 0..1 */
    var opacity: Float = 1f,
)
