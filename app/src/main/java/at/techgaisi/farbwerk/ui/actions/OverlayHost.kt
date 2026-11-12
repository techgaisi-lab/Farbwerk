package at.techgaisi.farbwerk.ui.actions

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.animation.AnimationController
import at.techgaisi.farbwerk.ui.animation.AnimationTimeline
import at.techgaisi.farbwerk.ui.reference.ReferenceWindow
import at.techgaisi.farbwerk.ui.reference.ReferenceWindowState
import at.techgaisi.farbwerk.ui.text.TextEditorOverlay
import at.techgaisi.farbwerk.ui.theme.FarbwerkTheme

/**
 * Zustand der Vollbild-/Schwebe-Modi aus dem Aktionen-Paket (Zeichenhilfe-Editor, Zuschneiden, Text,
 * Referenzfenster). Es gibt nur eine Editor-Instanz pro Prozess, daher genügt ein Objekt.
 */
object ActionModes {
    /** Zeichenhilfe-Editor (Vollbild) aktiv. */
    var guideEditor by mutableStateOf<GuideEditorSession?>(null)
    /** Interaktives Zuschneiden aktiv. */
    var crop by mutableStateOf<CropSession?>(null)
    /** Referenzfenster. */
    val reference = ReferenceWindowState()

    /** Anzahl eingebetteter [ActionOverlays] (im EditorScreen). > 0 → der Fallback-Host bleibt leer. */
    internal var embedded by mutableIntStateOf(0)
}

/**
 * Alle Overlays dieses Pakets über der Leinwand: Zeitleiste (Animation/Seiten), Referenzfenster,
 * Text-Bearbeitung, Zeichenhilfe-Editor und Zuschneiden.
 *
 * **Einhängen:** im EditorScreen als letztes Kind der Haupt-Box (über Leinwand, Leisten und Popovers),
 * `ActionOverlays(state)`. Solange das nicht geschehen ist, legt [OverlayHost] eine eigene ComposeView über
 * den Inhalt der Activity (Fallback, funktional gleichwertig).
 */
@Composable
fun ActionOverlays(state: EditorState) {
    DisposableEffect(Unit) {
        ActionModes.embedded++
        onDispose { ActionModes.embedded-- }
    }
    OverlayContent(state)
}

@Composable
private fun OverlayContent(state: EditorState) {
    if (state.showGallery || state.document == null) return
    @Suppress("UNUSED_VARIABLE") val v = AnimationController.version
    Box(Modifier.fillMaxSize()) {
        AnimationTimeline(state)
        ReferenceWindow(state, ActionModes.reference)
        TextEditorOverlay(state)
        ActionModes.guideEditor?.let { GuideEditorScreen(state, it) }
        ActionModes.crop?.let { CropScreen(state, it) }
    }
}

/**
 * Fallback-Host: hängt eine transparente ComposeView über den Activity-Inhalt. Berührungen außerhalb der
 * Overlay-Bedienelemente gehen unverändert an die darunterliegende Leinwand (Compose meldet sie als
 * nicht verarbeitet). Wird aus der Galerie und dem Aktionen-Panel aufgerufen; mehrfacher Aufruf ist harmlos.
 */
object OverlayHost {
    private const val TAG_KEY = 0x46_57_36_00 // "FW6"

    fun install(activity: Activity?, state: EditorState) {
        val act = activity ?: return
        val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
        for (i in 0 until content.childCount) {
            if (content.getChildAt(i).getTag(TAG_KEY) == true) return
        }
        val view = ComposeView(act).apply {
            setTag(TAG_KEY, true)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FarbwerkTheme(light = state.prefs.lightInterface) {
                    if (ActionModes.embedded == 0) OverlayContent(state)
                }
            }
        }
        content.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

/** Sucht die Activity hinter einem Context (ContextWrapper-Kette). */
internal fun android.content.Context.findActivity(): Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
