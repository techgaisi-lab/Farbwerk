package at.techgaisi.farbwerk.render

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import at.techgaisi.farbwerk.EditorState

/** Debug-build measurements from Android's frame pipeline, not estimated from pointer events. */
object EngineDiagnostics {
    var text by mutableStateOf("Engine-Test: beim Zeichnen werden Framezeiten und Speicher angezeigt")
        private set
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var commitMs = 0.0
    fun recordCommit(nanos: Long) {
        if (listener == null) return
        commitMs = nanos / 1_000_000.0
        text = text.substringBefore("\nCommit:") + String.format(java.util.Locale.ROOT, "\nCommit: %.1f ms", commitMs)
        android.util.Log.i("Farbwerk-Engine", "commitMs=$commitMs")
    }
    fun attach(window: Window, state: EditorState) {
        detach(window)
        val durations = LongArray(120)
        var count = 0; var started = SystemClock.uptimeMillis(); var gpu = 0L
        listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val now = SystemClock.uptimeMillis()
            if (!state.strokeActive) { count = 0; started = now; return@OnFrameMetricsAvailableListener }
            durations[count % durations.size] = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            count++
            if (Build.VERSION.SDK_INT >= 31) gpu = maxOf(gpu, metrics.getMetric(FrameMetrics.GPU_DURATION))
            if (now - started >= 1000) {
                val sorted = durations.copyOf(minOf(count, durations.size)).sorted()
                val p95 = sorted[((sorted.size - 1) * 0.95).toInt()] / 1_000_000.0
                val fps = count * 1000.0 / (now - started)
                val doc = state.document
                text = "${doc?.width}×${doc?.height} · ${doc?.layers?.size} Ebenen · ${state.brushEngine.liveRenderer}\n" +
                    String.format(java.util.Locale.ROOT, "%.1f Frames/s · p95 %.1f ms · GPU max %.1f ms\n", fps, p95, gpu / 1_000_000.0) +
                    EngineMemory.statistics(state.document, state.brushEngine.workingBytes, state.undo.memoryBytes) +
                    String.format(java.util.Locale.ROOT, "\nCommit: %.1f ms", commitMs)
                android.util.Log.i("Farbwerk-Engine", text)
                count = 0; gpu = 0; started = now
            }
        }
        window.addOnFrameMetricsAvailableListener(listener!!, Handler(Looper.getMainLooper()))
    }
    fun detach(window: Window) { listener?.let { window.removeOnFrameMetricsAvailableListener(it) }; listener = null }
}
