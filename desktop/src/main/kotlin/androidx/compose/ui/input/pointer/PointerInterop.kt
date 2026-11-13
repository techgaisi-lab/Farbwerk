@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.input.pointer

import android.view.MotionEvent
import androidx.compose.ui.Modifier
import compat.PointerBridge
import compat.pointerBridge

/** Wie auf Android: übergibt die Zeigerereignisse dieses Elements als MotionEvents. */
class RequestDisallowInterceptTouchEvent : (Boolean) -> Unit {
    override fun invoke(disallowIntercept: Boolean) {}
}

fun Modifier.pointerInteropFilter(
    requestDisallowInterceptTouchEvent: RequestDisallowInterceptTouchEvent? = null,
    onTouchEvent: (MotionEvent) -> Boolean,
): Modifier = pointerBridge(PointerBridge(null, onTouchEvent), null)
