package at.techgaisi.farbwerk.selection

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.model.Selection
import at.techgaisi.farbwerk.model.Tool
import java.lang.ref.WeakReference

/**
 * Zeichnet die aktive Auswahl (View-Koordinaten):
 *  - im Auswahlwerkzeug: Ameisenlinie + abgedunkelter Bereich außerhalb; im Modus „Automatisch“
 *    wird der gewählte Bereich stattdessen farblich invertiert dargestellt (wie im Vorbild),
 *  - nach dem Übernehmen (anderes Werkzeug): halbtransparente, langsam wandernde diagonale
 *    Schwarz/Weiß-Streifen über dem Bereich *außerhalb* der Auswahl (Deckkraft aus [SelectionPrefs.maskOpacity]).
 *
 * Die Streifen sind ein wiederholter [LinearGradient] mit harten Stopps (keine Bitmap-Kachel) und werden
 * über einen [ComposeShader] mit der invertierten Auswahlmaske maskiert – ein einziger drawRect pro Bild.
 * Kontur und invertierte Maske werden pro Auswahl-Objekt zwischengespeichert.
 */
object SelectionOverlay {

    /** Periode der Streifen (Schwarz + Weiß) senkrecht zur Laufrichtung in dp. */
    private const val STRIPE_PERIOD_DP = 9f
    /** Bildabstand der Animation (ms) – ca. 12 Bilder/s genügen für die langsame Bewegung. */
    private const val TICK_MS = 85L

    private var cachedFor: WeakReference<Selection>? = null
    private var cachedOutline: Path? = null
    private var cachedInverseFor: WeakReference<Selection>? = null
    private var cachedInverse: Bitmap? = null

    private val viewPath = Path()
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dimPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = 0x66000000 }
    private val invertPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE
        blendMode = BlendMode.DIFFERENCE
    }

    // ------------------------------------------------------------ Streifen
    private var stripeDensity = 0f
    private var stripeGradient: LinearGradient? = null
    private var maskShaderFor: WeakReference<Bitmap>? = null
    private var maskShader: BitmapShader? = null
    private var composed: ComposeShader? = null
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripeMatrix = Matrix()
    private val invView = Matrix()

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
        SelectionActions.noteSelection(doc, sel)
        // Während des Transformierens zeichnet das Transform-Werkzeug selbst.
        if (state.tool == Tool.TRANSFORM && state.transformTool.active) return
        val density = state.context.resources.displayMetrics.density
        val selecting = state.tool == Tool.SELECT
        val now = SystemClock.uptimeMillis()

        if (selecting) {
            if (state.selectionTool.mode == SelectionMode.AUTOMATIC) {
                // Gewählten Bereich farblich invertieren (Differenz mit Weiß)
                canvas.save()
                canvas.concat(docToView)
                canvas.drawBitmap(sel.mask, 0f, 0f, invertPaint)
                canvas.restore()
            } else {
                val inv = inverseMask(sel)
                canvas.save()
                canvas.concat(docToView)
                canvas.drawBitmap(inv, 0f, 0f, dimPaint)
                canvas.restore()
            }
        } else {
            drawStripes(canvas, sel, doc.width, doc.height, docToView, density, now)
        }

        val outline = outline(sel)
        outline.transform(docToView, viewPath)
        val phase = (now % 100_000L) / 40f
        val dash = 5f * density
        solid.color = if (selecting) Color.WHITE else 0x99FFFFFF.toInt()
        solid.strokeWidth = (if (selecting) 2f else 1f) * density
        dashed.color = if (selecting) Color.BLACK else 0x99000000.toInt()
        dashed.strokeWidth = (if (selecting) 1.5f else 1f) * density
        dashed.pathEffect = DashPathEffect(floatArrayOf(dash, dash), -(phase % (2 * dash)))
        canvas.drawPath(viewPath, solid)
        canvas.drawPath(viewPath, dashed)

        // Sparsame Animation: nur die Leinwand neu zeichnen (keine Compose-Neuberechnung).
        if (!scheduled) {
            scheduled = true
            stateRef = WeakReference(state)
            handler.postDelayed(tick, TICK_MS)
        }
    }

    /**
     * Diagonale, wandernde Streifen außerhalb der Auswahl. Die Streifen liegen fest im Bildschirmraum
     * (gleich breit bei jedem Zoom) und laufen ~1 Periode pro Sekunde.
     */
    private fun drawStripes(
        canvas: Canvas, sel: Selection, w: Int, h: Int, docToView: Matrix, density: Float, now: Long,
    ) {
        val opacity = SelectionPrefs.maskOpacity
        if (opacity <= 0.005f) return
        if (!docToView.invert(invView)) return
        val inv = inverseMask(sel)
        val period = STRIPE_PERIOD_DP * density
        if (stripeGradient == null || stripeDensity != density) {
            stripeDensity = density
            // Gradient entlang (1,1): Wert hängt von (x + y) ab → Streifen unter 45°.
            stripeGradient = LinearGradient(
                0f, 0f, period, period,
                intArrayOf(Color.BLACK, Color.BLACK, Color.WHITE, Color.WHITE),
                floatArrayOf(0f, 0.5f, 0.5f, 1f),
                Shader.TileMode.REPEAT,
            )
            composed = null
        }
        if (maskShaderFor?.get() !== inv || maskShader == null) {
            maskShader = BitmapShader(inv, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            maskShaderFor = WeakReference(inv)
            composed = null
        }
        val grad = stripeGradient ?: return
        val ms = maskShader ?: return
        // Laufrichtung entlang x: eine volle Periode (2 × period in x) pro Sekunde
        val shift = (now % 1000L) / 1000f * 2f * period
        stripeMatrix.setTranslate(shift, 0f)
        stripeMatrix.postConcat(invView)          // View-Raum → Dokumentraum (Zeichnen erfolgt im Dokumentraum)
        grad.setLocalMatrix(stripeMatrix)
        val cs = composed ?: ComposeShader(ms, grad, PorterDuff.Mode.SRC_IN).also { composed = it }
        stripePaint.shader = cs
        stripePaint.alpha = (opacity * 255f).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.concat(docToView)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), stripePaint)
        canvas.restore()
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
