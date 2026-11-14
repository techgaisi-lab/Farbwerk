package at.techgaisi.farbwerk.brush

import android.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
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
    /** Drehung der Ansicht (Radiant) – für "An Bildschirm ausrichten". */
    var viewRotation = 0f
    /** Feinberechnung des Dokuments ([at.techgaisi.farbwerk.model.ArtDocument.detailScale]) für feste Pixellängen. */
    var detailScale = 1f
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
    /** Die beiden letzten (ungeglätteten) Druckwerte für den Median-Vorfilter. */
    private var rawP1 = 1f; private var rawP2 = 1f
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
    /** Druckabhängiger Durchmesser und Strichposition des vorigen Tupfers (Begrenzung der Breitenänderung). */
    private var prevPressD = -1f
    private var prevPressDist = 0f
    /** Tupferabstand nach dem letzten Tupfer (für die Fortsetzung in [emitTail]). */
    private var lastStep = 1f
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
    private var taperSizeAmt = 0.9f
    private var taperOpAmt = 0f
    private var taperPressAmt = 0f
    private var streamK = 1f
    private var strokeRotOffset = 0f
    private var linearMix = false
    private var expr = 0f
    /** Durchgehender Strich (solide Spitze ohne Streuung/Stempelabstand) → Mindest-Tupferdichte. */
    private var continuous = false

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
        rawP1 = p0; rawP2 = p0
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
        val ps = max(max(globalPressureSmoothing, brush[BP.PRESSURE_SMOOTHING]), brush[BP.STREAMLINE_PRESSURE])
        // Median der letzten drei Werte: einzelne Ausreißer des Digitizers verschwinden vollständig
        val med = if (ps > 0.001f) max(min(rawP1, rawP2), min(max(rawP1, rawP2), p)) else p
        rawP1 = rawP2; rawP2 = p
        smP = filterPressure(smP, med, dt, ps)
        pressureSum += smP; pressureCount++
        // Neigung und Ausrichtung leicht glätten (Ausrichtung über den kürzesten Winkelweg)
        smT += (tilt - smT) * 0.5f
        smO = wrapAngle(smO + wrapAngle(orient - smO) * 0.5f)
        lastTilt = smT; lastOrient = smO

        pushStab(x, y)
        var ax = 0f; var ay = 0f
        for (i in 0 until stabCount) { ax += stabX[i]; ay += stabY[i] }
        ax /= stabCount; ay /= stabCount
        // Bewegungsfilterung "Ausdruck": schnelle Bewegungen kommen ungefiltert durch
        if (expr > 0f) {
            val e = expr * (speed / 3f).coerceIn(0f, 1f)
            ax += (x - ax) * e; ay += (y - ay) * e
        }
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

    /**
     * Länge (Dokumentpixel), über die das Strichende verjüngt wird, wenn die Verjüngung live – während des
     * Strichs – angewandt wird; 0 = keine Ende-Verjüngung (oder Form mit bekannter Länge).
     */
    val liveEndTaperLength: Float get() = if (taperOn && taperEndLen > 0f && knownTotalLength <= 0f) taperEndLen else 0f

    /** Gibt alle Tupfer erneut aus, wobei das Ende verjüngt wird (z. B. Pinselvorschau). */
    fun replayWithEndTaper(out: DabConsumer) = emitTail(0, distAlong, true, out)

    /** Länge der Fortsetzung vom letzten Tupfer über die Punkte [xs]/[ys] (Kopf, Vorhersage). */
    fun extensionLength(xs: FloatArray, ys: FloatArray, n: Int): Float {
        val l = dabs
        if (l.size == 0 || n <= 0) return 0f
        var px = l.x[l.size - 1]; var py = l.y[l.size - 1]
        var len = 0f
        for (k in 0 until n) { len += hypot(xs[k] - px, ys[k] - py); px = xs[k]; py = ys[k] }
        return len
    }

    /** Strichposition des letzten Tupfers (0 ohne Tupfer). */
    val lastDabDist: Float get() = if (dabs.size == 0) 0f else dabs.dist[dabs.size - 1]

    /**
     * Gibt das (noch veränderliche) Strichende aus: Tupfer ab Index [from], mit Ende-Verjüngung relativ zur
     * Strichlänge [total] (wenn [taper]). Bei dünn werdender Spitze werden Zwischen-Tupfer eingefügt, damit sie
     * nicht gepunktet wirkt. Optional folgt eine Fortsetzung entlang [extX]/[extY] (aktuelle Kopfposition und
     * Bewegungsvorhersage) mit Größe/Form/Farbe des letzten Tupfers – so zeigt die Live-Ansicht schon während des
     * Malens die Spitze, die beim Absetzen an dieser Stelle entstünde.
     */
    fun emitTail(
        from: Int, total: Float, taper: Boolean, out: DabConsumer,
        extX: FloatArray? = null, extY: FloatArray? = null, extN: Int = 0, to: Int = dabs.size,
    ) {
        val l = dabs
        val useTaper = taper && taperEndLen > 0f
        val simple = brush.int(BP.COUNT) <= 1 && brush[BP.JITTER] <= 0.001f
        val tSize = taperSizeAmt; val tOp = taperOpAmt
        val spacing = min(brush[BP.SPACING], if (continuous) MAX_CONTINUOUS_SPACING else 3f)
        val end = to.coerceAtMost(l.size)
        for (i in from.coerceAtLeast(0) until end) {
            val f = if (useTaper) endFactor(total, l.dist[i]) else 1f
            val d = l.diam[i] * (1f - tSize * (1f - f))
            val a = l.alpha[i] * (1f - tOp * (1f - f))
            out.dab(l.x[i], l.y[i], max(d, 0.3f), l.aspect[i], l.angle[i], a, l.color[i], l.flags[i])
            if (simple && f < 1f && i + 1 < end) {
                // Abstand im Verhältnis zur (verjüngten) Größe beibehalten – nicht dichter als im übrigen Strich,
                // sonst wird die Spitze bei lasierenden/körnigen Pinseln dunkler
                val gap = hypot(l.x[i + 1] - l.x[i], l.y[i + 1] - l.y[i])
                val rel = min(gap / max(l.diam[i], 0.3f), max(spacing, 0.01f))
                val step = max(0.3f, rel * max(d, 0.3f))
                if (gap > step * 1.5f) {
                    val k = min((gap / step).roundToInt() - 1, 64)
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
        if (extX == null || extY == null || extN <= 0 || l.size == 0 || end < l.size) return
        // Fortsetzung (Kopf + Vorhersage) – nur Vorschau, wird nie aufgezeichnet
        val i = l.size - 1
        val len = extensionLength(extX, extY, extN)
        if (len < 0.5f) return
        val d0 = l.diam[i]; val asp = l.aspect[i]; val ang = l.angle[i]
        val a0 = l.alpha[i]; val col = l.color[i]; val fl = l.flags[i]
        val frac = (lastStep / max(d0, 0.3f)).coerceAtLeast(0.001f)
        val minStep = max(0.3f, len / MAX_EXTENSION_DABS)
        var ax = l.x[i]; var ay = l.y[i]
        var walked = 0f
        var next = max(minStep, frac * d0)
        for (k in 0 until extN) {
            val bx = extX[k]; val by = extY[k]
            val seg = hypot(bx - ax, by - ay)
            while (seg > 0f && next <= walked + seg) {
                val u = (next - walked) / seg
                val f = if (useTaper) endFactor(total, l.dist[i] + next) else 1f
                val d = max(d0 * (1f - tSize * (1f - f)), 0.3f)
                out.dab(ax + (bx - ax) * u, ay + (by - ay) * u, d, asp, ang, a0 * (1f - tOp * (1f - f)), col, fl)
                next += max(minStep, frac * (if (simple) d else d0))
            }
            walked += seg
            ax = bx; ay = by
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
        prevPressD = -1f; prevPressDist = 0f
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
        // Stift → Druck-Verjüngung, Finger → Touch-Verjüngung
        val tStart: Float; var tEnd: Float; val tTip: Float
        if (stylus) {
            tStart = b[BP.TAPER_START]; tEnd = b[BP.TAPER_END]; tTip = b[BP.TAPER_TIP]
            if (b.on(BP.TAPER_LINK)) tEnd = tStart
            taperSizeAmt = b[BP.TAPER_SIZE]; taperOpAmt = b[BP.TAPER_OPACITY]; taperPressAmt = b[BP.TAPER_PRESSURE]
        } else {
            tStart = b[BP.TOUCH_TAPER_START]; tEnd = b[BP.TOUCH_TAPER_END]; tTip = b[BP.TOUCH_TAPER_TIP]
            if (b.on(BP.TOUCH_TAPER_LINK)) tEnd = tStart
            taperSizeAmt = b[BP.TOUCH_TAPER_SIZE]; taperOpAmt = b[BP.TOUCH_TAPER_OPACITY]; taperPressAmt = 0f
        }
        taperOn = tStart > 0f || tEnd > 0f
        val base = diameter * 8f + 150f * detailScale
        taperStartLen = tStart * base
        taperEndLen = tEnd * base
        taperExp = 1.6f + (0.45f - 1.6f) * tTip
        expr = b[BP.MOTION_FILTER_EXPR]
        linearMix = b.on(BP.LUMINANCE_BLEND)
        continuous = b[BP.SPACING] <= 0.25f && b[BP.JITTER] <= 0.001f && b[BP.JITTER_LONG] <= 0.001f &&
            b[BP.SCATTER] <= 0.001f && b.int(BP.COUNT) <= 1 && b.int(BP.ROTATION_MODE) != RotationMode.RANDOM
        strokeRotOffset = if (b.on(BP.SHAPE_RANDOMIZE)) rand() * 2f * PI.toFloat() else 0f
        val s = b[BP.STREAMLINE]
        streamK = 1f - s.pow(0.6f) * 0.93f
        stabWindow = (1 + ((b[BP.STABILIZE] + b[BP.MOTION_FILTER] * 0.5f + globalMotionFilter * 0.5f) * 24f).roundToInt())
            .coerceIn(1, stabX.size)

        // Strich-Farbstreuung
        var c = color
        if (b[BP.STROKE_HUE_JITTER] > 0f || b[BP.STROKE_BRIGHT_JITTER] > 0f || b[BP.STROKE_SAT_JITTER] > 0f ||
            b[BP.STROKE_DARK_JITTER] > 0f) {
            Color.colorToHSV(c, hsv)
            hsv[0] = (hsv[0] + (rand() - 0.5f) * 2f * b[BP.STROKE_HUE_JITTER] * 180f + 360f) % 360f
            hsv[1] = (hsv[1] + (rand() - 0.5f) * 2f * b[BP.STROKE_SAT_JITTER]).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] * (1f + (rand() - 0.5f) * 2f * b[BP.STROKE_BRIGHT_JITTER])).coerceIn(0f, 1f)
            hsv[2] = hsv[2] * (1f - b[BP.STROKE_DARK_JITTER] * rand())
            c = Color.HSVToColor(hsv)
        }
        if (b[BP.STROKE_SECONDARY_JITTER] > 0f) c = mixC(c, secondaryColor, b[BP.STROKE_SECONDARY_JITTER] * rand())
        strokeColor = c
        carR = Color.red(c).toFloat(); carG = Color.green(c).toFloat(); carB = Color.blue(c).toFloat()
    }

    /**
     * Zeitbasierte Druckglättung (unabhängig von der Abtastrate des Stifts). [ps] 0..1 ergibt eine Zeitkonstante
     * bis [PRESSURE_TAU_MS]. Kleine Abweichungen (Digitizer-Rauschen, Quantisierung) werden voll geglättet; große
     * Abweichungen – gewollter Druckaufbau oder -abbau – verkürzen die Zeitkonstante, damit der Strich nicht träge
     * auf den Druck reagiert (einzelne Ausreißer hat der Median-Vorfilter da bereits entfernt).
     */
    private fun filterPressure(prev: Float, p: Float, dtMs: Float, ps: Float): Float {
        if (ps <= 0.001f) return p
        val tau = ps * PRESSURE_TAU_MS
        val e = abs(p - prev)
        val u = ((e - 0.06f) / 0.24f).coerceIn(0f, 1f)
        val tauEff = tau * (1f - 0.8f * u * u * (3f - 2f * u))
        val a = 1f - exp(-dtMs / tauEff.coerceAtLeast(0.5f))
        return prev + (p - prev) * a
    }

    private fun inputPressure(raw: Float): Float {
        if (stylus) return brush.mapPressure(raw.coerceIn(0.01f, 1f)).coerceIn(0.01f, 1f)
        if (brush.on(BP.FINGER_PRESSURE)) {
            val sn = (speed / 4f).coerceIn(0f, 1f)
            return brush.mapPressure((1.1f - sn * 0.85f).coerceIn(0.2f, 1f)).coerceIn(0.01f, 1f)
        }
        return 1f
    }

    /** Parameterwert mit Druck-/Neigungskopplung (für Parameter mit [BP.dyn]). */
    private fun m(p: BP, pr: Float, tiltT: Float): Float {
        val b = brush
        val v = b[p]
        val pc = b.couplings[p.ordinal * 2]; val tc = b.couplings[p.ordinal * 2 + 1]
        if (pc == 0f && tc == 0f) return v
        val f = (1f - pc * (1f - pr)) * (1f - tc * tiltT)
        return (v * f).coerceIn(p.min, p.max)
    }

    private fun mixC(a: Int, b: Int, t: Float): Int = if (linearMix) mixLinear(a, b, t) else mix(a, b, t)

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

        // Verjüngung (Druckkopplung: stärkerer Druck schwächt die Verjüngung ab)
        var tf = 1f
        if (taperOn && !closedShape) {
            if (taperStartLen > 0f) tf = min(tf, (distAlong / taperStartLen).coerceIn(0f, 1f).pow(taperExp))
            if (knownTotalLength > 0f) tf = min(tf, endFactor(knownTotalLength, distAlong))
            if (taperPressAmt > 0f) tf += (1f - tf) * taperPressAmt * p
        }

        // Größe: druckabhängiger Anteil mit begrenzter Änderungsrate entlang des Strichs – einzelne
        // Ausreißer im Stiftdruck erzeugen so keine sichtbaren Sprünge an der Strichkante
        var d = diameter * (1f - b[BP.PRESSURE_SIZE] * (1f - p))
        if (prevPressD >= 0f) {
            val maxStep = MAX_WIDTH_SLOPE * (distAlong - prevPressDist) + 0.002f * diameter
            d = d.coerceIn(prevPressD - maxStep, prevPressD + maxStep)
        }
        prevPressD = d; prevPressDist = distAlong
        d *= 1f + b[BP.TILT_SIZE] * tiltT
        d *= signedSpeed(b[BP.SPEED_SIZE], speedNorm).coerceAtLeast(0.05f)
        d *= 1f - taperSizeAmt * (1f - tf)
        d = max(d, 0.3f)

        // Deckkraft / Fluss
        var a = flowEff * (m(BP.FLOW, p, tiltT) / b[BP.FLOW].coerceAtLeast(0.01f))
        a *= 1f - b[BP.PRESSURE_OPACITY] * (1f - p)
        a *= 1f - b[BP.PRESSURE_FLOW] * (1f - p)
        a *= 1f - b[BP.TILT_OPACITY] * tiltT
        a *= 1f - 0.6f * b[BP.TILT_GRADATION] * tiltT
        a *= signedSpeed(b[BP.SPEED_OPACITY], speedNorm).coerceAtLeast(0f)
        a *= 1f - taperOpAmt * (1f - tf)
        val distL = distAlong / detailScale
        if (b[BP.FALLOFF] > 0f) a *= 1f - b[BP.FALLOFF] * min(1f, distL / 1500f)
        if (b[BP.CHARGE] < 0.999f) a *= exp(-distL / (b[BP.CHARGE] * 4000f + 60f))
        a *= 1f - 0.85f * m(BP.DILUTION, p, tiltT)
        a *= m(BP.ATTACK, p, tiltT)
        if (heavy) a *= opacity

        // Abstand (Streuung vergrößert nur – der Mindestabstand bleibt)
        var sp = m(BP.SPACING, p, tiltT) * signedSpeed(-b[BP.SPEED_SPACING], speedNorm).coerceAtLeast(0.1f)
        val sj = m(BP.SPACING_JITTER, p, tiltT)
        if (sj > 0f) sp *= 1f + 2f * sj * rand()
        // Harte runde Spitze ohne Streuung: Abstand begrenzen, damit die Strichkante auch bei
        // starkem Zoom glatt ist und keine "Perlenkette" aus einzelnen Tupfern zeigt
        if (sj <= 0f && b[BP.SHAPE_KIND].toInt() == ShapeKind.HARD.ordinal && b[BP.JITTER] <= 0.001f) sp = sp.coerceAtMost(0.035f)
        // Mindest-Tupferdichte für durchgehende Striche (keine Streuung, kein bewusster Stempelabstand):
        // auch "Tempo → Abstand" darf die Kante nicht in einzelne Tupfer zerfallen lassen
        if (continuous && sj <= 0f) sp = sp.coerceAtMost(MAX_CONTINUOUS_SPACING)
        distToNext = max(0.3f, sp * d)
        lastStep = distToNext
        headDiameter = d

        // Farbe: Druck/Neigung → Farbton/Sättigung/Helligkeit/Sekundärfarbe
        var col = strokeColor
        val ph = b[BP.PRESSURE_HUE] * (1f - p) + b[BP.TILT_HUE] * tiltT
        val psat = b[BP.PRESSURE_SAT] * (1f - p) + b[BP.TILT_SAT] * tiltT
        val pb = b[BP.PRESSURE_BRIGHT] * (1f - p) + b[BP.TILT_BRIGHT] * tiltT
        if (ph > 0f || psat > 0f || pb > 0f) {
            Color.colorToHSV(col, hsv)
            hsv[0] = (hsv[0] + ph * 180f) % 360f
            hsv[1] = (hsv[1] * (1f - psat)).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] * (1f - pb)).coerceIn(0f, 1f)
            col = Color.HSVToColor(hsv)
        }
        val cp = b[BP.COLOR_PRESSURE] * (1f - p) + b[BP.COLOR_TILT] * tiltT
        if (cp > 0f) col = mixC(col, secondaryColor, cp.coerceIn(0f, 1f))
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
            col = mixC(col, carried, pullEff)
        }

        // Körnungstiefe (Minimum über Druck, Streuung, Ausbluten, Körnigkeit)
        val hasGrain = b.grainImage != null || b.int(BP.GRAIN_KIND) > 0
        val depthBase = if (hasGrain) m(BP.GRAIN_DEPTH, p, tiltT) else 0f
        val depthMin = b[BP.GRAIN_DEPTH_MIN]
        val depthJ = b[BP.GRAIN_DEPTH_JITTER]
        val bleed = b[BP.PRESSURE_BLEED] * (1f - p) + b[BP.TILT_BLEED] * tiltT + b[BP.GRADE] * 0.5f
        val customDepth = hasGrain && (depthMin < 0.999f || depthJ > 0f || bleed > 0f ||
            b.pressureCoupling(BP.GRAIN_DEPTH) != 0f || b.tiltCoupling(BP.GRAIN_DEPTH) != 0f)

        // Unschärfe der Form
        val blurAmt = m(BP.WET_BLUR, p, tiltT)
        val blurJ = b[BP.WET_BLUR_JITTER]

        // Stempel
        val baseCount = b.int(BP.COUNT).coerceAtLeast(1)
        val count = baseCount + (rand() * b[BP.COUNT_JITTER] * baseCount).toInt()
        val jitter = m(BP.JITTER, p, tiltT)
        val jitterLong = m(BP.JITTER_LONG, p, tiltT)
        val rotMode = b.int(BP.ROTATION_MODE)
        val baseRot = Math.toRadians(m(BP.ROTATION, p, tiltT).toDouble()).toFloat() + strokeRotOffset -
            (if (b.on(BP.ORIENT_SCREEN)) viewRotation else 0f)
        val scatter = m(BP.SCATTER, p, tiltT)
        val tiltRound = b[BP.TILT_ROUNDNESS]
        val roundBase = m(BP.ROUNDNESS, p, tiltT) * (1f - b[BP.ROUNDNESS_PRESSURE] * (1f - p))
        val aspect0 = (roundBase * (1f - tiltRound * tiltT * 0.7f)).coerceIn(0.03f, 1f)
        val roundJ = b[BP.ROUNDNESS_JITTER]
        // Schraffieren mit geneigtem Stift: Bei runden Spitzen mit "Neigung → Rundheit" liegt die lange Achse der
        // abgeflachten Spitze entlang der Stiftausrichtung (wie die Mine eines flach gehaltenen Bleistifts).
        val tiltOriented = tiltRound > 0f && tiltT > 0f && b[BP.ROUNDNESS] > 0.95f && rotMode == RotationMode.FIXED
        val hj = m(BP.HUE_JITTER, p, tiltT); val sjc = m(BP.SAT_JITTER, p, tiltT); val bj = m(BP.BRIGHT_JITTER, p, tiltT)
        val dj0 = m(BP.DARK_JITTER, p, tiltT); val secj = m(BP.SECONDARY_JITTER, p, tiltT)
        val sizeJ = m(BP.SIZE_JITTER, p, tiltT); val opJ = m(BP.OPACITY_JITTER, p, tiltT)
        val cosD = kotlin.math.cos(dir); val sinD = kotlin.math.sin(dir)
        for (k in 0 until count) {
            var sx = x; var sy = y
            if (jitter > 0f) {
                val off = (rand() - 0.5f) * 2f * jitter * d
                sx += -sinD * off; sy += cosD * off
            }
            if (jitterLong > 0f) {
                val off = (rand() - 0.5f) * 2f * jitterLong * d
                sx += cosD * off; sy += sinD * off
            }
            var ang = when (rotMode) {
                RotationMode.FOLLOW -> dir + baseRot
                RotationMode.RANDOM -> rand() * 2f * PI.toFloat()
                RotationMode.AZIMUTH -> orient + baseRot
                else -> if (tiltOriented) orient + baseRot else baseRot
            }
            if (scatter > 0f) ang += (rand() - 0.5f) * 2f * PI.toFloat() * scatter
            val dj = d * (1f - sizeJ * rand())
            val aj = a * (1f - opJ * rand())
            val asp = if (roundJ > 0f) (aspect0 * (1f - roundJ * rand())).coerceIn(0.03f, 1f) else aspect0
            val depth = if (customDepth) {
                (depthBase * (depthMin + (1f - depthMin) * p) * (1f - depthJ * rand()) + bleed).coerceIn(0f, 1f)
            } else -1f
            val blurLevel = if (blurAmt > 0f) ((blurAmt * (1f - blurJ * rand())) * 4f + 0.5f).toInt() else 0
            val flags = DabPainter.encodeFlags(
                b.on(BP.FLIP_X) && rand() < 0.5f, b.on(BP.FLIP_Y) && rand() < 0.5f, depth, blurLevel,
            )
            var c = col
            if (hj > 0f || sjc > 0f || bj > 0f || dj0 > 0f) {
                Color.colorToHSV(c, hsv)
                hsv[0] = (hsv[0] + (rand() - 0.5f) * 2f * hj * 180f + 360f) % 360f
                hsv[1] = (hsv[1] + (rand() - 0.5f) * 2f * sjc).coerceIn(0f, 1f)
                hsv[2] = (hsv[2] + (rand() - 0.5f) * 2f * bj).coerceIn(0f, 1f)
                hsv[2] = hsv[2] * (1f - dj0 * rand())
                c = Color.HSVToColor(hsv)
            }
            if (secj > 0f) c = mixC(c, secondaryColor, secj * rand())
            dabs.add(sx, sy, max(dj, 0.3f), asp, ang, aj, c, flags, distAlong)
            sink?.dab(sx, sy, max(dj, 0.3f), asp, ang, aj, c, flags)
        }
    }

    /** Tempo-Dynamik: positiv = schneller verringert, negativ = langsamer verringert. */
    private fun signedSpeed(amount: Float, speedNorm: Float): Float =
        if (amount >= 0f) 1f - amount * speedNorm else 1f + amount * (1f - speedNorm)

    private fun rand(): Float {
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
        rng = x
        return ((x ushr 40) and 0xFFFFFF).toFloat() / 16777216f
    }

    companion object {
        /** Zeitkonstante der Druckglättung bei 100 % (ms). */
        const val PRESSURE_TAU_MS = 80f
        /** Größte Breitenänderung des druckabhängigen Durchmessers pro Pixel Strichlänge (Kantenwinkel ≈ 14°). */
        const val MAX_WIDTH_SLOPE = 1.0f
        /** Größter Tupferabstand (Anteil des Durchmessers) für durchgehende Striche. */
        const val MAX_CONTINUOUS_SPACING = 0.1f
        /** Höchstzahl der Vorschau-Tupfer der Fortsetzung (Kopf + Vorhersage). */
        private const val MAX_EXTENSION_DABS = 300f
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

        /** Lichtrichtiges Mischen in linearem RGB (Luminanz-Mischen). */
        fun mixLinear(a: Int, b: Int, t: Float): Int {
            fun lin(c: Int) = (c / 255f).let { it * it }
            fun srgb(v: Float) = (kotlin.math.sqrt(v.coerceIn(0f, 1f)) * 255f + 0.5f).toInt()
            val u = 1f - t
            return Color.argb(
                255,
                srgb(lin(Color.red(a)) * u + lin(Color.red(b)) * t),
                srgb(lin(Color.green(a)) * u + lin(Color.green(b)) * t),
                srgb(lin(Color.blue(a)) * u + lin(Color.blue(b)) * t),
            )
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
