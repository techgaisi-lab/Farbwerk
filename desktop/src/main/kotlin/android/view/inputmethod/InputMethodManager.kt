@file:Suppress("unused", "UNUSED_PARAMETER")

package android.view.inputmethod

import android.view.View

/**
 * Bildschirmtastatur gibt es auf dem Desktop nicht. [isAcceptingText] ist false: Der Desktop-Host leitet
 * Tasten nur dann an die Kürzel weiter, wenn kein Textfeld sie verbraucht hat (Compose onKeyEvent).
 */
object InputMethodManager {
    val isAcceptingText: Boolean get() = false
    val isActive: Boolean get() = false
    fun isActive(view: View): Boolean = false
    fun showSoftInput(view: View?, flags: Int): Boolean = false
    fun hideSoftInputFromWindow(token: Any?, flags: Int): Boolean = false
    const val SHOW_IMPLICIT = 1
    const val HIDE_NOT_ALWAYS = 2
}
