@file:Suppress("unused", "UNUSED_PARAMETER")

package android.app

import android.content.ContextWrapper
import android.content.DesktopContext
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.Window
import java.lang.management.ManagementFactory

/**
 * Activity-Nachbildung: Lebenszyklus wird vom Desktop-Einstieg (Main.kt) getrieben
 * (onCreate beim Start, onPause/onStop beim Schließen des Fensters).
 */
open class Activity : ContextWrapper(DesktopContext) {
    val window: Window = Window()
    var isFinishing: Boolean = false
        private set
    var title: CharSequence? = "Farbwerk"
    open val intent: android.content.Intent get() = android.content.Intent()

    protected open fun onCreate(savedInstanceState: Bundle?) {}
    protected open fun onStart() {}
    protected open fun onResume() {}
    protected open fun onPause() {}
    protected open fun onStop() {}
    protected open fun onDestroy() {}

    open fun dispatchKeyEvent(event: KeyEvent): Boolean = false

    fun <T : View> findViewById(id: Int): T? = null
    fun runOnUiThread(r: Runnable) {
        if (java.awt.EventQueue.isDispatchThread()) r.run() else java.awt.EventQueue.invokeLater(r)
    }
    open fun finish() { isFinishing = true; compat.DesktopPlatform.requestExit() }
    fun recreate() {}

    // -------- vom Desktop-Einstieg aufgerufen --------
    fun desktopCreate() { onCreate(null); onStart(); onResume() }
    fun desktopPause() { onPause() }
    fun desktopStop() { onStop(); onDestroy() }
}

/** Speicherinformationen aus dem Betriebssystem bzw. der Java-Laufzeit. */
object ActivityManager {
    class MemoryInfo {
        @JvmField var totalMem: Long = 0L
        @JvmField var availMem: Long = 0L
        @JvmField var threshold: Long = 0L
        @JvmField var lowMemory: Boolean = false
    }

    fun getMemoryInfo(out: MemoryInfo) {
        val rt = Runtime.getRuntime()
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        // Ebenenpixel liegen im nativen Skia-Speicher – maßgeblich ist der physische Arbeitsspeicher;
        // ohne OS-Werte wird auf das Java-Heap-Limit zurückgefallen.
        out.totalMem = os?.totalMemorySize?.takeIf { it > 0 } ?: rt.maxMemory()
        out.availMem = os?.freeMemorySize?.takeIf { it > 0 } ?: (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory()))
        out.threshold = out.totalMem / 10
        out.lowMemory = out.availMem < out.threshold
    }

    val memoryClass: Int get() = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
    val largeMemoryClass: Int get() = memoryClass
    val isLowRamDevice: Boolean get() = false
}
