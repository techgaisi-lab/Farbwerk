package at.techgaisi.farbwerk

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
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
            }
        }
    }

    private fun lastCustomNonConfigurationInstanceCompat(): Any? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!state.showGallery && state.document != null) {
            val handled = when (event.action) {
                KeyEvent.ACTION_DOWN -> KeyboardShortcuts.onKeyDown(state, event)
                KeyEvent.ACTION_UP -> KeyboardShortcuts.onKeyUp(state, event)
                else -> false
            }
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        if (::state.isInitialized) state.saveNow()
    }
}
