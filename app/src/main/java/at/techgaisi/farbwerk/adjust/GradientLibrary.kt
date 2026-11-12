package at.techgaisi.farbwerk.adjust

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

/** Farbstopp eines Verlaufs: Position 0 (Schatten) … 1 (Lichter), Farbe ARGB (deckend). */
class GradientStop(var pos: Float, var color: Int) {
    fun copy() = GradientStop(pos, color)
}

/** Ein Verlauf der Verlaufsumsetzung. */
class GradientDef(val id: String, var name: String, val stops: MutableList<GradientStop>) {

    fun copy(newId: String = id, newName: String = name) =
        GradientDef(newId, newName, stops.mapTo(mutableListOf()) { it.copy() })

    /** Stopps nach Position sortiert (Kopie). */
    fun sorted(): List<GradientStop> = stops.sortedBy { it.pos }

    /** 256-Einträge-LUT (ARGB, deckend) – Index = Helligkeit. */
    fun lut(): IntArray {
        val s = sorted()
        val out = IntArray(256)
        if (s.isEmpty()) { for (i in 0 until 256) out[i] = (0xFF shl 24) or (i shl 16) or (i shl 8) or i; return out }
        var k = 0
        for (i in 0 until 256) {
            val t = i / 255f
            while (k < s.size - 2 && t > s[k + 1].pos) k++
            out[i] = when {
                t <= s.first().pos -> s.first().color
                t >= s.last().pos -> s.last().color
                else -> {
                    val a = s[k]; val b = s[k + 1]
                    val f = ((t - a.pos) / (b.pos - a.pos).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
                    mix(a.color, b.color, f)
                }
            } or (0xFF shl 24)
        }
        return out
    }

    /** Farbe an Position [t] (für neue Stopps). */
    fun colorAt(t: Float): Int = lut()[(t.coerceIn(0f, 1f) * 255f).roundToInt()]

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("stops", JSONArray().apply { stops.forEach { put(JSONObject().put("p", it.pos.toDouble()).put("c", it.color)) } })
    }

    companion object {
        fun fromJson(o: JSONObject): GradientDef? = try {
            val arr = o.getJSONArray("stops")
            val stops = MutableList(arr.length()) {
                val s = arr.getJSONObject(it)
                GradientStop(s.getDouble("p").toFloat().coerceIn(0f, 1f), s.getInt("c") or (0xFF shl 24))
            }
            if (stops.size < 2) null else GradientDef(o.getString("id"), o.optString("name", "Verlauf"), stops)
        } catch (_: Exception) { null }

        /** Gleichmäßig verteilte Stopps aus Farben. */
        fun even(id: String, name: String, vararg colors: Int) = GradientDef(id, name,
            colors.mapIndexedTo(mutableListOf()) { i, c -> GradientStop(i / (colors.size - 1f), c) })

        fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).roundToInt()).coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/**
 * Verlaufsbibliothek der Verlaufsumsetzung: 8 eigene Vorgaben, eigene Verläufe anlegen, duplizieren,
 * löschen, umsortieren, bearbeiten; dauerhaft gespeichert (eigene SharedPreferences-Datei).
 * Die Liste ist Compose-Zustand; nach Änderungen an Stopps/Namen [changed] aufrufen.
 */
class GradientLibrary(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("farbwerk_gradients", Context.MODE_PRIVATE)

    val items = mutableStateListOf<GradientDef>()
    /** Erhöht sich bei jeder Änderung an einem Verlauf (Stopps/Name) – Compose liest das. */
    var version by mutableIntStateOf(0)
        private set

    init { load() }

    private fun load() {
        items.clear()
        val raw = sp.getString(KEY, null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) GradientDef.fromJson(arr.getJSONObject(i))?.let { items.add(it) }
            } catch (_: Exception) { items.clear() }
        }
        if (items.isEmpty()) items.addAll(defaults())
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    fun indexOf(id: String?): Int = items.indexOfFirst { it.id == id }

    /** Nach Änderungen an Stopps oder Name eines Verlaufs (speichert). */
    fun changed() { version++; save() }

    /** Während einer laufenden Geste: nur neu zeichnen, noch nicht speichern. */
    fun touch() { version++ }

    /** Neuer Verlauf (Schwarz → Weiß) am Ende; liefert seinen Index. */
    fun add(): Int {
        items.add(GradientDef.even(newId(), "Neuer Verlauf", 0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        changed()
        return items.lastIndex
    }

    fun duplicate(index: Int): Int {
        val src = items.getOrNull(index) ?: return -1
        items.add(index + 1, src.copy(newId(), src.name + " Kopie"))
        changed()
        return index + 1
    }

    /** Löscht einen Verlauf (der letzte bleibt immer erhalten). */
    fun delete(index: Int): Boolean {
        if (items.size <= 1 || index !in items.indices) return false
        items.removeAt(index)
        changed()
        return true
    }

    fun move(from: Int, to: Int) {
        if (from !in items.indices) return
        val t = to.coerceIn(0, items.lastIndex)
        if (t == from) return
        val g = items.removeAt(from)
        items.add(t, g)
        changed()
    }

    /** Stellt die Vorgaben wieder her (eigene Verläufe bleiben am Ende erhalten). */
    fun restoreDefaults() {
        val defs = defaults()
        val defIds = defs.map { it.id }.toSet()
        val own = items.filter { it.id !in defIds }
        items.clear()
        items.addAll(defs)
        items.addAll(own)
        changed()
    }

    private fun newId() = "g_" + UUID.randomUUID().toString().take(8)

    companion object {
        private const val KEY = "library_v1"

        /** Die 8 mitgelieferten Vorgaben (eigene Farbzusammenstellungen). */
        fun defaults(): List<GradientDef> = listOf(
            GradientDef.even("d_noir", "Noir", 0xFF050505.toInt(), 0xFF4A4A4A.toInt(), 0xFFF2F2F2.toInt()),
            GradientDef.even("d_dusk", "Abendrot", 0xFF1B0A3A.toInt(), 0xFFB0255E.toInt(), 0xFFF58A3C.toInt(), 0xFFFFE3A0.toInt()),
            GradientDef.even("d_ocean", "Ozean", 0xFF021428.toInt(), 0xFF0B5C8C.toInt(), 0xFF38B3C9.toInt(), 0xFFE2FAF7.toInt()),
            GradientDef.even("d_sepia", "Sepia", 0xFF2B1D14.toInt(), 0xFF8A6A48.toInt(), 0xFFE7D3A8.toInt()),
            GradientDef.even("d_neon", "Neon", 0xFF12002B.toInt(), 0xFFFF2E9A.toInt(), 0xFF2EF2FF.toInt()),
            GradientDef.even("d_heat", "Wärmebild", 0xFF000020.toInt(), 0xFF6A00A8.toInt(), 0xFFE0303A.toInt(), 0xFFFFB000.toInt(), 0xFFFFFFC8.toInt()),
            GradientDef.even("d_forest", "Wald", 0xFF0E1A0E.toInt(), 0xFF2F5D2A.toInt(), 0xFF9CC46B.toInt(), 0xFFF1F5D6.toInt()),
            GradientDef.even("d_pastel", "Pastell", 0xFF5B4B8A.toInt(), 0xFFE69AB8.toInt(), 0xFFFCE3C4.toInt(), 0xFFFFFFF4.toInt()),
        )
    }
}
