package at.techgaisi.farbwerk.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.render.Compositor
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Projektinfo für die Galerie. [stackId] = Stapel, in dem das Projekt liegt (null = Galerie-Ebene).
 */
data class ProjectInfo(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val modified: Long,
    val stackId: String? = null,
    val layerCount: Int = 0,
    val created: Long = modified,
)

/** Ein Stapel (Ordner) in der Galerie. */
data class StackInfo(val id: String, val name: String, val created: Long)

/**
 * Projektspeicher. Layout auf dem Gerät:
 * ```
 * filesDir/projects/index.json              – Stapel + Zuordnung Projekt → Stapel
 * filesDir/projects/<id>/project.farbwerk   – vollständiges Projekt (ZIP, siehe ProjectFormat)
 * filesDir/projects/<id>/meta.json          – Kurzinfo für die Galerie (Name, Größe, Zeit)
 * filesDir/projects/<id>/thumb.png          – Vorschaubild (max. 512 px)
 * ```
 * [save] erledigt auf dem Main-Thread nur die Metadaten (und bei geöffnetem Werk die Miniatur); Pixel werden
 * im Hintergrund (ein serieller Thread + Kodier-Pool) im schnellen Kachelformat geschrieben, nur geänderte
 * Kacheln werden neu komprimiert ([LayerCodec]). Geschrieben wird immer erst in eine temporäre Datei, die danach
 * umbenannt wird – ein Absturz hinterlässt nie ein halbes Projekt.
 *
 * Solange ein Projekt gespeichert wird, liefert [list] seine Kurzinfo aus dem Speicher und [saving] enthält
 * seine ID (Galerie: „Wird gespeichert …“). [load] wartet auf ein laufendes Speichern.
 */
class ProjectStore(private val context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "farbwerk-store").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, Future<*>>()
    /** Signatur (Metadaten ohne Zeitstempel) des zuletzt gespeicherten Standes je Projekt. */
    private val lastSignature = ConcurrentHashMap<String, String>()
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val indexLock = Any()

    /** Kurzinfo von Projekten, deren Speichern noch läuft (Galerie zeigt sie sofort an). */
    private val pendingInfo = ConcurrentHashMap<String, ProjectInfo>()
    /** Geschlossene Dokumente, deren Speichern noch läuft (erneutes Öffnen nutzt sie direkt). */
    private val detachedDocs = ConcurrentHashMap<String, ArtDocument>()
    /** Miniatur eines geschlossenen Dokuments wird gerade gerendert. */
    private val thumbReady = ConcurrentHashMap<String, CountDownLatch>()

    /** Wird nach jeder abgeschlossenen Speicher-/Löschoperation erhöht (Galerie beobachtet das). */
    var changeCount by mutableIntStateOf(0)
        private set

    /** IDs der Projekte, die gerade gespeichert werden (nur auf dem Main-Thread verändert). */
    val saving = mutableStateMapOf<String, Int>()

    /** Letzter Speicherfehler (Galerie zeigt ihn kurz an); null = keiner. */
    var saveError by mutableStateOf<String?>(null)
        private set
    fun clearSaveError() { saveError = null }

    /** Zähler der zuletzt abgeschlossenen Speicherung (Messung/Protokoll). */
    @Volatile internal var lastWriteStats: ProjectFormat.WriteStats? = null
        private set

    private fun notifyChanged() = main.post { changeCount++ }

    private fun markSaving(id: String, delta: Int) {
        val n = (saving[id] ?: 0) + delta
        if (n <= 0) saving.remove(id) else saving[id] = n
    }

    /**
     * Wartet höchstens [timeoutMs], bis alle laufenden Speichervorgänge fertig sind (App-Ende). Liefert true,
     * wenn nichts mehr aussteht.
     */
    fun awaitIdle(timeoutMs: Long): Boolean {
        if (Thread.currentThread().name == "farbwerk-store") return false
        return try { worker.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS); true } catch (_: Throwable) { false }
    }

    private fun dir(id: String) = File(root, id)
    fun projectFile(id: String) = File(dir(id), "project.${ProjectFormat.EXTENSION}")
    private fun metaFile(id: String) = File(dir(id), "meta.json")
    private fun thumbFile(id: String) = File(dir(id), "thumb.png")

    private fun awaitPending(id: String) {
        try { pending[id]?.get() } catch (t: Throwable) { Log.e(TAG, "pending save", t) }
    }

    // =================================================================== Liste

    /**
     * Alle Projekte, neueste zuerst. Wartet **nicht** auf laufende Speichervorgänge: deren Kurzinfo kommt aus
     * dem Speicher (auch für neue Werke, die noch keine Datei haben).
     */
    fun list(): List<ProjectInfo> {
        val members = readIndex().members
        val out = ArrayList<ProjectInfo>()
        val inFlight = HashMap(pendingInfo)
        inFlight.values.forEach { out += it.copy(stackId = members[it.id]) }
        root.listFiles()?.forEach { d ->
            if (!d.isDirectory) return@forEach
            val id = d.name
            if (inFlight.containsKey(id)) return@forEach
            if (!projectFile(id).exists()) return@forEach
            val meta = try { JSONObject(metaFile(id).readText()) } catch (_: Exception) { rebuildMeta(id) }
                ?: return@forEach
            out += ProjectInfo(
                id = id,
                name = meta.optString("name", "Ohne Titel"),
                width = meta.optInt("width"),
                height = meta.optInt("height"),
                modified = meta.optLong("modified"),
                stackId = members[id],
                layerCount = meta.optInt("layers"),
                created = meta.optLong("created", meta.optLong("modified")),
            )
        }
        out.sortByDescending { it.modified }
        return out
    }

    private fun rebuildMeta(id: String): JSONObject? = try {
        val json = ProjectFormat.readJson(projectFile(id))
        writeMeta(id, json); JSONObject(metaFile(id).readText())
    } catch (t: Throwable) { Log.e(TAG, "meta $id", t); null }

    private fun writeMeta(id: String, docJson: JSONObject) {
        val meta = JSONObject()
            .put("id", id)
            .put("name", docJson.optString("name"))
            .put("width", docJson.optInt("width"))
            .put("height", docJson.optInt("height"))
            .put("modified", docJson.optLong("modified", System.currentTimeMillis()))
            .put("created", docJson.optLong("created", System.currentTimeMillis()))
            .put("layers", docJson.optJSONArray("layers")?.length() ?: 0)
        writeAtomic(metaFile(id), meta.toString().toByteArray())
    }

    // =================================================================== Speichern

    /**
     * Speichert [doc], falls sich seit dem letzten Speichern etwas geändert hat. Auf dem Main-Thread aufrufen.
     *
     * Der Main-Thread-Anteil ist klein (Metadaten, bei geöffnetem Werk die Miniatur); die Pixel werden im
     * Hintergrund direkt aus den Ebenen gelesen – je Ebene in einem Zug ([LayerCodec.encode]), ohne Kopien
     * der ganzen Ebenen auf dem Main-Thread.
     *
     * [detached] = true: Das Dokument wird gerade geschlossen und danach nicht mehr verändert. Dann rendert auch
     * die Miniatur im Hintergrund, und ein erneutes Öffnen vor Ende des Speicherns bekommt dasselbe Dokument.
     *
     * Bei geöffnetem Werk ([detached] = false) liest der Hintergrund die Ebenen, während weitergemalt werden kann:
     * Alles bis zum Aufruf ist sicher enthalten; ein Strich, der genau während des Lesens einer Ebene entsteht,
     * landet ggf. erst im nächsten Speichern (das Dokument bleibt dafür nicht „sauber“ markiert, siehe unten).
     */
    fun save(doc: ArtDocument, detached: Boolean = false) {
        val sig = ProjectFormat.toJson(doc, withTimes = false).toString()
        val file = projectFile(doc.id)
        val known = lastSignature[doc.id]
        if (!doc.dirty && known == sig && (file.exists() || pending.containsKey(doc.id))) return
        if (!doc.dirty && known != null) doc.modified = System.currentTimeMillis()
        lastSignature[doc.id] = sig
        val json = ProjectFormat.toJson(doc)
        val id = doc.id
        // Momentaufnahme der Ebenen-Referenzen (Bitmaps selbst werden im Hintergrund gelesen)
        val layers = doc.layers.map { ProjectFormat.LayerData(it.bitmap, it.mask) }
        val info = ProjectInfo(
            id = id, name = doc.name, width = doc.width, height = doc.height, modified = doc.modified,
            layerCount = doc.layers.size, created = doc.created,
        )
        var thumb: Bitmap? = null
        var latch: CountDownLatch? = null
        if (detached) {
            latch = CountDownLatch(1)
            thumbReady[id] = latch
            thumbs.remove(id)
            detachedDocs[id] = doc
        } else {
            thumb = renderThumbnail(doc)
            thumb?.let { thumbs.put(id, it) }
        }
        pendingInfo[id] = info
        markSaving(id, +1)
        doc.dirty = false
        val f = worker.submit {
            var ok = false
            try {
                val t = thumb ?: renderThumbnail(doc)?.also { thumbs.put(id, it) }
                latch?.countDown()
                val stats = ProjectFormat.WriteStats()
                writeProject(id, json, layers, t, stats)
                lastWriteStats = stats
                ok = true
            } catch (t: Throwable) {
                // Speichern darf die App nie beenden (z. B. Speicher voll / zu wenig RAM)
                Log.e(TAG, "save $id", t)
                lastSignature.remove(id)
                if (detached) doc.dirty = true else main.post { doc.dirty = true }
                main.post { saveError = "„${doc.name}“ konnte nicht gespeichert werden" }
            } finally {
                latch?.countDown()
                if (latch != null) thumbReady.remove(id, latch)
                // Bei einem Fehler bleibt ein geschlossenes Dokument im Speicher (Öffnen holt es zurück)
                if (ok || !detached) pendingInfo.remove(id, info)
                if (ok) detachedDocs.remove(id, doc)
                main.post { markSaving(id, -1) }
                notifyChanged()
            }
        }
        pending[id] = f
    }

    /**
     * Schreibt ein Dokument synchron auf dem aufrufenden Thread, ohne Kopien. Nur für Dokumente,
     * die gerade nicht bearbeitet werden (z. B. frisch importierte). Nicht auf dem Main-Thread aufrufen.
     */
    fun saveDetached(doc: ArtDocument, stackId: String? = null) {
        val json = ProjectFormat.toJson(doc)
        val thumb = try { renderThumbnail(doc) } catch (t: Throwable) { null }
        writeProject(doc.id, json, doc.layers.map { ProjectFormat.LayerData(it.bitmap, it.mask) }, thumb)
        lastSignature[doc.id] = ProjectFormat.toJson(doc, withTimes = false).toString()
        if (stackId != null) moveToStack(listOf(doc.id), stackId)
        notifyChanged()
    }

    private fun writeProject(
        id: String, json: JSONObject, layers: List<ProjectFormat.LayerData>, thumb: Bitmap?,
        stats: ProjectFormat.WriteStats? = null,
    ) {
        dir(id).mkdirs()
        ProjectFormat.writeInternal(projectFile(id), json, layers, thumb, stats)
        if (thumb != null) {
            val tmp = File(dir(id), "thumb.png.tmp")
            FileOutputStream(tmp).use { thumb.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(thumbFile(id))) { thumbFile(id).delete(); tmp.renameTo(thumbFile(id)) }
            thumbs.put(id, thumb)
        }
        writeMeta(id, json)
    }

    /** Vorschaubild (max. 512 px) über den Compositor – sehr schnell, da verkleinert gezeichnet. */
    private fun renderThumbnail(doc: ArtDocument): Bitmap? = try {
        val s = min(1f, THUMB_SIZE / max(doc.width, doc.height).toFloat())
        val tw = max(1, (doc.width * s).roundToInt())
        val th = max(1, (doc.height * s).roundToInt())
        val b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(tw / doc.width.toFloat(), th / doc.height.toFloat())
        // Private Ebenen erscheinen nicht in der Miniatur
        val hidden = doc.layers.filter { it.isPrivate && it.drawOverride == null }
        hidden.forEach { it.drawOverride = HIDE }
        try { Compositor.draw(c, doc) } finally { hidden.forEach { if (it.drawOverride === HIDE) it.drawOverride = null } }
        b
    } catch (t: Throwable) { Log.e(TAG, "thumb", t); null }

    private val HIDE: (Canvas) -> Unit = {}

    // =================================================================== Laden

    /**
     * Lädt ein Projekt vollständig (auf einem Hintergrund-Thread aufrufen). Läuft gerade das Speichern eines
     * geschlossenen Dokuments, wird darauf gewartet und dasselbe Dokument zurückgegeben (kein Neu-Laden nötig).
     */
    fun load(id: String): ArtDocument? {
        val inMemory = detachedDocs[id]
        awaitPending(id)
        if (inMemory != null) {
            detachedDocs.remove(id, inMemory)
            if (!inMemory.dirty) lastSignature[id] = ProjectFormat.toJson(inMemory, withTimes = false).toString()
            return inMemory
        }
        val f = projectFile(id)
        if (!f.exists()) return null
        return try {
            val doc = ProjectFormat.read(f, idOverride = id)
            lastSignature[id] = ProjectFormat.toJson(doc, withTimes = false).toString()
            doc
        } catch (t: Throwable) {
            Log.e(TAG, "load $id", t); null
        }
    }

    /** Vorschaubild (Cache, sonst Datei). Wartet nur auf eine gerade entstehende Miniatur, nie aufs Speichern. */
    fun thumbnail(id: String): Bitmap? {
        thumbReady[id]?.let { try { it.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {} }
        thumbs.get(id)?.let { return it }
        val b = try {
            BitmapFactory.decodeFile(thumbFile(id).path) ?: ProjectFormat.readThumb(projectFile(id))
        } catch (_: Throwable) { null }
        if (b != null) thumbs.put(id, b)
        return b
    }

    // =================================================================== Verwalten

    fun delete(id: String) {
        awaitPending(id)
        pendingInfo.remove(id); detachedDocs.remove(id)
        dir(id).deleteRecursively()
        thumbs.remove(id)
        lastSignature.remove(id)
        updateIndex { it.members.remove(id) }
        notifyChanged()
    }

    /** Dupliziert ein Projekt (gleicher Name, gleicher Stapel). Liefert die neue ID. */
    fun duplicate(id: String): String? {
        awaitPending(id)
        val src = projectFile(id)
        if (!src.exists()) return null
        val newId = UUID.randomUUID().toString()
        return try {
            val now = System.currentTimeMillis()
            var json: JSONObject? = null
            ProjectFormat.rewrite(src, projectFile(newId)) {
                it.put("id", newId); it.put("created", now); it.put("modified", now); json = it
            }
            thumbFile(id).takeIf { it.exists() }?.copyTo(thumbFile(newId), overwrite = true)
            writeMeta(newId, json!!)
            val stack = readIndex().members[id]
            if (stack != null) updateIndex { it.members[newId] = stack }
            notifyChanged()
            newId
        } catch (t: Throwable) {
            Log.e(TAG, "duplicate", t); dir(newId).deleteRecursively(); null
        }
    }

    fun rename(id: String, newName: String) {
        val name = newName.trim().ifEmpty { "Ohne Titel" }
        awaitPending(id)
        val f = projectFile(id)
        if (!f.exists()) return
        try {
            var json: JSONObject? = null
            ProjectFormat.rewrite(f, f) { it.put("name", name); json = it }
            writeMeta(id, json!!)
            lastSignature.remove(id)
            notifyChanged()
        } catch (t: Throwable) { Log.e(TAG, "rename", t) }
    }

    /**
     * Importiert eine .farbwerk-Datei (Inhalt-URI). Das Projekt bekommt eine neue ID, damit es
     * kein vorhandenes überschreibt. Liefert die neue ID oder null. Hintergrund-Thread.
     */
    fun importProjectZip(uri: Uri, stackId: String? = null): String? {
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}.zip")
        val newId = UUID.randomUUID().toString()
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(tmp).use { ins.copyTo(it, 1 shl 16) }
            } ?: return null
            var json: JSONObject? = null
            ProjectFormat.rewrite(tmp, projectFile(newId)) {
                if (it.optString("format", ProjectFormat.FORMAT) != ProjectFormat.FORMAT) {
                    throw java.io.IOException("Keine Farbwerk-Datei")
                }
                it.put("id", newId)
                if (!it.has("modified")) it.put("modified", System.currentTimeMillis())
                json = it
            }
            ProjectFormat.readThumb(projectFile(newId))?.let { t ->
                FileOutputStream(thumbFile(newId)).use { t.compress(Bitmap.CompressFormat.PNG, 100, it) }
                thumbs.put(newId, t)
            }
            writeMeta(newId, json!!)
            if (stackId != null) moveToStack(listOf(newId), stackId)
            notifyChanged()
            newId
        } catch (t: Throwable) {
            Log.e(TAG, "import", t); dir(newId).deleteRecursively(); null
        } finally {
            tmp.delete()
        }
    }

    /**
     * Schreibt [doc] als .farbwerk-Datei nach cacheDir/exports/ (zum Teilen) – synchron, liest die
     * Ebenen direkt. Für die UI besser [Exporters.export] verwenden (Kopie auf Main, Kodieren im Hintergrund).
     */
    fun exportProjectZip(doc: ArtDocument): File {
        val out = File(Exporters.exportDir(context), Exporters.safeName(doc.name) + "." + ProjectFormat.EXTENSION)
        ProjectFormat.write(out, ProjectFormat.toJson(doc),
            doc.layers.map { ProjectFormat.LayerData(it.bitmap, it.mask) }, renderThumbnail(doc))
        return out
    }

    /** Kopiert die gespeicherte Projektdatei nach exports/ (Teilen aus der Galerie). */
    fun exportStoredProject(id: String, name: String): File? {
        awaitPending(id)
        val src = projectFile(id)
        if (!src.exists()) return null
        val out = File(Exporters.exportDir(context), Exporters.safeName(name) + "." + ProjectFormat.EXTENSION)
        src.copyTo(out, overwrite = true)
        return out
    }

    // =================================================================== Stapel

    private class Index(
        val stacks: MutableMap<String, StackInfo>,
        val members: MutableMap<String, String>,
        /** Manuelle Reihenfolge der Galerie (Schlüssel "p:<id>" bzw. "s:<id>"), vorne = oben. */
        val order: MutableList<String> = ArrayList(),
    )

    private fun readIndex(): Index = synchronized(indexLock) {
        val stacks = LinkedHashMap<String, StackInfo>()
        val members = HashMap<String, String>()
        val order = ArrayList<String>()
        try {
            if (indexFile.exists()) {
                val o = JSONObject(indexFile.readText())
                o.optJSONArray("stacks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.getJSONObject(i)
                        val id = s.getString("id")
                        stacks[id] = StackInfo(id, s.optString("name", "Stapel"), s.optLong("created"))
                    }
                }
                o.optJSONObject("members")?.let { m ->
                    for (k in m.keys()) { val v = m.getString(k); if (stacks.containsKey(v)) members[k] = v }
                }
                o.optJSONArray("order")?.let { a -> for (i in 0 until a.length()) order += a.getString(i) }
            }
        } catch (t: Throwable) { Log.e(TAG, "index", t) }
        Index(stacks, members, order)
    }

    private fun updateIndex(block: (Index) -> Unit) = synchronized(indexLock) {
        val idx = readIndex()
        block(idx)
        // leere Stapel automatisch auflösen
        val used = idx.members.values.toSet()
        idx.stacks.keys.retainAll { it in used }
        val o = JSONObject()
        o.put("stacks", org.json.JSONArray().apply {
            idx.stacks.values.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("created", it.created)) }
        })
        o.put("members", JSONObject().apply { idx.members.forEach { (k, v) -> put(k, v) } })
        o.put("order", org.json.JSONArray().apply { idx.order.distinct().forEach { put(it) } })
        writeAtomic(indexFile, o.toString().toByteArray())
    }

    fun stacks(): List<StackInfo> = readIndex().stacks.values.toList()

    /** Manuelle Galerie-Reihenfolge (Schlüssel "p:<id>"/"s:<id>"); Unbekanntes kommt nach Änderungszeit davor. */
    fun order(): List<String> = readIndex().order

    /**
     * Speichert eine neue Reihenfolge für die angegebenen Schlüssel. Andere bekannte Schlüssel behalten ihre
     * relative Lage dahinter (z. B. Inhalte anderer Stapel).
     */
    fun setOrder(keys: List<String>) {
        updateIndex { idx ->
            val rest = idx.order.filter { it !in keys }
            idx.order.clear(); idx.order.addAll(keys); idx.order.addAll(rest)
        }
        notifyChanged()
    }

    /** Legt einen Stapel mit den angegebenen Projekten an. Liefert die Stapel-ID. */
    fun createStack(name: String, projectIds: Collection<String>): String {
        val sid = UUID.randomUUID().toString()
        updateIndex { idx ->
            idx.stacks[sid] = StackInfo(sid, name.ifBlank { "Stapel" }, System.currentTimeMillis())
            projectIds.forEach { idx.members[it] = sid }
        }
        notifyChanged()
        return sid
    }

    /** Verschiebt Projekte in einen Stapel (null = zurück in die Galerie). */
    fun moveToStack(projectIds: Collection<String>, stackId: String?) {
        updateIndex { idx ->
            projectIds.forEach { if (stackId == null || !idx.stacks.containsKey(stackId)) idx.members.remove(it) else idx.members[it] = stackId }
        }
        notifyChanged()
    }

    fun renameStack(stackId: String, name: String) {
        updateIndex { idx -> idx.stacks[stackId]?.let { idx.stacks[stackId] = it.copy(name = name.ifBlank { "Stapel" }) } }
        notifyChanged()
    }

    /** Löst einen Stapel auf; die Projekte bleiben in der Galerie erhalten. */
    fun dissolveStack(stackId: String) {
        updateIndex { idx -> idx.members.entries.removeAll { it.value == stackId }; idx.stacks.remove(stackId) }
        notifyChanged()
    }

    // =================================================================== Hilfen

    private fun writeAtomic(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Belegter Speicher eines Projekts in Bytes. */
    fun sizeOnDisk(id: String): Long = projectFile(id).length()

    companion object {
        private const val TAG = "FarbwerkStore"
        const val THUMB_SIZE = 512f
    }
}
