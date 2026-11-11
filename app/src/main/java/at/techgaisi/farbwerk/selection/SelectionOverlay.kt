package at.techgaisi.farbwerk.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.Selection
import at.techgaisi.farbwerk.model.Tool
import java.lang.ref.WeakReference

/**
 * Zeichnet die aktive Auswahl als animierte Ameisenlinie (View-Koordinaten).
 * Bei aktivem Auswahlwerkzeug wird zusätzlich der Bereich außerhalb der Auswahl abgedunkelt.
 * Die Kontur reiner Masken (Automatisch, Umkehren, kombinierte Formen) wird einmalig berechnet und
 * pro Auswahl-Objekt zwischengespeichert.
 */
object SelectionOverlay {

    private var cachedFor: WeakReference<Selection>? = null
    private var cachedOutline: Path? = null
    private var cachedInverseFor: WeakReference<Selection>? = null
    private var cachedInverse: Bitmap? = null

    private val viewPath = Path()
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dimPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0x66000000 }

    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var stateRef: WeakReference<EditorState>? = null
    private val tick = Runnable {
        scheduled = false
        stateRef?.get()?.canvasView?.postInvalidateOnAnimation()
    }

    fun draw(canvas: Canvas, state: EditorState, docToView: Matrix) {
        val doc = state.document ?: return
        val sel = doc.selection ?: return
        // Während des Transformierens zeichnet das Transform-Werkzeug selbst.
        if (state.tool == Tool.TRANSFORM && state.transformTool.active) return
        val density = state.context.resources.displayMetrics.density
        val selecting = state.tool == Tool.SELECT

        if (selecting) {
            val inv = inverseMask(sel)
            canvas.save()
            canvas.concat(docToView)
            canvas.drawBitmap(inv, 0f, 0f, dimPaint)
            canvas.restore()
        }

        val outline = outline(sel)
        outline.transform(docToView, viewPath)
        val phase = (SystemClock.uptimeMillis() % 100_000L) / 40f
        val dash = 5f * density
        solid.color = if (selecting) Color.WHITE else 0xCCFFFFFF.toInt()
        solid.strokeWidth = (if (selecting) 2f else 1.2f) * density
        dashed.color = if (selecting) Color.BLACK else 0xB0000000.toInt()
        dashed.strokeWidth = (if (selecting) 1.5f else 1f) * density
        dashed.pathEffect = DashPathEffect(floatArrayOf(dash, dash), -(phase % (2 * dash)))
        canvas.drawPath(viewPath, solid)
        canvas.drawPath(viewPath, dashed)

        // Sparsame Animation: nur Leinwand neu zeichnen (keine Compose-Neuberechnung), ca. 12 fps.
        if (!scheduled) {
            scheduled = true
            stateRef = WeakReference(state)
            handler.postDelayed(tick, 85)
        }
    }

    /** Kontur der Auswahl in Dokument-Koordinaten (Pfad, falls vorhanden, sonst aus der Maske). */
    fun outline(sel: Selection): Path {
        sel.path?.let { return it }
        if (cachedFor?.get() === sel) cachedOutline?.let { return it }
        val p = MaskUtils.contour(sel.mask)
        cachedFor = WeakReference(sel)
        cachedOutline = p
        return p
    }

    private fun inverseMask(sel: Selection): Bitmap {
        if (cachedInverseFor?.get() === sel) cachedInverse?.let { return it }
        // Alte Bitmap nicht recyceln – der RenderThread kann sie noch referenzieren (GC räumt auf).
        val inv = MaskUtils.invert(sel.mask)
        cachedInverseFor = WeakReference(sel)
        cachedInverse = inv
        return inv
    }
}
