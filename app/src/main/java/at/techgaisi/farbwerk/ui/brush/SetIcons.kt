package at.techgaisi.farbwerk.ui.brush

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.brush.BrushLibrary

/**
 * Selbst gezeichnete Set-Symbole der Pinselbibliothek (14 dp, einfarbig).
 *
 * Jedes Symbol wird in einem 14 × 14-Raster gezeichnet und auf die Zielgröße skaliert. Die Motive geben nur die
 * Bedeutung des Sets wieder (Bleistift, Feder, Pinsel, Sprühdose …) und sind eigene, schlichte Formen.
 * Hat ein Set ein vom Nutzer festgelegtes Zeichen/Emoji, wird stattdessen dieses angezeigt.
 */
@Composable
internal fun SetIcon(setId: String, emoji: String?, tint: Color, size: Dp = 14.dp) {
    if (!emoji.isNullOrBlank()) {
        Box(Modifier.size(size + 2.dp), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 12.sp, maxLines = 1, color = tint)
        }
        return
    }
    // Offscreen-Ebene, damit die ausgestanzten Details (BlendMode.Clear) transparent bleiben
    Canvas(Modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val k = this.size.width / GRID
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            drawSetGlyph(glyphFor(setId), tint)
        }
    }
}

/** Raster, in dem die Symbole entworfen sind. */
private const val GRID = 14f

private enum class Glyph {
    HOURGLASS, STAR, PENCIL, NIB, FINELINER, PAINTBRUSH, PALETTE, SPRAY, TEXTURE, CHARCOAL, DROP, SCRIPT, IMPORT, STROKE,
    MARKER, CRAYON, TUBE, SHAPES, BUBBLE, SWIRL, GLOW, LEAF,
}

private fun glyphFor(setId: String): Glyph = when (setId) {
    BrushLibrary.SET_RECENT -> Glyph.HOURGLASS
    BrushLibrary.SET_FAVORITES -> Glyph.STAR
    BrushLibrary.SET_IMPORTED -> Glyph.IMPORT
    "skizze" -> Glyph.PENCIL
    "zeichnen" -> Glyph.FINELINER
    "tinte" -> Glyph.NIB
    "marker" -> Glyph.MARKER
    "pastell" -> Glyph.CRAYON
    "oel" -> Glyph.TUBE
    "malen" -> Glyph.PAINTBRUSH
    "gouache" -> Glyph.PALETTE
    "aquarell" -> Glyph.DROP
    "kohle" -> Glyph.CHARCOAL
    "grundformen" -> Glyph.SHAPES
    "kalligrafie" -> Glyph.SCRIPT
    "comic" -> Glyph.BUBBLE
    "texturen" -> Glyph.TEXTURE
    "kunst" -> Glyph.SWIRL
    "leuchten" -> Glyph.GLOW
    "elemente" -> Glyph.LEAF
    "airbrush" -> Glyph.SPRAY
    else -> Glyph.STROKE
}

private val thin = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Zeichnet das Symbol im 14er-Raster. */
private fun DrawScope.drawSetGlyph(g: Glyph, c: Color) {
    when (g) {
        Glyph.HOURGLASS -> {
            // Rahmenleisten oben/unten
            drawRoundRect(c, Offset(2.5f, 1f), Size(9f, 1.6f), CornerRadius(0.8f))
            drawRoundRect(c, Offset(2.5f, 11.4f), Size(9f, 1.6f), CornerRadius(0.8f))
            // Glaskörper
            val glass = Path().apply {
                moveTo(3.6f, 2.6f); lineTo(10.4f, 2.6f)
                cubicTo(10.4f, 5.2f, 7.8f, 6.2f, 7.8f, 7f)
                cubicTo(7.8f, 7.8f, 10.4f, 8.8f, 10.4f, 11.4f)
                lineTo(3.6f, 11.4f)
                cubicTo(3.6f, 8.8f, 6.2f, 7.8f, 6.2f, 7f)
                cubicTo(6.2f, 6.2f, 3.6f, 5.2f, 3.6f, 2.6f)
                close()
            }
            drawPath(glass, c, style = Stroke(1.1f, join = StrokeJoin.Round))
            // Sand unten
            val sand = Path().apply { moveTo(4.6f, 11.2f); lineTo(9.4f, 11.2f); quadraticTo(8.6f, 9.2f, 7f, 8.8f); quadraticTo(5.4f, 9.2f, 4.6f, 11.2f); close() }
            drawPath(sand, c)
        }
        Glyph.STAR -> {
            val p = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 6.2f else 2.7f
                val a = Math.toRadians((-90.0 + i * 36.0))
                val x = 7f + r * kotlin.math.cos(a).toFloat()
                val y = 7.4f + r * kotlin.math.sin(a).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, c)
        }
        Glyph.PENCIL -> rotate(-45f, Offset(7f, 7f)) {
            // Körper, Zwinge und Spitze als Bleistift quer gelegt
            drawRoundRect(c, Offset(4.2f, 5.3f), Size(7.6f, 3.4f), CornerRadius(0.6f))
            drawRect(c, Offset(11.2f, 5.3f), Size(1.6f, 3.4f), alpha = 0.6f)
            val tip = Path().apply { moveTo(4.2f, 5.3f); lineTo(1.2f, 7f); lineTo(4.2f, 8.7f); close() }
            drawPath(tip, c, alpha = 0.75f)
        }
        Glyph.NIB -> {
            // Federspitze: Tropfenform mit Schlitz und Luftloch
            val nib = Path().apply {
                moveTo(7f, 13f)
                lineTo(3.2f, 6.6f)
                quadraticTo(3.6f, 3.6f, 5.2f, 2.6f)
                lineTo(8.8f, 2.6f)
                quadraticTo(10.4f, 3.6f, 10.8f, 6.6f)
                close()
            }
            drawPath(nib, c)
            drawRoundRect(c, Offset(4.6f, 0.8f), Size(4.8f, 1.4f), CornerRadius(0.5f))
            drawLine(Color.Black, Offset(7f, 7.4f), Offset(7f, 12.4f), 0.8f, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
            drawCircle(Color.Black, 0.9f, Offset(7f, 6.6f), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
        }
        Glyph.FINELINER -> rotate(-45f, Offset(7f, 7f)) {
            drawRoundRect(c, Offset(3.6f, 5.6f), Size(8.8f, 2.8f), CornerRadius(1.2f))
            drawRect(c, Offset(2.2f, 6.2f), Size(1.4f, 1.6f), alpha = 0.8f)
            drawLine(c, Offset(0.8f, 7f), Offset(2.2f, 7f), 0.9f, StrokeCap.Round)
            drawLine(Color.Black, Offset(9.2f, 5.4f), Offset(9.2f, 8.6f), 0.6f, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
        }
        Glyph.PAINTBRUSH -> rotate(-45f, Offset(7f, 7f)) {
            // Stiel
            val handle = Path().apply { moveTo(7.2f, 6f); lineTo(13.2f, 6.6f); quadraticTo(13.6f, 7f, 13.2f, 7.4f); lineTo(7.2f, 8f); close() }
            drawPath(handle, c)
            // Zwinge
            drawRect(c, Offset(5.2f, 5.7f), Size(1.6f, 2.6f), alpha = 0.65f)
            // Borsten
            val tuft = Path().apply { moveTo(5f, 5.5f); quadraticTo(2f, 5.5f, 0.6f, 7f); quadraticTo(2f, 8.5f, 5f, 8.5f); close() }
            drawPath(tuft, c)
        }
        Glyph.PALETTE -> {
            val pal = Path().apply {
                moveTo(7f, 1.4f)
                cubicTo(10.8f, 1.4f, 13f, 3.8f, 12.8f, 6.6f)
                cubicTo(12.6f, 8.6f, 10.6f, 8.4f, 9.6f, 9f)
                cubicTo(8.6f, 9.6f, 9.8f, 11f, 9f, 12f)
                cubicTo(8.2f, 12.9f, 7.4f, 12.8f, 6.6f, 12.7f)
                cubicTo(3.4f, 12.4f, 1.2f, 10f, 1.2f, 7f)
                cubicTo(1.2f, 3.8f, 3.6f, 1.4f, 7f, 1.4f)
                close()
            }
            drawPath(pal, c)
            val clear = androidx.compose.ui.graphics.BlendMode.Clear
            drawCircle(Color.Black, 1f, Offset(4.2f, 5f), blendMode = clear)
            drawCircle(Color.Black, 1f, Offset(7.2f, 3.8f), blendMode = clear)
            drawCircle(Color.Black, 1f, Offset(10f, 5.2f), blendMode = clear)
            drawCircle(Color.Black, 1f, Offset(4f, 8.6f), blendMode = clear)
        }
        Glyph.SPRAY -> {
            drawRoundRect(c, Offset(4.4f, 5f), Size(5.2f, 8.2f), CornerRadius(1.2f))
            drawRect(c, Offset(5.6f, 3.2f), Size(2.8f, 1.4f))
            drawRect(c, Offset(6.4f, 1.8f), Size(1.6f, 1.2f), alpha = 0.8f)
            drawCircle(c, 0.55f, Offset(10.4f, 1.6f))
            drawCircle(c, 0.55f, Offset(12f, 2.6f))
            drawCircle(c, 0.55f, Offset(10.6f, 3.6f))
            drawCircle(c, 0.45f, Offset(12.6f, 4.4f))
        }
        Glyph.TEXTURE -> {
            val radii = floatArrayOf(1.4f, 0.8f, 1.2f, 0.7f, 1.5f, 0.9f, 1.1f, 0.6f, 1.3f)
            for (i in 0 until 9) {
                val cx = 2.6f + (i % 3) * 4.4f
                val cy = 2.6f + (i / 3) * 4.4f
                drawCircle(c, radii[i], Offset(cx, cy))
            }
        }
        Glyph.CHARCOAL -> {
            // Abrieb + Kohlestück
            drawLine(c.copy(alpha = c.alpha * 0.45f), Offset(1.6f, 12f), Offset(7f, 9.4f), 2.2f, StrokeCap.Round)
            rotate(-35f, Offset(8.6f, 6f)) {
                drawRoundRect(c, Offset(4.4f, 4.4f), Size(8.6f, 3.4f), CornerRadius(1.1f))
            }
        }
        Glyph.DROP -> {
            val drop = Path().apply {
                moveTo(7f, 1f)
                cubicTo(8.6f, 3.6f, 11.4f, 6.2f, 11.4f, 8.8f)
                cubicTo(11.4f, 11.4f, 9.4f, 13.2f, 7f, 13.2f)
                cubicTo(4.6f, 13.2f, 2.6f, 11.4f, 2.6f, 8.8f)
                cubicTo(2.6f, 6.2f, 5.4f, 3.6f, 7f, 1f)
                close()
            }
            drawPath(drop, c)
            drawArc(Color.Black, 100f, 70f, false, Offset(4.4f, 6.8f), Size(4.4f, 4.4f),
                style = Stroke(0.9f, cap = StrokeCap.Round), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
        }
        Glyph.SCRIPT -> {
            val s = Path().apply {
                moveTo(1.2f, 10.6f)
                cubicTo(3.2f, 10.6f, 5.4f, 3f, 7.4f, 3.2f)
                cubicTo(9.2f, 3.4f, 6.6f, 9.8f, 5.6f, 10.2f)
                cubicTo(7.4f, 6.8f, 9.6f, 6f, 10.2f, 7.6f)
                cubicTo(10.6f, 8.8f, 9.8f, 10.4f, 12.8f, 10f)
            }
            drawPath(s, c, style = thin)
        }
        Glyph.IMPORT -> {
            drawLine(c, Offset(7f, 1.4f), Offset(7f, 8.4f), 1.4f, StrokeCap.Round)
            val head = Path().apply { moveTo(3.8f, 5.6f); lineTo(7f, 8.8f); lineTo(10.2f, 5.6f) }
            drawPath(head, c, style = Stroke(1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            val tray = Path().apply { moveTo(1.6f, 9f); lineTo(1.6f, 12.4f); lineTo(12.4f, 12.4f); lineTo(12.4f, 9f) }
            drawPath(tray, c, style = Stroke(1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Glyph.MARKER -> rotate(-45f, Offset(7f, 7f)) {
            // dicker Marker mit schräger Keilspitze und Kappenring
            drawRoundRect(c, Offset(4.4f, 4.6f), Size(8.8f, 4.8f), CornerRadius(1.4f))
            drawLine(Color.Black, Offset(10.4f, 4.4f), Offset(10.4f, 9.6f), 0.7f, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
            val tip = Path().apply { moveTo(4.4f, 5.4f); lineTo(1.4f, 6.2f); lineTo(1.4f, 8.2f); lineTo(4.4f, 8.6f); close() }
            drawPath(tip, c, alpha = 0.8f)
        }
        Glyph.CRAYON -> {
            // zwei kurze Kreidestücke, eines liegend, eines schräg
            drawRoundRect(c, Offset(1.4f, 9.6f), Size(8.4f, 3f), CornerRadius(1.5f))
            rotate(-50f, Offset(9.4f, 5.6f)) {
                drawRoundRect(c, Offset(5.6f, 4.2f), Size(7.6f, 2.8f), CornerRadius(1.4f), alpha = 0.7f)
            }
        }
        Glyph.TUBE -> rotate(-35f, Offset(7f, 7f)) {
            // Farbtube: Körper, Falz am Ende, Hals und Deckel
            val body = Path().apply { moveTo(3.2f, 5f); lineTo(10.6f, 4.6f); lineTo(10.6f, 9.4f); lineTo(3.2f, 9f); close() }
            drawPath(body, c)
            drawRect(c, Offset(11f, 4.4f), Size(1.4f, 5.2f), alpha = 0.6f)
            drawRect(c, Offset(2f, 6f), Size(1.2f, 2f), alpha = 0.8f)
            drawRoundRect(c, Offset(0.4f, 5.6f), Size(1.6f, 2.8f), CornerRadius(0.5f))
        }
        Glyph.SHAPES -> {
            drawCircle(c, 3.6f, Offset(5f, 5f))
            drawRect(c, Offset(6.6f, 6.6f), Size(6.4f, 6.4f), alpha = 0.6f)
        }
        Glyph.BUBBLE -> {
            // Sprechblase mit Zipfel
            drawOval(c, Offset(1f, 1.6f), Size(12f, 8.4f), style = Stroke(1.2f))
            val tail = Path().apply { moveTo(4f, 9.2f); lineTo(3f, 12.8f); lineTo(6.6f, 9.8f) }
            drawPath(tail, c, style = Stroke(1.2f, join = StrokeJoin.Round, cap = StrokeCap.Round))
            drawCircle(c, 0.8f, Offset(4.4f, 5.8f)); drawCircle(c, 0.8f, Offset(7f, 5.8f)); drawCircle(c, 0.8f, Offset(9.6f, 5.8f))
        }
        Glyph.SWIRL -> {
            // Spirale nach außen
            val p = Path()
            var first = true
            var t = 0f
            while (t <= 4.2f * Math.PI.toFloat()) {
                val r = 0.4f + 0.46f * t
                val x = 7f + r * kotlin.math.cos(t); val y = 7f + r * kotlin.math.sin(t)
                if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
                t += 0.2f
            }
            drawPath(p, c, style = thin)
        }
        Glyph.GLOW -> {
            // Lichtpunkt mit Strahlen
            drawCircle(c.copy(alpha = c.alpha * 0.35f), 4.4f, Offset(7f, 7f))
            drawCircle(c, 2.4f, Offset(7f, 7f))
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val r0 = 5f; val r1 = if (i % 2 == 0) 6.8f else 6f
                val ca = kotlin.math.cos(a).toFloat(); val sa = kotlin.math.sin(a).toFloat()
                drawLine(c, Offset(7f + ca * r0, 7f + sa * r0), Offset(7f + ca * r1, 7f + sa * r1), 0.9f, StrokeCap.Round)
            }
        }
        Glyph.LEAF -> {
            val leaf = Path().apply {
                moveTo(2f, 12f)
                cubicTo(1.6f, 6f, 5.4f, 1.8f, 12.6f, 1.4f)
                cubicTo(12.4f, 8.6f, 8f, 12.4f, 2f, 12f)
                close()
            }
            drawPath(leaf, c)
            drawLine(Color.Black, Offset(2.6f, 11.4f), Offset(9.6f, 4.4f), 0.8f, StrokeCap.Round,
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
        }
        Glyph.STROKE -> {
            // Allgemeines Set: geschwungener Pinselstrich, dick → dünn
            val p = Path().apply {
                moveTo(1.4f, 11.4f)
                cubicTo(3.6f, 6.2f, 6.4f, 12.4f, 8.6f, 6.4f)
                quadraticTo(10f, 2.8f, 12.8f, 2f)
                quadraticTo(10.8f, 3.8f, 9.8f, 7f)
                cubicTo(7.6f, 13.6f, 4f, 9.4f, 2.6f, 12.4f)
                close()
            }
            drawPath(p, c)
        }
    }
}

/** Kleines Häkchen (12 dp) für eigene/importierte Pinsel rechts oben in der Zeile. */
@Composable
internal fun RowCheckMark(tint: Color, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 12f
        val p = Path().apply { moveTo(2.2f * k, 6.4f * k); lineTo(4.8f * k, 9f * k); lineTo(10f * k, 3.2f * k) }
        drawPath(p, tint, style = Stroke(1.4f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Dünnes „+“ (22 dp, 1,5 dp Strich) für die Titelzeile. */
@Composable
internal fun ThinPlus(tint: Color, size: Dp = 22.dp, strokeWidth: Dp = 1.5.dp) {
    Canvas(Modifier.size(size)) {
        val w = strokeWidth.toPx()
        val s = this.size.width
        drawLine(tint, Offset(s / 2f, w), Offset(s / 2f, s - w), w, StrokeCap.Round)
        drawLine(tint, Offset(w, s / 2f), Offset(s - w, s / 2f), w, StrokeCap.Round)
    }
}

/** Kleine Lupe (Suche) neben dem Titel. */
@Composable
internal fun SmallSearchGlyph(tint: Color, size: Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val w = s * 0.11f
        drawCircle(tint, s * 0.3f, Offset(s * 0.42f, s * 0.42f), style = Stroke(w))
        drawLine(tint, Offset(s * 0.64f, s * 0.64f), Offset(s * 0.9f, s * 0.9f), w * 1.1f, StrokeCap.Round)
    }
}
