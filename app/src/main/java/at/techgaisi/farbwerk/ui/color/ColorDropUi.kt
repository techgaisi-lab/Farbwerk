package at.techgaisi.farbwerk.ui.color

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.color.ColorDrop
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.Tool
import at.techgaisi.farbwerk.ui.theme.LocalUi
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Farbe per Ziehen auf die Leinwand fallen lassen (wie im Vorbild):
 *
 *  1. Farbkreis oben rechts (oder Hauptfarbe im Farben-Panel) gedrückt halten und auf die Leinwand ziehen –
 *     ein Farbtropfen folgt dem Finger.
 *  2. Loslassen füllt den geschlossenen Bereich unter dem Finger mit dem zuletzt benutzten Schwellenwert.
 *  3. Bleibt der Finger nach dem Ablegen kurz still liegen, wird sofort gefüllt und oben erscheint die
 *     Leiste „ColorDrop-Schwellenwert: 47 %“. Waagrechtes Ziehen ändert den Wert live mit Vorschau,
 *     Loslassen übernimmt.
 *  4. Danach erscheint kurz „Nachfüllen fortsetzen“. Tippen darauf schaltet den Nachfüllmodus ein: jedes
 *     Tippen auf die Leinwand füllt den Bereich mit der aktuellen Farbe – bis das Werkzeug gewechselt wird.
 *
 * Alle Positionen sind Fensterkoordinaten. Pro [EditorState] gibt es genau einen Controller ([of]),
 * damit Farbkreis (obere Leiste) und Farben-Panel denselben Zustand und dieselbe Anzeige nutzen.
 */
class ColorDropController private constructor(private val state: EditorState) {

    // ---------------------------------------------------------------- Zustand (Compose-beobachtbar)
    /** Wird gerade eine Farbe gezogen? */
    var dragging by mutableStateOf(false); private set
    /** Aktuelle Fingerposition während des Ziehens (Fensterkoordinaten). */
    var position by mutableStateOf(Offset.Zero); private set
    /** Finger liegt nach dem Ablegen still → Schwellenwert wird eingestellt. */
    var thresholdMode by mutableStateOf(false); private set
    var thresholdPercent by mutableIntStateOf(0); private set
    /** Hinweis „Nachfüllen fortsetzen“ sichtbar. */
    var offerRefill by mutableStateOf(false); private set
    /** Nachfüllmodus aktiv: Tippen auf die Leinwand füllt. */
    var refillActive by mutableStateOf(false); private set
    /** Werkzeug beim Einschalten des Nachfüllmodus (Wechsel beendet den Modus). */
    internal var refillTool: Tool = Tool.PAINT; private set
    /** Dokument beim Einschalten des Nachfüllmodus (Dokumentwechsel beendet den Modus). */
    internal var refillDoc: Any? = null; private set

    /**
     * Unterkante der oberen Leiste (Fensterkoordinaten). Über der Leiste wird nie gefüllt –
     * so löst ein kurzes Zurückziehen auf den Farbkreis nichts aus.
     */
    var blockAboveY: Float = 0f

    // ---------------------------------------------------------------- Parameter (vom Overlay gesetzt)
    internal var slopPx = 24f
    /** Wegstrecke (px) für 100 % Schwellenwertänderung. */
    internal var rangePx = 700f

    private var exclude: Rect? = null
    private var holdAnchor = Offset.Zero
    private var anchorX = 0f
    private var startThreshold = 0f
    private var pendingThreshold = Float.NaN

    private val handler = Handler(Looper.getMainLooper())
    private val holdRunnable = Runnable { onHold() }
    private val hideOffer = Runnable { offerRefill = false }
    /** Schwellenwert-Neuberechnung bündeln: höchstens eine Füllung pro Nachrichtenschleifen-Durchlauf. */
    private val applyThreshold = Runnable {
        val t = pendingThreshold
        pendingThreshold = Float.NaN
        if (!t.isNaN() && thresholdMode) {
            ColorDrop.updateThreshold(t)
            thresholdPercent = (ColorDrop.currentThreshold * 100f).roundToInt()
        }
    }

    // ---------------------------------------------------------------- Ziehen

    /**
     * Ziehen beginnt bei [windowPos]. [excludeRect] (z. B. das Farben-Panel) ist kein Ablageziel.
     */
    fun start(windowPos: Offset, excludeRect: Rect? = null) {
        cancel()
        hideRefillOffer()
        refillActive = false
        exclude = excludeRect
        dragging = true
        position = windowPos
        thresholdMode = false
        scheduleHold(windowPos)
    }

    fun move(windowPos: Offset) {
        if (!dragging) return
        position = windowPos
        if (thresholdMode) {
            val t = (startThreshold + (windowPos.x - anchorX) / rangePx).coerceIn(0f, 1f)
            // Anzeige sofort, Füllung gebündelt (große Leinwände brauchen einige ms pro Füllung)
            thresholdPercent = (t * 100f).roundToInt()
            val wasPending = !pendingThreshold.isNaN()
            pendingThreshold = t
            if (!wasPending) handler.post(applyThreshold)
        } else if ((windowPos - holdAnchor).getDistance() > slopPx) {
            scheduleHold(windowPos)
        }
    }

    /** Finger angehoben: füllen bzw. Schwellenwert übernehmen. */
    fun end() {
        if (!dragging) return
        handler.removeCallbacks(holdRunnable)
        val filled = if (thresholdMode) {
            // letzten, evtl. noch gebündelten Wert anwenden
            handler.removeCallbacks(applyThreshold)
            if (!pendingThreshold.isNaN()) ColorDrop.updateThreshold(pendingThreshold)
            pendingThreshold = Float.NaN
            ColorDrop.commit()
        } else if (canDropAt(position)) {
            ColorDrop.fillAtWindowPoint(state, position.x, position.y)
        } else false
        reset()
        if (filled) showRefillOffer()
    }

    /** Geste abgebrochen (System, Panel geschlossen …): nichts füllen. */
    fun cancel() {
        handler.removeCallbacks(holdRunnable)
        handler.removeCallbacks(applyThreshold)
        pendingThreshold = Float.NaN
        if (thresholdMode) ColorDrop.cancel()
        reset()
    }

    private fun reset() {
        dragging = false
        thresholdMode = false
        exclude = null
    }

    private fun canDropAt(p: Offset): Boolean =
        p.y > blockAboveY && exclude?.contains(p) != true && state.document != null

    private fun scheduleHold(p: Offset) {
        holdAnchor = p
        handler.removeCallbacks(holdRunnable)
        handler.postDelayed(holdRunnable, HOLD_MS)
    }

    /** Finger liegt still: Füllung als Vorschau beginnen und Schwellenwert-Modus einschalten. */
    private fun onHold() {
        if (!dragging || thresholdMode || !canDropAt(position)) return
        if (ColorDrop.beginAtWindowPoint(state, position.x, position.y)) {
            thresholdMode = true
            anchorX = position.x
            startThreshold = ColorDrop.currentThreshold
            thresholdPercent = (startThreshold * 100f).roundToInt()
        }
    }

    // ---------------------------------------------------------------- Nachfüllen

    private fun showRefillOffer() {
        offerRefill = true
        handler.removeCallbacks(hideOffer)
        handler.postDelayed(hideOffer, OFFER_MS)
    }

    private fun hideRefillOffer() {
        handler.removeCallbacks(hideOffer)
        offerRefill = false
    }

    /** „Nachfüllen fortsetzen“ angetippt. */
    fun activateRefill() {
        hideRefillOffer()
        refillTool = state.tool
        refillDoc = state.document
        refillActive = true
    }

    fun stopRefill() {
        refillActive = false
    }

    /** Im Nachfüllmodus an einer Fensterposition füllen (aktuelle Farbe, letzter Schwellenwert). */
    internal fun refillAt(windowX: Float, windowY: Float) {
        if (windowY <= blockAboveY) return
        ColorDrop.fillAtWindowPoint(state, windowX, windowY)
    }

    companion object {
        private const val HOLD_MS = 550L
        private const val OFFER_MS = 3500L
        private val instances = WeakHashMap<EditorState, ColorDropController>()

        /** Gemeinsamer Controller für diesen Editor. */
        fun of(state: EditorState): ColorDropController =
            instances.getOrPut(state) { ColorDropController(state) }
    }
}

// ==================================================================================== Anzeige

/**
 * Anzeige-Ebene für ColorDrop – als letztes Kind über alles im Editor legen. Enthält selbst keine
 * Touch-Fläche außer den Hinweis-Pillen, blockiert also weder Leinwand noch Bedienelemente.
 * Zeigt: Farbtropfen am Finger, Schwellenwert-Leiste, „Nachfüllen fortsetzen“, Nachfüllmodus-Hinweis.
 */
@Composable
fun ColorDropOverlay(state: EditorState, modifier: Modifier = Modifier) {
    val c = remember(state) { ColorDropController.of(state) }
    val ui = LocalUi.current
    val density = LocalDensity.current
    val vc = LocalViewConfiguration.current
    c.slopPx = vc.touchSlop
    c.rangePx = with(density) { 320.dp.toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }

    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        // ---------- Farbtropfen am Finger
        if (c.dragging) {
            val color = Color(state.color)
            Canvas(Modifier.fillMaxSize()) {
                val p = c.position - origin
                val r = if (c.thresholdMode) 20.dp.toPx() else 26.dp.toPx()
                // leicht über dem Finger, damit der Tropfen sichtbar bleibt
                val lift = if (c.thresholdMode) 0f else 6.dp.toPx()
                val q = Offset(p.x, p.y - lift)
                drawCircle(Color(0x40000000), r + 4.dp.toPx(), q + Offset(0f, 2.dp.toPx()))
                drawCircle(Color.White, r + 2.5.dp.toPx(), q)
                drawCircle(color, r, q)
                drawCircle(Color(0x33000000), r + 2.5.dp.toPx(), q, style = Stroke(1.dp.toPx()))
            }
        }

        // ---------- Schwellenwert-Leiste
        AnimatedVisibility(
            c.thresholdMode,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp),
        ) {
            ThresholdBar(c.thresholdPercent)
        }

        // ---------- „Nachfüllen fortsetzen“
        AnimatedVisibility(
            c.offerRefill && !c.dragging,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 58.dp),
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ui.panel)
                    .border(0.5.dp, ui.divider, RoundedCornerShape(50))
                    .clickable { c.activateRefill() }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Text("Nachfüllen fortsetzen", color = ui.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        // ---------- Nachfüllmodus aktiv
        AnimatedVisibility(
            c.refillActive && !c.dragging,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 58.dp),
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ui.panel)
                    .border(0.5.dp, ui.divider, RoundedCornerShape(50))
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(12.dp).height(12.dp).clip(CircleShape).background(Color(state.color)))
                Spacer(Modifier.width(8.dp))
                Text("Nachfüllen: auf Bereiche tippen", color = ui.text, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Beenden", color = ui.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { c.stopRefill() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/** Dünne Leiste oben: „ColorDrop-Schwellenwert: 47 %“. */
@Composable
private fun ThresholdBar(percent: Int) {
    val ui = LocalUi.current
    Column(
        Modifier
            .widthIn(min = 260.dp, max = 420.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ui.panel)
            .border(0.5.dp, ui.divider, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Füll-Schwellenwert: $percent %", color = ui.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(ui.divider)) {
            Box(
                Modifier.fillMaxWidth((percent / 100f).coerceIn(0f, 1f)).height(4.dp)
                    .clip(CircleShape).background(ui.accent)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("Nach links: weniger · nach rechts: mehr", color = ui.textMuted, fontSize = 11.sp)
    }
}

// ==================================================================================== Nachfüllmodus

/**
 * Touch-Ebene für den Nachfüllmodus – direkt ÜBER der Leinwand, aber UNTER Leisten und Panels einfügen.
 * Ein-Finger-Tippen füllt; Mehrfinger-Gesten (Zoomen, Drehen, Rückgängig …) sowie Pipette/Leertaste
 * werden unverändert an die Leinwand weitergereicht. Ist der Modus aus, wird nichts zusammengesetzt.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ColorDropTapLayer(state: EditorState, modifier: Modifier = Modifier) {
    val c = remember(state) { ColorDropController.of(state) }
    if (!c.refillActive) return

    // Werkzeugwechsel, Pinselbibliothek oder Dokumentwechsel beendet den Modus
    LaunchedEffect(state.tool) { if (state.tool != c.refillTool) c.stopRefill() }
    LaunchedEffect(state.openPanel) { if (state.openPanel == Panel.BRUSHES) c.stopRefill() }
    LaunchedEffect(state.document, state.showGallery) {
        if (state.document !== c.refillDoc || state.showGallery) c.stopRefill()
    }

    val slop = LocalViewConfiguration.current.touchSlop
    val router = remember(state) { RefillTouchRouter(state, c) }
    router.slop = slop
    DisposableEffect(router) { onDispose { router.abort() } }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { router.origin = it.positionInWindow() }
            .pointerInteropFilter { ev -> router.onTouch(ev) }
    )
}

/**
 * Verteilt Touch-Ereignisse im Nachfüllmodus: Ein einzelnes Tippen füllt; sobald ein zweiter Finger
 * dazukommt, wird die gesamte Geste (inkl. des gepufferten ersten DOWN) an die Leinwand weitergeleitet,
 * die – wie bei normalem Malen – den begonnenen Strich verwirft und die Mehrfinger-Geste ausführt.
 */
private class RefillTouchRouter(private val state: EditorState, private val c: ColorDropController) {
    var origin = Offset.Zero
    var slop = 16f

    private enum class Mode { IDLE, PENDING, FORWARD, BLOCKED }
    private var mode = Mode.IDLE
    private var pendingDown: MotionEvent? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clear()
                when {
                    state.openPanel != Panel.NONE -> {
                        // wie auf der Leinwand: Tippen schließt das offene Panel und füllt nicht
                        state.openPanel = Panel.NONE
                        mode = Mode.BLOCKED
                    }
                    state.eyedropperArmed || state.spaceHeld -> {
                        mode = Mode.FORWARD
                        forward(ev)
                    }
                    else -> {
                        mode = Mode.PENDING
                        pendingDown = MotionEvent.obtain(ev)
                        downX = ev.x; downY = ev.y
                        moved = false
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> when (mode) {
                Mode.PENDING -> {
                    mode = Mode.FORWARD
                    pendingDown?.let { forward(it) }
                    forward(ev)
                }
                Mode.FORWARD -> forward(ev)
                else -> {}
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.PENDING -> if (kotlin.math.hypot(ev.x - downX, ev.y - downY) > slop) moved = true
                Mode.FORWARD -> forward(ev)
                else -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.FORWARD) forward(ev)
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.PENDING -> if (!moved) c.refillAt(ev.x + origin.x, ev.y + origin.y)
                    Mode.FORWARD -> forward(ev)
                    else -> {}
                }
                clear()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.FORWARD) forward(ev)
                clear()
            }
        }
        return true
    }

    /** Modus wurde mitten in einer weitergeleiteten Geste beendet → Leinwand sauber abbrechen. */
    fun abort() {
        if (mode == Mode.FORWARD) {
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            state.canvasView?.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        clear()
    }

    private fun clear() {
        pendingDown?.recycle()
        pendingDown = null
        mode = Mode.IDLE
        moved = false
    }

    /** Ereignis (lokal zu dieser Ebene) in Leinwand-Koordinaten umrechnen und weiterreichen. */
    private fun forward(ev: MotionEvent) {
        val view = state.canvasView ?: return
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val copy = MotionEvent.obtain(ev)
        copy.offsetLocation(origin.x - loc[0], origin.y - loc[1])
        view.dispatchTouchEvent(copy)
        copy.recycle()
    }
}
