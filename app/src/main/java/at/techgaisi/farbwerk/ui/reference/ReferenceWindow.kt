package at.techgaisi.farbwerk.ui.reference

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.io.Importers
import at.techgaisi.farbwerk.render.Compositor
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ReferenceMode { CANVAS, IMAGE }

/**
 * Zustand des Referenzfensters: Position/Größe (dp), Modus, Bild und eigener Zoom/Versatz des Inhalts.
 */
class ReferenceWindowState {
    var visible by mutableStateOf(false)
    var mode by mutableStateOf(ReferenceMode.CANVAS)
    var image by mutableStateOf<Bitmap?>(null)
    var x by mutableFloatStateOf(-1f)
    var y by mutableFloatStateOf(-1f)
    var w by mutableFloatStateOf(300f)
    var h by mutableFloatStateOf(240f)
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var controls by mutableStateOf(true)

    fun toggle() { visible = !visible }
    fun resetView() { zoom = 1f; panX = 0f; panY = 0f }
}

/**
 * Schwebendes Referenzfenster. Griff oben = verschieben, untere Ecken = Größe ändern, innen kneifen/ziehen =
 * Zoom/Versatz, Tippen blendet die Bedienelemente ein/aus, Halten = Farbe aufnehmen.
 * Unten: **Leinwand** (Live-Ansicht des ganzen Werks) und **Bild** (Referenzfoto; „Bild löschen“).
 */
@Composable
fun ReferenceWindow(state: EditorState, rs: ReferenceWindowState) {
    val ui = LocalUi.current
    val doc = state.document ?: return
    val ctx = state.context
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) { try { Importers.decodeImage(ctx, uri, 2048, 2048) } catch (_: Throwable) { null } }
            if (bmp == null) state.flash("Bild konnte nicht geladen werden")
            else { rs.image = bmp; rs.mode = ReferenceMode.IMAGE; rs.resetView() }
        }
    }

    AnimatedVisibility(rs.visible, enter = fadeIn(tween(180)) + scaleIn(tween(200), initialScale = 0.92f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.95f)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxW = maxWidth.value; val maxH = maxHeight.value
            if (rs.x < 0f) { rs.x = maxW - rs.w - 70f; rs.y = 120f }
            rs.x = rs.x.coerceIn(0f, max(0f, maxW - 60f)); rs.y = rs.y.coerceIn(0f, max(0f, maxH - 60f))

            // Live-Ansicht der Leinwand (entprellt nach Änderungen)
            var live by remember { mutableStateOf<Bitmap?>(null) }
            val rev = state.revision
            LaunchedEffect(rev, rs.mode, doc, rs.w, rs.h) {
                if (rs.mode != ReferenceMode.CANVAS) return@LaunchedEffect
                delay(120)
                val target = with(density) { max(rs.w, rs.h).dp.toPx() } * 1.5f
                val s = min(1f, target / max(doc.width, doc.height))
                val bw = max(1, (doc.width * s).roundToInt()); val bh = max(1, (doc.height * s).roundToInt())
                // Neue Bitmap je Aktualisierung (klein), damit Compose sicher neu zeichnet
                val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                val c = Canvas(b); c.scale(bw / doc.width.toFloat(), bh / doc.height.toFloat())
                try { Compositor.draw(c, doc) } catch (_: Throwable) {}
                live = b
            }
            val content: Bitmap? = if (rs.mode == ReferenceMode.CANVAS) live else rs.image

            Column(
                Modifier.offset { IntOffset(with(density) { rs.x.dp.roundToPx() }, with(density) { rs.y.dp.roundToPx() }) }
                    .size(rs.w.dp, rs.h.dp)
                    .shadow(16.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                    .background(ui.panel).border(0.5.dp, ui.divider, RoundedCornerShape(12.dp))
            ) {
                // Griff
                Box(
                    Modifier.fillMaxWidth().height(24.dp).pointerInput(Unit) {
                        detectDragGestures { ch, d ->
                            ch.consume()
                            rs.x += d.x / density.density; rs.y += d.y / density.density
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(ui.iconMuted))
                    Text("✕", color = ui.textMuted, fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.CenterEnd).clickable { rs.visible = false }.padding(horizontal = 10.dp))
                }
                // Inhalt
                Box(
                    Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp)).background(Color(0xFF1E1E20))
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                rs.zoom = (rs.zoom * zoom).coerceIn(0.25f, 12f)
                                rs.panX += pan.x; rs.panY += pan.y
                            }
                        }
                        .pointerInput(content) {
                            detectTapGestures(
                                onTap = { rs.controls = !rs.controls },
                                onDoubleTap = { rs.resetView() },
                                onLongPress = { p -> content?.let { pickColor(state, rs, it, p, size.width, size.height) } },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (content != null && !content.isRecycled) {
                        Image(
                            content.asImageBitmap(), "Referenz", contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = rs.zoom; scaleY = rs.zoom; translationX = rs.panX; translationY = rs.panY
                            }
                        )
                    } else if (rs.mode == ReferenceMode.IMAGE) {
                        Text("Kein Bild", color = ui.textMuted, fontSize = 13.sp)
                    }
                    // Größe ändern: untere Ecken
                    ResizeCorner(Modifier.align(Alignment.BottomEnd)) { dx, dy ->
                        rs.w = (rs.w + dx / density.density).coerceIn(160f, maxW); rs.h = (rs.h + dy / density.density).coerceIn(140f, maxH)
                    }
                    ResizeCorner(Modifier.align(Alignment.BottomStart)) { dx, dy ->
                        val nw = (rs.w - dx / density.density).coerceIn(160f, maxW)
                        rs.x += rs.w - nw; rs.w = nw
                        rs.h = (rs.h + dy / density.density).coerceIn(140f, maxH)
                    }
                }
                AnimatedVisibility(rs.controls) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RefButton("Leinwand", rs.mode == ReferenceMode.CANVAS) { rs.mode = ReferenceMode.CANVAS; rs.resetView() }
                        RefButton("Bild", rs.mode == ReferenceMode.IMAGE) {
                            if (rs.image == null) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            else { rs.mode = ReferenceMode.IMAGE; rs.resetView() }
                        }
                        if (rs.mode == ReferenceMode.IMAGE && rs.image != null) {
                            RefButton("Neues Bild", false) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                            RefButton("Bild löschen", false, destructive = true) {
                                rs.image = null; rs.mode = ReferenceMode.CANVAS
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Farbe unter dem Finger aus dem angezeigten Inhalt übernehmen (Fit + eigener Zoom/Versatz). */
private fun pickColor(state: EditorState, rs: ReferenceWindowState, b: Bitmap, p: Offset, vw: Int, vh: Int) {
    if (b.isRecycled) return
    val fit = min(vw / b.width.toFloat(), vh / b.height.toFloat())
    // Rückrechnung: Bildschirm → (Zoom um Mitte, Versatz) → Fit
    val cx = vw / 2f; val cy = vh / 2f
    val ux = (p.x - cx - rs.panX) / rs.zoom + cx
    val uy = (p.y - cy - rs.panY) / rs.zoom + cy
    val bx = ((ux - (vw - b.width * fit) / 2f) / fit).toInt()
    val by = ((uy - (vh - b.height * fit) / 2f) / fit).toInt()
    if (bx !in 0 until b.width || by !in 0 until b.height) return
    val c = b.getPixel(bx, by)
    if (android.graphics.Color.alpha(c) < 8) return
    state.setColorAndRemember(c or (0xFF shl 24))
    state.flash("Farbe übernommen")
}

@Composable
private fun ResizeCorner(modifier: Modifier, onDrag: (Float, Float) -> Unit) {
    Box(
        modifier.size(26.dp).pointerInput(Unit) {
            detectDragGestures { ch, d -> ch.consume(); onDrag(d.x, d.y) }
        },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0x99FFFFFF))) }
}

@Composable
private fun RefButton(label: String, active: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    val ui = LocalUi.current
    Text(
        label, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = when { destructive -> Color(0xFFFF453A); active -> ui.accent; else -> ui.text },
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(if (active) ui.panelElevated else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
