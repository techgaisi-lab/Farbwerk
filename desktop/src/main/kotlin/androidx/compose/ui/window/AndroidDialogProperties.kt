@file:Suppress("UNUSED_PARAMETER")

package androidx.compose.ui.window

import androidx.compose.ui.graphics.Color

/**
 * Android-Variante von DialogProperties mit `decorFitsSystemWindows` (auf dem Desktop bedeutungslos).
 * Wird nur gewählt, wenn der Parameter angegeben ist; sonst gilt der Desktop-Konstruktor.
 */
fun DialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    usePlatformDefaultWidth: Boolean = true,
    decorFitsSystemWindows: Boolean,
    securePolicy: Any? = null,
): DialogProperties = DialogProperties(
    dismissOnBackPress = dismissOnBackPress,
    dismissOnClickOutside = dismissOnClickOutside,
    usePlatformDefaultWidth = usePlatformDefaultWidth,
    usePlatformInsets = decorFitsSystemWindows,
    scrimColor = Color.Black.copy(alpha = 0.6f),
)
