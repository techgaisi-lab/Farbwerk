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
     * Vorhergesagte (noch nicht eingetroffene) Punkte des laufenden Strichs – nur als temporäre Vorschau
     * zeichnen, nie festschreiben. Jeder Aufruf ersetzt die vorige Vorhersage; eine leere Liste löscht sie.
     * Wird nach jedem [onStrokeMove] aufgerufen.
     */
    fun onPredicted(points: List<InputPoint>) {}

    /**
     * Soll eine Zwei-Finger-Geste, die bei ([docX], [docY]) beginnt, an dieses Werkzeug gehen
     * statt die Ansicht zu zoomen/drehen? (z. B. Kneifen innerhalb des Transform-Rahmens)
     */
    fun handlesTwoFingerAt(docX: Float, docY: Float): Boolean = false

    /**
     * Zwei-Finger-Änderung in Dokument-Koordinaten: Verschiebung ([dx], [dy]), Skalierung [scale] und
     * Drehung [rotationDeg] jeweils relativ zum letzten Aufruf, um den Drehpunkt ([pivotX], [pivotY]).
     */
    fun onTwoFingerTransform(pivotX: Float, pivotY: Float, dx: Float, dy: Float, scale: Float, rotationDeg: Float) {}

    /** Ende der Zwei-Finger-Geste, die an dieses Werkzeug ging. */
    fun onTwoFingerEnd() {}
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
