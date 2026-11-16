package at.techgaisi.farbwerk.timelapse

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.render.Compositor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Zeitraffer-Aufnahme.
 *
 * Nach jeder Verlaufsänderung ruft der [at.techgaisi.farbwerk.undo.UndoManager] [onChange] auf. Die Aufnahme ist
 * gedrosselt (höchstens [MIN_INTERVAL_MS] zwischen zwei Bildern); Änderungen innerhalb des Fensters werden
 * gesammelt und am Fensterende als *ein* Bild aufgenommen. Das Bild wird auf dem Main-Thread verkleinert
 * gerendert (private Ebenen ausgeblendet) und auf einem eigenen Schreib-Thread als JPEG unter
 * `filesDir/projects/<id>/timelapse/000001.jpg …` abgelegt.
 */
object Timelapse {
    /** Mindestabstand zwischen zwei Bildern (≈ 4 Bilder/s). */
    private const val MIN_INTERVAL_MS = 250L
    /** Ruhezeit nach einem Strich, bevor aufgenommen wird (Pausen zwischen schnellen Strichen überbrücken). */
    private const val IDLE_AFTER_STROKE_MS = 350L
    private const val JPEG_QUALITY = 88
    private const val PREFS = "farbwerk_timelapse"
    private const val KEY_ENABLED = "record"
    private const val KEY_MAX_SIDE = "max_side"

    /** Wählbare Obergrenzen der langen Bildseite. */
    val RESOLUTIONS = listOf(1080 to "1080p", 1440 to "2K", 2160 to "4K")

    private val main = Handler(Looper.getMainLooper())
    // Höchstens ein gerendertes Bild beim Schreiber halten; weitere Änderungen zusammenfassen.
    private val writeInFlight = AtomicBoolean(false)
    private val writerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Zeitraffer-Schreiber").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** Ein einziger Schreib-Thread: garantiert die Reihenfolge der Bilder und dient als Synchronisationspunkt. */
    val writer: CoroutineDispatcher = writerExecutor.asCoroutineDispatcher()

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    // Drosselung (nur Main-Thread)
    private var lastCaptureAt = 0L
    private var pendingState: EditorState? = null
    private var scheduled = false
    private val captureRunnable = object : Runnable {
        override fun run() {
            val st = pendingState
            if (st != null) {
                // Nicht während eines Strichs (und kurz danach) aufnehmen: Das Rendern läuft auf dem Main-Thread
                // (bei großen Leinwänden ~50 ms) und würde den laufenden bzw. den nächsten Strich ruckeln lassen.
                val sinceStroke = SystemClock.uptimeMillis() - st.lastStrokeEndAt
                if (st.strokeActive || sinceStroke < IDLE_AFTER_STROKE_MS) {
                    main.postDelayed(this, if (st.strokeActive) IDLE_AFTER_STROKE_MS else IDLE_AFTER_STROKE_MS - sinceStroke)
                    return
                }
            }
            if (st != null && writeInFlight.get()) {
                main.postDelayed(this, MIN_INTERVAL_MS)
                return
            }
            scheduled = false
            st?.let { capture(it) }
            pendingState = null
        }
    }

    // Nächste Bildnummer je Projekt (nur Schreib-Thread)
    private val nextIndex = HashMap<String, Int>()

    /** Anzahl Bilder des zuletzt aufgenommenen bzw. abgefragten Projekts (für die Anzeige im Video-Tab). */
    var frameCount by mutableIntStateOf(0)
        private set
    private var countedId: String? = null

    // ------------------------------------------------------------------ Einstellungen

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        appContext = context.applicationContext
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY_ENABLED, on).apply() }

    fun maxSide(context: Context): Int = prefs(context).getInt(KEY_MAX_SIDE, 1080)
    fun setMaxSide(context: Context, side: Int) { prefs(context).edit().putInt(KEY_MAX_SIDE, side).apply() }

    // ------------------------------------------------------------------ Dateien

    fun dir(context: Context, docId: String): File =
        File(File(File(context.filesDir, "projects"), docId), "timelapse")

    /** Alle Bilder eines Projekts in Aufnahmereihenfolge. Aufruf außerhalb des Main-Threads bevorzugt. */
    fun frames(context: Context, docId: String): List<File> =
        dir(context, docId).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }.orEmpty()

    /** Wartet, bis alle ausstehenden Bilder geschrieben sind, und liefert dann die Bildliste. */
    suspend fun framesFlushed(context: Context, docId: String): List<File> =
        withContext(writer) { frames(context, docId) }

    /** Aktualisiert [frameCount] für [docId] (z. B. beim Öffnen des Video-Tabs). */
    suspend fun refreshCount(context: Context, docId: String) {
        val n = framesFlushed(context, docId).size
        countedId = docId
        frameCount = n
    }

    /** Löscht die Aufnahme eines Projekts. */
    suspend fun clear(context: Context, docId: String) {
        withContext(writer) {
            dir(context, docId).deleteRecursively()
            nextIndex.remove(docId)
        }
        if (countedId == docId) frameCount = 0
    }

    // ------------------------------------------------------------------ Aufnahme

    /** Wird nach jeder Änderung aufgerufen (Main-Thread). */
    fun onChange(state: EditorState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onChange(state) }
            return
        }
        if (state.document == null || !isEnabled(state.context)) return
        pendingState = state
        if (scheduled) return
        val wait = MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastCaptureAt)
        scheduled = true
        // Kurz verzögern, damit nachfolgende Teiländerungen derselben Aktion im Bild landen
        main.postDelayed(captureRunnable, wait.coerceAtLeast(16L))
    }

    /** Rendert das verkleinerte Bild (Main-Thread) und übergibt es dem Schreib-Thread. */
    private fun capture(state: EditorState) {
        val doc = state.document ?: return
        if (doc.width <= 0 || doc.height <= 0 || !isEnabled(state.context)) return
        lastCaptureAt = SystemClock.uptimeMillis()
        val bmp = try { renderScaled(doc, maxSide(state.context)) } catch (t: Throwable) {
            android.util.Log.w("Farbwerk", "Zeitraffer-Bild fehlgeschlagen", t); return
        }
        val id = doc.id
        val folder = dir(state.context, id)
        writeInFlight.set(true)
        try {
            writerExecutor.execute {
                try { write(id, folder, bmp) } finally { writeInFlight.set(false) }
            }
        } catch (t: Throwable) {
            writeInFlight.set(false)
            bmp.recycle()
            android.util.Log.w("Farbwerk", "Zeitraffer-Auftrag fehlgeschlagen", t)
        }
    }

    /** Zusammengesetztes Bild, lange Seite höchstens [maxSide], private Ebenen ausgeblendet, deckend. */
    private fun renderScaled(doc: ArtDocument, maxSide: Int): Bitmap {
        val scale = minOf(1f, maxSide.toFloat() / max(doc.width, doc.height))
        val w = (doc.width * scale).roundToInt().coerceAtLeast(2)
        val h = (doc.height * scale).roundToInt().coerceAtLeast(2)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // JPEG kennt keine Transparenz: ohne sichtbaren Hintergrund auf Weiß setzen
        canvas.drawColor(if (doc.backgroundVisible) doc.backgroundColor else Color.WHITE)
        canvas.scale(w.toFloat() / doc.width, h.toFloat() / doc.height)
        val hidden = ArrayList<Layer>(2)
        for (l in doc.layers) if (l.isPrivate && l.visible) { l.visible = false; hidden.add(l) }
        try {
            Compositor.drawCached(canvas, doc)
        } finally {
            for (l in hidden) l.visible = true
        }
        return out
    }

    /** Schreib-Thread: JPEG mit fortlaufender Nummer ablegen. */
    private fun write(docId: String, folder: File, bmp: Bitmap) {
        try {
            if (!folder.isDirectory && !folder.mkdirs()) return
            val index = nextIndex[docId] ?: (scanMaxIndex(folder) + 1)
            val file = File(folder, String.format(java.util.Locale.ROOT, "%06d.jpg", index))
            val tmp = File(folder, "$index.tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!tmp.renameTo(file)) { tmp.delete(); return }
            nextIndex[docId] = index + 1
            main.post {
                if (countedId == docId) frameCount = index else { countedId = docId; frameCount = index }
            }
        } catch (t: Throwable) {
            android.util.Log.w("Farbwerk", "Zeitraffer schreiben fehlgeschlagen", t)
        } finally {
            bmp.recycle()
        }
    }

    private fun scanMaxIndex(folder: File): Int {
        var m = 0
        folder.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp")) f.delete()
            else if (f.name.endsWith(".jpg")) f.name.removeSuffix(".jpg").toIntOrNull()?.let { if (it > m) m = it }
        }
        return m
    }
}
