package at.techgaisi.farbwerk.desktop

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.MainActivity
import compat.DesktopPlatform
import compat.KeyMapping
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.system.exitProcess

/**
 * Einstieg der Windows-/Desktop-Version von Farbwerk.
 *
 * Erzeugt die unveränderte [MainActivity] (EditorState, FarbwerkTheme, EditorScreen, CrashReportDialog,
 * Zurück-Behandlung), zeigt deren Compose-Inhalt in einem 1440 × 960-Fenster und leitet Tastatur-
 * ereignisse, die kein Textfeld verbraucht hat, an MainActivity.dispatchKeyEvent (Tastenkürzel, Esc = Zurück).
 * Beim Schließen wird – wie in onPause – gespeichert.
 */
fun main() {
    // Ruhigere Skalierung/Darstellung unter Windows
    System.setProperty("sun.java2d.noddraw", "true")
    System.setProperty("compose.swing.render.on.graphics", System.getProperty("compose.swing.render.on.graphics") ?: "false")

    // Activity auf dem UI-Thread (Swing-EDT) erzeugen – dort laufen auch Compose und alle Handler
    lateinit var activity: MainActivity
    java.awt.EventQueue.invokeAndWait {
        activity = MainActivity()
        DesktopPlatform.activity = activity
        activity.desktopCreate()
        DesktopPlatform.editorState = editorStateOf(activity)
    }

    var closed = false
    fun saveAndExit() {
        if (closed) return
        closed = true
        try { activity.desktopPause() } catch (t: Throwable) { t.printStackTrace() }
        try { activity.desktopStop() } catch (t: Throwable) { t.printStackTrace() }
        exitProcess(0)
    }
    DesktopPlatform.exitHandler = ::saveAndExit

    application(exitProcessOnExit = false) {
        val windowState = rememberWindowState(
            size = DpSize(1440.dp, 960.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )
        val icon = remember { BitmapPainter(useResource("farbwerk_icon.png", ::loadImageBitmap)) }
        Window(
            onCloseRequest = { saveAndExit() },
            title = "Farbwerk",
            icon = icon,
            state = windowState,
            onKeyEvent = { handleKey(activity, it) },
        ) {
            LaunchedEffect(Unit) {
                DesktopPlatform.window = window
                window.minimumSize = java.awt.Dimension(800, 600)
                WindowsTitleBar.applyDark(window)
            }
            DisposableEffect(Unit) {
                // Fokusverlust: gedrückte Tasten loslassen (z. B. Leertaste beim Alt+Tab)
                val l = object : WindowAdapter() {
                    override fun windowDeactivated(e: WindowEvent?) {
                        KeyMapping.releaseAll().forEach { ev -> runCatching { activity.dispatchKeyEvent(ev) } }
                    }
                }
                window.addWindowListener(l)
                onDispose { window.removeWindowListener(l) }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val config = remember(constraints.maxWidth, constraints.maxHeight, density) {
                    DesktopPlatform.updateWindowMetrics(constraints.maxWidth, constraints.maxHeight, density.density, density.fontScale)
                }
                CompositionLocalProvider(
                    LocalContext provides activity,
                    LocalView provides DesktopPlatform.rootView,
                    LocalConfiguration provides config,
                ) {
                    activity.desktopContent.value?.invoke()
                }
            }
        }
    }
}

/** Liest den EditorState der MainActivity (privates Feld „state“) für Desktop-Extras (Mausrad, Pan). */
private fun editorStateOf(activity: MainActivity): EditorState? = try {
    MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }.get(activity) as? EditorState
} catch (t: Throwable) {
    System.err.println("EditorState nicht gefunden: $t"); null
}

private fun handleKey(activity: MainActivity, event: ComposeKeyEvent): Boolean {
    val awt = event.nativeKeyEvent as? java.awt.event.KeyEvent ?: return false
    val ev = KeyMapping.convert(awt) ?: return false
    return try { activity.dispatchKeyEvent(ev) } catch (t: Throwable) {
        System.err.println("Tastenkürzel fehlgeschlagen: $t"); false
    }
}
