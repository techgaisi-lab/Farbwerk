@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Merkt den Inhalt; der Desktop-Einstieg zeigt ihn im Fenster an. */
fun ComponentActivity.setContent(parent: CompositionContext? = null, content: @Composable () -> Unit) {
    OnBackPressedDispatcher.current = onBackPressedDispatcher
    desktopContent.value = content
}

/** Zurück-Behandlung; auf dem Desktop durch die Esc-Taste ausgelöst. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val currentOnBack = rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() { currentOnBack.value() }
        }
    }
    SideEffect { callback.isEnabled = enabled }
    DisposableEffect(Unit) {
        val d = OnBackPressedDispatcher.current
        d.addCallback(callback)
        onDispose { d.removeCallback(callback) }
    }
}

class ManagedActivityResultLauncher<I, O> internal constructor(
    override val contract: ActivityResultContract<I, O>,
    private val onResult: () -> ((O) -> Unit),
) : ActivityResultLauncher<I>() {
    override fun launch(input: I) {
        // erst nach dem aktuellen Ereignis öffnen, damit der modale Dialog keine Zeigerverarbeitung unterbricht
        java.awt.EventQueue.invokeLater {
            contract.launchOnDesktop(input) { result -> onResult()(result) }
        }
    }
    override fun unregister() {}
}

/** Öffnet die passenden Desktop-Dialoge (Datei öffnen/speichern) für den jeweiligen Vertrag. */
@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> {
    val current = rememberUpdatedState(onResult)
    return remember { ManagedActivityResultLauncher(contract) { current.value } }
}
