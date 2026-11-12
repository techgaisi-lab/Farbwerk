package at.techgaisi.farbwerk.input

import at.techgaisi.farbwerk.prefs.Prefs

/**
 * Funktionen der Gestensteuerung, die über frei belegbare Auslöser gestartet werden (Anleitung §2.4).
 * Reihenfolge = Vorrang bei Konflikten (ein Auslöser gehört genau einer Funktion; die erste gewinnt,
 * alle beteiligten Schalter zeigen ein gelbes Warnsymbol).
 */
enum class GestureFunction(val label: String, val description: String, val allowed: List<GestureTrigger>) {
    EYEDROPPER(
        "Pipette", "Farbe von der Leinwand aufnehmen. Per Modifizierer erscheint die Pipette an der zuletzt bemalten Stelle.",
        listOf(GestureTrigger.TOUCH_HOLD, GestureTrigger.MODIFIER_TAP, GestureTrigger.MODIFIER_TOUCH),
    ),
    QUICKMENU(
        "QuickMenu", "Kreisförmiges Schnellmenü mit sechs frei belegbaren Aktionen.",
        listOf(GestureTrigger.FINGER_TAP, GestureTrigger.TOUCH_HOLD, GestureTrigger.MODIFIER_TAP, GestureTrigger.MODIFIER_TOUCH),
    ),
    SELECT_LAYER(
        "Ebene wählen", "Halten auf der Leinwand zeigt die Ebenen mit Inhalt unter dem Finger zur Auswahl.",
        listOf(GestureTrigger.TOUCH_HOLD, GestureTrigger.MODIFIER_TOUCH),
    ),
    SMUDGE("Wischen", "Striche mit diesem Auslöser wischen immer, egal welches Werkzeug aktiv ist.",
        listOf(GestureTrigger.MODIFIER_TOUCH)),
    ERASE("Radieren", "Striche mit diesem Auslöser radieren immer, egal welches Werkzeug aktiv ist.",
        listOf(GestureTrigger.MODIFIER_TOUCH)),
}

/** Auslöser, die in der Gestensteuerung einer Funktion zugeordnet werden können. */
enum class GestureTrigger(val label: String, val hasDelay: Boolean = false) {
    FINGER_TAP("Tippen (Finger, wenn nur der Stift malt)"),
    TOUCH_HOLD("Berühren & halten", hasDelay = true),
    MODIFIER_TAP("Modifizierer tippen"),
    MODIFIER_TOUCH("Modifizierer halten + Berühren"),
}

/**
 * Lesen/Schreiben der Zuordnung Funktion → Auslöser aus [Prefs.gestureTriggers].
 * Format: "EYEDROPPER:TOUCH_HOLD,MODIFIER_TAP;QUICKMENU:" – fehlende Funktionen bekommen die Standardbelegung.
 */
object GestureMap {

    /** Standardbelegung wie im Vorbild: Pipette per Halten und Modifizierer, alles andere aus. */
    private val DEFAULTS: Map<GestureFunction, Set<GestureTrigger>> = mapOf(
        GestureFunction.EYEDROPPER to setOf(GestureTrigger.TOUCH_HOLD, GestureTrigger.MODIFIER_TAP, GestureTrigger.MODIFIER_TOUCH),
    )

    private var cacheKey: String? = null
    private var cache: Map<GestureFunction, Set<GestureTrigger>> = DEFAULTS

    /** Aktuelle Zuordnung (gepuffert, solange sich der Pref-Text nicht ändert). */
    fun all(prefs: Prefs): Map<GestureFunction, Set<GestureTrigger>> {
        val raw = prefs.gestureTriggers
        if (raw == cacheKey) return cache
        val map = HashMap<GestureFunction, Set<GestureTrigger>>()
        for (f in GestureFunction.entries) map[f] = DEFAULTS[f] ?: emptySet()
        for (part in raw.split(';')) {
            val i = part.indexOf(':')
            if (i <= 0) continue
            val f = GestureFunction.entries.firstOrNull { it.name == part.substring(0, i) } ?: continue
            map[f] = part.substring(i + 1).split(',')
                .mapNotNull { n -> GestureTrigger.entries.firstOrNull { it.name == n } }
                .filter { it in f.allowed }
                .toSet()
        }
        cacheKey = raw
        cache = map
        return map
    }

    fun triggers(prefs: Prefs, f: GestureFunction): Set<GestureTrigger> = all(prefs)[f] ?: emptySet()

    fun isOn(prefs: Prefs, f: GestureFunction, t: GestureTrigger): Boolean = t in triggers(prefs, f)

    /** Schaltet einen Auslöser für eine Funktion und speichert. */
    fun set(prefs: Prefs, f: GestureFunction, t: GestureTrigger, on: Boolean) {
        val map = all(prefs).toMutableMap()
        val cur = (map[f] ?: emptySet()).toMutableSet()
        if (on) cur.add(t) else cur.remove(t)
        map[f] = cur
        prefs.gestureTriggers = GestureFunction.entries.joinToString(";") { fn ->
            fn.name + ":" + (map[fn] ?: emptySet()).joinToString(",") { it.name }
        }
        // Altes Einzel-Flag für „Berühren & halten = Pipette“ synchron halten (wird von der Leinwand gelesen)
        prefs.gestureHoldEyedropper = owner(prefs, GestureTrigger.TOUCH_HOLD) == GestureFunction.EYEDROPPER
        prefs.save()
    }

    /** Funktion, der ein Auslöser gehört (bei Konflikten die mit dem höchsten Vorrang), oder null. */
    fun owner(prefs: Prefs, t: GestureTrigger): GestureFunction? {
        val map = all(prefs)
        // Altes Flag respektieren: Halten = Pipette lässt sich dort weiterhin abschalten
        return GestureFunction.entries.firstOrNull { f ->
            t in (map[f] ?: emptySet()) &&
                !(f == GestureFunction.EYEDROPPER && t == GestureTrigger.TOUCH_HOLD && !prefs.gestureHoldEyedropper)
        }
    }

    /** Gehört [t] außer [f] noch einer weiteren Funktion? (gelbes Warnsymbol) */
    fun conflicts(prefs: Prefs, f: GestureFunction, t: GestureTrigger): Boolean {
        if (!isOn(prefs, f, t)) return false
        return GestureFunction.entries.any { it != f && t in triggers(prefs, it) }
    }

    /** Alle Standardwerte wiederherstellen. */
    fun reset(prefs: Prefs) {
        prefs.gestureTriggers = ""
        prefs.gestureHoldEyedropper = true
        prefs.save()
    }
}
