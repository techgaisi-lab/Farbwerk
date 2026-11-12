package at.techgaisi.farbwerk.ui.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.io.Exporters
import at.techgaisi.farbwerk.timelapse.Timelapse
import at.techgaisi.farbwerk.timelapse.TimelapseExport
import at.techgaisi.farbwerk.ui.actions.ActionGroup
import at.techgaisi.farbwerk.ui.actions.ActionPage
import at.techgaisi.farbwerk.ui.actions.ActionRow
import at.techgaisi.farbwerk.ui.actions.CheckRow
import at.techgaisi.farbwerk.ui.actions.FwDialog
import at.techgaisi.farbwerk.ui.actions.SwitchRow
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video-Tab des Aktionen-Panels: Zeitraffer-Wiedergabe, Aufnahme-Schalter, Auflösung und MP4-Export.
 */
@Composable
fun VideoTab(state: EditorState) {
    val ctx = state.context
    val doc = state.document
    @Suppress("UNUSED_VARIABLE") val rev = state.revision
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf(Timelapse.isEnabled(ctx)) }
    var maxSide by remember { mutableIntStateOf(Timelapse.maxSide(ctx)) }
    var showPlayer by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(doc?.id) { doc?.let { Timelapse.refreshCount(ctx, it.id) } }
    val count = if (doc != null) Timelapse.frameCount else 0
    val hasFrames = count > 0

    fun export(length: TimelapseExport.Length) {
        val d = doc ?: return
        showExport = false
        progress = 0f
        exportJob = scope.launch {
            try {
                val f = TimelapseExport.exportMp4(ctx, d.id, d.name, length) { p ->
                    scope.launch { if (progress != null) progress = p }
                }
                progress = null
                Exporters.share(ctx, f, "video/mp4")
            } catch (c: CancellationException) {
                progress = null
                throw c
            } catch (t: Throwable) {
                android.util.Log.w("Farbwerk", "Zeitraffer-Export fehlgeschlagen", t)
                progress = null
                state.flash("Export fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    ActionPage {
        ActionGroup(
            footer = if (hasFrames) "$count Bilder · ${TimelapseExport.formatTime(count / TimelapseExport.FPS.toFloat())} bei ${TimelapseExport.FPS} Bildern/s"
            else "Noch keine Aufnahme für dieses Kunstwerk."
        ) {
            ActionRow("Zeitraffer-Wiedergabe", enabled = hasFrames, chevron = true) { showPlayer = true }
            SwitchRow("Zeitraffer aufnehmen", recording) {
                recording = it
                Timelapse.setEnabled(ctx, it)
            }
            ActionRow("Zeitraffer exportieren", enabled = hasFrames && progress == null, chevron = true) { showExport = true }
        }
        ActionGroup(header = "Aufnahmequalität", footer = "Gilt für neu aufgenommene Bilder.") {
            Timelapse.RESOLUTIONS.forEach { (side, label) ->
                CheckRow(label, maxSide == side) { maxSide = side; Timelapse.setMaxSide(ctx, side) }
            }
        }
        ActionGroup {
            ActionRow("Zeitraffer löschen", enabled = hasFrames, destructive = true) { confirmClear = true }
        }
    }

    if (showExport) {
        FwDialog("Zeitraffer exportieren", onDismiss = { showExport = false }, confirmLabel = null) {
            ActionGroup(modifier = Modifier.padding(bottom = 4.dp)) {
                TimelapseExport.Length.entries.forEach { len ->
                    ActionRow(len.label, trailing = TimelapseExport.formatTime(TimelapseExport.durationSeconds(count, len))) {
                        export(len)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        FwDialog(
            "Zeitraffer löschen?", onDismiss = { confirmClear = false }, confirmLabel = "Löschen", destructive = true,
            onConfirm = {
                confirmClear = false
                doc?.let { d -> scope.launch { Timelapse.clear(ctx, d.id); state.flash("Zeitraffer gelöscht") } }
            },
        ) {
            Text("Die gesamte Aufnahme dieses Kunstwerks wird entfernt. Das lässt sich nicht rückgängig machen.",
                color = LocalUi.current.textMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }

    progress?.let { p -> ExportProgressDialog(p) { exportJob?.cancel(); progress = null } }

    if (showPlayer && doc != null) TimelapsePlayer(doc.id, state) { showPlayer = false }
}

/** Modaler Fortschritt des MP4-Exports mit Abbrechen. */
@Composable
private fun ExportProgressDialog(progress: Float, onCancel: () -> Unit) {
    FwDialog("Zeitraffer wird exportiert", onDismiss = onCancel, confirmLabel = null) {
        val ui = LocalUi.current
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) }, color = ui.accent, trackColor = Color(0xFF48484A),
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text("${(progress * 100).roundToInt()} %", color = ui.textMuted, fontSize = 13.sp)
        }
    }
}

/**
 * Vollbild-Wiedergabe der JPEG-Sequenz mit [TimelapseExport.FPS] Bildern/s.
 * Tippen = Pause/Weiter, Schieberegler = Scrubben, Schließen-Knopf oben rechts.
 * Dekodiert wird im Hintergrund in zwei abwechselnd genutzte Bitmaps (keine Allokation pro Bild).
 */
@Composable
private fun TimelapsePlayer(docId: String, state: EditorState, onClose: () -> Unit) {
    val ctx = state.context
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableFloatStateOf(0f) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val buffers = remember { arrayOfNulls<Bitmap>(2) }
    val opts = remember { BitmapFactory.Options().apply { inMutable = true } }
    var front by remember { mutableIntStateOf(0) }
    // Ein Dekodier-Thread: verhindert gleichzeitiges Schreiben in denselben Puffer
    val decoder = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
    val decodeDispatcher = remember(decoder) { decoder.asCoroutineDispatcher() }

    LaunchedEffect(docId) { files = Timelapse.framesFlushed(ctx, docId) }

    // Bild für [index] laden (in den gerade nicht angezeigten Puffer)
    LaunchedEffect(files, index) {
        val f = files.getOrNull(index) ?: return@LaunchedEffect
        val back = 1 - front
        val bmp = withContext(decodeDispatcher) { TimelapseExport.decode(f.path, opts, buffers[back]) }
        if (bmp != null) {
            buffers[back] = bmp
            front = back
            image = bmp.asImageBitmap()
        }
    }

    // Wiedergabe-Takt
    LaunchedEffect(files, playing, scrubbing) {
        if (files.isEmpty() || !playing || scrubbing) return@LaunchedEffect
        if (index >= files.lastIndex) index = 0
        val frameMs = 1000L / TimelapseExport.FPS
        while (isActive) {
            delay(frameMs)
            if (index >= files.lastIndex) { playing = false; break }
            index++
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Nach einer evtl. laufenden Dekodierung freigeben
            decoder.execute { buffers.forEach { it?.recycle() }; buffers.fill(null) }
            decoder.shutdown()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val ui = LocalUi.current
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(remember { MutableInteractionSource() }, indication = null) { playing = !playing }
        ) {
            val img = image
            if (img != null) {
                Canvas(Modifier.fillMaxSize().padding(bottom = 72.dp, top = 56.dp)) {
                    val s = min(size.width / img.width, size.height / img.height)
                    val dw = (img.width * s).roundToInt(); val dh = (img.height * s).roundToInt()
                    drawImage(
                        img, dstOffset = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()),
                        dstSize = IntSize(dw, dh),
                    )
                }
            } else if (files.isEmpty()) {
                CircularProgressIndicator(color = ui.accent, modifier = Modifier.align(Alignment.Center).size(28.dp))
            }

            // Pause-Hinweis
            if (!playing && img != null) {
                Box(Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color(0x99000000)),
                    contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(26.dp)) {
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * 0.2f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.2f, size.height); close()
                        }
                        drawPath(p, Color.White)
                    }
                }
            }

            // Schließen
            Box(
                Modifier.align(Alignment.TopEnd).padding(14.dp).size(36.dp).clip(CircleShape)
                    .background(Color(0x33FFFFFF)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(14.dp)) {
                    val sw = 2.dp.toPx()
                    drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), sw, StrokeCap.Round)
                    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), sw, StrokeCap.Round)
                }
            }

            // Scrubben + Zeitanzeige
            if (files.isNotEmpty()) {
                val fps = TimelapseExport.FPS.toFloat()
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(TimelapseExport.formatTime(index / fps), color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = if (scrubbing) scrubPos else index.toFloat(),
                        onValueChange = { v ->
                            scrubbing = true; scrubPos = v
                            index = v.roundToInt().coerceIn(0, files.lastIndex)
                        },
                        onValueChangeFinished = { scrubbing = false },
                        valueRange = 0f..files.lastIndex.coerceAtLeast(1).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = ui.accent,
                            inactiveTrackColor = Color(0xFF48484A)),
                        modifier = Modifier.weight(1f),
                    )
                    Text(TimelapseExport.formatTime(files.size / fps), color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.width(0.dp))
                }
            }
        }
    }
}
