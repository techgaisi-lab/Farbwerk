package at.techgaisi.farbwerk.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.model.ArtDocument
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.WeakHashMap

/**
 * Metadaten einer Ebenengruppe. Die Zugehörigkeit einer Ebene steht in [at.techgaisi.farbwerk.model.Layer.groupId];
 * Name, Sichtbarkeit, Einklapp-Zustand und Deckkraft der Gruppe liegen hier.
 * Gruppen verhalten sich wie Ordner: Sichtbarkeit und Deckkraft wirken auf alle enthaltenen Ebenen.
 */
class LayerGroup(val id: String, name: String) {
    var name by mutableStateOf(name)
    var visible by mutableStateOf(true)
    var collapsed by mutableStateOf(false)
    var opacity by mutableFloatStateOf(1f)
}

/**
 * Register der Gruppen je Dokument (schwach referenziert, damit geschlossene Dokumente freigegeben werden).
 * Fehlt zu einer groupId ein Eintrag (z. B. nach dem Laden), wird automatisch eine Standardgruppe angelegt.
 *
 * Für die Speicherung: [toJson] / [fromJson] (ProjectStore kann das ins Projekt schreiben).
 */
object LayerGroups {
    private val registry = WeakHashMap<ArtDocument, MutableMap<String, LayerGroup>>()

    private fun map(doc: ArtDocument): MutableMap<String, LayerGroup> =
        synchronized(registry) { registry.getOrPut(doc) { LinkedHashMap() } }

    /** Gruppe zu [id] (wird bei Bedarf angelegt). */
    fun get(doc: ArtDocument, id: String): LayerGroup = synchronized(registry) {
        map(doc).getOrPut(id) { LayerGroup(id, "Gruppe") }
    }

    fun find(doc: ArtDocument, id: String?): LayerGroup? =
        if (id == null) null else synchronized(registry) { registry[doc]?.get(id) }

    /** Legt eine neue Gruppe an. Name: "Neue Gruppe" bzw. durchnummeriert. */
    fun create(doc: ArtDocument, name: String? = null): LayerGroup {
        val g = LayerGroup(UUID.randomUUID().toString(), name ?: nextName(doc))
        synchronized(registry) { map(doc)[g.id] = g }
        return g
    }

    /** Fügt eine (z. B. per Undo wiederhergestellte) Gruppe wieder ein. */
    fun put(doc: ArtDocument, group: LayerGroup) = synchronized(registry) { map(doc)[group.id] = group }

    private fun nextName(doc: ArtDocument): String {
        val used = doc.layers.mapNotNull { it.groupId }.toSet()
        val names = used.mapNotNull { find(doc, it)?.name }.toSet()
        if ("Neue Gruppe" !in names) return "Neue Gruppe"
        var n = 2
        while ("Neue Gruppe $n" in names) n++
        return "Neue Gruppe $n"
    }

    /** True, wenn die Gruppe (falls vorhanden) sichtbar ist. */
    fun isVisible(doc: ArtDocument, groupId: String?): Boolean =
        groupId == null || find(doc, groupId)?.visible != false

    /** Deckkraft-Faktor der Gruppe (1 ohne Gruppe). */
    fun opacity(doc: ArtDocument, groupId: String?): Float =
        if (groupId == null) 1f else find(doc, groupId)?.opacity ?: 1f

    /** Serialisiert alle aktuell benutzten Gruppen. */
    fun toJson(doc: ArtDocument): JSONArray {
        val arr = JSONArray()
        val used = doc.layers.mapNotNull { it.groupId }.distinct()
        for (id in used) {
            val g = get(doc, id)
            arr.put(JSONObject().apply {
                put("id", g.id); put("name", g.name); put("visible", g.visible)
                put("collapsed", g.collapsed); put("opacity", g.opacity.toDouble())
            })
        }
        return arr
    }

    /** Stellt Gruppen-Metadaten aus [toJson] wieder her. */
    fun fromJson(doc: ArtDocument, arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val g = LayerGroup(o.getString("id"), o.optString("name", "Gruppe"))
            g.visible = o.optBoolean("visible", true)
            g.collapsed = o.optBoolean("collapsed", false)
            g.opacity = o.optDouble("opacity", 1.0).toFloat()
            put(doc, g)
        }
    }
}
