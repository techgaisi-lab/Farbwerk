@file:Suppress("unused")

package android.animation

import java.awt.EventQueue
import javax.swing.Timer

/**
 * Zeitgesteuerter ValueAnimator (Swing-Timer, ~60 Hz, auf dem EDT) mit Android-Standardinterpolation
 * (AccelerateDecelerate).
 */
class ValueAnimator private constructor(private val values: FloatArray) {
    var duration: Long = 300L
    var startDelay: Long = 0L
    var repeatCount: Int = 0
    var interpolator: TimeInterpolator? = null
    var animatedValue: Any = values.first()
        private set
    var animatedFraction: Float = 0f
        private set
    val isRunning: Boolean get() = timer?.isRunning == true
    val isStarted: Boolean get() = timer != null

    private val updateListeners = ArrayList<AnimatorUpdateListener>()
    private val listeners = ArrayList<Animator.AnimatorListener>()
    private var timer: Timer? = null
    private var startNanos = 0L

    fun interface AnimatorUpdateListener { fun onAnimationUpdate(animation: ValueAnimator) }

    fun addUpdateListener(l: AnimatorUpdateListener) { updateListeners.add(l) }
    fun removeAllUpdateListeners() { updateListeners.clear() }
    fun addListener(l: Animator.AnimatorListener) { listeners.add(l) }
    fun setDuration(ms: Long): ValueAnimator = apply { duration = ms }

    fun start() {
        if (!EventQueue.isDispatchThread()) { EventQueue.invokeLater { start() }; return }
        cancelTimer()
        startNanos = System.nanoTime() + startDelay * 1_000_000L
        listeners.forEach { it.onAnimationStart(Animator.Dummy) }
        update(0f)
        timer = Timer(16) { tick() }.apply { isRepeats = true; start() }
    }

    private fun tick() {
        val elapsed = (System.nanoTime() - startNanos) / 1_000_000f
        if (elapsed < 0f) return
        val f = if (duration <= 0L) 1f else (elapsed / duration).coerceIn(0f, 1f)
        update(f)
        if (f >= 1f) {
            cancelTimer()
            listeners.forEach { it.onAnimationEnd(Animator.Dummy) }
        }
    }

    private fun update(fraction: Float) {
        animatedFraction = fraction
        val t = interpolator?.getInterpolation(fraction)
            ?: ((Math.cos((fraction + 1) * Math.PI) / 2.0).toFloat() + 0.5f)
        animatedValue = if (values.size == 1) values[0] * t else {
            val seg = t * (values.size - 1)
            val i = seg.toInt().coerceIn(0, values.size - 2)
            values[i] + (values[i + 1] - values[i]) * (seg - i)
        }
        updateListeners.toList().forEach { it.onAnimationUpdate(this) }
    }

    private fun cancelTimer() { timer?.stop(); timer = null }

    fun cancel() {
        if (timer != null) { cancelTimer(); listeners.forEach { it.onAnimationCancel(Animator.Dummy); it.onAnimationEnd(Animator.Dummy) } }
    }

    fun end() { if (timer != null) { cancelTimer(); update(1f); listeners.forEach { it.onAnimationEnd(Animator.Dummy) } } }

    companion object {
        @JvmStatic fun ofFloat(vararg values: Float): ValueAnimator = ValueAnimator(values.copyOf())
        @JvmStatic fun ofInt(vararg values: Int): ValueAnimator = ValueAnimator(FloatArray(values.size) { values[it].toFloat() })
    }
}

fun interface TimeInterpolator { fun getInterpolation(input: Float): Float }

abstract class Animator {
    interface AnimatorListener {
        fun onAnimationStart(animation: Animator) {}
        fun onAnimationEnd(animation: Animator) {}
        fun onAnimationCancel(animation: Animator) {}
        fun onAnimationRepeat(animation: Animator) {}
    }
    internal object Dummy : Animator()
}

open class AnimatorListenerAdapter : Animator.AnimatorListener
