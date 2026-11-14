package at.techgaisi.farbwerk.brush

import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Kleiner gemeinsamer Thread-Pool für rechenintensive Pinselarbeit, die sich in unabhängige Streifen teilen lässt
 * (große Tupfer in [DabPainter], Kanteneffekte in [StrokeEffects]). Ein Teil läuft immer im aufrufenden Thread,
 * die übrigen parallel; [run] kehrt erst zurück, wenn alle fertig sind.
 *
 * Die Aufgaben im Pool rufen [run] nie selbst auf (keine Verschachtelung → keine Verklemmung).
 */
internal object BrushParallel {
    /** Anzahl gleichzeitig arbeitender Threads (inkl. Aufrufer). */
    val THREADS: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private val pool by lazy {
        Executors.newFixedThreadPool(maxOf(1, THREADS - 1)) { r ->
            Thread(r, "Farbwerk-Pinsel").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
        }
    }

    /** Führt [fn] für die Teile 0 until [parts] aus (Teil 0 im aufrufenden Thread). */
    inline fun run(parts: Int, crossinline fn: (Int) -> Unit) {
        if (parts <= 1) { fn(0); return }
        val futures = arrayOfNulls<Future<*>>(parts - 1)
        for (p in 1 until parts) futures[p - 1] = submit { fn(p) }
        var error: Throwable? = null
        try { fn(0) } catch (t: Throwable) { error = t }
        // immer auf alle warten – die Teile arbeiten auf gemeinsamen Puffern
        for (f in futures) {
            try { f!!.get() } catch (t: Throwable) { if (error == null) error = t.cause ?: t }
        }
        if (error != null) throw error
    }

    /** Teilt 0 until [n] in [parts] zusammenhängende Stücke und ruft [fn] (von, bis) für jedes auf. */
    inline fun ranges(parts: Int, n: Int, crossinline fn: (Int, Int) -> Unit) {
        val k = if (n < parts * 8) 1 else parts
        run(k) { p -> fn(n * p / k, n * (p + 1) / k) }
    }

    @PublishedApi internal fun submit(r: Runnable): Future<*> = pool.submit(r)
}
