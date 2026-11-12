package at.techgaisi.farbwerk.brush

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.atan2

/** Empfänger für fertig berechnete Tupfer. */
fun interface DabConsumer {
    fun dab(x: Float, y: Float, diam: Float, aspect: Float, angle: Float, alpha: Float, color: Int, flags: Int)
}

/** Liefert die (nicht vormultiplizierte) Farbe der Ebene an einer Stelle – für "Ziehen"/Mischen. */
fun interface ColorSampler {
    fun sample(x: Float, y: Float): Int
}

/** Wachsende Float-Liste ohne Boxing. */
class FloatBuf(cap: Int = 256) {
    var data = FloatArray(cap); private set
    var size = 0; private set
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    operator fun get(i: Int) = data[i]
    fun clear() { size = 0 }
}

/** Aufgezeichnete Tupfer eines Strichs (spaltenweise, ohne Objekte pro Tupfer). */
class DabList {
    var size = 0; private set
    var x = FloatArray(512); private set
    var y = FloatArray(512); private set
    var diam = FloatArray(512); private set
    var aspect = FloatArray(512); private set
    var angle = FloatArray(512); private set
    var alpha = FloatArray(512); private set
    var dist = FloatArray(512); private set
    var color = IntArray(512); private set
    var flags = IntArray(512); private set

    fun add(px: Float, py: Float, d: Float, asp: Float, ang: Float, a: Float, c: Int, f: Int, ds: Float): Int {
        if (size == x.size) grow()
        x[size] = px; y[size] = py; diam[size] = d; aspect[size] = asp; angle[size] = ang
        alpha[size] = a; color[size] = c; flags[size] = f; dist[size] = ds
        return size++
    }

    private fun grow() {
        val n = size * 2
        x = x.copyOf(n); y = y.copyOf(n); diam = diam.copyOf(n); aspect = aspect.copyOf(n)
        angle = angle.copyOf(n); alpha = alpha.copyOf(n); dist = dist.copyOf(n)
        color = color.copyOf(n); flags = flags.copyOf(n)
    }

    fun clear() { size = 0 }
}

/**
 * Wandelt Eingabepunkte in Pinsel-Tupfer um:
 *  Rohpunkte → Stabilisierung (gleitender Mittelwert) → StreamLine (nachziehende Glättung) →
 *  Catmull-Rom-Interpolation → Abtasten im Pinsel-Abstand → Dynamik (Druck, Neigung, Tempo, Verjüngung,
 *  Streuung, Farbdynamik, nasse Farbe) → [sink].
 *
 * Wird von der Leinwand-Engine, der Bibliotheksvorschau und dem Pinsel-Studio gemeinsam benutzt.
 * Keine Allokationen pro Eingabepunkt.
 */
class StrokeRenderer {
    // ------------------------------------------------------------ Konfiguration
    var brush: Brush = Brush("_", "", "", false)
    /** Grunddurchmesser in Dokumentpixeln (aus Größe-Regler). */
    var diameter = 20f
    /** Strichdeckkraft 0..1 (nur bei "Starke Lasur" pro Tupfer wirksam, sonst beim Zusammenführen). */
    var opacity = 1f
    var color = Color.BLACK
    var secondaryColor = Color.WHITE
    var globalPressureSmoothing = 0f
    var globalMotionFilter = 0f
    /** Faktor Dokumentpixel → Bildschirmpixel (für Tempo-Normierung). */
    var viewScale = 1f
    var sampler: ColorSampler? = null
    var sink: DabConsumer? = null

    // ------------------------------------------------------------ Ergebnis
    val dabs = DabList()
    /** Geglättete Pfadpunkte (für QuickShape). */
    val pathX = FloatBuf()
    val pathY = FloatBuf()
    /** Länge des bisherigen Strichs in Dokumentpixeln. */
    val totalLength: Float get() = distAlong
    /** Wenn > 0, ist die Gesamtlänge vorab bekannt (QuickShape) und das Ende wird sofort verjüngt. */
    var knownTotalLength = 0f
        private set
    /** Aktuelle (geglättete) Kopfposition und Größe – für den Pinsel-Cursor. */
    var headX = 0f; private set
    var headY = 0f; private set
    var headDiameter = 0f; private set
    var stylus = false; private set
    private var pressureSum = 0.0
    private var pressureCount = 0
    val meanPressure: Float get() = if (pressureCount == 0) 1f else (pressureSum / pressureCount).toFloat()

    // ------------------------------------------------------------ Zustand
    private val stabX = FloatArray(64)
    private val stabY = FloatArray(64)
    private var stabWindow = 1
    private var stabCount = 0
    private var stabPos = 0
    private var smX = 0f; private var smY = 0f
    private var smP = 1f
    private var lastRawX = 0f; private var lastRawY = 0f
    private var lastTime = 0L
    private var speed = 0f
    private var lastTilt = 0f; private var lastOrient = 0f
    /** Geglättete Neigung/Ausrichtung (Rohwerte des Digitizers rauschen leicht). */
    private var smT = 0f; private var smO = 0f

    private val wx = FloatArray(4); private val wy = FloatArray(4)
    private val wp = FloatArray(4); private val wt = FloatArray(4); private val wo = FloatArray(4)
    private var wCount = 0

    private var distAlong = 0f
    private var distToNext = 0f
    private var segPa = 1f; private var segPb = 1f
    private var segTa = 0f; private var segTb = 0f
    private var segOa = 0f; private var segOb = 0f
    private var closedShape = false

    private var strokeColor = Color.BLACK
    private var carR = 0f; private var carG = 0f; private var carB = 0f
    private val hsv = FloatArray(3)
    private var seed = 1L
    private var rng = 1L

    // pro Strich vorberechnet
    private var flowEff = 1f
    private var pullEff = 0f
    private var heavy = false
    private var taperOn = false
    private var taperStartLen = 0f
    private var taperEndLen = 0f
    private var taperExp = 1f
    private var streamK = 1f

    // ------------------------------------------------------------ Strich-API

    fun begin(x: Float, y: Float, pressure: Float, tilt: Float, orient: Float, time: Long, isStylus: Boolean) {
        stylus = isStylus
        speed = 0f
        prepare()
        knownTotalLength = 0f
        closedShape = false
        pathX.clear(); pathY.clear()
        pressureSum = 0.0; pressureCount = 0
        stabCount = 0; stabPos = 0
        val p0 = inputPressure(pressure)
        smP = p0
        pressureSum = p0.toDouble(); pressureCount = 1
        smX = x; smY = y
        lastRawX = x; lastRawY = y; lastTime = time
        lastTilt = tilt; lastOrient = orient
        smT = tilt; smO = orient
        headX = x; headY = y; headDiameter = diameter
        pushStab(x, y)
        addControl(x, y, smP, tilt, orient)
    }

    fun add(x: Float, y: Float, pressure: Float, tilt: Float, orient: Float, time: Long) {
        val dt = max(1L, time - lastTime).toFloat()
        val d = hypot(x - lastRawX, y - lastRawY)
        val inst = d / dt * viewScale
        speed += (inst - speed) * 0.3f
        lastRawX = x; lastRawY = y; lastTime = time

        val p = inputPressure(pressure)
        val ps = max(globalPressureSmoothing, brush[BP.PRESSURE_SMOOTHING])
        smP += (p - smP) * (1f - 0.92f * ps)
        pressureSum += smP; pressureCount++
        // Neigung und Ausrichtung leicht glätten (Ausrichtung über den kürzesten Winkelweg)
        smT += (tilt - smT) * 0.5f
        smO = wrapAngle(smO + wrapAngle(orient - smO) * 0.5f)
        lastTilt = smT; lastOrient = smO

        pushStab(x, y)
        var ax = 0f; var ay = 0f
        for (i in 0 until stabCount) { ax += stabX[i]; ay += stabY[i] }
        ax /= stabCount; ay /= stabCount
        smX += (ax - smX) * streamK
        smY += (ay - smY) * streamK
        addControl(smX, smY, smP, smT, smO)
    }

    /** Strich beenden: etwas zum Endpunkt nachziehen und das letzte Segment zeichnen. */
    fun end() {
        // sanftes Aufholen (StreamLine-Verzug teilweise ausgleichen)
        repeat(3) {
            pushStab(lastRawX, lastRawY)
            var ax = 0f; var ay = 0f
            for (i in 0 until stabCount) { ax += stabX[i]; ay += stabY[i] }
            ax /= stabCount; ay /= stabCount
            smX += (ax - smX) * max(streamK, 0.35f)
            smY += (ay - smY) * max(streamK, 0.35f)
            addControl(smX, smY, smP, lastTilt, lastOrient)
        }
        if (wCount >= 2) {
            crSegment(wx[1], wy[1], wx[2], wy[2], wx[3], wy[3], wx[3], wy[3], 2, 3)
        }
        if (dabs.size == 0) {
            // Tippen ohne Bewegung: ein einzelner, unverjüngter Tupfer
            val keepTaper = taperOn
            taperOn = false
            segPa = smP; segPb = smP; segTa = lastTilt; segTb = lastTilt; segOa = lastOrient; segOb = lastOrient
            emit(wx[3], wy[3], 0f, 0f)
            taperOn = keepTaper
        }
    }

    /**
     * Rendert eine fertige Form (QuickShape) als Polylinie mit konstantem Druck neu. Vorherige Tupfer werden
     * verworfen; die Gesamtlänge ist bekannt, daher werden offene Formen an beiden Enden verjüngt.
     */
    fun renderPath(xs: FloatArray, ys: FloatArray, n: Int, closed: Boolean, pressure: Float) {
        if (n < 2) return
        prepare(reseed = false)
        rng = seed
        dabs.clear()
        distAlong = 0f; distToNext = 0f
        closedShape = closed
        var len = 0f
        for (i in 1 until n) len += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if (closed) len += hypot(xs[0] - xs[n - 1], ys[0] - ys[n - 1])
        knownTotalLength = max(len, 0.001f)
        segPa = pressure; segPb = pressure; segTa = 0f; segTb = 0f; segOa = 0f; segOb = 0f
        for (i in 1 until n) walkLinear(xs[i - 1], ys[i - 1], xs[i], ys[i], 0f, 1f)
        if (closed) walkLinear(xs[n - 1], ys[n - 1], xs[0], ys[0], 0f, 1f)
        if (dabs.size == 0) emit(xs[0], ys[0], 0f, 0f)
        headX = xs[n - 1]; headY = ys[n - 1]
    }

    // ------------------------------------------------------------ Ende-Verjüngung (nachträglich)

    /** true, wenn das Strichende nach dem Absetzen noch verjüngt werden muss. */
    fun needsEndTaper(): Boolean = taperOn && taperEndLen > 0f && knownTotalLength <= 0f && dabs.size > 1

    /** Erster Tupfer-Index, der von der Ende-Verjüngung betroffen ist. */
    fun tailStartIndex(): Int {
        val from = distAlong - taperEndLen
        var i = dabs.size - 1
        while (i > 0 && dabs.dist[i - 1] >= from) i--
        return i
    }

    /**
     * Gibt alle Tupfer erneut aus, wobei das Ende verjüngt wird. Im Endbereich werden bei Bedarf
     * Zwischen-Tupfer eingefügt, damit die dünne Spitze nicht gepunktet wirkt.
     */
    fun replayWithEndTaper(out: DabConsumer) {
        val l = dabs
        val total = distAlong
        val simple = brush.int(BP.COUNT) <= 1 && brush[BP.JITTER] <= 0.001f
        val tSize = brush[BP.TAPER_SIZE]; val tOp = brush[BP.TAPER_OPACITY]
        val spacing = brush[BP.SPACING]
        for (i in 0 until l.size) {
            val f = endFactor(total, l.dist[i])
            val d = l.diam[i] * (1f - tSize * (1f - f))
            val a = l.alpha[i] * (1f - tOp * (1f - f))
            out.dab(l.x[i], l.y[i], max(d, 0.3f), l.aspect[i], l.angle[i], a, l.color[i], l.flags[i])
            if (simple && f < 1f && i + 1 < l.size) {
                val gap = hypot(l.x[i + 1] - l.x[i], l.y[i + 1] - l.y[i])
                val step = max(0.3f, spacing * max(d, 0.3f))
                if (gap > step * 1.5f) {
                    val k = min(ceil(gap / step).toInt() - 1, 64)
                    for (j in 1..k) {
                        val t = j / (k + 1f)
                        val ds = l.dist[i] + (l.dist[i + 1] - l.dist[i]) * t
                        val fj = endFactor(total, ds)
                        val dj = (l.diam[i] + (l.diam[i + 1] - l.diam[i]) * t) * (1f - tSize * (1f - fj))
                        val aj = (l.alpha[i] + (l.alpha[i + 1] - l.alpha[i]) * t) * (1f - tOp * (1f - fj))
                        out.dab(
                            l.x[i] + (l.x[i + 1] - l.x[i]) * t, l.y[i] + (l.y[i + 1] - l.y[i]) * t,
                            max(dj, 0.3f), l.aspect[i], l.angle[i], aj, l.color[i], l.flags[i],
                        )
                    }
                }
            }
        }
    }

    private fun endFactor(total: Float, dist: Float): Float {
        if (taperEndLen <= 0f) return 1f
        val u = ((total - dist) / taperEndLen).coerceIn(0f, 1f)
        return u.pow(taperExp)
    }

    // ------------------------------------------------------------ intern

    private fun prepare(reseed: Boolean = true) {
        val b = brush
        dabs.clear()
        wCount = 0
        distAlong = 0f; distToNext = 0f
        if (reseed) {
            seed = System.nanoTime() or 1L
            rng = seed
        }
        val flow = b[BP.FLOW]
        val mode = b.int(BP.RENDER_MODE)
        flowEff = when (mode) {
            RenderMode.INTENSE_GLAZE -> sqrt(flow)
            RenderMode.UNIFORM_GLAZE -> flow.pow(0.85f)
            else -> flow
        }
        heavy = mode == RenderMode.HEAVY_GLAZE
        pullEff = (b[BP.PULL] + when (mode) {
            RenderMode.UNIFORM_BLEND -> 0.25f
            RenderMode.INTENSE_BLEND -> 0.5f
            else -> 0f
        }).coerceIn(0f, 0.95f)
        taperOn = (b[BP.TAPER_START] > 0f || b[BP.TAPER_END] > 0f) && !(b.on(BP.TAPER_TOUCH_ONLY) && stylus)
        val base = diameter * 8f + 150f
        taperStartLen = b[BP.TAPER_START] * base
        taperEndLen = b[BP.TAPER_END] * base
        taperExp = 1.6f + (0.45f - 1.6f) * b[BP.TAPER_TIP]
        val s = b[BP.STREAMLINE]
        streamK = 1f - s.pow(0.6f) * 0.93f
        stabWindow = (1 + ((b[BP.STABILIZE] + b[BP.MOTION_FILTER] * 0.5f + globalMotionFilter * 0.5f) * 24f).roundToInt())
            .coerceIn(1, stabX.size)

        // Strich-Farbstreuung
        var c = color
        if (b[BP.STROKE_HUE_JITTER] > 0f || b[BP.STROKE_BRIGHT_JITTER] > 0f) {
            Color.colorToHSV(c, hsv)
            hsv[0] = (hsv[0] + (rand() - 0.5f) * 2f * b[BP.STROKE_HUE_JITTER] * 180f + 360f) % 360f
            hsv[2] = (hsv[2] * (1f + (rand() - 0.5f) * 2f * b[BP.STROKE_BRIGHT_JITTER])).coerceIn(0f, 1f)
            c = Color.HSVToColor(hsv)
        }
        strokeColor = c
        carR = Color.red(c).toFloat(); carG = Color.green(c).toFloat(); carB = Color.blue(c).toFloat()
    }

    private fun inputPressure(raw: Float): Float {
        if (stylus) return raw.coerceIn(0.01f, 1f)
        if (brush.on(BP.FINGER_PRESSURE)) {
            val sn = (speed / 4f).coerceIn(0f, 1f)
            return (1.1f - sn * 0.85f).coerceIn(0.2f, 1f)
        }
        return 1f
    }

    private fun pushStab(x: Float, y: Float) {
        stabX[stabPos] = x; stabY[stabPos] = y
        stabPos = (stabPos + 1) % stabWindow
        if (stabCount < stabWindow) stabCount++
    }

    private fun addControl(x: Float, y: Float, p: Float, tilt: Float, orient: Float) {
        if (wCount == 0) {
            for (i in 0 until 4) { wx[i] = x; wy[i] = y; wp[i] = p; wt[i] = tilt; wo[i] = orient }
            wCount = 1
            pathX.add(x); pathY.add(y)
            return
        }
        if (hypot(x - wx[3], y - wy[3]) < 0.4f) {
            wp[3] = p; wt[3] = tilt; wo[3] = orient
            return
        }
        for (i in 0 until 3) { wx[i] = wx[i + 1]; wy[i] = wy[i + 1]; wp[i] = wp[i + 1]; wt[i] = wt[i + 1]; wo[i] = wo[i + 1] }
        wx[3] = x; wy[3] = y; wp[3] = p; wt[3] = tilt; wo[3] = orient
        wCount++
        pathX.add(x); pathY.add(y)
        headX = x; headY = y
        if (wCount >= 3) crSegment(wx[0], wy[0], wx[1], wy[1], wx[2], wy[2], wx[3], wy[3], 1, 2)
    }

    /** Catmull-Rom-Segment P1→P2; [ia]/[ib] = Fenster-Indizes für Druck/Neigung an den Enden. */
    private fun crSegment(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
        ia: Int, ib: Int,
    ) {
        val chord = hypot(x2 - x1, y2 - y1)
        if (chord < 1e-4f) return
        segPa = wp[ia]; segPb = wp[ib]; segTa = wt[ia]; segTb = wt[ib]; segOa = wo[ia]; segOb = wo[ib]
        val m = ceil(chord / 2f).toInt().coerceIn(1, 256)
        var px = x1; var py = y1
        for (k in 1..m) {
            val t = k / m.toFloat()
            val t2 = t * t; val t3 = t2 * t
            val x = 0.5f * (2f * x1 + (-x0 + x2) * t + (2f * x0 - 5f * x1 + 4f * x2 - x3) * t2 + (-x0 + 3f * x1 - 3f * x2 + x3) * t3)
            val y = 0.5f * (2f * y1 + (-y0 + y2) * t + (2f * y0 - 5f * y1 + 4f * y2 - y3) * t2 + (-y0 + 3f * y1 - 3f * y2 + y3) * t3)
            walkLinear(px, py, x, y, (k - 1) / m.toFloat(), t)
            px = x; py = y
        }
    }

    /** Läuft eine gerade Strecke ab und setzt Tupfer im aktuellen Abstand. */
    private fun walkLinear(ax: Float, ay: Float, bx: Float, by: Float, ta: Float, tb: Float) {
        val len = hypot(bx - ax, by - ay)
        if (len <= 0f) return
        val dir = atan2(by - ay, bx - ax)
        var consumed = 0f
        var guard = 0
        while (distToNext <= len - consumed && guard++ < 100000) {
            consumed += distToNext
            distAlong += distToNext
            val f = consumed / len
            emit(ax + (bx - ax) * f, ay + (by - ay) * f, ta + (tb - ta) * f, dir)
        }
        val rest = len - consumed
        distToNext -= rest
        distAlong += rest
    }

    /** Berechnet einen Tupfer (inkl. aller Dynamiken) und gibt ihn aus. Setzt den Abstand zum nächsten. */
    private fun emit(x: Float, y: Float, t: Float, dir: Float) {
        val b = brush
        val p = segPa + (segPb - segPa) * t
        val tilt = segTa + (segTb - segTa) * t
        val orient = wrapAngle(segOa + wrapAngle(segOb - segOa) * t)
        val tiltT = tiltFactor(b, tilt)
        val speedNorm = (speed / 4f).coerceIn(0f, 1f)

        // Verjüngung
        var tf = 1f
        if (taperOn && !closedShape) {
            if (taperStartLen > 0f) tf = min(tf, (distAlong / taperStartLen).coerceIn(0f, 1f).pow(taperExp))
            if (knownTotalLength > 0f) tf = min(tf, endFactor(knownTotalLength, distAlong))
        }

        // Größe
        var d = diameter
        d *= 1f - b[BP.PRESSURE_SIZE] * (1f - p)
        d *= 1f + b[BP.TILT_SIZE] * tiltT
        d *= (1f - b[BP.SPEED_SIZE] * speedNorm).coerceAtLeast(0.05f)
        d *= 1f - b[BP.TAPER_SIZE] * (1f - tf)
        d = max(d, 0.3f)

        // Deckkraft / Fluss
        var a = flowEff
        a *= 1f - b[BP.PRESSURE_OPACITY] * (1f - p)
        a *= 1f - b[BP.PRESSURE_FLOW] * (1f - p)
        a *= 1f - b[BP.TILT_OPACITY] * tiltT
        a *= 1f - b[BP.SPEED_OPACITY] * speedNorm
        a *= 1f - b[BP.TAPER_OPACITY] * (1f - tf)
        if (b[BP.FALLOFF] > 0f) a *= 1f - b[BP.FALLOFF] * min(1f, distAlong / 1500f)
        if (b[BP.CHARGE] < 0.999f) a *= exp(-distAlong / (b[BP.CHARGE] * 4000f + 60f))
        a *= 1f - 0.85f * b[BP.DILUTION]
        if (heavy) a *= opacity

        distToNext = max(0.3f, b[BP.SPACING] * d)
        headDiameter = d

        // Farbe
        var col = strokeColor
        val cp = b[BP.COLOR_PRESSURE] * (1f - p) + b[BP.COLOR_TILT] * tiltT
        if (cp > 0f) col = mix(col, secondaryColor, cp.coerceIn(0f, 1f))
        val smp = sampler
        if (pullEff > 0f && smp != null) {
            val s = smp.sample(x, y)
            val sa = Color.alpha(s) / 255f
            val w = pullEff * sa * (1f - b[BP.WETNESS_JITTER] * rand()) * 0.6f
            carR += (Color.red(s) - carR) * w
            carG += (Color.green(s) - carG) * w
            carB += (Color.blue(s) - carB) * w
            // Pinsel lädt sich langsam wieder mit der eigenen Farbe auf
            val re = 0.12f * (1f - pullEff)
            carR += (Color.red(col) - carR) * re
            carG += (Color.green(col) - carG) * re
            carB += (Color.blue(col) - carB) * re
            val carried = Color.rgb(carR.toInt().coerceIn(0, 255), carG.toInt().coerceIn(0, 255), carB.toInt().coerceIn(0, 255))
            col = mix(col, carried, pullEff)
        }

        // Stempel
        val baseCount = b.int(BP.COUNT).coerceAtLeast(1)
        val count = baseCount + (rand() * b[BP.COUNT_JITTER] * baseCount).toInt()
        val jitter = b[BP.JITTER]
        val rotMode = b.int(BP.ROTATION_MODE)
        val baseRot = Math.toRadians(b[BP.ROTATION].toDouble()).toFloat()
        val scatter = b[BP.SCATTER]
        val tiltRound = b[BP.TILT_ROUNDNESS]
        val aspect = (b[BP.ROUNDNESS] * (1f - tiltRound * tiltT * 0.7f)).coerceIn(0.03f, 1f)
        // Schraffieren mit geneigtem Stift: Bei runden Spitzen mit "Neigung → Rundheit" liegt die lange Achse der
        // abgeflachten Spitze entlang der Stiftausrichtung (wie die Mine eines flach gehaltenen Bleistifts).
        val tiltOriented = tiltRound > 0f && tiltT > 0f && b[BP.ROUNDNESS] > 0.95f && rotMode == RotationMode.FIXED
        val hj = b[BP.HUE_JITTER]; val sj = b[BP.SAT_JITTER]; val bj = b[BP.BRIGHT_JITTER]
        for (k in 0 until count) {
            var sx = x; var sy = y
            if (jitter > 0f) {
                val r = jitter * d * sqrt(rand())
                val an = rand() * 2f * PI.toFloat()
                sx += kotlin.math.cos(an) * r; sy += kotlin.math.sin(an) * r
            }
            var ang = when (rotMode) {
                RotationMode.FOLLOW -> dir + baseRot
                RotationMode.RANDOM -> rand() * 2f * PI.toFloat()
                RotationMode.AZIMUTH -> orient + baseRot
                else -> if (tiltOriented) orient + baseRot else baseRot
            }
            if (scatter > 0f) ang += (rand() - 0.5f) * 2f * PI.toFloat() * scatter
            val dj = d * (1f - b[BP.SIZE_JITTER] * rand())
            val aj = a * (1f - b[BP.OPACITY_JITTER] * rand())
            var flags = 0
            if (b.on(BP.FLIP_X) && rand() < 0.5f) flags = flags or 1
            if (b.on(BP.FLIP_Y) && rand() < 0.5f) flags = flags or 2
            var c = col
            if (hj > 0f || sj > 0f || bj > 0f) {
                Color.colorToHSV(c, hsv)
                hsv[0] = (hsv[0] + (rand() - 0.5f) * 2f * hj * 180f + 360f) % 360f
                hsv[1] = (hsv[1] + (rand() - 0.5f) * 2f * sj).coerceIn(0f, 1f)
                hsv[2] = (hsv[2] + (rand() - 0.5f) * 2f * bj).coerceIn(0f, 1f)
                c = Color.HSVToColor(hsv)
            }
            dabs.add(sx, sy, max(dj, 0.3f), aspect, ang, aj, c, flags, distAlong)
            sink?.dab(sx, sy, max(dj, 0.3f), aspect, ang, aj, c, flags)
        }
    }

    private fun rand(): Float {
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
        rng = x
        return ((x ushr 40) and 0xFFFFFF).toFloat() / 16777216f
    }

    companion object {
        private const val HALF_PI = (PI / 2.0).toFloat()
        private const val TWO_PI = (PI * 2.0).toFloat()

        /**
         * Neigungsfaktor 0..1 für die Dynamik: 0 = senkrecht (bzw. steiler als [BP.TILT_ANGLE]), 1 = flach.
         * [tilt] in Radiant ab der Senkrechten (wie MotionEvent.AXIS_TILT). Ein weicher Übergang (smoothstep)
         * verhindert sichtbare Sprünge, wenn die Schwelle überschritten wird.
         */
        fun tiltFactor(b: Brush, tilt: Float): Float {
            if (tilt <= 0f) return 0f
            val start = Math.toRadians(b[BP.TILT_ANGLE].toDouble()).toFloat().coerceIn(0f, HALF_PI - 0.05f)
            val u = ((tilt - start) / (HALF_PI - start)).coerceIn(0f, 1f)
            return u * u * (3f - 2f * u)
        }

        /** Winkel auf -π..π abbilden. */
        fun wrapAngle(a: Float): Float {
            var r = a
            while (r > PI) r -= TWO_PI
            while (r < -PI) r += TWO_PI
            return r
        }

        fun mix(a: Int, b: Int, t: Float): Int {
            val u = 1f - t
            return Color.argb(
                255,
                (Color.red(a) * u + Color.red(b) * t).toInt(),
                (Color.green(a) * u + Color.green(b) * t).toInt(),
                (Color.blue(a) * u + Color.blue(b) * t).toInt(),
            )
        }
    }
}
