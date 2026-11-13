@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity

import android.app.Activity
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf

/**
 * ComponentActivity für den Desktop: hält den Compose-Inhalt (aus setContent), den der Desktop-Einstieg
 * im Fenster anzeigt, und den Zurück-Dispatcher (Esc ≙ Zurück-Taste).
 */
open class ComponentActivity : Activity() {
    /** Mit setContent gesetzter Inhalt. */
    val desktopContent = mutableStateOf<(@Composable () -> Unit)?>(null)

    val onBackPressedDispatcher = OnBackPressedDispatcher()

    /** Esc (bzw. Zurück) ohne andere Verwendung → wie die Android-Zurück-Taste. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if ((event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK) && !event.isCtrlPressed) {
            if (event.action == KeyEvent.ACTION_UP) return onBackPressedDispatcher.onBackPressed()
            return onBackPressedDispatcher.hasEnabledCallbacks()
        }
        return false
    }

    @Deprecated("onBackPressedDispatcher verwenden")
    open fun onBackPressed() { onBackPressedDispatcher.onBackPressed() }
}

/** Zurück-Rückrufe; der zuletzt hinzugefügte aktive gewinnt (wie auf Android). */
class OnBackPressedDispatcher {
    private val callbacks = ArrayList<OnBackPressedCallback>()

    fun addCallback(cb: OnBackPressedCallback) { callbacks.add(cb) }
    fun removeCallback(cb: OnBackPressedCallback) { callbacks.remove(cb) }
    fun hasEnabledCallbacks(): Boolean = callbacks.any { it.isEnabled }

    fun onBackPressed(): Boolean {
        val cb = callbacks.lastOrNull { it.isEnabled } ?: return false
        cb.handleOnBackPressed()
        return true
    }

    companion object {
        /** Dispatcher der (einzigen) Desktop-Activity. */
        @JvmStatic var current: OnBackPressedDispatcher = OnBackPressedDispatcher()
    }
}

abstract class OnBackPressedCallback(enabled: Boolean) {
    var isEnabled: Boolean = enabled
    abstract fun handleOnBackPressed()
}

/** Systemleisten gibt es auf dem Desktop nicht. */
class SystemBarStyle private constructor(val lightScrim: Int, val darkScrim: Int) {
    companion object {
        @JvmStatic fun dark(scrim: Int): SystemBarStyle = SystemBarStyle(scrim, scrim)
        @JvmStatic fun light(scrim: Int, darkScrim: Int): SystemBarStyle = SystemBarStyle(scrim, darkScrim)
        @JvmStatic fun auto(lightScrim: Int, darkScrim: Int): SystemBarStyle = SystemBarStyle(lightScrim, darkScrim)
        @JvmStatic fun auto(lightScrim: Int, darkScrim: Int, detectDarkMode: (Any?) -> Boolean): SystemBarStyle =
            SystemBarStyle(lightScrim, darkScrim)
    }
}

fun ComponentActivity.enableEdgeToEdge(
    statusBarStyle: SystemBarStyle = SystemBarStyle.dark(0),
    navigationBarStyle: SystemBarStyle = SystemBarStyle.dark(0),
) {}
