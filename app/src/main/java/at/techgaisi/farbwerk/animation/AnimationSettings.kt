package at.techgaisi.farbwerk.animation

import org.json.JSONObject

/** Wiedergabeart des Animationsassistenten. */
enum class PlaybackMode(val label: String) { LOOP("Schleife"), PING_PONG("Ping-Pong"), ONCE("Einmal") }

/** Welcher Assistent aktiv ist: Animation (Bilder) oder Seiten (z. B. Comic/Buch). */
enum class AssistMode { ANIMATION, PAGES }

/**
 * Einstellungen des Animations-/Seitenassistenten. Liegt in [at.techgaisi.farbwerk.model.ArtDocument.animation]
 * (null = Assistent aus) und wird mit dem Projekt gespeichert.
 */
class AnimationSettings(var mode: AssistMode = AssistMode.ANIMATION) {
    var fps: Int = 15
    var playback: PlaybackMode = PlaybackMode.LOOP
    /** Anzahl Zwiebelschicht-Bilder je Richtung (0–12). */
    var onionFrames: Int = 2
    /** Deckkraft der nächstgelegenen Zwiebelschicht (0..1). */
    var onionOpacity: Float = 0.45f
    /** Aktuelles Bild halbtransparent anzeigen (nur Anzeige, Export bleibt deckend). */
    var blendPrimary: Boolean = false
    var onionPrevColor: Int = 0xFFFF3B30.toInt()
    var onionNextColor: Int = 0xFF34C759.toInt()
    /** Erstes (linkes) Bild liegt als Hintergrund unter allen Bildern. */
    var firstIsBackground: Boolean = false
    /** Letztes (rechtes) Bild liegt als Vordergrund über allen Bildern. */
    var lastIsForeground: Boolean = false
    /** Zusätzliche Haltedauer je Bild (Schlüssel = Bildschlüssel, Wert = weitere Bilder). */
    val holds: MutableMap<String, Int> = mutableMapOf()

    fun copy(): AnimationSettings = AnimationSettings(mode).also { c ->
        c.fps = fps; c.playback = playback; c.onionFrames = onionFrames; c.onionOpacity = onionOpacity
        c.blendPrimary = blendPrimary; c.onionPrevColor = onionPrevColor; c.onionNextColor = onionNextColor
        c.firstIsBackground = firstIsBackground; c.lastIsForeground = lastIsForeground
        c.holds.putAll(holds)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.name)
        put("fps", fps)
        put("playback", playback.name)
        put("onionFrames", onionFrames)
        put("onionOpacity", onionOpacity.toDouble())
        put("blendPrimary", blendPrimary)
        put("onionPrevColor", onionPrevColor)
        put("onionNextColor", onionNextColor)
        put("firstIsBackground", firstIsBackground)
        put("lastIsForeground", lastIsForeground)
        put("holds", JSONObject().apply { holds.forEach { (k, v) -> if (v > 0) put(k, v) } })
    }

    companion object {
        fun fromJson(o: JSONObject?): AnimationSettings? {
            if (o == null) return null
            val s = AnimationSettings(AssistMode.entries.firstOrNull { it.name == o.optString("mode") } ?: AssistMode.ANIMATION)
            s.fps = o.optInt("fps", 15).coerceIn(1, 60)
            s.playback = PlaybackMode.entries.firstOrNull { it.name == o.optString("playback") } ?: PlaybackMode.LOOP
            s.onionFrames = o.optInt("onionFrames", 2).coerceIn(0, 12)
            s.onionOpacity = o.optDouble("onionOpacity", 0.45).toFloat().coerceIn(0f, 1f)
            s.blendPrimary = o.optBoolean("blendPrimary", false)
            s.onionPrevColor = o.optInt("onionPrevColor", s.onionPrevColor)
            s.onionNextColor = o.optInt("onionNextColor", s.onionNextColor)
            s.firstIsBackground = o.optBoolean("firstIsBackground", false)
            s.lastIsForeground = o.optBoolean("lastIsForeground", false)
            o.optJSONObject("holds")?.let { h -> for (k in h.keys()) s.holds[k] = h.optInt(k).coerceIn(0, 120) }
            return s
        }
    }
}
