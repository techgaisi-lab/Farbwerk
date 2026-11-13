package at.techgaisi.farbwerk

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import at.techgaisi.farbwerk.input.KeyboardShortcuts
import at.techgaisi.farbwerk.model.Panel
import at.techgaisi.farbwerk.ui.EditorScreen
import at.techgaisi.farbwerk.ui.theme.FarbwerkTheme

class MainActivity : ComponentActivity() {
    private lateinit var state: EditorState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(this)
        // Systemleisten komplett durchsichtig und dunkel – kein heller Streifen unten (Gesten-Leiste),
        // auch wenn das Gerät im hellen Systemmodus läuft.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        state = (lastCustomNonConfigurationInstanceCompat() as? EditorState) ?: EditorState(applicationContext)
        setContent {
            FarbwerkTheme(light = state.prefs.lightInterface) {
                BackHandler(enabled = !state.showGallery) {
                    when {
                        state.copyPasteMenuVisible -> state.copyPasteMenuVisible = false
                        state.openPanel != Panel.NONE -> state.openPanel = Panel.NONE
                        state.fullscreen -> state.fullscreen = false
                        else -> state.closeToGallery()
                    }
                }
                EditorScreen(state)
                CrashReportDialog()
            }
        }
    }

    private fun lastCustomNonConfigurationInstanceCompat(): Any? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!state.showGallery && state.document != null && !isTextInputActive()) {
            val handled = when (event.action) {
                KeyEvent.ACTION_DOWN -> KeyboardShortcuts.onKeyDown(state, event)
                KeyEvent.ACTION_UP -> KeyboardShortcuts.onKeyUp(state, event)
                else -> false
            }
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * true, solange ein Texteingabefeld (z. B. Hex-Feld im Farben-Panel) den Fokus hat. Dann gehen
     * Tasten an das Feld statt an die Kürzel – sonst würde z. B. Rücktaste die Ebene leeren.
     */
    private fun isTextInputActive(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return try { imm.isAcceptingText } catch (_: Throwable) { false }
    }

    override fun onPause() {
        super.onPause()
        if (::state.isInitialized) state.saveNow()
    }
}
