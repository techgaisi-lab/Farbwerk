@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.platform

import android.content.Context
import android.content.DesktopContext
import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import compat.DesktopPlatform

/** Kontext der Desktop-Activity (vom Einstieg gesetzt, sonst der Anwendungskontext). */
val LocalContext = staticCompositionLocalOf<Context> { DesktopPlatform.activity ?: DesktopContext }

/** Wurzel-„View“ des Fensters (nur für performHapticFeedback & Co.). */
val LocalView = staticCompositionLocalOf<View> { DesktopPlatform.rootView }

/** Fenstermaße in dp (ändert sich mit der Fenstergröße). */
val LocalConfiguration = compositionLocalOf { DesktopPlatform.configuration }

/** Nur für den Android-Fallback-Overlay-Host vorhanden; auf dem Desktop nie in eine Ansicht eingehängt. */
class ComposeView(context: Context) : android.view.ViewGroup(context) {
    internal val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    fun setContent(content: @Composable () -> Unit) { this.content.value = content }
    fun setViewCompositionStrategy(strategy: ViewCompositionStrategy) {}
    fun disposeComposition() { content.value = null }
}

interface ViewCompositionStrategy {
    object DisposeOnViewTreeLifecycleDestroyed : ViewCompositionStrategy
    object DisposeOnDetachedFromWindow : ViewCompositionStrategy
    object DisposeOnDetachedFromWindowOrReleasedFromPool : ViewCompositionStrategy
    companion object {
        val Default: ViewCompositionStrategy get() = DisposeOnDetachedFromWindowOrReleasedFromPool
    }
}
