@file:Suppress("unused", "UNUSED_PARAMETER")

package android.os

import java.awt.EventQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Desktop-Nachbildung von android.os.Looper. Es gibt genau einen „Main“-Looper: den Swing-Event-Dispatch-Thread,
 * auf dem auch Compose Desktop läuft. Hintergrund-Threads haben keinen Looper.
 */
class Looper private constructor(internal val isMain: Boolean) {
    val thread: Thread? get() = if (isMain) mainThread else null

    fun isCurrentThread(): Boolean = isMain && EventQueue.isDispatchThread()

    companion object {
        private val main = Looper(true)
        @Volatile internal var mainThread: Thread? = null

        @JvmStatic fun getMainLooper(): Looper = main

        /** Main-Looper auf dem EDT, sonst null (wie ein Android-Hintergrund-Thread ohne prepare()). */
        @JvmStatic fun myLooper(): Looper? = if (EventQueue.isDispatchThread()) main else null

        @JvmStatic fun prepare() {}
        @JvmStatic fun loop() {}
    }
}

/**
 * Handler: führt Runnables auf dem Swing-EDT aus (verzögert über einen Scheduler-Thread).
 * [removeCallbacks] entfernt auch bereits geplante, noch nicht ausgeführte Aufträge.
 */
open class Handler @JvmOverloads constructor(private val looper: Looper = Looper.getMainLooper()) {
    constructor(looper: Looper, callback: Any?) : this(looper)

    private class Job(val r: Runnable, val token: Any?) {
        @Volatile var cancelled = false
        var future: ScheduledFuture<*>? = null
    }

    private val jobs = ConcurrentHashMap.newKeySet<Job>()

    fun getLooper(): Looper = looper

    fun post(r: Runnable): Boolean = postDelayed(r, 0L)

    fun postDelayed(r: Runnable, delayMillis: Long): Boolean = schedule(r, null, delayMillis)

    fun postDelayed(r: Runnable, token: Any?, delayMillis: Long): Boolean = schedule(r, token, delayMillis)

    fun postAtTime(r: Runnable, uptimeMillis: Long): Boolean =
        schedule(r, null, uptimeMillis - SystemClock.uptimeMillis())

    fun postAtFrontOfQueue(r: Runnable): Boolean = post(r)

    private fun schedule(r: Runnable, token: Any?, delayMillis: Long): Boolean {
        val job = Job(r, token)
        jobs.add(job)
        val fire = Runnable {
            EventQueue.invokeLater {
                if (jobs.remove(job) && !job.cancelled) r.run()
            }
        }
        if (delayMillis <= 0L) fire.run()
        else job.future = scheduler.schedule(fire, delayMillis, TimeUnit.MILLISECONDS)
        return true
    }

    fun removeCallbacks(r: Runnable) {
        jobs.removeIf { j -> if (j.r === r) { j.cancelled = true; j.future?.cancel(false); true } else false }
    }

    fun removeCallbacks(r: Runnable, token: Any?) {
        jobs.removeIf { j -> if (j.r === r && (token == null || j.token === token)) { j.cancelled = true; j.future?.cancel(false); true } else false }
    }

    fun removeCallbacksAndMessages(token: Any?) {
        jobs.removeIf { j -> if (token == null || j.token === token) { j.cancelled = true; j.future?.cancel(false); true } else false }
    }

    fun hasCallbacks(r: Runnable): Boolean = jobs.any { it.r === r && !it.cancelled }

    companion object {
        internal val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Farbwerk-Handler").apply { isDaemon = true }
        }
        @JvmStatic fun createAsync(looper: Looper): Handler = Handler(looper)
    }
}

/** Monotone Uhr wie auf Android (Millisekunden seit Programmstart). */
object SystemClock {
    private val start = System.nanoTime()
    @JvmStatic fun uptimeMillis(): Long = (System.nanoTime() - start) / 1_000_000L
    @JvmStatic fun elapsedRealtime(): Long = uptimeMillis()
    @JvmStatic fun elapsedRealtimeNanos(): Long = System.nanoTime() - start
    @JvmStatic fun currentThreadTimeMillis(): Long = uptimeMillis()
    @JvmStatic fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
}

/** Gerätedaten – auf dem Desktop mit Werten des Betriebssystems belegt. SDK_INT entspricht Android 15. */
object Build {
    @JvmField val MANUFACTURER: String = System.getProperty("os.name") ?: "Desktop"
    @JvmField val MODEL: String = (System.getProperty("os.arch") ?: "") + " / Java " + (System.getProperty("java.version") ?: "")
    @JvmField val BRAND: String = "Desktop"
    @JvmField val DEVICE: String = "desktop"
    @JvmField val PRODUCT: String = "Farbwerk Desktop"

    object VERSION {
        @JvmField val SDK_INT: Int = 35
        @JvmField val RELEASE: String = (System.getProperty("os.name") ?: "") + " " + (System.getProperty("os.version") ?: "")
    }

    object VERSION_CODES {
        const val O = 26
        const val O_MR1 = 27
        const val P = 28
        const val Q = 29
        const val R = 30
        const val S = 31
        const val S_V2 = 32
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
        const val VANILLA_ICE_CREAM = 35
    }
}

/** Einfache Schlüssel/Wert-Sammlung (Activity-Zustand). */
open class BaseBundle {
    internal val map = LinkedHashMap<String, Any?>()
    fun containsKey(key: String) = map.containsKey(key)
    fun keySet(): Set<String> = map.keys
    fun isEmpty() = map.isEmpty()
    fun size() = map.size
    fun remove(key: String) { map.remove(key) }
    fun get(key: String): Any? = map[key]
    fun putString(key: String, value: String?) { map[key] = value }
    fun getString(key: String): String? = map[key] as? String
    fun getString(key: String, def: String): String = map[key] as? String ?: def
    fun putInt(key: String, value: Int) { map[key] = value }
    fun getInt(key: String, def: Int = 0): Int = map[key] as? Int ?: def
    fun putLong(key: String, value: Long) { map[key] = value }
    fun getLong(key: String, def: Long = 0L): Long = map[key] as? Long ?: def
    fun putBoolean(key: String, value: Boolean) { map[key] = value }
    fun getBoolean(key: String, def: Boolean = false): Boolean = map[key] as? Boolean ?: def
    fun putFloat(key: String, value: Float) { map[key] = value }
    fun getFloat(key: String, def: Float = 0f): Float = map[key] as? Float ?: def
}

class Bundle : BaseBundle() {
    fun putBundle(key: String, value: Bundle?) { map[key] = value }
    fun getBundle(key: String): Bundle? = map[key] as? Bundle
}

/** Marker wie auf Android (Uri, Intents …). */
interface Parcelable
