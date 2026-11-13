package at.techgaisi.farbwerk.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Prozedural erzeugte Pinselformen. */
enum class ShapeKind(val label: String) {
    HARD("Rund hart"),
    MEDIUM("Rund mittel"),
    SOFT("Rund weich"),
    AIR("Airbrush"),
    INK("Tinte"),
    PENCIL("Graphit"),
    CHALK("Kreide"),
    CHARCOAL("Kohle"),
    BRISTLE("Borsten"),
    FLAT("Flachpinsel"),
    SPLATTER("Spritzer"),
    SPONGE("Schwamm"),
    CLOUD("Wolke"),
    SPARKLE("Funkeln"),
    LEAF("Blatt"),
    SQUARE("Quadrat"),
    DRYBRUSH("Trockenpinsel"),
    // neue Formen nur hinten anhängen (Index wird gespeichert)
    GRASS("Halme"),
    STAR("Stern"),
    STREAK("Strich"),
    FLAME("Flamme"),
    RING("Ring"),
    STIPPLE("Punktwolke"),
    FUR("Fasern"),
    CHISEL("Keilspitze"),
    TRIANGLE("Dreieck"),
    BOKEH("Lichtscheibe"),
}

/** Prozedural erzeugte, nahtlos kachelbare Körnungen. */
enum class GrainKind(val label: String) {
    NONE("Keine"),
    PAPER("Papier"),
    CANVAS("Leinwand"),
    NOISE("Rauschen"),
    STREAKS("Kohlepapier"),
    WATERCOLOR("Aquarell"),
    CONCRETE("Beton"),
    HATCH("Schraffur"),
    HALFTONE("Raster"),
    // neue Körnungen nur hinten anhängen (Index wird gespeichert)
    WOOD("Holz"),
    CROSSHATCH("Kreuzschraffur"),
    LINES("Linien"),
    CELLS("Zellen"),
}

/** Rausch-Hilfsfunktionen (deterministisch, optional periodisch/kachelbar). */
internal object Noise {
    fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun wrap(i: Int, p: Int) = if (p <= 0) i else ((i % p) + p) % p

    /** Wertrauschen; [period] > 0 macht es in Gitterzellen-Einheiten periodisch. */
    fun value(x: Float, y: Float, period: Int, seed: Int): Float = value(x, y, period, period, seed)

    /** Wertrauschen mit getrennten Perioden je Achse. */
    fun value(x: Float, y: Float, periodX: Int, periodY: Int, seed: Int): Float {
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        val fx = x - ix; val fy = y - iy
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val x0 = wrap(ix, periodX); val x1 = wrap(ix + 1, periodX)
        val y0 = wrap(iy, periodY); val y1 = wrap(iy + 1, periodY)
        val a = hash(x0, y0, seed); val b = hash(x1, y0, seed)
        val c = hash(x0, y1, seed); val d = hash(x1, y1, seed)
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy
    }

    /** Fraktales Rauschen, Ergebnis ca. 0..1. */
    fun fbm(x: Float, y: Float, period: Int, octaves: Int, seed: Int): Float = fbm(x, y, period, period, octaves, seed)

    /** Fraktales Rauschen mit getrennten Perioden je Achse (0 = nicht periodisch). */
    fun fbm(x: Float, y: Float, periodX: Int, periodY: Int, octaves: Int, seed: Int): Float {
        var sum = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
        for (o in 0 until octaves) {
            val fi = f.toInt()
            sum += amp * value(x * f, y * f, if (periodX > 0) periodX * fi else 0, if (periodY > 0) periodY * fi else 0, seed + o * 31)
            norm += amp; amp *= 0.5f; f *= 2f
        }
        return sum / norm
    }
}

/**
 * Erzeugt und cacht Formen (ALPHA_8, [SHAPE_SIZE]²) und Körnungen (ALPHA_8, [GRAIN_SIZE]², kachelbar).
 * Thread-sicher (Vorschauen werden im Hintergrund gerendert).
 */
object BrushTextures {
    const val SHAPE_SIZE = 256
    const val GRAIN_SIZE = 256

    private val shapes = arrayOfNulls<Bitmap>(ShapeKind.entries.size)
    private val grains = HashMap<Int, Bitmap>()
    private val thumbs = HashMap<String, Bitmap>()

    @Synchronized
    fun shape(index: Int): Bitmap {
        val i = index.coerceIn(0, ShapeKind.entries.size - 1)
        shapes[i]?.let { return it }
        val b = toAlpha8(generateShape(ShapeKind.entries[i]), SHAPE_SIZE)
        shapes[i] = b
        return b
    }

    /** Körnung mit eingerechneter Tiefe (Alpha = 1 − Tiefe·(1 − g)). null = keine Körnung. */
    @Synchronized
    fun grain(index: Int, depth: Float, invert: Boolean): Bitmap? {
        if (index <= 0 || index >= GrainKind.entries.size || depth <= 0.001f) return null
        val dq = (depth.coerceIn(0f, 1f) * 50).toInt()
        val key = index * 1000 + dq * 2 + (if (invert) 1 else 0)
        grains[key]?.let { return it }
        val base = generateGrain(GrainKind.entries[index])
        val d = dq / 50f
        for (k in base.indices) {
            val g = if (invert) 1f - base[k] else base[k]
            base[k] = 1f - d * (1f - g)
        }
        val b = toAlpha8(base, GRAIN_SIZE)
        if (grains.size > 24) grains.clear()
        grains[key] = b
        return b
    }

    /** Weiße ARGB-Vorschau einer Form oder Körnung für die Oberfläche. */
    @Synchronized
    fun thumbnail(grain: Boolean, index: Int, size: Int = 96): Bitmap {
        val key = "${if (grain) "g" else "s"}$index@$size"
        thumbs[key]?.let { return it }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val src = if (grain) grain(index, 1f, false) else shape(index)
        if (src != null) {
            val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.WHITE }
            Canvas(out).drawBitmap(src, null, android.graphics.Rect(0, 0, size, size), p)
        }
        thumbs[key] = out
        return out
    }

    private fun toAlpha8(a: FloatArray, n: Int): Bitmap {
        val bytes = ByteArray(n * n)
        for (i in bytes.indices) bytes[i] = (a[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
        if (b.rowBytes == n) {
            b.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        } else {
            // Zeilen-Padding berücksichtigen
            val buf = ByteBuffer.allocate(b.rowBytes * n)
            for (y in 0 until n) { buf.position(y * b.rowBytes); buf.put(bytes, y * n, n) }
            buf.rewind(); b.copyPixelsFromBuffer(buf)
        }
        return b
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    // ------------------------------------------------------------------ Formen

    private fun generateShape(kind: ShapeKind): FloatArray {
        val n = SHAPE_SIZE
        val out = FloatArray(n * n)
        val h = n / 2f
        val px = 1f / h // ein Pixel in normierten Koordinaten
        // vorab: zufällige Elemente für zusammengesetzte Formen
        val dots: List<FloatArray> = when (kind) {
            ShapeKind.BRISTLE -> List(46) { i ->
                val a = Noise.hash(i, 1, 7) * 2 * PI.toFloat(); val r = sqrt(Noise.hash(i, 2, 7))
                floatArrayOf(cos(a) * r * 0.82f, sin(a) * r * 0.55f, 0.07f + 0.08f * Noise.hash(i, 3, 7), 0.5f + 0.5f * Noise.hash(i, 4, 7))
            }
            ShapeKind.SPLATTER -> List(38) { i ->
                val a = Noise.hash(i, 1, 9) * 2 * PI.toFloat(); val r = Noise.hash(i, 2, 9).pow(0.8f) * 0.85f
                floatArrayOf(cos(a) * r, sin(a) * r, 0.025f + 0.16f * Noise.hash(i, 3, 9).pow(3f) * (1.1f - r), 1f)
            }
            ShapeKind.CLOUD -> List(9) { i ->
                val a = Noise.hash(i, 1, 13) * 2 * PI.toFloat(); val r = Noise.hash(i, 2, 13) * 0.45f
                floatArrayOf(cos(a) * r, sin(a) * r * 0.8f, 0.3f + 0.22f * Noise.hash(i, 3, 13), 1f)
            }
            ShapeKind.DRYBRUSH -> List(60) { i ->
                floatArrayOf(-0.9f + 1.8f * Noise.hash(i, 1, 21), 0f, 0.01f + 0.035f * Noise.hash(i, 2, 21), 0.4f + 0.6f * Noise.hash(i, 3, 21))
            }
            ShapeKind.GRASS -> List(9) { i ->
                // Basis-x, Spitzen-x, Höhe, Breite
                val bx = -0.55f + 1.1f * Noise.hash(i, 1, 41)
                floatArrayOf(bx, bx + (Noise.hash(i, 2, 41) - 0.5f) * 0.9f, 0.9f + 0.95f * Noise.hash(i, 3, 41), 0.04f + 0.04f * Noise.hash(i, 4, 41))
            }
            ShapeKind.STIPPLE -> List(70) { i ->
                val a = Noise.hash(i, 1, 43) * 2 * PI.toFloat(); val r = sqrt(Noise.hash(i, 2, 43)) * 0.85f
                floatArrayOf(cos(a) * r, sin(a) * r, 0.025f + 0.035f * Noise.hash(i, 3, 43), 0.6f + 0.4f * Noise.hash(i, 4, 43))
            }
            ShapeKind.FUR -> List(40) { i ->
                val y0 = -0.9f + 0.9f * Noise.hash(i, 2, 47)
                floatArrayOf(-0.85f + 1.7f * Noise.hash(i, 1, 47), y0, y0 + 0.6f + 0.9f * Noise.hash(i, 3, 47), 0.5f + 0.5f * Noise.hash(i, 4, 47))
            }
            else -> emptyList()
        }
        for (y in 0 until n) for (x in 0 until n) {
            val u = (x + 0.5f - h) / h
            val v = (y + 0.5f - h) / h
            val r = sqrt(u * u + v * v)
            val a: Float = when (kind) {
                ShapeKind.HARD -> ((1f - r) / px).coerceIn(0f, 1f)
                ShapeKind.MEDIUM -> 1f - smooth(0.62f, 1f, r)
                ShapeKind.SOFT -> { val q = (1f - r * r).coerceAtLeast(0f); q * q }
                ShapeKind.AIR -> if (r >= 1f) 0f else ((exp(-4.5f * r * r) - exp(-4.5f)) / (1f - exp(-4.5f)))
                ShapeKind.INK -> {
                    val ang = atan2(v, u)
                    val rr = 0.95f - 0.05f * Noise.value((ang / (2 * PI.toFloat()) + 0.5f) * 14f, 0f, 14, 3) -
                        0.025f * Noise.value((ang / (2 * PI.toFloat()) + 0.5f) * 40f, 3f, 40, 4)
                    ((rr - r) / px).coerceIn(0f, 1f)
                }
                ShapeKind.PENCIL -> {
                    val base = 1f - smooth(0.7f, 1f, r)
                    val g = Noise.value(x * 0.9f, y * 0.9f, 0, 5)
                    base * (0.35f + 0.65f * smooth(0.25f, 0.75f, g))
                }
                ShapeKind.CHALK -> {
                    val edge = 1f - smooth(0.7f, 0.95f, r + 0.22f * (Noise.fbm(u * 3f + 5f, v * 3f + 5f, 0, 3, 11) - 0.5f))
                    val holes = smooth(0.3f, 0.6f, Noise.fbm(x / 7f, y / 7f, 0, 3, 12))
                    edge * (0.25f + 0.75f * holes)
                }
                ShapeKind.CHARCOAL -> {
                    val f = Noise.fbm(u * 2.5f + 9f, v * 2.5f + 9f, 0, 4, 17)
                    val blob = ((0.88f - r + 0.4f * (f - 0.5f)) * 5f).coerceIn(0f, 1f)
                    val fine = Noise.value(x * 0.7f, y * 0.25f, 0, 18)
                    blob * (0.3f + 0.7f * smooth(0.2f, 0.7f, fine))
                }
                ShapeKind.BRISTLE -> {
                    var s = 0f
                    for (d in dots) {
                        val dd = sqrt((u - d[0]) * (u - d[0]) + (v - d[1]) * (v - d[1]))
                        s += (1f - smooth(d[2] * 0.4f, d[2], dd)) * d[3]
                    }
                    min(1f, s)
                }
                ShapeKind.FLAT -> {
                    val inside = (1f - smooth(0.24f, 0.32f, abs(v))) * (1f - smooth(0.85f, 0.97f, abs(u)))
                    val streak = 0.55f + 0.45f * Noise.value(0.5f, (v + 1f) * 40f, 0, 23)
                    inside * streak
                }
                ShapeKind.SPLATTER -> {
                    var s = 0f
                    for (d in dots) {
                        val dd = sqrt((u - d[0]) * (u - d[0]) + (v - d[1]) * (v - d[1]))
                        s = max(s, ((d[2] - dd) / px).coerceIn(0f, 1f))
                    }
                    s
                }
                ShapeKind.SPONGE -> {
                    val base = 1f - smooth(0.6f, 1f, r)
                    val pores = smooth(0.38f, 0.55f, Noise.fbm(x / 9f, y / 9f, 0, 3, 31))
                    base * pores
                }
                ShapeKind.CLOUD -> {
                    var s = 0f
                    for (d in dots) {
                        val dd = sqrt((u - d[0]) * (u - d[0]) + (v - d[1]) * (v - d[1])) / d[2]
                        s = max(s, (1f - dd * dd).coerceAtLeast(0f))
                    }
                    val f = 0.6f + 0.4f * Noise.fbm(u * 3f + 2f, v * 3f + 2f, 0, 4, 33)
                    (s * f * 1.3f).coerceIn(0f, 1f) * (1f - smooth(0.9f, 1f, r))
                }
                ShapeKind.SPARKLE -> {
                    val au = abs(u); val av = abs(v)
                    val ray = max(exp(-av * 28f) * (1f - au), exp(-au * 28f) * (1f - av))
                    val diag = 0.45f * max(exp(-abs(u - v) * 30f), exp(-abs(u + v) * 30f)) * (1f - r).coerceAtLeast(0f)
                    val glow = exp(-r * r * 30f)
                    (ray + diag + glow).coerceIn(0f, 1f) * (1f - smooth(0.92f, 1f, r))
                }
                ShapeKind.LEAF -> {
                    val w = 0.42f * (1f - u * u).coerceAtLeast(0f).pow(0.9f) * (1f + 0.15f * u)
                    val leaf = ((w - abs(v)) / px).coerceIn(0f, 1f) * (if (abs(u) < 0.98f) 1f else 0f)
                    val vein = 1f - 0.45f * exp(-abs(v) / (px * 1.5f)) * (1f - smooth(0.7f, 0.95f, abs(u)))
                    leaf * vein
                }
                ShapeKind.SQUARE -> min(((0.9f - abs(u)) / px).coerceIn(0f, 1f), ((0.9f - abs(v)) / px).coerceIn(0f, 1f))
                ShapeKind.DRYBRUSH -> {
                    var s = 0f
                    for (d in dots) {
                        val dd = abs(u - d[0])
                        s = max(s, (1f - smooth(d[2] * 0.5f, d[2], dd)) * d[3])
                    }
                    val lenMask = 1f - smooth(0.25f + 0.2f * Noise.value(u * 30f, 0f, 0, 22), 0.5f, abs(v))
                    s * lenMask * (1f - smooth(0.88f, 0.98f, abs(u)))
                }
                ShapeKind.GRASS -> {
                    var s = 0f
                    for (d in dots) {
                        val t = (0.95f - v) / d[2] // 0 = Fuß, 1 = Spitze
                        if (t < 0f || t > 1f) continue
                        val cx = d[0] + (d[1] - d[0]) * t * t
                        val w = d[3] * (1f - t)
                        s = max(s, ((w - abs(u - cx)) / px).coerceIn(0f, 1f))
                    }
                    s
                }
                ShapeKind.STAR -> {
                    val ang = atan2(v, u) + PI.toFloat() / 2f
                    val rr = 0.4f + 0.52f * abs(cos(2.5f * ang)).pow(5f)
                    ((rr - r) / px).coerceIn(0f, 1f)
                }
                ShapeKind.STREAK -> (1f - smooth(0.025f, 0.07f, abs(u))) * (1f - smooth(0.55f, 0.95f, abs(v))) * (0.7f + 0.3f * (1f - abs(v)))
                ShapeKind.FLAME -> {
                    // Spitze oben, runder Bauch unten
                    val t = ((v + 0.92f) / 1.37f).coerceIn(0f, 1f)
                    val w = if (v < 0.45f) 0.42f * t.pow(0.75f) else sqrt((0.42f * 0.42f - (v - 0.45f) * (v - 0.45f)).coerceAtLeast(0f))
                    val sway = 0.08f * sin(v * 5f) * (1f - t)
                    val body = 1f - smooth(w - 0.08f, w + 0.02f, abs(u - sway))
                    body * (0.55f + 0.45f * t) * (if (v < -0.95f) 0f else 1f)
                }
                ShapeKind.RING -> 1f - smooth(0.07f, 0.13f, abs(r - 0.72f))
                ShapeKind.STIPPLE -> {
                    var s = 0f
                    for (d in dots) {
                        val dd = sqrt((u - d[0]) * (u - d[0]) + (v - d[1]) * (v - d[1]))
                        s = max(s, ((d[2] - dd) / px).coerceIn(0f, 1f) * d[3])
                    }
                    s
                }
                ShapeKind.FUR -> {
                    var s = 0f
                    for (d in dots) {
                        if (v < d[1] || v > d[2]) continue
                        val t = (v - d[1]) / (d[2] - d[1])
                        val cx = d[0] + 0.06f * sin(t * 3f + d[3] * 6f)
                        s = max(s, (1f - smooth(0.006f, 0.02f, abs(u - cx))) * d[3] * sin(t * PI.toFloat()).pow(0.5f))
                    }
                    s
                }
                ShapeKind.CHISEL -> {
                    val edge = (1f - smooth(0.78f, 0.86f, abs(u))) * (1f - smooth(0.26f, 0.32f, abs(v)))
                    edge * (0.82f + 0.18f * Noise.value(u * 3f, (v + 1f) * 30f, 0, 49))
                }
                ShapeKind.TRIANGLE -> {
                    // gleichseitig, Spitze oben
                    val d1 = v - 0.62f
                    val d2 = -0.866f * u - 0.5f * v - 0.62f
                    val d3 = 0.866f * u - 0.5f * v - 0.62f
                    ((-max(d1, max(d2, d3))) / px).coerceIn(0f, 1f)
                }
                ShapeKind.BOKEH -> ((0.86f - r) / (px * 2f)).coerceIn(0f, 1f) * (0.5f + 0.5f * smooth(0.55f, 0.84f, r))
            }
            out[y * n + x] = a
        }
        return out
    }

    // ------------------------------------------------------------------ Körnungen

    private fun generateGrain(kind: GrainKind): FloatArray {
        val n = GRAIN_SIZE
        val out = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val fx = x.toFloat(); val fy = y.toFloat()
            val g: Float = when (kind) {
                GrainKind.NONE -> 1f
                GrainKind.PAPER -> {
                    val f = Noise.fbm(fx / 8f, fy / 8f, n / 8, 4, 101)
                    smooth(0.3f, 0.7f, f) * 0.85f + 0.15f * Noise.value(fx / 2f, fy / 2f, n / 2, 102)
                }
                GrainKind.CANVAS -> {
                    val wx = 0.5f + 0.5f * sin(fx * 2f * PI.toFloat() / 8f)
                    val wy = 0.5f + 0.5f * sin(fy * 2f * PI.toFloat() / 8f)
                    val checker = if (((x / 8) + (y / 8)) % 2 == 0) wx else wy
                    (0.25f + 0.6f * checker + 0.25f * Noise.fbm(fx / 4f, fy / 4f, n / 4, 2, 103)).coerceIn(0f, 1f)
                }
                GrainKind.NOISE -> smooth(0.15f, 0.85f, Noise.value(fx / 2f, fy / 2f, n / 2, 104))
                GrainKind.STREAKS -> {
                    val f = Noise.fbm(fx / 32f, fy / 4f, n / 32, n / 4, 3, 105)
                    smooth(0.35f, 0.65f, f)
                }
                GrainKind.WATERCOLOR -> {
                    val f = Noise.fbm(fx / 32f, fy / 32f, n / 32, 5, 106)
                    0.35f + 0.65f * smooth(0.25f, 0.8f, f)
                }
                GrainKind.CONCRETE -> {
                    val f = Noise.fbm(fx / 16f, fy / 16f, n / 16, 4, 107)
                    val sp = if (Noise.hash(x, y, 108) > 0.93f) 0.3f else 1f
                    smooth(0.25f, 0.75f, f) * sp
                }
                GrainKind.HATCH -> {
                    val t = ((fx + fy) % 8f) / 8f
                    1f - smooth(0.0f, 0.18f, abs(t - 0.5f) - 0.2f)
                }
                GrainKind.HALFTONE -> {
                    val cx = (x % 8) - 3.5f; val cy = (y % 8) - 3.5f
                    1f - smooth(2.6f, 3.4f, sqrt(cx * cx + cy * cy))
                }
                GrainKind.WOOD -> {
                    val f = Noise.fbm(fx / 64f, fy / 8f, n / 64, n / 8, 3, 109)
                    val ring = 0.5f + 0.5f * sin(fy / n * 2f * PI.toFloat() * 14f + f * 9f)
                    0.3f + 0.7f * smooth(0.2f, 0.8f, ring) * (0.75f + 0.25f * Noise.value(fx / 2f, fy / 6f, n / 2, n / 6, 110))
                }
                GrainKind.CROSSHATCH -> {
                    val t1 = abs(((fx + fy) % 8f) / 8f - 0.5f)
                    val t2 = abs(((fx - fy + n) % 8f) / 8f - 0.5f)
                    val l1 = smooth(0.12f, 0.28f, t1); val l2 = smooth(0.12f, 0.28f, t2)
                    min(l1, l2) * 0.85f + 0.15f
                }
                GrainKind.LINES -> {
                    val t = abs((fy % 6f) / 6f - 0.5f)
                    smooth(0.15f, 0.3f, t) * (0.8f + 0.2f * Noise.value(fx / 16f, fy / 6f, n / 16, n / 6, 111))
                }
                GrainKind.CELLS -> {
                    // kachelbares Zellmuster (Abstand zur Zellgrenze)
                    val c = 32; val cells = n / c
                    val gx = x / c; val gy = y / c
                    var d1 = 1e9f; var d2 = 1e9f
                    for (oy in -1..1) for (ox in -1..1) {
                        val cx = gx + ox; val cy = gy + oy
                        val wx = ((cx % cells) + cells) % cells; val wy = ((cy % cells) + cells) % cells
                        val ppx = (cx + Noise.hash(wx, wy, 112)) * c; val ppy = (cy + Noise.hash(wx, wy, 113)) * c
                        val d = sqrt((fx - ppx) * (fx - ppx) + (fy - ppy) * (fy - ppy))
                        if (d < d1) { d2 = d1; d1 = d } else if (d < d2) d2 = d
                    }
                    smooth(0.5f, 4f, d2 - d1) * (0.7f + 0.3f * smooth(0f, 24f, d1))
                }
            }
            out[y * n + x] = g.coerceIn(0f, 1f)
        }
        return out
    }
}
