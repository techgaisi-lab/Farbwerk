@file:Suppress("UNUSED_PARAMETER")

package androidx.input.motionprediction

import android.view.MotionEvent
import android.view.View

/** Bewegungsvorhersage: auf dem Desktop ohne Vorhersage (Maus/Stift liefern ohnehin geringe Latenz). */
interface MotionEventPredictor {
    fun record(event: MotionEvent)
    fun predict(): MotionEvent?

    companion object {
        @JvmStatic fun newInstance(view: View): MotionEventPredictor = object : MotionEventPredictor {
            override fun record(event: MotionEvent) {}
            override fun predict(): MotionEvent? = null
        }
    }
}
