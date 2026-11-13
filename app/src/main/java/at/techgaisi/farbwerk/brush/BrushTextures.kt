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
    COMB("Haarbüschel"),
    SINE("Wellenlinie"),
    CRYSTAL("Kristall"),
    SNOWFLAKE("Schneeflocke"),
    DROP("Tropfen"),
    WAVE("Wellenkamm"),
    POLYGON("Vieleck"),
    SMOKE("Rauchfahne"),
    NEEDLES("Nadelzweig"),
    BLOSSOM("Blüte"),
    BAMBOO("Bambusrohr"),
    TWIG("Zweig"),
    REED("Schilf"),
    STAR4("Retrostern"),
    FLARE("Linsenreflex"),
    DRIP("Laufspur"),
    BURST("Explosion"),
    SCRATCHES("Kratzspuren"),
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
    GRID("Gitter"),
    DICE("Würfel"),
    ROSETTE("Rosette"),
    GRUNGE("Grunge"),
    RUST("Rost"),
    STONE("Stein"),
    CORRUGATED("Wellblech"),
    MARBLE("Marmor"),
    MEMBRANE("Membran"),
    COTTON("Baumwolle"),
    JUTE("Jute"),
    HEMP("Hanf"),
    FABRIC("Stoff"),
    CLAY("Ton"),
    PORES("Poren"),
    NEWSPRINT("Zeitungsraster"),
    DOTS("Punkte"),
    STRIPES("Streifen"),
    CHEVRON("Zickzack"),
    DIAGONAL("Diagonalstreifen"),
    SAND("Sand"),
    NET("Netz"),
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
            ShapeKind.COMB -> List(9) { i ->
                floatArrayOf((Noise.hash(i, 1, 51) - 0.5f) * 0.1f, -0.8f + 1.6f * i / 8f + (Noise.hash(i, 2, 51) - 0.5f) * 0.12f,
                    0.045f + 0.045f * Noise.hash(i, 3, 51), 0.45f + 0.55f * Noise.hash(i, 4, 51))
            }
            ShapeKind.CRYSTAL -> List(4) { i ->
                // Mitte x/y, Winkel, Länge, Breite
                val ang = -PI.toFloat() / 2f + (i - 1.5f) * 0.38f + (Noise.hash(i, 3, 53) - 0.5f) * 0.2f
                val len = 0.4f + 0.35f * Noise.hash(i, 4, 53)
                // Kristalle wachsen von einem gemeinsamen Fuß unten nach oben
                floatArrayOf(cos(ang) * len * 0.85f + (Noise.hash(i, 1, 53) - 0.5f) * 0.15f, 0.55f + sin(ang) * len * 0.85f,
                    ang, len, 0.11f + 0.06f * Noise.hash(i, 5, 53))
            }
            ShapeKind.POLYGON -> List(7) { i ->
                floatArrayOf(i * 2f * PI.toFloat() / 7f + (Noise.hash(i, 1, 55) - 0.5f) * 0.5f, 0.62f + 0.3f * Noise.hash(i, 2, 55))
            }
            ShapeKind.REED -> List(5) { i ->
                val bx = -0.35f + 0.7f * Noise.hash(i, 1, 57)
                floatArrayOf(bx, bx + (Noise.hash(i, 2, 57) - 0.3f) * 0.5f, 1.4f + 0.45f * Noise.hash(i, 3, 57), 0.025f + 0.02f * Noise.hash(i, 4, 57))
            }
            ShapeKind.BURST -> List(28) { i ->
                floatArrayOf(i * 2f * PI.toFloat() / 28f + (Noise.hash(i, 1, 59) - 0.5f) * 0.18f, 0.45f + 0.5f * Noise.hash(i, 2, 59),
                    0.04f + 0.05f * Noise.hash(i, 3, 59))
            }
            ShapeKind.SCRATCHES -> List(9) { i ->
                // Mitte u/v, Winkel, halbe Länge, Breite, Deckkraft
                floatArrayOf((Noise.hash(i, 1, 61) - 0.5f) * 0.6f, (Noise.hash(i, 2, 61) - 0.5f) * 1.5f, (Noise.hash(i, 3, 61) - 0.5f) * 0.35f,
                    0.3f + 0.6f * Noise.hash(i, 4, 61), 0.006f + 0.012f * Noise.hash(i, 5, 61), 0.45f + 0.55f * Noise.hash(i, 6, 61))
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
                ShapeKind.COMB -> {
                    var s = 0f
                    for (d in dots) {
                        val dd = sqrt((u - d[0]) * (u - d[0]) + (v - d[1]) * (v - d[1]))
                        s = max(s, (1f - smooth(d[2] * 0.5f, d[2], dd)) * d[3])
                    }
                    s
                }
                ShapeKind.SINE -> {
                    // eine volle Periode über die Breite – bei Abstand 100 % entsteht eine durchgehende Welle
                    val cy = 0.5f * sin(PI.toFloat() * u)
                    val dist = abs(v - cy) / sqrt(1f + (0.5f * PI.toFloat() * cos(PI.toFloat() * u)).pow(2))
                    ((0.07f - dist) / px).coerceIn(0f, 1f)
                }
                ShapeKind.CRYSTAL -> {
                    var s = 0f
                    for (d in dots) {
                        val c = cos(d[2]); val sn = sin(d[2])
                        val lu = (u - d[0]) * c + (v - d[1]) * sn
                        val lv = -(u - d[0]) * sn + (v - d[1]) * c
                        val inside = min((d[4] - abs(lv)) / px, (d[3] - abs(lu) - abs(lv) * 1.4f) / px).coerceIn(0f, 1f)
                        val facet = if (lv > 0f) 1f else 0.62f
                        val ridge = 1f - 0.3f * exp(-abs(lv) / (px * 1.5f))
                        s = max(s, inside * facet * ridge)
                    }
                    s
                }
                ShapeKind.SNOWFLAKE -> {
                    val ang = atan2(v, u)
                    val sec = PI.toFloat() / 3f
                    val a0 = Math.round(ang / sec) * sec
                    val lx = u * cos(a0) + v * sin(a0); val ly = abs(-u * sin(a0) + v * cos(a0))
                    var dist = if (lx in 0f..0.85f) ly else sqrt((lx - 0.85f).coerceAtLeast(0f).pow(2) + ly * ly + min(lx, 0f).pow(2))
                    for (bp in floatArrayOf(0.38f, 0.62f)) {
                        val bl = 0.28f - bp * 0.18f
                        dist = min(dist, segDist(lx, ly, bp, 0f, bp + bl * 0.5f, bl * 0.866f))
                    }
                    max(((0.04f - dist) / px).coerceIn(0f, 1f), ((0.12f - r) / px).coerceIn(0f, 1f))
                }
                ShapeKind.DROP -> {
                    // Spitze oben, runder Bauch unten
                    val w = if (v < 0.3f) 0.55f * ((v + 0.9f) / 1.2f).coerceIn(0f, 1f).pow(1.4f)
                    else sqrt((0.55f * 0.55f - (v - 0.3f) * (v - 0.3f)).coerceAtLeast(0f))
                    val body = ((w - abs(u)) / px).coerceIn(0f, 1f)
                    val hl = 1f - 0.35f * exp(-((u + 0.2f).pow(2) + (v - 0.22f).pow(2)) * 60f)
                    body * hl
                }
                ShapeKind.WAVE -> {
                    // Brecher: Wasserkörper steigt zum Kamm an, die Lippe rollt sich über eine hohle Röhre nach rechts
                    val sx = if (u < 0f) 0.55f else 0.28f
                    val top = 0.55f - 0.95f * exp(-(u / sx) * (u / sx))
                    val body = ((v - top) / px).coerceIn(0f, 1f)
                    val cx = 0.32f; val cy = 0.08f
                    val rr = sqrt((u - cx) * (u - cx) + (v - cy) * (v - cy))
                    val tube = ((rr - 0.24f) / px).coerceIn(0f, 1f)
                    val th = atan2(v - cy, u - cx)
                    val lip = if (th < -0.15f || th > 2.9f) ((0.12f - abs(rr - 0.3f)) / px).coerceIn(0f, 1f) else 0f
                    val tip = ((0.07f - sqrt((u - 0.58f).pow(2) + (v - 0.1f).pow(2))) / px).coerceIn(0f, 1f)
                    val foam = smooth(0.5f, 0.7f, Noise.fbm(u * 7f + 3f, v * 7f, 0, 3, 63)) * exp(-((v - top) * (v - top)) * 60f)
                    val water = max(body * (if (v < 0.4f) tube else 1f), max(lip, tip))
                    (max(water, foam) * (1f - smooth(0.88f, 0.98f, abs(u))) * (1f - smooth(0.85f, 0.97f, v))).coerceIn(0f, 1f)
                }
                ShapeKind.POLYGON -> {
                    val ang = (atan2(v, u) + 2f * PI.toFloat()) % (2f * PI.toFloat())
                    var a = 0f
                    for (i in dots.indices) {
                        val p0 = dots[i]; val p1 = dots[(i + 1) % dots.size]
                        val a0 = (p0[0] + 2f * PI.toFloat()) % (2f * PI.toFloat())
                        var a1 = (p1[0] + 2f * PI.toFloat()) % (2f * PI.toFloat()); if (a1 < a0) a1 += 2f * PI.toFloat()
                        var aa = ang; if (aa < a0) aa += 2f * PI.toFloat()
                        if (aa < a0 || aa > a1) continue
                        val x0 = cos(p0[0]) * p0[1]; val y0 = sin(p0[0]) * p0[1]
                        val x1 = cos(p1[0]) * p1[1]; val y1 = sin(p1[0]) * p1[1]
                        val ex = x1 - x0; val ey = y1 - y0
                        val side = (ex * (v - y0) - ey * (u - x0)) / sqrt(ex * ex + ey * ey)
                        a = (side / px).coerceIn(0f, 1f) * (0.45f + 0.55f * Noise.hash(i, 9, 55))
                    }
                    a
                }
                ShapeKind.SMOKE -> {
                    val w1 = Noise.fbm(u * 1.6f + 4f, v * 1.6f + 4f, 0, 4, 65)
                    val w2 = Noise.fbm(u * 2.2f + 9f, v * 2.2f + 1f, 0, 4, 66)
                    val c1 = 0.35f * sin(u * 3.2f + 5f * w1) - 0.15f
                    val c2 = 0.3f * sin(u * 2.4f + 6f * w2 + 2f) + 0.2f
                    val band = exp(-((v - c1) * (v - c1)) / 0.012f) + 0.7f * exp(-((v - c2) * (v - c2)) / 0.02f)
                    val env = (1f - r * r).coerceAtLeast(0f)
                    (band * env * (0.6f + 0.4f * w2)).coerceIn(0f, 1f)
                }
                ShapeKind.NEEDLES -> {
                    var dist = if (abs(u) <= 1f) abs(v) - 0.015f else 1f
                    var k = -0.95f
                    var i = 0
                    while (k < 0.95f) {
                        val len = 0.42f + 0.12f * Noise.hash(i, 1, 67)
                        dist = min(dist, segDist(u, v, k, 0f, k + 0.22f, -len))
                        dist = min(dist, segDist(u, v, k + 0.06f, 0f, k + 0.28f, len))
                        k += 0.12f; i++
                    }
                    ((0.016f - dist) / px).coerceIn(0f, 1f)
                }
                ShapeKind.BLOSSOM -> {
                    val ang = atan2(v, u)
                    val petal = 0.88f * (0.55f + 0.45f * abs(cos(2.5f * ang)).pow(0.6f))
                    val body = ((petal - r) / px).coerceIn(0f, 1f)
                    val gap = smooth(0.13f, 0.17f, r) * 0.85f + 0.15f
                    val center = ((0.13f - r) / px).coerceIn(0f, 1f)
                    max(body * gap * (0.7f + 0.3f * r), center)
                }
                ShapeKind.BAMBOO -> {
                    val node = abs(u) > 0.9f
                    val half = if (node) 0.36f else 0.3f
                    val body = ((half - abs(v)) / px).coerceIn(0f, 1f) * (if (abs(u) > 0.985f) 0.35f else 1f)
                    val shade = 0.65f + 0.35f * smooth(0.0f, half, abs(v)) + 0.15f * Noise.value(u * 2f, v * 40f, 0, 69)
                    body * shade.coerceIn(0f, 1f)
                }
                ShapeKind.TWIG -> {
                    val wob = 0.05f * sin(u * 4f)
                    val thick = 0.035f - 0.012f * u
                    var dist = abs(v - wob * (1f - u * u)) - thick
                    dist = min(dist, segDist(u, v, -0.5f, 0f, -0.1f, -0.55f) - 0.02f)
                    dist = min(dist, segDist(u, v, -0.25f, -0.32f, -0.3f, -0.72f) - 0.012f)
                    dist = min(dist, segDist(u, v, 0.15f, 0f, 0.55f, 0.5f) - 0.018f)
                    dist = min(dist, segDist(u, v, 0.38f, 0.28f, 0.62f, 0.2f) - 0.01f)
                    dist = min(dist, segDist(u, v, 0.6f, 0f, 0.85f, -0.3f) - 0.012f)
                    (-dist / px).coerceIn(0f, 1f) * (if (abs(u) > 1f) 0f else 1f)
                }
                ShapeKind.REED -> {
                    var s = 0f
                    for ((i, d) in dots.withIndex()) {
                        val t = (0.95f - v) / d[2]
                        if (t < 0f || t > 1f) continue
                        val cx = d[0] + (d[1] - d[0]) * t * t
                        val w = d[3] * (1f - t * 0.9f)
                        s = max(s, ((w - abs(u - cx)) / px).coerceIn(0f, 1f))
                        if (i == 0) {
                            // Kolben am ersten Halm
                            val ky = 0.95f - d[2] * 0.72f
                            val kx = d[0] + (d[1] - d[0]) * 0.72f * 0.72f
                            val ke = ((u - kx) / 0.07f).pow(2) + ((v - ky) / 0.2f).pow(2)
                            s = max(s, ((1f - ke) * 8f).coerceIn(0f, 1f))
                        }
                    }
                    s
                }
                ShapeKind.STAR4 -> {
                    val f = sqrt(abs(u)) + sqrt(abs(v))
                    ((0.95f - f) / (px * 4f)).coerceIn(0f, 1f)
                }
                ShapeKind.FLARE -> {
                    val core = exp(-r * r * 40f)
                    val streak = exp(-abs(v) * 45f) * (1f - abs(u)).coerceAtLeast(0f).pow(1.5f)
                    val halo = 0.3f * exp(-(r - 0.62f).pow(2) * 300f) + 0.15f * exp(-r * r * 4f)
                    (core + streak + halo).coerceIn(0f, 1f) * (1f - smooth(0.92f, 1f, r))
                }
                ShapeKind.DRIP -> {
                    // oben ein breiter Farbwulst, darunter eine dünner werdende Laufspur mit Tropfen am Ende
                    val top = ((1f - ((u / 0.5f).pow(2) + ((v + 0.62f) / 0.16f).pow(2))) * 6f).coerceIn(0f, 1f)
                    val t = ((v + 0.62f) / 1.25f).coerceIn(0f, 1f)
                    val runW = 0.13f - 0.07f * t
                    val run = ((runW - abs(u + 0.03f * sin(v * 5f))) / px).coerceIn(0f, 1f) * (if (v > -0.62f && v < 0.7f) 1f else 0f)
                    val bulb = ((0.11f - sqrt(u * u + (v - 0.74f) * (v - 0.74f))) / px).coerceIn(0f, 1f)
                    max(top, max(run, bulb))
                }
                ShapeKind.BURST -> {
                    val ang = atan2(v, u)
                    var s = ((0.2f - r) / px).coerceIn(0f, 1f)
                    for (d in dots) {
                        val tp = 2f * PI.toFloat()
                        var da = ((ang - d[0]) % tp + tp) % tp; if (da > PI.toFloat()) da = tp - da
                        if (r > d[1]) continue
                        val w = d[2] * (1f - r / d[1])
                        s = max(s, ((w - da * r) / px).coerceIn(0f, 1f))
                    }
                    s
                }
                ShapeKind.SCRATCHES -> {
                    var s = 0f
                    for (d in dots) {
                        val c = cos(d[2]); val sn = sin(d[2])
                        val ax = d[0] - c * d[3]; val ay = d[1] - sn * d[3]
                        val bx = d[0] + c * d[3]; val by = d[1] + sn * d[3]
                        val dist = segDist(u, v, ax, ay, bx, by)
                        s = max(s, ((d[4] - dist) / px + 0.5f).coerceIn(0f, 1f) * d[5])
                    }
                    s * (1f - smooth(0.9f, 1f, r))
                }
            }
            out[y * n + x] = a
        }
        return out
    }

    /** Abstand eines Punkts zur Strecke a–b. */
    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        val qx = ax + dx * t - px; val qy = ay + dy * t - py
        return sqrt(qx * qx + qy * qy)
    }

    // ------------------------------------------------------------------ Körnungen

    /** Kachelbares Zellrauschen: Abstand zum nächsten und zweitnächsten Zellpunkt (Zellgröße [c], n/c ganzzahlig). */
    private fun voronoi(fx: Float, fy: Float, c: Int, seed: Int): FloatArray {
        val cells = GRAIN_SIZE / c
        val gx = floor(fx / c).toInt(); val gy = floor(fy / c).toInt()
        var d1 = 1e9f; var d2 = 1e9f
        for (oy in -1..1) for (ox in -1..1) {
            val cx = gx + ox; val cy = gy + oy
            val wx = ((cx % cells) + cells) % cells; val wy = ((cy % cells) + cells) % cells
            val ppx = (cx + Noise.hash(wx, wy, seed)) * c; val ppy = (cy + Noise.hash(wx, wy, seed + 1)) * c
            val d = sqrt((fx - ppx) * (fx - ppx) + (fy - ppy) * (fy - ppy))
            if (d < d1) { d2 = d1; d1 = d } else if (d < d2) d2 = d
        }
        return floatArrayOf(d1, d2)
    }

    private fun tri(t: Float): Float = abs(t - floor(t) - 0.5f) * 2f // Dreieckswelle 0..1

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
                GrainKind.GRID -> {
                    val lx = abs((x % 16) - 7.5f); val ly = abs((y % 16) - 7.5f)
                    max(smooth(5.6f, 6.8f, lx), smooth(5.6f, 6.8f, ly))
                }
                GrainKind.DICE -> {
                    // Würfelmuster: Sechsecke aus drei Rauten (oben hell, links mittel, rechts dunkel)
                    var bx = 0f; var by = 0f; var best = 1e9f
                    val row0 = floor(fy / 16f).toInt()
                    for (r in row0 - 1..row0 + 1) {
                        val ox = if (((r % 2) + 2) % 2 == 1) 16f else 0f
                        val col0 = floor((fx - ox) / 32f).toInt()
                        for (c in col0 - 1..col0 + 1) {
                            val cx = c * 32f + ox; val cy = r * 16f
                            val dx = (fx - cx); val dy = (fy - cy) * 1.5f
                            val d = dx * dx + dy * dy
                            if (d < best) { best = d; bx = fx - cx; by = fy - cy }
                        }
                    }
                    val ang = Math.toDegrees(atan2(by.toDouble(), bx.toDouble())).toFloat()
                    val face = when {
                        ang < -30f && ang > -150f -> 1f
                        ang >= -30f && ang < 90f -> 0.12f
                        else -> 0.5f
                    }
                    face * (0.92f + 0.08f * Noise.value(fx / 4f, fy / 4f, n / 4, 131))
                }
                GrainKind.ROSETTE -> {
                    var m = 1e9f
                    val gx = floor(fx / 32f).toInt(); val gy = floor(fy / 32f).toInt()
                    for (oy in -1..2) for (ox in -1..2) {
                        val cx = (gx + ox) * 32f; val cy = (gy + oy) * 32f
                        m = min(m, abs(sqrt((fx - cx) * (fx - cx) + (fy - cy) * (fy - cy)) - 32f))
                    }
                    0.12f + 0.88f * (1f - smooth(0.8f, 2.4f, m))
                }
                GrainKind.GRUNGE -> {
                    val blot = smooth(0.38f, 0.62f, Noise.fbm(fx / 32f, fy / 32f, n / 32, 5, 133))
                    val speck = if (Noise.hash(x, y, 134) > 0.9f) 0.25f else 1f
                    val scratch = 1f - 0.7f * (1f - smooth(0.0f, 0.06f, abs(Noise.fbm(fx / 64f, fy / 4f, n / 64, n / 4, 2, 135) - 0.5f)))
                    (blot * speck * scratch + 0.08f * Noise.value(fx / 2f, fy / 2f, n / 2, 136)).coerceIn(0f, 1f)
                }
                GrainKind.RUST -> {
                    val patch = smooth(0.35f, 0.7f, Noise.fbm(fx / 32f, fy / 32f, n / 32, 4, 137))
                    val pits = smooth(0.25f, 0.75f, Noise.fbm(fx / 4f, fy / 4f, n / 4, 3, 138))
                    val hole = if (Noise.hash(x / 2, y / 2, 139) > 0.97f) 0.1f else 1f
                    ((0.25f + 0.75f * patch) * (0.45f + 0.55f * pits) * hole).coerceIn(0f, 1f)
                }
                GrainKind.STONE -> {
                    val d = voronoi(fx, fy, 64, 141)
                    val crack = smooth(0.8f, 3.5f, d[1] - d[0])
                    val surf = 0.55f + 0.45f * Noise.fbm(fx / 16f, fy / 16f, n / 16, 4, 143)
                    crack * surf * (0.85f + 0.15f * Noise.value(fx, fy, n, 144))
                }
                GrainKind.CORRUGATED -> {
                    val w = 0.5f + 0.5f * sin(fx * 2f * PI.toFloat() / 16f)
                    (0.15f + 0.85f * w) * (0.9f + 0.1f * Noise.value(fx / 8f, fy / 2f, n / 8, n / 2, 145))
                }
                GrainKind.MARBLE -> {
                    val warp = Noise.fbm(fx / 64f, fy / 64f, n / 64, 5, 147)
                    val vein = abs(sin(fx / n * 2f * PI.toFloat() * 2f + fy / n * 2f * PI.toFloat() + warp * 7f))
                    val fine = abs(sin(fx / n * 2f * PI.toFloat() * 5f - fy / n * 2f * PI.toFloat() * 3f + warp * 11f))
                    (0.12f + 0.88f * (1f - smooth(0.0f, 0.18f, vein)) + 0.4f * (1f - smooth(0f, 0.06f, fine)) +
                        0.1f * Noise.fbm(fx / 8f, fy / 8f, n / 8, 3, 148)).coerceIn(0f, 1f)
                }
                GrainKind.MEMBRANE -> {
                    val d = voronoi(fx, fy, 32, 149)
                    0.1f + 0.9f * (1f - smooth(0.6f, 2.8f, d[1] - d[0])) + 0.15f * (1f - smooth(0f, 10f, d[0]))
                }
                GrainKind.COTTON -> {
                    val fluff = smooth(0.3f, 0.75f, Noise.fbm(fx / 16f, fy / 16f, n / 16, 4, 151))
                    val fib = smooth(0.4f, 0.8f, Noise.fbm(fx / 2f, fy / 16f, n / 2, n / 16, 2, 152))
                    (0.25f + 0.55f * fluff + 0.2f * fib).coerceIn(0f, 1f)
                }
                GrainKind.JUTE -> {
                    val cx = x / 16; val cy = y / 16
                    val horiz = (cx + cy) % 2 == 0
                    val t = if (horiz) (y % 8) / 8f else (x % 8) / 8f
                    val thread = sin(t * PI.toFloat()).pow(0.7f)
                    val fib = 0.7f + 0.3f * Noise.value(if (horiz) fx / 6f else fx / 1.5f, if (horiz) fy / 1.5f else fy / 6f, n, 153)
                    (thread * fib).coerceIn(0f, 1f)
                }
                GrainKind.HEMP -> {
                    val band = sin(((y % 16) / 16f) * PI.toFloat()).pow(0.8f)
                    val twist = 0.55f + 0.45f * sin(2f * PI.toFloat() * (fx / 8f + fy / 16f))
                    val fib = 0.75f + 0.25f * Noise.value(fx / 3f, fy, n / 3 + 1, n, 155)
                    (band * twist * fib).coerceIn(0f, 1f)
                }
                GrainKind.FABRIC -> {
                    val twill = 0.5f + 0.5f * sin(2f * PI.toFloat() * (fx + 2f * fy) / 8f)
                    val warp = 0.5f + 0.5f * sin(2f * PI.toFloat() * fx / 4f)
                    (0.25f + 0.5f * twill + 0.25f * warp * Noise.value(fx / 4f, fy / 4f, n / 4, 157)).coerceIn(0f, 1f)
                }
                GrainKind.CLAY -> {
                    val smear = smooth(0.25f, 0.8f, Noise.fbm(fx / 64f, fy / 16f, n / 64, n / 16, 4, 159))
                    val d = voronoi(fx, fy, 64, 160)
                    val crack = 0.55f + 0.45f * smooth(0.3f, 1.6f, d[1] - d[0])
                    (0.2f + 0.8f * smear) * crack
                }
                GrainKind.PORES -> {
                    val gx = x / 8; val gy = y / 8
                    var s = 0f
                    for (oy in -1..1) for (ox in -1..1) {
                        val cx = gx + ox; val cy = gy + oy
                        val wx = ((cx % 32) + 32) % 32; val wy = ((cy % 32) + 32) % 32
                        if (Noise.hash(wx, wy, 163) < 0.35f) continue
                        val ppx = (cx + Noise.hash(wx, wy, 161)) * 8f; val ppy = (cy + Noise.hash(wx, wy, 162)) * 8f
                        val rad = 0.8f + 1.1f * Noise.hash(wx, wy, 164)
                        val d = sqrt((fx - ppx) * (fx - ppx) + (fy - ppy) * (fy - ppy))
                        s = max(s, 1f - smooth(rad * 0.5f, rad + 0.6f, d))
                    }
                    s
                }
                GrainKind.NEWSPRINT -> {
                    val a = ((x + y) % 16) - 7.5f; val b = (((x - y) % 16 + 16) % 16) - 7.5f
                    val d = sqrt(a * a + b * b) / 1.414f
                    val rad = 1.2f + 3.6f * Noise.fbm(fx / 32f, fy / 32f, n / 32, 3, 165)
                    1f - smooth(rad - 0.6f, rad + 0.6f, d)
                }
                GrainKind.DOTS -> {
                    val ry = y / 32
                    val ox = if (ry % 2 == 0) 0 else 16
                    val cx = ((x + ox) % 32) - 15.5f; val cy = (y % 32) - 15.5f
                    1f - smooth(8.4f, 9.6f, sqrt(cx * cx + cy * cy))
                }
                GrainKind.STRIPES -> 1f - smooth(7.4f, 8.6f, abs((y % 32) - 15.5f))
                GrainKind.CHEVRON -> {
                    val yy = fy + tri(fx / 32f) * 12f
                    1f - smooth(7.2f, 8.8f, abs(((yy % 32f) + 32f) % 32f - 16f))
                }
                GrainKind.DIAGONAL -> 1f - smooth(7.2f, 8.8f, abs(((x + y) % 32) - 15.5f))
                GrainKind.SAND -> {
                    val dens = 0.15f + 0.6f * smooth(0.3f, 0.7f, Noise.fbm(fx / 16f, fy / 16f, n / 16, 3, 167))
                    val h1 = Noise.hash(x, y, 168)
                    if (h1 < dens) 0.6f + 0.4f * Noise.hash(x, y, 169) else 0.05f * Noise.hash(x, y, 170)
                }
                GrainKind.NET -> {
                    val a = abs(((x + y) % 32) - 15.5f); val b = abs((((x - y) % 32 + 32) % 32) - 15.5f)
                    val line = max(smooth(13.5f, 15f, a), smooth(13.5f, 15f, b))
                    val knot = smooth(12.5f, 15f, a) * smooth(12.5f, 15f, b)
                    (line * (0.8f + 0.2f * Noise.value(fx / 2f, fy / 2f, n / 2, 171)) + knot).coerceIn(0f, 1f)
                }
            }
            out[y * n + x] = g.coerceIn(0f, 1f)
        }
        return out
    }
}
