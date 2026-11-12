package at.techgaisi.farbwerk.animation

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.LayerOps
import at.techgaisi.farbwerk.render.LayerGroups
import at.techgaisi.farbwerk.undo.LambdaAction

/**
 * Ein Bild (Animation) bzw. eine Seite (Seitenassistent): eine Ebene ohne Gruppe oder eine ganze Gruppe.
 * [key] ist stabil (Ebenen- bzw. Gruppen-ID) und dient z. B. für die Haltedauer.
 */
class Frame(val key: String, val layers: List<Layer>, val groupId: String?) {
    /** Oberste Ebene des Bildes (wird beim Anwählen zur aktiven Ebene). */
    val top: Layer get() = layers.last()
    fun name(doc: ArtDocument): String =
        if (groupId != null) LayerGroups.find(doc, groupId)?.name ?: "Gruppe" else layers.first().name
}

/**
 * Steuerung von Animations- und Seitenassistent.
 *
 * Darstellung ohne Eingriff in den Compositor: Ebenen nicht angezeigter Bilder bekommen den
 * [Layer.drawOverride] [HIDE] (zeichnet nichts). Das gilt für Anzeige, Miniatur und Export gleichermaßen –
 * wie im Vorbild zeigt die Leinwand nur das aktuelle Bild (plus Hinter-/Vordergrund). Nur selbst gesetzte
 * HIDE-Overrides werden wieder entfernt; Overrides anderer Werkzeuge bleiben unangetastet.
 *
 * Zwiebelschichten zeichnet [drawOnCanvas] (aufgerufen aus dem Hilfslinien-Overlay) eingefärbt über das Dokument.
 */
object AnimationController {
    /** Zeichnet nichts – blendet die Ebene in Anzeige und Export aus. */
    val HIDE: (Canvas) -> Unit = {}

    /** Wiedergabe läuft. */
    var playing by mutableStateOf(false)
        private set

    /** Bildindex während der Wiedergabe (sonst −1). */
    var playFrame by mutableIntStateOf(-1)
        private set

    /** Wird bei Strukturänderungen erhöht (Zeitleiste beobachtet das zusätzlich zu state.revision). */
    var version by mutableIntStateOf(0)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var playState: EditorState? = null
    private var sequence: IntArray = IntArray(0)
    private var seqPos = 0

    private val onionPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var syncedRevision = -1
    private var syncedDoc: ArtDocument? = null
    private var syncedPlayFrame = -2

    // ------------------------------------------------------------------ Abfragen

    fun settings(doc: ArtDocument?): AnimationSettings? = doc?.animation as? AnimationSettings

    fun isOn(doc: ArtDocument?, mode: AssistMode): Boolean = settings(doc)?.mode == mode

    /** Bilder von links (unterste Ebene) nach rechts. Gruppenmitglieder bilden ein gemeinsames Bild. */
    fun frames(doc: ArtDocument): List<Frame> {
        val out = ArrayList<Frame>()
        var i = 0
        val ls = doc.layers
        while (i < ls.size) {
            val gid = ls[i].groupId
            if (gid == null) {
                out += Frame(ls[i].id, listOf(ls[i]), null); i++
            } else {
                val start = i
                while (i < ls.size && ls[i].groupId == gid) i++
                out += Frame("g:$gid", ls.subList(start, i).toList(), gid)
            }
        }
        return out
    }

    /** Index des Bildes, das die aktive Ebene enthält. */
    fun currentIndex(doc: ArtDocument, frames: List<Frame> = frames(doc)): Int {
        val active = doc.activeLayer ?: return 0
        return frames.indexOfFirst { f -> f.layers.any { it === active } }.coerceAtLeast(0)
    }

    fun holdOf(doc: ArtDocument, f: Frame): Int = settings(doc)?.holds?.get(f.key) ?: 0

    // ------------------------------------------------------------------ Ein/Aus

    /** Schaltet den Assistenten [mode] ein (bzw. aus, wenn er schon aktiv ist). */
    fun toggle(state: EditorState, mode: AssistMode) {
        val doc = state.document ?: return
        val cur = settings(doc)
        if (cur?.mode == mode) { disable(state); return }
        stop()
        doc.animation = (cur ?: AnimationSettings()).also { it.mode = mode }
        state.markDirty()
        sync(state, force = true)
        bump(state)
    }

    fun disable(state: EditorState) {
        val doc = state.document ?: return
        stop()
        doc.animation = null
        clearHidden(doc)
        state.markDirty()
        bump(state)
    }

    private fun bump(state: EditorState) { version++; state.invalidate() }

    /** Einstellungen wurden in der Oberfläche geändert. */
    fun settingsChanged(state: EditorState) { state.markDirty(); sync(state, force = true); bump(state) }

    // ------------------------------------------------------------------ Sichtbarkeit

    private fun clearHidden(doc: ArtDocument) {
        for (l in doc.layers) if (l.drawOverride === HIDE) l.drawOverride = null
    }

    /**
     * Gleicht die Sichtbarkeit der Bilder ab (nur wenn sich seit dem letzten Mal etwas geändert hat).
     * Liefert true, wenn sich etwas geändert hat (dann muss neu gezeichnet werden).
     */
    fun sync(state: EditorState, force: Boolean = false): Boolean {
        val doc = state.document ?: return false
        if (!force && doc === syncedDoc && state.revision == syncedRevision && playFrame == syncedPlayFrame) return false
        syncedDoc = doc; syncedRevision = state.revision; syncedPlayFrame = playFrame
        val s = settings(doc)
        if (s == null) {
            var changed = false
            for (l in doc.layers) if (l.drawOverride === HIDE) { l.drawOverride = null; changed = true }
            return changed
        }
        val frames = frames(doc)
        if (frames.isEmpty()) return false
        val cur = if (playing && playFrame in frames.indices) playFrame else currentIndex(doc, frames)
        var changed = false
        frames.forEachIndexed { i, f ->
            val shown = i == cur ||
                (i == 0 && s.firstIsBackground && frames.size > 1) ||
                (i == frames.lastIndex && s.lastIsForeground && frames.size > 1 && s.mode == AssistMode.ANIMATION)
            for (l in f.layers) {
                if (shown) {
                    if (l.drawOverride === HIDE) { l.drawOverride = null; changed = true }
                } else if (l.drawOverride == null) {
                    l.drawOverride = HIDE; changed = true
                }
            }
        }
        return changed
    }

    // ------------------------------------------------------------------ Leinwand

    /** Aufruf aus dem Overlay: Sichtbarkeit abgleichen und Zwiebelschichten zeichnen. */
    fun drawOnCanvas(canvas: Canvas, state: EditorState, doc: ArtDocument, docToView: Matrix) {
        val s = settings(doc)
        if (s == null) {
            if (syncedDoc !== doc || syncedRevision != state.revision) {
                if (sync(state)) state.canvasView?.postInvalidateOnAnimation()
            }
            return
        }
        if (sync(state)) state.canvasView?.postInvalidateOnAnimation()
        if (s.mode != AssistMode.ANIMATION || playing || s.onionFrames <= 0 || s.onionOpacity <= 0f) return
        val frames = frames(doc)
        val cur = currentIndex(doc, frames)
        val first = if (s.firstIsBackground) 1 else 0
        val last = if (s.lastIsForeground) frames.lastIndex - 1 else frames.lastIndex
        canvas.save()
        canvas.concat(docToView)
        canvas.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        // Fernere zuerst, damit nähere obenauf liegen
        for (k in s.onionFrames downTo 1) {
            val a = s.onionOpacity * (1f - (k - 1f) / s.onionFrames)
            drawOnion(canvas, doc, frames, cur - k, first, last, s.onionPrevColor, a)
            drawOnion(canvas, doc, frames, cur + k, first, last, s.onionNextColor, a)
        }
        canvas.restore()
    }

    private fun drawOnion(canvas: Canvas, doc: ArtDocument, frames: List<Frame>, idx: Int, first: Int, last: Int,
                          color: Int, alpha: Float) {
        if (idx < first || idx > last || idx !in frames.indices) return
        onionPaint.colorFilter = PorterDuffColorFilter(color or (0xFF shl 24), PorterDuff.Mode.SRC_IN)
        onionPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        for (l in frames[idx].layers) {
            if (!l.visible || !LayerGroups.isVisible(doc, l.groupId)) continue
            canvas.drawBitmap(l.bitmap, 0f, 0f, onionPaint)
        }
    }

    // ------------------------------------------------------------------ Bilder bearbeiten

    fun selectFrame(state: EditorState, index: Int) {
        val doc = state.document ?: return
        val frames = frames(doc)
        val f = frames.getOrNull(index) ?: return
        if (playing) stop()
        LayerOps.setActive(state, doc.indexOf(f.top))
        sync(state, force = true)
        bump(state)
    }

    /** Neues leeres Bild rechts neben dem aktuellen (Undo-fähig). */
    fun addFrame(state: EditorState) {
        val doc = state.document ?: return
        if (doc.layers.size >= LayerOps.maxLayers(state)) { state.flash("Maximale Ebenenzahl erreicht"); return }
        stop()
        val frames = frames(doc)
        val cur = frames.getOrNull(currentIndex(doc, frames))
        val insertAt = if (cur == null) doc.layers.size else doc.indexOf(cur.top) + 1
        val s = settings(doc)
        val name = (if (s?.mode == AssistMode.PAGES) "Seite " else "Bild ") + (frames.size + 1)
        val layer = Layer(name, doc.newLayerBitmap())
        val prevActive = doc.activeIndex
        val apply = {
            doc.layers.add(insertAt.coerceAtMost(doc.layers.size), layer)
            doc.activeIndex = doc.indexOf(layer)
        }
        apply()
        state.undo.push(LambdaAction(if (s?.mode == AssistMode.PAGES) "Neue Seite" else "Bild hinzufügen",
            onUndo = {
                doc.layers.remove(layer)
                doc.activeIndex = prevActive.coerceIn(0, doc.layers.lastIndex)
                version++
            },
            onRedo = { apply(); version++ },
            bytes = layer.bitmap.allocationByteCount.toLong()))
        sync(state, force = true)
        bump(state)
    }

    /** Dupliziert ein Bild (alle Ebenen, Gruppe wird mitkopiert) direkt rechts daneben. */
    fun duplicateFrame(state: EditorState, index: Int) {
        val doc = state.document ?: return
        val f = frames(doc).getOrNull(index) ?: return
        if (doc.layers.size + f.layers.size > LayerOps.maxLayers(state)) { state.flash("Maximale Ebenenzahl erreicht"); return }
        stop()
        val newGroup = f.groupId?.let { gid ->
            val g = LayerGroups.create(doc, (LayerGroups.find(doc, gid)?.name ?: "Gruppe") + " Kopie")
            g.id
        }
        val copies = f.layers.map { src ->
            LayerOps.copyLayer(src).also {
                it.groupId = newGroup; it.name = src.name; it.isPrivate = src.isPrivate
                if (it.drawOverride === HIDE) it.drawOverride = null
            }
        }
        val insertAt = doc.indexOf(f.top) + 1
        val prevActive = doc.activeIndex
        val apply = {
            doc.layers.addAll(insertAt.coerceAtMost(doc.layers.size), copies)
            doc.activeIndex = doc.indexOf(copies.last())
            settings(doc)?.let { s -> s.holds[f.key]?.let { h -> s.holds[if (newGroup != null) "g:$newGroup" else copies.last().id] = h } }
        }
        apply()
        state.undo.push(LambdaAction("Bild duplizieren",
            onUndo = {
                doc.layers.removeAll { l -> copies.any { it === l } }
                doc.activeIndex = prevActive.coerceIn(0, doc.layers.lastIndex)
                version++
            },
            onRedo = { apply(); version++ },
            bytes = copies.sumOf { it.bitmap.allocationByteCount.toLong() }))
        sync(state, force = true)
        bump(state)
    }

    fun deleteFrame(state: EditorState, index: Int) {
        val doc = state.document ?: return
        val f = frames(doc).getOrNull(index) ?: return
        stop()
        LayerOps.deleteLayers(state, f.layers.map { it.id })
        sync(state, force = true)
        bump(state)
    }

    fun setHold(state: EditorState, index: Int, hold: Int) {
        val doc = state.document ?: return
        val s = settings(doc) ?: return
        val f = frames(doc).getOrNull(index) ?: return
        if (hold <= 0) s.holds.remove(f.key) else s.holds[f.key] = hold.coerceAtMost(120)
        state.markDirty(); version++
    }

    /** Für QuickMenu/Tastatur: nächstes Bild bzw. nächste Seite (auf der letzten: neue Seite im Seitenmodus). */
    fun next(state: EditorState) {
        val doc = state.document ?: return
        val frames = frames(doc)
        val cur = currentIndex(doc, frames)
        if (cur >= frames.lastIndex) {
            if (settings(doc)?.mode == AssistMode.PAGES) addFrame(state)
        } else selectFrame(state, cur + 1)
    }

    fun previous(state: EditorState) {
        val doc = state.document ?: return
        val cur = currentIndex(doc)
        if (cur > 0) selectFrame(state, cur - 1)
    }

    // ------------------------------------------------------------------ Wiedergabe

    /** Abspielreihenfolge (Bildindizes inkl. Haltedauer), ohne Hinter-/Vordergrund. */
    fun playbackSequence(doc: ArtDocument): IntArray {
        val s = settings(doc) ?: return IntArray(0)
        val frames = frames(doc)
        val first = if (s.firstIsBackground && frames.size > 1) 1 else 0
        val last = if (s.lastIsForeground && frames.size > 1) frames.lastIndex - 1 else frames.lastIndex
        val forward = ArrayList<Int>()
        for (i in first..last) {
            val reps = 1 + holdOf(doc, frames[i])
            repeat(reps) { forward += i }
        }
        if (s.playback == PlaybackMode.PING_PONG && forward.size > 2) {
            val back = forward.subList(1, forward.size - 1).asReversed()
            return (forward + back).toIntArray()
        }
        return forward.toIntArray()
    }

    fun togglePlay(state: EditorState) { if (playing) stop() else play(state) }

    fun play(state: EditorState) {
        val doc = state.document ?: return
        val seq = playbackSequence(doc)
        if (seq.isEmpty()) return
        playState = state
        sequence = seq
        val cur = currentIndex(doc)
        seqPos = seq.indexOfFirst { it == cur }.coerceAtLeast(0)
        playing = true
        playFrame = seq[seqPos]
        sync(state, force = true)
        state.invalidate()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, frameDelay(doc))
    }

    private fun frameDelay(doc: ArtDocument): Long = 1000L / (settings(doc)?.fps ?: 15).coerceIn(1, 60)

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val st = playState ?: return
            val doc = st.document
            if (!playing || doc == null || settings(doc) == null) { stop(); return }
            seqPos++
            if (seqPos >= sequence.size) {
                if (settings(doc)?.playback == PlaybackMode.ONCE) { stop(); return }
                seqPos = 0
            }
            playFrame = sequence[seqPos]
            sync(st, force = true)
            st.canvasView?.postInvalidateOnAnimation()
            handler.postDelayed(this, frameDelay(doc))
        }
    }

    /** Hält die Wiedergabe an; die Leinwand zeigt wieder das aktive Bild. */
    fun stop() {
        handler.removeCallbacks(tick)
        if (!playing) return
        playing = false
        playFrame = -1
        playState?.let { sync(it, force = true); it.invalidate() }
        playState = null
    }
}
