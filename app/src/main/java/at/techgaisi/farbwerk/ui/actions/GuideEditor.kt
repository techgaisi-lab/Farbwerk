package at.techgaisi.farbwerk.ui.actions

import android.graphics.Matrix
import android.graphics.PointF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.guides.GuidesOverlay
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.GuideKind
import at.techgaisi.farbwerk.model.GuideSettings
import at.techgaisi.farbwerk.model.InputPoint
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.model.SymmetryMode
import at.techgaisi.farbwerk.model.ToolInputHandler
import at.techgaisi.farbwerk.undo.LambdaAction
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Kopie aller Zeichenhilfe-Felder (für Abbrechen und Undo). */
class GuideSnapshot(g: GuideSettings) {
    private val kind = g.kind; private val visible = g.visible; private val gridSize = g.gridSize
    private val color = g.color; private val opacity = g.opacity; private val symmetry = g.symmetry
    private val cx = g.symmetryCenterX; private val cy = g.symmetryCenterY
    private val rot = g.rotationalSymmetry; private val segs = g.radialSegments
    private val thickness = g.thickness; private val ox = g.offsetX; private val oy = g.offsetY
    private val rotation = g.rotation; private val vps = g.vanishingPoints.map { PointF(it.x, it.y) }
    private val assisted = g.assisted

    fun restore(g: GuideSettings) {
        g.kind = kind; g.visible = visible; g.gridSize = gridSize; g.color = color; g.opacity = opacity
        g.symmetry = symmetry; g.symmetryCenterX = cx; g.symmetryCenterY = cy; g.rotationalSymmetry = rot
        g.radialSegments = segs; g.thickness = thickness; g.offsetX = ox; g.offsetY = oy; g.rotation = rotation
        g.vanishingPoints.clear(); vps.forEach { g.vanishingPoints.add(PointF(it.x, it.y)) }
        g.assisted = assisted
    }
}

/** Steuerpunkte im Editor. */
enum class GuideHandle { MOVE, ROTATE, VP0, VP1, VP2, CENTER }

/**
 * Sitzung des Vollbild-Editors „Zeichenhilfe“. Bearbeitet die Hilfe des Dokuments live; Abbrechen stellt
 * den Ausgangszustand wieder her, Fertig legt einen Rückgängig-Schritt an.
 */
class GuideEditorSession(private val state: EditorState) : ToolInputHandler {
    val doc: ArtDocument = state.document!!
    private val before = GuideSnapshot(doc.guides)
    private val prevFullscreen = state.fullscreen
    private val prevAssisted = doc.activeLayer?.assisted ?: false

    /** Zähler für Compose (nach jeder Änderung erhöht). */
    var tick by mutableIntStateOf(0)
        private set
    /** Zuletzt angetippter Punkt (zeigt „Zurücksetzen“/„Löschen“). */
    var selected by mutableStateOf<GuideHandle?>(null)

    private var dragging: GuideHandle? = null
    private var downX = 0f; private var downY = 0f
    private var moved = false
    private var grabDx = 0f; private var grabDy = 0f
    private val pts = FloatArray(2)

    val g get() = doc.guides

    fun changed() { tick++; state.invalidate() }

    // ------------------------------------------------------------------ Start / Ende

    fun start() {
        state.openPanel = Panel.NONE
        if (g.kind == GuideKind.NONE) g.kind = GuideKind.GRID_2D
        g.visible = true
        state.fullscreen = true
        state.canvasOverride = this
        changed()
    }

    fun cancel() {
        before.restore(g)
        doc.activeLayer?.assisted = prevAssisted
        finish()
    }

    fun done() {
        val after = GuideSnapshot(g)
        val layer = doc.activeLayer
        val nowAssisted = layer?.assisted ?: false
        val b = before
        val pa = prevAssisted
        state.undo.push(LambdaAction("Zeichenhilfe bearbeiten",
            onUndo = { b.restore(doc.guides); layer?.assisted = pa },
            onRedo = { after.restore(doc.guides); layer?.assisted = nowAssisted }))
        state.markDirty()
        finish()
    }

    private fun finish() {
        if (state.canvasOverride === this) state.canvasOverride = null
        state.fullscreen = prevFullscreen
        ActionModes.guideEditor = null
        state.invalidate()
    }

    // ------------------------------------------------------------------ Geometrie

    private fun m(): Matrix? = state.canvasView?.docToViewMatrix()

    private fun scale(m: Matrix): Float {
        pts[0] = 1f; pts[1] = 0f; m.mapVectors(pts); return hypot(pts[0], pts[1]).coerceAtLeast(1e-4f)
    }

    private fun origin(): PointF = PointF(doc.width / 2f + g.offsetX, doc.height / 2f + g.offsetY)

    /** Grüner Drehpunkt: 110 dp vom Ursprung entlang der Rasterdrehung. */
    private fun rotateHandle(m: Matrix): PointF {
        val o = origin()
        val dist = 110f * Handles.density / scale(m)
        val a = Math.toRadians(g.rotation.toDouble())
        return PointF(o.x + cos(a).toFloat() * dist, o.y + sin(a).toFloat() * dist)
    }

    private fun handlePositions(m: Matrix): List<Pair<GuideHandle, PointF>> = when (g.kind) {
        GuideKind.GRID_2D, GuideKind.ISOMETRIC -> listOf(GuideHandle.MOVE to origin(), GuideHandle.ROTATE to rotateHandle(m))
        GuideKind.PERSPECTIVE -> g.vanishingPoints.take(3).mapIndexed { i, p -> GuideHandle.entries[GuideHandle.VP0.ordinal + i] to p }
        GuideKind.SYMMETRY -> {
            val (cx, cy) = GuidesOverlay.symmetryCenter(doc)
            listOf(GuideHandle.CENTER to PointF(cx, cy))
        }
        GuideKind.NONE -> emptyList()
    }

    private fun toView(m: Matrix, p: PointF): PointF { pts[0] = p.x; pts[1] = p.y; m.mapPoints(pts); return PointF(pts[0], pts[1]) }

    private fun hit(x: Float, y: Float): GuideHandle? {
        val m = m() ?: return null
        val v = toView(m, PointF(x, y))
        var best: GuideHandle? = null; var bestD = Handles.hitRadius
        for ((h, p) in handlePositions(m)) {
            val pv = toView(m, p)
            val d = hypot(pv.x - v.x, pv.y - v.y)
            if (d < bestD) { bestD = d; best = h }
        }
        return best
    }

    // ------------------------------------------------------------------ Eingabe

    override fun onStrokeStart(p: InputPoint) {
        downX = p.x; downY = p.y; moved = false
        dragging = hit(p.x, p.y)
        val cur = dragging?.let { h -> m()?.let { m -> handlePositions(m).firstOrNull { it.first == h }?.second } }
        grabDx = (cur?.x ?: p.x) - p.x; grabDy = (cur?.y ?: p.y) - p.y
    }

    override fun onStrokeMove(points: List<InputPoint>) {
        val p = points.lastOrNull() ?: return
        val m = m() ?: return
        if (!moved && hypot(p.x - downX, p.y - downY) * scale(m) < 6f * Handles.density) return
        moved = true
        val x = p.x + grabDx; val y = p.y + grabDy
        when (val h = dragging) {
            GuideHandle.MOVE -> { g.offsetX = x - doc.width / 2f; g.offsetY = y - doc.height / 2f }
            GuideHandle.ROTATE -> {
                val o = origin()
                var deg = Math.toDegrees(atan2((y - o.y).toDouble(), (x - o.x).toDouble())).toFloat()
                // Magnetisch auf 15°-Schritte (innerhalb von 3°)
                val snap = (deg / 15f).roundToInt() * 15f
                if (abs(deg - snap) < 3f) deg = snap
                g.rotation = ((deg % 360f) + 360f) % 360f
            }
            GuideHandle.VP0, GuideHandle.VP1, GuideHandle.VP2 -> {
                g.vanishingPoints.getOrNull(h.ordinal - GuideHandle.VP0.ordinal)?.set(x, y)
            }
            GuideHandle.CENTER -> { g.symmetryCenterX = x; g.symmetryCenterY = y }
            null -> return
        }
        changed()
    }

    override fun onStrokeEnd(p: InputPoint) {
        if (!moved) {
            val h = dragging
            if (h != null) {
                selected = if (selected == h) null else h
            } else if (g.kind == GuideKind.PERSPECTIVE && g.vanishingPoints.size < 3) {
                // Tippen ins Leere legt einen Fluchtpunkt an
                g.vanishingPoints.add(PointF(p.x, p.y)); selected = null
            } else selected = null
        }
        dragging = null
        changed()
    }

    override fun onStrokeCancel() { dragging = null }

    override fun drawOverlay(canvas: android.graphics.Canvas, docToView: Matrix) {
        val hs = handlePositions(docToView)
        if (g.kind == GuideKind.GRID_2D || g.kind == GuideKind.ISOMETRIC) {
            val o = toView(docToView, origin()); val r = toView(docToView, rotateHandle(docToView))
            canvas.drawLine(o.x, o.y, r.x, r.y, Handles.line)
        }
        for ((h, p) in hs) {
            val v = toView(docToView, p)
            Handles.draw(canvas, v.x, v.y, if (h == GuideHandle.ROTATE) Handles.GREEN else Handles.BLUE, h == selected)
        }
    }

    // ------------------------------------------------------------------ Aktionen

    fun resetSelected() {
        when (val h = selected) {
            GuideHandle.MOVE -> { g.offsetX = 0f; g.offsetY = 0f }
            GuideHandle.ROTATE -> g.rotation = 0f
            GuideHandle.CENTER -> { g.symmetryCenterX = Float.NaN; g.symmetryCenterY = Float.NaN }
            GuideHandle.VP0, GuideHandle.VP1, GuideHandle.VP2 -> {
                val i = h.ordinal - GuideHandle.VP0.ordinal
                if (i < g.vanishingPoints.size) g.vanishingPoints.removeAt(i)
            }
            null -> {}
        }
        selected = null
        changed()
    }

    fun setKind(k: GuideKind) {
        g.kind = k; selected = null
        if (k == GuideKind.PERSPECTIVE && g.vanishingPoints.isEmpty()) {
            // Startpunkt: Zentralperspektive mit Fluchtpunkt auf halber Höhe
            g.vanishingPoints.add(PointF(doc.width / 2f, doc.height * 0.45f))
        }
        // Symmetrie schaltet den Assistenten wie im Vorbild automatisch ein
        if (k == GuideKind.SYMMETRY) setAssisted(true)
        changed()
    }

    fun setAssisted(on: Boolean) {
        doc.activeLayer?.assisted = on
        // Symmetrie (Kern) liest weiterhin das globale Feld
        g.assisted = on
        changed()
    }
}

// =====================================================================================
// Oberfläche
// =====================================================================================

/** Startet den Vollbild-Editor der Zeichenhilfe. */
fun openGuideEditor(state: EditorState) {
    if (state.document == null) return
    if (ActionModes.crop != null) return
    val s = GuideEditorSession(state)
    ActionModes.guideEditor = s
    s.start()
}

@Composable
fun GuideEditorScreen(state: EditorState, s: GuideEditorSession) {
    val ui = LocalUi.current
    @Suppress("UNUSED_VARIABLE") val t = s.tick
    val g = s.g
    DisposableEffect(s) { onDispose { } }
    Box(Modifier.fillMaxSize()) {
        // ---------------- oben: Leiste + Farbton
        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            ModeTopBar("Zeichenhilfe", onCancel = { s.cancel() }, onDone = { s.done() })
            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(g.color, it) }
            Box(Modifier.fillMaxWidth().background(ui.bar).padding(horizontal = 20.dp, vertical = 6.dp).consumeTouches()) {
                HueBar(hsv[0], Modifier.fillMaxWidth()) { h ->
                    g.color = androidx.compose.ui.graphics.Color.hsv(h.coerceIn(0f, 359.9f), 0.85f, 1f).toArgb(); s.changed()
                }
            }
        }

        // ---------------- Hinweis/Aktion für den gewählten Punkt
        AnimatedVisibility(s.selected != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)) {
            val label = when (s.selected) {
                GuideHandle.VP0, GuideHandle.VP1, GuideHandle.VP2 -> "Fluchtpunkt löschen"
                else -> "Zurücksetzen"
            }
            Box(Modifier.shadow(10.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(ui.panel)
                .consumeTouches()) {
                BarTextButton(label, if (label.startsWith("Flucht")) androidx.compose.ui.graphics.Color(0xFFFF453A) else ui.accent,
                    { s.resetSelected() })
            }
        }

        // ---------------- unten: Typ und Regler
        AnimatedVisibility(true, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.navigationBarsPadding().padding(12.dp).widthIn(max = 640.dp).fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(ui.panel)
                    .consumeTouches().padding(vertical = 8.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp)) {
                    Segmented(listOf(GuideKind.GRID_2D to "2D-Raster", GuideKind.ISOMETRIC to "Isometrisch",
                        GuideKind.PERSPECTIVE to "Perspektive", GuideKind.SYMMETRY to "Symmetrie"),
                        g.kind, Modifier.fillMaxWidth()) { s.setKind(it) }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        SliderRow("Deckkraft", g.opacity, 0.05f..1f, { "${(it * 100).roundToInt()} %" },
                            onChange = { g.opacity = it; s.changed() })
                        SliderRow("Dicke", g.thickness, 0.25f..6f, { String.format(java.util.Locale.GERMANY, "%.1f", it) },
                            onChange = { g.thickness = it; s.changed() })
                    }
                    Column(Modifier.weight(1f)) {
                        when (g.kind) {
                            GuideKind.GRID_2D, GuideKind.ISOMETRIC, GuideKind.PERSPECTIVE ->
                                SliderRow(if (g.kind == GuideKind.PERSPECTIVE) "Rasterabstand" else "Rastergröße",
                                    g.gridSize, 10f..1000f, { "${it.roundToInt()} px" },
                                    onChange = { g.gridSize = it.roundToInt().toFloat(); s.changed() })
                            GuideKind.SYMMETRY -> Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Segmented(listOf(SymmetryMode.VERTICAL to "Vertikal", SymmetryMode.HORIZONTAL to "Horizontal",
                                    SymmetryMode.QUADRANT to "Quadrant", SymmetryMode.RADIAL to "Radial"),
                                    g.symmetry, Modifier.fillMaxWidth()) { g.symmetry = it; s.changed() }
                            }
                            GuideKind.NONE -> {}
                        }
                        SwitchRow("Zeichenassistent", s.doc.activeLayer?.assisted == true,
                            subtitle = "Für die aktive Ebene") { s.setAssisted(it) }
                    }
                }
                if (g.kind == GuideKind.SYMMETRY) {
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f)) {
                            SwitchRow("Rotationssymmetrie", g.rotationalSymmetry) { g.rotationalSymmetry = it; s.changed() }
                        }
                        Box(Modifier.weight(1f)) {
                            if (g.symmetry == SymmetryMode.RADIAL) {
                                SliderRow("Segmente", g.radialSegments.toFloat(), 2f..16f, { it.roundToInt().toString() },
                                    steps = 13, onChange = { g.radialSegments = it.roundToInt(); s.changed() })
                            }
                        }
                    }
                }
                if (g.kind == GuideKind.PERSPECTIVE) {
                    Text(
                        "Tippe auf die Leinwand, um Fluchtpunkte anzulegen (max. 3). Ziehen verschiebt, Antippen wählt aus.",
                        color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                } else if (g.kind != GuideKind.SYMMETRY) {
                    Text("Blauer Punkt verschiebt, grüner Punkt dreht das Raster.", color = ui.textMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    BarTextButton("Alles zurücksetzen", ui.textMuted, {
                        g.offsetX = 0f; g.offsetY = 0f; g.rotation = 0f; g.thickness = 1f
                        g.symmetryCenterX = Float.NaN; g.symmetryCenterY = Float.NaN
                        if (g.kind == GuideKind.PERSPECTIVE) {
                            g.vanishingPoints.clear(); g.vanishingPoints.add(PointF(s.doc.width / 2f, s.doc.height * 0.45f))
                        }
                        s.selected = null; s.changed()
                    })
                }
            }
        }
    }
}
